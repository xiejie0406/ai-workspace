<template>
  <el-card class="plan-launcher" shadow="never">
    <template #header><strong>{{readinessOnly?'完整排程数据预检':'新建排程与选择计划'}}</strong></template>
    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />
    <el-form inline label-position="top" @submit.prevent="submit">
      <el-form-item label="站点"><el-input :model-value="site ? `${site.siteCode} · ${site.zoneId}` : '站点配置未就绪'" readonly style="width:280px"/></el-form-item>
      <el-form-item label="车间">
        <el-select v-model="form.workshopIds" multiple filterable placeholder="选择车间" style="width: 260px">
          <el-option v-for="value in workshops" :key="value.id" :label="`${value.code} · ${value.name}`" :value="value.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="已释放订单">
        <el-select v-model="form.orderIds" multiple filterable placeholder="选择已释放订单" style="width: 320px">
          <el-option v-for="value in orders" :key="value.id" :label="`${value.orderNo} · ${value.lines.length}个产品行`" :value="value.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="排程开始（站点时间）"><el-date-picker v-model="form.start" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width: 210px" /></el-form-item>
      <el-form-item label="排程结束（站点时间）"><el-date-picker v-model="form.end" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width: 210px" /></el-form-item>
      <el-form-item label="求解上限（秒）"><el-input-number v-model="form.maxSolveSeconds" :min="1" :max="3600" style="width: 140px" /></el-form-item>
      <el-form-item label="操作">
        <el-button v-if="!readinessOnly" v-hasPermi="['aps:planning:solve']" type="primary" native-type="submit" :loading="saving" :disabled="!canSubmit || pending">发起排程</el-button>
        <el-button v-hasPermi="['aps:readiness:view']" :loading="checkingInput" :disabled="!canSubmit" @click="precheck">检查完整排程数据</el-button><el-button :loading="catalogLoading" @click="refreshCatalog">刷新可选资料</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="readiness" :type="readiness.ready?'success':'warning'" :closable="false" :title="readiness.ready?'所选范围通过输入预检':'所选范围存在阻断问题'" :description="`检查时间 ${site?siteTime(readiness.checkedAt,site.zoneId):readiness.checkedAt}（${site?.zoneId||'UTC'}）；此结果不替代提交和发布时复验，也不承诺已有可行排程。`"/>
    <el-table v-if="readiness?.issues.length" :data="readiness.issues"><el-table-column prop="code" label="问题" width="210"/><el-table-column label="业务对象"><template #default="s">{{objectName(s.row.objectId,s.row.objectType)}}</template></el-table-column><el-table-column prop="message" label="修正说明" min-width="260"/><el-table-column label="操作" width="120"><template #default="s"><a :href="issueLocation(s.row.objectType,s.row.objectId)">进入资料修正</a></template></el-table-column></el-table>
    <el-text v-if="!catalogLoading && (!workshops.length || !orders.length)" type="warning">请先准备有权限的车间和已释放订单，再发起排程。</el-text>
    <el-form v-if="!readinessOnly" class="plan-picker" inline label-position="top" @submit.prevent="openSelected">
      <el-form-item label="选择计划（最近200个可见版本）">
        <el-select v-model="selectedId" filterable placeholder="按计划名称或状态选择" style="width: 480px">
          <el-option v-for="value in plans" :key="value.planVersionId" :value="value.planVersionId"
            :label="`${value.versionName} · 版本${value.versionNo} · ${planStatusLabel(value.status)}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="操作">
        <el-button type="primary" :disabled="!selectedId" @click="openSelected">加载所选计划</el-button>
        <el-button :loading="catalogLoading" @click="refreshCatalog">刷新计划列表</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="status" type="info" :closable="false" show-icon :title="`排程状态：${planStatusLabel(status.planStatus)}`"
      :description="status.reasonCodes.length ? status.reasonCodes.join('、') : status.planStatus === 'PUBLISHED' ? '正式计划已生效，可从下方正式作业进入派工。' : '候选完成后仍需复验并发布，才可派工。'" />
    <div v-if="requestId" class="status-actions">
      <el-button :loading="checking" @click="refreshStatus">刷新求解状态</el-button>
      <el-button v-if="pending" v-hasPermi="['aps:planning:cancel']" type="warning" :loading="cancelling" @click="cancel">取消求解</el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { getSiteContext, type ApsSiteContext } from '@/api/aps/context'
