param([switch]$Start, [switch]$PrepareTopics, [switch]$Check)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$serviceRoot = Join-Path $workspaceRoot '.runtime\paismart\local-services\kafka'
$manifestPath = Join-Path $serviceRoot 'processes.json'
$javaPath = 'C:\Users\admin\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\java.exe'
$softwareRoot = 'D:\javaSoft\kafka_2.13-2.6.0'
$logRoot = Join-Path $serviceRoot 'logs'
function Verify-OriginalIdentity {
    $expected=@(Get-Content -LiteralPath (Join-Path $serviceRoot 'original-meta.properties') | Where-Object {$_ -match '^(broker.id|cluster.id|version)='} | Sort-Object)
    $actual=@(Get-Content -LiteralPath 'D:\tmp\kafka-logs\meta.properties' | Where-Object {$_ -match '^(broker.id|cluster.id|version)='} | Sort-Object)
    if (Compare-Object $expected $actual) { throw '原Kafka集群身份变化，禁止恢复或初始化' }
    if (-not (Test-Path -LiteralPath 'D:\tmp\zookeeper\version-2')) { throw '原ZK数据不存在，禁止创建空替代服务' }
}
function Start-SavedRole([string]$Role) {
    Verify-OriginalIdentity
    $manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $saved=$manifest.processes | Where-Object role -eq $Role
    if (-not $saved -or $saved.config -notlike ($serviceRoot+'\*') -or -not (Test-Path -LiteralPath $saved.config)) { throw '原私有配置缺失，不生成替代身份' }
    $prior=Get-CimInstance Win32_Process -Filter ('ProcessId='+$saved.pid)
    if ($prior -and [Math]::Abs(($prior.CreationDate-[datetime]$saved.createdAt).TotalSeconds) -lt 1 -and $prior.ExecutablePath -eq $javaPath -and $prior.CommandLine -like ('*'+$saved.config+'*')) { throw ('登记'+$Role+'进程仍存在但未监听，保留诊断现场') }
    $stamp=(Get-Date).ToString('yyyyMMdd-HHmmss')
    $process=Start-Process -FilePath $javaPath -ArgumentList $saved.args -WorkingDirectory $softwareRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot ($Role+'-'+$stamp+'.stdout.log')) -RedirectStandardError (Join-Path $logRoot ($Role+'-'+$stamp+'.stderr.log')) -PassThru
    $saved.pid=$process.Id; $saved.createdAt=$process.StartTime.ToString('o')
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    Write-Output ('恢复原'+$Role+' PID='+$process.Id)
}
function Wait-SavedListener([int]$Port,[string]$Role) {
    for ($attempt=0;$attempt -lt 30;$attempt++) {
        $client=[Net.Sockets.TcpClient]::new()
        try { $connected=$client.ConnectAsync('127.0.0.1',$Port).Wait(750) -and $client.Connected } catch { $connected=$false } finally { $client.Dispose() }
        if ($connected -and (Verify-Listener $Port $Role)) { return }
        $manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        $saved=$manifest.processes | Where-Object role -eq $Role
        if (-not (Get-Process -Id $saved.pid -ErrorAction SilentlyContinue)) { throw ('新'+$Role+'已退出，保存日志，不盲目重试') }
        Start-Sleep -Seconds 1
    }
    throw ($Role+'未在有限时间内恢复监听，保留现场')
}
function Kafka-Tool([string]$Class, [string[]]$ToolArguments, [string]$Name) {
    $arguments = @('-Xms32m','-Xmx128m','-Dlog4j.configuration=file:///D:/javaSoft/kafka_2.13-2.6.0/config/tools-log4j.properties','-cp','D:\javaSoft\kafka_2.13-2.6.0\libs\*',$Class) + $ToolArguments
    $outputFile = Join-Path $logRoot ($Name + '.stdout.log')
    $errorFile = Join-Path $logRoot ($Name + '.stderr.log')
    $tool = Start-Process -FilePath $javaPath -ArgumentList $arguments -WindowStyle Hidden -RedirectStandardOutput $outputFile -RedirectStandardError $errorFile -PassThru
    if (-not $tool.WaitForExit(65000)) { $tool.Kill(); throw 'Kafka工具超时；不重试生产/消费' }
    if ($tool.ExitCode -ne 0) { throw ('Kafka只读/主题配置工具失败，日志=' + $errorFile) }
    return @(Get-Content -LiteralPath $outputFile | Where-Object { $_.Trim() })
}
function Verify-Listener([int]$Port,[string]$Role) {
    $listener = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if (-not $listener.Count) { return $false }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $saved = $manifest.processes | Where-Object role -eq $Role
    $actual = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $saved.pid)
    $mainClass=if ($Role -eq 'zookeeper') {'org.apache.zookeeper.server.quorum.QuorumPeerMain'} else {'kafka.Kafka'}
    if (@($listener | Where-Object { $_.LocalAddress -ne '127.0.0.1' -or $_.OwningProcess -ne $saved.pid }).Count -or -not $actual -or [Math]::Abs(($actual.CreationDate - [datetime]$saved.createdAt).TotalSeconds) -gt 1 -or $actual.ExecutablePath -ne $javaPath -or $actual.CommandLine -notlike ('*'+$saved.config+'*') -or $actual.CommandLine -notlike ('*'+$mainClass+'*')) { throw ('服务归属/监听不匹配: ' + $Role) }
    return $true
}
if ($Start) {
    Verify-OriginalIdentity
    $zkReady = Verify-Listener 2181 'zookeeper'; $brokerReady = Verify-Listener 9092 'kafka'
    if (-not $zkReady -and -not $brokerReady) {
        Start-SavedRole 'zookeeper'
        Wait-SavedListener 2181 'zookeeper'
        # 原broker会话超时18秒，恢复原ZK后等待自然过期；不删除/brokers节点。
        Start-Sleep -Seconds 25
        if (-not (Verify-Listener 2181 'zookeeper')) { throw '原ZK未就绪，禁止启动Kafka' }
        Start-SavedRole 'kafka'
        Wait-SavedListener 9092 'kafka'
    } elseif ($zkReady -and -not $brokerReady) {
        Start-SavedRole 'kafka'
        Wait-SavedListener 9092 'kafka'
    } elseif (-not $zkReady) { Start-SavedRole 'zookeeper'; Wait-SavedListener 2181 'zookeeper' }
    else { Write-Output '复用原Kafka/ZooKeeper登记进程' }
}
if ($PrepareTopics -or $Check) {
    if (-not (Verify-Listener 2181 'zookeeper') -or -not (Verify-Listener 9092 'kafka')) { throw '原服务尚未就绪，未创建主题' }
    $api = Kafka-Tool 'kafka.admin.BrokerApiVersionsCommand' @('--bootstrap-server','127.0.0.1:9092') 'broker-api-final-readonly'
    $topics = Kafka-Tool 'kafka.admin.TopicCommand' @('--bootstrap-server','127.0.0.1:9092','--list') 'topic-list-before-readonly'
    if ($PrepareTopics) {
        foreach ($topic in @('paismart.jobs','paismart.jobs.dlq')) {
            if ($topics -notcontains $topic) { Kafka-Tool 'kafka.admin.TopicCommand' @('--bootstrap-server','127.0.0.1:9092','--create','--topic',$topic,'--partitions','1','--replication-factor','1','--config','cleanup.policy=delete') ('create-' + $topic) | Out-Null }
        }
    }
    $topics = Kafka-Tool 'kafka.admin.TopicCommand' @('--bootstrap-server','127.0.0.1:9092','--list') 'topic-list-final-readonly'
    $descriptions = @()
    foreach ($topic in @('paismart.jobs','paismart.jobs.dlq')) { if ($topics -contains $topic) { $descriptions += Kafka-Tool 'kafka.admin.TopicCommand' @('--bootstrap-server','127.0.0.1:9092','--describe','--topic',$topic) ('describe-' + $topic) } }
    $ready = @{checkedAt=(Get-Date).ToString('o');endpoint='127.0.0.1:9092';brokerApiExit=0;topicListExit=0;topics=$topics;topicDescriptions=$descriptions;consumerGroup='paismart-center-workers';consumerEnabled=$false;messagesProduced=$false;originalData=@('D:\tmp\kafka-logs','D:\tmp\zookeeper');listeners=@(Get-NetTCPConnection -State Listen | Where-Object LocalPort -in 2181,9092 | Select-Object LocalAddress,LocalPort,OwningProcess); scope='原集群恢复及获授权项目topic准备，无消息生产/消费'}
    $ready | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $serviceRoot 'project-topics-readiness.json') -Encoding utf8
    Write-Output ('Kafka就绪；项目主题=' + ($topics -join ', ') + '; 未消费/投递消息')
}
