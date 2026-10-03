<template>
  <div class="app-container fashion-products">
    <el-alert
      title="商品列表直接展示身份、资料、三种价格及审核图片。使用列工具查看全部业务字段，展开行核对完整属性与图片；库存按仓库查询。"
      type="info" :closable="false" show-icon class="mb20"
    />
    <el-alert
      v-if="!aiCapability.enabled"
      :title="`AI 商品属性建议已禁用：${capabilityReason(aiCapability.reason)}`"
      type="warning" :closable="false" show-icon class="mb20"
    />
    <el-form v-show="showSearch" :inline="true" :model="query" @submit.prevent="searchProducts">
      <el-form-item label="关键词"><el-input v-model="query.keyword" clearable placeholder="规格SKU、款号、商品名" /></el-form-item>
      <el-form-item label="来源">
        <el-select v-model="query.sourceCode" clearable filterable style="width: 160px">
          <el-option v-for="item in dictionaries.source" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="品类">
        <el-select v-model="query.categoryCode" clearable filterable style="width: 160px">
          <el-option v-for="item in dictionaries.category" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable style="width: 130px">
          <el-option label="草稿" value="draft" /><el-option label="在售" value="active" />
          <el-option label="下架" value="inactive" />
        </el-select>
      </el-form-item>
      <el-form-item label="服装属性"><el-select v-model="query.attributeKey" clearable style="width:160px"><el-option v-for="field in attributeFields" :key="field.key" :label="field.label" :value="field.key" /></el-select><el-input v-model="query.attributeValue" clearable placeholder="输入属性值" style="width:180px;margin-left:8px" /></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="searchProducts">查询</el-button></el-form-item>
    </el-form>
    <el-alert v-if="metadataError" :title="metadataError" type="warning" :closable="false" class="mb20"><el-button link @click="loadFields">重新读取字段定义</el-button></el-alert>
    <el-alert v-if="dictionaryError" :title="dictionaryError" type="warning" :closable="false" class="mb20"><el-button link @click="loadDictionaries">重新读取字典</el-button></el-alert>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="mb20"><el-button link @click="loadProducts">重新查询</el-button></el-alert>
    <div class="toolbar mb8">
      <div class="actions">
        <el-button type="primary" plain icon="Plus" v-hasPermi="['fashion:product:edit']" @click="openCreate">新增 SKU</el-button>
        <el-button v-hasPermi="['fashion:product:import']" @click="router.push('/fashion/import')">导入商品资料</el-button>
        <el-popover placement="bottom-start" :width="520" trigger="click">
          <template #reference><el-button>分组选择列（{{visibleColumns.length}}）</el-button></template>
          <div class="column-groups">
            <div class="group-actions"><el-button link @click="selectGroup(undefined,true)">全部业务字段</el-button><el-button link @click="restoreDefaultColumns">恢复默认28列</el-button></div>
            <section v-for="group in columnGroups" :key="group.name">
              <el-checkbox :model-value="group.columns.every(column=>column.visible)" :indeterminate="group.columns.some(column=>column.visible)&&!group.columns.every(column=>column.visible)" @change="selectGroup(group.name,Boolean($event))">{{group.name}}</el-checkbox>
              <div class="group-options"><el-checkbox v-for="column in group.columns" :key="column.key" v-model="column.visible">{{column.label}}</el-checkbox></div>
            </section>
          </div>
        </el-popover>
      </div>
      <RightToolbar v-if="fieldsLoaded" v-model:show-search="showSearch" :columns="columns" :storage-key="columnStorageKey" @query-table="loadProducts" />
      <RightToolbar v-else v-model:show-search="showSearch" @query-table="loadProducts" />
    </div>
    <div class="product-table-region">
      <el-table v-loading="loading" :data="page.items" row-key="id" border :empty-text="loading?'商品加载中':loadError?'查询失败，请重新查询':'没有符合条件的商品'" table-layout="fixed">
        <el-table-column type="expand" width="48" fixed="left"><template #default="{row}"><ProductListExpansion :product="row" :fields="attributeFields" /></template></el-table-column>
        <el-table-column v-for="column in visibleColumns" :key="column.key" :label="column.label" :width="column.width" :fixed="column.fixed" :show-overflow-tooltip="column.key!=='picture'">
          <template #default="{row}">
            <template v-if="column.key==='picture'">
              <ProductThumbnail v-if="row.mainImageKey" :product-id="row.id" :image-key="row.mainImageKey" :can-view="mainImageCanView(row)" class="list-thumbnail" />
              <span v-else>未维护主图</span>
            </template>
            <template v-else-if="column.key==='sourceCode'">{{labelOf(dictionaries.source,row.sourceCode)}}</template>
            <template v-else-if="column.key==='season'">{{labelOf(dictionaries.season,row.season)||'未维护'}}</template>
            <span v-else>{{productColumnText(row,column)}}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{row}">
            <div class="row-actions">
              <el-button link type="primary" @click="details?.open(row.id)">详情</el-button>
              <el-button link type="primary" v-hasPermi="['fashion:product:edit']" @click="details?.open(row.id)">编辑资料</el-button>
              <el-button link type="primary" v-hasPermi="['fashion:price:import']" @click="prices?.open(row)">维护价格</el-button>
              <el-button link type="primary" v-hasPermi="['fashion:product:image']" @click="details?.open(row.id,'images')">维护图片</el-button>
              <el-button link type="primary" v-hasPermi="['fashion:product:edit']" @click="toggleStatus(row)">{{row.status==='active'?'下架':'上架'}}</el-button>
              <el-tooltip :content="aiCapability.enabled?'生成待人工确认的商品属性建议':capabilityReason(aiCapability.reason)" placement="top"><span><el-button link type="success" :disabled="!aiCapability.enabled" v-hasPermi="['fashion:ai:run:execute']" @click="suggestAttributes(row)">AI 属性建议</el-button></span></el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <pagination v-show="page.total > 0" :total="page.total" v-model:page="query.page" v-model:limit="query.pageSize" @pagination="loadProducts" />

    <ProductDetails ref="details" @saved="loadProducts" /><CommercialPrices ref="prices" @saved="loadProducts" />
    <el-dialog v-model="createVisible" title="新增 SKU 商品" width="760px" append-to-body>
      <el-form label-width="100px" :model="createForm">
        <el-row :gutter="16">
          <el-col v-for="field in createFields" :key="field.key" :span="12">
            <el-form-item :label="field.label" :required="field.required">
              <el-select v-if="field.dict" v-model="createForm[field.key]" filterable style="width:100%">
                <el-option v-for="item in dictionaries[field.dict]" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <el-input v-else v-model="createForm[field.key]" />
            </el-form-item>
          </el-col>
        </el-row>
        <ProductAttributes v-model="createAttributes" :fields="attributeFields" />
      </el-form>
      <template #footer><el-button @click="createVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitCreate">保存草稿</el-button></template>
    </el-dialog>

    <el-dialog v-model="suggestionVisible" title="AI 商品属性建议" width="680px" append-to-body @closed="stopPolling">
      <el-alert title="AI 只生成候选属性；点击“确认采用”前不会修改商品。价格、库存、客户信息不进入该任务。" type="info" :closable="false" show-icon class="mb20" />
      <el-descriptions v-if="activeRun" :column="2" border>
        <el-descriptions-item label="任务号">{{ activeRun.runNo }}</el-descriptions-item>
        <el-descriptions-item label="状态"><el-tag :type="runStatusType(activeRun.status)">{{ runStatusLabel(activeRun.status) }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ activeRun.runAttempt }}</el-descriptions-item>
        <el-descriptions-item label="采用状态">{{ activeRun.applyStatus === 'applied' ? '已采用' : '待确认' }}</el-descriptions-item>
      </el-descriptions>
      <el-table v-if="suggestedAttributes.length" :data="suggestedAttributes" class="mt20" size="small">
        <el-table-column prop="label" label="属性" width="130" />
        <el-table-column prop="current" label="当前值" min-width="180" />
        <el-table-column prop="suggested" label="建议值" min-width="180" />
      </el-table>
      <el-empty v-else-if="activeRun?.status === 'succeeded'" description="Runtime 未返回可采用的属性" />
      <el-result v-else-if="activeRun && ['failed','expired','cancelled'].includes(activeRun.status)" icon="error" title="建议生成失败" :sub-title="activeRun.errorMessage || activeRun.errorCode || '请稍后重试'" />
      <div v-else class="run-waiting">任务正在排队或执行，页面会自动刷新状态。</div>
      <template #footer>
        <el-button @click="suggestionVisible=false">关闭</el-button>
        <el-button type="primary" :disabled="activeRun?.status !== 'succeeded' || !suggestedAttributes.length || activeRun.applyStatus === 'applied'" :loading="applyingSuggestion" v-hasPermi="['fashion:ai:run:apply']" @click="applySuggestion">确认采用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import ProductDetails from './ProductDetails.vue'
