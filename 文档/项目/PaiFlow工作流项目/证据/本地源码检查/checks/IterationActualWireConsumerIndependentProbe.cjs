// 仅在根通知实际产物SHA后执行；读取真实MockMvc wire，不合成节点或终帧。
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../../..'),front=path.join(root,'frontend/paiflow-web'),ts=require(path.join(front,'node_modules/typescript'));
const sha=value=>crypto.createHash('sha256').update(value).digest('hex');
assert.equal(process.argv.length,4,'usage: node probe.cjs frozenActualArtifact expectedSha256');
const report=path.resolve(process.argv[2]);assert.equal(sha(fs.readFileSync(report)),process.argv[3],'实际产物必须是已通知冻结字节');
const evidence=JSON.parse(fs.readFileSync(report,'utf8'));
const fixturePath=path.join(root,evidence.sourceFixture.path);assert.equal(sha(fs.readFileSync(fixturePath)),evidence.sourceFixture.sha256);
assert.equal(evidence.sourceFixture.sha256,'a694b3776c2b41d565b307df2c1d97f6fc12e4d455982ffb7cfa4f21f7b09792');
const fixture=JSON.parse(fs.readFileSync(fixturePath,'utf8'));
for(const source of fixture.sources)assert.equal(sha(fs.readFileSync(path.join(root,source.path))),source.sha256);
assert.equal(evidence.actualStatus,200);assert.equal(evidence.actualContentType.split(';')[0].trim().toLowerCase(),'text/event-stream');
const bytes=Buffer.from(evidence.bodyUTF8Base64,'base64');assert.equal(sha(bytes),evidence.bodySha256);assert.equal(bytes.toString('utf8'),evidence.wire);
const header=name=>{const found=Object.entries(evidence.actualHeaders).filter(([key])=>key.toLowerCase()===name.toLowerCase());assert.equal(found.length,1);assert.equal(found[0][1].length,1);return found[0][1][0];};
const runId=header('X-PaiFlow-Run-Id'),versionId=header('X-PaiFlow-Workflow-Version-Id');
assert.equal(runId,evidence.replayBoundary.newRunId);assert.equal(versionId,evidence.actualRequestBody.debugReceipt.workflowVersionId);
assert.equal(evidence.beforeFormalResultFrames,24);assert.equal(evidence.beforeFormalResultCompleted,false);assert.equal(evidence.finalFrames,25);
const frames=[];
for(const block of evidence.wire.replaceAll('\r\n','\n').split('\n\n')){
 const lines=block.split('\n'),data=lines.filter(line=>line.startsWith('data:')).map(line=>line.slice(5).replace(/^ /,''));
 if(!data.length)continue;
 const ids=lines.filter(line=>line.startsWith('id:'));assert.equal(ids.length,1);
 frames.push({id:ids[0].slice(3).replace(/^ /,''),raw:JSON.parse(data.join('\n'))});
}
assert.equal(frames.length,25);
for(let i=0;i<frames.length;i++)assert.equal(frames[i].id,`${runId}:${i+1}`);
for(let i=0;i<24;i++){
 const expected=structuredClone(fixture.originalCallbackFrames[i].raw);expected.id=runId;
 assert.deepEqual(frames[i].raw,expected,'原24节点帧只允许raw.id显式关联实际新Run');
 assert.equal(frames[i].raw.choices[0].finish_reason,null);
}
assert.equal(frames[5].raw.workflow_step.node.id,'node-end::e');assert.equal(frames[5].raw.workflow_step.node.finish_reason,null);
assert.equal(frames[24].raw.id,runId);assert.equal(frames[24].raw.code,0);assert.equal(frames[24].raw.workflow_step.node.id,'flow_obj');assert.equal(frames[24].raw.choices[0].finish_reason,'stop');
const source=path.join(root,'参考项目/PaiFlow-main/console/frontend/src/components/workflow/store/flow-chat-function.ts');
const current=path.join(front,'src/components/workflow/store/flow-chat-function.ts');
const utils=path.join(root,'参考项目/PaiFlow-main/console/frontend/src/components/workflow/utils/reactflowUtils.ts');
const watched=[source,current,utils,report,fixturePath];const before=Object.fromEntries(watched.map(file=>[path.relative(root,file),sha(fs.readFileSync(file))]));
function declaration(file,name){const text=fs.readFileSync(file,'utf8'),ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
 const selected=ast.statements.find(node=>ts.isVariableStatement(node)&&node.declarationList.declarations.some(d=>d.name.getText(ast)===name));assert.ok(selected,name);return selected.getText(ast).replace(/^export\s+/,'');}
