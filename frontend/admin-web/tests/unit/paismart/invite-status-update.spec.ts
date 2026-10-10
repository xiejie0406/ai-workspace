import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Invites from '@/views/paismart/operations/invites/index.vue'

const mocks = vi.hoisted(() => ({ listInvites: vi.fn(), saveInvite: vi.fn(), deleteInvite: vi.fn(), batchInvites: vi.fn(), listSpaces: vi.fn(), confirm: vi.fn() }))
vi.mock('@/api/paismart/operations', () => mocks)
vi.mock('@/api/paismart/spaces', () => mocks)
vi.mock('@/api/paismart/client', () => ({ newKey: () => 'fixture-key' }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() }, ElMessageBox: { confirm: mocks.confirm, alert: vi.fn() } }))
const slot = { template: '<div><slot/><slot name="footer"/></div>' }
const global = { stubs: { ...Object.fromEntries(['el-alert','el-button','el-form','el-form-item','el-select','el-option','el-table','el-pagination','el-dialog','el-input','el-input-number','el-date-picker','el-switch'].map(name => [name, slot])), 'el-table-column': true }, directives: { loading: () => {}, hasPermi: () => {} } }
const expired = { id: 'expired-invite', code: 'EXPIRED_2025', expiresAt: '2025-01-01T00:00:00Z', maxUses: 3, usedCount: 1, enabled: true, initialLlmTokens: 100, initialEmbeddingTokens: 50, rowVersion: 4 }
beforeEach(() => { vi.clearAllMocks(); mocks.listInvites.mockResolvedValue({ rows: [], total: 0 }); mocks.listSpaces.mockResolvedValue({ rows: [], total: 0 }); mocks.saveInvite.mockResolvedValue({ data: {} }); mocks.confirm.mockResolvedValue('confirm') })
describe('邀请码状态更新保留原有效期和权益', () => {
  it('停用已过期邀请码只提交状态与版本，不重新提交过去有效期', async () => {
    const wrapper = mount(Invites, { global }); await flushPromises(); await (wrapper.vm as any).toggle(expired); await flushPromises()
    expect(mocks.saveInvite).toHaveBeenCalledWith({ id: 'expired-invite', rowVersion: 4, enabled: false }); expect(mocks.deleteInvite).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('批量停用对每个邀请码只改状态，不改变原日期、次数、空间或初始额度', async () => {
    const wrapper = mount(Invites, { global }); await flushPromises(); const vm = wrapper.vm as any
    vm.selected = [expired, { ...expired, id: 'future-invite', expiresAt: '2099-01-01T00:00:00Z', rowVersion: 8 }]; await vm.bulk(false); await flushPromises()
    expect(mocks.saveInvite.mock.calls.map(call => call[0])).toEqual([{ id: 'expired-invite', rowVersion: 4, enabled: false }, { id: 'future-invite', rowVersion: 8, enabled: false }]); expect(mocks.deleteInvite).not.toHaveBeenCalled(); expect(vm.selected).toEqual([]); wrapper.unmount()
  })
})
