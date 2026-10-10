import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { Blob as NodeBlob } from 'node:buffer'
import DocumentPreview from '@/views/paismart/documents/components/DocumentPreview.vue'
import type { Document } from '@/types/paismart'

const api = vi.hoisted(() => ({ getDocumentContent: vi.fn(), getDocumentPage: vi.fn(), downloadDocument: vi.fn() }))
vi.mock('@/api/paismart/documents', () => api)
vi.mock('@/views/paismart/documents/components/PdfPage.vue', () => ({ default: { name: 'OfflinePdfPage', props: ['blob', 'zoom', 'anchor'], template: '<div data-test="pdf-page">PDF</div>' } }))
const global = {
  stubs: {
    'el-drawer': { template: '<div><slot /></div>' }, 'el-alert': true, 'el-empty': true,
    'el-input-number': { props: ['modelValue'], template: '<input data-test="page-number" :value="modelValue" />' },
    'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' }
  }, directives: { loading: () => {} }
}
function document(mimeType: string, pageCount: number): Document {
  return { id: 'offline-document', title: '离线预览夹具', rowVersion: 2, contentVersion: 3, visibility: 'PUBLIC', state: 'READY', mimeType, pageCount }
}
async function open(doc: Document, initialPage = 1) {
  const wrapper = mount(DocumentPreview, { props: { modelValue: false, document: doc, initialPage, initialAnchor: '引用原文' }, global })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.clearAllMocks()
  api.getDocumentContent.mockResolvedValue(new NodeBlob(['蓝杉项目归档原文'], { type: 'text/plain;charset=UTF-8' }))
  api.getDocumentPage.mockResolvedValue(new NodeBlob(['offline-pdf'], { type: 'application/pdf' }))
  vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:offline-fixture'), revokeObjectURL: vi.fn() })
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('文档预览按类型选择受控接口', () => {
  it('下载 A 等待期间切换 B，保存的仍是 A 文件名和 A 字节', async () => {
    let complete!: (value: NodeBlob) => void
    api.downloadDocument.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const clicked: string[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { clicked.push(this.download) })
    const wrapper = await open({ ...document('text/plain', 1), id: 'doc-a', filename: 'A.txt' })
    await wrapper.findAll('button').find(button => button.text() === '下载原文件')!.trigger('click')
    await wrapper.setProps({ document: { ...document('text/plain', 1), id: 'doc-b', filename: 'B.txt' } })
    await flushPromises()
    const originalBytes = new NodeBlob(['A 的原始字节'], { type: 'text/plain' })
    complete(originalBytes)
    await flushPromises()
    expect(api.downloadDocument).toHaveBeenCalledExactlyOnceWith('doc-a')
    expect(URL.createObjectURL).toHaveBeenCalledWith(originalBytes)
    expect(clicked).toEqual(['A.txt'])
    click.mockRestore()
    wrapper.unmount()
  })
  it('下载 A 等待期间切换 B，A 的失败不会污染 B 的预览', async () => {
    let fail!: (reason: Error) => void
    api.downloadDocument.mockImplementation(() => new Promise((_resolve, reject) => { fail = reject }))
    const wrapper = await open({ ...document('text/plain', 1), id: 'doc-a', filename: 'A.txt' })
    await wrapper.findAll('button').find(button => button.text() === '下载原文件')!.trigger('click')
    await wrapper.setProps({ document: { ...document('text/plain', 1), id: 'doc-b', filename: 'B.txt' } })
    await flushPromises()
    fail(new Error('A 的下载已失败'))
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElAlert' }).exists()).toBe(false)
    expect(wrapper.find('el-alert-stub').exists()).toBe(false)
    expect(wrapper.find('pre').text()).toBe('蓝杉项目归档原文')
    wrapper.unmount()
  })
  it('TXT 解析得到 pageCount=1 时读取正文并隐藏 PDF 分页', async () => {
    const wrapper = await open(document('text/plain', 1))
    expect(api.getDocumentContent).toHaveBeenCalledExactlyOnceWith('offline-document', 3, expect.any(AbortSignal))
    expect(api.getDocumentPage).not.toHaveBeenCalled()
    expect(wrapper.find('pre').text()).toBe('蓝杉项目归档原文')
    expect(wrapper.find('[data-test="page-number"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('PDF 有页数时保留引用指定页和内容版本，使用 PDF 页接口', async () => {
    const wrapper = await open(document('application/pdf', 4), 2)
    expect(api.getDocumentPage).toHaveBeenCalledExactlyOnceWith('offline-document', 2, 3, expect.any(AbortSignal))
    expect(api.getDocumentContent).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="page-number"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="pdf-page"]').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'OfflinePdfPage' }).props('anchor')).toBe('引用原文')
    wrapper.unmount()
  })
  it('PDF 尚无页数时维持正文接口和待解析提示', async () => {
    const wrapper = await open(document('application/pdf', 0))
    expect(api.getDocumentContent).toHaveBeenCalledExactlyOnceWith('offline-document', 3, expect.any(AbortSignal))
    expect(api.getDocumentPage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('页数待解析')
    expect(wrapper.find('[data-test="page-number"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
