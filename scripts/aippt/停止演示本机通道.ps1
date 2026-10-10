[CmdletBinding(SupportsShouldProcess)]
param([ValidateSet('Java','Worker','Frontend','All')][string]$Scope = 'All')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$aipptRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$manifestPath=Join-Path $aipptRoot '.runtime/aippt/processes.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw '缺少本项目原运行清单，不停止未知进程。' }
$entries=@(Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json)
$targets=@()
foreach ($entry in $entries) {
    $kind=if ($entry.name -eq 'aippt-java') {'Java'} elseif ($entry.name -eq 'aippt-worker') {'Worker'} elseif ($entry.name -in @('aippt-react-source','aippt-platform-entry')) {'Frontend'} else { throw '清单含未知项目进程。' }
    if ($Scope -ne 'All' -and $Scope -ne $kind) { continue }
    $identity=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)"
    if (-not $identity) { continue }
    if ($identity.CreationDate.ToUniversalTime() -ne ([datetime]$entry.createdUtc).ToUniversalTime() -or $identity.CommandLine -ne $entry.commandLine -or $identity.ExecutablePath -ne $entry.executable) { throw 'PID 已复用或身份变化，保留进程。' }
    if ($kind -eq 'Java') {
        if ($identity.CommandLine -notmatch ('-jar\s+"?'+[regex]::Escape($entry.artifact)+'"?(?:\s|$)') -or (Get-FileHash -LiteralPath $entry.artifact -Algorithm SHA256).Hash -ne $entry.sha256) { throw 'Java 制品或命令身份变化。' }
    } elseif ($kind -eq 'Frontend') {
        $work=if ($entry.name -eq 'aippt-react-source') {'frontend/aippt-web'} else {'frontend/admin-web'}
        $vite=Join-Path $aipptRoot "$work/node_modules/vite/bin/vite.js"
        if (-not $identity.CommandLine.Contains($vite) -or $identity.CommandLine -notmatch '--host\s+127\.0\.0\.1(?:\s|$)' -or $identity.CommandLine -notmatch ('--port\s+'+$entry.port+'(?:\s|$)') -or $identity.CommandLine -notmatch '--strictPort(?:\s|$)') { throw '前端入口或监听参数变化。' }
    } else {
        $python=Join-Path $aipptRoot 'python/aippt-worker/.venv/Scripts/python.exe'
        if ($identity.ExecutablePath -ne $python -or $identity.CommandLine -notmatch '-m\s+app\s+run(?:\s|$)') { throw 'Worker 解释器或模块身份变化。' }
        $aipptPythonHome=Get-Content -LiteralPath (Join-Path $aipptRoot 'python/aippt-worker/.venv/pyvenv.cfg') | Where-Object { $_ -match '^home\s*=\s*(.+)$' } | ForEach-Object { $Matches[1].Trim() } | Select-Object -First 1
        if (-not $aipptPythonHome) { throw '缺少基础解释器身份。' }
        $basePython=Join-Path $aipptPythonHome 'python.exe'
        foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($entry.pid)")) {
            # Windows隐藏控制台宿主是该入口的系统辅助子进程；让它随所属控制台退出。
            if ($child.ExecutablePath -eq (Join-Path $env:SystemRoot 'System32/conhost.exe') -and $child.Name -eq 'conhost.exe') { continue }
            if ($child.ExecutablePath -ne $basePython -or $child.CommandLine -notmatch '-m\s+app\s+run(?:\s|$)') { throw 'Worker 有未知子进程，先核对任务和进程。' }
            $targets+= $child
        }
    }
    if ($entry.port -gt 0) {
        $listeners=@(Get-NetTCPConnection -LocalPort $entry.port -State Listen -ErrorAction SilentlyContinue)
        if (@($listeners | Where-Object OwningProcess -ne $entry.pid).Count) { throw '监听归属已变化。' }
    }
    $targets+=$identity
}
# 全部身份核对通过后逐个复核；只停止登记的本项目进程，保留共享 MySQL/Redis。
foreach ($target in $targets) {
    $current=Get-CimInstance Win32_Process -Filter "ProcessId=$($target.ProcessId)"
    if (-not $current) { continue }
    if ($current.CreationDate -ne $target.CreationDate -or $current.CommandLine -ne $target.CommandLine -or $current.ExecutablePath -ne $target.ExecutablePath) { throw '停止前进程身份发生变化。' }
    $process=Get-Process -Id $target.ProcessId -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    try {
        $null=$process.Handle
        # CIM 的创建时间精度低于进程句柄 StartTime，仅容许 1ms 表示精度差。
        if ([Math]::Abs(($process.StartTime.ToUniversalTime()-$target.CreationDate.ToUniversalTime()).TotalMilliseconds) -gt 1) { throw '停止前 PID 被复用。' }
        if ($PSCmdlet.ShouldProcess("$($target.Name) PID $($target.ProcessId)", '停止已核对的演示通道进程')) { Stop-Process -InputObject $process -ErrorAction Stop }
    } finally { $process.Dispose() }
}
if ($WhatIfPreference) { "已核对 $Scope 范围，WhatIf 未停止进程。" }
else { "已处理 $Scope 范围内经核对的演示通道进程；文件、私有配置和日志保留。" }
