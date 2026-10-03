// 只核budget/Design差异与既有源码审查carry；不重跑SSR/Runtime/Service/MVC。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../..'),dir='文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/';
const read=p=>fs.readFileSync(path.join(root,p),'utf8'),hash=p=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex');
const oldProofPath=dir+'调试显示历史授权存取修后独立源码审查.json';
assert.equal(hash(oldProofPath),'1009b4924db5f46efbf2c1d9d1fb5b0f2a851dd81ca21564243b22c4087132c2');
const oldProof=JSON.parse(read(oldProofPath)),gatePath=dir+'调试显示历史显示预算修正后源码冻结输入.json';
assert.equal(hash(gatePath),'7e7b2d2350dab717ecc6a08b8db1b67b42bab867121def8bf4609aa06542f44d');
const gate=JSON.parse(read(gatePath)),archiveDir=dir+'调试显示历史重复编码预算修正前原文/';
const archive=JSON.parse(read(archiveDir+'输入.json'));
for(const row of archive.inputs){assert.equal(hash(row.backup),row.sha256);assert.equal(row.sha256,oldProof.sourceHashesAfter[row.path]);}
for(const row of gate.inputs)assert.equal(hash(row.path),row.sha256);
const service='ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowDialogService.java',design='文档/项目/PaiFlow工作流项目/完整迁移设计.md',runLimits='ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunProtocolLimits.java';
const paths=[...new Set([...Object.keys(oldProof.sourceHashesAfter),gatePath,oldProofPath,...archive.inputs.map(r=>r.backup)])];
const before=Object.fromEntries(paths.map(p=>[p,hash(p)]));
function once(value,from,to){assert.equal(value.split(from).length,2,'应为唯一明确局部替换');return value.replace(from,to);}
const archivedService=read(archive.inputs.find(r=>r.path===service).backup),currentService=read(service);
let expected=once(archivedService,
 '    private static final String QUERY="paiflow:workflow:query",EDIT="paiflow:workflow:edit";\n',
 '    private static final String QUERY="paiflow:workflow:query",EDIT="paiflow:workflow:edit";\n    // 原页面重复JSON编码显示项；显示POST不能借用单份Run结果预算。\n    static final int DISPLAY_BYTES=20*1024*1024;\n    static final int DISPLAY_FIELD_BYTES=16*1024*1024-1;\n');
expected=once(expected,'getBytes(StandardCharsets.UTF_8).length>RunProtocolLimits.RESULT_BYTES','getBytes(StandardCharsets.UTF_8).length>DISPLAY_BYTES');
assert.equal(expected.split('RunProtocolLimits.RESULT_BYTES').length-1,4);
expected=expected.replaceAll('RunProtocolLimits.RESULT_BYTES','DISPLAY_FIELD_BYTES');
expected=once(expected,
 '        if(!(raw instanceof String value) || (!nullable && value.isEmpty()) || value.codePointCount(0,value.length())>max)\n            throw bad(name+"须为允许长度的字符串");\n',
 '        if(!(raw instanceof String value) || (!nullable && value.isEmpty()))\n            throw bad(name+"须为允许长度的字符串");\n        if(max==DISPLAY_FIELD_BYTES && value.getBytes(StandardCharsets.UTF_8).length>max)\n            throw new ServiceException("显示记录字段超出允许大小",413);\n        if(value.codePointCount(0,value.length())>max)throw bad(name+"须为允许长度的字符串");\n');
