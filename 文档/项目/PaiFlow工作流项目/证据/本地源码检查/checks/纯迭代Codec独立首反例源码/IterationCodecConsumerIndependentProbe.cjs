const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../../..'),front=path.join(root,'frontend/paiflow-web'),ts=require(path.join(front,'node_modules/typescript'));
const sha=value=>crypto.createHash('sha256').update(value).digest('hex');
const source=path.join(root,'参考项目/PaiFlow-main/console/frontend/src/components/workflow/store/flow-chat-function.ts');
const current=path.join(front,'src/components/workflow/store/flow-chat-function.ts');
const utils=path.join(root,'参考项目/PaiFlow-main/console/frontend/src/components/workflow/utils/reactflowUtils.ts');
const report=path.join(__dirname,'纯迭代原帧实际Codec独立复核.json');
const evidence=JSON.parse(fs.readFileSync(report,'utf8'));const artifact=evidence.originalAssignment.path,assignment=JSON.parse(fs.readFileSync(artifact,'utf8'));
const frames=evidence.observed.projectedFrames;assert.equal(frames.length,24);assert.equal(sha(fs.readFileSync(report)),'18285cb5d2ebf82b20f1672fd3b9644dd6b7914c01b94e9c51685aa40c5cc990');
const hashes=Object.fromEntries([source,current,utils,report,artifact].map(file=>[path.relative(root,file),sha(fs.readFileSync(file))]));
function declaration(file,name){const text=fs.readFileSync(file,'utf8'),ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
 const selected=ast.statements.find(node=>ts.isVariableStatement(node)&&node.declarationList.declarations.some(d=>d.name.getText(ast)===name));assert.ok(selected,name);return selected.getText(ast).replace(/^export\s+/,'');}
const names=['extractNodeInfo','handleAnswerContent','updateAnswerItem','handleRunningNode','handleFinishedNode','handleNodeStatusChange','handleMessage','pushContentToAnswer'];
function consume(file){
 const nodes=assignment.dsl.nodes.map(n=>({id:n.id,data:{...structuredClone(n.data),status:'',debuggerResult:{}},position:{x:0,y:0}}));
 const edges=assignment.dsl.edges.map(e=>({source:e.sourceNodeId,target:e.targetNodeId}));const writes=[],queues={messageNodeTextQueue:'',endNodeReasoningTextQueue:'',endNodeTextQueue:''};
 const state={...queues,chatInfoRef:{answerItem:'',answer:{}},preRunningNodeIds:[],currentFollowNodeId:''};let terminalCalls=0;
 const sandbox={module:{exports:{}},useFlowsManager:{getState:()=>({autonomousMode:true})},useFlowStore:{getState:()=>({setNode:(id,value)=>writes.push({id,status:value.data.status,result:structuredClone(value.data.debuggerResult)}),setEdges:fn=>fn(edges)})},
  cloneDeep:structuredClone,isJSON:value=>{try{JSON.parse(value);return true;}catch{return false;}},handleMoveToPosition:()=>{},handleFlowStop:()=>terminalCalls++,handleInterrupt:()=>{throw new Error('actual sample has no QA');},handleAuditFailed:()=>{throw new Error('actual sample has no audit failure');}};
 const code=[declaration(utils,'handleParmasOrder'),declaration(utils,'generateInputsAndOutputsOrder'),...names.map(name=>declaration(file,name))].join('\n')+'\nmodule.exports={handleMessage};';
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,sandbox);
 for(const frame of frames)sandbox.module.exports.handleMessage(nodes,edges,{data:JSON.stringify(frame.raw)},()=>state,()=>{});
 const byId=Object.fromEntries(nodes.map(node=>[node.id,{status:node.data.status,result:node.data.debuggerResult}]));
 // 转到宿主realm保留undefined/value，避免不同VM对象prototype使值相等断言误报。
 return structuredClone({writeCount:writes.length,joinerTransitions:writes.filter(item=>item.id==='text-joiner::j').map(item=>({status:item.status,output:item.result.output})),container:byId['iteration::i'],innerEnd:byId['iteration-node-end::ie'],
  finalEnd:byId['node-end::e'],terminalCalls,textQueue:state.endNodeTextQueue,rawPublicIterationFields:frames.some(frame=>Object.hasOwn(frame.raw,'iterationId')||Object.hasOwn(frame.raw.workflow_step.node,'iterationId'))});
}
const originalResult=consume(source),currentResult=consume(current);
assert.deepEqual(currentResult,originalResult);assert.equal(originalResult.writeCount,24);assert.deepEqual(originalResult.joinerTransitions.map(x=>x.status),['running','success','running','success','running','success']);
assert.deepEqual(originalResult.container.result.output,{value:['item=乙','item=甲','item=丙']});assert.deepEqual(originalResult.innerEnd.result.output,{value:'item=丙'});assert.equal(originalResult.terminalCalls,0);assert.equal(originalResult.rawPublicIterationFields,false);
const before=hashes,after=Object.fromEntries(Object.keys(hashes).map(relative=>[relative,sha(fs.readFileSync(path.join(root,relative)))]));assert.deepEqual(after,before);
const output=path.join(__dirname,'纯迭代实际Codec投影原React独立消费.json');assert.equal(fs.existsSync(output),false);
const result={checkedAt:new Date().toISOString(),status:'actual_codec_projected_frames_private_ast_consumer_checked',sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],actualFrameCount:frames.length,
 actualFrameSource:'已保存fa4fd2原Factory实际24callback帧经冻结Codec5d7实际纯投影，raw.id显式绑定privateRun，未重新执行Factory/Worker/中心；当前与原handleMessage等及原排序函数AST均在VM执行',originalResult,currentResult,
 observations:['内部相同nodeId三轮running/success，最新结果覆盖旧结果，没有独立批次列表','容器最终聚合数组保原顺序乙/甲/丙，主End原文本保全','24个原节点帧choice.finish_reason均null，未调用全局终态处理','中心外层iterationId没有进入原公开rawframe，页面不读取iteration字段'],
 limitations:['node/store/edge更新为明确memory spy，画布节点元数据来自固定assignment作为已存在UI节点前提','未挂载React/DOM/浏览器，不能称迭代弹窗或按钮页面验收','实际Codec5d7正常24帧投影已执行；三项负向边界仍失败并单列首证据，不以消费者通过抵销','仅使用已有静态实际帧；lease/attempt/中心Event合法性留给正式实现，不以本probe证明'],businessVerified:false};
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({frames:frames.length,writeCount:originalResult.writeCount,terminalCalls:originalResult.terminalCalls,sha256:sha(fs.readFileSync(output))}));
