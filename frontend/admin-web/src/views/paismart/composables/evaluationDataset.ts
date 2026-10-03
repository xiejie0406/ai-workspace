export function validateDataset(value: string): number {
  if(new Blob([value]).size>900000)throw new Error('评测集 UTF-8 内容不能超过 900000 字节')
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
export function validateEvaluationSeed(value: number): number {if(!Number.isSafeInteger(value))throw new Error('随机种子必须为安全整数（−9007199254740991 至 9007199254740991）');return value}
