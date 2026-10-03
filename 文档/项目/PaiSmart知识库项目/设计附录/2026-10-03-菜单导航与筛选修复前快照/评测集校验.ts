export function validateDataset(value: string): number {
  const lines = value.replace(/^\uFEFF/,'').split(/\r?\n/).filter(line => line.trim()&&!/^(#|\/\/)/.test(line.trim()))
  if (lines.length < 1 || lines.length > 500) throw new Error('评测集应包含 1—500 个 JSONL 样本')
  for (const [index, line] of lines.entries()) {
    let row: Record<string, unknown>
    try { row = JSON.parse(line) } catch { throw new Error(`第 ${index + 1} 行不是有效 JSON`) }
    if (!row || typeof row !== 'object' || typeof (row.question ?? row.query) !== 'string' || !String(row.question ?? row.query).trim()) throw new Error(`第 ${index + 1} 行缺少 question 或 query`)
    if (typeof row.referenceAnswer !== 'string' || !row.referenceAnswer.trim()) throw new Error(`第 ${index + 1} 行缺少独立参考答案 referenceAnswer`)
  }
  return lines.length
}
