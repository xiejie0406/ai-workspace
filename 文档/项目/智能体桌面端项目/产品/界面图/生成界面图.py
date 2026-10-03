from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
OUT.mkdir(parents=True, exist_ok=True)
FONT = 'C:/Windows/Fonts/msyh.ttc'
fonts = {n: ImageFont.truetype(FONT, n) for n in (16, 18, 20, 24, 30, 36, 48)}
INK, MUTED, GREEN, LINE = '#23352f', '#64736d', '#276554', '#c9d7cf'
BG, PALE, AMBER, RED = '#f3f6f4', '#e8f2ec', '#fff2d5', '#fae8e6'
W, H = 1440, 1040

def text(x, y, value, size=20, fill=INK, maxw=None):
    font = fonts[size]
    if maxw is None:
        d.text((x, y), value, font=font, fill=fill)
        return y + size + 10
    lines, line = [], ''
    for ch in value:
        if ch == '\n':
            lines.append(line); line = ''; continue
        if d.textlength(line + ch, font=font) > maxw:
            lines.append(line); line = ch
        else:
            line += ch
    if line: lines.append(line)
    for line in lines:
        d.text((x, y), line, font=font, fill=fill)
        y += size + 10
    return y

def box(x, y, w, h, fill='white', radius=10, outline=LINE):
    d.rounded_rectangle((x, y, x+w, y+h), radius=radius, fill=fill, outline=outline, width=2)

def button(x, y, label, w=150, primary=False, disabled=False):
    fill = '#eef0ee' if disabled else GREEN if primary else 'white'
    box(x, y, w, 44, fill, radius=7)
    tw = d.textlength(label, font=fonts[18])
    text(x+(w-tw)/2, y+8, label, 18, '#8c9690' if disabled else 'white' if primary else INK)

def field(x, y, label, value, w=430, h=48):
    text(x, y, label, 18)
    box(x, y+32, w, h)
    text(x+13, y+43, value, 18, MUTED, w-26)

def check(x, y, label, checked=True):
    box(x, y+3, 21, 21, GREEN if checked else 'white', radius=3)
    if checked: d.line([(x+5,y+14),(x+9,y+18),(x+17,y+8)],fill='white',width=3)
    text(x+35, y, label, 18)

def base(title, active='工作台', candidate=False, sidebar=True):
    global im, d
    im = Image.new('RGB', (W,H), BG); d = ImageDraw.Draw(im)
    d.rectangle((0,0,W,82), fill='white')
    text(26, 19, 'Aden', 30, GREEN)
    text(145, 24, title, 24)
    text(1000, 28, '统一设计 1.2 · ' + ('后续候选界面' if candidate else '界面线框'), 18, MUTED)
    if sidebar:
        d.rectangle((0,83,210,H), fill='#e9efeb')
        for i, label in enumerate(['工作台','采集任务','商品结果','导出记录','设置','关于']):
            y=115+i*66
            if label == active: box(16,y-7,178,50, 'white', radius=7, outline=GREEN)
            text(35,y,label,20,GREEN if label==active else INK)
        text(24, 924, '当前账号', 16, MUTED)
        text(24, 956, '测试操作员', 18)
    text(240 if sidebar else 26, 101, '设计图：示例数据。图稿不表示安装版已经实现或验收。',16,MUTED)

def status_cards(items, y=846):
    widths = (W-280-24*(len(items)-1))//len(items)
    for i,(title, message, label, colour) in enumerate(items):
        x=240+i*(widths+24)
        box(x,y,widths,165,colour)
        text(x+16,y+13,title,20)
        text(x+16,y+51,message,16,MUTED,widths-32)
        if label: button(x+16,y+108,label,min(widths-32,180))

def save(name):
    im.save(OUT/name)

