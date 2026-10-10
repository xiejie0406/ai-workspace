[CmdletBinding()]
param(
    [switch]$Java, [switch]$Worker, [switch]$Frontend,
    [string]$JavaArtifactPath, [string]$ExpectedJavaSha256,
    [string]$RuntimeConfigPath = 'D:\javaData\aippt\runtime\local.env'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Java) {
    $aipptCaptchaDebugEnabled=[Environment]::GetEnvironmentVariable('RUOYI_LOCAL_CAPTCHA_DEBUG_ENABLED','Process')
    if([string]::IsNullOrEmpty($aipptCaptchaDebugEnabled)) { $aipptCaptchaDebugEnabled='true' }
    $aipptCaptchaDebugEnabled=$aipptCaptchaDebugEnabled.ToLowerInvariant()
    if($aipptCaptchaDebugEnabled -notin @('true','false')) { throw 'RUOYI_LOCAL_CAPTCHA_DEBUG_ENABLED仅允许true或false；未启动Java。' }
}
if (-not ($Java -or $Worker -or $Frontend)) { throw '请显式选择 Java、Worker 或 Frontend；启动不自动领取真实任务。' }
if ($Java -and $Worker) { throw '先单独启动并核实 Java，再启动 Worker。' }
$aipptRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$aipptRun = Join-Path $aipptRoot '.runtime/aippt'
$manifestPath = Join-Path $aipptRun 'processes.json'
New-Item -ItemType Directory -Path $aipptRun -Force | Out-Null
$entries = @(if (Test-Path -LiteralPath $manifestPath) { Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json })
$settings = @{}
function Read-Settings([string]$Path, [string]$Allowed) {
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^([A-Z][A-Z0-9_]+)=(.*)$') {
            $configName=$Matches[1]; $configValue=$Matches[2]
            if ($configName -match $Allowed) { $settings[$configName] = $configValue.Trim().Trim('"').Trim("'") }
        }
    }
}
if ($Java -or $Worker) {
    if ([IO.Path]::GetFullPath($RuntimeConfigPath) -ne 'D:\javaData\aippt\runtime\local.env') { throw '运行配置必须使用已批准的私有路径。' }
    if ((Get-Item -LiteralPath $RuntimeConfigPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '配置不能是链接。' }
    Read-Settings $RuntimeConfigPath '^AIPPT_'
    foreach ($key in @('AIPPT_WORKER_KEY','AIPPT_STORAGE_PATH','AIPPT_CATALOG_DIR')) { if (-not $settings[$key]) { throw "缺少 $key。" } }
    if ($settings.AIPPT_WORKER_KEY.Length -lt 32) { throw 'Worker 配对凭据长度不足。' }
    if ($settings.AIPPT_PROVIDER_ENABLED -ne 'false' -or $settings.AIPPT_IMAGE_ENABLED -ne 'false') { throw '本入口当前只允许关闭外部模型和图像调用的就绪通道。' }
    $settings.AIPPT_TEXT_ENABLED='false'
    if ($settings.AIPPT_CENTER_URL -ne 'http://127.0.0.1:18097') { throw '中心地址不符合本项目通道。' }
}
if ($Java) {
    if (-not $JavaArtifactPath -or $ExpectedJavaSha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Java 启动须显式提供已核实固定包及 SHA256，不默认共享 target。' }
    $JavaArtifactPath = (Resolve-Path -LiteralPath $JavaArtifactPath).Path
    if ((Get-FileHash -LiteralPath $JavaArtifactPath -Algorithm SHA256).Hash -ne $ExpectedJavaSha256) { throw 'Java 包哈希不符。' }
    Read-Settings (Join-Path $aipptRoot '.env') '^RUOYI_'
    if ($settings.RUOYI_DB_URL -notmatch '^jdbc:mysql://(localhost|127\.0\.0\.1):3306/ry-vue[?]') { throw '只允许复用原 MySQL ry-vue。' }
    foreach ($key in @('RUOYI_DB_USERNAME','RUOYI_DB_PASSWORD')) { if (-not $settings[$key]) { throw "缺少原 $key。" } }
    # 九业务只有演示文稿开启；aden 子开关独立关闭，禁止迁移/导入/清理和 Quartz。
    foreach ($name in @('INTERVIEW','FASHION','ADEN','APS','AIPICTURE','PAISMART','PAIFLOW','PAICLI')) { $settings[($name + '_ENABLED')] = 'false' }
    $settings.SPRING_PROFILES_ACTIVE='druid,local,aippt'
    $settings.SPRING_QUARTZ_AUTO_STARTUP='false'
    $settings.RUOYI_SERVER_PORT='18097'
    $settings.SPRING_REDIS_HOST='127.0.0.1'; $settings.SPRING_REDIS_PORT='6379'
}
if ($Worker) {
    if ($settings.AIPPT_CONCURRENCY -ne '1' -or $settings.AIPPT_WORKER_ID -ne 'aippt-local-worker' -or $settings.AIPPT_CLAIM_TYPES -ne '["parse_source","quality","export"]') { throw '初期 Worker 仅允许并发 1 和三类无 Provider 任务。' }
    if (-not (Get-NetTCPConnection -LocalPort 18097 -State Listen -ErrorAction SilentlyContinue)) { throw '先启动并核实 Java 中心。' }
    $center = @($entries | Where-Object name -eq 'aippt-java')
    if ($center.Count -ne 1) { throw '缺少唯一已登记的中心身份。' }
    $centerProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($center[0].pid)"
    if (-not $centerProcess -or $centerProcess.CreationDate.ToUniversalTime() -ne ([datetime]$center[0].createdUtc).ToUniversalTime() -or $centerProcess.CommandLine -ne $center[0].commandLine) { throw '中心身份不符。' }
    # 不从源码 .env 意外继承旧 Provider 配置。
    $settings.AIPPT_LLM_API_KEY=''; $settings.AIPPT_IMAGE_API_KEY=''; $settings.AIPPT_UNSPLASH_ACCESS_KEY=''
}
$jobs = @()
if ($Java) {
    $javaArgs=@('-Xms128m','-Xmx768m','-jar',('"'+$JavaArtifactPath+'"'),'--server.address=127.0.0.1','--server.port=18097',('--ruoyi.local-captcha-debug.enabled='+$aipptCaptchaDebugEnabled),
      '--interview.enabled=false','--fashion.enabled=false','--aden.enabled=false','--aps.enabled=false','--aipicture.enabled=false',
      '--aippt.enabled=true','--paismart.enabled=false','--paiflow.enabled=false','--paicli.enabled=false',
      '--aden.collection.enabled=false','--aps.datasource.enabled=false','--aps.worker.enabled=false','--aps.worker.polling-enabled=false',
      '--fashion.image.worker-enabled=false','--fashion.ai-runtime.worker-enabled=false',
      '--aippt.migration.enabled=false','--aippt.import.enabled=false','--aippt.cleanup.enabled=false','--spring.quartz.auto-startup=false',
      '--aippt.worker.global-concurrency=1','--aippt.worker.project-concurrency=1',
      '--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=6379','--spring.data.redis.database=0')
    $jobs += @{name='aippt-java'; executable=(Get-Command java).Source; artifact=$JavaArtifactPath; args=$javaArgs; work=$aipptRoot; port=18097}
}
if ($Worker) {
    $python = Join-Path $aipptRoot 'python/aippt-worker/.venv/Scripts/python.exe'
    $jobs += @{name='aippt-worker'; executable=$python; artifact=$python; args=@('-B','-m','app','run'); work=(Join-Path $aipptRoot 'python/aippt-worker'); port=0}
}
if ($Frontend) {
    foreach ($front in @(@('aippt-react-source','frontend/aippt-web',39173,'vite-cache-source'),@('aippt-platform-entry','frontend/admin-web',15178,'vite-cache-admin'))) {
        $existing=@($entries | Where-Object { $_.name -eq $front[0] -and (Get-Process -Id $_.pid -ErrorAction SilentlyContinue) })
        if ($existing.Count) {
            if ($existing.Count -ne 1) { throw '前端清单有重复项。' }
            $identity=Get-CimInstance Win32_Process -Filter "ProcessId=$($existing[0].pid)"
            if ($identity.CreationDate.ToUniversalTime() -ne ([datetime]$existing[0].createdUtc).ToUniversalTime() -or $identity.CommandLine -ne $existing[0].commandLine -or $identity.ExecutablePath -ne $existing[0].executable) { throw '已有前端身份变化，保留进程并停止派发。' }
            continue
        }
        $work=Join-Path $aipptRoot $front[1]
        $jobs += @{name=$front[0]; executable=(Get-Command node).Source; artifact=(Join-Path $work 'node_modules/vite/bin/vite.js'); args=@(('"'+(Join-Path $work 'node_modules/vite/bin/vite.js')+'"'),'--host','127.0.0.1','--port',"$($front[2])",'--strictPort'); work=$work; port=$front[2]; cache=(Join-Path $aipptRun $front[3])}
    }
}
# 先验证所有拟启动目标和端口；已有前端保持原进程，不扫杀端口。
foreach ($job in $jobs) {
    if (-not (Test-Path -LiteralPath $job.artifact -PathType Leaf)) { throw "缺少 $($job.name) 制品。" }
    if (@($entries | Where-Object { $_.name -eq $job.name -and (Get-Process -Id $_.pid -ErrorAction SilentlyContinue) }).Count) { throw "已有 $($job.name)，请先核对版本。" }
    if ($job.port -gt 0 -and (Get-NetTCPConnection -LocalPort $job.port -State Listen -ErrorAction SilentlyContinue)) { throw "端口 $($job.port) 被占用，保留现有进程。" }
}
$saved=@{}
try {
    foreach ($key in @('PROJECT_VITE_CACHE_DIR','ADMIN_DEV_BACKEND_URL')) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
    foreach ($key in $settings.Keys) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process'); [Environment]::SetEnvironmentVariable($key,$settings[$key],'Process') }
    foreach ($job in $jobs) {
        $logStamp=[DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')
        $stdout=Join-Path $aipptRun ($job.name+'.'+$logStamp+'.stdout.log'); $stderr=Join-Path $aipptRun ($job.name+'.'+$logStamp+'.stderr.log')
        if ($job.ContainsKey('cache')) { $env:PROJECT_VITE_CACHE_DIR=$job.cache; $env:ADMIN_DEV_BACKEND_URL='http://127.0.0.1:18097' }
        $process=Start-Process -FilePath $job.executable -ArgumentList $job.args -WorkingDirectory $job.work -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        Start-Sleep -Milliseconds 300
        $identity=Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
        if (-not $identity) { throw "$($job.name) 立即退出，请检查脱敏日志。" }
        $entry=[ordered]@{name=$job.name;pid=$process.Id;createdUtc=$identity.CreationDate.ToUniversalTime().ToString('o');commandLine=$identity.CommandLine;executable=$identity.ExecutablePath;artifact=$job.artifact;sha256=(Get-FileHash -LiteralPath $job.artifact -Algorithm SHA256).Hash.ToLowerInvariant();workingDirectory=$job.work;port=$job.port;stdout=$stdout;stderr=$stderr;runtimeConfigPath=$RuntimeConfigPath}
        if ($job.name -eq 'aippt-worker') {
            $entry.children=@(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)" | Where-Object { $_.CommandLine -match '-m\s+app\s+run(?:\s|$)' } | ForEach-Object { [pscustomobject]@{pid=$_.ProcessId;parentPid=$process.Id;createdUtc=$_.CreationDate.ToUniversalTime().ToString('o');commandLine=$_.CommandLine;executable=$_.ExecutablePath} })
        }
        if ($job.ContainsKey('cache')) { $entry.cache=$job.cache; $entry.backendProxy='http://127.0.0.1:18097' }
        $entries=@($entries | Where-Object name -ne $job.name)+[pscustomobject]$entry
        if (Test-Path -LiteralPath $manifestPath) { Copy-Item -LiteralPath $manifestPath -Destination ($manifestPath+'.before-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')) }
        ConvertTo-Json -InputObject @($entries) -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    }
} finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
}
'已派发所选演示通道；启动结果请核对日志与接口，未自动提交业务任务。'
