import type { IpcMain, WebContents } from 'electron'
import type { RendererTrust } from '../config/trusted-origins'
import { AdenAuthService, type LoginInput } from '../auth/auth-service'
import { SessionController } from '../auth/session-controller'
import { AdenApiClient, isRecord } from '../transport/api-client'
import { AdenSseCoordinator } from '../transport/sse-client'
import { parseCanonicalInt64 } from '../../shared/contracts/wire-scalars'
import type { EpochContext } from '../../shared/contracts/operator-events'
import { parseTaskSnapshot, parseWorkspaceBootstrap, parseWorkspaceList } from '../../shared/contracts/operator-bootstrap'
import { parseOperatorTaskCommand } from '../../shared/contracts/operator-task-command'
import { DESKTOP_IPC as IPC } from '../../shared/contracts/desktop-api'
import { assertPlainPayload, assertTrustedSender } from './validate-sender'
import { serializeTransportError } from '../transport/error-mapper'
import { CollectionService } from '../collection/collection-service'
import { CollectionSetupService } from '../collection/collection-setup'
import type { CollectionAction, CollectionOperation } from '../../shared/contracts/collection'

const BOOTSTRAP_PERMISSIONS = ['aden:task:list', 'aden:runner:list', 'aden:capability:list', 'aden:event:subscribe']

