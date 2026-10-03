<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkspaceStore } from '../../stores/workspace'
import { useSessionStore } from '../../stores/session'
import { collectionRequest } from './collection-request'
import type { CollectionAction, CollectionCuration, CollectionDetail, CollectionItem, CollectionPage, CollectionSnapshot, CollectionVersionPage } from '../../../../shared/contracts/collection'

const workspace = useWorkspaceStore(); const session = useSessionStore()
const page = ref<CollectionPage>({ items: [], total: 0, page: 1, pageSize: 20 })
const route = useRoute()
const q = ref(''); const selected = ref<string[]>([])
const appliedQuery = ref('')
const detail = ref<CollectionDetail | null>(null); const snapshotId = ref('')
const snapshot = ref<CollectionSnapshot | null>(null)
const versions = ref<CollectionVersionPage>({ items: [], total: 0, page: 1, pageSize: 20 })
const images = reactive<Record<string, string>>({}); const busy = ref(false); const error = ref(''); const notice = ref('')
const thumbnails = reactive<Record<string, string>>({})
const tab = ref('fields')
const dialog = ref<'export' | null>(null)
const exportScope = ref<'selected' | 'filtered' | 'version'>('selected')
const exportFormat = ref<'XLSX' | 'ZIP'>('XLSX')
const exportPreview = ref<{ ids: string[]; count: number; imageSaved: number; imageTotal: number; imageCountKnown: boolean; snapshotId?: string } | null>(null)
const exportResult = ref<{ count: number; format: 'XLSX' | 'ZIP'; imageSaved: number; missing: number; imageCountKnown: boolean; filePath: string } | null>(null)
const curation = ref<CollectionCuration>({ revision: 0, notes: '', tags: [], overrides: {}, removedImageIds: [], mainImageId: null, imageOrder: [] })
const tags = ref(''); const correctedTitle = ref(''); const correctedPrice = ref('')
let exportTrigger: HTMLElement | null = null
watch(dialog, async value => {
  if (value) {
    exportTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    document.querySelector<HTMLButtonElement>('.collection-dialog button')?.focus()
  } else { await nextTick(); exportTrigger?.focus() }
})
function handleDialogKey(event: KeyboardEvent): void {
  if (event.key === 'Escape' && !busy.value) { event.preventDefault(); dialog.value = null; return }
  if (event.key !== 'Tab') return
  const elements = [...(event.currentTarget as HTMLElement).querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),[tabindex="0"]')]
  if (!elements.length) return
  const first = elements[0]!; const last = elements[elements.length - 1]!
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
let requestId = 0; let detailRequest = 0
const can = (action: string) => session.permissions.includes('*:*:*') || session.permissions.includes(`aden:collection:${action}`)
async function call<T>(action: CollectionAction, input: Record<string, unknown>): Promise<T> {
  const context = workspace.context()
  const result = await window.adenDesktop.collection.execute<T>(collectionRequest(context, action, input))
  const now = workspace.context()
  if (now.workspaceId !== context.workspaceId || now.sessionEpoch !== context.sessionEpoch || now.workspaceEpoch !== context.workspaceEpoch) throw new Error('工作空间已变化，请重新操作')
  return result.value
}
async function run(action: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请重试' } finally { busy.value = false }
}
async function load(number = 1, keepSelection = false): Promise<void> {
  const current = ++requestId
  const priorSelection = [...selected.value]
  const result = await call<CollectionPage>('list', { q: appliedQuery.value, deleted: false, page: number })
  if (current === requestId) {
    page.value = result; selected.value = keepSelection ? priorSelection.filter(id => result.items.some(item => item.itemId === id)) : []
    for (const key of Object.keys(thumbnails)) delete thumbnails[key]
    void (async () => { for (const item of result.items) {
      if (!item.thumbnailAssetId) continue
      try { const data = await call<string>('image', { assetId: item.thumbnailAssetId, thumbnail: true }); if (current === requestId) thumbnails[item.thumbnailAssetId] = data }
      catch { /* 列表缩略图失败不阻断数据，详情读取会显示错误。 */ }
      if (current !== requestId) break
    } })()
  }
}
async function open(itemId: string): Promise<void> {
  const current = ++detailRequest
  const result = await call<CollectionDetail>('detail', { itemId })
  if (current !== detailRequest) return
  detail.value = result; snapshotId.value = result.snapshotId; snapshot.value = result.currentSnapshot ?? result.snapshots[0]
  curation.value = { revision: result.curation.revision, notes: result.curation.notes ?? '', tags: [...(result.curation.tags ?? [])], overrides: { ...result.curation.overrides }, removedImageIds: [...(result.curation.removedImageIds ?? [])], mainImageId: result.curation.mainImageId ?? null, imageOrder: [...(result.curation.imageOrder ?? [])] }
  if (result.curation.snapshotId && result.curation.snapshotId !== result.snapshotId) {
    curation.value.overrides = {}; curation.value.removedImageIds = []; curation.value.mainImageId = null; curation.value.imageOrder = []
  }
  tags.value = curation.value.tags.join('，'); correctedTitle.value = curation.value.overrides.title ?? ''; correctedPrice.value = curation.value.overrides.price ?? ''
  for (const key of Object.keys(images)) delete images[key]
  await loadVersions(1)
}
async function loadVersions(number: number): Promise<void> {
  if (!detail.value) return
  const current = detailRequest
  const result = await call<CollectionVersionPage>('versions', { itemId: detail.value.itemId, page: number })
  if (current === detailRequest) versions.value = result
}
async function selectSnapshot(id: string): Promise<void> {
  if (!detail.value) return
  const current = detailRequest; const itemId = detail.value.itemId
  snapshotId.value = id; snapshot.value = null
  for (const key of Object.keys(images)) delete images[key]
  const result = await call<CollectionSnapshot>('snapshot', { itemId, snapshotId: id })
  if (current === detailRequest && snapshotId.value === id) snapshot.value = result
}
watch(snapshot, async (value) => {
  if (!value) return
  const current = detailRequest
  for (const image of value.images) {
    if (current !== detailRequest) break
    if (!image.assetId || images[image.assetId]) continue
    try { const data = await call<string>('image', { assetId: image.assetId }); if (current === detailRequest) images[image.assetId] = data }
    catch (e) { if (current === detailRequest) error.value = e instanceof Error ? e.message : '图片读取失败' }
  }
})
watch(() => [session.context.sessionEpoch, session.context.workspaceEpoch, workspace.selectedId], () => {
  ++requestId; ++detailRequest; detail.value = null; selected.value = []; page.value = { items: [], total: 0, page: 1, pageSize: 20 }
  snapshot.value = null; versions.value = { items: [], total: 0, page: 1, pageSize: 20 }
  curation.value = { revision: 0, notes: '', tags: [], overrides: {}, removedImageIds: [], mainImageId: null, imageOrder: [] }
  tags.value = ''; correctedTitle.value = ''; correctedPrice.value = ''; notice.value = ''; error.value = ''
  q.value = ''; appliedQuery.value = ''; dialog.value = null; exportPreview.value = null; exportResult.value = null
  for (const key of Object.keys(images)) delete images[key]
  for (const key of Object.keys(thumbnails)) delete thumbnails[key]
  if (workspace.selectedId && session.context.authenticated) void load().catch(e => { error.value = e instanceof Error ? e.message : '读取采集库失败' })
}, { deep: true })
const routeItemId = computed(() => typeof route?.params.itemId === 'string' ? route.params.itemId : '')
watch(routeItemId, id => {
  if (id && workspace.selectedId) void open(id).catch(e => { error.value = e instanceof Error ? e.message : '读取商品详情失败' })
})
onMounted(() => { if (workspace.selectedId) void run(async () => { await load(); if (routeItemId.value) await open(routeItemId.value) }) })
onBeforeUnmount(() => { ++requestId; ++detailRequest })
async function filter(): Promise<void> { appliedQuery.value = q.value; selected.value = []; detail.value = null; dialog.value = null; ++detailRequest; await load(); notice.value = '已更新筛选，已清空选择' }
async function saveCuration(): Promise<void> {
  if (!detail.value || snapshotId.value !== detail.value.snapshotId) return
  const itemId = detail.value.itemId
  await run(async () => {
    const overrides = { ...curation.value.overrides }
    if (correctedTitle.value) overrides.title = correctedTitle.value; else delete overrides.title
    if (correctedPrice.value) { if (!/^\d+(\.\d{1,4})?$/.test(correctedPrice.value)) throw new Error('纠错价格应为非负金额'); overrides.price = correctedPrice.value } else delete overrides.price
    await call('curation', { itemId, curation: { ...curation.value, snapshotId: snapshotId.value, tags: tags.value.split(/[,，]/).map(t => t.trim()).filter(Boolean), overrides } }); await open(itemId); await load(page.value.page, true); notice.value = '人工补充已保存，原始快照保持不变'
  })
}
function toggleImage(imageId: string): void {
  const restoring = curation.value.removedImageIds.includes(imageId)
  curation.value.removedImageIds = restoring ? curation.value.removedImageIds.filter(id => id !== imageId) : [...curation.value.removedImageIds, imageId]
  if (!restoring && curation.value.mainImageId === imageId) curation.value.mainImageId = null
}
function moveImage(imageId: string, delta: number): void {
  const ids = orderedImages.value.map(i => i.imageId); const from = ids.indexOf(imageId); const to = from + delta
  if (to < 0 || to >= ids.length) return
  ;[ids[from], ids[to]] = [ids[to], ids[from]]; curation.value.imageOrder = ids
}
const orderedImages = computed(() => [...(snapshot.value?.images ?? [])].sort((a, b) => {
  if (snapshotId.value !== detail.value?.snapshotId) return a.order - b.order
  const ai = curation.value.imageOrder.indexOf(a.imageId); const bi = curation.value.imageOrder.indexOf(b.imageId)
  return (ai < 0 ? 1000 + a.order : ai) - (bi < 0 ? 1000 + b.order : bi)
}))
const parameterRows = computed<Record<string, unknown>[]>(() => {
  const value = snapshot.value?.fields.parameters
  if (Array.isArray(value)) return value.map(row => typeof row === 'object' && row !== null ? row as Record<string, unknown> : { name: '参数', value: row })
  if (value && typeof value === 'object') return Object.entries(value).map(([name, value]) => ({ name, value }))
  return []
})
const imageGroup = (group: string) => ({ MAIN: '主图', GALLERY: '轮播图', DETAIL: '详情图', MANUAL: '人工添加' }[group.toUpperCase()] ?? group)
const imageStatus = (status: string) => ({ SAVED: '已保存', PENDING: '待保存', FAILED: '保存失败', REMOVED: '已移除' }[status.toUpperCase()] ?? status)
const imageRemoved = (imageId: string) => snapshotId.value === detail.value?.snapshotId && curation.value.removedImageIds.includes(imageId)
const imageMain = (imageId: string) => snapshotId.value === detail.value?.snapshotId && curation.value.mainImageId === imageId
async function upload(): Promise<void> {
  if (!detail.value) return
  const itemId = detail.value.itemId
  await run(async () => { const result = await call<{ canceled: boolean; uploaded: number }>('upload', { itemId, snapshotId: snapshotId.value }); await open(itemId); await load(page.value.page, true); notice.value = result.canceled ? '已取消添加图片' : `已保存 ${result.uploaded} 张本地图片` })
}
async function prepareExport(scope: 'selected' | 'filtered' | 'version'): Promise<void> {
  if (!can('export')) return
  await run(async () => {
    let items: CollectionItem[] = []
    let ids: string[] = []
    let versionId: string | undefined
    if (scope === 'version') {
      if (!detail.value || !snapshot.value) throw new Error('请先选择一个可导出的商品版本')
      ids = [detail.value.itemId]
      versionId = snapshotId.value
    } else if (scope === 'selected') {
      ids = [...selected.value]
      items = page.value.items.filter(item => ids.includes(item.itemId))
    } else {
      if (!page.value.total) throw new Error('当前筛选没有可导出的商品')
      if (page.value.total > 100) throw new Error('单次最多导出 100 个商品，请缩小筛选范围')
      for (let p = 1; items.length < page.value.total; p++) {
        const result = await call<CollectionPage>('list', { q: appliedQuery.value, deleted: false, page: p })
        if (!result.items.length) break
        items.push(...result.items)
      }
      ids = [...new Set(items.map(item => item.itemId))]
      if (ids.length !== page.value.total) throw new Error('商品列表已变化，请刷新后重新确认导出数量')
    }
    if (!ids.length) throw new Error('请先选择商品')
    const imageSaved = scope === 'version' ? snapshot!.value!.images.filter(image => image.assetId).length : items.reduce((sum, item) => sum + (item.imageSaved ?? 0), 0)
    const imageTotal = scope === 'version' ? snapshot!.value!.images.length : items.reduce((sum, item) => sum + (item.imageTotal ?? 0), 0)
    exportPreview.value = { ids, count: ids.length, imageSaved, imageTotal, imageCountKnown: scope === 'version' || items.every(item => item.imageTotal !== undefined && item.imageSaved !== undefined), snapshotId: versionId }
    exportScope.value = scope; exportFormat.value = 'XLSX'; dialog.value = 'export'
  })
}
async function exportItems(): Promise<void> {
  const preview = exportPreview.value
  if (!preview || dialog.value !== 'export' || !can('export')) return
  await run(async () => {
    const input: Record<string, unknown> = { itemIds: preview.ids, format: exportFormat.value }
    if (preview.snapshotId) input.snapshotId = preview.snapshotId
    const result = await call<{ canceled: boolean; filePath?: string }>('export', input)
    if (result.canceled) { notice.value = '已取消导出；商品选择已保留'; return }
    exportResult.value = { count: preview.count, format: exportFormat.value, imageSaved: preview.imageSaved, missing: preview.imageTotal - preview.imageSaved, imageCountKnown: preview.imageCountKnown, filePath: result.filePath ?? '系统保存对话框选择的位置' }
    notice.value = `已导出 ${preview.count} 个商品。`
    dialog.value = null; exportPreview.value = null
  })
}
const formatTime = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
const statusLabel = (status: string) => ({ COMPLETE: '保存完整', COMPLETED: '保存完整', SAVED: '已保存', SAVING: '保存中', PARTIAL: '部分保存', FAILED: '保存失败', UNVERIFIED: '待核对' }[status.toUpperCase()] ?? status)
const display = (value: unknown) => value === undefined || value === null || value === '' ? '未采集' : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)
const fieldName = (key: string) => ({ title: '商品标题', price: '价格数值', priceText: '价格原文', sourceUrl: '来源链接', sku: '商品 SKU', specification: '所选规格', shop: '店铺', parameters: '商品参数', description: '商品描述', stock: '库存原文', delivery: '配送原文' }[key] ?? key)
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">商品采集 · 已保存资料</p><h1>商品结果</h1><p class="lede">每件商品汇总展示，历次采集保留为版本。选择商品后可导出资料。</p></div></header>

  <p v-if="error" role="alert" class="error-banner">{{ error }}</p><p v-if="notice" role="status" class="collection-notice">{{ notice }}</p>

  <form class="toolbar-card collection-toolbar" @submit.prevent="run(filter)"><label>商品标题或 SKU<input v-model="q" placeholder="搜索已采集商品" maxlength="200" /></label><button :disabled="busy || !workspace.selectedId || !can('read')">查询 / 刷新</button></form>
  <div class="collection-actions"><span>已采集商品 {{ page.total }} 件 · 当前第 {{ page.page }} 页</span><div class="collection-buttons"><button :disabled="busy || !page.items.length" @click="selected = page.items.map(item => item.itemId)">全选当前页</button><button :disabled="busy || !selected.length || !can('export')" @click="prepareExport('selected')">导出所选{{ selected.length ? '（' + selected.length + '）' : '' }}</button><button :disabled="busy || !page.total || page.total > 100 || !can('export')" @click="prepareExport('filtered')">导出当前筛选全部</button></div></div><p v-if="page.total > 100" class="collection-muted">单次最多导出 100 个商品，请缩小筛选范围。</p>
  <div v-if="selected.length" class="collection-selection" role="status"><strong>已选择当前页 {{ selected.length }} 个商品</strong><span>用于本次导出；切换筛选或页面会清空选择</span><button :disabled="busy" @click="selected = []">取消选择</button></div>
  <section v-if="exportResult" class="collection-export-result" role="status"><div><strong>导出完成：{{ exportResult.count }} 个商品</strong><p>已写入 {{ exportResult.format === 'ZIP' ? 'ZIP 包（内含 Excel、图片和内容快照）' : 'Excel' }}。{{ exportResult.imageCountKnown ? `预览时已保存图片 ${exportResult.imageSaved} 张、缺失 ${exportResult.missing} 张；以导出文件中的清单为准。` : '图片数量以导出文件中的清单为准。' }}</p><p class="collection-export-path">保存位置：{{ exportResult.filePath }}</p></div><button @click="exportResult = null">关闭提示</button></section>
  <p v-if="busy" role="status">正在处理，请稍候…</p>
  <section v-if="!page.items.length && !busy && !error" class="collection-empty collection-panel"><template v-if="!workspace.selectedId"><h2>登录数据尚未准备好</h2><p>请重新登录，后台将确定你可以查看的资料。</p></template><template v-else-if="!can('read')"><h2>暂时无法查看商品</h2><p>当前账号没有商品读取权限，请联系管理员。</p></template><template v-else-if="appliedQuery"><h2>没有找到匹配的商品</h2><p>试试其他标题或 SKU，也可以清空搜索查看全部。</p><button @click="q = ''; run(filter)">清空搜索</button></template><template v-else><h2>还没有已采集商品</h2><p>在 Chrome 打开商品详情，通过插件提取、预览并确认保存，之后可在这里核对和导出。</p></template></section>
  <section v-else class="collection-layout" :class="{ 'with-detail': detail }"><div><div class="collection-table"><table><thead><tr><th><input type="checkbox" aria-label="选择当前页全部商品" :checked="page.items.length > 0 && selected.length === page.items.length" :indeterminate="selected.length > 0 && selected.length < page.items.length" @change="selected = ($event.target as HTMLInputElement).checked ? page.items.map(i => i.itemId) : []" /></th><th>商品 / SKU</th><th>所选规格 / 页面价格</th><th>已存图片</th><th>最近采集</th></tr></thead><tbody><tr v-for="item in page.items" :key="item.itemId"><td><input v-model="selected" :value="item.itemId" type="checkbox" :aria-label="'选择 ' + item.title" /></td><td><div class="collection-product"><img v-if="item.thumbnailAssetId && thumbnails[item.thumbnailAssetId]" class="collection-thumb" :src="thumbnails[item.thumbnailAssetId]" alt="商品主图" /><div v-else class="collection-thumb collection-thumb-empty" aria-hidden="true">无图</div><div><button class="collection-title" @click="run(() => open(item.itemId))">{{ item.title }}</button><small>{{ item.sku ? 'SKU ' + item.sku : '商品编号待核对' }} · {{ item.platform }}</small><span class="collection-status">{{ statusLabel(item.status) }}</span></div></div></td><td><strong>{{ item.specification || '规格未记录' }}</strong><small>{{ item.priceText || item.price || '价格未采集' }}</small></td><td><span v-if="item.imageTotal !== undefined">图片已保存 {{ item.imageSaved ?? 0 }} / {{ item.imageTotal }}</span><small v-else>图片数量未知</small></td><td>{{ formatTime(item.updatedAt) }}<small>{{ item.version ? '第 ' + item.version + ' 版' : '' }}</small></td></tr></tbody></table></div><div class="collection-pagination"><button :disabled="busy || page.page <= 1" @click="run(() => load(page.page - 1))">上一页</button><span>第 {{ page.page }} 页，共 {{ Math.max(1, Math.ceil(page.total / page.pageSize)) }} 页</span><button :disabled="busy || page.page * page.pageSize >= page.total" @click="run(() => load(page.page + 1))">下一页</button></div></div>
    <aside v-if="detail" class="collection-panel collection-detail"><div class="collection-detail-heading"><h2>{{ detail.title }}</h2><button @click="detail = null; ++detailRequest">关闭详情</button></div><label>采集版本<select v-model="snapshotId" :disabled="busy" @change="run(() => selectSnapshot(snapshotId))"><option v-if="snapshot && !versions.items.some(s => s.snapshotId === snapshotId)" :value="snapshotId">{{ formatTime(snapshot.createdAt) }} · 当前选中版本</option><option v-for="s in versions.items" :key="s.snapshotId" :value="s.snapshotId">{{ s.createdAt }} · {{ s.source }} · {{ s.status }}</option></select></label><div class="collection-buttons"><span>共 {{ versions.total }} 个版本 · 第 {{ versions.page }} 页</span><button :disabled="busy || versions.page <= 1" @click="run(() => loadVersions(versions.page - 1))">上一页版本</button><button :disabled="busy || versions.page * versions.pageSize >= versions.total" @click="run(() => loadVersions(versions.page + 1))">下一页版本</button><button :disabled="busy || snapshotId === detail.snapshotId" @click="run(() => selectSnapshot(detail!.snapshotId))">查看当前版本</button></div><div class="collection-buttons"><button v-for="entry in [['fields','商品信息'],['images','图片'],['snapshot','内容快照']]" :key="entry[0]" :aria-pressed="tab === entry[0]" @click="tab = entry[0]">{{ entry[1] }}</button></div>
      <template v-if="snapshot"><div v-if="tab === 'fields'" class="collection-detail-summary"><h3>原始采集 · 只读</h3><p>来源：{{ snapshot.source }} · 采集时间：{{ formatTime(snapshot.createdAt) }} · {{ statusLabel(snapshot.status) }}</p><p>商品 SKU：{{ display(snapshot.fields.sku) }} · 所选规格：{{ display(snapshot.fields.specification) }}</p><p>页面价格：{{ display(snapshot.fields.priceText ?? snapshot.fields.price) }}</p><p>图片已保存 {{ snapshot.images.filter(i => i.assetId).length }} / {{ snapshot.images.length }} 张</p></div><dl v-if="tab === 'fields'" class="collection-fields"><template v-for="(value,key) in snapshot.fields" :key="key"><dt>{{ fieldName(String(key)) }}（原始）</dt><dd><table v-if="String(key) === 'parameters' && parameterRows.length" class="parameter-table"><tbody><tr v-for="(row,index) in parameterRows" :key="index"><th>{{ display(row.name) }}</th><td>{{ display(row.value) }}<small v-if="row.unit">{{ row.unit }}</small><small v-if="row.unknownReason">{{ row.unknownReason }}</small><small v-if="row.excluded">本次已排除</small></td></tr></tbody></table><template v-else>{{ display(value) }}</template></dd></template></dl><div v-if="tab === 'snapshot'"><p>已加载内容快照 · 原文只读</p><article v-for="(block,index) in snapshot.blocks" :key="index" class="snapshot-block"><strong>{{ block.type }}</strong><p>{{ block.text }}</p><small v-if="block.imageId">图片引用：{{ block.imageId }}</small></article><p v-if="!snapshot.blocks.length">没有已保存的内容区块。</p></div><div v-if="tab === 'images'"><p>图片 {{ snapshot.images.filter(i => i.assetId).length }} / {{ snapshot.images.length }} 已保存</p><button v-if="!detail.deleted" :disabled="busy || !can('edit') || snapshotId !== detail.snapshotId" @click="upload">添加本地图片</button><div class="collection-images"><article v-for="image in orderedImages" :key="image.imageId" :class="{ removed: imageRemoved(image.imageId) }"><img v-if="image.assetId && images[image.assetId]" :src="images[image.assetId]" :alt="`${image.group} 商品图片`" /><p>{{ imageGroup(image.group) }} · {{ imageStatus(image.status) }}<br />{{ image.error }}</p><small v-if="imageMain(image.imageId)">当前主图</small><div v-if="!detail.deleted && snapshotId === detail.snapshotId" class="collection-buttons"><button :disabled="!can('edit')" @click="curation.mainImageId = image.imageId">设主图</button><button :disabled="!can('edit')" @click="toggleImage(image.imageId)">{{ curation.removedImageIds.includes(image.imageId) ? '恢复' : '移除' }}</button><button aria-label="图片前移" :disabled="!can('edit')" @click="moveImage(image.imageId,-1)">前移</button><button aria-label="图片后移" :disabled="!can('edit')" @click="moveImage(image.imageId,1)">后移</button></div></article></div></div></template>
      <form v-if="!detail.deleted && snapshotId === detail.snapshotId" class="collection-form" @submit.prevent="saveCuration"><h3>人工补充 · 不修改原始采集</h3><p class="collection-muted">纠错值和备注用于整理；原始采集内容始终可回看。</p><label>备注<textarea v-model="curation.notes" maxlength="2000" /></label><label>标签（逗号分隔）<input v-model="tags" maxlength="500" /></label><label>纠错标题<input v-model="correctedTitle" maxlength="500" /></label><label>纠错价格<input v-model="correctedPrice" inputmode="decimal" /></label><button :disabled="busy || !can('edit')">保存人工补充及图片整理</button></form><div v-if="!detail.deleted" class="collection-buttons"><button :disabled="busy || !can('export')" @click="prepareExport('version')">导出当前查看的版本</button></div>
    </aside>
  </section>
  <div v-if="dialog" class="collection-dialog-backdrop" @click.self="!busy && (dialog = null)">
    <section class="collection-dialog collection-panel" role="dialog" aria-modal="true" aria-label="确认导出商品" @keydown="handleDialogKey">
      <template v-if="dialog === 'export' && exportPreview">
        <h2>确认导出</h2>
        <p>范围：{{ exportScope === 'version' ? '当前查看的商品版本' : exportScope === 'filtered' ? '当前筛选全部商品' : '当前页选中的商品' }} · {{ exportPreview.count }} 个商品</p>
        <p>版本：{{ exportScope === 'version' ? '所选历史版本' : '每件商品的当前版本' }}。确认生成时固定资料版本。</p>
        <p>图片：{{ exportPreview.imageCountKnown ? `已保存 ${exportPreview.imageSaved} / 候选 ${exportPreview.imageTotal} 张，缺失 ${exportPreview.imageTotal - exportPreview.imageSaved} 张` : '当前列表没有完整的图片数量；以导出清单为准' }}。</p>
        <fieldset><legend>文件组成</legend><label><input v-model="exportFormat" type="radio" value="XLSX" /> Excel 表格（商品汇总、图片清单、快照与异常信息）</label><label><input v-model="exportFormat" type="radio" value="ZIP" /> ZIP 包（内含 Excel 表格、已保存图片和内容快照）</label></fieldset>
        <p class="collection-muted">点击确认后将打开系统保存对话框，你可以在那里选择保存位置。缺失图片和人工移除的图片不会写入 ZIP，具体原因见导出清单。</p>
        <div class="collection-dialog-actions"><button :disabled="busy" @click="dialog = null">返回调整</button><button class="collection-primary" :disabled="busy" @click="exportItems">确认导出 {{ exportPreview.count }} 个商品</button></div>
      </template>

    </section>
  </div>
