const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
function execute(file,imports={},extra={}) {
 const exports={};const source=fs.readFileSync(path.join(__dirname,'../src',file),'utf8');
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,
  {exports,AbortController,Headers,Response,JSON,BigInt,setTimeout,clearTimeout,...extra,require:name=>{if(!(name in imports))throw new Error(`unexpected ${name}`);return imports[name];}});
 return exports;
}
const helper=execute('utils/workflow-debug.ts');
const receipt=(extra={})=>({workflowVersionId:'51',workflowId:'9',flowId:'flow-nine',mode:'ordinary',comparisonId:null,
 buildRequestKey:'build-key-fixture',snapshotRevision:'5',dslHash:'a'.repeat(64),engineProfile:'python',executionCodec:'paiflow.execution.v3',...extra});
const data=(r=receipt(),extra={})=>({id:'9',flowId:'flow-nine',spaceId:null,revision:r.snapshotRevision,debugReceipt:r,...extra});
const expected=(extra={})=>({workflowId:'9',flowId:'flow-nine',spaceId:'',mode:'ordinary',comparisonId:null,buildRequestKey:'build-key-fixture',...extra});
const opened=(runId='run-fixture-nine',version='51')=>({status:200,headers:new Headers({'Content-Type':'text/event-stream; charset=utf-8','X-PaiFlow-Run-Id':runId,'X-PaiFlow-Workflow-Version-Id':version})});
function context(r=receipt()) {let scope='actor-space';const ctx=new helper.WorkflowDebugContext(r,scope,()=>scope,'request-key-fixture',
 {flow_id:r.flowId,inputs:{original:null},chatId:'original-chat-id',regen:false,...(r.mode==='comparison'?{version:r.comparisonId,promptDebugger:true}:{})},()=> 'reply-key-fixture');
 return {ctx,switch:()=>{scope='other-actor';}};}
const frame=(finish=null,extra={})=>JSON.stringify({code:0,choices:[{finish_reason:finish,delta:{content:null}}],workflow_step:{node:{id:'qa',finish_reason:null}},...extra});
const interrupt=(extra={})=>frame('interrupt',{event_data:{event_id:'checkpoint-nine',checkpoint_revision:'0',checkpoint_state:'WAITING',checkpoint_waiting:true,value:{content:'问题',type:'direct'},need_reply:true,...extra}});

