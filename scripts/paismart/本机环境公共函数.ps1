$script:PaismartWorkspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script:PaismartRuntime = Join-Path $script:PaismartWorkspace '.runtime/paismart'
$script:PaismartJava = 'C:/Users/admin/AppData/Local/Programs/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin/java.exe'
# 唯一非秘密功能配置；禁止把任意 Spring 参数、地址或密钥透传到运行配置。
function Get-PaismartLocalFeatureMap {
    return [ordered]@{
        parseOcrEnabled='paismart.intelligence.parse.ocr-enabled'
        aliyunOcrEnabled='paismart.intelligence.ocr.aliyun-enabled'
        contextualEnabled='paismart.intelligence.contextual-enabled'
        rerankEnabled='paismart.intelligence.rerank-enabled'
        citationVerificationEnabled='paismart.intelligence.citation-verification-enabled'
        conflictLlmConfirmationEnabled='paismart.intelligence.conflict-llm-confirmation-enabled'
        paymentEnabled='paismart.payment.enabled'
        operationsExecuteEnabled='paismart.operations.execute-enabled'
        operationsLegacySourceEnabled='paismart.operations.legacy-source.enabled'
    }
}
function Read-PaismartLocalFeatures([string]$Path=(Join-Path $PSScriptRoot '本机功能配置.json')) {
    if(-not (Test-Path -LiteralPath $Path -PathType Leaf)){throw '缺本机功能配置.json；请恢复唯一非秘密配置文件，不自动生成或覆盖配置'}
    try{$config=Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop}
    catch{throw '本机功能配置.json 不是有效 JSON；请核对格式，文件内容未回显'}
    if($null -eq $config -or $config -isnot [pscustomobject]){throw '本机功能配置必须为 JSON 对象'}
    $names=@($config.PSObject.Properties.Name)
    if($names.Count -ne 2 -or 'version' -cnotin $names -or 'features' -cnotin $names){throw '本机功能配置只允许 version 与 features 两项，不接受任意参数或密钥'}
    if(($config.version -isnot [int] -and $config.version -isnot [long]) -or $config.version -ne 1){throw '本机功能配置 version 必须为整数 1'}
    if($config.features -isnot [pscustomobject]){throw '本机功能配置 features 必须为对象'}
    $map=Get-PaismartLocalFeatureMap
    $actual=@($config.features.PSObject.Properties.Name)
    if($actual.Count -ne $map.Count -or @($actual | Where-Object {$_ -cnotin @($map.Keys)}).Count){throw '本机功能配置 features 必须完整且只包含九个已定义开关，不能遗漏或增加字段'}
    foreach($name in $map.Keys){if($config.features.$name -isnot [bool]){throw "本机功能开关 $name 必须为 JSON 布尔值 true 或 false，不能填写字符串或数字"}}
    if($config.features.parseOcrEnabled -ne $config.features.aliyunOcrEnabled){throw '当前本机 OCR 使用阿里云：parseOcrEnabled 与 aliyunOcrEnabled 必须同时开启或同时关闭'}
    if($config.features.operationsLegacySourceEnabled -and -not $config.features.operationsExecuteEnabled){throw '旧源维护要求 operationsExecuteEnabled 同时开启；专项授权和真实连接另行核对'}
    return $config.features
}
function Get-PaismartLocalFeatureProperties($Features) {
    $map=Get-PaismartLocalFeatureMap
    foreach($name in $map.Keys){$map[$name]+'='+$Features.$name.ToString().ToLowerInvariant()}
}
# 开关为 true 只表示期望启用。这里只读前提，不调用模型、OCR、支付或维护接口。
function Get-PaismartLocalFeatureBlockers($Features,[hashtable]$ValidModels,$DatabaseState) {
    if(($Features.contextualEnabled -or $Features.citationVerificationEnabled -or $Features.conflictLlmConfirmationEnabled) -and -not $ValidModels['LLM']){
        '上下文、引用核验或冲突确认已申请启用，但现有文字模型托管用途和兼容门禁未就绪'
    }
    if($Features.rerankEnabled){
        $candidates=@($DatabaseState.providers | Where-Object {$_.scope -eq 'RERANK' -and $_.active -and $_.enabled -and $_.configured})
        if($candidates.Count -ne 1){'重排已申请启用，但缺唯一启用、激活且用途可解析的 RERANK Provider'}
    }
    if($Features.aliyunOcrEnabled){
        # 当前原库就绪工具只核对六个既有用途，尚未核对 OCR 的 AK/SK、回调、页数与费用条件。
        'OCR 已申请启用；日常前提检查尚未接入 OCR AK/SK、回调和页数费用条件，保留配置并等待专项配置核对'
    }
    if($Features.paymentEnabled){'支付已申请启用；日常前提检查尚未接入商户、通知地址和真实交易条件，保留配置并等待专项配置核对'}
    if($Features.operationsExecuteEnabled){'维护已申请启用；日常前提检查尚未接入批准计划、目标与恢复条件，保留配置并等待专项配置核对'}
}
function Get-PaismartOriginalEnvironment {
    $allowed = @('RUOYI_DB_URL','RUOYI_DB_USERNAME','RUOYI_DB_PASSWORD','RUOYI_MANAGED_SECRET_MASTER_KEY_ID','RUOYI_MANAGED_SECRET_MASTER_KEY_BASE64','RUOYI_MANAGED_SECRET_PREVIOUS_MASTER_KEY_ID','RUOYI_MANAGED_SECRET_PREVIOUS_MASTER_KEY_BASE64')
    $result=@{}
    foreach($line in Get-Content -LiteralPath (Join-Path $script:PaismartWorkspace '.env')){
        if($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$' -and $Matches[1] -in $allowed){
            $name=$Matches[1];$value=$Matches[2].Trim()
            if(($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))){$value=$value.Substring(1,$value.Length-2)}
            $result[$name]=$value
        }
    }
    foreach($name in $allowed[0..4]){if(-not $result[$name]){throw "原配置缺少$name"}}
    $uri=[Uri]($result.RUOYI_DB_URL -replace '^jdbc:','')
    if($uri.Host -notin @('localhost','127.0.0.1') -or $uri.Port -ne 3306 -or $uri.AbsolutePath -ne '/ry-vue'){throw '原数据库范围变化，停止本机启动'}
    return $result
}
function Invoke-PaismartWithEnvironment([hashtable]$Values,[scriptblock]$Action){
    $prior=@{}
    foreach($name in $Values.Keys){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
    try{foreach($name in $Values.Keys){[Environment]::SetEnvironmentVariable($name,$Values[$name],'Process')}; & $Action}
    finally{foreach($name in $Values.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}}
}
function Read-PaismartDpapi([string]$Path){
    $secure=(Get-Content -LiteralPath $Path -Raw).Trim() | ConvertTo-SecureString
    return [Net.NetworkCredential]::new('',$secure).Password
}
function Invoke-PaismartLocalSetup([ValidateSet('register','readiness','connections')][string]$Mode){
    $values=Get-PaismartOriginalEnvironment
    foreach($name in @('PAISMART_MINIO_ACCESS_KEY','PAISMART_MINIO_SECRET_KEY','PAISMART_ES_API_KEY','PAISMART_LLM_KEY','PAISMART_EMBEDDING_KEY')){$values[$name]=$null}
    if($Mode -eq 'register'){
        $minio=Join-Path $script:PaismartRuntime 'local-services/minio/business.credentials.clixml'
        $es=Join-Path $script:PaismartRuntime 'local-services/elasticsearch/paismart-api-key.dpapi'
        if(Test-Path -LiteralPath $minio){$credential=Import-Clixml -LiteralPath $minio;$values.PAISMART_MINIO_ACCESS_KEY=$credential.UserName;$values.PAISMART_MINIO_SECRET_KEY=$credential.GetNetworkCredential().Password}
        if(Test-Path -LiteralPath $es){$values.PAISMART_ES_API_KEY=Read-PaismartDpapi $es}
        foreach($pair in @(@('sub2api','PAISMART_LLM_KEY'),@('aliyun','PAISMART_EMBEDDING_KEY'))){
            $path=Join-Path $script:PaismartRuntime ('model-credentials/'+$pair[0]+'.clixml')
            if(Test-Path -LiteralPath $path){$credential=Import-Clixml -LiteralPath $path;$values[$pair[1]]=$credential.GetNetworkCredential().Password}
        }
    }
    Invoke-PaismartWithEnvironment $values {
        $classpath=(Join-Path $script:PaismartRuntime 'local-setup-classes')+';'+(Join-Path $script:PaismartRuntime 'startup-fix/lib/*')
        $arguments=@('-Dorg.slf4j.simpleLogger.defaultLogLevel=error','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8')
        if($Mode -eq 'connections'){$arguments+=('-Djavax.net.ssl.trustStore='+(Join-Path $script:PaismartRuntime 'local-services/elasticsearch/java-truststore.jks'));$arguments+='-Djavax.net.ssl.trustStorePassword=changeit'}
        $arguments+=@('-cp',$classpath,'PaismartLocalSecrets',$Mode)
        if($Mode -eq 'connections'){$arguments+=(Join-Path $script:PaismartRuntime 'application-local-connections.properties')}
        $output=@(& $script:PaismartJava @arguments 2>&1)
        if($LASTEXITCODE -ne 0){throw '本机项目连接登记/回读失败；未回显可能含连接信息的原始异常'}
        return $output
    }
}
function Test-PaismartOwnedProcess($Expected,[string]$Marker,[int]$Port){
    $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($Expected.pid)"
    if(-not $actual){return $false}
    if($actual.ExecutablePath.Replace('/','\') -ne $Expected.executable.Replace('/','\') -or [Math]::Abs(($actual.CreationDate-[datetime]$Expected.createdAt).TotalSeconds) -gt 2 -or -not $actual.CommandLine.Contains($Marker) -or -not $actual.CommandLine.Contains([string]$Port)){throw '登记进程身份不匹配，禁止接管或结束'}
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if(@($listeners | Where-Object {$_.OwningProcess -ne $Expected.pid -or $_.LocalAddress -notin @('127.0.0.1','::1')}).Count){throw '端口监听与登记PID或本机范围不同，禁止接管'}
    return $true
}
