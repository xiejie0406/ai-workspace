import {test,expect as baseExpect,type Page,type TestInfo} from '@playwright/test'
import {readFileSync,writeFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {installAipptFixture} from './fixture'

const expect=baseExpect.configure({timeout:15000})
test.setTimeout(180000)
test.use({screenshot:'only-on-failure',trace:'retain-on-failure'})
const material=fileURLToPath(new URL('../../../../../文档/项目/AI演示文稿项目/证据/2026-10-03-完整迁移业务验收/验收材料/',import.meta.url))
const snapshot=JSON.parse(readFileSync(material+'四十页原始快照.json','utf8'))
const expected=JSON.parse(readFileSync(material+'四十页输入预期.json','utf8'))
const copy=<T>(value:T):T=>JSON.parse(JSON.stringify(value))

async function setup(page:Page){
 await page.route('**/*',route=>{const url=new URL(route.request().url());return ['127.0.0.1','localhost'].includes(url.hostname)?route.fallback():route.abort('blockedbyclient')})
 const state=await installAipptFixture(page)
 state.project={...state.project,...copy(snapshot.project),id:'fixture-project',input_revision:1,theme_revision:1,deck_revision:1}
 state.deck={...state.deck,project_id:'fixture-project',total:40,slides:copy(snapshot.slides).map((slide:any,index:number)=>({...slide,project_id:'fixture-project',position:index,issues:[]}))}
 state.outline.pages=expected.pages.map((p:any)=>({id:p.slide_id,title:p.title,objective:'离线40页技术核对',key_points:['核对页序与标题','保留独立输入'],source_refs:[],page_role:'content',visual:'',layout_id:p.layout_id}))
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message))
 return {state,errors}
}
function titleBlock(row:any){const slide=snapshot.slides.find((s:any)=>s.id===row.slide_id),source=slide.blocks.find((b:any)=>b.type==='text'&&b.text===row.title);expect(source).toBeDefined();const independent=row.blocks.find((b:any)=>b.id===source.id);expect(independent?.type).toBe('text');return independent}
async function screenshot(page:Page,info:TestInfo,name:string){await page.screenshot({path:info.outputPath(name),fullPage:true});await info.attach(name,{path:info.outputPath(name),contentType:'image/png'})}
async function evidence(info:TestInfo,data:unknown,name='DOM与夹具回读.json'){const path=info.outputPath(name);writeFileSync(path,JSON.stringify(data,null,2));await info.attach(name,{path,contentType:'application/json'})}
async function select(page:Page,row:any,measure=false){
 const button=page.getByRole('button',{name:`选择第${row.position}页 ${row.title}`,exact:true})
 const bid=titleBlock(row).id
 if(measure)await page.evaluate(({bid,text})=>{
  const win=window as any;win.aipptSwitchProbe={started:0,elapsed:null}
  const capture=(event:Event)=>{if(!(event.target as HTMLElement).closest('.film>button'))return;document.removeEventListener('click',capture,true);const started=performance.now();win.aipptSwitchProbe.started=started;const frame=()=>{const title=document.querySelector(`.canvas-wrap [data-block-id="${bid}"] .plain-edit`);if(title?.textContent===text)requestAnimationFrame(()=>win.aipptSwitchProbe.elapsed=performance.now()-started);else requestAnimationFrame(frame)};requestAnimationFrame(frame)}
  document.addEventListener('click',capture,true)
 },{bid,text:row.title})
 const automationStart=Date.now();await button.click()
 await expect(button).toHaveAttribute('aria-pressed','true')
 await expect(page.locator(`.canvas-wrap [data-block-id="${bid}"] .plain-edit`)).toHaveText(row.title)
 expect(await page.locator('.canvas-wrap [data-block-id]').evaluateAll(elements=>elements.map(e=>e.getAttribute('data-block-id')).sort())).toEqual(row.blocks.map((b:any)=>b.id).sort())
 if(measure){await page.waitForFunction(()=>typeof (window as any).aipptSwitchProbe?.elapsed==='number');return {position:row.position,browser_frame_ms:await page.evaluate(()=>(window as any).aipptSwitchProbe.elapsed),automation_ms:Date.now()-automationStart}}
}
async function outerWidth(page:Page){return page.evaluate(()=>({viewport:innerWidth,client:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth}))}

