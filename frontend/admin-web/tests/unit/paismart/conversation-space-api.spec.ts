import { beforeEach, describe, expect, it, vi } from 'vitest'
import { conversationAction, createConversation } from '@/api/paismart/conversations'

const request = vi.hoisted(() => vi.fn())
vi.mock('@/utils/request', () => ({ default: request }))
beforeEach(() => {
  vi.clearAllMocks()
  request.mockResolvedValue({ code: 200, data: { id: 'conversation', title: '新会话', space_id: 'allowed-space', row_version: 3, updated_at: '2026-10-05' } })
})
describe('会话范围复用平台 API 协议', () => {
  it('指定空间通过原创建接口提交，并归一化真实服务元数据', async () => {
    const response = await createConversation('新会话', 'allowed-space')
    expect(request.mock.calls[0]?.[0]).toMatchObject({ url: '/paismart/conversations', method: 'post', data: { title: '新会话', spaceId: 'allowed-space' } })
    expect(request.mock.calls[0]?.[0].headers).not.toHaveProperty('Authorization')
    expect(response.data).toMatchObject({ spaceId: 'allowed-space', rowVersion: 3, updatedAt: '2026-10-05' })
  })
  it('全部有权空间保留原调用兼容性，不传空空间', async () => {
    await createConversation('默认会话')
    expect(request.mock.calls[0]?.[0].data).toEqual({ title: '默认会话' })
  })
  it('恢复旧会话读取服务端固定范围，不能借 switch 修改 spaceId', async () => {
    const response = await conversationAction({ id: 'old/id', title: '旧会话', spaceId: 'client-stale', rowVersion: 2, archived: false }, 'switch')
    expect(request.mock.calls[0]?.[0]).toMatchObject({ url: '/paismart/conversations/old%2Fid/switch', method: 'put', data: { expectedVersion: 2 }, headers: { 'If-Match': '2' } })
    expect(request.mock.calls[0]?.[0].data).not.toHaveProperty('spaceId')
    expect(response.data.spaceId).toBe('allowed-space')
  })
})
