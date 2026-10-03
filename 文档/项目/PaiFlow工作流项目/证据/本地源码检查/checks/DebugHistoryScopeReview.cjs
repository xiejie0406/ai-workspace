const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../..'),front=path.join(root,'frontend/paiflow-web'),ts=require(path.join(front,'node_modules/typescript'));
const sha=value=>crypto.createHash('sha256').update(value).digest('hex');
const flow=path.join(front,'src/components/workflow/store/flow-chat-function.ts'),card=path.join(front,'src/components/workflow/drawer/version-management/index.tsx'),helper=path.join(front,'src/utils/workflow-debug.ts');
const hashes=Object.fromEntries([flow,card,helper].map(file=>[path.relative(root,file),sha(fs.readFileSync(file))]));
function declaration(file,name,deep=false) {const text=fs.readFileSync(file,'utf8'),ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,file.endsWith('tsx')?ts.ScriptKind.TSX:ts.ScriptKind.TS);let selected;
 function visit(node){if(ts.isVariableStatement(node)&&node.declarationList.declarations.some(d=>d.name.getText(ast)===name))selected=node;if(deep&&!selected)ts.forEachChild(node,visit);}
 if(deep)visit(ast);else ast.statements.forEach(visit);assert.ok(selected,name);return selected.getText(ast);}
const exportsObject={};vm.runInNewContext(ts.transpileModule(fs.readFileSync(helper,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports:exportsObject,AbortController});
function probe() {
 const manager={currentFlow:{id:'9',flowId:'flow-nine'},historyVersion:true,historyVersionData:{id:'10',name:'v1',data:'old-raw'}},space={spaceId:'',spaceType:'personal',enterpriseId:''};
 const subscribers=new Set();let graph='old-raw';
 const sandbox={module:{exports:{}},getAuthorization:()=> 'Bearer fixture',useSpaceStore:{getState:()=>space,subscribe:()=>()=>{}},useFlowsManager:{getState:()=>manager,subscribe:fn=>{subscribers.add(fn);return()=>subscribers.delete(fn);}},
  window:{addEventListener:()=>{},removeEventListener:()=>{}},useMemoizedFn:fn=>fn,currentFlow:manager.currentFlow,versionList:[manager.historyVersionData,{id:'11',name:'v2',data:'new-raw'}],
  setHistoryVersion:value=>{manager.historyVersion=value;subscribers.forEach(fn=>fn());},setHistoryVersionData:value=>{manager.historyVersionData=value;subscribers.forEach(fn=>fn());},
  handleSetNodesAndEdges:value=>graph=value,setIsallowEdit:()=>{},setSelectedCardId:()=>{},setTimeout:()=>0,setUpdateNodeInputData:()=>{}};
 const code=[declaration(flow,'debugScope'),declaration(flow,'watchDebugContext'),declaration(card,'handleCardClick',true)].join('\n')+'\nmodule.exports={debugScope,watchDebugContext,handleCardClick};';
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,sandbox);
 const stamp=sandbox.module.exports.debugScope();const receipt={workflowVersionId:'51',workflowId:'9',flowId:'flow-nine',mode:'comparison',comparisonId:'comparison-fixture',buildRequestKey:'build-fixture',snapshotRevision:'4',dslHash:'a'.repeat(64),engineProfile:'python',executionCodec:'paiflow.execution.v3'};
 const context=new exportsObject.WorkflowDebugContext(receipt,stamp,sandbox.module.exports.debugScope,'request-fixture',{flow_id:'flow-nine',inputs:{},chatId:'chat',version:'comparison-fixture',promptDebugger:true},()=> 'reply-fixture');sandbox.module.exports.watchDebugContext(context);
 manager.historyVersionData={...manager.historyVersionData,name:'same card metadata'};subscribers.forEach(fn=>fn());const sameCardAlive=context.alive();
 sandbox.module.exports.handleCardClick('11');const current=sandbox.module.exports.debugScope();return {historyBefore:true,historyAfter:manager.historyVersion,selectedIdAfter:manager.historyVersionData.id,graphAfter:graph,sameCardAlive,stampChanged:stamp!==current,oldContextAlive:context.alive(),oldSignalAborted:context.controller.signal.aborted};
}
const result=probe();const output=path.join(__dirname,process.argv[2]);assert.ok(process.argv[2]);assert.equal(fs.existsSync(output),false);
const after=Object.fromEntries(Object.keys(hashes).map(relative=>[relative,sha(fs.readFileSync(path.join(root,relative)))]));assert.deepEqual(after,hashes);
fs.writeFileSync(output,JSON.stringify({checkedAt:new Date().toISOString(),actualOriginalCardHandlerAst:true,currentScopeAst:true,result,sourceHashesBefore:hashes,sourceHashesAfter:after,sourceWindowChanges:[],scope:'实际原卡片回调与当前scope/watch在内存VM；store/setNodes和window用明确spy，不计页面或HTTP验收'},null,2)+'\n');console.log(JSON.stringify({...result,sha256:sha(fs.readFileSync(output))}));