test('构建回执逐字段绑定同一次调用且复制冻结，不依赖latest/versionId猜测',()=>{
 const original=data();const r=helper.readDebugReceipt(original,expected());assert.equal(r.workflowVersionId,'51');assert.equal(Object.isFrozen(r),true);
 original.debugReceipt.workflowVersionId='99';assert.equal(r.workflowVersionId,'51');
 for(const patch of [{id:9},{flowId:'other'},{spaceId:1},{revision:'6'},{debugReceipt:null}]) assert.throws(()=>helper.readDebugReceipt(data(receipt(),patch),expected()));
 for(const patch of [{workflowId:'10'},{flowId:'other'},{workflowVersionId:'0'},{snapshotRevision:'05'},{mode:'comparison'},{comparisonId:undefined},{buildRequestKey:'other-key'},{dslHash:'A'.repeat(64)},{executionCodec:'unknown'},{executionCodec:['paiflow.execution.v3']}])
  assert.throws(()=>helper.readDebugReceipt(data(receipt(patch)),expected()));
});
test('比较回执必须原comparison UUID与明确mode，具名space和long字符串精确保留',()=>{
 const r=receipt({mode:'comparison',comparisonId:'comparison-fixture',workflowId:'9223372036854775807',snapshotRevision:'9223372036854775806'});
 const actual=helper.readDebugReceipt(data(r,{id:r.workflowId,spaceId:'9223372036854775807'}),expected({workflowId:r.workflowId,spaceId:'9223372036854775807',mode:'comparison',comparisonId:r.comparisonId}));
 assert.equal(actual.snapshotRevision,r.snapshotRevision);assert.throws(()=>helper.readDebugReceipt(data(r),expected({mode:'comparison',comparisonId:'other'})));
 assert.throws(()=>helper.readDebugReceipt(data(),expected({workflowId:9223372036854775807})));
});
test('普通模式不泄漏前次comparison版本；chat原输入和chatId保留，requestKey独立固定',()=>{
 const {ctx}=context();const body=JSON.parse(ctx.chatBody);assert.deepEqual(body.inputs,{original:null});assert.equal(body.chatId,'original-chat-id');assert.equal(body.requestKey,'request-key-fixture');assert.equal(body.debugReceipt.workflowVersionId,'51');
 assert.throws(()=>new helper.WorkflowDebugContext(receipt(),'scope',()=> 'scope','request-key-fixture',{flow_id:'flow-nine',version:'old'},()=> 'reply-key-fixture'));
});
test('V4 Python构建回执完整绑定调试请求，错引擎和未知格式不能通过',()=>{
 const r=receipt({executionCodec:'paiflow.execution.v4'});
 const actual=helper.readDebugReceipt(data(r),expected());
 const ctx=new helper.WorkflowDebugContext(actual,'scope',()=> 'scope','request-key-fixture',{flow_id:r.flowId,inputs:{question:'原问题'},chatId:'original-chat-id',regen:false},()=> 'reply-key-fixture');
 const body=JSON.parse(ctx.chatBody);assert.equal(body.debugReceipt.executionCodec,'paiflow.execution.v4');assert.equal(body.debugReceipt.workflowVersionId,r.workflowVersionId);assert.deepEqual(body.inputs,{question:'原问题'});
 for(const patch of [{engineProfile:'java'},{executionCodec:'paiflow.execution.v5'},{executionCodec:null}])assert.throws(()=>helper.readDebugReceipt(data({...r,...patch}),expected()));
});
test('打开流必须HTTP200/SSE和真实同Run双头，跨Run/version/JSON响应不能接收',()=>{
 const {ctx}=context();ctx.open(opened());ctx.open(opened());
 for(const response of [opened('other-run-id'),opened('run-fixture-nine','52'),{status:503,headers:opened().headers},{status:200,headers:new Headers({'Content-Type':'application/json'})}]) assert.throws(()=>ctx.open(response));
 assert.equal(ctx.runId,'run-fixture-nine');
});
test('runId:seq持久序号单调去重，跳过不投影事件允许间隔，错误Run/大整数拒绝',()=>{
 const {ctx}=context();ctx.open(opened());assert.equal(ctx.frame('run-fixture-nine:1',frame()),true);assert.equal(ctx.frame('run-fixture-nine:1',frame()),false);
 assert.equal(ctx.frame('run-fixture-nine:5',frame()),true);assert.equal(ctx.lastEventId,'run-fixture-nine:5');
 for(const id of ['other-run-id:6','run-fixture-nine:0','run-fixture-nine:01','run-fixture-nine:9223372036854775808']) assert.throws(()=>ctx.frame(id,frame()));
 assert.equal(ctx.terminal,false);
});
test('只有真实WAITING帧可暂停EOF，CP首次revision和replyKey固定，回复重试body一致',()=>{
 const {ctx}=context();ctx.open(opened());ctx.frame('run-fixture-nine:1',interrupt());assert.equal(ctx.waiting,true);
 const first=ctx.reply('checkpoint-nine','resume','回答');assert.equal(ctx.waiting,false);assert.equal(ctx.reply('checkpoint-nine','resume','回答'),first);
 const body=JSON.parse(first);assert.equal(body.expectedRevision,'0');assert.equal(body.replyKey,'reply-key-fixture');assert.equal(body.runId,'run-fixture-nine');assert.equal(body.flow_id,'flow-nine');assert.equal('version' in body,false);
 assert.throws(()=>ctx.reply('checkpoint-nine','resume','换答案'));assert.throws(()=>ctx.reply('other-cp','resume','回答'));
 assert.throws(()=>ctx.frame('run-fixture-nine:2',interrupt({checkpoint_revision:'1'})));
});
test('问答条件缺失/非WAITING/非法revision不展示interrupt，不回读最新CP条件',()=>{
 for(const patch of [{checkpoint_revision:undefined},{checkpoint_revision:0},{checkpoint_revision:'01'},{checkpoint_state:'RESUMED'},{checkpoint_waiting:undefined},{checkpoint_waiting:'true'},{event_id:'other cp'}]) {
  const {ctx}=context();ctx.open(opened());assert.throws(()=>ctx.frame('run-fixture-nine:1',interrupt(patch)));assert.equal(ctx.waiting,false);
 }
});
test('历史interrupt不暴露可回复等待；同seq资格失效先清等待，再去重且不升级CP条件',()=>{
 const {ctx}=context();ctx.open(opened());ctx.frame('run-fixture-nine:1',interrupt());assert.equal(ctx.waiting,true);
 assert.equal(ctx.frame('run-fixture-nine:1',interrupt({checkpoint_waiting:false})),false);assert.equal(ctx.waiting,false);assert.equal(ctx.checkpoint,undefined);
 assert.throws(()=>ctx.reply('checkpoint-nine','resume','回答'));
 assert.equal(ctx.frame('run-fixture-nine:2',interrupt({checkpoint_state:'EXPIRED',checkpoint_waiting:false})),false);assert.equal(ctx.lastEventId,'run-fixture-nine:2');
});
test('abort窄补空字符串，comparison回复绑定原UUID与原receipt版本',()=>{
 const r=receipt({mode:'comparison',comparisonId:'comparison-fixture'});const {ctx}=context(r);ctx.open(opened());ctx.frame('run-fixture-nine:1',interrupt());
 const body=JSON.parse(ctx.reply('checkpoint-nine','abort',''));assert.equal(body.content,'');assert.equal(body.version,'comparison-fixture');assert.equal(body.promptDebugger,true);assert.equal(body.debugReceipt.workflowVersionId,'51');
});
test('原question-answer::ID可接收真实WAITING；比较stop带原版本和promptDebugger',()=>{
 const {ctx}=context(receipt({mode:'comparison',comparisonId:'comparison-fixture'}));ctx.open(opened());
 const message=JSON.parse(interrupt());message.workflow_step.node.id='question-answer::q';assert.equal(ctx.frame('run-fixture-nine:1',JSON.stringify(message)),true);
 assert.equal(ctx.checkpoint.nodeId,'question-answer::q');const body=JSON.parse(ctx.cancelBody());assert.equal(body.version,'comparison-fixture');assert.equal(body.promptDebugger,true);
 for(const invalid of ['', 'q'.repeat(256),null]) {const f=context().ctx;f.open(opened());message.workflow_step.node.id=invalid;assert.throws(()=>f.frame('run-fixture-nine:1',JSON.stringify(message)));}
});
test('停止/回复ACK只确认受理，真实terminal仍由同Run SSE决定；错Run或CP拒绝',()=>{
 const {ctx}=context();ctx.open(opened());ctx.frame('run-fixture-nine:1',interrupt());ctx.reply('checkpoint-nine','abort','');
 for(const state of ['RESUMED','CONSUMED','EXPIRED','CANCELLED']) ctx.acknowledge({code:0,data:{runId:ctx.runId,checkpointId:'checkpoint-nine',state}},true);
 for(const state of ['CANCEL_REQUESTED','CANCELLED','COMPLETED','FAILED','EFFECT_UNKNOWN']) ctx.acknowledge({code:0,data:{runId:ctx.runId,state}},false);
 assert.equal(ctx.terminal,false);assert.throws(()=>ctx.acknowledge({code:0,data:{runId:'other-run-id',state:'CANCEL_REQUESTED'}},false));
 assert.throws(()=>ctx.acknowledge({code:0,data:{runId:ctx.runId,checkpointId:'wrong',state:'RESUMED'}},true));
 assert.throws(()=>ctx.acknowledge({code:0,data:{runId:ctx.runId,state:'WAITING'}},false));
});
test('只有中心stop码结束上下文，EOF无终态；原节点23802失败不伪造终态',()=>{
 const {ctx}=context();ctx.open(opened());ctx.frame('run-fixture-nine:1',frame(null,{code:23802}));assert.equal(ctx.terminal,false);
 assert.throws(()=>ctx.frame('run-fixture-nine:2',frame('stop',{code:23802})));assert.equal(ctx.terminal,false);
 ctx.frame('run-fixture-nine:3',frame('stop',{code:500}));assert.equal(ctx.terminal,true);assert.equal(ctx.lastEventId,'run-fixture-nine:3');
});
test('会话/空间切换或显式detach取消本地流，禁止旧回复但不发送业务cancel',()=>{
 const fixture=context();const signal=fixture.ctx.nextStream();let cleanups=0;fixture.ctx.addCleanup(()=>cleanups++);
 fixture.switch();assert.equal(fixture.ctx.alive(),false);assert.equal(signal.aborted,true);assert.equal(cleanups,1);assert.throws(()=>fixture.ctx.cancelBody());
 const other=context();const old=other.ctx.nextStream(), fresh=other.ctx.nextStream();assert.equal(old.aborted,true);assert.equal(fresh.aborted,false);other.ctx.detach();assert.equal(fresh.aborted,true);
});

