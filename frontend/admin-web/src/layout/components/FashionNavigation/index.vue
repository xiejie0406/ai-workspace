<template>
  <div class="fashion-navigation">
    <router-link class="workspace-brand" to="/index" aria-label="返回工作区首页">智能选品</router-link>
    <nav aria-label="智能选品业务菜单" class="business-menus">
      <router-link v-for="menu in menus" :key="menu.path" :to="menu.path"
        :class="{ active: activePath === menu.path }" :aria-current="activePath === menu.path ? 'page' : undefined">
        {{ menu.title }}
      </router-link>
    </nav>
    <el-dropdown v-if="settings.length" trigger="click" class="workspace-settings" @command="navigate">
      <el-button text aria-label="智能选品设置">设置<el-icon><ArrowDown /></el-icon></el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="menu in settings" :key="menu.path" :command="menu.path">{{ menu.title }}</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>
<script setup lang="ts">
import { computed, watch, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowDown } from '@element-plus/icons-vue';
import useUserStore from '@/store/modules/user';
import { useDynamicTitle } from '@/utils/dynamicTitle';
import { fashionMenus, fashionSettingsMenus, fashionActivePath, visibleFashionMenus } from './menu';
const route = useRoute();
const router = useRouter();
const user = useUserStore();
const menus = computed(() => visibleFashionMenus(fashionMenus, user.permissions));
const settings = computed(() => visibleFashionMenus(fashionSettingsMenus, user.permissions));
const activePath = computed(() => fashionActivePath(route.path));
watch(() => [route.path, route.meta.title], () => {
  const title = route.meta.title || [...fashionMenus, ...fashionSettingsMenus].find(menu => menu.path === activePath.value)?.title;
  document.title = `${title || '工作区'} - 智能选品`;
}, { immediate: true, flush: 'post' });
onBeforeUnmount(() => useDynamicTitle());
function navigate(path: string) { router.push(path); }
</script>
<style scoped>
.fashion-navigation { display:flex; align-items:center; flex:1; min-width:0; height:50px; gap:16px; }
.workspace-brand { font-size:17px; font-weight:700; color:var(--el-text-color-primary); white-space:nowrap; padding-left:18px; }
.business-menus { display:flex; align-items:center; gap:2px; flex:1; min-width:0; height:50px; overflow-x:auto; }
.business-menus a { padding:0 12px; height:50px; display:flex; align-items:center; white-space:nowrap; color:var(--el-text-color-regular); border-bottom:3px solid transparent; }
.business-menus a:hover { background:var(--el-fill-color-light); }
.business-menus a.active { color:var(--el-color-primary); border-color:var(--el-color-primary); font-weight:600; }
.workspace-settings { flex-shrink:0; }
@media(max-width:1100px) { .fashion-navigation { gap:8px; } .business-menus a { padding:0 9px; } }
</style>
