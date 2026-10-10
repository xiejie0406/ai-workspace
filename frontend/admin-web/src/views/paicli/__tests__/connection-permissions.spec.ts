import { flushPromises,shallowMount } from '@vue/test-utils'
import { beforeEach,describe,expect,it,vi } from 'vitest'
const mocks=vi.hoisted(()=>({request:vi.fn(),permissions:['paicli:workspace:list','paicli:connection:read']}))
vi.mock('@/store/modules/user',()=>({default:()=>({permissions:mocks.permissions,roles:['ordinary']})}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{}})}))
vi.mock('@/utils/request',()=>({default:mocks.request}))
import hasPermi from '@/directive/permission/hasPermi'
import Connections from '../connections/index.vue'
const Container={template:'<div><slot /><slot name="footer" /></div>'}
const global={directives:{hasPermi},stubs:{ElTabs:Container,ElTabPane:Container,ElButton:{template:'<button><slot /></button>'},ElDialog:Container,ElDrawer:Container,ElSelect:Container,ElOption:true,ElAlert:true,ElTableColumn:true,ElInput:true,ElForm:Container,ElFormItem:Container,ElTag:Container,ElEmpty:true,ElCheckbox:Container,ElSwitch:true,ElInputNumber:true,ElCollapse:Container,ElCollapseItem:Container,ElDescriptions:Container,ElDescriptionsItem:Container,ElTable:true,WorkspaceKnowledgeConfig:true,OperationTask:true,RunnerBinding:true}}
describe('连接页普通角色分开读取范围',()=>{
  beforeEach(()=>{mocks.permissions=['paicli:workspace:list','paicli:connection:read'];mocks.request.mockReset();mocks.request.mockImplementation((config:{url:string})=>Promise.resolve(config.url==='/paicli/v1/personal/context'?{data:{ownerUserId:'42',directoryContext:{id:'42',label:'个人默认目录',path:'D:/fixture',state:'VERIFIED',version:1},models:[],runtime:{ready:false,message:'未联机'},canChat:false,canOpenTerminal:false}}:config.url==='/paicli/v1/personal/directories'?{data:[{id:'42',label:'个人默认目录',path:'D:/fixture',state:'VERIFIED',version:1}]}:config.url==='/paicli/v1/browser'?{data:{directoryContextId:'42',version:0,mode:'DISCONNECTED',connected:false}}:config.url==='/paicli/v1/wechat'?{data:null}:config.url==='/paicli/v1/policies'?{data:undefined}:{data:[]}))})
  it('connection:read不隐含workspace:manage或runner:list，不发送这些请求',async()=>{const wrapper=shallowMount(Connections,{global});await flushPromises();const urls=mocks.request.mock.calls.map(call=>call[0].url);expect(urls).toContain('/paicli/v1/providers');expect(urls).not.toContain('/paicli/v1/runners');expect(urls).not.toContain('/paicli/v1/workspaces/42/members');expect(wrapper.text()).not.toContain('工作区成员与数据边界');expect(wrapper.findAll('button').map(button=>button.text())).not.toContain('登记供应商');wrapper.unmount()})
  it('个人设置即使有旧管理权限也不再查询设备与成员',async()=>{mocks.permissions.push('paicli:runner:list','paicli:workspace:manage');const wrapper=shallowMount(Connections,{global});await flushPromises();const urls=mocks.request.mock.calls.map(call=>call[0].url);expect(urls).not.toContain('/paicli/v1/runners');expect(urls).not.toContain('/paicli/v1/workspaces/42/members');expect(urls).toContain('/paicli/v1/personal/context');expect(wrapper.text()).not.toContain('工作区成员与数据边界');wrapper.unmount()})
})
