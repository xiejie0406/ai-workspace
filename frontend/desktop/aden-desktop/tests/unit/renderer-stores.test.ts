// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '../../src/renderer/src/stores/session'
import { useWorkspaceStore } from '../../src/renderer/src/stores/workspace'
import { useTasksStore } from '../../src/renderer/src/stores/tasks'
import { useConnectionStore } from '../../src/renderer/src/stores/connection'
import { router } from '../../src/renderer/src/router'
import { parseCanonicalInt64 } from '../../src/shared/contracts/wire-scalars'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const workspaceId = '11111111-1111-4111-8111-111111111111'
const context = { authenticated: true, workspaceId, sessionEpoch: 1, workspaceEpoch: 2 }

describe('renderer stores', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const session = useSessionStore(); session.phase = 'authenticated'; session.context = { ...context }
  })

  it('finishes login only after the returned default scope bootstrap is usable', async () => {
    let complete!: (value: unknown) => void
    installApi({ authLogin: vi.fn(() => new Promise(resolve => { complete = resolve })) })
    const session = useSessionStore(); session.clear()
    const login = session.login({ username: 'viewer', password: 'secret' })
    expect(session.phase).toBe('loading')
    expect(useWorkspaceStore().selectedId).toBeNull()
    const bootstrap = JSON.parse(readFileSync(resolve('../../../contracts/aden/examples/current/operator/bootstrap-long-max-watermark.json'), 'utf8'))
    bootstrap.tasks.items = []; bootstrap.runners = []; bootstrap.capabilities = []
    complete({ value: { user: {}, roles: ['viewer'], permissions: ['aden:collection:read'], workspace: bootstrap.workspace, bootstrap,
      session: { ...context, workspaceId: bootstrap.workspace.workspaceId } } })
    await login
    expect(session.phase).toBe('authenticated')
    expect(useWorkspaceStore().selectedId).toBe(bootstrap.workspace.workspaceId)
    expect(useTasksStore().items).toEqual([])
    expect(session.permissions).toEqual(['aden:collection:read'])
  })

  it('retains the default-scope error after refreshing captcha and clears protected projections', async () => {
    installApi({ authLogin: vi.fn(async () => { throw new Error('账号业务范围未配置或不可用，请联系管理员。') }),
      authCaptcha: vi.fn(async () => ({ value: { enabled: false, uuid: null, jpegDataUrl: null }, context })) })
    const workspace = useWorkspaceStore(); workspace.selectedId = workspaceId
    useTasksStore().replace([task('1')])
    const session = useSessionStore()
    await expect(session.login({ username: 'viewer', password: 'secret' })).rejects.toThrow(/范围未配置/)
    expect(session.phase).toBe('anonymous')
    expect(session.error).toContain('账号业务范围未配置')
    expect(workspace.selectedId).toBeNull()
    expect(useTasksStore().items).toEqual([])
  })

  it('drops a late response from an old workspace epoch', async () => {
    installApi({
      create: vi.fn(async () => ({ value: { task: task('1'), etag: '"task-v1"', correlationId: 'c' }, context: { ...context, workspaceEpoch: 1 } }))
    })
    const store = useTasksStore()
    await expect(store.create(context, createRequest(), 'retry-key-0001')).rejects.toThrow(/过期/)
    expect(store.items).toEqual([])
  })

  it('refreshes after 412 and requires explicit reconfirmation', async () => {
    const latest = task('2')
    installApi({
      command: vi.fn(async () => { throw Object.assign(new Error('版本冲突'), { status: 412 }) }),
      get: vi.fn(async () => ({ value: { task: latest, etag: '"task-v2"', correlationId: 'c2' }, context }))
    })
    const store = useTasksStore(); store.replace([task('1')]); store.etags[latest.taskId] = '"task-v1"'
    await expect(store.command(context, latest.taskId, { command: 'SUBMIT_FOR_VALIDATION' }, 'retry-key-0002')).rejects.toThrow(/版本冲突/)
    expect(store.byId(latest.taskId)?.version).toBe('2')
    expect(store.conflictTaskId).toBe(latest.taskId)
    expect(store.pending[latest.taskId]).toBeUndefined()
  })

  it('clears all workspace projections before switching', async () => {
    installApi({ select: vi.fn(async () => ({ value: { ...context, workspaceEpoch: 3 }, context: { ...context, workspaceEpoch: 3 } })) })
    const workspace = useWorkspaceStore(); workspace.selectedId = workspaceId
    const tasks = useTasksStore(); tasks.replace([task('1')])
    // bootstrap 未设置，选择在调用 main 前已经清空旧缓存。
    const promise = workspace.select('33333333-3333-4333-8333-333333333333')
    expect(tasks.items).toEqual([])
    await expect(promise).rejects.toBeDefined()
  })

  it('clears renderer authentication before remote logout completes', async () => {
    let completeLogout!: () => void
    installApi({ authLogout: vi.fn(() => new Promise<void>((resolve) => { completeLogout = resolve })) })
    const session = useSessionStore()

    const logout = session.logout()

    expect(session.phase).toBe('anonymous')
    expect(session.context.authenticated).toBe(false)
    completeLogout()
    await logout
  })

  it('reuses the idempotency key only when retrying the same create payload', async () => {
    const create = vi.fn(async (_operation: unknown) => { throw new Error('网络中断') })
    installApi({ create })
    const store = useTasksStore()
    await expect(store.create(context, createRequest())).rejects.toThrow(/网络中断/)
    await expect(store.create(context, createRequest())).rejects.toThrow(/网络中断/)
    await expect(store.create(context, { ...createRequest(), title: '另一任务' })).rejects.toThrow(/网络中断/)

    const calls = create.mock.calls.map(([operation]) => operation as { idempotencyKey: string })
    expect(calls[1].idempotencyKey).toBe(calls[0].idempotencyKey)
    expect(calls[2].idempotencyKey).not.toBe(calls[0].idempotencyKey)
  })

  it('reuses both idempotency key and ETag for the same command retry', async () => {
    const command = vi.fn(async (_operation: unknown) => { throw new Error('响应丢失') })
    installApi({ command })
    const store = useTasksStore(); store.replace([task('1')]); store.etags[task('1').taskId] = '"task-v1"'
    await expect(store.command(context, task('1').taskId, { command: 'SUBMIT_FOR_VALIDATION' })).rejects.toThrow(/响应丢失/)
    store.etags[task('1').taskId] = '"task-v2"'
    await expect(store.command(context, task('1').taskId, { command: 'SUBMIT_FOR_VALIDATION' })).rejects.toThrow(/响应丢失/)

    const calls = command.mock.calls.map(([operation]) => operation as { idempotencyKey: string; etag: string })
    expect(calls[1].idempotencyKey).toBe(calls[0].idempotencyKey)
    expect(calls[1].etag).toBe('"task-v1"')
  })

  it('clears projections and returns to login after an SSE 401', async () => {
    installApi({})
    const workspace = useWorkspaceStore(); workspace.selectedId = workspaceId
    const tasks = useTasksStore(); tasks.replace([task('1')])
    await router.push('/tasks')

    useConnectionStore().applyStatus({ ...context, state: 'UNAUTHENTICATED', reason: 'http-401' })

    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(useSessionStore().phase).toBe('anonymous')
    expect(workspace.selectedId).toBeNull()
    expect(tasks.items).toEqual([])
  })
})

