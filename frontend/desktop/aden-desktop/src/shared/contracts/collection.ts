import { PRODUCT_IMPORT_CONTRACT } from '../generated/product-import-contract'
import type { IpcResult, WorkspaceRequestContext } from './desktop-api'

export interface CollectionRequest extends WorkspaceRequestContext {
  urls?: string
  entryId?: string
  skuId?: string
  values?: ProductValues
  format?: 'xlsx' | 'sources'
}
export interface CollectionFileResult { cancelled: boolean; filename?: string; snapshot?: CollectionSnapshot }
export interface CollectionApi {
  start(input: CollectionRequest): Promise<IpcResult<CollectionSnapshot>>
  snapshot(input: CollectionRequest): Promise<IpcResult<CollectionSnapshot>>
  cancel(input: CollectionRequest): Promise<IpcResult<CollectionSnapshot>>
  retry(input: CollectionRequest): Promise<IpcResult<CollectionSnapshot>>
  update(input: CollectionRequest): Promise<IpcResult<CollectionSnapshot>>
  save(input: CollectionRequest): Promise<IpcResult<CollectionFileResult>>
  load(input: CollectionRequest): Promise<IpcResult<CollectionFileResult>>
  export(input: CollectionRequest): Promise<IpcResult<CollectionFileResult>>
}

export const COLLECTION_FIELDS = [...PRODUCT_IMPORT_CONTRACT.baseFields, ...PRODUCT_IMPORT_CONTRACT.extensionFields]
export type ProductField = typeof COLLECTION_FIELDS[number]['key']
export type ProductValues = Partial<Record<ProductField, string>>
export type CollectionState = 'queued' | 'reading' | 'collected' | 'failed' | 'cancelled'
export interface CollectedSku {
  id: string
  original: ProductValues
  values: ProductValues
  availability: 'in_stock' | 'out_of_stock' | 'unknown'
  evidence: string
}
export interface CollectionSource {
  originalUrl: string
  url: string
  collectedAt: string
  adapter: string
  evidenceUrl: string
  sha256: string
  dataKind: 'public_observation'
}
export interface CollectionEntry {
  id: string
  url: string
  originalUrl?: string
  state: CollectionState
  message: string
  source?: CollectionSource
  skus: CollectedSku[]
}
export interface CollectionSnapshot {
  schemaVersion: 'aden-collection-1'
  entries: CollectionEntry[]
  running: boolean
}
export const COLLECTION_IPC = {
  start: 'aden:collection:start', snapshot: 'aden:collection:snapshot', cancel: 'aden:collection:cancel',
  retry: 'aden:collection:retry', update: 'aden:collection:update', save: 'aden:collection:save',
  load: 'aden:collection:load', export: 'aden:collection:export'
} as const
export function missingFields(values: ProductValues): string[] {
  return COLLECTION_FIELDS.filter(f => f.required && !values[f.key]?.trim()).map(f => f.label)
}
export function validateValues(value: unknown): ProductValues {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('商品字段必须是对象')
  const result: ProductValues = {}
  for (const [key, raw] of Object.entries(value)) {
    const field = COLLECTION_FIELDS.find(f => f.key === key)
    if (!field || typeof raw !== 'string') throw new TypeError('商品包含未知字段或非文本值')
    const text = raw.trim()
    const maximum = 'maxLength' in field ? field.maxLength : 2000
    if (text.length > maximum || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(text)) throw new TypeError(`${field.label}长度或内容无效`)
    if (text && (key.endsWith('Url') || key === 'jdUrl' || key === 'sourceDetailImageUrls')) {
      const urls = key === 'sourceDetailImageUrls' ? text.split(/\r?\n/) : [text]
      if (urls.length > PRODUCT_IMPORT_CONTRACT.rules.detailImages.maxItems) throw new TypeError('细节图最多20条')
      for (const link of urls) {
        const u = new URL(link)
        if (u.protocol !== 'https:' || !u.hostname || u.username || u.password || link.length > 2048) throw new TypeError(`${field.label}需要HTTPS链接`)
      }
    }
    if (text && ['collectedAt', 'observedAt'].includes(key)
      && (!/^\d{4}-\d\d-\d\dT.+(?:Z|[+-]\d\d:\d\d)$/.test(text) || !Number.isFinite(Date.parse(text)))) throw new TypeError('时间必须带时区')
    if (text && key === 'observedPrice' && !/^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(text)) throw new TypeError('观察价最多两位小数')
    if (text && key === 'observedCurrency' && !/^[A-Z]{3}$/.test(text)) throw new TypeError('币种须为三位大写字母')
    if (text && key === 'listingYear' && !/^[12][0-9]{3}$/.test(text)) throw new TypeError('年份格式无效')
    if (text && key === 'sizeSystem' && !(PRODUCT_IMPORT_CONTRACT.rules.sizeSystems as readonly string[]).includes(text)) throw new TypeError('尺码制式不受支持')
    result[field.key] = text
  }
  return result
}
