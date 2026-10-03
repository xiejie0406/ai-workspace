"""从本轮事实生成报告；只写本功能证据和追加唯一实施记录。"""
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
import html
import json
import shutil
from urllib.parse import unquote
import re

HERE = Path(__file__).resolve().parent
FEATURE = HERE.parent.parent
PROJECT = FEATURE.parent.parent
now = datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()
environment = json.loads((HERE/'只读环境核对.json').read_text(encoding='utf-8-sig'))
old = json.loads((HERE.parent/'2026-10-01-统一设计实现/业务记录.json').read_text(encoding='utf-8-sig'))
shutil.copyfile(HERE/'截图/01-安装版登录离线.png', HERE/'截图/01-安装版登录离线.jpg')

modules = [
 {'name':'执行底座与任务、事件', 'design':'现行统一设计及已批准底座切片', 'implementation':'RuoYi任务内核、REST/ETag、审计/Outbox、可恢复SSE；Electron安全壳；低权限Runner simulator、离线Fake Agent。采集任务读取权限映射已在源码中。', 'technical':'历史底座Java/MySQL、跨进程合成成功/取消验证；旧任务读取18项定向检查。10月1日新包构建已记录。本轮未重复构建/测试。', 'business':'本轮中心离线，任务、事件与统计页面受阻；旧隔离库结果未沿用为当前原库通过。', 'use':'环境恢复后在桌面查看任务、状态与回执。', 'gap':'原库缺Aden表及Runner用途；没有当前有效空间/成员依据和新心跳。', 'acceptance':'待确认'},
 {'name':'审批与真实动作控制', 'design':'动作内容绑定、逐次确认、单次消费、未知结果回查等候选/后续要求', 'implementation':'当前可核实的是CORE任务命令、取消和回执门禁；源码未检出可确认的独立生产审批功能。', 'technical':'CORE取消/幂等历史技术检查不能代表外发审批通过。', 'business':'采购/微信批准发送链路未执行，无实际生产审批页面证据。', 'use':'当前不能据底座代码直接批准真实外发。', 'gap':'需独立实现与验证审批、连接器和动作回查链路。', 'acceptance':'待确认'},
 {'name':'安装版与本地同步', 'design':'当前用户安装、本地同步与候选发布形态', 'implementation':'Aden Local Test 0.3.0，固定安装路径；可见编译目录与sync:local脚本；云端手动打包配置。', 'technical':'本轮19个关键文件与10月1日基线匹配，桌面bundle与工作区相同；未重新打包/同步。云端打包、签名、发布仍未验证。', 'business':'首次PowerShell启动退出9，日志为bad option；显式环境定向启动后实际看见登录页并保存回读截图。', 'use':'从本机EXE启动。登录页有插件目录与设置入口。', 'gap':'常规启动路径仍有现场失败；诊断启动成功不等于已修复脚本或完整安装业务通过。', 'acceptance':'待确认'},
 {'name':'京东详情页手动采集', 'design':'已批准当前详情页七区提取、预览、统一保存与区域加入/排除', 'implementation':'Chrome扩展、桌面桥接、后端保存已编码；页面/规格变化失效、防重放、参数行来源及超限拒绝等已有代码。', 'technical':'10月1日扩展52项、Host20项及后端75项为历史合成/定向检查；本轮仅核对分发文件。', 'business':'本轮未访问或采集真实京东，也未加载Chrome扩展。', 'use':'用户手动搜索/打开详情→插件按区提取→核对→确认保存。当前联机不能完成。', 'gap':'原库/中心缺前提；Host注册指向另一安装；真页和完整异常矩阵未完成。', 'acceptance':'待确认'},
 {'name':'商品库与内容、图片整理', 'design':'商品去重、快照版本、人工备注/图片来源、缺图及恢复规则', 'implementation':'商品列表/详情、快照版本、人工补充与图片整理；取消手动新增、商品删除、回收站入口。', 'technical':'历史合成保存闭环及组件检查已记录；本轮未写数据、未重跑业务。', 'business':'当前原库没有Aden表，商品列表/详情/图片新截图未取得。旧隔离空间0商品不代表原库当前查询成功。', 'use':'恢复后从商品库查来源、版本及图片，保留采集原文与人工整理。', 'gap':'缺当前原库业务样本和有效会话；保存/版本/缺图恢复未实测。', 'acceptance':'待确认'},
 {'name':'Excel、图片包与导出记录', 'design':'选择范围、冻结版本、缺失计数、权限回读', 'implementation':'后端导出任务与文件处理，桌面范围确认和导出记录页已有代码。', 'technical':'历史合成导出与定向测试；本轮未生成或打开Excel/ZIP成品。', 'business':'本轮受原库/会话/样本条件阻塞，未取得导出页面和成品证据。', 'use':'环境恢复后选择商品或当前筛选范围生成Excel/ZIP，再查导出记录。', 'gap':'需在获明确授权的样本范围执行导出并打开表格、解包核对冻结版本。', 'acceptance':'待确认'},
 {'name':'扩展与Native Host分发', 'design':'固定安装目录、受限本机桥及兼容诊断', 'implementation':'扩展与Host已随本机安装资源分发，安装设置页有固定目录检测/复制。', 'technical':'本轮19文件哈希匹配；manifest与Chrome注册目标只读核对。', 'business':'设置点击时用户Escape中止；Host注册仍指向Aden Collection Test，不能称当前安装版已连接。', 'use':'用户可从安装版设置取得扩展目录，实际Chrome加载与注册兼容需继续检查。', 'gap':'实际加载、配对/兼容页面证据缺失；本轮未改注册或权限。', 'acceptance':'待确认'},
 {'name':'采购询价', 'design':'需求、费用/报价比较、逐次确认与停止条件已有设计', 'implementation':'存在本地交互原型；没有可核实的生产连接器。', 'technical':'历史原型37项通过仅限本地演示覆盖；未本轮重跑。', 'business':'未联系真实供应商，未实现生产任务/询价链路；本轮未打开原型替代安装版。', 'use':'原型可供评审流程，不能对外询价。', 'gap':'生产任务、金标/故障集、平台和Runner集成未完成。', 'acceptance':'生产能力未接受'},
 {'name':'微信客服', 'design':'长期候选设计，真实范围与固定UIA条件待明确', 'implementation':'未实现微信连接器。离线Agent Fake不能视为客服。', 'technical':'消息识别、ERP、草稿失效、逐次批准与发送未验证。', 'business':'未读取或发送真实微信消息。', 'use':'当前没有可用客服入口。', 'gap':'需独立范围决定、实现与实机试点，不能由商品采集通过推导。', 'acceptance':'生产能力未接受'},
]

