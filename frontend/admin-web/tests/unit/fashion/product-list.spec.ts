import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import type { FashionProduct, ProductAttributeField } from '@/api/fashion/types'
import { createProductColumns, productColumnText, setColumnGroup, valueText } from '@/views/fashion/product/productList'

const api = vi.hoisted(() => ({ list: vi.fn(), fields: vi.fn(), dicts: vi.fn(), capability: vi.fn() }))
vi.mock('@/api/fashion/product', () => ({ listProducts: api.list, createProduct: vi.fn(), updateProducts: vi.fn(), updateProductStatus: vi.fn(), getProduct: vi.fn() }))
vi.mock('@/api/fashion/importBatch', () => ({ getProductFields: api.fields }))
vi.mock('@/api/system/dict/data', () => ({ getDicts: api.dicts }))
vi.mock('@/api/fashion/agent', () => ({ getProductAttributeCapability: api.capability, applyProductAttributeRun: vi.fn(), createProductAttributeRun: vi.fn(), getRun: vi.fn() }))
vi.mock('@/plugins/auth', () => ({ default: { hasPermiOr: () => true } }))
vi.mock('@/store/modules/user', () => ({ default: () => ({ id: 'product-test-user' }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn() }, ElMessageBox: { prompt: vi.fn() } }))
import ProductList from '@/views/fashion/product/index.vue'

const attributeNames = ['mainSkuCode','itemNo','articleNo','categoryLeafCode','categoryLeafPath','platform','shopName','supplierName','platformProductId','platformSkuId','sourceUrl','collectedAt','categoryPath','listingYear','barcode','gender','audience','style','scene','fabricComposition','lining','thickness','elasticity','fit','collar','sleeveType','sleeveLength','garmentLength','pattern','craft','trouserFit','trouserLength','waistType','sizeName','sizeMeasurements','measurementUnit','sizeSource','sourceMainImageUrl','sourceColorImageUrl','sourceDetailImageUrls','sourceSizeImageUrl','observedPrice','observedCurrency','observedAt']
const fields: ProductAttributeField[] = attributeNames.map(key => ({ key, label: key, group: '服装', maxLength: 200 }))
const product = { id: '1', sourceCode: 'JD', skuCode: '00012-BL-L', styleCode: 'STYLE', name: '上衣', categoryCode: 'OLD', images: [], attributes: { mainSkuCode: '00012', itemNo: '00123', articleNo: 'A-03' }, salePrice: 0, groupPrice: null, vipPrice: 120, incompleteReasons: [], status: 'active' } as unknown as FashionProduct
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }

