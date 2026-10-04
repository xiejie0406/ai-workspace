<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from './stores/session'
import { useWorkspaceStore } from './stores/workspace'
import { useConnectionStore } from './stores/connection'

const session = useSessionStore()
const workspace = useWorkspaceStore()
const connection = useConnectionStore()
const router = useRouter()
const runtime = window.adenDesktop.getRuntimeInfo()
const userName = computed(() => String(session.user?.nickName ?? session.user?.userName ?? '操作员'))

async function logout(): Promise<void> {
  connection.stopListeners()
  workspace.resetWorkspaceData()
  await session.logout()
  await router.replace('/login')
}
</script>

<template>
  <div v-if="session.phase === 'authenticated'" class="app-shell">
    <aside class="sidebar" aria-label="Aden 主导航">
      <div class="brand"><span class="brand-mark">A</span><div><strong>Aden</strong><small>桌面智能执行平台</small></div></div>
      <nav class="nav-list">
        <RouterLink class="nav-item" active-class="nav-item-active" to="/tasks">⌘ 任务中心</RouterLink>
        <RouterLink class="nav-item" active-class="nav-item-active" to="/collection">▤ 商品采集</RouterLink>
        <RouterLink class="nav-item" active-class="nav-item-active" to="/capabilities">◇ 能力总览</RouterLink>
        <RouterLink class="nav-item" active-class="nav-item-active" to="/about">ⓘ 关于</RouterLink>
      </nav>
      <div class="sidebar-note"><span class="status-dot" :class="{ 'status-dot-muted': connection.state !== 'LIVE' }"></span><div><strong>{{ userName }}</strong><small>{{ workspace.selected?.displayName ?? '尚未选择 Workspace' }} · {{ connection.state }}</small></div></div>
      <button class="sidebar-logout" type="button" @click="logout">退出登录</button>
    </aside>
    <main class="main-content"><RouterView /></main>
  </div>
  <RouterView v-else />
  <span class="runtime-version">Electron {{ runtime.versions.electron }} · {{ runtime.platform }}</span>
</template>
