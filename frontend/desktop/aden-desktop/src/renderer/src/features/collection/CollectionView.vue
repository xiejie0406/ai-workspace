<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useWorkspaceStore } from '../../stores/workspace'
import { COLLECTION_FIELDS, missingFields, type CollectionSnapshot, type CollectionRequest, type ProductValues } from '../../../../shared/contracts/collection'

const workspace = useWorkspaceStore()
const api = window.adenDesktop.collection
const urls = ref(''); const error = ref(''); const notice = ref(''); const busy = ref(false)
const snapshot = ref<CollectionSnapshot>({ schemaVersion: 'aden-collection-1', entries: [], running: false })
const selected = ref(''); const form = ref<ProductValues>({}); const dirty = ref(false)
const fields = COLLECTION_FIELDS.map(f => ({ ...f, group: 'group' in f ? f.group : '基础', maxLength: 'maxLength' in f ? f.maxLength : 2000 }))
const groups = [...new Set(fields.map(f => f.group))]
const rows = computed(() => snapshot.value.entries.flatMap(entry => entry.skus.map(sku => ({ entry, sku, key: `${entry.id}/${sku.id}` }))))
const current = computed(() => rows.value.find(row => row.key === selected.value))
const missing = computed(() => missingFields(form.value))
const incomplete = computed(() => rows.value.filter(row => missingFields(row.sku.values).length > 0).length)
const completed = computed(() => snapshot.value.entries.filter(e => !['queued', 'reading'].includes(e.state)).length)
const stateLabel = { queued: '排队', reading: '读取中', collected: '已采集', failed: '失败', cancelled: '已取消' }
const availabilityLabel = { in_stock: '有货（数量未知）', out_of_stock: '无货（数量未知）', unknown: '可售状态未知' }
let poll: ReturnType<typeof setTimeout> | undefined
let disposed = false
function request(): CollectionRequest { return workspace.context() }
function apply(value: CollectionSnapshot): void {
  snapshot.value = value
  if (!rows.value.some(r => r.key === selected.value)) selected.value = rows.value[0]?.key ?? ''
}
function selectRow(key: string): void {
  if (dirty.value && !window.confirm('当前修正尚未应用，放弃这些输入并切换SKU？')) return
  selected.value = key
  form.value = { ...(rows.value.find(r => r.key === key)?.sku.values ?? {}) }; dirty.value = false
}
watch(selected, key => { form.value = { ...(rows.value.find(r => r.key === key)?.sku.values ?? {}) }; dirty.value = false })
watch(() => workspace.selectedId, () => { snapshot.value = { schemaVersion: 'aden-collection-1', entries: [], running: false }; selected.value = ''; dirty.value = false })
async function run(action: () => Promise<void>): Promise<void> {
  error.value = ''; notice.value = ''; busy.value = true
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请重试' } finally { busy.value = false }
}
async function refresh(): Promise<void> {
  if (disposed) return
  try {
    if (workspace.selectedId && snapshot.value.running) apply((await api.snapshot(request())).value)
  } catch (e) { error.value = e instanceof Error ? e.message : '采集状态读取失败'; snapshot.value.running = false }
  if (!disposed) poll = setTimeout(() => { void refresh() }, 700)
}
function start(): void { void run(async () => { apply((await api.start({ ...request(), urls: urls.value })).value) }) }
function retry(): void { void run(async () => { apply((await api.retry(request())).value) }) }
function cancel(): void { void run(async () => { apply((await api.cancel(request())).value); notice.value = '已请求取消，正在停止当前读取' }) }
function update(): void { void run(async () => {
  if (!current.value) return
  const result = await api.update({ ...request(), entryId: current.value.entry.id, skuId: current.value.sku.id, values: { ...form.value } })
  apply(result.value); dirty.value = false; notice.value = '已应用修正，原始采集值保留在来源清单中'
}) }
function file(action: 'save' | 'load' | 'xlsx' | 'sources'): void { void run(async () => {
  if (dirty.value) throw new Error('请先应用当前SKU修正，再保存或导出')
  if (action === 'load' && snapshot.value.entries.length && !window.confirm('载入将替换当前内存草稿；请先保存需要保留的内容。继续？')) return
  const result = (await (action === 'save' ? api.save(request()) : action === 'load' ? api.load(request()) : api.export({ ...request(), format: action }))).value
  if (result.snapshot) { apply(result.snapshot); selectRow(rows.value[0]?.key ?? '') }
  notice.value = result.cancelled ? '已取消文件操作' : `${action === 'load' ? '已载入' : '已保存'}：${result.filename}`
}) }
onMounted(async () => {
  await run(async () => {
    if (!workspace.items.length) await workspace.loadList()
    if (!workspace.selectedId && workspace.items.length === 1) await workspace.select(workspace.items[0].workspaceId)
    if (workspace.selectedId) apply((await api.snapshot(request())).value)
  })
  void refresh()
})
onUnmounted(() => { disposed = true; clearTimeout(poll) })
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">COL · 商品资料</p><h1>电商商品采集</h1><p class="lede">从公开商品页保留真实规格，复核后交给智能选品。</p></div><span class="runtime-pill">本地草稿 · 尚未入库</span></header>
  <p v-if="!workspace.selectedId" class="warning-text">请先在任务中心选择有权限的工作空间。</p>
  <section class="collection-input detail-card">
    <label for="collection-urls">商品链接 <small>每行一个，最多50个；支持公开结构化商品页与 Shopify 商品页</small></label>
    <textarea id="collection-urls" v-model="urls" rows="4" maxlength="102400" placeholder="https://item.jd.com/商品编号.html&#10;https://商店域名/products/商品名称" :disabled="snapshot.running" />
    <div class="collection-actions"><button class="primary-button" :disabled="busy || snapshot.running || !workspace.selectedId || !urls.trim()" @click="start">开始采集</button><button :disabled="!snapshot.running" @click="cancel">取消采集</button><button :disabled="busy || snapshot.running || !snapshot.entries.some(e => ['failed','cancelled'].includes(e.state))" @click="retry">重试失败项</button><span aria-live="polite">{{ completed }} / {{ snapshot.entries.length }} 来源 · {{ rows.length }} 个SKU</span></div>
    <p class="collection-help">遇到登录、验证码或拒绝访问会停止该项。公开“有货”仅是观察状态，库存数量保持未知。</p>
  </section>
  <p v-if="error" class="error-banner" role="alert">{{ error }}</p><p v-if="notice" class="collection-notice" role="status">{{ notice }}</p>
  <section v-if="snapshot.entries.length" class="detail-card collection-progress" aria-label="采集进度"><h2>来源进度</h2><progress :value="completed" :max="snapshot.entries.length" /><ol><li v-for="entry in snapshot.entries" :key="entry.id"><span class="state-chip">{{ stateLabel[entry.state] }}</span><span class="collection-url">{{ entry.url }}</span><small>{{ entry.message }}</small></li></ol></section>
  <section class="collection-actions collection-files" aria-label="草稿与导出"><button :disabled="busy || snapshot.running || !workspace.selectedId" @click="file('load')">载入草稿</button><button :disabled="busy || snapshot.running || !snapshot.entries.length" @click="file('save')">保存草稿</button><button :disabled="busy || snapshot.running || !rows.length" @click="file('xlsx')">导出商品 Excel</button><button :disabled="busy || snapshot.running || !snapshot.entries.length" @click="file('sources')">导出来源清单</button></section>
  <p v-if="incomplete" class="warning-text">{{ incomplete }} 个SKU缺少导入必填资料。可导出草稿供补充，当前不代表可以直接导入；未知值不会自动填写。</p>
  <div v-if="!rows.length" class="empty-card">暂无商品结果。输入链接开始采集，或载入已保存的草稿。</div>
  <section v-else class="collection-grid">
    <aside class="detail-card collection-skus"><h2>商品与 SKU <small>{{ rows.length }}</small></h2><button v-for="row in rows" :key="row.key" class="collection-sku" :class="{ selected: selected === row.key }" @click="selectRow(row.key)"><strong>{{ row.sku.values.name }}</strong><span>{{ row.sku.values.colorName || '颜色未知' }} · {{ row.sku.values.sizeCode || '尺码未知' }}</span><small>平台SKU {{ row.sku.values.platformSkuId || '未知' }}</small><small>{{ availabilityLabel[row.sku.availability] }}</small></button></aside>
    <form v-if="current" class="detail-card collection-editor" @submit.prevent="update"><div class="section-heading"><h2>复核与修正</h2><button class="primary-button" type="submit" :disabled="busy || snapshot.running || !dirty">应用修正</button></div><p>原始值保留；平台SKU身份不会因字段修正而自动生成新变体。</p><p v-if="missing.length" class="warning-text">待补充：{{ missing.join('、') }}</p>
      <details v-for="(group,index) in groups" :key="group" :open="index === 0 || group === '来源'"><summary>{{ group }}</summary><div class="collection-field-grid"><label v-for="field in fields.filter(f => f.group === group)" :key="field.key">{{ field.label }}<span v-if="field.required"> *</span><textarea v-if="field.maxLength > 2000 || field.key === 'fabricComposition'" v-model="form[field.key]" :maxlength="field.maxLength" rows="3" :disabled="snapshot.running" @input="dirty = true" /><input v-else v-model="form[field.key]" :maxlength="field.maxLength" :disabled="snapshot.running" placeholder="未知 / 未取得" @input="dirty = true" /></label></div></details>
      <p class="collection-help">观察价来自采集时点，不能用作正式销售价；来源图片链接尚未经素材审核。关闭应用或切换工作空间前请保存草稿。</p>
    </form>
  </section>
