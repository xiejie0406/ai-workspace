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
const transport = vi.mocked(request) as unknown as Mock<(config: { url: string, method?: string, data?: any }) => Promise<any>>
function page() {
  const wrapper = mount(EvaluationPage, { attachTo: document.body, global: { plugins: [ElementPlus], directives: { hasPermi: () => {} }, stubs: { pagination: true } } })
  mounted.push(wrapper)
  return wrapper
}
const vmOf = (wrapper: ReturnType<typeof mount>) => wrapper.vm as any
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

describe('专项评测页正式来源与人工质量', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
    transport.mockImplementation(async config => config.url === '/aipicture/assets'
      ? { code: 200, rows: [{ id: 'independent-mask', width: 64, height: 64, kind: 'uploaded' }] }
      : config.url === '/aipicture/evaluations' && config.method === 'get' ? { code: 200, rows: [], total: 0 }
        : { code: 200, data: { capabilities: {} } } as any)
  })
  afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = ''; vi.unstubAllGlobals() })

  it('换来源、关闭和销毁后不接纳迟到正式结果', async () => {
    const old = deferred<any>(), closing = deferred<any>(), destroying = deferred<any>()
    const original = transport.getMockImplementation()!
    transport.mockImplementation(config => config.url === '/aipicture/runs/old' ? old.promise : config.url === '/aipicture/runs/closing' ? closing.promise : config.url === '/aipicture/runs/destroying' ? destroying.promise : config.url === '/aipicture/runs/new' ? Promise.resolve({ code: 200, data: { id: 'new', tool: 'generate_image', status: 'succeeded', candidates: [{ id: 'new-output' }] } }) as any : original(config))
    const wrapper = page(), vm = vmOf(wrapper)
    await flushPromises(); await vm.openCreate()
    vm.sample.task = 'assess_result'; vm.sample.suite = 'generation'; vm.resetAssessment()
    vm.sample.source_run_id = 'old'; const oldRead = vm.loadSourceRun()
    vm.sample.source_run_id = 'new'; await vm.loadSourceRun(); vm.sample.output_asset_id = 'new-output'
    old.resolve({ code: 200, data: { id: 'old', status: 'succeeded', candidates: [{ id: 'old-output' }] } }); await oldRead
    expect(vm.sourceRun.id).toBe('new'); expect(vm.sample.output_asset_id).toBe('new-output')
    vm.sample.source_run_id = 'closing'; const closeRead = vm.loadSourceRun(); vm.creating = false; await flushPromises()
    closing.resolve({ code: 200, data: { id: 'closing', status: 'succeeded', candidates: [] } }); await closeRead
    expect(vm.sourceRun).toBeNull()
    await vm.openCreate(); vm.sample.source_run_id = 'destroying'; const destroyRead = vm.loadSourceRun(); wrapper.unmount()
    destroying.resolve({ code: 200, data: { id: 'destroying', status: 'succeeded', candidates: [] } }); await destroyRead
    expect(vm.sourceRun).toBeNull()
  })

  it('仅提交用户选中的正式候选与人工记录，不制造来源或接受结论', async () => {
    const original = transport.getMockImplementation()!
    transport.mockImplementation(config => config.url === '/aipicture/runs/formal' ? Promise.resolve({ code: 200, data: { id: 'formal', tool: 'generate_image', status: 'succeeded', candidates: [{ id: 'first', width: 64, height: 64 }, { id: 'chosen', width: 64, height: 64 }] } }) as any : original(config))
    const wrapper = page(), vm = vmOf(wrapper)
    await flushPromises(); await vm.openCreate()
    vm.sample.task = 'assess_result'; vm.sample.suite = 'generation'; vm.resetAssessment(); vm.sample.id = '内容核对'; vm.sample.source_run_id = 'formal'; await vm.loadSourceRun()
    vm.sample.output_asset_id = 'chosen'; vm.sample.human_review = { status: 'pending', notes: '主体质量尚待检查' }; vm.addSample(); await vm.submit()
    const submit = transport.mock.calls.find(([config]) => config.url === '/aipicture/evaluations' && config.method === 'post')?.[0]
    expect(submit?.data.manifest).toEqual([{ id: '内容核对', task: 'assess_result', suite: 'generation', source_run_id: 'formal', output_asset_id: 'chosen', thresholds: { size: 1 }, human_review: { status: 'pending', notes: '主体质量尚待检查' } }])
    expect(vm.sample.human_review).toEqual({ status: 'pending', notes: '' })
  })

  it('来源读取错误可重读并保留独立输入，未读取成功不能加入清单', async () => {
    const original = transport.getMockImplementation()!
    let recovered = false
    transport.mockImplementation(config => config.url === '/aipicture/runs/sam-formal'
      ? recovered ? Promise.resolve({ code: 200, data: { id: 'sam-formal', tool: 'selection', status: 'succeeded', candidates: [] } }) as any : Promise.reject(new Error('正式来源 503 暂不可读'))
      : original(config))
    const wrapper = page(), vm = vmOf(wrapper)
    await flushPromises(); await vm.openCreate(); vm.sample.task = 'assess_result'; vm.resetAssessment(); vm.sample.source_run_id = 'sam-formal'; vm.sample.id = '人工遮罩'; vm.sample.expect_asset_id = 'independent-mask'
    await vm.loadSourceRun(); await flushPromises()
    expect(vm.sourceError).toContain('503'); expect(document.body.textContent).toContain('正式来源 503 暂不可读'); vm.addSample(); expect(vm.manifest).toHaveLength(0)
    recovered = true; await vm.loadSourceRun(); vm.addSample()
    expect(vm.sourceError).toBe(''); expect(vm.manifest[0].expect_asset_id).toBe('independent-mask')
  })

  it('报告明确区分技术通过、人工未通过与专项整体，OCR 格式可见', async () => {
    const original = transport.getMockImplementation()!
    const manifest = [{ id: 'OCR', task: 'assess_result', suite: 'ocr', human_review: { status: 'failed', notes: '价格文字误识别' } }]
    transport.mockImplementation(config => config.url === '/aipicture/evaluations/report-one/report' ? Promise.resolve({ code: 200, data: { id: 'report-one', status: 'succeeded', manifest, metrics: { cases: [{ id: 'OCR', technical_passed: true, real_model_verified: true, overall: false, passed: false, metrics: { cer: 0, bbox_iou: 1 }, human_review: manifest[0].human_review }] } } }) as any : original(config))
    const wrapper = page(), vm = vmOf(wrapper)
    await flushPromises(); await vm.openCreate(); vm.sample.task = 'assess_result'; vm.sample.suite = 'ocr'; vm.resetAssessment(); await flushPromises()
    expect(document.body.textContent).toContain('来源画布像素'); expect(document.body.textContent).toContain('文字数量必须一致')
    await vm.showReport('report-one'); await flushPromises()
    expect(vm.reportRows[0]).toMatchObject({ technicalLabel: '通过', modelLabel: '已核实', reviewLabel: '人工质量未通过', overallLabel: '未通过', reviewNotes: '价格文字误识别' })
    expect(document.body.textContent).toContain('不由技术评测或人工质量代签')
  })
})