import CommercialPrices from './CommercialPrices.vue'
import ProductListExpansion from './ProductListExpansion.vue'
import ProductThumbnail from '../components/ProductThumbnail.vue'
import RightToolbar from '@/components/RightToolbar/index.vue'
import cache from '@/plugins/cache'
import useUserStore from '@/store/modules/user'
import auth from '@/plugins/auth'
import { createProductColumns, productColumnText, setColumnGroup, type ProductColumn } from './productList'
import ProductAttributes from './ProductAttributes.vue'
import { getProductFields } from '@/api/fashion/importBatch'
import type { ProductAttributeField } from '@/api/fashion/types'
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDicts } from '@/api/system/dict/data'
import { useRoute, useRouter } from 'vue-router'
import { createProduct, listProducts, updateProducts, updateProductStatus, type ProductCreateBody } from '@/api/fashion/product'
import { applyProductAttributeRun, createProductAttributeRun, getProductAttributeCapability, getRun, type RunCapability } from '@/api/fashion/agent'
import type { FashionProduct, FashionRun, ProductStatus } from '@/api/fashion/types'

interface Option { label: string; value: string }
type DictionaryKey = 'source' | 'category' | 'color' | 'unit' | 'season'
const dictionaries = reactive<Record<DictionaryKey, Option[]>>({ source: [], category: [], color: [], unit: [], season: [] })
const dictTypes: Record<DictionaryKey, string> = { source: 'fashion_product_source', category: 'fashion_product_category', color: 'fashion_product_color', unit: 'fashion_product_unit', season: 'fashion_product_season' }
const details = ref<InstanceType<typeof ProductDetails>>()
const prices = ref<InstanceType<typeof CommercialPrices>>()
const showSearch = ref(true), loadError = ref(''), metadataError = ref(''), dictionaryError = ref(''), fieldsLoaded = ref(false)
const canManage=auth.hasPermiOr(['fashion:product:edit','fashion:product:image'])
const columns = reactive<Record<string,ProductColumn>>(createProductColumns([],canManage))
const columnStorageKey = 'fashion:product:columns:v1:' + useUserStore().id
const visibleColumns = computed(()=>Object.values(columns).filter(column=>column.visible))
const columnGroups = computed(()=>[...new Set(Object.values(columns).map(column=>column.group))].map(name=>({name,columns:Object.values(columns).filter(column=>column.group===name)})))
function selectGroup(group:string|undefined,visible:boolean){setColumnGroup(columns,group,visible)}
function restoreDefaultColumns(){const defaults=createProductColumns(attributeFields.value,canManage);Object.values(columns).forEach(column=>{column.visible=defaults[column.key]?.visible??false})}
let columnPreferencesReady=false
watch(columns,()=>{if(columnPreferencesReady)try{cache.local.setJSON(columnStorageKey,Object.fromEntries(Object.values(columns).map(column=>[column.key,column.visible])))}catch{/* 浏览器禁止缓存时仍允许查看字段。 */}},{deep:true})
function mainImageCanView(product:FashionProduct){return product.images?.find(image=>image.objectKey===product.mainImageKey)?.allowInternal!==false}
const router = useRouter()
const route = useRoute()
const createAttributes = ref<Record<string,string>>({})
const attributeFields = ref<ProductAttributeField[]>([])
const loading = ref(false)
const saving = ref(false)
const createVisible = ref(false)
const suggestionVisible = ref(false)
const applyingSuggestion = ref(false)
const aiCapability = reactive<RunCapability>({ enabled: false, reason: 'capability_loading' })
const activeRun = ref<FashionRun>()
const activeProduct = ref<FashionProduct>()
const page = reactive({ items: [] as FashionProduct[], total: 0 })
const query = reactive<{ sourceCode?: string; categoryCode?: string; status: ProductStatus | ''; keyword?: string; attributeKey?: string; attributeValue?: string; page: number; pageSize: number }>({ status: '', page: 1, pageSize: 20 })
let controller: AbortController | undefined
let loadSequence=0, disposed=false
let pollingTimer: ReturnType<typeof setTimeout> | undefined
const createForm = reactive<Record<string, string>>({ sizeSystem: 'LETTER', season: '四季' })
const createFields: { key: string; label: string; required: boolean; dict?: DictionaryKey }[] = [
  { key: 'sourceCode', label: '来源', required: true, dict: 'source' }, { key: 'skuCode', label: 'SKU 编码', required: true },
  { key: 'styleCode', label: '款号', required: true }, { key: 'name', label: '商品名', required: true },
  { key: 'categoryCode', label: '品类', required: true, dict: 'category' }, { key: 'colorCode', label: '颜色编码', required: true, dict: 'color' },
  { key: 'colorName', label: '颜色名称', required: true }, { key: 'sizeCode', label: '尺码', required: true },
  { key: 'sizeSystem', label: '尺码制式', required: true }, { key: 'unit', label: '单位', required: true, dict: 'unit' },
  { key: 'season', label: '季节', required: true, dict: 'season' }, { key: 'brand', label: '品牌', required: false },
  { key: 'material', label: '材质', required: false }, { key: 'jdItemId', label: '京东商品 ID', required: false },
  { key: 'jdUrl', label: '京东 HTTPS 链接', required: false }
]