const names=['extractNodeInfo','handleAnswerContent','updateAnswerItem','handleRunningNode','handleFinishedNode','handleNodeStatusChange','handleMessage','pushContentToAnswer'];
function consume(file){
 const nodes=fixture.execution.nodes.map(n=>({id:n.id,data:{...structuredClone(n.data),status:'',debuggerResult:{}},position:{x:0,y:0}}));
 const edges=fixture.execution.edges.map(e=>({source:e.sourceNodeId,target:e.targetNodeId}));const writes=[],queues={messageNodeTextQueue:'',endNodeReasoningTextQueue:'',endNodeTextQueue:''};
 const state={...queues,chatInfoRef:{answerItem:'',answer:{}},preRunningNodeIds:[],currentFollowNodeId:''};let terminalCalls=0;
 const sandbox={module:{exports:{}},useFlowsManager:{getState:()=>({autonomousMode:true})},useFlowStore:{getState:()=>({setNode:(id,value)=>writes.push({id,status:value.data.status,result:structuredClone(value.data.debuggerResult)}),setEdges:fn=>fn(edges)})},
  cloneDeep:structuredClone,isJSON:value=>{try{JSON.parse(value);return true;}catch{return false;}},handleMoveToPosition:()=>{},handleFlowStop:()=>terminalCalls++,handleInterrupt:()=>{throw new Error('actual sample has no QA');},handleAuditFailed:()=>{throw new Error('actual sample has no audit failure');}};
 const code=[declaration(utils,'handleParmasOrder'),declaration(utils,'generateInputsAndOutputsOrder'),...names.map(name=>declaration(file,name))].join('\n')+'\nmodule.exports={handleMessage};';
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,sandbox);
 const terminalCallsAfter=[];
 for(const frame of frames){sandbox.module.exports.handleMessage(nodes,edges,{id:frame.id,data:JSON.stringify(frame.raw)},()=>state,()=>{});terminalCallsAfter.push(terminalCalls);}
 const byId=Object.fromEntries(nodes.map(node=>[node.id,{status:node.data.status,result:node.data.debuggerResult}]));
 return structuredClone({writeCount:writes.length,joinerTransitions:writes.filter(item=>item.id==='text-joiner::j').map(item=>({status:item.status,output:item.result.output})),container:byId['iteration::i'],innerEnd:byId['iteration-node-end::ie'],finalEnd:byId['node-end::e'],terminalCalls,terminalCallsAfter,textQueue:state.endNodeTextQueue});
}
const originalResult=consume(source),currentResult=consume(current);assert.deepEqual(currentResult,originalResult);
assert.equal(originalResult.writeCount,24);assert.deepEqual(originalResult.joinerTransitions.map(item=>item.status),['running','success','running','success','running','success']);
assert.deepEqual(originalResult.container.result.output,{value:fixture.expectedOutput.answer});assert.equal(originalResult.innerEnd.result.output.value,'item=丙');
assert.deepEqual(originalResult.terminalCallsAfter.slice(0,24),Array(24).fill(0));assert.equal(originalResult.terminalCallsAfter[24],1);assert.equal(originalResult.terminalCalls,1);
const after=Object.fromEntries(watched.map(file=>[path.relative(root,file),sha(fs.readFileSync(file))]));assert.deepEqual(before,after);
const output=path.join(__dirname,'纯迭代实际HTTPwire原React消费者独立核对.json');assert.equal(fs.existsSync(output),false);
const result={checkedAt:new Date().toISOString(),scope:'根真实H2/MockMvc产物只读消费；此probe只Node/AST/VM+memory spy，无HTTP/Engine/DB/Maven/浏览器执行',
 artifact:{path:report,sha256:process.argv[3],actualStatus:evidence.actualStatus,actualContentType:evidence.actualContentType,actualHeaders:evidence.actualHeaders,bodySha256:evidence.bodySha256},
 sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],wireFrames:25,nodeFrames:24,actualRunId:runId,actualWorkflowVersionId:versionId,
 originalResult,currentResult,originalFramesOnlyRawIdRebound:true,mainEndEarlyStartedPreserved:true,terminalOrigin:'actual centre leases.result frame25 from frozen MockMvc wire; not synthesized by probe',
 limitations:['未挂载React/DOM/浏览器，不算页面验收','H2/MockMvc由根实际Maven生成，本probe未重跑，其隔离token/scheduler前提沿用产物','handleFlowStop为路由计数spy，证明第25帧路由到原终态分支，不能证明页面全部终态副作用'],businessVerified:false};
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({frames:25,nodeWrites:24,terminalCalls:1,sha256:sha(fs.readFileSync(output))}));
