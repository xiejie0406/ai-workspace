import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import UsagePage from '@/views/paismart/usage/index.vue'

const mocks = vi.hoisted(() => ({ myMembership: vi.fn(), claimEligibility: vi.fn(), myUsage: vi.fn(), usageOverview: vi.fn(), usageRecords: vi.fn() }))
vi.mock('@/api/paismart/spaces', () => ({ myMembership: mocks.myMembership, claimEligibility: mocks.claimEligibility }))
vi.mock('@/api/paismart/usage', () => ({ myUsage: mocks.myUsage, usageOverview: mocks.usageOverview, usageRecords: mocks.usageRecords }))
const slot = { template: '<div><slot/><slot name="header"/></div>' }
const global = { stubs: {
  ...Object.fromEntries(['el-card','el-form','el-form-item','el-input','el-select','el-option','el-date-picker','el-table','el-table-column','el-pagination'].map(name => [name, slot])),
  'el-alert': { props: ['title'], template: '<div>{{title}}</div>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' },
  'el-empty': { template: '<div/>' }, QuotaScopeSummary: { template: '<div/>' }, UsageOverview: { template: '<div/>' }
}, directives: { loading: () => {}, hasPermi: () => {} } }
const usage = (balance: number) => ({ businessDay: '2026-10-05', llm: { remainingTokens: balance }, embedding: { remainingTokens: 10 }, daily: [] })
function deferred<T>() { let resolve!: (v:T)=>void, reject!: (e:unknown)=>void; const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no}); return {promise,resolve,reject} }
beforeEach(() => { vi.clearAllMocks(); mocks.myMembership.mockResolvedValue({data:{eligible:true}}); mocks.myUsage.mockResolvedValue({data:usage(50)}); mocks.usageRecords.mockResolvedValue({rows:[],total:0}); mocks.usageOverview.mockResolvedValue({data:{days:7}}) })

describe('用量刷新保持最新请求且卸载后不启动后续读取', () => {
  it('较旧余额迟到不会覆盖最新刷新，也不再启动旧流水查询', async () => {
    const old=deferred<unknown>(); mocks.myUsage.mockReturnValueOnce(old.promise).mockResolvedValueOnce({data:usage(41)})
    const wrapper=mount(UsagePage,{global}); const vm=wrapper.vm as any; await flushPromises()
    await vm.refresh(); await flushPromises(); expect(vm.snapshot.llm.remainingTokens).toBe(41)
    old.resolve({data:usage(50)}); await flushPromises()
    expect(vm.snapshot.llm.remainingTokens).toBe(41); expect(mocks.usageRecords).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('旧资格未启用迟到不能清空新刷新已读到的本人余额', async () => {
    const old=deferred<unknown>(); mocks.myMembership.mockReturnValueOnce(old.promise)
    const wrapper=mount(UsagePage,{global}); const vm=wrapper.vm as any; await flushPromises(); await vm.refresh()
    old.resolve({data:{eligible:false}}); await flushPromises()
    expect(vm.eligible).toBe(true); expect(vm.snapshot.llm.remainingTokens).toBe(50); expect(mocks.myUsage).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('较旧读取失败不能污染最新成功快照的错误状态', async () => {
    const old=deferred<unknown>(); mocks.myUsage.mockReturnValueOnce(old.promise)
    const wrapper=mount(UsagePage,{global}); const vm=wrapper.vm as any; await flushPromises(); await vm.refresh()
    old.reject(new Error('上一请求连接失败')); await flushPromises()
    expect(vm.pageError).toBe(''); expect(vm.snapshot.llm.remainingTokens).toBe(50); wrapper.unmount()
  })
  it('资格读取期间卸载，迟到结果不启动余额或流水读取', async () => {
    const old=deferred<unknown>(); mocks.myMembership.mockReturnValueOnce(old.promise)
    const wrapper=mount(UsagePage,{global}); await flushPromises(); wrapper.unmount(); old.resolve({data:{eligible:true}}); await flushPromises()
    expect(mocks.myUsage).not.toHaveBeenCalled(); expect(mocks.usageRecords).not.toHaveBeenCalled()
  })
  it('当前资格未启用准确清空快照且不请求余额', async () => {
    const wrapper=mount(UsagePage,{global}); const vm=wrapper.vm as any; await flushPromises()
    mocks.myMembership.mockResolvedValueOnce({data:{eligible:false}}); await vm.refresh(); await flushPromises()
    expect(vm.eligible).toBe(false); expect(vm.snapshot).toBeUndefined(); expect(mocks.myUsage).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('当前失败仍显示对应错误，之后成功刷新可恢复', async () => {
    const wrapper=mount(UsagePage,{global}); const vm=wrapper.vm as any; await flushPromises()
    mocks.myUsage.mockRejectedValueOnce(new Error('当前读取失败')); await vm.refresh(); expect(vm.pageError).toBe('当前读取失败')
    await vm.refresh(); expect(vm.pageError).toBe(''); expect(vm.snapshot.llm.remainingTokens).toBe(50); wrapper.unmount()
  })
})
