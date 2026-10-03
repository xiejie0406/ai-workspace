const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
const {AxiosHeaders}=require('axios');
function execute(file,imports={},sandbox={}) {
 const source=fs.readFileSync(path.join(__dirname,'../src',file),'utf8').replaceAll('import.meta.env','__buildEnv');
 const exports={};
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports,URL,URLSearchParams,...sandbox,require:name=>{if(!(name in imports))throw new Error(`Unexpected import ${name}`);return imports[name];}});
 return exports;
}
const revisionModule=execute('utils/workflow-revision.ts');
const centre='/paiflow/legacy',origin='https://app.example';
function fixture(limit=128) {const cache=new revisionModule.WorkflowRevisionTransport(limit);cache.setScope('actor-a','5');return cache;}
function request(cache,method,url,data,extra={}) {const config={method,url,headers:{},...extra};if(data!==undefined)config.data=data;return {config,ticket:cache.prepare(config,centre,origin)};}
function accept(cache,req,id,flowId,revision,extra={}) {cache.accept(req.ticket,{code:0,data:{id,flowId,revision,spaceId:5,...extra}}, {etag:`"${revision}"`});}
function load(cache,id=9,flowId='flow-nine',revision=4) {const req=request(cache,'get',`/workflow?id=${id}`);accept(cache,req,id,flowId,revision);return req;}
function conditional(cache,id=9) {return request(cache,'put','/workflow',{id,description:'original draft'}).config.headers['If-Match'];}

test('比较构建按flowId捕获当前已读条件，固定snapshot不推进草稿缓存',()=>{
 const cache=fixture();load(cache);
 const comparison=request(cache,'post','/workflow/add-comparisons',{flowId:'flow-nine',version:'comparison-fixture'},{headers:{'X-PaiFlow-Build-Key':'build-key-fixture'}});
 assert.equal(comparison.config.headers['If-Match'],'"4"');assert.equal(comparison.ticket.kind,'comparison');
 const result={code:0,data:{id:'9',flowId:'flow-nine',spaceId:5,revision:'4',debugReceipt:{mode:'comparison',workflowId:'9',flowId:'flow-nine',snapshotRevision:'4',workflowVersionId:'51',comparisonId:'comparison-fixture',buildRequestKey:'build-key-fixture',dslHash:'a'.repeat(64),engineProfile:'python',executionCodec:'paiflow.execution.v3'}}};
 assert.equal(cache.accept(comparison.ticket,result,{ETag:'"4"'}),true);assert.equal(conditional(cache),'"4"');
 assert.equal(cache.accept(comparison.ticket,{...result,data:{...result.data,revision:'5'}},{ETag:'"5"'}),false);
 assert.equal(conditional(cache),'"4"');
});
test('比较回执迟到或跨会话/空间/工作流不能交付或提升条件',()=>{
 const cache=fixture();load(cache);
 const make=()=>request(cache,'post','/workflow/add-comparisons',{flowId:'flow-nine',version:'comparison-fixture'},{headers:{'X-PaiFlow-Build-Key':'build-key-fixture'}});
 const result={code:0,data:{id:'9',flowId:'flow-nine',spaceId:5,revision:'4',debugReceipt:{mode:'comparison',workflowId:'9',flowId:'flow-nine',snapshotRevision:'4',workflowVersionId:'51',comparisonId:'comparison-fixture',buildRequestKey:'build-key-fixture',dslHash:'a'.repeat(64),engineProfile:'python',executionCodec:'paiflow.execution.v3'}}};
 for(const field of ['mode','workflowId','flowId','snapshotRevision']) {
  const receipt={...result.data.debugReceipt,[field]:'wrong'};
  assert.equal(cache.accept(make().ticket,{...result,data:{...result.data,debugReceipt:receipt}},{ETag:'"4"'}),false);
 }
 const late=make();const save=request(cache,'put','/workflow',{id:9,flowId:'flow-nine'});accept(cache,save,9,'flow-nine',5);
 assert.equal(cache.accept(late.ticket,result,{ETag:'"4"'}),false);assert.equal(conditional(cache),'"5"');
 const scope=make();cache.setScope('actor-b','5');assert.equal(cache.accept(scope.ticket,result,{ETag:'"4"'}),false);
});
test('比较未读不伪造条件，明确条件冲突和外部URL保持原拒绝边界',()=>{
 const cache=fixture();assert.equal(request(cache,'post','/workflow/add-comparisons',{flowId:'unread'}).config.headers['If-Match'],undefined);load(cache);
 const explicit=request(cache,'post','/workflow/add-comparisons',{flowId:'flow-nine',revision:'2'},{headers:{'If-Match':'"2"'}});
 assert.equal(explicit.config.headers['If-Match'],'"2"');
 const conflict=request(cache,'post','/workflow/add-comparisons',{flowId:'flow-nine',revision:'2'},{headers:{'If-Match':'"4"'}});
 assert.equal(conflict.ticket.expectedRevision,undefined);
 for(const url of ['https://outside.example/workflow/add-comparisons','/workflow/../workflow/add-comparisons']) assert.equal(request(cache,'post',url,{flowId:'flow-nine'}).ticket,undefined);
});
test('普通构建先核完整同次receipt再推进草稿条件，错误回执不能给旧窗口解锁',()=>{
 const cache=fixture();load(cache);
 const make=()=>request(cache,'post','/workflow/build',{id:'9',flowId:'flow-nine'},{headers:{'X-PaiFlow-Build-Key':'build-key-fixture'}});
 const receipt={workflowId:'9',workflowVersionId:'51',flowId:'flow-nine',snapshotRevision:'5',mode:'ordinary',comparisonId:null,buildRequestKey:'build-key-fixture',dslHash:'a'.repeat(64),engineProfile:'python',executionCodec:'paiflow.execution.v3'};
 const good={code:0,data:{id:'9',flowId:'flow-nine',spaceId:5,revision:'5',debugReceipt:receipt}};
 for(const patch of [{buildRequestKey:'wrong-key'},{comparisonId:'old'},{snapshotRevision:'4'},{executionCodec:'unknown'},{executionCodec:['paiflow.execution.v3']},{workflowVersionId:'051'},{dslHash:'wrong'}]) {
  assert.equal(cache.accept(make().ticket,{...good,data:{...good.data,debugReceipt:{...receipt,...patch}}},{ETag:'"5"'}),false);
  assert.equal(conditional(cache),'"4"');
 }
 assert.equal(cache.accept(make().ticket,good,{ETag:'"5"'}),true);assert.equal(conditional(cache),'"5"');
});
test('同域比较构建反序回执只交付最新请求且不修改条件',()=>{
 const cache=fixture();load(cache);
 const make=()=>request(cache,'post','/workflow/add-comparisons',{flowId:'flow-nine',version:'comparison-fixture'},{headers:{'X-PaiFlow-Build-Key':'build-key-fixture'}});
 const first=make(),second=make();const value={code:0,data:{id:'9',flowId:'flow-nine',spaceId:5,revision:'4',debugReceipt:{workflowId:'9',workflowVersionId:'51',flowId:'flow-nine',snapshotRevision:'4',mode:'comparison',comparisonId:'comparison-fixture',buildRequestKey:'build-key-fixture',dslHash:'a'.repeat(64),engineProfile:'python',executionCodec:'paiflow.execution.v3'}}};
 assert.equal(cache.accept(first.ticket,value,{ETag:'"4"'}),false);assert.equal(cache.accept(second.ticket,value,{ETag:'"4"'}),true);assert.equal(conditional(cache),'"4"');
});

