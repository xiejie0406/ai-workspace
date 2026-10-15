# 共享基础服务只核对；本入口仅管理当前 PaiCLI 中心、网页和本人 Runner。
param(
    [Parameter(Mandatory=$true)][string]$WorkspaceDirectory,
    [Parameter(Mandatory=$true)][string]$RuntimeDirectory,
    [string]$BackendJarPath,
    [string]$JavaPath,
    [string]$NodePath='C:/Program Files/nodejs/node.exe',
    [switch]$InitializeRunner,
    [switch]$ReadPlatformTokenFromStdin,
    [Security.SecureString]$PlatformToken
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion -lt [Version]'7.5'){throw '请使用 PowerShell 7.5 或以上运行本机启动入口'}
$workspace=[IO.Path]::GetFullPath($WorkspaceDirectory)
$runtime=[IO.Path]::GetFullPath($RuntimeDirectory)
if(-not $BackendJarPath){$BackendJarPath=Join-Path $workspace 'ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar'}
if(-not $JavaPath){$JavaPath=(Get-Command java -ErrorAction Stop).Source}
$java=[IO.Path]::GetFullPath($JavaPath)
$manifestPath=Join-Path $runtime 'processes.json'
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
foreach($path in @($runtime,(Join-Path $runtime 'logs'),(Join-Path $runtime 'profile'),(Join-Path $runtime 'vite-cache'))){
    $item=Get-Item -LiteralPath $path
    if(-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw '已登记运行目录类型不符'}
    $acl=Get-Acl -LiteralPath $path
    if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value){throw '运行目录所有者不符'}
    # 已登记外层目录允许现有工具访问；日志、数据与 Runner 凭据目录必须本人独占。
    if($path -ne $runtime){foreach($entry in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){if($entry.AccessControlType -eq 'Allow' -and $entry.IdentityReference.Value -ne $sid.Value){throw '运行目录存在其他主体权限'}}}
}
try{$lock=[IO.File]::Open((Join-Path $runtime 'paicli-start.lock'),'OpenOrCreate','ReadWrite','None')}catch{throw '另一个 PaiCLI 启动操作正在执行'}
try{
    $previous=if(Test-Path -LiteralPath $manifestPath){Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json -AsHashtable -DateKind String}else{@{Processes=@()}}
    $source=[IO.Path]::GetFullPath($BackendJarPath)
    if($source -ne [IO.Path]::GetFullPath((Join-Path $workspace 'ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar'))){throw '仅接受本工作区已登记的中心构建入口'}
    $jarHash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    $jar=Join-Path $runtime ('ruoyi-admin-'+$jarHash.ToLowerInvariant()+'.jar')
    if(-not(Test-Path -LiteralPath $jar)){Copy-Item -LiteralPath $source -Destination $jar}
    if((Get-FileHash -LiteralPath $jar).Hash -ne $jarHash){throw '中心固定包摘要不符'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($jar)
    try{
        $entries=@($zip.Entries|Where-Object FullName -match '^BOOT-INF/lib/paicli-api-[^/]+\.jar$')
        if($entries.Count -ne 1){throw '中心包缺少唯一 PaiCLI API'}
        $stream=$entries[0].Open();$digest=[Security.Cryptography.SHA256]::Create()
        try{$apiHash=[Convert]::ToHexString($digest.ComputeHash($stream))}finally{$stream.Dispose();$digest.Dispose()}
        if($apiHash -ne (Get-FileHash -LiteralPath (Join-Path $workspace 'ruoyi-backend/ruoyi-paicli/paicli-api/target/paicli-api-3.9.2.jar')).Hash){throw '中心包与 API 构建版本不符'}
    }finally{$zip.Dispose()}
    $pong=& 'C:/Program Files/Redis/redis-cli.exe' -h 127.0.0.1 -p 6379 ping
    if($LASTEXITCODE -ne 0 -or $pong -ne 'PONG'){throw '原共享 Redis 尚未就绪'}
    $settings=@{}
    foreach($line in Get-Content -LiteralPath (Join-Path $workspace '.env')){if($line -match '^((?:RUOYI_DB_[A-Z0-9_]+|RUOYI_MANAGED_SECRET_[A-Z0-9_]+))=(.*)$'){$settings[$Matches[1]]=$Matches[2].Trim().Trim('"').Trim("'")}}
    foreach($name in @('RUOYI_DB_URL','RUOYI_DB_USERNAME','RUOYI_DB_PASSWORD','RUOYI_MANAGED_SECRET_MASTER_KEY_ID','RUOYI_MANAGED_SECRET_MASTER_KEY_BASE64')){if(-not $settings.ContainsKey($name)){throw '原共享环境配置缺项'}}
    $db=[Uri]($settings.RUOYI_DB_URL -replace '^jdbc:','')
    if($db.Host -notin @('localhost','127.0.0.1') -or $db.Port -ne 3306 -or $db.AbsolutePath -ne '/ry-vue'){throw '原业务数据库范围不符'}
    $businesses=@('interview','fashion','aden','aps','paiflow','aipicture','aippt','paismart','paicli')
    $moduleArgs=@(foreach($business in $businesses){'--{0}.enabled={1}' -f $business,($business -eq 'paicli').ToString().ToLowerInvariant()})
    $manifest=@{Time=[DateTimeOffset]::Now.ToString('o');RuntimeDirectory=$runtime;Database='localhost:3306/ry-vue';MigrationVersion=7;Ports=@{Backend=18095;Frontend=15176;Redis=6379};RedisShared=$true;Jar=$jar;JarSHA256=$jarHash;ApiSHA256=$apiHash;BusinessSwitches=$moduleArgs;Processes=@($previous.Processes);StartupVerified=$false;RunnerStarted=$false;BusinessExecuted=0;BusinessPassed=0}
    function Save-Manifest{$tmp=$manifestPath+'.tmp';$manifest|ConvertTo-Json -Depth 9|Set-Content -LiteralPath $tmp -Encoding utf8;[IO.File]::Move($tmp,$manifestPath,$true)}
    function Start-OrReuse([string]$Name,[string]$Program,[string[]]$Arguments,[string]$Directory,[int]$Port){
        $escaped=@($Arguments|ForEach-Object {if($_.Contains('"')){throw '启动参数不支持引号'};if($_ -match '\s'){'"'+$_+'"'}else{$_}})
        $expectedCommand='"'+[IO.Path]::GetFullPath($Program)+'" '+($escaped -join ' ')
        $old=@($previous.Processes|Where-Object {$_.Name -eq $Name})|Select-Object -First 1
        $actual=if($old){Get-CimInstance Win32_Process -Filter ('ProcessId='+$old.Pid)}else{$null}
        $listener=@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
        if($actual -and $actual.CommandLine -ceq $old.CommandLine -and $actual.CreationDate.ToUniversalTime().ToString('o') -ceq $old.StartedUtc -and $actual.ExecutablePath -ieq [IO.Path]::GetFullPath($Program)){
            if($actual.CommandLine -cne $expectedCommand){throw '已登记进程启动参数与本次配置不同，未混用或重复启动'}
            if($Name -eq 'paicli-java' -and $previous.JarSHA256 -ne $jarHash){throw '旧中心仍在运行，更新包前须先按原身份停止，未重复启动'}
            if(@($listener|Where-Object {$_.OwningProcess -ne $old.Pid -or $_.LocalAddress -ne '127.0.0.1'}).Count){throw '目标端口不属于已登记的回环进程'}
            Save-Manifest;Write-Output ('复用 '+$Name+' PID='+$old.Pid);return
        }
        if($listener.Count){throw ('端口 '+$Port+' 被未核实的进程占用，未停止该进程')}
        $stamp=Get-Date -Format 'yyyyMMddTHHmmssfff';$out=Join-Path $runtime ('logs/'+$Name+'-'+$stamp+'.out.log');$err=Join-Path $runtime ('logs/'+$Name+'-'+$stamp+'.err.log')
        $p=Start-Process -FilePath $Program -ArgumentList $escaped -WorkingDirectory $Directory -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
        $identity=Get-CimInstance Win32_Process -Filter ('ProcessId='+$p.Id)
        if(-not $identity){throw '启动后未取得进程身份，保留日志'}
        $manifest.Processes=@($manifest.Processes|Where-Object {$_.Name -ne $Name})+@(@{Name=$Name;Pid=$p.Id;StartedUtc=$identity.CreationDate.ToUniversalTime().ToString('o');CommandLine=$identity.CommandLine;ExecutablePath=$identity.ExecutablePath;Port=$Port;Stdout=$out;Stderr=$err});Save-Manifest
        Write-Output ('启动 '+$Name+' PID='+$p.Id)
    }
    $arguments=@('-Xms128m','-Xmx768m','-jar',$jar,'--spring.profiles.active=druid,local','--server.address=127.0.0.1','--server.port=18095','--spring.quartz.auto-startup=false','--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=6379','--spring.data.redis.database=0','--spring.data.redis.password=','--paicli.terminal.enabled=true','--paicli.terminal.allowed-origins=http://127.0.0.1:15176','--ruoyi.local-captcha-debug.enabled=true','--spring.flyway.enabled=false','--paicli.expected-database=ry-vue','--paicli.formal-evaluation-paused=true',('--ruoyi.profile='+(Join-Path $runtime 'profile')),('--logging.config=file:'+(Join-Path $runtime 'logback.xml').Replace('\','/')),'--spring.datasource.druid.initial-size=1','--spring.datasource.druid.min-idle=1','--spring.datasource.druid.max-active=8','--spring.servlet.multipart.max-file-size=50MB','--spring.servlet.multipart.max-request-size=55MB','--aippt.worker.enabled=false','--paismart.jobs.enabled=false','--paismart.parse.enabled=false','--paismart.object.enabled=false','--paismart.model.enabled=false','--aden.collection.enabled=false','--fashion.ai-runtime.worker-enabled=false','--fashion.image.worker-enabled=false','--fashion.migration.enabled=false','--interview.flyway.enabled=false','--interview.foundation-safety.background-jobs-enabled=false','--interview.foundation-safety.business-rest-endpoints-enabled=false','--interview.foundation-safety.catalog-rest-endpoints-enabled=false','--interview.foundation-safety.external-provider-calls-enabled=false','--interview.foundation-safety.object-storage-writes-enabled=false','--aps.api.enabled=false','--aps.persistence.enabled=false','--aps.datasource.enabled=false','--aps.worker.enabled=false','--aps.worker.polling-enabled=false','--aps.flyway.enabled=false','--paiflow.worker.enabled=false')+$moduleArgs
    $prior=@{}
    try{foreach($setting in $settings.GetEnumerator()){$prior[$setting.Key]=[Environment]::GetEnvironmentVariable($setting.Key,'Process');[Environment]::SetEnvironmentVariable($setting.Key,$setting.Value,'Process')};Start-OrReuse 'paicli-java' $java $arguments $runtime 18095}
    finally{foreach($name in $settings.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')};$settings.Clear()}
    $ready=$false
    for($i=0;$i -lt 90;$i++){try{$r=Invoke-RestMethod 'http://127.0.0.1:18095/captchaImage' -TimeoutSec 2;if($r.code -eq 200){$ready=$true;break}}catch{};Start-Sleep -Milliseconds 500}
    if(-not $ready){throw '中心尚未就绪，未继续启动 Runner'}
    $runnerDirectory=Join-Path $runtime 'runner-launcher'
    $runnerArgs=@{RuntimeDirectory=$runnerDirectory;ReadyTimeoutSeconds=60}
    if($InitializeRunner){
        $runnerArgs.Action='Initialize';$runnerArgs.CliJarPath=Join-Path $workspace 'ruoyi-backend/ruoyi-paicli/paicli-cli/target/paicli-cli-3.9.2.jar';$runnerArgs.JavaPath=$java
        if($ReadPlatformTokenFromStdin){$runnerArgs.ReadPlatformTokenFromStdin=$true}elseif($PlatformToken){$runnerArgs.PlatformToken=$PlatformToken}
    }else{
        $runnerConfig=Get-Content -LiteralPath (Join-Path $runnerDirectory 'local-runner.json') -Raw|ConvertFrom-Json -AsHashtable -DateKind String
        if($runnerConfig.CliSHA256 -ne (Get-FileHash -LiteralPath (Join-Path $workspace 'ruoyi-backend/ruoyi-paicli/paicli-cli/target/paicli-cli-3.9.2.jar')).Hash){throw '当前 CLI 构建与已登记运行包不同，请停止本人 Runner 后受控更新，未混用版本'}
        $runnerArgs.Action='Start'
    }
    & (Join-Path $PSScriptRoot 'local-runner.ps1') @runnerArgs
    $manifest.RunnerStarted=$true;$manifest.RunnerLauncher=$runnerDirectory;Save-Manifest
    $front=Join-Path $workspace 'frontend/admin-web';$priorBackend=$env:ADMIN_DEV_BACKEND_URL;$priorCache=$env:PROJECT_VITE_CACHE_DIR
    try{$env:ADMIN_DEV_BACKEND_URL='http://127.0.0.1:18095';$env:PROJECT_VITE_CACHE_DIR=Join-Path $runtime 'vite-cache';Start-OrReuse 'paicli-frontend' $NodePath @((Join-Path $front 'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','15176','--strictPort') $front 15176}
    finally{$env:ADMIN_DEV_BACKEND_URL=$priorBackend;$env:PROJECT_VITE_CACHE_DIR=$priorCache}
    $frontReady=$false
    for($i=0;$i -lt 40;$i++){try{$r=Invoke-WebRequest 'http://127.0.0.1:15176/dev-api/captchaImage' -TimeoutSec 2;if($r.StatusCode -eq 200 -and ($r.Content|ConvertFrom-Json).code -eq 200){$frontReady=$true;break}}catch{};Start-Sleep -Milliseconds 500}
    if(-not $frontReady){throw '网页代理尚未就绪'}
    $manifest.StartupVerified=$true;Save-Manifest
    Write-Output 'PaiCLI 中心、本人 Runner 与网页已就绪：http://127.0.0.1:15176/paicli/workbench'
}finally{$lock.Dispose();$PlatformToken=$null}
