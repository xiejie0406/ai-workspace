import { describe, expect, it } from 'vitest'
import { initialImportSelection, normalizeImportSelection, selectedImportMapping, REQUIRED_PRODUCT_FIELDS, SELECTION_IDENTITY_FIELDS } from '@/views/fashion/import/fieldSelection'
import type { ProductImportFields } from '@/api/fashion/importBatch'
const fields:ProductImportFields={templateVersion:'3.0',mapping:Object.fromEntries([...REQUIRED_PRODUCT_FIELDS,'brand','material',...SELECTION_IDENTITY_FIELDS,'jdItemId','jdUrl','gender'].map(key=>[key,key])),attributes:[],requiredFields:REQUIRED_PRODUCT_FIELDS,defaultFields:[...REQUIRED_PRODUCT_FIELDS,'brand','material']}
describe('基本商品导入字段边界',()=>{
  it('基础13列不强制选品身份或旧京东列，按需追加四个身份得到17列',()=>{const initial=initialImportSelection(fields);expect(initial).toHaveLength(13);expect(initial).not.toContain('jdItemId');expect(normalizeImportSelection([...initial,...SELECTION_IDENTITY_FIELDS],fields)).toHaveLength(17)})
  it('取消或隐藏列表列不能移除身份字段，显示列和价库字段不能进入映射',()=>{const selected=normalizeImportSelection(['brand','操作','salePrice','availableQty','mainImageKey'],fields);expect(selected).toEqual([...REQUIRED_PRODUCT_FIELDS,'brand']);expect(selectedImportMapping(selected,fields.mapping)).not.toHaveProperty('salePrice')})
  it('取消可选字段后不发送其映射，兼容选入旧模板字段',()=>{const selected=normalizeImportSelection(['jdItemId','jdUrl'],fields);const mapping=selectedImportMapping(selected,{...fields.mapping,brand:'品牌'});expect(mapping).not.toHaveProperty('brand');expect(mapping).toHaveProperty('jdItemId');expect(mapping).toHaveProperty('jdUrl')})
})
