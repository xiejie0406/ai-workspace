param([Parameter(Mandatory)][ValidateSet('check','init')][string]$Mode)
$ErrorActionPreference='Stop'
$workspaceRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$settings=@{}
Get-Content -LiteralPath (Join-Path $workspaceRoot '.env') | ForEach-Object {
  if($_ -match '^((?:RUOYI_DB_[A-Z0-9_]+|RUOYI_MANAGED_SECRET_[A-Z0-9_]+))=(.*)$'){
    $settingName=$Matches[1];$settingValue=$Matches[2].Trim().Trim('"').Trim("'")
    if($settingValue){$settings[$settingName]=$settingValue}
  }
}
foreach($required in @('RUOYI_DB_URL','RUOYI_DB_USERNAME','RUOYI_DB_PASSWORD','RUOYI_MANAGED_SECRET_MASTER_KEY_ID','RUOYI_MANAGED_SECRET_MASTER_KEY_BASE64')){
  if(-not $settings.ContainsKey($required)){throw '现有数据库或托管主密钥配置缺失，停止。'}
}
$uri=[Uri]($settings['RUOYI_DB_URL'] -replace '^jdbc:','')
if($uri.Host -notin @('localhost','127.0.0.1') -or $uri.Port -ne 3306 -or $uri.AbsolutePath -ne '/ry-vue'){throw '现有数据库与批准目标不符，停止。'}
$report=Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-paicli/paicli-api/target/surefire-reports') -Filter 'TEST-*.xml' | Select-Object -First 1
[xml]$xml=[IO.File]::ReadAllText($report.FullName)
$resolvedClasspath=($xml.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
$libraries=@($resolvedClasspath.Split(';') | Where-Object { $_.EndsWith('.jar') -and (Test-Path -LiteralPath $_) })
$modules=@('ruoyi-common','ruoyi-system','ruoyi-framework') | ForEach-Object { Join-Path $workspaceRoot ("ruoyi-backend/$_/target/classes") }
$driver=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'
foreach($path in @($modules)+@($driver)){if(-not(Test-Path -LiteralPath $path)){throw '已构建平台依赖或已安装驱动缺失，停止。'}}
$classpath=(@($modules)+$libraries+@($driver)) -join ';'
$start=[Diagnostics.ProcessStartInfo]::new();$start.FileName=(Get-Command java.exe -ErrorAction Stop).Source
$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
foreach($setting in $settings.GetEnumerator()){$start.Environment[$setting.Key]=$setting.Value}
if($Mode -eq 'init'){$start.Environment['PAICLI_CONTROL_KEY_CONFIRM']='INIT_CONTROL_KEY:ry-vue'}
$arguments=@('--source','17','--class-path',$classpath,(Join-Path $PSScriptRoot 'paicli/PcControlKeyBootstrap.java'),$Mode,'ry-vue')
foreach($argument in $arguments){$start.ArgumentList.Add($argument)}
$process=[Diagnostics.Process]::new();$process.StartInfo=$start;[void]$process.Start()
$outTask=$process.StandardOutput.ReadToEndAsync();$errorTask=$process.StandardError.ReadToEndAsync()
if(-not$process.WaitForExit(30000)){$process.Kill($true);throw '固定用途操作超时，停止；不自动重试。'}
$output=$outTask.GetAwaiter().GetResult();$errorText=$errorTask.GetAwaiter().GetResult()
foreach($setting in $settings.GetEnumerator()){$errorText=$errorText.Replace($setting.Value,'[已隐藏]');$output=$output.Replace($setting.Value,'[已隐藏]')}
if($process.ExitCode -ne 0){throw ('固定用途操作失败，退出码'+$process.ExitCode+'；'+$errorText)}
$evidence=Join-Path $workspaceRoot '文档/项目/PaiCLI智能编码项目/证据/2026-10-03-完整功能与数据库'
[IO.File]::WriteAllText((Join-Path $evidence ('控制密钥-'+$Mode+'-'+[DateTime]::Now.ToString('yyyyMMddTHHmmss')+'.txt')),('时间：'+[DateTimeOffset]::Now.ToString('o')+"`n"+$output),[Text.UTF8Encoding]::new($false))
$output
