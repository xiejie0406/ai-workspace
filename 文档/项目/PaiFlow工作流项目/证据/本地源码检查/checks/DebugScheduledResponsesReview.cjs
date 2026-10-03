const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../../..'),front=path.join(root,'frontend/paiflow-web'),ts=require(path.join(front,'node_modules/typescript'));
const sha=value=>crypto.createHash('sha256').update(value).digest('hex'),sourceHashes={};
function load(relative,imports={}) {const file=path.join(front,relative),bytes=fs.readFileSync(file);sourceHashes[relative]=sha(bytes);const exports={};vm.runInNewContext(ts.transpileModule(bytes.toString('utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports,URL,URLSearchParams,Headers,AbortController,require:name=>{if(!(name in imports))throw new Error(name);return imports[name];}});return exports;}
const artifacts={};for(const name of ['调试构建实际MVC响应.json','调试流调度修复实际MVC响应.json','调试回复调度修复实际MVC响应.json']) {const file=path.join(__dirname,name),bytes=fs.readFileSync(file);artifacts[name]={sha256:sha(bytes),data:JSON.parse(bytes)};}
assert.equal(artifacts['调试流调度修复实际MVC响应.json'].sha256,'ed3b58fd492c4e59ea8fcd3001aff2ca790de461cb677e12da0b69b7df00d38d');assert.equal(artifacts['调试回复调度修复实际MVC响应.json'].sha256,'735f766bebaf7c918f24b318d248a240d6f860e7070a2311b994b64657570924');
const helper=load('src/utils/workflow-debug.ts'),revisions=load('src/utils/workflow-revision.ts');const build=artifacts['调试构建实际MVC响应.json'].data,stream=artifacts['调试流调度修复实际MVC响应.json'].data,reply=artifacts['调试回复调度修复实际MVC响应.json'].data;
const facts=[];let assertions=0;function check(name,fn){fn();facts.push(name);assertions++;}
const cache=new revisions.WorkflowRevisionTransport();cache.setScope('fixture-actor','1');
const detail={method:'get',url:`/workflow?id=${build.request.id}`};const ticket=cache.prepare(detail,'/paiflow/legacy','https://fixture.example');
check('实际构建源域与读条件0建立',()=>assert.equal(cache.accept(ticket,{code:0,data:{id:build.request.id,flowId:build.request.flowId,spaceId:1,revision:'0'}},{ETag:'"0"'}),true));
let sent;const services=load('src/services/flow.ts',{'@/utils/http':{default:{post:async(url,body,options)=>{const config={method:'post',url,data:body,headers:options.headers};const write=cache.prepare(config,'/paiflow/legacy','https://fixture.example');sent=config;
 const bytes=Buffer.from(build.bodyUTF8Base64,'base64'),envelope=JSON.parse(bytes.toString('utf8'));assert.equal(cache.accept(write,envelope,build.headers),true);return envelope.data;}}}});
(async()=>{
 const returned=await services.buildFlowAPI(build.request,build.requestHeaders['X-PaiFlow-Build-Key']);
 check('实际原服务保持build body对象与专用key头，并用原读If-Match',()=>{assert.equal(sent.data,build.request);assert.equal(sent.headers['If-Match'],build.requestHeaders['If-Match']);assert.equal(sent.headers['X-PaiFlow-Build-Key'],build.requestHeaders['X-PaiFlow-Build-Key']);});
 const receipt=helper.readDebugReceipt(returned,{workflowId:build.request.id,flowId:build.request.flowId,spaceId:'1',mode:'ordinary',comparisonId:null,buildRequestKey:build.requestHeaders['X-PaiFlow-Build-Key']});
 check('实际Java构建回执完整且普通构建条件推进到1',()=>{assert.equal(receipt.dslHash,build.response.data.debugReceipt.dslHash);const write={method:'put',url:'/workflow',data:{id:build.request.id},headers:{}};cache.prepare(write,'/paiflow/legacy','https://fixture.example');assert.equal(write.headers['If-Match'],'"1"');});
 const ctx=new helper.WorkflowDebugContext(stream.request.debugReceipt,'fixture-scope',()=> 'fixture-scope',stream.request.requestKey,stream.request,()=> 'fixture-reply-key');
 // artifact仅保存两个X头；标准Content-Type/status为本探针上下文，不能冒充实录。
 ctx.open({status:200,headers:new Headers({'Content-Type':'text/event-stream',...stream.headers})});
 const wireBytes=Buffer.from(stream.bodyUTF8Base64,'base64');check('实际SSE原UTF8 bytes逐字一致',()=>assert.equal(wireBytes.toString('utf8'),stream.wire));
 const parse=require(path.join(front,'node_modules/@microsoft/fetch-event-source/lib/cjs/parse.js')),messages=[];
 const onLine=parse.getLines(parse.getMessages(()=>{},()=>{},message=>messages.push(message)));
 await parse.getBytes(new ReadableStream({start(controller){controller.enqueue(wireBytes);controller.close();}}),onLine);
 check('实际原fetch-event-source解析器得到真实runId:seq499终帧',()=>{assert.equal(messages.length,1);assert.equal(messages[0].id,`${stream.headers['X-PaiFlow-Run-Id']}:1`);assert.equal(JSON.parse(messages[0].data).code,499);});
 check('当前helper接收实际终帧并固定同Run游标',()=>{assert.equal(ctx.frame(messages[0].id,messages[0].data),true);assert.equal(ctx.terminal,true);assert.equal(ctx.lastEventId,messages[0].id);});
 const original=path.join(root,'参考项目/PaiFlow-main/console/frontend/src/components/workflow/store/flow-chat-function.ts'),text=fs.readFileSync(original,'utf8');sourceHashes.originalFlowChat=sha(Buffer.from(text));
 const ast=ts.createSourceFile(original,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);const names=['initInterruptChat','extractNodeInfo','handleMessage','handleInterrupt','handleFlowStop','handleMessageEnd'];
 const declarations=names.map(name=>{const statement=ast.statements.find(s=>ts.isVariableStatement(s)&&s.declarationList.declarations.some(d=>d.name.getText(ast)===name));assert.ok(statement);return statement.getText(ast);});
 const state={chatInfoRef:{answer:{}},setInterruptChat:()=>{}},observation={pushed:[],flowResult:null};const sandbox={module:{exports:{}},isJSON:()=>true,handleNodeStatusChange:()=>{throw new Error('unexpected node');},handleAuditFailed:()=>{throw new Error('unexpected audit');},handleRunningNodeStatus:()=>{},pushContentToAnswer:(name,value)=>observation.pushed.push({name,value}),useFlowStore:{getState:()=>({setEdges:fn=>fn([])})},useFlowsManager:{getState:()=>({setShowNodeList:()=>{},setFlowResult:value=>observation.flowResult=value,setCanvasesDisabled:()=>{},historyVersion:false})}};
 vm.runInNewContext(ts.transpileModule(declarations.join('\n')+'\nmodule.exports={handleMessage};',{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,sandbox);
 sandbox.module.exports.handleMessage([],[],messages[0],()=>state,()=>{});
 check('实际Java499终帧进入未改原handleMessage停止分支，原结果failed',()=>assert.equal(observation.flowResult.status,'failed'));
 const replyContext=new helper.WorkflowDebugContext(reply.request.debugReceipt,'fixture-scope',()=> 'fixture-scope','fixture-run-key',{flow_id:reply.request.flow_id,inputs:{},chatId:'fixture'},()=>reply.request.replyKey);
 replyContext.open({status:200,headers:new Headers({'Content-Type':'text/event-stream','X-PaiFlow-Run-Id':reply.request.runId,'X-PaiFlow-Workflow-Version-Id':reply.request.debugReceipt.workflowVersionId})});
 // 真实ACK保存了CP条件，未保存CP SSE帧；只为ACK验证建立隔离的已读条件上下文。
 replyContext.checkpoint={eventId:reply.request.eventId,nodeId:'fixture-context-only',revision:reply.request.expectedRevision,state:'WAITING',replyKey:reply.request.replyKey};
 check('实际abort ACK RESUMED/stoppedfalse只确认受理，不冒充Run已停',()=>{replyContext.acknowledge(reply.response,true);assert.equal(reply.response.data.stopped,false);assert.equal(replyContext.terminal,false);});
 check('同真实检查点abort请求窄补空串并保首次replyKey/revision',()=>{const body=JSON.parse(replyContext.reply(reply.request.eventId,'abort',''));assert.equal(body.content,'');assert.equal(body.replyKey,reply.request.replyKey);assert.equal(body.expectedRevision,reply.request.expectedRevision);});
 const changed=Object.keys(sourceHashes).filter(relative=>relative!=='originalFlowChat'&&sourceHashes[relative]!==sha(fs.readFileSync(path.join(front,relative))));assert.deepEqual(changed,[]);
 const report={checkedAt:new Date().toISOString(),status:'actual_java_artifacts_private_consumer_checked',assertions,facts,artifacts:Object.fromEntries(Object.entries(artifacts).map(([name,value])=>[name,{sha256:value.sha256}])),sourceHashes,sourceWindowChanges:changed,
 actualJavaStage:'第二次10项实际H2/MVC调度复验生成；第三次只跑1项未重生成这两份',priorActualConsumerProof:{path:'调试接续Java实际响应原消费者最终独立核对.json',sha256:sha(fs.readFileSync(path.join(__dirname,'调试接续Java实际响应原消费者最终独立核对.json')))},currentFlowSourceHashForViewStage:sha(fs.readFileSync(path.join(front,'src/components/workflow/store/flow-chat-function.ts'))),originalConsumerObservation:observation,streamWireSha256:sha(wireBytes),limitations:['Content-Type/status为公共SSE探针上下文，artifact未实录这两项','ACK探针用真实request CP条件建立隔离已读上下文，未实录CP SSE帧','原handleMessage由AST在VM执行，UI/store更新用spy','HTTP客户端为内存适配器，未启动正式服务/页面/Worker/Provider；不计业务验收'],businessVerified:false};
 const output=path.join(__dirname,process.argv[2]||'调试接续调度修复Java实际响应原消费者最终独立核对.json');if(fs.existsSync(output))throw new Error('不可覆盖旧结果');fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({assertions,changed,sha256:sha(fs.readFileSync(output))}));
})().catch(error=>{console.error(error);process.exitCode=1;});
