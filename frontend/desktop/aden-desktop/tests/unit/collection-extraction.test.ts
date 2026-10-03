import { describe, expect, it } from 'vitest'
import { validateCaptureExtraction } from '../../src/main/collector-bridge/extraction'

function fixture() {
  return { protocolVersion: 1, documentId: 'document-one', pageFingerprint: 'a'.repeat(64), selectionRevision: '9007199254740993', confirmationRevision: '9007199254740994', selectedSections: ['params', 'main'],
    sections: ['params', 'main'].map(sectionId => ({ sectionId, documentId: 'document-one', pageFingerprint: 'a'.repeat(64), selectionRevision: '9007199254740993', rangeRevision: '2', extractedRangeRevision: '2', resultRevision: '1', sourceIds: [sectionId + '-source'] })),
    parameterRows: [{ sectionId: 'params', sourceId: 'params-source', name: '净重', value: '500', unit: 'g', rawText: '净重：500g' }],
    ranges: [{ sectionId: 'params', sourceId: 'params-source', name: '规格参数表', mode: 'include' }] }
}
describe('current section extraction boundary', () => {
  it('preserves valid ordered provenance with revision precision above Number limits', () => {
    const value = fixture(); const original = structuredClone(value)
    expect(() => validateCaptureExtraction(value)).not.toThrow()
    expect(value).toEqual(original)
  })
  it('rejects stale ranges, mixed pages, unselected sections and duplicate sources', () => {
    const range = fixture(); range.sections[0]!.extractedRangeRevision = '1'
    expect(() => validateCaptureExtraction(range)).toThrow('范围已变化')
    const page = fixture(); page.sections[0]!.documentId = 'another-document'
    expect(() => validateCaptureExtraction(page)).toThrow('旧页面')
    const selection = fixture(); selection.selectedSections = ['main']
    expect(() => validateCaptureExtraction(selection)).toThrow('有效提取')
    const repeated = fixture(); repeated.sections[0]!.sourceIds.push('params-source')
    expect(() => validateCaptureExtraction(repeated)).toThrow('重复')
  })
  it('requires missing confirmation and parameter source ownership', () => {
    const missing = fixture(); Object.assign(missing.sections[1]!, { sourceIds: [], missingReason: '主图尚未加载' })
    expect(() => validateCaptureExtraction(missing)).toThrow('确认缺失')
    Object.assign(missing.sections[1]!, { missingConfirmed: true })
    expect(() => validateCaptureExtraction(missing)).not.toThrow()
    const source = fixture(); source.parameterRows[0]!.sourceId = 'foreign-source'
    expect(() => validateCaptureExtraction(source)).toThrow('不属于')
  })
  it('requires each missing section to be confirmed and rejects aggregate confirmation', () => {
    const value = fixture(); value.parameterRows = []; value.ranges = []
    for (const section of value.sections) Object.assign(section, { sourceIds: [], missingReason: '页面尚未加载' })
    Object.assign(value.sections[0]!, { missingConfirmed: true })
    expect(() => validateCaptureExtraction(value)).toThrow('确认缺失')
    expect(() => validateCaptureExtraction({ ...value, missingConfirmed: true })).toThrow('未知字段')
    Object.assign(value.sections[1]!, { missingConfirmed: true })
    expect(() => validateCaptureExtraction(value)).not.toThrow()
  })
  it('enforces backend int64 revisions and positive confirmation without Number conversion', () => {
    const max = fixture(); max.selectionRevision = '9223372036854775807'
    max.sections.forEach(section => { section.selectionRevision = max.selectionRevision; section.resultRevision = '9223372036854775807' })
    max.confirmationRevision = '9223372036854775807'
    expect(() => validateCaptureExtraction(max)).not.toThrow()
    for (const invalid of ['0', '9223372036854775808', '9999999999999999999999999999999999999999', '01', '1e3']) {
      expect(() => validateCaptureExtraction({ ...fixture(), confirmationRevision: invalid })).toThrow('版本不合法')
    }
    const overflowingRange = fixture(); overflowingRange.sections[0]!.rangeRevision = overflowingRange.sections[0]!.extractedRangeRevision = '9223372036854775808'
    expect(() => validateCaptureExtraction(overflowingRange)).toThrow('版本不合法')
  })
  it('rejects overlong source identifiers and more than 1000 parameter rows', () => {
    const source = fixture(); source.sections[1]!.sourceIds = ['a'.repeat(128)]
    expect(() => validateCaptureExtraction(source)).not.toThrow()
    source.sections[1]!.sourceIds = ['a'.repeat(129)]
    expect(() => validateCaptureExtraction(source)).toThrow('文字不合法')
    const rows = fixture(); rows.parameterRows = Array.from({ length: 1000 }, () => ({ ...rows.parameterRows[0]! }))
    expect(() => validateCaptureExtraction(rows)).not.toThrow()
    rows.parameterRows.push({ ...rows.parameterRows[0]! })
    expect(() => validateCaptureExtraction(rows)).toThrow('参数行数量')
    const complete = fixture(); Object.assign(complete.sections[0]!, { missingConfirmed: true })
    expect(() => validateCaptureExtraction(complete)).toThrow('完整分区')
  })
})
