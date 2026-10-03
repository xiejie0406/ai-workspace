import type { ImportDetail } from '@/api/fashion/types'

export interface ProductFieldDiff { key: string; before: unknown; after: unknown; changed: boolean; cleared: boolean }
function flatten(values?: Record<string, unknown>) {
  const result: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(values ?? {})) {
    if (key === 'attributes' && value && typeof value === 'object' && !Array.isArray(value)) Object.assign(result, value)
    else result[key] = value
  }
  return result
}
export function productFieldDiffs(row: Pick<ImportDetail, 'beforeData' | 'normalizedData'>): ProductFieldDiff[] {
  const before = flatten(row.beforeData), after = flatten(row.normalizedData)
  return Object.keys(after).map(key => {
    const changed = JSON.stringify(before[key]) !== JSON.stringify(after[key])
    const blank = after[key] == null || after[key] === ''
    return { key, before: before[key], after: after[key], changed, cleared: changed && blank && before[key] != null && before[key] !== '' }
  })
}
export function productDiffSummary(row: Pick<ImportDetail, 'beforeData' | 'normalizedData'>, mapping: Record<string, string> = {}, side: 'before' | 'after') {
  const changes = productFieldDiffs(row).filter(field => field.changed)
  return changes.map(field => `${mapping[field.key] ?? field.key}：${field[side] == null || field[side] === '' ? '未维护' : String(field[side])}`).join('；') || '无字段变化'
}
