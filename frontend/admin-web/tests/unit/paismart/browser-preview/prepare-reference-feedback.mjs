// 保留前版技术证据，操作前把本轮本地Mock计划写入既有结果入口。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
const dir='D:/2025Ai/26-05-23/java-workspace/文档/项目/PaiSmart知识库项目/设计附录'
const filename=path.join(dir,'2026-10-03-迁移补齐-前端技术复验.json')
const report=JSON.parse(fs.readFileSync(filename,'utf8'))
if(report.citationSpaceSearch)throw new Error('本轮计划已保存，不能覆盖')
report.citationSpaceSearch={kind:'本地Mock组件技术复验',businessAcceptanceExecuted:false,plan:[
 {point:'回答、历史和右栏核验反馈',precondition:'本地Mock会话引用包含supported=false、缺失核验字段及verified=true',steps:'点击会话甲，核对回答引用和右侧列表，再切换会话乙',expected:'两处可疑引用都显示待核实及理由；未知显示未核验；旧会话引用移除',capture:'会话甲和会话乙核验标记'},
 {point:'本人主空间',precondition:'本地me返回primarySpaceId=fixture-space',steps:'进入知识空间；将空间乙设为主空间',expected:'先显示空间甲，再PUT后GET me回读空间乙；无真实业务写入',capture:'主空间初始值与保存后回读'},
 {point:'跨空间检索和失败清空',precondition:'本地甲、乙空间列表；失败关键词产生明确测试错误',steps:'甲检索；关闭改乙；重开核对空态；乙检索；输入失败再检索',expected:'切换空间查询和旧结果清空；请求使用乙id；新失败不留旧结果',capture:'甲结果、乙重开空态及乙失败状态'}
],status:'待本地浏览器操作',priorVersion:{unit:report.unit,productionBuild:report.productionBuild,browser:report.browser,cleanup:report.cleanup,staticEvidenceCheck:report.staticEvidenceCheck,sourceHashes:report.sourceHashes,snapshot:'2026-10-03-引用主空间检索修复前快照/前版60项技术结果.json',htmlSnapshot:'2026-10-03-引用主空间检索修复前快照/前版60项技术报告.html',screenshots:18}}
fs.writeFileSync(filename,JSON.stringify(report,null,2)+'\n')
const manifestFile=path.join(dir,'2026-10-03-引用主空间检索修复前快照/快照说明.json')
const manifest=JSON.parse(fs.readFileSync(manifestFile,'utf8'))
const snapshot='前版业务样式.css',snapshotPath=path.join(path.dirname(manifestFile),snapshot)
manifest.sources.push({snapshot,sha256:crypto.createHash('sha256').update(fs.readFileSync(snapshotPath)).digest('hex'),source:'D:/2025Ai/26-05-23/java-workspace/frontend/admin-web/src/views/paismart/common.css'})
fs.writeFileSync(manifestFile,JSON.stringify(manifest,null,2)+'\n')
console.log('本轮3项Mock操作前计划已保存，前版60项与18图原文保留')
