[CmdletBinding()]
param([string]$DatabaseConfigPath, [string]$RuntimeConfigPath, [string]$JavaArtifactPath, [switch]$Frontend, [switch]$PythonOnly, [switch]$JavaOnly, [switch]$WorkerOnly, [switch]$SkipWorker)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $PythonOnly -and -not $WorkerOnly) {
    $pictureCaptchaDebugEnabled=[Environment]::GetEnvironmentVariable('RUOYI_LOCAL_CAPTCHA_DEBUG_ENABLED','Process')
    if([string]::IsNullOrEmpty($pictureCaptchaDebugEnabled)) { $pictureCaptchaDebugEnabled='true' }
    $pictureCaptchaDebugEnabled=$pictureCaptchaDebugEnabled.ToLowerInvariant()
    if($pictureCaptchaDebugEnabled -notin @('true','false')) { throw 'RUOYI_LOCAL_CAPTCHA_DEBUG_ENABLED仅允许true或false；未启动Java。' }
}
if($SkipWorker -and ($JavaOnly -or $WorkerOnly)) { throw 'SkipWorker仅用于完整启动或Python恢复，不能与JavaOnly或WorkerOnly混用。' }
if(-not $PythonOnly -and -not $WorkerOnly -and -not $JavaArtifactPath) { throw '启动Java必须显式JavaArtifactPath指定已核实的本项目固定包，不默认使用共享target。' }
if($WorkerOnly -and ($PythonOnly -or $JavaOnly -or $Frontend -or $JavaArtifactPath)) { throw 'WorkerOnly不能与PythonOnly、JavaOnly、Frontend或JavaArtifactPath混用。' }
if($PythonOnly -and ($Frontend -or $JavaArtifactPath)) { throw '仅恢复Python不能同时启动前端或替换Java制品。' }
if($PythonOnly -and $JavaOnly) { throw 'PythonOnly与JavaOnly不能同时使用。' }
if($JavaOnly -and $Frontend) { throw '仅恢复Java不能同时启动前端。' }
$pictureRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$pictureRunRoot=Join-Path $pictureRoot '.runtime/aipicture'
if($WorkerOnly -and (-not (Test-Path -LiteralPath $pictureRunRoot -PathType Container) -or -not $RuntimeConfigPath)) { throw '仅恢复Worker需要原运行目录和显式原RuntimeConfigPath。' }
New-Item -ItemType Directory -Path $pictureRunRoot -Force | Out-Null
$pictureSettings=@{}
if (-not $DatabaseConfigPath) { $DatabaseConfigPath=Join-Path $pictureRoot '.env' }
foreach($config in @($DatabaseConfigPath,$RuntimeConfigPath)) {
    if (-not $config) { continue }
    Get-Content -LiteralPath $config | ForEach-Object { if ($_ -match '^([A-Z][A-Z0-9_]+)=(.*)$') { $pictureSettings[$Matches[1]]=$Matches[2].Trim().Trim('"').Trim("'") } }
}
if ($pictureSettings.RUOYI_DB_URL -notmatch '^jdbc:mysql://(localhost|127\.0\.0\.1):3306/ry-vue[?]') { throw '图片通道只沿用已核实的本机 ry-vue 原连接。' }
foreach($required in @('RUOYI_DB_USERNAME','RUOYI_DB_PASSWORD')) { if (-not $pictureSettings[$required]) { throw "缺少原配置 $required。" } }
if ($pictureSettings['AIPICTURE_OFFLINE_TEST'] -eq 'true' -or $pictureSettings['AIPICTURE_IMAGE_PROVIDER'] -eq 'mock') { throw '正式中心通道不能连接离线测试替身。' }
$picturePorts=if($WorkerOnly){@()}elseif($PythonOnly){@(7405)}elseif($JavaOnly){@(8415)}else{@(8415,7405)}
if ($Frontend) { $picturePorts+=14385 }
foreach($port in $picturePorts) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "图片通道端口 $port 已占用；保留现有进程，请先核对本项目运行清单。" } }
$pictureManifest=Join-Path $pictureRunRoot 'processes.json'
$previous=$null
if(($PythonOnly -or $JavaOnly -or $WorkerOnly) -and -not (Test-Path -LiteralPath $pictureManifest -PathType Leaf)) { throw '局部恢复需要原图片通道清单，不能创建替代通道。' }
if (Test-Path -LiteralPath $pictureManifest) {
    $previous=if($WorkerOnly){Get-Content -LiteralPath $pictureManifest -Raw | ConvertFrom-Json -DateKind String}else{Get-Content -LiteralPath $pictureManifest -Raw | ConvertFrom-Json}
    foreach($process in $previous.processes) {
        if($PythonOnly -and $process.name -notin @('python-serve','python-worker')) { continue }
        if($JavaOnly -and $process.name -ne 'java') { continue }
        if($WorkerOnly -and $process.name -ne 'python-worker') { continue }
        if(Get-Process -Id $process.pid -ErrorAction SilentlyContinue) { throw '已有图片通道进程，先核对其版本，不自动重复启动或停止。' }
    }
}
$pictureJar=Join-Path $pictureRunRoot 'ruoyi-admin.jar'
if($WorkerOnly) {
    # 原中心和已预热serve必须保持原身份；任何检查失败均不派发新Worker。
    foreach($name in @('java','python-serve','python-worker')) { if(@($previous.processes | Where-Object { $_.name -eq $name }).Count -ne 1) { throw '原清单必须有唯一Java、serve和worker，仅恢复Worker停止。' } }
    $workerEntry=@($previous.processes | Where-Object { $_.name -eq 'python-worker' })[0]
    if($workerEntry.port -ne 0) { throw 'Worker不应持有监听端口，仅恢复Worker停止。' }
    $serveEntry=@($previous.processes | Where-Object { $_.name -eq 'python-serve' })[0]
    $centerEntry=@($previous.processes | Where-Object { $_.name -eq 'java' })[0]
    $center=Get-CimInstance Win32_Process -Filter "ProcessId=$($centerEntry.pid)"
    if(-not $center -or $center.Name -ne 'java.exe' -or [IO.Path]::GetFullPath($centerEntry.artifact) -ne $pictureJar -or $center.CommandLine -notmatch ('-jar\s+"?'+[regex]::Escape($centerEntry.artifact)+'"?(?:\s|$)') -or (Get-FileHash -LiteralPath $centerEntry.artifact -Algorithm SHA256).Hash -ne $centerEntry.sha256) { throw '原Java命令或制品身份变化，仅恢复Worker停止。' }
    $centerListeners=@(Get-NetTCPConnection -LocalPort 8415 -State Listen -ErrorAction SilentlyContinue)
    if($centerEntry.port -ne 8415 -or $centerListeners.Count -ne 1 -or $centerListeners[0].OwningProcess -ne $centerEntry.pid) { throw '8415不属于原Java，仅恢复Worker停止。' }
    $expectedPython=[IO.Path]::GetFullPath((Join-Path $pictureRoot 'python/aipicture-runtime/.venv/Scripts/python.exe'))
    foreach($entry in @($serveEntry,$workerEntry)) { if([IO.Path]::GetFullPath($entry.artifact) -ne $expectedPython) { throw '原Python虚拟环境路径变化，仅恢复Worker停止。' } }
    $venvConfig=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($expectedPython))) 'pyvenv.cfg'
    $baseHome=Get-Content -LiteralPath $venvConfig | Where-Object { $_ -match '^home\s*=\s*(.+)$' } | ForEach-Object { $Matches[1].Trim() } | Select-Object -First 1
    if(-not $baseHome) { throw '无法核对原Python基础解释器，仅恢复Worker停止。' }
    $basePython=[IO.Path]::GetFullPath((Join-Path $baseHome 'python.exe'))
    $serve=Get-CimInstance Win32_Process -Filter "ProcessId=$($serveEntry.pid)"
    if(-not $serve -or [IO.Path]::GetFullPath($serve.ExecutablePath) -ne $expectedPython -or $serve.CommandLine -notmatch '(^|\s)-m\s+aipicture_runtime\s+serve(?:\s|$)') { throw '原serve父进程身份变化，仅恢复Worker停止。' }
    $serveChildren=@(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($serveEntry.pid)" | Where-Object { $_.CommandLine -match '(^|\s)-m\s+aipicture_runtime\s+serve(?:\s|$)' })
    foreach($child in $serveChildren) { if([IO.Path]::GetFullPath($child.ExecutablePath) -ne $basePython) { throw '原serve子进程身份变化，仅恢复Worker停止。' } }
    $servePids=@($serveEntry.pid)+@($serveChildren | ForEach-Object ProcessId)
    $serveListeners=@(Get-NetTCPConnection -LocalPort 7405 -State Listen -ErrorAction SilentlyContinue)
    if($serveEntry.port -ne 7405 -or $serveListeners.Count -ne 1 -or $serveListeners[0].OwningProcess -notin $servePids) { throw '7405不属于原serve，仅恢复Worker停止。' }
    # 保守拒绝同名图片Worker残留，包括venv基础解释器子；不停止任何未登记进程。
    $activeWorkers=@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match '(^|\s)-m\s+aipicture_runtime\s+worker(?:\s|$)' })
    if($activeWorkers.Count -or (Get-CimInstance Win32_Process -Filter "ProcessId=$($workerEntry.pid)")) { throw '原Worker尚未退出、PID被复用或存在残留Worker，仅恢复Worker停止。' }
    if(-not (Test-Path -LiteralPath (Join-Path $pictureRunRoot 'service.secret') -PathType Leaf)) { throw '仅恢复Worker需要原服务凭据，不能新建或轮换。' }
    $configProperty=$previous.PSObject.Properties['workerRuntimeConfigPath'];if(-not $configProperty) { $configProperty=$previous.PSObject.Properties['pythonRuntimeConfigPath'] }
    if(-not $configProperty -or [IO.Path]::GetFullPath($configProperty.Value) -ne [IO.Path]::GetFullPath($RuntimeConfigPath)) { throw 'RuntimeConfigPath不是清单中原Worker配置，仅恢复Worker停止。' }
}
if($JavaOnly) {
    # 沿用原Python父子与7405归属，只更新Java，不把已预热模型重启为未加载。
    $picturePythonEntries=@($previous.processes | Where-Object { $_.name -in @('python-serve','python-worker') })
    if($picturePythonEntries.Count -ne 2 -or @($picturePythonEntries.name | Sort-Object -Unique).Count -ne 2) { throw '原清单必须有唯一serve和worker，仅恢复Java停止。' }
    $pictureServePids=@()
    foreach($entry in $picturePythonEntries) {
        $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)"
        $kind=$entry.name.Substring('python-'.Length)
        if(-not $process -or [IO.Path]::GetFullPath($process.ExecutablePath) -ne [IO.Path]::GetFullPath($entry.artifact) -or $process.CommandLine -notmatch "-m\s+aipicture_runtime\s+$kind(?:\s|$)") { throw '原Python父进程身份变化，仅恢复Java停止。' }
        $venvConfig=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($entry.artifact))) 'pyvenv.cfg'
        $baseHome=Get-Content -LiteralPath $venvConfig | Where-Object { $_ -match '^home\s*=\s*(.+)$' } | ForEach-Object { $Matches[1].Trim() } | Select-Object -First 1
        if(-not $baseHome) { throw '无法核对原Python基础解释器，仅恢复Java停止。' }
        $basePython=[IO.Path]::GetFullPath((Join-Path $baseHome 'python.exe'))
        $children=@(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($entry.pid)" | Where-Object { $_.CommandLine -match "-m\s+aipicture_runtime\s+$kind(?:\s|$)" })
        foreach($child in $children) { if([IO.Path]::GetFullPath($child.ExecutablePath) -ne $basePython) { throw '原Python子进程身份变化，仅恢复Java停止。' } }
        if($kind -eq 'serve') { $pictureServePids=@($entry.pid)+@($children | ForEach-Object ProcessId) }
    }
    $picturePythonListeners=@(Get-NetTCPConnection -LocalPort 7405 -State Listen -ErrorAction SilentlyContinue)
    if($picturePythonListeners.Count -ne 1 -or $picturePythonListeners[0].OwningProcess -notin $pictureServePids) { throw '7405不属于原Python serve，仅恢复Java停止。' }
    if(-not (Test-Path -LiteralPath (Join-Path $pictureRunRoot 'service.secret') -PathType Leaf)) { throw '仅恢复Java需要原服务凭据，不能新建或轮换。' }
}
if($PythonOnly) {
    $pictureActivePython=@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match '(^|\s)-m\s+aipicture_runtime\s+(serve|worker)(\s|$)' })
    if($pictureActivePython.Count) { throw '仍有图片Python进程，不能重复启动。' }
    $pictureCenterEntries=@($previous.processes | Where-Object { $_.name -eq 'java' })
    if($pictureCenterEntries.Count -ne 1) { throw '原清单必须有唯一Java中心，不能猜测或新建。' }
    $pictureCenter=$pictureCenterEntries[0]
    $pictureCenterProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($pictureCenter.pid)"
    if(-not $pictureCenterProcess -or -not $pictureCenterProcess.CommandLine.Contains($pictureCenter.artifact)) { throw '原Java中心身份变化，仅恢复Python停止。' }
    $pictureCenterListeners=@(Get-NetTCPConnection -LocalPort 8415 -State Listen -ErrorAction SilentlyContinue)
    if(-not $pictureCenterListeners.Count -or @($pictureCenterListeners | Where-Object { $_.OwningProcess -ne $pictureCenter.pid }).Count) { throw '8415不属于原Java中心，仅恢复Python停止。' }
} elseif(-not $WorkerOnly) {
    $pictureJarSource=if ($JavaArtifactPath) { [IO.Path]::GetFullPath($JavaArtifactPath) } else { Join-Path $pictureRoot 'ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar' }
    if (-not (Test-Path -LiteralPath $pictureJarSource -PathType Leaf)) { throw '指定的 Java 制品不存在，请先完成对应构建。' }
    $pictureArchive=[IO.Compression.ZipFile]::OpenRead($pictureJarSource)
    try { if (-not ($pictureArchive.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/lib/ruoyi-aipicture-3.9.2.jar' })) { throw '总包缺少图片模块；先完成 Maven 装配。' } } finally { $pictureArchive.Dispose() }
    if([IO.Path]::GetFullPath($pictureJarSource) -ne [IO.Path]::GetFullPath($pictureJar)) { Copy-Item -LiteralPath $pictureJarSource -Destination $pictureJar }
}
$picturePython=Join-Path $pictureRoot 'python/aipicture-runtime/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $picturePython)) { throw '图片 Python 虚拟环境未准备。' }
$pictureSecretPath=Join-Path $pictureRunRoot 'service.secret'
if(($PythonOnly -or $WorkerOnly) -and -not (Test-Path -LiteralPath $pictureSecretPath -PathType Leaf)) { throw '局部恢复Python需要原服务凭据，不自动新建或轮换。' }
if (-not (Test-Path -LiteralPath $pictureSecretPath -PathType Leaf)) { throw '缺少原图片服务凭据，启动不自动创建或轮换身份。' }
if(-not $JavaOnly -and -not $SkipWorker) {
    $spool=$pictureSettings['AIPICTURE_DELIVERY_SPOOL_DIRECTORY']
    if(-not $spool -or -not [IO.Path]::IsPathFullyQualified($spool) -or -not (Test-Path -LiteralPath $spool -PathType Container)) { throw '原Worker缺少已核实的私有绝对交付目录；仅查看页面可显式SkipWorker，不能创建替代Worker配置。' }
}
$pictureSettings.AIPICTURE_SERVICE_SECRET=(Get-Content -LiteralPath $pictureSecretPath -Raw).Trim()
$pictureSettings.AIPICTURE_ENABLED='true'
$pictureSettings.AIPICTURE_CENTER_URL='http://127.0.0.1:8415'
$pictureSettings.AIPICTURE_WORKER_URL='http://127.0.0.1:7405'
$pictureSettings.AIPICTURE_STORAGE_ROOT=Join-Path $pictureRunRoot 'storage'
$pictureSettings.RUOYI_SERVER_PORT='8415'
$pictureSettings.INTERVIEW_ENABLED='false'
$pictureSettings.FASHION_ENABLED='false'
$pictureSettings.ADEN_ENABLED='false'
$pictureSettings.APS_ENABLED='false'
$pictureSettings.AIPPT_ENABLED='false'
$pictureSettings.PAICLI_ENABLED='false'
$pictureSettings.PAISMART_ENABLED='false'
$pictureSettings.PAIFLOW_ENABLED='false'
$pictureSettings.SPRING_QUARTZ_AUTO_STARTUP='false'
$pictureSettings.SPRING_PROFILES_ACTIVE='druid,test,aipicture'
$pictureOldEnv=@{}
$pictureStarted=[Collections.Generic.List[object]]::new()
try {
    foreach($name in $pictureSettings.Keys) { $pictureOldEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,$pictureSettings[$name],'Process') }
    if(-not $PythonOnly -and -not $WorkerOnly) {
        # 使用命令行最高优先级关闭九业务，再仅开启图片；复用原Redis，不启动基础服务。
        $javaArgs=@('-Xms128m','-Xmx768m','-jar',('"'+$pictureJar+'"'),'--server.address=127.0.0.1','--server.port=8415',('--ruoyi.local-captcha-debug.enabled='+$pictureCaptchaDebugEnabled),
            '--interview.enabled=false','--fashion.enabled=false','--aipicture.enabled=true','--aden.enabled=false',
            '--aippt.enabled=false','--paismart.enabled=false','--paiflow.enabled=false','--paicli.enabled=false','--aps.enabled=false',
            '--aden.collection.enabled=false','--aps.datasource.enabled=false','--aps.worker.enabled=false','--aps.worker.polling-enabled=false',
            '--fashion.image.worker-enabled=false','--fashion.ai-runtime.worker-enabled=false','--spring.quartz.auto-startup=false',
            '--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=6379','--spring.data.redis.database=0')
        $java=Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList $javaArgs -WorkingDirectory $pictureRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $pictureRunRoot 'java.log') -RedirectStandardError (Join-Path $pictureRunRoot 'java.err.log')
        $pictureStarted.Add([pscustomobject]@{name='java';pid=$java.Id;artifact=$pictureJar;sha256=(Get-FileHash -LiteralPath $pictureJar -Algorithm SHA256).Hash;port=8415})
    }
    if(-not $JavaOnly) { foreach($kind in $(if($WorkerOnly){@('worker')}elseif($SkipWorker){@('serve')}else{@('serve','worker')})) {
        $arguments=@('-m','aipicture_runtime',$kind)
        if($kind -eq 'serve') { $arguments+=@('--host','127.0.0.1','--port','7405') }
        $python=Start-Process -FilePath $picturePython -ArgumentList $arguments -WorkingDirectory (Join-Path $pictureRoot 'python/aipicture-runtime') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $pictureRunRoot "python-$kind.log") -RedirectStandardError (Join-Path $pictureRunRoot "python-$kind.err.log")
        $pictureStarted.Add([pscustomobject]@{name="python-$kind";pid=$python.Id;artifact=$picturePython;port=$(if($kind -eq 'serve'){7405}else{0})})
    } }
    if($Frontend) {
        foreach($name in @('ADMIN_DEV_BACKEND_URL','PROJECT_VITE_CACHE_DIR')) { if(-not $pictureOldEnv.ContainsKey($name)) { $pictureOldEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process') } }
        $env:ADMIN_DEV_BACKEND_URL='http://127.0.0.1:8415'
        $env:PROJECT_VITE_CACHE_DIR=Join-Path $pictureRunRoot 'vite-cache'
        $node=(Get-Command node.exe).Source
        $vite=Join-Path $pictureRoot 'frontend/admin-web/node_modules/vite/bin/vite.js'
        $web=Start-Process -FilePath $node -ArgumentList @(('"'+$vite+'"'),'--host','127.0.0.1','--port','14385','--strictPort') -WorkingDirectory (Join-Path $pictureRoot 'frontend/admin-web') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $pictureRunRoot 'frontend.log') -RedirectStandardError (Join-Path $pictureRunRoot 'frontend.err.log')
        $pictureStarted.Add([pscustomobject]@{name='frontend';pid=$web.Id;artifact=$vite;port=14385})
    }
} finally {
    foreach($name in $pictureOldEnv.Keys) { [Environment]::SetEnvironmentVariable($name,$pictureOldEnv[$name],'Process') }
    foreach($entry in $pictureStarted) { $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)"; if($proc) { $entry | Add-Member -NotePropertyName createdAt -NotePropertyValue $proc.CreationDate.ToUniversalTime().ToString('o') -Force } }
    # 只保存进程与产物身份，不保存数据库或供应商密钥；进程启动不代表业务健康。
    if($WorkerOnly) {
        # 派发前失败时保留原清单完整字节，便于凭原 worker 身份继续恢复。
        $pictureStartedWorkers=@($pictureStarted.ToArray() | Where-Object { $_.name -eq 'python-worker' })
        if($pictureStartedWorkers.Count -eq 1) {
            $previous.processes=@($previous.processes | Where-Object { $_.name -ne 'python-worker' })+$pictureStartedWorkers
            $previous | Add-Member -NotePropertyName workerStartedAt -NotePropertyValue (Get-Date).ToUniversalTime().ToString('o') -Force
            $previous | Add-Member -NotePropertyName workerRuntimeConfigPath -NotePropertyValue ([IO.Path]::GetFullPath($RuntimeConfigPath)) -Force
            $previous | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $pictureManifest -Encoding utf8
        }
    } elseif($JavaOnly) {
        $previous.processes=@($previous.processes | Where-Object { $_.name -ne 'java' })+$pictureStarted.ToArray()
        $previous | Add-Member -NotePropertyName javaStartedAt -NotePropertyValue (Get-Date).ToUniversalTime().ToString('o') -Force
        if($RuntimeConfigPath) { $previous | Add-Member -NotePropertyName javaRuntimeConfigPath -NotePropertyValue ([IO.Path]::GetFullPath($RuntimeConfigPath)) -Force }
        $previous | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $pictureManifest -Encoding utf8
    } elseif($PythonOnly) {
        $previous.processes=@($previous.processes | Where-Object { $_.name -notin @('python-serve','python-worker') })+$pictureStarted.ToArray()
        $previous | Add-Member -NotePropertyName pythonStartedAt -NotePropertyValue (Get-Date).ToUniversalTime().ToString('o') -Force
        if($RuntimeConfigPath) { $previous | Add-Member -NotePropertyName pythonRuntimeConfigPath -NotePropertyValue ([IO.Path]::GetFullPath($RuntimeConfigPath)) -Force }
        $previous | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $pictureManifest -Encoding utf8
    } else {
        [pscustomobject]@{startedAt=(Get-Date).ToUniversalTime().ToString('o');databaseMode='existing';database='ry-vue';databaseConfigPath=[IO.Path]::GetFullPath($DatabaseConfigPath);pythonRuntimeConfigPath=$RuntimeConfigPath;javaArtifactSourcePath=$pictureJarSource;redis='127.0.0.1:6379/0';workerStarted=(-not $SkipWorker);frontendProxy='http://127.0.0.1:8415';viteCache=(Join-Path $pictureRunRoot 'vite-cache');processes=$pictureStarted.ToArray()} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $pictureManifest -Encoding utf8
    }
}
'图片通道已派发启动；请核对日志与实际接口。前端：http://127.0.0.1:14385/；未配置模型的能力会明确失败。'
