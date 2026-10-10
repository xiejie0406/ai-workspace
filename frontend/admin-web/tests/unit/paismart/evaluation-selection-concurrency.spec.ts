import {beforeEach,describe,expect,it,vi} from 'vitest'
import {flushPromises,mount} from '@vue/test-utils'
import {ElButton,ElForm,ElFormItem,ElInput,ElInputNumber,ElRadio,ElRadioGroup} from 'element-plus'
import Evaluations from '@/views/paismart/governance/evaluations/index.vue'
const api=vi.hoisted(()=>({listEvaluations:vi.fn(),createEvaluation:vi.fn(),listEvaluationDatasets:vi.fn(),uploadEvaluationDataset:vi.fn(),listSpaces:vi.fn(),newKey:vi.fn(),push:vi.fn()}))
vi.mock('@/api/paismart/governance',()=>api)
vi.mock('@/api/paismart/spaces',()=>({listSpaces:api.listSpaces}))
vi.mock('@/api/paismart/client',()=>({newKey:api.newKey}))
vi.mock('vue-router',()=>({useRouter:()=>({push:api.push})}))
const global={components:{ElButton,ElForm,ElFormItem,ElInput,ElInputNumber,ElRadio,ElRadioGroup},stubs:{
 ...Object.fromEntries(['el-table','el-table-column','el-pagination'].map(name=>[name,true])),
 'el-dialog':{props:['modelValue'],template:'<section v-if="modelValue" data-dialog><slot/><slot name="footer"/></section>'},
 'el-select':{props:['modelValue','placeholder'],emits:['update:modelValue'],template:'<select :aria-label="placeholder" :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value)"><option value=""/><slot/></select>'},
 'el-option':{props:['value','label'],template:'<option :value="value">{{label}}</option>'},
 'el-alert':{props:['title'],template:'<p>{{title}}</p>'},JobDialog:true,
 },directives:{hasPermi:()=>{},loading:()=>{}}}
