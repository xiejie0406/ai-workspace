// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CollectionView from '../../src/renderer/src/features/collection/CollectionView.vue'
import { useSessionStore } from '../../src/renderer/src/stores/session'
import { useWorkspaceStore } from '../../src/renderer/src/stores/workspace'

const workspaceId = '11111111-1111-4111-8111-111111111111'
describe('商品库行为', () => {
  const execute = vi.fn()
  beforeEach(() => {
    setActivePinia(createPinia()); execute.mockReset()
    const session = useSessionStore(); session.phase = 'authenticated'; session.permissions = ['*:*:*']; session.context = { authenticated: true, workspaceId, sessionEpoch: 1, workspaceEpoch: 2 }
    useWorkspaceStore().selectedId = workspaceId
    Object.defineProperty(window, 'adenDesktop', { configurable: true, value: { collection: { execute } } })
  })
  it('loads only server data and clears page selection when filtering', async () => {
    execute.mockResolvedValue({ value: { items: [{ itemId: 'one', title: '服务器商品', platform: 'JD', status: 'PARTIAL', updatedAt: 'now' }], total: 1, page: 1, pageSize: 20 }, context: useSessionStore().context })
    const wrapper = mount(CollectionView); await flushPromises()
    expect(wrapper.text()).toContain('服务器商品'); expect(wrapper.text()).toContain('部分保存')
    await wrapper.find('tbody input[type=checkbox]').setValue(true)
    expect(wrapper.find('.collection-selection').text()).toContain('已选择当前页 1 个商品')
    await wrapper.find('form.collection-toolbar').trigger('submit'); await flushPromises()
    expect(wrapper.find('.collection-selection').exists()).toBe(false); expect(wrapper.text()).toContain('已清空选择')
    expect(execute.mock.calls.every(([op]) => op.workspaceId === workspaceId)).toBe(true)
    wrapper.unmount()
  })
  it('shows an error rather than fabricated empty success on a rejected server request', async () => {
    execute.mockRejectedValue(new Error('无采集库权限'))
    const wrapper = mount(CollectionView); await flushPromises()
    expect(wrapper.find('[role=alert]').text()).toContain('无采集库权限')
    wrapper.unmount()
  })
  it('discards a stale result after workspace changes', async () => {
    let complete: (value: unknown) => void = () => undefined
    execute.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const wrapper = mount(CollectionView); await flushPromises()
    useSessionStore().clear(); useWorkspaceStore().selectedId = null
    complete({ value: { items: [{ itemId: 'one', title: '旧空间秘密' }], total: 1, page: 1, pageSize: 20 } }); await flushPromises()
    expect(wrapper.text()).not.toContain('旧空间秘密')
    wrapper.unmount()
  })
  it('keeps historical images independent from current curation', async () => {
    const item = { itemId: 'one', title: '版本商品', platform: 'JD', status: 'SAVED', snapshotId: 'new', updatedAt: 'now' }
    const snapshots = ['new', 'old'].map(snapshotId => ({ snapshotId, fields: {}, blocks: [], images: [{ imageId: 'shared-id', group: '主图', order: 1, status: 'FAILED' }], source: 'JD', status: 'PARTIAL', createdAt: snapshotId }))
    execute.mockImplementation(async (op) => ({ value: op.action === 'list' ? { items: [item], total: 1, page: 1, pageSize: 20 } : op.action === 'versions' ? { items: snapshots.slice(op.input.page === 2 ? 1 : 0, op.input.page === 2 ? 2 : 1).map(({ snapshotId, source, status, createdAt }) => ({ snapshotId, source, status, createdAt })), total: 21, page: op.input.page, pageSize: 20 } : op.action === 'snapshot' ? snapshots.find(s => s.snapshotId === op.input.snapshotId) : {
      ...item, snapshots: [snapshots[0]], currentSnapshot: snapshots[0], snapshotCount: 2,
      curation: { revision: 1, snapshotId: 'new', notes: '', tags: [], overrides: {}, removedImageIds: ['shared-id'], mainImageId: 'shared-id', imageOrder: [] }
    } }))
    const wrapper = mount(CollectionView); await flushPromises()
    await wrapper.find('.collection-title').trigger('click'); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '图片')!.trigger('click')
    expect(wrapper.find('.collection-images article').classes()).toContain('removed')
    await wrapper.findAll('button').find(b => b.text() === '下一页版本')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('第 2 页')
    await wrapper.find('.collection-detail select').setValue('old')
    await flushPromises()
    expect(wrapper.find('.collection-images article').classes()).not.toContain('removed')
    expect(wrapper.find('.collection-images').text()).not.toContain('当前主图')
    expect(execute.mock.calls.some(([op]) => op.action === 'snapshot' && op.input.snapshotId === 'old')).toBe(true)
    wrapper.unmount()
  })
  it('removes product creation and trash while keeping export-only selection', async () => {
    execute.mockResolvedValue({ value: { items: [], total: 0, page: 1, pageSize: 20 } })
    const wrapper = mount(CollectionView); await flushPromises()
    expect(wrapper.text()).not.toContain('手动新增')
    expect(wrapper.text()).not.toContain('回收站')
    expect(wrapper.findAll('button').find(b => b.text() === '导出所选')!.attributes('disabled')).toBeDefined()
    expect(execute.mock.calls.every(([op]) => !['create', 'trash', 'restore'].includes(op.action))).toBe(true)
    wrapper.unmount()
  })
  it('refreshes list image counts after upload while preserving selection and detail', async () => {
    let uploaded = false
    const item = () => ({ itemId: 'one', title: '上传商品', platform: 'MANUAL', status: 'SAVED', snapshotId: 'current', imageSaved: uploaded ? 1 : 0, imageTotal: uploaded ? 1 : 0 })
    const snapshot = () => ({ snapshotId: 'current', fields: {}, blocks: [], images: [], source: 'MANUAL', status: 'SAVED', createdAt: 'now' })
    execute.mockImplementation(async (op) => {
      if (op.action === 'upload') { uploaded = true; return { value: { canceled: false, uploaded: 1 } } }
      if (op.action === 'list') return { value: { items: [item()], total: 1, page: 1, pageSize: 20 } }
      if (op.action === 'versions') return { value: { items: [snapshot()], total: 1, page: 1, pageSize: 20 } }
      return { value: { ...item(), currentSnapshot: snapshot(), snapshots: [snapshot()], snapshotCount: 1,
        curation: { revision: 0, snapshotId: 'current', notes: '', tags: [], overrides: {}, removedImageIds: [], mainImageId: null, imageOrder: [] } } }
    })
    const wrapper = mount(CollectionView); await flushPromises()
    expect(wrapper.find('tbody').text()).toContain('图片已保存 0 / 0')
    await wrapper.find('tbody input[type=checkbox]').setValue(true)
    await wrapper.find('.collection-title').trigger('click'); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '图片')!.trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '添加本地图片')!.trigger('click'); await flushPromises()
    expect(wrapper.find('tbody').text()).toContain('图片已保存 1 / 1')
    expect(wrapper.find('.collection-selection').text()).toContain('已选择当前页 1 个商品')
    expect(wrapper.find('.collection-detail').exists()).toBe(true)
    wrapper.unmount()
  })
})