test('原详情只缓存条件版本，保存、版本和恢复定位同一工作流而非版本ID',()=>{
 const cache=fixture();load(cache);
 assert.equal(conditional(cache),'"4"');
 const version=request(cache,'post','/workflow/version',{flowId:'flow-nine',name:'v1.0'});
 assert.equal(version.config.headers['If-Match'],'"4"');
 accept(cache,version,501,'flow-nine',5,{workflowId:9});
 const restore=request(cache,'post','/workflow/version/restore',{flowId:'flow-nine',id:501});
 assert.equal(restore.config.headers['If-Match'],'"5"');
 accept(cache,restore,9,'flow-nine',6);assert.equal(conditional(cache),'"6"');
});
test('未读取草稿和版本列表、工作流列表绝不伪造写版本',()=>{
 const cache=fixture();assert.equal(conditional(cache),undefined);
 for(const url of ['/workflow/list','/workflow/version/list']) {
  const list=request(cache,'get',url);accept(cache,list,9,'flow-nine',99);assert.equal(conditional(cache),undefined);
 }
 load(cache);const list=request(cache,'get','/workflow/list');accept(cache,list,9,'flow-nine',99);
 assert.equal(conditional(cache),'"4"');
 const unknown=request(cache,'post','/workflow/version/restore',{flowId:'unread-flow',id:9});assert.equal(unknown.config.headers['If-Match'],undefined);
});
test('自身创建响应可建立新草稿revision与flowId映射，迟到创建不能覆盖已读取文档',()=>{
 const cache=fixture();const created=request(cache,'post','/workflow',{name:'new original flow'});
 assert.equal(created.config.headers['If-Match'],undefined);accept(cache,created,11,'flow-new',0);
 assert.equal(conditional(cache,11),'"0"');assert.equal(request(cache,'post','/workflow/version',{flowId:'flow-new'}).config.headers['If-Match'],'"0"');
 const late=request(cache,'post','/workflow',{name:'another flow'});load(cache,12,'flow-twelve',4);accept(cache,late,12,'flow-twelve',9);
 assert.equal(conditional(cache,12),'"4"');
});
test('并发写捕获相同原读版本；自身成功写之后迟到读取与旧写都不提升版本',()=>{
 const cache=fixture();load(cache);
 const delayedRead=request(cache,'get','/workflow?id=9');
 const first=request(cache,'put','/workflow',{id:9,flowId:'flow-nine'});
 const second=request(cache,'put','/workflow',{id:9,flowId:'flow-nine'});
 assert.equal(first.config.headers['If-Match'],'"4"');assert.equal(second.config.headers['If-Match'],'"4"');
 accept(cache,first,9,'flow-nine',5);accept(cache,delayedRead,9,'flow-nine',88);accept(cache,second,9,'flow-nine',77);
 assert.equal(second.config.headers['If-Match'],'"4"');assert.equal(conditional(cache),'"5"');
});
test('仅最新详情请求可以建立读版本，迟到响应与更小ETag不能回退',()=>{
 const cache=fixture();load(cache);
 const old=request(cache,'get','/workflow?id=9'),latest=request(cache,'get','/workflow?id=9');
 accept(cache,latest,9,'flow-nine',7);accept(cache,old,9,'flow-nine',80);assert.equal(conditional(cache),'"7"');
 const smaller=request(cache,'get','/workflow?id=9');accept(cache,smaller,9,'flow-nine',3);assert.equal(conditional(cache),'"7"');
});
test('请求与响应身份不匹配、错误flowId映射不能给其他文档附加版本',()=>{
 const cache=fixture();const wrong=request(cache,'get','/workflow?id=9');accept(cache,wrong,10,'flow-ten',40);assert.equal(conditional(cache,10),undefined);
 load(cache);load(cache,10,'flow-ten',2);
 const forged=request(cache,'put','/workflow',{id:10,flowId:'flow-nine'});assert.equal(forged.config.headers['If-Match'],undefined);
 const write=request(cache,'put','/workflow',{id:9,flowId:'flow-nine'});accept(cache,write,10,'flow-ten',8);
 assert.equal(conditional(cache,9),'"4"');assert.equal(conditional(cache,10),'"2"');
});
test('会话、空间切换清空缓存，旧作用域晚到结果不能重新播种',()=>{
 const cache=fixture();load(cache);const late=request(cache,'put','/workflow',{id:9});
 cache.setScope('actor-b','5');accept(cache,late,9,'flow-nine',5);assert.equal(conditional(cache),undefined);
 load(cache);cache.setScope('actor-b','6');assert.equal(conditional(cache),undefined);
 cache.setScope('actor-b','5');assert.equal(conditional(cache),undefined);
 load(cache);cache.setScope(null,'5');assert.equal(conditional(cache),undefined);assert.equal(cache.size,0);
});
test('明确If-Match与body/query revision优先，冲突和非法条件不被自动替换',()=>{
 const cache=fixture();load(cache);
 const explicit=request(cache,'put','/workflow',{id:9},{headers:new AxiosHeaders({'if-match':'"2"'})});assert.equal(explicit.config.headers.get('If-Match'),'"2"');
 const body=request(cache,'put','/workflow',{id:9,revision:1});assert.equal(body.config.headers['If-Match'],undefined);
 const nullBody=request(cache,'put','/workflow',{id:9,revision:null});assert.equal(nullBody.config.headers['If-Match'],undefined);
 const conflict=request(cache,'put','/workflow',{id:9,revision:3},{headers:{'If-Match':'"4"'}});accept(cache,conflict,9,'flow-nine',20);assert.equal(conditional(cache),'"4"');
 const query=request(cache,'delete','/workflow?id=9&revision=2');assert.equal(query.config.headers['If-Match'],undefined);
 for(const value of ['*','W/"4"','4',false]) {const invalid=request(cache,'put','/workflow',{id:9},{headers:{'If-Match':value}});assert.equal(invalid.config.headers['If-Match'],value);}
 const axios=request(cache,'put','/workflow',{id:9},{headers:new AxiosHeaders()});assert.equal(axios.config.headers.get('If-Match'),'"4"');
});
test('外部绝对URL、协议相对URL和另一baseURL不能注入条件头',()=>{
 const cache=fixture();load(cache);
 for(const url of ['https://external.example/workflow','https://app.example/workflow','//external.example/workflow','/\\external.example/workflow']) assert.equal(request(cache,'put',url,{id:9}).config.headers['If-Match'],undefined);
 assert.equal(request(cache,'put','/workflow',{id:9},{baseURL:'https://external.example/paiflow/legacy'}).config.headers['If-Match'],undefined);
 assert.equal(request(cache,'put','/workflow',{id:9},{baseURL:'/another/legacy'}).config.headers['If-Match'],undefined);
 assert.equal(request(cache,'put','/workflow',{id:9},{baseURL:'https://app.example/paiflow/legacy'}).config.headers['If-Match'],'"4"');
});
test('缓存有界，淘汰后的旧响应和未读记录不复活',()=>{
 const cache=fixture(2);load(cache,1,'one',0);const old=request(cache,'put','/workflow',{id:1});
 load(cache,2,'two',1);load(cache,3,'three',2);assert.equal(cache.size,2);assert.equal(conditional(cache,1),undefined);
 accept(cache,old,1,'one',1);assert.equal(conditional(cache,1),undefined);assert.equal(cache.size,2);
});
test('业务失败、无效ETag和body/header不一致不更新缓存；删除使旧读取失效',()=>{
 const cache=fixture();load(cache);const failed=request(cache,'put','/workflow',{id:9});cache.accept(failed.ticket,{code:409,data:{id:9,flowId:'flow-nine',revision:90}}, {etag:'"90"'});assert.equal(conditional(cache),'"4"');
 const mismatch=request(cache,'get','/workflow?id=9');cache.accept(mismatch.ticket,{code:0,data:{id:9,flowId:'flow-nine',revision:5}}, {etag:'"6"'});assert.equal(conditional(cache),'"4"');
 const delayed=request(cache,'get','/workflow?id=9'),deleted=request(cache,'delete','/workflow?id=9');cache.accept(deleted.ticket,{code:0,data:null},{});accept(cache,delayed,9,'flow-nine',5);assert.equal(conditional(cache),undefined);
});
test('long字符串保持精确，超过安全整数的JSON数值不参与条件写',()=>{
 const cache=fixture();load(cache,9,'flow-nine','9007199254740993');assert.equal(conditional(cache),'"9007199254740993"');
 const unsafe=fixture();load(unsafe,9,'flow-nine',9007199254740992);assert.equal(conditional(unsafe),undefined);
});
test('build只传原读条件，不把缺路由当成功；不存在的版本删除不套工作流版本',()=>{
 const cache=fixture();load(cache);const build=request(cache,'post','/workflow/build',{id:9,flowId:'flow-nine'});assert.equal(build.config.headers['If-Match'],'"4"');
 cache.accept(build.ticket,{code:503,data:null},{});assert.equal(conditional(cache),'"4"');assert.equal(request(cache,'delete','/workflow/version?id=9').config.headers['If-Match'],undefined);
});
function httpFixture() {
 const hooks={},listeners={};let state={spaceId:'5',spaceType:'personal',enterpriseId:''};let token='actor-a';let spaceListener;
 const axios={defaults:{headers:{common:{}}},CancelToken:function(callback){callback(()=>{});},interceptors:{request:{use:f=>hooks.request=f},response:{use:(ok,fail)=>{hooks.response=ok;hooks.error=fail;}}},isCancel:()=>false};
 const window={__APP_CONFIG__:{},location:{pathname:'/workflow',origin,hostname:'app.example'},addEventListener:(name,fn)=>listeners[name]=fn};
 const module=execute('utils/http.ts',{
  axios:{default:axios},qs:{default:{stringify:JSON.stringify}},antd:{message:{error:()=>{}}},'../../package.json':{default:{version:'fixture'}},
  '@/locales/zh':{zh:{}},'@/locales/en':{en:{}},'@/locales/i18n':{default:{language:'zh',on:()=>{}}},'@/utils/event-bus':{default:{emit:()=>{}}},
  '@/store/space-store':{default:{getState:()=>state,subscribe:fn=>spaceListener=fn,setState:next=>state={...state,...next}}},
  './auth':{handleLoginRedirect:()=>{token=null;}},'./workflow-revision':revisionModule,
 },{window,localStorage:{getItem:()=>token},document:{cookie:''},__buildEnv:{},console});
 return {hooks,module,listeners,setToken:value=>token=value,setSpace:spaceId=>{const previous=state;state={...state,spaceId};spaceListener(state,previous);}};
}
async function httpRead(f,revision=4) {const config=await f.hooks.request({url:'/workflow?id=9',method:'get',headers:{}});const data={id:9,flowId:'flow-nine',revision,spaceId:5,data:'{"nodes":[],"edges":[],"viewport":{"zoom":1}}'};assert.equal(f.hooks.response({config,data:{code:0,data},headers:{etag:`"${revision}"`}}),data);return data;}
test('实际http.ts请求/响应拦截器保持原data形状，只附加条件头',async()=>{
 const f=httpFixture();const data=await httpRead(f);assert.equal(typeof data.data,'string');
 const body={id:9,flowId:'flow-nine',data:{nodes:[],edges:[]}};const config=await f.hooks.request({url:'/workflow',method:'put',data:body,headers:{}});
 assert.equal(config.headers['If-Match'],'"4"');assert.equal(config.data,body);assert.equal(Object.hasOwn(body,'revision'),false);
 const response={...data,revision:5};assert.equal(f.hooks.response({config,data:{code:0,data:response},headers:{etag:'"5"'}}),response);
 assert.equal((await f.hooks.request({url:'/workflow',method:'put',data:body,headers:{}})).headers['If-Match'],'"5"');
});
test('实际HTTP缓存随空间/令牌/storage/logout失效',async()=>{
 const f=httpFixture();await httpRead(f);f.setSpace('6');f.setSpace('5');assert.equal((await f.hooks.request({url:'/workflow',method:'put',data:{id:9},headers:{}})).headers['If-Match'],undefined);
 await httpRead(f);f.setToken('actor-b');assert.equal((await f.hooks.request({url:'/workflow',method:'put',data:{id:9},headers:{}})).headers['If-Match'],undefined);
 await httpRead(f);f.listeners.storage({key:'accessToken'});assert.equal((await f.hooks.request({url:'/workflow',method:'put',data:{id:9},headers:{}})).headers['If-Match'],undefined);
 await httpRead(f);f.hooks.response({config:{url:'/logout',platformResponse:true},data:{code:200},headers:{}});assert.equal((await f.hooks.request({url:'/workflow',method:'put',data:{id:9},headers:{}})).headers['If-Match'],undefined);
});
test('实际HTTP迟到响应在令牌清除后不能把旧身份版本带回新会话',async()=>{
 const f=httpFixture();const old=await f.hooks.request({url:'/workflow?id=9',method:'get',headers:{}});f.setToken(null);
 const data={id:9,flowId:'flow-nine',revision:7,spaceId:5};await assert.rejects(f.hooks.response({config:old,data:{code:0,data},headers:{etag:'"7"'}}),error=>error.code===409);
 f.setToken('actor-b');assert.equal((await f.hooks.request({url:'/workflow',method:'put',data:{id:9},headers:{}})).headers['If-Match'],undefined);
});

