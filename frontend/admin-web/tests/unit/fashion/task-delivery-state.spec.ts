import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {flushPromises,shallowMount,type VueWrapper} from '@vue/test-utils'
import type {BusinessTask} from '@/api/fashion/proposal'
import {businessTaskDetailRows} from '@/views/fashion/tasks/taskDetail'
const api=vi.hoisted(()=>({tasks:vi.fn(),deliveries:vi.fn(),detail:vi.fn(),request:vi.fn()}))
vi.mock('@/api/fashion/proposal',()=>({listBusinessTasks:api.tasks,listProposalDeliveries:api.deliveries,getBusinessTaskDetail:api.detail,downloadProposalDelivery:vi.fn(),createProposalDelivery:vi.fn()}))
vi.mock('@/api/fashion/client',()=>({fashionRequest:api.request}))
vi.mock('@/store/modules/user',()=>({default:()=>({id:'projection-test'})}))
vi.mock('vue-router',()=>({useRouter:()=>({push:vi.fn()})}))
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn(),warning:vi.fn()}}))
import Tasks from '@/views/fashion/tasks/index.vue'
import Deliveries from '@/views/fashion/delivery/index.vue'
import DeliveryTools from '@/views/fashion/pool/DeliveryTools.vue'
const task:BusinessTask={id:'t1',kind:'try_on',taskType:'model_try_on',relatedName:'方案1',status:'running',reviewStatus:'running',adopted:false,createdAt:'2026-10-01',actualCost:3,currency:'CNY',billingStatus:'unknown',errorMessage:'旧失败',detailPath:'/fashion/pools/p1/proposal/images/t1'}
const values=(data:Record<string,unknown>,source=task)=>Object.fromEntries(businessTaskDetailRows(source,data).map(row=>[row.label,row.value]))
function deferred<T>(){let resolve!:(value:T)=>void;const promise=new Promise<T>(r=>resolve=r);return{resolve,promise}}
describe('任务详情最新事实与分页归属',()=>{
  const wrappers:VueWrapper[]=[]
  const global={stubs:{'el-table':{name:'ElTable',props:['data'],template:'<div><slot /></div>'},'el-table-column':{template:'<span />'}},directives:{loading:()=>{},hasPermi:()=>{}},config:{warnHandler:()=>{}}}
  beforeEach(()=>{vi.clearAllMocks();sessionStorage.clear();api.tasks.mockResolvedValue({data:{items:[task],total:25}});api.deliveries.mockResolvedValue({data:{items:[{id:'d1',title:'第一页提案',status:'success'}],total:25}})})
  afterEach(()=>wrappers.splice(0).forEach(wrapper=>wrapper.unmount()))
  it('采用、费用和结束时间来自最新试衣详情，旧失败不回退',()=>{
    const data=values({status:'adopted',actualCost:0,billingStatus:'settled',finishedAt:'2026-10-02',errorMessage:null,review:{adopted:true,resultNo:0,reviewer:'42',reviewedAt:'2026-10-02',reason:'',checks:{style:true,color:true,quality:false}}})
    expect(data['执行状态']).toBe('已采用');expect(data['复核状态']).toBe('已采用');expect(data['采用状态']).toBe('已采用');expect(data['实际费用']).toBe('0 CNY');expect(data['费用状态']).toBe('已对账');expect(data['完成时间']).toBe('2026-10-02');expect(data['失败原因']).toBe('无失败记录');expect(data['复核图片序号']).toBe('1');expect(data['复核检查：图像质量']).toBe('未确认');expect(data['复核检查：完整性']).toBe('未记录')
  })
  it('详情明确未知费用不能显示旧费用，结构内容不作为文字输出',()=>{
    const data=values({status:'review',actualCost:null,finishedAt:null,errorMessage:{privateKey:'secret'},review:{reason:{prompt:'secret'},checks:{style:{privateKey:'secret'}}}})
    expect(data['实际费用']).toBe('未知');expect(data['完成时间']).toBe('尚未完成');expect(Object.values(data).join(' ')).not.toContain('secret');expect(Object.values(data).join(' ')).not.toContain('[object Object]')
  })
  it('AI执行成功与未应用、已应用由最新applyStatus分别表达',()=>{
    const source={...task,kind:'ai_run' as const,status:'running',reviewStatus:'not_applied'}
    expect(values({status:'succeeded',applyStatus:'not_applied'},source)['采用状态']).toBe('待决定')
    expect(values({status:'succeeded',applyStatus:'applied',runAttempt:2},source)).toMatchObject({'执行状态':'执行成功','复核状态':'已采用','采用状态':'已采用','执行尝试次数':'2'})
  })
  for(const [label,component,read] of [['任务',Tasks,api.tasks],['交付',Deliveries,api.deliveries]] as const){
    it(label+'第二页读取失败清除第一页记录与旧总数',async()=>{
      const wrapper=shallowMount(component,{global});wrappers.push(wrapper);await flushPromises()
      const state=wrapper.vm as unknown as {items:unknown[];total:number;page:number;load:()=>Promise<void>;error:string}
      expect(state.items).toHaveLength(1);read.mockRejectedValueOnce(new Error('没有权限'));state.page=2;await state.load();expect(state.items).toEqual([]);expect(state.total).toBe(0);expect(state.error).toBe('没有权限')
    })
  }
  it('较早任务详情迟到不会覆盖新任务',async()=>{
    const wrapper=shallowMount(Tasks,{global});wrappers.push(wrapper);await flushPromises()
    const state=wrapper.vm as unknown as {showDetail:(task:BusinessTask)=>Promise<void>;detailRows:{label:string;value:string}[]}
    const old=deferred<{data:Record<string,unknown>}>();api.detail.mockReturnValueOnce(old.promise).mockResolvedValueOnce({data:{status:'rejected'}})
    const first=state.showDetail(task);await state.showDetail({...task,id:'t2'});old.resolve({data:{status:'adopted'}});await first
    expect(state.detailRows.find(row=>row.label==='任务编号')?.value).toBe('t2');expect(state.detailRows.find(row=>row.label==='采用状态')?.value).toBe('未采用')
  })
  it('试衣卡使用后台冻结服装与服务信息，不用当前方案代替',async()=>{
    Object.defineProperty(HTMLElement.prototype,'scrollIntoView',{configurable:true,value:vi.fn()})
    const frozen=[{productId:'old-product',mainSku:'old-main',color:'红',visualVersion:7,garment_position:'top'}]
    api.request.mockResolvedValue({data:{providerAvailable:false,blockedReason:'未启用',capabilities:{supportedPositions:[]},tasks:[{id:'frozen-task',combinationId:'c1',status:'review',rowVersion:1,draftVersion:3,stale:false,results:[],frozenInputs:{draftVersion:3,products:frozen},providerCode:'old-provider',model:'old-model',submittedAt:'2026-10-01',retryCount:0,resultCount:0}]}})
    const wrapper=shallowMount(DeliveryTools,{props:{modelValue:true,kind:'image',poolId:'p1',rowVersion:4,selectedCount:1,combinationIds:['c1'],combinations:[]},global:{...global,renderStubDefaultSlot:true}});wrappers.push(wrapper);await flushPromises()
    expect(wrapper.text()).toContain('old-provider / old-model');expect(wrapper.text()).toContain('2026-10-01');expect(wrapper.findComponent({name:'ElTable'}).props('data')).toEqual(frozen)
  })
})
