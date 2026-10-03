import type { FashionProduct, ProductAttributeField, ProductImage } from '@/api/fashion/types'

export interface ProductColumn {
  key: string
  label: string
  group: string
  visible: boolean
  width: number
  fixed?: 'left'
  attribute?: boolean
}

const defaultColumns: Array<[string, string, string, number, boolean?]> = [
  ['picture', '图片', '图片', 100, true], ['name', '商品名称', '身份与基本资料', 180, true],
  ['mainSkuCode', '主SKU', '身份与基本资料', 140], ['itemNo', '品号', '身份与基本资料', 140, true],
  ['articleNo', '货号', '身份与基本资料', 140], ['styleCode', '款号', '身份与基本资料', 140],
  ['skuCode', '规格SKU', '身份与基本资料', 170], ['sourceCode', '来源', '来源', 140],
  ['categoryLeafCode', '三级分类', '身份与基本资料', 200], ['colorName', '颜色', '身份与基本资料', 120],
  ['sizeCode', '尺码', '尺码', 100], ['sizeSystem', '尺码制式', '尺码', 120],
  ['unit', '单位', '身份与基本资料', 90], ['brand', '品牌', '身份与基本资料', 130],
  ['material', '材质', '服装', 160], ['season', '季节', '服装', 100],
  ['salePrice', '原销售价', '价格', 130], ['groupPrice', '团购价', '价格', 130],
  ['vipPrice', 'VIP价', '价格', 130], ['currency', '币种', '价格', 90],
  ['taxMode', '税口径', '价格', 130], ['priceAsOf', '价格取得时间', '价格', 210],
  ['imageReview', '图片复核', '图片', 190], ['proposalPermission', '提案许可', '图片', 190],
  ['attributesConfirmed', '属性核实', '身份与基本资料', 120], ['completeness', '完整性', '身份与基本资料', 200],
  ['status', '状态', '身份与基本资料', 100], ['updateTime', '更新时间', '身份与基本资料', 210]
]

/** 扩展字段取自服务端白名单；身份字段只注册一次，保持编码原文。 */
export function createProductColumns(fields: ProductAttributeField[], canManage = false): Record<string, ProductColumn> {
  const attributeKeys = new Set(fields.map(field => field.key))
  const columns: Record<string, ProductColumn> = {}
  for (const [key, label, group, width, fixed] of defaultColumns) {
    columns[key] = { key, label, group, width, fixed: fixed ? 'left' : undefined, visible: true, attribute: attributeKeys.has(key) || ['mainSkuCode', 'itemNo', 'articleNo', 'categoryLeafCode'].includes(key) }
  }
  for (const [key, label, group] of [
    ['categoryCode', '品类编码', '身份与基本资料'], ['colorCode', '颜色编码', '身份与基本资料'],
    ['tags', '标签', '服装'], ['jdItemId', '京东商品ID', '来源'], ['jdUrl', '京东链接', '来源'],
    ['mainImageKey', '审核主图对象', '图片'], ['images', '审核图片明细', '图片'],
    ['incompleteReasons', '完整性原因', '身份与基本资料']
  ]) columns[key] = { key, label, group, width: 200, visible: false }
  for (const field of fields) {
    if (columns[field.key]) continue
    const group = field.group === '主SKU与分类' || field.group === '基础' ? '身份与基本资料'
      : field.group === '图片来源' ? '图片' : field.group === '观察价格' ? '价格' : field.group
    columns[field.key] = { key: field.key, label: field.label, group, width: 220, visible: false, attribute: true }
  }
  if (!canManage) delete columns.mainImageKey
  return columns
}

export const missingValue = '未维护'
export function valueText(value: unknown): string {
  if (value === null || value === undefined || value === '') return missingValue
  if (Array.isArray(value)) return value.length ? value.map(valueText).join('、') : missingValue
  return String(value)
}

export function statusLabel(value: string): string {
  return ({ draft: '草稿', active: '在售', inactive: '下架' } as Record<string, string>)[value] ?? valueText(value)
}
export function reasonLabel(value: string): string {
  return ({ missing_price: '缺价格', missing_image: '缺主图', attributes_unconfirmed: '属性待确认', not_active: '未上架' } as Record<string, string>)[value] ?? value
}
export function reviewLabel(value?: string): string {
  return ({ active: '已入库', confirmed: '已复核', pending: '待复核', rejected: '已驳回', revoked: '已撤销', inactive: '已停用' } as Record<string, string>)[value || ''] ?? (value || '未记录')
}
export function permissionLabel(image: ProductImage): string {
  if (image.allowProposal === undefined) return '许可未记录'
  if (!image.allowProposal) return '未许可'
  if (image.status !== 'active') return '已许可，当前不可用'
  return Number(image.confirmedBy) > 0 && image.confirmedAt ? '允许' : '已许可，待复核'
}

export function productColumnText(product: FashionProduct, column: ProductColumn): string {
  if ((column.key === 'groupPrice' || column.key === 'vipPrice') && product[column.key] === undefined) return '未取得'
  if (column.key === 'imageReview') {
    const summary = product.imageSummary
    if (!summary) return '摘要未返回'
    return summary.reviewStatus === 'none' ? '未维护图片' : `${summary.activeCount === 0 ? '无生效图片' : summary.reviewStatus === 'confirmed' ? '已复核' : '待复核'} · 入库${summary.activeCount}/${summary.totalCount}`
  }
  if (column.key === 'proposalPermission') {
    const summary = product.imageSummary
    if (!summary) return '摘要未返回'
    return summary.proposalPermission === 'none' ? '无图片' : summary.proposalPermission === 'denied' ? '未许可' : `已许可${summary.proposalAllowedCount}张`
  }
  if (column.key === 'categoryLeafCode') {
    const code = product.attributes?.categoryLeafCode
    return code ? (product.attributes?.categoryLeafPath || code) : `待映射（原品类：${valueText(product.categoryCode)}）`
  }
  if (column.key === 'attributesConfirmed') return product.attributesConfirmed ? '已核实' : '待核实'
  if (column.key === 'completeness' || column.key === 'incompleteReasons') {
    return product.incompleteReasons?.length ? product.incompleteReasons.map(reasonLabel).join('、') : '可用'
  }
  if (column.key === 'status') return statusLabel(product.status)
  if (column.key === 'images') return product.images?.length ? product.images.map(image => `${image.originalFilename || '文件名未记录'} · ${image.usage === 'main' ? '主图' : '细节图'} · ${reviewLabel(image.status)} · 提案${permissionLabel(image)}`).join('；') : '未维护'
  if (column.attribute) return valueText(product.attributes?.[column.key])
  return valueText((product as unknown as Record<string, unknown>)[column.key])
}

export function setColumnGroup(columns: Record<string, ProductColumn>, group: string | undefined, visible: boolean): void {
  Object.values(columns).filter(column => group === undefined || column.group === group).forEach(column => { column.visible = visible })
}
