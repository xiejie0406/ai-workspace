<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkspaceStore } from '../../stores/workspace'
import { useSessionStore } from '../../stores/session'
import { collectionRequest } from './collection-request'
import type { CollectionTaskDetail, CollectionTaskPage, CollectionTaskSummary } from '../../../../shared/contracts/collection'

const route = useRoute()
const workspace = useWorkspaceStore()
const session = useSessionStore()
const query = ref('')
const appliedQuery = ref('')
const status = ref<'ALL' | 'SAVING' | 'SAVED' | 'PARTIAL'>('ALL')
const fromDate = ref('')
const toDate = ref('')
const appliedDates = ref({ fromDate: '', toDate: '' })
const page = ref<CollectionTaskPage>({ items: [], total: 0, page: 1, pageSize: 20 })
const detail = ref<CollectionTaskDetail | null>(null)
const images = reactive<Record<string, string>>({})
const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const detailError = ref('')
const activeTab = ref<'summary' | 'fields' | 'blocks' | 'images'>('summary')
let listRequest = 0
let detailRequest = 0
const selectedTaskId = computed(() => typeof route.params.taskId === 'string' ? route.params.taskId : '')
const canRead = computed(() => session.permissions.includes('*:*:*') || session.permissions.includes('aden:collection:read'))

