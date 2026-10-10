import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {defineComponent,KeepAlive,nextTick} from 'vue'
import {createMemoryHistory,createRouter,RouterView,type Router} from 'vue-router'
import type {PcAttachment,PcPersonalContext,PcSessionDetail} from '@/types/paicli'

const mocks=vi.hoisted(()=>({request:vi.fn(),terminalOpen:vi.fn(),failTurn:false,completed:false,sessionState:'DRAFT',canWrite:true}))
vi.mock('@/utils/request',()=>({default:mocks.request}))
vi.mock('@/store/modules/user',()=>({default:()=>({id:'42',permissions:['paicli:session:read',...(mocks.canWrite?['paicli:session:write']:[]),'paicli:terminal:use'],roles:['ordinary']})}))
import Workbench from '../workbench/index.vue'
import Knowledge from '../knowledge/index.vue'
import hasPermi from '@/directive/permission/hasPermi'

const Container={template:'<div><slot /><slot name="footer" /><slot name="title" /></div>'}
const Button={props:['disabled','loading'],template:'<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>'}
const Input={props:['modelValue','disabled'],template:'<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\',$event.target.value)" />'}
const Terminal=defineComponent({props:['initialTerminalId'],setup(_props,{expose}){expose({open:()=>mocks.terminalOpen()});return {}},template:'<div data-terminal :data-initial-terminal="initialTerminalId" />'})
const stubs={ElButton:Button,ElInput:Input,ElSelect:Container,ElOption:true,ElAlert:{...Container,props:['title'],template:'<div>{{title}}<slot /><slot name="title" /></div>'},ElTag:Container,ElEmpty:true,ElCollapse:Container,ElCollapseItem:Container,Pagination:true,ImageAttachments:true,ConversationPane:true,ExecutionResults:true,PersonalDirectoryPicker:true,ApprovalDrawer:true,SnapshotDialog:true,SessionHistory:true,LedgerReader:true,ContextPanel:true,PlanPanel:true,WebTerminalPane:true}
const context:PcPersonalContext={ownerUserId:'42',directoryContext:{id:'12',label:'个人默认目录',path:'D:/fixture/42',state:'VERIFIED',version:1},modelId:'8',models:[{id:'8',providerId:'7',modelId:'fixture-model',contextWindow:128000,supportsImages:true,supportsThinking:false,supportsTools:true,capabilityKnown:true,enabled:true,version:1}],runtime:{ready:true,code:'READY',message:'已就绪',runnerId:'9'},canChat:true,canOpenTerminal:true}
function detail(id='51'):PcSessionDetail{return {id,title:`对话${id}`,directoryContextId:'12',directoryContext:context.directoryContext!,runnerId:'9',modelId:'8',mode:'REACT',approvalMode:'ASK',status:mocks.completed?'COMPLETED':'DRAFT',version:1,eventCursor:'0',createdAt:'',updatedAt:'',messages:[],approvals:[],context:{sessionId:id,version:1,estimatedTokens:0,contextWindow:128000,messages:0,compressionCount:0,externalMemoryPaused:false,rawLedgerAvailable:true,promptLayers:[],artifacts:[],diagnostics:[]}}}
const image:PcAttachment={id:'71',fileName:'已上传夹具.png',contentType:'image/png',size:10,sha256:'a'.repeat(64),width:1,height:1}
type Editor={text:string;attachments:PcAttachment[];selectedFiles:{id:string;name:string}[];chooseSession:(id:string)=>Promise<void>;newConversation:()=>void}
const mounted:VueWrapper[]=[]
const paths=['/paicli/knowledge','/paicli/connections','/paicli/workbench']

