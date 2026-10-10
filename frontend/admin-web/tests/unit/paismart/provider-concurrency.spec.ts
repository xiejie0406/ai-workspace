import {beforeEach,describe,expect,it,vi} from 'vitest'
import {flushPromises,mount} from '@vue/test-utils'
import {ElForm,ElInput} from 'element-plus'
import Providers from '@/views/paismart/operations/providers/index.vue'

const api=vi.hoisted(()=>({listProviders:vi.fn(),getProvider:vi.fn(),saveProvider:vi.fn(),testProvider:vi.fn(),newKey:vi.fn()}))
vi.mock('@/api/paismart/operations',()=>api)
vi.mock('@/api/paismart/client',()=>({newKey:api.newKey}))
const slots={template:'<div><slot/><slot name="footer"/></div>'}
const global={stubs:{...Object.fromEntries(['el-dialog','el-form','el-form-item','el-descriptions','el-descriptions-item','el-select','el-option'].map(name=>[name,slots])),
 'el-table':true,'el-table-column':true,'el-tag':true,'router-link':true,'el-alert':{props:['title'],template:'<p>{{title}}</p>'},'el-checkbox':true,'el-input':true,'el-input-number':true,'el-switch':true,
 'el-button':{props:['disabled','loading'],emits:['click'],template:'<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>'}},directives:{hasPermi:()=>{},loading:()=>{}}}
