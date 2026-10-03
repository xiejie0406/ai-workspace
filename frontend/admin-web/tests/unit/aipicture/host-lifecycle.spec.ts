import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { reactive, ref, h, KeepAlive, defineComponent } from 'vue'

const load = vi.hoisted(() => vi.fn())
const store = vi.hoisted(() => vi.fn())
const routeHook = vi.hoisted(() => vi.fn())
vi.mock('@/api/aipicture/bridge', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/aipicture/bridge')>(), loadAiPictureWorkbench: load
}))
vi.mock('@/utils/request', () => ({ default: vi.fn() }))
vi.mock('file-saver', () => ({ saveAs: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ default: store }))
vi.mock('vue-router', () => ({ useRoute: routeHook, useRouter: () => ({}) }))
import AiPictureHost from '@/components/AiPictureHost/index.vue'
import { createAiPictureHost } from '@/api/aipicture/bridge'

describe('TagsView 图片业务宿主生命周期', () => {
  const config = { props: { page: 'editor' }, global: { directives: { loading: () => {} }, stubs: { 'el-alert': true, 'el-button': true } } }
  beforeEach(() => {
    vi.clearAllMocks()
    store.mockReturnValue(reactive({ id: 'u1', token: 'technical-store-token', roles: ['reader'], permissions: ['aipicture:session:query'] }))
    routeHook.mockReturnValue(reactive({ fullPath: '/editor/s1', params: { sessionId: 's1' }, query: {} }))
  })

  it('账号/权限变化更新宿主，退出与销毁停止业务实例', async () => {
    const instance = { update: vi.fn(), unmount: vi.fn() }
    const entry = vi.fn(() => instance)
    load.mockResolvedValue({ mount: entry })
    const wrapper = mount(AiPictureHost, config)
    await flushPromises()
    expect(entry).toHaveBeenCalledOnce()
    const user = store.mock.results[0].value
    user.id = ''
    user.permissions = []
    await flushPromises()
    expect(instance.update).toHaveBeenLastCalledWith(expect.objectContaining({ active: false }))
    wrapper.unmount()
    expect(instance.unmount).toHaveBeenCalledOnce()
  })

  it('加载未完成就销毁，不允许迟到模块挂载', async () => {
    let release!: (value: unknown) => void
    load.mockReturnValue(new Promise(resolve => { release = resolve }))
    const entry = vi.fn()
    const wrapper = mount(AiPictureHost, config)
    wrapper.unmount()
    release({ mount: entry })
    await flushPromises()
    expect(entry).not.toHaveBeenCalled()
  })

  it('实际若依退出形状保留id，清token/roles/permissions仍更新为无身份宿主', async () => {
    const instance = { update: vi.fn(), unmount: vi.fn() }
    const entry = vi.fn((_container: unknown, _initialHost: ReturnType<typeof createAiPictureHost>) => instance)
    load.mockResolvedValue({ mount: entry })
    const wrapper = mount(AiPictureHost, config)
    await flushPromises()
    const user = store.mock.results[0].value
    expect(entry.mock.calls[0][1].identity.userId).toBe('u1')
    // 同 user.logOut 成功后的字段赋值；原 store 并不清除 id。
    user.token = ''; user.roles = []; user.permissions = []
    await flushPromises()
    expect(user.id).toBe('u1')
    expect(instance.update).toHaveBeenLastCalledWith(expect.objectContaining({ identity: expect.objectContaining({ userId: 0 }), active: false }))
    user.token = 'technical-new-token'; user.roles = ['reader']; user.permissions = ['aipicture:session:query']
    await flushPromises()
    expect(instance.update).toHaveBeenLastCalledWith(expect.objectContaining({ identity: expect.objectContaining({ userId: 'u1' }), active: true }))
    expect(JSON.stringify(instance.update.mock.calls)).not.toContain('technical-new-token')
    wrapper.unmount()
  })

  it('同账号刷新token及TagsView失活保持身份，单独清token可停止已有实例', async () => {
    const instance = { update: vi.fn(), unmount: vi.fn() }
    const entry = vi.fn(() => instance)
    load.mockResolvedValue({ mount: entry })
    const shown = ref(true)
    const holder = defineComponent({ setup: () => () => h(KeepAlive, null, { default: () => shown.value ? h(AiPictureHost, { page: 'create' }) : null }) })
    const wrapper = mount(holder, { global: config.global })
    await flushPromises()
    const user = store.mock.results[0].value
    user.token = 'technical-refreshed-token'
    await flushPromises()
    expect(instance.update).toHaveBeenLastCalledWith(expect.objectContaining({ identity: expect.objectContaining({ userId: 'u1' }), active: true }))
    shown.value = false
    await flushPromises()
    expect(instance.update).toHaveBeenLastCalledWith(expect.objectContaining({ identity: expect.objectContaining({ userId: 'u1' }), active: false }))
    shown.value = true
    await flushPromises()
    expect(instance.update).toHaveBeenLastCalledWith(expect.objectContaining({ identity: expect.objectContaining({ userId: 'u1' }), active: true }))
    user.token = ''
    await flushPromises()
    expect(instance.update).toHaveBeenLastCalledWith(expect.objectContaining({ identity: expect.objectContaining({ userId: 0 }), active: false }))
    wrapper.unmount()
  })

  it('构建产物缺失给出可恢复反馈，不白屏或伪造业务成功', async () => {
    load.mockRejectedValue(new Error('找不到业务制品'))
    const wrapper = mount(AiPictureHost, config)
    await flushPromises()
    const alert = wrapper.findComponent({ name: 'ElAlert' })
    expect(alert.exists() ? alert.attributes('description') : wrapper.html()).toContain('找不到业务制品')
    wrapper.unmount()
  })
})
