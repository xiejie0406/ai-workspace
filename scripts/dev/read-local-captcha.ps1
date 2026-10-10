<#
.SYNOPSIS
按后端端口和验证码 UUID 读取当前用户专用的本机验收验证码。
.DESCRIPTION
默认标准输出仅包含答案。MetadataOnly 只返回不含答案的元信息。
文件固定来自当前用户主目录，不接受任意路径；不访问浏览器、账号或网络。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int]$BackendPort,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-fA-F0-9]{32}$')]
    [string]$Uuid,

    [switch]$MetadataOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-PrivateCaptchaPath {
    param([string]$LiteralPath, [bool]$IsDirectory, [string]$UserSid, [string]$PathStage)

    $script:checkStageId = $PathStage + '_NOT_FOUND'
    $item = Get-Item -LiteralPath $LiteralPath -Force -ErrorAction Stop
    $script:checkStageId = $PathStage + '_REPARSE'
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw '验证码路径不能是符号链接或重解析点。'
    }
    $script:checkStageId = $PathStage + '_TYPE'
    if ($item.PSIsContainer -ne $IsDirectory) {
        throw '验证码路径类型不符合要求。'
    }
    $script:checkStageId = $PathStage + '_ACL'
    # 只读取所有者和 DACL，不请求审计权限，也不依赖跨版本 Security 模块自动加载。
    $sections = [System.Security.AccessControl.AccessControlSections]::Owner -bor
        [System.Security.AccessControl.AccessControlSections]::Access
    if ($PSVersionTable.PSVersion.Major -lt 6) {
        if ($IsDirectory) {
            $acl = [System.IO.Directory]::GetAccessControl($LiteralPath, $sections)
        } else {
            $acl = [System.IO.File]::GetAccessControl($LiteralPath, $sections)
        }
    } else {
        $acl = [System.IO.FileSystemAclExtensions]::GetAccessControl($item, $sections)
    }
    $script:checkStageId = $PathStage + '_OWNER'
    $ownerSid = $acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value
    if ($ownerSid -ne $UserSid) {
        throw '验证码路径所有者不是当前 Windows 用户。'
    }
    $hasCurrentUserAllow = $false
    foreach ($rule in $acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])) {
        $script:checkStageId = $PathStage + '_OTHER_ALLOW'
        if ($rule.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Allow -and
            $rule.IdentityReference.Value -ne $UserSid) {
            throw '验证码路径允许其他 Windows 身份访问，已拒绝读取。'
        }
        if ($rule.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Allow -and
            $rule.IdentityReference.Value -eq $UserSid) {
            $hasCurrentUserAllow = $true
        }
    }
    $script:checkStageId = $PathStage + '_NO_ALLOW'
    if (-not $hasCurrentUserAllow) {
        throw '验证码路径没有明确的当前用户独占访问规则。'
    }
    return $item
}

