import { parseCanonicalInt64 } from '../../shared/contracts/wire-scalars'

const SECTION_IDS = new Set(['basic', 'params', 'intro', 'detail', 'main', 'gallery', 'detailImages'])

function record(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('分区提取数据格式错误')
  const result = value as Record<string, unknown>
  if (Object.keys(result).some(key => !keys.includes(key))) throw new Error('分区提取含未知字段')
  return result
}
function text(value: unknown, max: number, allowEmpty = false): string {
  if (typeof value !== 'string' || value.length > max || (!allowEmpty && !value.trim())) throw new Error('分区提取文字不合法')
  return value
}
function revision(value: unknown, positive = false): string {
  try {
    const parsed = parseCanonicalInt64(value)
    if (positive && parsed === '0') throw new Error()
    return parsed
  } catch { throw new Error('分区提取版本不合法') }
}
function sourceId(value: unknown): string {
  const source = text(value, 128)
  if (/[\u0000-\u001f\u007f-\u009f]/.test(source)) throw new Error('分区来源标识不合法')
  return source
}

/** 仅核对传输边界；后台继续裁决持久化与来源，不制造预览或替代后台授权。 */
export function validateCaptureExtraction(value: unknown): void {
  const extraction = record(value, ['protocolVersion', 'documentId', 'pageFingerprint', 'selectionRevision', 'confirmationRevision', 'selectedSections', 'sections', 'parameterRows', 'ranges'])
  if (extraction.protocolVersion !== 1) throw new Error('分区协议版本不兼容，请重新提取')
  const documentId = text(extraction.documentId, 128)
  const fingerprint = text(extraction.pageFingerprint, 64)
  if (!/^[0-9a-f]{64}$/.test(fingerprint)) throw new Error('页面指纹不合法')
  const selection = revision(extraction.selectionRevision)
  revision(extraction.confirmationRevision, true)
  if (!Array.isArray(extraction.selectedSections) || !extraction.selectedSections.length || extraction.selectedSections.length > 7) throw new Error('至少选择一个采集分区')
  const selected = new Set(extraction.selectedSections)
  if (selected.size !== extraction.selectedSections.length || [...selected].some(id => typeof id !== 'string' || !SECTION_IDS.has(id))) throw new Error('采集分区重复或未知')
  if (!Array.isArray(extraction.sections) || extraction.sections.length !== selected.size) throw new Error('所选分区还未完成有效提取')
  const sourceIds = new Map<string, Set<string>>()
  let totalSources = 0
  for (const value of extraction.sections) {
    const section = record(value, ['sectionId', 'documentId', 'pageFingerprint', 'selectionRevision', 'rangeRevision', 'extractedRangeRevision', 'resultRevision', 'sourceIds', 'missingReason', 'missingConfirmed'])
    const sectionId = text(section.sectionId, 20)
    if (!selected.has(sectionId) || sourceIds.has(sectionId)) throw new Error('提交的分区与当前选择不一致')
    if (section.documentId !== documentId || section.pageFingerprint !== fingerprint || section.selectionRevision !== selection) throw new Error('分区属于旧页面或旧选择，请重新提取')
    const range = revision(section.rangeRevision)
    if (revision(section.extractedRangeRevision) !== range) throw new Error('分区范围已变化，请重新提取')
    revision(section.resultRevision, true)
    if (!Array.isArray(section.sourceIds) || section.sourceIds.length > 1000) throw new Error('分区来源数量超限')
    const sources = new Set(section.sourceIds.map(sourceId))
    if (sources.size !== section.sourceIds.length) throw new Error('分区来源重复')
    totalSources += sources.size
    if (totalSources > 3000) throw new Error('总来源数量超限')
    sourceIds.set(sectionId, sources)
    if (section.missingReason !== undefined) {
      text(section.missingReason, 500)
      if (section.missingConfirmed !== true) throw new Error('请确认缺失分区后再保存')
    } else {
      if (!sources.size) throw new Error('空分区缺少明确的缺失说明')
      if (section.missingConfirmed === true) throw new Error('完整分区不能标为缺失')
    }
    if (section.missingConfirmed !== undefined && typeof section.missingConfirmed !== 'boolean') throw new Error('缺失确认格式不合法')
  }
  if (extraction.parameterRows !== undefined) {
    if (!Array.isArray(extraction.parameterRows) || extraction.parameterRows.length > 1000) throw new Error('参数行数量不合法')
    for (const value of extraction.parameterRows) {
      const row = record(value, ['sectionId', 'sourceId', 'name', 'value', 'unit', 'rawText', 'unknownReason', 'excluded'])
      if (row.sectionId !== 'params' || !sourceIds.get('params')?.has(sourceId(row.sourceId))) throw new Error('参数行来源不属于本次参数分区')
      const name = text(row.name, 200, true); const content = text(row.value, 2000, true)
      if (row.unit !== undefined) text(row.unit, 80, true)
      if (row.rawText !== undefined) text(row.rawText, 4000, true)
      const unknown = row.unknownReason === undefined ? '' : text(row.unknownReason, 500, true)
      if ((!name.trim() || !content.trim()) && !unknown.trim()) throw new Error('未识别参数需要说明原因')
      if (row.excluded !== undefined && typeof row.excluded !== 'boolean') throw new Error('参数排除状态不合法')
    }
  }
  if (extraction.ranges !== undefined) {
    if (!Array.isArray(extraction.ranges) || extraction.ranges.length > 100) throw new Error('自选范围数量超限')
    for (const value of extraction.ranges) {
      const range = record(value, ['sectionId', 'sourceId', 'name', 'mode'])
      const sectionId = text(range.sectionId, 20)
      if (!selected.has(sectionId)) throw new Error('自选范围不属于本次所选分区')
      sourceId(range.sourceId); text(range.name, 80)
      if (!['include', 'exclude'].includes(String(range.mode))) throw new Error('自选范围操作不合法')
    }
  }
}
