$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskJdk = 'C:\Users\admin\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin'
$taskClasses = Join-Path $taskRoot 'target\boundary-offline-classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
$taskSources = @(
    'src/main/java/com/ruoyi/paiflow/worker/domain/WorkerProtocol.java',
    'src/main/java/com/ruoyi/paiflow/worker/adapter/CenterClient.java',
    'src/main/java/com/ruoyi/paiflow/worker/service/WorkerException.java',
    'src/main/java/com/ruoyi/paiflow/worker/service/ExecutionScope.java',
    'src/main/java/com/ruoyi/paiflow/worker/service/DslSnapshotVerifier.java',
    'src/test/java/com/ruoyi/paiflow/worker/WorkerBoundaryOfflineTest.java'
) | ForEach-Object { Join-Path $taskRoot $_ }
& (Join-Path $taskJdk 'javac.exe') -encoding UTF-8 -d $taskClasses $taskSources
if ($LASTEXITCODE -ne 0) { throw 'JDK边界源码编译失败' }
$taskFixture = [System.IO.Path]::GetFullPath((Join-Path $taskRoot '../../contracts/paiflow/fixtures/dsl-canonical.json'))
& (Join-Path $taskJdk 'java.exe') -cp $taskClasses com.ruoyi.paiflow.worker.WorkerBoundaryOfflineTest $taskFixture
if ($LASTEXITCODE -ne 0) { throw 'JDK边界检查失败' }