test('四十页首次懒加载、全部切页与125/160画布缩放',async({page},info)=>{
 const {state,errors}=await setup(page),measurements:any[]=[]
 await page.setViewportSize({width:1440,height:900});await page.goto('/aippt/editor?projectId=fixture-project')
 await expect(page.getByRole('heading',{name:snapshot.project.title,exact:true})).toBeVisible()
 await expect(page.locator('.film')).toHaveCount(40)
 await expect.poll(()=>page.locator('.filmstrip .slide-stage').count()).toBeGreaterThan(0)
 const initial=await page.locator('.filmstrip .slide-stage').count();expect(initial).toBeLessThan(40)
 await expect(page.locator('.film').last().locator('.slide-stage')).toHaveCount(0)
 await screenshot(page,info,'01-四十页初始懒加载.png')
 await select(page,expected.pages[39]);await expect(page.locator('.film').last().locator('.slide-stage')).toBeVisible()
 await screenshot(page,info,'02-末页首次可见.png')
 for(const row of expected.pages)measurements.push(await select(page,row,true))
 await expect(page.locator('.filmstrip .slide-stage')).toHaveCount(40)
 const widths:any[]=[]
 for(const [width,zoom] of [[1280,125],[1440,160]]){await page.setViewportSize({width,height:900});const control=page.locator('.canvas-tools label input');await control.fill(String(zoom));await control.press('Tab');await expect(page.locator('.canvas-wrap')).toHaveAttribute('style',new RegExp(`width: ${zoom}%`));const actual=await outerWidth(page);widths.push({...actual,zoom});expect(actual.scroll).toBeLessThanOrEqual(actual.client+1);await screenshot(page,info,`03-${width}画布${zoom}.png`)}
 const reset=page.locator('.canvas-tools label input');await reset.fill('100');await reset.press('Tab');await expect(page.locator('.canvas-wrap')).toHaveAttribute('style',/width: 100%/);await screenshot(page,info,'03b-画布恢复100.png')
 const values=measurements.map(x=>x.browser_frame_ms).sort((a,b)=>a-b)
 expect(state.calls.filter((c:any)=>c.method!=='GET'&&c.path.startsWith('/aippt/'))).toHaveLength(0)
 expect(errors).toEqual([])
 await evidence(info,{scope:'隔离40页技术夹具，不计业务通过',initial_rendered_thumbnails:initial,final_rendered_thumbnails:40,pages_checked:40,widths,measurements,browser_frame_p95_ms:values[Math.ceil(values.length*.95)-1],performance_threshold_verdict:'本机阈值未核定，仅记录测量',errors})
})

