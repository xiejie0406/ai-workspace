<template>
  <div class="app-container report-page">
    <el-card shadow="never" class="filter-card">
      <template #header>
        <div class="page-head">
          <div>
            <strong>APS 生产与交期报表</strong>
            <span class="head-note">日冻结基线、当前计划、实际事实和 ETA 使用同一口径</span>
          </div>
          <div class="actions no-print">
            <el-button :loading="loading" type="primary" @click="loadCurrent">刷新</el-button>
            <el-button :loading="exporting" @click="exportCurrent">导出 CSV</el-button>
            <el-button @click="printPage">打印</el-button>
          </div>
        </div>
      </template>

      <el-form inline label-position="top" @submit.prevent="loadCurrent">
        <template v-if="activeTab === 'daily'">
          <el-form-item label="业务日期"><el-date-picker v-model="dailyQuery.businessDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        </template>
        <template v-else-if="activeTab === 'labor'">
          <el-form-item label="开始日期"><el-date-picker v-model="laborQuery.fromDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker v-model="laborQuery.toDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="技能编码"><el-input v-model.trim="laborQuery.skillCode" clearable placeholder="可选" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="订单 ID"><el-input v-model.trim="orderQuery.orderId" clearable placeholder="留空查询权限范围内全部订单" class="id-input" /></el-form-item>
        </template>
        <el-form-item v-if="activeTab !== 'orders'" label="车间 ID"><el-input v-model.trim="common.workshopId" clearable placeholder="可选 UUID" class="id-input" /></el-form-item>
        <el-form-item v-if="activeTab !== 'orders'" label="工作中心 ID"><el-input v-model.trim="common.workCenterId" clearable placeholder="可选 UUID" class="id-input" /></el-form-item>
      </el-form>

      <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" />
      <el-alert v-else-if="metadata" type="info" :closable="false" class="metadata-alert">
        <template #title>
          {{ metadata.siteCode }} · {{ metadata.zoneId }} · 数据截止 {{ timeText(metadata.dataCutoffAt) }}
          <template v-if="activeTab === 'daily'">
            · 日冻结 {{ shortId(metadata.baselinePlanVersionId) }} · 当前正式 {{ shortId(metadata.currentPlanVersionId) }}
          </template>
        </template>
      </el-alert>
    </el-card>

    <el-card class="report-card" shadow="never">
      <el-tabs v-model="activeTab" @tab-change="loadCurrent">
        <el-tab-pane label="车间每日生产表" name="daily">
          <el-skeleton v-if="loading && !dailyReport" :rows="8" animated />
          <el-empty v-else-if="!dailyReport || dailyReport.rows.length === 0" description="当前日期和权限范围内没有计划或实际事实" />
          <el-table v-else :data="dailyReport.rows" border stripe show-overflow-tooltip>
            <el-table-column prop="workshopName" label="车间" width="120" fixed="left" />
            <el-table-column prop="workCenterName" label="工作中心" width="130" />
            <el-table-column prop="orderNo" label="订单" width="130" />
            <el-table-column label="产品/任务" min-width="220">
              <template #default="scope"><strong>{{ scope.row.itemCode }}</strong> {{ scope.row.taskCode }} · {{ scope.row.taskName }}</template>
            </el-table-column>
            <el-table-column label="冻结/当前计划量" width="160" align="right">
              <template #default="scope">{{ numberText(scope.row.baselinePlannedQty) }} / {{ numberText(scope.row.currentPlannedQty) }} {{ scope.row.uomCode }}</template>
            </el-table-column>
            <el-table-column label="加工/合格/报废" width="180" align="right">
              <template #default="scope">{{ numberText(scope.row.actualProcessedQty) }} / {{ numberText(scope.row.actualGoodQty) }} / {{ numberText(scope.row.scrapQty) }}</template>
            </el-table-column>
            <el-table-column label="计划人时/实际" width="145" align="right">
              <template #default="scope">{{ hourText(scope.row.currentPersonHours) }} / {{ hourText(scope.row.actualPersonHours) }}</template>
            </el-table-column>
            <el-table-column label="计划机时/实际" width="145" align="right">
              <template #default="scope">{{ hourText(scope.row.currentMachineHours) }} / {{ hourText(scope.row.actualMachineHours) }}</template>
            </el-table-column>
            <el-table-column label="冻结达成" width="110" align="right"><template #default="scope">{{ percentText(scope.row.achievementRatio) }}</template></el-table-column>
            <el-table-column label="预计结束" width="180"><template #default="scope">{{ timeText(scope.row.currentEndAt) }}</template></el-table-column>
            <el-table-column label="结果" width="170" fixed="right">
              <template #default="scope"><el-tag :type="dailyResultTag(scope.row)">{{ scope.row.reasonCodes.join(' / ') || '正常' }}</el-tag></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="人力累计产能" name="labor">
          <el-skeleton v-if="loading && !laborReport" :rows="8" animated />
          <template v-else-if="laborReport">
            <el-alert type="warning" :closable="false" show-icon :title="laborReport.aggregationNotice" />
            <h3>按员工去重</h3>
            <el-table :data="laborReport.people" border stripe empty-text="范围内没有人员资源">
              <el-table-column prop="workshopName" label="车间" width="120" />
              <el-table-column prop="workCenterName" label="工作中心" width="130" />
              <el-table-column prop="resourceName" label="人员" width="120" />
              <el-table-column label="技能" min-width="190"><template #default="scope">{{ scope.row.skillCodes.join('、') || '未配置' }}</template></el-table-column>
              <el-table-column label="可用人时" width="120" align="right"><template #default="scope">{{ hourText(scope.row.availableHours) }}</template></el-table-column>
              <el-table-column label="已排人时" width="120" align="right"><template #default="scope">{{ hourText(scope.row.scheduledHours) }}</template></el-table-column>
              <el-table-column label="剩余人时" width="120" align="right"><template #default="scope">{{ hourText(scope.row.remainingHours) }}</template></el-table-column>
              <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.overloaded ? 'danger' : 'success'">{{ scope.row.overloaded ? '超载' : '正常' }}</el-tag></template></el-table-column>
            </el-table>
            <h3>按技能看需求与时段峰值</h3>
            <el-table :data="laborReport.skills" border stripe empty-text="范围内没有技能需求">
              <el-table-column prop="skillCode" label="技能" width="150" />
              <el-table-column label="潜力人时" width="120" align="right"><template #default="scope">{{ hourText(scope.row.availablePotentialHours) }}</template></el-table-column>
              <el-table-column label="已排需用" width="120" align="right"><template #default="scope">{{ hourText(scope.row.scheduledHours) }}</template></el-table-column>
              <el-table-column label="待排需用" width="120" align="right"><template #default="scope">{{ hourText(scope.row.unplannedRequiredHours) }}</template></el-table-column>
              <el-table-column prop="peakRequiredPeople" label="峰值需用" width="110" align="right" />
              <el-table-column prop="peakQualifiedPeople" label="峰值可用" width="110" align="right" />
              <el-table-column prop="peakShortagePeople" label="峰值缺口" width="110" align="right" />
              <el-table-column label="峰值时刻" width="180"><template #default="scope">{{ timeText(scope.row.peakAt) }}</template></el-table-column>
              <el-table-column label="原因" min-width="220"><template #default="scope">{{ scope.row.reasonCodes.join(' / ') }}</template></el-table-column>
            </el-table>
          </template>
        </el-tab-pane>

        <el-tab-pane label="订单交期与 ETA" name="orders">
          <el-skeleton v-if="loading && !orderReport" :rows="8" animated />
          <el-empty v-else-if="orderLines.length === 0" description="权限范围内没有可计算的生产订单" />
          <el-table v-else :data="orderLines" border stripe show-overflow-tooltip>
            <el-table-column prop="orderNo" label="订单" width="130" fixed="left" />
            <el-table-column prop="orderStatus" label="订单状态" width="130" />
            <el-table-column prop="lineNo" label="产品行" width="80" />
            <el-table-column label="产品" min-width="180"><template #default="scope">{{ scope.row.itemCode }} · {{ scope.row.itemName }}</template></el-table-column>
            <el-table-column label="需求/末端放行" width="170" align="right"><template #default="scope">{{ numberText(scope.row.demandQty) }} / {{ numberText(scope.row.releasedTerminalQty) }} {{ scope.row.uomCode }}</template></el-table-column>
            <el-table-column label="ETA" width="190"><template #default="scope"><el-tag :type="scope.row.etaState === 'KNOWN' ? 'success' : 'warning'">{{ scope.row.etaState }}</el-tag> {{ timeText(scope.row.expectedProductionAt) }}</template></el-table-column>
            <el-table-column label="已知下界" width="180"><template #default="scope">{{ timeText(scope.row.knownLowerBoundAt) }}</template></el-table-column>
            <el-table-column label="承诺时间" width="180"><template #default="scope">{{ timeText(scope.row.promisedAt) }}</template></el-table-column>
            <el-table-column label="解释" min-width="260"><template #default="scope">{{ scope.row.reasons.map((reason: any) => `${reason.code}: ${reason.detail}`).join('；') || '全部必需任务信息完整' }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  exportDailyProductionReport,
  exportLaborCapacityReport,
  exportOrderDeliveryReport,
  getDailyProductionReport,
  getLaborCapacityReport,
  getOrderDeliveryReport
} from '@/api/aps/reporting'
import type {
  ApsDailyProductionReport,
  ApsLaborCapacityReport,
  ApsOrderDeliveryReport,
  ApsReportMetadata
} from '@/types/aps/reporting'
import { dailyResultTag, decimalNumber, flattenOrderLines, hourText, localDateText, percentText } from './report-model'

