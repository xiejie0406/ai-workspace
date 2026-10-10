const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
function storage(initial={}) { const values=new Map(Object.entries(initial));return {getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)}; }
function execute(file,imports,sandbox={}) {
 const source=fs.readFileSync(path.join(__dirname,'../src',file),'utf8').replaceAll('import.meta.env','__buildEnv');
 const exports={};
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX}}).outputText,{exports,URL,...sandbox,require:name=>{if (!(name in imports)) throw new Error(`Unexpected import ${name}`);return imports[name];}});
 return exports;
}
function httpFixture(runtime={}) {
 const errors=[];const hooks={};const redirects=[];
 const axios={defaults:{headers:{common:{}}},CancelToken:function(callback){callback(()=>{});},interceptors:{request:{use:f=>hooks.request=f},response:{use:f=>hooks.response=f}},isCancel:()=>false};
 const localStorage=storage({accessToken:'platform-fixture-without-exp',refreshToken:'old-casdoor-fixture'});
 const window={__APP_CONFIG__:runtime,location:{pathname:'/workflow',hostname:'localhost',origin:'https://app.example'}};
 const module=execute('utils/http.ts',{
  axios:{default:axios},qs:{default:{stringify:JSON.stringify}},antd:{message:{error:m=>errors.push(m)}},
  '../../package.json':{default:{version:'fixture'}},'@/locales/zh':{zh:{}},'@/locales/en':{en:{}},
  '@/locales/i18n':{default:{language:'zh',on:()=>{}}},'@/utils/event-bus':{default:{emit:()=>{}}},
  '@/store/space-store':{default:{getState:()=>({spaceType:'personal',spaceId:'fixture-space'}),setState:()=>{}}},
  './auth':{handleLoginRedirect:()=>redirects.push(true)},
  './workflow-revision':execute('utils/workflow-revision.ts',{}),
 },{localStorage,window,document:{cookie:''},__buildEnv:{},console});
 return {module,hooks,errors,redirects,localStorage};
}
test('original HTTP instance sends the platform token without contacting Casdoor',async()=>{
 const f=httpFixture();const request=await f.hooks.request({url:'/user-info/me',method:'get',headers:{}});
 assert.equal(request.headers.Authorization,'Bearer platform-fixture-without-exp');
 assert.equal(f.module.baseURL,'/paiflow/legacy');
 assert.equal(f.localStorage.getItem('refreshToken'),'old-casdoor-fixture');
});
test('legacy zero and platform 200 envelopes are handled explicitly',async()=>{
 const f=httpFixture();const payload={items:['fixture']};
 assert.equal(f.hooks.response({config:{},data:{code:0,data:payload}}),payload);
 const captcha={code:200,captchaEnabled:true,img:'fixture',uuid:'fixture'};
 assert.equal(f.hooks.response({config:{platformResponse:true},data:captcha}),captcha);
 await assert.rejects(Promise.resolve().then(()=>f.hooks.response({config:{},data:{code:200,msg:'Wrong envelope'}})));
 assert.equal(f.errors[0],'Wrong envelope');
});
test('HTTP-200 unauthorized response still redirects and rejects',async()=>{
 const f=httpFixture();await assert.rejects(Promise.resolve().then(()=>f.hooks.response({config:{},data:{code:401,msg:'Session expired'}})));
 assert.equal(f.redirects.length,1);
});
test('runtime centre configuration also applies in production',()=>{
 assert.equal(httpFixture({BASE_URL:'https://centre.example/api/paiflow/legacy'}).module.baseURL,'https://centre.example/api/paiflow/legacy');
});
function authFixture(fail=false,redirect='/workflow?fixture=1') {
 const errors=[];const calls=[];const localStorage=storage({accessToken:'fixture',refreshToken:'old'});
 const sessionStorage=storage({postLoginRedirect:redirect});const window={location:{origin:'https://app.example',pathname:'/workflow',search:'?id=1',href:'/workflow'}};
 const module=execute('utils/auth.ts',{'@/services/platform-auth':{logoutPlatformSession:async()=>{calls.push(localStorage.getItem('accessToken'));if(fail)throw new Error('offline');}},antd:{message:{error:m=>errors.push(m)}}},{window,localStorage,sessionStorage});
 return {module,window,localStorage,sessionStorage,errors,calls};
}
test('logout deletes local tokens only after the platform session is revoked',async()=>{
 const f=authFixture();await f.module.handleLogout();assert.deepEqual(f.calls,['fixture']);
 assert.equal(f.localStorage.getItem('accessToken'),null);assert.equal(f.localStorage.getItem('refreshToken'),null);assert.equal(f.window.location.href,'/login');
});
test('logout network failure remains retryable and never reports success',async()=>{
 const f=authFixture(true);await f.module.handleLogout();assert.equal(f.localStorage.getItem('accessToken'),'fixture');assert.equal(f.errors.length,1);assert.equal(f.window.location.href,'/workflow');
});
test('login redirect keeps an internal return path and removes obsolete tokens',()=>{
 const f=authFixture();f.module.handleLoginRedirect();assert.equal(f.sessionStorage.getItem('postLoginRedirect'),'/workflow?id=1');assert.equal(f.localStorage.getItem('accessToken'),null);assert.equal(f.window.location.href,'/login');
});
test('unauthorized requests on the login page clear tokens without reloading or losing the return path',()=>{
 for(const pathname of ['/login','/login/']) {
  const f=authFixture();f.window.location.pathname=pathname;f.window.location.search='?next=/workflow';
  let navigations=0;
  Object.defineProperty(f.window.location,'href',{get:()=>pathname+'?next=/workflow',set:()=>{navigations++;}});
  f.module.handleLoginRedirect();f.module.handleLoginRedirect();
  assert.equal(navigations,0);assert.equal(f.localStorage.getItem('accessToken'),null);assert.equal(f.localStorage.getItem('refreshToken'),null);
  assert.equal(f.sessionStorage.getItem('postLoginRedirect'),'/workflow?fixture=1');
 }
});
test('unauthorized callback still returns to login without replacing the saved return path',()=>{
 const f=authFixture();f.window.location.pathname='/callback';f.module.handleLoginRedirect();
 assert.equal(f.window.location.href,'/login');assert.equal(f.localStorage.getItem('accessToken'),null);
 assert.equal(f.sessionStorage.getItem('postLoginRedirect'),'/workflow?fixture=1');
});
test('post-login redirect rejects external URLs and login loops',()=>{
 for(const url of ['//evil.example','https://evil.example','/\\evil.example','/login?next=/','/callback','/\n/evil.example','/workflow/../login']) assert.equal(authFixture(false,url).module.getPostLoginRedirect(),'/home');
 assert.equal(authFixture(false,'/workflow?id=3').module.getPostLoginRedirect(),'/workflow?id=3');
});
test('captcha and logout reuse the same HTTP instance and configured platform root',async()=>{
 const calls=[];const window={__APP_CONFIG__:{}};
 const http={get:(url,config)=>{calls.push({url,config});return Promise.resolve({code:200});},post:(url,data,config)=>{calls.push({url,config});return Promise.resolve({code:200});}};
 const module=execute('services/platform-auth.ts',{'@/utils/http':{default:http,baseURL:'/gateway/paiflow/legacy'}},{window});
 await module.getCaptchaImage();await module.logoutPlatformSession();assert.equal(calls[0].url,'/captchaImage');assert.equal(calls[0].config.baseURL,'/gateway');assert.equal(calls[1].url,'/logout');assert.equal(calls[1].config.platformResponse,true);
 window.__APP_CONFIG__.PLATFORM_BASE_URL='https://centre.example/api';assert.equal(module.getPlatformBaseURL(),'https://centre.example/api');
});
test('token storage retains a platform token without exp and never persists an old refresh token',()=>{
 const localStorage=storage();
 const module=execute('hooks/use-login.ts',{react:{useCallback:f=>f,useState:initial=>[initial,()=>{}]},'@/utils/http':{default:{get:async()=>({})}}},{localStorage,window:{atob:value=>Buffer.from(value,'base64').toString()}});
 assert.equal(module.tokenStorage.isAccessTokenExpired(),true);
 module.tokenStorage.setTokens({accessToken:'platform-token-without-exp',refreshToken:'obsolete'});
 assert.equal(module.tokenStorage.isAccessTokenExpired(),false);assert.equal(module.tokenStorage.getRefreshToken(),null);
});
test('session check uses the protected centre endpoint and preserves token on network failure',async()=>{
 const localStorage=storage({accessToken:'platform-fixture'});const calls=[];
 const module=execute('hooks/use-login.ts',{react:{useCallback:f=>f,useState:initial=>[initial,()=>{}]},'@/utils/http':{default:{get:async url=>{calls.push(url);throw {code:100};}}}},{localStorage});
 assert.equal(await module.default().refreshToken(),false);assert.deepEqual(calls,['/api/session']);assert.equal(localStorage.getItem('accessToken'),'platform-fixture');
});

