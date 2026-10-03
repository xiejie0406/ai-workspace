[CmdletBinding()]
param([string]$EvidenceDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$desktopRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repoRoot=[IO.Path]::GetFullPath((Join-Path $desktopRoot '../../..'))
if (-not $EvidenceDirectory) { throw '请先展示并保存本轮验收清单，再提供 -EvidenceDirectory；本入口不创建数据库、账号或合成商品。' }
$env:ADEN_E2E_EVIDENCE_DIR=[IO.Path]::GetFullPath($EvidenceDirectory)
$env:ADEN_EXISTING_WORKSPACE_ROOT=$repoRoot
Push-Location $desktopRoot
try {
    & node 'tests/e2e/collection.e2e.mjs'
    if ($LASTEXITCODE -ne 0) { throw "安装版定向检查退出码 $LASTEXITCODE；实际失败和截图保留在证据目录。" }
} finally { Pop-Location }
