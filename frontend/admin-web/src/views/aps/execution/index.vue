<template>
  <div class="app-container execution-page">
    <el-card shadow="never">
      <template #header>
        <div class="page-head">
          <div>
            <strong>APS 现场执行</strong>
            <span class="head-note">实际占用、报工、质量和数量流水</span>
          </div>
          <el-tag v-if="detail" effect="plain">执行水位 {{ detail.executionRevision }}</el-tag>
        </div>
      </template>

      <el-form inline label-position="top" @submit.prevent="loadDirectory">
        <el-form-item label="订单/工序/作业"><el-input v-model="runQuery.keyword" clearable placeholder="输入业务名称"/></el-form-item><el-form-item label="状态"><el-select v-model="runQuery.status" clearable><el-option v-for="value in ['READY','RUNNING','PAUSED','WAIT_QUALITY','COMPLETED','CANCELLED']" :key="value" :label="statusLabel(value)" :value="value"/></el-select></el-form-item><el-form-item label="资源"><el-select v-model="runQuery.resourceId" filterable clearable><el-option v-for="r in directory.resources" :key="r.id" :label="`${r.code} · ${r.name}`" :value="r.id"/></el-select></el-form-item><el-button native-type="submit" :loading="directoryLoading">查询执行单</el-button><el-button v-if="detail" @click="loadRun()">刷新详情</el-button><el-button @click="openDispatch">从正式计划派工</el-button>
      </el-form><el-text type="info">按所选范围显示最近200个执行单，详情与操作仍受服务端权限限制。</el-text>
      <el-table :data="runOptions" @row-click="openRun"><el-table-column prop="orderNumbers" label="订单"/><el-table-column prop="taskNames" label="工序任务"/><el-table-column prop="jobCode" label="计划作业"/><el-table-column prop="run.runNo" label="执行序号" width="100"/><el-table-column label="状态"><template #default="v">{{statusLabel(v.row.run.status)}}</template></el-table-column><el-table-column label="分配量"><template #default="v">{{v.row.run.assignedQty}} {{v.row.run.uomCode}}</template></el-table-column></el-table>

      <el-alert v-if="loadError" class="state-alert" type="error" show-icon :closable="false" :title="loadError">
        <template #default>接口连接或数据权限修正后，可以直接重试；当前输入不会被清空。</template>
      </el-alert>
      <el-skeleton v-if="loading && !detail" :rows="6" animated />
      <el-empty v-else-if="!detail" description="尚未选择执行单；可在下方从正式计划作业创建。" />
    </el-card>

    <el-card v-if="!detail" class="section-card" shadow="never">
      <template #header><strong>从当前正式计划创建派工</strong></template>
      <el-alert v-if="dispatchError" type="error" :closable="false" :title="dispatchError" />
      <el-form label-position="top" class="grid-form" @submit.prevent="submitCreate">
        <el-form-item label="当前正式计划"><el-select v-model="createForm.planVersionId" filterable placeholder="选择正式计划" @change="loadDispatchPlan">
          <el-option v-for="plan in dispatchPlans" :key="plan.planVersionId" :label="`${plan.versionName} · 版本${plan.versionNo}`" :value="plan.planVersionId" />
        </el-select></el-form-item>
        <el-form-item label="计划作业"><el-select v-model="createForm.planJobId" filterable placeholder="选择作业" @change="selectDispatchJob">
          <el-option v-for="job in dispatchPlan?.jobs || []" :key="job.jobId" :label="`${job.jobCode} · 可分配${job.remainingQty} ${job.uomCode}`" :value="job.jobId" />
        </el-select></el-form-item>
        <el-form-item label="本次分配量"><el-input-number v-model="createForm.assignedQty" :min="0.001" :step="0.001" controls-position="right" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="createForm.uomCode" disabled /></el-form-item>
        <el-form-item class="form-actions"><el-button v-hasPermi="['aps:execution:start']" type="primary" native-type="submit" :loading="saving" :disabled="!canCreate">创建 READY Run</el-button></el-form-item>
      </el-form>
      <el-button :loading="dispatchLoading" @click="loadDispatchCatalog">刷新正式计划</el-button>
      <el-alert type="info" :closable="false" show-icon title="只有唯一当前 PUBLISHED 版本中的作业可以创建 Run；旧正式版本会由服务端拒绝。" />
    </el-card>

    <template v-if="detail">
      <el-card class="section-card" shadow="never">
        <template #header>
          <div class="page-head">
            <strong>Run #{{ detail.run.runNo }} · {{ statusLabel(detail.run.status) }}</strong>
            <div class="action-row">
              <el-button
                v-for="action in actions"
                :key="action" v-hasPermi="[(action==='START'||action==='RESUME')?'aps:execution:start':'aps:execution:pause']"
                :type="action === 'CANCEL' ? 'danger' : 'primary'"
                :loading="saving"
                @click="submitTransition(action)"
              >{{ actionLabel(action) }}</el-button>
              <el-button v-if="detail.run.status === 'RUNNING'" v-hasPermi="['aps:execution:start']" type="warning" :loading="saving"
                @click="submitPhaseAdvance">推进下一计划段</el-button>
              <el-button v-if="detail.run.status === 'RUNNING'" v-hasPermi="['aps:execution:report']" type="success" @click="openReport">提交报工</el-button>
            </div>
          </div>
        </template>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="Run ID"><span class="break-id">{{ detail.run.id }}</span></el-descriptions-item>
          <el-descriptions-item label="计划作业"><span class="break-id">{{runOptions.find(o=>o.run.id===detail?.run.id)?.jobCode||dispatchPlan?.jobs.find(j=>j.jobId===detail?.run.planJobId)?.jobCode||'计划作业'}}</span></el-descriptions-item>
          <el-descriptions-item label="分配量">{{ detail.run.assignedQty }} {{ detail.run.uomCode }}</el-descriptions-item>
          <el-descriptions-item label="实际开始">{{ displayTime(detail.run.actualStartAt) }}</el-descriptions-item>
          <el-descriptions-item label="实际结束">{{ displayTime(detail.run.actualEndAt) }}</el-descriptions-item>
          <el-descriptions-item label="行版本">{{ detail.run.rowVersion }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="detail.run.status === 'WAIT_QUALITY'" class="state-alert" type="warning" show-icon :closable="false"
          title="运行正在等待全部产出批完成质量处置；待检和隔离数量不会进入下游可用量。" />
      </el-card>

      <el-card class="section-card" shadow="never">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="实际占用" name="occupancies">
            <el-table :data="detail.occupancies" empty-text="当前没有实际占用记录">
              <el-table-column prop="activityType" label="活动" width="120" />
              <el-table-column prop="planSegmentId" label="计划段 ID" min-width="260" show-overflow-tooltip />
              <el-table-column label="实际资源" min-width="180"><template #default="v">{{resourceName(v.row.resourceId)}}</template></el-table-column>
              <el-table-column label="开始" min-width="180"><template #default="scope">{{ displayTime(scope.row.startAt) }}</template></el-table-column>
              <el-table-column label="结束" min-width="180"><template #default="scope">{{ displayTime(scope.row.endAt) }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="120" />
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="scope">
                  <el-button v-if="scope.row.status === 'ACTIVE' && detail.run.status === 'RUNNING'" v-hasPermi="['aps:execution:start']" link type="primary" @click="openResourceChange(scope.row)">换资源</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`报工记录 ${detail.reports.length}`" name="reports">
            <el-table :data="detail.reports" empty-text="尚未报工">
              <el-table-column prop="reportType" label="类型" width="110" />
              <el-table-column label="报工时间" min-width="180"><template #default="scope">{{ displayTime(scope.row.reportedAt) }}</template></el-table-column>
              <el-table-column label="加工/合格/待检/不良" min-width="220">
                <template #default="scope">{{ quantityText(scope.row.quantities) }}</template>
              </el-table-column>
              <el-table-column prop="uomCode" label="单位" width="90" />
              <el-table-column label="工序任务" min-width="180"><template #default="v">{{reportMembers.find(m=>m.taskId===v.row.taskId)?.taskName||'工序任务'}}</template></el-table-column>
              <el-table-column label="操作" width="100" fixed="right">
                <template #default="scope">
                  <el-button v-if="scope.row.reportType !== 'CORRECTION'" v-hasPermi="['aps:execution:report']" link type="primary" @click="openCorrection(scope.row)">更正</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`产出与质量 ${detail.outputLots.length}`" name="lots">
            <el-table :data="detail.outputLots" empty-text="尚无产出批">
              <el-table-column prop="outputLotNo" label="产出批号" min-width="170" />
              <el-table-column prop="qualityStatus" label="质量状态" width="120" />
              <el-table-column label="总量/可用/预留/投入/报废" min-width="260">
                <template #default="scope">{{ lotQuantityText(scope.row) }}</template>
              </el-table-column>
              <el-table-column prop="dispositionType" label="处置" width="110" />
              <el-table-column label="操作" min-width="170" fixed="right">
                <template #default="scope">
<el-button link @click="openEvents(scope.row)">数量流水</el-button>
                  <el-button v-if="scope.row.qualityStatus !== 'CLOSED'" v-hasPermi="['aps:execution:quality']" link type="warning" @click="openQuality(scope.row)">质量处置</el-button>
                  <el-button v-if="scope.row.availableQty > 0 || scope.row.reservedQty > 0" v-hasPermi="['aps:execution:report']" link type="primary" @click="openMovement(scope.row)">数量流转</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </template>

    <el-dialog v-model="reportDialog" title="提交部分/完工报工" width="min(760px, 94vw)" destroy-on-close>
      <el-form label-position="top" class="grid-form">
        <el-form-item v-if="reportMembers.length" label="报工任务成员"><el-select v-model="reportForm.planJobMemberId" @change="selectReportMember">
          <el-option v-for="member in reportMembers" :key="member.id" :label="`${member.taskName} · 成员${member.memberNo} · 剩余${member.remainingQty} ${member.uomCode}`" :value="member.id" />
        </el-select></el-form-item>
<el-alert v-else type="warning" :closable="false" title="报工成员读取不到，不能提交；请刷新详情并检查任务关系和权限。"/>
        <el-form-item label="报工类型"><el-select v-model="reportForm.reportType"><el-option label="部分报工" value="PROGRESS" /><el-option label="完工报工" value="COMPLETE" /></el-select></el-form-item>
        <el-form-item label="单位"><el-input v-model="reportForm.uomCode" readonly /></el-form-item>
        <el-form-item label="本次加工量"><el-input-number v-model="reportForm.processedQty" :min="0" /></el-form-item>
        <el-form-item label="合格量"><el-input-number v-model="reportForm.goodQty" :min="0" /></el-form-item>
        <el-form-item label="待检量"><el-input-number v-model="reportForm.pendingQty" :min="0" /></el-form-item>
        <el-form-item label="不良量"><el-input-number v-model="reportForm.rejectedQty" :min="0" /></el-form-item>
        <el-form-item label="其中报废量"><el-input-number v-model="reportForm.scrapQty" :min="0" /></el-form-item>
        <el-form-item label="转序分类量"><el-input-number v-model="reportForm.transferredQty" :min="0" /></el-form-item>
        <el-form-item label="缺陷说明"><el-input v-model="reportForm.defectReason" maxlength="500" /></el-form-item>
        <el-text type="info">操作人由当前登录身份写入，提交时服务端复验成员、单位和剩余数量。</el-text>
      </el-form>
      <el-alert v-if="reportError" type="error" :closable="false" :title="reportError" />
      <template #footer><el-button @click="reportDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitReport">提交且写入数量账</el-button></template>
    </el-dialog>

    <el-dialog v-model="correctionDialog" title="追加报工更正" width="min(700px, 94vw)" destroy-on-close>
      <el-alert type="warning" :closable="false" title="更正会追加替代快照和 REVERSE 事件；原报工不会被修改或删除。" />
      <el-form label-position="top" class="grid-form dialog-form">
        <el-form-item label="加工量"><el-input-number v-model="correctionForm.processedQty" :min="0" /></el-form-item>
        <el-form-item label="合格量"><el-input-number v-model="correctionForm.goodQty" :min="0" /></el-form-item>
        <el-form-item label="待检量"><el-input-number v-model="correctionForm.pendingQty" :min="0" /></el-form-item>
        <el-form-item label="不良量"><el-input-number v-model="correctionForm.rejectedQty" :min="0" /></el-form-item>
        <el-form-item label="报废量"><el-input-number v-model="correctionForm.scrapQty" :min="0" /></el-form-item>
        <el-form-item label="转序分类量"><el-input-number v-model="correctionForm.transferredQty" :min="0" /></el-form-item>
        <el-form-item class="wide-field" label="更正原因"><el-input v-model="correctionForm.reason" type="textarea" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <el-alert v-if="reportError" type="error" :closable="false" :title="reportError" />
      <template #footer><el-button @click="correctionDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitCorrection">确认追加更正</el-button></template>
    </el-dialog>

    <el-dialog v-model="qualityDialog" title="质量处置" width="min(620px, 94vw)" destroy-on-close>
      <el-form label-position="top" class="grid-form">
        <el-form-item label="处置决定"><el-select v-model="qualityForm.decision"><el-option v-for="value in qualityDecisions" :key="value" :label="qualityLabel(value)" :value="value" /></el-select></el-form-item>
        <el-form-item label="处置数量"><el-input-number v-model="qualityForm.quantity" :min="0.001" /></el-form-item>
        <el-form-item class="wide-field" label="原因"><el-input v-model="qualityForm.reason" type="textarea" maxlength="500" /></el-form-item>
        <el-form-item v-if="qualityForm.decision === 'SCRAP'" class="wide-field"><el-checkbox v-model="qualityForm.createReplenishment">同步创建补产批和任务</el-checkbox></el-form-item>
      </el-form>
<el-alert v-if="reportError" :title="reportError" type="error" :closable="false"/><template #footer><el-button @click="qualityDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitQuality">提交质量决定</el-button></template>
    </el-dialog>

    <el-dialog v-model="movementDialog" title="数量流转" width="min(700px, 94vw)" destroy-on-close>
      <el-form label-position="top" class="grid-form">
        <el-form-item label="动作"><el-select v-model="movementForm.operation"><el-option v-for="value in quantityOperations" :key="value" :label="movementLabel(value)" :value="value" /></el-select></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="movementForm.quantity" :min="0.001" /></el-form-item>
        <el-form-item label="目标物料需求"><el-select v-model="movementForm.materialDemandId" filterable @change="selectMovementTarget"><el-option v-for="t in movementTargets" :key="t.materialDemandId" :label="`${t.orderNo} · ${t.taskName} · 待分配${t.remainingDemandQty}/本批预留${t.reservedQty} ${t.uomCode}`" :value="t.materialDemandId"/></el-select></el-form-item>
        <el-form-item label="目标任务"><el-input :model-value="movementTargets.find(t=>t.materialDemandId===movementForm.materialDemandId)?.taskName" readonly/></el-form-item>
        <el-form-item v-if="movementForm.operation === 'CONSUME'" label="目标执行单"><el-select v-model="movementForm.targetExecutionRunId" filterable><el-option v-for="v in targetRuns" :key="v.run.id" :label="`${v.jobCode} · 执行${v.run.runNo} · ${statusLabel(v.run.status)}`" :value="v.run.id"/></el-select></el-form-item>
        <el-form-item class="wide-field" label="原因"><el-input v-model="movementForm.reason" maxlength="500" /></el-form-item>
      </el-form>
<el-alert v-if="reportError" :title="reportError" type="error" :closable="false"/><el-text>只列来源、物料和单位匹配的需求；查询最多200项，余额以提交时复验为准。</el-text><template #footer><el-button @click="movementDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitMovement">提交数量事件</el-button></template>
    </el-dialog>

    <el-dialog v-model="resourceDialog" title="受控切换实际资源" width="min(680px, 94vw)" destroy-on-close>
      <el-form label-position="top" class="grid-form">
        <el-form-item label="原资源"><el-input :model-value="resourceName(resourceForm.replacedResourceId)" disabled/></el-form-item>
        <el-form-item label="合法替换资源"><el-select v-model="resourceForm.resourceId" filterable :loading="candidateLoading"><el-option v-for="r in replacements" :key="r.resourceId" :label="`${r.code} · ${r.name}`" :value="r.resourceId"/></el-select></el-form-item>
        <el-form-item label="活动类型"><el-select v-model="resourceForm.activityType" disabled><el-option v-for="value in occupancyActivities" :key="value" :label="value" :value="value" /></el-select></el-form-item>
        <el-form-item class="wide-field" label="切换原因"><el-input v-model="resourceForm.reason" maxlength="500" /></el-form-item>
      </el-form>
<el-alert v-if="reportError" :title="reportError" type="error" :closable="false"/><el-text>候选已检查当前资格、日历和实际占用，提交时再次校验。</el-text><template #footer><el-button @click="resourceDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitResourceChange">校验并切换</el-button></template>
    </el-dialog>
<el-dialog v-model="eventsDialog" title="产出批数量流水" width="min(1000px,96vw)"><el-table :data="quantityEvents"><el-table-column prop="eventType" label="事件"/><el-table-column prop="quantity" label="数量"/><el-table-column prop="uomCode" label="单位"/><el-table-column label="发生时间"><template #default="v">{{displayTime(v.row.occurredAt)}}</template></el-table-column><el-table-column prop="reversalOfId" label="冲正来源"/><el-table-column prop="reason" label="原因"/></el-table></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listExecutionRuns,getReportMembers,getResourceReplacements,getMovementTargets,getQuantityEvents,
  getDispatchPlan,listDispatchPlans,type ApsDispatchPlan,type ApsRunOption,type ApsReportMember,type ApsMovementTarget,type ApsResourceReplacement,advanceExecutionPhase,
  changeExecutionResource,
  correctProductionReport,
  createExecutionRun,
  createProductionReport,
  decideOutputLotQuality,
  getExecutionRun,
  moveOutputLotQuantity,
  transitionExecutionRun
} from '@/api/aps/execution'
import type {
  ApsActualOccupancy,
  ApsExecutionAction,
  ApsExecutionRunDetail,
  ApsOccupancyActivity,
  ApsOutputLot,
  ApsProductionReport,
  ApsQualityDecision,
  ApsQuantityOperation,
  ApsReportQuantities
} from '@/types/aps/execution'
import { allowedExecutionActions, availableQualityQuantity, isConcurrencyConflict, validateReportQuantities } from './execution-model'
import { getSiteContext, type ApsSiteContext } from '@/api/aps/context'
import { getResourceDirectory, type ApsDirectory } from '@/api/aps/resource'
import { siteTime, operationError } from '@/components/aps/shared/site-time'
const site=ref<ApsSiteContext>(),directory=ref<ApsDirectory>({workshops:[],centers:[],resources:[]}),runOptions=ref<ApsRunOption[]>([]),reportMembers=ref<ApsReportMember[]>([])
const runQuery=reactive({keyword:'',status:'',resourceId:''}),directoryLoading=ref(false),candidateLoading=ref(false),replacements=ref<ApsResourceReplacement[]>([]),movementTargets=ref<ApsMovementTarget[]>([])
const targetRuns=computed(()=>movementTargets.value.find(t=>t.materialDemandId===movementForm.materialDemandId)?.executionRuns||[])
const eventsDialog=ref(false),quantityEvents=ref<Awaited<ReturnType<typeof getQuantityEvents>>>([])
const route = useRoute()
const router = useRouter()
const runId = ref(queryText('runId'))
const detail = ref<ApsExecutionRunDetail>()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const activeTab = ref('occupancies')
const reportDialog = ref(false)
const correctionDialog = ref(false)
const qualityDialog = ref(false)
const movementDialog = ref(false)
const resourceDialog = ref(false)
const reportError = ref('')
const selectedReport = ref<ApsProductionReport>()
const selectedLot = ref<ApsOutputLot>()
const selectedOccupancy = ref<ApsActualOccupancy>()

