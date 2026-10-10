import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { ElInput, ElInputNumber } from 'element-plus'
import Providers from '@/views/paismart/operations/providers/index.vue'

const api = vi.hoisted(() => ({ listProviders: vi.fn(), getProvider: vi.fn(), saveProvider: vi.fn(), testProvider: vi.fn() }))
vi.mock('@/api/paismart/operations', () => api)
vi.mock('@/api/paismart/client', () => ({ newKey: () => 'offline-provider-test' }))
const slots = { template: '<div><slot /><slot name="footer" /></div>' }
const select = defineComponent({
  props: ['modelValue', 'disabled'], emits: ['update:modelValue', 'change'],
  setup(props, { slots, emit }) {
    return () => h('select', {
      value: props.modelValue, disabled: props.disabled,
      onChange: (event: Event) => { const value = (event.target as HTMLSelectElement).value; emit('update:modelValue', value); emit('change', value) }
    }, slots.default?.())
  }
})
const global = {
  stubs: {
    ...Object.fromEntries(['el-dialog', 'el-form', 'el-form-item', 'el-descriptions', 'el-descriptions-item'].map(name => [name, slots])),
    'el-table': true, 'el-table-column': true, 'el-alert': true, 'el-checkbox': true, 'router-link': true,
    'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
    'el-input': { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
    'el-input-number': { props: ['modelValue'], template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />' }, 'el-switch': true, 'el-select': select,
    'el-option': { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' }
  }, directives: { hasPermi: () => {}, loading: () => {} }
}
beforeEach(() => {
  vi.clearAllMocks()
  api.listProviders.mockResolvedValue({ rows: [] })
  api.getProvider.mockResolvedValue({ data: {
    id: 11, scope: 'LLM', provider: 'SUB2API', model: 'gpt-6.1-sol', address: 'https://model.example.test/v1',
    enabled: true, active: false, rowVersion: 2,
    config: { providerCode: 'sub2api', apiProtocol: 'responses', maxOutputTokens: 2048, reasoningEffort: 'low' }
  } })
  api.saveProvider.mockResolvedValue({ data: { id: 11, scope: 'LLM', rowVersion: 3 } })
})
async function openConfiguration() {
  const wrapper = mount(Providers, { global })
  await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '配置对话模型')!.trigger('click')
  await flushPromises()
  return wrapper
}
async function save(wrapper: Awaited<ReturnType<typeof openConfiguration>>) {
  await wrapper.findAll('button').find(button => button.text() === '保存配置')!.trigger('click')
  await flushPromises()
  expect(api.saveProvider).toHaveBeenCalledOnce()
  expect(api.testProvider).not.toHaveBeenCalled()
  return api.saveProvider.mock.calls[0]![0]
}
describe('供应商配置的 Responses 推理参数边界', () => {
  it('真实 Element Plus 数字控件在新增候选和协议切换后保留手输预算', async () => {
    const wrapper = mount(Providers, { global: { ...global, components: { ElInput, ElInputNumber }, stubs: { ...global.stubs, 'el-input': false, 'el-input-number': false } } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '新增供应商')!.trigger('click')
    await wrapper.findAll('select')[1]!.setValue('SUB2API')
    await wrapper.findAll('select')[2]!.setValue('responses')
    await wrapper.find('select[aria-label="推理强度"]').setValue('low')
    const budget = wrapper.find('input[type="number"]')
    await budget.setValue('4096')
    await budget.trigger('change')
    await budget.trigger('blur')
    await flushPromises()
    expect(wrapper.findComponent(ElInputNumber).props('modelValue')).toBe(4096)
    expect((budget.element as HTMLInputElement).value).toBe('4096')
    await wrapper.find('input[placeholder="精确填写实例支持的模型 ID"]').setValue('gpt-6.1-sol')
    await wrapper.find('input[placeholder="HTTPS 供应商根地址"]').setValue('https://model.example.test/v1')
    await wrapper.find('input[placeholder="填写 ai.paismart.* 托管引用"]').setValue('ai.paismart.sub2api.llm')
    const saved = await save(wrapper)
    expect(saved.config.maxOutputTokens).toBe(4096)
    wrapper.unmount()
  })
  it('新建候选可在原表单选择 low 并保存，保持未启用未激活', async () => {
    const wrapper = mount(Providers, { global })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '新增供应商')!.trigger('click')
    await wrapper.findAll('select')[1]!.setValue('SUB2API')
    await wrapper.findAll('select')[2]!.setValue('responses')
    await wrapper.find('select[aria-label="推理强度"]').setValue('low')
    await wrapper.find('input[placeholder="精确填写实例支持的模型 ID"]').setValue('gpt-6.1-sol')
    await wrapper.find('input[placeholder="HTTPS 供应商根地址"]').setValue('https://model.example.test/v1')
    await wrapper.find('input[placeholder="填写 ai.paismart.* 托管引用"]').setValue('ai.paismart.sub2api.llm')
    await wrapper.find('input[type="number"]').setValue('4096')
    const saved = await save(wrapper)
    expect(saved.config).toMatchObject({ providerCode: 'sub2api', apiProtocol: 'responses', maxOutputTokens: 4096, reasoningEffort: 'low' })
    expect(saved.enabled).toBe(false)
    expect(saved.active).toBe(false)
    wrapper.unmount()
  })
  it('编辑时选择供应商默认会省略字段而不是发送空值', async () => {
    const wrapper = await openConfiguration()
    await wrapper.find('select[aria-label="推理强度"]').setValue('')
    const saved = await save(wrapper)
    expect(saved.config).not.toHaveProperty('reasoningEffort')
    wrapper.unmount()
  })
  it('回读并保存 Responses 候选时保留 low，不触发模型验证', async () => {
    const wrapper = await openConfiguration()
    const saved = await save(wrapper)
    expect(saved.config).toMatchObject({ apiProtocol: 'responses', reasoningEffort: 'low', maxOutputTokens: 2048 })
    expect(saved.secretRef).toBe('')
    wrapper.unmount()
  })
  it('改用 Chat Completions 后保存只带所选输出参数，不携带 Responses 推理参数', async () => {
    const wrapper = await openConfiguration()
    await wrapper.findAll('select')[2]!.setValue('chat_completions')
    await wrapper.findAll('select')[3]!.setValue('max_completion_tokens')
    const saved = await save(wrapper)
    expect(saved.config).toMatchObject({ apiProtocol: 'chat_completions', outputTokenParameter: 'max_completion_tokens' })
    expect(saved.config).not.toHaveProperty('reasoningEffort')
    wrapper.unmount()
  })
  it('改用其他供应商后保存清理 Sub2API 参数，保留原保存入口', async () => {
    const wrapper = await openConfiguration()
    await wrapper.findAll('select')[1]!.setValue('QWEN')
    const saved = await save(wrapper)
    expect(saved.provider).toBe('QWEN')
    expect(saved.active).toBe(false)
    expect(saved.config).not.toHaveProperty('reasoningEffort')
    expect(saved.config).not.toHaveProperty('apiProtocol')
    wrapper.unmount()
  })
  it('新配置改为向量用途时清理文字专用参数', async () => {
    const candidate = (await api.getProvider()).data
    api.getProvider.mockResolvedValue({ data: { ...candidate, id: undefined, enabled: false } })
    const wrapper = await openConfiguration()
    await wrapper.findAll('select')[0]!.setValue('EMBEDDING')
    await wrapper.findAll('select')[1]!.setValue('ALIYUN')
    const saved = await save(wrapper)
    expect(saved.scope).toBe('EMBEDDING')
    expect(saved.provider).toBe('ALIYUN')
    expect(saved.config).not.toHaveProperty('reasoningEffort')
    expect(saved.config).not.toHaveProperty('apiProtocol')
    wrapper.unmount()
  })
  it('先编辑 Sub2API 再回读普通旧供应商时保留其未知配置', async () => {
    const wrapper = await openConfiguration()
    api.getProvider.mockResolvedValue({ data: {
      id: 12, scope: 'LLM', provider: 'QWEN', model: 'legacy-model', address: 'https://legacy.example.test/v1',
      enabled: true, active: false, rowVersion: 4,
      config: { providerCode: 'dashscope', reasoningEffort: 'legacy-custom-value', outputTokenParameter: 'legacy-output-value' }
    } })
    await wrapper.findAll('button').find(button => button.text() === '配置对话模型')!.trigger('click')
    await flushPromises()
    const saved = await save(wrapper)
    expect(saved.provider).toBe('QWEN')
    expect(saved.config).toMatchObject({ reasoningEffort: 'legacy-custom-value', outputTokenParameter: 'legacy-output-value' })
    wrapper.unmount()
  })
})
