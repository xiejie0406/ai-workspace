import { beforeEach,describe,expect,it,vi } from 'vitest'
import { modelTemplateFields } from '../shared/modelProfile'
import { workspaceKnowledgePayload } from '../shared/workspaceConfig'
import { imageArtifactIds } from '../shared/artifactReferences'
import type { PcModel,PcWorkspace } from '@/types/paicli'
const mocks=vi.hoisted(()=>({request:vi.fn()}))
vi.mock('@/utils/request',()=>({default:mocks.request}))
import { addModel,browserAction,requestMcpContent } from '@/api/paicli/connections'
import { saveWorkspace } from '@/api/paicli/workspaces'
import { savePromptTemplate } from '@/api/paicli/prompts'
const workspace:PcWorkspace={id:'42',name:'原工作区',rootPath:'D:/source',rootFingerprint:'fixed',commandSandbox:'required',status:'ACTIVE',version:7,defaultProviderId:'5'}
describe('完整迁移配置与真实中心DTO',()=>{
  beforeEach(()=>{mocks.request.mockReset();mocks.request.mockResolvedValue({code:200,data:{}})})
  it('MCP资源与prompt读取提交版本和完整参数，路径引用不成为认证URL',async()=>{await requestMcpContent('9/3',4,{kind:'prompt',reference:'review',arguments:{path:'src/main.java'}},'stable');expect(mocks.request.mock.calls[0]![0]).toMatchObject({url:'/paicli/v1/mcp/9%2F3/contents',method:'post',data:{kind:'prompt',reference:'review',arguments:{path:'src/main.java'}},headers:{'If-Match':'4','Idempotency-Key':'stable',repeatSubmit:false}})})
  it('浏览器四动作均为真实CONTROL接口，保留CAS与幂等',async()=>{for(const action of ['PROBE','CONNECT','TABS','DISCONNECT'] as const)await browserAction('12',8,action,`key-${action}`);expect(mocks.request.mock.calls.map(call=>call[0].data.action)).toEqual(['PROBE','CONNECT','TABS','DISCONNECT']);for(const call of mocks.request.mock.calls)expect(call[0].headers['If-Match']).toBe('8')})
  it('模型编辑提交中央numeric ID及模板全部推理字段，不生成本地profile',async()=>{const model={id:'5',version:9,providerId:'2',modelId:'ds-full',contextWindow:128000,maxOutputTokens:8192,supportsImages:false,supportsThinking:true,supportsTools:true,capabilityKnown:true,enabled:true,thinking:'enabled',reasoningEffort:'high',reasoningHistory:true,dsml:true} satisfies PcModel;expect(modelTemplateFields(model)).toMatchObject({template:'ds-full',maxOutputTokens:8192,thinking:'enabled',reasoningEffort:'high',reasoningHistory:true,dsml:true});await addModel({...model,workspaceId:'42'},'edit');expect(mocks.request.mock.calls[0]![0].headers['If-Match']).toBe('9');expect(mocks.request.mock.calls[0]![0].data.id).toBe('5')})
  it('未知模型模板保留128k文本默认，不伪造图片或工具能力',()=>{expect(modelTemplateFields()).toMatchObject({contextWindow:128000,supportsImages:false,supportsTools:false,capabilityKnown:false})})
  it('工作区检索配置保留原版本、根路径、默认供应商及命令隔离',async()=>{const data=workspaceKnowledgePayload(workspace,{provider:'searxng',baseUrl:'https://search.example'},{provider:'ollama',model:'embed-full',baseUrl:'http://localhost:11434'});expect(data).toMatchObject({...workspace,searchConfig:{provider:'searxng'},embeddingConfig:{model:'embed-full'}});await saveWorkspace(data,'save');expect(mocks.request.mock.calls[0]![0].headers).toMatchObject({'If-Match':'7','Idempotency-Key':'save',repeatSubmit:false})})
  it('跨工作区密钥引用与缺失embedding模型拒绝，停用明确清配置',()=>{expect(()=>workspaceKnowledgePayload(workspace,{provider:'zhipu',secretRef:'ai.paicli.99.search'})).toThrow('当前工作区');expect(()=>workspaceKnowledgePayload(workspace,undefined,{provider:'ollama',model:'',baseUrl:'http://localhost'})).toThrow('不能为空');expect(workspaceKnowledgePayload(workspace)).toMatchObject({searchConfig:null,embeddingConfig:null})})
  it('提示词保存版本及个人/项目scope通过唯一中心接口',async()=>{await savePromptTemplate({id:'9',version:3,scope:'PROJECT',workspaceId:'42',relativePath:'prompts/base.md',content:'用户规则',enabled:true},'prompt');expect(mocks.request.mock.calls[0]![0]).toMatchObject({url:'/paicli/v1/prompt-templates',headers:{'If-Match':'3','Idempotency-Key':'prompt',repeatSubmit:false}})})
  it('图片证据只接受中央image_artifact数字ID，不以外部URL或base64冒充',()=>{expect(imageArtifactIds({parts:[{type:'image_artifact',artifactId:'21'},{type:'image_artifact',artifactId:'21'},{type:'image_artifact',artifactId:'https://outside.test'},{type:'image_base64',imageBase64:'secret'}]})).toEqual(['21']);expect(imageArtifactIds('普通工具正文')).toEqual([])})
})