const createForm = reactive({
  planVersionId: queryText('planVersionId'), planJobId: queryText('planJobId'),
  assignedQty: 1, uomCode: queryText('uomCode') || 'PCS'
})
const dispatchPlans=ref<Awaited<ReturnType<typeof listDispatchPlans>>>([])
const dispatchPlan=ref<ApsDispatchPlan>()
const dispatchLoading = ref(false), dispatchError = ref('')
let dispatchSequence = 0
async function loadDispatchCatalog() {
  dispatchLoading.value = true; dispatchError.value = ''
  try {
    dispatchPlans.value=await listDispatchPlans()
    if (!createForm.planVersionId && dispatchPlans.value.length === 1) createForm.planVersionId = dispatchPlans.value[0]!.planVersionId
    if (createForm.planVersionId) await loadDispatchPlan()
  } catch { dispatchError.value = '未能读取正式计划，请刷新或从工作台重新选择作业。' }
  finally { dispatchLoading.value = false }
}
async function loadDispatchPlan() {
  const id = detail.value?.run.planVersionId || createForm.planVersionId
  if (!id) return
  const sequence = ++dispatchSequence
  dispatchPlan.value = undefined
  try {
    const plan=await getDispatchPlan(id)
    if (sequence !== dispatchSequence) return
    dispatchPlan.value = plan

    if (!plan.jobs.some(job => job.jobId === createForm.planJobId)) createForm.planJobId = ''
    selectDispatchJob()
    if (reportMembers.value.length === 1) { reportForm.planJobMemberId = reportMembers.value[0]!.id; selectReportMember() }
  } catch { if (sequence === dispatchSequence) dispatchError.value = '未能读取所选计划作业，请检查权限后重试。' }
}
function selectDispatchJob() {
  const job = dispatchPlan.value?.jobs.find(value => value.jobId === createForm.planJobId)
  if (!job) return
  createForm.uomCode = job.uomCode; createForm.assignedQty = Number(job.remainingQty)
}
function selectReportMember() {
  const member = reportMembers.value.find(value => value.id === reportForm.planJobMemberId)
  if (member) { reportForm.taskId = member.taskId; reportForm.uomCode = member.uomCode }
}
const reportForm = reactive({
  planJobMemberId: queryText('memberId'), taskId: queryText('taskId'), reportType: 'PROGRESS' as 'PROGRESS' | 'COMPLETE',
  processedQty: 0, goodQty: 0, pendingQty: 0, rejectedQty: 0, scrapQty: 0, transferredQty: 0,
  uomCode: queryText('uomCode') || 'PCS', defectReason: '', operatorUserId: ''
})
const correctionForm = reactive({ processedQty: 0, goodQty: 0, pendingQty: 0, rejectedQty: 0, scrapQty: 0, transferredQty: 0, reason: '' })
const qualityForm = reactive({ decision: 'RELEASE' as ApsQualityDecision, quantity: 1, reason: '', createReplenishment: false })
const movementForm = reactive({ operation: 'RESERVE' as ApsQuantityOperation, quantity: 1, materialDemandId: '', targetTaskId: '', targetExecutionRunId: '', reason: '' })
const resourceForm = reactive({ replacedResourceId: '', resourceId: '', planSegmentId: '', activityType: 'RUN' as ApsOccupancyActivity, reason: '' })
const qualityDecisions: readonly ApsQualityDecision[] = ['HOLD', 'RELEASE', 'REJECT', 'REWORK', 'SCRAP', 'USE_AS_IS']
const quantityOperations: readonly ApsQuantityOperation[] = ['RESERVE', 'UNRESERVE', 'CONSUME', 'TRANSFER']
const occupancyActivities: readonly ApsOccupancyActivity[] = ['SETUP', 'RUN', 'UNLOAD', 'WAIT_HOLD', 'PAUSE_HOLD', 'TRANSPORT']

