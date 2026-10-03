from pathlib import Path
from html import escape
BASE=Path(__file__).resolve().parent
parts=[]
def rect(x,y,w,h,fill='#fff',stroke='#d5ddd8'):
    parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{fill}" stroke="{stroke}"/>')
def text(x,y,t,size=20,color='#25392f'):
    parts.append(f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}">{escape(t)}</text>')
def box(x,y,w,h,title,lines):
    rect(x,y,w,h);text(x+20,y+38,title,23)
    for i,l in enumerate(lines):text(x+20,y+76+i*32,l,18)
def shell(title,route,full=False):
    global parts
    parts=['<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="900" viewBox="0 0 1440 900"><g font-family="Microsoft YaHei, sans-serif">']
    rect(0,0,1440,900,'#f4f5ef','#f4f5ef');text(28,38,title,27);text(28,72,route,17,'#647469')
    rect(24,92,1392,66,'#fff');text(48,133,'AI 演示文稿   ·   '+('WorkbenchHeader' if full else 'AppShell'),22)
    text(1010,133,'原操作/用户菜单保留',18)
def footer(a,b):
    rect(24,774,1392,102,'#e4efe6');text(44,810,'接入改动：'+a,19);text(44,845,b,17)
def save(n):
    parts.append('</g></svg>');(BASE/f'演示文稿界面-{n}.svg').write_text(''.join(parts),encoding='utf-8')
shell('1. 我的演示文稿 · 保留原卡片与入口','/aippt-web/projects · ProjectsPage + AppShell')
text(50,218,'我的演示文稿',34);box(1060,180,330,90,'＋ 新建演示文稿',[])
for j in range(3):
    x=50+j*450;rect(x,295,420,320);rect(x+20,315,380,175,'#eae9dd');text(x+40,360,'原 ThemeCover 缩略封面',21)
    text(x+22,540,['节能改造方案','产品发布方案','季度回顾'][j],25);text(x+22,582,'状态 · 更新时间 · 继续编辑 / 删除',17)
text(50,683,'空列表 / 加载 / 错误状态沿原页面；分页协议在原列表中适配',20)
footer('平台菜单打开同源React；项目列表/分页与中心API连接','原AppShell、卡片、按钮和Tailwind样式保留；图为布局设计示意，非产品截图。');save(1)
shell('2. 新建演示文稿 · 原表单与输入方式','/aippt-web/create · CreatePage + AppShell')
box(45,185,855,555,'从材料开始',['一句主题   |   粘贴文字   |   文档上传','原主题500字 / 原长文本20000字入口','文档：TXT / Markdown / DOCX / 文本PDF','材料输入区域与上传列表保持原组件','逐份显示解析进度、章节和失败警告','原创建按钮 → 提纲工作区'])
box(925,185,470,555,'原参数侧栏',['受众 · 语气','目标页数 5–20','固定 / 灵活布局','文字量 · 四套主题','原字段顺序、样式和默认值','参数上限由UI与API分别核对'])
footer('原表单调用中心项目/来源/解析任务；局部显示权限和异步错误','不扩大原表单上限，不另建管理端表单；部分文件失败保留已成功材料。');save(2)
shell('3. 材料与提纲 · 原全屏工作区','/aippt-web/projects/:projectId · OutlineWorkspace',True)
box(24,180,340,555,'材料与参数区',['来源列表 / 章节定位','原文警告 / 补传','指纹过期提示','确认后锁定内容参数','保存 / 取消确认'])
box(386,180,740,555,'原提纲页列表',['01  标题 / 目的 / 要点','02  来源 / 角色 / 版式 / 配图意图','03  拖动排序 / 插入 / 删除','逐页编辑与原组件结构保持','排队 → 整理材料 → 规划 → 完成'])
box(1148,180,268,555,'原操作区',['保存提纲','确认并生成','确认前flush','冲突保留草稿'])
footer('绑定input/outlineRevision与来源ID；先保存再原子确认/生成','中心任务/SSE恢复进度；不在React复制正式状态规则。');save(3)
shell('4. 编辑工作台 · 原三栏全屏布局','/aippt-web/projects/:projectId · EditorWorkspace',True)
box(24,181,218,552,'Filmstrip',['页缩略图','新增 / 复制','排序 / 删除','原状态标识'])
rect(264,181,808,552);text(290,222,'SlideStage + SlidePage',24);rect(300,264,736,414,'#edeadd');text(340,321,'原 16:9 画布 · 960×540 pt',25)
text(340,375,'原 BlockView / EditableText',22);text(340,423,'原 Recharts 图表 / 表格 / 图片',22);text(340,471,'原灵活树拖拽 / ElementToolbar',22);text(292,717,'缩放 / 选块 / 保存状态 / 锁定',18)
box(1094,181,322,552,'EditorRail',['AI / 主题 / 布局 / 图片','原面板与交互保留','采纳 / 撤回 / 候选','RelayoutDock','质量 / 导出 / 放映'])
footer('原保存队列适配revision、409、幂等与私有资产；候选/导出接中心任务','TanStack Query + 原auth Zustand + 局部React状态保留，不迁Pinia，不换图表库。');save(4)
shell('5. 导出与放映 · 保留原对话框和覆盖层','ExportDialog / PresentMode · 仍在原编辑工作区',True)
rect(24,180,1392,553,'#e9eee8');box(180,215,630,480,'原 ExportDialog',['等待当前页/主题保存完成','质量检查：错误阻断，警告显示','使用同版本不可变导出快照','排队 / 构造 / PPTX回读 / 失败原因','成功后下载原生可编辑PPTX'])
box(840,215,490,480,'原 PresentMode',['已就绪页原SlidePage渲染','上一页 / 下一页 / 首页 / 末页','点击 / 键盘 / 全屏 / 退出','退出恢复原选页与焦点','保留原浏览器全屏退化'])
footer('导出从同步端点适配中心异步任务；私有下载带鉴权并释放blob','Python原构造与回读算法保留；图稿不表示导出或真实办公软件验收已通过。');save(5)
shell('6. 登录、加载与保存异常 · 局部接入状态','RequireAuth / AuthPage / 原保存队列错误状态')
box(45,190,430,535,'平台身份',['有效同源会话 → getInfo','无会话 → 平台登录引导','安全深链存在sessionStorage','URL不传Token','403拒绝 / 退出清缓存','不恢复原注册/JWT'])
box(500,190,430,535,'异步与断线',['保留原加载/进度界面','中心SSE序号 / 心跳 / 退避','断线后查询快照及终态','不重复提交生成/计费','局部失败保留成功页','不显示虚假成功'])
box(955,190,430,535,'保存与离开',['原每页串行保存','未知回执保留幂等键','409暂停并保留草稿','明确重试 / 回读新版本','切React路由 / 关闭标签','beforeunload提示未保存'])
footer('权限、任务、保存和资源错误只补在原组件；正式真相留Java/MySQL','示意图依据原组件结构；精确样式和实际业务效果必须运行原React接入后取证。');save(6)
print('已生成6幅原React布局SVG')
