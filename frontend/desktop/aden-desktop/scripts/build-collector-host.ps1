[CmdletBinding()]
param([string]$PythonExecutable = $env:ADEN_HOST_BUILD_PYTHON)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$desktopRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $desktopRoot '../../..')).Path
if (-not $PythonExecutable) { $PythonExecutable = Join-Path $desktopRoot 'test-results/collector-host-build/.venv/Scripts/python.exe' }
if (-not (Test-Path -LiteralPath $PythonExecutable -PathType Leaf)) {
    throw '缺少独立Native Host构建Python；设置ADEN_HOST_BUILD_PYTHON，并在其环境安装scripts/requirements-collector-host.txt。'
}
$destination = Join-Path $desktopRoot 'test-results/collector-native'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
& $PythonExecutable -m PyInstaller --noconfirm --onefile --console --name AdenCollectorHost --distpath (Join-Path $destination 'dist') --workpath (Join-Path $destination 'build') --specpath $destination (Join-Path $repoRoot 'python/aden-runner/src/aden_runner/native_host/__main__.py')
if ($LASTEXITCODE -ne 0) { throw "Native Host构建失败：$LASTEXITCODE" }
$hostExecutable = Join-Path $destination 'dist/AdenCollectorHost.exe'
$hashStream = [IO.File]::OpenRead($hostExecutable)
try {
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { $digest = [BitConverter]::ToString($sha256.ComputeHash($hashStream)).Replace('-', '') }
    finally { $sha256.Dispose() }
} finally { $hashStream.Dispose() }
Write-Host "AdenCollectorHost.exe SHA256 $digest"