const actions = computed(() => detail.value ? allowedExecutionActions(detail.value.run.status) : [])
const canCreate = computed(() => dispatchPlan.value?.planVersionId === createForm.planVersionId
  && dispatchPlan.value.jobs.some(job => job.jobId === createForm.planJobId)
  && createForm.assignedQty > 0 && createForm.assignedQty<=Number(dispatchPlan.value?.jobs.find(j=>j.jobId===createForm.planJobId)?.remainingQty||0) && !!createForm.uomCode.trim())

onMounted(async()=>{try{[site.value,directory.value]=await Promise.all([getSiteContext(),getResourceDirectory()]);await loadDirectory();if(runId.value)await loadRun();else await loadDispatchCatalog()}catch(e){loadError.value=operationError(e)}})
let runSequence=0,directorySequence=0,memberSequence=0,candidateSequence=0,disposed=false
onUnmounted(()=>{disposed=true;++runSequence;++directorySequence;++memberSequence;++candidateSequence})
async function loadDirectory(){const token=++directorySequence;directoryLoading.value=true;try{const rows=await listExecutionRuns({...runQuery});if(!disposed&&token===directorySequence)runOptions.value=rows}catch(e){if(!disposed&&token===directorySequence)loadError.value=operationError(e)}finally{if(token===directorySequence)directoryLoading.value=false}}
function openDispatch(){if(saving.value)return;++runSequence;++memberSequence;++candidateSequence;detail.value=undefined;runId.value='';loading.value=false;const query={...route.query};delete query.runId;void router.replace({query})}
function openRun(option:ApsRunOption){if(saving.value)return;runId.value=option.run.id;detail.value=undefined;reportDialog.value=false;correctionDialog.value=false;qualityDialog.value=false;movementDialog.value=false;resourceDialog.value=false;++candidateSequence;void loadRun()}
function resourceName(id:string){const r=directory.value.resources.find(r=>r.id===id);return r?`${r.code} · ${r.name}`:'未知资源'}
function selectMovementTarget(){const t=movementTargets.value.find(t=>t.materialDemandId===movementForm.materialDemandId);movementForm.targetTaskId=t?.targetTaskId||'';movementForm.targetExecutionRunId=''}
async function openEvents(lot:ApsOutputLot){const id=detail.value?.run.id;if(!id)return;try{const rows=await getQuantityEvents(id,lot.id);if(!disposed&&detail.value?.run.id===id){quantityEvents.value=rows;eventsDialog.value=true}}catch(e){if(!disposed&&detail.value?.run.id===id)loadError.value=operationError(e)}}

