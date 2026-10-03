param([Parameter(ValueFromRemainingArguments=$true)][string[]]$RunnerArguments)
& (Join-Path $PSScriptRoot 'paicli.ps1') 'runner' @RunnerArguments
exit $LASTEXITCODE