const agentData=(id=9,revision=2,bound=false)=>({id,botId:id,revision,spaceId:5,maasId:bound?19:null,workflowId:bound?19:null,flowId:bound?'bound-flow':null,workflowRevision:bound?4:null});
function agentAccept(cache,req,data=agentData(),headers={etag:`"${data.revision}"`}) {cache.accept(req.ticket,{code:0,data},headers);}
function agentLoad(cache,id=9,revision=2,bound=false) {const req=request(cache,'post',`/my-bot/bot-detail?botId=${id}`);agentAccept(cache,req,agentData(id,revision,bound));return req;}
function agentWrite(cache,id=9,extra={}) {return request(cache,'post','/bot/update',{botId:id,name:'原页面草稿',...extra});}

test('智能体与同数值工作流ID分开缓存条件，原响应形状保留',()=>{
 const cache=fixture();load(cache,9,'original-flow',7);agentLoad(cache);
 assert.equal(conditional(cache,9),'"7"');assert.equal(agentWrite(cache).config.headers['If-Match'],'"2"');
 assert.equal(agentWrite(cache).config.headers['X-Workflow-Revision'],undefined);assert.equal(cache.size,2);
});
test('工作流智能体同时带原读两个版本，不从其他画布读取自动提升',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);let write=agentWrite(cache);
 assert.equal(write.config.headers['If-Match'],'"2"');assert.equal(write.config.headers['X-Workflow-Revision'],'"4"');
 assert.equal(conditional(cache,19),undefined);load(cache,19,'bound-flow',12);
 write=request(cache,'post','/workflow/base-save',{botId:9,name:'原配置'});
 assert.equal(write.config.headers['X-Workflow-Revision'],'"4"');assert.equal(conditional(cache,19),'"12"');
 cache.accept(write.ticket,{code:409,data:agentData(9,80,true)},{etag:'"80"'});
 assert.equal(agentWrite(cache).config.headers['If-Match'],'"2"');assert.equal(agentWrite(cache).config.headers['X-Workflow-Revision'],'"4"');
});
test('普通和工作流智能体自身创建可记录条件，新绑定不冒充已读取画布',()=>{
 const cache=fixture();const normal=request(cache,'post','/bot/create',{name:'原普通智能体'});agentAccept(cache,normal,agentData(9,0));
 assert.equal(agentWrite(cache).config.headers['If-Match'],'"0"');
 const bound=request(cache,'post','/workflow/base-save',{botId:null,name:'原工作流智能体'});agentAccept(cache,bound,agentData(10,0,true),{etag:'"0"','x-workflow-revision':'"4"'});
 assert.equal(agentWrite(cache,10).config.headers['If-Match'],'"0"');assert.equal(agentWrite(cache,10).config.headers['X-Workflow-Revision'],'"4"');assert.equal(conditional(cache,19),undefined);
});
test('智能体列表和复制空回执不播种或提升编辑版本',()=>{
 const cache=fixture();for(const url of ['/my-bot/list','/workflow/copy-bot']) {const r=request(cache,'post',url,{botId:9});agentAccept(cache,r,agentData(9,90,true));}
 assert.equal(agentWrite(cache).config.headers['If-Match'],undefined);
 agentLoad(cache);agentAccept(cache,request(cache,'post','/my-bot/list',{}),agentData(9,90));assert.equal(agentWrite(cache).config.headers['If-Match'],'"2"');
});
test('绑定型自身成功更新推进两条件，旧并发写和迟到读取不能覆盖',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);const read=request(cache,'post','/my-bot/bot-detail?botId=9'),a=agentWrite(cache),b=agentWrite(cache);
 const data={...agentData(9,3,true),workflowRevision:5};agentAccept(cache,a,data,{etag:'"3"','x-workflow-revision':'"5"'});
 agentAccept(cache,read,{...data,revision:70,workflowRevision:70});agentAccept(cache,b,{...data,revision:90,workflowRevision:90});
 assert.equal(b.config.headers['If-Match'],'"2"');assert.equal(b.config.headers['X-Workflow-Revision'],'"4"');
 assert.equal(agentWrite(cache).config.headers['If-Match'],'"3"');assert.equal(agentWrite(cache).config.headers['X-Workflow-Revision'],'"5"');
});
test('智能体仅最新详情能建立版本，旧读和较小绑定版本不能倒退',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);const a=request(cache,'post','/my-bot/bot-detail?botId=9'),b=request(cache,'post','/my-bot/bot-detail?botId=9');
 agentAccept(cache,b,{...agentData(9,3,true),workflowRevision:6});agentAccept(cache,a,{...agentData(9,70,true),workflowRevision:70});
 agentAccept(cache,request(cache,'post','/my-bot/bot-detail?botId=9'),{...agentData(9,4,true),workflowRevision:5});
 assert.equal(agentWrite(cache).config.headers['If-Match'],'"3"');assert.equal(agentWrite(cache).config.headers['X-Workflow-Revision'],'"6"');
});
test('智能体明确条件和非法条件保持，body/query冲突不自动更正',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);
 const explicit=request(cache,'post','/bot/update',{botId:9},{headers:new AxiosHeaders({'if-match':'"1"','x-workflow-revision':'"3"'})});
 assert.equal(explicit.config.headers.get('If-Match'),'"1"');assert.equal(explicit.config.headers.get('X-Workflow-Revision'),'"3"');
 const body=agentWrite(cache,9,{revision:null,workflowRevision:1});assert.equal(body.config.headers['If-Match'],undefined);assert.equal(body.config.headers['X-Workflow-Revision'],undefined);
 const conflict=request(cache,'post','/bot/update?revision=1',{botId:9,revision:2});agentAccept(cache,conflict,{...agentData(9,3,true),workflowRevision:5});assert.equal(agentWrite(cache).config.headers['If-Match'],'"2"');
 for(const value of ['*','W/"2"',false]) {const invalid=request(cache,'post','/bot/update',{botId:9},{headers:{'If-Match':value,'X-Workflow-Revision':value}});assert.equal(invalid.config.headers['If-Match'],value);assert.equal(invalid.config.headers['X-Workflow-Revision'],value);}
});
test('智能体跨ID/空间/绑定别名/ETag或非法版本不污染旧条件',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);
 const cases=[{data:agentData(10,8,true)},{data:{...agentData(9,8,true),spaceId:6}},{data:{...agentData(9,8,true),maasId:20}},{data:{...agentData(9,8,true),workflowId:null}},{data:{...agentData(9,8,true),workflowRevision:null},headers:{etag:'"8"','x-workflow-revision':'"10"'}},{data:{...agentData(9,8,true),workflowRevision:10},headers:{etag:'"9"'}},{data:{...agentData(9,8,true),revision:null},headers:{etag:'"8"'}}];
 for(const c of cases) {const read=request(cache,'post','/my-bot/bot-detail?botId=9');agentAccept(cache,read,c.data,c.headers);}
 assert.equal(agentWrite(cache).config.headers['If-Match'],'"2"');assert.equal(agentWrite(cache).config.headers['X-Workflow-Revision'],'"4"');
});
test('原智能体form删除只加两条件，删除回执令迟到读失效',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);const late=request(cache,'post','/my-bot/bot-detail?botId=9');
 const raw='botId=9&name=%E5%8E%9F%E5%BD%A2';const deleted=request(cache,'post','/my-bot/delete',raw,{headers:new AxiosHeaders({'Content-Type':'application/x-www-form-urlencoded'})});
 assert.equal(deleted.config.data,raw);assert.equal(deleted.config.headers.get('If-Match'),'"2"');assert.equal(deleted.config.headers.get('X-Workflow-Revision'),'"4"');
 cache.accept(deleted.ticket,{code:0,data:null},{});agentAccept(cache,late,{...agentData(9,7,true),workflowRevision:9});assert.equal(agentWrite(cache).config.headers['If-Match'],undefined);
 const duplicate=request(cache,'post','/my-bot/delete?botId=9','botId=9&botId=10',{headers:{'Content-Type':'application/x-www-form-urlencoded'}});assert.equal(duplicate.ticket,undefined);
});
test('智能体scope与混合缓存有界，淘汰/旧会话响应不复活',()=>{
 const cache=fixture(2);agentLoad(cache);const late=agentWrite(cache);load(cache,11,'eleven',1);agentLoad(cache,12);assert.equal(cache.size,2);assert.equal(agentWrite(cache).config.headers['If-Match'],undefined);agentAccept(cache,late,agentData(9,3));assert.equal(cache.size,2);
 const old=request(cache,'post','/my-bot/bot-detail?botId=12');cache.setScope('actor-b','5');agentAccept(cache,old,agentData(12,8));assert.equal(agentWrite(cache,12).config.headers['If-Match'],undefined);
 agentLoad(cache);cache.setScope('actor-b','6');cache.setScope('actor-b','5');assert.equal(agentWrite(cache).config.headers['If-Match'],undefined);
});
test('智能体long条件精确且外部或不同baseURL不传条件',()=>{
 const cache=fixture();agentLoad(cache,9,'9007199254740993',true);assert.equal(agentWrite(cache).config.headers['If-Match'],'"9007199254740993"');
 for(const url of ['https://app.example/bot/update','//external.example/bot/update']) assert.equal(request(cache,'post',url,{botId:9}).config.headers['If-Match'],undefined);
 assert.equal(request(cache,'post','/bot/update',{botId:9},{baseURL:'/another'}).config.headers['If-Match'],undefined);
 const unsafe=fixture();agentLoad(unsafe,9,9007199254740992,true);assert.equal(agentWrite(unsafe).config.headers['If-Match'],undefined);
});
test('实际HTTP原智能体详情/表单删除保持形状且发真实已读两条件',async()=>{
 const f=httpFixture();const config=await f.hooks.request({url:'/my-bot/bot-detail?botId=9',method:'post',headers:{}});const data=agentData(9,2,true);
 assert.equal(f.hooks.response({config,data:{code:0,data},headers:{etag:'"2"','x-workflow-revision':'"4"'}}),data);
 const raw='botId=9';const remove=await f.hooks.request({url:'/my-bot/delete',method:'post',data:raw,headers:new AxiosHeaders({'Content-Type':'application/x-www-form-urlencoded'})});
 assert.equal(remove.data,raw);assert.equal(remove.headers.get('If-Match'),'"2"');assert.equal(remove.headers.get('X-Workflow-Revision'),'"4"');
 f.hooks.response({config:remove,data:{code:0,data:null},headers:{}});const next=await f.hooks.request({url:'/bot/update',method:'post',data:{botId:9},headers:{}});assert.equal(next.headers['If-Match'],undefined);
});
test('智能体URL重复参数或config/query冲突不借用已读条件',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);
 for(const url of ['/bot/update?botId=9&botId=10','/bot/update?revision=1&revision=2','/bot/update?workflowRevision=3&workflowRevision=4']) {
  const req=request(cache,'post',url,{botId:9});assert.equal(req.ticket,undefined);assert.equal(req.config.headers['If-Match'],undefined);assert.equal(req.config.headers['X-Workflow-Revision'],undefined);
 }
 const conflicted=request(cache,'post','/bot/update?botId=9',{botId:9},{params:{botId:10}});assert.equal(conflicted.ticket,undefined);assert.equal(conflicted.config.headers['If-Match'],undefined);
});