function statusLabel(task: CollectionTaskSummary): string {
  if (task.taskState === 'FAILED') return '任务失败'
  if (task.status === 'PARTIAL') return '部分保存'
  if (task.status === 'SAVED') return '已保存'
  return '保存中'
}
function display(value: unknown): string {
  if (value === undefined || value === null || value === '') return '未采集'
  return typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
const fieldName = (key: string) => ({ title: '商品标题', sku: '商品 SKU', specification: '所选规格', price: '价格数值', priceText: '价格原文', sourceUrl: '来源链接', parameters: '商品参数', shop: '店铺', stock: '库存原文', delivery: '配送原文' }[key] ?? key)
async function call<T>(action: 'tasks' | 'taskDetail' | 'image', input: Record<string, unknown>): Promise<T> {
  const context = workspace.context()
  const result = await window.adenDesktop.collection.execute<T>(collectionRequest(context, action, input))
  const now = workspace.context()
  if (now.workspaceId !== context.workspaceId || now.sessionEpoch !== context.sessionEpoch || now.workspaceEpoch !== context.workspaceEpoch) throw new Error('工作空间已变化，请重新操作')
  return result.value
}
async function loadList(number = 1): Promise<void> {
  const current = ++listRequest
  error.value = ''
  page.value = { items: [], total: 0, page: number, pageSize: 20 }
  if (!workspace.selectedId || !canRead.value) return
  loading.value = true
  try {
    const result = await call<CollectionTaskPage>('tasks', { q: appliedQuery.value, status: status.value, page: number, ...appliedDates.value })
    if (current === listRequest) page.value = result
  } catch (cause) {
    if (current === listRequest) error.value = cause instanceof Error ? cause.message : '采集任务暂时无法读取'
  } finally {
    if (current === listRequest) loading.value = false
  }
}
async function loadDetail(taskId: string): Promise<void> {
  const current = ++detailRequest
  detail.value = null
  detailError.value = ''
  for (const key of Object.keys(images)) delete images[key]
  if (!taskId || !workspace.selectedId || !canRead.value) return
  detailLoading.value = true
  try {
    const result = await call<CollectionTaskDetail>('taskDetail', { taskId })
    if (current !== detailRequest) return
    detail.value = result
    for (const image of result.images) {
      if (!image.assetId) continue
      void call<string>('image', { assetId: image.assetId, thumbnail: true }).then((data) => {
        if (current === detailRequest && image.assetId) images[image.assetId] = data
      }).catch(() => undefined)
    }
  } catch (cause) {
    if (current === detailRequest) detailError.value = cause instanceof Error ? cause.message : '任务详情暂时无法读取'
  } finally {
    if (current === detailRequest) detailLoading.value = false
  }
}
function filter(): void {
  if (fromDate.value && toDate.value && fromDate.value > toDate.value) { error.value = '开始日期不能晚于结束日期。'; return }
  appliedQuery.value = query.value.trim()
  appliedDates.value = { fromDate: fromDate.value, toDate: toDate.value }
  void loadList(1)
}
watch(() => [workspace.selectedId, session.context.sessionEpoch, session.context.workspaceEpoch], () => { ++listRequest; ++detailRequest; void loadList(1); void loadDetail(selectedTaskId.value) }, { immediate: true })
watch(selectedTaskId, (taskId) => { activeTab.value = 'summary'; void loadDetail(taskId) })
onBeforeUnmount(() => { ++listRequest; ++detailRequest })
</script>

<template>
  <div class="capture-tasks">
    <header class="topbar"><div><p class="eyebrow">商品采集 · 保存记录</p><h1>采集任务</h1><p class="lede">在 Chrome 插件里每确认保存一次，这里记录一次任务。商品结果按商品汇总，历次采集保留为版本。</p></div><RouterLink class="capture-link" to="/collection">查看商品结果 →</RouterLink></header>
    <p v-if="workspace.error" role="alert" class="error-banner">{{ workspace.error }}</p>
    <p v-if="!workspace.selectedId" class="capture-empty">登录资料尚未准备好，请重新登录。</p>
    <p v-else-if="!canRead" class="capture-empty">当前账号没有查看采集任务的权限。</p>
    <template v-else>
      <form class="capture-filter" @submit.prevent="filter"><label>商品名或 SKU<input v-model="query" placeholder="搜索采集任务" maxlength="200" /></label><label>保存状态<select v-model="status"><option value="ALL">全部</option><option value="SAVING">保存中</option><option value="SAVED">已保存</option><option value="PARTIAL">部分保存</option></select></label><label>开始日期<input v-model="fromDate" type="date" /></label><label>结束日期<input v-model="toDate" type="date" /></label><button type="submit">筛选</button><button type="button" @click="loadList(page.page)">刷新</button><small>按上海日期</small></form>
      <p v-if="error" role="alert" class="error-banner">读取失败：{{ error }}</p>
      <p v-if="loading" role="status">正在读取采集任务…</p>
      <div class="capture-layout" :class="{ 'with-detail': selectedTaskId }">
        <section class="capture-panel">
          <div class="capture-panel-heading"><h2>保存记录</h2><span>共 {{ page.total }} 次 · 第 {{ page.page }} 页</span></div>
          <p v-if="!loading && !error && !page.items.length" class="capture-empty">暂无符合条件的保存记录。先在 Chrome 插件里提取、预览并确认保存。</p>
          <div v-else class="capture-table-wrap"><table><thead><tr><th>保存时间</th><th>商品与规格</th><th>任务状态</th><th>内容</th><th>图片</th><th>操作</th></tr></thead><tbody><tr v-for="task in page.items" :key="task.taskId"><td>{{ time(task.createdAt) }}</td><td><strong>{{ task.title }}</strong><small>{{ task.sku ? `SKU ${task.sku}` : task.platform }} · {{ task.specification || '规格未记录' }}</small></td><td><span class="capture-chip" :class="{ partial: task.status === 'PARTIAL', failed: task.taskState === 'FAILED' }">{{ statusLabel(task) }}</span></td><td>{{ task.contentSaved ? '已保存' : '未保存' }}</td><td>{{ task.imageSaved }}/{{ task.imageTotal }}</td><td><RouterLink :to="`/collection/tasks/${encodeURIComponent(task.taskId)}`">查看详情</RouterLink></td></tr></tbody></table></div>
          <div class="capture-pagination"><button :disabled="loading || page.page <= 1" @click="loadList(page.page - 1)">上一页</button><span>第 {{ page.page }} / {{ Math.max(1, Math.ceil(page.total / page.pageSize)) }} 页</span><button :disabled="loading || page.page * page.pageSize >= page.total" @click="loadList(page.page + 1)">下一页</button></div>
        </section>
        <aside v-if="selectedTaskId" class="capture-panel capture-detail">
          <div class="capture-panel-heading"><h2>本次任务详情</h2><RouterLink to="/collection/tasks">关闭</RouterLink></div>
          <p v-if="detailLoading" role="status">正在读取任务详情…</p><p v-if="detailError" role="alert" class="error-banner">{{ detailError }}</p>
          <template v-if="detail"><h3>{{ detail.title }}</h3><span class="capture-chip" :class="{ partial: detail.status === 'PARTIAL', failed: detail.taskState === 'FAILED' }">{{ statusLabel(detail) }}</span><dl class="capture-facts"><div><dt>任务编号</dt><dd>{{ detail.taskId }}</dd></div><div><dt>保存时间</dt><dd>{{ time(detail.createdAt) }}</dd></div><div><dt>所选规格</dt><dd>{{ detail.specification || '未记录' }}</dd></div><div><dt>来源页面</dt><dd>{{ detail.sourceUrl || '未记录' }}</dd></div><div><dt>内容</dt><dd>{{ detail.contentSaved ? '已保存' : '未保存' }} · {{ detail.blockCount }} 个内容区块</dd></div><div><dt>图片</dt><dd>已保存 {{ detail.imageSaved }} / 候选 {{ detail.imageTotal }} 张，失败 {{ detail.imageFailed }} 张</dd></div><div v-if="detail.errorMessage"><dt>异常原因</dt><dd>{{ detail.errorMessage }}</dd></div></dl>
            <div class="capture-tabs" role="group" aria-label="查看任务内容"><button v-for="entry in [['summary','概览'],['fields','商品信息'],['blocks','介绍与详情'],['images','图片']]" :key="entry[0]" type="button" :aria-pressed="activeTab === entry[0]" @click="activeTab = entry[0] as typeof activeTab">{{ entry[1] }}</button></div>
            <div v-if="activeTab === 'summary'" class="capture-section"><p>这里只显示本次实际保存的内容。未加载或保存失败的部分不会标记为完整。</p><p>采集规则版本：{{ detail.parserVersion || '未记录' }} · 页面完整度：{{ detail.completeness || '未知' }}</p><RouterLink :to="'/collection/items/' + encodeURIComponent(detail.itemId)">查看商品与历史版本 →</RouterLink></div>
            <dl v-if="activeTab === 'fields'" class="capture-facts"><div v-for="(value,key) in detail.fields" :key="key"><dt>{{ fieldName(String(key)) }}</dt><dd>{{ display(value) }}</dd></div><p v-if="!Object.keys(detail.fields).length">没有已保存的商品字段。</p></dl>
            <div v-if="activeTab === 'blocks'" class="capture-section"><article v-for="(block,index) in detail.blocks" :key="index"><strong>{{ block.type }}</strong><p>{{ block.text || '仅图片引用' }}</p></article><p v-if="!detail.blocks.length">没有已保存的介绍或详情区块。</p></div>
            <div v-if="activeTab === 'images'" class="capture-gallery"><article v-for="image in detail.images" :key="image.imageId"><img v-if="image.assetId && images[image.assetId]" :src="images[image.assetId]" :alt="`${image.group} 商品图片`" /><div v-else class="capture-image-empty">未保存预览</div><strong>{{ image.group }}</strong><small>{{ image.assetId ? '已保存' : image.error || image.status }}</small></article><p v-if="!detail.images.length">本次没有保存图片。</p></div>
          </template>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.capture-tasks{max-width:1320px}.capture-link,.capture-panel a{color:#2b6e4d;font-weight:700}.capture-context{display:flex;gap:18px;flex-wrap:wrap;margin:16px 0;color:#5b7062;font-size:12px}.capture-filter{display:flex;align-items:end;flex-wrap:wrap;gap:12px;margin:18px 0;padding:17px;border:1px solid #dce5de;border-radius:13px;background:#fff}.capture-filter label:first-child{flex:1}.capture-filter label:nth-child(2){width:140px}.capture-filter label:nth-child(3),.capture-filter label:nth-child(4){width:145px}.capture-filter>small{color:#86968b;font-size:11px;padding-bottom:12px}.capture-filter button,.capture-pagination button,.capture-tabs button{min-height:40px;padding:9px 13px;border:1px solid #adc8b5;border-radius:8px;background:#edf5ef;color:#285840;cursor:pointer}.capture-filter button[type=submit]{background:#2f7655;color:#fff}.capture-layout{display:grid;gap:16px}.capture-layout.with-detail{grid-template-columns:minmax(0,1.1fr) minmax(390px,.9fr)}.capture-panel{min-width:0;padding:20px;border:1px solid #dce5de;border-radius:13px;background:#fff}.capture-panel-heading{display:flex;justify-content:space-between;align-items:center;gap:14px}.capture-panel-heading h2{margin:0}.capture-panel-heading span{color:#607369;font-size:12px}.capture-table-wrap{max-width:100%;overflow:auto;margin-top:16px}.capture-table-wrap table{width:100%;border-collapse:collapse;font-size:13px}.capture-table-wrap th,.capture-table-wrap td{padding:12px 9px;text-align:left;border-bottom:1px solid #e3eae5;vertical-align:top}.capture-table-wrap th{background:#f6f9f6;white-space:nowrap}.capture-table-wrap small{display:block;color:#66776d;margin-top:6px}.capture-chip{display:inline-block;padding:5px 9px;border-radius:20px;background:#e3f2e9;color:#276344;font-size:12px;font-weight:700;white-space:nowrap}.capture-chip.partial{background:#fff0d4;color:#825c1c}.capture-chip.failed{background:#ffe7e4;color:#963e35}.capture-pagination{display:flex;justify-content:center;align-items:center;gap:10px;margin-top:15px;color:#64766a;font-size:12px}.capture-empty{padding:20px;background:#f5f8f5;border-radius:9px;color:#607369}.capture-detail{max-height:calc(100vh - 95px);overflow:auto;overflow-wrap:anywhere}.capture-detail h3{margin:20px 0 10px}.capture-facts{margin:17px 0}.capture-facts>div{display:grid;grid-template-columns:105px minmax(0,1fr);gap:9px;padding:10px 0;border-bottom:1px solid #e5ebe6;font-size:13px}.capture-facts dt{color:#6a7a6e}.capture-facts dd{margin:0;white-space:pre-wrap}.capture-tabs{display:flex;flex-wrap:wrap;gap:7px;margin:20px 0}.capture-tabs button[aria-pressed=true]{background:#2f7655;color:#fff}.capture-section{line-height:1.7;color:#405649}.capture-section article{padding:12px 0;border-top:1px solid #e5ebe6;white-space:pre-wrap}.capture-gallery{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.capture-gallery article{display:grid;gap:7px;padding:10px;border:1px solid #e1e9e2;border-radius:9px;min-width:0}.capture-gallery img,.capture-image-empty{width:100%;height:125px;object-fit:contain;background:#f6f8f6}.capture-image-empty{display:grid;place-items:center;color:#7d8b80;font-size:12px}.capture-gallery small{color:#6b796f}@media(max-width:1150px){.capture-layout.with-detail{grid-template-columns:1fr}.capture-detail{grid-row:1;max-height:none}}@media(max-width:800px){.capture-filter{flex-wrap:wrap}.capture-filter label:first-child{flex-basis:100%}.capture-filter label:nth-child(2){flex:1}}
</style>
