[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$desktopExe=Join-Path $env:LOCALAPPDATA 'Programs\Aden Local Test\Aden Local Test.exe'
if (-not (Test-Path -LiteralPath $desktopExe -PathType Leaf)) { throw '本机安装版不存在。' }
$config=Get-Content -LiteralPath (Join-Path (Split-Path -Parent $desktopExe) 'resources/app/local-test.json') -Raw | ConvertFrom-Json
if ($config.channel -ne 'local-test' -or $config.apiBaseUrl -ne 'http://127.0.0.1:18093') { throw '安装版中心通道配置不符，先核对原配置。' }
$start=Get-Content -LiteralPath (Join-Path $workspaceRoot '.runtime/four-projects/aden/installed-start-result.json') -Raw | ConvertFrom-Json
if (-not $start.desktopProfile -or -not (Test-Path -LiteralPath $start.desktopProfile -PathType Container)) { throw '缺少已核实的原资料目录；不自动创建测试资料。' }
$running=@(Get-CimInstance Win32_Process -Filter "Name = 'Aden Local Test.exe'" | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.Equals($desktopExe,[StringComparison]::OrdinalIgnoreCase) })
if ($running.Count) { Write-Output '本机安装版已运行，沿用现有窗口。'; return }
$launch=[Diagnostics.ProcessStartInfo]::new()
$launch.FileName=$desktopExe
$launch.WorkingDirectory=Split-Path -Parent $desktopExe
$launch.UseShellExecute=$false
$launch.CreateNoWindow=$true
$launch.WindowStyle=[Diagnostics.ProcessWindowStyle]::Normal
$launch.Arguments='"--user-data-dir='+$start.desktopProfile+'"'
# 明确从子进程环境删除，避免继承桌面工具的 Node 模式或源码调试入口。
foreach ($key in @('ELECTRON_RUN_AS_NODE','ELECTRON_RENDERER_URL','ADEN_DESKTOP_SMOKE','ADEN_API_BASE_URL','NODE_OPTIONS')) { $launch.EnvironmentVariables.Remove($key) }
$process=[Diagnostics.Process]::Start($launch)
if ($process.WaitForExit(2000)) { throw "安装版启动后立即退出，ExitCode=$($process.ExitCode)。" }
Write-Output '已打开本机安装版。中心服务另按原数据库启动；本入口不建库、迁移、创建账号或结束服务。'