function serviceTransport(f) {
 const pending=[];
 const send=(method,url,data)=>new Promise((resolve,reject)=>{
  f.hooks.request({method,url,data,headers:{}}).then(config=>pending.push({config,
   respond:(value,headers)=>Promise.resolve().then(()=>f.hooks.response({config,data:{code:0,data:value},headers})).then(resolve,reject)
  }),reject);
 });
 const service=execute('services/flow.ts',{'@/utils/http':{default:{get:url=>send('get',url),put:(url,data)=>send('put',url,data)}}});
 return {pending,service,ready:async()=>{await new Promise(resolve=>setImmediate(resolve));}};
}
test('原详情与保存服务经实际HTTP交付：GET迟到不能把旧画布配上新条件自动保存',async()=>{
 const f=httpFixture();const transport=serviceTransport(f);await httpRead(f);
 let canvas={nodes:[{id:'new-node'}],edges:[]};let delivered=0;
 const late=transport.service.getFlowDetailAPI('9').then(data=>{delivered++;canvas=JSON.parse(data.data);});
 const rejected=assert.rejects(late,error=>error.code===409);await transport.ready();
 const saved=transport.service.saveFlowAPI({id:9,flowId:'flow-nine',data:canvas});await transport.ready();
 assert.equal(transport.pending[1].config.headers['If-Match'],'"4"');
 transport.pending[1].respond({id:9,flowId:'flow-nine',revision:5,spaceId:5,data:JSON.stringify(canvas)},{etag:'"5"'});await saved;
 transport.pending[0].respond({id:9,flowId:'flow-nine',revision:4,spaceId:5,data:'{"nodes":[{"id":"old-node"}],"edges":[]}'},{etag:'"4"'});await rejected;
 assert.equal(delivered,0);assert.equal(canvas.nodes[0].id,'new-node');
 const autosave=transport.service.saveFlowAPI({id:9,flowId:'flow-nine',data:canvas});await transport.ready();
 const request=transport.pending[2].config;assert.equal(request.headers['If-Match'],'"5"');assert.equal(request.data.data.nodes[0].id,'new-node');assert.equal(Object.hasOwn(request.data,'revision'),false);
 transport.pending[2].respond({id:9,flowId:'flow-nine',revision:6,spaceId:5,data:JSON.stringify(canvas)},{etag:'"6"'});await autosave;
});
test('实际HTTP智能体迟到详情不交付旧表单，自身更新仍推进原读两条件',async()=>{
 const f=httpFixture();const read=await f.hooks.request({url:'/my-bot/bot-detail?botId=9',method:'post',headers:{}});
 const before={...agentData(9,2,true),name:'原元数据'};f.hooks.response({config:read,data:{code:0,data:before},headers:{etag:'"2"','x-workflow-revision':'"4"'}});
 const late=await f.hooks.request({url:'/my-bot/bot-detail?botId=9',method:'post',headers:{}});
 const write=await f.hooks.request({url:'/workflow/base-save',method:'post',data:{botId:9,name:'当前修改'},headers:{}});
 const after={...agentData(9,3,true),workflowRevision:5,name:'当前修改'};
 assert.equal(f.hooks.response({config:write,data:{code:0,data:after},headers:{etag:'"3"','x-workflow-revision':'"5"'}}),after);
 let delivered=false;await assert.rejects(Promise.resolve(f.hooks.response({config:late,data:{code:0,data:before},headers:{etag:'"2"','x-workflow-revision':'"4"'}})).then(()=>{delivered=true;}),error=>error.code===409);assert.equal(delivered,false);
 const next=await f.hooks.request({url:'/bot/update',method:'post',data:{botId:9,name:after.name},headers:{}});assert.equal(next.headers['If-Match'],'"3"');assert.equal(next.headers['X-Workflow-Revision'],'"5"');
});
test('实际HTTP仅最新详情和当前条件写可交付；迟到成功写不能替换新详情',async()=>{
 const f=httpFixture();await httpRead(f);
 const oldRead=await f.hooks.request({url:'/workflow?id=9',method:'get',headers:{}}),oldWrite=await f.hooks.request({url:'/workflow',method:'put',data:{id:9},headers:{}});
 const latest=await f.hooks.request({url:'/workflow?id=9',method:'get',headers:{}}),data={id:9,flowId:'flow-nine',revision:7,spaceId:5};
 assert.equal(f.hooks.response({config:latest,data:{code:0,data},headers:{etag:'"7"'}}),data);
 for(const config of [oldRead,oldWrite]) await assert.rejects(f.hooks.response({config,data:{code:0,data:{...data,revision:5}},headers:{etag:'"5"'}}),error=>error.code===409);
 assert.equal((await f.hooks.request({url:'/workflow',method:'put',data:{id:9},headers:{}})).headers['If-Match'],'"7"');
});
test('实际HTTP空间切换和绑定身份错误的跟踪响应均不交付；无关接口保留原返回',async()=>{
 const f=httpFixture();await httpRead(f);const old=await f.hooks.request({url:'/workflow?id=9',method:'get',headers:{}});f.setSpace('6');
 await assert.rejects(f.hooks.response({config:old,data:{code:0,data:{id:9,flowId:'flow-nine',revision:5,spaceId:5}},headers:{etag:'"5"'}}),error=>error.code===409);
 f.setSpace('5');const wrong=await f.hooks.request({url:'/my-bot/bot-detail?botId=9',method:'post',headers:{}});
 await assert.rejects(f.hooks.response({config:wrong,data:{code:0,data:agentData(10,2,true)},headers:{etag:'"2"','x-workflow-revision':'"4"'}}),error=>error.code===409);
 const other={original:['unchanged']};assert.equal(f.hooks.response({config:{url:'/textNode/config/list'},data:{code:0,data:other},headers:{}}),other);
});
test('原API精确路径不经dot段折叠，禁止条件头越出Axios中心前缀',()=>{
 const cache=fixture();load(cache);agentLoad(cache,9,2,true);
 for(const url of ['/../../workflow','/a/../workflow','/%2e%2e/workflow','/%2e./workflow','/a/%2E%2E/workflow','/workflow#ignored']) {
  const req=request(cache,'put',url,{id:9});assert.equal(req.ticket,undefined);assert.equal(req.config.headers['If-Match'],undefined);
 }
 for(const url of ['/../../bot/update','/x/%2e%2e/bot/update']) {
  const req=request(cache,'post',url,{botId:9});assert.equal(req.ticket,undefined);assert.equal(req.config.headers['If-Match'],undefined);assert.equal(req.config.headers['X-Workflow-Revision'],undefined);
 }
 assert.equal(request(cache,'put','/workflow?q=../%2e%2e',{id:9}).config.headers['If-Match'],'"4"');
});

