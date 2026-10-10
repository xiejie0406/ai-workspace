import {flushPromises,shallowMount} from '@vue/test-utils'
import {beforeEach,describe,expect,it,vi} from 'vitest'
import type {PcModel,PcProvider} from '@/types/paicli'
const mocks=vi.hoisted(()=>({request:vi.fn(),confirm:vi.fn(),permissions:['paicli:connection:manage','paicli:workspace:list']}))
vi.mock('@/store/modules/user',()=>({default:()=>({permissions:mocks.permissions,roles:['ordinary']})}))
vi.mock('@/utils/request',()=>({default:mocks.request}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{}})}))
vi.mock('element-plus',async original=>({...await original<object>(),ElMessageBox:{confirm:mocks.confirm}}))
import Connections from '../connections/index.vue'
import hasPermi from '@/directive/permission/hasPermi'
const Container={template:'<div><slot /><slot name="footer" /></div>'}
const global={directives:{hasPermi},stubs:{ElTabs:Container,ElTabPane:Container,ElButton:{template:'<button><slot /></button>'},ElDialog:Container,ElDrawer:Container,ElSelect:Container,ElOption:true,ElAlert:{props:['title'],template:'<p>{{title}}</p>'},ElTable:true,ElTableColumn:true,ElInput:true,ElForm:Container,ElFormItem:Container,ElTag:Container,ElEmpty:true,ElCheckbox:Container,ElSwitch:true,ElInputNumber:true,ElCollapse:Container,ElCollapseItem:Container,ElDescriptions:Container,ElDescriptionsItem:Container,WorkspaceKnowledgeConfig:true,OperationTask:true,RunnerBinding:true}}
const provider={id:'9007199254740993',version:3,name:'Sub2API',provider:'sub2api',wireApi:'responses',baseUrl:'https://sub2api.example/v1',secretConfigured:true} as PcProvider
const model={id:'9007199254740995',version:5,providerId:provider.id,modelId:'exact-model',contextWindow:128000,supportsImages:false,supportsThinking:false,supportsTools:false,capabilityKnown:false,enabled:true} as PcModel
type Vm={providerForm:Partial<PcProvider>;providerUsesSub2Api:boolean;sub2EndpointPreview:string;directoryContextId:string;providers:PcProvider[];models:PcModel[];operationTaskId:string;error:string;reload:()=>Promise<void>;editProvider:(p?:PcProvider)=>void;saveProviderConfig:()=>Promise<void>;verifyModel:(m:PcModel,capability?:'TEXT'|'IMAGES')=>Promise<void>}
describe('Sub2API独立协议和兼容验证操作',()=>{
  beforeEach(()=>{
    mocks.permissions=['paicli:connection:manage','paicli:workspace:list'];mocks.confirm.mockReset().mockResolvedValue('confirm');mocks.request.mockReset();
    mocks.request.mockImplementation((c:{url:string;method:string})=>Promise.resolve(c.url==='/paicli/v1/personal/context'?{data:{ownerUserId:'42',directoryContext:{id:'42',version:1,label:'个人默认目录',state:'VERIFIED',path:'D:/fixture'},models:[model],runtime:{ready:false,message:'未联机'},canChat:false,canOpenTerminal:false}}:c.url==='/paicli/v1/personal/directories'?{data:[{id:'42',version:1,label:'个人默认目录',state:'VERIFIED',path:'D:/fixture'}]}:c.url==='/paicli/v1/providers'&&c.method==='get'?{data:[{...provider}]}:c.url==='/paicli/v1/models'?{data:[{...model}]}:c.url.endsWith('/verify')?{data:{id:'71',mode:'CONTROL'}}:c.url==='/paicli/v1/policies'||c.url==='/paicli/v1/browser'||c.url==='/paicli/v1/wechat'?{data:undefined}:{data:[]}))
  })
  it('新增Sub2API未选择调用API不能保存，选择后保留完整根路径与协议',async()=>{
    const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;vm.editProvider();Object.assign(vm.providerForm,{name:'sub2api',baseUrl:provider.baseUrl,secretRef:'ai.paicli.user.42.sub2api.text',loraId:'old-hidden-value'});
    await vm.saveProviderConfig();expect(mocks.request.mock.calls.filter(c=>c[0].method==='put')).toHaveLength(0);expect(w.text()).toContain('请选择 Sub2API 调用 API');
    vm.providerForm.wireApi='responses';expect(vm.sub2EndpointPreview).toBe('最终请求路径：https://sub2api.example/v1/responses');await vm.saveProviderConfig();
    const sent=mocks.request.mock.calls.find(c=>c[0].method==='put')![0];expect(sent.data).toMatchObject({wireApi:'responses',baseUrl:provider.baseUrl});expect(sent.data.directoryContextId).toBeUndefined();expect(sent.data.loraId).toBeUndefined();w.unmount();
  })
  it('取消付费确认不发请求；确认绑定完整BIGINT字符串及模型供应商版本',async()=>{
    const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;mocks.confirm.mockRejectedValueOnce('cancel');await vm.verifyModel(model);expect(mocks.request.mock.calls.some(c=>c[0].url.endsWith('/verify'))).toBe(false);
    await vm.verifyModel(model);const sent=mocks.request.mock.calls.find(c=>c[0].url.endsWith('/verify'))![0];expect(sent).toMatchObject({url:`/paicli/v1/providers/${provider.id}/verify`,method:'post',data:{modelId:model.id,modelVersion:5,confirmCost:true},headers:{'If-Match':'3',repeatSubmit:false}});expect(mocks.confirm.mock.calls[1][0]).toContain('最多发送两次');w.unmount();
  })
  it('缺少配置管理权限不能绕过按钮直接触发验证',async()=>{
    mocks.permissions=['paicli:connection:read','paicli:workspace:list'];const w=shallowMount(Connections,{global});await flushPromises();await(w.vm as unknown as Vm).verifyModel(model);expect(mocks.confirm).not.toHaveBeenCalled();expect(mocks.request.mock.calls.some(c=>c[0].method==='post')).toBe(false);w.unmount();
  })
  it('现有供应商保存不携带Sub2API协议或清空其LoRA',async()=>{
    const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;vm.editProvider({...provider,provider:'deepseek',name:'deepseek',loraId:'existing-lora'});await vm.saveProviderConfig();const sent=mocks.request.mock.calls.find(c=>c[0].method==='put')![0];expect(sent.data.wireApi).toBeUndefined();expect(sent.data.loraId).toBe('existing-lora');w.unmount();
  })
  it('确认尚未结束时重复验证只显示一个确认，取消后可再次发起',async()=>{
    const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;let cancel!:(reason:string)=>void;
    mocks.confirm.mockImplementationOnce(()=>new Promise((_,reject)=>{cancel=reject}));const pending=vm.verifyModel(model);await vm.verifyModel(model);
    expect(mocks.confirm).toHaveBeenCalledTimes(1);expect(mocks.request.mock.calls.some(c=>c[0].url.endsWith('/verify'))).toBe(false);
    cancel('cancel');await pending;await vm.verifyModel(model);expect(mocks.confirm).toHaveBeenCalledTimes(2);expect(mocks.request.mock.calls.filter(c=>c[0].url.endsWith('/verify'))).toHaveLength(1);w.unmount();
  })
  it.each(['工作区','供应商版本','模型版本','权限','调用API','密钥可用状态'] as const)('费用确认期间%s变化不发起付费验证',async(change)=>{
    const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;let confirm!:()=>void;
    mocks.confirm.mockImplementationOnce(()=>new Promise<void>(resolve=>{confirm=resolve}));const pending=vm.verifyModel(model);
    if(change==='工作区')vm.directoryContextId='43';else if(change==='供应商版本')vm.providers[0].version++;else if(change==='模型版本')vm.models[0].version++;else if(change==='调用API')vm.providers[0].wireApi='chat_completions';else if(change==='密钥可用状态')vm.providers[0].secretConfigured=false;else mocks.permissions=['paicli:connection:read','paicli:workspace:list'];
    await flushPromises();confirm();await pending;expect(mocks.request.mock.calls.some(c=>c[0].url.endsWith('/verify'))).toBe(false);expect(vm.error).toContain('已变化');w.unmount();
  })
  it('旧工作区的迟到验证回执不进入新工作区任务面板',async()=>{
    const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;let complete!:(value:unknown)=>void;const existing=mocks.request.getMockImplementation()!;
    mocks.request.mockImplementation(c=>c.url.endsWith('/verify')?new Promise(resolve=>{complete=resolve}):existing(c));const pending=vm.verifyModel(model);await flushPromises();vm.directoryContextId='43';await flushPromises();complete({data:{id:'old-workspace-task',mode:'CONTROL'}});await pending;
    expect(vm.operationTaskId).toBe('');expect(vm.directoryContextId).toBe('43');w.unmount();
  })
  it('版本冲突保留服务端反馈和原配置，失败后可以重新确认',async()=>{
    const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;const existing=mocks.request.getMockImplementation()!;let failed=false;
    mocks.request.mockImplementation(c=>{if(c.url.endsWith('/verify')&&!failed){failed=true;return Promise.reject({response:{data:{msg:'供应商版本已变化，请刷新'}}})}return existing(c)});
    await vm.verifyModel(model);expect(vm.error).toContain('供应商版本已变化');expect(vm.providers[0].version).toBe(3);expect(vm.models[0].capabilityKnown).toBe(false);expect(vm.operationTaskId).toBe('');
    await vm.verifyModel(model);expect(vm.operationTaskId).toBe('71');expect(mocks.confirm).toHaveBeenCalledTimes(2);w.unmount();
  })
  it('图片单独验证只接受已核验文字的Responses模型，任务请求区分图片且不改文字状态',async()=>{
    const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;
    await vm.verifyModel(model,'IMAGES');expect(mocks.confirm).not.toHaveBeenCalled();expect(vm.error).toContain('已通过文字');
    vm.models[0].capabilityKnown=true;vm.models[0].supportsTools=true;await vm.verifyModel(vm.models[0],'IMAGES');
    const sent=mocks.request.mock.calls.find(c=>c[0].url.endsWith('/verify'))![0];expect(sent.data).toMatchObject({modelId:model.id,modelVersion:5,capability:'IMAGES',confirmCost:true});
    expect(mocks.confirm.mock.calls[0][0]).toContain('图片观察及工具回灌');expect(vm.operationTaskId).toBe('71');w.unmount();
  })
  it('ChatCompletions不能从程序入口发起图片验证',async()=>{
    const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;vm.providers[0].wireApi='chat_completions';vm.models[0].capabilityKnown=true;
    await vm.verifyModel(vm.models[0],'IMAGES');expect(mocks.confirm).not.toHaveBeenCalled();expect(mocks.request.mock.calls.some(c=>c[0].url.endsWith('/verify'))).toBe(false);w.unmount();
  })
})
