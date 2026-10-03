<template>
  <div class="app-container">
    <el-card><el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <template #header><div class="head"><span>资源数据就绪</span><el-button type="primary" :disabled="!workshopId" @click="loadIssues">重新检查</el-button></div></template>
      <el-form inline>
        <el-form-item label="车间"><el-select v-model="workshopId" style="width: 300px"><el-option v-for="item in workshops" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="检查时刻"><el-date-picker v-model="at" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item>
      </el-form>
      <el-result v-if="checked && issues.length === 0" icon="success" title="资源主数据已就绪" sub-title="此结论只覆盖资源与绝对日历，请使用完整排程预检检查订单和工艺。" />
      <el-table v-else :data="issues" empty-text="请选择车间并执行检查">
        <el-table-column prop="code" label="问题码" width="240" />
        <el-table-column prop="objectType" label="对象类型" width="130" />
        <el-table-column label="业务对象" min-width="180"><template #default="v">{{names[v.row.objectId]||v.row.objectType}}</template></el-table-column><el-table-column label="操作"><template #default="v"><a :href="issueLocation(v.row.objectType,v.row.objectId)">进入资料修正</a></template></el-table-column>
        <el-table-column prop="message" label="说明" min-width="240" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listReadiness, getResourceDirectory } from '@/api/aps/resource'
import type { ApsReadinessIssue, ApsWorkshop } from '@/types/aps/resource'

const workshops = ref<ApsWorkshop[]>([])
const workshopId = ref('')
import {getSiteContext,type ApsSiteContext} from '@/api/aps/context'
import {siteTime,siteTimeToUtc,operationError} from '@/components/aps/shared/site-time'
import {issueLocation} from '../workbench/plan-request-model'
const site=ref<ApsSiteContext>(),error=ref(''),names=ref<Record<string,string>>({}),at=ref('')
const issues = ref<ApsReadinessIssue[]>([])
const checked = ref(false)
async function loadIssues(){if(!workshopId.value||!site.value)return;checked.value=false;error.value='';try{issues.value=await listReadiness(workshopId.value,siteTimeToUtc(at.value,site.value.zoneId));checked.value=true}catch(e){error.value=operationError(e)}}
onMounted(async()=>{try{const [d,c]=await Promise.all([getResourceDirectory(),getSiteContext()]);site.value=c;workshops.value=d.workshops;names.value=Object.fromEntries([...d.workshops,...d.centers,...d.resources].map(o=>[o.id,o.name]));at.value=siteTime(new Date().toISOString(),c.zoneId);workshopId.value=workshops.value[0]?.id||''}catch(e){error.value=operationError(e)}})
</script>

<style scoped>.head { display: flex; justify-content: space-between; align-items: center; }</style>
