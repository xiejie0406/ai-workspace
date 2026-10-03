// 原/current真实下载AST，所有响应头、状态、XHR/DOM均为内存夹具；不执行页面/网络。
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../../..'),ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
const original='参考项目/PaiFlow-main/console/frontend/src/utils/http.ts',current='frontend/paiflow-web/src/utils/http.ts';
const files=[original,current,'frontend/paiflow-web/src/components/workflow/utils/index.ts','frontend/paiflow-web/src/pages/workflow/components/btn-groups/index.tsx','frontend/paiflow-web/src/pages/space-page/agent-page/index.tsx'];
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex');
const before=Object.fromEntries(files.map(p=>[p,sha(p)]));
function declaration(file,name){const text=fs.readFileSync(path.join(root,file),'utf8'),ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true);const node=ast.statements.find(n=>ts.isVariableStatement(n)&&n.declarationList.declarations.some(d=>d.name.getText(ast)===name));assert.ok(node,name);return node.getText(ast).replace(/^export\s+/,'');}
const exportUrl='/paiflow/legacy/workflow/export/5000000001';
const cases=[
 {id:'export-octet-success',url:exportUrl,status:200,contentType:'application/octet-stream;charset=UTF-8',currentDownload:true},
 {id:'configured-absolute-export-success',url:'https://center.invalid/prefix/workflow/export/5000000001',status:200,contentType:'application/octet-stream',currentDownload:true},
 {id:'authentication-http200-json',url:exportUrl,status:200,contentType:'application/json;charset=UTF-8',currentDownload:false,approvedDifference:true},
 {id:'json-case-insensitive-response-header',url:exportUrl,status:200,contentType:'Application/JSON;Charset=UTF-8',currentDownload:false,approvedDifference:true},
 {id:'export-query-fragment-json',url:exportUrl+'/?download=1#fixture',status:200,contentType:'application/json',currentDownload:false,approvedDifference:true},
 {id:'other-endpoint-legitimate-json',url:'/paiflow/legacy/dataset/export/7',status:200,contentType:'application/json',currentDownload:true},
 {id:'similar-name-is-other-endpoint',url:'/paiflow/legacy/workflow/exported/7',status:200,contentType:'application/json',currentDownload:true},
 {id:'export-real403',url:exportUrl,status:403,contentType:'application/json',currentDownload:false},
 {id:'export-real404',url:exportUrl,status:404,contentType:'application/json',currentDownload:false},
 {id:'export-network-error',url:exportUrl,status:0,contentType:null,networkError:true,currentDownload:false},
 {id:'export-no-recorded-content-type',url:exportUrl,status:200,contentType:null,currentDownload:true},
 {id:'same-extra-header-overrides',url:exportUrl,status:200,contentType:'application/octet-stream',currentDownload:true,extraHeaders:{'space-id':'8','X-Fixture':'only'}}
];
function evaluate(file,test){let xhr;const calls=[];class MemoryXHR{constructor(){xhr=this;this.headers={};}open(method,url,async){calls.push({operation:'open',method,url,async});}setRequestHeader(name,value){this.headers[name]=value;}getResponseHeader(name){calls.push({operation:'getResponseHeader',name});return name.toLowerCase()==='content-type'?test.contentType:null;}send(){calls.push({operation:'send'});}}
 const box={module:{exports:{}},XMLHttpRequest:MemoryXHR,useSpaceStore:{getState:()=>({spaceId:'7',spaceType:'team',enterpriseId:'9'})},localStorage:{getItem:key=>{assert.equal(key,'accessToken');return 'FIXTURE_NOT_A_TOKEN';}},
  document:{createElement:name=>{assert.equal(name,'a');calls.push({operation:'createAnchor'});return {click(){calls.push({operation:'click',filename:this.download,href:this.href});}};},body:{appendChild:()=>calls.push({operation:'appendChild'}),removeChild:()=>calls.push({operation:'removeChild'})}},
  URL:{createObjectURL:body=>{assert.equal(body,'private-response-blob');calls.push({operation:'createObjectURL'});return 'blob:fixture-memory';},revokeObjectURL:url=>{assert.equal(url,'blob:fixture-memory');calls.push({operation:'revokeObjectURL'});}},message:{error:text=>calls.push({operation:'error',text})}};
 vm.runInNewContext(ts.transpileModule(declaration(file,'downloadFileWithHeaders')+'\nmodule.exports=downloadFileWithHeaders',{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,box);
 box.module.exports(test.url,'原调用者中文.yml',test.extraHeaders||{});
 xhr.status=test.status;xhr.statusText='fixture-status';xhr.response='private-response-blob';if(test.networkError)xhr.onerror();else xhr.onload();
 assert.equal(xhr.responseType,'blob');const headers=JSON.parse(JSON.stringify(xhr.headers));assert.deepEqual(headers,{'space-id':'7',Authorization:'Bearer FIXTURE_NOT_A_TOKEN','enterprise-id':'9',...(test.extraHeaders||{})});
 return {id:test.id,headers,responseType:xhr.responseType,downloadCount:calls.filter(v=>v.operation==='click').length,anchorCount:calls.filter(v=>v.operation==='createAnchor').length,objectUrlCount:calls.filter(v=>v.operation==='createObjectURL').length,revokeCount:calls.filter(v=>v.operation==='revokeObjectURL').length,errorCount:calls.filter(v=>v.operation==='error').length,calls};}
const results=cases.map(test=>{const old=evaluate(original,test),now=evaluate(current,test);assert.equal(old.downloadCount,test.status===200&&!test.networkError?1:0);assert.equal(now.downloadCount,test.currentDownload?1:0);assert.equal(now.anchorCount,now.downloadCount);assert.equal(now.objectUrlCount,now.downloadCount);assert.equal(now.revokeCount,now.downloadCount);assert.equal(now.errorCount,now.downloadCount?0:1);
 if(!test.approvedDifference){const clean=value=>({...value,calls:value.calls.filter(v=>v.operation!=='getResponseHeader')});assert.deepEqual(clean(now),clean(old));}
 else assert.equal(now.calls.filter(v=>v.operation==='getResponseHeader').length,1);
 return {fixture:test,original:old,current:now,approvedDifference:!!test.approvedDifference};});
// URL组装只核原getFixedUrl函数；BASE_URL选择与两个按钮入参只读源，不猜当前运行配置。
const box={baseURL:'/paiflow/legacy',module:{exports:{}}};vm.runInNewContext(ts.transpileModule(declaration(files[2],'getFixedUrl')+'\nmodule.exports=getFixedUrl',{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,box);assert.equal(box.module.exports('/workflow/export/5000000001'),exportUrl);
const after=Object.fromEntries(files.map(p=>[p,sha(p)]));assert.deepEqual(before,after);
const report={schemaVersion:'paiflow.workflow-export-json-download-review.v1',sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],caseCountEach:cases.length,approvedDifferenceCount:results.filter(v=>v.approvedDifference).length,results,urlAssembly:{actualFunctionAst:true,baseURLFixture:'/paiflow/legacy',path:'/workflow/export/5000000001',result:exportUrl},
 limitations:['真实原/current下载函数AST，responseType blob；HTTP status/Content-Type是明确内存夹具，不是正式HTTP实录。','原/current正向octetstream、其他合法JSON端点和真正403/404行为相同；export HTTP200认证JSON原下载/当前报错属于Root批准的局部修正。','没有MockMVC/原Service权限/页面挂载/账号/网络/磁盘下载，不证明业务通过。','原golden8e7b与旧下载各6例不覆盖此新http源码阶段，原文件不覆写。'],businessVerified:false};
const output=path.join(__dirname,'工作流YAML下载JSON判别原React独立核对.json');assert.ok(!fs.existsSync(output),'冻结证据不覆写');fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({path:path.relative(root,output),sha256:sha(path.relative(root,output)),caseCountEach:cases.length,approvedDifferenceCount:report.approvedDifferenceCount,sourceWindowChanges:0,businessVerified:false}));
