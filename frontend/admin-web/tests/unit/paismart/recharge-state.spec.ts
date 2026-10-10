import { beforeEach,afterEach,describe,expect,it,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import Recharge from '@/views/paismart/recharge/index.vue'

const mocks=vi.hoisted(()=>({createOrder:vi.fn(),getOrder:vi.fn(),listPackages:vi.fn(),listOrders:vi.fn(),call:vi.fn(),revoke:vi.fn()}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{tradeNo:'trade1'}})}))
vi.mock('@/api/paismart/recharge',()=>mocks)
vi.mock('@/api/paismart/client',()=>({call:mocks.call,newKey:()=> 'key',pathId:(id:string)=>id}))
const pending={id:'order1',tradeNo:'trade1',amountFen:100,state:'NOT_PAY',codeUrl:'weixin://payment',expiresAt:'2030-01-01T00:00:00Z'}
function deferred<T>(){let resolve!:(value:T)=>void,reject!:(error:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return{promise,resolve,reject}}
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
  it('有效原订单深链接初始化只读原交易并保留金额与权益',async()=>{
    mocks.getOrder.mockResolvedValue({code:200,data:{...pending,state:'CLOSED',llmTokens:700,embeddingTokens:300}})
    const wrapper=mount(Recharge,{global});await flushPromises();const vm=wrapper.vm as any
    expect(vm.route.query.tradeNo).toBe('trade1');expect(mocks.getOrder).toHaveBeenCalledOnce();expect(mocks.getOrder).toHaveBeenCalledWith('trade1');expect(vm.order).toMatchObject({tradeNo:'trade1',amountFen:100,state:'CLOSED',llmTokens:700,embeddingTokens:300});expect(wrapper.text()).toContain('1.00');expect(mocks.createOrder).not.toHaveBeenCalled();expect(mocks.call).not.toHaveBeenCalled();wrapper.unmount()
  })
  it('套餐初始化等待期间离开，有效深链接迟到成功不再启动支付查单',async()=>{
    const wait=deferred<unknown>();mocks.listPackages.mockReturnValue(wait.promise);mocks.getOrder.mockResolvedValue({data:{...pending,state:'CLOSED'}})
    const wrapper=mount(Recharge,{global});await flushPromises();const vm=wrapper.vm as any;expect(vm.route.query.tradeNo).toBe('trade1');expect(mocks.getOrder).not.toHaveBeenCalled();wrapper.unmount()
    wait.resolve({rows:[{id:'package-late',name:'迟到套餐',enabled:true,amountFen:100,llmTokens:700,embeddingTokens:300}]});await flushPromises()
    expect(mocks.getOrder).not.toHaveBeenCalled();expect(vm.packages).toEqual([]);expect(vm.error).toBe('');expect(mocks.createOrder).not.toHaveBeenCalled();expect(mocks.call).not.toHaveBeenCalled()
  })
  it('套餐初始化等待期间离开，迟到失败不写已销毁页面错误',async()=>{
    const wait=deferred<unknown>();mocks.listPackages.mockReturnValue(wait.promise)
    const wrapper=mount(Recharge,{global});await flushPromises();const vm=wrapper.vm as any;wrapper.unmount();wait.reject(new Error('离开后的套餐故障'));await flushPromises()
    expect(vm.error).toBe('');expect(vm.packages).toEqual([]);expect(mocks.getOrder).not.toHaveBeenCalled();expect(mocks.createOrder).not.toHaveBeenCalled()
  })
  it('已经发出的原单查询返回成功时页面已离开，不追加二维码或轮询',async()=>{
    const wait=deferred<unknown>();mocks.getOrder.mockReturnValue(wait.promise)
    const wrapper=mount(Recharge,{global});await flushPromises();const vm=wrapper.vm as any;expect(mocks.getOrder).toHaveBeenCalledWith('trade1');wrapper.unmount();wait.resolve({data:pending});await flushPromises()
    expect(vm.order).toBeUndefined();expect(mocks.call).not.toHaveBeenCalled();await vi.advanceTimersByTimeAsync(15000);expect(mocks.getOrder).toHaveBeenCalledOnce();expect(mocks.createOrder).not.toHaveBeenCalled()
  })
  it('已经发出的原单查询返回失败时页面已离开，不追加请求或错误',async()=>{
    const wait=deferred<unknown>();mocks.getOrder.mockReturnValue(wait.promise)
    const wrapper=mount(Recharge,{global});await flushPromises();const vm=wrapper.vm as any;wrapper.unmount();wait.reject(new Error('离开后的原单故障'));await flushPromises()
    expect(vm.error).toBe('');expect(vm.order).toBeUndefined();await vi.advanceTimersByTimeAsync(15000);expect(mocks.getOrder).toHaveBeenCalledOnce();expect(mocks.call).not.toHaveBeenCalled()
  })
  it('已离开的页面不接受显式原单恢复查询',async()=>{
    mocks.getOrder.mockResolvedValue({data:{...pending,state:'CLOSED'}})
    const wrapper=mount(Recharge,{global});await flushPromises();const vm=wrapper.vm as any;wrapper.unmount();await vm.readOrder('late-trade')
    expect(mocks.getOrder).toHaveBeenCalledOnce();expect(mocks.getOrder).toHaveBeenLastCalledWith('trade1');expect(mocks.createOrder).not.toHaveBeenCalled()
  })
  it('实际查询原订单已成功，二维码仍等待时离开，不追加历史查询',async()=>{
    const qr=deferred<Blob>();mocks.getOrder.mockResolvedValueOnce({data:{...pending,state:'UNCERTAIN'}}).mockResolvedValue({data:pending});mocks.call.mockReturnValue(qr.promise)
    const wrapper=mount(Recharge,{global});await flushPromises();const vm=wrapper.vm as any;expect(vm.route.query.tradeNo).toBe('trade1');expect(mocks.listOrders).toHaveBeenCalledOnce()
    await wrapper.findAll('button').find(button=>button.text()==='查询原订单')!.trigger('click');await flushPromises();expect(mocks.getOrder).toHaveBeenLastCalledWith('trade1');expect(mocks.call).toHaveBeenCalledWith('/recharge/orders/trade1/qrcode',{responseType:'blob'});wrapper.unmount()
    qr.resolve(new Blob(['png'],{type:'image/png'}));await flushPromises();expect(mocks.listOrders).toHaveBeenCalledOnce();expect(vm.qrUrl).toBe('');expect(mocks.createOrder).not.toHaveBeenCalled();await vi.advanceTimersByTimeAsync(15000);expect(mocks.getOrder).toHaveBeenCalledTimes(2)
  })
  it('当前原单二维码完成后显示同单二维码并回读历史列表',async()=>{
    const qr=deferred<Blob>();mocks.getOrder.mockResolvedValueOnce({data:{...pending,state:'UNCERTAIN'}}).mockResolvedValue({data:pending});mocks.call.mockReturnValue(qr.promise)
    const wrapper=mount(Recharge,{global});await flushPromises();await wrapper.findAll('button').find(button=>button.text()==='查询原订单')!.trigger('click');await flushPromises();expect(mocks.listOrders).toHaveBeenCalledOnce()
    qr.resolve(new Blob(['png'],{type:'image/png'}));await flushPromises();expect(wrapper.find('img').attributes('src')).toBe('blob:payment-test');expect((wrapper.vm as any).order.tradeNo).toBe('trade1');expect(mocks.listOrders).toHaveBeenCalledTimes(2);expect(mocks.createOrder).not.toHaveBeenCalled();wrapper.unmount()
  })
  it('本地替身创建已返回原单，二维码等候期间离开，不再回读历史',async()=>{
    const qr=deferred<Blob>();mocks.getOrder.mockResolvedValue({data:{...pending,state:'CLOSED'}});mocks.createOrder.mockResolvedValue({data:pending});mocks.call.mockReturnValue(qr.promise)
    const wrapper=mount(Recharge,{global});await flushPromises();await wrapper.findAll('button').find(button=>button.text()==='创建充值订单')!.trigger('click');await flushPromises();expect(mocks.createOrder).toHaveBeenCalledOnce();expect(mocks.call).toHaveBeenCalledOnce();wrapper.unmount()
    qr.resolve(new Blob(['png'],{type:'image/png'}));await flushPromises();expect(mocks.listOrders).toHaveBeenCalledOnce();expect(mocks.createOrder).toHaveBeenCalledOnce();await vi.advanceTimersByTimeAsync(15000);expect(mocks.getOrder).toHaveBeenCalledOnce()
  })
})
