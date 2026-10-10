import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Component } from 'vue'

const mocks = vi.hoisted(() => ({ permissions: [] as string[], request: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ default: () => ({ id: '5', permissions: mocks.permissions, roles: ['ordinary'] }) }))
vi.mock('@/utils/request', () => ({ default: mocks.request }))
import hasPermi from '@/directive/permission/hasPermi'
import PromptTemplates from '../components/PromptTemplates.vue'
import ProjectRules from '../components/ProjectRules.vue'

const Container = { template: '<div><slot /><slot name="footer" /></div>' }
const Button = { props: ['disabled', 'loading'], emits: ['click'], template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>' }
const global = {
  directives: { hasPermi },
  stubs: {
    ElButton: Button, ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
    ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
    ElTable: { props: ['data'], template: '<div data-list>{{JSON.stringify(data)}}</div>' },
    ElTableColumn: true, ElEmpty: true, ElForm: Container, ElFormItem: Container,
    ElInput: true, ElSelect: Container, ElOption: true, ElSwitch: true,
    ElRadioGroup: Container, ElRadio: true, ElCollapse: Container, ElCollapseItem: Container,
  },
}
const definitions: { title: string; component: Component; permission: string; url: string; refresh: string; personal: Record<string, unknown> }[] = [
  { title: '个人提示词', component: PromptTemplates, permission: 'paicli:connection:read', url: '/paicli/v1/prompt-templates', refresh: '刷新模板元数据', personal: { id: '51', scope: 'PERSONAL', relativePath: 'prompts/personalities/calm.md', version: 2, enabled: true, sha256: 'a'.repeat(64) } },
  { title: '个人 PAI 规则', component: ProjectRules, permission: 'paicli:knowledge:read', url: '/paicli/v1/project-rules', refresh: '刷新规则元数据', personal: { id: '52', scope: 'PERSONAL', layer: 'USER_PAI', sourcePath: 'user/PAI.md', version: 3, enabled: true, sha256: 'b'.repeat(64) } },
]
type Loader = { load: () => Promise<void> }
function deferred() {
  let resolve!: (response: { data: Record<string, unknown>[] }) => void
  let reject!: (error: Error) => void
  const promise = new Promise<{ data: Record<string, unknown>[] }>((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

describe.each(definitions)('$title 无目录仍读取本人元数据', definition => {
  beforeEach(() => { mocks.permissions = [definition.permission]; mocks.request.mockReset() })

  it('没有执行目录时读取并展示 PERSONAL，刷新仍只 GET 元数据，不读原文或写入', async () => {
    mocks.request.mockResolvedValue({ data: [definition.personal] })
    const wrapper = mount(definition.component, { props: { directoryContextId: '' }, global })
    await flushPromises()
    expect(mocks.request).toHaveBeenCalledTimes(1)
    expect(mocks.request.mock.calls[0][0]).toMatchObject({ url: definition.url, method: 'get', params: { directoryContextId: '' } })
    expect(wrapper.get('[data-list]').text()).toContain(JSON.stringify(definition.personal))
    await wrapper.findAll('button').find(button => button.text() === definition.refresh)!.trigger('click')
    await flushPromises()
    expect(mocks.request).toHaveBeenCalledTimes(2)
    expect(mocks.request.mock.calls.every(([config]) => config.url === definition.url && config.method === 'get')).toBe(true)
    wrapper.unmount()
  })

  it('缺读取权限时无目录和程序刷新入口均不请求', async () => {
    mocks.permissions = []
    const wrapper = mount(definition.component, { props: { directoryContextId: '' }, global })
    await flushPromises()
    await (wrapper.vm as unknown as Loader).load()
    expect(mocks.request).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('没有')
    wrapper.unmount()
  })

  it('无目录读取失败展示原错误，刷新成功后清除错误并恢复列表', async () => {
    mocks.request.mockRejectedValueOnce(new Error('本人资料读取暂时失败')).mockResolvedValue({ data: [definition.personal] })
    const wrapper = mount(definition.component, { props: { directoryContextId: '' }, global })
    await flushPromises()
    expect(wrapper.text()).toContain('本人资料读取暂时失败')
    const refresh = wrapper.findAll('button').find(button => button.text() === definition.refresh)!
    expect(refresh.attributes('disabled')).toBeUndefined()
    await refresh.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('本人资料读取暂时失败')
    expect(wrapper.get('[data-list]').text()).toContain(JSON.stringify(definition.personal))
    expect(mocks.request).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('目录 A 切到无目录再切到 B 时保留每次参数，旧成功与旧失败不覆盖当前列表', async () => {
    const old = deferred(), personal = deferred(), current = deferred()
    mocks.request.mockReturnValueOnce(old.promise).mockReturnValueOnce(personal.promise).mockReturnValueOnce(current.promise)
    const wrapper = mount(definition.component, { props: { directoryContextId: '42' }, global })
    await wrapper.setProps({ directoryContextId: '' })
    await wrapper.setProps({ directoryContextId: '43' })
    expect(mocks.request.mock.calls.map(([config]) => config.params.directoryContextId)).toEqual(['42', '', '43'])
    const project = { ...definition.personal, id: '53', scope: 'PROJECT', directoryContextId: '43' }
    current.resolve({ data: [project] }); await flushPromises()
    old.resolve({ data: [{ ...definition.personal, id: 'old-directory' }] })
    personal.reject(new Error('旧个人列表失败')); await flushPromises()
    expect(wrapper.get('[data-list]').text()).toBe(JSON.stringify([project]))
    expect(wrapper.text()).not.toContain('旧个人列表失败')
    expect(wrapper.text()).not.toContain('old-directory')
    wrapper.unmount()
  })

  it('同一目录连续刷新以最新请求为准，卸载后晚到错误不会写回', async () => {
    const old = deferred(), latest = deferred(), removed = deferred()
    mocks.request.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise).mockReturnValueOnce(removed.promise)
    const wrapper = mount(definition.component, { props: { directoryContextId: '42' }, global })
    const vm = wrapper.vm as unknown as Loader
    const refreshed = vm.load()
    latest.resolve({ data: [definition.personal] }); await refreshed; await flushPromises()
    old.resolve({ data: [{ ...definition.personal, id: 'stale-refresh' }] }); await flushPromises()
    expect(wrapper.get('[data-list]').text()).toBe(JSON.stringify([definition.personal]))
    const pending = vm.load(); wrapper.unmount()
    removed.reject(new Error('卸载后的错误')); await pending
    expect(mocks.request).toHaveBeenCalledTimes(3)
  })
})
