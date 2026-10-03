$ErrorActionPreference = 'Stop'
$outputDir = $PSScriptRoot
$script:svg = [System.Text.StringBuilder]::new()
function E([string]$s) { [System.Security.SecurityElement]::Escape($s) }
function SvgRect($x,$y,$w,$h,$fill='#ffffff',$stroke='#dfe4ed',$radius=6) {
    [void]$script:svg.AppendLine("<rect x='$x' y='$y' width='$w' height='$h' rx='$radius' fill='$fill' stroke='$stroke'/>")
}
function T($x,$y,[string]$s,$size=16,$fill='#303745',$weight=400) {
    [void]$script:svg.AppendLine("<text x='$x' y='$y' font-size='$size' fill='$fill' font-weight='$weight'>$(E $s)</text>")
}
function B($x,$y,[string]$s,$w=120,$primary=$false) {
    if($primary){SvgRect $x $y $w 36 '#409eff' '#409eff'; T ($x+12) ($y+24) $s 14 '#ffffff' 600}
    else{SvgRect $x $y $w 36; T ($x+12) ($y+24) $s 14}
}
function F($x,$y,[string]$label,[string]$value,$w=300) {
    T $x $y $label 14 '#657184'; SvgRect $x ($y+10) $w 42; T ($x+12) ($y+37) $value 15
}
function Shell([string]$title,[string]$sub) {
    $script:svg = [System.Text.StringBuilder]::new()
    [void]$script:svg.AppendLine("<svg xmlns='http://www.w3.org/2000/svg' width='1440' height='900' viewBox='0 0 1440 900'><title>$(E $title) — 待审核设计图</title><g font-family='Microsoft YaHei, Noto Sans CJK SC, sans-serif'>")
    SvgRect 0 0 1440 900 '#f3f5f8' '#f3f5f8' 0
    SvgRect 0 0 190 900 '#263445' '#263445' 0
    T 24 40 '共享若依平台' 21 '#ffffff' 600
    T 24 96 '工作台' 15 '#c6d3e2'; T 24 145 '系统管理' 15 '#c6d3e2'
    SvgRect 10 174 170 42 '#409eff' '#409eff'; T 24 202 'AI 演示文稿' 16 '#ffffff' 600
    T 32 252 '我的演示文稿' 15 '#ffffff'; T 32 298 '新建演示文稿' 15 '#c6d3e2'; T 32 344 '样例与主题' 15 '#c6d3e2'
    T 24 825 'navType = 1 示例' 12 '#a9bace'; T 24 852 '主导航来自服务器菜单' 12 '#a9bace'
    SvgRect 190 0 1250 54 '#ffffff' '#e2e6ec' 0; T 214 35 '≡  / AI 演示文稿 /' 16; T 1230 35 '用户甲   设置  退出' 14
    SvgRect 190 54 1250 38 '#ffffff' '#e2e6ec' 0; SvgRect 208 61 160 25 '#ecf5ff' '#b3d8ff'; T 220 80 '我的演示文稿 ×' 13 '#337ecc'; T 390 80 $title 13
    T 220 130 $title 26 '#263445' 600; T 220 160 $sub 14 '#657184'
    SvgRect 1210 106 200 35 '#fff8e6' '#ebc56c'; T 1223 129 '设计图 · 未连接业务 API' 13 '#906b1d'
}
function Save($n){
    [void]$script:svg.AppendLine('</g></svg>')
    [System.IO.File]::WriteAllText((Join-Path $outputDir "演示文稿界面-$n.svg"),$script:svg.ToString(),[System.Text.UTF8Encoding]::new($false))
}