function installApi(overrides: { create?: unknown; command?: unknown; get?: unknown; select?: unknown; authLogout?: unknown; authLogin?: unknown; authCaptcha?: unknown }): void {
  window.adenDesktop = {
    getRuntimeInfo: () => ({ platform: 'win32', versions: { electron: '44', chrome: '1' } }),
    auth: { captcha: overrides.authCaptcha ?? vi.fn(), login: overrides.authLogin ?? vi.fn(), restore: vi.fn(), logout: overrides.authLogout ?? vi.fn() },
    workspace: { list: vi.fn(), select: overrides.select ?? vi.fn(), bootstrap: vi.fn(async () => { throw new Error('bootstrap stopped') }) },
    tasks: { create: overrides.create ?? vi.fn(), get: overrides.get ?? vi.fn(), command: overrides.command ?? vi.fn() },
    events: { ack: vi.fn(), onBatch: vi.fn(() => () => undefined), onStatus: vi.fn(() => () => undefined) }
  } as never
}

function createRequest() {
  return { taskType: 'SYNTHETIC_CORE' as const, capabilityCode: 'CORE' as const, title: '合成任务', input: { fixtureId: 'fixture:test', instruction: '测试' } }
}
function task(version: string) {
  return {
    taskId: '22222222-2222-4222-8222-222222222222', workspaceId, taskType: 'SYNTHETIC_CORE' as const,
    capabilityCode: 'CORE' as const, title: '合成任务', state: 'DRAFT' as const,
    version: parseCanonicalInt64(version), allowedCommands: ['SUBMIT_FOR_VALIDATION' as const], steps: [], reasonCode: null,
    createdAt: '2026-09-13T00:00:00Z', updatedAt: '2026-09-13T00:00:00Z', correlationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  }
}