async function loadDictionaries() {
  dictionaryError.value=''
  const results=await Promise.allSettled((Object.keys(dictTypes) as DictionaryKey[]).map(async key => {
    const response = await getDicts(dictTypes[key])
    if(!disposed)dictionaries[key] = (response.data ?? []).filter((item: any) => String(item.status) === '0').map((item: any) => ({ label: String(item.dictLabel), value: String(item.dictValue) }))
  }))
  if(!disposed&&results.some(result=>result.status==='rejected'))dictionaryError.value='部分字典读取失败，列表保留原编码；请重试后选择查询条件。'
}
async function loadFields() {
  metadataError.value=''
  try {
    const response=await getProductFields()
    if(disposed)return
    columnPreferencesReady=false
    attributeFields.value=response.data.attributes
    const next=createProductColumns(attributeFields.value,canManage)
    Object.entries(next).forEach(([key,column])=>{if(columns[key])column.visible=columns[key].visible;columns[key]=column})
    fieldsLoaded.value=true
    // 等若依列工具恢复偏好后才持久化，避免初始化覆盖已有选择。
    await nextTick()
    columnPreferencesReady=true
  } catch(e) { if(!disposed)metadataError.value=e instanceof Error?`完整字段定义读取失败：${e.message}`:'完整字段定义读取失败' }
}
function searchProducts(){query.page=1;void loadProducts()}
async function loadProducts() {
  const sequence=++loadSequence
  controller?.abort(); controller = new AbortController(); loading.value=true;loadError.value=''
  page.items=[];page.total=0
  try {
    const response=await listProducts({...query},controller.signal)
    if(disposed||sequence!==loadSequence)return
    page.items=response.data.items;page.total=response.data.total
  } catch(e) {
    if(disposed||sequence!==loadSequence)return
    const status=(e as {status?:number})?.status
    loadError.value=status===403?'无商品查看权限，请联系管理员':e instanceof Error?`商品查询失败：${e.message}`:'商品查询失败，请重试'
  } finally { if(!disposed&&sequence===loadSequence)loading.value=false }
}
async function loadAiCapability() {
  try { Object.assign(aiCapability, (await getProductAttributeCapability()).data) }
  catch { Object.assign(aiCapability, { enabled: false, reason: 'capability_unavailable' }) }
}
function openCreate() { createAttributes.value={}; Object.keys(createForm).forEach(key => delete createForm[key]); createForm.sizeSystem='LETTER'; createForm.season='四季'; createVisible.value=true }
async function submitCreate() {
  saving.value = true
  try { await createProduct({...createForm, attributes:createAttributes.value} as unknown as ProductCreateBody); ElMessage.success('商品草稿已创建'); createVisible.value=false; await loadProducts() }
  finally { saving.value = false }
}
async function rename(row: FashionProduct) {
  const result = await ElMessageBox.prompt('商品新名称', '修改商品', { inputValue: row.name, inputPattern: /\S+/, inputErrorMessage: '名称不能为空' })
  await updateProducts([{ id: row.id, rowVersion: row.rowVersion, changes: { name: result.value.trim() } }]); ElMessage.success('商品已更新'); await loadProducts()
}
async function toggleStatus(row: FashionProduct) {
  const status: ProductStatus = row.status === 'active' ? 'inactive' : 'active'
  await updateProductStatus(row.id, status, row.rowVersion); ElMessage.success(status === 'active' ? '已上架' : '已下架'); await loadProducts()
}
async function suggestAttributes(row: FashionProduct) {
  if (!aiCapability.enabled) { ElMessage.warning(`AI 商品属性建议已禁用：${capabilityReason(aiCapability.reason)}`); return }
  stopPolling(); activeProduct.value = row; activeRun.value = undefined; suggestionVisible.value = true
  const requestKey = `product-attribute-${row.id}-${Date.now()}`
  activeRun.value = (await createProductAttributeRun(row.id, requestKey)).data
  schedulePolling()
}
function schedulePolling() {
  stopPolling()
  if (!activeRun.value || ['succeeded','failed','expired','cancelled'].includes(activeRun.value.status)) return
  pollingTimer = setTimeout(async () => {
    if (!activeRun.value || !suggestionVisible.value) return
    try { activeRun.value = (await getRun(activeRun.value.id)).data } finally { schedulePolling() }
  }, 1500)
}
function stopPolling() { if (pollingTimer) clearTimeout(pollingTimer); pollingTimer = undefined }
async function applySuggestion() {
  if (!activeRun.value || !activeProduct.value) return
  applyingSuggestion.value = true
  try {
    const requestKey = `product-attribute-apply-${activeRun.value.id}-${Date.now()}`
    activeProduct.value = (await applyProductAttributeRun(activeRun.value.id, requestKey, activeProduct.value.rowVersion)).data
    activeRun.value = (await getRun(activeRun.value.id)).data
    ElMessage.success('商品属性建议已确认采用'); await loadProducts()
  } finally { applyingSuggestion.value = false }
}
const suggestedAttributes = computed(() => {
  const draft = activeRun.value?.output?.draft
  if (!draft || typeof draft !== 'object' || Array.isArray(draft) || !activeProduct.value) return []
  const values = draft as Record<string, unknown>
  const fields = [
    ['category_code', '品类', activeProduct.value.categoryCode],
    ['color_code', '颜色编码', activeProduct.value.colorCode], ['color_name', '颜色名称', activeProduct.value.colorName],
    ['season', '季节', activeProduct.value.season], ['style', '风格', activeProduct.value.tags.join('、')],
    ['scene', '场景', activeProduct.value.tags.join('、')], ['audience', '人群', activeProduct.value.tags.join('、')],
    ['observable_tags', '外观标签', activeProduct.value.tags.join('、')]
  ] as const
  return fields.filter(([key]) => (typeof values[key] === 'string' && String(values[key]).trim()) || (Array.isArray(values[key]) && values[key].length))
    .map(([key, label, current]) => ({ label, current: current || '—', suggested: Array.isArray(values[key]) ? values[key].join('、') : String(values[key]) }))
})
function labelOf(options: Option[], value: string) { return options.find(item => item.value === value)?.label ?? value }
function statusLabel(value: ProductStatus) { return ({ draft: '草稿', active: '在售', inactive: '下架' } as const)[value] }
function reasonLabel(value: string) { return ({ missing_price: '缺价格', missing_image: '缺主图', attributes_unconfirmed: '属性待确认', not_active: '未上架' } as Record<string,string>)[value] ?? value }
function capabilityReason(value?: string) { return ({ provider_disabled: 'Provider 未配置', product_attribute_agent_not_published: '商品属性 Agent 尚未发布', capability_loading: '正在检查能力', capability_unavailable: '能力检查失败' } as Record<string,string>)[value || ''] ?? (value || '未知原因') }
function runStatusLabel(value: string) { return ({ queued: '排队中', running: '执行中', retry_wait: '等待重试', succeeded: '已完成', failed: '失败', expired: '超时', cancelled: '已取消' } as Record<string,string>)[value] ?? value }
function runStatusType(value: string) { return value === 'succeeded' ? 'success' : ['failed','expired','cancelled'].includes(value) ? 'danger' : 'warning' }
let initialized = false
onMounted(async () => { await Promise.allSettled([loadDictionaries(),loadAiCapability(),loadFields(),loadProducts()]); initialized=true;if(typeof route.query.productId==='string')await details.value?.open(route.query.productId) })
onActivated(() => { if(initialized) void loadProducts() })
onBeforeUnmount(() => { disposed=true;loadSequence++;controller?.abort(); stopPolling() })
</script>

<style scoped>
.fashion-products{min-width:0;max-width:100%;box-sizing:border-box}.toolbar{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;flex-wrap:wrap}.actions{display:flex;gap:8px;flex-wrap:wrap}.actions .el-button{margin-left:0}.product-table-region{width:100%;max-width:100%;min-width:0}.row-actions{display:flex;gap:6px 12px;flex-wrap:wrap}.row-actions .el-button{margin-left:0}.list-thumbnail{width:76px!important;height:80px!important}.column-groups{max-height:60vh;overflow-y:auto}.column-groups section{margin:12px 0}.group-actions{display:flex;gap:16px}.group-options{display:flex;flex-wrap:wrap;padding-left:16px;gap:0 12px}.group-options .el-checkbox{margin-right:0}.reason-tag{margin:2px}.fashion-products code{font-weight:600}.run-waiting{padding:36px;text-align:center;color:var(--el-text-color-secondary)}
</style>
