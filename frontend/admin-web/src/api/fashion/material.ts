import { fashionRequest } from './client'
import type { ImportBatchView, RuoYiResult } from './types'

export interface ImageMapping {
  filename: string
  skuCode?: string
  styleCode?: string
  colorCode?: string
  usage: 'main' | 'detail'
  main: boolean
  colorConfirmed: boolean
  sourceType: 'jd_plugin' | 'manual'
  jdId?: string
  sourceUrl?: string
  capturedAt: string
  allowAi: boolean
  allowProposal: boolean
  allowEcommerce: boolean
}

export function previewMaterials(
  files: File[], sourceCode: string, asOf: string, mappings: ImageMapping[]
) {
  const body = new FormData()
  for (const file of files) body.append('files', file)
  body.append('sourceCode', sourceCode)
  body.append('asOf', asOf)
  body.append('mappings', JSON.stringify(mappings))
  return fashionRequest<RuoYiResult<ImportBatchView>, FormData>({
    path: 'materials/preview', method: 'post', body, timeoutMs: 120_000
  })
}

export function getMaterialImport(batchId: string) {
  return fashionRequest<RuoYiResult<ImportBatchView>>({ path: `materials/${batchId}` })
}

export function confirmMaterials(batchId: string, rowVersion: number) {
  return fashionRequest<RuoYiResult<ImportBatchView>, { rowVersion: number }>({
    path: `materials/${batchId}/confirm`, method: 'post', body: { rowVersion }, timeoutMs: 120_000
  })
}

/** 只访问已绑定到商品且服务端允许查看的审核图片。 */
export function getProductImageContent(productId: string, imageId: string) {
  return fashionRequest<Blob>({ path: `products/${productId}/images/${imageId}/content`, responseType: 'blob' })
}
