[CmdletBinding()]
param([string]$PrivateRoot = 'D:\javaData\aippt')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# 首次准备只写获批准的本项目私有目录，不导入、迁移或领取任务。
if ([IO.Path]::GetFullPath($PrivateRoot).TrimEnd('\') -ne 'D:\javaData\aippt') { throw '仅允许已批准的 D:\javaData\aippt。' }
$aipptRuntime = Join-Path $PrivateRoot 'runtime'
foreach ($directory in @($PrivateRoot, $aipptRuntime, (Join-Path $PrivateRoot 'storage'), (Join-Path $PrivateRoot 'compensation'))) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    if ((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '私有目录不能是链接。' }
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    # icacls 仅更改 DACL，不请求普通启动令牌没有的 SACL/SeSecurityPrivilege。
    & icacls.exe $directory '/inheritance:r' '/grant:r' "*$($owner.Value):(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '无法收紧私有目录访问权限。' }
    foreach ($rule in @((Get-Acl -LiteralPath $directory).Access)) {
        $sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        if ($sid -notin @($owner.Value,'S-1-5-18')) {
            & icacls.exe $directory '/remove' "*$sid" | Out-Null
            if ($LASTEXITCODE -ne 0) { throw '无法移除私有目录的额外访问权限。' }
        }
    }
}
$aipptConfig = Join-Path $aipptRuntime 'local.env'
if (Test-Path -LiteralPath $aipptConfig) {
    # 不轮换已有配对身份，不覆盖用户后补的模型配置。
    if ((Get-Item -LiteralPath $aipptConfig).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '配置不能是链接。' }
    & icacls.exe $aipptConfig '/inheritance:r' '/grant:r' "*$($owner.Value):F" '*S-1-5-18:F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '无法收紧私有文件访问权限。' }
    foreach ($rule in @((Get-Acl -LiteralPath $aipptConfig).Access)) {
        $sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        if ($sid -notin @($owner.Value,'S-1-5-18')) { & icacls.exe $aipptConfig '/remove' "*$sid" | Out-Null; if ($LASTEXITCODE -ne 0) { throw '无法移除私有文件额外访问权限。' } }
    }
    Write-Output "已有配置，保留：$aipptConfig"
    return
}
$randomBytes = [byte[]]::new(48)
[Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
$aipptKey = [Convert]::ToBase64String($randomBytes)
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$contents = @(
    'AIPPT_ENABLED=true', 'AIPPT_ENVIRONMENT=aippt-local', 'AIPPT_REDIS_ENABLED=true',
    "AIPPT_STORAGE_PATH=$(Join-Path $PrivateRoot 'storage')", "AIPPT_COMPENSATION_PATH=$(Join-Path $PrivateRoot 'compensation')",
    'AIPPT_STORAGE_DRIVER=local', 'AIPPT_WORKER_ENABLED=true', "AIPPT_WORKER_KEY=$aipptKey",
    'AIPPT_CENTER_URL=http://127.0.0.1:18097', 'AIPPT_WORKER_ID=aippt-local-worker', 'AIPPT_CONCURRENCY=1',
    'AIPPT_CLAIM_TYPES=["parse_source","quality","export"]',
    'AIPPT_PROVIDER_ENABLED=false', 'AIPPT_TEXT_ENABLED=false', 'AIPPT_IMAGE_ENABLED=false', 'AIPPT_LLM_MAX_RETRIES=0',
    "AIPPT_FONTS_DIR=$(Join-Path $PrivateRoot 'fonts')", "AIPPT_FONT_MANIFEST_PATH=$(Join-Path $PrivateRoot 'fonts/manifest.json')",
    "AIPPT_CATALOG_DIR=$(Join-Path $workspace 'contracts/aippt/catalog')", 'AIPPT_FONT_MANIFEST_HASH=unavailable'
)
[IO.File]::WriteAllLines($aipptConfig, $contents, [Text.UTF8Encoding]::new($false))
[Array]::Clear($randomBytes, 0, $randomBytes.Length)
$aipptKey = $null
Write-Output "本机私有配置已准备：$aipptConfig；配对凭据仅写入私有文件。"
