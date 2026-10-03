import { readFileSync } from 'node:fs'
import path from 'node:path'
import { ReadableStream } from 'node:stream/web'
import { TextEncoder } from 'node:util'
import { act, createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { platformRequest } from '@/api/client'
import { useEventStream } from '@/hooks/useEventStream'
import { consumeEventStream } from '@/lib/sse'

// 原始信封为真实 H2 持久事件经 EventService 发送捕获，不按 React 提取器反推字段。
const captured = JSON.parse(readFileSync(path.resolve(import.meta.dirname,
  '../../../../output/aippt-redis-delivery-20261003/backend-module-complete/captured-frames.json'), 'utf8'))
const rawProgress = captured.sse.find((frame: Frame) => frame.type === 'progress' && frame.delivery?.status === 'connected') as Frame
const rawDelivery = captured.sse.find((frame: Frame) => frame.type === 'delivery') as Frame
type Frame = { eventSeq: number; type: string; projectId?: string; state?: string; data: Record<string, unknown>; delivery?: { status: string; reason: string } }
type Business = { type: string; status?: string; progress?: number; message?: string }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 200, data }), { headers: { 'Content-Type': 'application/json' } })
const deck = (ready = 1, status = 'generating') => ({ project_id: 'isolated-project', status, ready, failed: 0, total: 4, slides: [] })
const outline = (status = 'generating') => ({ project_id: 'isolated-project', status, pages: [], revision: 2 })

function wire() {
  let writer!: import('node:stream/web').ReadableStreamDefaultController<Uint8Array>
  let cancellations = 0
  const body = new ReadableStream<Uint8Array>({ start(controller) { writer = controller }, cancel() { cancellations++ } })
  const encoder = new TextEncoder()
  const response = new Response(body as unknown as BodyInit, { headers: { 'Content-Type': 'text/event-stream' } })
  return {
    response,
    send(frame: Frame) { writer.enqueue(encoder.encode(`id: ${frame.eventSeq}\nevent: ${frame.type}\ndata: ${JSON.stringify(frame)}\n\n`)) },
    bytes(value: Uint8Array) { writer.enqueue(value) },
    close() { writer.close() },
    cancelled: () => cancellations,
  }
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done }); return { promise, resolve } }
const microtasks = async () => { for (let i = 0; i < 32; i++) await Promise.resolve() }
const settle = () => act(microtasks)
let root: Root | null = null
let container: HTMLDivElement | null = null
const terminalTypes = ['completed', 'failed']
function Probe({ eventPath, active = true, onEvent }: { eventPath: string; active?: boolean; onEvent: (event: Business) => void }) {
  const state = useEventStream<Business>({ path: eventPath, active, terminalTypes, onEvent })
  return createElement('pre', { 'data-state': true }, JSON.stringify(state))
}
async function render(eventPath: string, onEvent: (event: Business) => void, active = true) {
  if (!root) { container = document.createElement('div'); document.body.append(container); root = createRoot(container) }
  await act(async () => root!.render(createElement(Probe, { eventPath, active, onEvent })))
  await settle()
}
const state = () => JSON.parse(container!.querySelector('[data-state]')!.textContent!)
async function send(stream: ReturnType<typeof wire>, frame: Frame) { await act(async () => { stream.send(frame); await microtasks() }); await settle() }

beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  vi.restoreAllMocks()
  document.cookie = `Admin-Token=${crypto.randomUUID()}; Path=/`
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 200, user: { userId: 1 }, permissions: ['*:*:*'] }))))
  await platformRequest('/getInfo')
})
afterEach(async () => {
  if (root) await act(async () => root!.unmount())
  container?.remove(); root = null; container = null
  vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals()
})

describe('原 SSE 解析器消费中心真实信封', () => {
  it('分片 UTF-8/CRLF、心跳与两类真实信封逐字段保留', async () => {
    const stream = wire(); const abort = new AbortController(); const received: unknown[] = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(stream.response))
    const pending = consumeEventStream('/tasks/isolated/events?afterSeq=0', abort.signal, (frame) => { received.push(frame) })
    const encoder = new TextEncoder()
    const bytes = encoder.encode(`: heartbeat\r\n\r\nid: ${rawProgress.eventSeq}\r\nevent: progress\r\ndata: ${JSON.stringify(rawProgress)}\r\n\r\ndata: ${JSON.stringify(rawDelivery)}\r\n\r\n`)
    for (let i = 0; i < bytes.length; i += 3) stream.bytes(bytes.slice(i, i + 3))
    stream.close(); await pending
    expect(received).toEqual([rawProgress, rawDelivery])
    expect((received[0] as Frame).data.progress).toEqual({ percent: 30, message: '独立持久进度' })
    expect((received[1] as Frame).data).toEqual({})
    expect(stream.cancelled()).toBeLessThanOrEqual(1)
  })
})

