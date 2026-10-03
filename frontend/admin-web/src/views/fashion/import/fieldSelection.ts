import type { ProductImportFields } from '@/api/fashion/importBatch'

// 旧元数据兼容保留原有必填身份；新版字段仍以 Java 元数据为准。
export const REQUIRED_PRODUCT_FIELDS = ['sourceCode', 'skuCode', 'styleCode', 'name', 'categoryCode', 'colorCode', 'colorName', 'sizeCode', 'sizeSystem', 'unit', 'season']
export const SELECTION_IDENTITY_FIELDS = ['mainSkuCode', 'itemNo', 'articleNo', 'categoryLeafCode']
export function importRequiredFields(fields: ProductImportFields) {
  return fields.requiredFields?.length ? fields.requiredFields : REQUIRED_PRODUCT_FIELDS
}
export function normalizeImportSelection(selected: string[], fields: ProductImportFields) {
  return [...new Set([...importRequiredFields(fields), ...selected])].filter(key => key in fields.mapping)
}
export function initialImportSelection(fields: ProductImportFields) {
  return normalizeImportSelection(fields.defaultFields?.length ? fields.defaultFields : [...REQUIRED_PRODUCT_FIELDS, 'brand', 'material'], fields)
}
export function selectedImportMapping(selected: string[], mapping: Record<string, string>) {
  return Object.fromEntries(selected.map(key => [key, mapping[key]]))
}
