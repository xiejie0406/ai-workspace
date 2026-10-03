import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { shallowMount, flushPromises, type VueWrapper } from '@vue/test-utils'
const api=vi.hoisted(()=>({customers:vi.fn(),history:vi.fn()}))
vi.mock('@/api/fashion/customer',()=>({listCustomers:api.customers,createCustomer:vi.fn(),updateCustomer:vi.fn(),archiveCustomer:vi.fn()}))
vi.mock('@/api/fashion/client',()=>({fashionRequest:api.history}))
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn()},ElMessageBox:{confirm:vi.fn()}}))
import Customers from '@/views/fashion/customer/index.vue'
import History from '@/views/fashion/import/components/ImportHistory.vue'
function deferred(){let resolve!:(value:any)=>void;const promise=new Promise<any>(r=>resolve=r);return {promise,resolve}}
const empty={code:200,data:{items:[],total:0}}
describe('列表错误与迟到结果',()=>{
 let wrapper:VueWrapper
 const options={global:{stubs:{'el-table':true,'el-table-column':true,'el-dialog':true},directives:{hasPermi:()=>{},loading:()=>{}},config:{warnHandler:()=>{}}}}
 beforeEach(()=>{api.customers.mockResolvedValue(empty);api.history.mockResolvedValue(empty)})
 afterEach(()=>wrapper?.unmount())
 it('客户查询失败显示错误并清除旧客户，不伪装无可见客户',async()=>{wrapper=shallowMount(Customers,options);await flushPromises();const state=wrapper.vm as any;state.page.items=[{id:'old'}];state.page.total=1;api.customers.mockRejectedValueOnce(new Error('客户接口不可用'));await state.load();expect(state.error).toBe('客户接口不可用');expect(state.page.items).toEqual([]);expect(state.page.total).toBe(0);expect(state.loading).toBe(false)})
 it('连续客户检索时旧响应不能覆盖新条件结果或提前解除加载',async()=>{wrapper=shallowMount(Customers,options);await flushPromises();const state=wrapper.vm as any;const old=deferred(),latest=deferred();api.customers.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);state.query.keyword='甲';const a=state.load();state.query.keyword='乙';const b=state.load();old.resolve({code:200,data:{items:[{id:'a'}],total:1}});await a;expect(state.loading).toBe(true);latest.resolve({code:200,data:{items:[{id:'b'}],total:1}});await b;expect(state.page.items[0].id).toBe('b');expect(state.loading).toBe(false);expect(api.customers.mock.calls.at(-2)?.[0].keyword).toBe('甲')})
 it('导入类型切换后旧类型历史迟到不能覆盖新历史',async()=>{wrapper=shallowMount(History,{...options,props:{kind:'product'}});await flushPromises();const state=wrapper.vm as any;const old=deferred(),latest=deferred();api.history.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);const a=state.load();await wrapper.setProps({kind:'stock'});latest.resolve({code:200,data:{items:[{batchId:'stock'}],total:1}});await flushPromises();old.resolve({code:200,data:{items:[{batchId:'product'}],total:1}});await a;expect(state.items[0].batchId).toBe('stock');expect(state.loading).toBe(false)})
})