function application(history=false, capturedNodes) {
 let next=0;const requests=[], builds=[],errors=[],windowListeners=new Map();const listeners={space:new Set(),manager:new Set()};
 const notify=kind=>listeners[kind].forEach(fn=>fn());
 const manager={currentFlow:{id:'9',flowId:'flow-nine',name:'原图',advancedConfig:JSON.stringify({suggestedQuestionsAfterAnswer:{enabled:false}})},historyVersion:history,historyVersionData:{id:'10'},
  setCanPublish:()=>{},setShowNodeList:()=>{},setCanvasesDisabled:value=>manager.disabled=value,setFlowResult:value=>{manager.flowResult=value;notify('manager');}};
 const space={spaceId:'',spaceType:'personal',enterpriseId:''};let token='Bearer actor-a';
 const nodes=capturedNodes??[{id:'node-start',nodeType:'node-start',data:{status:'',debuggerResult:{}},position:{x:0,y:0}},{id:'qa',data:{status:'',debuggerResult:{}},position:{x:0,y:0}}];
 const flow={nodes,edges:[],setNodes:change=>{flow.nodes=typeof change==='function'?change(flow.nodes):change;},setNode:()=>{},setEdges:()=>{}};
 const service={buildFlowAPI:(params,key)=>new Promise(resolve=>builds.push({mode:'ordinary',params,key,resolve})),addComparisons:(params,key)=>new Promise(resolve=>builds.push({mode:'comparison',params,key,resolve})),getInputsType:()=>Promise.resolve([])};
 const streams=async(url,options)=>new Promise(resolve=>requests.push({url,options,resolve}));
 const imports={'@/services/flow':service,'@/services/common':{nextQuestionAdvice:()=>Promise.resolve([])},uuid:{v4:()=>`uuid-key-${++next}`},'./flow-function':{moveToPosition:()=>{}},
  '../utils/reactflowUtils':{generateDefaultInput:()=>'',generateInputsAndOutputsOrder:(_,values)=>values},'./use-flows-manager':{default:{getState:()=>manager,subscribe:fn=>{listeners.manager.add(fn);return()=>listeners.manager.delete(fn);}}},
  './use-flow-store':{default:{getState:()=>flow}},'@/utils':{isJSON:value=>{try{JSON.parse(value);return true;}catch{return false;}}},i18next:{default:{t:value=>value}},lodash:{cloneDeep:structuredClone},
  '@/components/workflow/utils':{getFixedUrl:path=>`https://centre.example/paiflow/legacy${path}`,getAuthorization:()=>token},'@microsoft/fetch-event-source':{fetchEventSource:streams},
  antd:{message:{error:value=>errors.push(value)}},'@/store/space-store':{default:{getState:()=>space,subscribe:fn=>{listeners.space.add(fn);return()=>listeners.space.delete(fn);}}},'@/utils/workflow-debug':helper};
 const module=execute('components/workflow/store/flow-chat-function.ts',imports,{fetch:async(url,options)=>{requests.push({url,options,short:true});return new Response(JSON.stringify({code:0,data:{runId:'run-fixture-nine',checkpointId:'checkpoint-nine',state:url.endsWith('/resume')?'RESUMED':'CANCEL_REQUESTED'}}),{headers:{'Content-Type':'application/json'}});},
  window:{addEventListener:(name,fn)=>windowListeners.set(name,fn),removeEventListener:(name,fn)=>{if(windowListeners.get(name)===fn)windowListeners.delete(name);}},setTimeout:(fn,ms)=>ms<=500?setTimeout(fn,0):setTimeout(fn,ms),clearTimeout});
 const state={...structuredClone(module.initialStatus),setChatList:change=>{state.chatList=typeof change==='function'?change(state.chatList):change;},setInterruptChat:value=>state.interruptChat=value,
  setStartNodeParams:()=>{},setSuggestLoading:()=>{},setSuggestProblem:()=>{},setDebuggering:value=>state.debuggering=value};
 const get=()=>state,set=change=>Object.assign(state,change);
 return {module,state,manager,space,nodes,get,set,requests,builds,errors,windowListeners,notify,switchActor:()=>{token='Bearer actor-b';windowListeners.get('storage')?.();},
  start:()=>module.handleRunDebugger({nodes,edges:[],get,set,inputs:[{name:'AGENT_USER_INPUT',type:'string',default:'原输入'}]}),
  build:index=>{const build=builds[index];build.resolve(data(receipt({buildRequestKey:build.key,mode:build.mode,comparisonId:build.mode==='comparison'?build.params.version:null})));}};
}
const flush=async()=>{for(let i=0;i<8;i++)await Promise.resolve();};
test('原build与comparison服务只加专用key头，body和原无key调用形状保留',async()=>{
 const calls=[];const services=execute('services/flow.ts',{'@/utils/http':{default:{post:async(...args)=>{calls.push(args);return 'original';}}}});
 const body={flowId:'flow-nine',data:{nodes:[],edges:[]}};
 assert.equal(await services.buildFlowAPI(body,'build-key-fixture'),'original');await services.addComparisons(body,'comparison-key-fixture');await services.buildFlowAPI(body);
 assert.equal(calls[0][1],body);assert.equal(calls[0][2].headers['X-PaiFlow-Build-Key'],'build-key-fixture');assert.equal(calls[1][2].headers['X-PaiFlow-Build-Key'],'comparison-key-fixture');assert.equal(calls[2][2],undefined);
});
test('原manager.historyVersion同次选择比较构建与chat；旧chatStore缺字段不影响分支',async()=>{
 const app=application(true);app.start();assert.equal(app.builds[0].mode,'comparison');app.build(0);await flush();
 assert.equal(app.requests.length,1);const body=JSON.parse(app.requests[0].options.body);
 assert.equal(body.version,app.builds[0].params.version);assert.equal(body.promptDebugger,true);assert.equal(body.debugReceipt.mode,'comparison');assert.equal(body.inputs.AGENT_USER_INPUT,'原输入');
 assert.equal(app.requests[0].options.headers['space-id'],'');assert.equal(app.requests[0].options.headers.Authorization,'Bearer actor-a');app.state.debugContext.detach();
});
test('原构建反序/身份切换后的成功回执不创建新chat或覆盖新上下文',async()=>{
 const app=application();app.start();app.start();app.build(1);await flush();const context=app.state.debugContext;app.build(0);await flush();
 assert.equal(app.requests.length,1);assert.equal(app.state.debugContext,context);app.switchActor();assert.equal(context.controller.signal.aborted,true);
 const other=application();other.start();other.space.spaceId='2';other.notify('space');other.build(0);await flush();assert.equal(other.requests.length,0);
});
test('历史true到true选另卡片必须detach旧Run，同卡片元数据更新继续原上下文',async()=>{
 const app=application(true);app.start();app.build(0);await flush();const context=app.state.debugContext;
 app.manager.historyVersionData={id:'10',name:'同卡改名',data:'元数据'};app.notify('manager');assert.equal(context.alive(),true);
 app.manager.historyVersionData={id:'11',name:'另卡'};app.notify('manager');assert.equal(app.manager.historyVersion,true);assert.equal(context.controller.signal.aborted,true);
 assert.equal(app.requests.some(req=>req.short),false);app.requests[0].resolve();await flush();
});
test('未知历史选择不能建立比较上下文；默认普通域忽略无关历史元数据',async()=>{
 for(const invalid of [null,false,[],{}, {id:'01'}, {id:9223372036854775807}, {id:['10']}, {id:'9223372036854775808'}]) {
  const app=application(true);app.manager.historyVersionData=invalid;app.start();assert.equal(app.builds.length,0);assert.equal(app.errors.length,1);
 }
 const app=application(false);app.manager.historyVersionData=false;app.start();app.build(0);await flush();assert.equal(app.requests.length,1);app.state.debugContext.detach();app.requests[0].resolve();await flush();
});
test('构建期间选另一历史卡片，旧比较回执不派发新图chat',async()=>{
 const app=application(true);app.start();app.manager.historyVersionData={id:'11'};app.notify('manager');app.build(0);await flush();assert.equal(app.requests.length,0);assert.equal(app.state.controllerRef.signal.aborted,true);
});
test('跨历史卡断开只释放当前本地按钮和问答视图，旧队列不能流入新图',async()=>{
 const app=application(true);app.module.resetNodesAndEdges(app.get);app.start();app.build(0);await flush();const context=app.state.debugContext;
 app.state.interruptChat={interrupt:true,eventId:'checkpoint-nine',nodeId:'qa'};app.state.messageNodeTextQueue='旧消息';app.state.endNodeReasoningTextQueue='旧推理';app.state.endNodeTextQueue='旧答案';
 const originalChat=JSON.stringify(app.state.chatList);app.manager.historyVersionData={id:'11'};app.notify('manager');
 assert.equal(context.controller.signal.aborted,true);assert.equal(app.state.debugContext,undefined);assert.equal(app.state.debuggering,false);assert.equal(app.state.buildPassRef,false);
 assert.equal(app.state.interruptChat.interrupt,false);assert.equal(app.state.versionId,'');assert.equal(app.manager.flowResult.status,'');assert.equal(app.manager.disabled,true);
 assert.equal(app.state.messageNodeTextQueue+app.state.endNodeReasoningTextQueue+app.state.endNodeTextQueue,'');assert.equal(JSON.stringify(app.state.chatList),originalChat);
 assert.equal(app.requests.some(request=>request.short),false);app.requests[0].resolve();await flush();
});
test('真实终帧跳过detach视图释放，仍由原handleMessage更新成功状态并保留保存资格',async()=>{
 const app=application();app.module.resetNodesAndEdges(app.get);app.start();app.build(0);await flush();const request=app.requests[0],context=app.state.debugContext;await request.options.onopen(opened());
 request.options.onmessage({id:'run-fixture-nine:1',data:frame('stop')});
 assert.equal(context.terminal,true);assert.equal(app.state.debugContext,context);assert.equal(app.state.buildPassRef,true);assert.equal(app.state.debuggering,true);
 assert.equal(app.state.wsMessageStatus,'end');assert.equal(app.manager.flowResult.status,'success');assert.equal(app.manager.disabled,false);
 context.detach();assert.equal(app.manager.flowResult.status,'success');assert.equal(app.state.buildPassRef,true);assert.equal(app.requests.some(item=>item.short),false);request.resolve();await flush();
});
test('旧上下文cleanup不能释放后续上下文的按钮、interrupt和结果',async()=>{
 const app=application();app.module.resetNodesAndEdges(app.get);app.start();app.build(0);await flush();const old=app.state.debugContext;
 const newer={marker:'新所有者'};app.state.debugContext=newer;app.state.interruptChat={interrupt:true,eventId:'new-cp'};old.detach();
 assert.equal(app.state.debugContext,newer);assert.equal(app.state.debuggering,true);assert.equal(app.state.buildPassRef,true);assert.equal(app.state.interruptChat.eventId,'new-cp');
 assert.equal(app.manager.flowResult.status,'running');assert.equal(app.requests.some(request=>request.short),false);app.requests[0].resolve();await flush();
});
test('原reset后构建期间切卡，abort释放构建所有者且迟到回执不重锁按钮',async()=>{
 const app=application(true);app.module.resetNodesAndEdges(app.get);app.start();const controller=app.state.controllerRef;
 app.manager.historyVersionData={id:'11'};app.notify('manager');assert.equal(controller.signal.aborted,true);assert.equal(app.state.debuggering,false);
 assert.equal(app.state.buildPassRef,false);assert.equal(app.state.versionId,'');assert.equal(app.manager.flowResult.status,'');assert.equal(app.manager.disabled,true);
 app.build(0);await flush();assert.equal(app.state.debuggering,false);assert.equal(app.requests.length,0);
});
test('历史调试抽屉close保留当前只读选择；普通抽屉close可释放自身编辑锁',async()=>{
 for(const history of [true,false]) {
  const app=application(history);app.module.resetNodesAndEdges(app.get);app.start();app.build(0);await flush();const context=app.state.debugContext;
  app.manager.disabled=true;let closed=0;app.module.clearData(()=>closed++,app.get);
  assert.equal(closed,1);assert.equal(context.controller.signal.aborted,true);assert.equal(app.manager.disabled,history);assert.equal(app.state.debuggering,false);
  assert.equal(app.state.buildPassRef,false);assert.equal(app.manager.flowResult.status,'');assert.equal(app.requests.some(request=>request.short),false);app.requests[0].resolve();await flush();
 }
});
test('反序旧构建abort和旧ctxdetach都不能清新buildKey拥有的本地调试状态',async()=>{
 const app=application();app.module.resetNodesAndEdges(app.get);app.start();const oldController=app.state.controllerRef;
 app.module.resetNodesAndEdges(app.get);app.start();const key=app.state.debugBuildKey;assert.equal(oldController.signal.aborted,true);assert.equal(app.state.debuggering,true);
 app.build(0);await flush();assert.equal(app.state.debugBuildKey,key);assert.equal(app.state.debuggering,true);app.build(1);await flush();const old=app.state.debugContext;
 app.module.resetNodesAndEdges(app.get);app.start();const nextKey=app.state.debugBuildKey;assert.equal(old.controller.signal.aborted,true);assert.equal(app.state.debuggering,true);assert.equal(app.state.debugBuildKey,nextKey);
 app.build(2);await flush();assert.notEqual(app.state.debugContext,old);assert.equal(app.state.debuggering,true);app.state.debugContext.detach();for(const request of app.requests)request.resolve();await flush();
});
test('原SSE严格onopen绑定；onerror抛出阻止原库abort后timer重试',async()=>{
 const app=application();app.start();app.build(0);await flush();const req=app.requests[0];
 await req.options.onopen(opened());assert.throws(()=>req.options.onerror(new Error('断线')),/断线/);
 assert.equal(app.state.debugContext.runId,'run-fixture-nine');assert.equal(app.manager.flowResult.status,'running');app.state.debugContext.detach();req.resolve();await flush();
});
test('真实WAITING终止当前读取而不完成；原resume用新signal和首次reply条件',async()=>{
 const app=application();app.start();app.build(0);await flush();const first=app.requests[0];await first.options.onopen(opened());
 first.options.onmessage({id:'run-fixture-nine:1',data:interrupt()});first.resolve();await flush();assert.equal(app.requests.length,1);assert.equal(app.manager.flowResult.status,'running');
 app.module.handleResumeChat('回答',app.get,app.set);await flush();assert.equal(app.requests.length,2);
 const second=app.requests[1],body=JSON.parse(second.options.body);assert.equal(second.url.endsWith('/workflow/resume'),true);assert.notEqual(second.options.signal,first.options.signal);
 assert.equal(body.expectedRevision,'0');assert.equal(body.eventId,'checkpoint-nine');assert.equal(body.runId,'run-fixture-nine');assert.equal(body.content,'回答');assert.equal(typeof body.replyKey,'string');
 app.state.debugContext.detach();second.resolve();await flush();
});
test('resume真实同Run响应头确认后清问答控件，继续运行时stop走普通cancel',async()=>{
 const app=application();app.start();app.build(0);await flush();const first=app.requests[0];await first.options.onopen(opened());first.options.onmessage({id:'run-fixture-nine:1',data:interrupt()});first.resolve();await flush();
 app.module.handleResumeChat('回答',app.get,app.set);await flush();const resumed=app.requests[1];await resumed.options.onopen(opened());assert.equal(app.state.interruptChat.interrupt,false);
 app.module.handleStopConversation(app.get);await flush();assert.equal(app.requests.find(req=>req.short).url.endsWith('/workflow/cancel'),true);
 app.state.debugContext.detach();resumed.resolve();app.requests.at(-1).resolve();await flush();
});
test('原审查错误帧展示原提示但不提前把未终结的Run设failed',async()=>{
 const app=application();app.start();app.build(0);await flush();const stream=app.requests[0];await stream.options.onopen(opened());
 stream.options.onmessage({id:'run-fixture-nine:1',data:frame(null,{code:21103,message:'原审查失败'})});assert.equal(app.manager.flowResult.status,'running');
 assert.equal(app.state.chatList.at(-1).content,'原审查失败');stream.options.onmessage({id:'run-fixture-nine:2',data:frame('stop',{code:500})});assert.equal(app.manager.flowResult.status,'failed');stream.resolve();await flush();
});
test('问答停止用独立JSON短请求补空content，ACK不清运行状态并同body续读',async()=>{
 const app=application();app.start();app.build(0);await flush();const first=app.requests[0];await first.options.onopen(opened());first.options.onmessage({id:'run-fixture-nine:1',data:interrupt()});first.resolve();await flush();
 app.module.handleStopConversation(app.get);await flush();const short=app.requests.find(req=>req.short);assert.equal(short.options.headers['X-PaiFlow-Reply-Only'],'true');
 assert.equal(JSON.parse(short.options.body).content,'');assert.notEqual(short.options.signal,first.options.signal);assert.equal(app.manager.flowResult.status,'running');
 const continued=app.requests.at(-1);assert.equal(continued.url.endsWith('/workflow/chat'),true);assert.equal(continued.options.body,first.options.body);assert.equal(continued.options.headers['Last-Event-ID'],'run-fixture-nine:1');
 app.state.debugContext.detach();continued.resolve();await flush();
});
test('普通停止只发真实run取消，ACK不伪已停，中心terminal才更新原结果',async()=>{
 const app=application();app.start();app.build(0);await flush();const first=app.requests[0];await first.options.onopen(opened());app.module.handleStopConversation(app.get);await flush();
 const short=app.requests.find(req=>req.short);assert.equal(short.url.endsWith('/workflow/cancel'),true);assert.equal(JSON.parse(short.options.body).runId,'run-fixture-nine');assert.equal(app.manager.flowResult.status,'running');
 const continued=app.requests.at(-1);await continued.options.onopen(opened());continued.options.onmessage({id:'run-fixture-nine:2',data:frame('stop',{code:499})});
 assert.equal(app.manager.flowResult.status,'failed');assert.equal(app.state.debugContext.terminal,true);first.resolve();continued.resolve();await flush();
});
test('原抽屉关闭只detach，未知EOF有限同Run重连仍不置success',async()=>{
 const app=application();app.start();app.build(0);await flush();const first=app.requests[0];await first.options.onopen(opened());first.resolve();await new Promise(resolve=>setTimeout(resolve,10));
 assert.equal(app.requests.length,2);assert.equal(app.requests[1].options.body,first.options.body);assert.equal(app.manager.flowResult.status,'running');
 app.module.clearData(()=>{},app.get);assert.equal(app.state.debugContext,undefined);assert.equal(app.requests.some(req=>req.short),false);app.requests[1].resolve();await flush();
});
test('未知EOF只有限同body/key读取，四次后仍running并反馈未确认',async()=>{
 const app=application();app.start();app.build(0);await flush();
 for(let i=0;i<4;i++) {const request=app.requests[i];await request.options.onopen(opened());request.resolve();await new Promise(resolve=>setTimeout(resolve,15));}
 assert.equal(app.requests.length,4);assert.equal(new Set(app.requests.map(req=>req.options.body)).size,1);
 assert.equal(app.manager.flowResult.status,'running');assert.equal(app.errors.length,1);app.state.debugContext.detach();
});
test('不带真实Run头的错误首响不创建续读；pagehide取消本地读而不发业务停止',async()=>{
 const app=application();app.start();app.build(0);await flush();const request=app.requests[0];
 await assert.rejects(()=>request.options.onopen({status:200,headers:new Headers({'Content-Type':'text/event-stream'})}));assert.equal(app.state.debugContext.runId,undefined);
 app.windowListeners.get('pagehide')();assert.equal(request.options.signal.aborted,true);assert.equal(app.requests.some(req=>req.short),false);request.resolve();await flush();
});

