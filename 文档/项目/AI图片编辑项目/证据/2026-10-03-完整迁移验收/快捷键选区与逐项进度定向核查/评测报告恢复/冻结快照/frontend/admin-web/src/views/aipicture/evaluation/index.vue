<template>
  <div class="app-container">
    <el-alert title="能力、技术评测与用户验收分别记录" description="本页读取实际运行报告。测试算法和插值结果不证明真实模型质量，技术指标不能代替用户业务接受。" type="info" :closable="false" show-icon />
    <el-tabs v-model="tab" class="tabs">
      <el-tab-pane label="能力状态" name="capabilities">
        <el-button v-hasPermi="['aipicture:capability:query']" :loading="capabilityLoading" @click="refreshCapabilities">刷新实际状态</el-button>
        <el-alert v-if="capabilityError" :title="capabilityError" type="error" :closable="false" class="notice" show-icon />
        <el-empty v-if="!capability && !capabilityLoading" description="尚未取得图片运行时能力报告" />
        <template v-if="capability">
          <el-descriptions :column="2" border class="notice">
            <el-descriptions-item label="工具版本">{{ capability.tool_registry_version || capability.toolRegistryVersion || '未报告' }}</el-descriptions-item>
            <el-descriptions-item label="图像供应商">{{ capability.capabilities?.image_generation?.provider || capability.image_provider || '未报告' }}</el-descriptions-item>
            <el-descriptions-item label="运行模式">{{ capabilityMode }}</el-descriptions-item>
            <el-descriptions-item label="可用性">{{ capability.enabled === true ? '已启用' : capability.enabled === false ? '未启用' : '以逐项报告为准' }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="capabilityRows" border class="notice">
            <el-table-column prop="name" label="能力 / 模型" min-width="180" />
            <el-table-column prop="state" label="实际状态" width="160" />
            <el-table-column prop="details" label="版本、模式与缺项" min-width="350" />
          </el-table>
        </template>
      </el-tab-pane>
      <el-tab-pane label="评测任务与报告" name="evaluations">
        <el-button v-hasPermi="['aipicture:evaluation:execute']" type="primary" @click="openCreate">创建评测任务</el-button>
        <el-button v-hasPermi="['aipicture:evaluation:list']" :loading="listLoading" @click="refreshEvaluations">刷新任务</el-button>
        <el-table v-loading="listLoading" :data="evaluations" border class="notice">
          <el-table-column prop="id" label="任务编号" min-width="260" />
          <el-table-column label="执行状态" width="150"><template #default="scope">{{ statusLabel(scope.row.status) }}</template></el-table-column>
          <el-table-column prop="dataset_hash" label="数据集指纹" min-width="220" show-overflow-tooltip />
          <el-table-column prop="created_at" label="创建时间" width="200" />
          <el-table-column label="操作" width="140"><template #default="scope"><el-button link type="primary" @click="showReport(scope.row.id)">查看报告</el-button></template></el-table-column>
        </el-table>
        <pagination v-show="total > 0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="refreshEvaluations" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="creating" title="评测样本与独立预期" width="min(960px, 94vw)" destroy-on-close>
      <el-alert title="选择本人的输入与独立预期素材；样本、指标和阈值随报告保存。" type="info" :closable="false" />
      <el-form :model="sample" label-width="135px" class="notice">
        <el-form-item label="样本名称"><el-input v-model="sample.id" maxlength="80" /></el-form-item>
        <el-form-item label="评测操作"><el-select v-model="sample.task" @change="resetParameters"><el-option v-for="task in evaluationTasks" :key="task.value" :value="task.value" :label="task.label" /></el-select></el-form-item>
        <template v-if="sample.task === 'assess_result'">
          <el-form-item label="专项类型"><el-select v-model="sample.suite" aria-label="专项类型" @change="resetAssessment"><el-option v-for="suite in assessmentSuites" :key="suite.value" :value="suite.value" :label="suite.label" /></el-select></el-form-item>
          <el-form-item label="正式来源任务"><el-input v-model="sample.source_run_id" aria-label="正式来源任务编号" placeholder="填写本人的已成功任务编号" /><el-button :loading="sourceLoading" @click="loadSourceRun">读取正式结果</el-button></el-form-item>
          <el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" show-icon />
          <el-alert v-if="sourceRun" :title="`已读取：${sourceRun.tool} · ${statusLabel(sourceRun.status)} · ${sourceRun.id}`" description="来源文档、选区、供应商及适用工具由中心校验并冻结；不会重新调用生成模型。" type="info" :closable="false" show-icon />
          <el-form-item v-if="['enhancement', 'generation'].includes(sample.suite)" label="正式输出素材"><el-select v-model="sample.output_asset_id" aria-label="正式输出素材" :disabled="!sourceRun" class="full"><el-option v-for="asset in sourceRun?.candidates || []" :key="asset.id" :value="asset.id" :label="assetLabel(asset)" /></el-select></el-form-item>
          <el-alert v-if="sample.suite === 'region_protection'" title="遮罩取自正式任务，不允许覆换；同时核对遮罩外变化像素数必须为 0。" type="info" :closable="false" />
          <el-form-item v-if="sample.suite === 'ocr'" label="独立 OCR 预期"><el-input v-model="expectedOcrText" aria-label="独立 OCR 预期" type="textarea" :rows="5" placeholder='[{"text":"商品名称","bbox":[20,30,120,40]}]' /><p class="field-help">非空 JSON 数组，逐个填写独立文字与框，按位置匹配，无需保持清单顺序。bbox 使用来源画布像素 [x, y, width, height]，不使用归一化坐标；文字数量必须一致，变换后溢出画布的 x/y 可为负数。</p></el-form-item>
          <el-form-item v-if="sample.suite === 'enhancement'" label="真实超分要求"><el-checkbox v-model="sample.require_true_super_resolution">要求真实超分模型（默认开启）</el-checkbox><p class="field-help">当前增强实现报告 true_super_resolution=false，严格超分检查会实际失败；插值或生成式增强不计为真实超分。</p></el-form-item>
        </template>
        <el-form-item v-else label="输入素材"><el-select v-model="sample.input_asset_id" filterable class="full"><el-option v-for="asset in assets" :key="asset.id" :value="asset.id" :label="assetLabel(asset)" /></el-select></el-form-item>
        <el-form-item v-if="sample.task !== 'assess_result' || ['sam', 'enhancement', 'generation'].includes(sample.suite)" :label="sample.task === 'assess_result' && sample.suite === 'sam' ? '独立人工遮罩' : '独立预期素材'"><el-select v-model="sample.expect_asset_id" filterable clearable class="full"><el-option v-for="asset in assets" :key="asset.id" :value="asset.id" :label="assetLabel(asset)" /></el-select></el-form-item>
        <el-form-item v-if="['letterbox', 'resize'].includes(sample.task)" label="目标尺寸"><el-input-number v-model="sample.params.width" :min="32" :max="10000" /> × <el-input-number v-model="sample.params.height" :min="32" :max="10000" /></el-form-item>
        <el-form-item v-if="sample.task === 'prepare_delivery_sizes'" label="投放比例"><el-select v-model="sample.params.ratios" multiple><el-option v-for="ratio in ratios" :key="ratio" :value="ratio" :label="ratio" /></el-select></el-form-item>
        <el-form-item v-if="sample.task === 'encode'" label="输出格式"><el-radio-group v-model="sample.params.format"><el-radio value="png">PNG</el-radio><el-radio value="jpg">JPG 白底</el-radio></el-radio-group></el-form-item>
        <template v-if="sample.task === 'adjust_image'"><el-form-item v-for="adjustment in adjustments" :key="adjustment[0]" :label="adjustment[1]"><el-slider v-model="sample.params[adjustment[0]]" :min="adjustment[0] === 'vignette' ? 0 : -1" :max="1" :step="0.01" show-input /></el-form-item></template>
        <el-form-item v-if="sample.task !== 'assess_result'" label="检查指标"><el-checkbox-group v-model="sample.metrics"><el-checkbox v-for="metric in metricDefinitions" :key="metric.value" :value="metric.value">{{ metric.label }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item v-for="metric in selectedMetrics" :key="metric.value" :label="`${metric.label} ${metric.direction}`"><el-input-number v-model="sample.thresholds[metric.value]" :step="metric.value === 'psnr' || metric.value === 'mae' ? 1 : 0.01" :min="metric.min ?? 0" :max="metric.max" /></el-form-item>
        <template v-if="sample.task === 'assess_result'">
          <el-form-item label="人工质量"><el-select v-model="sample.human_review.status" aria-label="人工质量状态"><el-option value="pending" label="待人工判定" /><el-option value="passed" label="人工质量通过" /><el-option value="failed" label="人工质量未通过" /></el-select></el-form-item>
          <el-form-item label="人工说明"><el-input v-model="sample.human_review.notes" aria-label="人工质量说明" type="textarea" :rows="3" maxlength="2000" placeholder="通过需填写独立核对说明；此记录不等于用户接受。" /></el-form-item>
          <el-alert title="人工待判定或未通过时，专项整体不能通过；用户接受仍需用户另行明确确认。" type="info" :closable="false" />
        </template>
      </el-form>
      <el-button :disabled="sourceLoading || submitting" @click="addSample">加入样本清单</el-button>
      <el-table :data="manifest" class="notice" border><el-table-column prop="id" label="样本" /><el-table-column label="操作"><template #default="scope">{{ assessmentSuites.find(suite => suite.value === scope.row.suite)?.label || scope.row.task }}</template></el-table-column><el-table-column label="指标"><template #default="scope">{{ scope.row.task === 'assess_result' ? Object.keys(scope.row.thresholds).join('、') : scope.row.metrics.join('、') }}</template></el-table-column><el-table-column label="人工质量"><template #default="scope">{{ reviewLabel(scope.row.human_review?.status) }}</template></el-table-column><el-table-column label="移除" width="80"><template #default="scope"><el-button link type="danger" @click="manifest.splice(scope.$index, 1)">移除</el-button></template></el-table-column></el-table>
      <template #footer><el-button @click="creating = false">取消</el-button><el-button type="primary" :disabled="manifest.length === 0" :loading="submitting" @click="submit">提交评测</el-button></template>
    </el-dialog>

    <el-dialog v-model="reportOpen" title="评测报告详情" width="min(1000px, 94vw)">
      <el-alert title="运行报告保留实际指标与错误；用户接受需另行明确确认。" type="info" :closable="false" />
      <el-alert v-if="reportError" :title="reportError" type="error" :closable="false" class="notice" show-icon />
      <el-descriptions v-if="report" :column="2" border class="notice"><el-descriptions-item label="执行状态">{{ statusLabel(report.status) }}</el-descriptions-item><el-descriptions-item label="数据集指纹">{{ report.dataset_hash }}</el-descriptions-item><el-descriptions-item label="人工质量">按专项样本分别记录，见下表</el-descriptions-item><el-descriptions-item label="用户接受">不由技术评测或人工质量代签</el-descriptions-item></el-descriptions>
      <el-table v-if="report" :data="reportRows" class="notice" border><el-table-column prop="id" label="样本" /><el-table-column prop="suiteLabel" label="操作" /><el-table-column prop="technicalLabel" label="技术判定" /><el-table-column prop="metricValues" label="实际技术指标" min-width="220" /><el-table-column prop="modelLabel" label="真实模型核实" /><el-table-column prop="reviewLabel" label="人工质量" /><el-table-column prop="reviewNotes" label="人工说明" min-width="200" /><el-table-column prop="overallLabel" label="专项整体" /><el-table-column prop="error" label="实际错误" min-width="200" /></el-table>
      <pre v-if="report || reportLoading" class="report-data">{{ report ? JSON.stringify(report, null, 2) : '正在读取报告…' }}</pre>
      <template #footer><el-button @click="reportOpen = false">关闭</el-button><el-button v-if="reportError" :loading="reportLoading" @click="showReport(reportId)">重新读取报告</el-button><el-button type="primary" :disabled="!report" @click="downloadReport">下载完整报告</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { saveAs } from 'file-saver'
import auth from '@/plugins/auth'
import { readCapabilities, readEvaluations, readReport, readAssets, readAssessmentRun, createEvaluation, evaluationTasks, metricDefinitions, assessmentSuites, assessmentMetricDefinitions, validateAssessmentSource, validateEvaluationCase } from '@/api/aipicture/evaluation'

const tab = ref('capabilities')
const capability = ref(null)
const capabilityLoading = ref(false)
const capabilityError = ref('')
const listLoading = ref(false)
const evaluations = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })
const creating = ref(false)
const assets = ref([])
const manifest = ref([])
const submitting = ref(false)
const reportOpen = ref(false)
const report = ref(null)
const reportId = ref('')
const reportLoading = ref(false)
const reportError = ref('')
const sourceRun = ref(null)
const sourceLoading = ref(false)
const sourceError = ref('')
const expectedOcrText = ref('')
let sourceRead = 0
let reportRead = 0
let disposed = false
const ratios = ['1:1', '4:5', '3:4', '9:16', '16:9']
const adjustments = [['brightness', '亮度'], ['contrast', '对比度'], ['highlights', '高光'], ['shadows', '阴影'], ['temperature', '色温'], ['tint', '色调'], ['saturation', '饱和度'], ['vibrance', '自然饱和度'], ['sharpness', '锐度'], ['clarity', '清晰度'], ['vignette', '暗角']]
const defaultThresholds = Object.fromEntries([...metricDefinitions, ...assessmentSuites.flatMap(suite => suite.metrics)].map(metric => [metric.value, metric.threshold]))
const sample = reactive({ id: '', input_asset_id: '', expect_asset_id: '', task: 'letterbox', params: { width: 1080, height: 1080 }, metrics: ['size', 'contain'], thresholds: { ...defaultThresholds }, suite: 'sam', source_run_id: '', output_asset_id: '', require_true_super_resolution: true, human_review: { status: 'pending', notes: '' } })
const selectedMetrics = computed(() => sample.task === 'assess_result' ? assessmentMetricDefinitions(sample.suite, Boolean(sample.expect_asset_id)) : metricDefinitions.filter(metric => sample.metrics.includes(metric.value)))
const reviewLabel = state => ({ pending: '待人工判定', passed: '人工质量通过', failed: '人工质量未通过' })[state] || '未记录'
const checkLabel = state => state === true ? '通过' : state === false ? '未通过' : '尚未报告'
const reportRows = computed(() => (report.value?.manifest || []).map(item => {
  const actual = report.value?.metrics?.cases?.find(entry => entry.id === item.id)
  const review = actual?.human_review || item.human_review
  return { id: item.id, suiteLabel: assessmentSuites.find(suite => suite.value === item.suite)?.label || item.task, technicalLabel: checkLabel(actual?.technical_passed ?? actual?.passed), metricValues: actual ? JSON.stringify(actual.metrics || actual.outputs?.map(output => output.metrics) || {}) : '尚未报告', modelLabel: item.task !== 'assess_result' ? '确定性检查' : actual?.real_model_verified === true ? '已核实' : actual ? '未核实' : '尚未报告', reviewLabel: reviewLabel(review?.status), reviewNotes: review?.notes || '', overallLabel: checkLabel(actual?.overall ?? actual?.passed), error: actual?.error || actual?.reasons?.join('；') || '' }
}))
const capabilityMode = computed(() => {
  const states = Object.values(capability.value?.capabilities || {})
  return states.some(value => value.offline_test === true) ? '离线技术测试' : states.some(value => value.offline_test === false) ? '正式运行配置' : '未报告'
})
const capabilityRows = computed(() => {
  const data = capability.value || {}
  const names = { deterministic_pixels: '像素处理', text_rendering: '文字渲染', image_generation: '图片生成', image_edit: '生成式图片编辑', enhancement: '图片增强', matting: '抠图', sam: '点选分割', ocr: '文字提取', planner: '自然语言规划' }
  const states = Object.entries(data.capabilities || {}).map(([name, value]) => ({ name: names[name] || name, state: value.ready !== true ? '未就绪' : value.validation === 'files_and_dependencies_only' ? value.loaded === true ? '已加载模型' : '资源齐备，未加载' : '调用前提齐备', details: JSON.stringify(value) }))
  const tools = Array.isArray(data.tools) ? data.tools.map(tool => ({ name: tool.label || tool.name, state: '已注册', details: `执行端：${tool.executionOwner}；版本：${data.tool_registry_version || '未报告'}` })) : []
  return [...states, ...tools]
})
const statusLabel = state => ({ queued: '排队', running: '执行中', succeeded: '完成', failed: '失败', cancelled: '已取消', partial: '部分成功', uncertain: '外部结果待核对' })[state] || state || '未报告'
const assetLabel = asset => `${asset.kind || '素材'} · ${asset.width}×${asset.height} · ${asset.id}`
const messageOf = error => error?.response?.data?.msg || error?.message || '请求失败，请核对运行配置'

