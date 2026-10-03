'use strict';
const fs=require('fs'),vm=require('vm'),path=require('path');
const root=path.resolve(__dirname,'../../../../../..');
const ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
const file=path.join(root,'frontend/paiflow-web/src/utils/http.ts');
const text=fs.readFileSync(file,'utf8'),source=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true);
const declaration=source.statements.find(n=>ts.isVariableStatement(n)&&n.declarationList.declarations.some(d=>d.name.getText(source)==='downloadFileWithHeaders'));
if(!declaration)throw Error('missing actual function');
const code=ts.transpileModule(declaration.getText(source).replace(/^export\s+/,''),{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText+'\nthis.download=downloadFileWithHeaders;';
const cases=[
 ['workflow-auth-json', '/paiflow/legacy/workflow/export/17',200,'application/json;charset=UTF-8',0,1],
 ['workflow-auth-json-query','/paiflow/legacy/workflow/export/17?download=1',200,'Application/JSON',0,1],
 ['workflow-yaml','/paiflow/legacy/workflow/export/17',200,'application/octet-stream',1,0],
 ['workflow-denied','/paiflow/legacy/workflow/export/17',403,'application/json',0,1],
 ['other-download-unchanged','/other/export/17',200,'application/json',1,0],
];
const observations=[];
for(const [id,url,status,mime,expectedClicks,expectedErrors] of cases){
 let request,clicks=0,errors=0,creates=0;const headers={};
 class Xhr{constructor(){request=this;this.status=status;this.statusText='fixture';this.response={private:'memory-only'};}open(){}setRequestHeader(k,v){headers[k]=v;}getResponseHeader(){return mime;}send(){this.onload();}}
 const context={XMLHttpRequest:Xhr,message:{error(){errors++;}},useSpaceStore:{getState(){return {spaceId:'17',spaceType:'personal'};}},localStorage:{getItem(){return 'SYNTHETIC_TOKEN';}},URL:{createObjectURL(){creates++;return 'blob:memory';},revokeObjectURL(){}},document:{createElement(){return {click(){clicks++;}};},body:{appendChild(){},removeChild(){}}}};
 vm.runInNewContext(code,context);context.download(url,'workflow-17.yaml');
 if(clicks!==expectedClicks||errors!==expectedErrors||creates!==expectedClicks)throw Error(id);
 observations.push({id,passed:true,status,mime,clicks,errors,createdBlobs:creates});
}
process.stdout.write(JSON.stringify({cases:observations,count:observations.length,realXHR:false,network:false}));
