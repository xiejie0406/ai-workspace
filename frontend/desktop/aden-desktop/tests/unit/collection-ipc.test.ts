import { describe, expect, it, vi } from 'vitest'
import { registerDesktopIpcHandlers } from '../../src/main/ipc/register-handlers'
import { DESKTOP_IPC as IPC } from '../../src/shared/contracts/desktop-api'
import { SessionController } from '../../src/main/auth/session-controller'
import { AdenApiClient } from '../../src/main/transport/api-client'
import { resolveRuntimeConfig } from '../../src/main/config/runtime-config'
import { createRendererTrust } from '../../src/main/config/trusted-origins'

vi.mock('electron', () => ({ dialog: {}, nativeImage: {} }))
const copyDirectory = vi.hoisted(() => vi.fn(async () => ({ copied: true, path: 'fixed-extension-directory' })))
vi.mock('../../src/main/collection/collection-setup', () => ({ CollectionSetupService: class { copyDirectory = copyDirectory } }))

const workspaceId = '11111111-1111-4111-8111-111111111111'

function setup(permissions = ['aden:collection:read']) {
  const handlers = new Map<string, (event: unknown, payload: unknown) => Promise<any>>()
  const session = new SessionController()
  const fetcher = vi.fn(async () => new Response('{"items":[],"total":0}', {
    headers: { 'content-type': 'application/json' }
  }))
  const api = new AdenApiClient(resolveRuntimeConfig('https://aden.example.test', false), session, fetcher)
  const auth = {
    login: async () => { session.authenticate('synthetic-token'); return { permissions, workspace: { workspaceId }, bootstrap: { streamCursor: 'cursor', streamWatermark: '0' } } }
  }
  const sse = { stop: vi.fn(), start: vi.fn() }
  registerDesktopIpcHandlers({
    ipcMain: { handle: (channel: string, handler: any) => handlers.set(channel, handler) },
    trust: createRendererTrust(true, 'http://127.0.0.1:5173', 'out/renderer/index.html'),
    session, api, auth, sse
  } as never)
  const mainFrame = { url: 'http://127.0.0.1:5173/' }
  const event = { senderFrame: mainFrame, sender: { mainFrame } }
  const invoke = (payload: unknown, sender = event) => handlers.get(IPC.collection)!(sender, payload)
  const login = async () => {
    await handlers.get(IPC.login)!(event, { username: 'synthetic', password: 'synthetic' })
    session.selectWorkspace(workspaceId)
  }
  const operation = (action: string) => ({
    workspaceId, sessionEpoch: session.context().sessionEpoch, workspaceEpoch: session.context().workspaceEpoch,
    action, input: action === 'taskDetail' ? { taskId: 'task-1' } : {}
  })
  return { login, invoke, operation, fetcher, session, event, sse, handlers }
}

describe('采集任务经过真实 IPC 分发与权限边界', () => {
  it('copies only the main-process fixed extension directory and rejects renderer paths', async () => {
    copyDirectory.mockClear()
    const { handlers, event } = setup()
    const invokeSetup = handlers.get(IPC.collectionSetup)!
    expect((await invokeSetup(event, { action: 'copy-directory', path: 'C:\\other' })).ok).toBe(false)
    expect(copyDirectory).not.toHaveBeenCalled()
    expect((await invokeSetup(event, { action: 'copy-directory' })).value.path).toBe('fixed-extension-directory')
    expect(copyDirectory).toHaveBeenCalledTimes(1)
  })
  it('does not subscribe collection-only readers to CORE events', async () => {
    const { login, sse } = setup(['aden:collection:read'])
    await login()
    expect(sse.start).not.toHaveBeenCalled()
  })
  it.each(['tasks', 'taskDetail'])('有采集读取权限时 %s 发出只读请求', async (action) => {
    const { login, invoke, operation, fetcher } = setup()
    await login()
    const result = await invoke(operation(action))
    expect(result.ok).toBe(true)
    expect(result.value).toEqual({ items: [], total: 0 })
    expect(fetcher).toHaveBeenCalledTimes(1)
    const [url, options] = fetcher.mock.calls[0] as unknown as [string, RequestInit]
    expect(String(url)).toContain(`/collection/workspaces/${workspaceId}/tasks`)
    if (action === 'taskDetail') expect(String(url)).toContain('/tasks/task-1')
    expect(options.method).toBe('GET')
  })

  it.each(['tasks', 'taskDetail'])('没有采集读取权限时 %s 不访问后台', async (action) => {
    const { login, invoke, operation, fetcher } = setup(['aden:task:list'])
    await login()
    expect((await invoke(operation(action))).ok).toBe(false)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it.each(['tasks', 'taskDetail'])('切换空间后拒绝 %s 的旧上下文', async (action) => {
    const { login, invoke, operation, fetcher, session } = setup()
    await login()
    const stale = operation(action)
    session.selectWorkspace('22222222-2222-4222-8222-222222222222')
    expect((await invoke(stale)).ok).toBe(false)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('拒绝非主 frame 及不受信窗口的任务读取', async () => {
    const { login, invoke, operation, fetcher, event } = setup()
    await login()
    const subframe = { ...event, senderFrame: { url: event.senderFrame.url } }
    expect((await invoke(operation('tasks'), subframe)).ok).toBe(false)
    const remoteFrame = { url: 'https://evil.invalid/' }
    expect((await invoke(operation('taskDetail'), { senderFrame: remoteFrame, sender: { mainFrame: remoteFrame } })).ok).toBe(false)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('未知操作仍被拒绝且不访问后台', async () => {
    const { login, invoke, operation, fetcher } = setup()
    await login()
    const result = await invoke(operation('unknown'))
    expect(result.ok).toBe(false)
    expect(result.error.code).toBe('INVALID_REQUEST')
    expect(fetcher).not.toHaveBeenCalled()
  })
})
