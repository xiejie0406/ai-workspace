[CmdletBinding()]
param([switch]$Apply, [string]$DatabaseConfigPath, [string]$ExpectedDatabase='ry-vue')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$pictureRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $DatabaseConfigPath) { $DatabaseConfigPath=Join-Path $pictureRoot '.env' }
$pictureSettings=@{}
Get-Content -LiteralPath $DatabaseConfigPath | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]+)=(.*)$') { $pictureSettings[$Matches[1]]=$Matches[2].Trim().Trim('"').Trim("'") }
}
foreach ($requiredName in @('RUOYI_DB_URL','RUOYI_DB_USERNAME','RUOYI_DB_PASSWORD')) {
    if (-not $pictureSettings[$requiredName]) { throw "原配置缺少 $requiredName；不创建替代库或账号。" }
}
$pictureUrl=[regex]::Match($pictureSettings.RUOYI_DB_URL,'^jdbc:mysql://(?<server>[^/:]+)(:(?<port>\d+))?/(?<database>[^?]+)')
if (-not $pictureUrl.Success) { throw '原连接格式无法核对。' }
$pictureHost=$pictureUrl.Groups['server'].Value
$picturePort=if($pictureUrl.Groups['port'].Success){$pictureUrl.Groups['port'].Value}else{'3306'}
$pictureDatabase=$pictureUrl.Groups['database'].Value
if ($pictureDatabase -ne $ExpectedDatabase -or $pictureDatabase -match '^dev_') { throw '目标不是已核实的原数据库。' }
if ($pictureHost -notin @('localhost','127.0.0.1') -or $picturePort -ne '3306') { throw '当前授权范围是本机原 MySQL 3306；远程迁移不执行。' }
$pictureMigrationRoot=Join-Path $pictureRoot 'ruoyi-backend/ruoyi-aipicture/src/main/resources/db/migration/aipicture'
$pictureSchema=Join-Path $pictureMigrationRoot 'V001__aipicture_schema.sql'
$pictureMenus=Join-Path $pictureMigrationRoot 'V002__aipicture_menus.sql'
$pictureSql=Get-Content -LiteralPath $pictureSchema -Raw
if ($pictureSql -match '(?im)^\s*(DROP|ALTER|DELETE|TRUNCATE|CREATE\s+DATABASE|USE)\b') { throw '业务 DDL 超出新增表范围。' }
$pictureTables=@([regex]::Matches($pictureSql,'(?im)^CREATE TABLE (aip_[a-z_]+) \(') | ForEach-Object { $_.Groups[1].Value })
if ($pictureTables.Count -eq 0) { throw '未找到图片表定义。' }
$pictureMysql=(Get-Command mysql.exe -ErrorAction Stop).Source
$pictureArgs=@('--protocol=TCP','--host',$pictureHost,'--port',$picturePort,'--user',$pictureSettings.RUOYI_DB_USERNAME,'--database',$pictureDatabase,'--batch','--skip-column-names','--default-character-set=utf8mb4')
function Invoke-PictureSql([string]$Sql) {
    $pictureResult=@($Sql | & $pictureMysql @pictureArgs 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "图片迁移 SQL 执行失败；保留现有数据，不自动回滚或删表。$($pictureResult -join [Environment]::NewLine)" }
    return $pictureResult
}
$picturePriorPassword=$env:MYSQL_PWD
try {
    $env:MYSQL_PWD=$pictureSettings.RUOYI_DB_PASSWORD
    $pictureActualDatabase=@(Invoke-PictureSql 'SELECT DATABASE();')[0]
    if ($pictureActualDatabase -ne $ExpectedDatabase) { throw '实际连接数据库不一致。' }
    $pictureExisting=@(Invoke-PictureSql "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'aip_%';")
    $pictureOccupied=@(Invoke-PictureSql "SELECT menu_id FROM sys_menu WHERE (menu_id BETWEEN 950000 AND 950119 OR (parent_id=0 AND path='aipicture') OR perms LIKE 'aipicture:%') AND create_by <> 'aipicture-migration';")
    if ($pictureOccupied.Count) { throw '图片菜单候选范围被其他记录占用，不覆盖。' }
    if ($pictureExisting.Count -ne 0 -and $pictureExisting.Count -ne $pictureTables.Count) { throw '图片表存在部分迁移，停止自动应用并按原错误定向修复。' }
    if ($pictureExisting.Count) {
        foreach ($table in $pictureTables) { if ($table -notin $pictureExisting) { throw "缺少 $table，不自动补齐部分迁移。" } }
        $pictureFingerprintPath=Join-Path $pictureRoot '.runtime/aipicture/schema-applied.json'
        if (-not (Test-Path -LiteralPath $pictureFingerprintPath)) { throw '已有图片表缺少本脚本应用指纹，先核对实际结构。' }
        $picturePrevious=Get-Content -LiteralPath $pictureFingerprintPath -Raw | ConvertFrom-Json
        if ($picturePrevious.schemaSha256 -ne (Get-FileHash -LiteralPath $pictureSchema -Algorithm SHA256).Hash) { throw 'DDL 已变化，不能用重复执行冒充增量迁移。' }
    }
    [pscustomobject]@{ database=$pictureActualDatabase; tableDefinitions=$pictureTables.Count; existingPictureTables=$pictureExisting.Count; mode=$(if($Apply){'apply'}else{'read-only'}) } | ConvertTo-Json -Compress
    if (-not $Apply) { return }
    # 仅首次新增，无既有图片内容需要替换；菜单只插入缺项，不写角色授权。
    if (-not $pictureExisting.Count) { Invoke-PictureSql $pictureSql | Out-Null }
    Invoke-PictureSql (Get-Content -LiteralPath $pictureMenus -Raw) | Out-Null
    $pictureActual=@(Invoke-PictureSql "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'aip_%' ORDER BY table_name;")
    $pictureMenuCount=[int](@(Invoke-PictureSql "SELECT COUNT(*) FROM sys_menu WHERE create_by='aipicture-migration' AND menu_id BETWEEN 950000 AND 950119;")[0])
    if ($pictureActual.Count -ne $pictureTables.Count -or $pictureMenuCount -ne 21) { throw '实际表/菜单数量不符预期，保留错误现场。' }
    $pictureRunRoot=Join-Path $pictureRoot '.runtime/aipicture'
    New-Item -ItemType Directory -Path $pictureRunRoot -Force | Out-Null
    [pscustomobject]@{ appliedAt=(Get-Date).ToUniversalTime().ToString('o'); database=$pictureActualDatabase; schemaSha256=(Get-FileHash -LiteralPath $pictureSchema -Algorithm SHA256).Hash; menuSha256=(Get-FileHash -LiteralPath $pictureMenus -Algorithm SHA256).Hash; tables=$pictureActual; menuCount=$pictureMenuCount; roleGrantsWritten=0 } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $pictureRunRoot 'schema-applied.json') -Encoding utf8
    '新增图片业务表与若依菜单已核对；未创建数据库、账号或角色授权。'
} finally { $env:MYSQL_PWD=$picturePriorPassword }
