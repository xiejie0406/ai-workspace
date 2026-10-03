import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus, { ElForm, ElOption, ElSelect } from 'element-plus'
// @ts-expect-error 复用现行 JavaScript 权限指令，该模块没有类型声明。
import hasPermi from '@/directive/permission/hasPermi'
import PlanLauncher from '@/views/aps/workbench/PlanLauncher.vue'
import { createPlanRequest, getPlanRequestStatus, listPlanVersions } from '@/api/aps/planning'
import { getResourceDirectory } from '@/api/aps/resource'
import { getSiteContext } from '@/api/aps/context'
import { listOrders } from '@/api/aps/manufacturing'

const permissionState = vi.hoisted(() => ({ permissions: [] as string[] }))
vi.mock('@/store/modules/user', () => ({ default: () => permissionState }))

vi.mock('@/api/aps/planning', () => ({ listPlanVersions: vi.fn(), createPlanRequest: vi.fn(), getPlanRequestStatus: vi.fn(), cancelPlanRequest: vi.fn(),checkPlanReadiness:vi.fn() }))
vi.mock('@/api/aps/resource', () => ({ getResourceDirectory: vi.fn() }))
vi.mock('@/api/aps/context',()=>({getSiteContext:vi.fn()}))
vi.mock('@/api/aps/manufacturing', () => ({ listOrders: vi.fn() }))

const candidate = { planVersionId: 'plan-1', versionName: '候选一', versionNo: 1, status: 'FEASIBLE', requestId: 'request-1', baseVersionId: null, rowVersion: 2, updatedAt: '2026-10-01T00:00:00Z' }
const solving = { requestId: 'request-1', planVersionId: 'plan-1', planStatus: 'SOLVING', solverStatus: null, resultKind: null, reasonCodes: [] }
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-01T01:00:00Z')); vi.clearAllMocks()
  vi.mocked(getSiteContext).mockResolvedValue({siteCode:'LOCAL-DEV',zoneId:'Asia/Shanghai'});vi.mocked(getResourceDirectory).mockResolvedValue({centers:[],resources:[],workshops:[{ id: 'ws-1', status: 'ACTIVE', code: 'W1', name: '车间一' }, { id: 'ws-2', status: 'INACTIVE', code: 'W2', name: '停用车间' }]} as never)
  vi.mocked(listOrders).mockResolvedValue([{ id: 'order-1', orderNo: 'SO1', status: 'RELEASED', lines: [{ demandQty: 20 }] }, { id: 'order-2', orderNo: 'SO2', status: 'DRAFT', lines: [{ demandQty: 10 }] }] as never)
  vi.mocked(listPlanVersions).mockResolvedValue([candidate])
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.useRealTimers() })
async function open() {
  wrapper = mount(PlanLauncher, { global: { plugins: [ElementPlus] } }); await flushPromises(); return wrapper
}
describe('排程名称入口与求解生命周期', () => {
  it('普通角色持有登记的发起求解权限才显示排程按钮', async () => {
    permissionState.permissions = ['aps:planning:solve', 'aps:planning:view', 'aps:order:list']
    wrapper = mount(PlanLauncher, { attachTo: document.body, global: { plugins: [ElementPlus], directives: { hasPermi } } })
    await flushPromises()
    expect(wrapper.findAll('button').some(button => button.text() === '发起排程')).toBe(true)
    wrapper.unmount(); wrapper = undefined
    permissionState.permissions = ['aps:planning:view', 'aps:order:list']
    wrapper = mount(PlanLauncher, { attachTo: document.body, global: { plugins: [ElementPlus], directives: { hasPermi } } })
    await flushPromises()
    expect(wrapper.findAll('button').some(button => button.text() === '发起排程')).toBe(false)
  })
  it('只列可用车间与已释放订单，并在发布后同步名称和状态', async () => {
    const w = await open()
    const labels = () => w.findAllComponents(ElOption).map(option => option.props('label'))
    expect(labels()).toContain('W1 · 车间一'); expect(labels()).not.toContain('W2 · 停用车间')
    expect(labels()).toContain('SO1 · 1个产品行'); expect(labels()).not.toContain('SO2 · 1个产品行')
    await w.setProps({ activePlan: { planVersionId: 'plan-1', status: 'PUBLISHED' } })
    expect(labels()).toContain('候选一 · 版本1 · 当前正式计划')
  })
  it('一次提交保存业务选择，等待中禁止重复，终态打开计划，卸载后停止轮询', async () => {
    vi.mocked(createPlanRequest).mockResolvedValue(solving)
    vi.mocked(getPlanRequestStatus).mockResolvedValue({ ...solving, planStatus: 'FEASIBLE' })
    const w = await open(); const selects = w.findAllComponents(ElSelect)
    selects[0]!.vm.$emit('update:modelValue', ['ws-1']); selects[1]!.vm.$emit('update:modelValue', ['order-1']); await w.vm.$nextTick()
    await w.findAllComponents(ElForm)[0]!.trigger('submit'); await flushPromises()
    await w.findAllComponents(ElForm)[0]!.trigger('submit'); await flushPromises()
    expect(createPlanRequest).toHaveBeenCalledTimes(1)
    expect(createPlanRequest).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({ workshopIds: ['ws-1'], orderIds: ['order-1'], siteCode: 'LOCAL-DEV', horizon: expect.objectContaining({ timeUnitSeconds: 60 }) }))
    await vi.advanceTimersByTimeAsync(1500); await flushPromises()
    expect(w.emitted('open')).toEqual([['plan-1']])
    const count = vi.mocked(getPlanRequestStatus).mock.calls.length; w.unmount(); wrapper=undefined
    await vi.advanceTimersByTimeAsync(5000); expect(getPlanRequestStatus).toHaveBeenCalledTimes(count)
  })
})
