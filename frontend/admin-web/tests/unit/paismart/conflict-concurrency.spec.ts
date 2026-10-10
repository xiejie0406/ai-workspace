import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ConflictPage from '@/views/paismart/governance/conflicts/index.vue'

const mocks = vi.hoisted(() => ({ listConflicts: vi.fn(), resolveConflict: vi.fn() }))
vi.mock('@/api/paismart/governance', () => mocks)
vi.mock('@/views/paismart/components/JobDialog.vue', () => ({ default: { template: '<div/>' } }))
const slot = { template: '<div><slot/><slot name="footer"/></div>' }
const global = { stubs: {
  ...Object.fromEntries(['el-form','el-form-item','el-select','el-option','el-table','el-table-column','el-pagination','el-dialog','el-row','el-col','el-input','el-checkbox'].map(name => [name, slot])),
  'el-table-column': { template: '<div/>' },
  'el-alert': { props: ['title'], template: '<div>{{title}}</div>' },
  'el-button': { props: ['disabled'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>' }
}, directives: { loading: () => {}, hasPermi: () => {} } }
const a = { id: 'conflict-a', state: 'PENDING', rowVersion: 1, oldDocumentId: 'old-a', newDocumentId: 'new-a' }
const b = { id: 'conflict-b', state: 'PENDING', rowVersion: 4, oldDocumentId: 'old-b', newDocumentId: 'new-b' }
function deferred<T>() { let resolve!: (value:T)=>void, reject!: (error:unknown)=>void; const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no}); return {promise,resolve,reject} }
async function prepare() { const wrapper=mount(ConflictPage,{global}); await flushPromises(); const vm=wrapper.vm as any; vm.compare(a); vm.note='A 的处置理由'; vm.confirmed=true; return {wrapper,vm} }
beforeEach(()=>{vi.clearAllMocks();mocks.listConflicts.mockResolvedValue({rows:[a,b],total:2});mocks.resolveConflict.mockResolvedValue({data:{id:a.id,state:'RESOLVED'}})})

describe('知识冲突保存的请求与当前比较对象隔离',()=>{
  it('A 迟到成功不会关闭 B 的比较窗口或清除其输入',async()=>{
    const pending=deferred<unknown>();mocks.resolveConflict.mockReturnValue(pending.promise)
    const {wrapper,vm}=await prepare();const saving=vm.resolve();await flushPromises();vm.open=false;vm.compare(b);vm.note='B 尚未保存';vm.confirmed=true
    pending.resolve({data:{id:a.id,state:'RESOLVED'}});await saving;await flushPromises()
    expect(vm.selected.id).toBe(b.id);expect(vm.open).toBe(true);expect(vm.note).toBe('B 尚未保存');expect(vm.confirmed).toBe(true);expect(vm.formError).toBe('');expect(mocks.listConflicts).toHaveBeenCalledTimes(2);wrapper.unmount()
  })
  it('A 迟到失败不会写入 B 的错误提示',async()=>{
    const pending=deferred<unknown>();mocks.resolveConflict.mockReturnValue(pending.promise)
    const {wrapper,vm}=await prepare();const saving=vm.resolve();await flushPromises();vm.compare(b);vm.note='B 的处置理由'
    pending.reject(new Error('A 的版本已更新'));await saving;await flushPromises()
    expect(vm.selected.id).toBe(b.id);expect(vm.open).toBe(true);expect(vm.note).toBe('B 的处置理由');expect(vm.formError).toBe('');wrapper.unmount()
  })
  it('A→B→A 重新打开仍隔离旧 A 的保存响应',async()=>{
    const pending=deferred<unknown>();mocks.resolveConflict.mockReturnValue(pending.promise)
    const {wrapper,vm}=await prepare();const saving=vm.resolve();await flushPromises();vm.compare(b);vm.compare(a);vm.note='A 新一轮尚未保存'
    pending.resolve({data:{id:a.id,state:'RESOLVED'}});await saving;await flushPromises()
    expect(vm.selected.id).toBe(a.id);expect(vm.open).toBe(true);expect(vm.note).toBe('A 新一轮尚未保存');expect(vm.formError).toBe('');wrapper.unmount()
  })
  it('当前对象保存成功关闭当前窗口并刷新列表，重复执行只提交一次',async()=>{
    const pending=deferred<unknown>();mocks.resolveConflict.mockReturnValue(pending.promise)
    const {wrapper,vm}=await prepare();const first=vm.resolve();const second=vm.resolve();await flushPromises()
    expect(mocks.resolveConflict).toHaveBeenCalledTimes(1);expect(mocks.resolveConflict).toHaveBeenCalledWith(a,'IGNORED','A 的处置理由')
    pending.resolve({data:{id:a.id,state:'RESOLVED'}});await Promise.all([first,second]);await flushPromises()
    expect(vm.open).toBe(false);expect(vm.saving).toBe(false);expect(mocks.listConflicts).toHaveBeenCalledTimes(2);wrapper.unmount()
  })
  it('当前对象保存失败保留窗口、独立输入及明确错误',async()=>{
    mocks.resolveConflict.mockRejectedValue(new Error('当前版本已变化'))
    const {wrapper,vm}=await prepare();await vm.resolve();await flushPromises()
    expect(vm.open).toBe(true);expect(vm.note).toBe('A 的处置理由');expect(vm.confirmed).toBe(true);expect(vm.formError).toBe('当前版本已变化');expect(vm.saving).toBe(false);expect(mocks.listConflicts).toHaveBeenCalledTimes(1);wrapper.unmount()
  })
  it('关闭窗口后的迟到失败不写入隐藏窗口',async()=>{
    const pending=deferred<unknown>();mocks.resolveConflict.mockReturnValue(pending.promise)
    const {wrapper,vm}=await prepare();const saving=vm.resolve();await flushPromises();vm.open=false
    pending.reject(new Error('关闭前的旧请求失败'));await saving;await flushPromises()
    expect(vm.open).toBe(false);expect(vm.formError).toBe('');expect(vm.saving).toBe(false);wrapper.unmount()
  })
  it('页面卸载后的迟到成功不启动列表刷新',async()=>{
    const pending=deferred<unknown>();mocks.resolveConflict.mockReturnValue(pending.promise)
    const {wrapper,vm}=await prepare();const saving=vm.resolve();await flushPromises();wrapper.unmount()
    pending.resolve({data:{id:a.id,state:'RESOLVED'}});await saving;await flushPromises()
    expect(mocks.listConflicts).toHaveBeenCalledTimes(1)
  })
})
