# 本机部署入口。普通启动只使用已绑定设备缓存，不保存若依密码或 JWT。
param(
    [ValidateSet('Initialize','Start','Supervise','Stop','Status')][string]$Action='Start',
    [string]$RuntimeDirectory=(Join-Path $env:LOCALAPPDATA 'PaiCLI/launcher'),
    [string]$CenterUrl='http://127.0.0.1:18095',
    [string]$PersonalRoot=(Join-Path $env:USERPROFILE 'PaiCLI/data'),
    [string]$CliJarPath,
    [string]$JavaPath,
    [Security.SecureString]$PlatformToken,
    [switch]$ReadPlatformTokenFromStdin,
    [int]$ReadyTimeoutSeconds=60
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion -lt [Version]'7.5'){throw '请使用 PowerShell 7.5 或以上运行本机启动入口'}
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
function Ensure-PrivateDirectory([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path)
    $cursor=$full
    while($cursor){if(Test-Path -LiteralPath $cursor){if((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint){throw '运行目录不能经过重解析点'}};$parent=[IO.Directory]::GetParent($cursor);$cursor=if($parent){$parent.FullName}else{$null}}
    if(-not(Test-Path -LiteralPath $full)){[IO.Directory]::CreateDirectory($full)|Out-Null
        $acl=Get-Acl -LiteralPath $full
        $acl.SetAccessRuleProtection($true,$false);$acl.SetOwner($sid)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
        Set-Acl -LiteralPath $full -AclObject $acl
    }
    $item=Get-Item -LiteralPath $full
    if(-not $item.PSIsContainer){throw '运行路径必须是目录'}
    $acl=Get-Acl -LiteralPath $full
    if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value){throw '运行目录不属于当前 Windows 用户'}
    foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -ne $sid.Value){throw '运行目录必须仅允许当前 Windows 用户访问'}}
    return $full
}
function Require-PrivateFile([string]$Path){$item=Get-Item -LiteralPath $Path;if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $item.Length -gt 1048576){throw '状态文件类型或容量不符'};$acl=Get-Acl -LiteralPath $Path;if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value){throw '状态文件所有者不符'};foreach($entry in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){if($entry.AccessControlType -eq 'Allow' -and $entry.IdentityReference.Value -ne $sid.Value){throw '状态文件存在其他主体权限'}}}
function Read-Json([string]$Path){
    if(-not(Test-Path -LiteralPath $Path)){return $null}
    Require-PrivateFile $Path
    # 就绪状态由 Runner 原子替换；读取不应锁住 Windows 下的替换操作。
    $stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try{if($stream.Length -gt 1048576){throw '状态文件容量不符'};$reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8,$true);try{return $reader.ReadToEnd()|ConvertFrom-Json -AsHashtable -DateKind String}finally{$reader.Dispose()}}finally{$stream.Dispose()}
}
function Write-StopRequest([string]$Path){
    if(Test-Path -LiteralPath $Path){Require-PrivateFile $Path;return}
    $acl=[Security.AccessControl.FileSecurity]::new();$acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
    $rights=[Security.AccessControl.FileSystemRights]::FullControl -band (-bnot [Security.AccessControl.FileSystemRights]::ExecuteFile)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,$rights,'Allow'))
    $stream=[IO.FileSystemAclExtensions]::Create([IO.FileInfo]::new($Path),[IO.FileMode]::CreateNew,$rights,[IO.FileShare]::Read,4096,[IO.FileOptions]::None,$acl)
    try{$bytes=[Text.Encoding]::UTF8.GetBytes('stop');$stream.Write($bytes);$stream.Flush($true)}finally{$stream.Dispose()}
}
function Write-Json([string]$Path,$Value){if(Test-Path -LiteralPath $Path){Require-PrivateFile $Path};$tmp=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.tmp';try{[IO.File]::WriteAllText($tmp,($Value|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false));[IO.File]::Move($tmp,$Path,$true)}finally{if(Test-Path -LiteralPath $tmp){Remove-Item -LiteralPath $tmp}}}
function Get-BoundCache {
    $runtimeRoot=[IO.Path]::GetFullPath((Join-Path $config.PersonalRoot ('users/'+$config.OwnerUserId+'/runtime'))).ToLowerInvariant()
    $fingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($runtimeRoot))).ToLowerInvariant()
    $identity=($config.CenterUrl+"`n"+$fingerprint)|ConvertTo-Json -Compress
    $key=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($identity))).ToLowerInvariant()
    $saved=Read-Json (Join-Path $config.StateDirectory ('device-credentials/'+$key+'.json'))
    return $saved -and $saved.ContainsKey('deviceToken') -and -not [string]::IsNullOrWhiteSpace($saved.deviceToken)
}
function Is-Ready($Ready,[int]$RunnerPid) {
    if(-not $Ready){return $false}
    return $Ready.pid -eq $RunnerPid -and $Ready.ownerUserId -eq $config.OwnerUserId -and $Ready.runnerId -eq $config.RunnerId -and $Ready.terminalReady -and [DateTimeOffset]::Parse($Ready.checkedAt).AddSeconds(15) -gt [DateTimeOffset]::UtcNow -and [DateTimeOffset]::Parse($Ready.sessionExpiresAt) -gt [DateTimeOffset]::UtcNow
}
function Identity([int]$ProcessId){$p=Get-CimInstance Win32_Process -Filter ('ProcessId='+$ProcessId);if(-not $p){return $null};return @{Pid=$p.ProcessId;StartedUtc=$p.CreationDate.ToUniversalTime().ToString('o');CommandLine=$p.CommandLine;ExecutablePath=$p.ExecutablePath}}
function Is-SameProcess($Recorded){if(-not $Recorded){return $false};$p=Identity $Recorded.Pid;return $null -ne $p -and $p.StartedUtc -ceq $Recorded.StartedUtc -and $p.CommandLine -ceq $Recorded.CommandLine -and $p.ExecutablePath -ieq $Recorded.ExecutablePath}
function Quote-Argument([string]$Value){if($Value.Contains('"') -or $Value.Contains("`n") -or $Value.Contains("`r") -or $Value.EndsWith('\')){throw '启动参数含不支持的字符'};if($Value -match '\s'){return '"'+$Value+'"'};return $Value}
function Invoke-Center([string]$Path,[string]$Token,[string]$Method='GET',$Body=$null,[string]$Key=''){
    $headers=@{Authorization='Bearer '+$Token};if($Key){$headers['Idempotency-Key']=$Key}
    $args=@{Uri=$config.CenterUrl+$Path;Method=$Method;Headers=$headers;TimeoutSec=15;MaximumRedirection=0;ContentType='application/json'}
    if($null -ne $Body){$args.Body=$Body|ConvertTo-Json -Compress}
    try{$r=Invoke-RestMethod @args}catch{throw '本机初始化请求失败；请检查原登录状态、权限和中心连接（凭据未输出）'}
    if($r.code -ne 200){throw ('中心拒绝初始化：'+$r.msg)};return $r
}
$RuntimeDirectory=Ensure-PrivateDirectory $RuntimeDirectory
$configPath=Join-Path $RuntimeDirectory 'local-runner.json'
$processPath=Join-Path $RuntimeDirectory 'runner-process.json'
$stopPath=Join-Path $RuntimeDirectory 'supervisor-stop.request'
$bootstrapPath=Join-Path $RuntimeDirectory 'bootstrap-device.json'
$lock=$null
if($Action -ne 'Supervise'){
    try{$lock=[IO.File]::Open((Join-Path $RuntimeDirectory 'launcher.lock'),'OpenOrCreate','ReadWrite','None')}catch{throw '另一个启动或初始化操作正在执行，请等待后再试'}
}
try{
    $config=Read-Json $configPath
    if($Action -eq 'Initialize'){
        $uri=[Uri]$CenterUrl
        if($uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $uri.Port -ne 18095 -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo){throw '本机托管部署仅接受既定中心 http://127.0.0.1:18095'}
        if(-not $CliJarPath){throw '初始化需要指定与中心兼容的 CLI 分发包'}
        $candidate=[IO.Path]::GetFullPath($CliJarPath)
        if(-not(Test-Path -LiteralPath $candidate -PathType Leaf)){throw 'CLI 分发包不存在'}
        if(-not $JavaPath){$JavaPath=(Get-Command java -ErrorAction Stop).Source}
        $JavaPath=[IO.Path]::GetFullPath($JavaPath)
        if(-not(Test-Path -LiteralPath $JavaPath -PathType Leaf)){throw 'Java 执行文件不存在'}
        foreach($file in @($candidate,$JavaPath)){if((Get-Item -LiteralPath $file).Attributes -band [IO.FileAttributes]::ReparsePoint){throw '分发包和执行文件不能是重解析点'}}
        $PersonalRoot=[IO.Path]::GetFullPath($PersonalRoot)
        if($config -and ($config.CenterUrl -ne $CenterUrl.TrimEnd('/') -or $config.PersonalRoot -ne $PersonalRoot)){throw '已有部署的中心或个人根发生变化，需要先明确迁移，未重新绑定'}
        if($ReadPlatformTokenFromStdin){
            Write-Output '等待安装流程通过标准输入提供现有登录凭据（不回显）'
            if([Console]::IsInputRedirected){$rawToken=[Console]::ReadLine()}
            else{$buffer=[Text.StringBuilder]::new();do{$key=[Console]::ReadKey($true);if($key.Key -eq 'Enter'){break};if($key.Key -eq 'Backspace'){if($buffer.Length){$buffer.Length--}}elseif(-not [char]::IsControl($key.KeyChar)){[void]$buffer.Append($key.KeyChar)}}while($buffer.Length -le 4096);$rawToken=$buffer.ToString();$buffer.Clear()|Out-Null}
        }
        elseif($PlatformToken){$rawToken=[Net.NetworkCredential]::new('', $PlatformToken).Password}
        else{throw '首次准备需要安装流程提供经过认证的本人登录凭据；日常启动不需要此参数'}
        if(-not $rawToken -or $rawToken -notmatch '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$'){throw '安装流程未提供有效登录凭据'}
        if(-not $config){$config=@{CenterUrl=$CenterUrl.TrimEnd('/');PersonalRoot=$PersonalRoot;EnrollmentKey=[Guid]::NewGuid().ToString();WindowsSid=$sid.Value;Ready=$false}}
        $info=Invoke-Center '/getInfo' $rawToken
        $owner=[string]$info.user.userId
        if($owner -notmatch '^[1-9][0-9]*$'){throw '无法核对本人稳定账号'}
        if($config.ContainsKey('OwnerUserId') -and $config.OwnerUserId -ne $owner){throw '已有部署属于另一账号，未重新绑定'}
        $config.OwnerUserId=$owner
        $config.PersonalRoot=Ensure-PrivateDirectory $PersonalRoot
        $config.StateDirectory=Ensure-PrivateDirectory (Join-Path $PersonalRoot ('users/'+$owner+'/state'))
        $config.JavaPath=$JavaPath;$config.WindowsSid=$sid.Value
        $jarHash=(Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash
        $pinned=Join-Path $RuntimeDirectory ('paicli-cli-'+$jarHash.ToLowerInvariant()+'.jar')
        if(-not(Test-Path -LiteralPath $pinned)){Copy-Item -LiteralPath $candidate -Destination $pinned}
        if((Get-FileHash -LiteralPath $pinned).Hash -ne $jarHash){throw 'CLI 固定包摘要不符'}
        if((Read-Json $processPath) -and (Is-SameProcess (Read-Json $processPath).Supervisor)){throw '更新部署包前须先停止本人 Runner'}
        $config.CliJarPath=$pinned;$config.CliSHA256=$jarHash
        Write-Json $configPath $config
        $hasDevice=Get-BoundCache
        if(-not $hasDevice){
            $bootstrap=Read-Json $bootstrapPath
            $input=@{name='本机个人编码服务'};$requestKey=$config.EnrollmentKey
            if($config.ContainsKey('RunnerId') -and $bootstrap -and [DateTimeOffset]::Parse($bootstrap.ExpiresAt) -le [DateTimeOffset]::UtcNow){
                # 只有显式本人初始化才刷新同一待绑定设备，日常Start不会登记或改变授权。
                if(-not $config.ContainsKey('RefreshRequestedForExpiry') -or $config.RefreshRequestedForExpiry -ne $bootstrap.ExpiresAt -or -not $config.ContainsKey('RefreshRequestedAtUtc') -or [DateTimeOffset]::Parse($config.RefreshRequestedAtUtc).AddSeconds(300) -le [DateTimeOffset]::UtcNow){$config.RefreshEnrollmentKey=[Guid]::NewGuid().ToString();$config.RefreshRequestedForExpiry=$bootstrap.ExpiresAt;$config.RefreshRequestedAtUtc=[DateTimeOffset]::UtcNow.ToString('o');Write-Json $configPath $config}
                $input.runnerId=$config.RunnerId;$requestKey=$config.RefreshEnrollmentKey
            }
            $enrollment=Invoke-Center '/paicli/v1/runners/enroll' $rawToken 'POST' $input $requestKey
            if($config.ContainsKey('RunnerId') -and [string]$enrollment.data.runnerId -ne $config.RunnerId){throw '初始化恢复返回的设备身份发生变化，拒绝使用'}
            $config.RunnerId=[string]$enrollment.data.runnerId
            Write-Json $bootstrapPath @{DeviceToken=$enrollment.data.deviceToken;ExpiresAt=$enrollment.data.expiresAt}
            # 首次请求的成功响应在本机丢失且超过有效期时，先取得原设备身份再恢复它。
            if([DateTimeOffset]::Parse($enrollment.data.expiresAt) -le [DateTimeOffset]::UtcNow){
                $config.RefreshEnrollmentKey=[Guid]::NewGuid().ToString();$config.RefreshRequestedForExpiry=$enrollment.data.expiresAt;$config.RefreshRequestedAtUtc=[DateTimeOffset]::UtcNow.ToString('o');Write-Json $configPath $config
                $enrollment=Invoke-Center '/paicli/v1/runners/enroll' $rawToken 'POST' @{name='本机个人编码服务';runnerId=$config.RunnerId} $config.RefreshEnrollmentKey
                if([string]$enrollment.data.runnerId -ne $config.RunnerId -or [DateTimeOffset]::Parse($enrollment.data.expiresAt) -le [DateTimeOffset]::UtcNow){throw '原待绑定设备未返回有效首次码'}
                Write-Json $bootstrapPath @{DeviceToken=$enrollment.data.deviceToken;ExpiresAt=$enrollment.data.expiresAt}
            }
        }
        $rawToken=$null;$PlatformToken=$null
        Write-Json $configPath $config
        $Action='Start'
    }
    if(-not $config){throw '本机执行环境尚未初始化，请先由安装流程完成一次本人绑定'}
    if(-not $config.ContainsKey('RunnerId')){throw '首次准备尚未完成，请恢复同一次本人初始化'}
    if($config.WindowsSid -ne $sid.Value){throw '运行配置不属于当前 Windows 用户'}
    if($config.CenterUrl -ne 'http://127.0.0.1:18095'){throw '中心地址不在本机批准范围'}
    [void](Ensure-PrivateDirectory $config.PersonalRoot);[void](Ensure-PrivateDirectory $config.StateDirectory)
    $expectedCli=Join-Path $RuntimeDirectory ('paicli-cli-'+([string]$config.CliSHA256).ToLowerInvariant()+'.jar')
    if([IO.Path]::GetFullPath($config.CliJarPath) -ine [IO.Path]::GetFullPath($expectedCli)){throw 'CLI 引用必须是当前私有运行目录的固定摘要包'}
    foreach($file in @($config.CliJarPath,$config.JavaPath)){if(-not(Test-Path -LiteralPath $file -PathType Leaf) -or ((Get-Item -LiteralPath $file).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw '固定分发包和执行文件类型发生变化'}}
    if((Get-FileHash -LiteralPath $config.CliJarPath).Hash -ne $config.CliSHA256){throw 'CLI 固定包已改变，拒绝启动'}
    $readinessPath=Join-Path $config.StateDirectory 'runner-readiness.json'
    $runnerStopPath=Join-Path $config.StateDirectory 'runner-stop.request'
    if($Action -eq 'Status'){
        $record=Read-Json $processPath;if(-not $record){$record=@{Supervisor=$null;Runner=$null}};$ready=Read-Json $readinessPath
        @{Initialized=$true;RunnerId=$config.RunnerId;OwnerUserId=$config.OwnerUserId;SupervisorAlive=(Is-SameProcess $record.Supervisor);RunnerAlive=(Is-SameProcess $record.Runner);Readiness=$ready}|ConvertTo-Json -Depth 6
        return
    }
    if($Action -eq 'Stop'){
        $record=Read-Json $processPath
        if($record -and ((Is-SameProcess $record.Supervisor) -or (Is-SameProcess $record.Runner))){
            Write-StopRequest $stopPath;Write-StopRequest $runnerStopPath
            for($i=0;$i -lt 240 -and ((Is-SameProcess $record.Supervisor) -or (Is-SameProcess $record.Runner));$i++){Start-Sleep -Milliseconds 250}
            if(Is-SameProcess $record.Runner){throw 'Runner 尚未结束，保留身份记录，未强制结束其他进程'}
            if(Is-SameProcess $record.Supervisor){throw '监管进程尚未结束，保留身份记录'}
        }
        Write-Output '本人 Runner 已停止；个人文件、绑定及共享服务保留';return
    }
    if($Action -eq 'Supervise'){
        $supervisorLock=[IO.File]::Open((Join-Path $RuntimeDirectory 'supervisor.lock'),'OpenOrCreate','ReadWrite','None')
        try{
            $supervisor=Identity $PID
            $logs=Ensure-PrivateDirectory (Join-Path $RuntimeDirectory 'logs')
            $attempt=0;$retryWindow=[DateTimeOffset]::UtcNow
            while(-not(Test-Path -LiteralPath $stopPath)){
                if((Get-FileHash -LiteralPath $config.CliJarPath).Hash -ne $config.CliSHA256){throw 'CLI 固定包在恢复前发生变化'}
                if(Test-Path -LiteralPath $runnerStopPath){Remove-Item -LiteralPath $runnerStopPath}
                $stamp=Get-Date -Format 'yyyyMMddTHHmmssfff';$stdout=Join-Path $logs ('runner-'+$stamp+'.out.log');$stderr=Join-Path $logs ('runner-'+$stamp+'.err.log')
                $environment=@{PAICLI_CENTER_URL=$config.CenterUrl;PAICLI_PERSONAL_ROOT=$config.PersonalRoot;PAICLI_OWNER_USER_ID=$config.OwnerUserId;PAICLI_RUNNER_STATE=$config.StateDirectory;PAICLI_WEB_TERMINAL_ENABLED='true'}
                $bootstrap=Read-Json $bootstrapPath
                if(Get-BoundCache){$environment.PAICLI_RUNNER_TOKEN=$null}
                elseif($bootstrap){if([DateTimeOffset]::Parse($bootstrap.ExpiresAt) -le [DateTimeOffset]::UtcNow){throw '首次绑定码已过期，请重新核对初始化；未自动登记新设备'};$environment.PAICLI_RUNNER_TOKEN=$bootstrap.DeviceToken}
                else{$environment.PAICLI_RUNNER_TOKEN=$null}
                $prior=@{}
                try{
                    foreach($name in $environment.Keys){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$environment[$name],'Process')}
                    $arguments=@('-Xms64m','-Xmx512m','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-jar',$config.CliJarPath,'runner')|ForEach-Object {Quote-Argument $_}
                    $child=Start-Process -FilePath $config.JavaPath -ArgumentList $arguments -WorkingDirectory $RuntimeDirectory -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
                }finally{foreach($name in $environment.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')};$environment.Clear()}
                $runner=Identity $child.Id
                if(-not $runner){throw 'Runner 启动后未取得进程身份'}
                Write-Json $processPath @{Supervisor=$supervisor;Runner=$runner;Stdout=$stdout;Stderr=$stderr;State='STARTING';Attempt=$attempt;CheckedAt=[DateTimeOffset]::UtcNow.ToString('o')}
                $connectedAt=$null
                while(-not $child.HasExited){
                    if(Test-Path -LiteralPath $stopPath){Write-StopRequest $runnerStopPath}
                    $ready=Read-Json $readinessPath
                    if((Is-Ready $ready $child.Id) -and (Get-BoundCache)){
                        if(-not $connectedAt){$connectedAt=[DateTimeOffset]::UtcNow;Write-Json $processPath @{Supervisor=$supervisor;Runner=$runner;Stdout=$stdout;Stderr=$stderr;State='READY';Attempt=$attempt;CheckedAt=$connectedAt.ToString('o')}}
                        if($bootstrap -and (Test-Path -LiteralPath $bootstrapPath)){Remove-Item -LiteralPath $bootstrapPath;$bootstrap=$null}
                    }
                    Start-Sleep -Milliseconds 500;$child.Refresh()
                }
                $exit=$child.ExitCode
                Write-Json $processPath @{Supervisor=$supervisor;Runner=$runner;Stdout=$stdout;Stderr=$stderr;State='EXITED';ExitCode=$exit;Attempt=$attempt;CheckedAt=[DateTimeOffset]::UtcNow.ToString('o')}
                if((Test-Path -LiteralPath $stopPath) -or $exit -eq 0){break}
                $failure=if(Test-Path -LiteralPath $stderr){Get-Content -LiteralPath $stderr -Raw}else{''}
                if($failure -match 'HTTP (401|403)|绑定码|凭证|已撤销|目录.*(不符|非法)|摘要|ConPTY|私有|所有者|权限|工作根锁'){throw '执行环境明确拒绝恢复，请检查设备授权或部署条件；未重新登记'}
                if($connectedAt -and ([DateTimeOffset]::UtcNow-$connectedAt).TotalMinutes -ge 5){$attempt=0;$retryWindow=[DateTimeOffset]::UtcNow}
                if(++$attempt -gt 4 -or ([DateTimeOffset]::UtcNow-$retryWindow).TotalMinutes -gt 10){throw '执行环境恢复已达有限重试上限，请查看日志后重新启动'}
                $delay=[Math]::Min(20,[Math]::Pow(2,$attempt))
                for($i=0;$i -lt $delay*2 -and -not(Test-Path -LiteralPath $stopPath);$i++){Start-Sleep -Milliseconds 500}
            }
        }finally{$supervisorLock.Dispose()}
        return
    }
    $record=Read-Json $processPath
    if(-not $record -or -not(Is-SameProcess $record.Supervisor)){
        if($record -and (Is-SameProcess $record.Runner)){throw '原 Runner 仍存活但监管身份丢失，请核对后恢复，未重复启动'}
        if(Test-Path -LiteralPath $stopPath){Remove-Item -LiteralPath $stopPath}
        $pwsh=(Get-Command pwsh -ErrorAction Stop).Source
        $arguments=@('-NoProfile','-File',$PSCommandPath,'-Action','Supervise','-RuntimeDirectory',$RuntimeDirectory)|ForEach-Object {Quote-Argument $_}
        $logs=Ensure-PrivateDirectory (Join-Path $RuntimeDirectory 'logs');$stamp=Get-Date -Format 'yyyyMMddTHHmmssfff'
        $supervisorProcess=Start-Process -FilePath $pwsh -ArgumentList $arguments -WorkingDirectory $RuntimeDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs ('supervisor-'+$stamp+'.out.log')) -RedirectStandardError (Join-Path $logs ('supervisor-'+$stamp+'.err.log')) -PassThru
        $expectedSupervisor=Identity $supervisorProcess.Id
        Write-Output ('执行服务监管已启动 PID='+$supervisorProcess.Id)
    }else{$expectedSupervisor=$record.Supervisor;Write-Output '已核实本人执行服务监管身份，复用原进程'}
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
    do{
        $record=Read-Json $processPath;$ready=Read-Json $readinessPath
        if($record -and $record.Supervisor.Pid -eq $expectedSupervisor.Pid -and (Is-SameProcess $record.Runner) -and (Is-Ready $ready $record.Runner.Pid)){
            $config.Ready=$true;Write-Json $configPath $config
            Write-Output ('本人执行服务及真实终端连接已就绪 Runner='+$config.RunnerId+' PID='+$record.Runner.Pid);return
        }
        if(-not(Is-SameProcess $expectedSupervisor)){throw '执行服务未能就绪，请查看监管与 Runner 日志'}
        Start-Sleep -Milliseconds 500
    }while([DateTimeOffset]::UtcNow -lt $deadline)
    throw '执行服务尚未就绪，保留原绑定与日志；未自动登记新设备'
}finally{if($lock){$lock.Dispose()};$rawToken=$null}
