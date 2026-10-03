<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useWorkspaceStore } from '../../stores/workspace'
import { useSessionStore } from '../../stores/session'
import { collectionRequest } from '../collection/collection-request'
import type { CollectionOverview, CollectionTaskSummary } from '../../../../shared/contracts/collection'
const workspace = useWorkspaceStore()
const session = useSessionStore()
const loading = ref(false)
const error = ref('')
const summary = ref<CollectionOverview | null>(null)
let requestId = 0
const canRead = computed(() => session.permissions.includes('*:*:*') || session.permissions.includes('aden:collection:read'))
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
const taskLabel = (task: CollectionTaskSummary) => task.taskState === 'FAILED' ? '任务失败' : task.status === 'PARTIAL' ? '部分保存' : task.status === 'SAVED' ? '已保存' : '保存中'
async function loadSummary(): Promise<void> {
  const current = ++requestId
  summary.value = null; error.value = ''
  if (!workspace.selectedId || !canRead.value) return
  loading.value = true
  try {
    const context = workspace.context()
    const result = await window.adenDesktop.collection.execute<CollectionOverview>(collectionRequest(context, 'overview', {}))
    if (current !== requestId || context.workspaceId !== workspace.selectedId || context.sessionEpoch !== session.context.sessionEpoch || context.workspaceEpoch !== session.context.workspaceEpoch) return
    summary.value = result.value
  } catch (cause) { if (current === requestId) error.value = cause instanceof Error ? cause.message : '暂时无法读取工作台数据。' }
  finally { if (current === requestId) loading.value = false }
}
watch(() => [workspace.selectedId, session.context.sessionEpoch, session.context.workspaceEpoch], () => { void loadSummary() }, { immediate: true })
onBeforeUnmount(() => { ++requestId })
</script>

<template>
  <div class="collection-home">
    <header class="collection-home-header"><div><p class="eyebrow">Aden · 商品采集</p><h1>工作台</h1><p class="lede">最近的商品资料、采集记录和需要处理的事项。</p></div><button class="home-refresh" :disabled="loading || !canRead" @click="loadSummary">{{ loading ? '读取中…' : '刷新数据' }}</button></header>
    <p v-if="error" class="error-banner" role="alert">读取失败：{{ error }}</p><p v-else-if="!canRead" class="home-empty">当前账号没有查看采集数据的权限。</p>
    <div class="home-stat-row">
      <section class="home-panel home-stat"><span class="home-kicker">已保存商品</span><strong>{{ summary?.savedProductCount ?? '—' }}</strong><small>按商品计数，同一 SKU 的历次采集合并展示</small></section>
      <section class="home-panel home-stat"><span class="home-kicker">今日采集任务</span><strong>{{ summary?.todayCaptureCount ?? '—' }}</strong><small>今天确认保存的次数 · 上海时间</small></section>
      <section class="home-panel home-stat"><span class="home-kicker">待处理</span><strong>{{ summary?.pendingTaskCount ?? '—' }}</strong><small>需要核对的失败或部分缺失任务</small></section>
    </div>
    <p v-if="loading" role="status" class="home-loading">正在读取统计和近期记录…</p>
    <div class="home-content-grid">
      <section class="home-panel"><div class="home-panel-heading"><h2>最近保存的商品</h2><span>最近五条</span></div>
        <ul v-if="summary?.recentItems.length" class="home-products"><li v-for="item in summary.recentItems" :key="item.itemId"><RouterLink :to="'/collection/items/' + encodeURIComponent(item.itemId)"><div><strong>{{ item.title }}</strong><small>{{ item.sku ? 'SKU ' + item.sku : '商品编号待核对' }} · {{ item.specification || '规格未记录' }}</small><small>{{ time(item.updatedAt) }}</small></div><span>{{ item.imageTotal === undefined ? '图片数量未知' : '图片 ' + (item.imageSaved ?? 0) + '/' + item.imageTotal }}</span></RouterLink></li></ul>
        <p v-else-if="summary" class="home-empty">还没有已保存商品。</p>
      </section>
      <section class="home-panel"><div class="home-panel-heading"><h2>最近采集任务</h2><span>最近五条</span></div>
        <ul v-if="summary?.recentTasks.length" class="home-products"><li v-for="task in summary.recentTasks" :key="task.taskId"><RouterLink :to="'/collection/tasks/' + encodeURIComponent(task.taskId)"><div><strong>{{ task.title }}</strong><small>{{ task.specification || '规格未记录' }} · {{ time(task.createdAt) }}</small></div><span class="home-chip" :class="{ warning: task.status === 'PARTIAL' || task.taskState === 'FAILED' }">{{ taskLabel(task) }}</span></RouterLink></li></ul>
        <p v-else-if="summary" class="home-empty">今天可以从一次采集开始。</p>
      </section>
    </div>
    <section class="home-panel home-pending"><div class="home-panel-heading"><h2>待处理事项</h2><span>{{ summary ? summary.pendingTaskCount + ' 项' : '—' }}</span></div><ul v-if="summary?.pendingTasks.length" class="home-products"><li v-for="task in summary.pendingTasks" :key="task.taskId"><RouterLink :to="'/collection/tasks/' + encodeURIComponent(task.taskId)"><div><strong>{{ task.title }}</strong><small>{{ task.errorMessage || (task.imageFailed ? task.imageFailed + ' 张图片保存失败，请核对实际结果。' : '内容存在缺失，请进入任务核对。') }}</small></div><span>查看任务 →</span></RouterLink></li></ul><p v-else-if="summary" class="home-empty">当前没有需要处理的事项。</p></section>
    <small v-if="summary" class="home-updated">数据更新于 {{ time(summary.updatedAt) }}</small>
  </div>
