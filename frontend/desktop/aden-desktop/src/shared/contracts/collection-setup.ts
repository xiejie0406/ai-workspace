import type { IpcResult } from './desktop-api'

export interface CollectionSetupStatus {
  extensionPath: string
  extensionVersion: string | null
  extensionId: string
  extensionReady: boolean
  hostPath: string
  hostReady: boolean
  registrationReady: boolean
  registrationKind: 'current' | 'compatible' | 'unavailable'
  registeredHostPath: string | null
  chromePath: string | null
  problems: string[]
  checkedAt?: string
}
export interface CollectionSetupOpened {
  status: CollectionSetupStatus
  folderOpened: boolean
  chromeOpened: boolean
  messages: string[]
}
export interface CollectionSetupApi {
  status(): Promise<IpcResult<CollectionSetupStatus>>
  openInstall(): Promise<IpcResult<CollectionSetupOpened>>
  copyDirectory(): Promise<IpcResult<CollectionDirectoryCopied>>
}

export interface CollectionDirectoryCopied {
  copied: true
  path: string
  status: CollectionSetupStatus
}
