<template>
  <div class="aipicture-host">
    <el-alert v-if="failure" title="图片编辑业务未加载" :description="failure" type="error" :closable="false" show-icon />
    <el-button v-if="failure" class="retry" @click="load">重新加载</el-button>
    <div v-show="!failure" ref="container" v-loading="loading" class="workbench-container" />
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import useUserStore from '@/store/modules/user'
import { createAiPictureHost, loadAiPictureWorkbench } from '@/api/aipicture/bridge'

const props = defineProps({ page: { type: String, required: true } })
const route = useRoute()
const router = useRouter()
const user = useUserStore()
const container = ref()
const loading = ref(true)
const failure = ref('')
const active = ref(true)
let instance
let disposed = false
let generation = 0

function host() {
  return createAiPictureHost({
    user,
    router,
    page: props.page,
    params: {
      sessionId: route.params.sessionId || route.query.sessionId,
      runId: route.params.runId || route.query.runId
    },
    active: active.value && Boolean(user.id && user.permissions.length)
  })
}

async function load() {
  const attempt = ++generation
  loading.value = true
  failure.value = ''
  instance?.unmount()
  instance = undefined
  try {
    const library = await loadAiPictureWorkbench()
    if (disposed || attempt !== generation) return
    if (typeof library.mount !== 'function') throw new Error('业务库缺少挂载入口，请重新构建图片业务制品')
    instance = library.mount(container.value, host())
  } catch (error) {
    if (!disposed && attempt === generation) failure.value = `${error?.message || '加载失败'}。请运行图片业务构建与同步命令。`
  } finally {
    if (!disposed && attempt === generation) loading.value = false
  }
}

// 退出后即刻使原实例失活/清原身份草稿，不能等跳转销毁后再处理。
watch(() => [props.page, route.fullPath, user.id, user.token, user.permissions.join('|')], () => instance?.update(host()), { flush: 'sync' })
onMounted(load)
onActivated(() => { active.value = true; instance?.update(host()) })
onDeactivated(() => { active.value = false; instance?.update(host()) })
onBeforeUnmount(() => { disposed = true; generation++; instance?.unmount(); instance = undefined })
</script>

<style scoped>
.aipicture-host { padding: 12px; min-height: 420px; }
.workbench-container { height: calc(100vh - 144px); min-height: 420px; width: 100%; }
.retry { margin-top: 12px; }
@media (max-width: 1024px) { .aipicture-host { padding: 8px; } }
</style>
