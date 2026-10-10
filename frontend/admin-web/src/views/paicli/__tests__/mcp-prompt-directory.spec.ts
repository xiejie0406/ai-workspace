import { computed, defineComponent, h, inject, provide } from 'vue'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PcMcpPrompt, PcMcpServer } from '@/types/paicli'

const mocks = vi.hoisted(() => ({ request: vi.fn(), permissions: [] as string[], server: undefined as PcMcpServer | undefined }))
vi.mock('@/store/modules/user', () => ({ default: () => ({ permissions: mocks.permissions, roles: ['ordinary'] }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
vi.mock('@/utils/request', () => ({ default: mocks.request }))
import hasPermi from '@/directive/permission/hasPermi'
import Connections from '../connections/index.vue'

const Container = { template: '<div><slot /><slot name="footer" /></div>' }
const Modal = { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' }
const Button = { props: ['disabled', 'loading'], emits: ['click'], template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>' }
const Input = { props: ['modelValue', 'placeholder'], emits: ['update:modelValue'], template: '<input :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\',$event.target.value)" />' }
const Table = defineComponent({ props: ['data'], setup(props, { slots }) { provide('prompt-fixture-table-rows', computed(() => props.data)); return () => h('div', slots.default?.()) } })
const Column = defineComponent({ setup(_, { slots }) { const rows = inject<{ value: unknown[] }>('prompt-fixture-table-rows')!; return () => h('div', rows.value.map(row => slots.default?.({ row }))) } })
const global = {
  directives: { hasPermi },
  stubs: {
    ElTabs: Container, ElTabPane: Container, ElButton: Button, ElDialog: Modal, ElDrawer: Modal, ElSelect: Container,
    ElOption: true, ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' }, ElTable: Table,
    ElTableColumn: Column, ElInput: Input, ElForm: Container,
    ElFormItem: { props: ['label'], template: '<label>{{label}}<slot /></label>' }, ElTag: Container,
    ElEmpty: true, ElCheckbox: Container, ElSwitch: true, ElInputNumber: true, ElCollapse: Container,
    ElCollapseItem: Container, ElDescriptions: Container, ElDescriptionsItem: Container,
    WorkspaceKnowledgeConfig: true, OperationTask: true, RunnerBinding: true, WechatPolicy: true, ArtifactEvidence: true,
  },
}

function server(prompts: PcMcpPrompt[]): PcMcpServer { return { id: '9', name: 'fixture-server', scope: 'PROJECT', transport: 'HTTP', status: 'READY', version: 8, configHash: 'frozen', trustedHash: 'frozen', tools: [], resources: [], prompts } }
async function openDirectory(prompts: PcMcpPrompt[]) {
  mocks.server = server(prompts)
  const wrapper = shallowMount(Connections, { global })
  await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '高级能力')!.trigger('click'); await flushPromises();
  await wrapper.findAll('button').find(button => button.text() === '工具 / 资源 / 提示')!.trigger('click')
  await flushPromises()
  return wrapper
}
function requests(suffix: string) { return mocks.request.mock.calls.map(call => call[0]).filter(config => config.url.endsWith(suffix)) }
function button(wrapper: VueWrapper, title: string) { return wrapper.findAll('button').find(item => item.text() === title)! }

describe('连接页MCP结构化与历史展示目录', () => {
  beforeEach(() => {
    mocks.permissions = ['paicli:workspace:list', 'paicli:connection:read', 'paicli:connection:manage']
    mocks.request.mockReset()
    mocks.request.mockImplementation((config: { url: string; method?: string }) => {
      if (config.url === '/paicli/v1/personal/context') return Promise.resolve({data:{ownerUserId:'42',directoryContext:{id:'42',label:'个人默认目录',path:'D:/fixture',state:'VERIFIED',version:1},models:[],runtime:{ready:false,message:'未联机'},canChat:false,canOpenTerminal:false}}); if(config.url === '/paicli/v1/personal/directories')return Promise.resolve({data:[{id:'42',label:'个人默认目录',path:'D:/fixture',state:'VERIFIED',version:1}]})
      if (config.url === '/paicli/v1/mcp') return Promise.resolve({ data: [mocks.server] })
      if (config.url === '/paicli/v1/browser') return Promise.resolve({ data: { directoryContextId: '42', version: 0, mode: 'DISCONNECTED', connected: false } })
      if (config.url === '/paicli/v1/wechat') return Promise.resolve({ data: null })
      if (config.url === '/paicli/v1/policies') return Promise.resolve({ data: undefined })
      if (config.url.endsWith('/contents')) return Promise.resolve({ data: { id: '31', directoryContextId: '42', mode: 'CONTROL', status: 'QUEUED' } })
      if (config.url.endsWith('/prompt')) return Promise.resolve({ data: { messages: [{ content: { type: 'text', text: '最近正式回执' } }] } })
      return Promise.resolve({ data: [] })
    })
  })

  it('实际点击结构化目录并填必需参数才提交，沿用正式name、版本及幂等头', async () => {
    const wrapper = await openDirectory([{ name: 'review', description: '审查固定主题', arguments: [{ name: 'topic', required: true, description: '必需主题' }, { name: 'style', description: '可选风格' }] }])
    expect(wrapper.find('.pc-mcp-prompt').text()).toContain('review · 审查固定主题')
    await button(wrapper, '请求提示词与参数').trigger('click')
    expect(button(wrapper, '提交读取任务').attributes('disabled')).toBeDefined()
    expect(requests('/contents')).toHaveLength(0)
    await wrapper.find('input[placeholder="必需主题"]').setValue('固定协议主题')
    await wrapper.find('input[placeholder="可选风格"]').setValue('简洁')
    expect(button(wrapper, '提交读取任务').attributes('disabled')).toBeUndefined()
    await button(wrapper, '提交读取任务').trigger('click')
    await flushPromises()
    expect(requests('/contents')).toHaveLength(1)
    expect(requests('/contents')[0]).toMatchObject({ url: '/paicli/v1/mcp/9/contents', method: 'post', data: { kind: 'prompt', reference: 'review', arguments: { topic: '固定协议主题', style: '简洁' } }, headers: { 'If-Match': '8', 'Idempotency-Key': expect.any(String), repeatSubmit: false } })
    wrapper.unmount()
  })

  it('字符串保留完整展示文字并提示重取元数据，无读取按钮且程序入口也不发POST/GET', async () => {
    const legacy = '原展示标题 (review) - 保留原说明<script>未执行</script>'
    const wrapper = await openDirectory([legacy])
    const row = wrapper.find('.pc-mcp-prompt')
    expect(row.text()).toContain(legacy)
    expect(row.text()).toContain('历史展示目录，需重新获取结构化元数据后读取。')
    expect(row.findAll('button')).toHaveLength(0)
    expect(wrapper.find('script').exists()).toBe(false)
    await row.find('p').trigger('click')
    const vm = wrapper.vm as unknown as { openPrompt: (prompt: string) => void; submitPrompt: () => Promise<void>; requestMcp: (kind: 'prompt', reference: string) => Promise<void>; readMcp: (kind: 'prompt', reference: string) => Promise<void> }
    vm.openPrompt(legacy)
    await vm.submitPrompt()
    await vm.requestMcp('prompt', 'review')
    await vm.readMcp('prompt', 'review')
    expect(requests('/contents')).toHaveLength(0)
    expect(requests('/prompt')).toHaveLength(0)
    wrapper.unmount()
  })

  it('混合目录仅结构化条目可读缓存，不从历史标题猜正式name', async () => {
    const wrapper = await openDirectory(['旧展示 (review) - 历史说明', { name: 'real-review', description: '正式结构化提示' }])
    expect(wrapper.findAll('.pc-mcp-prompt')[0]!.findAll('button')).toHaveLength(0)
    await button(wrapper, '查看最近确认内容').trigger('click')
    await flushPromises()
    expect(requests('/prompt')).toHaveLength(1)
    expect(requests('/prompt')[0]).toMatchObject({ url: '/paicli/v1/mcp/9/prompt', method: 'get', params: { reference: 'real-review' } })
    expect(wrapper.text()).toContain('最近正式回执')
    expect(requests('/contents')).toHaveLength(0)
    wrapper.unmount()
  })

  it('普通只读角色保留结构化缓存读取，权限指令不显示申请读取按钮', async () => {
    mocks.permissions = ['paicli:workspace:list', 'paicli:connection:read']
    const wrapper = await openDirectory([{ name: 'review', arguments: [{ name: 'topic', required: true }] }])
    expect(wrapper.findAll('button').some(item => item.text() === '请求提示词与参数')).toBe(false)
    await button(wrapper, '查看最近确认内容').trigger('click')
    await flushPromises()
    expect(requests('/prompt')).toHaveLength(1)
    expect(requests('/contents')).toHaveLength(0)
    wrapper.unmount()
  })
})