base('登录', sidebar=False)
box(118,169,1204,726,'white',radius=24)
box(118,169,606,726,'#173f34',radius=24,outline='#173f34')
text(173,230,'Aden',48,'white')
text(175,317,'商品资料，集中整理',36,'white')
text(176,384,'把当前看到的商品保存下来，',20,'#cee0d6')
text(176,420,'整理图片与内容，再导出需要的资料。',20,'#cee0d6')
box(176,517,486,227,'#245343',radius=16,outline='#457462')
text(202,540,'商品资料',24,'white')
box(202,589,88,113,'#e1eee5',outline='#e1eee5')
text(219,629,'主图',20,GREEN)
text(314,586,'商品 A · 黑色 500ml',24,'white')
text(315,634,'商品信息   规格参数   图片',18,'#d6e6dc')
text(315,674,'查看原始内容与采集版本',18,'#d6e6dc')
text(176,817,'Aden 桌面端',18,'#cee0d6')
text(815,245,'欢迎回来',36)
text(817,305,'登录后进入工作台',20,MUTED)
field(817,362,'账号','请输入账号',420,52)
field(817,462,'密码','请输入密码',420,52)
text(1176,504,'显示',18,GREEN)
field(817,562,'验证码','请输入验证码',218,52)
box(1054,594,183,52,PALE)
text(1080,606,'图形验证码',18,GREEN)
text(817,661,'点击验证码图片刷新',16,MUTED)
button(817,715,'登录',420,True)
text(121,941,'设计说明：验证码按服务要求显示；窄窗口只保留居中表单。',18,MUTED)
save('01-登录.png')

base('商品采集工作台')
text(240,153,'工作台',30)
text(1070,166,'数据更新于 14:35（示例）',18,MUTED)
for x,title,value,desc in [(240,'已保存商品','12','当前可查看的商品'),(638,'今日采集任务','8','今天的保存请求'),(1036,'待处理','2','需要人工处理的任务')]:
    box(x,222,364,144)
    text(x+22,239,title,20,MUTED); text(x+22,280,value,36)
    text(x+100,295,desc,16,MUTED)
box(240,402,700,568)
text(265,423,'最近保存的商品',24)
for i,(name,sku,info,time) in enumerate([('商品 A · 黑色 500ml','SKU 10001 · 京东','图片 6/8','14:32'),('商品 B · 蓝色','SKU 10002 · 京东','图片 5/5','13:15'),('商品 D · 绿色','SKU 10003 · 京东','图片 4/4','11:42')]):
    y=490+i*136;box(265,y,650,112)
    box(283,y+20,72,72,PALE);text(299,y+43,'主图',18,MUTED)
    text(380,y+17,name,24);text(381,y+63,sku,16,MUTED)
    text(775,y+24,info,18);text(775,y+65,time,16,MUTED)
text(265,928,'点击商品行查看详情',16,MUTED)
box(965,402,435,342)
text(989,423,'最近采集任务',24)
for i,(name,state,time) in enumerate([('商品 A','部分保存','14:32'),('商品 B','已保存','13:15'),('商品 C','未保存','12:02')]):
    y=477+i*86
    text(990,y,name,20);text(1190,y,state,18,GREEN if i==1 else INK)
    text(990,y+34,'采集请求 · '+time,16,MUTED)
box(965,773,435,197,PALE)
text(989,793,'待处理 · 2 项',24)
text(989,844,'商品 A：2 张图片未保存',18)
text(989,884,'商品 C：内容保存失败',18)
text(989,928,'点击记录进入对应任务处理',16,MUTED)
save('02-工作台统计.png')