const sample=(id='A',scope='EMBEDDING',rowVersion=2)=>({id,scope,provider:scope==='LLM'?'SUB2API':'ALIYUN',model:scope==='LLM'?'gpt-6.1-sol':'text-embedding-v4',address:'https://provider.example.test/v1',dimension:scope==='EMBEDDING'?1024:undefined,enabled:true,active:false,rowVersion,config:scope==='LLM'?{providerCode:'sub2api',apiProtocol:'responses',maxOutputTokens:4096,reasoningEffort:'low'}:{providerCode:'aliyun'}})
const compatibility={state:'PASSED',checks:{text:true,stream:true,tool:true},usageSource:'SUPPLIER',providerVersion:2,checkedAt:'2026-10-05T00:00:00Z'}
function deferred<T>(){let resolve!:(value:T)=>void,reject!:(error:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return{promise,resolve,reject}}
async function setup(){const wrapper=mount(Providers,{global});await flushPromises();const vm=wrapper.vm as any;vm.edit(sample());vm.form.secretRef='ai.paismart.fixture.original';return{wrapper,vm}}
function editB(vm:any,id='B'){vm.edit(sample(id,'LLM'));vm.form.secretRef='ai.paismart.fixture.new-input'}
function expectB(vm:any,id='B'){expect(vm.form.id).toBe(id);expect(vm.form.scope).toBe('LLM');expect(vm.form.secretRef).toBe('ai.paismart.fixture.new-input');expect(vm.open).toBe(true);expect(vm.formError).toBe('')}
beforeEach(()=>{vi.resetAllMocks();api.newKey.mockReturnValue('provider-same-request-key');api.listProviders.mockResolvedValue({rows:[sample(),sample('B','LLM')]});api.getProvider.mockResolvedValue({data:sample()});api.saveProvider.mockResolvedValue({data:{id:'A',scope:'EMBEDDING',rowVersion:3}});api.testProvider.mockResolvedValue({data:{state:'PASSED',latencyMs:1,compatibility}})})

describe('供应商对象、版本与异步完成隔离',()=>{
 it('A保存迟到成功不关闭B或清除B新输入，但刷新实际列表',async()=>{
  const wait=deferred<any>();api.saveProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.save();editB(vm);wait.resolve({data:{id:'A',rowVersion:3}});await pending;await flushPromises();expectB(vm);expect(api.listProviders).toHaveBeenCalledTimes(2);wrapper.unmount()
 })
 it('A保存迟到失败不把错误写入B',async()=>{
  const wait=deferred<any>();api.saveProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.save();editB(vm);wait.reject(new Error('A版本冲突'));await pending;await flushPromises();expectB(vm);wrapper.unmount()
 })
 it('A→B→A新一轮编辑不会被旧A成功关闭',async()=>{
  const wait=deferred<any>();api.saveProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.save();editB(vm);vm.edit(sample());vm.form.secretRef='ai.paismart.fixture.new-input';wait.resolve({data:{id:'A',rowVersion:3}});await pending;expect(vm.form.id).toBe('A');expect(vm.form.scope).toBe('EMBEDDING');expect(vm.open).toBe(true);expect(vm.form.secretRef).toBe('ai.paismart.fixture.new-input');expect(vm.formError).toBe('');wrapper.unmount()
 })
 it('同ID行版本变化后旧成功不关闭新版输入',async()=>{
  const wait=deferred<any>();api.saveProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.save();vm.form.rowVersion=3;vm.form.secretRef='ai.paismart.fixture.updated';wait.resolve({data:{id:'A',rowVersion:3}});await pending;expect(vm.open).toBe(true);expect(vm.form.secretRef).toBe('ai.paismart.fixture.updated');expect(vm.form.rowVersion).toBe(3);wrapper.unmount()
 })
 it('关闭旧表单后保存成功不清除已关闭表单输入',async()=>{
  const wait=deferred<any>();api.saveProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.save();vm.open=false;wait.resolve({data:{id:'A',rowVersion:3}});await pending;expect(vm.open).toBe(false);expect(vm.form.secretRef).toBe('ai.paismart.fixture.original');wrapper.unmount()
 })
 it('当前保存按钮重复点击只提交一次、关闭当前表单并刷新列表',async()=>{
  const wait=deferred<any>();api.saveProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const button=wrapper.findAll('button').find(item=>item.text()==='保存配置')!;await button.trigger('click');await button.trigger('click');expect(api.saveProvider).toHaveBeenCalledTimes(1);expect(api.saveProvider.mock.calls[0][0]).toMatchObject({id:'A',scope:'EMBEDDING',rowVersion:2,secretRef:'ai.paismart.fixture.original'});wait.resolve({data:{id:'A',rowVersion:3}});await flushPromises();expect(vm.open).toBe(false);expect(vm.form.secretRef).toBe('');expect(vm.saving).toBe(false);expect(api.listProviders).toHaveBeenCalledTimes(2);expect(api.testProvider).not.toHaveBeenCalled();wrapper.unmount()
 })
 it('当前保存失败保留输入并显示版本冲突恢复说明',async()=>{
  api.saveProvider.mockRejectedValue(new Error('VERSION_CONFLICT'));const{wrapper,vm}=await setup();await vm.save();expect(vm.open).toBe(true);expect(vm.form.secretRef).toBe('ai.paismart.fixture.original');expect(vm.formError).toContain('VERSION_CONFLICT');expect(vm.formError).toContain('系统不会自动覆盖');expect(vm.saving).toBe(false);wrapper.unmount()
 })
 it('卸载后旧保存成功不启动额外配置列表请求',async()=>{
  const wait=deferred<any>();api.saveProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.save();wrapper.unmount();wait.resolve({data:{id:'A',rowVersion:3}});await pending;expect(api.listProviders).toHaveBeenCalledTimes(1)
 })
 it('先文字后向量回读，迟到文字不能覆盖向量对象',async()=>{
  const first=deferred<any>(),second=deferred<any>();api.getProvider.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);const{wrapper,vm}=await setup();const a=vm.editScope('LLM'),b=vm.editScope('EMBEDDING');second.resolve({data:sample('E','EMBEDDING')});await b;first.resolve({data:sample('L','LLM')});await a;expect(vm.form.id).toBe('E');expect(vm.form.scope).toBe('EMBEDDING');expect(vm.open).toBe(true);wrapper.unmount()
 })
 it('用途回读期间新建候选，旧响应不能覆盖新候选输入',async()=>{
  const wait=deferred<any>();api.getProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.editScope('LLM');vm.create();vm.form.model='新候选未保存';wait.resolve({data:sample('L','LLM')});await pending;expect(vm.form.id).toBeUndefined();expect(vm.form.model).toBe('新候选未保存');expect(vm.open).toBe(true);wrapper.unmount()
 })
 it('用途回读迟到失败不会污染用户随后编辑的B错误',async()=>{
  const wait=deferred<any>();api.getProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.editScope('LLM');editB(vm);wait.reject(new Error('旧用途读取失败'));await pending;expectB(vm);expect(vm.error).toBe('');wrapper.unmount()
 })
 it('卸载后用途回读不再打开表单',async()=>{
  const wait=deferred<any>();api.getProvider.mockReturnValue(wait.promise);const wrapper=mount(Providers,{global});await flushPromises();const vm=wrapper.vm as any;const pending=vm.editScope('LLM');wrapper.unmount();wait.resolve({data:sample('L','LLM')});await pending;expect(vm.open).toBe(false);expect(vm.form.id).toBeUndefined()
 })
 it('卸载后普通连接测试不写入已离开的结果',async()=>{
  const wait=deferred<any>();api.testProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.test(sample());wrapper.unmount();wait.resolve({data:{state:'READY',latencyMs:1}});await pending;expect(vm.testResult).toBe('');expect(vm.error).toBe('')
 })
 it('卸载后兼容验证不展示迟到结果或追加刷新',async()=>{
  const wait=deferred<any>();api.testProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await vm.test(sample('L','LLM'));const pending=vm.confirmVerification();wrapper.unmount();wait.resolve({data:{state:'PASSED',latencyMs:1,compatibility}});await pending;expect(api.listProviders).toHaveBeenCalledTimes(1);expect(vm.compatibilityResult).toBeUndefined();expect(vm.testResult).toBe('')
 })
 it('当前兼容验证重复确认只发一次、失败重试沿用同一请求键',async()=>{
  const wait=deferred<any>();api.testProvider.mockReturnValueOnce(wait.promise).mockResolvedValueOnce({data:{state:'PASSED',latencyMs:1,compatibility}});const{wrapper,vm}=await setup();await vm.test(sample('L','LLM'));const first=vm.confirmVerification(),second=vm.confirmVerification();expect(api.testProvider).toHaveBeenCalledTimes(1);wait.reject(new Error('请求结果未知'));await Promise.all([first,second]);expect(vm.verificationOpen).toBe(true);expect(vm.verificationError).toContain('同一次操作');await vm.confirmVerification();expect(api.testProvider).toHaveBeenCalledTimes(2);expect(api.testProvider.mock.calls[0]).toEqual(api.testProvider.mock.calls[1]);expect(api.testProvider.mock.calls[1][1]).toEqual({id:'L',mode:'compatibility',expectedVersion:2});expect(vm.compatibilityResult.state).toBe('PASSED');expect(vm.verificationOpen).toBe(false);wrapper.unmount()
 })
 it('验证中同ID确认行原位版本变化，旧结果不代表新版验证通过',async()=>{
  const wait=deferred<any>();api.testProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await vm.test(sample('L','LLM'));const pending=vm.confirmVerification();vm.verificationRow.rowVersion=3;wait.resolve({data:{state:'PASSED',latencyMs:1,compatibility}});await pending;expect(vm.compatibilityResult).toBeUndefined();expect(vm.testResult).toBe('');expect(vm.verificationOpen).toBe(true);expect(api.testProvider.mock.calls[0][1].expectedVersion).toBe(2);wrapper.unmount()
 })
 it('已关闭的验证确认不发送新的收费请求',async()=>{
  const{wrapper,vm}=await setup();await vm.test(sample('L','LLM'));vm.verificationOpen=false;await vm.confirmVerification();expect(api.testProvider).not.toHaveBeenCalled();wrapper.unmount()
 })
 it('验证窗口关闭重开后旧结果不能关闭新确认窗口',async()=>{
  const wait=deferred<any>();api.testProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await vm.test(sample('L','LLM'));const pending=vm.confirmVerification();vm.verificationOpen=false;vm.verificationOpen=true;wait.resolve({data:{state:'PASSED',latencyMs:1,compatibility}});await pending;expect(vm.compatibilityResult).toBeUndefined();expect(vm.testResult).toBe('');expect(vm.verificationOpen).toBe(true);wrapper.unmount()
 })
 it('卸载后兼容验证失败不发新刷新也不污染旧错误',async()=>{
  const wait=deferred<any>();api.testProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();await vm.test(sample('L','LLM'));const pending=vm.confirmVerification();wrapper.unmount();wait.reject(new Error('离开后的未知请求'));await pending;expect(api.listProviders).toHaveBeenCalledTimes(1);expect(vm.verificationError).toBe('')
 })
 it('页面卸载后最初列表迟到响应不更新已销毁的配置',async()=>{
  const wait=deferred<any>();api.listProviders.mockReturnValue(wait.promise);const wrapper=mount(Providers,{global});const vm=wrapper.vm as any;wrapper.unmount();wait.resolve({rows:[sample()]});await flushPromises();expect(vm.providers).toEqual([]);expect(vm.error).toBe('');expect(api.listProviders).toHaveBeenCalledTimes(1)
 })
 it('用途读取等待时真实表单可继续输入，迟到结果不覆盖用户草稿',async()=>{
  const wait=deferred<any>();api.getProvider.mockReturnValue(wait.promise)
  const wrapper=mount(Providers,{global:{...global,components:{ElForm,ElInput},stubs:{...global.stubs,'el-form':false,'el-input':false}}});await flushPromises();const vm=wrapper.vm as any;vm.edit(sample('L','LLM'));await flushPromises();const pending=vm.editScope('EMBEDDING')
  const model=wrapper.find('input[placeholder="精确填写实例支持的模型 ID"]'),ref=wrapper.find('input[placeholder="填写 ai.paismart.* 托管引用"]')
  expect((model.element as HTMLInputElement).disabled).toBe(false);await model.setValue('用户正在编辑的文字模型');await ref.setValue('ai.paismart.fixture.user-draft')
  wait.resolve({data:sample('E','EMBEDDING')});await pending;expect(vm.form.id).toBe('L');expect(vm.form.model).toBe('用户正在编辑的文字模型');expect(vm.form.secretRef).toBe('ai.paismart.fixture.user-draft');expect(vm.open).toBe(true);wrapper.unmount()
 })
 it('保存期间同ID同版本草稿的程序变化也保留，不能解释为用户可在禁用表单输入',async()=>{
  const wait=deferred<any>();api.saveProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.save();vm.form.secretRef='ai.paismart.fixture.defensive-draft';vm.form.model='程序更新的待保存模型';vm.form.config.extra='防御状态'
  wait.resolve({data:{id:'A',rowVersion:3}});await pending;expect(vm.form.secretRef).toBe('ai.paismart.fixture.defensive-draft');expect(vm.form.model).toBe('程序更新的待保存模型');expect(vm.form.config.extra).toBe('防御状态');expect(vm.open).toBe(true);expect(api.saveProvider.mock.calls[0][0]).toMatchObject({id:'A',model:'text-embedding-v4',secretRef:'ai.paismart.fixture.original'});wrapper.unmount()
 })
 it('用途读取期间平台供应商编码变化也保护当前草稿',async()=>{
  const wait=deferred<any>();api.getProvider.mockReturnValue(wait.promise);const{wrapper,vm}=await setup();const pending=vm.editScope('LLM');vm.providerCode='新输入的编码';wait.resolve({data:sample('L','LLM')});await pending;expect(vm.form.id).toBe('A');expect(vm.providerCode).toBe('新输入的编码');expect(vm.open).toBe(true);wrapper.unmount()
 })
})