const jsonl=JSON.stringify({id:'sample-A',question:'独立问题A',referenceAnswer:'独立答案A',expectedDocumentIds:['doc-A']})
const uploadedA={id:'dataset-A',label:'草稿A',caseCount:1,byteSize:100,sha256:'a'.repeat(64),source:'UPLOAD'}
const jobA={id:'job-A',jobId:'job-A',state:'QUEUED',kind:'EVALUATION_RUN'}
function deferred(){let resolve!:(v:any)=>void,reject!:(e:unknown)=>void;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return{resolve,reject,promise}}
function button(wrapper:any,label:string){return wrapper.findAll('button').find((b:any)=>b.text()===label)!}
async function source(wrapper:any,value:string){const radio=wrapper.findAll('input[type="radio"]').find((input:any)=>(input.element as HTMLInputElement).value===value);expect(radio).toBeDefined();expect((radio!.element as HTMLInputElement).disabled).toBe(false);await radio!.setValue(true);expect((wrapper.vm as any).source).toBe(value)}
async function label(wrapper:any,value:string){const input=wrapper.find('section[data-dialog] input[maxlength="100"]');expect(input.exists()).toBe(true);expect((input.element as HTMLInputElement).disabled).toBe(false);await input.setValue(value);expect((wrapper.vm as any).form.label).toBe(value)}
async function choose(wrapper:any,name='A.jsonl'){const input=wrapper.find('input[type="file"]');expect(input.exists()).toBe(true);expect((input.element as HTMLInputElement).disabled).toBe(false);const file=new File([jsonl],name,{type:'application/x-ndjson'});Object.defineProperty(input.element,'files',{configurable:true,value:[file]});await input.trigger('change');expect((wrapper.vm as any).file).toBe(file);return file}
async function setup(){const wrapper=mount(Evaluations,{global});await flushPromises();await button(wrapper,'发起评测').trigger('click');await flushPromises();await label(wrapper,'草稿A');return{wrapper,vm:wrapper.vm as any}}
beforeEach(()=>{vi.resetAllMocks();let key=0;api.newKey.mockImplementation(()=>`fixture-evaluation-key-${++key}`);api.listEvaluations.mockResolvedValue({rows:[],total:0});api.listEvaluationDatasets.mockResolvedValue({rows:[uploadedA,{...uploadedA,id:'builtin:fixture',label:'受控资源',source:'RESOURCE'}],total:2});api.listSpaces.mockResolvedValue({rows:[]});api.uploadEvaluationDataset.mockResolvedValue({data:uploadedA});api.createEvaluation.mockResolvedValue({data:jobA})})
describe('评测上传及提交的当前草稿归属',()=>{
 it('上传A等待期间名称改B，旧A回执不登记到当前B',async()=>{
  const wait=deferred();api.uploadEvaluationDataset.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await source(wrapper,'FILE');const file=await choose(wrapper);await button(wrapper,'上传评测集').trigger('click');await flushPromises();expect(api.uploadEvaluationDataset.mock.calls[0][0]).toBe(file);expect(api.uploadEvaluationDataset.mock.calls[0][2]).toBe('草稿A');expect((wrapper.find('input[type="file"]').element as HTMLInputElement).disabled).toBe(true)
  await label(wrapper,'草稿B');wait.resolve({data:uploadedA});await flushPromises();expect(vm.form.label).toBe('草稿B');expect(vm.uploaded).toBeUndefined();expect(vm.selectedDataset).toBeUndefined();expect(vm.formError).toBe('');expect(vm.open).toBe(true);wrapper.unmount()
 })
 it('上传A等待期间FILE→INLINE→FILE，旧响应不登记到新来源会话',async()=>{
  const wait=deferred();api.uploadEvaluationDataset.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await source(wrapper,'FILE');await choose(wrapper);await button(wrapper,'上传评测集').trigger('click');await flushPromises();expect(api.uploadEvaluationDataset).toHaveBeenCalledOnce();await source(wrapper,'INLINE');await wrapper.find('textarea').setValue(jsonl);await source(wrapper,'FILE');wait.resolve({data:uploadedA});await flushPromises();expect(vm.uploaded).toBeUndefined();expect(vm.form.inlineJsonl).toBe(jsonl);expect(vm.open).toBe(true);wrapper.unmount()
 })
 it('内联提交A等待期间实际编辑新草稿，旧成功不关闭新草稿或弹出旧任务',async()=>{
  const wait=deferred();api.createEvaluation.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await wrapper.find('textarea').setValue(jsonl);await button(wrapper,'提交评测').trigger('click');await flushPromises();expect(api.createEvaluation).toHaveBeenCalledOnce();expect(api.createEvaluation.mock.calls[0][0]).toMatchObject({label:'草稿A',inlineJsonl:jsonl,topK:5,seed:42});await label(wrapper,'草稿B');await wrapper.find('textarea').setValue(JSON.stringify({question:'问题B',referenceAnswer:'答案B'}));wait.resolve({data:jobA});await flushPromises();expect(vm.open).toBe(true);expect(vm.jobOpen).toBe(false);expect(vm.job).toBeUndefined();expect(vm.form.label).toBe('草稿B');expect(vm.formError).toBe('');expect(api.listEvaluations).toHaveBeenCalledTimes(2);wrapper.unmount()
 })
 it('内联提交等待期间实际切换文件来源，旧失败不污染新来源草稿',async()=>{
  const wait=deferred();api.createEvaluation.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await wrapper.find('textarea').setValue(jsonl);await button(wrapper,'提交评测').trigger('click');await flushPromises();expect(api.createEvaluation).toHaveBeenCalledOnce();await source(wrapper,'FILE');await label(wrapper,'文件新草稿');wait.reject(new Error('旧内联评测未知结果'));await flushPromises();expect(vm.formError).toBe('');expect(vm.open).toBe(true);expect(vm.form.label).toBe('文件新草稿');expect(vm.jobOpen).toBe(false);wrapper.unmount()
 })
 it('当前文件上传成功保留文件、标签和hash，随后真正选择B文件清除A登记',async()=>{
  const{wrapper,vm}=await setup();await source(wrapper,'FILE');const fileA=await choose(wrapper);await button(wrapper,'上传评测集').trigger('click');await flushPromises();expect(api.uploadEvaluationDataset).toHaveBeenCalledOnce();expect(api.uploadEvaluationDataset.mock.calls[0]).toEqual([fileA,expect.any(String),'草稿A']);expect(vm.selectedDataset).toMatchObject(uploadedA);expect(wrapper.text()).toContain(uploadedA.sha256);expect(api.createEvaluation).not.toHaveBeenCalled();expect(api.listEvaluationDatasets).toHaveBeenCalledTimes(2)
  const fileB=await choose(wrapper,'B.jsonl');expect(vm.file).toBe(fileB);expect(vm.uploaded).toBeUndefined();expect(vm.selectedDataset).toBeUndefined();expect(vm.formError).toBe('');wrapper.unmount()
 })
 it('同文件上传结果未知时保留输入，用户重试沿用原key而不自动重送',async()=>{
  api.uploadEvaluationDataset.mockRejectedValueOnce(new Error('上传结果未知')).mockResolvedValue({data:uploadedA});const{wrapper,vm}=await setup();await source(wrapper,'FILE');const file=await choose(wrapper);await button(wrapper,'上传评测集').trigger('click');await flushPromises();expect(vm.formError).toContain('上传结果未知');expect(vm.file).toBe(file);expect(vm.uploaded).toBeUndefined();expect(api.uploadEvaluationDataset).toHaveBeenCalledOnce();await button(wrapper,'上传评测集').trigger('click');await flushPromises();expect(api.uploadEvaluationDataset).toHaveBeenCalledTimes(2);expect(api.uploadEvaluationDataset.mock.calls[0]).toEqual(api.uploadEvaluationDataset.mock.calls[1]);expect(vm.uploaded).toMatchObject(uploadedA);wrapper.unmount()
 })
 it('上传等待切换内联草稿，旧失败不污染可编辑的新来源',async()=>{
  const wait=deferred();api.uploadEvaluationDataset.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await source(wrapper,'FILE');await choose(wrapper);await button(wrapper,'上传评测集').trigger('click');await flushPromises();await source(wrapper,'INLINE');await wrapper.find('textarea').setValue(jsonl);wait.reject(new Error('旧文件上传失败'));await flushPromises();expect(vm.formError).toBe('');expect(vm.form.inlineJsonl).toBe(jsonl);expect(vm.uploaded).toBeUndefined();expect(vm.uploading).toBe(false);wrapper.unmount()
 })
 it('上传等待关闭并重开同一窗口，旧成功只刷新可回查集列表',async()=>{
  const wait=deferred();api.uploadEvaluationDataset.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await source(wrapper,'FILE');await choose(wrapper);await button(wrapper,'上传评测集').trigger('click');await flushPromises();await button(wrapper,'取消').trigger('click');expect(vm.open).toBe(false);await button(wrapper,'发起评测').trigger('click');await flushPromises();wait.resolve({data:uploadedA});await flushPromises();expect(vm.open).toBe(true);expect(vm.uploaded).toBeUndefined();expect(vm.formError).toBe('');expect(api.listEvaluationDatasets).toHaveBeenCalledTimes(3);expect(api.createEvaluation).not.toHaveBeenCalled();wrapper.unmount()
 })
 it('上传等待期间卸载，旧成功不登记或追加集列表请求',async()=>{
  const wait=deferred();api.uploadEvaluationDataset.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await source(wrapper,'FILE');await choose(wrapper);await button(wrapper,'上传评测集').trigger('click');await flushPromises();wrapper.unmount();wait.resolve({data:uploadedA});await flushPromises();expect(vm.uploaded).toBeUndefined();expect(api.listEvaluationDatasets).toHaveBeenCalledOnce();expect(api.createEvaluation).not.toHaveBeenCalled()
 })
 it('当前内联提交只发一次，回执QUEUED后显示同一任务并刷新列表',async()=>{
  const wait=deferred();api.createEvaluation.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await wrapper.find('textarea').setValue(jsonl);const submitButton=button(wrapper,'提交评测');await submitButton.trigger('click');await submitButton.trigger('click');await flushPromises();expect(api.createEvaluation).toHaveBeenCalledOnce();expect(api.createEvaluation.mock.calls[0][0]).toEqual({label:'草稿A',spaceId:undefined,topK:5,seed:42,inlineJsonl:jsonl});wait.resolve({data:jobA});await flushPromises();expect(vm.open).toBe(false);expect(vm.jobOpen).toBe(true);expect(vm.job).toMatchObject({jobId:'job-A',state:'QUEUED'});expect(vm.saving).toBe(false);expect(api.listEvaluations).toHaveBeenCalledTimes(2);wrapper.unmount()
 })
 it('当前FILE提交只使用已确认datasetId，不混入内联文本或文件路径',async()=>{
  const{wrapper,vm}=await setup();await source(wrapper,'FILE');await choose(wrapper);await button(wrapper,'上传评测集').trigger('click');await flushPromises();expect(vm.selectedDataset).toMatchObject(uploadedA);await button(wrapper,'提交评测').trigger('click');await flushPromises();expect(api.createEvaluation).toHaveBeenCalledOnce();expect(api.createEvaluation.mock.calls[0][0]).toEqual({label:'草稿A',spaceId:undefined,topK:5,seed:42,datasetId:'dataset-A'});expect(vm.job).toMatchObject(jobA);expect(vm.open).toBe(false);wrapper.unmount()
 })
 it('当前DATASET来源实际选择受控资源，保持原API和seed参数',async()=>{
  const{wrapper,vm}=await setup();await source(wrapper,'DATASET');const select=wrapper.find('select[aria-label="选择后台登记资源或本人上传集"]');expect(select.exists()).toBe(true);await select.setValue('builtin:fixture');expect(vm.datasetId).toBe('builtin:fixture');expect(vm.selectedDataset.id).toBe('builtin:fixture');await button(wrapper,'提交评测').trigger('click');await flushPromises();expect(api.uploadEvaluationDataset).not.toHaveBeenCalled();expect(api.createEvaluation.mock.calls[0][0]).toEqual({label:'草稿A',spaceId:undefined,topK:5,seed:42,datasetId:'builtin:fixture'});expect(vm.job).toMatchObject(jobA);wrapper.unmount()
 })
 it('当前提交失败保留输入，用户同输入重试使用同key',async()=>{
  api.createEvaluation.mockRejectedValueOnce(new Error('评测受理结果未知')).mockResolvedValue({data:jobA});const{wrapper,vm}=await setup();await wrapper.find('textarea').setValue(jsonl);await button(wrapper,'提交评测').trigger('click');await flushPromises();expect(vm.open).toBe(true);expect(vm.form.inlineJsonl).toBe(jsonl);expect(vm.formError).toContain('评测受理结果未知');expect(api.createEvaluation).toHaveBeenCalledOnce();await button(wrapper,'提交评测').trigger('click');await flushPromises();expect(api.createEvaluation.mock.calls[0]).toEqual(api.createEvaluation.mock.calls[1]);expect(vm.job).toMatchObject(jobA);wrapper.unmount()
 })
 it('提交等待实际名称A→B→A，旧成功不关闭新一轮草稿',async()=>{
  const wait=deferred();api.createEvaluation.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await wrapper.find('textarea').setValue(jsonl);await button(wrapper,'提交评测').trigger('click');await flushPromises();await label(wrapper,'草稿B');await label(wrapper,'草稿A');wait.resolve({data:jobA});await flushPromises();expect(vm.open).toBe(true);expect(vm.jobOpen).toBe(false);expect(vm.job).toBeUndefined();expect(vm.formError).toBe('');expect(api.listEvaluations).toHaveBeenCalledTimes(2);wrapper.unmount()
 })
 it('提交等待关闭并重开，原成功不关闭新会话且原任务可从列表回查',async()=>{
  const wait=deferred();api.createEvaluation.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await wrapper.find('textarea').setValue(jsonl);await button(wrapper,'提交评测').trigger('click');await flushPromises();await button(wrapper,'取消').trigger('click');expect(vm.open).toBe(false);await button(wrapper,'发起评测').trigger('click');await flushPromises();wait.resolve({data:jobA});await flushPromises();expect(vm.open).toBe(true);expect(vm.jobOpen).toBe(false);expect(vm.job).toBeUndefined();expect(vm.form.inlineJsonl).toBe(jsonl);expect(api.listEvaluations).toHaveBeenCalledTimes(2);wrapper.unmount()
 })
 it('提交等待卸载，旧成功不写任务或追加评测列表请求',async()=>{
  const wait=deferred();api.createEvaluation.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await wrapper.find('textarea').setValue(jsonl);await button(wrapper,'提交评测').trigger('click');await flushPromises();wrapper.unmount();wait.resolve({data:jobA});await flushPromises();expect(vm.job).toBeUndefined();expect(vm.jobOpen).toBe(false);expect(api.listEvaluations).toHaveBeenCalledOnce()
 })
})
