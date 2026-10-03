"""PaiFlow 1.0 方案图稿生成器，仅生成本目录设计资产，不连接业务服务。"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import html

OUT = Path(__file__).resolve().parent
W, H = 1600, 1200
FONT = 'C:/Windows/Fonts/msyh.ttc'
BOLD = 'C:/Windows/Fonts/msyhbd.ttc'
BG, WHITE, INK, MUTED = '#f3f6fb', '#ffffff', '#263445', '#65758b'
BLUE, LINE, GREEN, RED, AMBER = '#409eff', '#dce4ef', '#168268', '#c54848', '#ad720b'
fonts = {}

def font(size=20, bold=False):
    key = (size, bold)
    if key not in fonts:
        fonts[key] = ImageFont.truetype(BOLD if bold else FONT, size)
    return fonts[key]

def txt(x, y, text, size=20, fill=INK, bold=False, maxw=None):
    f = font(size, bold)
    if maxw is None:
        draw.text((x, y), str(text), font=f, fill=fill)
        return y + size + 8
    yy = y
    for para in str(text).split('\n'):
        current = ''
        for c in para:
            if current and draw.textlength(current + c, font=f) > maxw:
                draw.text((x, yy), current, font=f, fill=fill)
                yy += size + 9
                current = ''
            current += c
        draw.text((x, yy), current, font=f, fill=fill)
        yy += size + 9
    return yy

def box(x, y, w, h, fill=WHITE, outline=LINE, radius=7):
    draw.rounded_rectangle((x, y, x+w, y+h), radius, fill, outline, 1)

def button(x, y, text, w=100, primary=False, disabled=False):
    box(x, y, w, 39, '#eef1f5' if disabled else BLUE if primary else WHITE,
        LINE if not primary else BLUE)
    tw = draw.textlength(text, font=font(18))
    txt(x+(w-tw)/2, y+8, text, 18, MUTED if disabled else WHITE if primary else INK)

def field(x, y, label, value, w=260, note=None):
    txt(x, y, label, 18, MUTED)
    box(x, y+29, w, 42)
    txt(x+11, y+39, value, 18, maxw=w-20)
    if note:
        txt(x, y+79, note, 16, MUTED, maxw=w)

def alert(x, y, w, text, kind='info', h=56):
    col, bg = (RED, '#fff0f0') if kind=='error' else (AMBER, '#fff7e8') if kind=='warning' else (BLUE, '#edf6ff')
    box(x, y, w, h, bg, bg)
    txt(x+16, y+12, text, 18, col, maxw=w-32)

def shell(title, subtitle, nav=1, selected='工作流与智能体'):
    global im, draw
    im = Image.new('RGB', (W,H), BG)
    draw = ImageDraw.Draw(im)
    draw.rectangle((0,0,W,45), fill='#fff6d9')
    txt(24,9,'设计线框图 1.0  ·  示例数据  ·  非实际产品 / 非验收截图',18,AMBER)
    draw.rectangle((0,45,W,105),fill=WHITE)
    txt(25,62,'RuoYi 共享管理平台',23,bold=True)
    txt(1130,65,'布局设置：模式 '+str(nav)+'    通知    平台账号',18,MUTED)
    if nav in (1,2):
        draw.rectangle((0,105,213,H-44),fill='#263445')
        txt(20,125,'服务器授权菜单',18,'#b6c4d6')
        menu=['工作流与智能体','空间与成员','对话与成果','模型管理','知识库','工具与 MCP','业务数据表','RPA','发布管理','运行记录','应用与用量']
        yy=178
        for m in menu:
            if m==selected:box(10,yy-8,193,43,'#326093','#326093')
            txt(23,yy,m,19,'#ffffff' if m==selected else '#c3cfdd')
            yy+=62
        left=236
    else:
        draw.rectangle((0,105,W,151),fill='#e8eff7')
        txt(25,117,'PaiFlow 工作流   /   资源管理   /   发布管理   /   运行记录      （TopBar，菜单来自服务器）',19)
        left=24
    if nav==2:
        txt(395,64,'首页   系统管理   PaiFlow 工作流',19,BLUE)
    draw.rectangle((left-10,155,W-20,195),fill=WHITE)
    txt(left,163,'首页  /  PaiFlow 工作流  /  '+title,17,MUTED)
    tabs = '页签： 工作流与智能体 ×' if title=='工作流与智能体' else '页签： 工作流与智能体 ×     '+title+' ×'
    txt(left,212,tabs,17,BLUE)
    txt(left,252,title,29,bold=True)
    txt(left,299,subtitle,18,MUTED,maxw=W-left-45)
    draw.rectangle((0,H-44,W,H),fill='#e8edf4')
    txt(24,H-32,'设计目的：复用登录、服务器菜单、Layout、三种导航、TagsView、共享组件与 request；页面内部承载业务操作。',17,MUTED)
    return left,350

def table(x,y,w,columns,rows,widths=None,rowh=57):
    widths=widths or [w/len(columns)]*len(columns)
    box(x,y,w,44+len(rows)*rowh)
    draw.rectangle((x+1,y+1,x+w-1,y+43),fill='#eef3f9')
    xx=x
    for col,ww in zip(columns,widths):
        txt(xx+13,y+10,col,18,MUTED);xx+=ww
    for n,row in enumerate(rows):
        yy=y+44+n*rowh
        draw.line((x,yy,x+w,yy),fill=LINE)
        xx=x
        for val,ww in zip(row,widths):
            txt(xx+13,yy+14,val,18,maxw=ww-25);xx+=ww

def save(name):
    im.save(OUT/name)
    return name

images=[]
x,y=shell('工作流与智能体','选择授权空间 → 新建或导入 → 编排与调试 → 发布固定版本。')
field(x,y,'当前空间','内容团队 · 已加入',290)
field(x+310,y,'名称 / 标签','搜索流程或智能体',305)
button(x+640,y+30,'搜索',80,True)
button(x+735,y+30,'模板',92)
button(x+838,y+30,'导入',92)
button(x+941,y+30,'新建流程',125,True)
txt(x,y+119,'全部    我的草稿    已发布    收藏    智能体目录',20,BLUE)
table(x,y+164,1325,['名称','引擎 / 版本','状态','最近更新','操作'],[
 ['AI 播客','Java / 版本 3','可运行 · 已发布','10-02 09:30','编排  对话  版本'],
 ['文档问答助手','Python / 草稿 8','索引准备中','10-02 09:15','编排  检查资源'],
 ['分支与迭代测试','Python / 草稿 2','待调试','10-01 18:22','编排  调试'],
 ['团队内容生成','Python / 版本 1','可运行 · 已发布','10-01 15:10','编排  对话  分享']], [350,225,250,205,295])
box(x,y+466,645,141)
txt(x+18,y+482,'空间操作',21,bold=True)
txt(x+18,y+522,'成员 6 人   待处理申请 1 条   所有者：平台用户 A',18)
button(x+18,y+560,'成员与邀请',135);button(x+168,y+560,'转让空间',120)
box(x+667,y+466,658,141)
txt(x+687,y+482,'空列表状态',21,bold=True)
txt(x+687,y+523,'暂无符合条件的流程；可清除筛选、从模板创建或导入。',18)
box(x,y+638,1325,136)
txt(x+18,y+651,'新建 / 编辑智能体的图片操作',21,bold=True)
txt(x+18,y+694,'头像：正方形裁剪、缩放、预览；背景：横版 / 竖版分别预览。取消保留原图，确认后登记文件。',18)
button(x+18,y+727,'上传并裁剪',150);button(x+182,y+727,'取消',100);button(x+296,y+727,'确认图片',130,True)
images.append(save('01-工作流与空间.png'))

def canvas(failure=False):
    x,y=shell('画布编排与运行' if not failure else '调试失败与恢复',
        'AI 播客 / 草稿 12 · 引擎 Java · 保存不改变已发布版本',selected='工作流与智能体')
    actions=['保存草稿','校验','比较','版本','导出','调试','发布']
    xx=x
    for a in actions:
        button(xx,y,a,110,a in ['调试'],failure and a=='发布');xx+=120
    txt(xx,y+10,'已保存 09:42 · 版本 12',17,MUTED)
    yy=y+66
    box(x,yy,180,419)
    txt(x+16,yy+16,'节点库 · 能力筛选',18,bold=True)
    nodes=['开始 / 结束','大模型','HTTP / MCP 工具','文字与消息','条件 / 意图','迭代 / 子流程','知识 / Agent','数据表 / RPA','代码 / 变量']
    for n,a in enumerate(nodes):txt(x+16,yy+61+n*36,a,17,MUTED if n>=3 else INK)
    box(x+195,yy,795,419,'#f7faff')
    for gx in range(x+210,x+980,24):
        for gy in range(yy+12,yy+405,24):draw.ellipse((gx,gy,gx+2,gy+2),fill='#d4dfed')
    specs=[(x+224,yy+82,'开始','主题 · 必填'),(x+460,yy+82,'生成播客脚本','模型：DeepSeek'),(x+460,yy+235,'语音合成','Qwen / v1.0'),(x+720,yy+235,'结束','音频成果')]
    for a,b in [(0,1),(1,2),(2,3)]:
        sx,sy,_,_=specs[a];ex,ey,_,_=specs[b]
        if sy==ey:draw.line((sx+198,sy+57,ex,ey+57),fill=BLUE,width=3)
        else:draw.line((sx+96,sy+113,ex+96,ey),fill=BLUE,width=3)
    for n,(nx,ny,a,b) in enumerate(specs):
        box(nx,ny,198,113,WHITE,RED if failure and n==2 else BLUE if n==1 else LINE)
        txt(nx+13,ny+13,a,20,bold=True)
        txt(nx+13,ny+51,b,16,MUTED)
        txt(nx+13,ny+81,'失败 · 定位节点' if failure and n==2 else '属性  /  单节点调试',15,RED if failure and n==2 else BLUE)
    txt(x+215,yy+375,'−  80%  +     适应画布     撤销     复制 / 粘贴',17,MUTED)
    box(x+1004,yy,322,419)
    txt(x+1020,yy+15,'节点属性：生成播客脚本',20,bold=True)
    field(x+1020,yy+56,'模型 *','DeepSeek · 配置版本 2',289)
    field(x+1020,yy+143,'输入变量 *','开始.主题  （引用）',289)
    field(x+1020,yy+230,'提示词 *','面向普通听众，生成两段脚本',289)
    txt(x+1020,yy+325,'温度 0.7   超时 60 秒   重试 0',17)
    txt(x+1020,yy+365,'输出：script（String）',17,BLUE)
    return x,yy+435

x,y=canvas()
box(x,y,1326,176)
txt(x+16,y+14,'调试与成果    |    节点轨迹    |    输入输出    |    变量',20,bold=True)
field(x+16,y+53,'主题 *','AI 如何帮助学习',320)
button(x+360,y+82,'运行',88,True);button(x+462,y+82,'停止',88)
txt(x+580,y+65,'开始 → 脚本生成 → 语音合成 → 完成',20,GREEN)
txt(x+580,y+109,'已生成音频   播放  /  下载   ·   运行快照 12',18)
images.append(save('02-画布编排与运行.png'))
x,y=canvas(True)
alert(x,y,1326,'语音供应商超时：脚本已保留。可以仅重试语音节点；真实效果未知时先核对外部回执。','error')
box(x,y+67,649,147)
txt(x+16,y+81,'等待补充 / 取消处理中',20,bold=True)
txt(x+16,y+120,'请输入节目时长；取消已提交，尚未确认 Runner 已停止。',17,maxw=615)
button(x+16,y+166,'补充后继续',140,True);button(x+168,y+166,'查看停止回执',150)
box(x+667,y+67,659,147)
txt(x+683,y+81,'保存冲突 / 引擎不支持',20,bold=True)
txt(x+683,y+119,'草稿已被其他页面修改。知识节点需要 Python 引擎。',17,maxw=625)
button(x+683,y+166,'重新加载',130);button(x+825,y+166,'另存草稿',130)
images.append(save('03-调试失败与恢复.png'))

x,y=shell('模型知识与工具','资源详情都使用同一授权空间；此图并列展示各资源页的关键区域。',nav=2,selected='知识库')
txt(x,y,'模型管理   知识库   工具与 MCP   业务数据表   RPA',21,BLUE)
box(x,y+49,637,250);box(x+659,y+49,667,250)
txt(x+17,y+64,'模型配置',22,bold=True)
field(x+17,y+105,'名称 / 来源 *','DeepSeek · OpenAI 兼容',280)
field(x+319,y+105,'凭据引用 *','平台托管引用 · 已配置',295)
txt(x+17,y+211,'能力：文字 / 工具调用    配置版本 2',18)
button(x+17,y+247,'校验连接',120,True);button(x+153,y+247,'停用',100)
txt(x+678,y+64,'知识文档 / 分段 / 检索',22,bold=True)
txt(x+678,y+105,'学习资料.pdf  ·  24 个分段  ·  索引版本 3',18)
box(x+678,y+148,624,83,'#f5f8fc')
txt(x+692,y+159,'片段 8 / 第 4 页  ·  可修改、启停、定位原文',18)
txt(x+692,y+192,'示例文本：通过先复习再练习巩固学习效果。',17,MUTED)
button(x+678,y+247,'上传文档',117);button(x+805,y+247,'分段预览',118);button(x+935,y+247,'召回测试',118,True)
table(x,y+321,1326,['工具 / MCP','版本与输入输出','连接状态','操作'],[
 ['语音合成工具','V1.0 / text → audio','凭据有效','调试  版本  停用'],
 ['测试 MCP 服务','tools/list · schema v2','待确认 schema 变化','差异  参数  调用']], [310,440,300,276])
box(x,y+496,637,162);box(x+659,y+496,667,162)
txt(x+17,y+514,'业务数据表',21,bold=True)
txt(x+17,y+556,'资料表  ·  环境：测试 / 正式  ·  字段版本 4',18)
button(x+17,y+602,'字段设置',116);button(x+145,y+602,'导入 / 导出',145);button(x+302,y+602,'查看数据',120,True)
txt(x+678,y+514,'RPA 资源与助手',21,bold=True)
txt(x+678,y+554,'Runner 未配置：可以编辑参数，不能执行或显示成功。',17,maxw=620)
button(x+678,y+602,'连接配置',126);button(x+817,y+602,'参数 schema',145);button(x+974,y+602,'调试',100,False,True)
images.append(save('04-模型知识与工具.png'))

x,y=shell('对话与播客成果','对话绑定发布版本；脚本、附件与生成音频均能回查所属运行。',selected='对话与成果')
button(1350,254,'中文 / English',210)
box(x,y,263,629);box(x+282,y,1044,629)
button(x+17,y+16,'新建会话',228,True)
for n,a in enumerate(['AI 播客 · 版本 3','学习资料问答','内容提纲讨论','历史会话 / 重新回答']):
    box(x+13,y+77+n*83,237,64,'#ecf5ff' if n==0 else WHITE)
    txt(x+25,y+95+n*83,a,18,maxw=209)
txt(x+299,y+17,'AI 播客  ·  已发布版本 3   |   运行记录   分享   清空历史',20,bold=True)
box(x+405,y+69,871,68,'#edf6ff')
txt(x+425,y+88,'我：请制作一段“AI 帮助学习”的短播客。',20)
txt(x+311,y+160,'播客脚本',22,bold=True)
txt(x+311,y+203,'欢迎收听。今天我们讨论如何结合复习、练习与AI反馈……',20,maxw=956)
txt(x+311,y+246,'来源：脚本节点 / 模型配置 2    成果：音频节点 / Qwen',17,MUTED)
box(x+309,y+288,984,116,'#f7faff')
txt(x+328,y+304,'播客.mp3   ·   音频可用   ·   运行 1038',20,bold=True)
button(x+328,y+346,'播放 / 暂停',142,True)
draw.line((x+488,y+368,x+1100,y+368),fill=LINE,width=7)
draw.line((x+488,y+368,x+737,y+368),fill=BLUE,width=7)
txt(x+491,y+389,'00:24 / 01:32',15,MUTED)
button(x+1144,y+346,'下载',118)
alert(x+309,y+423,984,'语音失败时保留脚本；可仅重新合成，不需要重新生成脚本。','warning')
box(x+309,y+491,984,119)
txt(x+323,y+503,'输入问题 / 补充信息……',19,MUTED)
txt(x+323,y+556,'附件    语音输入    重新回答    停止',17,BLUE)
button(x+1117,y+552,'发送',145,True)
images.append(save('05-对话与播客成果.png'))

x,y=shell('版本发布与渠道','发布绑定不可变版本；渠道状态由真实确认与回读决定。',nav=3,selected='发布管理')
box(x,y,365,602);box(x+385,y,1163,602)
txt(x+18,y+18,'AI 播客 / 版本列表',24,bold=True)
for n,(a,b) in enumerate([('版本 3 · 当前可调用','10-02 09:30 / 快照 abc…'),('版本 2 · 历史发布','10-01 15:30 / 快照 def…'),('草稿 12 · 未发布','有新修改，不影响版本 3')]):
    box(x+15,y+78+n*116,333,98,'#edf6ff' if n==0 else WHITE)
    txt(x+29,y+94+n*116,a,20,bold=True)
    txt(x+29,y+130+n*116,b,16,MUTED)
button(x+18,y+446,'比较版本',140);button(x+173,y+446,'恢复为草稿',165)
txt(x+403,y+18,'发布预检与五渠道',24,bold=True)
alert(x+403,y+66,1126,'配置缺失的渠道不可发布；其他已准备好的渠道仍可独立操作。','warning')
table(x+403,y+141,1126,['渠道','状态 / 目标','回读与操作'],[
 ['市场','已发布 / 团队目录','分类、标签、可见范围  ·  下线'],
 ['API','已发布 / 版本 3','调用凭据  ·  速率限制  ·  下线'],
 ['MCP','预检通过 / 待发布','生成 schema  ·  握手验证  ·  发布'],
 ['微信','缺授权 / 未发布','绑定 appId  ·  验签解密  ·  配置'],
 ['飞书','状态待核对 / 测试对象','查看回执  ·  重新核对  ·  下线']], [160,348,618])
txt(x+403,y+498,'发布影响：外部调用固定到所选版本；下线后拒绝新调用。',19)
button(x+403,y+548,'查看配置缺口',166);button(x+582,y+548,'发布所选渠道',184,True)
box(x,y+620,1548,121)
txt(x+18,y+636,'原讯飞渠道兼容',21,bold=True)
txt(x+18,y+677,'讯飞语音平台 / AIUI  ·  讯飞开放平台  ·  应用同步、公告和历史结果保留；未配置时不可发布。',18)
button(x+1268,y+661,'兼容渠道配置',252)
images.append(save('06-版本发布与渠道.png'))

x,y=shell('记录用量与权限','正式运行数据驱动统计；资源授权与平台功能权限同时校验。',selected='运行记录')
field(x,y,'时间范围','2026-10-01 至 2026-10-02',345)
field(x+366,y,'状态 / 引擎','全部 / Java + Python',275)
button(x+666,y+30,'查询',95,True);button(x+775,y+30,'日志导出',130)
table(x,y+105,1326,['运行 / 固定版本','状态','引擎 / 耗时','Token / 成果','操作'],[
 ['播客 1038 / 版本 3','完成','Java / 18 秒','820 / audio','轨迹  下载'],
 ['问答 1039 / 草稿 8','等待补充','Python / 12 秒','420 / 文本','输入  继续'],
 ['RPA 1040 / 版本 1','取消处理中','Python / 110 秒','0 / 回执待核对','回读  停止'],
 ['知识 1041 / 草稿 2','失败：索引未就绪','Python / 2 秒','0 / 无成果','原因  重试']], [345,266,235,230,250])
box(x,y+404,637,187);box(x+659,y+404,667,187)
txt(x+17,y+420,'用量与调用密钥',22,bold=True)
txt(x+17,y+461,'当前筛选  ·  完成 1 / 失败 1 / 未终态 2',18)
txt(x+17,y+501,'配额 80 / 100    Key：pk_••••••••  ·  可撤销',18)
button(x+17,y+546,'查看明细',130);button(x+159,y+546,'轮换 / 撤销',151)
txt(x+678,y+420,'空间成员与操作权限',22,bold=True)
txt(x+678,y+461,'A：所有者   B：编排者   C：无此空间权限',18)
txt(x+678,y+501,'成员权限不能增加平台未授予的发布权限。',18)
button(x+678,y+546,'邀请成员',130);button(x+820,y+546,'角色与范围',150)
alert(x,y+607,1326,'无权限：无法查看其他空间的运行或文件。可返回自己的空间，或由空间所有者按平台权限邀请。','error')
images.append(save('07-记录用量与权限.png'))

captions=['工作流、空间选择、列表、空状态和图片裁剪','画布、节点属性、调试输入和运行成果','失败、等待补充、取消待确认与保存冲突','模型、知识分段、HTTP/MCP、数据表和RPA资源','历史会话、中英文切换、脚本、音频播放器、附件与失败恢复','版本、五主渠道、原讯飞兼容、回执和下线','运行记录、配额密钥、空间成员和权限拒绝']
sections=[]
for i,(n,c) in enumerate(zip(images,captions),1):
    sections.append(f'<section id="p{i}"><h2>{i}. {html.escape(c)}</h2><p>拟实现界面 · 1.0 · 设计示例，未连接服务、未执行业务。</p><a href="{html.escape(n)}"><img src="{html.escape(n)}" alt="{html.escape(c)}"></a></section>')
page='''<!DOCTYPE html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PaiFlow 完整迁移界面设计</title><style>body{font-family:system-ui,"Microsoft YaHei",sans-serif;margin:0;background:#eef3f9;color:#263445}header{padding:32px 4%;background:white;border-bottom:1px solid #dce4ef}h1{font-size:28px;margin:0 0 12px}p{color:#65758b;line-height:1.7}nav{display:flex;gap:12px;flex-wrap:wrap}a{color:#176bb2}nav a{padding:9px 14px;background:#edf6ff;text-decoration:none;border-radius:6px}main{max-width:1600px;margin:auto;padding:20px}section{margin:20px 0 40px;padding:20px;background:white;border:1px solid #dce4ef;border-radius:9px}h2{font-size:22px;margin:0}img{width:100%;height:auto;display:block;border:1px solid #dce4ef}footer{padding:20px;text-align:center}</style><header><h1>PaiFlow 工作流项目 · 完整迁移界面设计</h1><p>设计线框图 1.0 / 2026-10-02。图中数据、运行结果和发布状态均为设计示例，不代表已实现、已迁移或已验收。保留若依登录、授权菜单、Layout、TagsView 与三种导航，业务画布位于页面内部。</p><nav>'''
page+=''.join(f'<a href="#p{i}">{i}. {html.escape(c.split("、")[0])}</a>' for i,c in enumerate(captions,1))
page+='</nav><p><a href="../完整迁移设计.md">完整迁移设计正文</a> · 可点击每张图片打开原尺寸。</p></header><main>'+''.join(sections)+'</main><footer>设计图仅供审核；实际页面验收另保存真实截图与数据回读证据。</footer></html>'
(OUT/'界面设计.html').write_text(page,encoding='utf-8')
sheet = Image.new('RGB', (1600, 4*648), '#eef3f9')
sd = ImageDraw.Draw(sheet)
for i, name in enumerate(images):
    thumb = Image.open(OUT/name).resize((784,588))
    sx, sy = (i%2)*800+8, (i//2)*648+42
    sd.text((sx,sy-32),name,font=font(20),fill=INK)
    sheet.paste(thumb,(sx,sy))
sheet.save(OUT/'图稿总览.png')
print('已生成 7 张 1600×1200 设计图及界面设计.html')
