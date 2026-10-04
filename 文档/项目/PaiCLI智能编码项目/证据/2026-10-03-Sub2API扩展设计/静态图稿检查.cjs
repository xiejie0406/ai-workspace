// 仅渲染本地设计图，不访问用户实例、不接管用户浏览器或原验收页。
const fs=require('fs'),path=require('path'),{pathToFileURL}=require('url');
const {chromium}=require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{
 const workspace=path.resolve(__dirname,'../../../../../');
 const project=path.resolve(__dirname,'../..');
 const general=path.join(workspace,'文档/若依框架/功能/FEAT-RUOYI-001-平台与AI基础设施');
 const errors=[],network=[],results=[];
 const generalOnly=process.argv.includes('--general-only');
 const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 try{
 const page=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});
 page.on('pageerror',e=>errors.push(String(e)));
 await page.route(/^https?:/,route=>{network.push(route.request().url());return route.abort();});
 if(!generalOnly){
 await page.goto(pathToFileURL(path.join(project,'图稿/Sub2API供应商扩展设计图.html')).href);
 const states=[['normal','新增配置'],['missing','未录入密钥'],['validating','验证中'],['failed','兼容失败'],['denied','权限拒绝'],['changed','配置版本变化']];
 for(const [state,name] of states){await page.locator('#state').selectOption(state);await page.screenshot({path:path.join(__dirname,`${name}设计状态.png`),fullPage:true});results.push({document:'PaiCLI设计图',state,name,...await page.evaluate(()=>({viewport:innerWidth,body:document.documentElement.scrollWidth,status:document.getElementById('status').textContent}))});}
 await page.locator('#state').selectOption('normal');
 await page.screenshot({path:path.join(project,'图稿/Sub2API供应商扩展设计图.png'),fullPage:true});
 await page.locator('#wire').selectOption('responses');
 results.push({action:'选择Responses',path:await page.locator('#path').textContent()});
 await page.locator('#probe').click();await page.screenshot({path:path.join(__dirname,'真实验证提示设计状态.png'),fullPage:true});
 results.push({action:'验证提示弹窗',visible:await page.locator('#dialog').isVisible()});await page.locator('#close').click();
 await page.setViewportSize({width:1280,height:800});await page.screenshot({path:path.join(__dirname,'1280视口设计图.png'),fullPage:true});
 results.push({document:'PaiCLI设计图',...await page.evaluate(()=>({viewport:innerWidth,body:document.documentElement.scrollWidth}))});
 }
 await page.setViewportSize({width:1440,height:1100});await page.goto(pathToFileURL(path.join(general,'统一文本通道线框图.html')).href);
 await page.screenshot({path:path.join(__dirname,'总体候选线框图.png'),fullPage:true});results.push({document:'总体候选HTML',...await page.evaluate(()=>({viewport:innerWidth,body:document.documentElement.scrollWidth}))});
 await page.setContent(`<html><body style="margin:0"><img id="svg" style="display:block;width:1440px;height:1040px" src="${pathToFileURL(path.join(general,'统一文本通道界面图.svg')).href}"></body></html>`);
 await page.locator('#svg').evaluate(img=>img.decode());
 await page.locator('#svg').screenshot({path:path.join(__dirname,'总体候选界面图.png'),timeout:15000});
 }finally{await browser.close();}
 const report={kind:'静态设计图渲染，非真实产品验收',date:'2026-10-04',browser:'独立headless Edge',results,errors,network};
 fs.writeFileSync(path.join(__dirname,generalOnly?'总体图稿复验.json':'静态图稿检查.json'),JSON.stringify(report,null,2));
 console.log(JSON.stringify(report));if(errors.length||network.length||results.some(r=>r.body>r.viewport))process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
