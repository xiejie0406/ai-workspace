// 只补拍实际受影响的右栏布局，两张先前截图保留。
async page=>{
 const base='http://127.0.0.1:18152',dir='D:/2025Ai/26-05-23/java-workspace/文档/项目/PaiSmart知识库项目/设计附录/',results=[]
 await page.goto(base+'/')
 await page.getByRole('button',{name:'会话与引用夹具',exact:true}).click()
 for(const name of ['甲','乙']){
  await page.getByRole('button',{name:'夹具会话'+name,exact:true}).click()
  await page.locator('.reference-list').getByText('技术夹具：可疑引用'+name,{exact:false}).waitFor()
  await page.locator('.reference-list').getByText('技术夹具：期限与该句不同，请核对原文',{exact:true}).waitFor()
  const display=await page.locator('.reference-button>span').first().evaluate(element=>getComputedStyle(element).display)
  if(display!=='block')throw new Error('引用按钮内部未采用块布局')
  if((await page.locator('.reference-list').innerText()).includes('引用'+(name==='甲'?'乙':'甲')))throw new Error('旧会话引用残留')
  await page.evaluate(()=>Promise.all(document.getAnimations().filter(a=>a.effect?.getTiming().iterations!==Infinity).map(a=>a.finished.catch(()=>{}))))
  const file=dir+'2026-10-03-引用主空间检索-终版页面-会话'+name+'核验反馈.png'
  await page.screenshot({path:file,scale:'css',fullPage:true})
  results.push({name:'会话'+name+'核验反馈',file,url:page.url(),calls:await page.locator('#fixture-calls').innerText()})
 }
 return{kind:'本地Mock引用布局定向复验',realBusinessExecuted:false,results}
}
