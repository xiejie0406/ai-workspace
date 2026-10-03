const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../..'),front=path.join(root,'frontend/paiflow-web'),ts=require(path.join(front,'node_modules/typescript'));
const sha=value=>crypto.createHash('sha256').update(value).digest('hex');
const flow=path.join(front,'src/components/workflow/store/flow-chat-function.ts'),card=path.join(front,'src/components/workflow/drawer/version-management/index.tsx'),drawer=path.join(front,'src/components/workflow/drawer/chat-debugger/index.tsx'),helper=path.join(front,'src/utils/workflow-debug.ts');
const hashes=Object.fromEntries([flow,card,drawer,helper].map(file=>[path.relative(root,file),sha(fs.readFileSync(file))]));
function ast(file){return ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,file.endsWith('tsx')?ts.ScriptKind.TSX:ts.ScriptKind.TS);}
function declaration(file,name){const source=ast(file);let selected;function visit(node){if(ts.isVariableStatement(node)&&node.declarationList.declarations.some(d=>d.name.getText(source)===name))selected=node;if(!selected)ts.forEachChild(node,visit);}visit(source);assert.ok(selected,name);return selected.getText(source);}
function effect(text){const source=ast(drawer);let selected;function visit(node){if(ts.isCallExpression(node)&&node.expression.getText(source)==='useEffect'&&node.arguments[0]?.getText(source).includes(text))selected=node.arguments[0];if(!selected)ts.forEachChild(node,visit);}visit(source);assert.ok(selected,text);return selected.getText(source);}
const exportsObject={};vm.runInNewContext(ts.transpileModule(fs.readFileSync(helper,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports:exportsObject,AbortController});
const receipt={workflowVersionId:'51',workflowId:'9',flowId:'flow-nine',mode:'comparison',comparisonId:'comparison-fixture',buildRequestKey:'build-fixture',snapshotRevision:'4',dslHash:'a'.repeat(64),engineProfile:'python',executionCodec:'paiflow.execution.v3'};
function fixture(){
 const calls={saved:0,deleted:0,end:0,stop:0,result:0},subscribers=new Set();let graph='old-raw';
 const manager={currentFlow:{id:'9',flowId:'flow-nine'},historyVersion:true,historyVersionData:{id:'10',name:'v1',data:'old-raw'},flowResult:{status:'running',timeCost:'',totalTokens:''},disabled:true,
  setFlowResult(value){calls.result++;manager.flowResult=value;subscribers.forEach(fn=>fn());},setShowNodeList(){},setCanvasesDisabled(value){manager.disabled=value;}};
 const space={spaceId:'',spaceType:'personal',enterpriseId:''};const state={debuggering:true,buildPassRef:true,versionId:'comparison-fixture',wsMessageStatus:'start',interruptChat:{interrupt:true,eventId:'cp-fixture',nodeId:'question-answer::q'},messageNodeTextQueue:'old queued',endNodeReasoningTextQueue:'old reasoning',endNodeTextQueue:'old text'};
 const get=()=>state,set=value=>Object.assign(state,value);
 const sandbox={module:{exports:{}},getAuthorization:()=> 'Bearer fixture',useSpaceStore:{getState:()=>space,subscribe:()=>()=>{}},useFlowsManager:{getState:()=>manager,subscribe:fn=>{subscribers.add(fn);return()=>subscribers.delete(fn);}},
  window:{addEventListener:()=>{},removeEventListener:()=>{}},useMemoizedFn:fn=>fn,currentFlow:manager.currentFlow,versionList:[manager.historyVersionData,{id:'11',name:'v2',data:'new-raw'}],
  setHistoryVersion:value=>{manager.historyVersion=value;subscribers.forEach(fn=>fn());},setHistoryVersionData:value=>{manager.historyVersionData=value;subscribers.forEach(fn=>fn());},
  handleSetNodesAndEdges:value=>graph=value,setIsallowEdit:()=>{},setSelectedCardId:()=>{},setTimeout:()=>0,setUpdateNodeInputData:()=>{},workflowDeleteComparisons:()=>calls.deleted++,
  handleSaveDialogue:()=>calls.saved++,handleMessageEnd:()=>calls.end++,handleStopConversation:()=>calls.stop++,isMounted:{current:true},
  setWsMessageStatus:value=>state.wsMessageStatus=value,setInterruptChat:value=>state.interruptChat=value,setNodes:()=>{},setEdges:()=>{},cloneDeep:structuredClone};
 for(const name of ['debuggering','buildPassRef','controllerRef','flowResult'])Object.defineProperty(sandbox,name,{get:()=>name==='flowResult'?manager.flowResult:state[name]});
 const release=fs.readFileSync(flow,'utf8').includes('const releaseDebugView')?[declaration(flow,'releaseDebugView')]:[];
 const code=[declaration(flow,'initInterruptChat'),declaration(flow,'debugScope'),...release,declaration(flow,'watchDebugContext'),declaration(flow,'handleWorkflowDeleteComparisons'),declaration(card,'handleCardClick')].join('\n')+
  `\nconst saveEffect=${effect('!debuggering && buildPassRef && handleSaveDialogue')};const resetEffect=${effect('if (!flowResult?.status)')};\nmodule.exports={debugScope,watchDebugContext,handleCardClick,saveEffect,resetEffect,handleWorkflowDeleteComparisons};`;
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,sandbox);
 const methods=sandbox.module.exports;sandbox.handleWorkflowDeleteComparisons=()=>methods.handleWorkflowDeleteComparisons(get);
 const context=new exportsObject.WorkflowDebugContext(receipt,methods.debugScope(),methods.debugScope,'request-fixture',{flow_id:'flow-nine',inputs:{},chatId:'chat',version:'comparison-fixture',promptDebugger:true},()=> 'reply-fixture');
 state.debugContext=context;state.controllerRef=context.controller;methods.watchDebugContext(context,get,set);
 return {calls,state,manager,methods,context,graph:()=>graph};
}
const cross=fixture();cross.methods.handleCardClick('11');cross.methods.saveEffect();cross.methods.resetEffect();
const terminal=fixture();terminal.context.open({status:200,headers:new Headers({'Content-Type':'text/event-stream','X-PaiFlow-Run-Id':'run-fixture','X-PaiFlow-Workflow-Version-Id':'51'})});
terminal.context.frame('run-fixture:1',JSON.stringify({code:0,choices:[{finish_reason:'stop',delta:{content:null}}]}));
const older=fixture();const newContext={marker:'new-context'};older.state.debugContext=newContext;older.context.detach();
const result={crossCard:{selectedId:cross.manager.historyVersionData.id,graph:cross.graph(),signalAborted:cross.context.controller.signal.aborted,debuggering:cross.state.debuggering,buildPassRef:cross.state.buildPassRef,
 interrupt:cross.state.interruptChat.interrupt,viewStatus:cross.manager.flowResult.status,canvasReadonly:cross.manager.disabled,calls:cross.calls},
 terminalCleanup:{terminal:terminal.context.terminal,debuggering:terminal.state.debuggering,buildPassRef:terminal.state.buildPassRef,viewStatus:terminal.manager.flowResult.status,calls:terminal.calls},
 olderCleanup:{newContextKept:older.state.debugContext===newContext,debuggering:older.state.debuggering,viewStatus:older.manager.flowResult.status,calls:older.calls}};