import { siteTime, operationError } from '@/components/aps/shared/site-time'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { checkPlanReadiness, type ApsPlanReadiness, cancelPlanRequest, createPlanRequest, getPlanRequestStatus, listPlanVersions } from '@/api/aps/planning'
import { getResourceDirectory } from '@/api/aps/resource'
import { getExpansion, listOrders } from '@/api/aps/manufacturing'
import type { ApsWorkshop } from '@/types/aps/resource'
import type { ApsOrder } from '@/types/aps/manufacturing'
import type { ApsPlanListItem, ApsPlanRequestStatus } from '@/types/aps/planning'
import { businessPlanInput, issueLocation, planStatusLabel } from './plan-request-model'

const emit = defineEmits<{ open: [planVersionId: string] }>()
const props = defineProps<{ readinessOnly?:boolean; initialOrderId?:string; activePlan?: { planVersionId: string; status: string } }>()
const form = reactive({ siteCode: '', workshopIds: [] as string[], orderIds: [] as string[], start: '', end: '', maxSolveSeconds: 30 })
const workshops = ref<ApsWorkshop[]>([]), orders = ref<ApsOrder[]>([]), plans = ref<ApsPlanListItem[]>([])
const selectedId = ref(''), requestId = ref(''), status = ref<ApsPlanRequestStatus>()
const error = ref(''), catalogLoading = ref(false), saving = ref(false), checking = ref(false), cancelling = ref(false)
const pending = computed(() => !!status.value && ['DRAFT', 'SOLVING', 'CANCEL_REQUESTED'].includes(status.value.planStatus))
watch(() => props.activePlan, plan => {
  if (!plan) return
  plans.value = plans.value.map(value => value.planVersionId === plan.planVersionId ? { ...value, status: plan.status } : value)
  if (status.value?.planVersionId === plan.planVersionId) status.value = { ...status.value, planStatus: plan.status }
})
const canSubmit=computed(()=>{try{return !!site.value&&new Date(businessPlanInput(site.value,form.workshopIds,form.orderIds,form.start,form.end,form.maxSolveSeconds).horizon.endAt).getTime()>Date.now()}catch{return false}})
const site=ref<ApsSiteContext>(),readiness=ref<ApsPlanReadiness>(),checkingInput=ref(false)
const names=ref<Record<string,string>>({})
function objectName(id:string,type:string){return names.value[id]||orders.value.flatMap(o=>o.lines.map(l=>({id:l.id,name:`${o.orderNo} 第${l.lineNo}行`}))).find(v=>v.id===id)?.name||type}
let readinessSequence=0
async function precheck(){
 if(!site.value||checkingInput.value)return;const token=++readinessSequence;checkingInput.value=true;readiness.value=undefined;error.value=''
 try{const result=await checkPlanReadiness(businessPlanInput(site.value,form.workshopIds,form.orderIds,form.start,form.end,form.maxSolveSeconds));if(disposed||token!==readinessSequence)return;readiness.value=result
   if(result.issues.some(i=>['TASK','LOT','MATERIAL_DEMAND'].includes(i.objectType))){const entries=await Promise.allSettled(form.orderIds.map(id=>getExpansion(id)));if(disposed||token!==readinessSequence)return;for(const entry of entries)if(entry.status==='fulfilled'){for(const task of entry.value.tasks)names.value[task.id]=`${entry.value.order.orderNo} · ${task.taskName}`;for(const lot of entry.value.lots)names.value[lot.id]=lot.lotNo;for(const demand of entry.value.materialDemands)names.value[demand.id]=`${entry.value.order.orderNo} · 第${demand.demandNo}项物料需求`}}
 }catch(e){if(!disposed&&token===readinessSequence)error.value=operationError(e)}finally{if(token===readinessSequence)checkingInput.value=false}
}
watch(()=>[form.workshopIds.join(),form.orderIds.join(),form.start,form.end],()=>{++readinessSequence;checkingInput.value=false;readiness.value=undefined})

