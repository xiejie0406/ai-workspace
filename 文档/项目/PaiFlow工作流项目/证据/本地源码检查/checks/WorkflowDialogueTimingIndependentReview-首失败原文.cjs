// 只读实际源码；私有AST/VM调用原effect回调，无React挂载、网络或业务写入。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../..'),front=path.join(root,'frontend/paiflow-web'),ts=require(path.join(front,'node_modules/typescript'));
const original='参考项目/PaiFlow-main/console/frontend/src/components/workflow',current='frontend/paiflow-web/src/components/workflow';
const toolkit='参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit';
const core='参考项目/PaiFlow-main/core/workflow',java='ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow';
const sources=[`${original}/store/flow-chat-function.ts`,`${current}/store/flow-chat-function.ts`,`${original}/drawer/chat-debugger/index.tsx`,`${current}/drawer/chat-debugger/index.tsx`,`${original}/store/use-chat-store.ts`,`${current}/store/use-chat-store.ts`,
 '参考项目/PaiFlow-main/console/frontend/src/services/flow.ts','frontend/paiflow-web/src/services/flow.ts',`${toolkit}/controller/workflow/WorkflowController.java`,`${toolkit}/service/workflow/WorkflowService.java`,`${toolkit}/mapper/workflow/WorkflowDialogMapper.java`,`${toolkit}/entity/table/workflow/WorkflowDialog.java`,
 '参考项目/PaiFlow-main/docker/PaiFlow/mysql/schema.sql',`${core}/api/v1/chat/debug.py`,`${core}/service/chat_service.py`,`${core}/engine/callbacks/callback_handler.py`,`${core}/extensions/otlp/trace/span.py`,`${java}/adapter/legacy/LegacyWorkflowStreamCodec.java`,`${java}/adapter/legacy/LegacyDebugBridgeService.java`,`${java}/domain/RunRecord.java`,`${java}/domain/WorkflowVersionRecord.java`,`${java}/service/WorkflowService.java`,
 `${original}/drawer/chat-debugger/components/chat-content.tsx`,`${original}/modal/feedback-dialog/index.tsx`,'frontend/paiflow-web/src/utils/workflow-debug.ts',
 '文档/项目/PaiFlow工作流项目/证据/本地源码检查/原问答SSE消费者黄金帧核对.json'];
const sha=data=>crypto.createHash('sha256').update(data).digest('hex'),read=name=>fs.readFileSync(path.join(root,name),'utf8'),hashes=()=>Object.fromEntries(sources.map(name=>[name,sha(fs.readFileSync(path.join(root,name)))]));
const before=hashes(),golden=JSON.parse(read(sources.at(-1)));
function ast(name){return ts.createSourceFile(name,read(name),ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);}
function decl(name,key){const tree=ast(name),node=tree.statements.find(node=>ts.isVariableStatement(node)&&node.declarationList.declarations.some(d=>d.name.getText(tree)===key));assert.ok(node,key);return node.getText(tree).replace(/^export\s+/,'');}
function effect(name,needle){const tree=ast(name),node=tree.statements.find(node=>ts.isVariableStatement(node)&&node.declarationList.declarations.some(d=>d.name.getText(tree)==='useChatDebuggerEffect'));assert.ok(node);const init=node.declarationList.declarations[0].initializer;
 const call=init.body.statements.find(s=>ts.isExpressionStatement(s)&&ts.isCallExpression(s.expression)&&s.expression.expression.getText(tree)==='useEffect'&&s.expression.arguments[0].getText(tree).includes(needle));assert.ok(call,needle);return call.expression.arguments[0].getText(tree);}
