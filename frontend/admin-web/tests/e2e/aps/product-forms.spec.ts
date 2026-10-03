import {expect,test} from '@playwright/test'
import {captureDevelopmentPage} from './fixtures/development-capture'

test('多阶段工序保存完整角色，服务端拒绝后保留编辑输入',async({page})=>{
  let submitted:any
  await page.route('**/api/aps/v1/**',async route=>{
    const path=new URL(route.request().url()).pathname
    if(route.request().method()==='POST'){
      submitted=route.request().postDataJSON()
      return route.fulfill({status:409,json:{detail:'工序已被其他人修改，请回查'}})
    }
    if(path.endsWith('/directory'))return route.fulfill({json:{workshops:[],centers:[{id:'c1',code:'WC-1',name:'焊接中心',status:'ACTIVE'}],resources:[]}})
    return route.fulfill({json:[]})
  })
  await page.setViewportSize({width:1400,height:1100})
  await page.goto('/tests/e2e/aps/fixtures/routing.html')
  await page.getByRole('button',{name:'新增工序'}).click()
  const dialog=page.getByRole('dialog')
  await dialog.getByLabel('工序编码',{exact:true}).fill('OP-WELD')
  await dialog.getByLabel('工序名称',{exact:true}).fill('焊接组合')
  await dialog.getByRole('button',{name:'添加人员/设备要求'}).click()
  await dialog.locator('.requirement').nth(1).locator('.el-select').first().click()
  await page.getByRole('option',{name:'设备',exact:true}).click()
  await dialog.getByRole('button',{name:'添加阶段',exact:true}).click()
  await dialog.getByLabel('阶段名称',{exact:true}).nth(1).fill('卸料')
  await dialog.locator('.phase-card').nth(1).locator('.el-select').first().click()
  await page.getByRole('option',{name:'卸料',exact:true}).click()
  await dialog.getByRole('button',{name:'保存草稿'}).click()
  await expect(dialog.getByText('工序已被其他人修改，请回查')).toBeVisible()
  await expect(dialog.getByLabel('工序名称',{exact:true})).toHaveValue('焊接组合')
  expect(submitted.phases).toHaveLength(2)
  expect(submitted.phases[0].requirements.map((r:any)=>r.resourceType)).toEqual(['PERSON','MACHINE'])
  expect(submitted.phases.map((p:any)=>p.phaseNo)).toEqual([1,2])
  expect(submitted.phases.map((p:any)=>p.phaseType)).toEqual(['RUN','UNLOAD'])
  await captureDevelopmentPage(page,'工序第二阶段')
  await dialog.getByText('工序已被其他人修改，请回查').scrollIntoViewIfNeeded()
  await captureDevelopmentPage(page,'工序多阶段与失败保留')
})

test('按业务名称执行完整预检，只提交检查命令并定位缺失资源',async({page})=>{
  const writes:string[]=[]
  const workshopId='00000000-0000-4000-8000-000000000001',orderId='00000000-0000-4000-8000-000000000002'
  await page.route('**/api/aps/v1/**',async route=>{
    const path=new URL(route.request().url()).pathname
    if(route.request().method()==='POST'){
      writes.push(path)
      const input=route.request().postDataJSON()
      expect(input.workshopIds).toEqual([workshopId]);expect(input.orderIds).toEqual([orderId])
      expect(input.horizon.startAt).toMatch(/Z$/)
      return route.fulfill({json:{ready:false,checkedAt:'2026-10-01T00:00:00Z',issues:[{code:'MISSING_SKILL',objectType:'RESOURCE',objectId:'r1',message:'焊接人员缺少有效技能'}]}})
    }
    if(path.endsWith('/site'))return route.fulfill({json:{siteCode:'SITE-01',zoneId:'Asia/Shanghai'}})
    if(path.endsWith('/directory'))return route.fulfill({json:{workshops:[{id:workshopId,code:'WS-1',name:'焊接车间',status:'ACTIVE'}],centers:[],resources:[{id:'r1',code:'P-1',name:'张三',type:'PERSON',status:'ACTIVE'}]}})
    if(path.endsWith('/orders'))return route.fulfill({json:[{id:orderId,orderNo:'SO-1001',status:'RELEASED',lines:[{id:'l1',lineNo:1}]}]})
    return route.fulfill({status:404,json:{}})
  })
  await page.goto('/tests/e2e/aps/fixtures/readiness.html')
  await page.getByLabel('车间',{exact:true}).click()
  await page.getByRole('option',{name:'WS-1 · 焊接车间'}).click();await page.keyboard.press('Escape')
  await page.getByLabel('已释放订单',{exact:true}).click()
  await page.getByRole('option',{name:'SO-1001 · 1个产品行'}).click();await page.keyboard.press('Escape')
  await page.getByRole('button',{name:'检查完整排程数据'}).click()
  await expect(page.getByText('焊接人员缺少有效技能')).toBeVisible()
  await expect(page.getByText('张三',{exact:true})).toBeVisible()
  await expect(page.getByRole('link',{name:'进入资料修正'})).toHaveAttribute('href','/aps/resources?resourceId=r1')
  expect(writes).toHaveLength(1);expect(writes[0]).toMatch(/\/readiness$/)
  await expect(page.getByRole('button',{name:'发起排程',exact:true})).toHaveCount(0)
  await captureDevelopmentPage(page,'完整预检与名称定位')
})
