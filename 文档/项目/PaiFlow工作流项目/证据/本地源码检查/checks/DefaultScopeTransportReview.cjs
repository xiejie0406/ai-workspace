const fs=require('node:fs'),crypto=require('node:crypto'),vm=require('node:vm'),path=require('node:path');
const root=path.resolve(__dirname,'../../../../../..');
const ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
const file=path.join(root,'frontend/paiflow-web/src/utils/workflow-revision.ts'),source=fs.readFileSync(file,'utf8'),moduleExports={};
vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports:moduleExports,URL,URLSearchParams});
const cases=[];
for(const scope of ['', '5'])for(const resource of ['workflow','agent','model'])for(const [name,value] of [['missing',undefined],['null',null],['empty',''],['array_empty',[]],['array_null',[null]],['array_empty_string',['']],['array_named',['5']],['object',{}],['bool',false],['positive',5],['positive_string','5'],['unsafe',9007199254740992],['leading_zero','05']]){
 const cache=new moduleExports.WorkflowRevisionTransport();cache.setScope('actor',scope);
 const req={url:resource==='model'?'/api/model/detail?modelId=9':resource==='agent'?'/my-bot/bot-detail':'/workflow?id=9',method:resource==='agent'?'post':'get',headers:{},...(resource==='agent'?{data:{botId:9}}:{})};
 const ticket=cache.prepare(req,'/paiflow/legacy','https://app.example');
 const data={id:9,revision:4,...(resource==='workflow'?{flowId:'flow-nine'}:resource==='agent'?{botId:9}:{llmSource:0})};if(value!==undefined)data.spaceId=value;
 const headers={ETag:resource==='model'?'"paiflow-model-9-4"':'"4"'};
 const expected=scope===''?['missing','null','empty'].includes(name):['positive','positive_string'].includes(name);
 cases.push({scope,resource,name,expected,accepted:cache.accept(ticket,{code:0,data},headers)});
}
process.stdout.write(JSON.stringify({sourceSha256:crypto.createHash('sha256').update(source).digest('hex'),cases},null,2));

