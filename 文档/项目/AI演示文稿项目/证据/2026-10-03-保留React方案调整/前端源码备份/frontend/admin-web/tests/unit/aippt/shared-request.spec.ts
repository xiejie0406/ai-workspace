import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ReadableStream } from 'node:stream/web'
import { TextEncoder, TextDecoder } from 'node:util'

const mock = vi.hoisted(() => ({
  request: undefined as any, response: undefined as any, reject: undefined as any,
  message: vi.fn(), notification: vi.fn(), relogin: vi.fn(), logout: vi.fn(),
  service: { interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } } }
}))
vi.mock('axios', () => ({ default: {
  defaults: { headers: {} }, isCancel: (e: any) => e.code === 'ERR_CANCELED',
  create: () => {
    mock.service.interceptors.request.use.mockImplementation((f: any) => { mock.request = f })
    mock.service.interceptors.response.use.mockImplementation((f: any, r: any) => { mock.response = f; mock.reject = r })
    return mock.service
  }
} }))
vi.mock('element-plus', () => ({ ElNotification: { error: mock.notification }, ElMessage: mock.message, ElMessageBox: { confirm: mock.relogin }, ElLoading: {} }))
vi.mock('@/utils/auth', () => ({ getToken: () => 'platform-user-token' }))
vi.mock('@/utils/ruoyi', () => ({ tansParams: (p: any) => Object.entries(p).map(([k,v]) => `${k}=${v}&`).join(''), blobValidate: vi.fn() }))
vi.mock('@/plugins/cache', () => ({ default: { session: { getJSON: vi.fn(), setJSON: vi.fn() } } }))
vi.mock('file-saver', () => ({ saveAs: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ default: () => ({ logOut: mock.logout }) }))
import '@/utils/request'

function stream(value: unknown) {
  return new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new TextEncoder().encode(JSON.stringify(value))); controller.close() } })
}
beforeEach(() => {
  vi.stubGlobal('TextDecoder', TextDecoder)
  vi.stubGlobal('TextEncoder', TextEncoder)
  mock.message.mockClear(); mock.notification.mockClear(); mock.relogin.mockReset()
  mock.relogin.mockRejectedValue(new Error('stay on page'))
})
describe('共享request兼容进度流', () => {
  it('流请求沿用平台认证、参数和可取消信号', () => {
    const signal = new AbortController().signal
    const config = mock.request({ url: '/aippt/tasks/task-1/events', method: 'get', headers: {}, params: { afterSeq: 7 }, signal, aipptStream: true })
    expect(config).toMatchObject({ adapter: 'fetch', responseType: 'stream', timeout: 0, signal, preserveBusinessError: true })
    expect(config.headers).toMatchObject({ Authorization: 'Bearer platform-user-token', Accept: 'text/event-stream' })
    expect(config.url).toBe('/aippt/tasks/task-1/events?afterSeq=7')
  })
  it('不允许流选项绕过业务同源路径', () => {
    expect(() => mock.request({ url: 'https://other.example/events', method: 'get', headers: {}, aipptStream: true })).toThrow()
  })
  it('普通JSON和二进制保持原响应形状', async () => {
    const value = { code: 200, data: { title: '材料' } }
    expect(await mock.response({ config: {}, data: value, headers: {} })).toBe(value)
    const blob = new Blob(['test'])
    expect(await mock.response({ config: { responseType: 'blob' }, data: blob, headers: {} })).toBe(blob)
  })
  it('真实事件流保持Reader并不提前消费', async () => {
    const body = stream({ state: 'running' })
    expect(await mock.response({ config: { aipptStream: true }, headers: { 'content-type': 'text/event-stream;charset=UTF-8' }, data: body })).toBe(body)
    expect(body.locked).toBe(false)
  })
  it('私有文件的会话JSON不被当成可下载Blob', async () => {
    const blob = { size: 50, stream: () => stream({ code: 401, msg: '会话过期' }) }
    await expect(mock.response({ config: { responseType: 'blob', aipptBinary: true, preserveBusinessError: true }, headers: { 'content-type': 'application/json' }, status: 200, data: blob })).rejects.toMatchObject({ status: 401 })
    expect(mock.relogin).toHaveBeenCalledOnce()
  })
  it('私有文件的权限JSON保留403业务错误', async () => {
    const blob = { size: 50, stream: () => stream({ code: 403, msg: '无文件权限' }) }
    await expect(mock.response({ config: { responseType: 'blob', aipptBinary: true, preserveBusinessError: true }, headers: { 'content-type': 'application/json' }, status: 200, data: blob })).rejects.toMatchObject({ status: 403 })
    expect(mock.notification).toHaveBeenCalledOnce()
  })
  it('流连接的HTTP200会话错误复用平台登录提示', async () => {
    await expect(mock.response({ config: { aipptStream: true, preserveBusinessError: true }, headers: { 'content-type': 'application/json' }, status: 200, data: stream({ code: 401, msg: '会话过期' }) })).rejects.toMatchObject({ status: 401, response: { data: { code: 401 } } })
    expect(mock.relogin).toHaveBeenCalledOnce()
  })
  it('流HTTP409保留版本错误供编辑器恢复草稿', async () => {
    const err = Object.assign(new Error('Request failed with status code 409'), { config: { aipptStream: true }, response: { status: 409, data: stream({ code: 409, currentRevision: 8 }) } })
    await expect(mock.reject(err)).rejects.toBe(err)
    expect(err.response.data).toMatchObject({ code: 409, currentRevision: 8 })
  })
  it('普通HTTP409仍保留原错误对象', async () => {
    const err = Object.assign(new Error('Request failed with status code 409'), { config: {}, response: { status: 409, data: { currentRevision: 9 } } })
    await expect(mock.reject(err)).rejects.toBe(err)
    expect(err.response.status).toBe(409)
  })
  it('超过64KiB的流错误体被限额并关闭Reader', async () => {
    const body = stream({ msg: 'x'.repeat(70000) })
    await expect(mock.response({ config: { aipptStream: true, preserveBusinessError: true }, headers: { 'content-type': 'application/json' }, status: 200, data: body })).rejects.toMatchObject({ status: 502 })
    expect(body.locked).toBe(false)
  })
  it('离页取消订阅不弹连接错误', async () => {
    const err = Object.assign(new Error('cancelled'), { code: 'ERR_CANCELED', config: { aipptStream: true } })
    await expect(mock.reject(err)).rejects.toBe(err)
    expect(mock.message).not.toHaveBeenCalled()
  })
})
