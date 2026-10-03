<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '../../stores/session'
import { useWorkspaceStore } from '../../stores/workspace'
import { collectionRequest } from './collection-request'
import type { CollectionExportDetail, CollectionExportPage, CollectionExportRecord } from '../../../../shared/contracts/collection'
const session = useSessionStore()
const workspace = useWorkspaceStore()
const page = ref<CollectionExportPage>({ items: [], total: 0, page: 1, pageSize: 20 })
const loading = ref(false)
const downloading = ref('')
const error = ref('')
const notice = ref('')
const detail = ref<CollectionExportDetail | null>(null)
const detailLoading = ref(false)
let detailGeneration = 0
let generation = 0
const canRead = computed(() => session.permissions.includes('*:*:*') || session.permissions.includes('aden:collection:read'))
const canExport = computed(() => session.permissions.includes('*:*:*') || session.permissions.includes('aden:collection:export'))
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
async function load(number = 1): Promise<void> {
  const request = ++generation
  page.value = { items: [], total: 0, page: number, pageSize: 20 }; error.value = ''; notice.value = ''
  if (!canRead.value || !workspace.selectedId) return
  loading.value = true
  try {
    const context = workspace.context()
    const result = await window.adenDesktop.collection.execute<CollectionExportPage>(collectionRequest(context, 'exports', { page: number }))
    if (request === generation && context.sessionEpoch === session.context.sessionEpoch && context.workspaceEpoch === session.context.workspaceEpoch) page.value = result.value
  } catch (cause) { if (request === generation) error.value = cause instanceof Error ? cause.message : '导出记录读取失败。' }
  finally { if (request === generation) loading.value = false }
}
async function download(record: CollectionExportRecord): Promise<void> {
  if (!canExport.value || downloading.value || !record.downloadAvailable) return
  const request = generation
  downloading.value = record.exportId; error.value = ''; notice.value = ''
  try {
    const context = workspace.context()
    const result = await window.adenDesktop.collection.execute<{ canceled: boolean; filePath?: string }>(collectionRequest(context, 'downloadExport', { exportId: record.exportId }))
    if (request === generation && context.sessionEpoch === session.context.sessionEpoch && context.workspaceEpoch === session.context.workspaceEpoch) notice.value = result.value.canceled ? '已取消保存。' : '文件已保存至：' + result.value.filePath
  } catch (cause) { if (request === generation) error.value = cause instanceof Error ? cause.message : '下载失败，请重新生成。' }
  finally { if (request === generation) downloading.value = '' }
}
async function showDetail(exportId: string): Promise<void> {
  const request = ++detailGeneration
  detailLoading.value = true; detail.value = null; error.value = ''
  try {
    const context = workspace.context()
    const result = await window.adenDesktop.collection.execute<CollectionExportDetail>(collectionRequest(context, 'exportDetail', { exportId }))
    if (request === detailGeneration && context.sessionEpoch === session.context.sessionEpoch && context.workspaceEpoch === session.context.workspaceEpoch) detail.value = result.value
  } catch (cause) { if (request === detailGeneration) error.value = cause instanceof Error ? cause.message : '导出详情读取失败。' }
  finally { if (request === detailGeneration) detailLoading.value = false }
}
watch(() => [workspace.selectedId, session.context.sessionEpoch, session.context.workspaceEpoch], () => { ++detailGeneration; detail.value = null; detailLoading.value = false; downloading.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { ++generation; ++detailGeneration })
</script>

<template>
  <div class="collection-exports">
    <header class="topbar"><div><p class="eyebrow">商品采集 · 文件记录</p><h1>导出记录</h1><p class="lede">查看已生成的资料，按当时确定的商品和版本再次保存文件。</p></div><button class="export-refresh" :disabled="loading || !!downloading || !canRead" @click="load(page.page)">刷新</button></header>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p><p v-if="notice" class="export-notice" role="status">{{ notice }}</p>
    <section class="export-panel"><div class="export-panel-heading"><h2>已生成文件</h2><span>共 {{ page.total }} 条</span></div><p v-if="loading" role="status" class="export-empty">正在读取导出记录…</p><p v-else-if="!canRead" class="export-empty">当前账号没有查看导出记录的权限。</p><p v-else-if="!page.items.length && !error" class="export-empty">还没有导出记录。在商品结果中选择商品并确认导出。</p>
      <div v-if="page.items.length" class="export-table"><table><thead><tr><th>文件与格式</th><th>生成时间</th><th>内容</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="record in page.items" :key="record.exportId"><td><button class="export-title" @click="showDetail(record.exportId)">{{ record.fileName }}</button><small>{{ record.format === 'ZIP' ? 'Excel + 图片与快照' : 'Excel 表格' }} · {{ Math.ceil(record.size / 1024) }} KB</small></td><td>{{ time(record.createdAt) }}</td><td>{{ record.itemCount }} 个商品<small>图片 {{ record.imageSaved }} 张 · 缺失 {{ record.imageMissing }} 张</small></td><td><span class="export-chip" :class="{ unavailable: !record.downloadAvailable }">{{ record.status === 'COMPLETED' && record.downloadAvailable ? '已生成' : '不可下载' }}</span><small v-if="record.unavailableReason">{{ record.unavailableReason }}</small></td><td><button :disabled="!canExport || !record.downloadAvailable || !!downloading" @click="download(record)">{{ downloading === record.exportId ? '保存中…' : '保存文件' }}</button><RouterLink v-if="!record.downloadAvailable" to="/collection">重新选择商品</RouterLink></td></tr></tbody></table></div>
      <div class="export-pagination"><button :disabled="loading || page.page <= 1 || !!downloading" @click="load(page.page - 1)">上一页</button><span>第 {{ page.page }} / {{ Math.max(1, Math.ceil(page.total / page.pageSize)) }} 页</span><button :disabled="loading || page.page * page.pageSize >= page.total || !!downloading" @click="load(page.page + 1)">下一页</button></div>
    </section>
    <p v-if="detailLoading" role="status" class="export-empty">正在读取导出详情…</p>
    <section v-if="detail" class="export-panel"><div class="export-panel-heading"><h2>{{ detail.fileName }}</h2><button class="export-refresh" @click="detail = null; ++detailGeneration">关闭详情</button></div><p class="export-empty">生成于 {{ time(detail.createdAt) }} · {{ detail.itemCount }} 个商品 · 图片 {{ detail.imageSaved }} 张，缺失 {{ detail.imageMissing }} 张</p><div class="export-table"><table><thead><tr><th>商品</th><th>固定快照版本</th></tr></thead><tbody><tr v-for="(selection,index) in detail.selections" :key="index"><td>{{ selection.title ?? selection.itemId ?? '商品记录' }}</td><td>{{ selection.snapshotId ?? '版本见文件清单' }}</td></tr></tbody></table></div><p v-if="!detail.downloadAvailable" class="export-empty">{{ detail.unavailableReason ?? '文件不可下载，请重新选择商品生成。' }}</p></section>
  </div>
</template>

<style scoped>
.collection-exports{display:grid;gap:21px;max-width:1280px}.export-panel{padding:23px;border:1px solid #dce5de;border-radius:14px;background:#fff;min-width:0}.export-panel-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:19px}.export-panel-heading h2{font-size:17px;margin:0}.export-panel-heading>span,.export-pagination>span{font-size:12px;color:#829389}.export-refresh,.export-table button,.export-pagination button{padding:8px 12px;min-height:37px;border:1px solid #bed0c4;border-radius:7px;color:#396b4d;background:#fff;cursor:pointer;font-size:12px}.export-table{overflow-x:auto}.export-table table{width:100%;border-collapse:collapse;text-align:left;font-size:12px}.export-table th{padding:12px 10px;color:#718676;background:#f6f8f5;font-weight:600;white-space:nowrap}.export-table td{padding:18px 10px;border-bottom:1px solid #e7ede8;color:#5b7564;vertical-align:top;overflow-wrap:anywhere}.export-table td:first-child{min-width:180px;max-width:260px}.export-table td strong{color:#3b5443;font-size:13px}.export-table small{display:block;line-height:1.7;color:#8b9b90;font-size:11px;margin-top:7px}.export-table a{display:block;margin-top:7px;color:#47795b}.export-chip{display:inline-block;padding:4px 7px;border-radius:5px;background:#edf5ef;color:#56805f;font-size:11px}.export-chip.unavailable{background:#fff4df;color:#9c7932}.export-empty{margin:0;padding:28px 0;color:#87988b;font-size:13px}.export-pagination{display:flex;justify-content:flex-end;align-items:center;gap:13px;margin-top:21px}.export-notice{padding:13px 15px;border:1px solid #cde1d1;border-radius:9px;background:#f1f8f2;color:#42734f;font-size:13px;overflow-wrap:anywhere}
.export-table button.export-title{border:0;background:none;padding:0;color:#3b6449;min-height:0;text-align:left;font-weight:700;font-size:13px}
</style>
