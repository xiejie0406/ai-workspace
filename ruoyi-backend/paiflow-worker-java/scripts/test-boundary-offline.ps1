param(
    [string]$OutputDirectory,
    [string]$MavenCommand = 'mvn',
    [string]$JdkHome = 'C:\Users\admin\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
)

$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskWorkspace = [System.IO.Path]::GetFullPath((Join-Path $taskRoot '../..'))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $taskWorkspace '.runtime/paiflow-worker-boundary-offline'
}
$taskOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
$taskJava = Join-Path $JdkHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $taskJava -PathType Leaf)) { throw "缺少已安装 JDK：$taskJava" }
Get-Command $MavenCommand -ErrorAction Stop | Out-Null
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null

# 复用本模块 POM 和原依赖，编译产物只写所选检查目录。
[xml]$taskPom = Get-Content -LiteralPath (Join-Path $taskRoot 'pom.xml') -Raw
$taskNamespace = $taskPom.DocumentElement.NamespaceURI
$taskNs = [System.Xml.XmlNamespaceManager]::new($taskPom.NameTable)
$taskNs.AddNamespace('m', $taskNamespace)
$taskBuild = $taskPom.SelectSingleNode('/m:project/m:build', $taskNs)
function Set-TaskBuildPath([string]$name, [string]$path) {
    $element = $taskBuild.SelectSingleNode("m:$name", $taskNs)
    if ($null -eq $element) { $element = $taskPom.CreateElement($name, $taskNamespace); $taskBuild.AppendChild($element) | Out-Null }
    $element.InnerText = $path.Replace('\', '/')
}
Set-TaskBuildPath 'directory' (Join-Path $taskOutput 'target')
Set-TaskBuildPath 'sourceDirectory' (Join-Path $taskRoot 'src/main/java')
Set-TaskBuildPath 'testSourceDirectory' (Join-Path $taskRoot 'src/test/java')
foreach ($entry in @(@('resources', 'resource', 'src/main/resources'), @('testResources', 'testResource', 'src/test/resources'))) {
    $container = $taskBuild.SelectSingleNode("m:$($entry[0])", $taskNs)
    if ($null -ne $container) { $taskBuild.RemoveChild($container) | Out-Null }
    $container = $taskPom.CreateElement($entry[0], $taskNamespace)
    $resource = $taskPom.CreateElement($entry[1], $taskNamespace)
    $directory = $taskPom.CreateElement('directory', $taskNamespace)
    $directory.InnerText = (Join-Path $taskRoot $entry[2]).Replace('\', '/')
    $resource.AppendChild($directory) | Out-Null
    $container.AppendChild($resource) | Out-Null
    $taskBuild.AppendChild($container) | Out-Null
}
$taskPomPath = Join-Path $taskOutput 'pom.xml'
$taskPom.Save($taskPomPath)
$taskClasspathFile = Join-Path $taskOutput 'dependency-classpath.txt'
$taskCompileLog = Join-Path $taskOutput 'compile.log'
& $MavenCommand -o -f $taskPomPath test-compile dependency:build-classpath '-Dmdep.includeScope=test' "-Dmdep.outputFile=$taskClasspathFile" *> $taskCompileLog
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath $taskCompileLog -Tail 30
    throw "边界检查的 Maven 编译或依赖 classpath 生成失败：$taskCompileLog"
}
$taskDependencies = (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
$taskClasspath = @((Join-Path $taskOutput 'target/test-classes'), (Join-Path $taskOutput 'target/classes'), $taskDependencies) -join [System.IO.Path]::PathSeparator
$taskFixture = Join-Path $taskWorkspace 'contracts/paiflow/fixtures/dsl-canonical.json'
& $taskJava -cp $taskClasspath com.ruoyi.paiflow.worker.WorkerBoundaryOfflineTest $taskFixture
if ($LASTEXITCODE -ne 0) { throw '离线边界检查失败' }