type ReportTab = 'daily' | 'labor' | 'orders'

const today = localDateText()
const activeTab = ref<ReportTab>('daily')
const loading = ref(false)
const exporting = ref(false)
const loadError = ref('')
const common = reactive({ workshopId: '', workCenterId: '' })
const dailyQuery = reactive({ businessDate: today })
const laborQuery = reactive({ fromDate: today, toDate: today, skillCode: '' })
const orderQuery = reactive({ orderId: '' })
const dailyReport = ref<ApsDailyProductionReport>()
const laborReport = ref<ApsLaborCapacityReport>()
const orderReport = ref<ApsOrderDeliveryReport>()

const orderLines = computed(() => orderReport.value ? flattenOrderLines(orderReport.value) : [])
const metadata = computed<ApsReportMetadata | undefined>(() => {
  if (activeTab.value === 'daily') return dailyReport.value?.metadata
  if (activeTab.value === 'labor') return laborReport.value?.metadata
  return orderReport.value?.metadata
})

const optional = (value: string) => value.trim() || undefined

const loadCurrent = async () => {
  loading.value = true
  loadError.value = ''
  try {
    if (activeTab.value === 'daily') {
      dailyReport.value = await getDailyProductionReport({
        businessDate: dailyQuery.businessDate,
        workshopId: optional(common.workshopId),
        workCenterId: optional(common.workCenterId)
      })
    } else if (activeTab.value === 'labor') {
      laborReport.value = await getLaborCapacityReport({
        fromDate: laborQuery.fromDate,
        toDate: laborQuery.toDate,
        workshopId: optional(common.workshopId),
        workCenterId: optional(common.workCenterId),
        skillCode: optional(laborQuery.skillCode)
      })
    } else {
      orderReport.value = await getOrderDeliveryReport({ orderId: optional(orderQuery.orderId) })
    }
  } catch {
    loadError.value = '报表读取失败。请核对日期、UUID、APS 报表开关、接口连接和车间数据权限后重试。'
  } finally {
    loading.value = false
  }
}

