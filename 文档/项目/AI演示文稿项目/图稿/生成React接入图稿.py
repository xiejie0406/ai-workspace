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
text(440,213,'想做一份什么 PPT？',31)
box(330,242,780,108,'一句主题   |   粘贴文字   |   文档上传',[])
box(330,367,780,264,'原 textarea / FileDrop 输入区域',['主题500字 / 文本20000字','保留原输入与拖放组件；名称原规则推导','文件列表 / 移除；生成中步骤与错误提示'])
box(330,648,780,103,'原底部参数行',['页数 / 排版 / 文字量 / 语气 / 受众   → 生成大纲'])
footer('原居中单栏保留；局部展示异步解析，失败保留已成功项目','主题在原提纲页右栏选择；材料补传/章节与参数操作通过必要新增对话框承接。');save(2)
shell('3. 材料与提纲 · 原全屏工作区','/aippt-web/projects/:projectId · OutlineWorkspace',True)
parts.pop() # 替换页头右侧示意操作，避免重复叠字
text(815,133,'自动保存 · 重新生成 · 生成 PPT',18)
box(90,185,870,545,'原左栏：确认大纲 / PageCard列表',['01  标题 / 目的 / 要点','02  来源 / 角色 / 版式 / 配图意图','03  拖动排序 / 插入 / 删除','页卡字段和自动保存保持原组件','生成中保留原进度及错误画面'])
box(990,185,360,545,'原右栏：外观主题',['ivory / obsidian','midnight / scholar','原ThemeCover卡片','19rem侧栏 / 响应式排列'])
footer('版本、指纹与任务适配；原页头局部补材料与参数按钮打开Dialog','原React无材料侧栏：补传/章节/参数在新增对话框承接，保持原提纲两栏。');save(3)
shell('4. 编辑工作台 · 原三栏全屏布局','/aippt-web/projects/:projectId · EditorWorkspace',True)
box(24,181,218,552,'Filmstrip',['页缩略图','新增 / 复制','排序 / 删除','原状态标识'])
rect(264,181,808,552);text(290,222,'SlideStage + SlidePage',24);rect(300,264,736,414,'#edeadd');text(340,321,'原 16:9 画布 · 960×540 pt',25)
text(340,375,'原 BlockView / EditableText',22);text(340,423,'原 Recharts 图表 / 表格 / 图片',22);text(340,471,'原灵活树拖拽 / ElementToolbar',22);text(292,717,'缩放 / 选块 / 保存状态 / 锁定',18)
box(1094,181,322,552,'EditorRail',['AI / 主题 / 布局 / 图片','原面板与交互保留','采纳 / 撤回 / 候选','RelayoutDock','质量 / 导出 / 放映'])
footer('原保存队列适配revision、409、幂等与私有资产；候选/导出接中心任务','TanStack Query + 原auth Zustand + 局部React状态保留，不迁Pinia，不换图表库。');save(4)
shell('5. 导出与放映 · 保留原对话框和覆盖层','ExportDialog / PresentMode · 仍在原编辑工作区',True)
rect(24,180,1392,553,'#e9eee8');box(180,215,630,480,'原 ExportDialog',['等待当前页/主题保存完成','质量检查：错误阻断，警告显示','使用同版本不可变导出快照','排队 / 构造 / PPTX回读 / 失败原因','成功后下载原生可编辑PPTX'])
box(840,215,490,480,'原 PresentMode',['已就绪页原SlideView渲染','上一页 / 下一页 / 首页 / 末页','点击 / 键盘 / 全屏 / 退出','退出恢复原选页与焦点','保留原浏览器全屏退化'])
footer('导出从同步端点适配中心异步任务；私有下载带鉴权并释放blob','Python原构造与回读算法保留；图稿不表示导出或真实办公软件验收已通过。');save(5)
shell('6. 登录、加载与保存异常 · 局部接入状态','RequireAuth / AuthPage / 原保存队列错误状态')
box(45,190,430,535,'平台身份',['有效同源会话 → getInfo','无会话 → 平台登录引导','安全深链存在sessionStorage','URL不传Token','403拒绝 / 退出清缓存','不恢复原注册/JWT'])
box(500,190,430,535,'异步与断线',['保留原加载/进度界面','中心SSE序号 / 心跳 / 退避','断线后查询快照及终态','不重复提交生成/计费','局部失败保留成功页','不显示虚假成功'])
box(955,190,430,535,'保存与离开',['原每页串行保存','未知回执保留幂等键','409暂停并保留草稿','明确重试 / 回读新版本','切React路由 / 关闭标签','beforeunload提示未保存'])
footer('权限、任务、保存和资源错误只补在原组件；正式真相留Java/MySQL','示意图依据原组件结构；精确样式和实际业务效果必须运行原React接入后取证。');save(6)
shell('7. 必要补齐 · 原组件上的材料/参数与样例对话框','拟新增ProjectMaterialsDialog / SamplePanel · 保留原工作区')
rect(24,178,1392,559,'#e9eee8')
box(100,210,730,502,'材料与参数 · 原Dialog / TextField / PillSelect',['项目名称 / 受众 / 语气 / 页数 / 文字量','已确认：内容字段锁定 · 允许取消确认后改材料','来源列表：名称 / 解析状态 / 警告 / 删除','展开章节：稳定sectionId / 定位 / 字数','补传文档 / 粘贴文字：逐份状态，部分失败保留','保存 / 关闭：等待队列，409保留草稿'])
box(860,210,490,502,'样例 · 原ThemePanel按钮进入',['四主题 / 11布局 / 当前catalogVersion','使用原SlideView预览样例','检查：等待实际质量结果','错误阻断 / 警告确认','导出：中心任务完成后私有下载','关闭回原主题面板，不新建项目'])
footer('这些是承接原后端能力的必要新组件，不能冒称原前端已有','材料对话框不增提纲第三栏；样例不新建独立路由，也不把样例计入个人生成。');save(7)
print('已生成7幅原React布局SVG')
