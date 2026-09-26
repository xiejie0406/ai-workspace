import { FASHION_API_PREFIX, fashionRequest } from './client'
import type { ImportBatchView, RuoYiResult } from './types'

export interface ProductImportPreviewOptions {
  file: File
  sourceCode: string
  asOf: string
  mapping?: Record<string, string>
  clearFields?: string[]
}

export function previewProductImport(options: ProductImportPreviewOptions) {
  const body = new FormData()
  body.append('file', options.file)
  body.append('sourceCode', options.sourceCode)
  body.append('asOf', options.asOf)
  if (options.mapping) body.append('mapping', JSON.stringify(options.mapping))
  if (options.clearFields?.length) body.append('clearFields', options.clearFields.join(','))
  return fashionRequest<RuoYiResult<ImportBatchView>, FormData>({
    path: 'imports/products/preview', method: 'post', body, timeoutMs: 120_000
  })
}

export function getProductImport(batchId: string) {
  return fashionRequest<RuoYiResult<ImportBatchView>>({ path: `imports/products/${batchId}` })
}

export function publishProductImport(batchId: string, rowVersion: number) {
  return fashionRequest<RuoYiResult<ImportBatchView>, { rowVersion: number }>({
    path: `imports/products/${batchId}/publish`, method: 'post', body: { rowVersion }, timeoutMs: 120_000
  })
}

export function productTemplateUrl() {
  const base = String(import.meta.env.VITE_APP_BASE_API ?? '').replace(/\/$/, '')
  return `${base}${FASHION_API_PREFIX}/imports/products/template`
}


export function getProductFields() {
  return fashionRequest<RuoYiResult<{ templateVersion: string; mapping: Record<string,string>; attributes: import('./types').ProductAttributeField[] }>>({ path: 'imports/products/fields' })
}
