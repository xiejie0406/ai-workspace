param([string]$Workspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference = 'Stop'
$run = Join-Path $Workspace '.runtime/paismart/menu-registration'
New-Item -ItemType Directory -Path $run -Force | Out-Null
$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $Workspace '.env')) {
    if ($line -match '^\s*(RUOYI_DB_(?:URL|USERNAME|PASSWORD))\s*=(.*)$') {
        $value = $Matches[2].Trim(); $name = $Matches[1]
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) { $value = $value.Substring(1,$value.Length-2) }
        $values[$name] = $value
    }
}
$db = [Uri]($values.RUOYI_DB_URL -replace '^jdbc:','')
if ($db.Host -notin @('localhost','127.0.0.1') -or $db.Port -ne 3306 -or $db.AbsolutePath -ne '/ry-vue') { throw '只能登记已批准原本机ry-vue菜单' }
$mysql = 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
function Invoke-MenuSql([string]$Sql) {
    $result = @(& $mysql --protocol=TCP --host=$($db.Host) --port=3306 --user=$($values.RUOYI_DB_USERNAME) --database=ry-vue --batch --skip-column-names --default-character-set=utf8mb4 --execute=$Sql 2>&1)
    if ($LASTEXITCODE -ne 0) { throw '菜单SQL失败，请查数据库状态；未回显连接秘密' }
    return $result
}
$prior = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $values.RUOYI_DB_PASSWORD
    $conflict = @(Invoke-MenuSql "SELECT COUNT(*) FROM sys_menu WHERE (path='paismart' AND parent_id=0 OR perms LIKE 'paismart:%' OR component LIKE 'paismart/%') AND (create_by IS NULL OR create_by<>'paismart-migration-v1');")
    if ([int]$conflict[0] -gt 0) { throw '存在其他归属的PaiSmart菜单或权限，停止以保护原记录' }
    $before = @(Invoke-MenuSql "SELECT JSON_OBJECT('menuId',menu_id,'parentId',parent_id,'name',menu_name,'path',path,'component',component,'perms',perms,'owner',create_by) FROM sys_menu WHERE path='paismart' AND parent_id=0 OR perms LIKE 'paismart:%' OR component LIKE 'paismart/%' ORDER BY menu_id;")
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $before | Set-Content -LiteralPath (Join-Path $run ('before-'+$stamp+'.jsonl')) -Encoding utf8
    $script = Join-Path $Workspace 'ruoyi-backend/ruoyi-paismart/src/main/resources/db/paismart-menu/菜单增量预演.sql'
    $sql = Get-Content -LiteralPath $script -Raw
    if ($sql -notmatch 'ROLLBACK;\s*$' -or $sql -match '(?im)^\s*(DELETE|UPDATE|DROP|ALTER|TRUNCATE|GRANT)\b') { throw '预演脚本范围已变，需重新核对' }
    $sql = $sql -replace 'ROLLBACK;\s*$','COMMIT;'
    Invoke-MenuSql $sql | Out-Null
    $after = Invoke-MenuSql "SELECT menu_type,COUNT(*) FROM sys_menu WHERE create_by='paismart-migration-v1' GROUP BY menu_type ORDER BY menu_type;"
    $metadata = Invoke-MenuSql "SELECT JSON_OBJECT('menuId',menu_id,'parentId',parent_id,'name',menu_name,'path',path,'component',component,'perms',perms,'owner',create_by) FROM sys_menu WHERE create_by='paismart-migration-v1' ORDER BY menu_id;"
    $metadata | Set-Content -LiteralPath (Join-Path $run ('after-'+$stamp+'.jsonl')) -Encoding utf8
    $record = [ordered]@{capturedAt=(Get-Date).ToString('o');database='localhost:3306/ry-vue';source=$script;sourceSha256=(Get-FileHash $script).Hash.ToLowerInvariant();beforeCount=$before.Count;afterCounts=$after;scope='仅现有PaiSmart菜单增量；无账号/角色创建或授权';recovery='按本轮after与before差集先核对后定向恢复，不删除其他新增菜单';}
    $record | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $run 'registration.json') -Encoding utf8
    $record | ConvertTo-Json -Depth 5
} finally { $env:MYSQL_PWD = $prior }
