import { beforeEach,afterEach,describe,expect,it,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import Recharge from '@/views/paismart/recharge/index.vue'

const mocks=vi.hoisted(()=>({createOrder:vi.fn(),getOrder:vi.fn(),listPackages:vi.fn(),listOrders:vi.fn(),call:vi.fn(),revoke:vi.fn()}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{tradeNo:'trade1'}})}))
vi.mock('@/api/paismart/recharge',()=>mocks)
vi.mock('@/api/paismart/client',()=>({call:mocks.call,newKey:()=> 'key',pathId:(id:string)=>id}))
const pending={id:'order1',tradeNo:'trade1',amountFen:100,state:'NOT_PAY',codeUrl:'weixin://payment',expiresAt:'2030-01-01T00:00:00Z'}
const slot={template:'<div><slot name="header"/><slot/></div>'}
const global={stubs:{...Object.fromEntries(['el-alert','el-card','el-empty','el-form','el-form-item','el-input-number','el-descriptions','el-descriptions-item','el-table','el-pagination'].map(name=>[name,slot])),'el-button':{template:'<button @click="$emit(\'click\')"><slot/></button>'},'el-table-column':{template:'<div><slot :row="{tradeNo:\'history-trade\',amountFen:100,state:\'UNCERTAIN\'}"/></div>'}},directives:{hasPermi:()=>{},loading:()=>{}}}
beforeEach(()=>{
  vi.useFakeTimers();vi.clearAllMocks();mocks.listPackages.mockResolvedValue({code:200,rows:[]});mocks.listOrders.mockResolvedValue({code:200,rows:[],total:0});mocks.call.mockResolvedValue(new Blob(['png'],{type:'image/png'}))
  vi.stubGlobal('URL',class extends URL {static createObjectURL(){return 'blob:payment-test'}static revokeObjectURL(value:string){mocks.revoke(value)}})
})
afterEach(()=>{vi.unstubAllGlobals();vi.useRealTimers()})
describe('支付状态回源',()=>{
  it('服务端 SUCCEEDED 撤下二维码、显示确认并停止继续查单',async()=>{
    mocks.getOrder.mockResolvedValueOnce({code:200,data:pending}).mockResolvedValue({code:200,data:{...pending,state:'SUCCEEDED'}})
    const wrapper=mount(Recharge,{global});await flushPromises();expect(wrapper.find('img').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(5000);await flushPromises()
    expect(wrapper.find('img').exists()).toBe(false);expect(wrapper.text()).toContain('支付已由服务端确认');expect(mocks.revoke).toHaveBeenCalledWith('blob:payment-test')
    await vi.advanceTimersByTimeAsync(15000);expect(mocks.getOrder).toHaveBeenCalledTimes(2);wrapper.unmount()
  })
  it('已关闭订单不会读取或展示扫码地址，也不会启动轮询',async()=>{
    mocks.getOrder.mockResolvedValue({code:200,data:{...pending,state:'CLOSED'}})
    const wrapper=mount(Recharge,{global});await flushPromises();expect(mocks.call).not.toHaveBeenCalled();expect(wrapper.find('img').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(15000);expect(mocks.getOrder).toHaveBeenCalledOnce();wrapper.unmount()
  })
  it('未确认订单停止自动查单，用户可从历史选原交易号恢复查询',async()=>{
    mocks.getOrder.mockResolvedValue({code:200,data:{...pending,state:'UNCERTAIN',errorMessage:'支付平台响应未确认'}})
    const wrapper=mount(Recharge,{global});await flushPromises();expect(wrapper.text()).toContain('当前停止自动查单');expect(mocks.call).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(15000);expect(mocks.getOrder).toHaveBeenCalledOnce()
    const select=wrapper.findAll('button').find(button=>button.text()==='选择原订单查询');expect(select).toBeDefined();await select!.trigger('click');await flushPromises()
    expect(mocks.getOrder).toHaveBeenLastCalledWith('history-trade');expect(mocks.createOrder).not.toHaveBeenCalled();wrapper.unmount()
  })
})
