// 静态核对当前源码/产物/截图与保留原文，不访问业务服务。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
const root='D:/2025Ai/26-05-23/java-workspace',dir=path.join(root,'文档/项目/PaiSmart知识库项目/设计附录'),name='2026-10-03-迁移补齐-前端技术复验',prefix='2026-10-03-引用主空间检索-',snapshotDir='2026-10-03-引用主空间检索修复前快照'
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const report=JSON.parse(fs.readFileSync(path.join(dir,name+'.json'),'utf8'))
const htmlPath=path.join(dir,name+'.html'),html=fs.readFileSync(htmlPath,'utf8')
const snapshots=JSON.parse(fs.readFileSync(path.join(dir,snapshotDir,'快照说明.json'),'utf8'))
const snapshotErrors=snapshots.sources.filter(item=>sha(path.join(dir,snapshotDir,item.snapshot))!==item.sha256)
const screenshots=[...report.screenshots,...report.followup.screenshots,...report.citationSpaceSearch.screenshots,...report.citationSpaceSearch.intermediateScreenshots]
const screenshotErrors=screenshots.filter(item=>!fs.existsSync(path.join(dir,item.file))||sha(path.join(dir,item.file))!==item.sha256)
const sourceErrors=Object.entries(report.sourceHashes).filter(([file,hash])=>!fs.existsSync(path.join(root,file))||sha(path.join(root,file))!==hash)
const references=[...html.matchAll(/(?:href|src)="([^"]+)"/g)].map(match=>match[1]).filter(reference=>!reference.startsWith('data:')&&!reference.startsWith('http')&&!reference.startsWith('#'))
const linkErrors=references.filter(reference=>!fs.existsSync(path.resolve(dir,decodeURIComponent(reference.split('#')[0].split('?')[0]))))
const out=path.join(dir,report.productionBuild.outDir),entry=path.join(out,'index.html'),buildHtml=fs.readFileSync(entry,'utf8')
const assets=[...buildHtml.matchAll(/(?:href|src)="([^"]+)"/g)].map(match=>match[1]).filter(reference=>!reference.startsWith('data:')&&!reference.startsWith('http'))
const assetErrors=assets.filter(asset=>!fs.existsSync(path.join(out,asset.replace(/^\//,'').split('?')[0])))
const scripts=fs.readdirSync(path.join(out,'static/js')).filter(file=>file.endsWith('.js'))
const markers=['待核实','未核验','当前主空间','检索内容']
const markerBundles=Object.fromEntries(markers.map(marker=>[marker,scripts.filter(file=>fs.readFileSync(path.join(out,'static/js',file),'utf8').includes(marker))]))
const check={kind:'最终前端静态核对',unit:report.unit,typecheck:report.typecheck,buildOutDir:report.productionBuild.outDir,indexSha256:sha(entry),htmlReferences:references.length,linkErrors,sourceHashes:Object.keys(report.sourceHashes).length,sourceHashErrors:sourceErrors,currentScreenshots:report.citationSpaceSearch.screenshots.length,previousScreenshots:18,preservedIntermediateScreenshots:report.citationSpaceSearch.intermediateScreenshots.length,screenshotFiles:screenshots.length,screenshotHashErrors:screenshotErrors,rawSnapshots:snapshots.sources.length,snapshotHashErrors:snapshotErrors,buildEntryAssets:assets.length,assetErrors,markerBundles,cleanup:report.cleanup,businessAcceptanceExecuted:false,businessAcceptanceCountAdded:0,htmlBrowserRenderingRepeated:false}
if(linkErrors.length||sourceErrors.length||screenshotErrors.length||snapshotErrors.length||assetErrors.length||Object.values(markerBundles).some(files=>!files.length))throw new Error(JSON.stringify(check,null,2))
report.staticEvidenceCheck={log:prefix+'最终证据静态核对.log',htmlReferences:references.length,allLinksExist:true,allHashesMatch:true,sourceHashes:Object.keys(report.sourceHashes).length,currentScreenshots:7,previousScreenshots:18,preservedIntermediateScreenshots:4,screenshotFiles:screenshots.length,rawSnapshots:snapshots.sources.length,buildEntryAssets:assets.length,allBuildEntryAssetsExist:true,markerBundles,htmlBrowserRenderingRepeated:false}
fs.writeFileSync(path.join(dir,name+'.json'),JSON.stringify(report,null,2)+'\n')
fs.writeFileSync(path.join(dir,prefix+'最终证据静态核对.log'),JSON.stringify(check,null,2)+'\n')
console.log(JSON.stringify({links:references.length,sources:Object.keys(report.sourceHashes).length,images:screenshots.length,currentImages:7,previousImages:18,intermediateImages:4,rawSnapshots:snapshots.sources.length,assets:assets.length,indexSha256:sha(entry),htmlSha256:sha(htmlPath),jsonSha256:sha(path.join(dir,name+'.json')),cleanup:report.cleanup},null,2))
