import {flushPromises,shallowMount} from '@vue/test-utils'
import {beforeEach,describe,expect,it,vi} from 'vitest'
import type {PcModel} from '@/types/paicli'
const mocks=vi.hoisted(()=>({request:vi.fn(),permissions:['paicli:connection:manage','paicli:session:read','paicli:session:write'],roles:['ordinary'],confirm:vi.fn()}))
vi.mock('@/store/modules/user',()=>({default:()=>({permissions:mocks.permissions,roles:mocks.roles})}))
vi.mock('@/utils/request',()=>({default:mocks.request}))
vi.mock('element-plus',async original=>({...await original<object>(),ElMessageBox:{confirm:mocks.confirm}}))
import Connections from '../connections/index.vue'
import PersonalDirectoryPicker from '../components/PersonalDirectoryPicker.vue'
import {personalKnowledgePayload} from '../shared/personalSettings'
import {executionEvidence} from '../shared/executionEvidence'
import hasPermi from '@/directive/permission/hasPermi'
const Container={template:'<div><slot /><slot name="footer" /></div>'},Button={props:['disabled','loading'],template:'<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>'}
const global={directives:{hasPermi},stubs:{ElButton:Button,ElTabs:Container,ElTabPane:Container,ElSelect:Container,ElOption:true,ElAlert:true,ElForm:Container,ElFormItem:Container,ElDialog:Container,ElDrawer:Container,ElTable:true,ElTableColumn:true,ElInput:true,ElCheckbox:true,ElTag:true,ElCollapse:Container,ElCollapseItem:Container,ElEmpty:true,ElRadioGroup:Container,ElRadio:true,ElSwitch:true,ElInputNumber:true,ElDescriptions:true,ElDescriptionsItem:true,OperationTask:true,PersonalKnowledgeConfig:true,WechatPolicy:true,ArtifactEvidence:true}}
const own:PcModel={id:'8',providerId:'7',modelId:'个人模型',version:2,contextWindow:128000,supportsImages:false,supportsThinking:false,supportsTools:true,enabled:true,capabilityKnown:true,releaseId:'19',releaseVersion:3,releaseCasVersion:6,releaseStatus:'PUBLISHED'}
const platform:PcModel={...own,id:'9',modelId:'内置模型',platformModel:true}
const context={ownerUserId:'42',bootstrapped:true,directoryContext:null,directoryState:'NOT_PREPARED',modelId:'9',models:[own,platform],runtime:{ready:false,code:'RUNTIME_UNAVAILABLE',message:'宿主未准备'},settings:{version:5},canChat:false,canOpenTerminal:false}
type Vm={defaultModelId:string;advanced:boolean;policy:Record<string,unknown>;savePersonalPolicy:()=>Promise<void>;publishOpen:boolean;shareConfirmed:boolean;publishAudience:'AUTHENTICATED'|'USERS';publishUsers:string;publishDefault:boolean;modelOpen:boolean;modelForm:Partial<PcModel>;editModel:(model:PcModel)=>void;saveModel:()=>Promise<void>;saveDefaultModel:()=>Promise<void>;openPublish:(model:PcModel)=>void;publishRelease:()=>Promise<void>;revokeRelease:(model:PcModel)=>Promise<void>}
describe('个人设置、内置模型与目录核对',()=>{
 beforeEach(()=>{mocks.roles=['ordinary'];mocks.confirm.mockReset().mockResolvedValue('confirm');mocks.request.mockReset();mocks.request.mockImplementation((c:{url:string;method:string})=>Promise.resolve(c.url.startsWith('/paicli/v1/personal/')&&c.url!=='/paicli/v1/personal/directories'?{data:structuredClone(context)}:c.url==='/paicli/v1/policies'?{data:{version:4,hardDeniedPaths:[],allowedCapabilities:[]}}:c.url==='/paicli/v1/models'&&c.method==='get'?{data:[own,platform]}:c.url==='/paicli/v1/providers'?{data:[]}:c.url.endsWith('/publish')?{data:{id:'19',version:4}}:c.url==='/paicli/v1/personal/directories'&&c.method==='post'?{data:{id:'61',status:'ENQUEUED'}}:{data:[]}))})
 it('高级目录能力解释真实不可用原因，区分未读取与MCP已有列表且不发起写入',async()=>{
  const Alert={props:['title','description'],template:'<aside>{{title}} {{description}}</aside>'}
  const w=shallowMount(Connections,{global:{...global,stubs:{...global.stubs,ElAlert:Alert}}});await flushPromises()
  expect(w.text()).not.toContain('当前个人执行目录尚不可用')
  const vm=w.vm as unknown as Vm;vm.advanced=true;await flushPromises()
  const reasons=w.findAllComponents(Alert).filter(item=>item.props('title')==='当前个人执行目录尚不可用')
  expect(reasons).toHaveLength(4)
  for(const item of reasons){expect(item.text()).toContain('宿主未准备');expect(item.text()).toContain('准备完成后刷新页面')}
  expect(reasons.map(item=>item.text()).join('\n')).toContain('现有服务列表仍可查看')
  for(const label of ['浏览器','Skills','微信'])expect(w.text()).toContain(`当前目录的${label}配置尚未读取`)
  expect(mocks.request.mock.calls.some(([c])=>/^\/paicli\/v1\/(browser|skills|wechat)$/.test(c.url))).toBe(false)
  expect(mocks.request.mock.calls.some(([c])=>c.method&&c.method!=='get')).toBe(false)
  const reload=w.findAll('button').find(item=>item.text()==='重载 Skills')!;expect(reload.attributes('disabled')).toBeDefined()
  vm.advanced=false;await flushPromises();expect(w.text()).not.toContain('当前个人执行目录尚不可用');w.unmount()
 })
 it('上下文读取失败沿用错误，不把未取得数据断言为个人目录不可用',async()=>{
  const originalRequest=mocks.request.getMockImplementation()!
  mocks.request.mockImplementation(c=>c.url==='/paicli/v1/personal/context'?Promise.reject(new Error('上下文读取失败')):originalRequest(c))
  const Alert={props:['title','description'],template:'<aside>{{title}} {{description}}</aside>'}
  const w=shallowMount(Connections,{global:{...global,stubs:{...global.stubs,ElAlert:Alert}}});await flushPromises();(w.vm as unknown as Vm).advanced=true;await flushPromises()
  expect(w.text()).toContain('上下文读取失败');expect(w.text()).not.toContain('当前个人执行目录尚不可用');w.unmount()
 })
 it('无目录也能设置默认模型，CAS绑定个人设置版本且不携带目录/旧成员字段',async()=>{const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;vm.defaultModelId='8';await vm.saveDefaultModel();const sent=mocks.request.mock.calls.find(c=>c[0].url==='/paicli/v1/personal/settings')![0];expect(sent.data).toEqual({modelId:'8'});expect(sent.headers['If-Match']).toBe('5');expect(sent.data.directoryContextId).toBeUndefined();w.unmount()})
 it('个人预算无需默认目录，读取和保存只绑定本人策略版本',async()=>{const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;vm.advanced=true;await flushPromises();const read=mocks.request.mock.calls.find(c=>c[0].url==='/paicli/v1/policies')![0];expect(read.params.directoryContextId).toBeUndefined();vm.policy={version:4,hardDeniedPaths:[],allowedCapabilities:[]};await vm.savePersonalPolicy();const sent=mocks.request.mock.calls.find(c=>c[0].url==='/paicli/v1/policies'&&c[0].method==='put')![0];expect(sent.data.directoryContextId).toBeUndefined();expect(sent.headers['If-Match']).toBe('4');w.unmount()})
 it('内置共享模型不能从个人模型配置程序入口修改或发布',async()=>{const w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;vm.editModel(platform);expect(vm.modelOpen).toBe(false);vm.modelForm.id='9';await vm.saveModel();vm.openPublish(platform);await vm.publishRelease();expect(mocks.request.mock.calls.some(c=>c[0].url==='/paicli/v1/models'&&c[0].method==='post'||c[0].url.endsWith('/publish'))).toBe(false);w.unmount()})
 it('管理员发布需要明确受众和确认，撤销带真实release版本；普通角色不能发布',async()=>{let w=shallowMount(Connections,{global});await flushPromises();(w.vm as unknown as Vm).openPublish(own);expect((w.vm as unknown as Vm).publishOpen).toBe(false);w.unmount();mocks.roles=['admin'];w=shallowMount(Connections,{global});await flushPromises();const vm=w.vm as unknown as Vm;vm.openPublish(own);await vm.publishRelease();expect(mocks.request.mock.calls.some(c=>c[0].url.endsWith('/publish'))).toBe(false);vm.publishAudience='USERS';vm.publishUsers='9007199254740993,42';vm.publishDefault=true;vm.shareConfirmed=true;await vm.publishRelease();const sent=mocks.request.mock.calls.find(c=>c[0].url.endsWith('/publish'))![0];expect(sent.data).toMatchObject({modelId:'8',modelVersion:2,userIds:['9007199254740993','42'],audience:'USERS',confirmSharing:true,defaultModel:true,releaseVersion:6});await vm.revokeRelease(own);expect(mocks.request.mock.calls.find(c=>c[0].url.endsWith('/19/revoke'))![0].headers['If-Match']).toBe('6');w.unmount()})
 it('目录只是验证请求，未取完成回执不加入选择列表，活跃执行不能改目录',async()=>{const w=shallowMount(PersonalDirectoryPicker,{props:{modelValue:true,currentId:'',directories:[]},global});const vm=w.vm as unknown as{path:string;verify:()=>Promise<void>;taskId:string;directorySettled:(task:unknown)=>void};vm.path='D:/users/42/files/project';await vm.verify();expect(mocks.request.mock.calls.find(c=>c[0].url.endsWith('/directories'))![0].data).toEqual({path:'D:/users/42/files/project'});expect(vm.taskId).toBe('61');expect(w.emitted('refresh')).toBeUndefined();vm.directorySettled({status:'FAILED'});expect(w.emitted('refresh')).toBeUndefined();vm.directorySettled({status:'COMPLETED'});expect(w.emitted('refresh')).toHaveLength(1);await w.setProps({active:true});await vm.verify();expect(mocks.request.mock.calls.filter(c=>c[0].url.endsWith('/directories'))).toHaveLength(1);w.unmount()})
 it('搜索/向量配置使用本人密钥用途，保留明确停用；跨用户引用拒绝',()=>{expect(personalKnowledgePayload('42')).toEqual({searchConfig:null,embeddingConfig:null});expect(personalKnowledgePayload('42',{provider:'zhipu',secretRef:'ai.paicli.user.42.search'}).searchConfig?.secretRef).toBe('ai.paicli.user.42.search');expect(()=>personalKnowledgePayload('42',undefined,{provider:'openai',model:'embed',baseUrl:'https://example',secretRef:'ai.paicli.user.43.embed'})).toThrow('当前用户')})
 it('完整差异产物按真实摘要供分页读取，二进制取证不足保留原因',()=>{const diffArtifact={artifactId:'9007199254740993',sha256:'a'.repeat(64),size:400000};const value=executionEvidence([{id:'1',parentId:'61',toolName:'edit_file',arguments:{},status:'COMPLETED',result:{path:'large.ts',diffArtifact}},{id:'2',parentId:'61',toolName:'write_file',arguments:{},status:'COMPLETED',result:{path:'image.png',diffUnavailable:'二进制文件无法生成文本差异'}}]);expect(value.diffs[0].diffArtifact).toEqual(diffArtifact);expect(value.diffs[0].diff).toBeUndefined();expect(value.diffs[1].unavailable).toContain('二进制')})
})
