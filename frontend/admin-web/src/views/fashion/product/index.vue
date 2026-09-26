<template>
  <div class="app-container fashion-products">
    <el-alert
      title="商品按颜色尺码 SKU 保存；主SKU与三级分类在详情中维护。销售价/库存通过批次更新，团购价/VIP价使用独立维护入口。"
      type="info" :closable="false" show-icon class="mb20"
    />
    <el-alert
      v-if="!aiCapability.enabled"
      :title="`AI 商品属性建议已禁用：${capabilityReason(aiCapability.reason)}`"
      type="warning" :closable="false" show-icon class="mb20"
    />
    <el-form :inline="true" :model="query" @submit.prevent="loadProducts">
      <el-form-item label="关键词"><el-input v-model="query.keyword" clearable placeholder="SKU、款号、商品名" /></el-form-item>
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
      <el-form-item><el-button type="primary" icon="Search" @click="loadProducts">查询</el-button></el-form-item>
    </el-form>
    <div class="toolbar mb8">
      <el-button type="primary" plain icon="Plus" v-hasPermi="['fashion:product:edit']" @click="openCreate">新增 SKU</el-button>
      <el-button icon="Refresh" @click="loadProducts">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="page.items" empty-text="没有符合条件的商品">
      <el-table-column label="款色分组" min-width="190">
        <template #default="{ row }"><strong>{{ row.sourceCode }} / {{ row.styleCode }}</strong><br><span>{{ row.colorName }}（{{ row.colorCode }}）</span></template>
      </el-table-column>
      <el-table-column label="SKU / 尺码" min-width="150"><template #default="{ row }"><code>{{ row.skuCode }}</code><br>{{ row.sizeSystem }} {{ row.sizeCode }}</template></el-table-column>
      <el-table-column prop="name" label="商品" min-width="180" show-overflow-tooltip />
      <el-table-column prop="categoryCode" label="品类" width="100"><template #default="{ row }">{{ labelOf(dictionaries.category, row.categoryCode) }}</template></el-table-column>
      <el-table-column label="当前价" width="110"><template #default="{ row }">{{ row.salePrice == null ? '缺价' : `¥${row.salePrice}` }}</template></el-table-column>
      <el-table-column label="完整性" min-width="180">
        <template #default="{ row }">
          <el-tag v-if="!row.incompleteReasons.length" type="success">可用</el-tag>
          <el-tag v-for="reason in row.incompleteReasons" v-else :key="reason" type="warning" class="reason-tag">{{ reasonLabel(reason) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'active' ? 'success' : 'info'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="370" fixed="right">
        <template #default="{ row }">
          <el-tooltip :content="aiCapability.enabled ? '生成待人工确认的商品属性建议' : capabilityReason(aiCapability.reason)" placement="top">
            <span><el-button link type="success" :disabled="!aiCapability.enabled" v-hasPermi="['fashion:ai:run:execute']" @click="suggestAttributes(row)">AI 属性建议</el-button></span>
          </el-tooltip>
          <el-button link type="primary" v-hasPermi="['fashion:product:edit']" @click="details?.open(row.id)">详情 / 编辑</el-button>
          <el-button link type="primary" v-hasPermi="['fashion:price:import']" @click="commercialPrices?.open(row)">团购 / VIP价</el-button>
          <el-button link type="primary" v-hasPermi="['fashion:product:edit']" @click="toggleStatus(row)">{{ row.status === 'active' ? '下架' : '上架' }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="page.total > 0" :total="page.total" v-model:page="query.page" v-model:limit="query.pageSize" @pagination="loadProducts" />

    <ProductDetails ref="details" @saved="loadProducts" />
    <CommercialPrices ref="commercialPrices" @saved="loadProducts" />
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
import ProductAttributes from './ProductAttributes.vue'
import { getProductFields } from '@/api/fashion/importBatch'
import type { ProductAttributeField } from '@/api/fashion/types'
import { computed, onActivated, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDicts } from '@/api/system/dict/data'
import { createProduct, listProducts, updateProducts, updateProductStatus, type ProductCreateBody } from '@/api/fashion/product'
import { applyProductAttributeRun, createProductAttributeRun, getProductAttributeCapability, getRun, type RunCapability } from '@/api/fashion/agent'
import type { FashionProduct, FashionRun, ProductStatus } from '@/api/fashion/types'

interface Option { label: string; value: string }
type DictionaryKey = 'source' | 'category' | 'color' | 'unit' | 'season'
const dictionaries = reactive<Record<DictionaryKey, Option[]>>({ source: [], category: [], color: [], unit: [], season: [] })
const dictTypes: Record<DictionaryKey, string> = { source: 'fashion_product_source', category: 'fashion_product_category', color: 'fashion_product_color', unit: 'fashion_product_unit', season: 'fashion_product_season' }
const details = ref<InstanceType<typeof ProductDetails>>()
const commercialPrices = ref<InstanceType<typeof CommercialPrices>>()
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
  await Promise.all((Object.keys(dictTypes) as DictionaryKey[]).map(async key => {
    const response = await getDicts(dictTypes[key])
    dictionaries[key] = (response.data ?? []).filter((item: any) => String(item.status) === '0').map((item: any) => ({ label: String(item.dictLabel), value: String(item.dictValue) }))
  }))
}
async function loadProducts() {
  controller?.abort(); controller = new AbortController(); loading.value = true
  try {
    const response = await listProducts({ ...query }, controller.signal)
    page.items = response.data.items; page.total = response.data.total
  } finally { loading.value = false }
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
onMounted(async () => { await Promise.all([loadDictionaries(), loadAiCapability(), getProductFields().then(response=>{attributeFields.value=response.data.attributes})]); await loadProducts(); initialized=true })
onActivated(() => { if(initialized) void loadProducts() })
onBeforeUnmount(() => { controller?.abort(); stopPolling() })
</script>

<style scoped>
.toolbar{display:flex;gap:8px}.reason-tag{margin:2px}.fashion-products code{font-weight:600}.run-waiting{padding:36px;text-align:center;color:var(--el-text-color-secondary)}
</style>