Shell '我的演示文稿' '查看自己的项目，从材料到提纲，再到可编辑 PPTX'
SvgRect 220 188 1190 80
F 240 211 '项目名称' '增长复盘' 260; F 530 211 '状态' '全部状态  ▾' 170
B 730 215 '搜索' 80 $true; B 820 215 '重置' 80; B 1220 213 '新建演示文稿' 168 $true
SvgRect 220 290 1190 475
T 240 326 '项目列表' 19 '#303745' 600; B 1180 302 '显示搜索 / 刷新' 210
SvgRect 240 348 1150 46 '#f5f7fa' '#ebeef5' 0
T 260 377 '名称' 14; T 635 377 '状态' 14; T 820 377 '页数' 14; T 935 377 '更新时间' 14; T 1200 377 '操作' 14
$rows=@(@('2026 上半年增长复盘','可编辑','10','10-02 14:20'),@('产品发布方案','部分完成 · 1 页失败','8','10-02 13:15'),@('技术分享','提纲待确认','12','10-01 18:40'),@('年度规划','草稿 · 材料已保存','10','10-01 09:00'))
for($i=0;$i -lt $rows.Count;$i++){
    $yy=394+$i*68; SvgRect 240 $yy 1150 68 '#ffffff' '#ebeef5' 0
    T 260 ($yy+40) $rows[$i][0] 16; T 635 ($yy+40) $rows[$i][1] 14 '#606e82'; T 826 ($yy+40) $rows[$i][2] 15; T 935 ($yy+40) $rows[$i][3] 14
    T 1200 ($yy+40) '继续   删除' 14 '#337ecc'
}
T 850 724 '共 24 项   每页 10 项 ▾   ＜  1  2  3  ＞' 15 '#657184'
SvgRect 220 790 1190 64 '#ecf5ff' '#b3d8ff'; T 242 819 '空列表：尚无演示文稿，点击“新建演示文稿”开始。' 15 '#337ecc'; T 242 844 '访问边界：默认本人私有；管理查询需要独立授权。' 13 '#657184'
Save 1

Shell '新建与材料' '每份材料可查看解析结果；失败文件可补传，已成功材料保留'
SvgRect 220 185 780 663; SvgRect 1020 185 390 663
B 242 205 '一句主题' 136; B 390 205 '粘贴文字' 136; B 538 205 '上传文档' 136 $true
F 244 273 '演示文稿名称' '2026 上半年增长复盘' 728
SvgRect 244 334 728 125 '#f6faff' '#a6cbfb'; T 440 385 '选择文件或拖入此处' 21 '#337ecc' 600; T 370 422 'PDF / DOCX / Markdown / TXT，每份不超过 10 MiB' 14 '#657184'
T 246 492 '已上传材料' 17 '#303745' 600
SvgRect 244 510 728 62; T 262 540 '半年增长报告.docx' 16; T 570 540 '已解析 · 6 节 · 4200 字' 14 '#248064'; T 854 540 '查看 / 删除' 14 '#337ecc'
SvgRect 244 585 728 66 '#fff8e6' '#ebc56c'; T 262 615 '扫描附件.pdf' 16; T 540 615 '未提取到文字，请补充文本' 14 '#906b1d'; T 868 615 '替换' 14 '#337ecc'
T 244 682 '材料小节定位可追溯，模型输入裁剪会另行提示。' 14 '#657184'
B 244 777 '保存材料' 128; B 785 777 '生成提纲 →' 185 $true
T 1044 219 '生成设置' 20 '#303745' 600
F 1044 260 '目标页数（5–20）' '10 页  ▾' 342
F 1044 340 '受众' '产品与业务团队' 342
F 1044 420 '语气' '专业  ▾' 342
F 1044 500 '排版模式' '灵活排版  ▾' 342
F 1044 580 '文字量' '中等  ▾' 342
F 1044 660 '主题' '象牙白 ivory  ▾' 342
T 1044 769 '统一 16:9 · 先确认提纲' 14 '#657184'
Save 2

