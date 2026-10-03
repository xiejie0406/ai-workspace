// 独立只读当前transport与原consumer AST；所有页面、请求和导航仅显式spy。
const fs=require('fs'),path=require('path'),vm=require('vm'),crypto=require('crypto');
const root=path.resolve(__dirname,'../../../../../..');
const ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
const files=['frontend/paiflow-web/src/utils/workflow-revision.ts','frontend/paiflow-web/src/utils/http.ts','frontend/paiflow-web/tests/workflow-revision.test.cjs','frontend/paiflow-web/src/pages/space-page/agent-page/index.tsx','frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts'];
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const hashes=()=>Object.fromEntries(files.map(p=>[p,sha(path.join(root,p))]));
const before=hashes(),checks=[];
function check(name,actual,expected){checks.push({name,actual,expected,passed:JSON.stringify(actual)===JSON.stringify(expected)});}
const exportsObject={};
const code=ts.transpileModule(fs.readFileSync(path.join(root,files[0]),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
vm.runInNewContext(code,{exports:exportsObject,URL,URLSearchParams},{timeout:1000});
const centre='/paiflow/legacy',origin='https://fixture.example';
function cache(limit=128,scope='5'){const c=new exportsObject.WorkflowRevisionTransport(limit);c.setScope('actor',scope);return c;}
function prepare(c,url,data,extra={}){const config={method:'post',url,data,headers:{},...extra};return {config,ticket:c.prepare(config,centre,origin)};}
function load(c,id='9',rev='2',workflowId='9'){
 const r=prepare(c,'/my-bot/bot-detail?botId='+id);
 const body={id,botId:id,revision:rev,spaceId:5,maasId:workflowId,workflowId,workflowRevision:'4',flowId:'flow-'+workflowId};
 check('load exact Agent '+id,c.accept(r.ticket,{code:0,data:body},{etag:'"'+rev+'"','x-workflow-revision':'"4"'}),true);
}
function sync(c,id='9',extra={}){return prepare(c,'/workflow/bot/get-inputs-type',{botId:id},extra);}
function reply(c,r,rev='3',id='9',data=[]){return c.accept(r.ticket,{code:0,data},{etag:'"'+rev+'"','x-paiflow-agent-id':id});}
function write(c,id='9',data={}){return prepare(c,'/bot/update',{botId:id,...data});}
function wf(c,id='9'){return prepare(c,'/workflow',{id},{method:'put'});}
// 同整数ID的三类资源：sync只能失效Agent命名空间。
{
 const c=cache();load(c);
 const wr=prepare(c,'/workflow?id=9',undefined,{method:'get'});
 check('same-id Workflow load',c.accept(wr.ticket,{code:0,data:{id:'9',flowId:'flow-9',revision:'8',spaceId:5}},{etag:'"8"'}),true);
 const mr=prepare(c,'/api/model/detail?modelId=9',undefined,{method:'get'});
 check('same-id Model load',c.accept(mr.ticket,{code:0,data:{id:'9',modelId:'9',revision:'12',llmSource:0,spaceId:5}},{etag:'"paiflow-model-9-12"'}),true);
 check('same-id valid sync',reply(c,sync(c)),true);
 check('same-id no Agent condition',write(c).config.headers['If-Match'],undefined);
 check('same-id Workflow unchanged',wf(c).config.headers['If-Match'],'"8"');
 check('same-id Model unchanged',prepare(c,'/api/model',{id:'9'}).config.headers['If-Match'],'"paiflow-model-9-12"');
}
// 同步后原并发write回执不可恢复旧窗口条件；明确旧body条件保留，但非自动播种。
{
 const c=cache();load(c);const pending=write(c),s=sync(c);
 check('clear via sync',reply(c,s),true);
 check('late own Agent write denied',c.accept(pending.ticket,{code:0,data:{id:'9',botId:'9',revision:'3',spaceId:5,workflowId:'9',maasId:'9',workflowRevision:'5',flowId:'flow-9'}},{etag:'"3"','x-workflow-revision':'"5"'}),false);
 check('late own write cannot unlock',write(c).config.headers['If-Match'],undefined);
 const explicit=write(c,'9',{revision:'2',workflowRevision:'4'});
 check('body original revision retained',explicit.ticket.expectedRevision,'2');
 check('body original WF revision retained',explicit.ticket.expectedWorkflowRevision,'4');
 check('body supplied no auto If-Match',explicit.config.headers['If-Match'],undefined);
}
// 两个不同Agent和长期pending-read，失效只定位其资源；epoch往返也不重用旧ticket。
{
 const c=cache();load(c,'9');load(c,'10','2','10');
 const own=prepare(c,'/my-bot/bot-detail?botId=9'),other=prepare(c,'/my-bot/bot-detail?botId=10');
 check('owner sync',reply(c,sync(c)),true);
 const data=id=>({id,botId:id,revision:'2',spaceId:5,workflowId:id,maasId:id,workflowRevision:'4',flowId:'flow-'+id});
 check('same Agent old detail denied',c.accept(own.ticket,{code:0,data:data('9')},{etag:'"2"','x-workflow-revision':'"4"'}),false);
 check('other Agent pending detail survives',c.accept(other.ticket,{code:0,data:data('10')},{etag:'"2"','x-workflow-revision':'"4"'}),true);
 const s=sync(c,'10');c.setScope('actor','6');c.setScope('actor','5');load(c,'10','2','10');
 check('scope roundtrip old sync denied',reply(c,s,'99','10'),false);
 check('scope roundtrip current detail retained',write(c,'10').config.headers['If-Match'],'"2"');
}
// Long边界以及malformed回执无法清除其他或当前已读条件。
{
 const id='9223372036854775807',c=cache();load(c,id,'9223372036854775806');
 const s=sync(c,id);
 check('LongMAX receipt accepted',reply(c,s,'9223372036854775807',id),true);
 check('LongMAX no fabricated next revision',write(c,id).config.headers['If-Match'],undefined);
 const bad=cache();load(bad);
 for(const [name,h] of [
  ['revision overflow',{etag:'"9223372036854775808"','x-paiflow-agent-id':'9'}],
  ['id overflow',{etag:'"3"','x-paiflow-agent-id':'9223372036854775808'}],
  ['id unsafe numeric',{etag:'"3"','x-paiflow-agent-id':9007199254740992}],
  ['weak revision',{etag:'W/"3"','x-paiflow-agent-id':'9'}],
  ['multivalue identity',{etag:'"3"','x-paiflow-agent-id':['9','10']}],
  ['negative revision',{etag:'"-1"','x-paiflow-agent-id':'9'}]]) {
  check(name,bad.accept(sync(bad).ticket,{code:0,data:[]},h),false);
  check(name+' cached version retained',write(bad).config.headers['If-Match'],'"2"');
 }
}
// 原AgentPage actual then箭头。只运行数组判定；navigation/message为spy。
const consumerPath=path.join(root,files[3]),consumerText=fs.readFileSync(consumerPath,'utf8');
const ast=ts.createSourceFile(consumerPath,consumerText,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
let callback;
function visit(node){
 if(ts.isCallExpression(node)&&ts.isPropertyAccessExpression(node.expression)&&node.expression.name.text==='then'
  &&ts.isCallExpression(node.expression.expression)&&node.expression.expression.expression.getText(ast)==='getInputsType'
  &&node.arguments[0]&&ts.isArrowFunction(node.arguments[0]))callback=node.arguments[0].getText(ast);
 ts.forEachChild(node,visit);
}
visit(ast);if(!callback)throw Error('原AgentPage输入类型消费者缺失');
const callbackCode=ts.transpileModule('module.exports='+callback,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
const system={id:'fixed',name:'AGENT_USER_INPUT',schema:{type:'string'},fileType:'string',required:true};
const arrayFile={id:'array',name:'files',schema:{type:'array-string'},fileType:'file',allowedFileType:['pdf'],required:null};
const scalarFile={id:'one',name:'file',schema:{type:'string'},fileType:'file',allowedFileType:['pdf'],required:null};
const consumerCases=[['reserved + array file',[system,arrayFile],'notSupported'],['reserved + scalar file',[system,scalarFile],'chat'],['array file alone original limitation',[arrayFile],'chat'],['array file + reserved order preserved',[arrayFile,system],'notSupported'],['more than two',[system,scalarFile,arrayFile],'notSupported'],['nullable extra fields',[system,{...scalarFile,nameErrMsg:null,refId:null,disabled:null,futureRoot:'untouched'}],'chat']];
const consumers=[];
for(const [name,data,expected] of consumerCases){
 const c=cache();load(c);const r=sync(c);const raw=JSON.stringify(data),result=reply(c,r,'3','9',data);
 const actions=[],sandbox={module:{exports:null},message:{info:()=>actions.push('notSupported')},t:key=>key,handleToChat:id=>actions.push('chat'),k:{botId:9}};
 vm.runInNewContext(callbackCode,sandbox,{timeout:1000});sandbox.module.exports(data);
 check(name+' receipt retains raw array',result,true);check(name+' array order/object content untouched',JSON.stringify(data),raw);check(name+' original branch',actions,[expected]);
 consumers.push({name,expected,actualActions:actions,responseUnmodified:raw===JSON.stringify(data)});
}
const after=hashes(),changes=Object.keys(before).filter(p=>before[p]!==after[p]);
const failed=checks.filter(item=>!item.passed);
const report={schemaVersion:'paiflow.input-type-sync.transport-readonly-review.v1',checkedAt:new Date().toISOString(),
 method:'actual TypeScript transport via transpileModule + original AgentPage then-arrow AST in isolated Node VM; request, headers, payload and navigation spies only',
 sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:changes,checks,checkCount:checks.length,failed,originalConsumerCases:consumers,
 sourceFindings:['receipt only removes Agent entry/pending; same numeric namespace WF/Model untouched','late prior read/write cannot reseed after invalidation; direct old body conditions do not obtain automatic new revision','original consumer uses ordered full response; AGENT_USER_INPUT metadata exclusion must not filter or reorder the response','len1 array-file alone reaches original chat branch: existing page limitation, not formal App/chat availability'],
 retainedProbeFailure:{report:'输入类型同步条件与原消费者探针夹具首失败.json',log:'输入类型同步条件与原消费者-探针夹具首失败.log',reason:'探针首次把Agent10加载为Workflow9，却把其pending detail响应构造为Workflow10；真实helper正确拒绝绑定不一致，不能归为生产漏洞。修正仅匹配夹具绑定，不改生产源或预期标准。'},
 productionSourceModified:false,frontendTestsExecuted:false,rootSuiteRerun:false,browserUsed:false,network:false,realDatabaseWrites:false,businessVerified:false,
 limits:['未运行HTTP/browser；prepare/accept就是实际helper，服务器正式sync尚由Java本批实现和隔离事务验证。','无新增全局source冻结；只核Root已冻结的5个前端来源窗口。','不能由VM branch chat spy认定正式App/chat或文件上传已完成。']};
const out=path.join(__dirname,'输入类型同步条件与原消费者独立核对.json');fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({path:path.relative(root,out),sha256:sha(out),checks:checks.length,failed,sourceWindowChanges:changes,consumerCases:consumers.length},null,2));
if(failed.length||changes.length)process.exitCode=1;