test('默认个人空store只接收原null或空spaceId，内部正ID不被猜成默认域',()=>{
 const cache=fixture();cache.setScope('actor-a','');
 const wf=request(cache,'get','/workflow?id=9');assert.equal(cache.accept(wf.ticket,{code:0,data:{id:9,flowId:'private-nine',revision:4,spaceId:null}},{etag:'"4"'}),true);
 assert.equal(conditional(cache),'"4"');
 const wrong=request(cache,'get','/workflow?id=9');assert.equal(cache.accept(wrong.ticket,{code:0,data:{id:9,flowId:'private-nine',revision:5,spaceId:77}},{etag:'"5"'}),false);assert.equal(conditional(cache),'"4"');
 const agent=request(cache,'post','/my-bot/bot-detail?botId=10');assert.equal(cache.accept(agent.ticket,{code:0,data:{...agentData(10,2),spaceId:null}},{etag:'"2"'}),true);assert.equal(agentWrite(cache,10).config.headers['If-Match'],'"2"');
});
test('默认个人模型null详情和列表分别播种原表单与按钮条件，作用域切换后拒旧响应',()=>{
 const cache=fixture();cache.setScope('actor-a','');
 const detail=request(cache,'get','/api/model/detail?modelId=9&llmSource=0');assert.equal(modelAccept(cache,detail,modelData(9,4,{spaceId:null}),{etag:modelTag(9,4)}),true);
 assert.equal(modelWrite(cache,9,{spaceId:null}).config.headers['If-Match'],modelTag(9,4));
 const listing=request(cache,'post','/api/model/list',{type:2,spaceId:null});assert.equal(modelAccept(cache,listing,{records:[modelData(9,5,{spaceId:null})]}),true);
 assert.equal(modelWrite(cache,9,{spaceId:''}).config.headers['If-Match'],modelTag(9,4));assert.equal(request(cache,'get','/api/model/off?modelId=9').config.headers['If-Match'],modelTag(9,5));
 const late=request(cache,'get','/api/model/detail?modelId=9');cache.setScope('actor-b','');assert.equal(modelAccept(cache,late,modelData(9,8,{spaceId:null}),{etag:modelTag(9,8)}),false);assert.equal(modelWrite(cache).config.headers['If-Match'],undefined);
});
test('具名空间仍拒原默认null响应和null请求体，不把其他私域借给具名表单',()=>{
 const cache=fixture();
 for(const spaceId of [null,undefined]) {
  const wf=request(cache,'get','/workflow?id=9');assert.equal(cache.accept(wf.ticket,{code:0,data:{id:9,flowId:'private-nine',revision:4,spaceId}},{etag:'"4"'}),false);
  const agent=request(cache,'post','/my-bot/bot-detail?botId=9');assert.equal(cache.accept(agent.ticket,{code:0,data:{...agentData(),spaceId}},{etag:'"4"'}),false);
 }
 const model=request(cache,'get','/api/model/detail?modelId=9');assert.equal(modelAccept(cache,model,modelData(9,4,{spaceId:null}),{etag:modelTag(9,4)}),false);assert.equal(modelWrite(cache,9,{spaceId:null}).ticket,undefined);
 assert.equal(conditional(cache),undefined);assert.equal(agentWrite(cache).config.headers['If-Match'],undefined);
});
test('默认与具名空间标识只允许原空值或严格正整数，数组和对象不能借字符串转换播种条件',()=>{
 for(const scope of ['', '5']) {
  const cache=fixture();cache.setScope('actor-a',scope);
  for(const spaceId of [[],[null],[''],['5'],{},true,false,0,-1,1.5,NaN,'05',' 5 ','null','undefined']) {
   const wf=request(cache,'get','/workflow?id=9');assert.equal(cache.accept(wf.ticket,{code:0,data:{id:9,flowId:'invalid-nine',revision:4,spaceId}},{etag:'"4"'}),false);
   const agent=request(cache,'post','/my-bot/bot-detail?botId=9');assert.equal(cache.accept(agent.ticket,{code:0,data:{...agentData(),spaceId}},{etag:'"4"'}),false);
   const model=request(cache,'get','/api/model/detail?modelId=9');assert.equal(modelAccept(cache,model,modelData(9,4,{spaceId}),{etag:modelTag(9,4)}),false);assert.equal(modelWrite(cache,9,{spaceId}).ticket,undefined);
  }
  assert.equal(conditional(cache),undefined);assert.equal(agentWrite(cache).config.headers['If-Match'],undefined);assert.equal(modelWrite(cache).config.headers['If-Match'],undefined);
 }
});
function modelData(id=9,revision=4,extra={}) {return {id,modelId:id,spaceId:5,revision,llmSource:0,...extra};}
function modelTag(id,revision) {return `"paiflow-model-${id}-${revision}"`;}
function modelAccept(cache,req,data,headers={}) {return cache.accept(req.ticket,{code:0,data},headers);}
function modelLoad(cache,id=9,revision=4) {const req=request(cache,'get','/api/model/detail',undefined,{params:{modelId:id,llmSource:0}});assert.equal(modelAccept(cache,req,modelData(id,revision),{etag:modelTag(id,revision)}),true);return req;}
function modelWrite(cache,id=9,body={}) {return request(cache,'post','/api/model',{id,modelName:'原模型',...body});}
function modelList(cache,rows) {const req=request(cache,'post','/api/model/list',{type:2,page:1,pageSize:10});assert.equal(modelAccept(cache,req,{records:rows,total:rows.length,size:10,current:1,pages:1}),true);return req;}

