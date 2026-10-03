[CmdletBinding()]
param([string]$TargetDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\Aden Local Test'))
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$desktopRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$targetRoot=[IO.Path]::GetFullPath($TargetDirectory).TrimEnd('\')
$resources=Join-Path $targetRoot 'resources'
$target=Join-Path $resources 'collector'
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$next=Join-Path $resources "collector-sync-next-$stamp"
$backup=Join-Path $resources "collector-sync-backup-$stamp"
foreach ($path in @($target,$next,$backup)) {
    if (-not ([IO.Path]::GetFullPath($path)).StartsWith(([IO.Path]::GetFullPath($resources).TrimEnd('\')+'\'),[StringComparison]::OrdinalIgnoreCase)) { throw '组件同步路径越过目标安装目录。' }
}
$exe=Join-Path $targetRoot 'Aden Local Test.exe'
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw '目标不是已安装的 Aden Local Test。' }
$config=Get-Content -LiteralPath (Join-Path $resources 'app/local-test.json') -Raw | ConvertFrom-Json
if ($config.channel -ne 'local-test' -or $config.apiBaseUrl -ne 'http://127.0.0.1:18093') { throw '安装版通道与中心地址未经核实，拒绝同步。' }
$hostTarget=Join-Path $target 'AdenCollectorHost.exe'
$running=@(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and ($_.ExecutablePath.Equals($exe,[StringComparison]::OrdinalIgnoreCase) -or $_.ExecutablePath.Equals($hostTarget,[StringComparison]::OrdinalIgnoreCase)) })
if ($running.Count) { throw '目标桌面或 Native Host 正在运行，请先关闭目标组件；不会结束其他安装进程。' }
$sourceExtension=[IO.Path]::GetFullPath((Join-Path $desktopRoot '../aden-collector-extension'))
$sourceHost=Join-Path $desktopRoot 'test-results/collector-native/dist/AdenCollectorHost.exe'
$extensionFiles=@('manifest.json','config.js','background.js','parser.js','picker.js','resources.js','spool.js','sections.js','page-watch.js','panel.html','panel.js','panel.css')
foreach ($file in $extensionFiles) { if (-not (Test-Path -LiteralPath (Join-Path $sourceExtension $file) -PathType Leaf)) { throw "扩展必需文件缺失：$file" } }
if (-not (Test-Path -LiteralPath $sourceHost -PathType Leaf)) { throw '缺少本轮已构建 Native Host；先执行 build-collector-host.ps1。' }
if ((Test-Path -LiteralPath $next) -or (Test-Path -LiteralPath $backup)) { throw '组件暂存或备份目录已存在。' }
# 先保留本安装自有注册清单，再更新组件；不修改系统注册表或另一安装。
Copy-Item -LiteralPath $target -Destination $next -Recurse
$extensionNext=Join-Path $next 'extension'
foreach ($file in $extensionFiles) { Copy-Item -LiteralPath (Join-Path $sourceExtension $file) -Destination (Join-Path $extensionNext $file) -Force }
Copy-Item -LiteralPath $sourceHost -Destination (Join-Path $next 'AdenCollectorHost.exe') -Force
Copy-Item -LiteralPath (Join-Path $desktopRoot 'scripts/collector-pipe.ps1') -Destination (Join-Path $next 'collector-pipe.ps1') -Force
$expected=@{ 'AdenCollectorHost.exe'=$sourceHost; 'collector-pipe.ps1'=(Join-Path $desktopRoot 'scripts/collector-pipe.ps1') }
foreach ($file in $extensionFiles) { $expected["extension/$file"]=Join-Path $sourceExtension $file }
foreach ($entry in $expected.GetEnumerator()) {
    if ((Get-FileHash -LiteralPath $entry.Value -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath (Join-Path $next $entry.Key) -Algorithm SHA256).Hash) { throw "组件暂存哈希不匹配：$($entry.Key)；尚未替换安装目录。" }
}
$moved=$false
try {
    Rename-Item -LiteralPath $target -NewName ([IO.Path]::GetFileName($backup)); $moved=$true
    Rename-Item -LiteralPath $next -NewName 'collector'
} catch {
    if ($moved -and -not (Test-Path -LiteralPath $target) -and (Test-Path -LiteralPath $backup)) { Rename-Item -LiteralPath $backup -NewName 'collector' }
    throw
}
foreach ($entry in $expected.GetEnumerator()) {
    if ((Get-FileHash -LiteralPath $entry.Value -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath (Join-Path $target $entry.Key) -Algorithm SHA256).Hash) { throw "安装文件哈希不匹配：$($entry.Key)" }
}
Write-Output "已同步扩展与 Native Host：$target；关键文件哈希全部匹配。"
Write-Output "旧组件备份：$backup；系统 Host 注册保持原值，另核对实际 Chrome 加载与连接。"
