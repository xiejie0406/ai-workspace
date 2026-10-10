[CmdletBinding()]
param([switch]$PythonOnly, [switch]$JavaOnly, [switch]$WorkerOnly)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($WorkerOnly -and ($PythonOnly -or $JavaOnly)) { throw 'WorkerOnly不能与PythonOnly或JavaOnly混用。' }
if($PythonOnly -and $JavaOnly) { throw 'PythonOnly与JavaOnly不能同时使用。' }
$pictureRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$pictureManifest=Join-Path $pictureRoot '.runtime/aipicture/processes.json'
if (-not (Test-Path -LiteralPath $pictureManifest)) { throw '没有本项目的运行清单，不停止任何进程。' }
$pictureState=if($WorkerOnly){Get-Content -LiteralPath $pictureManifest -Raw | ConvertFrom-Json -DateKind String}else{Get-Content -LiteralPath $pictureManifest -Raw | ConvertFrom-Json}
if($WorkerOnly) {
    foreach($name in @('java','python-serve','python-worker')) { if(@($pictureState.processes | Where-Object { $_.name -eq $name }).Count -ne 1) { throw '原清单必须有唯一Java、serve和worker，仅停止Worker中止。' } }
    if(-not (Test-Path -LiteralPath (Join-Path $pictureRoot '.runtime/aipicture/service.secret') -PathType Leaf)) { throw '缺少原服务凭据，仅停止Worker中止。' }
    $centerEntry=@($pictureState.processes | Where-Object { $_.name -eq 'java' })[0]
    $serveEntry=@($pictureState.processes | Where-Object { $_.name -eq 'python-serve' })[0]
    $workerEntry=@($pictureState.processes | Where-Object { $_.name -eq 'python-worker' })[0]
    $center=Get-CimInstance Win32_Process -Filter "ProcessId=$($centerEntry.pid)"
    $expectedJar=[IO.Path]::GetFullPath((Join-Path $pictureRoot '.runtime/aipicture/ruoyi-admin.jar'))
    if(-not $center -or $center.Name -ne 'java.exe' -or [IO.Path]::GetFullPath($centerEntry.artifact) -ne $expectedJar -or $center.CommandLine -notmatch ('-jar\s+"?'+[regex]::Escape($centerEntry.artifact)+'"?(?:\s|$)') -or (Get-FileHash -LiteralPath $centerEntry.artifact -Algorithm SHA256).Hash -ne $centerEntry.sha256) { throw '原Java命令或制品身份变化，仅停止Worker中止。' }
    $centerListeners=@(Get-NetTCPConnection -LocalPort 8415 -State Listen -ErrorAction SilentlyContinue)
    if($centerEntry.port -ne 8415 -or $centerListeners.Count -ne 1 -or $centerListeners[0].OwningProcess -ne $centerEntry.pid) { throw '8415不属于原Java，仅停止Worker中止。' }
    $expectedPython=[IO.Path]::GetFullPath((Join-Path $pictureRoot 'python/aipicture-runtime/.venv/Scripts/python.exe'))
    foreach($entry in @($serveEntry,$workerEntry)) { if([IO.Path]::GetFullPath($entry.artifact) -ne $expectedPython) { throw '原Python虚拟环境路径变化，仅停止Worker中止。' } }
    $venvConfig=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($expectedPython))) 'pyvenv.cfg'
    $baseHome=Get-Content -LiteralPath $venvConfig | Where-Object { $_ -match '^home\s*=\s*(.+)$' } | ForEach-Object { $Matches[1].Trim() } | Select-Object -First 1
    if(-not $baseHome) { throw '无法核对原Python基础解释器，仅停止Worker中止。' }
    $basePython=[IO.Path]::GetFullPath((Join-Path $baseHome 'python.exe'))
    $serve=Get-CimInstance Win32_Process -Filter "ProcessId=$($serveEntry.pid)"
    if(-not $serve -or [IO.Path]::GetFullPath($serve.ExecutablePath) -ne $expectedPython -or $serve.CommandLine -notmatch '(^|\s)-m\s+aipicture_runtime\s+serve(?:\s|$)') { throw '原serve父进程身份变化，仅停止Worker中止。' }
    $serveChildren=@(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($serveEntry.pid)" | Where-Object { $_.CommandLine -match '(^|\s)-m\s+aipicture_runtime\s+serve(?:\s|$)' })
    foreach($child in $serveChildren) { if([IO.Path]::GetFullPath($child.ExecutablePath) -ne $basePython) { throw '原serve子进程身份变化，仅停止Worker中止。' } }
    $servePids=@($serveEntry.pid)+@($serveChildren | ForEach-Object ProcessId)
    $serveListeners=@(Get-NetTCPConnection -LocalPort 7405 -State Listen -ErrorAction SilentlyContinue)
    if($serveEntry.port -ne 7405 -or $serveListeners.Count -ne 1 -or $serveListeners[0].OwningProcess -notin $servePids) { throw '7405不属于原serve，仅停止Worker中止。' }
    if($workerEntry.port -ne 0) { throw 'Worker不应持有监听端口，仅停止Worker中止。' }
}
$pictureTargets=@()
$picturePythonChildren=@()
foreach($entry in $pictureState.processes) {
    if($PythonOnly -and $entry.name -notin @('python-serve','python-worker')) { continue }
    if($JavaOnly -and $entry.name -ne 'java') { continue }
    if($WorkerOnly -and $entry.name -ne 'python-worker') { continue }
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)"
    if(-not $process) { continue }
    $creationProperty=$entry.PSObject.Properties['createdAt']
    if(-not $creationProperty -or -not $creationProperty.Value -or ([DateTimeOffset]$creationProperty.Value).UtcDateTime -ne $process.CreationDate.ToUniversalTime()) { throw '进程创建时间与启动清单不符或缺失，不停止。' }
    if($entry.name -eq 'java') {
        if(-not $process.CommandLine.Contains($entry.artifact)) { throw 'Java PID身份已变化，不停止。' }
        if($process.Name -ne 'java.exe' -or $process.CommandLine -notmatch ('-jar\s+"?'+[regex]::Escape($entry.artifact)+'"?(?:\s|$)') -or (Get-FileHash -LiteralPath $entry.artifact -Algorithm SHA256).Hash -ne $entry.sha256) { throw '原Java路径、命令或制品身份变化，不停止。' }
        if($JavaOnly) {
            if($process.Name -ne 'java.exe' -or $process.CommandLine -notmatch ('-jar\s+"?'+[regex]::Escape($entry.artifact)+'"?(?:\s|$)') -or (Get-FileHash -LiteralPath $entry.artifact -Algorithm SHA256).Hash -ne $entry.sha256) { throw '原Java路径、命令或制品身份变化，不停止。' }
            $javaListeners=@(Get-NetTCPConnection -LocalPort $entry.port -State Listen -ErrorAction SilentlyContinue)
            if($javaListeners.Count -ne 1 -or $javaListeners[0].OwningProcess -ne $entry.pid) { throw '原Java监听归属变化，不停止。' }
        }
    } elseif($entry.name -like 'python-*') {
        if([IO.Path]::GetFullPath($process.ExecutablePath) -ne [IO.Path]::GetFullPath($entry.artifact) -or -not $process.CommandLine.Contains('aipicture_runtime')) { throw 'Python PID身份已变化，不停止。' }
        # Windows venv 启动器会派生基础解释器，7405 实际由子进程监听。
        # 只接受已核对父进程的同一 runtime 子进程，不按 Python 名称或端口扫杀。
        $pictureKind=$entry.name.Substring('python-'.Length)
        if($pictureKind -notin @('serve','worker') -or $process.CommandLine -notmatch "-m\s+aipicture_runtime\s+$pictureKind(?:\s|$)") { throw 'Python 运行类型与清单不符，不停止。' }
        $pictureVenvConfig=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($entry.artifact))) 'pyvenv.cfg'
        $picturePythonHome=Get-Content -LiteralPath $pictureVenvConfig | Where-Object { $_ -match '^home\s*=\s*(.+)$' } | ForEach-Object { $Matches[1].Trim() } | Select-Object -First 1
        if(-not $picturePythonHome) { throw '无法核对虚拟环境基础解释器，不停止。' }
        $pictureBasePython=[IO.Path]::GetFullPath((Join-Path $picturePythonHome 'python.exe'))
        foreach($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($entry.pid)")) {
            if($child.CommandLine -notmatch '-m\s+aipicture_runtime\s+') { continue }
            if([IO.Path]::GetFullPath($child.ExecutablePath) -ne $pictureBasePython -or $child.CommandLine -notmatch "-m\s+aipicture_runtime\s+$pictureKind(?:\s|$)") { throw 'Python 子进程身份与基础解释器不符，不停止。' }
            $picturePythonChildren+=[pscustomobject]@{name=$entry.name;pid=$child.ProcessId;parentPid=$entry.pid;artifact=$pictureBasePython;executablePath=$child.ExecutablePath;port=0;createdAt=$child.CreationDate;commandLine=$child.CommandLine}
        }
    } elseif($entry.name -eq 'frontend') {
        if($process.Name -ne 'node.exe' -or -not $process.CommandLine.Contains($entry.artifact) -or $process.CommandLine -notmatch '--host\s+127\.0\.0\.1(?:\s|$)' -or $process.CommandLine -notmatch '--port\s+14385(?:\s|$)' -or $process.CommandLine -notmatch '--strictPort(?:\s|$)') { throw '前端PID或完整启动参数身份已变化，不停止。' }
    } else { throw '运行清单含未知进程，不停止。' }
    $pictureTargets+=[pscustomobject]@{name=$entry.name;pid=$entry.pid;artifact=$entry.artifact;executablePath=$process.ExecutablePath;port=$entry.port;createdAt=$process.CreationDate;commandLine=$process.CommandLine}
}
# 全部目标身份核对完成后，才停止此脚本曾派发的图片通道；不按端口扫杀其他进程。
$pictureTargets=@($picturePythonChildren)+$pictureTargets
if($WorkerOnly) {
    $activeWorkers=@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match '(^|\s)-m\s+aipicture_runtime\s+worker(?:\s|$)' })
    $registeredWorkerPids=@($pictureTargets | ForEach-Object pid)
    if(@($activeWorkers | Where-Object { $_.ProcessId -notin $registeredWorkerPids }).Count) { throw '存在未登记或原父进程已退出的Worker残留，仅停止Worker中止；不停止未知进程。' }
}
$pictureClosed=0
$picturePreserved=0
foreach($entry in $pictureTargets) {
    # 基础解释器退出后，venv 父进程可能已自然退出；PID 被复用时保留新进程。
    $pictureCurrent=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)"
    if(-not $pictureCurrent) { $pictureClosed++; continue }
    if($pictureCurrent.CreationDate -ne $entry.createdAt -or $pictureCurrent.CommandLine -ne $entry.commandLine -or [IO.Path]::GetFullPath($pictureCurrent.ExecutablePath) -ne [IO.Path]::GetFullPath($entry.executablePath)) { $picturePreserved++; continue }
    $pictureProcess=Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
    if(-not $pictureProcess) { $pictureClosed++; continue }
    try {
        $null=$pictureProcess.Handle
        if([Math]::Abs(($pictureProcess.StartTime.ToUniversalTime()-$entry.createdAt.ToUniversalTime()).TotalMilliseconds) -gt 1) { $picturePreserved++; continue }
        Stop-Process -InputObject $pictureProcess -ErrorAction Stop
        $pictureClosed++
    } finally { $pictureProcess.Dispose() }
    Wait-Process -Id $entry.pid -Timeout 10 -ErrorAction SilentlyContinue
}
# 本机实测进程退出后监听端口仍可能短暂收敛；等待释放，不扫杀新占用者。
for($attempt=0;$attempt -lt 40;$attempt++) {
    $listening=@($pictureTargets | Where-Object { $_.port -gt 0 } | ForEach-Object { Get-NetTCPConnection -LocalPort $_.port -State Listen -ErrorAction SilentlyContinue })
    if(-not $listening.Count) { break }
    Start-Sleep -Milliseconds 250
}
$pictureStopField=if($WorkerOnly){'workerStoppedAt'}elseif($PythonOnly){'pythonStoppedAt'}elseif($JavaOnly){'javaStoppedAt'}else{'stoppedAt'}
$pictureState | Add-Member -NotePropertyName $pictureStopField -NotePropertyValue (Get-Date).ToUniversalTime().ToString('o') -Force
$pictureState | ConvertTo-Json -Depth $(if($WorkerOnly){10}else{5}) | Set-Content -LiteralPath $pictureManifest -Encoding utf8
"已停止或确认退出 $pictureClosed 个经核对的图片通道进程；保留 $picturePreserved 个身份变化的进程。数据、配置与日志保留。"
