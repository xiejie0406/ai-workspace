import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, platformRequest, request, requestBinary } from '@/api/client'
import { waitTask } from '@/api/task'
import { useAuthStore } from '@/features/auth/store'
import { fetchProject } from '@/features/projects/api'
import { startPlatformSession } from '@/features/auth/session'
import { QueryClient } from '@tanstack/react-query'

const ok = (data: unknown) => new Response(JSON.stringify({ code: 200, data }), { headers: { 'Content-Type': 'application/json' } })
beforeEach(async () => {
  document.cookie = `Admin-Token=${crypto.randomUUID()}; Path=/`; vi.restoreAllMocks()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 200, user: { userId: 1 }, permissions: [] }))))
  await platformRequest('/getInfo')
  useAuthStore.setState({ permissions: ['*:*:*'] })
})
describe('原请求出口的中心适配', () => {
  it('共享 Cookie 鉴权且 Ajax/Table 归一，不写本地 token', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(ok({ id: 'p' })).mockResolvedValueOnce(new Response(JSON.stringify({ code: 200, rows: [{ id: 'p' }], total: 1 })))
    vi.stubGlobal('fetch', fetcher)
    expect(await request('/projects/p')).toEqual({ id: 'p' })
    expect(await request('/projects')).toEqual([{ id: 'p' }])
    expect(new Headers(fetcher.mock.calls[0][1].headers).get('Authorization')).toBe(`Bearer ${document.cookie.split('=')[1]}`)
    expect(localStorage.getItem('aippt.token')).toBeNull()
  })
  it('HTTP200业务409保留正式修订与错误标识', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 409, msg: '版本冲突', errorCode: 'REVISION_CONFLICT', currentRevision: 7, correlationId: 'c', retryable: false }))))
    await expect(request('/projects/p')).rejects.toMatchObject({ status: 409, message: '版本冲突', detail: { currentRevision: 7, errorCode: 'REVISION_CONFLICT', correlationId: 'c' } })
  })
  it('未知回执重试保持原body/key，外部新key不能替换；内容变化不发第二请求', async () => {
    const fetcher = vi.fn().mockRejectedValueOnce(new TypeError('断网')).mockResolvedValueOnce(ok({ id: 'p' }))
    vi.stubGlobal('fetch', fetcher)
    const body = JSON.stringify({ title: '原意图', input_revision: 2 })
    await expect(request('/projects/p', { method: 'PATCH', body, idempotencyKey: 'first' })).rejects.toThrow('断网')
    await expect(request('/projects/p', { method: 'PATCH', body: '{}', idempotencyKey: 'new' })).rejects.toThrow('上次请求结果未确认')
    expect(fetcher).toHaveBeenCalledTimes(1)
    await request('/projects/p', { method: 'PATCH', body, idempotencyKey: 'new' })
    expect(new Headers(fetcher.mock.calls[1][1].headers).get('Idempotency-Key')).toBe('first')
    expect(fetcher.mock.calls[1][1].body).toBe(body)
  })
  it('同名同大小同mtime不同File不能替代未决上传', async () => {
    const fetcher = vi.fn().mockRejectedValue(new TypeError('断网')); vi.stubGlobal('fetch', fetcher)
    const a = new FormData(); a.append('file', new File(['a'], 'a.png', { lastModified: 1 })); a.append('revision', '3')
    const b = new FormData(); b.append('file', new File(['b'], 'a.png', { lastModified: 1 })); b.append('revision', '3')
    await expect(request('/projects/p/image', { method: 'PUT', body: a })).rejects.toThrow('断网')
    await expect(request('/projects/p/image', { method: 'PUT', body: b })).rejects.toThrow('上次请求结果未确认')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(new Headers(fetcher.mock.calls[0][1].headers).has('Content-Type')).toBe(false)
  })
  it('私有文件HTTP200的JSON401拒绝，不能被当成Blob下载', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 401, msg: '会话过期' }), { headers: { 'Content-Type': 'application/json' } })))
    await expect(requestBinary('/assets/a')).rejects.toMatchObject({ status: 401 })
    expect(document.cookie).not.toContain('Admin-Token=')
  })
  it('迟到旧会话响应不回传业务数据', async () => {
    let release!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { release = resolve })))
    const pending = request('/projects/private')
    document.cookie = 'Admin-Token=other; Path=/'
    release(ok({ id: 'private' }))
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })
  it('换Cookie后第一业务请求先失效旧身份，getInfo完成前不向新会话发送旧操作', async () => {
    const changed = vi.fn(); window.addEventListener('aippt:session-change', changed)
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 200, user: { userId: 2 }, permissions: ['aippt:project:list'] })))
    vi.stubGlobal('fetch', fetcher); document.cookie = 'Admin-Token=new-account; Path=/'
    await expect(request('/projects/old-account')).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).not.toHaveBeenCalled(); expect(changed).toHaveBeenCalledOnce()
    await platformRequest('/getInfo')
    fetcher.mockResolvedValueOnce(ok([])); expect(await request('/projects')).toEqual([])
    window.removeEventListener('aippt:session-change', changed)
  })
  it('真实会话监听在首次新Cookie请求前清旧权限/Query，getInfo延迟不能沿用旧用户', async () => {
    const client = new QueryClient(); client.setQueryData(['projects', 'private'], { id: 'old-private' })
    useAuthStore.setState({ user: { id: '1', name: '旧用户', email: '' }, permissions: ['*:*:*'], roles: ['admin'], restoring: false })
    const stop = startPlatformSession(client)
    let release!: (response: Response) => void
    const fetcher = vi.fn(() => new Promise<Response>((resolve) => { release = resolve })); vi.stubGlobal('fetch', fetcher)
    document.cookie = 'Admin-Token=next-session; Path=/'
    await expect(request('/projects/old-private')).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).toHaveBeenCalledTimes(1); expect(fetcher.mock.calls[0]![0]).toContain('/getInfo')
    expect(client.getQueryData(['projects', 'private'])).toBeUndefined(); expect(useAuthStore.getState()).toMatchObject({ user: null, permissions: [], roles: [], restoring: true })
    release(new Response(JSON.stringify({ code: 200, user: { userId: 2, userName: '新用户' }, roles: ['reader'], permissions: ['aippt:project:list'] })))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(useAuthStore.getState()).toMatchObject({ user: { id: '2' }, permissions: ['aippt:project:list'], restoring: false })
    stop(); client.clear()
  })
  it('项目详情按实际两个端点组装材料，不假设嵌入来源', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(ok(url.endsWith('/sources') ? [{ id: 'source', sections: [] }] : { id: 'p', input_revision: 4 }))))
    expect(await fetchProject('p')).toEqual({ id: 'p', input_revision: 4, sources: [{ id: 'source', sections: [] }] })
  })
  it('异步导出回读失败保留原结构化问题，不冒充下载成功', async () => {
    const verify = { passed: false, issues: [{ check: 'editable', slide_index: 2, shape: 'chart', message: '图表不可编辑' }], slide_count: 3, expected_slide_count: 3, diagnostics_truncated: false, total_issues: 1 }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok({ status: 'failed', error: '回读失败', verification_failure: verify })))
    const result = waitTask({ job_id: 'job' })
    await expect(result).rejects.toBeInstanceOf(ApiError)
    await expect(result).rejects.toMatchObject({ status: 422, detail: verify })
  })
})