checks = [
 {'name':'安装版启动与登录页（局部）','existingRefs':['第1项','第17项'], 'precondition':'固定EXE、原profile、local-test/18093；不需账号。','steps':'普通安装启动失败后保存诊断；显式环境定向启动；定位唯一安装窗口并激活、拍图。未填写或提交登录。','expected':'实际窗口可见；空账号/密码、无空间选择；中心离线明确提示。','actual':'08:23普通启动退出码9，错误为bad option: --user-data-dir；08:25定向启动进程584在5秒检查内未退出，随后窗口显示登录页。账号/密码为空，无空间选择，提示无法连接中心服务。','result':'通过','scope':'只证明恢复后登录展示与真实离线反馈，保留首次启动未通过；不使完整登录/隔离业务通过。','screenshots':['截图/01-安装版登录离线.jpg'],'screenshotStatus':'已截图并以原始尺寸回读','gap':'普通启动失败尚未修复；未登录工作台。'},
 {'name':'离线设置与复制目录','existingRefs':['第16项','目录与离线设置历史条目'],'precondition':'安装版与已有扩展文件。','steps':'点击登录页插件目录与设置；须核对页面、复制路径并返回。','expected':'离线可查看当前目录，版本一致，复制值完整且可返回。','actual':'点击动作返回用户物理Escape停止电脑操作；动作结果未知，没有刷新观察，不重试。','result':'受阻','scope':'此前离线设置局部6项仅作为10月1日历史证据，本轮未重新通过。','screenshots':[],'screenshotStatus':'截图阻塞：用户停止电脑操作','gap':'没有设置、复制和返回的新证据。'},
 {'name':'关于页与实际运行版本','existingRefs':['第17项'],'precondition':'关于入口可达。源码路由为登录保护。','steps':'待有权限已有会话后进入关于并核对安装元数据。','expected':'0.3.0、local-test、18093与实际文件一致。','actual':'本轮未登录；源码关于路由需要认证。电脑操作已停止，未进入关于。文件元数据已只读确认。','result':'受阻','scope':'文件版本不能替代关于页截图。','screenshots':[],'screenshotStatus':'截图阻塞：会话及操作前提缺失','gap':'缺关于页面截图。'},
 {'name':'工作台与采集任务列表、详情','existingRefs':['第2项','第15项','第18项','旧任务读取失败与修复'],'precondition':'原库schema、中心、有效成员/会话、已有任务。','steps':'条件恢复后只读工作台→采集任务→已有详情，并对照后台数据。','expected':'统计真实，失败不当零，列表详情权限正确、错误可辨。','actual':'18093无监听；原ry-vue的Aden表0张，没有可用业务会话。旧18项读取权限修复及同步留为历史技术证据，不把旧空间0任务计成本轮查询。','result':'受阻','scope':'旧失败及修复均保留；本轮没有任务页面复验。','screenshots':[],'screenshotStatus':'截图阻塞：中心/原库前提缺失','gap':'无法核对统计、任务列表/详情及实际状态。'},
 {'name':'商品库、图片与导出记录','existingRefs':['第7～14项'],'precondition':'原库、已有有效会话、预先有权读取的商品/文件。','steps':'恢复后只读商品列表→详情→图片→已有导出记录。','expected':'SKU、版本、人工来源、图片与缺失数量、冻结导出版本一致。','actual':'原库Aden表0张，中心离线；本轮没有当前原库商品/任务查询、保存或生成导出。','result':'受阻','scope':'当前无商品/导出页面截图，旧合成链路仅保留历史。','screenshots':[],'screenshotStatus':'截图阻塞：原库/中心/样本缺失','gap':'列表详情、图片恢复、Excel/ZIP成品均未实测。'},
 {'name':'手动采集、扩展与Native Host','existingRefs':['第3～6项','第17项'],'precondition':'兼容注册、真实Chrome扩展/受控页、原库与已有明确写入范围。','steps':'本轮只读文件、manifest和Host注册，不加载扩展、不采集。','expected':'当前安装与注册目标一致，实际链路经过独立浏览器操作证明。','actual':'安装文件哈希匹配；Host注册指向另一Aden Collection Test。没有本轮Chrome扩展/真页截图或保存证据。','result':'受阻','scope':'分发文件核对不计实际采集通过。','screenshots':[],'screenshotStatus':'截图阻塞：实际通道与原库前提未成立','gap':'需明确兼容注册处理及实际Chrome通道检查；本轮未修改注册。'},
 {'name':'执行底座、审批事件与Runner联机','existingRefs':['底座既有验收','第五部分第3节'],'precondition':'原中心、空间成员、有效Runner凭据和原实例。','steps':'只读进程/监听、托管用途元数据与最近成功心跳。','expected':'新成功心跳、原实例身份和当前中心一致才能称在线。','actual':'18093/16393未监听；未找到Aden Runner进程。最后成功心跳2026-10-01 18:14:33上海时间，原库Runner用途缺失。旧restore-runner.py未运行。','result':'受阻','scope':'环境JSON属技术证据；当前不能验证任务/审批/事件业务。','screenshots':[],'screenshotStatus':'联机页面截图受阻；只读技术元数据不适用截图','gap':'不得自动创建空间、成员、凭据或替代Runner。'},
]

