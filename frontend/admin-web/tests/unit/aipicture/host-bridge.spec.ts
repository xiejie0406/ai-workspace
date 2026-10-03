import { describe, expect, it, vi, beforeEach } from 'vitest'
import { resolveAiPictureRoute } from '@/api/aipicture/routes'

const sharedRequest = vi.hoisted(() => vi.fn())
const sharedPermission = vi.hoisted(() => vi.fn())
vi.mock('@/utils/request', () => ({ default: sharedRequest }))
vi.mock('@/plugins/auth', () => ({ default: { hasPermi: sharedPermission } }))
vi.mock('element-plus', () => ({ ElMessage: vi.fn() }))
vi.mock('file-saver', () => ({ saveAs: vi.fn() }))
import { createAiPictureHost } from '@/api/aipicture/bridge'

describe('若依图片业务桥', () => {
  const router = { push: vi.fn(), replace: vi.fn() }
  const user = { id: '18', nickName: '测试用户', name: 'test', token: '不得传入React' }
  beforeEach(() => vi.clearAllMocks())

  it('使用共享请求且不传平台认证凭据', async () => {
    sharedRequest.mockResolvedValue({ code: 200, data: { id: 'run' } })
    const host = createAiPictureHost({ user, router, page: 'create' })
    const response = await host.request({ url: '/aipicture/generations', method: 'POST', data: { prompt: '商品' } })
    expect(response).toEqual({ code: 200, data: { id: 'run' } })
    expect(sharedRequest).toHaveBeenCalledWith(expect.objectContaining({ method: 'post', preserveBusinessError: true }))
    expect(host.identity).toEqual({ userId: '18', displayName: '测试用户' })
    expect(JSON.stringify(host)).not.toContain(user.token)
  })

  it('不允许业务桥请求平台账号或Python接口', async () => {
    const host = createAiPictureHost({ user, router, page: 'editor' })
    for (const url of ['/login', 'http://localhost:7301/compute', '/aipicture-other/runs']) {
      await expect(host.request({ url, method: 'GET' })).rejects.toThrow('超出授权')
    }
    expect(sharedRequest).not.toHaveBeenCalled()
  })

  it('保留取消信号与原生blob，经若依Router打开隐藏详情', async () => {
    const controller = new AbortController()
    const blob = new Blob(['image'])
    sharedRequest.mockResolvedValue(blob)
    const host = createAiPictureHost({ user, router, page: 'editor' })
    expect(await host.request({ url: '/aipicture/assets/id/download', method: 'GET', responseType: 'blob', signal: controller.signal })).toBe(blob)
    expect(sharedRequest.mock.calls[0][0].signal).toBe(controller.signal)
    host.navigate({ page: 'candidates', params: { runId: 'x/y' } }, { replace: true })
    expect(router.replace).toHaveBeenCalledWith({ path: '/aipicture-detail/candidates/x%2Fy' })
  })

  it('不同页导航保留会话关联，缺少详情参数明确拒绝', () => {
    expect(resolveAiPictureRoute({ page: 'marketing', params: { sessionId: 's1' } })).toEqual({ path: '/aipicture/marketing', query: { sessionId: 's1' } })
    expect(resolveAiPictureRoute({ page: 'batch', params: { runId: 'b1' } }).path).toBe('/aipicture-detail/batch/b1')
    expect(() => resolveAiPictureRoute({ page: 'exports' })).toThrow('缺少会话')
  })

  it('文字字体沿现有Vite配置传入，受控覆盖优先且不创建业务请求', () => {
    vi.stubEnv('VITE_AIPICTURE_TEXT_FONT_FAMILY', '  "Microsoft YaHei", sans-serif  ')
    try {
      expect(createAiPictureHost({ user, router, page: 'editor' }).textFontFamily).toBe('"Microsoft YaHei", sans-serif')
      expect(createAiPictureHost({ user, router, page: 'editor', textFontFamily: '  "PingFang SC"  ' }).textFontFamily).toBe('"PingFang SC"')
      expect(createAiPictureHost({ user, router, page: 'editor', textFontFamily: '   ' }).textFontFamily).toBeUndefined()
      expect(sharedRequest).not.toHaveBeenCalled()
    } finally { vi.unstubAllEnvs() }
  })

  it('登录状态沿若依token映射，退出保留id也不把旧身份交给React', () => {
    const loggedOut = { ...user, token: '', roles: [], permissions: [] }
    const host = createAiPictureHost({ user: loggedOut, router, page: 'create' })
    expect(loggedOut.id).toBe('18')
    expect(host.identity.userId).toBe(0)
    expect(host.active).toBe(false)
    const refreshed = createAiPictureHost({ user: { ...user, token: 'new-technical-token' }, router, page: 'create' })
    expect(refreshed.identity.userId).toBe('18')
    expect(refreshed.active).toBe(true)
    expect(JSON.stringify(refreshed)).not.toContain('new-technical-token')
  })
})