async function refreshCapabilities() {
  if (!auth.hasPermi('aipicture:capability:query')) return
  capabilityLoading.value = true
  try { capability.value = (await readCapabilities()).data; capabilityError.value = '' }
  catch (error) { capability.value = null; capabilityError.value = messageOf(error) }
  finally { capabilityLoading.value = false }
}
async function refreshEvaluations() {
  if (!auth.hasPermi('aipicture:evaluation:list')) return
  listLoading.value = true
  try { const response = await readEvaluations(query); evaluations.value = response.rows || response.data || []; total.value = response.total || evaluations.value.length }
  catch (error) { ElMessage.error(messageOf(error)) }
  finally { listLoading.value = false }
}
async function openCreate() {
  try { const response = await readAssets(); assets.value = response.rows || response.data || []; manifest.value = []; clearSource(); creating.value = true }
  catch (error) { ElMessage.error(messageOf(error)) }
}
function resetParameters() {
  sample.params = sample.task === 'adjust_image' ? Object.fromEntries(adjustments.map(([name]) => [name, 0])) : sample.task === 'prepare_delivery_sizes' ? { ratios: ['1:1', '4:5', '9:16'] } : sample.task === 'encode' ? { format: 'png' } : ['letterbox', 'resize'].includes(sample.task) ? { width: 1080, height: 1080 } : {}
  clearSource()
  if (sample.task === 'assess_result') resetAssessment()
}
function clearSource() { sourceRead += 1; sourceRun.value = null; sourceError.value = ''; sourceLoading.value = false; sample.output_asset_id = '' }
function resetAssessment() { clearSource(); sample.expect_asset_id = ''; expectedOcrText.value = ''; sample.thresholds = { ...defaultThresholds }; sample.require_true_super_resolution = true; sample.human_review = { status: 'pending', notes: '' } }
async function loadSourceRun() {
  clearSource()
  const id = sample.source_run_id.trim()
  if (!id) { sourceError.value = '请填写正式来源任务编号'; return }
  sample.source_run_id = id
  const serial = sourceRead
  sourceLoading.value = true
  try {
    const run = (await readAssessmentRun(id)).data
    if (serial !== sourceRead || !creating.value || sample.task !== 'assess_result') return
    if (!run || run.id !== id || run.status !== 'succeeded') throw new Error('来源任务尚未成功；请读取已完成的正式结果')
    sourceRun.value = run
  } catch (error) { if (serial === sourceRead && creating.value) sourceError.value = messageOf(error) }
  finally { if (serial === sourceRead) sourceLoading.value = false }
}
watch(() => sample.source_run_id, clearSource, { flush: 'sync' })
watch(creating, value => { if (!value) clearSource() })
function clearReport() { reportRead += 1; report.value = null; reportId.value = ''; reportLoading.value = false; reportError.value = '' }
watch(reportOpen, value => { if (!value) clearReport() }, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; clearSource(); clearReport() })
function addSample() {
  try {
    if (sourceLoading.value) throw new Error('正式来源读取尚未完成')
    let item
    if (sample.task === 'assess_result') {
      const raw = { id: sample.id.trim(), task: sample.task, suite: sample.suite, source_run_id: sample.source_run_id.trim(), thresholds: Object.fromEntries(selectedMetrics.value.map(metric => [metric.value, sample.thresholds[metric.value]])), human_review: { ...sample.human_review } }
      if (sample.expect_asset_id) raw.expect_asset_id = sample.expect_asset_id
      if (['enhancement', 'generation'].includes(sample.suite)) raw.output_asset_id = sample.output_asset_id
      if (sample.suite === 'enhancement') raw.require_true_super_resolution = sample.require_true_super_resolution
      if (sample.suite === 'ocr') {
        try { raw.expected_ocr = JSON.parse(expectedOcrText.value) } catch { throw new Error('OCR 预期需要合法 JSON 数组') }
      }
      validateAssessmentSource(raw, sourceRun.value)
      item = validateEvaluationCase(raw)
    } else {
      const raw = { id: sample.id, input_asset_id: sample.input_asset_id, expect_asset_id: sample.expect_asset_id, task: sample.task, params: sample.params, metrics: sample.metrics, thresholds: Object.fromEntries(metricDefinitions.map(metric => [metric.value, sample.thresholds[metric.value]])) }
      item = validateEvaluationCase(JSON.parse(JSON.stringify(raw)))
    }
    if (manifest.value.some(entry => entry.id === item.id)) throw new Error('样本名称不能重复')
    if (!item.expect_asset_id) delete item.expect_asset_id
    manifest.value.push(item)
    sample.id = ''
    sample.human_review = { status: 'pending', notes: '' }
    ElMessage.success('样本已加入清单')
  } catch (error) { ElMessage.error(messageOf(error)) }
}
async function submit() {
  submitting.value = true
  try { await createEvaluation(manifest.value); creating.value = false; tab.value = 'evaluations'; await refreshEvaluations(); ElMessage.success('评测已提交，请读取实际报告') }
  catch (error) { ElMessage.error(messageOf(error)) }
  finally { submitting.value = false }
}
async function showReport(id) {
  if (disposed) return
  const serial = ++reportRead
  reportOpen.value = true
  reportId.value = id
  report.value = null
  reportLoading.value = true
  reportError.value = ''
  const isCurrent = () => !disposed && serial === reportRead && reportOpen.value
  try {
    const data = (await readReport(id)).data
    if (!isCurrent()) return
    if (!data || data.id !== id) throw new Error('未取得所选评测任务的报告，请重新读取')
    report.value = data
  } catch (error) { if (isCurrent()) reportError.value = messageOf(error) }
  finally { if (isCurrent()) reportLoading.value = false }
}
function downloadReport() { saveAs(new Blob([JSON.stringify(report.value, null, 2)], { type: 'application/json;charset=utf-8' }), `图片评测-${report.value.id}.json`) }
onMounted(() => { refreshCapabilities(); refreshEvaluations() })
</script>

<style scoped>
.tabs, .notice { margin-top: 18px; }
.full { width: 100%; }
.report-data { max-height: 460px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: #f4f6fa; padding: 16px; }
.field-help { width: 100%; margin: 6px 0 0; line-height: 1.6; color: #606266; }
</style>
