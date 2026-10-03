[CmdletBinding()]
param([ValidateSet('all','interview','fashion','aden','aps')][string]$Project='all')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runRoot=Join-Path $root '.runtime/four-projects'
$manifest=Join-Path $runRoot 'processes.json'
if (-not (Test-Path -LiteralPath $manifest)) { return }
$entries=@(Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json)
foreach ($entry in @($entries | Sort-Object { if ($_.name -eq 'mysql') { 1 } else { 0 } })) {
    if ($Project -ne 'all' -and -not $entry.name.StartsWith("$Project-")) { continue }
    $live=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
    if (-not $live) { continue }
    if ($live.CommandLine -ne $entry.commandLine -or $live.CreationDate.ToUniversalTime() -ne [DateTime]$entry.startedUtc) { throw "进程身份变化，拒绝停止 $($entry.name) PID=$($entry.pid)" }
    # 仅记录在 manifest 中且创建时间、完整命令一致的本轮进程。
    Stop-Process -Id $entry.pid -ErrorAction Stop
    Write-Output "已停止 $($entry.name) PID=$($entry.pid)；测试数据和日志保留。"
}
