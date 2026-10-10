param([switch]$Prepare, [switch]$Start, [switch]$Restart, [switch]$Initialize, [switch]$Check, [ValidateRange(10,600)][int]$ReadyTimeoutSeconds=360)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$serviceRoot = Join-Path $workspaceRoot '.runtime\paismart\local-services\elasticsearch'
$esHome = 'D:\javaSoft\elasticsearch-9.3.1'
$configRoot = Join-Path $serviceRoot 'config'
$dataRoot = 'D:\javaSoft\elasticsearch-data\paismart-local'
$logRoot = Join-Path $serviceRoot 'logs'
$esEndpoint = 'https://127.0.0.1:9200'

function Protect-LocalPath([string]$Path) {
    $currentIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $Path /inheritance:r /grant:r ($currentIdentity + ':(OI)(CI)F') 'SYSTEM:(OI)(CI)F' /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '私有服务目录ACL设置失败' }
    if (Get-ChildItem -LiteralPath $Path -Force | Select-Object -First 1) {
        & icacls.exe (Join-Path $Path '*') /reset /T /Q | Out-Null
        if ($LASTEXITCODE -ne 0) { throw '私有服务文件继承ACL设置失败' }
    }
}
function Save-PrivateValue([string]$Name, [string]$Value) {
    $secureValue = ConvertTo-SecureString -String $Value -AsPlainText -Force
    $secureValue | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $serviceRoot $Name) -Encoding ascii
}
function Read-PrivateValue([string]$Name) {
    $secureValue = (Get-Content -LiteralPath (Join-Path $serviceRoot $Name) -Raw).Trim() | ConvertTo-SecureString
    return [System.Net.NetworkCredential]::new('', $secureValue).Password
}
function Run-PrivateProcess([string]$File, [string[]]$Arguments, [string]$InputText = '', [int]$TimeoutMs = 120000) {
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $File; $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    if ($File -eq 'C:\Windows\System32\cmd.exe') { $info.Arguments = '/d /s /c "' + $Arguments[-1] + '"' }
    else { foreach ($arg in $Arguments) { $info.ArgumentList.Add($arg) } }
    $info.Environment['ES_PATH_CONF'] = $configRoot
    $process = [System.Diagnostics.Process]::new(); $process.StartInfo = $info
    if (-not $process.Start()) { throw '私有工具进程无法启动' }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync(); $stderrTask = $process.StandardError.ReadToEndAsync()
    if ($InputText) { $process.StandardInput.Write($InputText) }; $process.StandardInput.Close()
    if (-not $process.WaitForExit($TimeoutMs)) { $process.Kill($true); throw '私有工具超过执行时间，已停止本工具' }
    $stdout = $stdoutTask.GetAwaiter().GetResult(); $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw ('私有工具失败，exit=' + $process.ExitCode + '; ' + $stderr.Substring(0,[Math]::Min(800,$stderr.Length))) }
    return $stdout
}
function Run-EsTool([string]$Tool, [string]$Arguments, [string]$InputText = '') {
    $batPath = Join-Path $esHome ('bin\' + $Tool + '.bat')
    return Run-PrivateProcess 'C:\Windows\System32\cmd.exe' @('/d','/s','/c', ('"' + $batPath + '" ' + $Arguments)) $InputText
}
function Es-Request([string]$Method, [string]$Path, [string]$Body = '', [switch]$ApiKey, [switch]$NoAuthentication, [int]$ExpectedStatus=200) {
    $caPath = Join-Path $configRoot 'certs\ca\ca.crt'
    $request = @{method=$Method;url=($esEndpoint+$Path);ca=$caPath;body=$Body;authorization=''}
    if (-not $NoAuthentication) {
        if ($ApiKey) { $request.authorization = 'ApiKey ' + (Read-PrivateValue 'paismart-api-key.dpapi') }
        else { $request.authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('elastic:'+(Read-PrivateValue 'admin-password.dpapi'))) }
    }
    $pythonCode = @'
import json,sys,ssl,urllib.request,urllib.error
r=json.load(sys.stdin)
context=ssl.create_default_context(cafile=r['ca'])
context.check_hostname=True
context.verify_mode=ssl.CERT_REQUIRED
headers={'Content-Type':'application/json'}
if r['authorization']: headers['Authorization']=r['authorization']
req=urllib.request.Request(r['url'],data=r['body'].encode('utf-8') if r['body'] else None,headers=headers,method=r['method'])
try:
    with urllib.request.urlopen(req,context=context,timeout=20) as response:
        raw=response.read().decode('utf-8')
        print(json.dumps({'status':response.status,'body':json.loads(raw) if raw else None}))
except urllib.error.HTTPError as error:
    print(json.dumps({'status':error.code,'body':None}))
except Exception as error:
    reason=getattr(error,'reason',error)
    sys.stderr.write(type(reason).__name__+': '+str(reason))
    sys.exit(1)
'@
    $response = Run-PrivateProcess 'C:\Program Files\Python312\python.exe' @('-c',$pythonCode) (($request | ConvertTo-Json -Depth 8 -Compress)+"`n") 30000
    $result = $response | ConvertFrom-Json
    if ($result.status -ne $ExpectedStatus) { throw ('ES认证/TLS请求未通过；HTTP=' + $result.status + '; path=' + $Path) }
    return $result.body
}
function Registered-EsProcess {
    $ports = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in 9200,9300)
    if (-not $ports.Count) { return $null }
    if (@($ports | Where-Object LocalAddress -ne '127.0.0.1').Count -or @($ports.OwningProcess | Select-Object -Unique).Count -ne 1) { throw 'ES端口不属于唯一loopback进程' }
    $saved = Get-Content -LiteralPath (Join-Path $serviceRoot 'processes.json') -Raw | ConvertFrom-Json
    $java = Get-CimInstance Win32_Process -Filter ('ProcessId='+$ports[0].OwningProcess)
    $launcher = Get-CimInstance Win32_Process -Filter ('ProcessId='+$java.ParentProcessId)
    $wrapper = Get-CimInstance Win32_Process -Filter ('ProcessId='+$saved.wrapperPid)
    if ($java.ExecutablePath -ne (Join-Path $esHome 'jdk\bin\java.exe') -or $java.CommandLine -notlike '*org.elasticsearch.bootstrap.Elasticsearch*' -or $launcher.ParentProcessId -ne $saved.wrapperPid -or $launcher.CommandLine -notlike ('*'+$configRoot+'*') -or -not $wrapper -or [Math]::Abs(($wrapper.CreationDate-[datetime]$saved.wrapperCreatedAt).TotalSeconds) -gt 1 -or $wrapper.CommandLine -notlike ('*'+(Join-Path $esHome 'bin\elasticsearch.bat')+'*')) { throw 'ES PID/创建时间/执行器/配置/父进程身份不匹配' }
    if ($saved.javaPid -and ($saved.javaPid -ne $java.ProcessId -or [Math]::Abs(($java.CreationDate-[datetime]$saved.actualJavaProcess.CreationDate).TotalSeconds) -gt 1)) { throw 'ES已登记Java身份不匹配' }
    $saved | Add-Member -NotePropertyName javaPid -NotePropertyValue $java.ProcessId -Force
    $saved | Add-Member -NotePropertyName actualJavaProcess -NotePropertyValue ($java | Select-Object ProcessId,CreationDate,ExecutablePath,CommandLine) -Force
    $saved | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $serviceRoot 'processes.json') -Encoding utf8
    return $java
}
function Registered-EsWrapper {
    $manifestPath = Join-Path $serviceRoot 'processes.json'
    if (-not (Test-Path -LiteralPath $manifestPath)) { return $null }
    $saved = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if (-not $saved.wrapperPid -or -not $saved.wrapperCreatedAt) { throw 'ES登记缺启动包装进程身份，禁止重复启动' }
    $wrapper = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $saved.wrapperPid)
    if (-not $wrapper) { return $null }
    if ($wrapper.ExecutablePath -ne 'C:\Windows\System32\cmd.exe' -or [Math]::Abs(($wrapper.CreationDate-[datetime]$saved.wrapperCreatedAt).TotalSeconds) -gt 1 -or $wrapper.CommandLine -notlike ('*'+(Join-Path $esHome 'bin\elasticsearch.bat')+'*') -or $saved.configRoot -ne $configRoot -or $saved.dataRoot -ne $dataRoot -or $saved.distribution -ne $esHome) {
        throw 'ES待就绪包装进程身份不匹配，禁止接管或重复启动'
    }
    return $wrapper
}
function Wait-RegisteredEsReady {
    $deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
    $delayMs = 250
    do {
        if (-not (Registered-EsWrapper)) { throw ('ES登记启动进程已退出；请检查日志 ' + $logRoot + '，保留数据和原身份') }
        $listener = @(Get-NetTCPConnection -State Listen -LocalPort 9200 -ErrorAction SilentlyContinue)
        if ($listener.Count) {
            # 先沿用完整 PID、父进程、loopback、安装和配置核对，再访问原认证/TLS健康接口。
            Registered-EsProcess | Out-Null
            try {
                $health = Es-Request 'GET' '/_cluster/health'
                if ($health.status -ne 'red' -and -not $health.timed_out -and $health.number_of_nodes -eq 1) { return }
            } catch {
                # 监听刚建立时安全索引可能仍在恢复；只容忍明确的暂不可用响应，不弱化认证/TLS检查。
                if ($_.Exception.Message -notmatch 'HTTP=503;') { throw }
            }
        }
        if ([DateTime]::UtcNow -lt $deadline) {
            $remainingMs = [int][Math]::Max(1,($deadline-[DateTime]::UtcNow).TotalMilliseconds)
            Start-Sleep -Milliseconds ([Math]::Min($delayMs,$remainingMs))
            $delayMs = [Math]::Min(2000,[int]($delayMs*1.5))
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw ('ES在 ' + $ReadyTimeoutSeconds + ' 秒内未就绪；登记进程保留，禁止重复启动；请检查日志 ' + $logRoot)
}
if (-not (Test-Path -LiteralPath $esHome)) { throw '已核实ES9.3.1安装不存在，不下载替代包' }
New-Item -ItemType Directory -Path $serviceRoot,$logRoot -Force | Out-Null
Protect-LocalPath $serviceRoot

if ($Prepare) {
    if (-not (Test-Path -LiteralPath (Join-Path $configRoot 'elasticsearch.yml'))) {
        if (Test-Path -LiteralPath $dataRoot) { if (Get-ChildItem -LiteralPath $dataRoot -Force | Select-Object -First 1) { throw '已有ES数据但无本机私有配置，禁止重新引导' } }
        Copy-Item -LiteralPath (Join-Path $esHome 'config') -Destination $configRoot -Recurse
        Copy-Item -LiteralPath (Join-Path $configRoot 'elasticsearch.yml') -Destination (Join-Path $serviceRoot 'original-elasticsearch.yml')
        @"
cluster.name: paismart-local
node.name: paismart-local-node
path.data: D:/javaSoft/elasticsearch-data/paismart-local
path.logs: $($logRoot.Replace('\','/'))
network.host: 127.0.0.1
http.port: 9200
transport.port: 9300
discovery.type: single-node
xpack.security.enabled: true
xpack.security.autoconfiguration.enabled: false
xpack.security.enrollment.enabled: false
xpack.security.http.ssl.enabled: true
xpack.security.http.ssl.key: certs/paismart-local/paismart-local.key
xpack.security.http.ssl.certificate: certs/paismart-local/paismart-local.crt
xpack.security.http.ssl.certificate_authorities: [certs/ca/ca.crt]
xpack.security.transport.ssl.enabled: true
xpack.security.transport.ssl.verification_mode: full
xpack.security.transport.ssl.key: certs/paismart-local/paismart-local.key
xpack.security.transport.ssl.certificate: certs/paismart-local/paismart-local.crt
xpack.security.transport.ssl.certificate_authorities: [certs/ca/ca.crt]
action.destructive_requires_name: true
"@ | Set-Content -LiteralPath (Join-Path $configRoot 'elasticsearch.yml') -Encoding ascii
        "-Xms1g`n-Xmx1g" | Set-Content -LiteralPath (Join-Path $configRoot 'jvm.options.d\paismart-heap.options') -Encoding ascii
    }
    $certRoot = Join-Path $configRoot 'certs'
    New-Item -ItemType Directory -Path $certRoot -Force | Out-Null
    Protect-LocalPath $serviceRoot
    if (-not (Test-Path -LiteralPath (Join-Path $certRoot 'ca\ca.crt'))) {
        $caZip = Join-Path $serviceRoot 'ca-certificates.zip'
        Run-EsTool 'elasticsearch-certutil' ('ca --silent --pem --out "' + $caZip + '"') | Out-Null
        Expand-Archive -LiteralPath $caZip -DestinationPath $certRoot
    }
    if (-not (Test-Path -LiteralPath (Join-Path $certRoot 'paismart-local\paismart-local.crt'))) {
        $certZip = Join-Path $serviceRoot 'node-certificates.zip'
        Run-EsTool 'elasticsearch-certutil' ('cert --silent --pem --ca-cert "' + (Join-Path $certRoot 'ca\ca.crt') + '" --ca-key "' + (Join-Path $certRoot 'ca\ca.key') + '" --name paismart-local --dns localhost --ip 127.0.0.1 --out "' + $certZip + '"') | Out-Null
        Expand-Archive -LiteralPath $certZip -DestinationPath $certRoot
    }
    if (-not (Test-Path -LiteralPath (Join-Path $serviceRoot 'admin-password.dpapi'))) {
        $passwordBytes = [byte[]]::new(36); [System.Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
        Save-PrivateValue 'admin-password.dpapi' ([Convert]::ToBase64String($passwordBytes))
    }
    if (-not (Test-Path -LiteralPath (Join-Path $configRoot 'elasticsearch.keystore'))) { Run-EsTool 'elasticsearch-keystore' 'create' | Out-Null }
    $keystoreKeys = Run-EsTool 'elasticsearch-keystore' 'list'
    if ($keystoreKeys -notmatch '(?m)^bootstrap\.password\s*$') { Run-EsTool 'elasticsearch-keystore' 'add -x -f bootstrap.password' ((Read-PrivateValue 'admin-password.dpapi') + "`n") | Out-Null }
    $bootstrapValue = Run-EsTool 'elasticsearch-keystore' 'show bootstrap.password'
    if ($bootstrapValue.Trim() -ne (Read-PrivateValue 'admin-password.dpapi')) { throw '私有keystore与DPAPI密码不一致，禁止覆盖身份' }
    $truststore = Join-Path $serviceRoot 'java-truststore.jks'
    if (-not (Test-Path -LiteralPath $truststore)) {
        $jdkHome = 'C:\Users\admin\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
        Copy-Item -LiteralPath (Join-Path $jdkHome 'lib\security\cacerts') -Destination $truststore
        $env:PAISMART_LOCAL_TRUSTSTORE_PASSWORD = 'changeit'
        try { Run-PrivateProcess (Join-Path $jdkHome 'bin\keytool.exe') @('-importcert','-noprompt','-alias','paismart-local-es-ca','-file',(Join-Path $certRoot 'ca\ca.crt'),'-keystore',$truststore,'-storepass:env','PAISMART_LOCAL_TRUSTSTORE_PASSWORD') | Out-Null }
        finally { Remove-Item Env:PAISMART_LOCAL_TRUSTSTORE_PASSWORD -ErrorAction SilentlyContinue }
    }
    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    Protect-LocalPath $serviceRoot; Protect-LocalPath $dataRoot
    Write-Output 'ES私有TLS/DPAPI/信任库准备完成；未输出凭据'
}
if ($Restart) {
    $target = Registered-EsProcess
    if ($target) {
        $restartStamp=(Get-Date).ToString('yyyyMMdd-HHmmss')
        Copy-Item -LiteralPath (Join-Path $serviceRoot 'processes.json') -Destination (Join-Path $serviceRoot ('processes-before-restart-'+$restartStamp+'.json'))
        foreach ($name in @('server.stdout.log','server.stderr.log')) { if (Test-Path -LiteralPath (Join-Path $logRoot $name)) { Copy-Item -LiteralPath (Join-Path $logRoot $name) -Destination (Join-Path $logRoot ($restartStamp+'-'+$name)) } }
        $targetExited = [Diagnostics.Process]::GetProcessById($target.ProcessId)
        Stop-Process -Id $target.ProcessId -Force
        if (-not $targetExited.WaitForExit(10000)) { throw '本ES进程尚未退出，禁止重复启动' }
    }
    $Start=$true
}
if ($Start) {
    $existing = Get-NetTCPConnection -State Listen -LocalPort 9200 -ErrorAction SilentlyContinue
    if ($existing) {
        Registered-EsProcess | Out-Null
        Write-Output '复用已登记ES进程'
    } elseif (Registered-EsWrapper) {
        Write-Output '复用已登记的ES冷启动进程，等待监听与健康，不重复启动'
    } else {
        if (-not (Test-Path -LiteralPath (Join-Path $configRoot 'elasticsearch.keystore'))) { throw '必须先完成TLS与keystore准备' }
        $priorConf = $env:ES_PATH_CONF
        $env:ES_PATH_CONF = $configRoot
        try {
            $wrapper = Start-Process -FilePath 'C:\Windows\System32\cmd.exe' -ArgumentList @('/d','/s','/c', ('""' + (Join-Path $esHome 'bin\elasticsearch.bat') + '""')) -WorkingDirectory $esHome -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'server.stdout.log') -RedirectStandardError (Join-Path $logRoot 'server.stderr.log') -PassThru
        } finally { $env:ES_PATH_CONF = $priorConf }
        @{wrapperPid=$wrapper.Id;wrapperCreatedAt=$wrapper.StartTime.ToString('o');configRoot=$configRoot;dataRoot=$dataRoot;endpoint=$esEndpoint;heap='1GiB';distribution=$esHome;startedAt=(Get-Date).ToString('o')} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $serviceRoot 'processes.json') -Encoding utf8
        Write-Output ('ES隐藏启动包装进程PID=' + $wrapper.Id)
    }
    Wait-RegisteredEsReady
}
if ($Initialize) {
    $health = Es-Request 'GET' '/_cluster/health'
    if (-not (Test-Path -LiteralPath (Join-Path $serviceRoot 'admin-initialized.json'))) {
        Es-Request 'POST' '/_security/user/elastic/_password' (@{password=(Read-PrivateValue 'admin-password.dpapi')} | ConvertTo-Json -Compress) | Out-Null
        @{initializedAt=(Get-Date).ToString('o');identity='elastic';passwordStorage='WindowsCurrentUserDPAPI';scope='local ES administration'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $serviceRoot 'admin-initialized.json') -Encoding utf8
    }
    if (-not (Test-Path -LiteralPath (Join-Path $serviceRoot 'paismart-api-key.dpapi'))) {
        $descriptor = @{name='paismart-local-center';role_descriptors=@{paismart=@{cluster=@('monitor');indices=@(@{names=@('paismart-knowledge','paismart-knowledge-*');privileges=@('read','write','create_index','manage');allow_restricted_indices=$false})}}}
        $createdKey = Es-Request 'POST' '/_security/api_key' ($descriptor | ConvertTo-Json -Depth 10 -Compress)
        Save-PrivateValue 'paismart-api-key.dpapi' $createdKey.encoded
        @{id=$createdKey.id;name=$createdKey.name;createdAt=(Get-Date).ToString('o');roleDescriptors=$descriptor.role_descriptors;encodedStorage='WindowsCurrentUserDPAPI'} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $serviceRoot 'api-key-metadata.json') -Encoding utf8
    }
    Protect-LocalPath $serviceRoot
    Write-Output 'ES管理员/APIKey初始化完成；凭据只保存DPAPI'
}
if ($Check) {
    Registered-EsProcess | Out-Null
    $health = Es-Request 'GET' '/_cluster/health'
    Es-Request 'GET' '/' -NoAuthentication -ExpectedStatus 401 | Out-Null
    $apiHealth = Es-Request 'GET' '/_cluster/health' -ApiKey
    $version = Es-Request 'GET' '/' -ApiKey
    $namespaceRequest=@{cluster=@('monitor');index=@(@{names=@('paismart-knowledge','paismart-knowledge-check');privileges=@('read','write','create_index','manage')})}
    $allowed=Es-Request 'POST' '/_security/user/_has_privileges' ($namespaceRequest|ConvertTo-Json -Depth 8 -Compress) -ApiKey
    $deniedRequest=@{cluster=@('manage_security');index=@(@{names=@('other-project-private');privileges=@('read','write','create_index','manage')})}
    $denied=Es-Request 'POST' '/_security/user/_has_privileges' ($deniedRequest|ConvertTo-Json -Depth 8 -Compress) -ApiKey
    if (-not $allowed.has_all_requested -or $denied.has_all_requested -or @($denied.index.'other-project-private'.PSObject.Properties | Where-Object Value -eq $true).Count -or $denied.cluster.manage_security) { throw 'APIKey命名空间权限核对不通过' }
    $ports = @(Get-NetTCPConnection -State Listen | Where-Object LocalPort -in 9200,9300 | Select-Object LocalAddress,LocalPort,OwningProcess)
    if (@($ports | Where-Object LocalAddress -ne '127.0.0.1').Count) { throw 'ES监听超出loopback范围' }
    $javaPid = ($ports | Where-Object LocalPort -eq 9200 | Select-Object -First 1).OwningProcess
    $actual = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $javaPid) | Select-Object ProcessId,CreationDate,ExecutablePath,CommandLine
    $manifestPath = Join-Path $serviceRoot 'processes.json'; $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $manifest | Add-Member -NotePropertyName javaPid -NotePropertyValue $javaPid -Force
    $manifest | Add-Member -NotePropertyName actualJavaProcess -NotePropertyValue $actual -Force
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    $ready = @{checkedAt=(Get-Date).ToString('o');endpoint=$esEndpoint;version=$version.version.number;status=$apiHealth.status;timedOut=$apiHealth.timed_out;numberOfNodes=$apiHealth.number_of_nodes;listeners=$ports;authentication='ApiKey';tls='Python ssl CERT_REQUIRED + CA + hostname verification';unauthenticatedStatus=401;namespaceAllowed=$allowed;namespaceDenied=$denied;javaTruststore=(Join-Path $serviceRoot 'java-truststore.jks');indicesNamespace=@('paismart-knowledge','paismart-knowledge-*');businessIndicesValidated=$false}
    $ready | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $serviceRoot 'readiness.json') -Encoding utf8
    Protect-LocalPath $serviceRoot
    Write-Output ('ES HTTPS/APIKey健康=' + $apiHealth.status + '; version=' + $version.version.number + '; PID=' + $javaPid)
}
