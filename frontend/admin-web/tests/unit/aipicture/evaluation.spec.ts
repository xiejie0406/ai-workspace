import { describe, expect, it, vi } from 'vitest'
import type { Mock } from 'vitest'
vi.mock('@/utils/request', () => ({ default: vi.fn() }))
import request from '@/utils/request'
import { validateEvaluationCase, validateAssessmentSource, evaluationTasks, metricDefinitions, assessmentSuites, assessmentMetricDefinitions, readAssessmentRun, createEvaluation } from '@/api/aipicture/evaluation'

describe('评测输入与独立预期', () => {
  it('保留原六类任务和五种指标', () => {
    expect(evaluationTasks.filter(task => task.value !== 'assess_result').map(task => task.value)).toEqual(['remove_background', 'adjust_image', 'letterbox', 'prepare_delivery_sizes', 'encode', 'resize'])
    expect(metricDefinitions.find(metric => metric.value === 'mae')?.direction).toBe('≤')
    expect(metricDefinitions).toHaveLength(5)
  })
  it('不让质量指标使用缺少独立预期的素材', () => {
    expect(() => validateEvaluationCase({ id: 'one', input_asset_id: 'input', task: 'adjust_image', params: {}, metrics: ['mae'], thresholds: { mae: 12 } })).toThrow('独立预期')
    expect(() => validateEvaluationCase({ id: 'one', input_asset_id: 'input', task: 'resize', params: { width: 1080, height: 1080 }, metrics: ['size'], thresholds: { size: 1 } })).not.toThrow()
  })

  it('提供只评估正式结果的专项入口', () => {
    expect(evaluationTasks.some(task => task.value === 'assess_result')).toBe(true)
  })

  it('SAM 专项以正式任务和独立人工遮罩为输入', () => {
    expect(() => validateEvaluationCase({ id: 'sam-one', task: 'assess_result', suite: 'sam', source_run_id: 'formal-sam', expect_asset_id: 'independent-mask', thresholds: { alpha_iou: 0.8 }, human_review: { status: 'pending', notes: '' } })).not.toThrow()
  })

  it('五专项独立保留指标，增强与生成可加独立像素预期', () => {
    expect(assessmentSuites.map(suite => suite.value)).toEqual(['sam', 'region_protection', 'ocr', 'enhancement', 'generation'])
    expect(assessmentMetricDefinitions('region_protection').map(metric => metric.value)).toEqual(['outside_mae', 'outside_max'])
    expect(assessmentMetricDefinitions('generation', true).map(metric => metric.value)).toEqual(['size', 'mae', 'psnr'])
  })

  it('拒绝客户端自造来源文档、遮罩或供应商', () => {
    const item = { id: 'region', task: 'assess_result', suite: 'region_protection', source_run_id: 'formal', thresholds: { outside_mae: 0, outside_max: 0 }, human_review: { status: 'pending', notes: '' } }
    for (const key of ['assessment', 'document', 'provider', 'mask_asset_id', 'input_asset_id']) expect(() => validateEvaluationCase({ ...item, [key]: {} })).toThrow('中心冻结')
    expect(validateEvaluationCase(item)).toEqual(item)
  })

  it('OCR 独立预期必须含非空文字和合法画布像素框', () => {
    const item = { id: 'ocr', task: 'assess_result', suite: 'ocr', source_run_id: 'formal', thresholds: { cer: 0, bbox_iou: 0.8 }, human_review: { status: 'pending', notes: '' } }
    expect(() => validateEvaluationCase({ ...item, expected_ocr: [] })).toThrow('1–128')
    expect(() => validateEvaluationCase({ ...item, expected_ocr: [{ text: '商品', bbox: [1, 2, 0, 4] }] })).toThrow('画布像素')
    expect(validateEvaluationCase({ ...item, expected_ocr: [{ text: '商品', bbox: [20, 30, 120, 40] }] }).expected_ocr).toEqual([{ text: '商品', bbox: [20, 30, 120, 40] }])
    expect(() => validateEvaluationCase({ ...item, expected_ocr: [{ text: '𠮷'.repeat(2000), bbox: [20, 30, 120, 40] }] })).not.toThrow()
    expect(() => validateEvaluationCase({ ...item, expected_ocr: [{ text: '𠮷'.repeat(2001), bbox: [20, 30, 120, 40] }] })).toThrow('2000')
    expect(() => validateEvaluationCase({ ...item, expected_ocr: Array.from({ length: 17 }, () => ({ text: '𠮷'.repeat(2000), bbox: [20, 30, 120, 40] })) })).toThrow('32000')
  })

  it('人工待判定与未通过原样保留，通过必须有独立说明', () => {
    const item = { id: 'gen', task: 'assess_result', suite: 'generation', source_run_id: 'formal', output_asset_id: 'candidate', thresholds: { size: 1 } }
    for (const status of ['pending', 'failed']) expect(validateEvaluationCase({ ...item, human_review: { status, notes: '' } }).human_review.status).toBe(status)
    expect(() => validateEvaluationCase({ ...item, human_review: { status: 'passed', notes: '  ' } })).toThrow('非空说明')
    expect(validateEvaluationCase({ ...item, human_review: { status: 'passed', notes: '逐张核对主体与文字' } }).human_review.notes).toBe('逐张核对主体与文字')
  })

  it('OCR 变换后负坐标可用，有限画布坐标越界与无效数字被拒绝', () => {
    const item = { id: 'ocr', task: 'assess_result', suite: 'ocr', source_run_id: 'formal', thresholds: { cer: 0, bbox_iou: 0.8 }, human_review: { status: 'pending', notes: '' } }
    expect(() => validateEvaluationCase({ ...item, expected_ocr: [{ text: '价格', bbox: [-20, -30, 120, 40] }] })).not.toThrow()
    for (const bbox of [[-100001, 30, 120, 40], [20, 100001, 120, 40], [20, 30, Infinity, 40], [20, 30, 120, -40], [NaN, 30, 120, 40]]) expect(() => validateEvaluationCase({ ...item, expected_ocr: [{ text: '价格', bbox }] })).toThrow('有限画布像素')
  })

  it('区域保护不能调成容忍遮罩外像素变化的正阈值', () => {
    const item = { id: 'protect', task: 'assess_result', suite: 'region_protection', source_run_id: 'formal', thresholds: { outside_mae: 0, outside_max: 0 }, human_review: { status: 'pending', notes: '' } }
    expect(() => validateEvaluationCase(item)).not.toThrow()
    for (const thresholds of [{ outside_mae: 0.001, outside_max: 0 }, { outside_mae: 0, outside_max: 1 }]) expect(() => validateEvaluationCase({ ...item, thresholds })).toThrow('阈值')
    expect(assessmentMetricDefinitions('region_protection')).toEqual([expect.objectContaining({ min: 0, max: 0 }), expect.objectContaining({ min: 0, max: 0 })])
  })

  it('只选择本人成功正式来源返回的候选，不混入其他任务', () => {
    const item = { source_run_id: 'formal', suite: 'generation', output_asset_id: 'chosen' }
    const run = { id: 'formal', status: 'succeeded', candidates: [{ id: 'chosen' }, { id: 'other' }] }
    expect(() => validateAssessmentSource(item, run)).not.toThrow()
    expect(() => validateAssessmentSource({ ...item, output_asset_id: 'foreign' }, run)).toThrow('正式任务返回')
    expect(() => validateAssessmentSource(item, { ...run, id: 'older' })).toThrow('该编号')
    expect(() => validateAssessmentSource(item, { ...run, status: 'running' })).toThrow('成功正式')
  })

  it('严格超分意图与专项阈值完整保留，拒绝非法阈值', () => {
    const item = { id: 'enhance', task: 'assess_result', suite: 'enhancement', source_run_id: 'formal', output_asset_id: 'chosen', thresholds: { size: 1 }, require_true_super_resolution: true, human_review: { status: 'pending', notes: '' } }
    expect(validateEvaluationCase(item).require_true_super_resolution).toBe(true)
    expect(() => validateEvaluationCase({ ...item, thresholds: { size: 0 } })).toThrow('阈值')
    expect(() => validateEvaluationCase({ ...item, thresholds: { size: 1, outside_max: 0 } })).toThrow('本专项')
    expect(() => validateEvaluationCase({ ...item, thresholds: { size: Number.NaN } })).toThrow('阈值')
  })

  it('正式结果与评测提交复用若依请求，保留信封和幂等metadata', async () => {
    const mocked = vi.mocked(request) as unknown as Mock<(config: { url: string, method?: string, preserveBusinessError?: boolean, data?: any }) => Promise<any>>
    const envelope = { code: 200, data: { id: '正式/任务', status: 'succeeded' } }
    mocked.mockResolvedValueOnce(envelope)
    expect(await readAssessmentRun('正式/任务')).toBe(envelope)
    expect(mocked).toHaveBeenLastCalledWith({ url: '/aipicture/runs/%E6%AD%A3%E5%BC%8F%2F%E4%BB%BB%E5%8A%A1', method: 'get', preserveBusinessError: true })
    const manifest = [{ id: 'sam', task: 'assess_result', suite: 'sam', source_run_id: 'formal', expect_asset_id: 'mask', thresholds: { alpha_iou: 0.8 }, human_review: { status: 'pending', notes: '' } }]
    mocked.mockResolvedValueOnce({ code: 200, data: { id: 'evaluation' } })
    await createEvaluation(manifest)
    expect(mocked.mock.calls.at(-1)?.[0]).toMatchObject({ url: '/aipicture/evaluations', preserveBusinessError: true, data: { schemaVersion: '1', toolRegistryVersion: '2026.10-r2', manifest } })
    expect(mocked.mock.calls.at(-1)?.[0].data.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
  })
})