const javaModelFrames=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/java-model-original-frames.json'),'utf8'));
for(const captured of javaModelFrames.cases) {
 test(`实际Java模型原帧${captured.variant}：null保持运行，节点stop保全7与0 token且不终结整体`,async()=>{
  const nodes=[...new Set(captured.frames.map(({frame})=>frame.workflow_step.node.id))]
   .map(id=>({id,data:{status:'',debuggerResult:{}},position:{x:0,y:0}}));
  const app=application(false,nodes);app.start();app.build(0);await flush();
  const request=app.requests[0],context=app.state.debugContext;await request.options.onopen(opened());
  try {
   for(const {seq,frame:original} of captured.frames) {
    const page=structuredClone(original);page.id='run-fixture-nine';
    request.options.onmessage({id:`run-fixture-nine:${seq}`,data:JSON.stringify(page)});
    const node=nodes.find(node=>node.id===page.workflow_step.node.id),completed=page.workflow_step.node.finish_reason==='stop';
    assert.equal(node.data.status,completed?'success':'running');assert.equal(node.data.debuggerResult.done,completed);
    assert.equal(context.terminal,false);assert.equal(app.manager.flowResult.status,'running');
   }
   const model=nodes.find(node=>node.id.startsWith('spark-llm'));
   assert.equal(model.data.debuggerResult.answerContent,' 原文本');assert.equal(model.data.debuggerResult.reasoningContent,'思考');
   assert.equal(model.data.debuggerResult.rawOutput,'原文本');assert.equal(model.data.debuggerResult.tokenCost,7);
   for(const node of nodes.filter(node=>!node.id.startsWith('spark-llm'))) assert.equal(node.data.debuggerResult.tokenCost,0);
   // 整体终帧只测试原store的终态入口；实际中心终帧另由父同Run Codec检查证明。
   request.options.onmessage({id:'run-fixture-nine:16',data:frame('stop',{workflow_step:{node:{id:'flow_obj',finish_reason:'stop'}}})});
   assert.equal(context.terminal,true);assert.equal(app.manager.flowResult.status,'success');
   assert.equal(model.data.status,'success');assert.equal(model.data.debuggerResult.tokenCost,7);
  } finally {context.detach();request.resolve();await flush();}
 });
}
test('原完成帧缺usage仍无token，失败帧仍保原failed判定',async()=>{
 const original=javaModelFrames.cases[0].frames.find(({frame})=>frame.workflow_step.node.id.startsWith('spark-llm')&&frame.workflow_step.node.finish_reason==='stop').frame;
 const node={id:original.workflow_step.node.id,data:{status:'',debuggerResult:{}},position:{x:0,y:0}},app=application(false,[node]);
 app.start();app.build(0);await flush();const request=app.requests[0],context=app.state.debugContext;await request.options.onopen(opened());
 try {
  const failed=structuredClone(original);failed.id='run-fixture-nine';failed.code=500;failed.message='原失败';failed.workflow_step.node.usage=null;
  request.options.onmessage({id:'run-fixture-nine:1',data:JSON.stringify(failed)});
  assert.equal(node.data.status,'failed');assert.equal(node.data.debuggerResult.done,true);
  assert.equal(node.data.debuggerResult.failedReason,'原失败');assert.equal(node.data.debuggerResult.tokenCost,undefined);
  assert.equal(context.terminal,false);assert.equal(app.manager.flowResult.status,'running');
 } finally {context.detach();request.resolve();await flush();}
});
