# 本机 MinIO 的首次准备、恢复与只读核对；秘密仅保存在当前用户 DPAPI 文件中。
[CmdletBinding()]
param([ValidateSet('Start','Check')][string]$Action='Start')
$ErrorActionPreference='Stop'
$workspaceRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$privateRoot=Join-Path $workspaceRoot '.runtime/paismart/local-services/minio'
$serverExe='D:\javaSoft\minio.exe'
$clientExe='D:\javaSoft\mc.windows-amd64.RELEASE.2025-08-13T08-35-41Z.exe'
$dataPath='D:\javaSoft\minio'
$endpoint='http://127.0.0.1:9000'
$bucket='paismart'
$rootFile=Join-Path $privateRoot 'root.credentials.clixml'
$businessFile=Join-Path $privateRoot 'business.credentials.clixml'
$processFile=Join-Path $privateRoot 'process.json'
$readyFile=Join-Path $privateRoot 'readiness.json'
function Protect-PrivatePath([string]$Path) {
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $existing=Get-Acl -LiteralPath $Path
    $rules=@($existing.Access)
    if($existing.AreAccessRulesProtected -and $rules.Count -eq 1 -and $rules[0].IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -eq $sid.Value -and $rules[0].FileSystemRights -eq [Security.AccessControl.FileSystemRights]::FullControl -and $rules[0].AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow){return}
    if((Get-Item -LiteralPath $Path).PSIsContainer){
        $acl=[Security.AccessControl.DirectorySecurity]::new()
        $acl.SetAccessRuleProtection($true,$false)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    } else {
        $acl=[Security.AccessControl.FileSecurity]::new()
        $acl.SetAccessRuleProtection($true,$false)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow'))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function New-RandomToken([int]$Length=32) {
    $bytes=New-Object byte[] $Length
    $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
    return ([Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_'))
}
function Invoke-MinioClient([string[]]$ClientArgs,[switch]$AllowFailure) {
    # MC_HOST_* 只在调用进程环境中传递，命令行和输出均不记录身份。
    $captured=(& $clientExe --json --config-dir (Join-Path $privateRoot 'mc-config') @ClientArgs 2>&1 | Out-String)
    $nativeExit=$LASTEXITCODE
    $parsed=@();try{$parsed=@($captured|ConvertFrom-Json)}catch{
        foreach($line in ($captured -split "`r?`n")){if($line.Trim().StartsWith('{')){try{$parsed+=@($line|ConvertFrom-Json)}catch{}}}
    }
    $hasError=@($parsed|Where-Object {$_.status -eq 'error'}).Count -gt 0
    $denied=$hasError -and $captured -match 'Access.?Denied|permission|not allowed|403'
    $result=[pscustomobject]@{ExitCode=$nativeExit;Output=$captured;Json=$parsed;HasError=$hasError;PermissionDenied=$denied}
    if(($result.ExitCode -ne 0 -or $result.HasError) -and -not $AllowFailure){throw 'MinIO 客户端操作失败；未输出可能含身份的原始响应'}
    return $result
}
function Set-MinioHost([string]$Name,[PSCredential]$Credential) {
    $url='http://'+[Uri]::EscapeDataString($Credential.UserName)+':'+[Uri]::EscapeDataString($Credential.GetNetworkCredential().Password)+'@127.0.0.1:9000'
    [Environment]::SetEnvironmentVariable('MC_HOST_'+$Name,$url,'Process')
}
function Get-SdkProbeStatus([PSCredential]$Credential,[string]$BucketName,[string]$ObjectName='') {
    # 使用本项目固定批次的 MinIO SDK；签名由 SDK 处理，凭据仅由环境传给短进程。
    $javaExe='C:\Users\admin\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\java.exe'
    $libraryRoot=Join-Path $workspaceRoot '.runtime/paismart-logic-20261003-373ca66f2b0a/backend/ruoyi-paismart/target/logic-worker/lib'
    $sourceFile=Join-Path $privateRoot 'MinioReadOnlyProbe.java'
    if(-not(Test-Path -LiteralPath $sourceFile) -or -not(Test-Path -LiteralPath (Join-Path $libraryRoot 'minio-8.6.0.jar'))){throw 'MinIO SDK 只读探测入口或固定依赖缺失'}
    $oldAccess=$env:PAISMART_MINIO_PROBE_ACCESS;$oldSecret=$env:PAISMART_MINIO_PROBE_SECRET
    try {
        $env:PAISMART_MINIO_PROBE_ACCESS=$Credential.UserName
        $env:PAISMART_MINIO_PROBE_SECRET=$Credential.GetNetworkCredential().Password
        $captured=@(& $javaExe --class-path "$libraryRoot/*" $sourceFile $BucketName $ObjectName 2>&1)
        $exitCode=$LASTEXITCODE
        $status=@($captured | ForEach-Object {[string]$_} | Where-Object {$_ -match '^\d{3}$'})
        if($exitCode -ne 0 -or $status.Count -ne 1){throw 'MinIO SDK 只读探测未返回有效状态；未输出原始响应'}
        return [int]$status[0]
    } finally {$env:PAISMART_MINIO_PROBE_ACCESS=$oldAccess;$env:PAISMART_MINIO_PROBE_SECRET=$oldSecret}
}
if(-not(Test-Path -LiteralPath $serverExe) -or -not(Test-Path -LiteralPath (Join-Path $dataPath '.minio.sys/format.json'))){throw '原安装或存储格式元数据缺失；禁止改为空目录'}
if(-not(Test-Path -LiteralPath $clientExe)){throw '缺少已审核的官方 mc 客户端'}
if((Get-FileHash -LiteralPath $clientExe -Algorithm SHA256).Hash.ToLower() -ne 'c8db13ebeda31497f354c0e950809db0ae9b2a2a69b8afee68c128c37300c157'){throw 'mc 官方摘要不匹配'}
New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
Protect-PrivatePath $privateRoot
$listeners=@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$_.LocalPort -in 9000,9001})
if($listeners.Count -gt 0){
    if(-not(Test-Path -LiteralPath $processFile)){throw '端口由未登记进程占用；不接管'}
    $registered=Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
    $current=Get-CimInstance Win32_Process -Filter "ProcessId=$($registered.pid)"
    if(-not $current -or $current.ExecutablePath -ne $serverExe -or $current.CreationDate -ne [datetime]$registered.creationTime -or @($listeners | Where-Object {$_.OwningProcess -ne $registered.pid -or $_.LocalAddress -notin @('127.0.0.1','::1')}).Count -gt 0){throw '已监听进程身份不匹配；不停止、不接管'}
}
if(-not(Test-Path -LiteralPath $rootFile)){
    if($Action -ne 'Start' -or $listeners.Count -gt 0){throw '缺少本机 root DPAPI 文件；不能猜测原身份'}
    $rootCredential=[PSCredential]::new(('paismart-local-root-'+(New-RandomToken 12)),(ConvertTo-SecureString (New-RandomToken 48) -AsPlainText -Force))
    $rootCredential | Export-Clixml -LiteralPath $rootFile
    Protect-PrivatePath $rootFile
}
$rootCredential=Import-Clixml -LiteralPath $rootFile
if($listeners.Count -eq 0){
    if($Action -ne 'Start'){throw 'MinIO 当前没有进程；Check 不自动启动'}
    $oldRootUser=$env:MINIO_ROOT_USER;$oldRootPassword=$env:MINIO_ROOT_PASSWORD
    try {
        $env:MINIO_ROOT_USER=$rootCredential.UserName
        $env:MINIO_ROOT_PASSWORD=$rootCredential.GetNetworkCredential().Password
        $process=Start-Process -FilePath $serverExe -ArgumentList @('server',$dataPath,'--address','127.0.0.1:9000','--console-address','127.0.0.1:9001') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $privateRoot 'stdout.log') -RedirectStandardError (Join-Path $privateRoot 'stderr.log') -PassThru
    } finally {$env:MINIO_ROOT_USER=$oldRootUser;$env:MINIO_ROOT_PASSWORD=$oldRootPassword}
    for($attempt=0;$attempt -lt 30;$attempt++){
        try {$health=Invoke-WebRequest -UseBasicParsing -Uri "$endpoint/minio/health/live";if($health.StatusCode -eq 200){break}}catch{}
        Start-Sleep -Milliseconds 500
    }
    $current=Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
    if(-not $current){throw 'MinIO 启动进程退出；保留原目录和日志'}
    $registered=[ordered]@{pid=$process.Id;creationTime=$current.CreationDate.ToString('o');executable=$serverExe;sha256=(Get-FileHash -LiteralPath $serverExe -Algorithm SHA256).Hash.ToLower();dataDirectory=$dataPath;api=$endpoint;console='http://127.0.0.1:9001';stdout=(Join-Path $privateRoot 'stdout.log');stderr=(Join-Path $privateRoot 'stderr.log');rootCredentialFile=$rootFile}
    $registered | ConvertTo-Json | Set-Content -LiteralPath $processFile -Encoding UTF8
}
$oldRoot=$env:MC_HOST_psroot;$oldBusiness=$env:MC_HOST_psbusiness
try {
    Set-MinioHost 'psroot' $rootCredential
    if(-not(Test-Path -LiteralPath $businessFile)){
        if($Action -ne 'Start'){throw '业务身份未准备；Check 不创建资源'}
        Invoke-MinioClient @('mb','--ignore-existing',"psroot/$bucket") | Out-Null
        $policyPath=Join-Path $privateRoot 'business-policy.json'
        $policy=@{Version='2012-10-17';Statement=@(@{Effect='Allow';Action=@('s3:GetBucketLocation','s3:ListBucket','s3:ListBucketMultipartUploads');Resource=@("arn:aws:s3:::$bucket")},@{Effect='Allow';Action=@('s3:GetObject','s3:PutObject','s3:DeleteObject','s3:AbortMultipartUpload','s3:ListMultipartUploadParts');Resource=@("arn:aws:s3:::$bucket/paismart/*")})}
        $policy | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $policyPath -Encoding UTF8
        # 空 TargetUser 表示当前 root；避免把 root access key 放进命令参数。
        $serviceResponse=Invoke-MinioClient @('admin','user','svcacct','add','psroot','','--policy',$policyPath,'--name','paismart-object-store')
        $serviceAccount=$serviceResponse.Output | ConvertFrom-Json
        if(-not $serviceAccount.accessKey -or -not $serviceAccount.secretKey){throw 'mc 未返回可用业务身份；不打印响应'}
        [PSCredential]::new($serviceAccount.accessKey,(ConvertTo-SecureString $serviceAccount.secretKey -AsPlainText -Force)) | Export-Clixml -LiteralPath $businessFile
        Protect-PrivatePath $businessFile
        $serviceAccount=$null;$serviceResponse=$null
    }
    $businessCredential=Import-Clixml -LiteralPath $businessFile
    Set-MinioHost 'psbusiness' $businessCredential
    $live=[int](Invoke-WebRequest -UseBasicParsing -Uri "$endpoint/minio/health/live").StatusCode
    $head=Get-SdkProbeStatus $businessCredential $bucket
    $list=Invoke-MinioClient @('ls',"psbusiness/$bucket")
    $globalList=Invoke-MinioClient @('ls','psbusiness') -AllowFailure
    $adminInfo=Invoke-MinioClient @('admin','info','psbusiness') -AllowFailure
    $outsideHead=Get-SdkProbeStatus $businessCredential 'paismart-forbidden-check'
    $outsidePrefixHead=Get-SdkProbeStatus $businessCredential $bucket 'outside-project/readonly-check'
    $globalKeys=@($globalList.Json|Where-Object {$_.status -eq 'success'}|ForEach-Object {[string]$_.key})
    $globalOnlyProject=($globalList.ExitCode -eq 0 -and -not $globalList.HasError -and $globalKeys.Count -gt 0 -and @($globalKeys|Where-Object {$_.TrimEnd('/') -ne $bucket}).Count -eq 0)
    $restricted=(($globalList.PermissionDenied -or $globalOnlyProject) -and $adminInfo.PermissionDenied -and $outsideHead -eq 403 -and $outsidePrefixHead -eq 403)
    $evidence=[ordered]@{checkedAt=[DateTimeOffset]::Now.ToString('o');action=$Action;service='MinIO';pid=$registered.pid;creationTime=$registered.creationTime;endpoint=$endpoint;console='http://127.0.0.1:9001';bucket=$bucket;objectPrefix='paismart/';liveHttpStatus=$live;bucketHeadHttpStatus=$head;headProbe='现有 MinIO Java SDK 8.6.0 BucketExists HEAD（显式region，无业务对象写入）';bucketListSucceeded=($list.ExitCode -eq 0 -and -not $list.HasError);globalBucketListDenied=$globalList.PermissionDenied;globalListOnlyProjectBucket=$globalOnlyProject;listedBuckets=$globalKeys;adminInfoDenied=$adminInfo.PermissionDenied;adminResponseJsonError=$adminInfo.HasError;adminNativeExitCode=$adminInfo.ExitCode;outsideBucketHeadHttpStatus=$outsideHead;outsideObjectPrefixHeadHttpStatus=$outsidePrefixHead;businessIdentityRestricted=$restricted;ready=($live -eq 200 -and $head -eq 200 -and $list.ExitCode -eq 0 -and -not $list.HasError -and $restricted);credentialProtection='Windows DPAPI CurrentUser + exclusive CurrentUser ACL';businessCredentialFile=$businessFile;rootCredentialFile=$rootFile;accessKeySecretRef='platform.paismart.minio.access-key';secretKeySecretRef='platform.paismart.minio.secret-key';mcPath=$clientExe;mcSha256=(Get-FileHash -LiteralPath $clientExe -Algorithm SHA256).Hash.ToLower();mcOfficialRelease='https://github.com/minio/mc/releases/tag/RELEASE.2025-08-13T08-35-41Z';scope='服务健康、业务身份桶HEAD/List；全局列表仅返回项目桶；跨桶/管理/桶内其他前缀访问拒绝；未上传业务文档'}
    $evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $readyFile -Encoding UTF8
    $historyPath=Join-Path $privateRoot 'readiness-history.json'
    $history=@();if(Test-Path -LiteralPath $historyPath){$history=@(Get-Content -LiteralPath $historyPath -Raw|ConvertFrom-Json)}
    @($history)+@($evidence) | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $historyPath -Encoding UTF8
    $evidence | ConvertTo-Json -Depth 6
    if(-not $evidence.ready){throw 'MinIO 就绪核对未全部通过；已保留非秘密结果'}
} finally {
    $env:MC_HOST_psroot=$oldRoot;$env:MC_HOST_psbusiness=$oldBusiness
    $rootCredential=$null;$businessCredential=$null
}
