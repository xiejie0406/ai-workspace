// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import CollectionTasksView from '../../src/renderer/src/features/collection/CollectionTasksView.vue'
import { useSessionStore } from '../../src/renderer/src/stores/session'
import { useWorkspaceStore } from '../../src/renderer/src/stores/workspace'

const workspaceId = '11111111-1111-4111-8111-111111111111'
const task = {
  taskId: 'task-1', captureId: 'capture-1', snapshotId: 'capture-1', itemId: 'item-1',
  title: '真实返回商品', platform: 'JD', source: 'JD', createdAt: '2026-09-26 14:32',
  taskState: 'COMPLETED', status: 'PARTIAL', contentSaved: true,
  imageSaved: 1, imageFailed: 1, imageTotal: 2, blockCount: 1, deleted: false
}
const execute = vi.fn()

async function setup(path = '/collection/tasks') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/collection/tasks', component: CollectionTasksView },
    { path: '/collection/tasks/:taskId', component: CollectionTasksView },
    { path: '/collection/items/:itemId', component: { template: '<div />' } },
    { path: '/collection', component: { template: '<div />' } }
  ] })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(CollectionTasksView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('采集任务界面', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    execute.mockReset()
    const session = useSessionStore()
    session.phase = 'authenticated'
    session.permissions = ['*:*:*']
    session.context = { authenticated: true, workspaceId, sessionEpoch: 1, workspaceEpoch: 2 }
    const workspace = useWorkspaceStore()
    workspace.selectedId = workspaceId
    workspace.items = [{ workspaceId, displayName: '测试空间' }] as typeof workspace.items
    Object.defineProperty(window, 'adenDesktop', { configurable: true, value: { collection: { execute } } })
  })

  it('shows only server returned saves and their actual image status', async () => {
    execute.mockImplementation(async (operation) => ({ value: operation.action === 'tasks'
      ? { items: [task], total: 1, page: 1, pageSize: 20 }
      : { ...task, fields: { title: task.title }, blocks: [{ type: 'INTRO', text: '已保存介绍' }], images: [] }
    }))
    const { wrapper, router } = await setup()
    expect(wrapper.text()).toContain('真实返回商品')
    expect(wrapper.text()).toContain('部分保存')
    expect(wrapper.text()).toContain('1/2')
    expect(wrapper.text()).not.toContain('Chrome 插件连接状态')
    await router.push('/collection/tasks/task-1')
    await flushPromises()
    expect(wrapper.text()).toContain('本次任务详情')
    expect(wrapper.text()).toContain('已保存 1 / 候选 2 张，失败 1 张')
    expect(execute.mock.calls.some(([operation]) => operation.action === 'taskDetail' && operation.input.taskId === 'task-1')).toBe(true)
    wrapper.unmount()
  })

  it('shows a server error without inventing an empty success', async () => {
    execute.mockRejectedValue(new Error('中心服务暂不可用'))
    const { wrapper } = await setup()
    expect(wrapper.find('[role=alert]').text()).toContain('中心服务暂不可用')
    wrapper.unmount()
  })
})
