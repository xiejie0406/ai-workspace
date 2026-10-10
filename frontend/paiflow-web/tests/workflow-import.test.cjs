const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
const hash='a'.repeat(64);
function preview(overrides={}) { return {previewHash:hash,fileHash:'b'.repeat(64),policyVersion:'fixture',name:'原流程',nodes:2,edges:1,differences:[],compatibility:{valid:false,engineProfile:'python',issues:[{nodeId:null,field:'edges',code:'CYCLE',message:'循环'}]},unresolvedResources:[],saveable:true,blockingReasons:[],executionStatus:'structure_invalid',...overrides}; }
function fixture(reply) {
 const calls=[];const exports={};
 const source=fs.readFileSync(path.join(__dirname,'../src/services/flow.ts'),'utf8');
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports,File,FormData,require:name=>{assert.equal(name,'@/utils/http');return {default:{post:async(...args)=>{calls.push(args);return reply(...args);}}};}});
 return {module:exports,calls};
}
test('预览只发送原YAML文件，不发送保存确认，保留图级null节点诊断',async()=>{
 const f=fixture(()=>preview());const file=new File(['flowMeta: {}\nflowData: {}'],'原流程.yaml');
 const result=await f.module.workflowImportPreview(file);
 assert.equal(result.compatibility.issues[0].nodeId,null);assert.equal(f.calls.length,1);
 const [url,body]=f.calls[0];assert.equal(url,'/workflow/import/preview');assert.equal(body.get('previewHash'),null);
 assert.equal(body.get('file').name,file.name);assert.equal(await body.get('file').text(),await file.text());
});
test('确认发送同一文件和回执，接受中心真实UUID且不自动追加第二次创建',async()=>{
 const id='5bb64d19-fd71-42b8-9b87-8e7e986c1bb2';const f=fixture(()=>({flowId:id}));
 const file=new File(['name: 测试'],'test.yml');assert.equal((await f.module.workflowImport({file,previewHash:hash})).flowId,id);
 assert.equal(f.calls.length,1);assert.equal(f.calls[0][0],'/workflow/import');assert.equal(f.calls[0][1].get('previewHash'),hash);
 assert.equal(await f.calls[0][1].get('file').text(),await file.text());
});
test('文件或回执不合法时不发送HTTP创建或预览',async()=>{
 const f=fixture(()=>preview());
 for(const file of [new File([],'empty.yml'),new File(['x'],'bad.txt'),new File(['x'.repeat(20*1024*1024+1)],'large.yaml')]) await assert.rejects(f.module.workflowImportPreview(file));
 await assert.rejects(f.module.workflowImport({file:new File(['x'],'test.yml'),previewHash:'stale'}));assert.equal(f.calls.length,0);
});
test('预览响应缺失字段或诊断畸形时不能成为确认回执',async()=>{
 for(const result of [null,{},preview({blockingReasons:null}),preview({compatibility:{valid:true,engineProfile:'python',issues:[{nodeId:42,field:'x',code:'x',message:'x'}]}})]) {
  const f=fixture(()=>result);await assert.rejects(f.module.workflowImportPreview(new File(['x'],'test.yml')));assert.equal(f.calls.length,1);
 }
});
test('确认超时和缺草稿ID均失败，不导航也不自动重试',async()=>{
 for(const reply of [()=>{throw new Error('timeout');},()=>({}),()=>({flowId:'../other'})]) {
  const f=fixture(reply);await assert.rejects(f.module.workflowImport({file:new File(['x'],'test.yml'),previewHash:hash}));assert.equal(f.calls.length,1);
 }
});
