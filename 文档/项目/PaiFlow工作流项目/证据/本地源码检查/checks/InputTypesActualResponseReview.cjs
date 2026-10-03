// 仅在Root READY后读取实际Java产物；原consumer AST、transport与请求均在隔离VM，不运行页面。
const fs=require('fs'),path=require('path'),crypto=require('crypto'),vm=require('vm');
const root=path.resolve(__dirname,'../../../../../..');
const argv=process.argv.slice(2);if(argv[0]!=='--ready'||!argv[1])throw Error('必须由Root READY并提供实际artifact SHA256后执行');
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const artifactPath=path.join(root,argv[2]||'ruoyi-backend/ruoyi-paiflow/target/input-types-actual-response.json');
if(sha(artifactPath)!==argv[1])throw Error('实际artifact SHA不匹配READY');
const artifact=JSON.parse(fs.readFileSync(artifactPath,'utf8'));
const sourcePaths=['frontend/paiflow-web/src/utils/workflow-revision.ts','frontend/paiflow-web/src/services/flow.ts','frontend/paiflow-web/src/pages/space-page/agent-page/index.tsx','frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts'];
const hashes=()=>Object.fromEntries(sourcePaths.map(p=>[p,sha(path.join(root,p))]));const before=hashes(),checks=[];
const ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
function comparable(value){if(Array.isArray(value))return value.map(comparable);if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(k=>[k,comparable(value[k])]));return value;}
function check(name,actual,expected){checks.push({name,actual,expected,passed:JSON.stringify(comparable(actual))===JSON.stringify(comparable(expected))});}
const bytes=Buffer.from(artifact.response.rawBodyBase64,'base64');
check('original HTTP raw UTF8 SHA',crypto.createHash('sha256').update(bytes).digest('hex'),artifact.response.rawBodySha256);
const body=JSON.parse(bytes.toString('utf8'));check('actual raw body agrees saved body',body,artifact.response.body);
check('actual isolated response status',artifact.response.status,200);check('actual envelope success',body.code,0);
const names=['id','name','nameErrMsg','schema','allowedFileType','fileType','description','required','refId','deleteDisabled','disabled','customParameterType'];
for(const [i,row] of body.data.entries())check('actual DTO12 keys '+i,Object.keys(row).sort(),[...names].sort());
function transpile(code){return ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;}
const actualHttp=[],serviceModule={exports:{}};
vm.runInNewContext(transpile(fs.readFileSync(path.join(root,sourcePaths[1]),'utf8')),{exports:serviceModule.exports,require:name=>name==='@/utils/http'?{default:{post:(url,data)=>{actualHttp.push({url,data});return Promise.resolve(body.data);}}}:(()=>{throw Error(name);})()},{timeout:1000});
const request=JSON.parse(artifact.request.body);
const exportsObject={};vm.runInNewContext(transpile(fs.readFileSync(path.join(root,sourcePaths[0]),'utf8')),{exports:exportsObject,URL,URLSearchParams},{timeout:1000});
const cache=new exportsObject.WorkflowRevisionTransport();cache.setScope('isolated-fixture-actor',artifact.request['space-id']);
const config={method:'post',url:'/workflow/bot/get-inputs-type',data:request,headers:{}};
const ticket=cache.prepare(config,'/paiflow/legacy','https://fixture.example');
check('sync sends no fabricated condition',config.headers['If-Match'],undefined);
const priorData=JSON.stringify(body.data);
check('actual receipt accepted by frozen transport',cache.accept(ticket,body,{ETag:artifact.response.ETag,'X-PaiFlow-Agent-Id':artifact.response['X-PaiFlow-Agent-Id']}),true);
check('original response array not rewritten',JSON.stringify(body.data),priorData);
const consumerPath=path.join(root,sourcePaths[2]),text=fs.readFileSync(consumerPath,'utf8'),ast=ts.createSourceFile(consumerPath,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let callback;
function visit(node){if(ts.isCallExpression(node)&&ts.isPropertyAccessExpression(node.expression)&&node.expression.name.text==='then'&&ts.isCallExpression(node.expression.expression)&&node.expression.expression.expression.getText(ast)==='getInputsType'&&node.arguments[0]&&ts.isArrowFunction(node.arguments[0]))callback=node.arguments[0].getText(ast);ts.forEachChild(node,visit);}
visit(ast);if(!callback)throw Error('原输入类型消费者不存在');
const actions=[],sandbox={module:{exports:null},message:{info:()=>actions.push('notSupported')},t:key=>key,handleToChat:id=>actions.push({chatSpy:id}),k:{botId:request.botId}};
vm.runInNewContext(transpile('module.exports='+callback),sandbox,{timeout:1000});sandbox.module.exports(body.data);
check('actual ordered Agent + scalar file original branch',actions,[{chatSpy:request.botId}]);
serviceModule.exports.getInputsType(request).then(()=>{
 check('actual original service path and raw body',actualHttp,[{url:'/workflow/bot/get-inputs-type',data:request}]);
 check('original reserved input remains first',body.data[0].name,'AGENT_USER_INPUT');
 check('actual artifact unchanged during review',sha(artifactPath),argv[1]);
 const after=hashes(),changes=Object.keys(before).filter(p=>before[p]!==after[p]),failed=checks.filter(c=>!c.passed);
 const copy=path.join(__dirname,'输入类型Java实际响应原字节快照.json');fs.copyFileSync(artifactPath,copy);
 const report={checkedAt:new Date().toISOString(),method:'Root READY actual isolated H2/Mapper/Service/MockMVC response bytes + actual helper and original service/AgentPage AST in Node VM',artifactPath:path.relative(root,artifactPath),artifactSha256:argv[1],artifactScope:artifact.scope,artifactCopy:path.relative(root,copy),sourcesBefore:before,sourcesAfter:after,sourceWindowChanges:changes,checks,failed,checkCount:checks.length,actualConsumerActions:actions,fullOriginalMaasExecuted:false,liveCentre:false,browserUsed:false,network:false,MavenExecuted:false,targetWrites:false,productionModified:false,businessVerified:false,limits:['仅复用Root实际isolated H2/MockMVC产物，未由reviewer重跑其JUnit。','chatSpy为原AST导航判断，不证明正式App/chat/文件上传完成。','本探针没有伪造Agent绑定或先前详情，真实缓存失效边界沿用62项独立VM检查。']};
 const out=path.join(__dirname,'输入类型Java实际响应原消费者独立核对.json');fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify({report:path.relative(root,out),sha256:sha(out),checks:checks.length,failed,sourceWindowChanges:changes},null,2));if(failed.length||changes.length)process.exitCode=1;
}).catch(error=>{console.error(error);process.exitCode=1;});
