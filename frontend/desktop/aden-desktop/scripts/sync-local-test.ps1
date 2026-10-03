[CmdletBinding()]
param(
    [string]$TargetDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\Aden Local Test'),
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$desktopRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$targetRoot = [IO.Path]::GetFullPath($TargetDirectory).TrimEnd('\')
$appRoot = Join-Path $targetRoot 'resources\app'
$sourceOut = Join-Path $desktopRoot 'out'
$targetOut = Join-Path $appRoot 'out'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$nextName = "out-sync-next-$stamp"
$backupName = "out-sync-backup-$stamp"
$next = Join-Path $appRoot $nextName
$backup = Join-Path $appRoot $backupName

function Assert-InAppRoot([string]$Path) {
    $root = [IO.Path]::GetFullPath($appRoot).TrimEnd('\') + '\'
    $candidate = [IO.Path]::GetFullPath($Path)
    if (-not $candidate.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
        throw "同步目标越过应用目录：$candidate"
    }
}

foreach ($path in @($targetOut, $next, $backup)) { Assert-InAppRoot $path }
if (-not (Test-Path -LiteralPath (Join-Path $targetRoot 'Aden Local Test.exe') -PathType Leaf)) {
    throw "目标不是 Aden Local Test 安装或打包目录：$targetRoot"
}
$configFile = Join-Path $appRoot 'local-test.json'
if (-not (Test-Path -LiteralPath $configFile -PathType Leaf) -or
    (Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json).channel -ne 'local-test') {
    throw '目标缺少 local-test 渠道配置，拒绝同步'
}
$running = @(Get-CimInstance Win32_Process -Filter "Name = 'Aden Local Test.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -and $_.ExecutablePath.Equals((Join-Path $targetRoot 'Aden Local Test.exe'), [StringComparison]::OrdinalIgnoreCase) })
if ($running.Count -gt 0) { throw '目标 Aden 正在运行。请先关闭该窗口，再运行同步命令；不会强制结束进程。' }
if ((Test-Path -LiteralPath $next) -or (Test-Path -LiteralPath $backup)) { throw '本次暂存或备份目录已存在，请稍后重试' }

if (-not $SkipBuild) {
    $previousChannel = [Environment]::GetEnvironmentVariable('ADEN_PACKAGE_CHANNEL', 'Process')
    $env:ADEN_PACKAGE_CHANNEL = 'local-test'
    try {
        Push-Location $desktopRoot
        try { & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw "构建失败：$LASTEXITCODE" } }
        finally { Pop-Location }
    } finally { [Environment]::SetEnvironmentVariable('ADEN_PACKAGE_CHANNEL', $previousChannel, 'Process') }
}
foreach ($entry in @('main\index.js', 'preload\index.cjs', 'renderer\index.html')) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourceOut $entry) -PathType Leaf)) { throw "构建产物缺失：$entry" }
}

Copy-Item -LiteralPath $sourceOut -Destination $next -Recurse
if (-not (Test-Path -LiteralPath (Join-Path $next 'renderer\index.html') -PathType Leaf)) {
    throw '暂存目录缺少 renderer 入口，未修改安装目录'
}
$oldMoved = $false
try {
    Rename-Item -LiteralPath $targetOut -NewName $backupName
    $oldMoved = $true
    Rename-Item -LiteralPath $next -NewName 'out'
} catch {
    if ($oldMoved -and -not (Test-Path -LiteralPath $targetOut) -and (Test-Path -LiteralPath $backup)) {
        Rename-Item -LiteralPath $backup -NewName 'out'
    }
    throw
}
Write-Host "已同步桌面端编译产物：$targetOut"
Write-Host "旧版备份：$backup"
Write-Host '请重新打开 Aden。Chrome 扩展和 Native Host 程序未在此次同步中更新。'
