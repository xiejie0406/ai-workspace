import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import JobDialog from '@/views/paismart/components/JobDialog.vue'

const api = vi.hoisted(() => ({ getJob: vi.fn(), evaluationReport: vi.fn() }))
vi.mock('@/api/paismart/operations', () => ({ getJob: api.getJob }))
vi.mock('@/api/paismart/governance', () => ({ evaluationReport: api.evaluationReport }))
const slot = { template: '<div><slot/><slot name="footer"/></div>' }
const global = { stubs: {
  'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot/><slot name="footer"/></div>' },
  'el-descriptions': slot,
  'el-descriptions-item': { props: ['label'], template: '<div>{{label}}：<slot/></div>' },
  'el-alert': { props: ['title'], template: '<div role="alert">{{title}}</div>' },
  'el-progress': { props: ['percentage'], template: '<div>{{percentage}}%</div>' },
  'el-button': { props: ['loading'], template: '<button @click="$emit(\'click\')"><slot/></button>' }
} }
function deferred() { let resolve!: (value: any) => void, reject!: (reason: unknown) => void; const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const job = (id = 'job-a', evaluationId = 'eval-a') => ({ jobId: id, state: 'SUCCEEDED', kind: 'EVALUATION_RUN', payload: { evaluationId } })
const partial = (id = 'eval-a') => ({ id, state: 'RUNNING', summary: { totalCases: 5, processedCases: 2, pendingCases: 3 }, cases: [{ id: 'one', state: 'COMPLETE' }, { id: 'two', state: 'FAILED' }] })
const final = () => ({ id: 'eval-a', state: 'COMPLETED_WITH_ERRORS', summary: { totalCases: 5, failedCases: 2 }, cases: Array.from({ length: 5 }, (_, i) => ({ id: `case-${i}`, state: i < 3 ? 'COMPLETE' : 'FAILED' })) })
async function setup(props: Record<string, unknown> = {}) { const wrapper = mount(JobDialog, { props: { modelValue: true, initial: job(), evaluationId: 'eval-a', ...props }, global }); await flushPromises(); return wrapper }
beforeEach(() => { vi.clearAllMocks(); api.getJob.mockImplementation((id: string) => Promise.resolve({ data: job(id, id === 'job-b' ? 'eval-b' : 'eval-a') })); api.evaluationReport.mockResolvedValue({ data: partial() }) })

describe('评测作业的真实案例计数与回执归属', () => {
  it('显示冻结总数和已存部分案例，失败案例也算已处理', async () => {
    const w = await setup(); expect(w.text()).toContain('总样本：5'); expect(w.text()).toContain('已处理：2'); expect(w.text()).toContain('待处理：3'); expect(api.evaluationReport).toHaveBeenCalledWith('eval-a', expect.any(AbortSignal)); w.unmount()
  })
  it('完整报告用真实五条CASE计算已处理，保留两条失败提示', async () => {
    api.evaluationReport.mockResolvedValue({ data: final() }); const w = await setup(); expect(w.text()).toContain('已处理：5'); expect(w.text()).toContain('待处理：0'); expect(w.text()).toContain('部分样本失败'); expect(w.text()).toContain('失败样本：2'); w.unmount()
  })
  it('受理回执没有评测ID时，仅原EVALUATION_RUN负载可恢复同一评测身份', async () => {
    const w = await setup({ evaluationId: undefined, initial: { jobId: 'job-a', kind: 'EVALUATION_RUN', state: 'QUEUED' } }); expect(api.evaluationReport).toHaveBeenCalledWith('eval-a', expect.any(AbortSignal)); expect(w.text()).toContain('总样本：5'); w.unmount()
  })
  it.each(['DOCUMENT_PROCESS','INDEX_REBUILD','OPERATION_EXECUTE'])('其它共用作业%s不读取评测报告，保留原作业状态', async kind => {
    api.getJob.mockResolvedValue({ data: { jobId: 'shared-job', kind, state: 'SUCCEEDED' } }); const w = await setup({ evaluationId: undefined, initial: { jobId: 'shared-job', state: 'QUEUED' } }); expect(api.evaluationReport).not.toHaveBeenCalled(); expect(w.text()).toContain('shared-job'); expect(w.text()).not.toContain('总样本'); w.unmount()
  })
  it('原列表评测ID与原作业负载矛盾时，计数未知而不读取另一报告', async () => {
    api.getJob.mockResolvedValue({ data: job('job-a', 'foreign-eval') }); const w = await setup(); expect(api.evaluationReport).not.toHaveBeenCalled(); expect(w.text()).toContain('计数未确认'); expect(w.text()).not.toContain('总样本：5'); w.unmount()
  })
  it('报告ID不匹配时拒绝计数，不把供应商或缓存内容挂到当前任务', async () => {
    api.evaluationReport.mockResolvedValue({ data: partial('foreign-eval') }); const w = await setup(); expect(w.text()).toContain('计数未确认'); expect(w.text()).not.toContain('已处理：2'); w.unmount()
  })
  it.each([
    { totalCases: 5, processedCases: 2, pendingCases: 2 },
    { totalCases: 5, processedCases: 2.5, pendingCases: 2.5 },
    { totalCases: '5', processedCases: 2, pendingCases: 3 },
    { totalCases: 5 },
  ])('计数缺失或不守恒时不补零：%j', async summary => {
    api.evaluationReport.mockResolvedValue({ data: { id: 'eval-a', state: 'RUNNING', summary } }); const w = await setup(); expect(w.text()).toContain('计数未确认'); expect(w.text()).not.toContain('已处理：0'); w.unmount()
  })
  it('报告读取等待时不显示上个任务的计数或伪造0', async () => {
    const wait = deferred(); api.evaluationReport.mockReturnValue(wait.promise); const w = await setup(); expect(w.text()).toContain('正在读取评测计数'); expect(w.text()).not.toContain('已处理：0'); wait.resolve({ data: partial() }); await flushPromises(); expect(w.text()).toContain('已处理：2'); w.unmount()
  })
  it('A报告迟到成功不得覆盖已切到B的ID和计数', async () => {
    const wait = deferred(); api.evaluationReport.mockImplementation((id: string) => id === 'eval-a' ? wait.promise : Promise.resolve({ data: { ...partial('eval-b'), summary: { totalCases: 8, processedCases: 2, pendingCases: 6 } } })); const w = await setup(); await w.setProps({ initial: job('job-b', 'eval-b'), evaluationId: 'eval-b' }); await flushPromises(); wait.resolve({ data: partial() }); await flushPromises(); expect(w.text()).toContain('job-b'); expect(w.text()).toContain('总样本：8'); expect(w.text()).not.toContain('总样本：5'); w.unmount()
  })
  it('同A关闭重开后迟到错误不能污染新一轮正常读取', async () => {
    const wait = deferred(); api.evaluationReport.mockReturnValueOnce(wait.promise).mockResolvedValue({ data: final() }); const w = await setup(); await w.setProps({ modelValue: false }); await w.setProps({ modelValue: true }); await flushPromises(); if (!api.evaluationReport.mock.calls.length) void wait.promise.catch(() => {}); wait.reject(new Error('旧A错误')); await flushPromises(); expect(w.text()).toContain('已处理：5'); expect(w.text()).not.toContain('旧A错误'); w.unmount()
  })
  it('A→B→A中首轮作业迟到，不能触发旧报告读取或写旧状态', async () => {
    const wait = deferred(); api.getJob.mockReturnValueOnce(wait.promise).mockImplementation((id: string) => Promise.resolve({ data: job(id, id === 'job-b' ? 'eval-b' : 'eval-a') })); api.evaluationReport.mockImplementation((id: string) => Promise.resolve({ data: { ...final(), id } })); const w = await setup(); await w.setProps({ initial: job('job-b', 'eval-b'), evaluationId: 'eval-b' }); await flushPromises(); await w.setProps({ initial: job(), evaluationId: 'eval-a' }); await flushPromises(); const n = api.evaluationReport.mock.calls.length; wait.resolve({ data: { ...job(), state: 'FAILED', errorMessage: '首轮旧错误' } }); await flushPromises(); expect(api.evaluationReport.mock.calls.length).toBe(n); expect(w.text()).not.toContain('首轮旧错误'); expect(w.text()).toContain('已处理：5'); w.unmount()
  })
  it('当前报告读取失败保留作业身份和状态，计数未知而不冒填', async () => {
    api.evaluationReport.mockRejectedValue(new Error('无报告权限')); const w = await setup(); expect(w.text()).toContain('job-a'); expect(w.text()).toContain('计数未确认'); expect(w.text()).toContain('无报告权限'); w.unmount()
  })
  it('显式进度与已存CASE数不一致，不能仅采信看似守恒的数字', async () => {
    api.evaluationReport.mockResolvedValue({ data: { ...partial(), cases: [{ id: 'one', state: 'COMPLETE' }] } }); const w = await setup(); expect(w.text()).toContain('计数未确认'); expect(w.text()).not.toContain('已处理：2'); w.unmount()
  })
  it('重复CASE或负数失败数不会冒充完整处理', async () => {
    api.evaluationReport.mockResolvedValue({ data: { ...final(), summary: { totalCases: 5, failedCases: -1 }, cases: [{ id: 'duplicate' }, { id: 'duplicate' }] } }); const w = await setup(); expect(w.text()).toContain('计数未确认'); expect(w.text()).not.toContain('已处理：5'); w.unmount()
  })
  it('已关闭或卸载时，迟到作业成功不追加报告请求和done事件', async () => {
    const wait = deferred(); api.getJob.mockReturnValue(wait.promise); const w = await setup(); await w.setProps({ modelValue: false }); w.unmount(); wait.resolve({ data: job() }); await flushPromises(); expect(api.evaluationReport).not.toHaveBeenCalled(); expect(w.emitted('done')).toBeUndefined()
  })
  it('新任务读取失败不能残留上一任务的有效计数', async () => {
    const w = await setup(); expect(w.text()).toContain('总样本：5'); api.getJob.mockRejectedValue(new Error('B没有权限')); await w.setProps({ initial: job('job-b','eval-b'), evaluationId: 'eval-b' }); await flushPromises(); expect(w.text()).toContain('job-b'); expect(w.text()).toContain('计数未确认'); expect(w.text()).not.toContain('总样本：5'); w.unmount()
  })
})
