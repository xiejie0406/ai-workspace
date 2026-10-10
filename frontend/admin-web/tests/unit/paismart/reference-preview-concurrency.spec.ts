import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReferencePreview from '@/views/paismart/chat/components/ReferencePreview.vue'
import type { Document, Reference } from '@/types/paismart'

const api = vi.hoisted(() => ({ getDocument: vi.fn(), getReference: vi.fn() }))
vi.mock('@/api/paismart/documents', () => api)
vi.mock('@/views/paismart/documents/components/DocumentPreview.vue', () => ({ default: {
  name: 'DocumentPreviewFixture', props: ['modelValue', 'document', 'initialPage', 'initialAnchor'],
  template: '<div data-test="document-preview" v-if="modelValue">{{document?.title}}</div>'
} }))
const slot = { template: '<div><slot/></div>' }
const global = { stubs: {
  'el-drawer': slot, 'el-descriptions': slot, 'el-descriptions-item': slot,
  'el-alert': { props: ['title'], template: '<p data-test="error">{{title}}</p>' },
  'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot/></button>' }
}, directives: { loading: () => {}, hasPermi: () => {} } }
const a: Reference = { id: 'ref-a', documentId: 'doc-a', title: '文档甲', contentVersion: 2, page: 3, text: '甲的原文片段' }
const b: Reference = { id: 'ref-b', documentId: 'doc-b', title: '文档乙', contentVersion: 2, page: 1, text: '乙的原文片段' }
const documents = {
  'doc-a': { id: 'doc-a', title: '文档甲', contentVersion: 2, rowVersion: 1, visibility: 'PRIVATE', state: 'READY' },
  'doc-b': { id: 'doc-b', title: '文档乙', contentVersion: 2, rowVersion: 1, visibility: 'PRIVATE', state: 'READY' }
} satisfies Record<string, Document>
function deferred<T>() { let resolve!: (value:T)=>void, reject!: (error:unknown)=>void; const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no}); return {promise,resolve,reject} }
const wrappers: ReturnType<typeof mount>[] = []
async function open() {
  const wrapper=mount(ReferencePreview,{props:{modelValue:false,reference:a},global}); wrappers.push(wrapper)
  await wrapper.setProps({modelValue:true}); await flushPromises(); return wrapper
}
async function clickDocument(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('button').trigger('click'); await flushPromises()
}
beforeEach(()=>{
  vi.clearAllMocks()
  api.getReference.mockImplementation((id:string)=>Promise.resolve({data:id===a.id?{...a}:{...b}}))
  api.getDocument.mockImplementation((id:keyof typeof documents)=>Promise.resolve({data:{...documents[id]}}))
})
afterEach(()=>{for(const wrapper of wrappers.splice(0))wrapper.unmount()})

describe('引用对应文档的异步身份与版本',()=>{
  it('甲文档迟到成功不能在已切换的乙引用中打开',async()=>{
    const pending=deferred<unknown>(); api.getDocument.mockReturnValueOnce(pending.promise)
    const wrapper=await open(); await clickDocument(wrapper)
    await wrapper.setProps({reference:b}); await flushPromises()
    pending.resolve({data:{...documents['doc-a']}}); await flushPromises()
    expect(wrapper.text()).toContain('乙的原文片段')
    expect(wrapper.find('[data-test="document-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
  })
  it('甲文档迟到失败不能污染乙引用的错误',async()=>{
    const pending=deferred<unknown>(); api.getDocument.mockReturnValueOnce(pending.promise)
    const wrapper=await open(); await clickDocument(wrapper)
    await wrapper.setProps({reference:b}); await flushPromises()
    pending.reject(new Error('甲的读取失败')); await flushPromises()
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('乙的原文片段')
  })
  it('关闭抽屉后旧成功不重新打开文档或写入错误',async()=>{
    const pending=deferred<unknown>(); api.getDocument.mockReturnValueOnce(pending.promise)
    const wrapper=await open(); await clickDocument(wrapper)
    await wrapper.setProps({modelValue:false})
    pending.resolve({data:{...documents['doc-a']}}); await flushPromises()
    expect(wrapper.find('[data-test="document-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
  })
  it('甲→乙→甲后旧甲响应不能打开新一轮预览',async()=>{
    const pending=deferred<unknown>(); api.getDocument.mockReturnValueOnce(pending.promise)
    const wrapper=await open(); await clickDocument(wrapper)
    await wrapper.setProps({reference:b}); await flushPromises()
    await wrapper.setProps({reference:a}); await flushPromises()
    pending.resolve({data:{...documents['doc-a']}}); await flushPromises()
    expect(wrapper.find('[data-test="document-preview"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('甲的原文片段')
  })
  it('卸载后旧读取不更新文档和错误状态',async()=>{
    const pending=deferred<unknown>(); api.getDocument.mockReturnValueOnce(pending.promise)
    const wrapper=await open(), vm=wrapper.vm as any; await clickDocument(wrapper)
    wrapper.unmount(); pending.reject(new Error('卸载前旧请求失败')); await flushPromises()
    expect(vm.error).toBe(''); expect(vm.documentOpen).toBe(false)
  })
  it('当前文档打开保留同一文档、版本、页码和原文定位',async()=>{
    const wrapper=await open(); await clickDocument(wrapper)
    const preview=wrapper.findComponent({name:'DocumentPreviewFixture'})
    expect(api.getDocument).toHaveBeenCalledExactlyOnceWith('doc-a')
    expect(preview.props('modelValue')).toBe(true)
    expect(preview.props('document').id).toBe('doc-a')
    expect(preview.props('initialPage')).toBe(3)
    expect(preview.props('initialAnchor')).toBe('甲的原文片段')
  })
  it('当前引用被移除时清理旧原文和对应文档',async()=>{
    const wrapper=await open(); await clickDocument(wrapper)
    await wrapper.setProps({reference:undefined}); await flushPromises()
    expect(wrapper.text()).not.toContain('甲的原文片段')
    expect(wrapper.find('[data-test="document-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
  })
  it('当前文档版本已改变时拒绝以新版冒充历史引用，可恢复重试',async()=>{
    api.getDocument.mockResolvedValueOnce({data:{...documents['doc-a'],contentVersion:3}})
    const wrapper=await open(); await clickDocument(wrapper)
    expect(wrapper.get('[data-test="error"]').text()).toContain('不能用新版本冒充原引用')
    expect(wrapper.find('[data-test="document-preview"]').exists()).toBe(false)
    await clickDocument(wrapper)
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="document-preview"]').text()).toBe('文档甲')
  })
  it('同一引用重复读取时晚回的旧错误不覆盖最新成功',async()=>{
    const pending=deferred<unknown>(); api.getDocument.mockReturnValueOnce(pending.promise)
    const wrapper=await open(); await clickDocument(wrapper); await clickDocument(wrapper)
    expect(wrapper.get('[data-test="document-preview"]').text()).toBe('文档甲')
    pending.reject(new Error('第一次读取已超时')); await flushPromises()
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
  })
})
