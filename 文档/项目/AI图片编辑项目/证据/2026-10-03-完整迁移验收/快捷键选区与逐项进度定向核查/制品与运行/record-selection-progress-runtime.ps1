param([ValidateSet('before','pre-stop','after')][string]$Phase)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path '.').Path
$privateRoot=Join-Path $taskRoot '.runtime/aipicture'
$output=Join-Path $privateRoot "selection-progress-runtime-$Phase.json"
if(Test-Path -LiteralPath $output){throw '禁止覆盖运行身份记录'}
$previousPath=Join-Path $privateRoot $(if($Phase -eq 'before'){'layer-delivery-runtime-after.json'}else{'selection-progress-runtime-before.json'})
$previous=Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json -DateKind String -Depth 30
$manifest=Get-Content -LiteralPath (Join-Path $privateRoot 'processes.json') -Raw | ConvertFrom-Json -DateKind String -Depth 20
$rows=@()
foreach($old in $previous.processes){
 if($Phase -eq 'after' -and $old.role -notlike 'protected-*'){if(Get-Process -Id $old.pid -ErrorAction SilentlyContinue){throw "旧本项目服务仍存在: $($old.pid)"};continue}
 $current=Get-CimInstance Win32_Process -Filter "ProcessId=$($old.pid)"
 if(-not $current -or $current.ParentProcessId -ne $old.parentPid -or $current.ExecutablePath -cne $old.executablePath -or $current.CommandLine -cne $old.commandLine -or $current.CreationDate.ToUniversalTime().Ticks -ne [DateTimeOffset]::Parse($old.creationDateUtc).UtcTicks){throw "运行身份变化: $($old.pid)"}
 $rows+=[ordered]@{role=$old.role;pid=$current.ProcessId;parentPid=$current.ParentProcessId;name=$current.Name;executablePath=$current.ExecutablePath;commandLine=$current.CommandLine;creationDateUtc=$current.CreationDate.ToUniversalTime().ToString('o');unchanged=$true}
}
if($Phase -in @('before','after')){
 foreach($entry in $manifest.processes){
  $parent=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)"
  if(-not $parent){throw '登记服务缺失'}
  if($entry.name -eq 'java'){
   if($parent.Name -ne 'java.exe' -or $parent.CommandLine -notmatch ('-jar\s+"?'+[regex]::Escape($entry.artifact)+'"?(?:\s|$)')){throw 'Java命令不符'}
   $serviceRows=@($parent)
  }elseif($entry.name -in @('python-serve','python-worker')){
   $kind=$entry.name.Substring(7)
   if([IO.Path]::GetFullPath($parent.ExecutablePath) -cne [IO.Path]::GetFullPath($entry.artifact) -or $parent.CommandLine -notmatch "-m\s+aipicture_runtime\s+$kind(?:\s|$)"){throw 'Python父身份不符'}
   $cfg=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($entry.artifact))) 'pyvenv.cfg'
   $baseHome=Get-Content -LiteralPath $cfg | Where-Object {$_ -match '^home\s*=\s*(.+)$'} | ForEach-Object {$Matches[1].Trim()} | Select-Object -First 1
   $basePython=[IO.Path]::GetFullPath((Join-Path $baseHome 'python.exe'))
   $children=@(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($parent.ProcessId)")
   $actual=@($children | Where-Object {$_.CommandLine -match "-m\s+aipicture_runtime\s+$kind(?:\s|$)"})
   if($actual.Count -ne 1 -or [IO.Path]::GetFullPath($actual[0].ExecutablePath) -cne $basePython){throw 'Python基础解释器子身份不符'}
   $console=@($children | Where-Object Name -eq 'conhost.exe')
   foreach($hostProcess in $console){if([IO.Path]::GetFullPath($hostProcess.ExecutablePath) -ine 'C:\Windows\system32\conhost.exe'){throw '控制台路径不符'}}
   $serviceRows=@($parent)+$actual+$console
  }else{throw '清单存在未知类型'}
  foreach($current in $serviceRows){
   if(@($rows | Where-Object pid -eq $current.ProcessId).Count){continue}
   $role=if($current.Name -eq 'conhost.exe'){'console-host-only'}elseif($current.ProcessId -eq $parent.ProcessId){$entry.name}else{"$($entry.name)-actual"}
   $rows+=[ordered]@{role=$role;pid=$current.ProcessId;parentPid=$current.ParentProcessId;name=$current.Name;executablePath=$current.ExecutablePath;commandLine=$current.CommandLine;creationDateUtc=$current.CreationDate.ToUniversalTime().ToString('o');unchanged=($Phase -eq 'before')}
  }
 }
}
$ports=@(Get-NetTCPConnection -State Listen -LocalPort 8415,7405,14385,8416 | Select-Object LocalAddress,LocalPort,OwningProcess)
foreach($oldPort in $previous.ports){if($Phase -eq 'after' -and $oldPort.LocalPort -in @(8415,7405)){continue};if(@($ports | Where-Object {$_.LocalPort -eq $oldPort.LocalPort -and $_.OwningProcess -eq $oldPort.OwningProcess}).Count -ne 1){throw '受保护监听变化'}}
foreach($port in @(8415,7405)){
 $role=if($port -eq 8415){'java'}else{'python-serve-actual'}
 $owner=@($rows | Where-Object role -eq $role)
 if($owner.Count -ne 1 -or @($ports | Where-Object {$_.LocalPort -eq $port -and $_.OwningProcess -eq $owner[0].pid}).Count -ne 1){throw '本项目服务监听归属不符'}
}
$hashes=[ordered]@{}
foreach($relative in @('.env','.runtime/aipicture/service.secret','.runtime/aipicture/vision/runtime.env')){$hashes[$relative]=(Get-FileHash -LiteralPath (Join-Path $taskRoot $relative) -Algorithm SHA256).Hash.ToLowerInvariant();if($hashes[$relative] -ne $previous.configurationSha256.$relative){throw '原配置或凭据发生变化'}}
$javaEntry=@($manifest.processes | Where-Object name -eq 'java')[0]
$javaHash=(Get-FileHash -LiteralPath $javaEntry.artifact -Algorithm SHA256).Hash.ToLowerInvariant()
$expected=if($Phase -eq 'after'){(Get-Content -LiteralPath (Join-Path $privateRoot 'selection-progress-final-artifacts.json') -Raw | ConvertFrom-Json -Depth 35).java.total_sha256}else{$previous.javaSha256}
if($javaHash -ne $expected -or $javaEntry.sha256.ToLowerInvariant() -ne $javaHash){throw 'Java制品不符'}
if($Phase -eq 'pre-stop' -and (ConvertTo-Json $manifest -Compress -Depth 20) -cne (ConvertTo-Json $previous.manifest -Compress -Depth 20)){throw '停止前清单变化'}
foreach($property in $previous.manifest.PSObject.Properties){if($property.Name -in @('processes','javaStartedAt','javaStoppedAt','javaRuntimeConfigPath','pythonStartedAt','pythonStoppedAt','pythonRuntimeConfigPath')){continue};if((ConvertTo-Json $manifest.($property.Name) -Compress) -cne (ConvertTo-Json $property.Value -Compress)){throw "原清单字段变化: $($property.Name)"}}
$record=[ordered]@{checkedAt=[DateTime]::UtcNow.ToString('o');phase=$Phase;passed=$true;processes=$rows;ports=$ports;manifest=$manifest;javaSha256=$javaHash;configurationSha256=$hashes;protectedProcesses=@($rows | Where-Object role -like 'protected-*').Count;scope='Java及两类Python同步；前端与技术宿主、原配置、账号和数据库保持；无业务写入或模型推理';pythonServeActualPid=@($rows | Where-Object role -eq 'python-serve-actual')[0].pid;pythonWorkerActualPid=@($rows | Where-Object role -eq 'python-worker-actual')[0].pid;historicalWorkerFieldsNote='worker*时间字段保留旧WorkerOnly历史，本轮实际进程身份以此CIM记录和pythonStartedAt为准'}
$record | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $output -Encoding utf8NoBOM
[ordered]@{phase=$Phase;passed=$true;processes=$rows.Count;protectedProcesses=$record.protectedProcesses;javaSha256=$javaHash;pythonServeActualPid=$record.pythonServeActualPid;pythonWorkerActualPid=$record.pythonWorkerActualPid} | ConvertTo-Json
