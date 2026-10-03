import request from '@/utils/request'

export const readCapabilities = () => request({ url: '/aipicture/capabilities', method: 'get', preserveBusinessError: true })
export const readEvaluations = params => request({ url: '/aipicture/evaluations', method: 'get', params, preserveBusinessError: true })
export const readReport = id => request({ url: `/aipicture/evaluations/${encodeURIComponent(id)}/report`, method: 'get', preserveBusinessError: true })
export const readAssets = () => request({ url: '/aipicture/assets', method: 'get', params: { pageNum: 1, pageSize: 100, limit: 100 }, preserveBusinessError: true })
export const readAssessmentRun = id => request({ url: `/aipicture/runs/${encodeURIComponent(id)}`, method: 'get', preserveBusinessError: true })
export const createEvaluation = manifest => request({
  url: '/aipicture/evaluations', method: 'post', preserveBusinessError: true,
  data: { schemaVersion: '1', toolRegistryVersion: '2026.10-r2', idempotencyKey: crypto.randomUUID(), manifest }
})

export const evaluationTasks = [
  { value: 'remove_background', label: '去背景' },
  { value: 'adjust_image', label: '调色' },
  { value: 'letterbox', label: '居中留白' },
  { value: 'prepare_delivery_sizes', label: '投放尺寸' },
  { value: 'encode', label: '格式编码' },
  { value: 'resize', label: '指定尺寸缩放' },
  { value: 'assess_result', label: '评估已完成的正式输出（不重跑模型）' }
]

export const metricDefinitions = [
  { value: 'alpha_iou', label: '透明区域交并比', direction: '≥', threshold: 0.8 },
  { value: 'mae', label: '平均像素误差', direction: '≤', threshold: 12 },
  { value: 'psnr', label: '峰值信噪比', direction: '≥', threshold: 20 },
  { value: 'contain', label: '原图完整容纳', direction: '≥', threshold: 0.99 },
  { value: 'size', label: '目标尺寸一致', direction: '≥', threshold: 1 }
]

export const assessmentSuites = [
  { value: 'sam', label: 'SAM 选区质量', metrics: [{ value: 'alpha_iou', label: '人工遮罩交并比', direction: '≥', threshold: 0.8, max: 1 }] },
  { value: 'region_protection', label: '区域外像素保护', metrics: [{ value: 'outside_mae', label: '遮罩外平均误差（保护不可降低）', direction: '≤', threshold: 0, min: 0, max: 0 }, { value: 'outside_max', label: '遮罩外最大误差（保护不可降低）', direction: '≤', threshold: 0, min: 0, max: 0 }] },
  { value: 'ocr', label: 'OCR 文字与位置', metrics: [{ value: 'cer', label: '文字字符错误率', direction: '≤', threshold: 0, max: 10 }, { value: 'bbox_iou', label: '文字框交并比', direction: '≥', threshold: 0.8, max: 1 }] },
  { value: 'enhancement', label: '增强与真实超分', metrics: [{ value: 'size', label: '正式比例对应尺寸', direction: '≥', threshold: 1, min: 1, max: 1 }] },
  { value: 'generation', label: '生成内容质量', metrics: [{ value: 'size', label: '正式比例对应尺寸', direction: '≥', threshold: 1, min: 1, max: 1 }] }
]

export function assessmentMetricDefinitions(suite, hasExpect = false) {
  const definitions = assessmentSuites.find(entry => entry.value === suite)?.metrics || []
  return hasExpect && ['enhancement', 'generation'].includes(suite)
    ? [...definitions, ...metricDefinitions.filter(entry => ['mae', 'psnr'].includes(entry.value)).map(entry => ({ ...entry, max: 255 }))]
    : definitions
}

export function validateAssessmentSource(item, run) {
  if (!run || run.id !== item.source_run_id || run.status !== 'succeeded') throw new Error('请先读取该编号的成功正式任务')
  if (['enhancement', 'generation'].includes(item.suite) && !run.candidates?.some(asset => asset.id === item.output_asset_id)) {
    throw new Error('请选择该正式任务返回的输出素材')
  }
}

