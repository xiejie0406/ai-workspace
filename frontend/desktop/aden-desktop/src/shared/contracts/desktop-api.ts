import type { EventBatch, EpochContext } from './operator-events'
import type { CollectionApi } from './collection'
import type {
  CreateTaskRequest,
  OperatorTaskCommandRequest,
  TaskSnapshot,
  WorkspaceListResponse
} from '../generated/operator-contracts'

export const DESKTOP_IPC = Object.freeze({
  captcha: 'aden:auth:captcha',
  login: 'aden:auth:login',
  restore: 'aden:auth:restore',
  logout: 'aden:auth:logout',
  listWorkspaces: 'aden:workspace:list',
  selectWorkspace: 'aden:workspace:select',
  bootstrapWorkspace: 'aden:workspace:bootstrap',
  eventAck: 'aden:events:ack',
  createTask: 'aden:tasks:create',
  getTask: 'aden:tasks:get',
  commandTask: 'aden:tasks:command',
  eventBatch: 'aden:events:batch',
  streamStatus: 'aden:events:status'
})

export interface CaptchaChallenge {
  readonly enabled: boolean
  readonly uuid: string | null
  readonly jpegDataUrl: string | null
}

export interface LoginInput {
  readonly username: string
  readonly password: string
  readonly code?: string
  readonly uuid?: string
}

export interface SessionContext {
  readonly authenticated: boolean
  readonly sessionEpoch: number
  readonly workspaceEpoch: number
  readonly workspaceId: string | null
}

export interface AuthenticatedUser {
  readonly user: Readonly<Record<string, unknown>>
  readonly roles: readonly string[]
  readonly permissions: readonly string[]
  readonly session: SessionContext
}

export interface StreamStatus extends EpochContext {
  readonly state: 'CONNECTING' | 'LIVE' | 'RECONNECTING' | 'DEGRADED' | 'STOPPED' | 'UNAUTHENTICATED'
  readonly reason?: string
}

export interface IpcResult<T> {
  readonly value: T
  readonly context: SessionContext
}

export interface AdenRuntimeInfo {
  readonly platform: string
  readonly versions: { readonly electron: string; readonly chrome: string }
}

export interface WorkspaceRequestContext extends EpochContext {}

export interface EventAck extends WorkspaceRequestContext {
  readonly batchId: string
  readonly lastCursor: string
}

export interface TaskOperationContext extends WorkspaceRequestContext {
  readonly taskId?: string
}

export interface CreateTaskOperation extends WorkspaceRequestContext {
  readonly idempotencyKey: string
  readonly request: CreateTaskRequest
}

export interface GetTaskOperation extends WorkspaceRequestContext {
  readonly taskId: string
}

export interface CommandTaskOperation extends GetTaskOperation {
  readonly idempotencyKey: string
  readonly etag: string
  readonly command: OperatorTaskCommandRequest
}

export interface TaskMutationResult {
  readonly task: TaskSnapshot
  readonly etag: string
  readonly correlationId: string
}

export interface AdenDesktopApi {
  readonly collection: CollectionApi
  readonly getRuntimeInfo: () => AdenRuntimeInfo
  readonly auth: {
    readonly captcha: () => Promise<IpcResult<CaptchaChallenge>>
    readonly login: (input: LoginInput) => Promise<IpcResult<AuthenticatedUser>>
    readonly restore: () => Promise<IpcResult<AuthenticatedUser>>
    readonly logout: () => Promise<IpcResult<SessionContext>>
  }
  readonly workspace: {
    readonly list: () => Promise<IpcResult<WorkspaceListResponse>>
    readonly select: (workspaceId: string) => Promise<IpcResult<SessionContext>>
    readonly bootstrap: (context: WorkspaceRequestContext) => Promise<IpcResult<unknown>>
  }
  readonly tasks: {
    readonly create: (operation: CreateTaskOperation) => Promise<IpcResult<TaskMutationResult>>
    readonly get: (operation: GetTaskOperation) => Promise<IpcResult<TaskMutationResult>>
    readonly command: (operation: CommandTaskOperation) => Promise<IpcResult<TaskMutationResult>>
  }
  readonly events: {
    readonly ack: (ack: EventAck) => Promise<IpcResult<boolean>>
    readonly onBatch: (listener: (batch: EventBatch) => void) => () => void
    readonly onStatus: (listener: (status: StreamStatus) => void) => () => void
  }
}
