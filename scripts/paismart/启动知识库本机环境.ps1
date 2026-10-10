[CmdletBinding()]
param([switch]$CheckOnly,[switch]$RestartBackend,[ValidateRange(10,360)][int]$ReadyTimeoutSeconds=300)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '本机环境公共函数.ps1')
# 在任何服务恢复、用途登记和配置写入前拒绝错误配置；CheckOnly 同样只读取。
$featureConfigPath=Join-Path $PSScriptRoot '本机功能配置.json'
$localFeatures=Read-PaismartLocalFeatures $featureConfigPath
$results=[Collections.Generic.List[object]]::new()
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$script:dependencyReady=$true
function Add-Ready([string]$Name,[string]$State,[string]$Detail){$results.Add([pscustomobject]@{name=$Name;state=$State;detail=$Detail;checkedAt=(Get-Date).ToString('o')})}
function Read-DatabaseState {
    $output=@(Invoke-PaismartLocalSetup 'readiness')
    $json=@($output | Where-Object {$_.ToString().TrimStart().StartsWith('{')}) | Select-Object -Last 1
    if(-not $json){throw '原库就绪回读未产生有效结果'}
    return ($json | ConvertFrom-Json)
}
function Read-VerifiedCenterArtifact {
    $expectedPath=[IO.Path]::GetFullPath((Join-Path $script:PaismartRuntime 'ruoyi-admin-startupfix.jar'))
    $recordPath=Join-Path $script:PaismartRuntime 'startup-fix/package-verification.json'
    try{
        $record=Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
        if(-not $record.target -or $record.target.path -isnot [string] -or $record.target.sha256 -isnot [string]){throw '制品记录缺固定目标及摘要'}
        if([IO.Path]::GetFullPath($record.target.path) -cne $expectedPath -or $record.target.sha256 -notmatch '^[0-9a-fA-F]{64}$'){throw '制品记录超出固定中心包范围或摘要格式不正确'}
        $actual=(Get-FileHash -LiteralPath $expectedPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if($actual -cne $record.target.sha256.ToLowerInvariant()){throw '固定中心包与既有制品核对记录摘要不一致'}
        return [pscustomobject]@{path=$expectedPath;sha256=$actual}
    }catch{throw '当前中心制品未通过固定路径与摘要核对；保留现有进程，先修复制品核对记录'}
}
New-Item -ItemType Directory -Path $script:PaismartRuntime -Force | Out-Null
try{
    $dbState=Read-DatabaseState
    if($dbState.database -ne 'ry-vue'){throw '数据库不是原ry-vue'}
    Add-Ready '原MySQL及项目配置' '就绪' "原库可读；知识库菜单$($dbState.menus)项"
}catch{Add-Ready '原MySQL及项目配置' '未就绪' $_.Exception.Message;$script:dependencyReady=$false}
try{
    $ping=@(& 'C:/Program Files/Redis/redis-cli.exe' -h 127.0.0.1 -p 6379 PING 2>&1)
    if($LASTEXITCODE -ne 0 -or $ping -notcontains 'PONG'){throw '原Redis PING未通过，保留现有认证配置'}
    Add-Ready '共享Redis' '就绪' '复用127.0.0.1:6379/0'
}catch{Add-Ready '共享Redis' '未就绪' $_.Exception.Message;$script:dependencyReady=$false}
foreach($service in @('MinIO','Kafka','ES')){
    try{
        $path=Join-Path $PSScriptRoot ($service+'本机服务.ps1')
        if($service -eq 'MinIO'){& $path -Action $(if($CheckOnly){'Check'}else{'Start'}) | Out-Null}
        elseif($service -eq 'Kafka'){if($CheckOnly){& $path -Check | Out-Null}else{& $path -Start -Check | Out-Null}}
        else{if($CheckOnly){& $path -Check | Out-Null}else{& $path -Start -Check | Out-Null}}
        if($service -eq 'MinIO'){$ready=Get-Content (Join-Path $script:PaismartRuntime 'local-services/minio/readiness.json') -Raw | ConvertFrom-Json;if(-not $ready.ready){throw 'MinIO项目身份检查未全部通过'}}
        elseif($service -eq 'Kafka'){$ready=Get-Content (Join-Path $script:PaismartRuntime 'local-services/kafka/project-topics-readiness.json') -Raw | ConvertFrom-Json;foreach($topic in @('paismart.jobs','paismart.jobs.dlq')){if($ready.topics -notcontains $topic){throw "Kafka缺项目主题$topic"}}}
        else{$ready=Get-Content (Join-Path $script:PaismartRuntime 'local-services/elasticsearch/readiness.json') -Raw | ConvertFrom-Json;if($ready.status -eq 'red' -or $ready.timedOut -or $ready.numberOfNodes -ne 1){throw 'ES集群状态不满足本机单节点就绪条件'}}
        Add-Ready $service '就绪' '通过本机身份与连接检查；正常进程复用，未重新初始化资源'
    }catch{Add-Ready $service '未就绪' $_.Exception.Message;$script:dependencyReady=$false}
}
try{
    if(-not $CheckOnly -and $script:dependencyReady){Invoke-PaismartLocalSetup 'register' | Out-Null}
    $dbState=Read-DatabaseState
    $missing=@($dbState.managedPurposes.PSObject.Properties | Where-Object {$_.Name.StartsWith('platform.') -and -not $_.Value} | ForEach-Object Name)
    if($missing.Count){throw ('缺项目连接用途：'+($missing -join '、'))}
    Add-Ready '存储与检索托管连接' '就绪' '三个固定用途可由原根密钥解密，未回显秘密'
}catch{Add-Ready '存储与检索托管连接' '未就绪' $_.Exception.Message;$script:dependencyReady=$false}
$validModels=@{}
$providersByScope=@{}
foreach($scope in @('LLM','EMBEDDING')){
    $providers=@($dbState.providers | Where-Object {$_.scope -eq $scope -and $_.enabled -and $_.active})
    $purpose=if($scope -eq 'LLM'){'ai.paismart.sub2api.llm'}else{'ai.paismart.aliyun.embedding'}
    $valid=$providers.Count -eq 1 -and $dbState.managedPurposes.$purpose -and $providers[0].configured -and $providers[0].secretRef -eq $purpose
    if($valid -and $scope -eq 'LLM'){$valid=$providers[0].providerCode -eq 'sub2api' -and $providers[0].compatibilityValid}
    if($valid -and $scope -eq 'EMBEDDING'){$valid=$providers[0].providerCode -eq 'aliyun' -and $providers[0].dimension -gt 0 -and $providers[0].dimension -le 8192}
    $validModels[$scope]=[bool]$valid
    if($valid){$providersByScope[$scope]=$providers[0]}
}
$featureBlockers=@(Get-PaismartLocalFeatureBlockers $localFeatures $validModels $dbState)
if($featureBlockers.Count){
    Add-Ready '本机功能配置' '未就绪' ($featureBlockers -join '；')
    $script:dependencyReady=$false
}else{
    $enabledFeatures=@($localFeatures.PSObject.Properties | Where-Object Value | ForEach-Object Name)
    Add-Ready '本机功能配置' '已配置' $(if($enabledFeatures.Count){'唯一持久配置已读取；启用 '+($enabledFeatures -join '、')+'，仅前提核对通过，收费与业务效果未验证'}else{'唯一持久配置已读取；九个可选能力保持关闭，不产生专项调用'})
}
try{
    $allowed=@('DOCUMENT_PROCESS','DOCUMENT_PARSE','DOCUMENT_REINDEX','DOCUMENT_DELETE','REINDEX','DELETE','CONFLICT_DETECT','INDEX_REBUILD','INDEX_ROLLBACK','INDEX_RECOVER','EVALUATION_RUN','CHAT_GENERATE')
    $unavailable=@($dbState.pendingJobTypes | Where-Object {$_.type -notin $allowed})
    if($unavailable.Count -or $dbState.legacyPendingTextJobs -gt 0){throw '存在未知、运维或旧策略排队任务；保留任务，禁止自动开启消费'}
    if($dbState.pendingJobs -gt 0 -and @($dbState.pendingJobTypes | Where-Object {$_.type -notin @('DOCUMENT_DELETE','DELETE')}).Count){
        foreach($scope in @('LLM','EMBEDDING')){if(-not $validModels[$scope]){throw '存在模型相关在途任务但密钥、用途、协议兼容或维度未就绪，保留任务并停止消费启动'}}
    }
    Add-Ready '任务消费前提' '就绪' "当前在途任务$($dbState.pendingJobs)项；无未知类型或旧文字策略任务"
}catch{Add-Ready '任务消费前提' '未就绪' $_.Exception.Message;$script:dependencyReady=$false}
$worker=Join-Path $script:PaismartWorkspace '.runtime/paismart-logic-20261003-373ca66f2b0a/backend/ruoyi-paismart/target/logic-worker'
$workerJar=Join-Path $worker 'ruoyi-paismart-3.9.2.jar'
$workerClasspath=$workerJar+';'+(Join-Path $worker 'lib/*')
if((Test-Path -LiteralPath $workerJar) -and @(Get-ChildItem -LiteralPath (Join-Path $worker 'lib') -Filter '*.jar' -ErrorAction SilentlyContinue).Count -eq 240){Add-Ready '解析Worker' '已配置' '固定Java解析JAR及240依赖存在；业务样本另行验证'}
else{Add-Ready '解析Worker' '未就绪' '固定解析JAR或同批240依赖缺失';$script:dependencyReady=$false}
$properties=Join-Path $script:PaismartRuntime 'application-local-connections.properties'
$hosts=[Collections.Generic.HashSet[string]]::new();$hosts.Add('127.0.0.1') | Out-Null
$approved=Get-Content -LiteralPath (Join-Path $PSScriptRoot '模型连接白名单.json') -Raw | ConvertFrom-Json
foreach($hostName in $approved.allowedHosts){
    if($hostName -ne '39.102.215.183' -and $hostName -ne 'dashscope.aliyuncs.com' -and $hostName -notmatch '^[a-z0-9-]+\.(cn-beijing|ap-southeast-1|us-east-1)\.maas\.aliyuncs\.com$'){throw '模型出口超出已批准Sub2API/阿里云范围'}
    $hosts.Add($hostName) | Out-Null
}
$allowHttp=[bool]$approved.allowHttp
$desired=@(
    'paismart.object-store.enabled=true','paismart.object-store.writes-enabled=true','paismart.object-store.probe-enabled=true',
    'paismart.object-store.endpoint=http://127.0.0.1:9000','paismart.object-store.bucket=paismart',
    'paismart.jobs.kafka-enabled=true','paismart.jobs.bootstrap-servers=127.0.0.1:9092','paismart.jobs.security-protocol=PLAINTEXT','paismart.jobs.allow-plaintext=true',
    'paismart.intelligence.external-calls-enabled=true',('paismart.intelligence.allow-http='+$allowHttp.ToString().ToLowerInvariant()),('paismart.intelligence.allowed-hosts='+([string]::Join(',',($hosts | Sort-Object)))),
    'paismart.intelligence.elasticsearch.endpoint=https://127.0.0.1:9200','paismart.intelligence.elasticsearch.analyzer=standard','paismart.intelligence.elasticsearch.secret-ref=platform.paismart.elasticsearch.api-key',
    'paismart.intelligence.parse.process-enabled=true',('paismart.intelligence.parse.worker-classpath='+$workerClasspath.Replace('\','/')),
    'paismart.intelligence.parse.pdf-engine=pdfbox','paismart.intelligence.parse.concurrency=1'
    Get-PaismartLocalFeatureProperties $localFeatures
    'paismart.intelligence.chat.allowed-origins=http://127.0.0.1:15177','paismart.intelligence.chat.max-output-tokens=1024'
) -join "`n"
$configurationChanged=(-not (Test-Path -LiteralPath $properties)) -or (Get-Content -LiteralPath $properties -Raw) -ne ($desired+"`n")
if(-not $CheckOnly -and $script:dependencyReady){if($configurationChanged -and (Test-Path -LiteralPath $properties)){Copy-Item -LiteralPath $properties -Destination (Join-Path $script:PaismartRuntime ('connections-before-'+$stamp+'.properties'))};[IO.File]::WriteAllText($properties,$desired+"`n",[Text.UTF8Encoding]::new($false))}
try{
    $connectionOutput=@(Invoke-PaismartLocalSetup 'connections')
    $connectionJson=@($connectionOutput | Where-Object {$_.ToString().TrimStart().StartsWith('{')}) | Select-Object -Last 1
    if(-not $connectionJson){throw '生产连接探测未产生有效结果'}
    $connectionJson | Set-Content -LiteralPath (Join-Path $script:PaismartRuntime 'production-connections-readiness.json') -Encoding utf8
    Add-Ready 'Java生产存储与ES连接' '就绪' '原托管用途解密；生产MinIO桶HEAD和Java私有TLS ES健康真实响应；未写业务对象/索引'
}catch{Add-Ready 'Java生产存储与ES连接' '未就绪' $_.Exception.Message;$script:dependencyReady=$false}
$manifestPath=Join-Path $script:PaismartRuntime 'processes.json'
$manifest=if(Test-Path -LiteralPath $manifestPath){Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json}else{[pscustomobject]@{}}
try{
    $verifiedArtifact=Read-VerifiedCenterArtifact
    Add-Ready '中心固定制品' '就绪' '固定运行包与既有制品核对记录路径及SHA256一致'
    if($CheckOnly -and $manifest.backend -and $manifest.backend.sha256 -cne $verifiedArtifact.sha256){Add-Ready '中心运行制品' '未就绪' '登记运行摘要与当前已验证制品不一致；仅检查模式保留现有进程，请执行日常入口更新本项目中心'}
}catch{Add-Ready '中心固定制品' '未就绪' $_.Exception.Message;$script:dependencyReady=$false}
if($CheckOnly){
    if($configurationChanged -or -not $manifest.backend -or -not (Test-Path -LiteralPath $properties) -or $manifest.backend.configurationSha256 -ne (Get-FileHash -LiteralPath $properties).Hash.ToLowerInvariant()){
        Add-Ready '中心运行配置' '未就绪' '期望配置、当前文件与登记运行摘要不一致；请运行日常入口恢复，仅检查模式不修改或重启'
    }else{Add-Ready '中心运行配置' '就绪' '期望配置、当前文件与登记运行摘要一致'}
}
if(-not $CheckOnly -and $script:dependencyReady){
    try{
        # 停止旧中心前再次核对，缺失或不符时保留正常旧进程。
        $verifiedArtifact=Read-VerifiedCenterArtifact
        $backendListening=@(Get-NetTCPConnection -State Listen -LocalPort 18096 -ErrorAction SilentlyContinue)
        $backendOwned=$false
        if($manifest.backend){$backendOwned=Test-PaismartOwnedProcess $manifest.backend $manifest.backend.artifact 18096}
        if($backendListening.Count -and -not $backendOwned){throw '18096由未登记进程占用，禁止接管'}
        $sameConfig=($manifest.backend.configurationSha256 -eq (Get-FileHash -LiteralPath $properties).Hash.ToLowerInvariant())
        $sameArtifact=($manifest.backend.sha256 -ceq $verifiedArtifact.sha256)
        if($backendOwned -and ($RestartBackend -or -not $sameConfig -or -not $sameArtifact)){
            Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $script:PaismartRuntime ('processes-before-'+$stamp+'.json'))
            Stop-Process -Id $manifest.backend.pid
            for($i=0;$i -lt 20;$i++){if(-not (Get-Process -Id $manifest.backend.pid -ErrorAction SilentlyContinue)){break};Start-Sleep -Milliseconds 300}
            if(Get-Process -Id $manifest.backend.pid -ErrorAction SilentlyContinue){throw '原本项目中心未退出，停止恢复'}
            $backendOwned=$false
        }
        if(-not $backendOwned){
            $verifiedArtifact=Read-VerifiedCenterArtifact
            $jar=$verifiedArtifact.path
            $arguments=@('-Xms128m','-Xmx768m',('-Djavax.net.ssl.trustStore='+('"'+(Join-Path $script:PaismartRuntime 'local-services/elasticsearch/java-truststore.jks')+'"')),'-Djavax.net.ssl.trustStorePassword=changeit','-jar',('"'+$jar+'"'),'--server.address=127.0.0.1','--server.port=18096','--spring.profiles.active=druid,local',('--spring.config.additional-location=file:'+ $properties.Replace('\','/')))
            foreach($business in @('interview','fashion','aden','aps','aipicture','aippt','paismart','paiflow','paicli')){$arguments+='--{0}.enabled={1}' -f $business,($business -eq 'paismart').ToString().ToLowerInvariant()}
            $arguments+=@('--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=6379','--spring.data.redis.database=0','--spring.flyway.enabled=false','--spring.quartz.auto-startup=false','--aden.collection.enabled=false','--aps.datasource.enabled=false','--aps.persistence.enabled=false','--aps.flyway.enabled=false','--interview.flyway.enabled=false','--interview.foundation-safety.background-jobs-enabled=false','--fashion.image.worker-enabled=false','--fashion.ai-runtime.worker-enabled=false','--fashion.migration.enabled=false','--logging.level.com.ruoyi=INFO','--logging.level.org.springframework=INFO',('--logging.config=file:'+(Join-Path $script:PaismartRuntime 'logback-local.xml').Replace('\','/')))
            $stdout=Join-Path $script:PaismartRuntime ('center-'+$stamp+'.stdout.log');$stderr=Join-Path $script:PaismartRuntime ('center-'+$stamp+'.stderr.log')
            $process=Invoke-PaismartWithEnvironment (Get-PaismartOriginalEnvironment) {Start-Process -FilePath $script:PaismartJava -ArgumentList $arguments -WorkingDirectory $script:PaismartWorkspace -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru}
            $manifest | Add-Member -NotePropertyName backend -NotePropertyValue @{pid=$process.Id;createdAt=$process.StartTime.ToString('o');executable=$script:PaismartJava;artifact=$jar;sha256=(Get-FileHash -LiteralPath $jar).Hash.ToLowerInvariant();configurationSha256=(Get-FileHash -LiteralPath $properties).Hash.ToLowerInvariant();arguments=$arguments;port=18096;stdout=$stdout;stderr=$stderr} -Force
            $manifest | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath $manifestPath -Encoding utf8
            Add-Ready '中心进程' '启动中' ('本项目中心PID='+$process.Id)
        }else{Add-Ready '中心进程' '已复用' ('现有PID='+$manifest.backend.pid)}
        $frontendListening=@(Get-NetTCPConnection -State Listen -LocalPort 15177 -ErrorAction SilentlyContinue)
        $frontendOwned=$false
        if($manifest.frontend){$frontendOwned=Test-PaismartOwnedProcess $manifest.frontend 'vite' 15177}
        if($frontendListening.Count -and -not $frontendOwned){throw '15177由未登记进程占用，禁止接管'}
        if(-not $frontendOwned){
            $node='C:/Program Files/nodejs/node.exe';$vite=Join-Path $script:PaismartWorkspace 'frontend/admin-web/node_modules/vite/bin/vite.js'
            $frontendArgs=@(('"'+$vite+'"'),'--host','127.0.0.1','--port','15177','--strictPort')
            $process=Invoke-PaismartWithEnvironment @{ADMIN_DEV_BACKEND_URL='http://127.0.0.1:18096';PROJECT_VITE_CACHE_DIR=(Join-Path $script:PaismartRuntime 'vite-cache')} {Start-Process -FilePath $node -ArgumentList $frontendArgs -WorkingDirectory (Join-Path $script:PaismartWorkspace 'frontend/admin-web') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $script:PaismartRuntime ('frontend-'+$stamp+'.stdout.log')) -RedirectStandardError (Join-Path $script:PaismartRuntime ('frontend-'+$stamp+'.stderr.log')) -PassThru}
            $manifest | Add-Member -NotePropertyName frontend -NotePropertyValue @{pid=$process.Id;createdAt=$process.StartTime.ToString('o');executable=$node;arguments=$frontendArgs;port=15177;proxy='http://127.0.0.1:18096';cacheDir=(Join-Path $script:PaismartRuntime 'vite-cache')} -Force
        }
        $manifest | Add-Member -NotePropertyName capturedAt -NotePropertyValue (Get-Date).ToString('o') -Force
        $manifest | Add-Member -NotePropertyName scope -NotePropertyValue '本机存储、ES、Kafka消费及解析已配置；模型和正式索引就绪单独判断' -Force
        $manifest | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath $manifestPath -Encoding utf8
        Add-Ready '前端进程' '已运行' ('本项目Vite PID='+$manifest.frontend.pid)
        Write-Host '正在等待本项目中心及前端代理响应；详细启动日志已保存。'
    }catch{Add-Ready '本项目通道启动' '未就绪' $_.Exception.Message}
}
try{
    $deadline=[DateTime]::UtcNow.AddSeconds($(if($CheckOnly){10}else{$ReadyTimeoutSeconds}))
    $httpReady=$false
    do{
        try{
            $center=Invoke-WebRequest -Uri 'http://127.0.0.1:18096/' -TimeoutSec 2 -UseBasicParsing
            $proxy=Invoke-RestMethod -Uri 'http://127.0.0.1:15177/dev-api/captchaImage' -TimeoutSec 2
            if($center.StatusCode -eq 200 -and $proxy.code -eq 200){
                if(-not $manifest.backend -or -not $manifest.frontend){throw '缺中心或前端进程登记，不能只以HTTP响应判就绪'}
                if(-not (Test-PaismartOwnedProcess $manifest.backend $manifest.backend.artifact 18096) -or -not (Test-PaismartOwnedProcess $manifest.frontend 'vite' 15177)){throw 'HTTP响应缺登记进程归属'}
                $httpReady=$true;break
            }
        }catch{<# 仅等待就绪；不重送业务请求或重启进程。 #>}
        if($manifest.backend -and -not (Get-Process -Id $manifest.backend.pid -ErrorAction SilentlyContinue)){throw '中心进程退出，保留本次日志，不自动反复重启'}
        if([DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 750}
    }while([DateTime]::UtcNow -lt $deadline)
    if(-not $httpReady){throw '中心或前端代理未在时间预算内就绪；请检查本次日志'}
    foreach($item in $results){if($item.name -eq '中心进程' -and $item.state -eq '启动中'){$item.state='就绪';$item.detail+='；归属及HTTP已核对'}}
    Add-Ready '中心与前端HTTP' '就绪' '中心200、同源代理正常；未登录及业务功能单独验证'
}catch{Add-Ready '中心与前端HTTP' '未就绪' $_.Exception.Message}
foreach($scope in @('LLM','EMBEDDING')){
    if($validModels[$scope]){Add-Ready $(if($scope -eq 'LLM'){'文字模型'}else{'向量模型'}) '已配置' "当前模型$($providersByScope[$scope].model)，地址$($providersByScope[$scope].endpoint)，维度$($providersByScope[$scope].dimension)；原兼容与用途门禁通过，日常启动不发送收费请求"}
    else{Add-Ready $(if($scope -eq 'LLM'){'文字模型'}else{'向量模型'}) '未就绪' '缺唯一启用/激活的真实Provider或本项目有效密钥'}
}
if($dbState.activeIndexes -eq 1){Add-Ready '正式索引' '已配置' '存在唯一活动版本；实际文档检索另行验证'}else{Add-Ready '正式索引' '未就绪' '尚无唯一活动版本；不能用空索引伪装知识检索通过'}
$report=[ordered]@{checkedAt=(Get-Date).ToString('o');mode=$(if($CheckOnly){'仅检查'}else{'复用或恢复'});project='PaiSmart';items=$results.ToArray();configurationReady=(@($results | Where-Object state -eq '未就绪').Count -eq 0);completeBusinessVerified=$false;startupModelProbeMade=$false;backgroundModelCallsNotMeasured=$true;businessSamplesSubmitted=$false;sharedServicesStopped=$false;manifest=$manifestPath;frontend='http://127.0.0.1:15177';}
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $script:PaismartRuntime 'daily-readiness.json') -Encoding utf8
$results | Format-Table name,state,detail -Wrap
Write-Host ('逐项结果：'+(Join-Path $script:PaismartRuntime 'daily-readiness.json'))
