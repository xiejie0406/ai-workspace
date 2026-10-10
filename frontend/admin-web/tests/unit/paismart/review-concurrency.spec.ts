import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, ref, type Ref } from 'vue'
import Review from '@/views/paismart/governance/evaluations/review.vue'

const mocks = vi.hoisted(() => ({ evaluationReport: vi.fn(), listReviews: vi.fn(), saveReview: vi.fn() }))
vi.mock('@/api/paismart/governance', () => mocks)
vi.mock('@/api/paismart/client', () => ({ newKey: () => 'fixture-key', pathId: String, call: vi.fn(), get: vi.fn(), list: vi.fn(), post: vi.fn(), put: vi.fn(), remove: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
const slot = { template: '<div><slot name="header"/><slot/><slot name="footer"/></div>' }
const table = defineComponent({ props: ['data'], setup(props, { slots }) { provide('testRows', computed(() => props.data)); return () => h('div', slots.default?.()) } })
const column = defineComponent({ props: ['prop'], setup(props, { slots }) { const rows = inject<Ref<Record<string, unknown>[]>>('testRows', ref([])); return () => h('div', rows.value.map(row => slots.default?.({ row }) ?? String(row[props.prop] ?? ''))) } })
const global = { stubs: { ...Object.fromEntries(['el-card','el-form','el-form-item','el-empty','el-checkbox','el-select','el-option','el-collapse','el-collapse-item','el-drawer','el-descriptions','el-descriptions-item'].map(name => [name, slot])), 'el-alert': { props: ['title'], template: '<div>{{title}}</div>' }, 'el-input': { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>' }, 'el-button': { props: ['disabled'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>' }, 'el-table': table, 'el-table-column': column, ReferencePreview: true }, directives: { loading: () => {}, hasPermi: () => {} } }
const report = { id: 'eval-1', label: '独立复核夹具', state: 'COMPLETE', summary: {}, cases: [{ id: 'case-a', question: '案例 A', rowVersion: 1 }, { id: 'case-b', question: '案例 B', rowVersion: 3 }] }
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
async function prepare() { const wrapper = mount(Review, { global }); await wrapper.find('input').setValue('eval-1'); await wrapper.findAll('button').find(button => button.text() === '读取报告')!.trigger('click'); await flushPromises(); await wrapper.findAll('button').filter(button => button.text() === '逐例复核')[0]!.trigger('click'); await flushPromises(); const vm = wrapper.vm as any; vm.reason = 'A 的独立理由'; return { wrapper, vm } }
beforeEach(() => { vi.clearAllMocks(); mocks.evaluationReport.mockResolvedValue({ data: report }); mocks.listReviews.mockImplementation((_id, caseId) => Promise.resolve({ rows: [{ id: 'review-' + caseId, caseId, reason: caseId + ' 的既有记录' }] })); mocks.saveReview.mockResolvedValue({ data: {} }) })

describe('人工复核保存的对象与选择归属', () => {
  it('案例 A 保存迟到成功不覆盖后来选中的案例 B 及其未保存输入', async () => {
    const pending = deferred<unknown>(); mocks.saveReview.mockReturnValue(pending.promise)
    const { wrapper, vm } = await prepare(); const saving = vm.save(); await flushPromises()
    await wrapper.findAll('button').filter(button => button.text() === '逐例复核')[1]!.trigger('click'); await flushPromises()
    vm.reason = 'B 尚未保存的理由'; vm.conclusion = 'UNSUPPORTED'; vm.humanScore = 2
    pending.resolve({ data: {} }); await saving; await flushPromises()
    expect(vm.selected.id).toBe('case-b'); expect(vm.reason).toBe('B 尚未保存的理由'); expect(vm.conclusion).toBe('UNSUPPORTED'); expect(vm.humanScore).toBe(2); expect(vm.reviews[0].caseId).toBe('case-b'); expect(vm.formError).toBe(''); wrapper.unmount()
  })
  it('案例 A 保存迟到失败不把错误写入案例 B', async () => {
    const pending = deferred<unknown>(); mocks.saveReview.mockReturnValue(pending.promise)
    const { wrapper, vm } = await prepare(); const saving = vm.save(); await flushPromises()
    await wrapper.findAll('button').filter(button => button.text() === '逐例复核')[1]!.trigger('click'); await flushPromises(); vm.reason = 'B 的独立理由'
    pending.reject(new Error('A 的版本已更新')); await saving; await flushPromises()
    expect(vm.selected.id).toBe('case-b'); expect(vm.reason).toBe('B 的独立理由'); expect(vm.formError).toBe(''); wrapper.unmount()
  })
  it('修改尚未读取的评测 ID 不改变已加载案例的保存目标', async () => {
    const { wrapper, vm } = await prepare(); await wrapper.find('input').setValue('eval-2'); await vm.save(); await flushPromises()
    expect(mocks.saveReview).toHaveBeenCalledWith('eval-1', expect.objectContaining({ caseId: 'case-a', expectedVersion: 1, reason: 'A 的独立理由' })); expect(mocks.listReviews).toHaveBeenLastCalledWith('eval-1', 'case-a'); wrapper.unmount()
  })
  it('修改尚未读取的评测 ID 后下载仍以已加载报告 ID 命名', async () => {
    const { wrapper, vm } = await prepare(); await wrapper.find('input').setValue('eval-2')
    const createObjectURL = vi.fn((_blob: Blob) => 'blob:review-fixture'), revokeObjectURL = vi.fn(); let filename = ''
    vi.useFakeTimers(); vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function(this: HTMLAnchorElement) { filename = this.download })
    try { vm.download(); expect(filename).toBe('评测报告-eval-1.json'); expect(createObjectURL).toHaveBeenCalledOnce(); expect(createObjectURL.mock.calls[0]![0]).toBeInstanceOf(Blob); expect(vm.report.id).toBe('eval-1'); vi.runAllTimers(); expect(revokeObjectURL).toHaveBeenCalledWith('blob:review-fixture') }
    finally { click.mockRestore(); vi.unstubAllGlobals(); vi.useRealTimers(); wrapper.unmount() }
  })
  it('保存案例 A 期间读取另一份报告，A 的迟到响应不能恢复旧抽屉或覆盖新报告', async () => {
    const pending = deferred<unknown>(); mocks.saveReview.mockReturnValue(pending.promise)
    const { wrapper, vm } = await prepare(); const saving = vm.save(); await flushPromises()
    mocks.evaluationReport.mockResolvedValue({ data: { ...report, id: 'eval-2', label: '第二份报告', cases: [{ id: 'case-new', question: '另一评测的案例', rowVersion: 1 }] } })
    await wrapper.find('input').setValue('eval-2'); await wrapper.findAll('button').find(button => button.text() === '读取报告')!.trigger('click'); await flushPromises()
    expect(vm.selected).toBeUndefined(); expect(vm.open).toBe(false)
    pending.resolve({ data: {} }); await saving; await flushPromises()
    expect(vm.report.id).toBe('eval-2'); expect(vm.selected).toBeUndefined(); expect(vm.reviews).toEqual([]); expect(vm.formError).toBe(''); wrapper.unmount()
  })
  it('保存中的重复执行只提交一次，当前案例成功后更新真实版本与历史', async () => {
    const pending = deferred<unknown>(); mocks.saveReview.mockReturnValue(pending.promise)
    const { wrapper, vm } = await prepare(); const first = vm.save(); const duplicate = vm.save(); await flushPromises(); expect(mocks.saveReview).toHaveBeenCalledTimes(1)
    mocks.evaluationReport.mockResolvedValue({ data: { ...report, cases: [{ ...report.cases[0], rowVersion: 2 }, report.cases[1]] } }); pending.resolve({ data: {} }); await Promise.all([first, duplicate]); await flushPromises()
    expect(vm.selected.id).toBe('case-a'); expect(vm.selected.rowVersion).toBe(2); expect(vm.reviews[0].caseId).toBe('case-a'); expect(vm.saving).toBe(false); wrapper.unmount()
  })
})