base('Chrome 插件：分区提取、展开并核对', sidebar=False)
box(25,150,905,840)
text(48,171,'Chrome · 用户已打开商品详情页（示意）',24)
box(60,239,295,300,PALE); text(140,362,'商品主图',24)
text(389,254,'商品 A · 黑色 500ml',30)
text(389,318,'当前显示价格：¥89.00',24)
text(389,375,'已选规格：黑色 / 500ml',20)
box(389,427,470,80,PALE,outline=GREEN);text(410,449,'规格参数 · 当前网页区域',24)
box(60,577,810,130,PALE);text(83,600,'商品介绍区域',24)
text(83,649,'用户选择并提取这一块，不自动操作网页。',20,MUTED)
box(60,741,810,173);text(83,764,'已加载详情内容与图片',24)
for i in range(4):box(83+i*184,817,160,66,BG);text(111+i*184,838,'图片示例',18,MUTED)
box(956,150,457,840)
text(979,167,'Aden 商品采集',24)
text(979,210,'商品 A · 黑色 500ml',18,GREEN)
button(980,251,'内容说明',180);button(1179,251,'区域点选',205)
groups=[('商品信息','12 项字段'),('规格参数','4 项参数'),('商品介绍','3 段文字'),('详情内容','4 个区块'),('主图','1 张候选'),('轮播图','3 张候选'),('详情图','4 张候选')]
for i,(label,count) in enumerate(groups):
    y=315+i*70
    box(980,y,405,63,'white',radius=7)
    check(989,y+9,label)
    text(1118,y+11,'展开',16,GREEN)
    text(1025,y+36,'已提取 · '+count,16,MUTED)
    button(1235,y+9,'提取本区',136)
text(983,820,'分区提取只更新预览；最后统一保存。',16,MUTED)
button(981,858,'提取全部所选',197)
button(1191,858,'确认保存',195,True)
text(983,928,'所选分区未提取或过期时，指出需要处理的区名。',16,MUTED,399)
save('03-插件提取与预览.png')

base('Chrome 插件：选择网页区域并确认', sidebar=False)
box(32,153,865,620)
text(58,176,'网页点选模式 · 点击业务区域',24)
for i,(label,colour) in enumerate([('商品参数：已纳入采集',PALE),('商品介绍：可排除',PALE),('售后保障：新增自选区域',AMBER)]):
    box(60,245+i*153,805,112,colour,outline=GREEN)
    text(88,267+i*153,label,24)
    text(88,311+i*153,'高亮范围与侧栏样本对应；不执行网页指令。',18,MUTED)
box(923,153,485,620)
text(946,176,'区域说明与样本',24)
field(948,224,'区域名称 / 所属分区','售后保障 / 自选详情',434)
box(948,350,434,163,PALE); text(968,370,'样本预览',24)
text(968,417,'当前页面读取到的文字与图片数量',18,MUTED,392)
button(948,547,'加入本次采集',210,True); button(1180,547,'排除该区域',200)
button(948,614,'恢复默认全选',235); button(1197,614,'退出点选',183)
text(948,682,'已确认的区域保留；未确认的选择不保存。',16,MUTED,432)
button(948,720,'返回提取',180)
box(32,807,1376,198,AMBER)
text(60,825,'确认区域变更后，返回并提取受影响分区',24)
text(60,871,'范围修改只让对应分区待重提取；更换商品或规格时，全部旧结果失效。',20)
button(60,933,'受影响分区待提取',220,disabled=True); button(305,933,'返回分区列表',220,True)
save('04-区域点选与关键异常.png')

base('采集任务与任务详情','采集任务')
text(240,153,'采集任务',30)
field(240,209,'搜索商品名 / SKU','输入关键词',440); button(701,241,'查询',100)
field(831,209,'状态','全部',200);field(1060,209,'时间','全部时间',340)
box(240,335,1160,300)
for i,cols in enumerate([
    ('时间','商品 / 规格','保存结果','图片','操作'),
    ('14:32','商品 A / 黑色 500ml','部分保存','6/8','查看任务'),
    ('13:15','商品 B / 蓝色','已保存','5/5','查看任务'),
    ('12:02','商品 C','未保存','0/4','查看原因'),
]):
    y=358+i*68
    if i==0: d.rectangle((242,y-12,1397,y+38),fill=PALE)
    for x,value in zip((262,410,885,1097,1235),cols): text(x,y,value,18)
