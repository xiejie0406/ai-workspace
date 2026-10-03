[CmdletBinding()]
param([Parameter(Mandatory)][string]$ExpectedNewApiSHA256)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../..'))
$runtimeRoot=[IO.File]::ReadAllText((Join-Path $workspaceRoot '.runtime/paicli/current-runtime.txt')).Trim()
$runtimeRoot=[IO.Path]::GetFullPath($runtimeRoot)
$userRoot=[Environment]::GetFolderPath('UserProfile')
if([IO.Path]::GetDirectoryName($runtimeRoot) -ne $userRoot -or [IO.Path]::GetFileName($runtimeRoot) -notmatch '^paicli-local-\d{8}T\d{6}-[a-f0-9]{32}$'){throw '专用运行目录边界不符。'}
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
foreach($path in @($runtimeRoot,(Join-Path $runtimeRoot 'logs'))){
  $item=Get-Item -LiteralPath $path
  if(-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw '私有目录类型或重解析点不符。'}
  $acl=Get-Acl -LiteralPath $path
  if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value){throw '运行目录所有者不符。'}
  foreach($entry in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){if($entry.AccessControlType -eq 'Allow' -and $entry.IdentityReference.Value -ne $sid.Value){throw '私有目录有其他主体授权，未修改ACL。'}}
}
$proofPath=Join-Path $PSScriptRoot '本机Skill修复包更新前置核对.json'
if(Test-Path -LiteralPath $proofPath){throw '此轮更新已留记录，停止重复执行或覆盖。'}
$manifestPath=Join-Path $runtimeRoot 'processes.json'
$manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$oldJar=[IO.Path]::GetFullPath($manifest.Jar)
if([IO.Path]::GetDirectoryName($oldJar) -ne $runtimeRoot -or ((Get-Item -LiteralPath $oldJar).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw '旧不可变包路径不符。'}
if((Get-FileHash -LiteralPath $oldJar -Algorithm SHA256).Hash -ne $manifest.JarSHA256){throw '旧运行包摘要变化。'}
$newJar=Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar'
$apiJar=Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-paicli/paicli-api/target/paicli-api-3.9.2.jar'
$apiHash=(Get-FileHash -LiteralPath $apiJar -Algorithm SHA256).Hash
if($ExpectedNewApiSHA256 -notmatch '^[a-fA-F0-9]{64}$' -or $apiHash -ne $ExpectedNewApiSHA256){throw '新API包不是已检查的冻结版本。'}
if(Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-paicli/paicli-api/src/main') -File -Recurse | Where-Object {$_.LastWriteTimeUtc -gt (Get-Item -LiteralPath $apiJar).LastWriteTimeUtc}){throw 'API源码晚于当前包。'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($newJar)
try{
  $entries=@($zip.Entries | Where-Object {$_.FullName -match '^BOOT-INF/lib/paicli-api-[^/]+\.jar$'})
  if($entries.Count -ne 1){throw '主服务缺唯一API依赖。'}
  $stream=$entries[0].Open();$hasher=[Security.Cryptography.SHA256]::Create()
  try{$embedded=[Convert]::ToHexString($hasher.ComputeHash($stream))}finally{$stream.Dispose();$hasher.Dispose()}
  if($embedded -ne $apiHash){throw '主服务嵌入API与检查包不符。'}
}finally{$zip.Dispose()}
$frozen=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'paicli-migration-20261003T122259-406bb44af6604a5fa38a3b42e2036c8e.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if(@($frozen.Migrations).Count -ne 5){throw '迁移冻结清单不完整。'}
$apiZip=[IO.Compression.ZipFile]::OpenRead($apiJar)
try{
  foreach($migration in $frozen.Migrations){
    $file=Join-Path $workspaceRoot ('ruoyi-backend/ruoyi-paicli/paicli-api/src/main/resources/db/paicli-migration/'+$migration.Name)
    if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $migration.SHA256){throw '已应用迁移源发生变化。'}
    $entry=$apiZip.GetEntry('db/paicli-migration/'+$migration.Name)
    if(-not $entry){throw 'API缺已冻结迁移资源。'}
    $stream=$entry.Open();$hasher=[Security.Cryptography.SHA256]::Create()
    try{$hash=[Convert]::ToHexString($hasher.ComputeHash($stream))}finally{$stream.Dispose();$hasher.Dispose()}
    if($hash -ne $migration.SHA256){throw 'API迁移资源与已应用冻结版本不符。'}
  }
}finally{$apiZip.Dispose()}
$owned=@($manifest.Processes)
if($owned.Count -ne 3 -or (@($owned.Port | Sort-Object) -join ',') -ne '15176,16395,18095'){throw '原通道进程数量或端口不符。'}
function Started-Ticks($value){
  if($value -is [DateTimeOffset]){return $value.UtcDateTime.Ticks}
  if($value -is [DateTime]){return $value.ToUniversalTime().Ticks}
  return [DateTimeOffset]::Parse([string]$value,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::RoundtripKind).UtcDateTime.Ticks
}
function Assert-Identity($entry){
  $actual=Get-CimInstance Win32_Process -Filter ('ProcessId='+$entry.Pid) -ErrorAction Stop
  if(-not $actual -or $actual.CreationDate.ToUniversalTime().Ticks -ne (Started-Ticks $entry.StartedUtc) -or $actual.CommandLine -cne $entry.CommandLine){throw 'PID身份变化，停止通道更新。'}
  return $actual
}
$processProof=@()
foreach($entry in $owned){
  [void](Assert-Identity $entry)
  $listeners=@(Get-NetTCPConnection -LocalPort $entry.Port -State Listen -ErrorAction Stop)
  if($listeners.Count -ne 1 -or $listeners[0].OwningProcess -ne $entry.Pid -or $listeners[0].LocalAddress -ne '127.0.0.1'){throw '目标监听与原身份不符。'}
  $processProof+=[pscustomobject]@{Name=$entry.Name;Pid=$entry.Pid;Port=$entry.Port;IdentityVerified=$true;LoopbackListenerVerified=$true}
}
$otherPorts=@(15173,15174,15175,16391,16392,16393,16394,18092,18093,18094)
$others=@(Get-NetTCPConnection -State Listen | Where-Object {$_.LocalPort -in $otherPorts} | Sort-Object LocalPort,LocalAddress,OwningProcess | Select-Object LocalAddress,LocalPort,OwningProcess)
$settings=@{}
Get-Content -LiteralPath (Join-Path $workspaceRoot '.env') | ForEach-Object {if($_ -match '^(RUOYI_DB_[A-Z0-9_]+)=(.*)$'){$settings[$Matches[1]]=$Matches[2].Trim().Trim('"').Trim("'")}}
$databaseUri=[Uri]($settings['RUOYI_DB_URL'] -replace '^jdbc:','')
if($databaseUri.Host -notin @('localhost','127.0.0.1') -or $databaseUri.Port -ne 3306 -or $databaseUri.AbsolutePath -ne '/ry-vue'){throw '数据库不属于已授权原库。'}
$start=[Diagnostics.ProcessStartInfo]::new();$start.FileName='C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe';$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
foreach($argument in @('--protocol=TCP',('--host='+$databaseUri.Host),'--port=3306',('--user='+$settings['RUOYI_DB_USERNAME']),'--database=ry-vue','--batch','--skip-column-names','--execute=SELECT COUNT(*) FROM pc_task;')){$start.ArgumentList.Add($argument)}
$start.Environment['MYSQL_PWD']=$settings['RUOYI_DB_PASSWORD']
$child=[Diagnostics.Process]::new();$child.StartInfo=$start
try{[void]$child.Start();$out=$child.StandardOutput.ReadToEndAsync();$err=$child.StandardError.ReadToEndAsync();if(-not $child.WaitForExit(15000)){$child.Kill($true);throw '任务数核对超时。'};$count=$out.GetAwaiter().GetResult().Trim();[void]$err.GetAwaiter().GetResult();if($child.ExitCode -ne 0 -or $count -ne '0'){throw '任务数不是0或原库核对失败，未停止任何进程。'}}finally{$child.Dispose();$settings.Clear()}
$newHash=(Get-FileHash -LiteralPath $newJar -Algorithm SHA256).Hash
$immutable=Join-Path $runtimeRoot ('ruoyi-admin-'+$newHash.ToLowerInvariant()+'.jar')
if(Test-Path -LiteralPath $immutable){if((Get-FileHash -LiteralPath $immutable -Algorithm SHA256).Hash -ne $newHash){throw '已有新不可变副本摘要不符。'}}else{Copy-Item -LiteralPath $newJar -Destination $immutable}
$backup=Join-Path $runtimeRoot ('processes.pre-skill-update-'+[DateTime]::Now.ToString('yyyyMMddTHHmmssfff')+'.json')
Copy-Item -LiteralPath $manifestPath -Destination $backup
$proof=[ordered]@{Time=[DateTimeOffset]::Now.ToString('o');Database='localhost:3306/ry-vue';PaiCLITaskCount=0;DatabaseMutations=0;OldImmutableJarSHA256=$manifest.JarSHA256;NewImmutableJarSHA256=$newHash;ApiSHA256=$apiHash;FrozenMigrationCount=5;Processes=$processProof;OtherProjectListeners=$others;StoppedProcesses=0;StartupVerified=$false}
$proof | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $proofPath -Encoding UTF8
foreach($entry in $owned){
  [void](Assert-Identity $entry)
  $handle=Get-Process -Id $entry.Pid -ErrorAction Stop
  try{Stop-Process -Id $entry.Pid -ErrorAction Stop;if(-not $handle.WaitForExit(15000)){throw '目标进程未退出，停止后续更新。'}}finally{$handle.Dispose()}
  $deadline=[DateTime]::UtcNow.AddSeconds(5)
  do{
    $remaining=Get-CimInstance Win32_Process -Filter ('ProcessId='+$entry.Pid) -ErrorAction SilentlyContinue
    if(-not $remaining){break}
    if($remaining.CreationDate.ToUniversalTime().Ticks -ne (Started-Ticks $entry.StartedUtc) -or $remaining.CommandLine -cne $entry.CommandLine){throw '退出后PID已被复用，未触碰新进程。'}
    Start-Sleep -Milliseconds 250
  }while([DateTime]::UtcNow -lt $deadline)
  if($remaining){throw ('退出后PID仍存在：'+$entry.Pid+'；停止后续更新，须重新核对。')}
  $proof.StoppedProcesses++
  $proof | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $proofPath -Encoding UTF8
}
& (Join-Path $workspaceRoot 'ruoyi-backend/bin/start-paicli-local.ps1') -Mode start -RuntimeDirectory $runtimeRoot -BackendJarPath $immutable
Write-Output '本轮三个目标进程已启动；尚待HTTP、进程身份及包摘要核对，未记业务通过。'
