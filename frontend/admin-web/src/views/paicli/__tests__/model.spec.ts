import { describe, expect, it } from 'vitest'
import { approvalReason, operationKeys, safeText, validateDag } from '../model'
import type { PcApproval, PcPlanStep } from '@/types/paicli'
const step = (id: string, dependencies: string[] = []): PcPlanStep => ({ id, dependencies, description:`任务 ${id}`,status:'PENDING',attempts:0 })
const approval = (values: Partial<PcApproval> = {}): PcApproval => ({ id:'1',directoryContextId:'1',taskId:'1',runnerId:'1',version:3,status:'PENDING',hardDenied:false,toolName:'execute_command',arguments:{ command:'echo hello' },inputHash:'fixed',rootFingerprint:'root',paths:[],impact:'构建目录',requesterName:'测试用户',createdAt:'2026-01-01T00:00:00Z',expiresAt:'2026-01-02T00:00:00Z',policyVersion:1,...values })
describe('计划与批准输入边界', () => {
  it('有效 DAG 保留独立分支，循环与未知依赖拒绝执行', () => { expect(validateDag([step('A'),step('B',['A']),step('C')])).toEqual([]); expect(validateDag([step('A',['B']),step('B',['A'])]).join('')).toContain('依赖循环'); expect(validateDag([step('A',['UNKNOWN'])]).join('')).toContain('未知依赖') })
  it('重复编号与缺描述不能变成有效计划', () => { expect(validateDag([step('A'),step('A')]).join('')).toContain('重复'); expect(validateDag([{ ...step('A'),description:'' }]).join('')).toContain('缺少描述') })
  it('硬拒绝、过期、已决定均不能重复批准', () => { const now = Date.parse('2026-01-01T00:00:00Z'); expect(approvalReason(approval({ hardDenied:true }),now)).toContain('不能解除'); expect(approvalReason(approval(),Date.parse('2026-01-03T00:00:00Z'))).toContain('有效期'); expect(approvalReason(approval({ status:'APPROVED' }),now)).toContain('不能再次批准'); expect(approvalReason(approval(),now)).toBe('') })
  it('终端控制字符转义显示，数据不成为终端控制指令', () => { expect(safeText('\u001b[2J\u0000hello')).toBe('\\u001b[2J\\u0000hello'); expect(safeText({ command:'echo hello' })).toContain('echo hello') })
  it('不确定响应再次提交保留原幂等键，成功后新操作取新键', () => { const keys = operationKeys(), intent = { id:'42',version:1,command:'echo safe' }; const first = keys.get(intent); expect(keys.get({ ...intent })).toBe(first); expect(keys.get({ ...intent,version:2 })).not.toBe(first); keys.done(intent); expect(keys.get(intent)).not.toBe(first) })
})
