$ErrorActionPreference = 'Stop'

$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Get-WorkspaceRelativePath([string]$Path) {
    $basePath = $workspaceRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar
    $baseUri = [System.Uri]::new($basePath)
    $pathUri = [System.Uri]::new([System.IO.Path]::GetFullPath($Path))
    return [System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($pathUri).ToString()).Replace(
        '/',
        [System.IO.Path]::DirectorySeparatorChar
    )
}

$requiredPaths = @(
    'AGENTS.md',
    '文档/README.md',
    '文档/若依框架/README.md',
    '文档/若依框架/多项目并行开发启动指南.md',
    '文档/项目/README.md',
    '文档/项目/面试项目/README.md',
    '文档/项目/智能选品项目/README.md',
    '文档/项目/智能体桌面端项目/README.md',
    '文档/项目/生产排产项目/README.md',
    '文档/开发记录',
    '文档/规范/README.md',
    '文档/规范/文档组织规范.md',
    '文档/归档/README.md'
)
$forbiddenCurrentPaths = @(
    '文档/产品',
    '文档/功能',
    '文档/架构',
    '文档/调研',
    '文档/参考',
    '文档/许可',
    '文档/阶段',
    '文档/原型',
    '文档/评审',
    '文档/项目调研',
    '文档/环境配置',
    '文档/项目/AI面试教练',
    '文档/项目/01-生产排程与车间调度',
    '文档/项目/02-Aden桌面智能执行平台',
    '文档/项目/03-服装智能选品与报价',
    'docs',
    '0word需求文档'
)

$errors = [System.Collections.Generic.List[string]]::new()
$docsRoot = Join-Path $workspaceRoot '文档'

foreach ($relativePath in $requiredPaths) {
    $path = Join-Path $workspaceRoot $relativePath
    if (-not (Test-Path -LiteralPath $path)) {
        $errors.Add("Missing required path: $relativePath")
    }
}

foreach ($relativePath in $forbiddenCurrentPaths) {
    $path = Join-Path $workspaceRoot $relativePath
    if (Test-Path -LiteralPath $path) {
        $errors.Add("Legacy directory must not be a current entry: $relativePath")
    }
}

$expectedDocumentRootDirectories = @(
    '归档',
    '规范',
    '开发记录',
    '若依框架',
    '项目'
)
$actualDocumentRootDirectories = @(
    Get-ChildItem -LiteralPath $docsRoot -Directory |
        ForEach-Object Name |
        Sort-Object
)
$expectedDocumentRootSorted = @($expectedDocumentRootDirectories | Sort-Object)
if (($actualDocumentRootDirectories -join '|') -ne ($expectedDocumentRootSorted -join '|')) {
    $errors.Add(
        "Document root directory set mismatch: expected $($expectedDocumentRootSorted -join ', '); actual $($actualDocumentRootDirectories -join ', ')"
    )
}

$expectedProjectDirectories = @(
    '面试项目',
    '智能选品项目',
    '智能体桌面端项目',
    '生产排产项目',
    'AI图片编辑项目',
    'AI演示文稿项目',
    'PaiSmart知识库项目',
    'PaiFlow工作流项目',
    'PaiCLI智能编码项目'
)
$projectsRoot = Join-Path $docsRoot '项目'
if (Test-Path -LiteralPath $projectsRoot -PathType Container) {
    $actualProjectDirectories = @(
        Get-ChildItem -LiteralPath $projectsRoot -Directory |
            ForEach-Object Name |
            Sort-Object
    )
    $expectedSorted = @($expectedProjectDirectories | Sort-Object)
    if (($actualProjectDirectories -join '|') -ne ($expectedSorted -join '|')) {
        $errors.Add(
            "Project directory set mismatch: expected $($expectedSorted -join ', '); actual $($actualProjectDirectories -join ', ')"
        )
    }
}

if (Test-Path -LiteralPath $docsRoot -PathType Container) {
    $documentDirectories = @((Get-Item -LiteralPath $docsRoot)) + @(
        Get-ChildItem -LiteralPath $docsRoot -Directory -Recurse
    )
    foreach ($directory in $documentDirectories) {
        if ($directory.Name -notmatch '[\u4e00-\u9fff]') {
            $relativeDirectory = Get-WorkspaceRelativePath $directory.FullName
            $errors.Add("Document directory does not use a Chinese name: $relativeDirectory")
        }
    }
}

$docsHumanReadableFiles = @(
    Get-ChildItem -LiteralPath $docsRoot -File -Recurse |
        Where-Object { $_.Extension -in '.md', '.html', '.docx', '.pdf', '.txt' }
)