export function registerDesktopIpcHandlers(options: {
  ipcMain: IpcMain
  trust: RendererTrust
  webContents: WebContents
  session: SessionController
  api: AdenApiClient
  auth: AdenAuthService
  sse: AdenSseCoordinator
}): () => void {
  const { ipcMain, trust, session, api, auth, sse } = options
  const permissions = new Set<string>()
  const channels: string[] = []
  const collection = new CollectionService(api, session)
  const collectionSetup = new CollectionSetupService()
  const handle = (channel: string, handler: (payload: unknown) => Promise<unknown> | unknown): void => {
    channels.push(channel)
    ipcMain.handle(channel, async (event, payload) => {
      try {
        assertTrustedSender(event, trust)
        const safePayload = payload ?? null
        assertPlainPayload(safePayload)
        const value = await handler(safePayload)
        return { ok: true, value, context: session.context() }
      } catch (error) {
        return { ok: false, error: serializeTransportError(error), context: session.context() }
      }
    })
  }

  handle(IPC.collectionSetup, (payload) => {
    if (!isRecord(payload)) throw new TypeError('扩展安装操作参数非法')
    exactKeys(payload, ['action'])
    if (payload.action === 'status') return collectionSetup.status()
    if (payload.action === 'open-install') return collectionSetup.openInstall()
    if (payload.action === 'copy-directory') return collectionSetup.copyDirectory()
    throw new TypeError('未知扩展安装操作')
  })
  handle(IPC.collection, (payload) => {
    if (!isRecord(payload) || typeof payload.action !== 'string') throw new TypeError('采集库参数非法')
    exactKeys(payload, ['workspaceId', 'sessionEpoch', 'workspaceEpoch', 'action', 'input'])
    const permission: Record<CollectionAction, string> = { list: 'read', detail: 'read', versions: 'read', snapshot: 'read', tasks: 'read', taskDetail: 'read', image: 'read', create: 'create', curation: 'edit', upload: 'edit', trash: 'delete', restore: 'restore', export: 'export', overview: 'read', exports: 'read', exportDetail: 'read', downloadExport: 'export' }
    if (!Object.hasOwn(permission, payload.action)) throw new TypeError('未知采集库操作')
    requirePermissions(permissions, [`aden:collection:${permission[payload.action as CollectionAction]}`])
    assertContext(payload, session, stringField(payload, 'workspaceId', 36))
    return collection.execute(payload as unknown as CollectionOperation)
  })
  handle(IPC.captcha, () => auth.captcha())
  handle(IPC.login, async (payload) => {
    const input = parseLogin(payload)
    sse.stop(); permissions.clear()
    const result = await auth.login(input)
    permissions.clear()
    result.permissions.forEach((permission) => permissions.add(permission))
    if (permissions.has('*:*:*') || permissions.has('aden:event:subscribe')) {
      void sse.start(result.workspace.workspaceId, result.bootstrap.streamCursor, parseCanonicalInt64(result.bootstrap.streamWatermark))
    }
    return result
  })
  handle(IPC.restore, async () => {
    const restoringEpoch = session.context().sessionEpoch
    try {
      const result = await auth.getInfo()
      permissions.clear()
      result.permissions.forEach((permission) => permissions.add(permission))
      if (permissions.has('*:*:*') || permissions.has('aden:event:subscribe')) {
        void sse.start(result.workspace.workspaceId, result.bootstrap.streamCursor, parseCanonicalInt64(result.bootstrap.streamWatermark))
      }
      return result
    } catch (error) {
      if (session.context().sessionEpoch === restoringEpoch) { sse.stop(); permissions.clear(); session.clear() }
      throw error
    }
  })
  handle(IPC.logout, async () => {
    sse.stop()
    permissions.clear()
    return auth.logout()
  })
  handle(IPC.selectWorkspace, (payload) => {
    if (!isRecord(payload)) throw new TypeError('Workspace 选择参数必须是 object')
    exactKeys(payload, ['workspaceId'])
    const workspaceId = stringField(payload, 'workspaceId', 36)
    sse.stop()
    return session.selectWorkspace(workspaceId)
  })
  handle(IPC.listWorkspaces, async () => {
    requirePermissions(permissions, ['aden:workspace:list'])
    const response = await api.request<unknown>({ path: '/api/v1/aden/workspaces' })
    return parseWorkspaceList(response.data)
  })
  handle(IPC.bootstrapWorkspace, async (payload) => {
    if (!permissions.has('aden:collection:read')) requirePermissions(permissions, BOOTSTRAP_PERMISSIONS)
    const workspaceId = stringField(payload, 'workspaceId', 36)
    exactKeys(payload as Record<string, unknown>, ['workspaceId', 'sessionEpoch', 'workspaceEpoch'])
    assertContext(payload, session, workspaceId)
    const response = await api.request<unknown>({
      path: `/api/v1/aden/workspaces/${encodeURIComponent(workspaceId)}/bootstrap?taskLimit=50`
    })
    const bootstrap = parseWorkspaceBootstrap(response.data, permissions.has('aden:collection:read'))
    const cursor = bootstrap.streamCursor
    const watermark = parseCanonicalInt64(bootstrap.streamWatermark)
    if (permissions.has('*:*:*') || permissions.has('aden:event:subscribe')) void sse.start(workspaceId, cursor, watermark)
    return { data: bootstrap, context: response.context, correlationId: response.correlationId }
  })
  handle(IPC.eventAck, (payload) => {
    if (!isRecord(payload)) throw new TypeError('事件 ack 必须是 object')
    exactKeys(payload, ['workspaceId', 'sessionEpoch', 'workspaceEpoch', 'batchId', 'lastCursor'])
    const context = parseEpochContext(payload)
    return sse.ack(stringField(payload, 'batchId', 64), stringField(payload, 'lastCursor', 768), context)
  })
  handle(IPC.createTask, async (payload) => {
    requirePermissions(permissions, ['aden:task:create'])
    const context = parseTaskContext(payload)
    exactKeys(payload as Record<string, unknown>, ['workspaceId', 'sessionEpoch', 'workspaceEpoch', 'idempotencyKey', 'request'])
    assertContext(payload, session, context.workspaceId)
    const idempotencyKey = idempotencyField(payload)
    const request = createTaskRequest(payload)
    const response = await api.request<unknown>({
      path: `/api/v1/aden/workspaces/${encodeURIComponent(context.workspaceId)}/tasks`,
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: request
    })
    return taskMutation(response.data, response.headers, response.correlationId)
  })
  handle(IPC.getTask, async (payload) => {
    requirePermissions(permissions, ['aden:task:query'])
    const context = parseTaskContext(payload)
    exactKeys(payload as Record<string, unknown>, ['workspaceId', 'sessionEpoch', 'workspaceEpoch', 'taskId'])
    assertContext(payload, session, context.workspaceId)
    const taskId = uuidField(payload, 'taskId')
    const response = await api.request<unknown>({
      path: `/api/v1/aden/workspaces/${encodeURIComponent(context.workspaceId)}/tasks/${encodeURIComponent(taskId)}`
    })
    return taskMutation(response.data, response.headers, response.correlationId)
  })
  handle(IPC.commandTask, async (payload) => {
    const context = parseTaskContext(payload)
    exactKeys(payload as Record<string, unknown>, ['workspaceId', 'sessionEpoch', 'workspaceEpoch', 'taskId', 'idempotencyKey', 'etag', 'command'])
    assertContext(payload, session, context.workspaceId)
    const taskId = uuidField(payload, 'taskId')
    const idempotencyKey = idempotencyField(payload)
    const etag = stringField(payload, 'etag', 256)
    const command = commandRequest(payload)
    requirePermissions(permissions, [command.command === 'REQUEST_CANCEL' ? 'aden:task:cancel' : 'aden:task:command'])
    const response = await api.request<unknown>({
      path: `/api/v1/aden/workspaces/${encodeURIComponent(context.workspaceId)}/tasks/${encodeURIComponent(taskId)}/commands`,
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': etag },
      body: command
    })
    return taskMutation(response.data, response.headers, response.correlationId)
  })

  return () => {
    sse.stop()
    channels.forEach((channel) => ipcMain.removeHandler(channel))
  }
}

