import type { EpochContext } from './operator-events'

export interface CollectionImage { imageId: string; group: string; order: number; status: string; assetId?: string; error?: string }
export interface CollectionSnapshot {
  snapshotId: string; coreTaskId?: string; generation: number; source: string; createdAt: string; status: string
  fields: Record<string, unknown>
  blocks: { type: string; text?: string; imageId?: string }[]
  images: CollectionImage[]
}
export interface CollectionCuration {
  snapshotId?: string
  revision: number; notes: string; tags: string[]; overrides: Record<string, string>
  removedImageIds: string[]; mainImageId: string | null; imageOrder: string[]
}
export interface CollectionItem { itemId: string; title: string; platform: string; sku?: string; snapshotId: string; version: number; deleted: boolean; updatedAt: string; status: string; generation: number; price?: string; priceText?: string; specification?: string; thumbnailAssetId?: string; imageSaved?: number; imageTotal?: number }
export interface CollectionVersionSummary { snapshotId: string; source: string; createdAt: string; status: string; assetManifestVersion?: number }
export interface CollectionVersionPage { items: CollectionVersionSummary[]; total: number; page: number; pageSize: number }
export interface CollectionDetail extends CollectionItem { snapshots: CollectionSnapshot[]; currentSnapshot: CollectionSnapshot; snapshotCount: number; curation: CollectionCuration }
export interface CollectionPage { items: CollectionItem[]; total: number; page: number; pageSize: number }
export interface CollectionBatch { results: { itemId: string; success: boolean; error?: string }[] }
export interface CollectionTaskSummary {
  taskId: string; captureId: string; snapshotId: string; itemId: string; title: string; sku?: string
  platform: string; source: string; sourceUrl?: string; specification?: string; createdAt: string
  taskState: string; status: 'SAVING' | 'SAVED' | 'PARTIAL'; contentSaved: boolean
  imageSaved: number; imageFailed: number; imageTotal: number; blockCount: number; deleted: boolean
  errorCode?: string; errorMessage?: string
}
export interface CollectionTaskDetail extends CollectionTaskSummary {
  fields: Record<string, unknown>; blocks: CollectionSnapshot['blocks']; images: CollectionImage[]
  parserVersion?: string; completeness?: string
}
export interface CollectionTaskPage { items: CollectionTaskSummary[]; total: number; page: number; pageSize: number }
export interface CollectionOverview {
  savedProductCount: number; todayCaptureCount: number; pendingTaskCount: number
  recentItems: CollectionItem[]; recentTasks: CollectionTaskSummary[]; pendingTasks: CollectionTaskSummary[]
  updatedAt: string; timezone: 'Asia/Shanghai'
}
export interface CollectionExportRecord {
  exportId: string; fileName: string; mimeType: string; size: number; format: 'XLSX' | 'ZIP'
  status: 'COMPLETED' | 'UNAVAILABLE'; createdAt: string; itemCount: number; imageSaved: number; imageMissing: number
  downloadAvailable: boolean; unavailableReason?: string
}
export interface CollectionExportPage { items: CollectionExportRecord[]; total: number; page: number; pageSize: number }
export interface CollectionExportDetail extends CollectionExportRecord { selections: Record<string, unknown>[] }
export interface CollectionLibraryTarget { view?: 'task'; taskId?: string }
export type CollectionAction = 'list' | 'detail' | 'versions' | 'snapshot' | 'tasks' | 'taskDetail' | 'create' | 'curation' | 'trash' | 'restore' | 'image' | 'upload' | 'export' | 'overview' | 'exports' | 'exportDetail' | 'downloadExport'
export interface CollectionOperation extends EpochContext { action: CollectionAction; input: Record<string, unknown> }
export interface CollectionApi {
  onOpenLibrary(listener: (target?: CollectionLibraryTarget) => void): () => void
  execute<T = unknown>(operation: CollectionOperation): Promise<{ value: T; context: { workspaceId: string | null; sessionEpoch: number; workspaceEpoch: number } }>
}
