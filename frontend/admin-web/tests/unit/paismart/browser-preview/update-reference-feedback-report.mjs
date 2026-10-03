// 只更新既有技术结果入口；此前60项/18图、原失败与中间产物保留。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
const root='D:/2025Ai/26-05-23/java-workspace',dir=path.join(root,'文档/项目/PaiSmart知识库项目/设计附录'),name='2026-10-03-迁移补齐-前端技术复验',prefix='2026-10-03-引用主空间检索-'
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const read=name=>fs.readFileSync(path.join(dir,name),'utf8')
const report=JSON.parse(read(name+'.json'))
if(report.citationSpaceSearch.status!=='待本地浏览器操作')throw new Error('本轮结果已更新，不重复覆盖')
const original=JSON.parse(read(prefix+'浏览器操作.log').split('### Result')[1].trim().split('\n')[0])
const stable=JSON.parse(read(prefix+'浏览器稳定终版复验.log').split('### Result')[1].trim().split('\n')[0])
const descriptions={
 '会话甲核验反馈':'点击甲会话读取历史回答；回答引用和右栏分别显示可疑引用“待核实”及实际理由，缺核验字段“未核验”，兼容verified=true“已核验”。右栏内部块布局已补拍回读。',
 '会话乙核验反馈':'实际切换到乙会话，回答与右栏核验标记和理由同步更新；没有甲引用残留。此图覆盖本轮当前引用UI，旧甲乙图仅作历史。',
 '本人主空间初始':'普通用户组件从本人me读取primarySpaceId，显示技术夹具空间甲，不以空字段或余额假定主空间。',
 '本人主空间更新回读':'点击乙的“设为主空间”，本地PUT记录fixture-space-b；成功后再次GET本人信息，主空间显示乙。后端继续裁决资格和权限；未发真实写入。',
 '甲空间检索结果':'选甲空间后查询合同；Mock请求携带fixture-space，显示甲结果。截图已保存回读。',
 '乙空间重开清空':'关闭甲检索，改选乙，再打开；检索词与甲结果都为空。旧请求取消/迟到响应边界由组件用例另行验证。',
 '乙空间检索失败清空':'乙空间检索成功后再输入失败；实际请求携带fixture-space-b，页面显示明确错误并清空旧结果，未呈现甲或乙旧成功结果。'
}
const currentShots=[...stable.results,...original.results.filter(item=>!item.name.startsWith('会话'))].map(item=>({point:item.name,file:path.basename(item.file),url:item.url,calls:item.calls,description:descriptions[item.name],readBack:true,sha256:sha(item.file),basis:item.name.startsWith('会话')?'本轮最后CSS稳定新session操作并回读':'本轮实际Mock操作并回读；最后仅右侧引用CSS改变，不影响此组件'}))
const intermediateShots=[...original.results.filter(item=>item.name.startsWith('会话')),...JSON.parse(read(prefix+'浏览器布局终版复验.log').split('### Result')[1].trim().split('\n')[0]).results].map(item=>({point:item.name,file:path.basename(item.file),sha256:sha(item.file),readBack:true,basis:'保留中间图，不作为稳定最终引用UI证明'}))
const finalBuild=prefix+'含最终引用布局构建产物',buildLog=prefix+'含最终引用布局生产构建.log',duration=Number(read(buildLog).match(/built in ([\d.]+)s/)[1])
if(!/Tests\s+72 passed \(72\)/.test(read(prefix+'终版全量组件检查.log')))throw new Error('72项实际日志不匹配')
if(!read(prefix+'稳定终版浏览器与夹具清理.log').includes('18152无监听'))throw new Error('清理未结束')
const consoleLog=prefix+'稳定终版浏览器控制台.log',networkLog=prefix+'稳定终版浏览器网络.log'
const counts=read(consoleLog).match(/Errors: (\d+), Warnings: (\d+)/)
const network=read(networkLog),requests=[...network.matchAll(/^\d+\. \[\w+\] (\S+)/gm)]
if(requests.some(item=>new URL(item[1]).origin!=='http://127.0.0.1:18152'))throw new Error('稳定网络记录存在非本地来源')
report.typecheck={exitCode:0,command:'npm.cmd run typecheck:paismart',log:prefix+'终版类型检查.log'}
report.unit={exitCode:0,files:10,passed:72,failed:0,command:'npm.cmd run test:unit:paismart',log:prefix+'终版全量组件检查.log',added:12}
report.productionBuild={exitCode:0,durationMilliseconds:Math.round(duration*1000),duration:duration+'秒',command:'npm.cmd run build:prod -- --outDir '+path.join(dir,finalBuild).replaceAll('\\','/'),outDir:finalBuild,log:buildLog,isolatedOutDir:true,sharedDistOverwritten:false,indexSha256:sha(path.join(dir,finalBuild,'index.html'))}
report.browser={session:'paismart-reference-stable',url:'http://127.0.0.1:18152',host:'127.0.0.1',proxy:false,viewport:[1280,1200],externalNetworkObserved:false,staticRequests:requests.length,errors:Number(counts[1]),warnings:Number(counts[2]),scope:'引用甲乙最终CSS稳定定向Mock；主空间及检索5图沿用本轮已执行且不受该CSS影响的操作。没有真实菜单/后台代理/登录。',networkLog,consoleLog}
report.cleanup={browserSessions:['paismart-reference-feedback 已关闭','paismart-reference-stable 已关闭'],fixturePort18152:'两个本人Vite进程均已核对命令后停止，18152无监听',rootPort18151:'未操作',otherBrowsersOrServicesStopped:false,log:prefix+'稳定终版浏览器与夹具清理.log'}
report.citationSpaceSearch.status='本地技术检查已完成；真实业务未执行'
report.citationSpaceSearch.screenshots=currentShots
report.citationSpaceSearch.intermediateScreenshots=intermediateShots
report.citationSpaceSearch.intermediateBuild={exitCode:0,outDir:prefix+'最终构建产物',log:prefix+'生产构建.log',duration:'57.12秒',basis:'右栏布局修正前中间构建，不能证明最后CSS'}
report.citationSpaceSearch.priorFailures=['首次测试导入共享router，缺业务API模块替身导致未收集用例；日志保留','两次11通过1失败：mock实现未reset且button替身未声明emits导致重复click；修正夹具后12项通过','右栏第一版图显示新增理由被Element Plus内部flex挤成两栏，已作局部块布局修复并另存补拍图','首session采集日志与停服务未完全串行，停止后的Vite HMR连接失败22条和1弃用警告完整保留；最终新session按采集、关浏览器、停服务顺序复验0运行错误1弃用警告']
report.citationSpaceSearch.componentCoverage=['supported=false/verified=false/SUSPICIOUS反例优先','缺核验字段未核验；supported=true已核验','理由安全文本，无HTML注入；引用点击身份保留','历史回答和右栏同步','关闭/空间变更清空查询与结果','在途abort与迟到旧响应不回写','新检索失败和重置清旧态','me返回真实主空间；PUT成功后回读','me无权限或无资格不冒称尚未设置']
report.citationSpaceSearch.previousScreenshotsImpact={affected:['会话甲引用','会话乙引用'],affectedReason:'本輪新增核验标记改变回答及右栏引用UI，旧甲乙图只作历史，不证明当前引用UI；当前两图已重新操作回读',unaffected:'此前其余16图保留原版本。新增样式只匹配citation-state/citation-reason和reference-button，SearchDialog仅检索独立弹窗reset，Spaces仅本人主空间反馈；旧额度、续传、评测、本人筛选、充值、索引场景不受该三处业务改变影响，未重做旧场景。'}
report.screenshots.forEach(item=>{item.basis=['会话甲引用','会话乙引用'].includes(item.point)?'前版历史图，已受本轮引用UI变更影响，不作为当前UI证明':'此前版本技术基线，本轮未重新执行；与本轮三处改变无关'})
report.followup.screenshots.forEach(item=>{item.basis='前版60项对应的Mock基线，本轮未重新执行；不受引用/检索弹窗/主空间改变影响'})
const changed=['src/views/paismart/chat/components/ChatMessage.vue','src/views/paismart/chat/index.vue','src/views/paismart/documents/components/SearchDialog.vue','src/views/paismart/spaces/index.vue','src/views/paismart/common.css','src/views/paismart/composables/referencePresentation.ts','src/types/paismart/index.ts','tests/unit/paismart/migration-feedback.spec.ts','tests/unit/paismart/browser-preview/mock-api.ts','tests/unit/paismart/browser-preview/main.ts','tests/unit/paismart/browser-preview/Harness.vue','tests/unit/paismart/browser-preview/reference-feedback-browser-check.js','tests/unit/paismart/browser-preview/reference-layout-stable-check.js'].map(file=>'frontend/admin-web/'+file)
report.sourceHashes=Object.fromEntries([...new Set([...Object.keys(report.sourceHashes),...changed])].map(file=>[file,sha(path.join(root,file))]))
report.citationSpaceSearch.changedFiles=changed
const newLogs=fs.readdirSync(dir).filter(file=>file.startsWith(prefix)&&file.endsWith('.log'))
report.logs=[...new Set([...report.logs,...newLogs])]
report.citationSpaceSearch.logs=newLogs
report.reportRendering.previousVersionOnly=true
report.reportRendering.currentHtmlBrowserRepeated=false
report.staticEvidenceCheck={status:'待最后静态核对'}
fs.writeFileSync(path.join(dir,name+'.json'),JSON.stringify(report,null,2)+'\n')
const esc=value=>String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;')
let html=read(name+'.html')
if(html.includes('id="citation-space-search-current"'))throw new Error('HTML已更新，不能重复')
html=html.replace('<h2>本次最终检查</h2>','<h2>此前60项终版技术基线（历史）</h2>').replaceAll('本次Mock技术复验','此前Mock技术基线').replace('最终为60项与部分报告终版构建。','该前版最终为60项与部分报告终版构建，本轮结果另列在报告开头。').replace('<h2>收口核对与清理</h2>','<h2>此前60项收口核对与清理（历史）</h2>')
for(const point of ['会话甲引用','会话乙引用'])html=html.replace('<h2>'+point+'</h2>','<h2>'+point+'（已受变更影响的历史图）</h2>')
const planRows=report.citationSpaceSearch.plan.map(item=>`<tr><td>${esc(item.point)}</td><td>${esc(item.steps)}</td><td>${esc(item.expected)}</td></tr>`).join('')
const articles=currentShots.map(item=>`<article><h2>${esc(item.point)}（本轮Mock技术复验）</h2><p>${esc(item.description)}</p><p><code>${esc(item.url)}</code></p><a href="${esc(item.file)}"><img src="${esc(item.file)}" alt="${esc(item.point)}本地技术截图"></a><p class="muted">实际保存并回读；SHA-256：<code>${item.sha256}</code>。${esc(item.basis)}</p></article>`).join('')
const links=newLogs.map(file=>`<li><a href="${esc(file)}">${esc(file)}</a></li>`).join('')
const current=`<section class="card" id="citation-space-search-current"><h2>本轮最终检查：引用反馈、检索范围和本人主空间</h2><p>最后源码类型检查exit 0；10个文件72项通过0失败，新增12项边界。从最后引用CSS源码构建exit 0，${duration}秒，独立产物<a href="${finalBuild}/index.html">${esc(finalBuild)}</a>，未覆盖共享dist。会话最终稳定session为0运行错误、1条Element Plus分页small弃用警告；所有网络仅127.0.0.1本地静态资源。</p><p>本轮7张当前截图，前版18张完整保留为历史/沿用基线。旧会话甲乙图已受新增核验标记影响，不作为当前引用UI证明；其余16图对应场景与本轮三处改变无关，本轮未重新执行。前版60项及18图原文保存于<a href="2026-10-03-引用主空间检索修复前快照/前版60项技术报告.html">HTML快照</a>与<a href="2026-10-03-引用主空间检索修复前快照/前版60项技术结果.json">JSON快照</a>，<a href="2026-10-03-引用主空间检索修复前快照/快照说明.json">未提交原文与哈希清单</a>。</p><p class="notice">本轮只用明确Mock响应，未执行真实登录、菜单、数据库、模型、OCR、支付或Kafka。31项真实业务仍0执行、0新增通过，用户接受未记录。本HTML增补仅作静态文件核对，前版HTML浏览器呈现不能证明此增补版。</p><h3>操作前已保存的本地技术清单</h3><table><tr><th>中文业务点</th><th>实际步骤</th><th>预期</th></tr>${planRows}</table></section>${articles}<section class="card"><h2>本轮失败保留与清理</h2><p>前两次夹具因模块mock和重复click没有通过，原日志保留；第三次定向12项通过，最终全部72项通过。第一版右栏理由布局不易阅读，原图与57.12秒中间构建保留，当前图已另存。首session控制台采集与停止服务未完全串行，产生22条停止后的HMR连接失败日志；最终新session完整采集console/requests、关闭本人浏览器后再停止Vite，0运行错误、1条原分页弃用警告。两个本人浏览器均已关闭、本人Vite已按命令核对停止，18152无监听，未操作18151与其他项目。</p><ul>${links}</ul><p>中间会话图只作恢复和问题追溯：${intermediateShots.map(item=>`<a href="${item.file}">${esc(item.file)}</a>`).join(' · ')}。</p></section>`
html=html.replace('<section class="card"><h2>此前60项终版技术基线（历史）</h2>',current+'<section class="card"><h2>此前60项终版技术基线（历史）</h2>')
fs.writeFileSync(path.join(dir,name+'.html'),html)
console.log(JSON.stringify({unit:report.unit,outDir:finalBuild,newScreenshots:currentShots.length,previousScreenshots:18,preservedIntermediateScreenshots:intermediateShots.length,sourceHashes:Object.keys(report.sourceHashes).length,browser:report.browser,cleanup:report.cleanup},null,2))