test('原模型详情确切ID命名ETag只为同模型写条件，不借用智能体或画布版本',()=>{
 const cache=fixture();load(cache);agentLoad(cache,9,2,true);modelLoad(cache,9,6);
 const write=modelWrite(cache);assert.equal(write.config.headers['If-Match'],modelTag(9,6));assert.equal(write.config.headers['X-Workflow-Revision'],undefined);
 assert.equal(conditional(cache),'"4"');assert.equal(agentWrite(cache).config.headers['If-Match'],'"2"');
 const toggle=request(cache,'get','/api/model/off?modelId=9&llmSource=0');assert.equal(toggle.config.headers['If-Match'],modelTag(9,6));
 assert.equal(modelAccept(cache,toggle,true,{etag:modelTag(9,7)}),true);assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,6));
 assert.equal(request(cache,'get','/api/model/on?modelId=9&llmSource=0').config.headers['If-Match'],modelTag(9,7));
});

test('原模型列表实际行可给按钮读条件，但不替未读取或已打开表单解锁',()=>{
 const cache=fixture();modelList(cache,[modelData(9,4)]);assert.equal(modelWrite(cache).config.headers['If-Match'],undefined);
 assert.equal(request(cache,'get','/api/model/delete?modelId=9').config.headers['If-Match'],modelTag(9,4));
 modelLoad(cache,9,4);modelList(cache,[modelData(9,8)]);
 assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,4));assert.equal(request(cache,'get','/api/model/off?modelId=9&llmSource=0').config.headers['If-Match'],modelTag(9,8));
});

test('模型字符串成功与本地部署布尔回执仅用真实同ID ETag推进自身写条件',()=>{
 const cache=fixture();modelLoad(cache);const one=modelWrite(cache),two=modelWrite(cache);
 assert.equal(modelAccept(cache,one,'Model validation passed',{etag:modelTag(9,5)}),true);
 assert.equal(modelAccept(cache,two,'Model validation passed',{etag:modelTag(9,6)}),false);
 assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,5));
 const local=request(cache,'post','/api/model/local-model',{id:9,modelPath:'original-owned-path'});
 assert.equal(local.config.headers['If-Match'],modelTag(9,5));assert.equal(modelAccept(cache,local,true,{etag:modelTag(9,6)}),true);
 assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,6));
});

test('模型迟到详情和列表不交付旧配置，自身写入后也不能重新播种已删目录',()=>{
 const cache=fixture();modelLoad(cache);const late=request(cache,'get','/api/model/detail?modelId=9&llmSource=0');
 const list=request(cache,'post','/api/model/list',{type:2,page:1,pageSize:10}),write=modelWrite(cache);
 assert.equal(modelAccept(cache,write,'Model validation passed',{etag:modelTag(9,5)}),true);
 assert.equal(modelAccept(cache,late,modelData(9,4),{etag:modelTag(9,4)}),false);
 assert.equal(modelAccept(cache,list,{records:[modelData(9,4)]}),false);
 const deleted=request(cache,'get','/api/model/delete?modelId=9');const delayed=request(cache,'get','/api/model/detail?modelId=9&llmSource=0');
 assert.equal(modelAccept(cache,deleted,true),true);assert.equal(modelAccept(cache,delayed,modelData(9,99),{etag:modelTag(9,99)}),false);
 assert.equal(modelWrite(cache).config.headers['If-Match'],undefined);
});

test('模型详情仅最新请求交付，错误ID/空间/版本头与body不污染已读条件',()=>{
 const cache=fixture();modelLoad(cache);const before=request(cache,'get','/api/model/detail?modelId=9'),after=request(cache,'get','/api/model/detail?modelId=9');
 assert.equal(modelAccept(cache,after,modelData(9,8),{etag:modelTag(9,8)}),true);assert.equal(modelAccept(cache,before,modelData(9,9),{etag:modelTag(9,9)}),false);
 for(const [data,tag] of [[modelData(10,9),modelTag(10,9)],[modelData(9,9,{spaceId:6}),modelTag(9,9)],[modelData(9,9),modelTag(10,9)],[modelData(9,9),modelTag(9,10)],[modelData(9,9,{modelId:10}),modelTag(9,9)],[modelData(9,null),modelTag(9,9)],[modelData(9,9),'W/'+modelTag(9,9)],[modelData(9,9),'"9"'],[modelData(9,9),'malformed'],[modelData(9,9),false]]) {
  const read=request(cache,'get','/api/model/detail?modelId=9');assert.equal(modelAccept(cache,read,data,{etag:tag}),false);
 }
 assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,8));
});

test('模型原新建无条件且响应可建立新ID，未知或空成功回执不能赋予更新条件',()=>{
 const cache=fixture();const create=request(cache,'post','/api/model',{modelName:'new original'});
 assert.equal(create.config.headers['If-Match'],undefined);assert.equal(modelAccept(cache,create,'Model validation passed',{etag:modelTag(9,0)}),true);
 assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,0));
 const missing=request(cache,'post','/api/model',{modelName:'missing receipt'});assert.equal(modelAccept(cache,missing,null,{}),false);
 const read=request(cache,'get','/api/model/detail?modelId=10');assert.equal(modelAccept(cache,read,null,{etag:modelTag(10,1)}),false);
});

