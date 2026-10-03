// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CollectionSetupStatus } from '../../src/shared/contracts/collection-setup'
import CollectionSetupView from '../../src/renderer/src/features/collection/CollectionSetupView.vue'
const ready: CollectionSetupStatus = { extensionPath: 'C:/Aden/resources/collector/extension', extensionVersion: '0.2.0', extensionId: 'mnejmmlalapfhnanlnckfdhmfpbahidm', extensionReady: true, hostPath: 'C:/Aden/resources/collector/AdenCollectorHost.exe', hostReady: true, registrationReady: true, registrationKind: 'current', registeredHostPath: null, chromePath: null, problems: [], checkedAt: '2026-10-01T08:00:00Z' }
function setup(status = ready) {
  const api = { status: vi.fn(async () => ({ value: status })), copyDirectory: vi.fn(async () => ({ value: { copied: true, path: status.extensionPath, status } })), openInstall: vi.fn() }
  Object.defineProperty(window, 'adenDesktop', { configurable: true, value: { collectionSetup: api } })
  return { api, wrapper: mount(CollectionSetupView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } }) } }
describe('固定目录设置', () => {
  it('shows the complete directory and copies only through the restricted main action', async () => {
    const { wrapper, api } = setup(); await flushPromises()
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe(ready.extensionPath)
    await wrapper.find('button.setup-primary').trigger('click'); await flushPromises()
    expect(api.copyDirectory).toHaveBeenCalledExactlyOnceWith()
    expect(api.openInstall).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已复制')
    expect(wrapper.text()).toContain('加载已解压的扩展')
    expect(wrapper.text()).not.toContain('复制失败')
    wrapper.unmount()
  })
  it('keeps missing file feedback beside the directory and never copies it', async () => {
    const { wrapper, api } = setup({ ...ready, extensionReady: false, problems: ['资源缺失'] }); await flushPromises()
    expect(wrapper.find('button.setup-primary').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role=alert]').text()).toContain('资源缺失')
    expect(api.copyDirectory).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('releases the copy lock and shows a concise clipboard error', async () => {
    const { wrapper, api } = setup(); await flushPromises()
    api.copyDirectory.mockRejectedValueOnce(new Error('手工选择目录文字复制。'))
    await wrapper.find('button.setup-primary').trigger('click'); await flushPromises()
    expect(wrapper.find('[role=alert]').text()).toContain('手工选择')
    expect(wrapper.text()).not.toContain('已复制')
    expect(wrapper.find('button.setup-primary').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })
  it('ignores late inspection results from an earlier generation', async () => {
    const { wrapper, api } = setup(); await flushPromises()
    let first!: (result: { value: CollectionSetupStatus }) => void
    api.status.mockImplementationOnce(() => new Promise(resolve => { first = resolve }))
    const inspect = wrapper.findAll('button').find(button => button.text() === '重新检测')!
    await inspect.trigger('click')
    await inspect.trigger('click'); await flushPromises()
    first({ value: { ...ready, extensionVersion: '旧响应', extensionReady: false } }); await flushPromises()
    expect(wrapper.text()).not.toContain('旧响应')
    expect(wrapper.find('button.setup-primary').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })
})