Shell '提纲确认' '先完成标题、目的、要点与来源检查，保存后确认并开始生成'
SvgRect 220 186 810 664; SvgRect 1050 186 360 664
T 242 224 '10 页 · 自动保存：已保存' 17 '#248064' 600; B 590 201 '重新生成提纲' 170; B 775 201 '确认并生成 →' 225 $true
$outline=@(@('1  上半年增长复盘','页面角色：封面   版式：cover','目的：讲清复盘主题与核心结论'),@('2  三条增长曲线','页面角色：内容   版式：two-column','目的：对照自然增长、渠道与产品贡献'),@('3  核心增长指标','页面角色：内容   版式：kpi','目的：说明指标的变化和材料依据'))
for($i=0;$i -lt 3;$i++){
 $yy=258+$i*184; SvgRect 242 $yy 766 168; T 262 ($yy+32) $outline[$i][0] 19 '#303745' 600; T 262 ($yy+59) $outline[$i][1] 13 '#657184'; T 262 ($yy+86) $outline[$i][2] 15
 T 262 ($yy+113) '要点：已整理 3 条    ＋ 要点    ↑ ↓ 排序    复制 / 删除' 14 '#337ecc'
 T 262 ($yy+144) '来源：增长报告 第 2 节    配图意图：团队分析增长趋势' 13 '#657184'
}
B 242 818 '＋ 新增一页' 150
T 1074 220 '材料与生成信息' 19 '#303745' 600
SvgRect 1073 248 314 96 '#ecf5ff' '#b3d8ff'; T 1090 277 '已使用材料 1 份' 15; T 1090 307 '6 节 · 点击定位到原文' 14 '#337ecc'
T 1074 382 '增长报告：来源章节' 16 '#303745' 600
T 1074 422 '1. 复盘背景  — 第 1 页' 14; T 1074 463 '2. 增长变化  — 第 2 页' 14; T 1074 504 '3. 用户画像  — 第 3 页' 14
SvgRect 1073 552 314 140 '#fff8e6' '#ebc56c'; T 1090 583 '过期状态示例：材料已变化' 15 '#906b1d' 600; T 1090 615 '该提纲已过期，不能直接确认。' 14 '#906b1d'; T 1090 647 '重新生成后再核对来源与内容。' 14 '#906b1d'
T 1074 737 '确认后：材料和内容设置锁定' 14 '#657184'; T 1074 766 '外观主题仍可调整' 14 '#657184'; T 1074 798 '生成中须先取消并等待结束' 14 '#657184'
Save 3

Shell '内部编辑工作台' '专门业务画布置于若依页面内部；所有修改进入同页串行保存队列'
SvgRect 210 183 1210 54; T 227 217 '增长复盘 · 10 页' 17 '#303745' 600; T 467 217 '已保存 · 版本 24' 14 '#248064'
B 895 192 '继续生成' 115; B 1020 192 '整份重做' 115; B 1145 192 '放映' 90; B 1245 192 '质量 / 导出' 158 $true
SvgRect 210 249 160 605; SvgRect 384 249 710 605 '#e9edf2'; SvgRect 1108 249 312 605
T 226 281 '页面缩略图' 15 '#303745' 600
for($i=0;$i -lt 4;$i++){ $yy=300+$i*120; $stroke=if($i -eq 1){'#409eff'}else{'#dfe4ed'}; SvgRect 226 $yy 127 80 '#fcfbf5' $stroke; T 237 ($yy+24) ('0'+($i+1)) 12 '#a2aaac'; T 237 ($yy+48) @('增长复盘','三条增长曲线','核心指标','后续计划')[$i] 13 '#303745' 600; T 230 ($yy+104) '排序  复制  删除' 11 '#657184' }
B 225 799 '＋ 新增页' 130
T 406 285 '第 2 页  /  灵活排版' 15 '#657184'; B 765 260 '撤销  /  重做' 150; B 925 260 '缩放  100%' 148
SvgRect 408 355 662 373 '#faf8ef' '#cbd3de' 0
T 442 405 '三条增长曲线' 29 '#283c3a' 600; T 442 435 '核心结论：自然增长与产品改进共同推动上半年表现' 13 '#61716c'
SvgRect 438 463 287 207 '#ffffff' '#409eff'; T 457 493 '增长动因' 19 '#283c3a' 600; T 457 531 '• 产品体验：关键步骤缩短' 13 '#40504d'; T 457 566 '• 渠道运营：更稳定触达' 13 '#40504d'; T 457 600 '• 内容传播：主题更聚焦' 13 '#40504d'; T 457 639 '手工内容 · AI 保护已开启' 11 '#337ecc'
SvgRect 744 463 298 207 '#eef4ed' '#ced9ce'; T 767 500 '增长结构（示意图）' 17 '#283c3a' 600
SvgRect 778 573 50 70 '#699886' '#699886' 0; SvgRect 849 542 50 101 '#527a69' '#527a69' 0; SvgRect 920 515 50 128 '#344f46' '#344f46' 0
T 782 659 '渠道' 11; T 849 659 '产品' 11; T 925 659 '自然' 11
SvgRect 728 460 8 214 '#409eff' '#409eff' 0; T 552 706 '拖动分隔线 / 块边缘调整 · 服务端再校验' 12 '#657184'
T 407 769 '选中块：字号 18  |  字重 600  |  颜色  |  内边距  |  重置' 13 '#657184'; T 407 809 '保存失败保留草稿；冲突暂停后续操作，不自动覆盖。' 13 '#657184'
T 1128 282 'AI 修改   主题   版式   图片' 14 '#337ecc' 600
F 1128 320 '希望怎样修改？' '把增长动因写得更具体' 272
B 1128 389 '生成修改建议' 272 $true
SvgRect 1128 446 272 165 '#f9fafb'; T 1144 475 '建议 1 · 未锁定块替换' 16 '#303745' 600; T 1144 510 '修改前：增长结构' 13 '#657184'; T 1144 545 '修改后：增长结构与贡献' 13; B 1144 564 '采纳' 92 $true; B 1248 564 '舍弃' 130
SvgRect 1128 635 272 96 '#fff8e6' '#ebc56c'; T 1144 663 '已锁定块不会被 AI 覆盖' 14 '#906b1d'; T 1144 695 '建议基于版本 24，过期须重提。' 12 '#906b1d'
T 1128 774 '主题 / 版式 / 图片在本页切换' 13 '#657184'; T 1128 810 '返回项目列表保留若依页签' 13 '#657184'
Save 4

