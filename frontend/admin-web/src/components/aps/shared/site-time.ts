/** 页面时间按站点时区解释，传输仍使用 UTC；禁止继承操作系统时区。 */
export function siteTime(value: string | undefined, zoneId: string): string {
  if (!value) return ''
  const parts = new Intl.DateTimeFormat('sv-SE', { timeZone: zoneId, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(value))
  const p = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`
}

export function siteTimeToUtc(value: string, zoneId: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value)
  if (!match) throw new Error('请选择完整的日期和时间')
  const target = `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]}:${match[6] ?? '00'}`
  const wall = Date.UTC(+match[1]!, +match[2]! - 1, +match[3]!, +match[4]!, +match[5]!, +(match[6] ?? 0))
  const offsets = new Set<number>()
  for (const shift of [-86400000, 0, 86400000]) {
    const probe = wall + shift
    const local = siteTime(new Date(probe).toISOString(), zoneId)
    offsets.add(Date.parse(local.replace(' ', 'T') + 'Z') - probe)
  }
  const candidates = [...offsets].map(offset => wall - offset).filter(utc => siteTime(new Date(utc).toISOString(), zoneId) === target)
  if (candidates.length !== 1) throw new Error(candidates.length ? '此时刻因夏令时重复，请选择明确的时刻' : '此日期或站点时区中的时刻不存在')
  return new Date(candidates[0]!).toISOString()
}

export function operationError(error: unknown): string {
  if (error && typeof error === 'object') {
    const body = (error as {response?:{data?:{detail?:unknown;message?:unknown;msg?:unknown}}}).response?.data
    const detail = body?.detail ?? body?.message ?? body?.msg
    if (typeof detail === 'string' && detail.trim()) return detail
  }
  return error instanceof Error ? error.message : typeof error === 'string' ? error : '操作未完成，请重试；输入已保留'
}