</template>

<style scoped>
.collection-input { display:grid; gap:12px; margin-bottom:18px }.collection-input label{font-weight:600}.collection-input small{display:block;font-weight:400;margin-top:6px;color:#64748b}.collection-input textarea{width:100%;box-sizing:border-box;resize:vertical}.collection-actions{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.collection-help{color:#64748b;font-size:13px;line-height:1.7;margin:0}.collection-notice{background:#ecfdf5;color:#065f46;padding:12px;border-radius:8px}.collection-progress{margin-bottom:16px}.collection-progress progress{width:100%;height:8px}.collection-progress ol{padding:0;list-style:none;max-height:230px;overflow:auto}.collection-progress li{display:grid;grid-template-columns:76px minmax(160px,1fr);gap:6px 12px;padding:10px 0;border-bottom:1px solid #e2e8f0}.collection-progress li small{grid-column:2;color:#64748b}.collection-url{overflow-wrap:anywhere;font-size:13px}.collection-files{margin:18px 0}.collection-grid{display:grid;grid-template-columns:minmax(240px,1fr) minmax(360px,2fr);gap:20px;align-items:start}.collection-skus{max-height:900px;overflow:auto}.collection-sku{display:flex;flex-direction:column;text-align:left;width:100%;gap:6px;padding:12px;margin:8px 0;background:#fff;border:1px solid #dbe2eb;border-radius:8px;white-space:normal}.collection-sku.selected{background:#eff6ff;border-color:#2563eb}.collection-sku small{color:#64748b;overflow-wrap:anywhere}.collection-field-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.collection-field-grid label{font-size:13px;display:flex;flex-direction:column;gap:6px}.collection-field-grid input,.collection-field-grid textarea{width:100%;box-sizing:border-box}.collection-editor details{padding:14px 0;border-top:1px solid #e2e8f0}.collection-editor summary{cursor:pointer;font-weight:600;padding-bottom:12px}.collection-editor p{font-size:13px;line-height:1.6}.collection-editor{min-width:0}.collection-skus h2{margin-top:0}@media(max-width:1000px){.collection-grid{grid-template-columns:1fr}.collection-skus{max-height:320px}}@media(max-width:650px){.collection-field-grid{grid-template-columns:1fr}}button:focus-visible,summary:focus-visible{outline:3px solid #93c5fd;outline-offset:2px}
</style>
