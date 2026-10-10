from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent.parent/'图稿'
ROOT.mkdir(exist_ok=True)
FONT='C:/Windows/Fonts/msyh.ttc'
BOLD='C:/Windows/Fonts/msyhbd.ttc'
BLUE='#2563EB'; INK='#172033'; MUTED='#596579'; BORDER='#DAE1EB'
def canvas(title):
    im=Image.new('RGB',(1440,1000),'#F4F6FA'); d=ImageDraw.Draw(im)
    def text(x,y,s,size=18,color=INK,bold=False):
        d.text((x,y),s,font=ImageFont.truetype(BOLD if bold else FONT,size),fill=color)
    def box(x,y,w,h,fill='white',outline=BORDER,r=10):
        d.rounded_rectangle((x,y,x+w,y+h),r,fill=fill,outline=outline,width=1)
    def button(x,y,w,label,primary=False):
        box(x,y,w,44,BLUE if primary else 'white',BLUE if primary else BORDER,7)
        text(x+15,y+10,label,17,'white' if primary else INK)
    return im,d,text,box,button

def shell(title,active='开始对话'):
    im,d,t,b,btn=canvas(title)
    d.rectangle((0,0,213,1000),fill='#182336')
    t(22,26,'若依管理平台',23,'white',True)
    t(22,104,'系统管理',17,'#B7C2D5')
    t(22,155,'PaiCLI 智能编码',18,'#B9D4FF',True)
    for y,label in [(210,'开始对话'),(266,'我的任务'),(322,'我的文件'),(378,'模型与设置')]:
        if label==active: b(12,y-9,190,46,'#263D62','#263D62',5)
        t(36,y,label,18,'#85B5FF' if label==active else '#D3DAE6')
    t(22,492,'其他业务菜单',16,'#B7C2D5')
    d.rectangle((214,0,1440,67),fill='white')
    t(244,21,'首页  /  PaiCLI 智能编码  /  '+active,17,MUTED)
    t(1300,21,'当前用户',17,MUTED)
    b(236,82,1180,56,'#FFF8E6','#F2D89C',6)
    t(254,98,'设计稿 v0.1 · '+title+' · 拟实现界面，非实际运行截图',17,'#805617')
    return im,d,t,b,btn

im,d,t,b,btn=shell('首次进入：直接输入，不先配置项目')
b(236,156,240,807)
btn(254,175,204,'＋ 新对话')
t(258,252,'最近对话',17,MUTED)
t(258,294,'还没有历史对话',17,MUTED)
t(258,847,'我的文件',18,bold=True)
t(258,881,'个人目录已自动准备',16,MUTED)
t(258,913,'仅本人可见',16,MUTED)
b(492,156,924,807)
t(519,180,'开始对话',25,bold=True)
btn(1225,175,164,'打开终端')
t(686,296,'你好，想让我帮你做什么？',28,bold=True)
t(622,351,'可以问问题、写代码，也可以上传文件让我分析。',19,MUTED)
for x,y,s in [(579,426,'解释一段代码'),(876,426,'创建一个小工具'),(579,488,'分析上传文件'),(876,488,'整理我的文件')]:
    b(x,y,270,48,'#F7F9FD');t(x+17,y+13,s,17)
b(548,599,808,191)
t(568,619,'任务或问题',17,bold=True)
t(568,657,'例如：帮我写一个批量整理文件的小工具',20,MUTED)
t(568,733,'添加文件',17,BLUE)
btn(1204,726,130,'发送',True)
t(556,815,'模型：默认编码模型 · 选择',17)
t(895,815,'文件位置：我的目录  ·  更换',17)
t(556,860,'不用选目录也能开始；需要处理已有项目时再更换。',17,MUTED)
t(556,900,'修改已有文件或执行命令前，会先向你展示影响。',17,MUTED)
im.save(ROOT/'开始对话.png')

