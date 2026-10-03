param([string]$MavenCommand = 'mvn')
$ErrorActionPreference = 'Stop'
$moduleRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backendRoot = [System.IO.Path]::GetFullPath((Join-Path $moduleRoot '..'))
$generationName = 'parser-worker-' + [Guid]::NewGuid().ToString('N')
$workerRoot = Join-Path $moduleRoot ('target/' + $generationName)
$dependencyRoot = Join-Path $workerRoot 'lib'
# 只构建普通模块 JAR 和运行依赖；没有数据库访问、Worker 启动或文件清理。
Push-Location $backendRoot
try {
    # 同次 reactor 构建后复制；各模块落自己的target，避免-am把多版本依赖混入同一classpath。
    $dependencyOutput = '-DoutputDirectory=${project.build.directory}/' + $generationName + '/lib'
    $dependencyList = '-DoutputFile=${project.build.directory}/' + $generationName + '/依赖列表.txt'
    & $MavenCommand '-pl' 'ruoyi-paismart' '-am' '-DskipTests' 'package' 'dependency:copy-dependencies' 'dependency:list' '-DincludeScope=runtime' $dependencyOutput $dependencyList '-DappendOutput=false'
    if ($LASTEXITCODE -ne 0) { throw 'PaiSmart 构建失败，未准备 Worker classpath' }
    if (-not (Test-Path -LiteralPath $dependencyRoot -PathType Container)) { throw '本次Worker依赖目录不存在，不能启用解析进程' }
    $moduleJar = Get-ChildItem -LiteralPath (Join-Path $moduleRoot 'target') -Filter 'ruoyi-paismart-*.jar' | Where-Object { $_.Name -notmatch '(sources|javadoc|tests)' }
    if (@($moduleJar).Count -ne 1) { throw '模块普通 JAR 未唯一确认' }
    # 主JAR也固定到本批目录，后续package不能静默改变已配置的Worker版本。
    $workerJar = Join-Path $workerRoot $moduleJar.Name
    Copy-Item -LiteralPath $moduleJar.FullName -Destination $workerJar
    $separator = [System.IO.Path]::PathSeparator
    $workerClasspath = $workerJar + $separator + (Join-Path $dependencyRoot '*')
    $workerArtifacts = @((Get-Item -LiteralPath $workerJar)) + @(Get-ChildItem -LiteralPath $dependencyRoot -Filter '*.jar')
    $workerArtifacts | ForEach-Object { Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 } | Export-Csv -LiteralPath (Join-Path $workerRoot '依赖哈希.csv') -NoTypeInformation -Encoding UTF8
    Write-Output ('paismart.intelligence.parse.worker-classpath=' + $workerClasspath)
    Write-Output '此脚本未启动 Worker。启用前另核对 OS 运行权限、资源、网络和文件样例。'
}
finally { Pop-Location }
