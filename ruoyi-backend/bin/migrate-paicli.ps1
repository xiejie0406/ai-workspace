param(
  [Parameter(Mandatory)][ValidateSet('inspect', 'migrate')][string]$Mode,
  [switch]$RegisterExistingDatabase
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$approvedDatabase = 'ry-vue'
$settings = @{}
Get-Content -LiteralPath (Join-Path $workspaceRoot '.env') | ForEach-Object {
  if ($_ -match '^(RUOYI_DB_[A-Z0-9_]+)=(.*)$') {
    $settingName = $Matches[1]
    $settingValue = $Matches[2].Trim().Trim('"').Trim("'")
    if ($settingValue) { $settings[$settingName] = $settingValue }
  }
}
foreach ($required in @('RUOYI_DB_URL', 'RUOYI_DB_USERNAME', 'RUOYI_DB_PASSWORD')) {
  if (-not $settings.ContainsKey($required)) { throw '既有若依数据库配置不完整，停止。' }
}
$databaseUri = [Uri]($settings['RUOYI_DB_URL'] -replace '^jdbc:', '')
if ($databaseUri.Host -notin @('localhost', '127.0.0.1') -or
    $databaseUri.Port -ne 3306 -or $databaseUri.AbsolutePath.Trim('/') -ne $approvedDatabase) {
  throw '连接目标与已批准的既有localhost:3306/ry-vue不符，停止。'
}
$mysqlExe = (Get-Command mysql.exe -ErrorAction Stop).Source
$mysqlArguments = @('--protocol=TCP', ('--host=' + $databaseUri.Host), '--port=3306',
  ('--user=' + $settings['RUOYI_DB_USERNAME']), ('--database=' + $approvedDatabase),
  '--default-character-set=utf8mb4', '--batch', '--skip-column-names')

function Invoke-PrivateChild {
  param([string]$Program, [string[]]$ChildArguments, [hashtable]$ChildEnvironment,
    [string]$OutputFile, [int]$TimeoutSeconds = 120)
  $start = [Diagnostics.ProcessStartInfo]::new()
  $start.FileName = $Program
  $start.UseShellExecute = $false
  $start.CreateNoWindow = $true
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  foreach ($argument in $ChildArguments) { $start.ArgumentList.Add($argument) }
  foreach ($name in $ChildEnvironment.Keys) { $start.Environment[$name] = $ChildEnvironment[$name] }
  $child = [Diagnostics.Process]::new()
  $child.StartInfo = $start
  [void]$child.Start()
  $errorTask = $child.StandardError.ReadToEndAsync()
  $outputStream = $null
  try {
    if ($OutputFile) {
      $outputStream = [IO.File]::Open($OutputFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
      $outputTask = $child.StandardOutput.BaseStream.CopyToAsync($outputStream)
    } else { $outputTask = $child.StandardOutput.ReadToEndAsync() }
    if (-not $child.WaitForExit($TimeoutSeconds * 1000)) {
      $child.Kill($true)
      throw 'PaiCLI数据库子进程超时，停止；不自动重试。'
    }
    $output = $outputTask.GetAwaiter().GetResult()
    $errorText = $errorTask.GetAwaiter().GetResult()
    foreach ($name in @('RUOYI_DB_PASSWORD', 'RUOYI_DB_USERNAME', 'RUOYI_DB_URL')) {
      $errorText = $errorText.Replace($settings[$name], '[已隐藏]')
    }
    if ($child.ExitCode -ne 0) { throw ('子进程退出码' + $child.ExitCode + '：' + $errorText) }
    if ($OutputFile) { return }
    return $output
  } finally {
    if ($outputStream) { $outputStream.Dispose() }
    $child.Dispose()
  }
}
function Invoke-ApprovedQuery([string]$Sql) {
  return Invoke-PrivateChild $mysqlExe ($mysqlArguments + @('--execute=' + $Sql)) @{
    MYSQL_PWD = $settings['RUOYI_DB_PASSWORD']
  } '' 30
}
$actualDatabase = (Invoke-ApprovedQuery 'SELECT DATABASE();').Trim()
if ($actualDatabase -ne $approvedDatabase) { throw '实际连接目录不符，停止。' }
$pcTables = @((Invoke-ApprovedQuery "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND LEFT(table_name,3)='pc_' ORDER BY table_name;") -split '\r?\n' | Where-Object { $_ })
$metadataSql = "SELECT 'mysql_version', VERSION(); SELECT 'paicli_tables', COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND LEFT(table_name,3)='pc_'; SELECT 'paicli_menus', COUNT(*) FROM sys_menu WHERE perms LIKE 'paicli:%'; SELECT 'role_menu_rows', COUNT(*) FROM sys_role_menu;"
if ($Mode -eq 'inspect') {
  Invoke-ApprovedQuery $metadataSql
  if ($pcTables -contains 'pc_flyway_schema_history') {
    Invoke-ApprovedQuery 'SELECT version, description, success FROM pc_flyway_schema_history ORDER BY installed_rank;'
  }
  exit 0
}
$hasHistory = $pcTables -contains 'pc_flyway_schema_history'
if (-not $hasHistory -and (-not $RegisterExistingDatabase -or $pcTables.Count -ne 0)) {
  throw '既有库首次登记需显式-RegisterExistingDatabase，且不得存在未登记pc_表。未执行DDL。'
}

$migrationSource = Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-paicli/paicli-api/src/main/resources/db/paicli-migration'
$migrationFiles = @(Get-ChildItem -LiteralPath $migrationSource -Filter 'V*__*.sql' | Sort-Object Name)
if ($migrationFiles.Count -lt 4) { throw '完整功能迁移脚本不齐，未执行DDL。' }
foreach ($file in $migrationFiles) {
  $sqlText = [IO.File]::ReadAllText($file.FullName)
  if ($sqlText -match '(?im)^\s*(DROP|TRUNCATE|DELETE|CREATE\s+DATABASE)\b') {
    throw '迁移脚本存在超出本次新增表及字段范围的动作，停止。'
  }
}

$runName = 'paicli-migration-' + [DateTime]::Now.ToString('yyyyMMddTHHmmss') + '-' + [Guid]::NewGuid().ToString('N')
$privateRun = Join-Path ([Environment]::GetFolderPath('UserProfile')) $runName
[void][IO.Directory]::CreateDirectory($privateRun)
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$rule = [Security.AccessControl.FileSystemAccessRule]::new($currentSid,
  [Security.AccessControl.FileSystemRights]::FullControl,
  [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',
  [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $privateRun -AclObject $acl
$classes = Join-Path $privateRun 'classes'
$frozenMigrations = Join-Path $classes 'db/paicli-migration'
[void][IO.Directory]::CreateDirectory($frozenMigrations)
$frozen = @()
foreach ($file in $migrationFiles) {
  $beforeHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
  $destination = Join-Path $frozenMigrations $file.Name
  Copy-Item -LiteralPath $file.FullName -Destination $destination
  if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $beforeHash) { throw '脚本冻结摘要不符。' }
  $frozen += [pscustomobject]@{ Name = $file.Name; SHA256 = $beforeHash }
}
$beforeMetadata = Invoke-ApprovedQuery $metadataSql
$dump = Join-Path $privateRun 'before.sql'
$dumpExe = (Get-Command mysqldump.exe -ErrorAction Stop).Source
$dumpArguments = @('--protocol=TCP', ('--host=' + $databaseUri.Host), '--port=3306',
  ('--user=' + $settings['RUOYI_DB_USERNAME']), '--default-character-set=utf8mb4',
  '--single-transaction', '--set-gtid-purged=OFF', '--no-tablespaces', '--skip-comments',
  $approvedDatabase, 'sys_menu', 'sys_dict_type', 'sys_dict_data') + $pcTables
Invoke-PrivateChild $dumpExe $dumpArguments @{ MYSQL_PWD = $settings['RUOYI_DB_PASSWORD'] } $dump 120
if ((Get-Item -LiteralPath $dump).Length -lt 100) { throw '迁移前备份为空，停止。' }

# 沿用既有技术报告的实际已解析依赖；新编译CLI与冻结SQL置于class path首位。
$reports = Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-paicli/paicli-api/target/surefire-reports'
$report = Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' | Select-Object -First 1
[xml]$testReport = [IO.File]::ReadAllText($report.FullName)
$testClasspath = ($testReport.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
$libraries = @($testClasspath.Split(';') | Where-Object { $_.EndsWith('.jar') -and (Test-Path -LiteralPath $_) })
$mysqlDriver = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'
if (-not (Test-Path -LiteralPath $mysqlDriver)) { throw '已安装MySQL JDBC驱动缺失，停止。' }
$classpath = (@($classes) + $libraries + @($mysqlDriver)) -join ';'
$classpathQuoted = '"' + $classpath.Replace('\', '/') + '"'
$cliSource = Join-Path $workspaceRoot 'ruoyi-backend/ruoyi-paicli/paicli-api/src/main/java/com/ruoyi/paicli/migration/PcMigrationCli.java'
$javacArguments = Join-Path $privateRun 'compile.args'
[IO.File]::WriteAllLines($javacArguments, @('--release', '17', '-encoding', 'UTF-8', '-cp', $classpathQuoted,
  '-d', ('"' + $classes.Replace('\', '/') + '"'), ('"' + $cliSource.Replace('\', '/') + '"')), [Text.UTF8Encoding]::new($false))
Invoke-PrivateChild (Get-Command javac.exe -ErrorAction Stop).Source @('@' + $javacArguments) @{} '' 120 | Out-Null
$manifest = [ordered]@{ Time = [DateTimeOffset]::Now.ToString('o'); Target = 'localhost:3306/ry-vue';
  Backup = $dump; BackupSHA256 = (Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash;
  Migrations = $frozen; Before = $beforeMetadata; ExistingDatabaseRegistration = [bool]$RegisterExistingDatabase }
$evidenceDirectory = Join-Path $workspaceRoot '文档/项目/PaiCLI智能编码项目/证据/2026-10-03-完整功能与数据库'
[void][IO.Directory]::CreateDirectory($evidenceDirectory)
$manifestFile = Join-Path $evidenceDirectory ($runName + '.json')
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestFile -Encoding utf8
$migrationEnvironment = @{ PAICLI_DB_URL = $settings['RUOYI_DB_URL']; PAICLI_DB_USER = $settings['RUOYI_DB_USERNAME'];
  PAICLI_DB_PASSWORD = $settings['RUOYI_DB_PASSWORD'] }
if (-not $hasHistory) { $migrationEnvironment['PAICLI_BASELINE_CONFIRM'] = 'BASELINE0:' + $approvedDatabase }
$operations = @()
if (-not $hasHistory) { $operations += 'baseline-existing' }
$operations += @('migrate', 'validate')
foreach ($operation in $operations) {
  foreach ($file in $migrationFiles) {
    $expected = $frozen | Where-Object { $_.Name -eq $file.Name }
    if ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne $expected.SHA256) {
      throw '冻结后源码迁移发生变化，停止；保留已执行的迁移，不自动回撤。'
    }
  }
  $javaArguments = Join-Path $privateRun ($operation + '.args')
  [IO.File]::WriteAllLines($javaArguments, @('-cp', $classpathQuoted, 'com.ruoyi.paicli.migration.PcMigrationCli',
    $operation, $approvedDatabase), [Text.UTF8Encoding]::new($false))
  Invoke-PrivateChild (Get-Command java.exe -ErrorAction Stop).Source @('@' + $javaArguments) $migrationEnvironment '' 120 | Out-Null
}
$manifest['After'] = Invoke-ApprovedQuery $metadataSql
$manifest['History'] = Invoke-ApprovedQuery 'SELECT version, description, success FROM pc_flyway_schema_history ORDER BY installed_rank;'
$manifest['Validation'] = 'Flyway validate成功；逐表业务字段与真实功能另行核对。'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestFile -Encoding utf8
$manifest | ConvertTo-Json -Depth 6
