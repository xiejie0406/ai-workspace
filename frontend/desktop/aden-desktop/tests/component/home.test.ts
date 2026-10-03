// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HomeView from '../../src/renderer/src/features/home/HomeView.vue'
import { useSessionStore } from '../../src/renderer/src/stores/session'
import { useWorkspaceStore } from '../../src/renderer/src/stores/workspace'
const workspaceId = '11111111-1111-4111-8111-111111111111'
const execute = vi.fn()
const overview = { savedProductCount: 3, todayCaptureCount: 8, pendingTaskCount: 2, recentItems: [{ itemId: 'item-one', title: '已确认商品', sku: '123', updatedAt: '2026-10-01T08:00:00Z' }], recentTasks: [], pendingTasks: [], updatedAt: '2026-10-01T08:00:00Z', timezone: 'Asia/Shanghai' }
const options = { global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } } }
describe('authorized collection overview', () => {
  beforeEach(() => {
    setActivePinia(createPinia()); execute.mockReset()
    const session = useSessionStore(); session.phase = 'authenticated'; session.permissions = ['aden:collection:read']; session.context = { authenticated: true, workspaceId, sessionEpoch: 1, workspaceEpoch: 2 }
    useWorkspaceStore().selectedId = workspaceId
    Object.defineProperty(window, 'adenDesktop', { configurable: true, value: { collection: { execute } } })
  })
  it('displays only server overview statistics and lets a read-only user open a product', async () => {
    execute.mockResolvedValue({ value: overview })
    const wrapper = mount(HomeView, options); await flushPromises()
    expect(wrapper.findAll('.home-stat strong').map(element => element.text())).toEqual(['3', '8', '2'])
    expect(wrapper.find('a').attributes('href')).toBe('/collection/items/item-one')
    expect(execute.mock.calls.map(([operation]) => operation.action)).toEqual(['overview'])
    expect(wrapper.text()).not.toContain('工作空间'); expect(wrapper.text()).not.toContain('安装扩展'); expect(wrapper.text()).not.toContain('连接状态')
    wrapper.unmount()
  })
  it('keeps failures visible instead of showing fake zero counters or empty success', async () => {
    execute.mockRejectedValue(new Error('概览读取失败'))
    const wrapper = mount(HomeView, options); await flushPromises()
    expect(wrapper.find('[role=alert]').text()).toContain('概览读取失败')
    expect(wrapper.findAll('.home-stat strong').map(element => element.text())).toEqual(['—', '—', '—'])
    expect(wrapper.text()).not.toContain('当前没有需要处理的事项')
    wrapper.unmount()
  })
  it('discards an earlier session response after a new epoch', async () => {
    let old!: (result: unknown) => void
    execute.mockImplementationOnce(() => new Promise(resolve => { old = resolve })).mockResolvedValue({ value: { ...overview, savedProductCount: 1 } })
    const wrapper = mount(HomeView, options); await flushPromises()
    const session = useSessionStore(); session.context = { ...session.context, workspaceEpoch: session.context.workspaceEpoch + 1 }; await flushPromises()
    old({ value: { ...overview, savedProductCount: 99, recentItems: [{ title: '旧会话商品' }] } }); await flushPromises()
    expect(wrapper.find('.home-stat strong').text()).toBe('1'); expect(wrapper.text()).not.toContain('旧会话商品')
    wrapper.unmount()
  })
})
