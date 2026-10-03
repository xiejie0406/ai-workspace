import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionController } from '../../src/main/auth/session-controller'
import { AdenApiClient } from '../../src/main/transport/api-client'
import { resolveRuntimeConfig } from '../../src/main/config/runtime-config'
import { CollectionService, imageMime } from '../../src/main/collection/collection-service'
import type { CollectionAction } from '../../src/shared/contracts/collection'

const dialogs = vi.hoisted(() => ({ showOpenDialog: vi.fn(), showSaveDialog: vi.fn() }))
vi.mock('electron', () => ({ dialog: dialogs, nativeImage: { createFromBuffer: () => ({ isEmpty: () => false }) } }))
const workspaceId = '11111111-1111-4111-8111-111111111111'

describe('CollectionService restricted file / workspace boundary', () => {
  beforeEach(() => vi.clearAllMocks())
  function setup() {
    const session = new SessionController(); session.authenticate('secret'); session.selectWorkspace(workspaceId)
    const fetcher = vi.fn(async (_input: string | URL | Request, _init?: RequestInit) => new Response('{}', { headers: { 'content-type': 'application/json' } }))
    const api = new AdenApiClient(resolveRuntimeConfig('https://aden.example.test', false), session, fetcher)
    const service = new CollectionService(api, session)
    const operation = (action: CollectionAction, input: Record<string, unknown> = {}) => ({ ...session.context(), workspaceId, action, input })
    return { session, service, fetcher, operation }
  }
  it('rejects stale context, traversal IDs and arbitrary actions before requests', async () => {
    const { service, operation, session, fetcher } = setup()
    await expect(service.execute(operation('detail', { itemId: '../../secrets' }))).rejects.toThrow('标识')
    const stale = operation('list'); session.clear()
    await expect(service.execute(stale)).rejects.toThrow('上下文')
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('requires a user-selected export location and cancels after workspace change', async () => {
    const { service, operation, session, fetcher } = setup()
    dialogs.showSaveDialog.mockImplementation(async () => { session.clear(); return { canceled: false, filePath: 'unwritten.xlsx' } })
    await expect(service.execute(operation('export', { itemIds: ['item-1'], format: 'XLSX' }))).rejects.toThrow('会话')
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('does not create an export when the native dialog is canceled', async () => {
    const { service, operation, fetcher } = setup()
    dialogs.showSaveDialog.mockResolvedValue({ canceled: true })
    await expect(service.execute(operation('export', { itemIds: ['item-1'], format: 'ZIP' }))).resolves.toEqual({ canceled: true })
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('escapes search and accepts only image signatures', async () => {
    const { service, operation, fetcher } = setup()
    await service.execute(operation('list', { q: 'a&deleted=true', page: 1 }))
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('q=a%26deleted%3Dtrue&deleted=false')
    expect(() => imageMime(Buffer.from('<svg onload="evil()">'))).toThrow('真实')
    expect(imageMime(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))).toBe('image/png')
  })
  it('uses read-only task endpoints and rejects invalid task filters', async () => {
    const { service, operation, fetcher } = setup()
    await service.execute(operation('tasks', { q: 'M5&status=PARTIAL', page: 2, status: 'SAVED' }))
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('/tasks?q=M5%26status%3DPARTIAL&status=SAVED&page=2&pageSize=20')
    await service.execute(operation('taskDetail', { taskId: 'task-1' }))
    expect(String(fetcher.mock.calls[1]?.[0])).toContain('/tasks/task-1')
    await expect(service.execute(operation('tasks', { status: 'DROP' }))).rejects.toThrow('状态')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
  it('rejects more than 100 batch items before dialogs or transport', async () => {
    const { service, operation, fetcher } = setup()
    const itemIds = Array.from({ length: 101 }, (_, index) => `item-${index}`)
    await expect(service.execute(operation('trash', { itemIds }))).rejects.toThrow('100')
    await expect(service.execute(operation('export', { itemIds, format: 'XLSX' }))).rejects.toThrow('100')
    expect(fetcher).not.toHaveBeenCalled(); expect(dialogs.showSaveDialog).not.toHaveBeenCalled()
  })
  it('validates Shanghai date boundaries before sending task filters', async () => {
    const { service, operation, fetcher } = setup()
    await service.execute(operation('tasks', { fromDate: '2026-10-01', toDate: '2026-10-02' }))
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('&fromDate=2026-10-01&toDate=2026-10-02')
    await expect(service.execute(operation('tasks', { fromDate: '2026-02-30' }))).rejects.toThrow('日期不存在')
    await expect(service.execute(operation('tasks', { fromDate: '2026-10-02', toDate: '2026-10-01' }))).rejects.toThrow('晚于')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('rejects malformed overview instead of manufacturing zero statistics', async () => {
    const { service, operation } = setup()
    await expect(service.execute(operation('overview'))).rejects.toThrow('工作台数据格式无效')
  })
})
