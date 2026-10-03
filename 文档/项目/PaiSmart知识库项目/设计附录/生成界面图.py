"""绘制独立PNG界面设计，不控制浏览器，不生成产品验收截图。"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / '图稿'
FONT = 'C:/Windows/Fonts/msyh.ttc'
FONTS = {size: ImageFont.truetype(FONT, size) for size in (16, 18, 20, 22, 26, 32)}
BLUE, INK, MUTED, LINE = '#409eff', '#303133', '#606266', '#dcdfe6'

def text(x, y, value, size=20, color=INK, width=1120, line=34):
    for para in value.split('\n'):
        cur = ''
        for char in para:
            if d.textlength(cur + char, font=FONTS[size]) > width:
                d.text((x, y), cur, font=FONTS[size], fill=color)
                y += line
                cur = char
            else:
                cur += char
        d.text((x, y), cur, font=FONTS[size], fill=color)
        y += line
    return y

def box(x, y, w, h, title='', body='', fill='white'):
    d.rounded_rectangle((x, y, x+w, y+h), radius=7, fill=fill, outline=LINE, width=1)
    if title: text(x+18, y+15, title, 22, width=w-36)
    if body: text(x+18, y+58, body, 18, width=w-36, line=31)

def button(x, y, label, primary=False, danger=False):
    w = int(d.textlength(label, font=FONTS[18])) + 34
    color = '#b63832' if danger else ('white' if primary else INK)
    d.rounded_rectangle((x, y, x+w, y+42), radius=5, fill=BLUE if primary else 'white', outline='#e6b4b0' if danger else LINE)
    text(x+17,y+7,label,18,color,width=w-20)
    return x+w+12

def field(x, y, w, label, value, select=False, multiline=False):
    text(x,y,label,16,MUTED,width=w)
    h=70 if multiline else 40
    d.rounded_rectangle((x,y+27,x+w,y+27+h),radius=4,fill='white',outline='#c0c4cc')
    text(x+10,y+34,value,16,width=w-35,line=25)
    if select: text(x+w-26,y+34,'∨',16,MUTED)

def bar(y, content, warn=False, error=False):
    fill = '#fff0f0' if error else ('#fff8e8' if warn else '#ecf5ff')
    color = '#a42d2d' if error else ('#805b22' if warn else '#235b88')
    d.rounded_rectangle((224,y,1412,y+72),radius=6,fill=fill,outline=LINE)
    text(240,y+13,content,18,color,width=1150,line=29)

def shell(title):
    global im, d
    im = Image.new('RGB',(1440,1120),'#f5f7fa'); d=ImageDraw.Draw(im)
    d.rectangle((0,0,1440,100), fill='#18324f')
    text(24,16,'PaiSmart知识库完整迁移 · '+title,32,'white')
    text(24,62,'界面设计 1.0 ｜ 待评审 ｜ 2026-10-02 ｜ 虚构数据，不是实际产品截图',18,'#dbe9f9')
    d.rectangle((0,100,200,1080),fill='#304156')
    text(16,124,'若依共享管理平台',18,'white',width=180)
    for i, label in enumerate(['首页','知识库','　文档库','　知识问答','　历史记录','　组织空间','　用量与充值','　知识治理','　运营配置','系统管理']):
        y=180+i*51
        if i==1: d.rectangle((0,y-5,200,y+40),fill='#263445')
        text(20,y,label,18,'#62b1ff' if i==1 else '#c4d0df',width=170)
    text(16,766,'服务器授权菜单\nLayout / TagsView\nnavType 1 / 2 / 3',16,'#bac5d1',width=170,line=32)
    d.rectangle((200,100,1440,210),fill='white')
    text(224,119,'三　知识库 / '+title,20)
    text(1165,119,'布局设置　用户甲',18)
    d.line((200,164,1440,164),fill=LINE)
    text(224,176,'首页　　 ● '+title+'　×',18,'#477aad')
    text(224,230,title,26)
    text(224,275,'复用共享若依菜单、权限和公共组件；以下业务区域拟新增。',18,MUTED)
    text(224,1085,'设计资产：控件、数据和状态用于审阅，不连接真实服务。',16,MUTED)

def table(y, heads, rows, widths):
    x=224
    for head,w in zip(heads,widths):
        d.rectangle((x,y,x+w,y+46),fill='#eef2f7',outline=LINE)
        text(x+10,y+9,head,18,width=w-20)
        x+=w
    for i,row in enumerate(rows):
        x=224; top=y+46+i*70
        for value,w in zip(row,widths):
            d.rectangle((x,top,x+w,top+70),fill='white',outline=LINE)
            text(x+10,top+9,value,16,width=w-20,line=25);x+=w

def save(name): im.save(OUT/name)

shell('文档与上传')
field(224,306,245,'知识范围','全部可访问',True)
field(490,306,240,'文件名','接口说明')
field(750,306,220,'处理状态','全部',True)
button(1050,315,'查询',True); button(1150,315,'上传文档',True)
table(375,['文档与范围','处理状态','预计 / 实际Token','操作'],[['接口说明2026.pdf\n研发 / 平台组 · 组织共享','可检索','18,400 / 17,930','预览 / 下载 / 重建'],['设备手册.docx\n个人空间 · 私有','向量化中58%','3,800 / 处理中','查看任务 / 取消'],['规范扫描件.pdf\n公开 · 平台授权用户','OCR超时','12,000 / 8,100','查看原因 / 重试']], [380,200,260,348])
box(224,650,570,240,'续传上传弹窗','所属空间：研发 / 平台组　范围：组织共享\n文件：接口说明2026.pdf · 12.6MiB\n已校验2 / 3片；刷新后只补缺失片\n预计冻结18,400 Embedding Token')
button(240,827,'暂停');button(345,827,'继续上传',True)
box(812,650,600,240,'知识检索弹窗','检索问题：接口超时如何处理？　TopK：5\n证据：接口说明2026，第8页 / 块21\n重排不可用：已按RRF降级\n结果可少于TopK；预览始终使用原文')
bar(912,'额度不足：当前可用9,000，需18,400。保留分片，可查看用量或申请追加。',error=True)
text(240,1000,'其他状态：加载保留筛选；空列表提供上传/申请入口；格式错误明确拒绝。',18,MUTED)
save('01-文档与上传设计.png')

shell('问答与引用')
bar(316,'连接已断开，现有回答保留；恢复接续原生成编号，不重复发送提问。',warn=True)
box(224,410,180,535,'会话列表','＋新建会话\n接口处理流程\n产品资料摘要\n已归档会话\n归档 / 恢复')
box(420,410,580,535,'问题：接口超时有哪些要求？','工具：知识检索 · 已完成\n资料规定单次超时后有限重试，最多3次 [1]\n达到次数后进入人工处理 [2] 待核实\n\n部分回答已保存328字，正在等待重连')
button(438,663,'点赞');button(538,663,'点踩 / 纠错');button(697,663,'复制')
field(438,720,540,'输入问题（Enter发送，Shift+Enter换行）','请整理异常处理步骤',multiline=True)
button(438,865,'恢复连接',True);button(580,865,'停止生成',danger=True)
box(1018,410,394,535,'引用1 · 原文','接口说明2026.pdf\n第8页 / 块21 · 内容版本3\n\n8.异常处理\n超时后最多执行3次重试。\n每次需保存请求编号……\n\n上一页 / 下一页 / 下载\n\n原文锚点标亮，历史保存引用映射')
bar(970,'权限撤销：关闭正文预览，保留历史来源编号；进程重启中断时明确终态与实际用量。',error=True)
save('02-问答与引用设计.png')

shell('冲突处置')
bar(316,'检测候选：同一配置项“请求超时”数值不同，相似度0.93；检测不能自动删文档。',warn=True)
box(224,410,585,230,'新文档 · 接口规范2026.pdf','内容版本2 / 第8页\n生产请求超时为60秒。\n本规范自10月1日起生效。\n打开原文')
box(827,410,585,230,'旧文档 · 接口规范2025.pdf','内容版本5 / 第6页\n生产请求超时为30秒。\n上传时间：2025-11-10\n打开原文')
box(224,668,1188,240,'处置确认','影响：旧文档整篇退出检索；关联3个冲突失效，12条历史引用受影响。')
field(242,764,350,'处置方式','保留新文档，移除旧文档',True)
field(612,764,430,'处置理由','新版已生效，旧版进入回收站')
button(980,840,'取消');button(1080,840,'确认处置整篇文档',danger=True)
bar(935,'并发变化：其他管理员已裁决，请刷新。可恢复文档并重新索引，原裁决记录保留。',warn=True)
save('03-冲突处置设计.png')

shell('索引与回滚')
box(224,315,575,145,'当前索引与模型','别名：paismart_knowledge_current\n模型E1 · 2048维 · revision8')
box(817,315,595,145,'重建作业')
field(835,365,330,'目标供应商 / 模型','模型E2 · 1024维',True)
button(1180,391,'开始重建',True)
box(224,490,1188,220,'全量 → 增量追平 → 校验 → 切换 → 恢复消费','当前：增量追平　24,800 / 28,130片段\n待处理：上传30 / 删除2 / 权限变更1\n进度与checkpoint持久化；切换时短暂阻断本业务查询')
button(240,640,'查看任务',True);button(390,640,'查看失败片段')
table(742,['保留版本','模型 / 维度','当前状态','操作'],[['revision8','E1 / 2048','服务中','查看快照'],['revision6','E0 / 2048','保留备份','检查回滚条件 / 回滚']], [290,290,260,348])
bar(955,'无法回滚：当前配置与旧索引模型不符。先恢复配置；切换失败按持久日志补偿。',error=True)
save('04-索引与回滚设计.png')

shell('评测与复核')
field(224,305,350,'数据集','接口标注集v3 · 50例',True)
field(595,305,150,'最多返回','5')
field(765,305,240,'运行标签','重排对比')
button(1080,311,'上传JSONL');button(1220,311,'运行',True)
bar(375,'冻结快照：模型E1 / RRF60 / 重排启用 / 邻居扩展 / 抽样10% / seed42。',False)
box(224,476,378,140,'检索 Recall@5','0.84 · 由人工标注复算')
box(620,476,378,140,'模型裁判支持比例','0.78 · 失败样本单列')
box(1016,476,396,140,'人工一致性','目前3例，至少5例再判断')
box(224,645,580,273,'逐例证据','问题：超时设置是多少？\n标注：60秒；原文：规范2026第8页\n模型答：60秒 [1]\n裁判：支持 / 证据吻合\n查看配置快照 / 下载JSON报告')
box(822,645,590,273,'人工复核')
field(840,695,550,'人工结论','支持',True)
field(840,773,550,'复核理由','答案与生效新版原文一致')
button(840,855,'查看修订历史');button(1015,855,'保存人工结论',True)
bar(945,'数据集第7行缺question字段：未启动、无消耗。复核并发变更先比较，不覆盖旧结论。',error=True)
save('05-评测与复核设计.png')

shell('组织与资格')
box(224,315,370,340,'组织空间树','研发\n　平台组（已选）\n财务\n公开知识区\n个人空间（用户隔离）\n新增 / 绑定既有部门')
box(614,315,798,340,'空间配置与成员')
field(632,366,330,'空间名称','平台组');field(982,366,400,'父空间','研发',True)
field(632,450,330,'非管理员上传数量上限','100');field(982,450,400,'成员（既有若依账户）','用户甲 / 主空间：平台组',True)
text(632,537,'可读本空间及祖先共享资料，不自动读子组。',16,MUTED,width=745)
button(632,580,'保存',True);button(735,580,'配置成员');button(885,580,'查看平台账户')
box(224,685,1188,210,'邀请码资格','绑定空间：研发　最多使用：1　有效期：2026-10-31\n创建 / 批量创建 / 更新 / 禁用 / 删除\n邀请码赋予业务资格，不能授予平台管理员；平台注册关闭不能绕过')
bar(921,'拒绝：组织树不能形成环；在用空间不能直接删除；过期或已消费邀请码不能兑换。',error=True)
save('06-组织与资格设计.png')

shell('模型供应商')
text(224,320,'业务配置页签：对话模型　|　Embedding　|　重排　|　OCR / 裁判',18)
box(224,380,620,490,'供应商配置')
field(242,433,580,'协议','OpenAI兼容',True)
field(242,515,580,'已批准服务地址','https://批准的模型服务/v1')
field(242,597,580,'模型名称','批准的模型版本')
field(242,679,580,'凭证（已配置，不回显）','留空保留现有值')
button(242,784,'连通性测试');button(410,784,'保存候选配置',True)
box(864,380,548,490,'Embedding切换','目标维度：1024\n\n配置保存 → 创建重嵌入任务\n校验完成 → 索引/模型成对激活\n\n当前测试：未真实执行\n\n进入索引管理 / 查看脱敏失败原因')
bar(910,'未经批准的内部地址/重定向被拒绝，凭证不发送；无修改权限只读配置状态。',error=True)
save('07-供应商设计.png')

shell('用量与充值')
box(224,315,378,140,'LLM可用 / 冻结','32,100 / 2,000 Token')
box(620,315,378,140,'Embedding可用 / 冻结','18,300 / 5,000 Token')
box(1016,315,396,140,'今日请求 / 日上限','12 / 50次')
box(224,485,600,310,'正式用量流水','问答结算：-820　32,920 → 32,100\n管理员追加：+10,000　22,920 → 32,920\n理由：资料评测预算\n\n日期筛选 / 每日趋势 / 逐笔导出\n限流：分钟上限 / 日上限 / 时间窗口')
box(844,485,568,310,'套餐 / 自定义充值')
field(862,536,520,'选择套餐 / 自定义金额','批准套餐A（价格待审核）',True)
d.rectangle((862,630,975,743),outline='#aab3bf',fill='#f5f7fa')
text(874,652,'二维码\n设计占位\n不可扫码',16,MUTED,width=96,line=24)
text(992,638,'订单：设计样例 · 待支付\n价格和Token权益待审核\n剩余有效期：4分钟',16,width=390,line=29)
button(1000,743,'查询订单状态',True);button(1200,743,'套餐管理')
bar(825,'余额仅够一次时拒绝第二并发请求；追加/结算/付款重复请求不会重复入账。',warn=True)
bar(925,'支付未配置时明确不可支付；二维码过期后需确认新建，不自动再次收费。',error=True)
save('08-用量与充值设计.png')

shell('运维与恢复')
box(224,315,378,180,'依赖状态','MySQL / ES：可达\n解析Worker：失联\n状态不等于业务验收通过')
box(620,315,378,180,'队列 / 对象','Kafka：积压32条\nMinIO / Redis：可达\n仅本业务命名空间')
box(1016,315,396,180,'外部能力','支付：未配置\n阿里OCR：已关闭\n查看批准配置来源')
table(530,['作业','范围','状态','操作'],[['对象搬迁','批准目录 / 128对象','校验中','查看manifest / 暂停'],['重新索引','文档A / 版本3','有限重试耗尽','查看原因 / 人工重试']], [290,320,270,308])
box(224,770,1188,210,'清理预览 · 等待执行授权','范围：本知识库回收站中过期文档，共12份 / 80MiB\n不清理平台用户、其他业务、账务审计；下载对象清单 / 查看审批\n恢复：批准备份manifest与对象快照，保留目标新增记录后核对差异')
button(242,921,'下载对象清单');button(430,921,'查看审批');button(580,921,'未获执行授权',danger=True)
bar(998,'未获授权：执行按钮禁用。启动不会自动创建管理员、导入内置资料或清空数据。',error=True)
save('09-运维与恢复设计.png')
print('已绘制9张独立PNG设计图：1440×1120。')
