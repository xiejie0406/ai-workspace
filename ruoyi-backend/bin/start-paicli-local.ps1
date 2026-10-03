[CmdletBinding()]
param(
  [ValidateSet('inspect','prepare','start')][string]$Mode='inspect',
  [string]$BackendJarPath,
  [string]$RuntimeDirectory,
  [switch]$ServicesOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$userProfile=[Environment]::GetFolderPath('UserProfile')
$settings=@{}
Get-Content -LiteralPath (Join-Path $workspaceRoot '.env') | ForEach-Object {
  if($_ -match '^((?:RUOYI_DB_[A-Z0-9_]+|RUOYI_MANAGED_SECRET_[A-Z0-9_]+))=(.*)$'){
    $name=$Matches[1];$value=$Matches[2].Trim().Trim('"').Trim("'")
    if($value){$settings[$name]=$value}
  }
}
foreach($name in @('RUOYI_DB_URL','RUOYI_DB_USERNAME','RUOYI_DB_PASSWORD','RUOYI_MANAGED_SECRET_MASTER_KEY_ID','RUOYI_MANAGED_SECRET_MASTER_KEY_BASE64')){
  if(-not $settings.ContainsKey($name)){throw '原数据库或托管密钥自举配置缺失，未启动。'}
}
$databaseUri=[Uri]($settings['RUOYI_DB_URL'] -replace '^jdbc:','')
if($databaseUri.Host -notin @('localhost','127.0.0.1') -or $databaseUri.Port -ne 3306 -or $databaseUri.AbsolutePath -ne '/ry-vue'){
  throw '本机PaiCLI通道必须沿用已核实localhost:3306/ry-vue，未启动。'
}
function Invoke-MetadataQuery([string]$Sql){
  $start=[Diagnostics.ProcessStartInfo]::new()
  $start.FileName=(Get-Command mysql.exe -ErrorAction Stop).Source
  $start.UseShellExecute=$false;$start.CreateNoWindow=$true
  $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
  foreach($argument in @('--protocol=TCP',('--host='+$databaseUri.Host),'--port=3306',('--user='+$settings['RUOYI_DB_USERNAME']),'--database=ry-vue','--batch','--skip-column-names',('--execute='+$Sql))){$start.ArgumentList.Add($argument)}
  $start.Environment['MYSQL_PWD']=$settings['RUOYI_DB_PASSWORD']
  $child=[Diagnostics.Process]::new();$child.StartInfo=$start
  try{
    [void]$child.Start();$out=$child.StandardOutput.ReadToEndAsync();$err=$child.StandardError.ReadToEndAsync()
    if(-not $child.WaitForExit(15000)){$child.Kill($true);throw '本机通道元数据核对超时，未启动。'}
    $output=$out.GetAwaiter().GetResult();[void]$err.GetAwaiter().GetResult()
    if($child.ExitCode -ne 0){throw '本机通道元数据核对失败，未启动；错误细节未输出。'}
    return $output.Trim()
  }finally{$child.Dispose()}
}
$metadata=Invoke-MetadataQuery "SELECT DATABASE(); SELECT MAX(CAST(version AS UNSIGNED)) FROM pc_flyway_schema_history WHERE success=1; SELECT COUNT(*) FROM sys_managed_secret WHERE secret_kind='PLATFORM' AND secret_alias='platform.ruoyi.redis' AND status='ACTIVE'; SELECT COUNT(*) FROM sys_managed_secret WHERE secret_kind='PLATFORM' AND secret_alias='platform.paicli.control-key' AND project_code='paicli' AND status='ACTIVE';"
$values=@($metadata -split '\r?\n')
$latestVersion=(Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-paicli/paicli-api/src/main/resources/db/paicli-migration') -Filter 'V*__*.sql' | ForEach-Object { if($_.Name -match '^V(\d+)__'){[int]$Matches[1]} } | Measure-Object -Maximum).Maximum
if($values.Count -ne 4 -or $values[0] -ne 'ry-vue' -or [int]$values[1] -ne $latestVersion){throw '实际数据库迁移版本未与当前脚本一致，未启动。'}
# 当前平台没有启用Redis托管口令，启动独立回环缓存；若后续启用则停止，不能擅用空口令。
if($values[2] -ne '0'){throw '平台已启用Redis托管口令，须先接入该口令的私有启动配置，未启动。'}
if($values[3] -ne '1'){throw 'PaiCLI固定控制用途未就绪，未启动。'}
$ports=@(18095,16395,15176)
$listeners=@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports } | Select-Object LocalAddress,LocalPort,OwningProcess)
if($Mode -eq 'inspect'){
  [pscustomobject]@{Database='localhost:3306/ry-vue';MigrationVersion=$latestVersion;ManagedRedisPasswordActive=$false;ControlKeyActive=$true;Listeners=$listeners} | ConvertTo-Json -Depth 4
  exit 0
}
if($listeners.Count -gt 0){throw 'PaiCLI目标端口已有进程；先核对本通道既有PID与不可变包，不自动停止任何进程。'}
if(-not $RuntimeDirectory){$RuntimeDirectory=Join-Path $userProfile ('paicli-local-'+[DateTime]::Now.ToString('yyyyMMddTHHmmss')+'-'+[Guid]::NewGuid().ToString('N'))}
$RuntimeDirectory=[IO.Path]::GetFullPath($RuntimeDirectory)
if([IO.Path]::GetDirectoryName($RuntimeDirectory) -ne $userProfile -or [IO.Path]::GetFileName($RuntimeDirectory) -notmatch '^paicli-local-\d{8}T\d{6}-[a-f0-9]{32}$'){
  throw '运行目录必须是当前用户目录下的专用paicli-local生成目录。'
}
$currentSid=[Security.Principal.WindowsIdentity]::GetCurrent().User
function New-PrivateDirectory([string]$Path){
  if(Test-Path -LiteralPath $Path){
    $item=Get-Item -LiteralPath $Path
    if(-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw '运行目录类型或路径跳转不符。'}
    $existingAcl=Get-Acl -LiteralPath $Path
    if($existingAcl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $currentSid.Value){throw '运行目录不属于当前用户。'}
    foreach($entry in $existingAcl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){
      if($entry.AccessControlType -eq 'Allow' -and $entry.IdentityReference.Value -ne $currentSid.Value){throw '既有运行目录存在非所有者授权，未修改权限。'}
    }
    return
  }
  [void][IO.Directory]::CreateDirectory($Path)
  $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetOwner($currentSid);$acl.SetAccessRuleProtection($true,$false)
  $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($currentSid,[Security.AccessControl.FileSystemRights]::FullControl,[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
  Set-Acl -LiteralPath $Path -AclObject $acl
}
New-PrivateDirectory $RuntimeDirectory
$existingManifest=Join-Path $RuntimeDirectory 'processes.json'
if(Test-Path -LiteralPath $existingManifest){
  $previous=Get-Content -LiteralPath $existingManifest -Raw | ConvertFrom-Json
  foreach($entry in @($previous.Processes)){
    if(Get-CimInstance Win32_Process -Filter ('ProcessId='+$entry.Pid) -ErrorAction SilentlyContinue){throw '运行清单中的PID仍存在，须先核对既有进程，未覆盖清单或启动第二份。'}
  }
  Copy-Item -LiteralPath $existingManifest -Destination (Join-Path $RuntimeDirectory ('processes.before-'+[DateTime]::Now.ToString('yyyyMMddTHHmmssfff')+'.json'))
}
foreach($directory in @('redis','profile','logs','vite-cache')){New-PrivateDirectory (Join-Path $RuntimeDirectory $directory)}
$logbackPath=Join-Path $RuntimeDirectory 'logback.xml'
$logback=[IO.File]::ReadAllText((Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-admin/src/main/resources/logback.xml'))
$logback=$logback.Replace('value="/home/ruoyi/logs"',('value="'+($RuntimeDirectory.Replace('\','/')+'/logs')+'"'))
[IO.File]::WriteAllText($logbackPath,$logback,[Text.UTF8Encoding]::new($false))
$manifest=[ordered]@{Time=[DateTimeOffset]::Now.ToString('o');RuntimeDirectory=$RuntimeDirectory;Database='localhost:3306/ry-vue';MigrationVersion=$latestVersion;Ports=@{Backend=18095;Redis=16395;Frontend=15176};Jar=$null;JarSHA256=$null;ApiSHA256=$null;Processes=@();StartupVerified=$false}
$manifestPath=Join-Path $RuntimeDirectory 'processes.json'
function Save-Manifest{
  $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8
  $pointer=Join-Path $workspaceRoot '.runtime/paicli';[void][IO.Directory]::CreateDirectory($pointer)
  [IO.File]::WriteAllText((Join-Path $pointer 'current-runtime.txt'),$RuntimeDirectory,[Text.UTF8Encoding]::new($false))
}
Save-Manifest
if($Mode -eq 'prepare'){Write-Output ('PREPARED '+$RuntimeDirectory+'；未启动进程。');exit 0}
if(-not $BackendJarPath){throw '启动须显式指定已构建主服务包-BackendJarPath；准备目录已保留。'}
$BackendJarPath=[IO.Path]::GetFullPath($BackendJarPath)
$apiJar=Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-paicli/paicli-api/target/paicli-api-3.9.2.jar'
foreach($path in @($BackendJarPath,$apiJar)){if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw '实际主服务包或PaiCLI中心包缺失，未启动。'}}
$apiHash=(Get-FileHash -LiteralPath $apiJar -Algorithm SHA256).Hash
$apiSource=Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-paicli/paicli-api/src/main'
if(Get-ChildItem -LiteralPath $apiSource -Recurse -File | Where-Object { $_.LastWriteTimeUtc -gt (Get-Item -LiteralPath $apiJar).LastWriteTimeUtc }){
  throw '中心源码或资源晚于当前中心包，须重新完成本批构建，未启动。'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apiZip=[IO.Compression.ZipFile]::OpenRead($apiJar)
try{
  foreach($file in Get-ChildItem -LiteralPath (Join-Path $apiSource 'resources/db/paicli-migration') -Filter 'V*__*.sql'){
    $entry=$apiZip.GetEntry('db/paicli-migration/'+$file.Name)
    if(-not $entry){throw '中心包缺少当前冻结迁移资源，未启动。'}
    $stream=$entry.Open();$digest=[Security.Cryptography.SHA256]::Create()
    try{$resourceHash=[Convert]::ToHexString($digest.ComputeHash($stream))}finally{$stream.Dispose();$digest.Dispose()}
    if($resourceHash -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash){throw '中心包与冻结迁移资源摘要不符，未启动。'}
  }
}finally{$apiZip.Dispose()}
$zip=[IO.Compression.ZipFile]::OpenRead($BackendJarPath)
try{
  $entries=@($zip.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/paicli-api-[^/]+\.jar$' })
  if($entries.Count -ne 1){throw '主服务包没有唯一PaiCLI中心依赖，未启动。'}
  $stream=$entries[0].Open();$digest=[Security.Cryptography.SHA256]::Create()
  try{$embeddedHash=[Convert]::ToHexString($digest.ComputeHash($stream))}finally{$stream.Dispose();$digest.Dispose()}
  if($embeddedHash -ne $apiHash){throw '主服务内PaiCLI包与本批实际中心包摘要不同，未启动。'}
}finally{$zip.Dispose()}
$jarHash=(Get-FileHash -LiteralPath $BackendJarPath -Algorithm SHA256).Hash
$immutableJar=Join-Path $RuntimeDirectory ('ruoyi-admin-'+$jarHash.ToLowerInvariant()+'.jar')
if(-not(Test-Path -LiteralPath $immutableJar)){Copy-Item -LiteralPath $BackendJarPath -Destination $immutableJar}
if((Get-FileHash -LiteralPath $immutableJar -Algorithm SHA256).Hash -ne $jarHash){throw '运行副本摘要不符，未启动。'}
$manifest.Jar=$immutableJar;$manifest.JarSHA256=$jarHash;$manifest.ApiSHA256=$apiHash;Save-Manifest
& (Join-Path $PSScriptRoot 'paicli-control-key.ps1') -Mode check | Out-Null
function Start-Owned([string]$Name,[string]$Program,[string[]]$Arguments,[string]$Directory,[int]$Port){
  if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue){throw '目标端口已被占用，未启动后续进程。'}
  $escaped=@($Arguments | ForEach-Object { if($_.Contains('"')){throw '启动参数包含不支持的引号。'};if($_ -match '\s'){'"'+$_+'"'}else{$_} })
  $stamp=[DateTime]::Now.ToString('yyyyMMddTHHmmssfff')
  $out=Join-Path $RuntimeDirectory ('logs/'+$Name+'-'+$stamp+'.out.log');$err=Join-Path $RuntimeDirectory ('logs/'+$Name+'-'+$stamp+'.err.log')
  $process=Start-Process -FilePath $Program -ArgumentList $escaped -WorkingDirectory $Directory -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
  $identity=Get-CimInstance Win32_Process -Filter ('ProcessId='+$process.Id)
  if(-not $identity){throw '启动进程身份尚无法核对；本次日志保留，不自动重启。'}
  $manifest.Processes+=@([pscustomobject]@{Name=$Name;Pid=$process.Id;StartedUtc=$identity.CreationDate.ToUniversalTime().ToString('o');CommandLine=$identity.CommandLine;Port=$Port;Stdout=$out;Stderr=$err})
  Save-Manifest;Write-Output ('STARTED '+$Name+' PID='+$process.Id+' port='+$Port)
}
try{
  foreach($setting in $settings.GetEnumerator()){[Environment]::SetEnvironmentVariable($setting.Key,$setting.Value,'Process')}
  $redis='C:/Program Files/Redis/redis-server.exe'
  Start-Owned 'paicli-redis' $redis @('--port','16395','--bind','127.0.0.1','--protected-mode','yes','--appendonly','no','--dir',(Join-Path $RuntimeDirectory 'redis')) $RuntimeDirectory 16395
  $java=(Get-Command java.exe -ErrorAction Stop).Source
  $arguments=@('-Xms128m','-Xmx768m','-jar',$immutableJar,'--spring.profiles.active=druid,local','--server.address=127.0.0.1','--server.port=18095','--spring.quartz.auto-startup=false','--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=16395','--interview.enabled=false','--fashion.enabled=false','--aden.enabled=false','--aps.enabled=false','--paiflow.enabled=false','--aipicture.enabled=false','--aippt.enabled=false','--paismart.enabled=false','--paicli.enabled=true','--paicli.expected-database=ry-vue','--paicli.formal-evaluation-paused=true',('--ruoyi.profile='+ (Join-Path $RuntimeDirectory 'profile')),('--logging.config=file:'+ $logbackPath.Replace('\','/')),'--spring.datasource.druid.initial-size=1','--spring.datasource.druid.min-idle=1','--spring.datasource.druid.max-active=8','--spring.servlet.multipart.max-file-size=50MB','--spring.servlet.multipart.max-request-size=55MB')
  Start-Owned 'paicli-java' $java $arguments $RuntimeDirectory 18095
  if(-not $ServicesOnly){
    $frontRoot=Join-Path $workspaceRoot 'frontend/admin-web'
    $env:ADMIN_DEV_BACKEND_URL='http://127.0.0.1:18095';$env:PROJECT_VITE_CACHE_DIR=Join-Path $RuntimeDirectory 'vite-cache'
    Start-Owned 'paicli-frontend' (Get-Command node.exe -ErrorAction Stop).Source @((Join-Path $frontRoot 'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','15176','--strictPort') $frontRoot 15176
  }
}finally{
  foreach($name in $settings.Keys){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
  $env:ADMIN_DEV_BACKEND_URL=$null;$env:PROJECT_VITE_CACHE_DIR=$null
}
Write-Output ('启动清单：'+$manifestPath+'；尚须检查实际HTTP和运行日志，未记业务通过。')
