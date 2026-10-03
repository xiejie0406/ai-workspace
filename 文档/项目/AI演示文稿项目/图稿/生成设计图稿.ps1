$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot '生成React接入图稿.py')
if ($LASTEXITCODE -ne 0) { throw 'SVG图稿生成失败' }
& node (Join-Path $PSScriptRoot '离线渲染图稿.cjs')
if ($LASTEXITCODE -ne 0) { throw 'PNG图稿渲染失败' }
