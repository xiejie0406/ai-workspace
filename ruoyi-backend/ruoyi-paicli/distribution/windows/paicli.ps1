param([Parameter(ValueFromRemainingArguments=$true)][string[]]$CliArguments)
$ErrorActionPreference='Stop'
$taskJar=Join-Path $PSScriptRoot '../../paicli-cli/target/paicli-cli-3.9.2.jar'
if(-not(Test-Path -LiteralPath $taskJar -PathType Leaf)){throw 'CLI分发包不存在，请先构建paicli-cli'}
$taskJava=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{(Get-Command java -ErrorAction Stop).Source}
& $taskJava '-jar' $taskJar @CliArguments
exit $LASTEXITCODE
