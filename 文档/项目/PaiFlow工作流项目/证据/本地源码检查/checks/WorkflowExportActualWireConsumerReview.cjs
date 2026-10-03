// 冻结的实际H2/MVC下载wire，进入原/current按钮与XHR函数AST；DOM/XHR仍为内存spies，不运行页面。
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../../..');
const ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
const yaml=require(path.join(root,'frontend/paiflow-web/node_modules/js-yaml'));
const artifactFile='文档/项目/PaiFlow工作流项目/证据/本地源码检查/工作流YAML导出实际MVC响应.json';
const original={http:'参考项目/PaiFlow-main/console/frontend/src/utils/http.ts',utils:'参考项目/PaiFlow-main/console/frontend/src/components/workflow/utils/index.ts',button:'参考项目/PaiFlow-main/console/frontend/src/pages/workflow/components/btn-groups/index.tsx'};
const current={http:'frontend/paiflow-web/src/utils/http.ts',utils:'frontend/paiflow-web/src/components/workflow/utils/index.ts',button:'frontend/paiflow-web/src/pages/workflow/components/btn-groups/index.tsx'};
const files=[artifactFile,...Object.values(original),...Object.values(current),
 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowExportController.java',
 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowExportService.java',
 'frontend/paiflow-web/node_modules/js-yaml/index.js','frontend/paiflow-web/node_modules/js-yaml/package.json'];
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const sha=p=>hash(fs.readFileSync(path.join(root,p)));
const before=Object.fromEntries(files.map(p=>[p,sha(p)]));
assert.equal(before[artifactFile],'0563b6b65c9cf2b6aa74486bd1079bf44826bfcaf5f9d0b81f40f05be2a6d030');
assert.equal(before[current.http],'95922fd980f9cec57b5b9c72e462295bb7f3d737eb87985b35bf13737d623ab2');
const artifact=JSON.parse(fs.readFileSync(path.join(root,artifactFile),'utf8'));
const bytes=Buffer.from(artifact.bodyUTF8Base64,'base64');
assert.equal(bytes.toString('base64'),artifact.bodyUTF8Base64,'严格Base64往返');
assert.deepEqual(Buffer.from(artifact.actualBodyUTF8,'utf8'),bytes);
assert.equal(new TextDecoder('utf-8',{fatal:true}).decode(bytes),artifact.actualBodyUTF8);
assert.equal(bytes.length,1790);assert.equal(bytes.length,artifact.bodyUTF8Bytes);
assert.equal(hash(bytes),artifact.bodySha256);
assert.equal(artifact.actualStatus,200);
assert.equal(artifact.actualContentType,'application/octet-stream;charset=UTF-8');
assert.equal(artifact.actualCharacterEncoding,'UTF-8');
function responseHeader(name){const pair=Object.entries(artifact.actualHeaders).find(([k])=>k.toLowerCase()===name.toLowerCase());return pair?pair[1].join(', '):null;}
assert.equal(responseHeader('Content-Length'),String(bytes.length));
assert.equal(responseHeader('Content-Type'),artifact.actualContentType);
assert.equal(responseHeader('Content-Disposition'),"attachment; filename*=UTF-8''workflow-1.yaml");
assert.equal(responseHeader('Access-Control-Expose-Headers'),'Content-Disposition');
assert.deepEqual(artifact.beforeTruth,artifact.afterTruth);
assert.equal(artifact.currentRevision,2);assert.equal(artifact.beforeTruth.latestVersionId,1);
assert.equal(artifact.selectedVersionId,artifact.beforeTruth.latestVersionId);
for(const key of ['runCount','eventCount','checkpointCount'])assert.equal(artifact.beforeTruth[key],0);
const decoded=yaml.load(bytes.toString('utf8'));
assert.equal(decoded.flowMeta.name,'中文😀工作流');assert.equal(decoded.flowMeta.dslVersion,'v1');
assert.deepEqual(Object.keys(decoded.flowMeta),['name','description','avatarIcon','avatarColor','edgeType','category','advancedConfig','dslVersion']);
assert.equal(decoded.flowMeta.advancedConfig,'{"flag":false}');
assert.equal(decoded.flowData.nodes[0].data.label,'当前草稿');
assert.deepEqual(decoded.flowData.viewport,{x:0,y:false,future:null});
assert.equal(decoded.flowData.nodes[1].data.nodeParam.opaqueNull,null);
assert.ok(Object.hasOwn(decoded.flowData.nodes[1].data.nodeParam,'opaqueNull'));
assert.deepEqual(decoded.flowData,JSON.parse(artifact.beforeTruth.canvas));
assert.ok(!Object.hasOwn(decoded.flowMeta,'futureMetadata'));
function ast(file){return ts.createSourceFile(file,fs.readFileSync(path.join(root,file),'utf8'),ts.ScriptTarget.Latest,true, file.endsWith('tsx')?ts.ScriptKind.TSX:ts.ScriptKind.TS);}
function declaration(file,name){const source=ast(file);const n=source.statements.find(v=>ts.isVariableStatement(v)&&v.declarationList.declarations.some(d=>d.name.getText(source)===name));assert.ok(n,name);return n.getText(source).replace(/^export\s+/,'');}
function caller(file){const source=ast(file);let node;function walk(n){if(ts.isArrowFunction(n)&&ts.isCallExpression(n.body)&&n.body.expression.getText(source)==='downloadFileWithHeaders')node=n;ts.forEachChild(n,walk);}walk(source);assert.ok(node,'原按钮onClick表达式');return {text:node.getText(source),line:source.getLineAndCharacterOfPosition(node.getStart()).line+1};}
async function consume(set){let xhr;const calls=[],blobs=[];class MemoryXHR{constructor(){xhr=this;this.headers={};}open(method,url,async){calls.push({operation:'open',method,url,async});}setRequestHeader(name,value){this.headers[name]=value;}getResponseHeader(name){calls.push({operation:'getResponseHeader',name});return responseHeader(name);}send(){calls.push({operation:'send'});}}
 const button=caller(set.button);
 const box={module:{exports:{}},baseURL:'/paiflow/legacy',currentFlow:{id:artifact.workflowId,name:decoded.flowMeta.name},XMLHttpRequest:MemoryXHR,
 useSpaceStore:{getState:()=>({spaceId:artifact.actualRequest.spaceHeader,spaceType:'personal'})},
 localStorage:{getItem:key=>{assert.equal(key,'accessToken');return 'FIXTURE_NOT_A_REAL_TOKEN';}},
 document:{createElement:tag=>{assert.equal(tag,'a');calls.push({operation:'createAnchor'});return {click(){calls.push({operation:'click',filename:this.download,href:this.href});}};},body:{appendChild:()=>calls.push({operation:'appendChild'}),removeChild:()=>calls.push({operation:'removeChild'})}},
 URL:{createObjectURL:blob=>{assert.ok(blob instanceof Blob);blobs.push(blob);calls.push({operation:'createObjectURL',blobBytes:blob.size,blobType:blob.type});return 'blob:actual-wire-in-memory';},revokeObjectURL:url=>{assert.equal(url,'blob:actual-wire-in-memory');calls.push({operation:'revokeObjectURL'});}},
 message:{error:msg=>calls.push({operation:'error',text:msg})}};
 const code=declaration(set.http,'downloadFileWithHeaders')+'\n'+declaration(set.utils,'getFixedUrl')+'\nmodule.exports='+button.text;
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,box);
 box.module.exports();
 assert.equal(xhr.responseType,'blob');assert.deepEqual(JSON.parse(JSON.stringify(xhr.headers)),{'space-id':artifact.actualRequest.spaceHeader,Authorization:'Bearer FIXTURE_NOT_A_REAL_TOKEN'});
 assert.deepEqual(calls.find(v=>v.operation==='open'),{operation:'open',method:artifact.actualRequest.method,url:artifact.actualRequest.path,async:true});
 xhr.status=artifact.actualStatus;xhr.statusText='OK';xhr.response=new Blob([bytes],{type:artifact.actualContentType});xhr.onload();
 assert.equal(blobs.length,1);assert.deepEqual(Buffer.from(await blobs[0].arrayBuffer()),bytes);
 assert.equal(calls.filter(v=>v.operation==='click').length,1);
 const click=calls.find(v=>v.operation==='click');assert.equal(click.filename,decoded.flowMeta.name+'.yml');
 assert.notEqual(click.filename,'workflow-1.yaml','原下载名取按钮参数而不是服务器Content-Disposition');
 assert.equal(calls.filter(v=>v.operation==='revokeObjectURL').length,1);assert.equal(calls.filter(v=>v.operation==='error').length,0);
 assert.ok(!calls.some(v=>v.operation==='getResponseHeader'&&v.name.toLowerCase()==='content-disposition'));
 return {source:set,callerLine:button.line,headers:JSON.parse(JSON.stringify(xhr.headers)),responseType:xhr.responseType,blobBytes:blobs[0].size,blobSha256:hash(Buffer.from(await blobs[0].arrayBuffer())),callerFilename:click.filename,calls};
}
(async()=>{
 const old=await consume(original),now=await consume(current);
 const common=value=>({headers:value.headers,responseType:value.responseType,blobBytes:value.blobBytes,blobSha256:value.blobSha256,callerFilename:value.callerFilename,calls:value.calls.filter(v=>v.operation!=='getResponseHeader')});
 assert.deepEqual(common(old),common(now));
 const after=Object.fromEntries(files.map(p=>[p,sha(p)]));assert.deepEqual(before,after);
 const report={schemaVersion:'paiflow.workflow-export-actual-wire-consumer.v1',artifact:{path:artifactFile,sha256:before[artifactFile],status:artifact.actualStatus,contentType:artifact.actualContentType,bodyBytes:bytes.length,bodySha256:hash(bytes),base64Utf8LengthContentLengthIndependentMatch:true},
   sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],actualWireCount:1,actualAstConsumerCount:2,original:old,current:now,positiveDownloadEffectsEqual:true,
   sourceProjection:{currentDraftRevision:artifact.currentRevision,latestVersionIdPointer:artifact.beforeTruth.latestVersionId,artifactSelectedVersionIdMeaning:'字段名selectedVersionId实为未改变的latestVersionId指针；不是导出选择该版本',truthBeforeAfterEqual:true,runEventCheckpointCounts:[0,0,0],knownMetadataWhitelist:'原七keys非空+v1',approvedCanvasExtension:['原closedDTO丢弃的data.label/viewport在此保留','opaqueNull及viewport.future显式null保留','false与0保留'],canvasComparedToActualStoredJsonExactly:true,uidHygiene:'本actualwire没有nodeParam.uid；不冒称此样例验证清UID'} ,
   limitations:['真实HTTP元数据/UTF8/base64来自隔离H2+Mapper+Service+MockMVC实际产物；本checker没有重跑其测试。',
     '原/current按钮和下载函数AST真实调用，XHR、DOM、URL、登录token与store personal标签明确是内存夹具；不是浏览器、真实网络或文件下载验收。',
     'baseURL取/paiflow/legacy以匹配实际请求path，未声称已读取某部署运行配置；spaceHeader来自actualrequest，token是假fixture。',
     '只消费一个实际成功wire，不重跑已通过12场景矩阵；新的HTTP200 JSON guard差异由8eed证据另存。',
     '原13golden不是全部同形：unknown/layout/null扩展与UID hygiene由设计批准；当前拒绝原null canvas，nodes-null原异常没有照搬成正式成功。',
     '已核样例从currentdraft导出，不修改草稿、版本指针、Run/event/checkpoint或正式App；未运行原完整Service/数据库或source包导入。'],businessVerified:false};
 const out=path.join(__dirname,'工作流YAML导出实际MVC原React下载消费核对.json');assert.ok(!fs.existsSync(out),'冻结证据不得覆写');fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify({proofSha256:hash(fs.readFileSync(out)),actualWireCount:1,consumerCount:2,bodyBytes:bytes.length,bodySha256:hash(bytes),sourceWindowChanges:0,currentEqualsOriginal:true,businessVerified:false}));
})().catch(error=>{console.error(error);process.exitCode=1;});
