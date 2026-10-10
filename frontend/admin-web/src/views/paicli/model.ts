import type { PcApproval, PcPlan, PcPlanStep } from '@/types/paicli'
export const stateNames: Record<string, string> = { PERMITTED: '已许可', DENIED: '策略拒绝', CONSUMED: '许可已消费', ACCEPTED: '已接受', LATE_ARCHIVED: '迟到回执已归档', AWAITING_REVIEW: '等待审阅', HISTORICAL: '历史归档', PREFLIGHT: '预检完成', COMMITTED: '已导入', IMPORTED_UNVERIFIED: '已归档待核实', PAUSED_IMPORTED: '历史排队已暂停', DISABLED_IMPORTED: '历史配置待核对', REVOKED_IMPORT: '导入已撤销', MISSING: '未建立', READY: '可用', CONFIGURED: '已配置', BOUND: '已绑定', LOGIN_REQUIRED: '待登录', CONNECTED: '已连接', STOPPED: '已停止', ERROR: '错误', REVISED: '按意见重提', DISABLED: '已停用', ACTIVE: '可继续', ENQUEUED: '排队', LEASED: '已租用', RUNNING: '执行中', WAITING_APPROVAL: '等待批准', CANCELLING: '取消中', CANCELLED: '已取消', FAILED: '失败', COMPLETED: '完成', RECONCILE_REQUIRED: '租约失效待核对', ONLINE: '在线', OFFLINE: '离线', REVOKED: '已撤销', PENDING: '待核实', VERIFIED: '已核实', CONFLICT: '冲突待处理', STALE: '超过核实期', APPROVED: '已批准', REJECTED: '已拒绝', EXPIRED: '已过期', HARD_DENIED: '策略硬拒绝', DRAFT: '待审阅', SKIPPED: '已跳过', REVIEW_FAILED: '审查失败', REVIEW_REJECTED: '审查未通过', PASSED: '已通过', PAUSED: '已暂停', UNAVAILABLE: '不可用', DISCONNECTED: '未连接' }
export const isHistoricalSession = (session?: { status?: string }) => !!session && ['HISTORICAL', 'FROZEN'].includes(session.status || '')
export const stateName = (state?: string) => state === 'WAITING_DIRECTORY' ? '正在准备文件位置' : state === 'FROZEN' ? '只读历史' : state ? stateNames[state] ?? state : '未提供'
export const memorySourceName = (source?: string) => source ? ({ USER:'用户明确记录',AUTOMATIC:'自动候选',AUTO_USER_QUOTE:'自动引用用户原话',APPROVED_TOOL:'已批准工具记录',LEGACY_MEMORY:'历史记忆资料' } as Record<string,string>)[source] ?? source : '未提供'
export const protocolName = (protocol?: string) => protocol ? ({ OPENAI_COMPATIBLE:'兼容 OpenAI',NATIVE:'原生协议' } as Record<string,string>)[protocol] ?? protocol : '未提供'
export const modeName = (mode: string) => ({ REACT: '直接执行', PLAN: '计划执行', TEAM: '团队协作', CONTROL: '配置操作' }[mode] ?? mode)
export const terminalTaskStates = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
export const canCancel = (status: string) => !terminalTaskStates.has(status) && !['CANCELLING', 'RECONCILE_REQUIRED', 'PAUSED_IMPORTED'].includes(status)
export const displayTime = (value?: string) => value && Number.isFinite(Date.parse(value)) ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium', timeZone: 'Asia/Shanghai' }).format(new Date(value)) : '未提供'
export function safeText(value: unknown): string {
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2) ?? ''
  return text.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, char => `\\u${char.charCodeAt(0).toString(16).padStart(4, '0')}`)
}
export function errorText(error: unknown): string {
  if (typeof error === 'string') return error
  if (error && typeof error === 'object') {
    const e = error as { message?: string; response?: { data?: { msg?: string; message?: string } } }
    return e.response?.data?.msg ?? e.response?.data?.message ?? e.message ?? '操作失败，请刷新状态后核对再试。'
  }
  return '操作失败，请刷新状态后核对再试。'
}
export function validateDag(steps: PcPlanStep[]): string[] {
  const errors: string[] = [], byId = new Map<string, PcPlanStep>()
  for (const step of steps) {
    if (typeof step.id !== 'string' || !step.id.trim() || byId.has(step.id)) errors.push(`任务编号为空或重复：${step.id}`)
    if (typeof step.description !== 'string' || !step.description.trim()) errors.push(`任务 ${step.id} 缺少描述`)
    byId.set(step.id, step)
  }
  const visiting = new Set<string>(), visited = new Set<string>()
  function visit(id: string) {
    if (visiting.has(id)) { errors.push(`依赖循环包含：${id}`); return }
    if (visited.has(id)) return
    visiting.add(id)
    const dependencies = byId.get(id)?.dependencies
    if (!Array.isArray(dependencies)) { errors.push(`任务 ${id} 的依赖必须是数组`); visiting.delete(id); visited.add(id); return }
    for (const dep of dependencies) {
      if (!byId.has(dep)) errors.push(`任务 ${id} 引用未知依赖：${dep}`)
      else visit(dep)
    }
    visiting.delete(id); visited.add(id)
  }
  for (const id of byId.keys()) visit(id)
  return [...new Set(errors)]
}
export const planErrors = (plan: PcPlan) => [...new Set([...plan.validationErrors, ...validateDag(plan.steps)])]
export function approvalReason(approval: PcApproval, now = Date.now()): string {
  if (approval.hardDenied) return approval.denialReason ?? '确定性策略拒绝，人工批准不能解除。'
  if (!Number.isFinite(Date.parse(approval.expiresAt)) || new Date(approval.expiresAt).getTime() <= now) return '批准有效期已过或缺失，需重新读取影响并申请。'
  if (!['PENDING', 'WAITING_APPROVAL'].includes(approval.status)) return `当前申请${stateName(approval.status)}，不能再次批准。`
  return ''
}
/** 请求失败时，相同业务意图继续使用原幂等键；只保存在本页内存。 */
export function operationKeys() {
  const pending = new Map<string, string>()
  return { get(intent: unknown) { const encoded = JSON.stringify(intent); let key = pending.get(encoded); if (!key) { key = crypto.randomUUID(); if (pending.size > 100) pending.clear(); pending.set(encoded, key) }; return key }, done(intent: unknown) { pending.delete(JSON.stringify(intent)) } }
}
export async function saveDownload(blob: Blob, fileName: string) {
  if (blob.type.includes('json')) {
    const response = JSON.parse(await blob.text()) as { code?: number; msg?: string }
    if (response.code && response.code !== 200) throw new Error(response.msg ?? '下载被拒绝')
  }
  const url = URL.createObjectURL(blob), link = document.createElement('a')
  link.href = url; link.download = fileName; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