im,d,t,b,btn=shell('对话与文件修改：信息按需要展开')
b(236,156,240,807);btn(254,175,204,'＋ 新对话')
t(258,252,'今天',17,MUTED);b(251,287,211,78,'#EDF3FF','#CCDDFB',6)
t(265,301,'整理发票文件',18,bold=True);t(265,335,'等待你确认修改',15,MUTED)
t(258,398,'分析登录流程',18);t(258,449,'创建文件整理脚本',18)
b(492,156,924,807)
t(519,180,'整理发票文件',24,bold=True);btn(1225,175,164,'打开终端')
t(519,242,'文件位置：我的目录 / invoices   更换',17,MUTED)
b(700,296,680,82,'#EDF3FF','#CCDDFB',8)
t(720,318,'帮我按月份整理这里的发票，先给我看修改方案。',19)
t(520,410,'助手',18,bold=True)
t(520,452,'已找到 12 个文件。建议按月份建立文件夹，并移动对应文件。',18)
b(520,506,858,220,'#FFFBF1','#F2D89C',9)
t(542,525,'需要你确认：移动 12 个文件',21,bold=True)
t(542,572,'范围：我的目录 / invoices    原文件内容不会被修改',17)
t(542,615,'2026-09-15.pdf  →  2026-09 / 2026-09-15.pdf',18)
btn(542,661,125,'查看全部');btn(1031,661,116,'先不修改');btn(1163,661,190,'确认本次修改',True)
b(520,777,858,118)
t(539,795,'继续补充要求……',18,MUTED);btn(1206,836,145,'发送',True)
t(525,918,'模型：默认编码模型 · 选择    执行详情 · 查看记录',17,MUTED)
im.save(ROOT/'对话与确认.png')

im,d,t,b,btn=shell('终端与选择目录：一步进入，目录可选')
b(236,156,1180,807)
t(262,181,'整理发票文件',24,bold=True)
btn(1045,175,145,'返回对话');btn(1204,175,182,'结束终端')
t(262,240,'文件位置：我的目录 / invoices    更换',18)
b(262,289,760,624,'#152034','#152034',8)
t(285,315,'PaiCLI · 当前用户 · 个人目录',20,'#D7E5F5',True)
t(285,367,'执行环境已连接。输入要求即可与助手对话。',18,'#CAD8ED')
t(285,421,'> 帮我解释当前目录中的脚本',19,'#97BEFF')
t(285,475,'正在读取 organize.py …',18,'#CAD8ED')
t(285,531,'该脚本按日期分类文件，默认先预览修改。',18,'#CAD8ED')
t(285,851,'> 输入任务或命令…',18,'#CAD8ED')
b(1041,289,346,624)
t(1064,311,'选择文件位置',22,bold=True)
t(1064,359,'当前：我的目录',17,MUTED)
b(1064,401,298,53,'#EDF3FF','#CCDDFB',6);t(1080,416,'使用我的目录（默认）',17)
t(1064,481,'我的目录中的文件夹',17,bold=True)
t(1080,530,'invoices',18);t(1080,582,'demo-app',18)
t(1064,648,'处理电脑上的已有项目',17,bold=True)
btn(1064,689,298,'选择这台电脑上的目录')
t(1064,756,'仅在本机连接已准备时提供。',16,MUTED)
t(1064,789,'浏览器不能任意访问电脑文件。',16,MUTED)
btn(1214,845,148,'使用此目录',True)
im.save(ROOT/'终端与目录.png')

im,d,t,b,btn=canvas('关键状态')
t(42,28,'关键状态设计 · v0.1 · 非实际运行截图',26,bold=True)
cards=[(40,91,'1. 正在准备个人环境',['输入框可继续填写，不丢草稿。','显示准备阶段；不会让用户去注册设备。'],['正在准备…']),
       (735,91,'2. 没有可用模型',['保留对话和文件入口，说明缺少模型。','管理员配置默认模型；用户也可配置自己的。'],['选择或配置模型']),
       (40,510,'3. 终端连接中断',['保留已显示输出；说明任务可能仍在运行。','重新连接原终端，不自动创建或重复任务。'],['重新连接','返回对话']),
       (735,510,'4. 当前目录无法使用',['保留用户输入并指出目录不存在或无权访问。','可换回个人目录；不擅自扩大文件访问范围。'],['使用我的目录','重新选择'])]
for x,y,title,lines,actions in cards:
    b(x,y,665,367);t(x+26,y+24,title,23,bold=True)
    for j,line in enumerate(lines):t(x+26,y+94+j*46,line,19,MUTED)
    for j,a in enumerate(actions):btn(x+26+j*220,y+278,205,a,j==0)
t(44,916,'共同规则：有说明、有下一步、保留草稿；不再出现一排无解释的灰色按钮。',21,bold=True)
im.save(ROOT/'关键状态.png')
print('Rendered 4 PNG wireframes at',ROOT)