record = {'date':'2026-10-02','checkedAt':now,'version':'1.2.2','uiBaseline':'1.2.0','installedVersion':'0.3.0','userAcceptance':'待确认','scope':'项目进度查实及7项定向观察，未完成完整业务验收','modules':modules,'checks':checks,'localCounts':{'通过':1,'未通过':0,'受阻':6,'未执行':0},'firstLaunchFailurePreserved':True,'technicalFindings':[{'name':'常规安装启动','result':'未通过','actual':'退出码9，bad option错误原文保留；定向恢复成功不覆盖该失败'},{'name':'中心服务连接','result':'未通过','actual':'安装版实际显示无法连接中心服务，18093未监听；完整业务项前提受阻'},{'name':'安装分发哈希','result':'通过','actual':'19文件与10月1日基线一致，桌面bundle匹配工作区'}],'wholeBusinessBaseline':{'source':'../2026-10-01-统一设计实现/业务记录.json','counts':old['counts'],'newlyExecutedWholeBusiness':0,'historyNotReset':True},'computerUseStoppedByUser':True,'reportVisual':{'opened':False,'reason':'用户物理Escape停止电脑操作；未再调用电脑/浏览器控制'},'browser':{'tool':'Codex内置浏览器（cua_repl）','sessionId':'01a0f9f5-8f55-7df1-85fd-d3e781e01fb3','inventoryRead':True,'tabsCreated':0,'otherTabsChanged':False,'cookiesReadOrAccountChanged':False,'engineVersion':'未知','viewport':'未知'},'desktop':{'tool':'computer-use / @oai/sky','windowTitle':'Aden 桌面智能执行平台','app':'固定安装EXE','screenshotPixels':'1470×933','visibleObserved':True},'sourceEnvironment':'只读环境核对.json'}
(HERE/'业务记录.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
e = lambda value: html.escape(str(value), quote=True)
module_rows=''.join('<tr>'+''.join('<td>'+e(m[k])+'</td>' for k in ('name','design','implementation','technical','business','use','gap','acceptance'))+'</tr>' for m in modules)
cards=[]
for i,c in enumerate(checks,1):
    images=''.join('<figure><a href="'+e(p)+'"><img src="'+e(p)+'" alt="'+e(c['name'])+'实际安装版截图"></a><figcaption>本轮实际安装版截图，原图1470×933；已保存并回读。</figcaption></figure>' for p in c['screenshots'])
    cards.append('<article><h3>'+str(i)+'. '+e(c['name'])+' <span class="badge '+('pass' if c['result']=='通过' else 'blocked')+'">'+e(c['result'])+'</span></h3>'+''.join('<p><b>'+label+'：</b>'+e(c[key])+'</p>' for label,key in [('前提','precondition'),('操作','steps'),('执行前预期','expected'),('实际情况','actual'),('证明边界','scope'),('截图情况','screenshotStatus'),('不足与影响','gap')])+images+'</article>')
document='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>智能体桌面端：2026-10-02进度与页面检查</title><style>body{margin:0;background:#f4f7f6;color:#21372e;font:16px/1.7 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1280px;margin:auto;padding:40px 28px}header{background:#173f2e;color:white;padding:32px;border-radius:18px}h1{margin:0;font-size:30px}h2{margin-top:36px}h3{font-size:20px}a{color:#24714c}header a{color:#c3f0d1}.notice{background:#fff2d5;border-left:5px solid #d09b29;padding:18px;margin:24px 0}.table-wrap{overflow:auto;background:white;border-radius:12px}table{border-collapse:collapse;min-width:1500px;width:100%;font-size:14px}th,td{padding:14px;border-bottom:1px solid #e1e9e4;vertical-align:top;text-align:left;min-width:140px}th{background:#e6efea}td:first-child{font-weight:700;min-width:120px}article{background:white;padding:20px 26px;border:1px solid #dce6df;border-radius:12px;margin:20px 0}p{margin:10px 0}.badge{font-size:14px;border-radius:20px;padding:4px 12px;white-space:nowrap}.pass{color:#186944;background:#e0f4e8}.blocked{color:#8a5815;background:#fff0d0}img{display:block;width:100%;height:auto;border:1px solid #dce6df;border-radius:8px}figure{margin:24px 0 0}figcaption{color:#61776b;font-size:14px}.muted{color:#64776f}li{margin:8px 0}@media(max-width:700px){main{padding:20px 14px}header{padding:22px}h1{font-size:24px}article{padding:16px}}</style><main>'''
document += '<header><h1>智能体桌面端项目：进度与实际页面检查</h1><p>2026-10-02 · 统一设计1.2.2 / 界面1.2.0 · 本机安装版0.3.0 · 用户接受待确认</p><p>本轮已查实实现范围，恢复并看见安装版登录页；完整业务联机受阻。</p></header>'
document += '<div class="notice"><b>电脑操作已由用户Escape停止。</b>设置点击结果未知，后续页面与本报告内置浏览器预览没有继续执行。本报告已生成并检查结构、链接和图片文件；尚未进行浏览器整页视觉核对。登录截图是本轮实际画面，采购原型、设计图及旧合成截图未用作当前页面通过证据。</div>'
document += '<h2>本轮结果及使用入口</h2><p>7项定向观察：登录展示局部通过1项、受阻6项。首次普通启动未通过单独保留，恢复后局部通过没有覆盖该失败。中心连接实际未通过：安装版显示无法连接中心服务，18093未监听；完整业务项因前提缺失受阻。原18项完整业务沿用10月1日判定：0通过、17受阻、1未执行，本轮完整业务新执行0项；没有代用户接受。</p><p>桌面入口：<code>'+e(environment['installation']['path'])+'</code>；中心<code>http://127.0.0.1:18093</code>。本轮检查时中心和Redis均离线。报告当前以本地文件交付，没有创建浏览器标签。</p>'
document += '<h2>中文业务模块进度</h2><p class="muted">横向查看完整表格。设计、源码、技术检查、业务结果和用户接受分别列出，历史技术结果未作本轮新通过。</p><div class="table-wrap"><table><thead><tr>'+''.join('<th>'+x+'</th>' for x in ['模块','设计','源码实现','技术检查','实际页面/业务结果','用户怎样使用','缺口与业务影响','用户接受'])+'</tr></thead><tbody>'+module_rows+'</tbody></table></div>'
document += '<h2>逐项检查与实际截图</h2><p>规则来源为统一设计第五部分；完整执行前内容见<a href="手工验收清单.md">本轮手工清单</a>，内部对应和实际结果见<a href="业务记录.json">业务记录</a>。</p>'+''.join(cards)
document += '<h2>环境和历史证据</h2><ul><li>原库localhost:3306/ry-vue的Aden表0张，Runner托管用途不存在；没有迁移、创建或复制数据库。</li><li>19个安装文件与10月1日基线一致；桌面bundle与工作区一致，未并行打包/覆盖运行包。</li><li>Host注册指向另一Aden Collection Test；本轮未改注册、未加载Chrome扩展。</li><li>Runner最后成功心跳为10月1日18:14:33，无本轮原实例新心跳。</li></ul><p><a href="只读环境核对.json">原库、监听、安装哈希、注册及Runner回读</a> · <a href="安装版启动诊断.json">普通启动失败</a> · <a href="安装版启动诊断.stderr.log">退出9错误原文</a> · <a href="安装版定向启动.json">恢复启动</a> · <a href="../2026-10-01-统一设计实现/verification-report.html">10月1日完整业务及局部历史</a> · <a href="../../../FEAT-ADEN-001-桌面执行底座/证据/2026-09-30-独立环境启动/采集任务读取复验.json">旧任务读取失败及修复</a></p>'
document += '<h2>最小下一步</h2><ol><li>恢复电脑操作后，继续安装版设置/复制目录、关于页，并在本对话内置浏览器实际打开本报告核对。</li><li>由用户明确原库补表、已有托管用途恢复及空间/成员前提的具体范围，或提供已迁移且核实的原配置；只恢复既有原中心/Runner。不同动作分别核对授权。</li><li>核对当前安装的Host注册兼容及实际Chrome扩展通道；前提齐备再定向复验任务、商品、图片和导出，保留已有失败与证据。</li></ol><p>采购生产连接器、微信客服、云端构建、正式签名和发布仍未完成，不属于本次只读检查的实施范围。</p><footer class="muted">生成时间 '+e(now)+' · 执行者Codex · 技术与业务结果分开 · 用户接受待确认</footer></main></html>'
(HERE/'verification-report.html').write_text(document,encoding='utf-8')

for name in ('手工验收清单.md',):
    path=HERE/name
    text=path.read_text(encoding='utf-8-sig')
    suffix='\n## 2026-10-02 实际执行结果\n\n登录展示局部通过1项，其余6项受阻；首次普通启动退出9未通过保留，定向恢复后看见实际安装版登录页。设置点击时用户Escape停止电脑操作，结果未知，没有继续点击或浏览器预览。原18项完整业务仍0通过、17受阻、1未执行，本轮完整业务新执行0项。实际情况与证据唯一记录见[业务记录](业务记录.json)和[HTML报告](verification-report.html)；登录截图已保存并以原始尺寸回读，用户接受待确认。\n'
    if '## 2026-10-02 实际执行结果' not in text:
        path.write_text(text+suffix,encoding='utf-8')

implementation=FEATURE/'实现与验证记录.md'
text=implementation.read_text(encoding='utf-8-sig')
backup=HERE/'原文备份'
backup.mkdir(exist_ok=True)
if not (backup/'实现与验证记录.md').exists():
    shutil.copyfile(implementation,backup/'实现与验证记录.md')
section='\n## 2026-10-02 项目进度与页面只读检查\n\n本轮未改业务源码、数据库、公共脚本或Git。原库回读Aden表0张、Runner用途缺失，18093/16393未监听；19个安装文件与10月1日基线一致，桌面bundle匹配工作区，Host注册仍指向另一安装，Runner无新心跳。普通安装启动退出9（bad option: --user-data-dir），原失败保留；显式环境定向启动后实际看见安装版空账号/密码登录页，无空间选择，显示无法连接中心服务，截图已保存并回读。设置点击时用户Escape停止电脑操作，没有再操作或预览浏览器报告。\n\n本轮7项定向观察为1局部通过、6受阻；原18项完整业务判定仍为0通过、17受阻、1未执行，本轮完整业务新执行0项。源码与历史技术检查、当前页面和用户接受分开。审批生产外发链未核实实现，采购仅原型、微信未实现；没有把旧读取权限技术修复或合成空数据记为当前原库页面通过。详见[本轮清单](证据/2026-10-02-项目进度与页面检查/手工验收清单.md)、[业务记录](证据/2026-10-02-项目进度与页面检查/业务记录.json)与[中文报告](证据/2026-10-02-项目进度与页面检查/verification-report.html)。报告仅完成静态检查，整页浏览器视觉核对未执行；用户接受待确认。\n'
if '## 2026-10-02 项目进度与页面只读检查' not in text:
    implementation.write_text(text+section,encoding='utf-8')

missing=[]
for attribute in re.findall(r'(?:href|src)="([^"]+)"',document):
    target=unquote(html.unescape(attribute)).split('#')[0]
    if target and not re.match(r'^[a-z]+:',target) and not (HERE/target).exists():
        missing.append(target)
static={'checkedAt':now,'localHtmlLinksMissing':missing,'imageFileExists':(HERE/'截图/01-安装版登录离线.jpg').exists(),'imageEncoding':'JPEG','imagePixels':[1470,933],'screenshotReread':True,'browserVisualChecked':False,'browserVisualReason':'用户Escape停止电脑操作','modules':len(modules),'checks':len(checks),'originalTextBackup':'原文备份/实现与验证记录.md'}
(HERE/'交付静态核对.json').write_text(json.dumps(static,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(static,ensure_ascii=False,indent=2))
