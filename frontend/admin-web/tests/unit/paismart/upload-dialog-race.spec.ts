import {beforeEach,afterEach,describe,expect,it,vi} from 'vitest'
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {computed,defineComponent,h,inject,provide,ref,type Ref} from 'vue'
import {File as NodeFile} from 'node:buffer'
import {webcrypto} from 'node:crypto'
import UploadDialog from '@/views/paismart/documents/components/UploadDialog.vue'

const mocks=vi.hoisted(()=>({listUploads:vi.fn(),supportedUploadTypes:vi.fn(),mergeUpload:vi.fn(),createUpload:vi.fn(),uploadStatus:vi.fn(),putChunk:vi.fn(),createPersonalSpace:vi.fn()}))
vi.mock('@/api/paismart/upload',()=>mocks)
vi.mock('@/api/paismart/spaces',()=>mocks)
vi.mock('@/api/paismart/client',()=>({newKey:()=> 'upload-dialog-test-key'}))
function deferred(){let resolve!:(value:unknown)=>void,reject!:(reason:unknown)=>void;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
const slot={template:'<div><slot name="header"/><slot/><slot name="footer"/></div>'}
const table=defineComponent({props:['data'],setup(props,{slots}){provide('upload-rows',computed(()=>props.data));return()=>h('div',slots.default?.())}})
const column=defineComponent({props:['prop'],setup(props,{slots}){const rows=inject<Ref<Record<string,unknown>[]>>('upload-rows',ref([]));return()=>h('div',rows.value.map(row=>h('div',{'data-upload-id':row.uploadId},slots.default?.({row})??String(row[props.prop]??''))))}})
const stubs={...Object.fromEntries(['el-dialog','el-form','el-form-item','el-select','el-option','el-card','el-pagination'].map(name=>[name,slot])),
  'el-table':table,'el-table-column':column,'el-input':{template:'<input/>'},
  'el-alert':{props:['title'],template:'<p>{{title}}</p>'},'el-progress':{props:['percentage'],template:'<p data-progress>{{percentage}}</p>'},
  'el-button':{props:['disabled'],emits:['click'],template:'<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>'}}
const old={uploadId:'old',state:'MERGED',filename:'旧记录.txt',spaceId:'s1',visibility:'PRIVATE',completedChunks:[],totalChunks:1}
const next={uploadId:'new',state:'MERGED',filename:'新记录.txt',spaceId:'s1',visibility:'PRIVATE',completedChunks:[],totalChunks:1}
function button(wrapper:VueWrapper,text:string){return wrapper.findAll('button').find(b=>b.text()===text)!}
function rowButton(wrapper:VueWrapper,id:string){return wrapper.findAll(`[data-upload-id="${id}"] button`).at(-1)!}
async function open(){const wrapper=mount(UploadDialog,{props:{modelValue:true,spaces:[]},global:{stubs,directives:{hasPermi:()=>{},loading:()=>{}}}});await button(wrapper,'查询续传记录').trigger('click');await flushPromises();return wrapper}
beforeEach(()=>{vi.clearAllMocks();vi.stubGlobal('crypto',webcrypto);mocks.listUploads.mockResolvedValue({code:200,rows:[old,next],total:2});mocks.supportedUploadTypes.mockResolvedValue({data:[]})})
afterEach(()=>vi.unstubAllGlobals())
describe('上传记录回源的请求归属',()=>{
  it.each(['success','failure'])('关闭后旧读取迟到 %s 不更新提示或发出上传完成事件',async(outcome)=>{
    const pending=deferred();mocks.mergeUpload.mockImplementationOnce(()=>pending.promise)
    const wrapper=await open();await rowButton(wrapper,'old').trigger('click');await flushPromises()
    await wrapper.setProps({modelValue:false})
    if(outcome==='success')pending.resolve({data:{id:'old-doc',title:'迟到旧文档',state:'UPLOADED'}});else pending.reject(new Error('迟到旧错误'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('迟到旧文档');expect(wrapper.text()).not.toContain('迟到旧错误');expect(wrapper.emitted('uploaded')).toBeUndefined()
    wrapper.unmount()
  })
  it.each(['success','failure'])('暂停后选择另一已合并记录，旧读取迟到 %s 不能提交新界面',async(outcome)=>{
    const previous=deferred(),current=deferred();mocks.mergeUpload.mockImplementationOnce(()=>previous.promise).mockImplementationOnce(()=>current.promise)
    const wrapper=await open();await rowButton(wrapper,'old').trigger('click');await flushPromises()
    expect(rowButton(wrapper,'new').attributes('disabled')).toBeDefined();expect(button(wrapper,'新文件上传').attributes('disabled')).toBeDefined()
    await button(wrapper,'暂停上传').trigger('click');await rowButton(wrapper,'new').trigger('click');await flushPromises()
    expect(mocks.mergeUpload).toHaveBeenCalledTimes(2)
    if(outcome==='success')previous.resolve({data:{id:'old-doc',title:'迟到旧文档',state:'UPLOADED'}});else previous.reject(new Error('迟到旧错误'))
    await flushPromises()
    expect(wrapper.text()).toContain('原记录文件名：新记录.txt');expect(wrapper.text()).not.toContain('迟到旧文档');expect(wrapper.text()).not.toContain('迟到旧错误')
    expect(wrapper.emitted('uploaded')).toBeUndefined();expect(button(wrapper,'暂停上传').exists()).toBe(true)
    current.resolve({data:{id:'new-doc',title:'当前新文档',state:'UPLOADED'}});await flushPromises()
    expect(wrapper.text()).toContain('当前新文档');expect(wrapper.emitted('uploaded')).toHaveLength(1);expect(wrapper.findAll('button').some(b=>b.text()==='暂停上传')).toBe(false)
    wrapper.unmount()
  })
  it.each(['success','failure'])('普通上传暂停后读取已合并记录，旧上传迟到 %s 不能解锁新读取',async(outcome)=>{
    const previous=deferred(),current=deferred()
    mocks.createUpload.mockResolvedValue({data:{uploadId:'upload-run'}})
    mocks.uploadStatus.mockResolvedValue({data:{uploadId:'upload-run',state:'UPLOADING',chunkSize:5,completedChunks:[0],filename:'upload-run.txt',byteSize:5}})
    mocks.mergeUpload.mockImplementationOnce(()=>previous.promise).mockImplementationOnce(()=>current.promise)
    const wrapper=await open()
    await wrapper.setProps({modelValue:false,initialSpace:'s1'});await wrapper.setProps({modelValue:true});await flushPromises()
    const chooser=wrapper.find('input[type="file"]')
    Object.defineProperty(chooser.element,'files',{value:[new NodeFile(['abcde'],'upload-run.txt')],configurable:true})
    await chooser.trigger('change');await button(wrapper,'开始上传').trigger('click')
    await vi.waitFor(()=>expect(mocks.mergeUpload).toHaveBeenCalledTimes(1))
    await button(wrapper,'暂停上传').trigger('click');await rowButton(wrapper,'new').trigger('click');await flushPromises()
    expect(mocks.mergeUpload).toHaveBeenCalledTimes(2)
    if(outcome==='success')previous.resolve({data:{id:'upload-run-doc',title:'旧上传文档',state:'UPLOADED'}});else previous.reject(new Error('旧上传错误'))
    await flushPromises()
    expect(wrapper.text()).toContain('原记录文件名：新记录.txt')
    expect(wrapper.text()).not.toContain('旧上传文档');expect(wrapper.text()).not.toContain('旧上传错误')
    expect(wrapper.emitted('uploaded')).toBeUndefined()
    expect(wrapper.findAll('button').some(b=>b.text()==='暂停上传')).toBe(true)
    expect(button(wrapper,'新文件上传').attributes('disabled')).toBeDefined()
    current.resolve({data:{id:'new-doc',title:'当前新文档',state:'UPLOADED'}});await flushPromises()
    expect(wrapper.text()).toContain('当前新文档');expect(wrapper.emitted('uploaded')).toHaveLength(1)
    expect(wrapper.findAll('button').some(b=>b.text()==='暂停上传')).toBe(false)
    wrapper.unmount()
  })
})