async function open(initial='/paicli/workbench'){
  const router=createRouter({history:createMemoryHistory(),routes:[
    {path:'/paicli/workbench',name:'Workbench',component:Workbench,meta:{noCache:false}},
    {path:'/paicli/knowledge',name:'Knowledge',component:Knowledge,meta:{noCache:false}},
    {path:'/paicli/connections',name:'Connections',component:defineComponent({name:'Connections',template:'<p>模型与设置</p>'}),meta:{noCache:false}}
  ]})
  // 与AppMain的include及route.path键相同；使用原菜单生成的路由名，不把组件旧名加入缓存来掩盖失配。
  const Host=defineComponent({components:{RouterView,KeepAlive},data:()=>({cachedViews:['Workbench','Knowledge','Connections']}),template:'<router-view v-slot="{Component,route}"><keep-alive :include="cachedViews"><component :is="Component" :key="route.path" /></keep-alive></router-view>'})
  await router.push(initial);await router.isReady()
  // 保留真实Knowledge的analyseFile/router.push，仅隔离子文件列表与无关知识面板。
  const FileList={template:'<div><button @click="$emit(\'analyse\',{id:\'91\',fileName:\'甲资料.txt\'})">分析甲资料</button><button @click="$emit(\'analyse\',{id:\'92\',fileName:\'乙资料.txt\'})">分析乙资料</button></div>'}
  const wrapper=mount(Host,{global:{plugins:[router],directives:{hasPermi,loading:{}},stubs:{...stubs,WebTerminalPane:Terminal,ElTabs:Container,ElTabPane:Container,ElForm:Container,ElFormItem:Container,ElTable:Container,ElTableColumn:true,OperationTask:true,PersonalFiles:FileList,MemoryEditor:true,MemoryVerification:true,ProjectRules:true,PromptTemplates:true}}});mounted.push(wrapper)
  await flushPromises();return {wrapper,router}
}
function editor(wrapper:VueWrapper){return wrapper.findComponent(Workbench).vm as unknown as Editor}
function input(wrapper:VueWrapper){return wrapper.get<HTMLTextAreaElement>('#pc-turn-text')}
async function go(router:Router,path:string){await router.push(path);await flushPromises();await nextTick()}
function mutations(){return mocks.request.mock.calls.map(call=>call[0]).filter(request=>request.method && request.method!=='get')}
async function click(wrapper:VueWrapper,label:string){const button=wrapper.findAll('button').find(item=>item.text()===label||(item.find('strong').exists()&&item.find('strong').text()===label));expect(button,`可见按钮：${label}`).toBeDefined();await button!.trigger('click');await flushPromises()}
function delaySession(id:string){
  const previous=mocks.request.getMockImplementation()!,url=`/paicli/v1/sessions/${id}`
  let release!:()=>void
  const pending=new Promise<{data:PcSessionDetail}>(resolve=>{release=()=>resolve({data:detail(id)})})
  mocks.request.mockImplementation((request:{url:string})=>request.url===url?pending:previous(request))
  return release
}

beforeEach(()=>{
  mocks.failTurn=false;mocks.completed=false;mocks.sessionState='DRAFT';mocks.canWrite=true;mocks.request.mockReset();mocks.terminalOpen.mockReset().mockResolvedValue(undefined)
  mocks.request.mockImplementation(async(request:{url:string;method?:string})=>{
    if(request.url==='/paicli/v1/personal/context'||request.url==='/paicli/v1/personal/bootstrap')return {data:structuredClone(context)}
    if(request.url==='/paicli/v1/personal/directories')return {data:[structuredClone(context.directoryContext)]}
    if(request.url==='/paicli/v1/knowledge/index')return {data:null}
    if(request.url==='/paicli/v1/memories')return {rows:[],total:0}
    if(request.url==='/paicli/v1/sessions')return request.method==='post'?{data:detail()}:{rows:[detail('51'),detail('52')],total:2}
    if(request.url.endsWith('/events'))return {data:{events:[],cursor:'0',hasMore:false,terminal:true}}
    if(request.url.endsWith('/turns')){if(mocks.failTurn)throw new Error('夹具发送失败');mocks.completed=true;return {data:{taskId:'61',version:2,eventCursor:'1'}}}
    if(/^\/paicli\/v1\/sessions\/\d+$/.test(request.url)){const data=detail(request.url.split('/').at(-1));if(mocks.sessionState!=='DRAFT'){data.status=mocks.sessionState;if(data.status==='RUNNING')data.activeTaskId='61'};return {data}}
    throw new Error(`未定义的夹具请求：${request.url}`)
  })
})
afterEach(()=>{mounted.splice(0).forEach(wrapper=>wrapper.unmount())})

