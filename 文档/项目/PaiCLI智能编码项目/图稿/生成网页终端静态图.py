"""直接绘制静态设计资产，不执行HTML/JavaScript，不访问浏览器或业务服务。"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
E=Path(__file__).resolve().parent
FONT='C:/Windows/Fonts/msyh.ttc'
def f(size):return ImageFont.truetype(FONT,size)
def base(h):
    im=Image.new('RGB',(1440,h),'#edf2f7');return im,ImageDraw.Draw(im)
def box(d,rect,fill='#ffffff',outline='#d4dfe9',radius=7):d.rounded_rectangle(rect,radius,fill=fill,outline=outline,width=1)
def txt(d,x,y,s,size=18,color='#253b50'):d.text((x,y),s,font=f(size),fill=color)
def wrap(d,x,y,s,width,size=18,color='#253b50',line=30):
    current=''
    for ch in s:
        if ch=='\n' or d.textlength(current+ch,font=f(size))>width:
            txt(d,x,y,current,size,color);y+=line;current='' if ch=='\n' else ch
        else:current+=ch
    txt(d,x,y,current,size,color);return y+line
def button(d,x,y,w,s,primary=False):box(d,(x,y,x+w,y+36),'#268de3' if primary else '#ffffff');txt(d,x+12,y+7,s,15,'#ffffff' if primary else '#356185')
im,d=base(965)
d.rectangle((0,0,1440,48),fill='#fff1d5');txt(d,24,12,'设计图 · 网页命令行终端 v1.1',19);txt(d,880,14,'静态线框 · 示例内容 · 尚未实现',16,'#85631d')
d.rectangle((0,48,186,965),fill='#24374d');txt(d,20,78,'若依 · 研发工作台',19,'#ffffff')
txt(d,20,139,'PaiCLI 智能编码',15,'#a9c1d8')
for i,s in enumerate(['编码工作台','后台任务','知识与记忆','连接与模型','审计与评测']):
    if i==0:box(d,(12,174+i*48,174,215+i*48),'#365c7f','#365c7f')
    txt(d,26,182+i*48,s,17,'#e3edf7')
txt(d,26,503,'现有授权导航',14,'#9bb4ce');txt(d,26,544,'系统管理',16,'#dae6f0')
d.rectangle((186,48,1440,102),fill='white');txt(d,211,64,'首页 / PaiCLI 智能编码 / 编码工作台',16);txt(d,1190,64,'已登录若依用户',16)
d.rectangle((186,103,1440,143),fill='white');box(d,(211,111,340,137),'#eaf5ff','#acd4f3',3);txt(d,224,113,'编码工作台 ×',14,'#176dac')
txt(d,211,164,'编码工作台',26);txt(d,211,207,'网页对话和命令行视图使用同一编码会话',16,'#617b92')
button(d,1030,174,112,'网页对话');button(d,1150,174,112,'网页终端',True);button(d,1270,174,136,'会话账本')
box(d,(211,249,1412,294),'#eaf6ff','#b8dafa');txt(d,225,262,'打开原 PaiCLI 终端：命令、键位、三种界面、图片与剪贴板完整保留；同一若依中心。',16,'#216390')
box(d,(211,312,1412,411))
for x,label,value,w in [(225,'工作区','订单服务开发区',205),(444,'执行设备','本机 Runner · 在线',210),(668,'编码会话','订单金额校验',195),(877,'终端界面','inline · 默认',145)]:
    txt(d,x,324,label,14,'#5a7086');box(d,(x,349,x+w,389),'#fafcfe');txt(d,x+10,360,value+'  ▼',15)
button(d,1040,351,117,'打开终端',True);button(d,1169,351,99,'分离窗口');button(d,1280,351,115,'结束终端')
box(d,(211,431,1105,882),'#101c2e','#304660');d.rectangle((212,432,1104,477),fill='#20334b');txt(d,230,443,'真实命令行 · PaiCLI / inline',17,'#e5edf7');txt(d,861,446,'已连接 · 当前窗口可输入',14,'#8ae4b6')
rows=[('PaiCLI 若依联机终端 · UTF-8 · 118 × 30','#899fb9'),('工作区：订单服务开发区   审批模式：ASK','#d7e5f3'),('当前会话：订单金额校验   设备：本机 Runner','#9cb3cc'),('',None),('你 > 找出订单金额校验逻辑，先解释，不修改文件。','#a1e5be'),('PaiCLI > 我先读取订单服务与金额计算代码。','#91bffa'),('   read_file   src/main/java/OrderService.java   已确认','#dce8f6'),('   grep_code   validateAmount                   已确认','#dce8f6'),('',None),('结果：目前在 createOrder 中校验请求金额，建议补充……','#dce8f6'),('中心任务：示例 4821 · 已完成；右侧可查看原始账本。','#9cb3cc'),('',None),('你 > /help  ▌','#a1e5be'),('Enter 提交 · ↑↓ 历史 · Tab 补全 · Shift+Tab 切模式','#91a6bf')]
for i,(s,c) in enumerate(rows):
    if s:txt(d,231,494+i*25,s,16,c)
d.rectangle((212,849,1104,881),fill='#20334b');txt(d,227,856,'终端进程与编码任务分别管理；关闭浏览器仅分离。',14,'#b2c8df')
box(d,(1122,431,1412,659));txt(d,1139,447,'当前会话',19)
for i,s in enumerate(['订单金额校验','终端：运行中（示例）','输入控制：当前窗口']):txt(d,1139,485+i*31,s,15)
button(d,1139,585,256,'查看会话账本 / 后台任务')
box(d,(1122,678,1412,882));txt(d,1139,696,'计划与审批',19);box(d,(1139,735,1370,765),'#eef6fb','#cbdae8');txt(d,1150,740,'当前暂无待处理审批',15,'#42657e')
wrap(d,1139,778,'展开完整影响与参数；与终端使用同一中心审批。',250,15,line=26);button(d,1139,835,256,'打开审批抽屉')
box(d,(211,900,1412,947));button(d,224,905,142,'粘贴 / 上传图片');button(d,378,905,142,'发送快捷键');button(d,532,905,142,'更多启动入口');txt(d,701,915,'原 CLI 输入；已有进程只附着，本机入口继续保留。',15,'#42627d')
im.save(E/'网页命令行终端-主界面.png')
im,d=base(1080);txt(d,24,22,'网页命令行终端 · 四个关键状态',27);txt(d,24,67,'设计图 v1.1；静态示例，不连接后台、不运行命令',17,'#678097')
cards=[(24,112,'1. 等待中心批准',[
 '终端：等待批准，不执行写入。','工具：edit_file','目标：src/main/java/OrderService.java','右侧展开完整参数、影响及当前版本。','有批准权限：批准 / 拒绝 / 跳过 / 修订。','无批准权限：只读并提示联系批准者。','两处处理的是同一条中心审批。']),
 (734,112,'2. 断线、重连与输入控制权',[
 '连接断开；保留画面，暂停输入。','重连原进程，按输出序号补齐。','未确认输入不自动重发。','其他窗口接管后，本窗口改为只读。','Runner丢失：原实例标为丢失。','核对中心活动任务，再新建终端。','不能把进程丢失当成文件已回滚。']),
 (24,599,'3. 尚未启动 / 无法启动',[
 '选择工作区、在线 Runner、编码会话。','打开终端：已有实例附着，没有才启动。','进入页面不会请求模型或写文件。','缺设备绑定：前往连接与模型。','设备离线或无PTY：说明原因并禁用启动。','历史会话：先新建可执行会话。','不自动安装依赖或新起基础服务。']),
 (734,599,'4. 结束终端时的明确选择',[
 '当前有活动任务：显示结束确认。','默认：只结束CLI，保留后台任务。','可选：同时请求取消当前任务。','取消仍需原权限，等待中心实际确认。','关闭网页：仅分离，允许续接。','不会停止Runner、中心、MySQL或Redis。','会话、计划、审批和账本继续保留。'])]
for x,y,title,lines in cards:
    box(d,(x,y,x+682,y+454));txt(d,x+21,y+18,title,23)
    box(d,(x+18,y+67,x+664,y+354),'#122137','#314b68')
    for i,s in enumerate(lines):txt(d,x+35,y+83+i*36,s,18,'#dce8f5' if i else '#a4e7c4')
    if '等待' in title:button(d,x+22,y+382,173,'打开完整审批');button(d,x+213,y+382,173,'查看中心记录')
    elif '断线' in title:button(d,x+22,y+382,173,'重新连接');button(d,x+213,y+382,173,'明确接管输入')
    elif '启动' in title:button(d,x+22,y+382,173,'打开终端',True);button(d,x+213,y+382,173,'前往连接与模型')
    else:button(d,x+22,y+382,173,'返回');button(d,x+213,y+382,173,'确认结束')
im.save(E/'网页命令行终端-关键状态.png')


im,d=base(960);txt(d,24,22,'打开原终端 · 图片与浏览器快捷键适配',27);txt(d,24,65,'设计图 v1.1 · 所有内容为示例 · 完整保留要求，尚未实现',17,'#678097')
box(d,(24,105,1416,214));txt(d,45,123,'编码工作台 / 网页对话',21);txt(d,45,165,'当前会话：订单金额校验；已有终端运行中，打开后直接附着并聚焦。',17)
button(d,1220,143,169,'打开终端',True)
box(d,(24,238,905,934));txt(d,46,255,'原 PaiCLI 终端 / inline（可选择 Lanterna 或 plain）',21)
box(d,(44,296,885,505),'#122137','#314b68');txt(d,64,316,'你 > 分析这张图 @clipboard',19,'#a4e7c4');txt(d,64,361,'正在等待网页提供你确认的剪贴板图片……',18,'#dce8f5');txt(d,64,405,'输入保留；图片就绪后交同一个原 CLI 输入轮次。',18,'#dce8f5');txt(d,64,449,'取消可返回输入，不自动发起纯文字任务。',18,'#a4e7c4')
txt(d,47,525,'图片输入 · 用户主动确认后送入原 CLI',20)
box(d,(45,567,269,727),'#f0f6fc');d.rectangle((71,592,244,662),fill='#c9e3f8');d.line((78,650,120,615,155,641,194,606,238,648),fill='#417caf',width=4);txt(d,70,678,'截图示意 · PNG',16)
txt(d,295,576,'来源：当前浏览器设备剪贴板',18);txt(d,295,614,'预览：已就绪（示例） · 图片模型已确认',17);txt(d,295,650,'图片只走附件通道；PTY中不拼接二进制。',17);txt(d,295,688,'原 @image 路径仍指 Runner 授权文件。',17)
button(d,46,752,178,'读取 / 粘贴图片',True);button(d,239,752,146,'选择文件');button(d,400,752,146,'取消附件')
box(d,(45,807,885,906),'#fff5e2','#e1cca4');wrap(d,62,824,'拒绝权限 / 上传失败 / 模型不支持：保留文字和图片，说明原因，允许重试或取消。不能静默丢图继续发送。',795,17,line=29)
box(d,(930,238,1416,624));txt(d,951,256,'发送原快捷键',22);txt(d,951,298,'终端聚焦时沿用原键位；浏览器冲突时',17);txt(d,951,329,'用此入口发送同一按键序列。',17)
for y,s in [(382,'Ctrl+O · 展开 / 折叠'),(430,'Ctrl+C · 原终端中断'),(478,'Shift+Tab · 切换模式'),(526,'Ctrl+P · Lanterna历史')]:button(d,953,y,437,s)
txt(d,952,576,'复制文字：复制按钮 / Ctrl+Shift+C',16,'#617b92')
box(d,(930,647,1416,934));txt(d,951,665,'完整能力与原入口',22);wrap(d,951,710,'原命令与全部参数、原输入与界面、计划审批、图片剪贴板、工具、通道及分发逐项对应。',435,18,line=31);wrap(d,951,820,'本机 CLI 继续可用；任何缺失明确记录，不能宣布全面迁移。',435,18,line=31)
im.save(E/'网页命令行终端-打开与图片.png')
print('已生成三张独立静态界面图；未执行HTML、JavaScript或浏览器动作。')
