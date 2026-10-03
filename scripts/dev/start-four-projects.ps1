[CmdletBinding()]
param([switch]$Initialize, [switch]$ServicesOnly, [switch]$SkipFashionRuntime, [Alias('Project')][ValidateSet('all','interview','fashion','aden','aps')][string]$SelectedProject='all', [string]$DatabaseConfigPath, [string]$BackendJarPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runRoot = Join-Path $root '.runtime/four-projects'
$manifestPath = Join-Path $runRoot 'processes.json'
# 通道隔离只管理进程、端口、缓存和日志；绝不创建或克隆数据库。
if ($Initialize) { throw '-Initialize 已停用：通道启动必须沿用既有数据库，不创建库、角色或密钥。' }
if ($SelectedProject -eq 'all') { throw '先分别核对各项目的既有数据库配置，再按单项目启动；不自动推断四项目数据库。' }
if (-not $DatabaseConfigPath) {
    if ($SelectedProject -ne 'interview') { throw '请由该项目对话用 -DatabaseConfigPath 指定已核实的原有配置文件；不能继续采用 dev_* 隔离库。' }
    $DatabaseConfigPath=Join-Path $root '.env'
}
if (-not [IO.Path]::IsPathRooted($DatabaseConfigPath)) { $DatabaseConfigPath=Join-Path $root $DatabaseConfigPath }
if (-not (Test-Path -LiteralPath $DatabaseConfigPath -PathType Leaf)) { throw '原有数据库配置文件不存在；未启动服务。' }
$existingSettings=@{}
Get-Content -LiteralPath $DatabaseConfigPath | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]+)=(.*)$') { $existingSettings[$Matches[1]]=$Matches[2].Trim().Trim('"').Trim("'") }
}
foreach ($requiredName in @('RUOYI_DB_URL','RUOYI_DB_USERNAME','RUOYI_DB_PASSWORD','RUOYI_MANAGED_SECRET_MASTER_KEY_ID','RUOYI_MANAGED_SECRET_MASTER_KEY_BASE64')) {
    if (-not $existingSettings[$requiredName]) { throw "原配置缺少 $requiredName；未启动服务。" }
}
if ($existingSettings.RUOYI_DB_URL -notmatch '^jdbc:mysql://[^/]+/([^?]+)') { throw '原 MySQL 连接格式无法核对。' }
$existingPlatformDatabase=$Matches[1]
if ($existingPlatformDatabase -match '^dev_(interview|fashion|aden|aps)') { throw '指定配置仍指向误建隔离库，请核实该项目原有数据库。' }
if ($SelectedProject -eq 'fashion') {
    $existingFashionProfile=$existingSettings['RUOYI_PROFILE']
    if (-not $existingFashionProfile) {
        $profileText=Get-Content -LiteralPath (Join-Path $root 'ruoyi-backend/ruoyi-admin/src/main/resources/application.yml') -Raw
        $profileMatch=[regex]::Match($profileText,'(?m)^  profile:\s*([^#\r\n]+)')
        if (-not $profileMatch.Success) { throw '选品原上传目录未配置，不能切换到通道存储。' }
        $existingFashionProfile=$profileMatch.Groups[1].Value.Trim().Trim('"').Trim("'")
    }
    if (-not [IO.Path]::IsPathRooted($existingFashionProfile) -or -not (Test-Path -LiteralPath (Join-Path $existingFashionProfile 'fashion-private') -PathType Container)) { throw '选品原私有存储目录无法核实，停止启动。' }
    $existingFashionProfile=[IO.Path]::GetFullPath($existingFashionProfile)
}
if ($SelectedProject -eq 'interview') {
    foreach ($requiredName in @('INTERVIEW_DB_URL','INTERVIEW_DB_USERNAME','INTERVIEW_CATALOG_PUBLIC_TENANT_ID')) {
        if (-not $existingSettings[$requiredName]) { throw "原配置缺少 $requiredName；未启动服务。" }
    }
    if ($existingSettings.INTERVIEW_DB_URL -match '/interview_dev_') { throw '面试通道不能继续使用误建隔离库。' }
    # 日常面试通道沿用已批准的真实语音能力；缺配置时明确停止，不降级成关闭能力的服务。
    foreach ($requiredName in @('INTERVIEW_VOICE_STORAGE_ROOT','INTERVIEW_VOLCENGINE_ASR_MODEL','INTERVIEW_VOLCENGINE_ASR_RESOURCE_ID','INTERVIEW_VOLCENGINE_TTS_MODEL','INTERVIEW_VOLCENGINE_TTS_RESOURCE_ID','INTERVIEW_VOLCENGINE_TTS_VOICE','INTERVIEW_VOICE_ALLOWED_ORIGINS')) {
        if (-not $existingSettings[$requiredName]) { throw "面试语音原配置缺少 $requiredName；未启动服务。" }
    }
    foreach ($requiredName in @('INTERVIEW_EXTERNAL_PROVIDER_CALLS_ENABLED','INTERVIEW_OBJECT_STORAGE_WRITES_ENABLED')) {
        if ($existingSettings[$requiredName] -ne 'true') { throw "面试日常通道要求 $requiredName=true；不能默认关闭已批准的语音能力。" }
    }
    if (@($existingSettings.INTERVIEW_VOICE_ALLOWED_ORIGINS.Split(',') | ForEach-Object Trim) -notcontains 'http://interview.localhost:15173') { throw '面试语音 Origin 配置缺少当前门户 http://interview.localhost:15173。' }
    if (-not [IO.Path]::IsPathRooted($existingSettings.INTERVIEW_VOICE_STORAGE_ROOT) -or -not (Test-Path -LiteralPath $existingSettings.INTERVIEW_VOICE_STORAGE_ROOT -PathType Container)) { throw '面试音频根目录必须指向已核实的既有绝对目录。' }
    $voiceConfigurationNames=@('INTERVIEW_EXTERNAL_PROVIDER_CALLS_ENABLED','INTERVIEW_OBJECT_STORAGE_WRITES_ENABLED','INTERVIEW_VOICE_STORAGE_ROOT','INTERVIEW_VOICE_ALLOWED_ORIGINS','INTERVIEW_VOLCENGINE_ASR_MODEL','INTERVIEW_VOLCENGINE_ASR_RESOURCE_ID','INTERVIEW_VOLCENGINE_ASR_ENDPOINT','INTERVIEW_VOLCENGINE_TTS_MODEL','INTERVIEW_VOLCENGINE_TTS_RESOURCE_ID','INTERVIEW_VOLCENGINE_TTS_VOICE','INTERVIEW_VOLCENGINE_TTS_ENDPOINT')
    $voiceConfigurationText=($voiceConfigurationNames | ForEach-Object { "$_=$($existingSettings[$_])" }) -join "`n"
    $voiceConfigurationHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($voiceConfigurationText))).ToLowerInvariant()
}
if ($SelectedProject -eq 'aps') {
    foreach ($requiredName in @('APS_DB_URL','APS_DB_USERNAME')) {
        if (-not $existingSettings[$requiredName]) { throw "排产原配置缺少 $requiredName；未启动服务。" }
    }
    if ($existingSettings.APS_DB_URL -notmatch '^jdbc:mysql://[^/]+/([^?]+)') { throw '排产业务 MySQL 连接格式无法核对。' }
    if ($Matches[1] -match '^(dev_(interview|fashion|aden|aps)|aps_preview)') { throw '排产业务配置仍指向误建隔离库，拒绝启动。' }
}
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$redisServer = 'C:/Program Files/Redis/redis-server.exe'
$java = (Get-Command java.exe).Source
$node = (Get-Command node.exe).Source
$projects = @(
    @{id='interview'; database=$existingPlatformDatabase; backendPort=18091; redisPort=16391; frontendPort=15173},
    @{id='fashion'; database=$existingPlatformDatabase; backendPort=18092; redisPort=16392; frontendPort=15174},
    @{id='aden'; database=$existingPlatformDatabase; backendPort=18093; redisPort=16393; frontendPort=0},
    @{id='aps'; database=$existingPlatformDatabase; backendPort=18094; redisPort=16394; frontendPort=15175}
)
$startupMutex=[Threading.Mutex]::new($false,'Local\JavaWorkspaceFourProjectsStartup')
if (-not $startupMutex.WaitOne(0)) { throw '其他对话正在维护公共启动清单；稍后用相同参数重试。' }
try {
$processes = [Collections.Generic.List[object]]::new()
if (Test-Path -LiteralPath $manifestPath) {
    foreach ($entry in @(Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json)) { $processes.Add($entry) }
}
function Save-Manifest { @($processes.ToArray()) | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8 }
function Invoke-Checked([string]$exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "命令执行失败（$([IO.Path]::GetFileName($exe))，exit=$LASTEXITCODE），检查本轮日志。" }
}
function Start-Owned([string]$name, [string]$exe, [string[]]$argv, [string]$directory, [int]$port = 0) {
    $previous = @($processes | Where-Object { $_.name -eq $name })
    foreach ($item in $previous) {
        $live = Get-CimInstance Win32_Process -Filter "ProcessId=$($item.pid)" -ErrorAction SilentlyContinue
        if ($live -and $live.CommandLine -eq $item.commandLine -and $live.CreationDate.ToUniversalTime() -eq [DateTime]$item.startedUtc) {
            if ($name.EndsWith('-java') -and (-not $item.PSObject.Properties['databaseMode'] -or $item.databaseMode -ne 'existing' -or $item.platformDatabaseUrl -ne $existingSettings.RUOYI_DB_URL)) { throw "$name 的既有进程未证明连接原库，请由所属项目先核对并定向重启。" }
            if ($name -eq 'interview-java' -and (-not $item.PSObject.Properties['businessDatabaseUrl'] -or $item.businessDatabaseUrl -ne $existingSettings.INTERVIEW_DB_URL)) { throw '面试既有进程的业务数据库与原配置不一致，请先定向重启。' }
            if ($name -eq 'interview-java' -and ($live.CommandLine -notmatch '--interview.foundation-safety.external-provider-calls-enabled=true(?:\s|$)' -or $live.CommandLine -notmatch '--interview.foundation-safety.object-storage-writes-enabled=true(?:\s|$)' -or -not $item.PSObject.Properties['voiceConfigurationHash'] -or $item.voiceConfigurationHash -ne $voiceConfigurationHash)) { throw '面试既有进程未启用当前语音配置，请核对并仅重启面试后端；不能继续复用关闭语音的实例。' }
            if ($name -eq 'fashion-java') {
                if ($live.CommandLine -notmatch "--ruoyi.profile=$([regex]::Escape($existingFashionProfile))(?:\s|$)") { throw '选品现有实例没有沿用已核实的原图片存储，请定向重启选品 Java。' }
                if ($live.CommandLine -notmatch "--fashion.migration.expected-database=$([regex]::Escape($existingPlatformDatabase))(?:\s|$)") { throw '选品现有实例未声明原数据库供就绪核对，请定向重启选品 Java。' }
                $expectedFashionWorker=if (-not $SkipFashionRuntime -and $existingSettings.FASHION_AI_RUNTIME_WORKER_ENABLED -eq 'true') {'true'} else {'false'}
                if ($live.CommandLine -notmatch "--fashion.ai-runtime.worker-enabled=$expectedFashionWorker(?:\s|$)") { throw '选品现有实例的 AI Worker 开关与本次恢复目标不同，请定向重启选品 Java，不可悄悄复用部分恢复实例。' }
            }
            Write-Host "复用 $name PID=$($item.pid)"; return Get-Process -Id $item.pid
        }
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) { throw "$name 的端口 $port 被其他进程占用" }
    $logDir = Join-Path $runRoot 'logs'
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $out = Join-Path $logDir "$name-$stamp.out.log"
    $err = Join-Path $logDir "$name-$stamp.err.log"
    $p = Start-Process -FilePath $exe -ArgumentList $argv -WorkingDirectory $directory -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
    $identity = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
    foreach ($item in $previous) { [void]$processes.Remove($item) }
    $newEntry=[pscustomobject]@{name=$name;pid=$p.Id;startedUtc=$identity.CreationDate.ToUniversalTime().ToString('o');commandLine=$identity.CommandLine;port=$port;stdout=$out;stderr=$err}
    if ($name.EndsWith('-java')) { $newEntry|Add-Member databaseMode 'existing'; $newEntry|Add-Member platformDatabaseUrl $existingSettings.RUOYI_DB_URL; if ($SelectedProject -eq 'interview') { $newEntry|Add-Member businessDatabaseUrl $existingSettings.INTERVIEW_DB_URL; $newEntry|Add-Member voiceConfigurationHash $voiceConfigurationHash } }
    $processes.Add($newEntry)
    Save-Manifest
    Write-Host "启动 $name PID=$($p.Id) port=$port"
    return $p
}
function Wait-Http([string]$url, [int]$seconds=90) {
    $end = [DateTime]::UtcNow.AddSeconds($seconds)
    do {
        try { $r = Invoke-WebRequest -Uri $url -TimeoutSec 3; if ($r.StatusCode -eq 200) { return } } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $end)
    throw "服务未就绪：$url；本轮进程和日志保留供排查"
}
function Set-ProjectEnvironment($project) {
    Get-ChildItem Env: | Where-Object { $_.Name -match '^(RUOYI|INTERVIEW|FASHION|ADEN|APS)_' } | ForEach-Object { [Environment]::SetEnvironmentVariable($_.Name,$null,'Process') }
    foreach ($pair in $existingSettings.GetEnumerator()) {
        if ($pair.Key -match '^(RUOYI|INTERVIEW|FASHION|ADEN|APS)_') { [Environment]::SetEnvironmentVariable($pair.Key,$pair.Value,'Process') }
    }
}
# 日常恢复沿用该通道已登记的不可变版本；更新代码后显式提供新验证产物。
if (-not $BackendJarPath) {
    $recordedBackend=@($processes|Where-Object name -eq "$SelectedProject-java")
    if ($recordedBackend.Count -eq 1) {
        $jarMatch=[regex]::Match($recordedBackend[0].commandLine,'-jar\s+(?:"([^"]+)"|([^\s]+))')
        if ($jarMatch.Success) { $BackendJarPath=if ($jarMatch.Groups[1].Success) {$jarMatch.Groups[1].Value} else {$jarMatch.Groups[2].Value} }
    }
}
if (-not $BackendJarPath) { $BackendJarPath=Join-Path $root 'ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar' }
if (-not [IO.Path]::IsPathRooted($BackendJarPath)) { $BackendJarPath=Join-Path $root $BackendJarPath }
$sourceJar=$BackendJarPath
$hash=(Get-FileHash -LiteralPath $sourceJar -Algorithm SHA256).Hash.ToLowerInvariant()
foreach ($project in @($projects | Where-Object { $SelectedProject -eq 'all' -or $_.id -eq $SelectedProject })) {
    $dir=Join-Path $runRoot $project.id
    New-Item -ItemType Directory -Path $dir,(Join-Path $dir 'redis'),(Join-Path $dir 'files') -Force | Out-Null
    $jar=Join-Path $dir "ruoyi-admin-$hash.jar"
    if (-not (Test-Path -LiteralPath $jar)) { Copy-Item -LiteralPath $sourceJar -Destination $jar }
    if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) { throw '不可变 JAR 哈希不一致' }
    $logbackPath=Join-Path $dir 'logback.xml'
    $logback=Get-Content -LiteralPath (Join-Path $root 'ruoyi-backend/ruoyi-admin/src/main/resources/logback.xml') -Raw
    $logback=$logback.Replace('value="/home/ruoyi/logs"', ('value="' + (($dir -replace '\\','/') + '/logs') + '"'))
    Set-Content -LiteralPath $logbackPath -Value $logback -Encoding utf8
    Set-ProjectEnvironment $project
    $redis=Start-Owned "$($project.id)-redis" $redisServer @('--port',"$($project.redisPort)",'--bind','127.0.0.1','--protected-mode','yes','--appendonly','no','--dir',"`"$dir/redis`"") $dir $project.redisPort
    $argv=@('-Xms128m','-Xmx768m','-jar',$jar,'--spring.profiles.active=druid,local','--server.address=127.0.0.1',"--server.port=$($project.backendPort)",'--server.tomcat.threads.max=100','--server.tomcat.threads.min-spare=5','--spring.quartz.auto-startup=false','--spring.data.redis.host=127.0.0.1',"--spring.data.redis.port=$($project.redisPort)",'--interview.enabled=false','--fashion.enabled=false','--aden.enabled=false','--aps.enabled=false',"--ruoyi.profile=$dir/files",("--logging.config=file:" + ($logbackPath -replace '\\','/')),'--spring.datasource.druid.initial-size=1','--spring.datasource.druid.min-idle=1','--spring.datasource.druid.max-active=8')
    # Spring 采用最后一个同名命令行参数；各模块参数在下方先过滤再覆盖。
    $enabledFlag="--$($project.id).enabled=false"
    $argv=@($argv | Where-Object { $_ -ne $enabledFlag }) + "--$($project.id).enabled=true"
    switch ($project.id) {
        'interview' {
            $argv+=@('--interview.flyway.enabled=false','--interview.catalog.seed-enabled=false','--interview.foundation-safety.external-provider-calls-enabled=true','--interview.foundation-safety.object-storage-writes-enabled=true')
        }
        'fashion' {
            # 接回原库时沿用原配置的 Worker 开关，不把隔离演示 Worker 强制带到真实业务数据。
            $fashionWorkerEnabled=if ($existingSettings.FASHION_AI_RUNTIME_WORKER_ENABLED -eq 'true') {'true'} else {'false'}
            if ($SkipFashionRuntime) { $fashionWorkerEnabled='false' }
            $argv=@($argv | Where-Object { $_ -notlike '--ruoyi.profile=*' }) + "--ruoyi.profile=$existingFashionProfile"
            $argv+=@('--fashion.migration.enabled=false',"--fashion.migration.expected-database=$existingPlatformDatabase",'--fashion.image.worker-enabled=false','--fashion.delivery.worker-enabled=false','--fashion.ai-runtime.base-url=http://127.0.0.1:18100',"--fashion.ai-runtime.worker-enabled=$fashionWorkerEnabled",'--fashion.ai-runtime.worker-id=fashion-local')
            if ($SkipFashionRuntime) { Write-Output '本次仅恢复选品 Java；AI Worker 暂停，Python 缺原服务认证密钥，保持受阻，不修改原配置或创建替代密钥。'; break }
            # Provider 沿用原配置，不强制带入旧隔离演示模式。
            $env:PYTHONPATH=Join-Path $root 'python/fashion-ai-runtime/src'
            $runtime=Start-Owned 'fashion-python-launcher' $java @('-Xmx128m','-jar',$jar,'--run-fashion-ai-runtime',(Join-Path $root '.runtime/fashion-ai-runtime-venv/Scripts/python.exe'),'-m','uvicorn','fashion_ai.main:app','--host','127.0.0.1','--port','18100') $dir 18100
            Wait-Http 'http://127.0.0.1:18100/health' 30
            $child=@(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($runtime.Id)" | Where-Object { $_.Name -eq 'python.exe' })
            if ($child.Count -eq 1) {
                foreach ($old in @($processes | Where-Object name -eq 'fashion-python')) { [void]$processes.Remove($old) }
                $processes.Add([pscustomobject]@{name='fashion-python';pid=$child[0].ProcessId;startedUtc=$child[0].CreationDate.ToUniversalTime().ToString('o');commandLine=$child[0].CommandLine;port=18100;stdout=($processes|Where-Object name -eq 'fashion-python-launcher').stdout;stderr=($processes|Where-Object name -eq 'fashion-python-launcher').stderr})
                Save-Manifest
            }
        }
        'aden' { $argv+=@("--aden.schema.expected-database=$existingPlatformDatabase",'--aden.collection.enabled=true',"--aden.collection.storage-root=$dir/files/collection") }
        'aps' {
            $argv+=@('--aps.flyway.enabled=false','--aps.site.code=LOCAL-DEV','--aps.persistence.enabled=true','--aps.datasource.enabled=true','--aps.reporting.enabled=true')
        }
    }
    $backend=Start-Owned "$($project.id)-java" $java $argv $dir $project.backendPort
    Wait-Http "http://127.0.0.1:$($project.backendPort)/captchaImage" 120
    $runningBackend=Get-CimInstance Win32_Process -Filter "ProcessId=$($backend.Id)"
    $runningJarMatch=[regex]::Match($runningBackend.CommandLine,'-jar\s+(?:"([^"]+)"|([^\s]+))')
    if (-not $runningJarMatch.Success) { throw '运行包路径无法核对。' }
    $runningJarPath=if ($runningJarMatch.Groups[1].Success) {$runningJarMatch.Groups[1].Value} else {$runningJarMatch.Groups[2].Value}
    $runningHash=(Get-FileHash -LiteralPath $runningJarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($project.id -eq 'aps') {
        $workerSource=Join-Path $root 'ruoyi-backend/aps/aps-worker/target/aps-worker-3.9.2.jar'
        $workerHash=(Get-FileHash -LiteralPath $workerSource -Algorithm SHA256).Hash.ToLowerInvariant()
        $workerJar=Join-Path $dir "aps-worker-$workerHash.jar"
        if (-not (Test-Path -LiteralPath $workerJar)) { Copy-Item -LiteralPath $workerSource -Destination $workerJar }
        if ($existingSettings.ContainsKey('APS_DATASOURCE_PASSWORD')) { $env:APS_DATASOURCE_PASSWORD=$existingSettings.APS_DATASOURCE_PASSWORD }
        elseif ($existingSettings.APS_DB_URL -eq $existingSettings.RUOYI_DB_URL -and $existingSettings.APS_DB_USERNAME -eq $existingSettings.RUOYI_DB_USERNAME) {
            # 排产沿用同一原库和账号时，Worker 只在进程环境中复用现有口令。
            $env:APS_DATASOURCE_PASSWORD=$existingSettings.RUOYI_DB_PASSWORD
        }
        else { throw '排产 Worker 缺少原数据库口令；不能猜测或创建替代凭据。' }
        $worker=Start-Owned 'aps-worker' $java @('-Xms64m','-Xmx512m','-jar',$workerJar,'--aps.enabled=true','--aps.worker.enabled=true','--aps.worker.polling-enabled=true','--aps.persistence.enabled=true','--aps.datasource.enabled=true',"--aps.datasource.url=$env:APS_DB_URL","--aps.datasource.username=$env:APS_DB_USERNAME",'--aps.solver.enabled=true','--aps.site.code=LOCAL-DEV') $dir
    }
    if (-not $ServicesOnly -and $project.frontendPort -gt 0) {
        $frontendRoot=Join-Path $root $(if ($project.id -eq 'interview') { 'frontend/portal-web' } else { 'frontend/admin-web' })
        $env:ADMIN_DEV_BACKEND_URL="http://127.0.0.1:$($project.backendPort)"; $env:INTERVIEW_DEV_BACKEND_URL=$env:ADMIN_DEV_BACKEND_URL
        $env:PYTHONPATH=$null
        $env:PROJECT_VITE_CACHE_DIR=Join-Path $dir 'vite-cache'
        $front=Start-Owned "$($project.id)-frontend" $node @((Join-Path $frontendRoot 'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',"$($project.frontendPort)",'--strictPort') $frontendRoot $project.frontendPort
        Wait-Http "http://127.0.0.1:$($project.frontendPort)/" 30
    }
    Write-Output "READY $($project.id): backend=$($project.backendPort), frontend=$($project.frontendPort), platformDatabase=$($project.database), runningSha256=$runningHash"
}
Write-Output "运行清单：$manifestPath；仅完成启动，不代表业务 UAT 通过。"
} finally {
    Get-ChildItem Env: | Where-Object { $_.Name -match '^(RUOYI|INTERVIEW|FASHION|ADEN|APS)_' } | ForEach-Object { [Environment]::SetEnvironmentVariable($_.Name,$null,'Process') }
    $startupMutex.ReleaseMutex(); $startupMutex.Dispose()
}