foreach ($file in $docsHumanReadableFiles) {
    # 用户级验收规范 2026.09-r3 固定此 HTML 产物名，优先于项目中文命名约定。
    if ($file.Name -eq 'README.md' -or $file.Name -like 'LICENSE*' -or
        $file.Name -eq 'verification-report.html') {
        continue
    }
    if ($file.BaseName -notmatch '[\u4e00-\u9fff]') {
        $relativeFile = Get-WorkspaceRelativePath $file.FullName
        $errors.Add("Human-readable document does not use a Chinese filename: $relativeFile")
    }
}

$markdownFiles = @(
    Join-Path $workspaceRoot 'README.md'
    Join-Path $workspaceRoot 'AGENTS.md'
) + @(
    $docsHumanReadableFiles |
        Where-Object Extension -eq '.md' |
        ForEach-Object FullName
    Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'prototype') -File -Recurse -Filter '*.md' |
        Where-Object FullName -NotMatch '[\\/](node_modules|vendor|target|output)[\\/]' |
        ForEach-Object FullName
)
$additionalMarkdownFiles = @(
    Join-Path $workspaceRoot 'ruoyi-backend\AI-MIGRATION.md'
)
$markdownFiles += @($additionalMarkdownFiles | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
$markdownFiles = @($markdownFiles | Sort-Object -Unique)
$htmlFiles = @(
    $docsHumanReadableFiles |
        Where-Object Extension -eq '.html' |
        ForEach-Object FullName
    Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'prototype') -File -Recurse |
        Where-Object {
            $_.Extension -in '.html', '.htm' -and
            $_.FullName -NotMatch '[\\/](node_modules|vendor|target|output)[\\/]'
        } |
        ForEach-Object FullName
)
$htmlFiles = @($htmlFiles | Sort-Object -Unique)

$linkPattern = [regex]'!?\[[^\]]*\]\(([^)]+)\)'
foreach ($file in $markdownFiles) {
    $content = [System.IO.File]::ReadAllText($file)
    foreach ($match in $linkPattern.Matches($content)) {
        $target = $match.Groups[1].Value.Trim()
        if ($target.StartsWith('<') -and $target.EndsWith('>')) {
            $target = $target.Substring(1, $target.Length - 2)
        }
        $target = $target.Split('#')[0]
        if ([string]::IsNullOrWhiteSpace($target) -or
            $target -match '^(https?://|mailto:|data:|javascript:|[A-Za-z]:[/\\])') {
            continue
        }

        $decodedTarget = [uri]::UnescapeDataString($target)
        $resolvedTarget = [System.IO.Path]::GetFullPath(
            (Join-Path (Split-Path -Parent $file) $decodedTarget)
        )
        if (-not (Test-Path -LiteralPath $resolvedTarget)) {
            $relativeFile = Get-WorkspaceRelativePath $file
            $errors.Add("Broken link: $relativeFile -> $target")
        }

        $relativeFile = Get-WorkspaceRelativePath $file
        $isArchivedDocument = $relativeFile -match '(^|[\\/])归档([\\/]|$)'
        if (-not $isArchivedDocument -and
            $target -match '(^|/)(backend|apps)/') {
            $errors.Add("Current document links to a removed project path: $relativeFile -> $target")
        }
    }
}

$htmlLinkPattern = [regex]'(?i)(?:href|src)\s*=\s*["'']([^"'']+)["'']'
foreach ($file in $htmlFiles) {
    $content = [System.IO.File]::ReadAllText($file)
    foreach ($match in $htmlLinkPattern.Matches($content)) {
        $target = $match.Groups[1].Value.Trim().Split('#')[0].Split('?')[0]
        if ([string]::IsNullOrWhiteSpace($target) -or
            $target -match '^(https?://|mailto:|data:|javascript:|#|/|[A-Za-z]:[/\\])') {
            continue
        }

        $decodedTarget = [uri]::UnescapeDataString($target)
        $resolvedTarget = [System.IO.Path]::GetFullPath(
            (Join-Path (Split-Path -Parent $file) $decodedTarget)
        )
        if (-not (Test-Path -LiteralPath $resolvedTarget)) {
            $relativeFile = Get-WorkspaceRelativePath $file
            $errors.Add("Broken HTML link: $relativeFile -> $target")
        }
    }
}

if ($errors.Count -gt 0) {
    $errors | Sort-Object -Unique | ForEach-Object { Write-Output "ERROR: $_" }
    exit 1
}

Write-Output "PASS: five-category structure, Chinese documentation names, and links in $($markdownFiles.Count) Markdown and $($htmlFiles.Count) HTML files."
