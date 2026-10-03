import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest'
import {shallowMount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {reactive,computed,defineComponent,h,inject,provide,type ComputedRef} from 'vue'
import type {PoolSnapshot,PoolSelection,PoolPage,PoolConditionDraft,PoolCombination} from '@/api/fashion/poolTypes'
import {blankGroup} from '@/api/fashion/poolTypes'
import {blankConditionDraft} from '@/views/fashion/pool/conditionState'
import {draftCacheValue} from '@/views/fashion/pool/workspaceState'
const api=vi.hoisted(()=>({options:vi.fn(),pool:vi.fn(),selection:vi.fn(),page:vi.fn(),save:vi.fn(),create:vi.fn(),replace:vi.fn(),request:vi.fn(),query:{} as Record<string,string>}))
vi.mock('@/api/fashion/pool',()=>({getPoolOptions:api.options,getPool:api.pool,getPoolSelection:api.selection,getPoolPage:api.page,savePoolSelection:api.save,createPool:api.create}))
vi.mock('@/store/modules/user',()=>({default:()=>({id:'async-test'})}))
vi.mock('@/api/fashion/client',()=>({fashionRequest:api.request}))
vi.mock('@/plugins/auth',()=>({default:{hasPermi:(permission:string)=>permission==='fashion:quote:query'}}))
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn(),warning:vi.fn(),info:vi.fn()},ElMessageBox:{confirm:vi.fn().mockResolvedValue(undefined)}}))
vi.mock('vue-router',()=>({useRoute:()=>reactive({query:api.query}),useRouter:()=>({replace:api.replace}),onBeforeRouteLeave:vi.fn()}))
import Workspace from '@/views/fashion/pool/index.vue'
interface State {pool?:PoolSnapshot;page:PoolPage;form:PoolConditionDraft;selection:PoolSelection;dirty:boolean;busy:boolean;toolVisible:boolean;legacyReadOnly:boolean;openExisting:(id:string)=>Promise<number|undefined>;fresh:()=>Promise<void>;save:()=>Promise<boolean>;toggle:(id:string,value:boolean)=>void;rename:(id:string,name:string)=>void;chooseVariant:(id:string,key:string,product:string)=>void;openTool:(kind:'image'|'ppt')=>Promise<void>;go:(step:number)=>Promise<void>;tryOnSummary:(row:PoolCombination)=>string;taskSummaryError:string}
const selected=(poolId:string):PoolSelection=>({poolId,rowVersion:1,all:false,selectedIds:[],excludedIds:[],names:{},orderedIds:[],variantSelections:{}})
const snapshot=(id:string,groupCount=2):PoolSnapshot=>({id,title:id,groups:[],rawCount:2,total:2,createdAt:'2026-10-01',request:{title:id,groups:Array.from({length:groupCount},(_,i)=>blankGroup(i)),priceBasis:'group'}})
function deferred<T>(){let resolve!:(value:T)=>void;const promise=new Promise<T>(r=>resolve=r);return {promise,resolve}}
describe('工作单异步结果归属',()=>{
 let wrapper:VueWrapper,state:State
 function mount(){wrapper=shallowMount(Workspace,{global:{stubs:{'el-table-column':true,'el-table':true,'el-dialog':true,'el-drawer':true},directives:{hasPermi:()=>{},loading:()=>{}},config:{warnHandler:()=>{}}}});state=wrapper.vm as unknown as State}
 beforeEach(async()=>{sessionStorage.clear();api.query={};api.options.mockResolvedValue({code:200,data:{categories:[],brands:[],seasons:[],genders:[],warehouses:['MAIN'],defaultWarehouseCode:'MAIN'}});api.replace.mockResolvedValue(undefined);api.pool.mockImplementation(id=>Promise.resolve({code:200,data:snapshot(id)}));api.selection.mockImplementation(id=>Promise.resolve({code:200,data:selected(id)}));api.page.mockImplementation(()=>Promise.resolve({code:200,data:{items:[],total:2,page:1,pageSize:20,rawCount:2}}));mount();await flushPromises()})
 afterEach(()=>wrapper?.unmount())
 it('连续打开A/B时A迟到不能覆盖B，并清空旧页面结果',async()=>{await state.openExisting('OLD');state.page.items=[{id:'old-combination',items:[],stockUnknown:true}];const a=deferred<{code:number;data:PoolSnapshot}>(),b=deferred<{code:number;data:PoolSnapshot}>();api.pool.mockImplementation(id=>id==='A'?a.promise:b.promise);const openingA=state.openExisting('A');await flushPromises();expect(state.page.items).toEqual([]);const openingB=state.openExisting('B');await flushPromises();b.resolve({code:200,data:snapshot('B')});await openingB;expect(state.pool?.id).toBe('B');a.resolve({code:200,data:snapshot('A')});await openingA;expect(state.pool?.id).toBe('B');expect(state.selection.poolId).toBe('B')})
 it('保存等待期间新增选色和名称保留，回包只更新版本而继续待保存',async()=>{await state.openExisting('A');state.toggle('combo-1',true);const response=deferred<{code:number;data:PoolSelection}>();api.save.mockReturnValue(response.promise);const saving=state.save();state.chooseVariant('combo-1','main-top','blue-M');state.rename('combo-1','新名称');response.resolve({code:200,data:{...selected('A'),rowVersion:2,selectedIds:['combo-1']}});expect(await saving).toBe(false);expect(state.selection.rowVersion).toBe(2);expect(state.selection.variantSelections['combo-1']['main-top']).toBe('blue-M');expect(state.selection.names['combo-1']).toBe('新名称');expect(state.dirty).toBe(true);expect(api.save.mock.calls[0][1].variantSelections).toEqual({})})
 it('分页刷新失败不打开交付工具，原已选身份仍然保留',async()=>{await state.openExisting('A');state.selection.selectedIds=['combo-1'];api.page.mockRejectedValueOnce(new Error('读取失败'));await state.openTool('image');expect(state.toolVisible).toBe(false);expect(state.selection.selectedIds).toEqual(['combo-1'])})
 it('历史单组仅查看，不允许改选择或调用保存',async()=>{api.pool.mockResolvedValue({code:200,data:snapshot('legacy',1)});await state.openExisting('legacy');expect(state.legacyReadOnly).toBe(true);state.toggle('combo-1',true);state.rename('combo-1','不得写');expect(state.selection.selectedIds).toEqual([]);expect(state.selection.names).toEqual({});expect(await state.save()).toBe(false);expect(api.save).not.toHaveBeenCalled()})
 it('同一工作单恢复未提交条件后正常解除加载遮罩',async()=>{wrapper.unmount();const cached=blankConditionDraft('MAIN');cached.title='未提交的名称';sessionStorage.setItem('fashion:unsubmitted-conditions:async-test',draftCacheValue(cached,'A'));api.query={poolId:'A'};mount();await flushPromises();expect(state.pool?.id).toBe('A');expect(state.form.title).toBe('未提交的名称');expect(state.busy).toBe(false)})
 it('工作单恢复期间用户的新编辑不会被较早浏览器草稿覆盖',async()=>{wrapper.unmount();const cached=blankConditionDraft('MAIN');cached.title='较早草稿';sessionStorage.setItem('fashion:unsubmitted-conditions:async-test',draftCacheValue(cached,'A'));const pending=deferred<{code:number;data:PoolPage}>();api.page.mockReturnValue(pending.promise);api.query={poolId:'A'};mount();await flushPromises();expect(state.pool?.id).toBe('A');state.form.title='刚编辑的名称';pending.resolve({code:200,data:{items:[],total:2,page:1,pageSize:20,rawCount:2}});await flushPromises();expect(state.form.title).toBe('刚编辑的名称');expect(state.busy).toBe(false)})
 it('新建工作单立即清除旧请求遮罩及结果，旧回包不能恢复它们',async()=>{const pending=deferred<{code:number;data:PoolSnapshot}>();api.pool.mockReturnValue(pending.promise);const opening=state.openExisting('A');await flushPromises();expect(state.busy).toBe(true);await state.fresh();expect(state.busy).toBe(false);pending.resolve({code:200,data:snapshot('A')});await opening;expect(state.pool).toBeUndefined();expect(state.page.items).toEqual([]);expect(state.busy).toBe(false)})
 it('已选方案摘要携带草稿版本，从真实工作区结构读取tasks',async()=>{
   await state.openExisting('A');state.selection.rowVersion=7;state.selection.selectedIds=['combo-1']
   const row:PoolCombination={id:'combo-1',items:[],stockUnknown:true}
   api.page.mockResolvedValue({code:200,data:{items:[row],total:1,page:1,pageSize:20,rawCount:2}})
   api.request.mockResolvedValue({code:200,data:{providerAvailable:false,blockedReason:'服务未启用',capabilities:{supportedPositions:[]},monthlyBudget:0,tasks:[{combinationId:'combo-1',status:'adopted',stale:false}]}})
   await state.go(2);await flushPromises()
   expect(api.request).toHaveBeenLastCalledWith({path:'pools/A/proposal/images',params:{rowVersion:7},signal:expect.any(AbortSignal)})
   expect(state.page.items).toEqual([row]);expect(state.taskSummaryError).toBe('');expect(state.tryOnSummary(row)).toBe('已采用')
 })
 it('主列表将null需求满足显示待核实，保留满足与不满足的区别',async()=>{
   wrapper.unmount()
   const Table=defineComponent({props:['data'],setup(props,{slots}){provide('demand-rows',computed(()=>props.data||[]));return()=>h('div',slots.default?.())}})
   const Column=defineComponent({props:['label'],setup(props,{slots}){const rows=inject<ComputedRef<PoolCombination[]>>('demand-rows');return()=>h('span',props.label==='需求满足'?rows?.value.map(row=>slots.default?.({row})):[])}})
   wrapper=shallowMount(Workspace,{global:{stubs:{'el-table':Table,'el-table-column':Column,'el-dialog':true,'el-drawer':true},directives:{hasPermi:()=>{},loading:()=>{}},config:{warnHandler:()=>{}}}});state=wrapper.vm as unknown as State
   await flushPromises();await state.openExisting('A')
   state.page.items=[{id:'unknown',items:[],stockUnknown:true,meetsDemand:null},{id:'enough',items:[],stockUnknown:false,meetsDemand:true},{id:'short',items:[],stockUnknown:false,meetsDemand:false}]
   await flushPromises()
   expect(wrapper.text()).toContain('待核实');expect(wrapper.text()).toContain('满足数量');expect(wrapper.text()).toContain('不满足数量')
   const demand=wrapper.findAllComponents(Column).find(column=>column.props('label')==='需求满足')
   expect(demand?.text()).toBe('待核实满足数量不满足数量')
 })
})