const passed=!result.crossCard.debuggering&&!result.crossCard.buildPassRef&&!result.crossCard.interrupt&&result.crossCard.viewStatus===''&&result.crossCard.canvasReadonly&&
 result.crossCard.calls.saved===0&&result.crossCard.calls.deleted===0&&result.crossCard.calls.end===0&&result.crossCard.calls.stop===0&&
 result.terminalCleanup.terminal&&result.terminalCleanup.debuggering&&result.terminalCleanup.buildPassRef&&result.terminalCleanup.calls.result===0&&
 result.olderCleanup.newContextKept&&result.olderCleanup.debuggering&&result.olderCleanup.calls.result===0;
const after=Object.fromEntries(Object.keys(hashes).map(relative=>[relative,sha(fs.readFileSync(path.join(root,relative)))]));assert.deepEqual(after,hashes);
const output=path.join(__dirname,process.argv[2]||'');assert.ok(process.argv[2]);assert.equal(fs.existsSync(output),false);
fs.writeFileSync(output,JSON.stringify({checkedAt:new Date().toISOString(),status:passed?'passed':'actual_counterexample',actualAst:{watch:true,card:true,drawerSaveEffect:true,drawerResetEffect:true,comparisonCleanup:true},result,sourceHashesBefore:hashes,sourceHashesAfter:after,sourceWindowChanges:[],scope:'实际原卡片/watch与原抽屉两个effect的内存VM；明确store/window/node更新spy，无页面/网络/中心取消或保存联机'},null,2)+'\n');
console.log(JSON.stringify({status:passed?'passed':'actual_counterexample',result,sha256:sha(fs.readFileSync(output))}));
