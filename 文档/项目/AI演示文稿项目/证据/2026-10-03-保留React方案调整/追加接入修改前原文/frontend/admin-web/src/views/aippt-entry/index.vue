<template>
  <div class="app-container aippt-entry">
    <el-result v-if="denied" icon="warning" title="暂无演示文稿访问权限" sub-title="请联系管理员分配所需权限。">
      <template #extra><el-button @click="router.push('/index')">返回首页</el-button></template>
    </el-result>
    <p v-else role="status">正在打开演示文稿工作台…</p>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { checkPermi } from '@/utils/permission'
import { aipptEntryPermission, normalizeAipptReturnTo } from '@/utils/aipptEntry'

const route = useRoute()
const router = useRouter()
const denied = ref(false)

onMounted(() => {
  const destination = normalizeAipptReturnTo(route.query.returnTo)
  if (!checkPermi([aipptEntryPermission(destination)])) {
    denied.value = true
    return
  }
  window.location.replace(destination)
})
</script>

<style scoped>
.aippt-entry { min-height: 50vh; display: grid; place-items: center; }
</style>
