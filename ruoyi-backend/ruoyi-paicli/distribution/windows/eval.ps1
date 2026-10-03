param([Parameter(ValueFromRemainingArguments=$true)][string[]]$EvalArguments)
$ErrorActionPreference='Stop'
$taskJar=Join-Path $PSScriptRoot '../../paicli-eval/target/paicli-eval-3.9.2.jar'
if(-not(Test-Path -LiteralPath $taskJar -PathType Leaf)){throw '独立Eval包不存在，请先构建paicli-eval'}
$taskJava=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{(Get-Command java -ErrorAction Stop).Source}
& $taskJava '-jar' $taskJar @EvalArguments
exit $LASTEXITCODE
