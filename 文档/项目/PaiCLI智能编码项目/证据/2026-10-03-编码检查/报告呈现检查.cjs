const fs=require('fs');
const path=require('path');
const {pathToFileURL}=require('url');
const {chromium}=require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');
(async()=>{
  let browser;const errors=[];
  try{
    browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
    const page=await browser.newPage({viewport:{width:1440,height:1100}});
    page.on('pageerror',error=>errors.push(error.message));
    await page.goto(pathToFileURL(path.join(__dirname,'verification-report.html')).href,{waitUntil:'load'});
    // 回读的是已经执行并保存的实际页面证据，不执行业务动作。
    await page.locator('img').evaluateAll(images=>{
      images.forEach(image=>{image.loading='eager';});
      return Promise.all(images.map(image=>image.decode()));
    });
    const result=await page.evaluate(()=>({title:document.title,screenshots:document.querySelectorAll('article').length,
      imagesValid:[...document.images].every(image=>image.complete&&image.naturalWidth>0),
      overflow:document.documentElement.scrollWidth>innerWidth,
      businessPassed:0}));
    result.errors=errors;
    await page.screenshot({path:path.join(__dirname,'技术报告呈现.png'),animations:'disabled'});
    fs.writeFileSync(path.join(__dirname,'报告呈现检查.json'),JSON.stringify(result,null,2));
    console.log(JSON.stringify(result));
    if(!result.imagesValid||result.overflow||errors.length)process.exitCode=1;
  }finally{if(browser)await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
