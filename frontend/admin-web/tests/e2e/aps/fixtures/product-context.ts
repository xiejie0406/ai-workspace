import type {Route} from '@playwright/test'
/** 只补充各页面新增的只读目录；实际命令仍由每个场景独立断言。 */
export async function fulfillProductContext(route:Route,resources:unknown[]=[],run?:any){
  const fullPath=new URL(route.request().url()).pathname
  const path=fullPath.slice(fullPath.indexOf('/api/aps/v1/'))
  if(route.request().method()!=='GET')return false
  let json:unknown
  if(path==='/api/aps/v1/site')json={siteCode:'SITE-01',zoneId:'Asia/Shanghai'}
  else if(path==='/api/aps/v1/resources/directory')json={workshops:[],centers:[],resources}
  else if(path==='/api/aps/v1/resources/user-candidates'||path==='/api/aps/v1/reports/order-candidates'||path==='/api/aps/v1/execution-runs/dispatch-plans')json=[]
  else if(path==='/api/aps/v1/execution-runs'&&run)json=[{run:run.run,jobCode:'JOB-001',orderNumbers:'SO-001',taskNames:'焊接任务'}]
  else if(path.endsWith('/report-members')&&run)json=[{id:'90000000-0000-4000-8000-000000000004',memberNo:1,taskId:'90000000-0000-4000-8000-000000000005',taskName:'焊接任务',plannedQty:20,remainingQty:20,uomCode:'PCS'}]
  else return false
  await route.fulfill({json});return true
}