box(240,664,1160,292,PALE)
text(263,679,'任务详情：内容已保存，2 张图片失败',24)
text(263,723,'步骤：提取 → 内容提交 → 图片保存 → 完成检查',20)
button(263,765,'查看商品结果',210,True); button(494,765,'重试失败图片',220); button(1100,765,'停止后续图片',260)
text(263,840,'已有结果：商品信息与 6 张图片已保存，可进入详情查看。',18)
text(263,888,'需处理：2 张图片读取失败；重试只继续失败项。',18,MUTED)
save('05-采集任务与详情.png')

base('商品结果：已采集商品清单','商品结果')
text(240,153,'商品结果',30)
text(420,165,'一件商品一行；各次采集在详情中回看',18,MUTED)
field(240,210,'搜索商品标题 / SKU','输入关键词',610)
button(871,242,'查询',130)
field(1022,210,'图片完整度','全部',378)
text(240,330,'共 12 件商品 · 当前页 3 件',20,MUTED)
button(812,320,'全选当前页',275)
button(1118,320,'导出当前筛选全部',282)
box(240,385,1160,421)
d.rectangle((242,387,1398,440),fill=PALE)
box(260,401,21,21,GREEN,radius=3);d.line([(265,412),(276,412)],fill='white',width=3)
for x,label in [(304,'主图'),(400,'商品 / SKU'),(699,'规格'),(868,'价格'),(993,'图片'),(1104,'采集时间'),(1280,'操作')]:text(x,402,label,18)
data=[('商品 A','10001','黑色 / 500ml','¥89.00','6/8','14:32'),('商品 B','10002','蓝色','¥39.90','5/5','13:15'),('商品 D','10003','绿色','未知','4/4','11:42')]
for i,(name,sku,spec,price,images,time) in enumerate(data):
    y=457+i*116
    check(260,y+32,'',i<2);box(304,y+10,74,76,BG);text(321,y+34,'主图',18,MUTED)
    text(401,y+10,name,24,GREEN);text(402,y+53,'SKU '+sku+' · 京东',16,MUTED)
    text(699,y+25,spec,18,INK,146);text(868,y+26,price,18)
    text(999,y+25,images,18);text(999,y+59,'部分' if i==0 else '完整',16,MUTED)
    text(1104,y+22,'10-01',16,MUTED);text(1104,y+51,time,18)
    button(1264,y+24,'查看详情',118)
text(240,836,'上一页    第 1 / 4 页    下一页',18)
box(240,892,1160,105,PALE)
text(265,910,'已选 2 件商品',24)
text(265,952,'勾选仅用于导出；筛选改变后清空选择。',16,MUTED)
button(1178,924,'导出所选',197,True)
save('06-商品列表与导出.png')

base('商品详情：原始快照、人工补充与图片','商品结果')
text(240,153,'商品 A · 黑色 500ml',30); button(1210,153,'返回列表',170)
text(240,209,'来源：京东    采集时间：14:32    当前版本：第 3 次采集',20,MUTED)
box(240,254,1160,54,PALE); text(260,267,'原始信息    图片与整理    内容快照    历史版本',20)
box(240,340,552,228)
text(262,357,'原始采集信息（只读）',24)
for i,label in enumerate(['标题：示例商品 A','显示价格：¥89.00；适用条件保留原文','规格：黑色 / 500ml','库存：页面未显示 → 未知']): text(263,409+i*36,label,18)
box(820,340,580,228)
text(842,357,'人工补充（独立保存）',24)
field(842,414,'备注 / 标签','待进一步核对材质',370)
button(1230,446,'保存',140,True)
box(240,603,1160,230)
text(263,619,'图片：已保存 6 / 已发现 8',24)
button(1180,612,'上传本地图片',195)
for i in range(8):
    x=263+i*139; box(x,668,123,83,PALE if i<6 else AMBER,outline=GREEN if i==0 else LINE)
    text(x+12,676,'图 '+str(i+1)+(' · 已选' if i==0 else ''),18)
    text(x+12,717,'已保存' if i<6 else '失败 / 原因',16)
