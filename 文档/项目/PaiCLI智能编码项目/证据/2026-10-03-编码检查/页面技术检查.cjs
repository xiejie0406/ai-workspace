const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const frontend = path.resolve(__dirname, '../../../../../frontend/admin-web');
const { chromium } = require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');
const now = '2026-10-03T02:00:00Z';
const workspace = { id:'1',name:'协议测试仓库（受控数据）',rootPath:'fixture',rootFingerprint:'f'.repeat(64),status:'ACTIVE',version:1,role:'ADMIN',commandSandbox:'REQUIRED',dailyTokenBudget:64000,taskTokenBudget:32000 };
const runner = { id:'3',workspaceId:'1',name:'测试Runner',status:'ONLINE',version:1,platform:'Windows 11',distributionVersion:'development-unpackaged',capabilities:['files'],sandboxVerified:false,rootFingerprint:workspace.rootFingerprint };
const model = { id:'4',providerId:'5',modelId:'fixture-coding-model',contextWindow:128000,supportsImages:true,supportsThinking:true,supportsTools:true,capabilityKnown:true,enabled:true,version:1 };
const context = { sessionId:'2',version:7,estimatedTokens:400,contextWindow:128000,messages:2,compressionCount:0,externalMemoryPaused:false,rawLedgerAvailable:true,promptLayers:[],artifacts:[],diagnostics:[] };
const baseSession = { id:'2',title:'受控页面检查',workspaceId:'1',runnerId:'3',modelId:'4',mode:'REACT',approvalMode:'ASK',status:'ACTIVE',version:7,eventCursor:'12',createdAt:now,updatedAt:now,messages:[{id:'10',sequence:1,role:'user',content:'请说明当前文件变更。',createdAt:now},{id:'11',sequence:2,role:'assistant',content:'这是受控接口数据，仅用于页面技术检查。',createdAt:now}],approvals:[],context };
const historicalOnly=process.argv.includes('--historical-only');
const mcpOnly=process.argv.includes('--mcp-only');
function fixtureSession(){const value=structuredClone(baseSession);if(historicalOnly){value.status='HISTORICAL';value.runnerId=null;value.title='导入历史（受控只读）';}return value;}
let session = fixtureSession();
let searchQueries=0,graphQueries=0;
const requests = [];
function response(url, method, input) {
  const p = url.split('?')[0];
  requests.push({path:p,method,idempotency:input.key||null,version:input.version||null});
  if(p.endsWith('/workspaces'))return {code:200,rows:[workspace],total:1};
  if(p.endsWith('/runners'))return {code:200,data:[runner]};
  if(p.endsWith('/providers'))return {code:200,data:[{id:'5',workspaceId:'1',name:'受控供应商',provider:'deepseek',protocol:'OPENAI_COMPATIBLE',baseUrl:'https://fixture.invalid',secretConfigured:false,status:'DISABLED',version:1}]};
  if(p.endsWith('/models'))return {code:200,data:[model]};
  if(p.endsWith('/sessions'))return {code:200,rows:[session],total:1};
  if(p.endsWith('/sessions/2'))return {code:200,data:session};
  if(p.endsWith('/sessions/2/events'))return {code:200,data:{events:[],cursor:'12',hasMore:false,terminal:true}};
  if(p.endsWith('/sessions/2/turns')){session.activeTaskId='88';session.status='ENQUEUED';return {code:200,data:{taskId:'88',version:7,eventCursor:'12'}};}
  if(p.endsWith('/tasks'))return {code:200,rows:[{id:'88',title:'需要核对上次执行结果',workspaceId:'1',sessionId:'2',runnerId:'3',source:'WEB',mode:'REACT',status:'RECONCILE_REQUIRED',version:2,requesterName:'测试用户',createdAt:now,updatedAt:now,failureReason:'受控断线场景，不自动重跑副作用'}],total:1};
  if(p.endsWith('/memories'))return {code:200,rows:[{id:'91',workspaceId:'1',scope:'PROJECT',content:'使用中文说明变更',source:'USER',status:'VERIFIED',version:1,verifiedAt:now,createdAt:now},{id:'92',workspaceId:'1',scope:'PROJECT',content:'候选事实待用户核实',source:'AUTOMATIC',status:'PENDING',version:1,createdAt:now}],total:2};
  if(p.endsWith('/indexes'))return {code:200,data:{workspaceId:'1',status:'MISSING',stale:true,version:0}};
  if(p.endsWith('/search'))return {code:200,data:++searchQueries===1?{status:'PENDING',taskId:'188',sessionId:'2',message:'受控异步检索'}:[{file:'src/FixtureService.java',startLine:null,endLine:null,fileHash:null,snippet:'受控片段：行号和哈希均未上报',source:'EXACT',indexVersion:0,stale:true}]};
  if(p.endsWith('/graph'))return {code:200,data:++graphQueries===1?{status:'PENDING',taskId:'189',sessionId:'2',message:'受控异步关系图'}:{indexVersion:0,stale:true,nodes:[{id:'fixture-class',label:'FixtureService',type:'CLASS',file:null,line:null}],edges:[]}};
  if(p.endsWith('/browser'))return {code:200,data:{workspaceId:'1',mode:'ISOLATED',status:'DISCONNECTED',version:1,connected:false}};
  if(p.endsWith('/policies'))return {code:200,data:{...workspace,maxActiveSessions:2,maxQueuedTasks:100,maxReadTools:4,maxWriteTasks:1,commandTimeoutSeconds:60,approvalTimeoutSeconds:900,retentionDays:90,hardDeniedPaths:[],allowedCapabilities:['files']}};
  if(p.endsWith('/wechat'))return {code:200,data:{id:'8',workspaceId:'1',status:'NOT_CONFIGURED',version:1,bindings:[]}};
  if(p.endsWith('/mcp')&&mcpOnly)return {code:200,data:[{id:'61',workspaceId:'1',name:'受控 HTTP 服务',scope:'PROJECT',transport:'HTTP',url:'https://fixture.invalid/mcp',headers:{Accept:'application/json'},envSecretRefs:{},headerSecretRefs:{Authorization:'ai.paicli.1.mcp.auth'},configHash:'c'.repeat(64),trustedHash:null,status:'STOPPED',version:1,tools:[],resources:[],prompts:[]}]};
  if(p.endsWith('/audit'))return {code:200,rows:[],total:0};
  if(p.endsWith('/indexes')||p.endsWith('/mcp')||p.endsWith('/skills')||p.endsWith('/harness')||p.endsWith('/evaluations')||p.endsWith('/imports')||p.endsWith('/members')||p.endsWith('/snapshots'))return {code:200,data:[],rows:[],total:0};
  return {code:200,data:[],rows:[],total:0};
}
(async()=>{
  process.chdir(frontend);
  const {createServer}=await import(pathToFileURL(path.join(frontend,'node_modules/vite/dist/node/index.js')));
  const workbenchOnly=process.argv.includes('--workbench-only');
  const detailsOnly=process.argv.includes('--details-only');const scenes = mcpOnly?[]:historicalOnly?['workbench']:workbenchOnly?['workbench']:detailsOnly?['knowledge','connections']:['workbench','tasks','knowledge','connections','audit'];
  const componentScenes=['workbench','tasks','knowledge','connections','audit'];
  const moduleSource = `import {createApp,h} from 'vue';import {createPinia} from 'pinia';import {createRouter,createMemoryHistory} from 'vue-router';import ElementPlus from 'element-plus';import 'element-plus/dist/index.css';import '/src/assets/styles/index.scss';import zhCn from 'element-plus/es/locale/lang/zh-cn';import hasPermi from '/src/directive/permission/hasPermi.js';import useUserStore from '/src/store/modules/user.js';import Pagination from '/src/components/Pagination/index.vue';${componentScenes.map((s,i)=>`import P${i} from '/src/views/paicli/${s}/index.vue';`).join('')}const router=createRouter({history:createMemoryHistory(),routes:[${componentScenes.map((s,i)=>`{path:'/${s}',component:P${i}}`).join(',')}]});const pinia=createPinia();const app=createApp({render:()=>h('main',[h('p',{style:'margin:0;padding:12px;background:#fff2cc'},'受控接口fixture · 页面技术检查 · 尚未完成真实联机验收'),h(resolveRouter())])});function resolveRouter(){return {render:()=>h(router.currentRoute.value.matched[0].components.default)}}app.use(pinia);useUserStore().permissions=['*:*:*'];app.use(router);app.use(ElementPlus,{locale:zhCn});app.directive('hasPermi',hasPermi);app.component('Pagination',Pagination);await router.push('/'+(new URLSearchParams(location.search).get('scene')||'workbench')+'?workspaceId=1&sessionId=2');app.mount('#app');`;
  let server,browser;
  const report={kind:'CONTROLLED_FIXTURE_UI_TECHNICAL_CHECK',businessPassed:0,scenes:[],requests};
  try{
    server=await createServer({configFile:path.join(frontend,'vite.config.js'),cacheDir:path.join(frontend,'node_modules/.vite-paicli-check'),server:{host:'127.0.0.1',port:0,strictPort:true,open:false,proxy:{}},plugins:[{name:'paicli-controlled-test',resolveId(id){if(id==='virtual:paicli-test')return '\0paicli-test';},load(id){if(id==='\0paicli-test')return moduleSource;},configureServer(s){s.middlewares.use((req,res,next)=>{if(req.url.startsWith('/dev-api/')){let parts=[];req.on('data',d=>parts.push(d));req.on('end',()=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify(response(req.url,req.method,{key:req.headers['idempotency-key'],version:req.headers['if-match']})));});return;}if(req.url.startsWith('/__paicli-test')){session=fixtureSession();res.setHeader('Content-Type','text/html;charset=utf-8');res.end('<html><head><meta charset="utf-8"><title>PaiCLI页面技术检查</title></head><body style="margin:0"><div id="app"></div><script type="module" src="/@id/virtual:paicli-test"></script></body></html>');return;}next();});}}]});
    // Vite 将 port=0 回退为默认5173；先向OS取得测试端口，再严格监听，避免占用其他项目通道。
    const net=require('net');const reservation=net.createServer();await new Promise((resolve,reject)=>{reservation.once('error',reject);reservation.listen(0,'127.0.0.1',resolve);});const fixturePort=reservation.address().port;await new Promise(resolve=>reservation.close(resolve));
    await server.listen(fixturePort);const port=server.httpServer.address().port;
    browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
    const context=await browser.newContext({viewport:{width:1440,height:1100}});
    const page=await context.newPage();let errors=[];page.on('pageerror',e=>errors.push(e.message));
    for(const scene of scenes){errors=[];await page.goto(`http://127.0.0.1:${port}/__paicli-test?scene=${scene}`,{waitUntil:'domcontentloaded',timeout:60000});await page.waitForSelector('.pc-page',{timeout:30000}).catch(async error=>{fs.writeFileSync(path.join(__dirname,'页面检查异常.json'),JSON.stringify({scene,message:error.message,errors},null,2));await page.screenshot({path:path.join(__dirname,'页面检查异常.png'),fullPage:true,animations:'disabled'});error.message+='; JS='+JSON.stringify(errors);throw error;});await page.waitForTimeout(1500);await page.screenshot({path:path.join(__dirname,scene+'.png'),fullPage:true,animations:'disabled'});const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);report.scenes.push({scene,width:1440,overflow,errors:[...errors],title:await page.locator('h1').textContent()});if(scene==='knowledge'){await page.getByRole('tab',{name:'长期记忆与冲突',exact:true}).click();await page.getByText('使用中文说明变更',{exact:true}).waitFor();await page.screenshot({path:path.join(__dirname,'knowledge-memory.png'),fullPage:true,animations:'disabled'});report.memoryVisible=true;}if(scene==='connections'){await page.getByRole('tab',{name:'供应商与模型',exact:true}).click();await page.getByText('fixture-coding-model',{exact:true}).waitFor();await page.screenshot({path:path.join(__dirname,'connections-models.png'),fullPage:true,animations:'disabled'});report.modelVisible=true;}if(scene==='workbench'&&!historicalOnly){await page.locator('#pc-turn-text').fill('受控检查：说明变更与验证依据');await page.getByRole('button',{name:'发送任务',exact:true}).click();await page.waitForTimeout(700);await page.screenshot({path:path.join(__dirname,'workbench-sent.png'),fullPage:true,animations:'disabled'});report.sendDisabled=await page.getByRole('button',{name:'发送任务',exact:true}).isDisabled();}}
    if(mcpOnly){
      errors=[];await page.goto(`http://127.0.0.1:${port}/__paicli-test?scene=connections`,{waitUntil:'domcontentloaded',timeout:60000});await page.waitForSelector('.pc-page');
      await page.getByRole('tab',{name:'MCP 服务 / 资源 / 提示词',exact:true}).click();
      await page.getByRole('button',{name:'登记 MCP 服务',exact:true}).click();
      let dialog=page.getByRole('dialog',{name:'MCP 配置登记（保存后需重新确认信任）'});await dialog.waitFor();
      await dialog.getByText('环境凭证引用 JSON',{exact:true}).waitFor();
      report.mcpStdioReference=await dialog.locator('.el-form-item').filter({hasText:'环境凭证引用 JSON'}).locator('textarea').inputValue()==='{}';
      await dialog.getByText('只填写当前工作区 ai.paicli.1.* 密钥库引用；字段名为环境变量或 HTTP 头，原值不能录入。带凭证的 HTTP 连接须使用 HTTPS。',{exact:true}).waitFor();
      await page.screenshot({path:path.join(__dirname,'connections-mcp-stdio.png'),fullPage:true,animations:'disabled'});
      report.scenes.push({scene:'connections-mcp-stdio',width:1440,overflow:await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),errors:[...errors]});
      await dialog.getByRole('button',{name:'取消',exact:true}).click();
      await page.getByRole('button',{name:'修改配置',exact:true}).click();await dialog.waitFor();
      const refs=await dialog.locator('.el-form-item').filter({hasText:'请求头凭证引用 JSON'}).locator('textarea').inputValue();
      report.mcpHttpReference=JSON.parse(refs).Authorization==='ai.paicli.1.mcp.auth';
      report.mcpNoPlaintextInput=await dialog.locator('.el-form-item').filter({hasText:/密钥原值|凭据原值|认证原值/}).count()===0;
      await dialog.getByText('普通协议头 JSON',{exact:true}).waitFor();
      await page.screenshot({path:path.join(__dirname,'connections-mcp-http.png'),fullPage:true,animations:'disabled'});
      report.scenes.push({scene:'connections-mcp-http',width:1440,overflow:await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),errors:[...errors]});
      await dialog.getByRole('button',{name:'取消',exact:true}).click();report.mcpNoWrite=!requests.some(request=>request.method!=='GET');
    }else if(historicalOnly){
      await page.getByText('此会话是只读历史归档，旧任务与旧批准不能恢复执行。请新建会话绑定当前 Runner 与模型后继续工作。',{exact:true}).waitFor();
      report.historicalSendDisabled=await page.getByRole('button',{name:'发送任务',exact:true}).isDisabled();
      report.historicalNoWrite=!requests.some(request=>request.method==='POST');
      await page.screenshot({path:path.join(__dirname,'workbench-historical.png'),fullPage:true,animations:'disabled'});
      await page.getByRole('button',{name:'新建会话',exact:true}).click();
      report.newSessionDialog=await page.getByRole('dialog').count()===1;
      await page.screenshot({path:path.join(__dirname,'workbench-historical-new.png'),fullPage:true,animations:'disabled'});
    }else if(detailsOnly){
      errors=[];await page.goto(`http://127.0.0.1:${port}/__paicli-test?scene=knowledge`,{waitUntil:'domcontentloaded'});await page.waitForSelector('.pc-page');
      await page.getByPlaceholder('函数、类型、调用关系或关键词').fill('FixtureService');await page.getByRole('button',{name:'检索仓库',exact:true}).click();
      await page.getByText('src/FixtureService.java · 行号未上报',{exact:true}).waitFor();await page.getByText('未上报，须读取当前文件核对',{exact:false}).first().waitFor();
      await page.getByRole('button',{name:'查看关系图',exact:true}).click();await page.locator('svg[aria-labelledby="pc-graph-title"]').waitFor();
      await page.screenshot({path:path.join(__dirname,'knowledge-query-graph.png'),fullPage:true,animations:'disabled'});report.nullableMetadataVisible=true;
      report.scenes.push({scene:'knowledge-query-graph',width:1440,overflow:await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),errors:[...errors]});
      errors=[];await page.goto(`http://127.0.0.1:${port}/__paicli-test?scene=connections`,{waitUntil:'domcontentloaded'});await page.waitForSelector('.pc-page');
      await page.getByRole('button',{name:'隔离核验',exact:true}).click();const dialog=page.getByRole('dialog',{name:'登记 Runner 隔离核验'});await dialog.waitFor();
      await dialog.getByText('此设备平台没有可用的 seatbelt / bubblewrap 隔离核验；命令执行保持不可用，不能登记为已验证。',{exact:true}).waitFor();
      report.sandboxNoSubmit=await dialog.getByRole('button',{name:'按当前设备版本登记',exact:true}).count()===0;
      report.sandboxNoWrite=!requests.some(request=>request.path.endsWith('/sandbox')&&request.method==='POST');
      await page.screenshot({path:path.join(__dirname,'connections-sandbox-windows.png'),fullPage:true,animations:'disabled'});
      report.scenes.push({scene:'connections-sandbox-windows',width:1440,overflow:await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),errors:[...errors]});
    }else{
      errors=[];await page.setViewportSize({width:1024,height:1100});await page.goto(`http://127.0.0.1:${port}/__paicli-test?scene=workbench`,{waitUntil:'domcontentloaded',timeout:60000});await page.waitForSelector('.pc-page');await page.waitForTimeout(700);await page.screenshot({path:path.join(__dirname,'workbench-1024.png'),fullPage:true,animations:'disabled'});report.scenes.push({scene:'workbench',width:1024,overflow:await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),errors:[...errors]});
    }
    fs.writeFileSync(path.join(__dirname,mcpOnly?'MCP配置定向检查.json':historicalOnly?'历史会话定向检查.json':workbenchOnly?'工作台定向复验.json':detailsOnly?'记忆与模型定向检查.json':'页面技术检查.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify({scenes:report.scenes,sendDisabled:report.sendDisabled,requestCount:requests.length,businessPassed:0}));
    if(report.scenes.some(s=>s.overflow||s.errors.length)||(!mcpOnly&&!historicalOnly&&!detailsOnly&&!report.sendDisabled)||(mcpOnly&&(!report.mcpStdioReference||!report.mcpHttpReference||!report.mcpNoPlaintextInput||!report.mcpNoWrite))||(historicalOnly&&(!report.historicalSendDisabled||!report.historicalNoWrite||!report.newSessionDialog))||(detailsOnly&&(!report.memoryVisible||!report.modelVisible||!report.nullableMetadataVisible||!report.sandboxNoSubmit||!report.sandboxNoWrite)))process.exitCode=1;
  }finally{if(browser)await browser.close();if(server)await server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});