describe('原事件 hook 的正式快照与异步身份边界', () => {
  it('先 GET 正式 deck；delivery 同序号不覆盖业务，重复业务帧不二次回读', async () => {
    const stream = wire(); let reads = 0; const calls: string[] = []; const events = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => { calls.push(url); return url.includes('/events?') ? stream.response : ok(deck(++reads === 1 ? 1 : 2)) }))
    await render('/projects/a/deck/events', events)
    expect(calls[0]).toMatch(/\/aippt\/projects\/a\/deck$/)
    expect(calls[1]).toMatch(/\/aippt\/projects\/a\/deck\/events\?afterSeq=0$/)
    expect(events.mock.calls[0]![0]).toMatchObject({ type: 'snapshot', status: 'generating', progress: 25 })
    await send(stream, rawProgress)
    expect(state().event.progress).toBe(50)
    const count = events.mock.calls.length
    await send(stream, { ...rawDelivery, eventSeq: rawProgress.eventSeq })
    expect(events).toHaveBeenCalledTimes(count)
    expect(reads).toBe(2)
    expect(state().event.progress).toBe(50)
    expect(state().deliveryMessage).toBe('Redis通知暂不可用，正在定时读取已保存进度')
    await send(stream, rawProgress)
    expect(reads).toBe(2)
    await send(stream, { ...rawDelivery, eventSeq: rawProgress.eventSeq, delivery: { status: 'connected', reason: 'REDIS_CONNECTED' } })
    expect(state().deliveryMessage).toBeNull()
    expect(state().event.progress).toBe(50)
  })

  it('outline changed 描述不是提纲正文，必须回读正式 outline 再宣布完成', async () => {
    const stream = wire(); let reads = 0; const events = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('/events?') ? stream.response : ok(outline(++reads === 1 ? 'generating' : 'draft'))))
    await render('/projects/a/outline/events', events)
    // AipptService.command/event + EventService.replay 的真实 changed 字段语义。
    await send(stream, { eventSeq: 4, type: 'changed', projectId: 'a', state: 'changed', data: { scope: 'outline:save' } })
    expect(reads).toBe(2)
    expect(state().event).toMatchObject({ type: 'completed', status: 'draft', progress: 100 })
    expect(state().event).not.toHaveProperty('scope')
  })

  it('outline progress 使用 TaskLeaseService.progress 的 data.progress 对象及 running 状态', async () => {
    const stream = wire(); const events = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('/events?') ? stream.response : ok(outline())))
    await render('/projects/a/outline/events', events)
    // 项目 outline 流不含 task_data 包装：TaskLeaseService.progress 的持久 data 直接重放。
    await send(stream, { eventSeq: 2, type: 'progress', projectId: 'a', state: 'running', data: { task_id: 'isolated-outline', progress: rawProgress.data.progress } })
    expect(state().event).toMatchObject({ type: 'progress', status: 'generating', progress: 30, message: '独立持久进度' })
  })

  it('重连携带已读业务 seq；高游标 resync 用正式快照恢复且不把通知 data 当内容', async () => {
    vi.useFakeTimers(); const first = wire(); const second = wire(); const streams: string[] = []; let reads = 0; const events = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/events?')) { streams.push(url); return streams.length === 1 ? first.response : second.response }
      return ok(deck(++reads === 1 ? 1 : 2))
    }))
    await render('/projects/a/deck/events', events)
    await send(first, rawProgress)
    await act(async () => { first.close(); await microtasks(); await vi.advanceTimersByTimeAsync(1000) }); await settle()
    expect(streams[1]).toMatch(/\/aippt\/projects\/a\/deck\/events\?afterSeq=2$/)
    const before = reads
    // EventService.subscribe 的 resync_required 不携带正文，只要求客户端重新 GET。
    await send(second, { eventSeq: 90, type: 'resync_required', projectId: 'a', state: 'resync_required', data: { reason: '事件游标超过保留范围，请读取当前快照' } })
    expect(reads).toBe(before + 1)
    expect(state().event).toMatchObject({ type: 'snapshot', status: 'generating', progress: 50 })
    expect(state().event).not.toHaveProperty('reason')
    await act(async () => { second.close(); await microtasks(); await vi.advanceTimersByTimeAsync(1000) }); await settle()
    expect(streams[2]).toMatch(/\/aippt\/projects\/a\/deck\/events\?afterSeq=90$/)
  })

  it('resync 旧 GET 迟到不覆盖新项目且不把旧游标写入新连接', async () => {
    vi.useFakeTimers(); const a = wire(); const b = wire(); const b2 = wire(); const late = deferred<Response>(); const calls: string[] = []; let aReads = 0; let bStreams = 0; const events = vi.fn()
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      calls.push(url)
      if (url.includes('/a/deck/events?')) return Promise.resolve(a.response)
      if (url.includes('/b/deck/events?')) return Promise.resolve(++bStreams === 1 ? b.response : b2.response)
      if (url.endsWith('/a/deck')) return ++aReads === 1 ? Promise.resolve(ok(deck(1))) : late.promise
      return Promise.resolve(ok(deck(2)))
    }))
    await render('/projects/a/deck/events', events)
    await send(a, { eventSeq: 90, type: 'resync_required', state: 'resync_required', projectId: 'a', data: { reason: '事件游标超过保留范围，请读取当前快照' } })
    expect(aReads).toBe(2)
    await render('/projects/b/deck/events', events)
    const callbacks = events.mock.calls.length
    await act(async () => { late.resolve(ok(deck(4, 'ready'))); await microtasks() }); await settle()
    expect(events).toHaveBeenCalledTimes(callbacks)
    expect(state().event).toMatchObject({ type: 'snapshot', status: 'generating', progress: 50 })
    await act(async () => { b.close(); await microtasks(); await vi.advanceTimersByTimeAsync(1000) }); await settle()
    const reconnected = calls.filter((url) => url.includes('/b/deck/events?'))
    expect(reconnected).toHaveLength(2)
    reconnected.forEach((url) => expect(url).toMatch(/\/aippt\/projects\/b\/deck\/events\?afterSeq=0$/))
  })

  it('旧 reader 响应在路由切换后才到，不能再为旧页发 snapshot GET', async () => {
    const pendingStream = deferred<Response>(); const old = wire(); const current = wire(); const calls: string[] = []; const events = vi.fn()
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      calls.push(url)
      if (url.includes('/a/deck/events?')) return pendingStream.promise
      if (url.includes('/b/deck/events?')) return Promise.resolve(current.response)
      return Promise.resolve(ok(deck(url.includes('/a/') ? 1 : 2)))
    }))
    await render('/projects/a/deck/events', events)
    await render('/projects/b/deck/events', events)
    const callbacks = events.mock.calls.length
    old.send({ ...rawProgress, projectId: 'a' })
    await act(async () => { pendingStream.resolve(old.response); await microtasks() }); await settle()
    expect(events).toHaveBeenCalledTimes(callbacks)
    expect(calls.filter((url) => url.endsWith('/a/deck'))).toHaveLength(1)
    expect(state().event.progress).toBe(50)
    expect(old.cancelled()).toBe(1)
  })

  it('会话变化后的旧 resync GET 不写回、不重连旧流', async () => {
    const stream = wire(); const late = deferred<Response>(); let reads = 0; const calls: string[] = []; const events = vi.fn()
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      calls.push(url)
      if (url.endsWith('/getInfo')) return Promise.resolve(new Response(JSON.stringify({ code: 200, user: { userId: 2 }, permissions: ['*:*:*'] })))
      if (url.includes('/events?')) return Promise.resolve(stream.response)
      return ++reads === 1 ? Promise.resolve(ok(deck(1))) : late.promise
    }))
    await render('/projects/a/deck/events', events)
    await send(stream, { eventSeq: 90, type: 'resync_required', state: 'resync_required', projectId: 'a', data: { reason: '事件游标超过保留范围，请读取当前快照' } })
    const callbacks = events.mock.calls.length
    await act(async () => { document.cookie = `Admin-Token=${crypto.randomUUID()}; Path=/`; await platformRequest('/getInfo'); late.resolve(ok(deck(4, 'ready'))); await microtasks() }); await settle()
    expect(events).toHaveBeenCalledTimes(callbacks)
    expect(calls.filter((url) => url.includes('/events?'))).toHaveLength(1)
    expect(stream.cancelled()).toBe(1)
  })

  it.each(['snapshot-json', 'stream-http'] as const)('401 %s 停止且不重试，不能伪造完成', async (where) => {
    vi.useFakeTimers(); const calls: string[] = []; const events = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(url)
      if (where === 'snapshot-json') return new Response(JSON.stringify({ code: 401, msg: '认证失败' }), { headers: { 'Content-Type': 'application/json' } })
      return url.includes('/events?') ? new Response(JSON.stringify({ code: 401, msg: '认证失败' }), { status: 401, headers: { 'Content-Type': 'application/json' } }) : ok(deck(1))
    }))
    await render('/projects/a/deck/events', events)
    await act(async () => { await vi.advanceTimersByTimeAsync(60000) }); await settle()
    expect(calls).toHaveLength(where === 'snapshot-json' ? 1 : 2)
    expect(events).toHaveBeenCalledTimes(where === 'snapshot-json' ? 0 : 1)
    expect(events.mock.calls.some(([event]) => event.type === 'completed')).toBe(false)
    expect(document.cookie).not.toContain('Admin-Token=')
  })

  it('active=false 时不读业务接口，关闭后中止实际 reader', async () => {
    const stream = wire(); const fetcher = vi.fn(async (url: string) => url.includes('/events?') ? stream.response : ok(deck(1))); vi.stubGlobal('fetch', fetcher)
    const events = vi.fn()
    await render('/projects/a/deck/events', events, false)
    expect(fetcher).not.toHaveBeenCalled()
    await render('/projects/a/deck/events', events, true)
    expect(fetcher).toHaveBeenCalledTimes(2)
    await render('/projects/a/deck/events', events, false)
    expect(stream.cancelled()).toBe(1)
    expect(state().event).toBeNull()
  })
})
