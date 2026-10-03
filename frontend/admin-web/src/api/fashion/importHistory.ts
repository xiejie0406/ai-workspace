import { fashionRequest } from './client'
import type { RuoYiResult } from './types'
export type ImportHistoryKind = 'product' | 'price' | 'stock'
export interface ImportHistoryRow {
  batchId: string; batchNo: string; importType: ImportHistoryKind; operationType: string
  fileName: string | null; sourceCode: string; warehouseCode: string | null
  status: string; actualCount: number; expectedCount: number; errorCount: number
  asOf: string; createTime: string; createdBy: string | null
  validatedAt: string | null; publishedAt: string | null; publishedBy: string | null
  sourceBatchId: string | null; errorMessage: string | null; scope: string | null
  changeCounts: Record<string, number>
}
export const listImportHistory = (kind: ImportHistoryKind, page: number) =>
  fashionRequest<RuoYiResult<{items: ImportHistoryRow[]; total: number}>>({ path: `imports/${{product:'products',price:'prices',stock:'stocks'}[kind]}/history`, params:{ page, pageSize:20 } })