function validateAssessment(item) {
  if (!item.id?.trim() || !item.source_run_id?.trim()) throw new Error('请填写样本名称和正式来源任务编号')
  if (!assessmentSuites.some(entry => entry.value === item.suite)) throw new Error('未知专项评测类型')
  const allowed = ['id', 'task', 'suite', 'source_run_id', 'output_asset_id', 'expect_asset_id', 'expected_ocr', 'thresholds', 'require_true_super_resolution', 'human_review']
  if (Object.keys(item).some(key => !allowed.includes(key))) throw new Error('专项来源、文档、遮罩与供应商由中心冻结，不能自行指定')
  if (item.suite === 'sam' && !item.expect_asset_id) throw new Error('SAM 专项需要独立人工遮罩素材')
  if (['enhancement', 'generation'].includes(item.suite) && !item.output_asset_id) throw new Error('请从正式来源任务中选择输出素材')
  if (item.suite === 'ocr') {
    if (!Array.isArray(item.expected_ocr) || !item.expected_ocr.length || item.expected_ocr.length > 128) throw new Error('OCR 专项需要 1–128 个独立文字和框预期')
    for (const entry of item.expected_ocr) {
      if (!entry || typeof entry.text !== 'string' || !entry.text.trim() || [...entry.text].length > 2000 || !Array.isArray(entry.bbox) || entry.bbox.length !== 4 || entry.bbox.some(value => !Number.isFinite(value) || Math.abs(value) > 100000) || entry.bbox[2] <= 0 || entry.bbox[3] <= 0) throw new Error('OCR 预期框应为有限画布像素 [x, y, width, height]，每值绝对值最多 100000，文字不能为空或超过 2000 字')
    }
    if (item.expected_ocr.reduce((sum, entry) => sum + [...entry.text].length, 0) > 32000) throw new Error('OCR 独立预期文字总数不能超过 32000 字')
  }
  const review = item.human_review
  if (!review || !['pending', 'passed', 'failed'].includes(review.status) || typeof review.notes !== 'string' || review.notes.length > 2000) throw new Error('请记录人工质量状态和说明（最多 2000 字）')
  if (review.status === 'passed' && !review.notes.trim()) throw new Error('人工质量通过需要非空说明，不能代签用户接受')
  if (item.suite === 'enhancement' && typeof item.require_true_super_resolution !== 'boolean') throw new Error('请明确是否要求真实超分')
  const definitions = assessmentMetricDefinitions(item.suite, Boolean(item.expect_asset_id))
  const thresholds = item.thresholds || {}
  if (Object.keys(thresholds).some(name => !definitions.some(entry => entry.value === name))) throw new Error('阈值不属于本专项指标')
  for (const metric of definitions) {
    const value = thresholds[metric.value]
    if (!Number.isFinite(value) || value < (metric.min ?? 0) || (metric.max !== undefined && value > metric.max)) throw new Error('专项指标阈值不合法')
  }
  return item
}

/** Web 评测仅引用正式本人资产，不提供任意服务器文件路径入口。 */
export function validateEvaluationCase(item) {
  if (item.task === 'assess_result') return validateAssessment(item)
  if (!item.id?.trim() || !item.input_asset_id || !item.metrics?.length) throw new Error('请填写样本名称、输入素材和至少一项指标')
  if (!evaluationTasks.some(task => task.value === item.task)) throw new Error('未知评测任务')
  if (item.metrics.some(name => ['alpha_iou', 'mae', 'psnr'].includes(name)) && !item.expect_asset_id) {
    throw new Error('像素质量指标需要独立预期素材')
  }
  if (item.metrics.includes('size') && !['letterbox', 'resize', 'prepare_delivery_sizes'].includes(item.task) && !item.expect_asset_id) {
    throw new Error('此任务的尺寸指标需要独立预期素材')
  }
  for (const metric of item.metrics) {
    if (!metricDefinitions.some(entry => entry.value === metric) || !Number.isFinite(item.thresholds[metric])) throw new Error('指标或阈值不合法')
  }
  return item
}