Shell '质量与导出' '先检查同一快照的内容与资源，回读通过后才提供下载'
SvgRect 220 186 700 664 '#e9edf2'; SvgRect 943 186 467 664
SvgRect 252 306 635 358 '#faf8ef' '#ced5de' 0; T 290 367 '增长复盘' 31 '#283c3a' 600; T 290 417 '导出沿用当前已保存内容与主题' 17 '#61716c'
SvgRect 290 450 244 120 '#eef4ed' '#ced9ce'; T 311 491 '同一内容快照' 21 '#283c3a' 600; T 311 532 '正文 / 图表 / 表格' 16 '#61716c'
SvgRect 558 450 293 120 '#ffffff' '#dce2df'; T 578 491 '原生可编辑 PPTX' 21 '#283c3a' 600; T 578 532 '不会将整页截图替代正文' 15 '#61716c'
T 253 726 '预览示意 · 具体页面可由问题列表定位' 14 '#657184'; T 253 773 '先等待保存队列完成；导出期间的新编辑不改旧快照。' 14 '#657184'
T 967 220 '导出检查' 22 '#303745' 600; T 967 253 '内容版本 24 · 主题版本 5 · PPTX' 14 '#657184'
SvgRect 965 278 420 102 '#fff0f0' '#efb5b5'; T 981 308 '必须修复  1 项' 17 '#b13a3a' 600; T 981 340 '第 6 页 · 引用图片不存在' 15 '#b13a3a'; T 981 364 '定位页面 →   替换图片后重新检查' 13 '#337ecc'
SvgRect 965 396 420 130 '#fff8e6' '#ebc56c'; T 981 427 '核对提示  2 项' 17 '#906b1d' 600; T 981 459 '第 3 页：数字未在引用材料中找到' 14 '#906b1d'; T 981 493 '字体不全：溢出检查使用估算' 14 '#906b1d'
SvgRect 966 548 418 105 '#f4f6f8' '#dfe4ed'; T 984 580 '当前不能导出：仍有必须修复的问题' 14 '#657184'; T 984 614 '只有警告时：查看警告并继续导出' 14 '#657184'
B 966 673 '重新检查' 150; B 1128 673 '导出 PPTX（禁用）' 256
T 967 749 '任务流程：检查 → 渲染 → 回读 → 下载' 13 '#657184'; T 967 780 '回读失败：显示原因，不返回部分文件' 13 '#657184'; T 967 811 '就绪状态示例：回读通过后才提供下载' 13 '#248064'
Save 5