describe('商品完整列表业务字段', () => {
  it('默认28业务列，全44属性可选，身份字段没有重复注册', () => {
    const columns = createProductColumns(fields)
    expect(Object.values(columns).filter(column => column.visible)).toHaveLength(28)
    expect(fields.every(field => columns[field.key]?.attribute)).toBe(true)
    expect(Object.values(columns).filter(column => column.key === 'itemNo')).toHaveLength(1)
    expect(['picture', 'name', 'itemNo'].every(key => columns[key].fixed === 'left')).toBe(true)
    expect(columns.mainImageKey).toBeUndefined()
    expect(createProductColumns(fields,true).mainImageKey.visible).toBe(false)
  })
  it('编码保留前导零，主SKU、品号、货号、款号和规格独立', () => {
    const columns = createProductColumns(fields)
    expect(['mainSkuCode','itemNo','articleNo','styleCode','skuCode'].map(key => productColumnText(product, columns[key]))).toEqual(['00012','00123','A-03','STYLE','00012-BL-L'])
    expect(productColumnText({ ...product, attributes: {} }, columns.itemNo)).toBe('未维护')
  })
  it('三价不互相补值，合法零展示为零，观察价不覆盖销售价', () => {
    const columns = createProductColumns(fields)
    const row = { ...product, attributes: { ...product.attributes, observedPrice: '999.00' } }
    expect(['salePrice','groupPrice','vipPrice','observedPrice'].map(key => productColumnText(row, columns[key]))).toEqual(['0','未维护','120','999.00'])
    expect(valueText(false)).toBe('false')
    expect(productColumnText({ ...row, groupPrice: undefined }, columns.groupPrice)).toBe('未取得')
  })
  it('未映射分类保留原品类，缺摘要不假称未维护', () => {
    const columns = createProductColumns(fields)
    expect(productColumnText(product, columns.categoryLeafCode)).toBe('待映射（原品类：OLD）')
    expect(productColumnText(product, columns.imageReview)).toBe('摘要未返回')
    expect(productColumnText(product, columns.proposalPermission)).toBe('摘要未返回')
  })
  it('复核与许可分开，以分页权威摘要显示许可张数', () => {
    const columns = createProductColumns(fields)
    const row: FashionProduct = { ...product, imageSummary: { totalCount: 3, activeCount: 2, proposalAllowedCount: 1, aiAllowedCount: 0, reviewStatus: 'unconfirmed', proposalPermission: 'allowed', mainImageStatus: 'active' } }
    expect(productColumnText(row, columns.imageReview)).toBe('待复核 · 入库2/3')
    expect(productColumnText(row, columns.proposalPermission)).toBe('已许可1张')
    expect(productColumnText({ ...row, imageSummary: { ...row.imageSummary!, proposalPermission: 'denied' } }, columns.proposalPermission)).toBe('未许可')
  })
  it('按分组选择只改变该组，全选包含全部44属性', () => {
    const columns = createProductColumns(fields)
    setColumnGroup(columns, '价格', false)
    expect(columns.groupPrice.visible).toBe(false)
    expect(columns.name.visible).toBe(true)
    setColumnGroup(columns, undefined, true)
    expect(fields.every(field => columns[field.key].visible)).toBe(true)
  })
})

describe('商品列表查询状态与并发', () => {
  let wrapper: VueWrapper
  interface State { page: { items: FashionProduct[]; total: number }; loadError: string; loading: boolean; metadataError: string; query: { page: number }; loadProducts: () => Promise<void>; searchProducts: () => void }
  let state: State
  beforeEach(async () => {
    localStorage.clear(); api.list.mockReset(); api.fields.mockReset()
    api.list.mockResolvedValue({ data: { items: [product], total: 1 } }); api.fields.mockResolvedValue({ data: { attributes: fields } })
    api.dicts.mockResolvedValue({ data: [] }); api.capability.mockResolvedValue({ data: { enabled: false, reason: 'provider_disabled' } })
    wrapper = shallowMount(ProductList, { global: { stubs: { RightToolbar: true, ProductDetails: true, CommercialPrices: true, 'el-table': true, 'el-dialog': true, 'el-form': true }, directives: { hasPermi: () => {}, loading: () => {} }, config: { warnHandler: () => {} } } })
    state = wrapper.vm as unknown as State; await flushPromises()
  })
  afterEach(() => wrapper?.unmount())
  it('查询失败清空旧结果并保留错误，不当作没有商品', async () => {
    api.list.mockRejectedValueOnce(new Error('业务尚未就绪')); await state.loadProducts()
    expect(state.page.items).toEqual([]); expect(state.page.total).toBe(0)
    expect(state.loadError).toContain('业务尚未就绪'); expect(state.loading).toBe(false)
  })
  it('无权限单独反馈，查询条件变化回第一页', async () => {
    api.list.mockRejectedValueOnce(Object.assign(new Error('拒绝'), { status: 403 })); state.query.page = 3; state.searchProducts(); await flushPromises()
    expect(state.query.page).toBe(1); expect(state.loadError).toBe('无商品查看权限，请联系管理员')
  })
  it('较早回包不能覆盖较新查询或提前撤除遮罩', async () => {
    const a = deferred<{ data: { items: FashionProduct[]; total: number } }>(), b = deferred<{ data: { items: FashionProduct[]; total: number } }>()
    api.list.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const first = state.loadProducts(), second = state.loadProducts()
    a.resolve({ data: { items: [{ ...product, name: '旧结果' }], total: 1 } }); await first
    expect(state.loading).toBe(true); expect(state.page.items).toEqual([])
    b.resolve({ data: { items: [{ ...product, name: '新结果' }], total: 1 } }); await second
    expect(state.page.items[0].name).toBe('新结果'); expect(state.loading).toBe(false)
  })
})
