import type {Page} from '@playwright/test'
/** 按显式开发检查参数留图；模拟数据图片不能作为真实业务验收证据。 */
export async function captureDevelopmentPage(page:Page,name:string){
  const directory=(globalThis as {process?:{env:Record<string,string|undefined>}}).process?.env.APS_DEV_SCREENSHOT_DIR
  if(!directory)return
  await page.screenshot({path:`${directory}/${name}.png`,fullPage:true})
}