</template>

<style scoped>
.collection-home{display:grid;gap:20px;max-width:1280px}.collection-home-header{display:flex;justify-content:space-between;align-items:center;gap:25px}.collection-home-header h1{font-size:29px}.home-refresh{padding:9px 14px;border:1px solid #c2d3c6;border-radius:8px;background:#fff;color:#50745c;font-size:12px;cursor:pointer}.home-panel{min-width:0;padding:23px;border:1px solid #dce5de;border-radius:14px;background:#fff}.home-kicker{display:block;color:#698071;font-size:12px;font-weight:700}.home-stat-row{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.home-stat{display:grid;align-content:start;gap:14px}.home-stat strong{font-size:34px;color:#204c35;font-variant-numeric:tabular-nums}.home-stat small{color:#86938a;font-size:11px;line-height:1.5}.home-content-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.home-panel-heading{display:flex;justify-content:space-between;gap:12px;align-items:center}.home-panel-heading h2{font-size:17px;margin:0}.home-panel-heading>span{font-size:11px;color:#87958b}.home-products{padding:0;margin:18px 0 0;list-style:none}.home-products li{border-top:1px solid #e6ece8}.home-products li>a{display:flex;justify-content:space-between;gap:16px;padding:16px 0;align-items:center}.home-products li>a:hover strong{color:#327653}.home-products strong,.home-products small{display:block;overflow-wrap:anywhere}.home-products strong{font-size:13px;line-height:1.6;color:#3a5241}.home-products small,.home-products a>span{margin-top:5px;color:#85958a;font-size:11px;line-height:1.5}.home-products a>span{flex-shrink:0}.home-products .home-chip{padding:5px 8px;background:#edf5ee;border-radius:6px;color:#548260}.home-products .home-chip.warning{background:#fff4df;color:#9e792f}.home-empty{margin:18px 0 0;padding:18px;background:#f7f9f6;color:#829487;border-radius:8px;font-size:12px}.home-loading,.home-updated{color:#88998e;font-size:11px}.home-pending{padding-bottom:16px}@media(max-width:1100px){.home-content-grid{grid-template-columns:1fr}.home-stat{padding:20px 18px}.home-stat small{min-height:32px}}
</style>
