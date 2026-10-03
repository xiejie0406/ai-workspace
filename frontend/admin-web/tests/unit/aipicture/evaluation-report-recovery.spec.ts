import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Mock } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import request from '@/utils/request'
import EvaluationPage from '@/views/aipicture/evaluation/index.vue'

vi.mock('@/utils/request', () => ({ default: vi.fn() }))
vi.mock('@/plugins/auth', () => ({ default: { hasPermi: () => true } }))
const messages = vi.hoisted(() => ({ error: vi.fn(), success: vi.fn() }))
vi.mock('element-plus', async () => ({ ...await vi.importActual('element-plus'), ElMessage: messages }))
const mounted: ReturnType<typeof mount>[] = []
const transport = vi.mocked(request) as unknown as Mock<(config: { url: string, method?: string }) => Promise<any>>
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: Error) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
const result = (id: string) => ({ code: 200, data: { id, status: 'succeeded', manifest: [], metrics: { cases: [] } } })
function page() {
  const wrapper = mount(EvaluationPage, { attachTo: document.body, global: { plugins: [ElementPlus], directives: { hasPermi: () => {} }, stubs: { pagination: true } } })
  mounted.push(wrapper)
  return wrapper
}
const vmOf = (wrapper: ReturnType<typeof mount>) => wrapper.vm as any

describe('评测报告读取顺序与失败恢复', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
    transport.mockImplementation(async config => config.url === '/aipicture/evaluations'
      ? { code: 200, rows: [], total: 0 } : { code: 200, data: { capabilities: {} } })
  })
  afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = ''; vi.unstubAllGlobals() })

  it('先打开的报告迟到不能覆盖后来打开的报告', async () => {
    const old = deferred<any>(), original = transport.getMockImplementation()!
    transport.mockImplementation(config => config.url.endsWith('/old/report') ? old.promise : config.url.endsWith('/new/report') ? Promise.resolve(result('new')) : original(config))
    const wrapper = page(), vm = vmOf(wrapper)
    await flushPromises()
    const first = vm.showReport('old'); await vm.showReport('new')
    old.resolve(result('old')); await first; await flushPromises()
    expect(vm.report.id).toBe('new')
    expect(document.body.textContent).toContain('"id": "new"')
    expect(document.body.textContent).not.toContain('"id": "old"')
  })

  it('关闭报告后不接纳迟到的成功结果', async () => {
    const pending = deferred<any>(), original = transport.getMockImplementation()!
    transport.mockImplementation(config => config.url.endsWith('/closing/report') ? pending.promise : original(config))
    const wrapper = page(), vm = vmOf(wrapper)
    await flushPromises(); const read = vm.showReport('closing')
    vm.reportOpen = false; await flushPromises()
    pending.resolve(result('closing')); await read
    expect(vm.report).toBeNull()
  })

  it('旧报告的迟到失败不能覆盖当前成功报告或弹出旧错误', async () => {
    const old = deferred<any>(), original = transport.getMockImplementation()!
    transport.mockImplementation(config => config.url.endsWith('/old/report') ? old.promise : config.url.endsWith('/new/report') ? Promise.resolve(result('new')) : original(config))
    const wrapper = page(), vm = vmOf(wrapper)
    await flushPromises(); const first = vm.showReport('old'); await vm.showReport('new')
    old.reject(new Error('旧报告503')); await first
    expect(vm.report.id).toBe('new')
    expect(messages.error).not.toHaveBeenCalled()
    expect(vm.reportError).toBe('')
  })

  it('读取失败停止加载并在原报告明确重读后恢复，不发送评测POST', async () => {
    const original = transport.getMockImplementation()!
    let recovered = false
    transport.mockImplementation(config => config.url.endsWith('/retry/report')
      ? recovered ? Promise.resolve(result('retry')) : Promise.reject(new Error('报告503暂时不可读'))
      : original(config))
    const wrapper = page(), vm = vmOf(wrapper)
    await flushPromises(); await vm.showReport('retry'); await flushPromises()
    expect(document.body.textContent).not.toContain('正在读取报告…')
    expect(document.body.textContent).toContain('报告503暂时不可读')
    recovered = true
    const retry = [...document.body.querySelectorAll('button')].find(button => button.textContent?.trim() === '重新读取报告')
    expect(retry).toBeDefined(); retry!.click(); await flushPromises()
    expect(vm.report.id).toBe('retry'); expect(vm.reportError).toBe('')
    expect(transport.mock.calls.filter(([config]) => config.url.endsWith('/retry/report'))).toHaveLength(2)
    expect(transport.mock.calls.some(([config]) => config.method === 'post')).toBe(false)
  })

  it('销毁页面后不接纳迟到报告或弹出错误', async () => {
    const pending = deferred<any>(), original = transport.getMockImplementation()!
    transport.mockImplementation(config => config.url.endsWith('/destroying/report') ? pending.promise : original(config))
    const wrapper = page(), vm = vmOf(wrapper)
    await flushPromises(); const read = vm.showReport('destroying'); wrapper.unmount()
    pending.reject(new Error('已销毁旧报告错误')); await read
    expect(messages.error).not.toHaveBeenCalled()
    expect(vm.report).toBeNull()
  })
})