test('四十页1024视口、受控200%文字压力和冲突草稿缓存返回',async({page},info)=>{
 const {state,errors}=await setup(page),original=copy(state.deck.slides)
 const initializedAt=Date.now()
 await page.setViewportSize({width:1024,height:900});await page.goto('/aippt/editor?projectId=fixture-project')
 await expect(page.getByRole('heading',{name:snapshot.project.title,exact:true})).toBeVisible({timeout:60000})
 await expect(page.locator('.film')).toHaveCount(40)
 const initialization_ms=Date.now()-initializedAt
 const row=expected.pages.find((p:any)=>p.layout_mode==='flex')
 await select(page,row)
 await page.getByRole('button',{name:'收起页面列表',exact:true}).click();await expect(page.locator('.filmstrip')).toBeHidden()
 await page.getByRole('button',{name:'收起编辑面板',exact:true}).click();await expect(page.locator('.inspector')).toBeHidden()
 await screenshot(page,info,'04-1024折叠页面与面板.png')
 await page.getByRole('button',{name:'展开编辑面板',exact:true}).click()
 const immediateScale=await page.evaluate(()=>{
  const selectors='.workbench button,.workbench label,.workbench input,.workbench textarea,.workbench .el-tabs__item,.workbench .plain-edit,.workbench h2,.workbench small,.workbench p,.workbench .film span'
  const elements=Array.from(document.querySelectorAll<HTMLElement>(selectors)),baseline=elements.map(element=>({element,size:parseFloat(getComputedStyle(element).fontSize)}))
  ;(window as any).aipptTextScaleProbe=baseline
  baseline.forEach(({element,size})=>element.style.setProperty('font-size',size*2+'px','important'))
  return baseline.map(({element,size})=>({tag:element.tagName,original:size,actual:parseFloat(getComputedStyle(element).fontSize)}))
 })
 // Element Plus按钮有字号过渡；同一元素、同一目标值，等待计算值稳定再作倍数核对。
 let textScale:typeof immediateScale=[]
 try{await expect.poll(()=>page.evaluate(()=>(window as any).aipptTextScaleProbe.filter(({element,size}:any)=>Math.abs(parseFloat(getComputedStyle(element).fontSize)/size-2)>=.01).length)).toBe(0)}finally{
  textScale=await page.evaluate(()=>(window as any).aipptTextScaleProbe.map(({element,size}:any)=>({tag:element.tagName,original:size,actual:parseFloat(getComputedStyle(element).fontSize)})))
  await evidence(info,{immediateScale,settledScale:textScale,method:'同一批computed字号x2，等待元素CSS过渡完成；不禁用或改写过渡'},'文字压力测量.json')
 }
 expect(textScale.length).toBeGreaterThan(40);expect(textScale.every(x=>Math.abs(x.actual/x.original-2)<.01)).toBe(true)
 await expect(page.locator('.canvas-tools label input')).toHaveValue('100')
 const wide=await outerWidth(page);expect(wide.scroll).toBeLessThanOrEqual(wide.client+1)
 await screenshot(page,info,'05-受控文字200而画布100.png')
 const bid=titleBlock(row).id,value=page.locator(`.canvas-wrap [data-block-id="${bid}"] [contenteditable=true]`),draft='40页草稿保留 · 键盘输入2026'
 await value.focus();await page.keyboard.press('Control+a');await page.keyboard.insertText(draft)
 state.conflictNext=true;await page.getByRole('heading',{name:snapshot.project.title,exact:true}).click()
 await expect(page.getByText('页面版本冲突，保存队列已暂停',{exact:true})).toBeVisible()
 expect(state.deck.slides.find((s:any)=>s.id===row.slide_id).blocks.find((b:any)=>b.id===bid).text).toBe(row.title)
 await screenshot(page,info,'06-四十页409本地草稿.png')
 await page.getByRole('button',{name:'展开页面列表',exact:true}).click();await page.getByRole('button',{name:`选择第40页 ${expected.pages[39].title}`,exact:true}).click()
 await expect(page.getByRole('button',{name:`选择第${row.position}页 ${row.title}`,exact:true})).toHaveAttribute('aria-pressed','true')
 await expect(page.locator(`.canvas-wrap [data-block-id="${bid}"] .plain-edit`)).toHaveText(draft)
 await page.getByRole('button',{name:'材料与提纲',exact:true}).click();await page.getByRole('button',{name:'保留草稿并离开',exact:true}).click()
 await expect(page).toHaveURL(/\/aippt\/detail/)
 await page.getByRole('tab',{name:'内容参数',exact:true}).click()
 await expect(page.locator('.el-form-item').filter({hasText:'目标页数'}).locator('input')).toHaveValue('40')
 await screenshot(page,info,'07a-正式40页参数不自动裁值.png')
 await page.locator('#tags-view-container').getByRole('link',{name:'编辑工作台',exact:true}).click()
 await expect(page).toHaveURL(/\/aippt\/editor/)
 await expect(page.getByRole('dialog',{name:'未提交内容',exact:true})).toHaveCount(0)
 await expect(page.getByText('页面版本冲突，保存队列已暂停',{exact:true})).toBeVisible()
 await expect(page.locator(`.canvas-wrap [data-block-id="${bid}"] .plain-edit`)).toHaveText(draft)
 await screenshot(page,info,'07-缓存返回仍有四十页草稿.png')
 await page.getByRole('button',{name:'复制草稿',exact:true}).click()
 const copied=JSON.parse(await page.getByRole('dialog',{name:'复制本地草稿'}).locator('textarea').inputValue())
 expect(copied.blocks.find((b:any)=>b.id===bid).text).toBe(draft)
 await page.getByRole('dialog',{name:'复制本地草稿'}).getByRole('button',{name:'关闭',exact:true}).click()
 await page.getByRole('button',{name:'读取最新版本并重新应用',exact:true}).click();await page.getByRole('button',{name:'确定',exact:true}).click()
 await expect.poll(()=>state.deck.slides.find((s:any)=>s.id===row.slide_id).blocks.find((b:any)=>b.id===bid).text).toBe(draft)
 await page.getByRole('button',{name:`选择第40页 ${expected.pages[39].title}`,exact:true}).click();await expect(page.getByRole('button',{name:`选择第40页 ${expected.pages[39].title}`,exact:true})).toHaveAttribute('aria-pressed','true')
 await page.getByRole('button',{name:`选择第${row.position}页 ${row.title}`,exact:true}).click()
 await expect(page.locator(`.canvas-wrap [data-block-id="${bid}"] .plain-edit`)).toHaveText(draft)
 expect(state.deck.slides.filter((s:any)=>s.id!==row.slide_id)).toEqual(original.filter((s:any)=>s.id!==row.slide_id))
 expect(errors).toEqual([])
 await screenshot(page,info,'08-保存后跨四十页返回.png')
 // 同一隔离通道核参数保存真实异步边界；断网发生在正式夹具已提交之后。
 const parameterCalls:any[]=[],savedParameters=new Map<string,any>();let loseReceipt=true,parameterCommits=0
 await page.route('**/aippt/projects/fixture-project',async route=>{
  const request=route.request();if(request.method()!=='PATCH'){await route.fallback();return}
  const body=request.postDataJSON(),key=request.headers()['idempotency-key'];parameterCalls.push({body:copy(body),key})
  if(savedParameters.has(key)){await route.fulfill({contentType:'application/json',body:JSON.stringify({code:200,data:savedParameters.get(key)})});return}
  if(body.input_revision!==state.project.input_revision){await route.fulfill({contentType:'application/json',body:JSON.stringify({code:409,msg:'正式参数修订已变化'})});return}
  Object.assign(state.project,body);state.project.input_revision++;parameterCommits++;savedParameters.set(key,copy(state.project))
  if(loseReceipt){loseReceipt=false;await route.abort('connectionfailed');return}
  await route.fulfill({contentType:'application/json',body:JSON.stringify({code:200,data:state.project})})
 })
 await page.getByRole('button',{name:'材料与提纲',exact:true}).click();await expect(page).toHaveURL(/\/aippt\/detail/)
 await page.getByRole('tab',{name:'内容参数',exact:true}).click()
 const parameter=(label:string)=>page.locator('.el-form-item').filter({has:page.locator('.el-form-item__label',{hasText:label})}).locator('input')
 await expect(parameter('目标页数')).toHaveValue('40')
 await parameter('名称').fill('参数提交后回执断网')
 await expect(page.getByRole('button',{name:'重新生成提纲',exact:true})).toBeDisabled()
 await page.getByRole('button',{name:'保存参数',exact:true}).click()
 await expect(page.getByRole('button',{name:'重试同一参数请求',exact:true})).toBeVisible()
 await expect(parameter('名称')).toBeDisabled();expect(state.project.title).toBe('参数提交后回执断网')
 await screenshot(page,info,'09-已提交断网保留原参数请求.png')
 await page.getByRole('button',{name:'重试同一参数请求',exact:true}).click();await expect(parameter('名称')).toBeEnabled()
 expect(parameterCalls[1]).toEqual(parameterCalls[0]);expect(parameterCalls[0].body).not.toHaveProperty('page_count');expect(parameterCommits).toBe(1)
 await parameter('名称').fill('只重新应用本地名称')
 const changedAudience='后台最新受众'
 Object.assign(state.project,{title:'后台新名称',audience:changedAudience,tone:'plain',page_count:39,input_revision:state.project.input_revision+1});state.deck.slides.pop();state.deck.total=39
 await page.getByRole('button',{name:'保存参数',exact:true}).click();await expect(page.getByRole('button',{name:'核对最新参数版本',exact:true})).toBeVisible()
 await expect(parameter('目标页数')).toHaveValue('40');await expect(parameter('名称')).toBeDisabled()
 await screenshot(page,info,'10-参数冲突保留40页基线草稿.png')
 await page.getByRole('button',{name:'核对最新参数版本',exact:true}).click()
 const review=page.getByRole('dialog',{name:'核对参数草稿与最新版本',exact:true});await expect(review).toBeVisible();await expect(review).toContainText(changedAudience);await expect(review).toContainText('只重新应用本地名称');await expect(review).toContainText('后台新名称')
 await expect(review.locator('tr').filter({hasText:'目标页数'})).toContainText('40');await expect(review.locator('tr').filter({hasText:'目标页数'})).toContainText('39')
 await screenshot(page,info,'11-原读取草稿最新参数逐字段比对.png')
 await review.getByRole('button',{name:'重新应用并保存',exact:true}).click();await expect(review).toBeHidden();await expect(parameter('名称')).toBeEnabled();await expect(parameter('目标页数')).toHaveValue('39')
 const reapplied=parameterCalls[3];expect(reapplied.body).not.toHaveProperty('page_count');expect(reapplied.body.audience).toBe(changedAudience);expect(reapplied.body.tone).toBe('plain');expect(reapplied.key).not.toBe(parameterCalls[2].key);expect(state.project.title).toBe('只重新应用本地名称')
 await screenshot(page,info,'12-明确重新应用保留正式39页及远端字段.png')
 await parameter('名称').fill('待放弃的参数草稿');Object.assign(state.project,{title:'最终正式名称',input_revision:state.project.input_revision+1})
 await page.getByRole('button',{name:'保存参数',exact:true}).click();await page.getByRole('button',{name:'核对最新参数版本',exact:true}).click();await expect(review).toBeVisible()
 const beforeDiscard=parameterCalls.length;await review.getByRole('button',{name:'放弃参数草稿',exact:true}).click();await expect(parameter('名称')).toHaveValue('最终正式名称');expect(parameterCalls).toHaveLength(beforeDiscard)
 await screenshot(page,info,'13-明确放弃参数草稿恢复正式值.png')
 await page.locator('#tags-view-container').getByRole('link',{name:'编辑工作台',exact:true}).click();await expect(page).toHaveURL(/\/aippt\/editor/);await expect(page.getByRole('dialog',{name:'未提交内容',exact:true})).toHaveCount(0)
 expect(errors).toEqual([])
 await evidence(info,{scope:'隔离40页技术夹具，不计业务通过',initialization_ms,text_scaling_method:'测试浏览器computed字号x2，非原生文字-only设置',textScale,outerWidth:wide,selected_slide_id:row.slide_id,copied_draft_text:copied.blocks.find((b:any)=>b.id===bid).text,saved_text:draft,other_pages_unchanged_before_parameter_fixture:39,parameterCalls,parameterCommits,parameter_unknown_replay_same_body_and_key:true,parameter_reapply_omits_unchanged_page_count:true,parameter_reapply_retains_remote_fields:true,parameter_discard_no_patch:true,errors})
})
