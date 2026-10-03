import { ipcRenderer, type IpcRendererEvent } from 'electron'
import type { CollectionLibraryTarget, CollectionOperation } from '../shared/contracts/collection'
import type { CollectionDirectoryCopied, CollectionSetupStatus, CollectionSetupOpened } from '../shared/contracts/collection-setup'
import {
  DESKTOP_IPC as IPC,
  type AdenDesktopApi,
  type AdenRuntimeInfo,
  type AuthenticatedUser,
  type CaptchaChallenge,
  type CommandTaskOperation,
  type CreateTaskOperation,
  type EventAck,
  type IpcResult,
  type LoginInput,
  type GetTaskOperation,
  type SessionContext,
  type StreamStatus,
  type TaskMutationResult,
  type WorkspaceRequestContext
} from '../shared/contracts/desktop-api'
import type { EventBatch } from '../shared/contracts/operator-events'
import type { WorkspaceListResponse } from '../shared/generated/operator-contracts'

export interface DesktopErrorShape {
  readonly code: string
  readonly message: string
  readonly status: number | null
  readonly correlationId: string | null
  readonly retryable: boolean
}

interface IpcEnvelope<T> {
  readonly ok: boolean
  readonly value?: T
  readonly error?: DesktopErrorShape
  readonly context: SessionContext
}

export function createPreloadApi(runtime: AdenRuntimeInfo): AdenDesktopApi {
  return Object.freeze({
    getRuntimeInfo: () => runtime,
    collectionSetup: Object.freeze({
      status: () => invoke<CollectionSetupStatus>(IPC.collectionSetup, { action: 'status' }),
      openInstall: () => invoke<CollectionSetupOpened>(IPC.collectionSetup, { action: 'open-install' }),
      copyDirectory: () => invoke<CollectionDirectoryCopied>(IPC.collectionSetup, { action: 'copy-directory' })
    }),
    collection: Object.freeze({ execute: <T>(operation: CollectionOperation) => invoke<T>(IPC.collection, operation), onOpenLibrary: (listener: (target?: CollectionLibraryTarget) => void) => subscribe('aden:collection:open-library', listener) }),
    auth: Object.freeze({
      captcha: () => invoke<CaptchaChallenge>(IPC.captcha, null),
      login: (input: LoginInput) => invoke<AuthenticatedUser>(IPC.login, input),
      restore: () => invoke<AuthenticatedUser>(IPC.restore, null),
      logout: () => invoke<SessionContext>(IPC.logout, null)
    }),
    workspace: Object.freeze({
      list: () => invoke<WorkspaceListResponse>(IPC.listWorkspaces, null),
      select: (workspaceId: string) => invoke<SessionContext>(IPC.selectWorkspace, { workspaceId }),
      bootstrap: (context: WorkspaceRequestContext) => invoke<unknown>(IPC.bootstrapWorkspace, context)
    }),
    tasks: Object.freeze({
      create: (operation: CreateTaskOperation) => invoke<TaskMutationResult>(IPC.createTask, operation),
      get: (operation: GetTaskOperation) => invoke<TaskMutationResult>(IPC.getTask, operation),
      command: (operation: CommandTaskOperation) => invoke<TaskMutationResult>(IPC.commandTask, operation)
    }),
    events: Object.freeze({
      ack: (ack: EventAck) => invoke<boolean>(IPC.eventAck, ack),
      onBatch: (listener: (batch: EventBatch) => void) => subscribe(IPC.eventBatch, listener),
      onStatus: (listener: (status: StreamStatus) => void) => subscribe(IPC.streamStatus, listener)
    })
  })
}

async function invoke<T>(channel: string, payload: unknown): Promise<IpcResult<T>> {
  const envelope = await ipcRenderer.invoke(channel, payload) as IpcEnvelope<T>
  if (!envelope.ok) {
    const shape = envelope.error ?? {
      code: 'IPC_ERROR', message: '桌面端调用失败', status: null, correlationId: null, retryable: false
    }
    throw Object.assign(new Error(shape.message), shape)
  }
  return { value: envelope.value as T, context: envelope.context }
}

function subscribe<T>(channel: string, listener: (value: T) => void): () => void {
  const wrapped = (_event: IpcRendererEvent, value: T): void => listener(value)
  ipcRenderer.on(channel, wrapped)
  return () => ipcRenderer.removeListener(channel, wrapped)
}