describe('若依实际路由名称与KeepAlive中的编码草稿',()=>{
  it.each([true,false])('同页取消等待中交接时实际详情/草稿归属一致（原会话：%s）',async(hasPrevious)=>{
    const {wrapper,router}=await open();if(hasPrevious)await click(wrapper,'对话51')
    await input(wrapper).setValue(hasPrevious?'原会话51完整草稿':'个人新草稿完整要求')
    await go(router,'/paicli/knowledge');await click(wrapper,'分析甲资料')
    const release52=delaySession('52');await go(router,'/paicli/workbench?sessionId=52&fileId=92&fileName=乙资料.txt')
    await go(router,'/paicli/workbench');release52();await flushPromises()
    const vm=editor(wrapper) as Editor&{session:{detail:{value?:PcSessionDetail}}}
    expect(vm.session.detail.value?.id).toBe(hasPrevious?'51':undefined)
    expect(mocks.request.mock.calls.find(call=>call[0].url==='/paicli/v1/sessions/52')?.[0].signal.aborted).toBe(true)
    expect(input(wrapper).element.value).toBe(hasPrevious?'原会话51完整草稿':'个人新草稿完整要求');expect(vm.selectedFiles).toEqual([{id:'91',name:'甲资料.txt'}])
    expect(router.currentRoute.value.query.sessionId).toBe(hasPrevious?'51':undefined);expect(router.currentRoute.value.query.fileId).toBeUndefined()
    for(const path of paths)await go(router,path)
    expect(vm.session.detail.value?.id).toBe(hasPrevious?'51':undefined);expect(input(wrapper).element.value).toBe(hasPrevious?'原会话51完整草稿':'个人新草稿完整要求');expect(vm.selectedFiles).toEqual([{id:'91',name:'甲资料.txt'}])
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it('冷普通会话选择等待时改显式会话，旧选择同样不能覆盖新路由',async()=>{
    const release51=delaySession('51'),{wrapper,router}=await open('/paicli/workbench?sessionId=51')
    await go(router,'/paicli/workbench?sessionId=52');release51();await flushPromises()
    expect(router.currentRoute.value.query.sessionId).toBe('52');expect(input(wrapper).element.value).toBe('');expect(editor(wrapper).selectedFiles).toEqual([])
    await input(wrapper).setValue('对话52初始化后草稿');for(const path of paths)await go(router,path);expect(input(wrapper).element.value).toBe('对话52初始化后草稿');expect(router.currentRoute.value.query.sessionId).toBe('52')
  })

  it('有效冷终端链接仍定位原会话并传入原terminalId，初始化离页不打开终端',async()=>{
    const first=await open('/paicli/workbench?sessionId=51&view=terminal&terminalId=71')
    expect(first.router.currentRoute.value.query.sessionId).toBe('51');expect(first.wrapper.get('[data-terminal]').attributes('data-initial-terminal')).toBe('71');expect(mocks.terminalOpen).toHaveBeenCalled()
    first.wrapper.unmount();mounted.splice(mounted.indexOf(first.wrapper),1);mocks.terminalOpen.mockClear()
    const release51=delaySession('51'),second=await open('/paicli/workbench?sessionId=51&view=terminal&terminalId=71')
    await go(second.router,'/paicli/knowledge');release51();await flushPromises();expect(second.router.currentRoute.value.path).toBe('/paicli/knowledge');expect(mocks.terminalOpen).not.toHaveBeenCalled()
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it('冷显式会话51/资料91等待时改52/92，旧初始化不能覆盖最新选择',async()=>{
    const release51=delaySession('51'),{wrapper,router}=await open('/paicli/workbench?sessionId=51&fileId=91&fileName=甲资料.txt')
    await go(router,'/paicli/workbench?sessionId=52&fileId=92&fileName=乙资料.txt');release51();await flushPromises()
    expect(router.currentRoute.value.query.sessionId).toBe('52');expect(router.currentRoute.value.query.fileId).toBeUndefined();expect(editor(wrapper).selectedFiles).toEqual([{id:'92',name:'乙资料.txt'}])
    await click(wrapper,'对话51');expect(input(wrapper).element.value).toBe('');expect(editor(wrapper).selectedFiles).toEqual([])
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it('冷初始化显式会话等待时离页，不写原route/资料，返回保留个人新草稿',async()=>{
    const release51=delaySession('51'),{wrapper,router}=await open('/paicli/workbench?sessionId=51&fileId=91&fileName=甲资料.txt')
    await go(router,'/paicli/knowledge');release51();await flushPromises();expect(router.currentRoute.value.path).toBe('/paicli/knowledge')
    await go(router,'/paicli/workbench');expect(router.currentRoute.value.query.sessionId).toBeUndefined();expect(editor(wrapper).selectedFiles).toEqual([]);expect(input(wrapper).element.value).toBe('')
    await input(wrapper).setValue('返回后的个人新草稿');for(const path of paths)await go(router,path);expect(input(wrapper).element.value).toBe('返回后的个人新草稿')
  })

  it.each([{nextId:'53',nextFile:'92'},{nextId:'52',nextFile:'92'},{nextId:'53',nextFile:'91'}])('延迟交接52/91后最新$nextId/$nextFile获胜，旧请求不改路由或草稿',async({nextId,nextFile})=>{
    const {wrapper,router}=await open();await click(wrapper,'对话51');await input(wrapper).setValue('对话51原草稿')
    const release52=delaySession('52'),release53=nextId==='53'?delaySession('53'):()=>{}
    await go(router,'/paicli/workbench?sessionId=52&fileId=91&fileName=甲资料.txt')
    await go(router,`/paicli/workbench?sessionId=${nextId}&fileId=${nextFile}&fileName=最新资料.txt`)
    release52();await flushPromises();release53();await flushPromises()
    expect(router.currentRoute.value.query.sessionId).toBe(nextId);expect(router.currentRoute.value.query.fileId).toBeUndefined()
    expect(editor(wrapper).selectedFiles).toEqual([{id:nextFile,name:'最新资料.txt'}]);expect(input(wrapper).element.value).toBe('分析这份资料，说明关键内容和可以继续处理的事项。')
    await click(wrapper,'对话51');expect(input(wrapper).element.value).toBe('对话51原草稿');expect(editor(wrapper).selectedFiles).toEqual([])
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it('延迟交接期间离开工作台，旧请求不能跳回或把原草稿归给未完成选择',async()=>{
    const {wrapper,router}=await open();await click(wrapper,'对话51');await input(wrapper).setValue('离开前原对话51草稿')
    const release=delaySession('52');await go(router,'/paicli/workbench?sessionId=52&fileId=91&fileName=甲资料.txt')
    await go(router,'/paicli/knowledge');release();await flushPromises();expect(router.currentRoute.value.path).toBe('/paicli/knowledge')
    await go(router,'/paicli/workbench');expect(router.currentRoute.value.query.sessionId).toBe('51');expect(input(wrapper).element.value).toBe('离开前原对话51草稿');expect(editor(wrapper).selectedFiles).toEqual([])
  })

  it('无发送权限的真实路由交接明确拒绝，保留文本与原引用且无业务请求',async()=>{
    const {wrapper,router}=await open();await input(wrapper).setValue('权限变化前草稿')
    await go(router,'/paicli/knowledge');await click(wrapper,'分析甲资料');await input(wrapper).setValue('保留既有资料和文本')
    mocks.canWrite=false;await go(router,'/paicli/knowledge');await click(wrapper,'分析乙资料')
    expect(wrapper.text()).toContain('当前账号没有发送要求的权限');expect(input(wrapper).element.value).toBe('保留既有资料和文本');expect(editor(wrapper).selectedFiles).toEqual([{id:'91',name:'甲资料.txt'}]);expect(router.currentRoute.value.query.fileId).toBeUndefined()
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it.each(['RUNNING','HISTORICAL'])('%s对话拒绝文件交接且保留原草稿，不发送或反复消费',async(state)=>{
    const {wrapper,router}=await open();await click(wrapper,'对话51');await input(wrapper).setValue('受限前已有草稿')
    await go(router,'/paicli/knowledge');mocks.sessionState=state;await click(wrapper,'分析甲资料')
    expect(input(wrapper).element.value).toBe('受限前已有草稿');expect(editor(wrapper).selectedFiles).toEqual([]);expect(wrapper.text()).toContain(state==='RUNNING'?'当前对话正在执行':'只读历史不能加入资料');expect(router.currentRoute.value.query.fileId).toBeUndefined()
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it('同一路由交接显式会话也更新引用，冷进入显式会话保持原路由语义',async()=>{
    const {wrapper,router}=await open('/paicli/workbench?sessionId=51&fileId=91&fileName=甲资料.txt')
    expect(router.currentRoute.value.query.sessionId).toBe('51');expect(editor(wrapper).selectedFiles).toEqual([{id:'91',name:'甲资料.txt'}])
    await input(wrapper).setValue('对话51修改后的要求');await go(router,'/paicli/workbench?sessionId=52&fileId=91&fileName=甲资料.txt')
    expect(router.currentRoute.value.query.sessionId).toBe('52');expect(editor(wrapper).selectedFiles).toEqual([{id:'91',name:'甲资料.txt'}])
    await go(router,'/paicli/workbench?sessionId=52&fileId=92&fileName=乙资料.txt')
    expect(editor(wrapper).selectedFiles).toEqual([{id:'91',name:'甲资料.txt'},{id:'92',name:'乙资料.txt'}])
    await click(wrapper,'对话51');expect(input(wrapper).element.value).toBe('对话51修改后的要求');expect(editor(wrapper).selectedFiles).toEqual([{id:'91',name:'甲资料.txt'}])
  })

  it('冷进入文件交接只预填新草稿，消费query后普通往返不再改写',async()=>{
    const {wrapper,router}=await open('/paicli/workbench?fileId=91&fileName=甲资料.txt')
    expect(input(wrapper).element.value).toBe('分析这份资料，说明关键内容和可以继续处理的事项。');expect(editor(wrapper).selectedFiles).toEqual([{id:'91',name:'甲资料.txt'}])
    expect(router.currentRoute.value.query.fileId).toBeUndefined();expect(router.currentRoute.value.query.fileName).toBeUndefined()
    await input(wrapper).setValue('用户修改后的新草稿');for(const path of paths)await go(router,path)
    expect(input(wrapper).element.value).toBe('用户修改后的新草稿');expect(editor(wrapper).selectedFiles).toHaveLength(1)
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it('真实文件页向缓存新草稿交接保留文本，相同文件去重，不同文件加入',async()=>{
    const {wrapper,router}=await open();await input(wrapper).setValue('我已有的个人新草稿');editor(wrapper).attachments=[image]
    for(const label of ['分析甲资料','分析甲资料','分析乙资料']){await go(router,'/paicli/knowledge');await click(wrapper,label);expect(router.currentRoute.value.path).toBe('/paicli/workbench')}
    expect(input(wrapper).element.value).toBe('我已有的个人新草稿');expect(editor(wrapper).attachments).toEqual([image]);expect(editor(wrapper).selectedFiles).toEqual([{id:'91',name:'甲资料.txt'},{id:'92',name:'乙资料.txt'}])
    expect(router.currentRoute.value.query.fileId).toBeUndefined();expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it('真实文件页带引用回当前对话；显式会话交接独立保留原会话草稿',async()=>{
    const {wrapper,router}=await open();await input(wrapper).setValue('个人新草稿');await click(wrapper,'对话51');await input(wrapper).setValue('对话51草稿')
    await go(router,'/paicli/knowledge');await click(wrapper,'分析甲资料')
    expect(input(wrapper).element.value).toBe('对话51草稿');expect(router.currentRoute.value.query.sessionId).toBe('51');expect(editor(wrapper).selectedFiles).toEqual([{id:'91',name:'甲资料.txt'}])
    await go(router,'/paicli/knowledge');await go(router,'/paicli/workbench?sessionId=52&fileId=92&fileName=乙资料.txt')
    expect(router.currentRoute.value.query.sessionId).toBe('52');expect(editor(wrapper).selectedFiles).toEqual([{id:'92',name:'乙资料.txt'}])
    await click(wrapper,'对话51');expect(input(wrapper).element.value).toBe('对话51草稿');expect(editor(wrapper).selectedFiles).toEqual([{id:'91',name:'甲资料.txt'}])
    await click(wrapper,'新对话');expect(input(wrapper).element.value).toBe('个人新草稿');expect(editor(wrapper).selectedFiles).toEqual([])
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it('中文多行草稿和已有引用跨我的文件、设置返回保留，不重建或提交',async()=>{
    const {wrapper,router}=await open(),vm=editor(wrapper),instance=wrapper.findComponent(Workbench).vm.$,text='验收合成草稿：只核对跨页保留，不发送。\n第二行中文和 emoji 🧪'
    await input(wrapper).setValue(text);vm.attachments=[image];vm.selectedFiles=[{id:'72',name:'既有资料.txt'}]
    const before=mutations().length
    for(const path of paths)await go(router,path)
    expect(input(wrapper).element.value).toBe(text);expect(wrapper.findComponent(Workbench).vm.$).toBe(instance)
    expect(editor(wrapper).attachments).toEqual([image]);expect(editor(wrapper).selectedFiles).toEqual([{id:'72',name:'既有资料.txt'}])
    expect(mutations()).toHaveLength(before);expect(mocks.request.mock.calls.filter(call=>call[0].url.endsWith('/bootstrap'))).toHaveLength(1)
    expect(mocks.request.mock.calls.some(call=>call[0].url==='/paicli/v1/sessions'&&call[0].method==='post'||call[0].url.endsWith('/turns'))).toBe(false)
  })

  it('两个已有对话和新草稿各自保留，跨页激活不覆盖原草稿',async()=>{
    const {wrapper,router}=await open();await input(wrapper).setValue('新的未发送草稿')
    await click(wrapper,'对话51');await input(wrapper).setValue('对话51待补充');editor(wrapper).selectedFiles=[{id:'81',name:'甲资料.txt'}]
    await click(wrapper,'对话52');await input(wrapper).setValue('对话52待补充')
    for(const path of paths)await go(router,path)
    expect(input(wrapper).element.value).toBe('对话52待补充')
    await click(wrapper,'对话51');expect(input(wrapper).element.value).toBe('对话51待补充');expect(editor(wrapper).selectedFiles).toEqual([{id:'81',name:'甲资料.txt'}])
    await click(wrapper,'新对话');expect(input(wrapper).element.value).toBe('新的未发送草稿');expect(editor(wrapper).selectedFiles).toEqual([])
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions'||request.url.endsWith('/turns'))).toHaveLength(0)
  })

  it('发送成功清理已发送的文本和引用，跨页或回新对话不会恢复旧内容',async()=>{
    const {wrapper,router}=await open();await input(wrapper).setValue('这条夹具请求只提交一次');editor(wrapper).attachments=[image];editor(wrapper).selectedFiles=[{id:'82',name:'提交资料.txt'}]
    await click(wrapper,'发送');expect(input(wrapper).element.value).toBe('')
    for(const path of paths)await go(router,path)
    expect(input(wrapper).element.value).toBe('');expect(editor(wrapper).attachments).toEqual([]);expect(editor(wrapper).selectedFiles).toEqual([])
    await click(wrapper,'新对话');expect(input(wrapper).element.value).toBe('')
    const turns=mutations().filter(request=>request.url.endsWith('/turns'));expect(turns).toHaveLength(1);expect(turns[0].data).toMatchObject({text:'这条夹具请求只提交一次',attachments:['71'],files:['82']})
    expect(mutations().filter(request=>request.url==='/paicli/v1/sessions')).toHaveLength(1)
  })

  it('发送失败保留完整草稿和引用，跨页返回不自动重试失败请求',async()=>{
    const {wrapper,router}=await open();mocks.failTurn=true
    await input(wrapper).setValue('失败后仍保留\n完整中文草稿');editor(wrapper).attachments=[image];editor(wrapper).selectedFiles=[{id:'83',name:'失败资料.txt'}]
    await click(wrapper,'发送');expect(wrapper.text()).toContain('夹具发送失败')
    for(const path of paths)await go(router,path)
    expect(input(wrapper).element.value).toBe('失败后仍保留\n完整中文草稿');expect(editor(wrapper).attachments).toEqual([image]);expect(editor(wrapper).selectedFiles).toEqual([{id:'83',name:'失败资料.txt'}])
    expect(mutations().filter(request=>request.url.endsWith('/turns'))).toHaveLength(1);expect(mutations().filter(request=>request.url==='/paicli/v1/sessions')).toHaveLength(1)
  })

  it('销毁整个布局后新实例不继承上个布局的草稿或文件引用',async()=>{
    const first=await open();await input(first.wrapper).setValue('仅当前布局的临时草稿');editor(first.wrapper).selectedFiles=[{id:'84',name:'私有资料.txt'}]
    await go(first.router,'/paicli/knowledge');first.wrapper.unmount();mounted.splice(mounted.indexOf(first.wrapper),1)
    const second=await open();expect(input(second.wrapper).element.value).toBe('');expect(editor(second.wrapper).selectedFiles).toEqual([]);expect(editor(second.wrapper).attachments).toEqual([])
  })
})