test('模型显式条件保留旧版本，不用当前列表/详情替冲突条件自动解锁',()=>{
 const cache=fixture();modelLoad(cache);
 const explicit=modelWrite(cache,9,{}, {headers:{}});
 const old=request(cache,'post','/api/model',{id:9,revision:2});assert.equal(old.config.headers['If-Match'],undefined);assert.equal(old.ticket.expectedRevision,'2');
 const nil=modelWrite(cache,9,{revision:null});assert.equal(nil.config.headers['If-Match'],undefined);
 const headers=new AxiosHeaders({'if-match':modelTag(9,2)}),valid=request(cache,'post','/api/model',{id:9},{headers});assert.equal(valid.ticket.expectedRevision,'2');assert.equal(headers.get('If-Match'),modelTag(9,2));
 const conflict=request(cache,'post','/api/model',{id:9,revision:2},{headers:{'If-Match':modelTag(9,4)}});assert.equal(conflict.ticket.expectedRevision,undefined);
 assert.equal(modelAccept(cache,conflict,'Model validation passed',{etag:modelTag(9,5)}),false);
 const wrong=request(cache,'post','/api/model',{id:9},{headers:{'If-Match':modelTag(10,4)}});assert.equal(wrong.ticket.expectedRevision,undefined);
 assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,4));
});

test('模型URL/config/body冲突、重复参数和官方能力不得获得个人模型条件',()=>{
 const cache=fixture();modelLoad(cache);
 for(const url of ['/api/model/off?modelId=9&modelId=10','/api/model/off?modelId=9&revision=1&revision=2','/api/model/off?modelId=9&llmSource=1']) {
  const req=request(cache,'get',url);assert.equal(req.ticket,undefined);assert.equal(req.config.headers['If-Match'],undefined);
 }
 assert.equal(request(cache,'get','/api/model/detail?modelId=9',undefined,{params:{modelId:10}}).ticket,undefined);
 assert.equal(request(cache,'post','/api/model?modelId=10',{id:9}).ticket,undefined);
 assert.equal(modelWrite(cache,9,{spaceId:6}).ticket,undefined);
 for(const type of [0,1]) {const req=request(cache,'post','/api/model/list',{type});assert.equal(req.ticket,undefined);}
 for(const url of ['/api/model/category-tree','/api/model/local-model/list','/api/model/rsa/public-key','/api/model/off-model','/api/model/versions/5']) assert.equal(request(cache,'get',url).ticket,undefined);
});

test('模型列表先整体校验后播种，错行和重复ID不能部分提升缓存',()=>{
 const cache=fixture();modelLoad(cache);
 for(const rows of [[modelData(9,5),modelData(10,5,{spaceId:6})],[modelData(9,5),modelData(9,6)],[modelData(9,3)]]) {
  const req=request(cache,'post','/api/model/list',{type:2});assert.equal(modelAccept(cache,req,{records:rows}),false);
  assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,4));assert.equal(request(cache,'get','/api/model/off?modelId=9').config.headers['If-Match'],modelTag(9,4));
 }
 const old=request(cache,'post','/api/model/list',{type:2}),latest=request(cache,'post','/api/model/list',{type:2});
 assert.equal(modelAccept(cache,latest,{records:[modelData(10,1)]}),true);assert.equal(modelAccept(cache,old,{records:[modelData(11,1)]}),false);
});

test('模型与Agent/WF共享总缓存上限且切换作用域后旧模型回执拒绝',()=>{
 const cache=fixture(2);load(cache,1,'one',1);modelLoad(cache,2,2);const old=modelWrite(cache,2);agentLoad(cache,3,3);assert.equal(cache.size,2);assert.equal(conditional(cache,1),undefined);
 modelList(cache,[modelData(4,4),modelData(5,5)]);assert.equal(cache.size,2);assert.equal(modelWrite(cache,2).config.headers['If-Match'],undefined);assert.equal(modelAccept(cache,old,'Model validation passed',{etag:modelTag(2,3)}),false);
 const late=request(cache,'get','/api/model/detail?modelId=4');cache.setScope('actor-b','5');assert.equal(modelAccept(cache,late,modelData(4,8),{etag:modelTag(4,8)}),false);assert.equal(cache.size,0);
});

test('模型long条件不丢精度且外部URL/dot段/另baseURL没有条件头',()=>{
 const cache=fixture();modelLoad(cache,'9007199254740993','9007199254740995');assert.equal(modelWrite(cache,'9007199254740993').config.headers['If-Match'],modelTag('9007199254740993','9007199254740995'));
 for(const url of ['https://app.example/api/model','//other.example/api/model','/../../api/model','/api/a/../model','/api/model#fragment']) {const req=request(cache,'post',url,{id:'9007199254740993'});assert.equal(req.ticket,undefined);assert.equal(req.config.headers['If-Match'],undefined);}
 assert.equal(request(cache,'post','/api/model',{id:'9007199254740993'},{baseURL:'/another'}).ticket,undefined);
});

test('原模型服务经实际HTTP保持body与返回形状，列表/详情读条件用于不同操作',async()=>{
 const f=httpFixture(),pending=[];
 const send=(method,url,data,extra={})=>new Promise((resolve,reject)=>f.hooks.request({method,url,data,headers:{},...extra}).then(config=>pending.push({config,respond:(value,headers={})=>Promise.resolve().then(()=>f.hooks.response({config,data:{code:0,data:value},headers})).then(resolve,reject)}),reject));
 const model=execute('services/model.ts',{'@/utils/http':{default:{get:(url,config)=>send('get',url,undefined,config),post:(url,data)=>send('post',url,data)}}});
 const ready=()=>new Promise(resolve=>setImmediate(resolve));
 const listing=model.getModelList({type:2,page:1,pageSize:10});await ready();const page={records:[modelData()],total:1,size:10,current:1,pages:1};pending[0].respond(page);assert.equal(await listing,page);
 const raw={id:9,modelName:'原模型',apiKey:'opaque-binding',apiKeyMasked:false};const withoutDetail=model.modelCreate(raw);await ready();assert.equal(pending[1].config.data,raw);assert.equal(pending[1].config.headers['If-Match'],undefined);pending[1].respond('rejected',{etag:modelTag(9,4)});await assert.rejects(withoutDetail,error=>error.code===409);
 const detail=model.getModelDetail({modelId:9,llmSource:0});await ready();const row=modelData();pending[2].respond(row,{etag:modelTag(9,4)});assert.equal(await detail,row);
 const late=model.getModelDetail({modelId:9,llmSource:0});const rejected=assert.rejects(late,error=>error.code===409);await ready();
 const write=model.modelCreate(raw);await ready();assert.equal(pending[4].config.headers['If-Match'],modelTag(9,4));assert.equal(pending[4].config.data,raw);pending[4].respond('Model validation passed',{etag:modelTag(9,5)});assert.equal(await write,'Model validation passed');
 pending[3].respond(row,{etag:modelTag(9,4)});await rejected;
 const off=model.enabledModelAPI(9,0,'off');await ready();assert.equal(pending[5].config.url,'/api/model/off?modelId=9&llmSource=0');assert.equal(pending[5].config.headers['If-Match'],modelTag(9,5));pending[5].respond(true,{etag:modelTag(9,6)});assert.equal(await off,true);
 const remove=model.deleteModelAPI(9);await ready();assert.equal(pending[6].config.url,'/api/model/delete?modelId=9');assert.equal(pending[6].config.headers['If-Match'],modelTag(9,6));pending[6].respond(true);assert.equal(await remove,true);
});

test('模型失败/未知字符串或跨ID删除回执即使带版本头也不提升或清除条件',()=>{
 const cache=fixture();modelLoad(cache);
 for(const value of [false,null,'unexpected',{},'']) {
  const write=modelWrite(cache);assert.equal(modelAccept(cache,write,value,{etag:modelTag(9,5)}),false);assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,4));
 }
 for(const raw of [modelTag(10,5),modelTag(9,4),'W/'+modelTag(9,5)]) {
  const deleted=request(cache,'get','/api/model/delete?modelId=9');assert.equal(modelAccept(cache,deleted,true,{etag:raw}),false);assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,4));
 }
 const list=request(cache,'post','/api/model/list',{type:2});modelLoad(cache,9,4);assert.equal(modelAccept(cache,list,{records:[modelData(9,4)]}),false);
});