function parseLogin(value: unknown): LoginInput {
  if (!isRecord(value)) throw new TypeError('登录参数必须是 object')
  exactKeys(value, ['username', 'password', 'code', 'uuid'])
  return {
    username: stringField(value, 'username', 64),
    password: stringField(value, 'password', 256),
    ...(typeof value.code === 'string' ? { code: value.code } : {}),
    ...(typeof value.uuid === 'string' ? { uuid: value.uuid } : {})
  }
}

function parseTaskContext(value: unknown): EpochContext {
  return parseEpochContext(value)
}

function createTaskRequest(value: unknown): Record<string, unknown> {
  if (!isRecord(value) || !isRecord(value.request)) throw new TypeError('request 必须是 object')
  const request = value.request
  if (request.taskType !== 'SYNTHETIC_CORE' || request.capabilityCode !== 'CORE'
      || typeof request.title !== 'string' || request.title.length < 1 || request.title.length > 120
      || !isRecord(request.input)) throw new TypeError('CreateTaskRequest 非法')
  const input = request.input
  if (typeof input.fixtureId !== 'string' || !/^fixture:[a-z0-9][a-z0-9._-]{2,63}$/.test(input.fixtureId)
      || typeof input.instruction !== 'string' || input.instruction.length < 1 || input.instruction.length > 1000
      || (input.expectedOutcome !== undefined && !['SUCCEED', 'FAIL_VALIDATION', 'CANCEL_AT_SAFE_POINT'].includes(input.expectedOutcome as string))) {
    throw new TypeError('SyntheticTaskInput 非法')
  }
  exactKeys(request, ['taskType', 'capabilityCode', 'title', 'input'])
  exactKeys(input, ['fixtureId', 'instruction', 'expectedOutcome'])
  return request
}

function commandRequest(value: unknown): Record<string, unknown> & { command: string } {
  if (!isRecord(value) || !isRecord(value.command)) throw new TypeError('command 必须是 object')
  const request = value.command
  const command = parseOperatorTaskCommand(request.command)
  if (command === 'SUBMIT_FOR_VALIDATION') {
    exactKeys(request, ['command'])
  } else {
    exactKeys(request, ['command', 'reasonCode'])
    if (typeof request.reasonCode !== 'string' || !/^[A-Z][A-Z0-9_]{2,63}$/.test(request.reasonCode)) {
      throw new TypeError('REQUEST_CANCEL reasonCode 非法')
    }
  }
  return request as Record<string, unknown> & { command: string }
}

function taskMutation(value: unknown, headers: Headers, correlationId: string): Record<string, unknown> {
  const etag = headers.get('etag')
  if (!etag || etag.length > 256) throw new TypeError('Task 响应缺少 ETag')
  return { task: parseTaskSnapshot(value), etag, correlationId }
}

function idempotencyField(value: unknown): string {
  const key = stringField(value, 'idempotencyKey', 128)
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/.test(key)) throw new TypeError('idempotencyKey 非法')
  return key
}

function uuidField(value: unknown, field: string): string {
  const id = stringField(value, field, 36)
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id)) {
    throw new TypeError(`${field} 必须是 canonical UUID v4`)
  }
  return id
}

function exactKeys(value: Record<string, unknown>, allowed: readonly string[]): void {
  const fields = new Set(allowed)
  if (Object.keys(value).some((key) => !fields.has(key))) throw new TypeError('请求包含未知字段')
}

function parseEpochContext(value: unknown): EpochContext {
  return {
    workspaceId: stringField(value, 'workspaceId', 36),
    sessionEpoch: integerField(value, 'sessionEpoch'),
    workspaceEpoch: integerField(value, 'workspaceEpoch')
  }
}

function assertContext(value: unknown, session: SessionController, workspaceId: string): void {
  const supplied = parseEpochContext(value)
  const current = session.context()
  if (supplied.workspaceId !== workspaceId || !session.isCurrent({ ...current, workspaceId })) {
    throw new Error('IPC workspace 上下文已过期')
  }
  if (supplied.sessionEpoch !== current.sessionEpoch || supplied.workspaceEpoch !== current.workspaceEpoch) {
    throw new Error('IPC epoch 已过期')
  }
}

function requirePermissions(actual: ReadonlySet<string>, required: readonly string[]): void {
  if (required.some((permission) => !actual.has(permission) && !actual.has('*:*:*'))) {
    throw new Error('当前用户缺少桌面端所需 capability')
  }
}

function stringField(value: unknown, field: string, max: number): string {
  if (!isRecord(value) || typeof value[field] !== 'string' || value[field].length < 1 || value[field].length > max) {
    throw new TypeError(`${field} 必须是有效字符串`)
  }
  return value[field] as string
}

function integerField(value: unknown, field: string): number {
  if (!isRecord(value) || !Number.isSafeInteger(value[field]) || (value[field] as number) < 0) {
    throw new TypeError(`${field} 必须是非负安全整数`)
  }
  return value[field] as number
}
