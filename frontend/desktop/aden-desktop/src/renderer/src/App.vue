<script setup lang="ts">
import { computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from './stores/session'
import { useWorkspaceStore } from './stores/workspace'
import { useConnectionStore } from './stores/connection'

const session = useSessionStore()
const workspace = useWorkspaceStore()
const connection = useConnectionStore()
const router = useRouter()
const stopLibraryListener = window.adenDesktop.collection.onOpenLibrary((target) => {
  if (session.phase === 'authenticated') void router.push(target?.view === 'task' ? target.taskId ? '/collection/tasks/' + encodeURIComponent(target.taskId) : '/collection/tasks' : '/collection')
})
onUnmounted(stopLibraryListener)
const userName = computed(() => String(session.user?.nickName ?? session.user?.userName ?? '操作员'))

async function logout(): Promise<void> {
  connection.stopListeners()
  workspace.resetWorkspaceData()
  await session.logout()
  await router.replace('/login')
}
</script>

<template>
  <div v-if="session.phase === 'authenticated'" class="app-shell collection-shell">
    <aside class="sidebar" aria-label="Aden 主导航">
      <div class="brand"><span class="brand-mark">A</span><div><strong>Aden</strong><small>桌面智能执行平台</small></div></div>
      <nav class="nav-list">
        <RouterLink class="nav-item" active-class="nav-item-active" to="/home">⌂ 工作台</RouterLink>
        <RouterLink class="nav-item" active-class="nav-item-active" to="/collection/tasks">◷ 采集任务</RouterLink>
        <RouterLink class="nav-item" active-class="nav-item-active" :class="{ 'nav-item-active': $route.path.startsWith('/collection/items/') }" to="/collection">▣ 商品结果</RouterLink>
        <RouterLink class="nav-item" active-class="nav-item-active" to="/collection/exports">⇩ 导出记录</RouterLink>
        <RouterLink class="nav-item" active-class="nav-item-active" to="/settings">⚙ 设置</RouterLink>
        <RouterLink class="nav-item" active-class="nav-item-active" to="/about">ⓘ 关于</RouterLink>
      </nav>
      <div class="sidebar-note"><div><strong>{{ userName }}</strong><small>当前登录账号</small></div></div>
      <button class="sidebar-logout" type="button" @click="logout">退出登录</button>
    </aside>
    <main class="main-content"><RouterView /></main>
  </div>
  <RouterView v-else />
</template>

<style scoped>
.collection-shell > .sidebar { position: fixed; inset: 0 auto 0 0; width: 220px; z-index: 2; }
.collection-shell > .main-content { grid-column: 2; width: 100%; min-width: 0; padding: 28px 28px 48px; }
</style>