Shell '异常与恢复状态' '每种异常给出下一步；原失败保留，修复后另记复验'
$cards=@(
 @('生成进行中','3 页正在生成，已完成 5 / 10 页。','仅预览；编辑操作禁用。','取消尚未开始的页','#ecf5ff','#337ecc'),
 @('部分完成','第 3 页生成失败，其他 9 页保留。','查看失败原因，重试仅处理该页。','重试第 3 页','#fff8e6','#906b1d'),
 @('保存版本冲突','该页已被另一个窗口更新。','后续保存暂停；保留未保存草稿。','查看服务器版本 / 复制草稿','#fff0f0','#b13a3a'),
 @('网络暂时中断','进度连接已断开，正在恢复。','重连先读中心快照，不猜测状态。','查询最新状态','#ecf5ff','#337ecc'),
 @('材料没有可解析文字','扫描 PDF 无文字，本次不提供 OCR。','项目已保留，补传文档或粘贴文本。','补充材料','#fff8e6','#906b1d'),
 @('访问被拒绝','当前账号无此项目的访问权限。','不得显示他人的材料或预览。','返回我的演示文稿','#f2f4f7','#657184')
)
for($i=0;$i -lt 6;$i++){
 $xx=220+($i%2)*605; $yy=196+[math]::Floor($i/2)*210; $c=$cards[$i]
 SvgRect $xx $yy 585 190 $c[4] '#dce2eb'; T ($xx+22) ($yy+37) $c[0] 22 $c[5] 600
 T ($xx+22) ($yy+75) $c[1] 16; T ($xx+22) ($yy+110) $c[2] 14 '#657184'; B ($xx+22) ($yy+135) $c[3] 360
}
T 224 859 '以上为设计状态示例；无真实生成结果、真实验收截图或业务通过结论。' 14 '#657184'
Save 6

$titles=@('我的演示文稿','新建与材料','提纲确认','内部编辑工作台','质量与导出','异常与恢复状态')
$nav=''; $figures=''
for($i=1;$i -le 6;$i++){
 $active=if($i -eq 1){'aria-current="true"'}else{''}
 $nav += "<button type='button' data-page='$i' $active>$i. $($titles[$i-1])</button>"
 $hidden=if($i -eq 1){''}else{'hidden'}
 $figures += "<figure id='page-$i' $hidden><img src='演示文稿界面-$i.svg' alt='$($titles[$i-1])，待审核静态设计图，含若依主导航与业务控件'/><figcaption>$i. $($titles[$i-1]) — 1440×900 设计示意，当前展示 navType=1；实现须兼容另外两种平台导航。<a href='演示文稿界面-$i.svg'>单独打开图稿</a></figcaption></figure>"
}
$html=@"
<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>AI 演示文稿 · 完整迁移界面设计</title>
<style>*{box-sizing:border-box}body{margin:0;background:#edf1f6;color:#263445;font-family:'Microsoft YaHei',sans-serif}header{padding:22px 28px;background:white;border-bottom:1px solid #dfe4ed}h1{font-size:25px;margin:0 0 8px}p{margin:5px 0;font-size:14px;color:#657184}nav{padding:16px 28px;display:flex;gap:10px;flex-wrap:wrap}button{font:inherit;background:white;border:1px solid #ccd5e2;border-radius:6px;padding:10px 14px;cursor:pointer}button[aria-current=true]{color:white;background:#337ecc;border-color:#337ecc}button:focus-visible{outline:3px solid #409eff;outline-offset:3px}figure{margin:0 20px 24px;background:white;border:1px solid #dfe4ed;border-radius:8px;overflow:hidden}figure[hidden]{display:none}img{display:block;width:100%;height:auto}figcaption{padding:14px 20px;font-size:13px;color:#657184}a{color:#337ecc;margin-left:15px}.warning{color:#906b1d}footer{padding:0 28px 25px;color:#657184;font-size:13px}</style>
<header><h1>AI 演示文稿 · 完整迁移界面设计</h1><p>方案 0.1 / 2026-10-02 / 六幅可查看界面图，保留共享若依菜单、页签与登录状态。</p><p class='warning'>设计图 · 尚未实施迁移 · 示例内容与状态均非真实业务结果。图中按钮仅用于表达拟实现交互。</p></header><nav aria-label='界面设计图目录'>$nav</nav>$figures<footer>完整业务、权限、任务、保存冲突、数据导入和验收方法见 <a href='../完整迁移设计.md'>完整迁移设计</a>。图稿无远程脚本、远程字体或外部业务连接。</footer>
<script>document.querySelectorAll('button[data-page]').forEach(button=>button.addEventListener('click',()=>{document.querySelectorAll('button[data-page]').forEach(b=>b.setAttribute('aria-current',String(b===button)));document.querySelectorAll('figure').forEach(f=>f.hidden=f.id!=='page-'+button.dataset.page)}));</script></html>
"@
[System.IO.File]::WriteAllText((Join-Path $outputDir '界面设计.html'),$html,[System.Text.UTF8Encoding]::new($false))
Write-Output '已生成六幅 SVG 与界面设计图册，仅为待审核设计。'