test('模型启停保留原updateById成功布尔：on/off成功true，失败false不能推进条件',()=>{
 const cache=fixture();modelLoad(cache);
 const off=request(cache,'get','/api/model/off?modelId=9&llmSource=0');assert.equal(modelAccept(cache,off,false,{etag:modelTag(9,5)}),false);
 assert.equal(request(cache,'get','/api/model/on?modelId=9&llmSource=0').config.headers['If-Match'],modelTag(9,4));
 assert.equal(modelAccept(cache,off,true,{etag:modelTag(9,5)}),true);
 const on=request(cache,'get','/api/model/on?modelId=9&llmSource=0');assert.equal(on.config.headers['If-Match'],modelTag(9,5));assert.equal(modelAccept(cache,on,false,{etag:modelTag(9,6)}),false);assert.equal(modelAccept(cache,on,true,{etag:modelTag(9,6)}),true);
 assert.equal(modelWrite(cache).config.headers['If-Match'],modelTag(9,4));
});

const inputSync = (cache,id=9,extra={})=>request(cache,'post','/workflow/bot/get-inputs-type',{botId:id},extra);
const inputSyncReply = (cache,req,revision=3,data=[],id=9)=>cache.accept(req.ticket,{code:0,data},{etag:`"${revision}"`,'x-paiflow-agent-id':String(id)});
test('输入同步保持原字段数组和请求，只失效旧智能体条件，不推进工作流或模型条件',()=>{
 const cache=fixture();load(cache,19,'bound-flow',4);agentLoad(cache,9,2,true);modelLoad(cache,7,8);
 const sync=inputSync(cache);assert.equal(sync.ticket.kind,'sync');assert.equal(sync.config.headers['If-Match'],undefined);assert.equal(sync.config.headers['X-Workflow-Revision'],undefined);assert.deepEqual(sync.config.data,{botId:9});
 const fields=[{id:'q',name:'AGENT_USER_INPUT',schema:{type:'string'},required:null}];const before=JSON.stringify(fields);
 assert.equal(inputSyncReply(cache,sync,3,fields),true);assert.equal(JSON.stringify(fields),before);
 assert.equal(agentWrite(cache).config.headers['If-Match'],undefined);assert.equal(agentWrite(cache).config.headers['X-Workflow-Revision'],undefined);
 assert.equal(conditional(cache,19),'"4"');assert.equal(modelWrite(cache,7).config.headers['If-Match'],modelTag(7,8));
});
test('输入同步无变化回执保留条件，迟到较旧回执不清除已读的新详情',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);assert.equal(inputSyncReply(cache,inputSync(cache),2),true);assert.equal(agentWrite(cache).config.headers['If-Match'],'"2"');
 const sync=inputSync(cache);agentLoad(cache,9,5,true);assert.equal(inputSyncReply(cache,sync,3),true);assert.equal(agentWrite(cache).config.headers['If-Match'],'"5"');assert.equal(agentWrite(cache).config.headers['X-Workflow-Revision'],'"4"');
});
test('输入同步后旧详情响应不能重新安装条件，只有后续详情读取恢复条件',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);const old=request(cache,'post','/my-bot/bot-detail?botId=9'),sync=inputSync(cache);
 assert.equal(inputSyncReply(cache,sync,3),true);assert.equal(cache.accept(old.ticket,{code:0,data:agentData(9,2,true)},{etag:'"2"','x-workflow-revision':'"4"'}),false);
 assert.equal(agentWrite(cache).config.headers['If-Match'],undefined);agentLoad(cache,9,3,true);assert.equal(agentWrite(cache).config.headers['If-Match'],'"3"');
});
test('没有智能体缓存的输入同步仍保留原数组，阻止请求期间的旧详情安装条件',()=>{
 const cache=fixture();const pending=request(cache,'post','/my-bot/bot-detail?botId=9');
 assert.equal(inputSyncReply(cache,inputSync(cache),3,[{name:'q',schema:{type:'string'}}]),true);assert.equal(cache.size,0);
 assert.equal(cache.accept(pending.ticket,{code:0,data:agentData(9,2,true)},{etag:'"2"','x-workflow-revision':'"4"'}),false);
});
test('输入同步缺少或错配回执与非字段数组不能清除任何已读条件',()=>{
 const cache=fixture();agentLoad(cache,9,2,true);const sync=inputSync(cache);
 for(const headers of [{},{etag:'"3"'},{etag:'"3"','x-paiflow-agent-id':'10'},{etag:'W/"3"','x-paiflow-agent-id':'9'},{etag:'"9223372036854775808"','x-paiflow-agent-id':'9'}])assert.equal(cache.accept(sync.ticket,{code:0,data:[]},headers),false);
 for(const data of [null,{},'[]',[null],[[]],['wrong']])assert.equal(inputSyncReply(cache,sync,3,data),false);
 assert.equal(cache.accept(sync.ticket,{code:503,data:[]},{etag:'"3"','x-paiflow-agent-id':'9'}),true);assert.equal(agentWrite(cache).config.headers['If-Match'],'"2"');
});
test('输入同步按原身份和空间捕获响应，其他地址及矛盾ID不捕获',()=>{
 const cache=fixture();agentLoad(cache);const old=inputSync(cache);cache.setScope('actor-b','5');assert.equal(inputSyncReply(cache,old),false);cache.setScope('actor-a','5');agentLoad(cache);const oldSpace=inputSync(cache);cache.setScope('actor-a','6');assert.equal(inputSyncReply(cache,oldSpace),false);
 for(const url of ['https://app.example/workflow/bot/get-inputs-type','//other.example/workflow/bot/get-inputs-type','/workflow/../workflow/bot/get-inputs-type','/workflow/bot/get-inputs-type#x'])assert.equal(request(cache,'post',url,{botId:9}).ticket,undefined);
 assert.equal(request(cache,'get','/workflow/bot/get-inputs-type',{botId:9}).ticket,undefined);assert.equal(inputSync(cache,0).ticket,undefined);
 assert.equal(request(cache,'post','/workflow/bot/get-inputs-type?botId=10',{botId:9}).ticket,undefined);assert.equal(request(cache,'post','/workflow/bot/get-inputs-type?botId=9&botId=9',{botId:9}).ticket,undefined);
 assert.equal(inputSync(cache,9,{baseURL:'https://other.example/paiflow/legacy'}).ticket,undefined);
});
test('输入同步的long回执精确比较，超出安全整数的botId不参加缓存',()=>{
 const cache=fixture();agentLoad(cache,'9007199254740993','9007199254740995',true);const sync=inputSync(cache,'9007199254740993');
 assert.equal(inputSyncReply(cache,sync,'9007199254740996',[],'9007199254740993'),true);assert.equal(agentWrite(cache,'9007199254740993').config.headers['If-Match'],undefined);
 assert.equal(inputSync(cache,9007199254740992).ticket,undefined);
});
test('实际HTTP输入同步仍返回同一个原数组和botId请求，原详情再次读取才恢复更新条件',async()=>{
 const f=httpFixture();const detail=await f.hooks.request({url:'/my-bot/bot-detail?botId=9',method:'post',headers:{}});f.hooks.response({config:detail,data:{code:0,data:agentData(9,2,true)},headers:{etag:'"2"','x-workflow-revision':'"4"'}});
 const body={botId:'9'},sync=await f.hooks.request({url:'/workflow/bot/get-inputs-type',method:'post',data:body,headers:{}});assert.equal(sync.data,body);assert.equal(sync.headers['If-Match'],undefined);
 const fields=[{name:'q',schema:{type:'string'},allowedFileType:null}];assert.equal(f.hooks.response({config:sync,data:{code:0,data:fields},headers:{etag:'"3"','x-paiflow-agent-id':'9'}}),fields);
 const write=await f.hooks.request({url:'/bot/update',method:'post',data:{botId:9,name:'原表单'},headers:{}});assert.equal(write.headers['If-Match'],undefined);
 const read=await f.hooks.request({url:'/my-bot/bot-detail?botId=9',method:'post',headers:{}});f.hooks.response({config:read,data:{code:0,data:agentData(9,3,true)},headers:{etag:'"3"','x-workflow-revision':'"4"'}});
 assert.equal((await f.hooks.request({url:'/bot/update',method:'post',data:{botId:9},headers:{}})).headers['If-Match'],'"3"');
});