button(263,775,'设为主图',150); button(423,775,'前移',100); button(533,775,'后移',100)
button(643,775,'移除',120); button(773,775,'恢复',120); button(903,775,'导出此版本',220,True)
status_cards([('查看旧版本','历史观察只读；不会被新采集或人工备注覆盖。','选择历史版本',PALE),('编辑冲突 / 图片缺失','重载当前版本再编辑；只重试失败资源。','重载当前版本',AMBER)])
save('07-商品详情与图片.png')

base('插件展开页：规格参数独立提取', sidebar=False)
box(25,153,839,837)
text(49,178,'当前商品网页 · 用户已滚到参数区',24)
text(49,242,'商品 A · 黑色 500ml',30)
box(49,326,790,362,PALE,outline=GREEN)
text(72,348,'商品参数区域（来源示意）',24)
for i,(label,value) in enumerate([('容量','500ml'),('颜色','黑色'),('材质','页面未显示'),('产地','页面未显示')]):
    y=414+i*58;text(77,y,label,20);text(307,y,value,20)
box(49,739,790,169)
text(72,758,'本次只读取这个已加载区域',24)
text(72,812,'点选排除一行时，其他参数继续保留。',20,MUTED)
text(72,853,'修改范围后，只需要重新提取参数区。',20,MUTED)
box(892,153,521,837)
text(916,177,'规格参数',30)
text(916,229,'已提取 · 当前示例 4 行 · 2 项未知',18,GREEN)
button(916,269,'返回分区列表',219);button(1150,269,'调整区域',239)
box(916,345,473,119,PALE)
text(936,359,'来源范围',20)
text(936,398,'当前商品的参数表；与网页高亮对应。',18,MUTED,430)
box(916,501,473,280)
d.rectangle((918,503,1387,548),fill=PALE)
text(938,512,'参数名称',18);text(1135,512,'网页原值',18)
for i,(label,value) in enumerate([('容量','500ml'),('颜色','黑色'),('材质','未知'),('产地','未知')]):
    y=571+i*47;text(938,y,label,18);text(1135,y,value,18)
text(919,809,'缺失项保留未知；不推测商品参数。',18,MUTED)
button(917,858,'重新提取本区',472,True)
text(919,931,'提取完成后回首页核对，再统一确认保存。',16,MUTED,472)
save('08-参数分区提取.png')

base('导出确认与导出记录','导出记录')
text(240,153,'导出所选商品',30)
box(240,218,1160,161,PALE)
text(264,237,'商品 2 个 · 冻结各自快照与整理版本',24)
text(264,285,'已保存图片 11 张 · 缺失 2 张 · 来源与异常清单一并导出',20)
text(264,332,'导出不回访源站、不补抓图片；原值与人工补充值分开。',18,MUTED)
check(264,421,'仅 Excel：商品汇总、图片清单、快照说明、异常清单',True)
check(264,470,'Excel + 图片 ZIP：增加已存图片、快照与导出说明',False)
field(240,535,'保存位置','由系统文件保存对话框选择',940); button(1200,568,'选择位置',180)
button(240,656,'确认导出',210,True); button(475,656,'取消',180)
box(240,735,1160,93)
text(264,750,'导出记录：已完成 / 正在生成 / 失败 / 已过期',20)
text(264,789,'完成后显示商品数、图片数、缺失数与保存位置。',18,MUTED)
status_cards([('生成失败 / 文件过期','显示原因；可重新选择生成，不伪报下载成功。','重新生成',AMBER),('权限变化 / 商品已删除','拒绝旧导出下载，提示重新选择有效商品。','返回商品结果',AMBER)])
save('09-导出确认与记录.png')

base('设置：复制插件目录','设置')
text(240,153,'设置',30)
box(240,221,1160,622)
text(268,247,'浏览器插件',24)
text(268,297,'插件版本：0.2.0（示例）    最近检测：14:32',18,MUTED)
field(268,356,'当前安装版附带的插件目录（只读，可选中）',
    'C:\\Users\\admin\\AppData\\Local\\Programs\\Aden Local Test\\resources\\collector\\extension',1098,92)
