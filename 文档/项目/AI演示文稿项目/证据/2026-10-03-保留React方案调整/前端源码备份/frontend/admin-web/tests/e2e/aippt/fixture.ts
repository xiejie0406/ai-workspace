import type {Page,Route} from '@playwright/test'
import {readFileSync,readdirSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {join} from 'node:path'

// 只供隔离页面检查使用：拦截全部后台请求，不访问真实数据库、Provider 或对象存储。
const catalogRoot=fileURLToPath(new URL('../../../../../contracts/aippt/catalog/',import.meta.url))
const load=(name:string)=>JSON.parse(readFileSync(join(catalogRoot,name),'utf8'))
const themes=readdirSync(join(catalogRoot,'themes')).map(name=>load('themes/'+name))
const layouts=readdirSync(join(catalogRoot,'layouts')).map(name=>load('layouts/'+name))
const clone=<T>(value:T):T=>JSON.parse(JSON.stringify(value))
const allPermissions=['project:list','project:query','project:add','project:edit','project:remove','source:add','source:remove','source:query','outline:generate','outline:edit','outline:confirm','deck:query','deck:generate','deck:cancel','slide:edit','theme:edit','ai:edit','image:upload','export:check','export:create','export:download','design:query'].map(p=>'aippt:'+p)
const menu={path:'/aippt',name:'Aippt',component:'Layout',redirect:'/aippt/projects',alwaysShow:true,meta:{title:'AI演示文稿',icon:'documentation'},children:[
 {path:'projects',name:'AipptProjects',component:'aippt/projects/index',meta:{title:'我的演示文稿',icon:'documentation'}},
 {path:'create',name:'AipptCreate',component:'aippt/create/index',meta:{title:'新建演示文稿',icon:'edit'}},
 {path:'design',name:'AipptDesign',component:'aippt/design/index',meta:{title:'样例与主题',icon:'example'}},
 {path:'detail',name:'AipptDetail',component:'aippt/detail/index',hidden:true,meta:{title:'材料与提纲',activeMenu:'/aippt/projects'}},
 {path:'editor',name:'AipptEditor',component:'aippt/editor/index',hidden:true,meta:{title:'编辑工作台',activeMenu:'/aippt/projects'}}]}

export async function installAipptFixture(page:Page,options:{readonly?:boolean;navType?:number;permissions?:string[]}={}){
 const blocks:any[]=[{id:'t',slot_id:'title',type:'text',text:'八类内容检查',locked:false},{id:'b',slot_id:'body',type:'bullets',items:['核心要点','第二要点'],locked:false},{id:'i',slot_id:'visual',type:'image',url:'/aippt/assets/private-image',alt:'测试图片',source:'upload',credit:'本地测试',locked:false},{id:'c',slot_id:'chart',type:'chart',chart_type:'bar',categories:['第一季','第二季'],series:[{name:'收入',values:[10,20]}],unit:'万元',locked:false},{id:'table',slot_id:'table',type:'table',header:['名称','数量'],rows:[['甲','10'],['乙','20']],locked:false},{id:'k',slot_id:'kpi',type:'kpi',value:'42',label:'指标标签',note:'指标备注',locked:false},{id:'cards',slot_id:'cards',type:'cards',items:[{title:'卡片标题',desc:'卡片说明',icon:'◆'}],locked:false},{id:'callout',slot_id:'source',type:'callout',text:'引用提示',variant:'source',icon:'※',locked:false}]
 const tree={type:'column',id:'root',children:[{type:'row',id:'top',children:blocks.slice(0,4).map(b=>({type:'block',id:'leaf-'+b.id,block_id:b.id,grow:1}))},{type:'row',id:'bottom',children:blocks.slice(4).map(b=>({type:'block',id:'leaf-'+b.id,block_id:b.id,grow:1}))}]}
 const sample=load('sample-deck.json');sample.slides=sample.slides.map((s:any,index:number)=>({...s,layout_mode:'fixed',status:'ready',position:index,issues:[]}));sample.status='ready';sample.deck_revision=1
 const state:any={project:{id:'fixture-project',title:'隔离演示文稿',audience:'产品团队',page_count:5,tone:'professional',content_density:'medium',layout_mode:'flex',theme_id:'ivory',theme_overrides:null,input_revision:1,theme_revision:1,deck_revision:1,status:'ready',updated_at:'2026-10-03 00:00:00'},deck:{id:'fixture-project',project_id:'fixture-project',theme_id:'ivory',deck_revision:1,status:'ready',slides:[{id:'slide-one',title:'八类内容检查',position:0,layout_id:'bullets',layout_mode:'flex',layout_tree:tree,revision:1,status:'ready',blocks,issues:[],speaker_notes:''},{...clone(sample.slides[0]),id:'slide-two',title:'固定页面',position:1}]},sources:[{id:'source-one',kind:'text',name:'测试材料',text:'材料正文',parse_status:'ready',word_count:4,warnings:[],sections:[{id:'section-one',ref:'source-one:section-one',heading:'第一章节',text:'材料正文',locator:'第1段'}]}],outline:{id:'outline-one',project_id:'fixture-project',revision:1,status:'draft',confirmed:false,is_stale:false,input_signature:'fixture',pages:Array.from({length:5},(_,i)=>({id:'outline-'+i,title:'第'+(i+1)+'页',objective:'说明业务目标',key_points:['要点一','要点二'],source_refs:['source-one:section-one'],layout_id:'bullets',page_role:i?'content':'cover',visual:''}))},issues:[{severity:'warning',message:'该图片需要核对来源',slide_id:'slide-one',block_id:'i'}],calls:[] as any[],conflictNext:false,proposals:{} as Record<string,any>}
 const commands=new Map<string,unknown>()
 await page.context().addCookies([{name:'Admin-Token',value:'isolated-fixture-token',url:'http://127.0.0.1:4175'}])
 await page.addInitScript(({navType})=>{localStorage.setItem('layout-setting',JSON.stringify({navType,tagsView:true,sidebarLogo:false}));sessionStorage.clear()},{navType:options.navType||1})
 async function respond(route:Route,data:unknown,status=200){await route.fulfill({status,contentType:'application/json',body:JSON.stringify(status===200?{code:200,msg:'操作成功',data}:data)})}
 await page.route('**/*-api/**',async route=>{
  const request=route.request(),url=new URL(request.url()),at=url.pathname.indexOf('/aippt'),path=at>=0?url.pathname.slice(at):url.pathname.replace(/^\/[^/]*-api/,'');const method=request.method();let body:any={};if(request.headers()['content-type']?.includes('application/json'))body=request.postDataJSON()||{}
  state.calls.push({path,method,body:clone(body),headers:request.headers()})
  if(path==='/getInfo'){await route.fulfill({contentType:'application/json',body:JSON.stringify({code:200,user:{userId:101,userName:'fixture',nickName:'隔离检查'},roles:['fixture'],permissions:options.permissions|| (options.readonly?['aippt:project:list','aippt:project:query','aippt:deck:query','aippt:design:query']:allPermissions)})});return}
  if(path==='/system/notice/listTop'){await route.fulfill({contentType:'application/json',body:JSON.stringify({code:200,rows:[],total:0})});return}
  if(path==='/getRouters'){await respond(route,[menu]);return}
  if(path==='/logout'){await respond(route,null);return}
  if(path==='/aippt/design/themes'){await respond(route,{items:themes,catalog_version:'aippt-1-90c52568ea7d'});return}
  if(path==='/aippt/design/layouts'){await respond(route,{items:layouts,catalog_version:'aippt-1-90c52568ea7d'});return}
  if(path==='/aippt/design/sample-deck'){await respond(route,sample);return}
  if(path==='/aippt/design/sample-deck/issues'){await respond(route,{issues:[],can_export:true,status:'ready'});return}
  if(path==='/aippt/assets/private-image'){if(!request.headers().authorization){await respond(route,{code:401,msg:'无访问令牌'},401);return}await route.fulfill({contentType:'image/png',body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j6H8AAAAASUVORK5CYII=','base64')});return}
  if(path==='/aippt/projects'&&method==='GET'){await route.fulfill({contentType:'application/json',body:JSON.stringify({code:200,rows:[state.project],total:1})});return}
  if(path==='/aippt/projects'&&method==='POST'){state.project={...state.project,...body,status:'draft'};await respond(route,state.project);return}
  const project='/aippt/projects/fixture-project',key=request.headers()['idempotency-key'];if(method!=='GET'&&path.startsWith('/aippt/')){if(!key){await respond(route,{code:400,msg:'缺少幂等键'},400);return}if(commands.has(key)){await respond(route,commands.get(key));return}}
  const saved=async(value:unknown)=>{if(key)commands.set(key,clone(value));await respond(route,value)}
  if(path===project){if(method==='PATCH'){if(body.input_revision!==state.project.input_revision){await respond(route,{code:409,msg:'输入版本冲突'},409);return}Object.assign(state.project,body);state.project.input_revision++}await saved(state.project);return}
  if(path===project+'/sources'){if(method==='POST'){if(body.input_revision!==state.project.input_revision||!body.text){await respond(route,{code:409,msg:'材料版本或内容无效'},409);return}state.sources.push({id:'source-'+state.sources.length,kind:body.kind,text:body.text,parse_status:'ready',sections:[]});state.project.input_revision++;await saved(state.sources.at(-1));return}await respond(route,state.sources);return}
  if(path===project+'/outline'){if(method==='PATCH'){if(body.revision!==state.outline.revision||body.input_revision!==state.project.input_revision){await respond(route,{code:409,msg:'提纲版本冲突'},409);return}state.outline.pages=body.pages;state.outline.revision++;await saved(state.outline);return}await respond(route,state.outline);return}
  if(path===project+'/deck'){await respond(route,state.deck);return}
  if(path===project+'/theme'){if(body.theme_revision!==state.project.theme_revision){await respond(route,{code:409,msg:'主题版本冲突'},409);return}Object.assign(state.project,body);state.project.theme_revision++;await saved(state.project);return}
  if(path===project+'/deck/quality'){await respond(route,{status:'ready',issues:state.issues,can_export:!state.issues.some((i:any)=>i.severity==='error'),deck_revision:state.project.deck_revision});return}
  if(path===project+'/deck/exports'||path==='/aippt/design/sample-deck/exports'){if(path===project+'/deck/exports'&&(body.deck_revision!==state.project.deck_revision||body.theme_revision!==state.project.theme_revision||state.issues.length&&!body.acknowledge_warnings)){await respond(route,{code:409,msg:'导出快照或警告确认无效'},409);return}await saved({id:'export-one',job_id:'export-task',status:'queued'});return}
  if(path==='/aippt/tasks/export-task'){await respond(route,{id:'export-task',status:'succeeded',result:{export_id:'export-one'}});return}
  if(path.startsWith('/aippt/tasks/proposal-job-')){const pid=path.split('proposal-job-')[1];state.taskPolls ||= {};const count=state.taskPolls[pid]=(state.taskPolls[pid]||0)+1;await respond(route,{id:'proposal-job-'+pid,status:count<2?'running':'succeeded',progress:{percent:count<2?50:100,message:count<2?'生成候选':'已生成'},result:count<2?null:{proposal_id:pid}});return}
  if(path==='/aippt/exports/export-one'){await respond(route,{id:'export-one',status:'ready',job_id:'export-task',download_url:'/aippt/exports/export-one/download',verify:{passed:true,slide_count:2,native_shapes:true}});return}
  if(path==='/aippt/exports/export-one/download'){await route.fulfill({contentType:'application/vnd.openxmlformats-officedocument.presentationml.presentation',body:Buffer.from('isolated-test-download')});return}
  if(path.startsWith(project+'/proposals/')){await respond(route,state.proposals[path.split('/').at(-1)!]);return}
  const slidePath=path.match(/^\/aippt\/projects\/fixture-project\/deck\/slides\/([^/]+)(.*)$/);if(slidePath){const slide=state.deck.slides.find((s:any)=>s.id===slidePath[1]);const action=slidePath[2];if(!slide){await respond(route,{code:404,msg:'页面不存在'},404);return}
   if(action==='/layouts'){await respond(route,layouts.map(l=>({layout_id:l.id,compatible:l.id===slide.layout_id,reason:l.id===slide.layout_id?undefined:'测试页含八类内容，固定槽位不兼容'})));return}
   if(method!=='GET'&&body.revision!==slide.revision){await respond(route,{code:409,msg:'页面版本冲突'},409);return}
   if(state.conflictNext){state.conflictNext=false;slide.revision++;await route.fulfill({contentType:'application/json',body:JSON.stringify({code:409,msg:'模拟其他会话已修改'})});return}
   if(action==='/ai-edit'){const pid='proposal-'+Object.keys(state.proposals).length,proposal={id:pid,proposal_id:pid,revision:slide.revision,operations:[{id:'operation-one',op_id:'operation-one',op:'replace',block_id:'callout',before:clone(slide.blocks.find((b:any)=>b.id==='callout')),after:{...clone(slide.blocks.find((b:any)=>b.id==='callout')),text:'AI建议内容'},reason:'精简提示'}]};state.proposals[proposal.id]=proposal;await saved({id:'proposal-job-'+pid,job_id:'proposal-job-'+pid,status:'queued'});return}
   if(action==='/relayout'){const pid='layout-'+Object.keys(state.proposals).length,proposal={id:pid,proposal_id:pid,revision:slide.revision,candidates:[0,1].map(i=>({id:'candidate-'+i,layout_tree:{...clone(tree),gap_pt:16+i*12,children:clone(tree.children).reverse()},blocks:clone(slide.blocks),layout_mode:'flex',layout_id:slide.layout_id}))};state.proposals[pid]=proposal;await saved({id:'proposal-job-'+pid,job_id:'proposal-job-'+pid,status:'queued'});return}
   if(action==='/ai-edit/apply'){const op=state.proposals[body.proposal_id].operations.find((o:any)=>o.op_id===body.op_id);Object.assign(slide.blocks.find((b:any)=>b.id===op.block_id),op[body.side])}
   else if(action==='/relayout/apply'){const proposal=state.proposals[body.proposal_id];if(proposal.revision!==slide.revision){await respond(route,{code:409,msg:'布局候选已过期'},409);return}slide.layout_tree=clone(proposal.candidates.find((c:any)=>c.id===body.candidate_id).layout_tree);proposal.revision=slide.revision+1}
   else if(action==='/flex-layout'){slide.layout_tree=body.layout_tree}
   else if(action==='/flex-state'){Object.assign(slide,{blocks:body.blocks,layout_tree:body.layout_tree,layout_mode:body.layout_mode,layout_id:body.layout_id})}
   else if(action==='/unlock-flex'){slide.layout_mode='flex';slide.layout_tree={type:'column',id:'unlock-root',children:slide.blocks.map((b:any)=>({type:'block',id:'unlock-'+b.id,block_id:b.id,rect_override:layouts.find(l=>l.id===slide.layout_id).slots.find((s:any)=>s.id===b.slot_id).rect}))}}
   else if(action.startsWith('/blocks/')){const parts=action.split('/'),block=slide.blocks.find((b:any)=>b.id===parts[2]);if(method==='DELETE'){slide.blocks=slide.blocks.filter((b:any)=>b.id!==block.id);function remove(node:any){if(node.type==='block')return;node.children=node.children.filter((n:any)=>n.block_id!==block.id);node.children.forEach(remove);if(node.ratios)node.ratios=node.children.map(()=>100/node.children.length)}remove(slide.layout_tree)}else if(parts[3]==='style')block.style=body.style;else{if(body.type!==block.type){await respond(route,{code:400,msg:'不能改变块类型'},400);return}const content=clone(body);delete content.revision;Object.assign(block,content,{locked:true})}}
   else{await respond(route,{code:400,msg:'隔离夹具尚未覆盖该命令：'+action},400);return}
   slide.revision++;state.project.deck_revision++;state.deck.deck_revision=state.project.deck_revision;await saved(slide);return
  }
  await respond(route,{code:404,msg:'隔离夹具未覆盖接口：'+path},404)
 });return state
}