test('登录与回调入口不发起业务身份和空间初始化，业务入口保留初始化',async()=>{
 for(const pathname of ['/login','/login/','/callback','/callback/','/home','/login-other']) {
  const effects=[];const calls=[];const record=name=>async()=>{calls.push(name);};
  const module=execute('App.tsx',{
   react:{useCallback:f=>f,useEffect:f=>effects.push(f),useState:v=>[v,()=>{}]},
   'react/jsx-runtime':{jsx:()=>null,Fragment:'fragment'},
   'react-router-dom':{RouterProvider:()=>null},'@/router':{default:{}},
   '@/store/user-store':{default:selector=>selector({getUserInfo:record('user')})},
   './hooks/use-enterprise':{useEnterprise:()=>({getJoinedEnterpriseList:record('joined'),getEnterpriseSpaceCount:record('count'),visitEnterprise:record('visit')})},
   './hooks/use-space-type':{useSpaceType:()=>({getLastVisitSpace:record('last'),enterpriseId:'',switchToPersonal:record('switch'),isTeamSpace:()=>false})},
  },{window:{location:{pathname}},sessionStorage:storage()});
  module.default();for(const effect of effects)effect();await Promise.resolve();
  assert.deepEqual(calls,/^\/(login|callback)(\/|$)/.test(pathname)?[]:['user','last','count','joined'],pathname);
 }
});