button(268,507,'复制插件目录',230,True);button(522,507,'重新检测',180)
text(268,581,'在 Chrome 加载插件',24)
for i,line in enumerate(['1. 打开 chrome://extensions，开启开发者模式。','2. 选择“加载已解压的扩展程序”。','3. 粘贴上方目录，并确认加载。']):text(270,630+i*52,line,20)
text(240,888,'操作反馈在目录或按钮附近显示，不单独铺设异常卡片。',18,MUTED)
save('10-设置与插件目录.png')

base('关于 Aden','关于')
text(240,153,'关于',30)
box(240,221,1160,716)
text(281,258,'Aden',48,GREEN)
text(282,329,'商品采集与资料整理',24)
text(282,380,'版本 0.3.0（示例）',18,MUTED)
d.line([(282,440),(1357,440)],fill=LINE,width=2)
text(282,473,'使用步骤',24)
for i,line in enumerate(['1. 自己在 Chrome 打开需要保存的商品详情页。','2. 在插件分区提取，检查后确认保存。','3. 在商品结果查看、整理图片并导出。']):text(283,528+i*65,line,20)
text(282,773,'诊断信息',20)
button(282,820,'展开诊断信息',230)
save('11-关于.png')

base('后续候选：采购询价与微信客服', sidebar=False,candidate=True)
box(25,150,681,840); box(732,150,682,840)
text(48,171,'采购询价 · 三栏沟通与报价比较',24)
field(49,231,'需求复核','数量、规格、到货预算、城市、截止',630)
for x,w,label in [(49,157,'供应商列表'),(218,270,'消息与待审草稿'),(502,177,'报价条件')]:
    box(x,350,w,340,PALE); text(x+12,369,label,18)
    text(x+12,426,('商家 A\n商家 B\n身份来源' if x==49 else '最终正文\n目标对象\n每轮单次确认' if x==218 else '税费 / 运费\n交期 / 起算\n缺项标注'),16,MUTED,w-24)
button(235,612,'审核后发送',224,disabled=True)
box(49,730,630,217,AMBER); text(69,747,'比较只使用完整、同口径报价',24)
text(69,797,'缺运费不当作 0；结果保存为采购建议，交易另由人确认。',18,MUTED,590)
text(754,171,'微信客服 · 会话、草稿与事实来源',24)
for x,w,label in [(754,150,'白名单会话'),(918,274,'消息与回复草稿'),(1206,182,'引用事实')]:
    box(x,247,w,443,PALE); text(x+12,265,label,18)
    text(x+12,329,('已关联客户\n重名则阻断' if x==754 else '待处理消息\n回复草稿\n账号 / 联系人复核' if x==918 else 'ERP库存\n订单进度\n来源与时间'),16,MUTED,w-24)
button(938,610,'逐条确认发送',232,disabled=True)
box(754,730,634,217,AMBER); text(775,747,'真实环境与身份条件未验证时',24)
text(775,797,'保持不可发送；无法取得稳定会话身份时只交付草稿。用户抢占立即停止新动作。',18,MUTED,588)
save('12-采购与客服候选界面.png')

pages=['01-登录.png','02-工作台统计.png','03-插件提取与预览.png','04-区域点选与关键异常.png',
       '05-采集任务与详情.png','06-商品列表与导出.png','07-商品详情与图片.png','08-参数分区提取.png',
       '09-导出确认与记录.png','10-设置与插件目录.png','11-关于.png','12-采购与客服候选界面.png']
thumbs=Image.new('RGB',(1440,6*520),'white')
for i,name in enumerate(pages):
    with Image.open(OUT/name) as pic:
        pic.thumbnail((720,520));thumbs.paste(pic,((i%2)*720,(i//2)*520))
thumbs.save(OUT/'界面图总览.png')
print('WIREFRAMES_CREATED: 12 pages and overview; design 1.2')
