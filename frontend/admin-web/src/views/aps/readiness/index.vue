<template><div class="app-container"><el-tabs><el-tab-pane label="完整排程预检"><PlanLauncher readiness-only :initial-order-id="orderId" /></el-tab-pane><el-tab-pane label="资源资料检查"><ResourceReadiness /></el-tab-pane></el-tabs></div></template>
<script setup lang="ts">
import PlanLauncher from '@/views/aps/workbench/PlanLauncher.vue'
import ResourceReadiness from './ResourceReadiness.vue'
const orderId=new URLSearchParams(location.search).get('orderId')||''
</script>
