const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../../..'),ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
const file=path.resolve(root,process.argv[2]),output=path.resolve(__dirname,process.argv[3]),exportsObject={};
const sha=value=>crypto.createHash('sha256').update(value).digest('hex');
const source=fs.readFileSync(file);vm.runInNewContext(ts.transpileModule(source.toString('utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports:exportsObject,AbortController,Headers});
const receipt={workflowVersionId:'51',workflowId:'9',flowId:'flow-nine',mode:'comparison',comparisonId:'comparison-fixture',buildRequestKey:'build-fixture',snapshotRevision:'4',dslHash:'a'.repeat(64),engineProfile:'python',executionCodec:'paiflow.execution.v3'};
const context=new exportsObject.WorkflowDebugContext(receipt,'scope',()=> 'scope','request-fixture',{flow_id:'flow-nine',inputs:{},chatId:'chat',version:'comparison-fixture',promptDebugger:true},()=> 'reply-fixture');
context.open({status:200,headers:new Headers({'Content-Type':'text/event-stream','X-PaiFlow-Run-Id':'run-fixture','X-PaiFlow-Workflow-Version-Id':'51'})});
let accepted=false,error;
try{accepted=context.frame('run-fixture:1',JSON.stringify({code:0,choices:[{finish_reason:'interrupt'}],workflow_step:{node:{id:'question-answer::q'}},event_data:{event_id:'checkpoint-fixture',checkpoint_revision:'0',checkpoint_state:'WAITING',checkpoint_waiting:true}}));}catch(value){error=value.message;}
const body=JSON.parse(context.cancelBody());
const result={source:file,sha256:sha(source),nodeId:'question-answer::q',frameAccepted:accepted,error,cancelBody:body,cancelComparisonFieldsMatch:body.version===receipt.comparisonId&&body.promptDebugger===true,
 actualNodeVm:true,realHttp:false,browser:false,maven:false,scope:'两条前端纯协议反例；不声称正式中心或原页面通过'};
if(fs.existsSync(output))throw new Error('不可覆盖旧证据');fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({frameAccepted:accepted,cancelComparisonFieldsMatch:result.cancelComparisonFieldsMatch,sha256:result.sha256}));
