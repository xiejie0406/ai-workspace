// 最终静态文件核对；不启服务、不请求业务环境。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
const root='D:/2025Ai/26-05-23/java-workspace',dir=path.join(root,'文档/项目/PaiSmart知识库项目/设计附录'),prefix='2026-10-03-菜单筛选评测入口-',name='2026-10-03-迁移补齐-前端技术复验'
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const report=JSON.parse(fs.readFileSync(path.join(dir,name+'.json'),'utf8'))
const cleanupLog=prefix+'浏览器与夹具清理.log'
if(!fs.readFileSync(path.join(dir,cleanupLog),'utf8').includes('18152无监听'))throw new Error('清理未完成')
report.cleanup={baseline:report.baseline.cleanup,browserSession:'paismart-menu-eval 已关闭',fixturePort18152:'已停止本人Vite进程并确认无监听',rootPort18151:'未操作',otherBrowsersOrServicesStopped:false,log:cleanupLog}
report.logs=[...new Set([...report.logs,cleanupLog])]
if(!report.followup.plan.some(item=>item.point==='取消后的部分报告'))report.followup.plan.push({point:'取消后的部分报告',steps:'读取本地CANCELED部分报告',expected:'保留已存案例，合法完成与待处理数提示；无可计算指标保持空',capture:'部分提示与案例整页'})
const screenshots=[...report.screenshots,...report.followup.screenshots]
const screenshotErrors=screenshots.filter(item=>!fs.existsSync(path.join(dir,item.file))||sha(path.join(dir,item.file))!==item.sha256)
const sourceErrors=Object.entries(report.sourceHashes).filter(([file,hash])=>!fs.existsSync(path.join(root,file))||sha(path.join(root,file))!==hash)
let html=fs.readFileSync(path.join(dir,name+'.html'),'utf8')
if(!html.includes('id="final-cleanup"')){html=html.replace('</main></html>',`<section class="card" id="final-cleanup"><h2>收口核对与清理</h2><p>本人paismart-menu-eval浏览器已关闭，18152夹具Vite已停止并确认无监听；未操作主任务18151或其他浏览器、项目服务。18张截图（7张沿用、11张新增）和15份业务源码哈希均已核对，最终构建入口与3个引用资源存在；当前增补HTML只做静态核对，不冒称新报告浏览器呈现。</p><p><a href="${cleanupLog}">实际清理记录</a> · <a href="${prefix}最终证据静态核对.log">最终链接、哈希和构建静态核对</a></p></section></main></html>`);fs.writeFileSync(path.join(dir,name+'.html'),html)}
const references=[...html.matchAll(/(?:href|src)="([^"]+)"/g)].map(match=>match[1]).filter(reference=>!reference.startsWith('data:')&&!reference.startsWith('http')&&!reference.startsWith('#'))
const linkErrors=references.filter(reference=>!fs.existsSync(path.resolve(dir,decodeURIComponent(reference.split('#')[0].split('?')[0]))))
const out=path.join(dir,report.productionBuild.outDir),entry=path.join(out,'index.html'),buildHtml=fs.readFileSync(entry,'utf8')
const assets=[...buildHtml.matchAll(/(?:href|src)="([^"]+)"/g)].map(match=>match[1]).filter(reference=>!reference.startsWith('data:')&&!reference.startsWith('http'))
const assetErrors=assets.filter(asset=>!fs.existsSync(path.join(out,asset.replace(/^\//,'').split('?')[0])))
const scripts=fs.readdirSync(path.join(out,'static/js')).filter(file=>file.endsWith('.js'))
const partialBundles=scripts.filter(file=>fs.readFileSync(path.join(out,'static/js',file),'utf8').includes('当前为部分结果'))
const indexHash=sha(entry)
const check={kind:'最终静态文件与哈希核对',unit:{files:9,passed:60,failed:0},typecheckExitCode:report.typecheck.exitCode,buildExitCode:report.productionBuild.exitCode,buildOutDir:report.productionBuild.outDir,indexSha256:indexHash,htmlReferences:references.length,linkErrors,screenshots:screenshots.length,newScreenshots:report.followup.screenshots.length,screenshotHashErrors:screenshotErrors,sourceHashes:Object.keys(report.sourceHashes).length,sourceHashErrors:sourceErrors,buildEntryAssets:assets.length,assetErrors,partialBundles,cleanup:report.cleanup,businessAcceptanceExecuted:false,businessAcceptanceCountAdded:0}
if(linkErrors.length||screenshotErrors.length||sourceErrors.length||assetErrors.length||!partialBundles.length)throw new Error(JSON.stringify(check,null,2))
report.productionBuild.indexSha256=indexHash
report.staticEvidenceCheck={log:prefix+'最终证据静态核对.log',htmlReferences:references.length,screenshots:screenshots.length,sourceHashes:Object.keys(report.sourceHashes).length,allLinksExist:true,allHashesMatch:true,buildEntryAssets:assets.length,allBuildEntryAssetsExist:true,partialBundles,htmlBrowserRenderingRepeated:false}
fs.writeFileSync(path.join(dir,name+'.json'),JSON.stringify(report,null,2)+'\n')
fs.writeFileSync(path.join(dir,prefix+'最终证据静态核对.log'),JSON.stringify(check,null,2)+'\n')
console.log(JSON.stringify({links:references.length,screenshots:screenshots.length,sources:Object.keys(report.sourceHashes).length,assets:assets.length,indexSha256:indexHash,htmlSha256:sha(path.join(dir,name+'.html')),jsonSha256:sha(path.join(dir,name+'.json')),cleanup:report.cleanup},null,2))