const exportCurrent = async () => {
  exporting.value = true
  try {
    let blob: Blob
    let filename: string
    if (activeTab.value === 'daily') {
      blob = await exportDailyProductionReport({ businessDate: dailyQuery.businessDate,
        workshopId: optional(common.workshopId), workCenterId: optional(common.workCenterId) })
      filename = `aps-daily-production-${dailyQuery.businessDate}.csv`
    } else if (activeTab.value === 'labor') {
      blob = await exportLaborCapacityReport({ fromDate: laborQuery.fromDate, toDate: laborQuery.toDate,
        workshopId: optional(common.workshopId), workCenterId: optional(common.workCenterId),
        skillCode: optional(laborQuery.skillCode) })
      filename = `aps-labor-capacity-${laborQuery.fromDate}-${laborQuery.toDate}.csv`
    } else {
      blob = await exportOrderDeliveryReport({ orderId: optional(orderQuery.orderId) })
      filename = 'aps-order-delivery.csv'
    }
    const href = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = href
    link.download = filename
    link.click()
    URL.revokeObjectURL(href)
  } catch {
    ElMessage.error('CSV 导出失败；页面数据未改变。')
  } finally {
    exporting.value = false
  }
}

const printPage = () => window.print()
const numberText = (value: number | string) => decimalNumber(value).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
const shortId = (value?: string) => value ? `${value.slice(0, 8)}…` : '未形成'
const timeText = (value?: string) => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { timeZone: metadata.value?.zoneId || 'Asia/Shanghai', hour12: false })
}

onMounted(loadCurrent)
</script>

<style scoped>
.report-page { display: grid; gap: 16px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.head-note { margin-left: 12px; color: var(--el-text-color-secondary); font-size: 13px; }
.actions { display: flex; gap: 8px; }
.id-input { width: 270px; }
.metadata-alert { margin-top: 8px; }
h3 { margin: 20px 0 10px; font-size: 15px; }
@media (max-width: 900px) {
  .page-head { align-items: flex-start; flex-direction: column; }
  .id-input { width: 100%; }
}
@media print {
  .no-print, .filter-card :deep(.el-form), .report-card :deep(.el-tabs__header) { display: none !important; }
  .app-container { padding: 0; }
  .report-card, .filter-card { border: none; box-shadow: none; }
}
</style>
