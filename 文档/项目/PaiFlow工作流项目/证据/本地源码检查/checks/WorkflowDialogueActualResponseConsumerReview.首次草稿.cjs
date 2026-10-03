// 冻结的真实 H2/MockMVC 回执交给原/current AST 消费；没有页面、网络或业务写入。
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../../..'),modules=path.join(root,'frontend/paiflow-web/node_modules');
const ts=require(path.join(modules,'typescript')),React=require(path.join(modules,'react')),SSR=require(path.join(modules,'react-dom/server'));
const directory='文档/项目/PaiFlow工作流项目/证据/本地源码检查';
const artifactPath=`${directory}/调试显示历史实际MVC响应.json`,budgetPath=`${directory}/调试显示历史实际大答案预算.json`;
const productionGatePath=`${directory}/checks/调试显示历史显示预算修正后源码冻结输入.json`;
const mavenGatePath=`${directory}/checks/调试显示历史八类定向检查冻结输入.json`;
const proofPath=path.join(__dirname,'调试显示历史实际MVC原React独立消费核对.json');
assert.equal(fs.existsSync(proofPath),false,'已交付证据不得覆盖');
const digest=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const read=p=>fs.readFileSync(path.join(root,p)),hash=p=>digest(read(p));
function utf8(bytes){return new TextDecoder('utf-8',{fatal:true}).decode(bytes);}
function json(p){return JSON.parse(utf8(read(p)));}
function normalized(value){return JSON.parse(JSON.stringify(value));}
assert.equal(hash(artifactPath),'5881c427a84ac28afff9c2544db02bac8fa44dae4bf2a9bbe29760f98bfb5b19');
assert.equal(hash(budgetPath),'d243b52b2902e2e26214fa57c6e3af2e498ea0cac4a21ea870993a1f87ada8e5');
assert.equal(hash(productionGatePath),'7e7b2d2350dab717ecc6a08b8db1b67b42bab867121def8bf4609aa06542f44d');
assert.equal(hash(mavenGatePath),'c8f3102796aeb9345ab0848dd8d596d4b1f8afaf5cbeb8dc067c0ced1665a234');
const artifact=json(artifactPath),budget=json(budgetPath),productionGate=json(productionGatePath),mavenGate=json(mavenGatePath);
for(const source of [...productionGate.inputs,...mavenGate.inputs])assert.equal(hash(source.path),source.sha256,source.path);
const original='参考项目/PaiFlow-main/console/frontend/src/components/workflow',current='frontend/paiflow-web/src/components/workflow';
const flowFiles=[`${original}/store/flow-chat-function.ts`,`${current}/store/flow-chat-function.ts`];
const contentFiles=[`${original}/drawer/chat-debugger/components/chat-content.tsx`,`${current}/drawer/chat-debugger/components/chat-content.tsx`];
const checkerPath=path.relative(root,__filename).replaceAll('\\','/');
const sources=[...new Set([artifactPath,budgetPath,productionGatePath,mavenGatePath,...productionGate.inputs.map(s=>s.path),...mavenGate.inputs.map(s=>s.path),...flowFiles,...contentFiles,checkerPath])];
const before=Object.fromEntries(sources.map(p=>[p,hash(p)]));
function wire(base64,expected,recordedHash){
 assert.equal(typeof base64,'string');const bytes=Buffer.from(base64,'base64');assert.equal(bytes.toString('base64'),base64,'严格 base64 规范');
 const value=JSON.parse(utf8(bytes));assert.deepEqual(value,expected);
 const sha256=digest(bytes);if(recordedHash)assert.equal(sha256,recordedHash);
 return {utf8Bytes:bytes.length,sha256,base64Canonical:true,utf8Strict:true,jsonEqualsRecordedObject:true,recordedSha256Verified:!!recordedHash};
}
const wires={list:wire(artifact.bodyUTF8Base64,artifact.listResponse,artifact.bodySha256),save:wire(artifact.saveResponseUTF8Base64,artifact.actualSaveReply),nullableCases:artifact.nullFalsySavedCases.map(item=>({request:wire(item.requestUTF8Base64,item.requestBody),response:wire(item.responseUTF8Base64,item.saveResponse)}))};
assert.deepEqual(artifact.body,artifact.listResponse);assert.equal(artifact.actualStatus,200);assert.equal(artifact.actualContentType,'application/json');
assert.deepEqual(artifact.actualHeaders,{'Content-Type':['application/json']});
const fields=['id','uid','workflowId','question','answer','data','sid','questionItem','answerItem','createTime','deleted','type','chatId'];
const eightFields=['chatId','type','workflowId','sid','questionItem','answerItem','question','answer'];
assert.deepEqual(Object.keys(artifact.actualRequestBody),eightFields);
for(const reply of [artifact.listResponse,artifact.actualSaveReply,artifact.actualClearReply,artifact.clearAgainResponse,artifact.listAfterClearResponse,...artifact.nullFalsySavedCases.map(item=>item.saveResponse)])assert.equal(reply.code,0);
assert.equal(artifact.listResponse.data.length,3);assert.deepEqual(artifact.listResponse.data.map(row=>row.id),[3,2,1]);
for(const row of artifact.listResponse.data){
 assert.deepEqual(Object.keys(row).sort(),[...fields].sort());assert.equal(typeof row.id,'number');assert.equal(typeof row.workflowId,'number');
 assert.equal(row.workflowId,artifact.workflowId);assert.equal(row.uid,`ruoyi:${artifact.actualBinding.ownerUserId}`);assert.equal(row.sid,artifact.runId);
 assert.equal(row.deleted,false);assert.equal(row.type,1);assert.ok(Number.isFinite(Date.parse(row.createTime)));assert.equal(row.data,null);
}
assert.deepEqual(artifact.actualBinding,{runId:artifact.runId,workflowId:artifact.workflowId,workflowVersionId:artifact.workflowVersionId,spaceId:1,ownerUserId:42,eventWatermark:0});
assert.deepEqual(artifact.runtimeSnapshots.beforeDisplay,artifact.runtimeSnapshots.afterSaveAndList);assert.deepEqual(artifact.runtimeSnapshots.beforeDisplay,artifact.runtimeSnapshots.afterClear);
assert.equal(artifact.runtimeTruthUnchanged,true);assert.equal(artifact.businessVerified,false);
assert.equal(artifact.actualSaveReply.data,null);assert.equal(artifact.actualClearReply.data,3);assert.equal(artifact.clearAgainResponse.data,0);
assert.equal(Number.isInteger(artifact.actualClearReply.data),true);assert.equal(Number.isInteger(artifact.clearAgainResponse.data),true);assert.deepEqual(artifact.listAfterClearResponse.data,[]);
const actualRows=artifact.listResponse.data;
assert.equal(actualRows[0].question,null);assert.equal(actualRows[0].answer,null);
assert.equal(actualRows[1].question,'null');assert.deepEqual(JSON.parse(actualRows[1].answer),{messageContent:0,reasoningContent:false,content:null,option:false,future:{nil:null}});
assert.equal(actualRows[2].question,artifact.actualRequestBody.question);assert.equal(actualRows[2].answer,artifact.actualRequestBody.answer);
assert.equal(actualRows[2].answerItem,artifact.actualRequestBody.answerItem.slice(1,-1),'原外双引号去除不做 JSON 解码');
function declaration(file,key){
 const text=utf8(read(file)),ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let node;
 function walk(n){if(ts.isVariableDeclaration(n)&&n.name.getText(ast)===key)node=n;ts.forEachChild(n,walk);}walk(ast);assert.ok(node,key);
 return key==='renderInputElement'?node.initializer.arguments[0].getText(ast):node.parent.parent.getText(ast).replace(/^export\s+/,'');
}
function create(file,rows=actualRows,clearCount=3,history=false){
 const state={chatInfoRef:{question:[],answer:{messageContent:'',reasoningContent:'',content:''},answerItem:''},chatIdRef:'private_group_old',chatList:[],messageNodeTextQueue:'',endNodeReasoningTextQueue:'',endNodeTextQueue:'',wsMessageStatus:'start'},calls=[];
 let serial=0,resolveSave;
 state.setDeleteAllModal=value=>calls.push({setter:'deleteAllModal',value});state.setChatList=value=>{state.chatList=typeof value==='function'?value(state.chatList):value;calls.push({setter:'chatList'});};state.setInterruptChat=value=>{state.interruptChat=structuredClone(value);calls.push({setter:'interruptChat'});};
 const sandbox={module:{exports:{}},uuid:()=>`private_generated_${++serial}`,cloneDeep:structuredClone,
  useFlowsManager:{getState:()=>({currentFlow:{id:artifact.actualRequestBody.workflowId,flowId:'private-display-flow'},historyVersion:history,setCanvasesDisabled:value=>calls.push({setter:'canvasesDisabled',value})})},
  getDialogueAPI:(id,type)=>{calls.push({api:'list',id,type});return Promise.resolve(structuredClone(rows));},
  saveDialogueAPI:body=>{calls.push({api:'save',body:normalized(body)});return new Promise(resolve=>{resolveSave=resolve;});},
  workflowDialogClear:(id,type)=>{calls.push({api:'clear',id,type});return Promise.resolve(clearCount);},
  handleNodeStatusChange:()=>calls.push({nodeStatusSpy:true})};
 const names=['initChatInfo','initInterruptChat','getDialogues','handleSaveDialogue','deleteAllChat'];
 vm.runInNewContext(ts.transpileModule(names.map(n=>declaration(file,n)).join('\n')+'\nmodule.exports={getDialogues,handleSaveDialogue,deleteAllChat};',{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,sandbox);
 return {state,calls,functions:sandbox.module.exports,get:()=>state,set:patch=>Object.assign(state,patch),resolveSave:value=>resolveSave(value)};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function leaves(flowFile,contentFile,display){
 const warnings=[],sandbox={module:{exports:{}},React,console:{error:(...args)=>warnings.push(args.map(value=>String(value)).join(' '))},
  icons:{chatUser:'private-no-fetch'},typeList:new Map(),
  MarkdownRender:({content})=>React.createElement('private-markdown',null,typeof content==='string'?content:null),
  Image:()=>React.createElement('private-image'),JSONPretty:({src})=>React.createElement('private-json',null,JSON.stringify(src)),
  isJSON:value=>{try{JSON.parse(value);return true;}catch{return false;}}};
 const code=declaration(contentFile,'MessageAsk')+'\n'+declaration(contentFile,'MessageReplyContent')+'\nconst privateRenderer='+declaration(contentFile,'renderInputElement')+';\nmodule.exports={MessageAsk,MessageReplyContent,privateRenderer};';
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.React}}).outputText,sandbox);
 const calls=[];for(const [index,chat] of display.entries()){
  if(chat.type==='divider')continue;
  const component=chat.type==='ask'?sandbox.module.exports.MessageAsk:sandbox.module.exports.MessageReplyContent;
  const html=SSR.renderToStaticMarkup(React.createElement(component,{chat,renderInputElement:sandbox.module.exports.privateRenderer,debuggering:false,index,chatList:display,handleResumeChat:()=>{throw new Error('SSR 不执行点击或恢复');}}));
  calls.push({type:chat.type,id:chat.id??null,htmlUTF8Bytes:Buffer.byteLength(html),htmlSha256:digest(Buffer.from(html)),containsPrivateMarkdown:html.includes('private-markdown')});
 }
 assert.equal(calls.length,6);return {realLeafSSRCount:calls.length,leaves:calls,stubs:['MarkdownRender','Image','JSONPretty','icons','typeList','handleResumeChat（禁止调用）'],warnings};
}
async function evaluate(flowFile,contentFile){
 const cases=[];
 const list=create(flowFile);list.functions.getDialogues(String(artifact.workflowId),list.set,true);await flush();const display=normalized(list.state.chatList);
 assert.deepEqual(display.map(row=>row.type),['ask','answer','divider','ask','answer','divider','ask','answer','divider']);
 assert.deepEqual(display.filter(row=>row.type==='answer').map(row=>row.id),[1,2,3]);
 assert.equal(new Set(display.filter(row=>row.type==='answer').map(row=>row.sid)).size,1);
 assert.deepEqual(display[0].inputs,JSON.parse(artifact.actualRequestBody.question));
 assert.equal(display[0].inputs[0].future,null);assert.equal(display[0].inputs[0].zero,0);assert.equal(display[0].inputs[0].required,false);
 assert.equal(display[1].content,JSON.parse(artifact.actualRequestBody.answer).content);assert.equal(display[1].option,null);
 assert.equal(display[3].inputs,null);assert.equal(display[4].content,'');assert.equal(display[4].messageContent,'');assert.equal(display[4].reasoningContent,'');assert.equal(display[4].option,false);
 assert.equal(display[6].inputs,null);assert.equal(display[7].content,'');assert.equal(display[7].option,undefined);
 const leaf=leaves(flowFile,contentFile,display);cases.push({id:'actual-descending-three-rows-and-leaf-SSR',apiCalls:normalized(list.calls),display,leaf});
 const save=create(flowFile);save.state.chatIdRef=artifact.actualRequestBody.chatId;save.state.chatInfoRef={sid:artifact.actualRequestBody.sid,question:JSON.parse(artifact.actualRequestBody.question),answer:JSON.parse(artifact.actualRequestBody.answer),answerItem:JSON.parse(artifact.actualRequestBody.answerItem)};
 save.functions.handleSaveDialogue(save.get,save.set);const body=save.calls.find(call=>call.api==='save').body;
 assert.deepEqual(body,artifact.actualRequestBody);assert.deepEqual(Object.keys(body),eightFields);assert.equal(save.state.chatInfoRef.sid,undefined);assert.equal(save.calls.filter(call=>call.api==='list').length,0);
 save.resolveSave(artifact.actualSaveReply.data);await flush();assert.equal(save.calls.filter(call=>call.api==='save').length,1);assert.equal(save.calls.filter(call=>call.api==='list').length,1);assert.deepEqual(normalized(save.state.chatList),display);
 cases.push({id:'actual-first-eight-field-request-from-original-save-AST',requestEqualsActual:true,requestUTF8Bytes:Buffer.byteLength(JSON.stringify(body)),requestSha256:digest(Buffer.from(JSON.stringify(body))),saveResponseData:null,resetBeforeAck:true,listReloadAfterAck:true});
 for(const [label,count] of [['first',artifact.actualClearReply.data],['again',artifact.clearAgainResponse.data]]){
  const clear=create(flowFile,[],count);clear.state.chatList=structuredClone(display);clear.functions.deleteAllChat(clear.get);await flush();
  assert.deepEqual(normalized(clear.state.chatList),[]);assert.notEqual(clear.state.chatIdRef,'private_group_old');assert.deepEqual(normalized(clear.calls.filter(call=>call.api)),[{api:'clear',id:artifact.actualRequestBody.workflowId,type:1}]);
  cases.push({id:`actual-clear-${label}-integer-${count}`,responseData:count,displayEmpty:true,newLocalChatGroup:clear.state.chatIdRef,calls:normalized(clear.calls),runOrCheckpointOrSaveCalls:0});
 }
 const history=create(flowFile,[],0,true);history.functions.deleteAllChat(history.get);await flush();assert.equal(history.calls.filter(call=>call.setter==='canvasesDisabled').length,0);
 cases.push({id:'same-clear-on-history-does-not-unlock',calls:normalized(history.calls)});
 const empty=create(flowFile,artifact.listAfterClearResponse.data);empty.functions.getDialogues(String(artifact.workflowId),empty.set,true);await flush();assert.deepEqual(normalized(empty.state.chatList),[]);
 cases.push({id:'actual-after-clear-empty-list',display:[]});
 return cases;
}
(async()=>{
 const originalResults=await evaluate(flowFiles[0],contentFiles[0]),currentResults=await evaluate(flowFiles[1],contentFiles[1]);assert.deepEqual(currentResults,originalResults);
 for(const name of ['runResultSha256','originalEightFieldDisplaySha256','storedAnswerSha256','storedAnswerItemSha256'])assert.match(budget[name],/^[a-f0-9]{64}$/);
 assert.ok(budget.runResultUTF8Bytes<2097152);assert.ok(budget.originalEightFieldDisplayUTF8Bytes>2097152&&budget.originalEightFieldDisplayUTF8Bytes<20971520);
 assert.ok(budget.wholeBodyRejectedUTF8Bytes>20971520);assert.ok(budget.fieldRejectedUTF8Bytes>16777215);assert.ok(budget.fieldRejectedWholeBodyUTF8Bytes<20971520);
 assert.equal(budget.runResultLimitUnchanged,2097152);assert.equal(budget.controlLimitUnchanged,16384);assert.equal(budget.runtimeTruthUnchangedByDisplay,true);assert.equal(budget.storedOriginalAnswerEqual,true);assert.equal(budget.storedOriginalAnswerItemAfterOuterQuoteRemovalEqual,true);assert.equal(budget.businessVerified,false);
 const after=Object.fromEntries(sources.map(p=>[p,hash(p)]));assert.deepEqual(after,before);
 const proof={checkedAt:new Date().toISOString(),scope:'冻结真实H2/Mapper/MockMVC产物→原/current getDialogues/handleSaveDialogue/deleteAllChat AST 与真实 React SSR 叶子独立消费',
  sourceArtifacts:{actualMVC:{path:artifactPath,sha256:hash(artifactPath),bytes:read(artifactPath).length},largeAnswer:{path:budgetPath,sha256:hash(budgetPath),bytes:read(budgetPath).length}},
  gates:{production:{path:productionGatePath,sha256:hash(productionGatePath),inputs:productionGate.inputs.length},maven:{path:mavenGatePath,sha256:hash(mavenGatePath),inputs:mavenGate.inputs.length}},
  checker:{path:checkerPath,sha256:hash(checkerPath)},sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],
  wireChecks:wires,actualRecordedListResponse:{status:artifact.actualStatus,contentType:artifact.actualContentType,headers:artifact.actualHeaders},
  rows:{count:3,fieldCountEach:13,exactFields:fields,descendingIds:[3,2,1],numericIdAndWorkflowId:true,nullableFieldsPresent:true,noInternalRunBindingInView:true,dateParseable:true,clearIntegerCounts:[3,0],afterClear:[]},
  runtimeSnapshotDeepEqual:{saveAndList:true,clear:true},actualBinding:artifact.actualBinding,
  originalResults,currentResultsEqualOriginal:true,astCasesEach:originalResults.length,realLeafSSREach:6,
  largeAnswerRecordedOnly:{...budget,omittedBodiesRecomputed:false,largePayloadGeneratedOrReplayed:false},
  limitations:['本检查未运行Maven/H2/Controller；实际数据库/MockMVC回执来自root已执行的冻结产物，9合同测试由root执行97项定向检查，本检查不冒称独立重跑。',
   '仅list捕获了actualStatus/Content-Type，save及两nullablecase有原wireBase64，clear/count/清后空列表只有解析回执；不补未记录HTTP元数据。',
   'API/store为明确内存spies，仅将code0.data交给实际原/current消费者。React/ReactDOMServer真实叶子SSR，Markdown/Image/JSONPretty/icons/typeList为限定替身；无页面挂载、用户点击、浏览器、网络、上传、Provider、正式App或业务验收。',
   '仅首实际八字段请求由原/current handleSaveDialogue重建同形。两个outer-null/falsy request是H2 fixture实际保存形状，用于list消费者；不宣称原handleSave产生outer-null或null answerItem。',
   '大答案产物只提供长度/hash/绑定与实际断言结果。独立校验其文件SHA、记录格式与预算关系，无法在省略正文情况下重算各正文hash；未回放大payload。',
   '同Run三显示段按不同chatId保存，不表示Runtime/App/history或变量真相改变。字段null/false/0保全后，原UI消费的文本||空串行为如实保留。',
   '本检查源窗口仅本报告所列文件，不能代替root227编译窗口，且不更新接口覆盖索引。'],
  mainOrTestSourceModified:false,targetWrites:false,realDbWrites:false,businessVerified:false};
 fs.writeFileSync(proofPath,JSON.stringify(proof,null,2)+'\n');
 console.log(JSON.stringify({proof:path.relative(root,proofPath).replaceAll('\\','/'),sha256:digest(fs.readFileSync(proofPath)),sourceCount:sources.length,sourceWindowChanges:0,astCasesEach:6,realLeafSSREach:6,currentEqualsOriginal:true,listWireSha256:wires.list.sha256,largePayloadGeneratedOrReplayed:false,businessVerified:false}));
})().catch(error=>{console.error(error);process.exitCode=1;});
