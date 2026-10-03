// 仅操作127.0.0.1技术夹具，所有业务API在Vite中替换成本地Mock。
async page => {
  const base='http://127.0.0.1:18152',dir='D:/2025Ai/26-05-23/java-workspace/文档/项目/PaiSmart知识库项目/设计附录/',prefix='2026-10-03-菜单筛选评测入口-稳定回读页面-'
  const results=[]
  const shot=async name=>{const file=dir+prefix+name+'.png';await page.locator('.el-loading-mask').first().waitFor({state:'hidden'});await page.evaluate(()=>Promise.all(document.getAnimations().filter(a=>a.effect?.getTiming().iterations!==Infinity).map(a=>a.finished.catch(()=>{}))));await page.screenshot({path:file,scale:'css',fullPage:true});results.push({name,file,url:page.url(),calls:await page.locator('#fixture-calls').innerText()})}
  await page.goto(base+'/paismart/recharge?tradeNo=fixture-revoked')
  await page.getByText('订单已结束，扫码地址已撤下。').waitFor()
  if(!await page.getByRole('button',{name:'选择套餐',exact:true}).isEnabled())throw new Error('REVOKED错误阻止新套餐')
  await page.getByRole('combobox',{name:'订单状态',exact:true}).locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")]').click()
  await page.getByRole('option',{name:'支付失败',exact:true}).click()
  await page.getByRole('button',{name:'查询订单',exact:true}).click()
  await page.getByRole('button',{name:'选择原订单查询',exact:true}).click()
  if(!await page.getByRole('button',{name:'选择套餐',exact:true}).isEnabled())throw new Error('PAYERROR错误阻止新套餐')
  if(!await page.locator('#fixture-calls').innerText().then(text=>text.includes('"state":"PAYERROR"')))throw new Error('筛选没有传真实PAYERROR')
  await shot('支付失败终态')
  await page.getByRole('combobox',{name:'订单状态',exact:true}).locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")]').click()
  await page.getByRole('option',{name:'支付状态待核对',exact:true}).click()
  await page.getByRole('button',{name:'查询订单',exact:true}).click()
  await page.getByRole('button',{name:'选择原订单查询',exact:true}).click()
  await page.getByText(/当前停止自动查单/).waitFor()
  if(!await page.getByRole('button',{name:'选择套餐',exact:true}).isDisabled())throw new Error('UNCERTAIN保护未生效')
  await shot('待核对保护')
  return {kind:'本地Mock充值终态技术复验',results}
}

