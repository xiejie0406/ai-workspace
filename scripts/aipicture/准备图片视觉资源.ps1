[CmdletBinding()]
param([switch]$Apply, [switch]$SelfCheck)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pictureRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$picturePython = Join-Path $pictureRoot 'python/aipicture-runtime/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $picturePython -PathType Leaf)) { throw '需要本项目现有 Python 3.13 虚拟环境；本脚本不下载 Python。' }
if ($Apply) {
    $pictureActive = @(Get-CimInstance Win32_Process | Where-Object {
        # 虚拟环境子进程可能使用基础 Python 路径；图片模块命令才是服务身份。
        $_.CommandLine -match '(^|\s)-m\s+aipicture_runtime\s+(serve|worker)(\s|$)'
    })
    $pictureTracked = @()
    $pictureManifest = Join-Path $pictureRoot '.runtime/aipicture/processes.json'
    if (Test-Path -LiteralPath $pictureManifest -PathType Leaf) {
        $pictureTracked = @((Get-Content -LiteralPath $pictureManifest -Raw | ConvertFrom-Json).processes | Where-Object {
            $_.name -like 'python-*' -and (Get-Process -Id $_.pid -ErrorAction SilentlyContinue)
        })
    }
    if ($pictureActive.Count -or $pictureTracked.Count) { throw '安装前先按图片通道进程清单停止本项目 Python serve/worker；本脚本不会自动停止进程。' }
}
$pictureArguments = @('-B', (Join-Path $pictureRoot 'python/aipicture-runtime/scripts/prepare_cv_resources.py'))
if ($Apply) { $pictureArguments += '--apply' }
if ($SelfCheck) { $pictureArguments += '--self-check' }
& $picturePython @pictureArguments
if ($LASTEXITCODE -ne 0) { throw "视觉资源检查退出码 $LASTEXITCODE；保持原中心/Provider配置，按报告处理后再启用。" }