</template>

<style scoped>
.collection-panel{padding:22px;border:1px solid #d8e1dc;border-radius:12px;background:#fff;color:#24392e;min-width:0}
.collection-empty{max-width:750px;margin:20px auto;padding:30px 36px;line-height:1.7}
.collection-empty h2{margin-top:0}.collection-empty ol{padding-left:24px}.collection-empty li{margin:8px 0}
.collection-product{display:flex;align-items:flex-start;gap:12px;min-width:0}.collection-product>div:last-child{min-width:0}
.collection-thumb-empty{display:grid;place-items:center;background:#eff3f0;color:#6e7e73;font-size:12px;flex-shrink:0}
.collection-status{display:inline-block;border-radius:999px;padding:3px 9px;background:#eaf3ed;color:#23543b;font-size:12px;font-weight:700}
.collection-selection,.collection-export-result{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:12px 16px;margin:14px 0;border-radius:10px;background:#eaf3ed;border:1px solid #b8d2c0}
.collection-selection>span{color:#52675b;margin-right:auto}.collection-export-result>div{flex:1}.collection-export-result p{margin:5px 0 0;line-height:1.6}
.collection-muted{color:#52675b;line-height:1.5}
.collection-dialog-backdrop{position:fixed;inset:0;z-index:50;background:#15271dc9;display:grid;place-items:center;padding:16px}
.collection-dialog{width:min(580px,100%);max-height:calc(100vh - 32px);overflow:auto;box-shadow:0 20px 60px #10231755;line-height:1.6}
.collection-dialog h2{margin-top:0}.collection-dialog fieldset{border:1px solid #d8e1dc;border-radius:8px;padding:12px}.collection-dialog fieldset label{display:flex;gap:8px;margin:10px 0;align-items:flex-start}
.collection-dialog-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:20px;flex-wrap:wrap}.collection-dialog .collection-primary{background:#2f7655;color:#fff;border-color:#2f7655}
.collection-thumb{width:56px;height:56px;object-fit:contain;display:block;margin-bottom:8px}
.collection-help{padding:16px;background:#fff;border:1px solid #d8e1dc;border-radius:12px;margin-bottom:20px}
.collection-help summary{cursor:pointer;font-weight:600}
.collection-notice{padding:12px;background:#eaf3ed;overflow-wrap:anywhere}
.collection-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:16px 0}
.collection-actions label{display:flex;gap:6px;align-items:center;color:#435a4d}
.collection-layout{display:grid;gap:20px;align-items:start}
.collection-layout>div{min-width:0}
.collection-layout.with-detail{grid-template-columns:minmax(0,1fr) minmax(390px,.95fr)}
.collection-table{max-width:100%;overflow-x:auto;border:1px solid #d8e1dc;border-radius:12px;background:#fff}
.collection-table table{border-collapse:collapse;min-width:620px;width:100%;text-align:left}
th,td{padding:12px;border-bottom:1px solid #e6e9ef;vertical-align:top}
th{white-space:nowrap;background:#f8faf9;color:#344c40}
td small{display:block;color:#52675b;margin-top:5px}
td:nth-child(2){min-width:160px;max-width:260px}
.collection-table .collection-title{border:0;background:none;text-align:left;color:#235bc1;padding:0;white-space:normal;overflow-wrap:anywhere;min-width:130px;max-width:250px;font-weight:600}
.collection-pagination{display:flex;justify-content:center;align-items:center;gap:14px;margin:16px}
.collection-detail{overflow-wrap:anywhere;max-height:calc(100vh - 56px);overflow-y:auto;scrollbar-gutter:stable}
.collection-detail-heading{display:flex;align-items:flex-start;flex-wrap:wrap;gap:12px;margin-bottom:20px}
.collection-detail-heading h2{font-size:20px;line-height:1.5;flex:1;min-width:190px;margin:0;color:#24392e}
.collection-detail-heading button{flex:none}
.collection-detail>label,.collection-form label{display:grid;gap:7px;color:#435a4d;font-size:13px}
.collection-fields dt{font-weight:600;margin-top:14px;color:#435a4d}
.collection-fields dd{margin:4px 0;white-space:pre-wrap;line-height:1.6}
.collection-form{display:grid;gap:14px;margin:20px 0}
.collection-form h3{margin:8px 0;color:#24392e}
.collection-buttons{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:14px 0}
.collection-buttons>span{flex:1 0 100%;white-space:normal;line-height:1.6;color:#52675b}
.collection-images{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:14px 0}
.collection-images article{border:1px solid #d8e1dc;padding:12px;border-radius:8px;min-width:0}
.collection-images img{width:100%;height:160px;object-fit:contain;background:#f8faf9}
.collection-images .removed{border-style:dashed;background:#f1f2f4}
.snapshot-block{white-space:pre-wrap;border-bottom:1px solid #d8e1dc;padding:12px 0}
button{min-height:38px;padding:9px 13px;border:1px solid #bdcfc3;border-radius:8px;background:#eef5f0;color:#23543b;cursor:pointer;line-height:1.4;white-space:nowrap;font-weight:600}
button:hover:not(:disabled){background:#e1eee5;border-color:#799d88}
button[aria-pressed=true]{background:#2f7655;color:white;border-color:#2f7655}
button[aria-pressed=true]:hover:not(:disabled){background:#245f43;color:white;border-color:#245f43}
button:disabled{opacity:1;background:#edf0ee;color:#697b70;border-color:#d2dbd5;cursor:not-allowed}
button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible{outline:3px solid #2563eb;outline-offset:2px}
input[type=checkbox]{width:18px;height:18px;flex-shrink:0}
input,textarea,select{max-width:100%;min-width:0;color:#24392e}
.collection-toolbar button{color:white;background:#2f7655}
.collection-toolbar button:disabled{color:#697b70;background:#edf0ee}
@media(max-width:1200px){.collection-layout.with-detail{grid-template-columns:minmax(0,1fr)}.collection-detail{grid-row:1;max-height:calc(100vh - 48px)}.collection-toolbar{grid-template-columns:minmax(180px,1fr) 150px auto}}
.collection-toolbar{grid-template-columns:minmax(220px,1fr) auto}.parameter-table{width:100%;border-collapse:collapse;font-size:12px}.parameter-table th,.parameter-table td{padding:8px 10px;border-bottom:1px solid #e6ede7;text-align:left;overflow-wrap:anywhere}.parameter-table th{color:#647b6c;background:#f6f8f5;width:35%}.parameter-table small{display:block;color:#8b9a90;font-size:11px;margin-top:4px}
</style>
