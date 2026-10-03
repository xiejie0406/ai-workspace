import { describe, it, expect } from 'vitest'
import { stockMatrix, stockFreshness } from '@/views/fashion/stock/stockMatrix'
import { productFieldDiffs, productDiffSummary } from '@/views/fashion/import/productDiff'
import type { StockRow } from '@/api/fashion/stock'

const row = (extra: Partial<StockRow> = {}): StockRow => ({ productId: '1', sourceCode: 'ERP', styleCode: 'STYLE', skuCode: 'SKU1', name: '衣服', colorCode: 'BLUE', colorName: '蓝', sizeCode: 'M', sizeSystem: 'CN', warehouseCode: 'MAIN', availableQty: 0, stale: false, asOf: '2026-10-02T00:00:00Z', batchId: 'B1', ...extra })
describe('库存矩阵保留规格事实', () => {
  it('区分未知、明确零库存、时间缺失和过期', () => {
    expect(stockFreshness(row())).toBe('有效')
    expect(stockFreshness(row({ availableQty: null }))).toBe('未导入，数量未知')
    expect(stockFreshness(row({ asOf: null }))).toBe('时间未知，待核实')
    expect(stockFreshness(row({ stale: true }))).toBe('超时，待核实')
  })
  it('同颜色尺码多个规格保留，不静默覆盖也不跨仓库汇总', () => {
    const records = [row(), row({ productId: '2', skuCode: 'SKU2', availableQty: null }), row({ productId: '3', warehouseCode: 'OTHER', availableQty: 8, stale: true })]
    const result = stockMatrix(records)
    expect(result).toHaveLength(2)
    expect(result[0].sizes.M.map(x => x.availableQty)).toEqual([0, null])
    expect(result[0].rows.map(x => x.skuCode)).toEqual(['SKU1', 'SKU2'])
    expect(result[1].warehouseCode).toBe('OTHER')
    expect(result[1].rows[0].batchId).toBe('B1')
  })
})
describe('商品预览按字段核对差异', () => {
  it('清空只标记真实非空原值变为空，保留和新商品空值不会冒充清空', () => {
    const result = productFieldDiffs({ beforeData: { brand: '品牌甲', material: '棉', itemNo: 'NO1' }, normalizedData: { brand: null, material: '棉', itemNo: 'NO2', jdUrl: '' } })
    expect(result.filter(x => x.cleared).map(x => x.key)).toEqual(['brand'])
    expect(result.find(x => x.key === 'material')?.changed).toBe(false)
    expect(result.find(x => x.key === 'itemNo')).toMatchObject({ before: 'NO1', after: 'NO2', changed: true, cleared: false })
  })
  it('嵌套扩展属性展平为中文业务字段，不显示一个JSON块', () => {
    const value = { beforeData: { attributes: { itemNo: 'NO1', mainSkuCode: 'MAIN1' } }, normalizedData: { attributes: { itemNo: 'NO2', mainSkuCode: 'MAIN1' } } }
    expect(productDiffSummary(value, { itemNo: '品号' }, 'before')).toBe('品号：NO1')
    expect(productDiffSummary(value, { itemNo: '品号' }, 'after')).toBe('品号：NO2')
    expect(productFieldDiffs(value).map(x => x.key)).toEqual(['itemNo', 'mainSkuCode'])
  })
})
