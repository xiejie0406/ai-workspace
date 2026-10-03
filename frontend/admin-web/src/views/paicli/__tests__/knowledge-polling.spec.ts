import { describe, expect, it, vi } from 'vitest'
import { awaitKnowledge } from '../knowledgePolling'
const pending = { code:200,data:{ status:'PENDING' as const,taskId:'42',sessionId:'9' } }
describe('真实检索任务有限等待', () => {
  it('排队回执不能变成空结果，最终返回中心片段', async () => { const fetch = vi.fn().mockResolvedValueOnce(pending).mockResolvedValueOnce({ code:200,data:[{ file:'src/真实.java' }] }), receive = vi.fn(); const result = await awaitKnowledge(fetch,new AbortController().signal,receive,3,0); expect(result).toEqual([{ file:'src/真实.java' }]); expect(receive).toHaveBeenCalledWith(pending.data); expect(fetch).toHaveBeenCalledTimes(2) })
  it('超过等待次数保留真实任务编号，不再自动请求', async () => { const fetch = vi.fn().mockResolvedValue(pending), receive = vi.fn(); expect(await awaitKnowledge(fetch,new AbortController().signal,receive,3,0)).toBeUndefined(); expect(fetch).toHaveBeenCalledTimes(3); expect(receive.mock.calls[2]![0].taskId).toBe('42') })
  it('取消等待停止续取，页面不发送任务取消指令', async () => { const controller = new AbortController(), fetch = vi.fn().mockResolvedValue(pending); await expect(awaitKnowledge(fetch,controller.signal,() => controller.abort(),3,0)).rejects.toBeDefined(); expect(fetch).toHaveBeenCalledTimes(1) })
})
