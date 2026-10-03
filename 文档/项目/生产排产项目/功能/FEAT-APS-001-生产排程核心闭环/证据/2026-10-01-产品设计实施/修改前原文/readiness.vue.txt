<template>
  <div class="app-container">
    <el-card>
      <template #header><div class="head"><span>资源数据就绪</span><el-button type="primary" :disabled="!workshopId" @click="loadIssues">重新检查</el-button></div></template>
      <el-form inline>
        <el-form-item label="车间"><el-select v-model="workshopId" style="width: 300px"><el-option v-for="item in workshops" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="检查时刻"><el-date-picker v-model="at" type="datetime" /></el-form-item>
      </el-form>
      <el-result v-if="checked && issues.length === 0" icon="success" title="资源主数据已就绪" sub-title="此结论只覆盖 IMP-03 的资源与绝对日历规则，不代表可以自动排程。" />
      <el-table v-else :data="issues" empty-text="请选择车间并执行检查">
        <el-table-column prop="code" label="问题码" width="240" />
        <el-table-column prop="objectType" label="对象类型" width="130" />
        <el-table-column prop="objectId" label="对象 ID" min-width="230" />
        <el-table-column prop="message" label="说明" min-width="240" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listReadiness, listWorkshops } from '@/api/aps/resource'
import type { ApsReadinessIssue, ApsWorkshop } from '@/types/aps/resource'

const workshops = ref<ApsWorkshop[]>([])
const workshopId = ref('')
const at = ref(new Date())
const issues = ref<ApsReadinessIssue[]>([])
const checked = ref(false)
async function loadIssues() { if (!workshopId.value) return; issues.value = await listReadiness(workshopId.value, at.value.toISOString()); checked.value = true }
onMounted(async () => { workshops.value = await listWorkshops(); workshopId.value = workshops.value[0]?.id ?? '' })
</script>

<style scoped>.head { display: flex; justify-content: space-between; align-items: center; }</style>