function queryText(key: string): string {
  const value = route.query[key]
  return typeof value === 'string' ? value : ''
}

async function loadRun(showError = true) {
  const id = runId.value.trim()
  if (!id) return
  const token=++runSequence
  loading.value = true
  loadError.value = ''
  try {
    const value=await getExecutionRun(id)
    if(!disposed&&token===runSequence&&id===runId.value)applyDetail(value)
  } catch (error) {
    if (!disposed&&token===runSequence&&showError) loadError.value = '执行单读取失败：'+operationError(error)
  } finally {
    if(token===runSequence)loading.value = false
  }
}

function applyDetail(value: ApsExecutionRunDetail) {
  detail.value = value
  runId.value = value.run.id
  reportForm.uomCode = value.run.uomCode
  const token=++memberSequence
  reportMembers.value=[];void getReportMembers(value.run.id).then(rows=>{if(!disposed&&token===memberSequence&&detail.value?.run.id===value.run.id){reportMembers.value=rows;if(rows.length===1){reportForm.planJobMemberId=rows[0]!.id;selectReportMember()}}}).catch(e=>{if(!disposed&&token===memberSequence)reportError.value='报工成员读取失败：'+operationError(e)})
  void router.replace({ query: { ...route.query, runId: value.run.id } })
}

async function submitCreate() {
  if (!canCreate.value) return
  await mutate(async () => createExecutionRun(crypto.randomUUID(), { ...createForm }), '执行 Run 已创建')
}

