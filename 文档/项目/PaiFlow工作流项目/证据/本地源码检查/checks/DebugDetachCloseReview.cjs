const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../..'),front=path.join(root,'frontend/paiflow-web'),ts=require(path.join(front,'node_modules/typescript'));
const testFile=path.join(front,'tests/workflow-debug.test.cjs'),flow=path.join(front,'src/components/workflow/store/flow-chat-function.ts'),helperFile=path.join(front,'src/utils/workflow-debug.ts');
const sha=value=>crypto.createHash('sha256').update(value).digest('hex'),files=[testFile,flow,helperFile];const before=Object.fromEntries(files.map(file=>[path.relative(root,file),sha(fs.readFileSync(file))]));
const source=ts.createSourceFile(testFile,fs.readFileSync(testFile,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
const functionSource=name=>{const node=source.statements.find(node=>ts.isFunctionDeclaration(node)&&node.name?.text===name);assert.ok(node,name);return node.getText(source);};
const helper={};vm.runInNewContext(ts.transpileModule(fs.readFileSync(helperFile,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports:helper,AbortController});
const sandbox={module:{exports:{}},fs,path,vm,ts,helper,__dirname:path.dirname(testFile),AbortController,Headers,Response,JSON,BigInt,setTimeout,clearTimeout,structuredClone,
 receipt:patch=>({workflowVersionId:'51',workflowId:'9',flowId:'flow-nine',mode:'ordinary',comparisonId:null,buildRequestKey:'build-fixture',snapshotRevision:'5',dslHash:'a'.repeat(64),engineProfile:'python',executionCodec:'paiflow.execution.v3',...patch}),
 data:r=>({id:'9',flowId:'flow-nine',spaceId:null,revision:r.snapshotRevision,debugReceipt:r})};
vm.runInNewContext(functionSource('execute')+'\n'+functionSource('application')+'\nmodule.exports={application};',sandbox);
async function main(){
 const app=sandbox.module.exports.application(true);app.module.resetNodesAndEdges(app.get);app.start();app.build(0);for(let i=0;i<10;i++)await Promise.resolve();
 const oldContext=app.state.debugContext;const beforeClose={history:app.manager.historyVersion,readonly:app.manager.disabled,debuggering:app.state.debuggering};
 let closed=0;app.module.clearData(()=>closed++,app.get);for(const request of app.requests)request.resolve();for(let i=0;i<10;i++)await Promise.resolve();
 const result={beforeClose,historyAfter:app.manager.historyVersion,readonlyAfter:app.manager.disabled,debuggering:app.state.debuggering,buildPassRef:app.state.buildPassRef,viewStatus:app.manager.flowResult.status,
  contextDetached:oldContext.controller.signal.aborted,contextCleared:app.state.debugContext===undefined,closed,shortStopRequests:app.requests.filter(request=>request.short).length};
 const after=Object.fromEntries(files.map(file=>[path.relative(root,file),sha(fs.readFileSync(file))]));assert.deepEqual(after,before);
 const output=path.join(__dirname,process.argv[2]||'');assert.ok(process.argv[2]);assert.equal(fs.existsSync(output),false);
 fs.writeFileSync(output,JSON.stringify({checkedAt:new Date().toISOString(),status:!result.debuggering&&result.contextDetached&&result.readonlyAfter?'passed':'actual_counterexample',result,
  actualSources:'原resetNodesAndEdges+handleRunDebugger+clearData经完整当前模块VM；应用fixture明示memory store/API/window，不是页面/真实HTTP',sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[]},null,2)+'\n');
 console.log(JSON.stringify({result,sha256:sha(fs.readFileSync(output))}));
}main().catch(error=>{console.error(error);process.exitCode=1;});