function ConvertFrom-CaptchaUtc {
    param([object]$Value)

    # 后端写入 ISO UTC 时间；拒绝本地时间和缺少时区的字符串。
    if ($Value -isnot [string] -or $Value -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$') {
        throw '验证码时间格式无效。'
    }
    $parsed = [DateTimeOffset]::MinValue
    # Java Instant 可有 9 位小数，.NET 时间精度为 7 位，安全截断更细精度。
    $normalized = $Value -replace '(\.\d{7})\d{1,2}Z$', '${1}Z'
    if (-not [DateTimeOffset]::TryParse($normalized, [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$parsed)) {
        throw '验证码时间格式无效。'
    }
    return $parsed
}

$checkStage = '运行环境'
$checkStageId = 'ENVIRONMENT'
try {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw '本脚本仅支持 Windows 当前用户专用验收通道。'
    }
    $checkStage = '路径和当前用户专用权限'
    $checkStageId = 'PRIVATE_PATH'
    $currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $userDirectory = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $captchaRoot = Join-Path $userDirectory '.ruoyi-local-captcha'
    $portDirectory = Join-Path $captchaRoot ([string]$BackendPort)
    $captchaFile = Join-Path $portDirectory ($Uuid.ToLowerInvariant() + '.json')
    $null = Assert-PrivateCaptchaPath $captchaRoot $true $currentSid 'ROOT'
    $null = Assert-PrivateCaptchaPath $portDirectory $true $currentSid 'CHANNEL'
    $file = Assert-PrivateCaptchaPath $captchaFile $false $currentSid 'FILE'
    $checkStage = '文件大小和内容读取'
    $checkStageId = 'READ'
    if ($file.Length -gt 2048 -or $file.Length -le 0) {
        throw '验证码文件大小无效。'
    }

    # 读取期间不允许另一个进程写入或删除本文件，避免读到半份更新。
    $stream = [System.IO.File]::Open($captchaFile, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    try {
        $null = Assert-PrivateCaptchaPath $captchaRoot $true $currentSid 'ROOT'
        $null = Assert-PrivateCaptchaPath $portDirectory $true $currentSid 'CHANNEL'
        $null = Assert-PrivateCaptchaPath $captchaFile $false $currentSid 'FILE'
        $checkStageId = 'READ'
        if ($stream.Length -gt 2048 -or $stream.Length -le 0) {
            throw '验证码文件大小无效。'
        }
        $reader = New-Object System.IO.StreamReader($stream, (New-Object System.Text.UTF8Encoding($false, $true)), $true)
        try { $json = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $stream.Dispose() }

    $checkStage = 'JSON格式'
    $checkStageId = 'JSON'
    # PowerShell 7.5 默认把 ISO 时间转成 DateTime；保留字符串后再统一检查 UTC 格式。
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) {
        $record = $json | ConvertFrom-Json -DateKind String -ErrorAction Stop
    } else {
        $record = $json | ConvertFrom-Json -ErrorAction Stop
    }
    $checkStage = 'UUID和后端端口匹配'
    $checkStageId = 'MATCH'
    if ($record.uuid -isnot [string] -or $record.uuid -cne $Uuid.ToLowerInvariant() -or
        ($record.backendPort -isnot [int] -and $record.backendPort -isnot [long]) -or
        $record.backendPort -ne $BackendPort) {
        throw '验证码 UUID 或后端端口不匹配。'
    }
    $checkStage = '答案格式'
    $checkStageId = 'CODE_FORMAT'
    if ($record.code -isnot [string] -or $record.code -notmatch '^[A-Za-z0-9-]{1,16}$') {
        throw '验证码答案格式无效。'
    }
    $checkStage = 'UTC时间和有效期'
    $checkStageId = 'TIME'
    $createdAt = ConvertFrom-CaptchaUtc $record.createdAt
    $expiresAt = ConvertFrom-CaptchaUtc $record.expiresAt
    $now = [DateTimeOffset]::UtcNow
    if ($createdAt -gt $now.AddSeconds(5) -or $expiresAt -le $createdAt -or
        $expiresAt -gt $createdAt.AddMinutes(2)) {
        throw '验证码生成时间或有效期不合理。'
    }
    if ($expiresAt -le $now) {
        # 只删除校验过的这个过期 UUID 文件，不扫描或清理其他验证码。
        $null = Assert-PrivateCaptchaPath $captchaRoot $true $currentSid 'ROOT'
        $null = Assert-PrivateCaptchaPath $portDirectory $true $currentSid 'CHANNEL'
        $null = Assert-PrivateCaptchaPath $captchaFile $false $currentSid 'FILE'
        $checkStage = '过期验证码清理'
        $checkStageId = 'EXPIRED_CLEANUP'
        Remove-Item -LiteralPath $captchaFile -Force -ErrorAction Stop
        $checkStage = '验证码已过期并已清理'
        $checkStageId = 'EXPIRED'
        throw '验证码已过期并已清理，请刷新页面验证码后重新读取。'
    }
    if ($MetadataOnly) {
        [ordered]@{
            uuid = $record.uuid
            backendPort = $BackendPort
            createdAt = $record.createdAt
            expiresAt = $record.expiresAt
            valid = $true
        } | ConvertTo-Json -Compress
    } else {
        [Console]::Out.WriteLine($record.code)
    }
} catch {
    # JSON 解析异常可能包含原文，统一处理，不能将答案泄露到诊断输出。
    [Console]::Error.WriteLine(('本机验收验证码读取失败：{0}检查未通过。请确认开关已启用，并刷新当前验证码。[stage={1}]' -f $checkStage, $checkStageId))
    exit 1
}