async function submitTransition(action: ApsExecutionAction) {
  const value = detail.value
  if (!value) return
  let reason = ''
  if (action === 'PAUSE' || action === 'CANCEL') {
    try {
      reason = (await ElMessageBox.prompt('请输入本次状态变化的现场原因', actionLabel(action), {
        inputValidator: (text) => !!text.trim() || '原因不能为空',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      })).value.trim()
    } catch (result) {
      if (result === 'cancel' || result === 'close') return
      throw result
    }
  }
  await mutate(() => transitionExecutionRun(value.run.id, {
    action, expectedRowVersion: value.run.rowVersion, occurredAt: now(), reason: reason || undefined
  }), `Run 已${actionLabel(action)}`)
}

async function submitPhaseAdvance() {
  const value = detail.value
  if (!value) return
  try {
    await ElMessageBox.confirm(
      '系统会关闭当前计划段的全部实际占用，并按计划打开下一段资源。该动作不会完工，也不会自动报工。',
      '推进下一计划段',
      { confirmButtonText: '确认推进', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (result) {
    if (result === 'cancel' || result === 'close') return
    throw result
  }
  await mutate(() => advanceExecutionPhase(value.run.id, crypto.randomUUID(), {
    expectedRowVersion: value.run.rowVersion, occurredAt: now(), reason: '现场确认进入下一计划段'
  }), '当前计划段已关闭，下一计划段已开始')
}

function openReport() {
  const prior = [...(detail.value?.reports ?? [])].reverse()
    .find((value: ApsProductionReport) => value.reportType !== 'CORRECTION')
  if (prior) {
    reportForm.planJobMemberId = prior.planJobMemberId
    reportForm.taskId = prior.taskId
    reportForm.uomCode = prior.uomCode
  }
  reportError.value = ''
  reportDialog.value = true
}

async function submitReport() {
  const value = detail.value
  if (!value) return
  const quantities = reportQuantities(reportForm)
  reportError.value = validateReportQuantities(quantities) ?? ''
  if(!reportMembers.value.some(m=>m.id===reportForm.planJobMemberId&&m.taskId===reportForm.taskId)){reportError.value='请从已读取的任务成员中选择';return}
  if (reportError.value || !reportForm.planJobMemberId.trim() || !reportForm.taskId.trim()) return
  const succeeded = await mutate(() => createProductionReport(value.run.id, crypto.randomUUID(), {
    expectedRowVersion: value.run.rowVersion,
    planJobMemberId: reportForm.planJobMemberId.trim(), taskId: reportForm.taskId.trim(),
    reportType: reportForm.reportType, reportedAt: now(), quantities, uomCode: reportForm.uomCode.trim(),
    defectReason: reportForm.defectReason.trim() || undefined,

  }), '报工与数量账已原子提交')
  if (succeeded) reportDialog.value = false
}

function openCorrection(report: ApsProductionReport) {
  selectedReport.value = report
  Object.assign(correctionForm, report.quantities, { reason: '' })
  reportError.value = ''
  correctionDialog.value = true
}

async function submitCorrection() {
  const run = detail.value?.run
  const report = selectedReport.value
  if (!run || !report) return
  const quantities = reportQuantities(correctionForm)
  reportError.value = validateReportQuantities(quantities) ?? (!correctionForm.reason.trim() ? '更正原因不能为空' : '')
  if (reportError.value) return
  const succeeded = await mutate(() => correctProductionReport(report.id, crypto.randomUUID(), {
    expectedRunRowVersion: run.rowVersion, expectedReportRowVersion: report.rowVersion,
    reportedAt: now(), quantities, reason: correctionForm.reason.trim()
  }), '更正与反向数量链已追加')
  if (succeeded) correctionDialog.value = false
}

function openQuality(lot: ApsOutputLot) {
  selectedLot.value = lot
  qualityForm.decision = lot.qualityStatus === 'HOLD' ? 'RELEASE' : 'RELEASE'
  qualityForm.quantity = Math.max(0.001, availableQualityQuantity(lot))
  qualityForm.reason = ''
  qualityForm.createReplenishment = false
  qualityDialog.value = true
}

async function submitQuality() {
  const run = detail.value?.run
  const lot = selectedLot.value
  if (!run || !lot || qualityForm.quantity <= 0) return
  const succeeded = await mutate(() => decideOutputLotQuality(lot.id, crypto.randomUUID(), {
    expectedRunRowVersion: run.rowVersion, expectedOutputLotRowVersion: lot.rowVersion,
    decision: qualityForm.decision, quantity: qualityForm.quantity, occurredAt: now(),
    reason: qualityForm.reason.trim() || undefined, createReplenishment: qualityForm.createReplenishment
  }), '质量决定已提交')
  if (succeeded) qualityDialog.value = false
}

async function openMovement(lot: ApsOutputLot) {
  selectedLot.value = lot
  movementForm.operation = lot.availableQty > 0 ? 'RESERVE' : 'UNRESERVE'
  movementForm.quantity = Math.max(0.001, lot.availableQty || lot.reservedQty)
  movementForm.reason = ''
  const token=++candidateSequence,id=detail.value!.run.id;reportError.value='';movementTargets.value=[];movementForm.materialDemandId='';movementForm.targetTaskId='';movementForm.targetExecutionRunId='';movementDialog.value=true;try{const rows=await getMovementTargets(id,lot.id);if(disposed||token!==candidateSequence||detail.value?.run.id!==id)return;movementTargets.value=rows;if(!rows.length)reportError.value='没有来源、物料、单位及权限均匹配的目标需求'}catch(e){if(!disposed&&token===candidateSequence)reportError.value=operationError(e)}
}

async function submitMovement() {
  const run = detail.value?.run
  const lot = selectedLot.value
  if (!run || !lot || !movementForm.materialDemandId.trim() || !movementForm.targetTaskId.trim()) return
  const succeeded = await mutate(() => moveOutputLotQuantity(lot.id, crypto.randomUUID(), {
    expectedRunRowVersion: run.rowVersion, expectedOutputLotRowVersion: lot.rowVersion,
    materialDemandId: movementForm.materialDemandId.trim(), targetTaskId: movementForm.targetTaskId.trim(),
    targetExecutionRunId: movementForm.operation === 'CONSUME' ? movementForm.targetExecutionRunId.trim() : undefined,
    operation: movementForm.operation, quantity: movementForm.quantity, occurredAt: now(),
    reason: movementForm.reason.trim() || undefined
  }), '数量事件已提交')
  if (succeeded) movementDialog.value = false
}

async function openResourceChange(occupancy: ApsActualOccupancy) {
  selectedOccupancy.value = occupancy
  resourceForm.replacedResourceId = occupancy.resourceId
  resourceForm.resourceId = ''
  resourceForm.planSegmentId = occupancy.planSegmentId ?? ''
  resourceForm.activityType = occupancy.activityType
  resourceForm.reason = ''
  const token=++candidateSequence,id=detail.value!.run.id;reportError.value='';replacements.value=[];resourceDialog.value=true;candidateLoading.value=true;try{const rows=await getResourceReplacements(id,occupancy.resourceId);if(disposed||token!==candidateSequence||detail.value?.run.id!==id)return;replacements.value=rows;if(!rows.length)reportError.value='当前没有符合资格、日历与实际占用要求的替换资源'}catch(e){if(!disposed&&token===candidateSequence)reportError.value=operationError(e)}finally{if(token===candidateSequence)candidateLoading.value=false}
}

async function submitResourceChange() {
  const run = detail.value?.run
  const occupancy = selectedOccupancy.value
  if (!run || !occupancy || !resourceForm.resourceId.trim()) return
  const succeeded = await mutate(() => changeExecutionResource(run.id, crypto.randomUUID(), {
    expectedRowVersion: run.rowVersion, replacedResourceId: occupancy.resourceId,
    resourceId: resourceForm.resourceId.trim(), planSegmentId: resourceForm.planSegmentId || undefined,
    activityType: resourceForm.activityType, occurredAt: now(), reason: resourceForm.reason.trim() || undefined
  }), '实际资源已切换')
  if (succeeded) resourceDialog.value = false
}

async function mutate(operation: () => Promise<ApsExecutionRunDetail>, success: string): Promise<boolean> {
  if (saving.value) return false
  saving.value = true
  try {
    const updated=await operation();if(disposed)return false;applyDetail(updated);void loadDirectory()
    ElMessage.success(success)
    return true
  } catch (error) {
    if (isConcurrencyConflict(error)) {
      await loadRun(false)
      ElMessage.warning('执行事实已被其他操作更新，页面已刷新；请核对保留的输入后重试。')
    } else {
      reportError.value=operationError(error);ElMessage.error(reportError.value)
    }
    return false
  } finally {
    saving.value = false
  }
}

function reportQuantities(value: { processedQty: number; goodQty: number; pendingQty: number; rejectedQty: number; scrapQty: number; transferredQty: number }): ApsReportQuantities {
  return { processedQty: value.processedQty, goodQty: value.goodQty, pendingQty: value.pendingQty,
    rejectedQty: value.rejectedQty, scrapQty: value.scrapQty, transferredQty: value.transferredQty }
}
function now() { return new Date().toISOString() }
function displayTime(value: string | null) { return value&&site.value?siteTime(value,site.value.zoneId):'—' }
function quantityText(value: ApsReportQuantities) { return `${value.processedQty} / ${value.goodQty} / ${value.pendingQty} / ${value.rejectedQty}` }
function lotQuantityText(value: ApsOutputLot) { return `${value.totalQty} / ${value.availableQty} / ${value.reservedQty} / ${value.consumedQty} / ${value.scrappedQty} ${value.uomCode}` }
function actionLabel(value: ApsExecutionAction) { return ({ START: '开工', PAUSE: '暂停', RESUME: '恢复', CANCEL: '取消' } as const)[value] }
function statusLabel(value: string) { return ({ READY: '待开工', RUNNING: '执行中', PAUSED: '已暂停', WAIT_QUALITY: '待质量处置', COMPLETED: '已完成', CANCELLED: '已取消' } as Record<string, string>)[value] ?? value }
function qualityLabel(value: ApsQualityDecision) { return ({ HOLD: '隔离', RELEASE: '放行', REJECT: '拒收', REWORK: '返工', SCRAP: '报废', USE_AS_IS: '让步使用' } as const)[value] }
function movementLabel(value: ApsQuantityOperation) { return ({ RESERVE: '预留', UNRESERVE: '取消预留', CONSUME: '投入', TRANSFER: '转序' } as const)[value] }
</script>

<style scoped>
.execution-page { max-width: 1600px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.head-note { margin-left: 0.75rem; color: var(--el-text-color-secondary); font-weight: normal; }
.id-input { width: min(520px, 72vw); }
.state-alert, .section-card { margin-top: 1rem; }
.action-row { display: flex; flex-wrap: wrap; gap: 0.5rem; }
.action-row .el-button { margin-left: 0; }
.grid-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 1rem; }
.grid-form :deep(.el-input-number), .grid-form :deep(.el-select) { width: 100%; }
.form-actions, .wide-field { grid-column: 1 / -1; }
.form-actions { align-self: end; }
.break-id { overflow-wrap: anywhere; }
.dialog-form { margin-top: 1rem; }

@media (max-width: 720px) {
  .page-head { align-items: flex-start; flex-direction: column; }
  .head-note { display: block; margin: 0.35rem 0 0; }
  .grid-form { grid-template-columns: minmax(0, 1fr); }
  .form-actions, .wide-field { grid-column: auto; }
  .id-input { width: 100%; }
  :deep(.el-descriptions__body) { overflow-x: auto; }
}
</style>
