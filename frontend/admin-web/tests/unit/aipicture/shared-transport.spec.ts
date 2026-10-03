import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders, CanceledError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { Blob as NodeBlob } from 'node:buffer'

const platform = vi.hoisted(() => ({
  session: {} as Record<string, unknown>,
  getJSON: vi.fn(), setJSON: vi.fn(), confirm: vi.fn(), notify: vi.fn(), message: vi.fn()
}))
vi.mock('@/utils/auth', () => ({ getToken: () => 'technical-platform-token' }))
vi.mock('@/plugins/auth', () => ({ default: { hasPermi: () => true } }))
vi.mock('@/plugins/cache', () => ({ default: { session: { getJSON: platform.getJSON, setJSON: platform.setJSON } } }))
vi.mock('@/store/modules/user', () => ({ default: () => ({ logOut: vi.fn().mockResolvedValue(undefined) }) }))
vi.mock('element-plus', () => ({ ElNotification: { error: platform.notify }, ElMessageBox: { confirm: platform.confirm }, ElMessage: platform.message, ElLoading: { service: vi.fn() } }))
vi.mock('file-saver', () => ({ saveAs: vi.fn() }))
import request, { isRelogin } from '@/utils/request'
import { createAiPictureHost } from '@/api/aipicture/bridge'

describe('图片宿主与真实共享 Axios 传输', () => {
  const user = { id: '18', name: '技术测试' }
  const router = { push: vi.fn(), replace: vi.fn() }
  beforeEach(() => {
    vi.clearAllMocks()
    platform.session = {}
    platform.getJSON.mockImplementation(key => platform.session[key])
    platform.setJSON.mockImplementation((key, data) => { platform.session[key] = data })
    platform.confirm.mockRejectedValue(new Error('技术检查取消重新登录'))
    isRelogin.show = false
    vi.spyOn(console, 'log').mockImplementation(() => {})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  function respond(data: unknown, type: string, status = 200) {
    request.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      const response = { data, status, statusText: '', headers: new AxiosHeaders({ 'content-type': type }), config }
      if (status >= 400) throw new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_REQUEST', config, undefined, response)
      return response
    }
  }
  function binaryRequest(url: string) {
    const config = { url, responseType: 'blob', preserveBusinessError: true } satisfies AxiosRequestConfig & { preserveBusinessError: boolean }
    return request(config)
  }

  it('真实 transform 保留连续不同文件的 FormData，不写 JSON 防重缓存', async () => {
    const received: InternalAxiosRequestConfig[] = []
    request.defaults.adapter = async config => {
      received.push(config)
      return { data: { code: 200, data: { id: String(received.length) } }, status: 200, statusText: '', headers: new AxiosHeaders(), config }
    }
    const host = createAiPictureHost({ user, router, page: 'create' })
    for (const name of ['第一张.png', '第二张.png']) {
      const form = new FormData()
      form.append('file', new File([name], name, { type: 'image/png' }))
      await host.request({ url: '/aipicture/assets', method: 'POST', data: form })
    }
    expect(received).toHaveLength(2)
    expect(received.every(config => config.data instanceof FormData)).toBe(true)
    expect(received.map(config => config.data.get('file').name)).toEqual(['第一张.png', '第二张.png'])
    expect(received.every(config => config.headers.get('repeatSubmit') === false)).toBe(true)
    expect(received[0].headers.get('Authorization')).toBe('Bearer technical-platform-token')
    expect(platform.setJSON).not.toHaveBeenCalled()
  })

  it('普通 JSON 写请求继续使用共享 JSON 转换与防重复机制', async () => {
    respond({ code: 200 }, 'application/json')
    const host = createAiPictureHost({ user, router, page: 'create' })
    const options = { url: '/aipicture/generations', method: 'POST', data: { prompt: '测试' } }
    await host.request(options)
    await expect(host.request(options)).rejects.toThrow('请勿重复提交')
    expect(platform.setJSON).toHaveBeenCalledTimes(1)
  })

  it('HTTP 200 的会话过期 JSON blob 走平台重新登录和结构化错误', async () => {
    respond(new NodeBlob([JSON.stringify({ code: 401, msg: '登录已过期', errorCode: 'AUTH_EXPIRED' })], { type: 'application/json' }), 'application/json')
    const host = createAiPictureHost({ user, router, page: 'editor' })
    await expect(host.request({ url: '/aipicture/assets/id/download', method: 'GET', responseType: 'blob' })).rejects.toMatchObject({ response: { data: { code: 401, errorCode: 'AUTH_EXPIRED' } } })
    expect(platform.confirm).toHaveBeenCalledTimes(1)
  })

  it('HTTP 200 的文件冲突 JSON 保留业务字段，不能当文件保存', async () => {
    respond(new NodeBlob([JSON.stringify({ code: 409, msg: '版本冲突', errorCode: 'VERSION_CONFLICT', currentVersion: 8, retryable: true })]), 'application/json;charset=UTF-8')
    await expect(binaryRequest('/aipicture/exports/id/download')).rejects.toMatchObject({ response: { data: { errorCode: 'VERSION_CONFLICT', currentVersion: 8, retryable: true } } })
  })

  it.each([401, 404])('HTTP %s JSON blob 也保留响应并按实际认证状态处理', async status => {
    respond(new NodeBlob([JSON.stringify({ code: status, msg: '技术错误', errorCode: status === 401 ? 'AUTH_EXPIRED' : 'NOT_FOUND' })]), 'application/json', status)
    await expect(binaryRequest('/aipicture/assets/id/download')).rejects.toMatchObject({ response: { status, data: { code: status } } })
    expect(platform.confirm).toHaveBeenCalledTimes(status === 401 ? 1 : 0)
  })

  it.each(['image/png', 'application/zip'])('正常 %s 文件仍按原字节返回', async type => {
    const binary = new NodeBlob(['实际二进制传输样本'], { type })
    respond(binary, type)
    expect(await binaryRequest('/aipicture/assets/id/download')).toBe(binary)
    expect(platform.confirm).not.toHaveBeenCalled()
  })

  it('未声明业务错误的既有 blob 行为不改变', async () => {
    const binary = new NodeBlob(['{"code":401}'], { type: 'application/json' })
    respond(binary, 'application/json')
    expect(await request({ url: '/other/file', responseType: 'blob' })).toBe(binary)
    expect(platform.confirm).not.toHaveBeenCalled()
  })

  it('离开图片页主动中止在途共享请求时不弹连接故障', async () => {
    let entered!: () => void
    const inFlight = new Promise<void>(resolve => { entered = resolve })
    request.defaults.adapter = config => new Promise((_resolve, reject) => {
      config.signal?.addEventListener?.('abort', () => reject(new CanceledError('canceled', config)), { once: true })
      entered()
    })
    const controller = new AbortController()
    const host = createAiPictureHost({ user, router, page: 'editor' })
    const pending = host.request({ url: '/aipicture/assets/id/download', method: 'GET', responseType: 'blob', signal: controller.signal })
    await inFlight
    controller.abort()
    await expect(pending).rejects.toMatchObject({ code: 'ERR_CANCELED' })
    expect(platform.message).not.toHaveBeenCalled()
    expect(platform.confirm).not.toHaveBeenCalled()
  })
})