const functionNames=['initChatInfo','handleSaveDialogue','getDialogues','pushAskToChatList','pushAnswerToChatList','pushContentToAnswer','handleInterrupt','extractNodeInfo','handleMessage','setQueue','getTextQueueContent','getChatKey','isChatEnd'];
for(const key of functionNames)assert.equal(decl(sources[0],key).replaceAll('\r\n','\n'),decl(sources[1],key).replaceAll('\r\n','\n'),`${key} original/current body differs`);
const originalDrawer=sources[2],currentDrawer=sources[3];assert.equal(sha(read(originalDrawer)),sha(read(currentDrawer)),'本审查抽屉source应仍为原字节');
function probe(flow,drawer){
 const posts=[],promiseCallbacks=[],nodeStatuses=[],timerCallbacks=[],renderTransitions=[];let seq=0;
 const state={chatIdRef:'fixture-client-chat',chatInfoRef:null,chatList:[],messageNodeTextQueue:'',endNodeReasoningTextQueue:'',endNodeTextQueue:'',wsMessageStatus:'',debuggering:false,buildPassRef:true,interruptChat:{}};
 const get=()=>state,set=value=>Object.assign(state,value),sandbox={module:{exports:{}},cloneDeep:structuredClone,uuid:()=>`private-${++seq}`,useFlowsManager:{getState:()=>({currentFlow:{id:42,flowId:'fixture-flow'}})},
  saveDialogueAPI:params=>{posts.push(structuredClone(params));return{then:cb=>promiseCallbacks.push(cb)};},getDialogueAPI:()=>({then:()=>{}}),handleMoveToPosition:()=>{},handleNodeStatusChange:value=>nodeStatuses.push(value.nodeId),isJSON:value=>{try{JSON.parse(value);return true;}catch{return false;}},handleFlowStop:()=>{throw new Error('QA fixture不能走Run终态');},handleAuditFailed:()=>{throw new Error('QA fixture不含审核失败');},
  setInterval:cb=>{timerCallbacks.push(cb);return 1;},clearInterval:()=>{},isMounted:{current:false},debuggering:false,buildPassRef:true,chatInfoRef:null,interruptChat:{},
  getTextQueueContent:()=>sandbox.module.exports.getTextQueueContent(get),getChatKey:()=>sandbox.module.exports.getChatKey(get),isChatEnd:()=>sandbox.module.exports.isChatEnd(get),setQueue:n=>sandbox.module.exports.setQueue(n,get,set),
  setChatList:fn=>{state.chatList=fn(state.chatList);},setDebuggering:value=>{state.debuggering=value;renderTransitions.push(value);},handleSaveDialogue:()=>sandbox.module.exports.handleSaveDialogue(get,set)};
 state.setChatList=sandbox.setChatList;state.setInterruptChat=value=>{state.interruptChat=value;sandbox.interruptChat=value;};
 const code=functionNames.map(key=>decl(flow,key)).join('\n')+`\nmodule.exports={${functionNames.join(',')},timerEffectFactory:(getTextQueueContent,getChatKey,isChatEnd,setQueue,setChatList,setDebuggering,chatInfoRef,interruptChat,debuggering)=>${effect(drawer,'setInterval')},saveEffectFactory:(handleSaveDialogue,buildPassRef,debuggering,isMounted)=>${effect(drawer,'handleSaveDialogue')},reset:()=>cloneDeep(initChatInfo)};`;
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,sandbox);
 const api=sandbox.module.exports,saveEffect=()=>api.saveEffectFactory(sandbox.handleSaveDialogue,state.buildPassRef,state.debuggering,sandbox.isMounted)();state.chatInfoRef=api.reset();saveEffect();assert.equal(posts.length,0,'首个mounted effect不得保存');
 const question=[{name:'AGENT_USER_INPUT',type:'string',default:'fixture repeated reply',description:'fixture current round',required:true,validationSchema:null,errorMsg:'',originErrorMsg:''}];
 const stages=[];
 for(let i=0;i<2;i++){
  state.debuggering=true;sandbox.debuggering=true;state.wsMessageStatus='start';sandbox.chatInfoRef=state.chatInfoRef;
  api.pushAskToChatList(question,[],`question-answer::fixture`,get);api.pushAnswerToChatList(get);
  const frame=structuredClone(golden.frames.find(item=>item.name==='checkpoint.direct').frame);frame.id='fixture-run';frame.event_data.event_id=`fixture-checkpoint-${i+1}`;
  api.handleMessage([],[],{id:`fixture-run:${i+1}`,data:JSON.stringify(frame)},get,set);
  assert.equal(state.wsMessageStatus,'end');assert.equal(state.debuggering,true);assert.equal(state.chatInfoRef.sid,'fixture-run');assert.equal(posts.length,i);
  assert.equal(api.isChatEnd(get),false,'QA题面尚未排空时不能结束显示段');
  api.timerEffectFactory(sandbox.getTextQueueContent,sandbox.getChatKey,sandbox.isChatEnd,sandbox.setQueue,sandbox.setChatList,sandbox.setDebuggering,state.chatInfoRef,state.interruptChat,state.debuggering)();const tick=timerCallbacks.at(-1);let ticks=0;while(state.debuggering&&ticks++<20)tick();assert.equal(state.debuggering,false);assert.ok(ticks<20);
  sandbox.debuggering=false;saveEffect();assert.equal(posts.length,i+1);assert.equal(state.chatInfoRef.sid,undefined);assert.deepEqual(state.chatInfoRef.question,[]);assert.equal(promiseCallbacks.length,i+1,'save promise尚未完成时已经清空chatInfoRef');
  stages.push({checkpointId:frame.event_data.event_id,runTerminal:false,wsMessageStatus:state.wsMessageStatus,timerTicks:ticks,savedSid:posts[i].sid,savedQuestion:posts[i].question,savedAnswer:posts[i].answer,saveBeforePromiseAck:true});
 }
 assert.deepEqual(posts[0],posts[1],'同Run两个不同CP和相同显示内容应产生两次相同save请求，不能用bodyHash合并为一段');
 return{postCount:posts.length,stages,sameSid:true,identicalDistinctBoundaryPayload:true,allPostFields:Object.keys(posts[0]),nodeStatusCalls:nodeStatuses.length,renderTransitions,firstMountedSaveCount:0,pendingSaveAcks:promiseCallbacks.length,
  limitation:'实际AST effect回调由私有入口按依赖变化显式调用，非React render/StrictMode/浏览器时序执行；帧使用既有原DTO惰性fixture，两个CP ID为私有合成归属，不称正式中心CP'};
}
const originalResult=probe(sources[0],sources[2]),currentResult=probe(sources[1],sources[3]);assert.deepEqual(originalResult,currentResult);
const ddl=read('参考项目/PaiFlow-main/docker/PaiFlow/mysql/schema.sql').match(/CREATE TABLE `workflow_dialog` \([\s\S]*?\) ENGINE[^;]*;/)?.[0];assert.ok(ddl);assert.ok(!/UNIQUE/i.test(ddl));
const after=hashes();assert.deepEqual(before,after);
const ref=(p,a,b)=>({path:p,startLine:a,endLine:b}),facts=[
 {id:'D1',fact:'QA interrupt把wsMessageStatus置end，题面和option进入当前显示answer；并非Run完成',refs:[ref(sources[0],208,242),ref(sources[1],302,339)]},
 {id:'D2',fact:'原/current同字节drawer每10ms取10字符，队列清空且status=end后setDebuggering(false)；已mounted且buildPassRef的debuggering effect保存显示段',refs:[ref(sources[2],229,235),ref(sources[2],253,290),ref(sources[0],1062,1068)]},
 {id:'D3',fact:'save先构造8字段并POST，立即重置chatInfoRef，不等待ACK；success callback读取捕获currentFlow.id刷新列表',refs:[ref(sources[0],174,190),ref(sources[1],268,284)]},
 {id:'D4',fact:'resume先status=start/debuggering=true，pushAsk覆盖当前question，pushAnswer新增空answer；同Run可以继续下一显示段',refs:[ref(sources[0],516,550),ref(sources[1],613,647),ref(sources[0],125,150)]},
 {id:'D5',fact:'每个合法JSON帧data.id赋chatInfoRef.sid，SSE e.id未传入save；原Core resume覆response.id为本次Span sid，而中心投影绑定Run id',refs:[ref(sources[0],433,437),ref(sources[1],530,534),ref(`${core}/api/v1/chat/debug.py`,123,128),ref(`${core}/service/chat_service.py`,1211,1230),ref(`${core}/extensions/otlp/trace/span.py`,55,63),ref(`${java}/adapter/legacy/LegacyWorkflowStreamCodec.java`,61,68)]},
 {id:'D6',fact:'原saveDialog每POST直接insert，无按sid/bodyhash去重；原表仅PK id及workflow_id普通索引，sid nullable且无唯一约束',refs:[ref(`${toolkit}/service/workflow/WorkflowService.java`,1700,1711),ref(`${toolkit}/mapper/workflow/WorkflowDialogMapper.java`,1,9),ref('参考项目/PaiFlow-main/docker/PaiFlow/mysql/schema.sql',4464,4480)]},
 {id:'D7',fact:'原list按当前uid/workflow/type/notdeleted/createTime倒序最多10段；前端逐行unshift成chronological ask+answer并按chatId变化加divider，不按sid或Run聚合',refs:[ref(`${toolkit}/service/workflow/WorkflowService.java`,1714,1722),ref(sources[0],77,116)]},
 {id:'D8',fact:'原clear只按workflow/type soft delete并返回affected count，UI忽略count后刷新chatId/清显示与interrupt；原clear缺uid过滤不可移植成可清他人的权力',refs:[ref(`${toolkit}/service/workflow/WorkflowService.java`,2985,2989),ref(sources[0],936,946)]},
 {id:'D9',fact:'当前Bridge验证chatId字符串但未存正式Run列；Run只含owner/space/WF/version/input/事件等权威字段，不可从当前latestRun推导旧save的chatId',refs:[ref(`${java}/adapter/legacy/LegacyDebugBridgeService.java`,68,81),ref(`${java}/domain/RunRecord.java`,1,87)]},
 {id:'D10',fact:'调试版本已有debugOwner/debugAgent/真实固定version与当前绑定核对，可复用Run固定version授权；显示历史数据不授予App/Runtime history或memory能力',refs:[ref(`${java}/service/WorkflowService.java`,271,285),ref(`${java}/domain/WorkflowVersionRecord.java`,18,23)]},
 {id:'D11',fact:'此workflow drawer虽把sid/setSid传入MessageActions和FeedbackDialog，当前MessageActions仅copy、不调用setSid；FeedbackDialog提交sid字段。未找到此drawer的活动trace请求，不可把其他正式聊天hook的sid依赖混入',refs:[ref(`${original}/drawer/chat-debugger/components/chat-content.tsx`,215,249),ref(`${original}/drawer/chat-debugger/components/chat-content.tsx`,510,510),ref(`${original}/drawer/chat-debugger/components/chat-content.tsx`,751,757),ref(`${original}/modal/feedback-dialog/index.tsx`,148,169)]},
 {id:'D12',fact:'当前context只用SSE event.id的runId:seq和中心CP状态去重/续读，raw data.id由原消费保存为sid；保持两个标识职责',refs:[ref('frontend/paiflow-web/src/utils/workflow-debug.ts',116,156)]},
 {id:'D13',fact:'原Toolkit buildParams会把workflow_dialog用于multi-round history，这不是本轮显示历史授权；本轮应独立存显示片段，不自动装配到Worker history或模型messages',refs:[ref(`${toolkit}/service/workflow/WorkflowService.java`,3206,3246)]}
];
const result={checkedAt:new Date().toISOString(),scope:'原工作流调试显示历史时序独立只读审查；保持raw.id=Run及SSE runId:seq，不改变源/契约/旧证据',status:'有限源码与私有AST回调检查通过',sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],facts,privateProbe:{originalResult,currentResult,actualNetworkCalls:0,actualDatabaseCalls:0,reactMounted:false},
 recommendations:[
  '保原非幂等POST append语义，同Run可保存多个个人显示段；不要求Run terminal，不以unique(runId)/unique(sid)/bodyHash去重吞QA段，不声称request retry幂等。',
  'sid只解析和核对真实Run关系；当前主体/空间/WF/固定debug版本及真实Agent绑定fresh授权，客户端uid/id/createTime/deleted/data及metadata不赋予归属。Run绑定不等于将显示answer当正式结果。',
  'list和clear严格限定当前actor+已授权space/WF+type1，clear不cancel Run、不consume CP、不删events。排序使用createTime+稳定id，最多10原片段，不将sameRun聚合成一行。',
  'chatId是原显示分组标识，可作为受限display字段保留，但现Run未持久chatId，不能声称已独立证明它是正式会话；若未来要绑定，应在同次chat中心固定保存，不从latest猜测。',
  '四个JSON字符串及answerItem原trim规则保留为显示数据，限制字节/结构并输出真实nullable字段；原JSON.parse无异常兜底，不能返回损坏JSON成功。',
  '若未来要求save重试幂等，需另获明确段绑定协议；仅8字段无法区分相同内容合法两段与同一次retry。原resume新Span SID是来源事实，稳定段SID方案只是候选，本轮不改投影。'
 ],limitations:['React effect按私有入口显式调用，未证明真实render/批处理/StrictMode或DOM','未执行原Service/Mapper/DB或中心HTTP，没有本轮业务验收','原SQL仅提取DDL断言，未保留或引用INSERT数据','Runtime271及原QA/Provider算法未重跑'],productionModified:false,contractModified:false,targetWritten:false,businessVerified:false};
const out=path.join(__dirname,'工作流调试显示历史时序独立源码核对.json');assert.equal(fs.existsSync(out),false,'已冻结证据不可覆盖');fs.writeFileSync(out,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({sources:sources.length,facts:facts.length,actualVmPostsPerConsumer:2,sameRunSamePayloadDistinctCheckpoints:true,sourceChanges:0,sha256:sha(fs.readFileSync(out))}));
