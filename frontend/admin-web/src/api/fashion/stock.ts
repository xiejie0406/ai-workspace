import { fashionRequest, type FashionQuery } from './client'
import type { RuoYiResult } from './types'

export interface StockRow {
  productId: string
  sourceCode: string
  styleCode: string
  skuCode: string
  name: string
  colorCode: string
  colorName: string
  sizeCode: string
  sizeSystem: string
  warehouseCode: string
  availableQty: number | null
  stale: boolean
  asOf: string | null
  batchId: string | null
  mainSkuCode?: string | null
  unit?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
}
export interface StockQuery extends FashionQuery {
  warehouseCode: string
  sourceCode: string
  keyword: string
  mode: string
  page: number
  pageSize: number
}
export const listStocks = (params: StockQuery, signal?: AbortSignal) =>
  fashionRequest<RuoYiResult<{ items: StockRow[]; total: number; freshnessHours: number }>>({ path: 'stocks', params, signal })
