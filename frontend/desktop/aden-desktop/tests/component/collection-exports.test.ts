// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CollectionExportsView from '../../src/renderer/src/features/collection/CollectionExportsView.vue'
import { useSessionStore } from '../../src/renderer/src/stores/session'
import { useWorkspaceStore } from '../../src/renderer/src/stores/workspace'
const workspaceId = '11111111-1111-4111-8111-111111111111'
const execute = vi.fn()
const record = { exportId: 'export-one', fileName: '商品资料.xlsx', format: 'XLSX', status: 'COMPLETED', size: 1024, createdAt: '2026-10-01T08:00:00Z', itemCount: 2, imageSaved: 6, imageMissing: 2, downloadAvailable: true }
function view() { return mount(CollectionExportsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } }) }
describe('saved export records', () => {
  beforeEach(() => {
    setActivePinia(createPinia()); execute.mockReset()
    const session = useSessionStore(); session.phase = 'authenticated'; session.permissions = ['aden:collection:read', 'aden:collection:export']; session.context = { authenticated: true, workspaceId, sessionEpoch: 1, workspaceEpoch: 2 }
    useWorkspaceStore().selectedId = workspaceId
    Object.defineProperty(window, 'adenDesktop', { configurable: true, value: { collection: { execute } } })
  })
  it('shows frozen export details and saves an existing file without creating a new export', async () => {
    execute.mockImplementation(async operation => ({ value: operation.action === 'exports' ? { items: [record], total: 1, page: 1, pageSize: 20 } : operation.action === 'exportDetail' ? { ...record, selections: [{ itemId: '商品A', snapshotId: 'snapshot-one' }] } : { canceled: false, filePath: 'C:/chosen/file.xlsx' } }))
    const wrapper = view(); await flushPromises()
    await wrapper.find('.export-title').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('snapshot-one')
    await wrapper.findAll('button').find(button => button.text() === '保存文件')!.trigger('click'); await flushPromises()
    expect(wrapper.find('[role=status]').text()).toContain('C:/chosen/file.xlsx')
    expect(execute.mock.calls.some(([operation]) => operation.action === 'export')).toBe(false)
    wrapper.unmount()
  })
  it('does not offer downloads for a read-only user or unavailable record', async () => {
    useSessionStore().permissions = ['aden:collection:read']
    execute.mockResolvedValue({ value: { items: [{ ...record, status: 'UNAVAILABLE', downloadAvailable: false, unavailableReason: '权限已撤回' }], total: 1, page: 1, pageSize: 20 } })
    const wrapper = view(); await flushPromises()
    expect(wrapper.findAll('button').find(button => button.text() === '保存文件')!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('权限已撤回'); expect(wrapper.text()).not.toContain('生成中')
    wrapper.unmount()
  })
})
