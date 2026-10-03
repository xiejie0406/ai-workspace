[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evidence=Join-Path $root '文档/若依框架/证据/2026-09-30-四项目启动'
$source=Join-Path $evidence '实际结果.json'
$record=Get-Content -LiteralPath $source -Raw | ConvertFrom-Json
function Escape-Html($value) { [Net.WebUtility]::HtmlEncode([string]$value) }
$cards=[Collections.Generic.List[string]]::new()
$counts=[Collections.Generic.List[string]]::new()
foreach($type in @('EV','UI')) {
    $items=@($record.items|Where-Object type -eq $type)
    $counts.Add("<p><b>$type 共 $($items.Count) 项</b>：" + ((@('Pass','Fail','Blocked','NotRun','NotApplicable')|ForEach-Object { "$_ $(@($items|Where-Object result -eq $_).Count)" }) -join ' / ') + '</p>')
}
foreach($item in $record.items) {
    $screens=[Collections.Generic.List[string]]::new()
    foreach($link in $item.evidence) {
        $href=Escape-Html($link)
        if($link -match '\.(jpg|png)$') { $screens.Add("<figure><a href='$href'><img src='$href' alt='$(Escape-Html $item.id) 实际截图'></a><figcaption>$(Escape-Html $item.id)：实际采集并回读；点击查看原图</figcaption></figure>") }
        else { $screens.Add("<p><a href='$href'>$(Escape-Html $link)</a></p>") }
    }
    $steps=if($item.PSObject.Properties.Name -contains 'steps') { ($item.steps | ForEach-Object { Escape-Html $_ }) -join ' → ' } else { '进程身份、监听、HTTP、数据库和日志回读；完整步骤见清单。' }
    $expected=if($item.PSObject.Properties.Name -contains 'expected') { Escape-Html $item.expected } else { '本机独立服务就绪，专属测试环境明确；不将启动结果计入业务 UAT。' }
    $entry=if($item.PSObject.Properties.Name -contains 'url') { Escape-Html $item.url } else { '本机运行目录 .runtime/four-projects；对应端口见运行回读。' }
    $cards.Add("<article id='$(Escape-Html $item.id)'><h2>$(Escape-Html $item.id) · $(Escape-Html $item.goal)</h2><p><span class='badge $(Escape-Html $item.result)'>$(Escape-Html $item.result)</span> 截图：$(Escape-Html $item.screenshotStatus)</p><dl><dt>入口/输入</dt><dd>$entry；仓库合成平台 SQL 与空业务测试库</dd><dt>操作</dt><dd>$steps</dd><dt>预期</dt><dd>$expected</dd><dt>实际</dt><dd>$(Escape-Html $item.actual)</dd><dt>限制/遗留</dt><dd>$(Escape-Html $item.limitations)</dd></dl>$($screens -join '')</article>")
}
$html=@"
<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>四项目启动技术验证与验收准备</title>
<style>body{margin:0;background:#f4f7fb;color:#18334a;font:16px/1.7 system-ui,'Microsoft YaHei',sans-serif}main{max-width:1100px;margin:auto;padding:32px}header,article,aside{background:white;border:1px solid #dae4ee;border-radius:14px;padding:24px;margin:20px 0}h1{font-size:28px;margin-top:0}h2{font-size:21px}a{color:#1858ac}dt{font-weight:700}dd{margin:0 0 12px;overflow-wrap:anywhere}.badge{padding:4px 10px;border-radius:5px;background:#e8edf3}.Pass{background:#dcf7e9}.Blocked,.NotRun{background:#fff0d6}.Fail{background:#ffdddd}img{max-width:100%;height:auto;border:1px solid #ddd}figure{margin:20px 0}figcaption{font-size:13px;color:#536577}code{overflow-wrap:anywhere}nav a{margin-right:14px;display:inline-block}</style>
<main><header><h1>四项目启动技术验证与验收准备</h1><p>时间：$(Escape-Html $record.date) / Asia/Shanghai；执行者 Codex；用户决定：<b>$(Escape-Html $record.userDecision)</b></p><p>$(Escape-Html $record.scope)</p><p>后端 SHA-256：<code>$(Escape-Html $record.backendSha256)</code></p><p>构建、技术 EV、入口场景、用户接受分别记录。入口 Pass 不表示业务功能通过。</p>$($counts -join '')<nav>$(( $record.items | ForEach-Object { "<a href='#$(Escape-Html $_.id)'>$(Escape-Html $_.id)</a>" }) -join '')</nav><p><a href='启动与验收准备清单.md'>执行前清单</a> · <a href='实际结果.json'>唯一输入证据</a> · <a href='运行回读.json'>运行回读</a> · <a href='数据库隔离回读.txt'>数据库隔离回读</a></p></header>
<aside><b>环境与边界</b><p>四个独立 JVM、四个 Redis、四个平台测试库；APS 另有业务库，面试 PostgreSQL 仅复制 schema，业务数据为空。Python Fashion 为 demo，Runner 为 simulator。源码、Maven 构建及开发前端 HMR 仍共享，未完成工作树隔离。管理端标题仍使用共享“AI 面试教练管理后台”，后续由各项目核对。</p><p>浏览器通道：内置 IAB，本任务新建标签；内核版本未知。三项网页入口的 Console 无 warn/error；Network 证据为同源 HTTP 回读与启动配置，未采集浏览器 Network 全量。截图尺寸按实际文件；排产首次截图不完整，保留原图并重采完整页，未用不完整图判定通过。</p><p>历史业务验收不改写：面试 U-01～08 未执行；选品新版手工项未执行且旧版本曾被用户不接受；Aden 新版 MANUAL-28～35 未执行；排产旧联机存在 Fail/Blocked，完整 UAT 未完成。具体事实以四个新对话读取的 Feature 唯一记录为准。</p></aside>
$($cards -join '')<aside><h2>首次失败与复验</h2><p>选品首次启动因 Flyway 查询 MySQL performance_schema.user_variables_by_thread 权限不足失败；仅在新建合成实例授予这一项元数据读取权限后重新启动，成功。旧日志保留；业务数据库账号仍限制到各自库，跨项目查询测试明确拒绝。原 PowerShell 启动工具的日期解析与参数变量问题已修正，单项目 fashion 幂等启动退出 0 且原 PID 保持。</p><h2>生成与核对</h2><p>本报告由 scripts/dev/render-startup-report.ps1 从实际结果.json 生成；结果先写证据，HTML 不另维护业务事实。Java 19 模块构建成功（测试跳过）；Portal 和管理端生产构建成功。八项ID、状态和所引用文件已完成静态核对；三个网页截图已逐张回读。全仓文档检查仍因既有日志失链和旧英文文件名等失败，本轮未改写其他项目历史文件。</p><p><b>报告视觉检查未执行：</b>自动审批审查拒绝启动本地HTML预览服务，只返回 blocked by policy，未提供具体理由。open_in_codex 文件打开请求为 queued，不能据此声称报告已实际渲染检查。该限制不影响已运行的四个项目服务。</p></aside></main></html>
"@
Set-Content -LiteralPath (Join-Path $evidence 'verification-report.html') -Value $html -Encoding utf8
Write-Output "生成 $($record.items.Count) 项报告。"
