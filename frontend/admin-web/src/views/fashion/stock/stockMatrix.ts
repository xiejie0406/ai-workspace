import type { StockRow } from '@/api/fashion/stock'

export function stockFreshness(row: Pick<StockRow, 'availableQty' | 'asOf' | 'stale'>) {
  if (row.availableQty == null) return '未导入，数量未知'
  if (!row.asOf) return '时间未知，待核实'
  return row.stale ? '超时，待核实' : '有效'
}

/** 矩阵保留每条规格事实，不覆盖同尺码的多个规格，也不跨仓合并。 */
export function stockMatrix(rows: StockRow[]) {
  const groups = new Map<string, { key: string; sourceCode: string; mainSkuCode?: string | null; styleCode: string; colorName: string; sizeSystem: string; warehouseCode: string; rows: StockRow[]; sizes: Record<string, StockRow[]> }>()
  for (const row of rows) {
    const key = JSON.stringify([row.sourceCode, row.mainSkuCode ?? '', row.styleCode, row.colorCode, row.sizeSystem, row.warehouseCode])
    const group = groups.get(key) ?? { key, sourceCode: row.sourceCode, mainSkuCode: row.mainSkuCode, styleCode: row.styleCode, colorName: row.colorName, sizeSystem: row.sizeSystem, warehouseCode: row.warehouseCode, rows: [], sizes: {} }
    group.rows.push(row)
    ;(group.sizes[row.sizeCode] ??= []).push(row)
    groups.set(key, group)
  }
  return [...groups.values()]
}