assert.equal(currentService,expected,'Service除了明确budget替换外不能有其他差异');
const archivedDesign=read(archive.inputs.find(r=>r.path===design).backup),currentDesign=read(design);
const oldSentence='保存前校验实际MessageAsk/history消费者所需JSON形状、Unicode和现有2MiB结果正文预算，错误不吞为空成功。';
const newSentence='保存前校验实际MessageAsk/history消费者所需JSON形状、Unicode，以及独立20MiB显示请求预算和每字段16MiB减1字节的MEDIUMTEXT边界。原页面会重复JSON编码问题/答案显示项，该预算须容纳中心已支持的1MiB输入、2MiB结果及显示副本，不能把Run结果预算直接当作显示POST预算；现有Run预算不变，错误不吞为空成功。';
assert.equal(currentDesign,once(archivedDesign,oldSentence,newSentence),'Design仅原显示预算一句局部补充');
assert.equal(hash(runLimits),oldProof.sourceHashesAfter[runLimits]);
const limits=read(runLimits);for(const fragment of ['INPUT_BYTES=1024*1024','RESULT_BYTES=2*1024*1024','CONTROL_BYTES=16*1024'])assert.ok(limits.includes(fragment));
const changed=Object.keys(oldProof.sourceHashesAfter).filter(p=>before[p]!==oldProof.sourceHashesAfter[p]);
assert.deepEqual(changed.sort(),[service,design].sort());
assert.equal(oldProof.facts.length,16,'此前应按实际facts数组16条，不是旧注释15');
const after=Object.fromEntries(paths.map(p=>[p,hash(p)]));assert.deepEqual(after,before);
const report={checkedAt:new Date().toISOString(),scope:'显示重复编码预算的独立只读delta核对；不是实际Service/HTTP/预算用例',gate:{path:gatePath,sha256:hash(gatePath),inputs:gate.inputs.length},sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],changedFromPreviousReview:changed,
 exactDelta:{service:{path:service,beforeSha256:archive.inputs.find(r=>r.path===service).sha256,afterSha256:hash(service),onlyWhitelistedBudgetChanges:true},design:{path:design,beforeSha256:archive.inputs.find(r=>r.path===design).sha256,afterSha256:hash(design),onlySpecifiedBudgetSentenceChanged:true}},
 budgets:{displayBodyUTF8Bytes:20*1024*1024,displayFieldUTF8Bytes:16*1024*1024-1,fields:['question','answer','questionItem','answerItem','data'],fieldOverLimitStatus:413,sidCodePoints:128,chatIdCodePoints:100,run:{inputUTF8Bytes:1024*1024,resultUTF8Bytes:2*1024*1024,controlUTF8Bytes:16*1024,sourcePath:runLimits,sha256:hash(runLimits),unchanged:true}},
 carry:{previousProof:{path:oldProofPath,sha256:hash(oldProofPath),sourceFactCount:oldProof.facts.length},allOtherSourceHashesUnchanged:true,priorSemanticReviewStillApplicable:'权限、主体/域/固定WF版本/真实Agent/Run绑定、每POST追加、个人list/clear、nullable/raw字符串/Unicode/深度/原React形状guard与无Run/CP结果副作用均未改，可沿用1009b源码结论',replacedFact:'limits-unicode-json中的整体2MiB正文预算更新为独立20MiB及各显示字段16MiB减1 UTF8；该fact的Unicode/depth/trailing要求未变',countClarification:'此前facts数组实际16项源码事实；旧limitations手写15不作统计，不是业务用例或通过数'},
 limitations:['只比对actual source/完整备份字节与既有冻结hash，不调用候选Service/Jackson/H2/MVC','允许大显示正文、超20MiB或单字段16MiB拒绝413及Run2MiB仍拒的实际证明待root统一检查','不重跑SSR/Runtime271/Front/完整源码审，不修改任何生产源/contracts/tests/target；019未执行/正式App或Runtime记忆未接'],productionModified:false,businessVerified:false};
const out=path.join(__dirname,'调试显示历史显示预算独立差异补记.json');assert.equal(fs.existsSync(out),false);fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({sourceWindowFiles:paths.length,sourceWindowChanges:0,changedProductionAndDesign:changed.length,carriedSourceFacts:16,proofSha256:crypto.createHash('sha256').update(fs.readFileSync(out)).digest('hex')}));
