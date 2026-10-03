// 更新既有技术证据入口；仅本地文件，不调用业务服务。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
const root='D:/2025Ai/26-05-23/java-workspace'
const dir=path.join(root,'文档/项目/PaiSmart知识库项目/设计附录')
const base='2026-10-03-迁移补齐-前端技术复验'
const prefix='2026-10-03-菜单筛选评测入口-'
const read=name=>fs.readFileSync(path.join(dir,name),'utf8')
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const result=JSON.parse(read(base+'.json'))
const oldHtml=read(base+'.html')
const snapshot=path.join(dir,'2026-10-03-菜单导航与筛选修复前快照')
for(const [extension,text] of [['json',read(base+'.json')],['html',oldHtml]]){
 const saved=path.join(snapshot,'复验收口前技术报告原文.'+extension)
 if(!fs.existsSync(saved))fs.writeFileSync(saved,text)
}
result.baseline={typecheck:result.typecheck,unit:result.unit,productionBuild:result.productionBuild,browser:result.browser,reportRendering:result.reportRendering,cleanup:result.cleanup,impact:'此前33项与7张截图沿用，未作为本轮新执行；本次仅修改文档筛选、充值状态、评测入口和部分报告条件提示，原配额/续传/引用/索引及旧完整报告截图对应组件未受影响。'}
result.intermediate=[
 {passed:56,files:9,log:prefix+'前端组件测试.log',buildLog:prefix+'前端构建.log',outDir:prefix+'构建产物',exitCode:0,note:'上传短标签、CANCELED和部分报告提示补齐前的中间结果，不能证明最终源码。'},
 {passed:59,files:9,log:prefix+'终版前端组件测试.log',buildLog:prefix+'终版前端构建.log',outDir:prefix+'最终构建产物',exitCode:0,note:'短标签和CANCELED已补，部分报告提示补齐前的中间结果，不能证明最终源码。'}
]
const typeLog=prefix+'部分报告终版前端类型检查.log',unitLog=prefix+'部分报告终版前端组件测试.log',buildLog=prefix+'部分报告终版前端构建.log'
for(const name of [typeLog,unitLog])if(!read(name).includes('ExitCode=0'))throw new Error(name+'未通过')
if(!read(unitLog).includes('60 passed (60)'))throw new Error('最终测试计数不符')
const buildMatch=read(buildLog).match(/BuildExitCode=0 DurationMilliseconds=(\d+)/)
if(!buildMatch)throw new Error('终版构建未成功')
const outDir=prefix+'部分报告终版构建产物'
result.typecheck={exitCode:0,command:'npm.cmd run typecheck:paismart',log:typeLog}
result.unit={exitCode:0,files:9,passed:60,failed:0,command:'npm.cmd run test:unit:paismart',log:unitLog}
result.productionBuild={exitCode:0,durationMilliseconds:Number(buildMatch[1]),duration:'142.184秒',command:'npm.cmd run build:prod -- --outDir '+path.join(dir,outDir).replaceAll('\\','/'),outDir,log:buildLog,isolatedOutDir:true,sharedDistOverwritten:false}
const requests=read(prefix+'收口终版浏览器网络.log')
const urls=[...requests.matchAll(/\[GET\]\s+(https?:\/\/\S+)/g)].map(match=>match[1])
if(urls.some(url=>new URL(url).origin!=='http://127.0.0.1:18152'))throw new Error('发现非本地请求')
result.browser={session:'paismart-menu-eval',url:'http://127.0.0.1:18152',host:'127.0.0.1',proxy:false,viewport:[1280,1200],externalNetworkObserved:false,staticRequests:urls.length,errors:0,warnings:0,scope:'本地Mock组件与实际菜单路径静态兼容；没有读取真实getRouters或登录会话。',networkLog:prefix+'收口终版浏览器网络.log',consoleLog:prefix+'收口终版浏览器控制台.log'}
const parsedResult=name=>{const match=read(name).match(/### Result\s*\n(\{[^\n]+\})/);if(!match)throw new Error('浏览器结果缺失：'+name);return JSON.parse(match[1])}
const finalResult=parsedResult(prefix+'稳定回读浏览器.log')
const earlierResult=parsedResult(prefix+'稳定充值浏览器.log')
const points=[...earlierResult.results.filter(item=>['支付失败终态','待核对保护'].includes(item.name)),...finalResult.results]
const descriptions={
 '支付失败终态':'先恢复REVOKED原单，随后筛选PAYERROR并选择该原单；筛选调用实际state=PAYERROR，扫码地址撤下，选择套餐按钮可用。没有创建真实订单。',
 '待核对保护':'筛选UNCERTAIN并选择原单，停止自动查单并保留交易号，选择套餐按钮禁用；没有重复预支付。',
 '本人上传稳定页面':'勾选仅本人上传后请求mine=true、pageNum=1；本地表格仅剩本人文档。已等待加载层消失，修正仅属于测试夹具的全局main样式。',
 '非法seed拒绝':'在内联JSONL中输入1.5种子，提交被拒绝且没有createEvaluation调用；允许范围为安全整数。',
 '内联评测提交':'修改种子43提交，实际Mock调用包含inlineJsonl和seed，未传datasetId；关闭作业弹窗并等待动画结束后截图。',
 '文件保存短标签':'本地.jsonl保留原文件名，以已填评测名称作为短上传标签，显示服务返回的id、样本数与字节数；不会把合法长文件名直接作为超限label。超过160字符文件名由组件测试单独覆盖。',
 '文件datasetId提交':'上传保存后仅用服务返回fixture-upload-dataset和seed=44创建评测，未复制inline正文；调用记录为本地Mock。',
 '受控资源seed':'从实际Mock GET返回的列表选择受控资源并输入seed=42，不在页面捏造资源id；显示登记来源和样本数。',
 '受控资源提交':'实际Mock提交使用列表返回builtin:fixture-resource与seed=42，没有path/url/objectKey输入。',
 '报告菜单跳转':'点击报告与复核后实际浏览器URL为/paismart/review?evaluationId=fixture-evaluation，与当前菜单SQL path一致；未执行真实菜单写入或登录联机。',
 '取消后的部分报告':'CANCELED、冻结总数3、完成1、待处理2均可见；已存案例保留，未可计算指标显示无可计算样本，没有以待处理当0%或失败。完整旧报告无这两个计数字段时不额外提示。'
}
const newScreenshots=points.map(item=>({point:item.name,file:path.basename(item.file),description:descriptions[item.name],readBack:true,sha256:sha(item.file),url:item.url,fixtureCalls:item.calls}))
if(newScreenshots.length!==11)throw new Error('截图计数不符')
result.screenshots=result.screenshots.map(item=>({...item,basis:'沿用此前已回读基线，本轮未重新执行'}))
result.followup={...result.followup,state:'技术Mock定向复验完成',realBusinessExecuted:false,businessAcceptanceCountAdded:0,screenshots:newScreenshots,logs:[prefix+'稳定回读浏览器.log',prefix+'稳定充值浏览器.log',prefix+'终版补齐浏览器.log',prefix+'浏览器最终页面.log'],additionalPoints:['上传短label及输入改变后的幂等key','CANCELED筛选参数','取消后的部分结果'],oldFaultEvidence:'2026-10-03-菜单导航与筛选修复前快照/快照说明.json',menuCompatibility:'menu-consumer-compatibility.spec.ts静态读取真实菜单SQL，对业务push与组件path核对；不计真实menu/getRouters联机通过。'}
result.priorFailure.followup='首轮夹具缺少API别名、隐藏控件/required标签定位不符，及个别截图处于动画/加载中；失败日志、原图均保留。随后使用实际可见控件与等待状态补拍稳定图；network旧命令被当前CLI改为requests，旧help输出保留。'
const sourceFiles=['src/views/paismart/documents/index.vue','src/views/paismart/recharge/index.vue','src/views/paismart/governance/evaluations/index.vue','src/views/paismart/governance/evaluations/review.vue','src/api/paismart/governance.ts','src/types/paismart/index.ts','src/views/paismart/composables/evaluationDataset.ts','tests/unit/paismart/menu-consumer-compatibility.spec.ts','tests/unit/paismart/recharge-state.spec.ts','tests/unit/paismart/evaluation-dataset-api.spec.ts','tests/unit/paismart/migration-ui.spec.ts']
for(const source of sourceFiles){const relative='frontend/admin-web/'+source;result.sourceHashes[relative]=sha(path.join(root,relative))}
result.logs=[...new Set([...result.logs,typeLog,unitLog,buildLog,result.browser.networkLog,result.browser.consoleLog,...result.followup.logs,...result.intermediate.flatMap(item=>[item.log,item.buildLog]),prefix+'新增测试夹具失败记录.log',prefix+'浏览器首轮夹具失败.log',prefix+'浏览器控件复验.log'])]
result.reportRendering={...result.reportRendering,basis:'此前7图HTML呈现的历史基线；当前增补HTML完成静态链接和hash核对，没有再次启动报告服务或声称最终HTML已重新浏览器渲染。'}
result.cleanup={baseline:result.baseline.cleanup,browserSession:'paismart-menu-eval 待关闭',fixturePort18152:'待停止并确认',rootPort18151:'未操作',otherBrowsersOrServicesStopped:false}
fs.writeFileSync(path.join(dir,base+'.json'),JSON.stringify(result,null,2)+'\n')
const escape=text=>String(text).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;')
const links=names=>'<ul>'+names.map(name=>`<li><a href="${escape(name)}">${escape(name)}</a></li>`).join('')+'</ul>'
const latest=`<section class="card"><h2>本次最终检查</h2><p>最后源码包含短上传标签、CANCELED筛选和取消后的部分报告提示。以下结果均从最后源码重新执行，此前56项、59项及其构建作为中间证据保留。</p><table><tr><th>检查</th><th>实际结果</th><th>范围</th></tr><tr><td>类型检查</td><td>exit 0</td><td>PaiSmart Vue/TypeScript</td></tr><tr><td>组件与边界测试</td><td>9个文件，60项通过，0项失败</td><td>菜单静态兼容、mine传参、支付终态/待核对、受控三来源/seed/大小/输入幂等、长文件名短label、CANCELED筛选、部分报告</td></tr><tr><td>最终隔离构建</td><td>exit 0，142.184秒</td><td><a href="${escape(outDir)}/index.html">最终独立构建index.html</a>；未覆盖共享dist或旧包，未发布</td></tr><tr><td>本地Mock浏览器</td><td>11张新增稳定截图已逐张回读；0运行错误、0警告</td><td>仅127.0.0.1:18152；菜单兼容为静态SQL与Mock路由检查，不是真实登录菜单联机。原7图按影响判断沿用。</td></tr></table>${links([typeLog,unitLog,buildLog,result.browser.networkLog,result.browser.consoleLog,prefix+'稳定回读浏览器.log'])}<p>保留原错配：<a href="2026-10-03-菜单导航与筛选修复前快照/快照说明.json">修复前源路径与哈希</a>；<a href="2026-10-03-菜单导航与筛选修复前快照/评测入口补齐源路径.json">来源入口修改前原文索引</a>。后续夹具定位失败和动画中截图亦保留，并未记为成功。</p><p class="notice">真实31项业务验收仍未执行，新增通过数0；无真实数据库、登录、模型、支付、Kafka或菜单写入。用户接受未记录。</p></section>`
const articles=newScreenshots.map(item=>`<article><h2>${escape(item.point)}（本次Mock技术复验）</h2><p>${escape(item.description)}</p><p><code>${escape(item.url)}</code></p><a href="${escape(item.file)}"><img src="${escape(item.file)}" alt="${escape(item.point)}技术Mock截图"></a><p class="muted">实际截图保存并回读；SHA-256：<code>${item.sha256}</code>。详细调用记录见JSON。</p></article>`).join('')
let html=oldHtml.replace('<h2>最终检查</h2>','<h2>此前33项技术基线（沿用）</h2>').replace('<section class="card"><h2>此前33项',latest+articles+'<section class="card"><h2>此前33项').replace('未提交聊天、充值、模型/OCR测试、人工复核、索引切换或运维执行。','未执行真实聊天、充值、模型/OCR测试、人工复核、索引切换或运维执行；新增本地Mock评测提交与业务写入分开。')
html=html.replace('</main></html>',`<section class="card"><h2>中间结果与保留记录</h2><p>56项和59项结果分别属于部分修改完成时的中间检查，不能证明本次最后源码。最终为60项与部分报告终版构建。旧7图和旧HTML呈现截图沿用原证据，当前HTML增补仅做静态链接核对，没有新开报告服务。</p>${links(result.intermediate.flatMap(item=>[item.log,item.buildLog]))}<p>${escape(result.priorFailure.followup)}</p>${links([prefix+'新增测试夹具失败记录.log',prefix+'浏览器首轮夹具失败.log',prefix+'浏览器控件复验.log'])}</section></main></html>`)
fs.writeFileSync(path.join(dir,base+'.html'),html)
console.log(JSON.stringify({unit:result.unit,build:result.productionBuild,baselineScreenshots:7,newScreenshots:newScreenshots.length,localRequests:urls.length},null,2))