let timer: ReturnType<typeof setInterval> | undefined
let disposed = false, deadline = 0, catalogSequence = 0
function stopPolling() { if (timer) clearInterval(timer); timer = undefined }
function startPolling() {
  stopPolling(); deadline = Date.now() + (form.maxSolveSeconds + 60) * 1000
  timer = setInterval(() => {
    if (Date.now() > deadline) { stopPolling(); error.value = '自动等待已结束，求解不会因此取消；请刷新状态或取消求解。'; return }
    void refreshStatus()
  }, 1500)
}
async function refreshCatalog() {
  const sequence = ++catalogSequence
  catalogLoading.value = true; error.value = ''
  try {
    const [directory, nextOrders, nextPlans,context] = await Promise.all([getResourceDirectory(), listOrders(), props.readinessOnly?Promise.resolve([]):listPlanVersions(),getSiteContext()])
    if (disposed || sequence !== catalogSequence) return
    const nextWorkshops=directory.workshops;site.value=context;form.siteCode=context.siteCode;names.value=Object.fromEntries([...directory.workshops,...directory.centers,...directory.resources].map(value=>[value.id,value.name]));
    if(!form.start){form.start=siteTime(new Date(Date.now()+3600000).toISOString(),context.zoneId);form.end=siteTime(new Date(Date.now()+9*3600000).toISOString(),context.zoneId)}
    if (disposed || sequence !== catalogSequence) return
    workshops.value = nextWorkshops.filter(value => value.status === 'ACTIVE')
    orders.value = nextOrders.filter(value => ['RELEASED','IN_PRODUCTION'].includes(value.status))
    if(props.initialOrderId&&orders.value.some(o=>o.id===props.initialOrderId)&&!form.orderIds.length)form.orderIds=[props.initialOrderId]
    plans.value = nextPlans
  } catch { if (!disposed && sequence === catalogSequence) error.value = '未能读取车间、订单或计划列表，请检查权限和接口后刷新。' }
  finally { if (!disposed && sequence === catalogSequence) catalogLoading.value = false }
}
async function submit() {
  if (!canSubmit.value || saving.value || pending.value) return
  saving.value = true; error.value = ''
  const key = crypto.randomUUID()
  try {
    const result = await createPlanRequest(key,businessPlanInput(site.value!,form.workshopIds,form.orderIds,form.start,form.end,form.maxSolveSeconds))
    if (disposed) return
    requestId.value = result.requestId; status.value = result; selectedId.value = result.planVersionId
    await refreshCatalog()
    if (pending.value) startPolling(); else emit('open', result.planVersionId)
  } catch(e) { error.value=operationError(e)+'；请求不会自动重发，请检查数据与计划列表。' }
  finally { saving.value = false }
}
async function refreshStatus() {
  if (!requestId.value || checking.value) return
  const key = requestId.value
  checking.value = true
  try {
    const result = await getPlanRequestStatus(key)
    if (disposed || key !== requestId.value) return
    status.value = result; error.value = ''
    if (!pending.value) { stopPolling(); await refreshCatalog(); emit('open', result.planVersionId) }
    else if (!timer) startPolling()
  } catch { if (!disposed && key === requestId.value) { stopPolling(); error.value = '求解状态连接中断，请手动刷新恢复；请求仍保留。' } }
  finally { checking.value = false }
}
async function cancel() {
  if (!requestId.value || cancelling.value) return
  cancelling.value = true
  try { status.value = await cancelPlanRequest(requestId.value); await refreshStatus() }
  finally { cancelling.value = false }
}
function openSelected() {
  if (!selectedId.value) return
  stopPolling()
  requestId.value = plans.value.find(value => value.planVersionId === selectedId.value)?.requestId || ''
  status.value = undefined
  emit('open', selectedId.value)
  if (requestId.value) void refreshStatus()
}
onMounted(() => void refreshCatalog())
onUnmounted(() => { disposed = true; catalogSequence++; stopPolling() })
</script>
<style scoped>
.plan-launcher { margin-bottom: 16px; }.plan-picker { margin-top: 16px; }.status-actions { margin-top: 12px; }
@media (max-width: 640px) { :deep(.el-select), :deep(.el-input), :deep(.el-date-editor) { max-width: 100%; } }
</style>
