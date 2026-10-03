import { tokenStore } from '@/features/auth/token'

export type ApiErrorDetail = string | Record<string, unknown> | unknown[]
export class ApiError extends Error {
  readonly status: number
  readonly detail: ApiErrorDetail
  constructor(status: number, detail: ApiErrorDetail) {
    super(typeof detail === 'string' ? detail : typeof (detail as Record<string, unknown>)?.message === 'string' ? String((detail as Record<string, unknown>).message) : '请求失败')
    this.name = 'ApiError'; this.status = status; this.detail = detail
  }
}
const configured = import.meta.env.VITE_APP_BASE_API ?? (import.meta.env.DEV ? '/dev-api' : '/prod-api')
if (!/^\/[A-Za-z0-9/_-]*$/.test(configured) || configured.startsWith('//')) throw new Error('API 前缀必须为同源相对路径')
export const PLATFORM_PREFIX = configured.replace(/\/$/, '')
export const API_PREFIX = PLATFORM_PREFIX + '/aippt'
type Envelope = { code?: number; msg?: string; detail?: ApiErrorDetail; data?: unknown; rows?: unknown; total?: number; errorCode?: string; currentRevision?: number; correlationId?: string; retryable?: boolean }
export type RequestOptions = RequestInit & { idempotencyKey?: string; table?: boolean }
// 未知回执保留同一请求的正文与幂等键；不自动重试 POST。
const uncertain = new Map<string, { body: RequestInit['body']; key: string }>()
let sessionToken: string | null = tokenStore.get()
let sessionReady = false
function identity() {
  const current = tokenStore.get()
  if (current !== sessionToken) {
    uncertain.clear(); sessionToken = current; sessionReady = false
    window.dispatchEvent(new Event('aippt:session-change'))
  }
  return current
}
function assertSession(expected: string | null) {
  if (tokenStore.get() !== expected) throw new DOMException('登录会话已变化', 'AbortError')
}
export function newCommandKey() { return crypto.randomUUID() }
function detail(body: Envelope): ApiErrorDetail {
  if (body.detail != null) return body.detail
  if (body.errorCode || body.currentRevision !== undefined || body.correlationId || body.retryable !== undefined) return { message: body.msg ?? '请求失败', errorCode: body.errorCode, currentRevision: body.currentRevision, correlationId: body.correlationId, retryable: body.retryable }
  return body.msg ?? (body.data as ApiErrorDetail) ?? '请求失败'
}
function sameBody(a: RequestInit['body'], b: RequestInit['body']) {
  if (a === b) return true
  if (!(a instanceof FormData) || !(b instanceof FormData)) return false
  const left = [...a.entries()]; const right = [...b.entries()]
  return left.length === right.length && left.every(([key, value], index) => { const other = right[index]; return other?.[0] === key && value === other[1] })
}
export async function readErrorDetail(response: Response): Promise<ApiErrorDetail> {
  const text = await response.text().catch(() => '')
  try { const body = JSON.parse(text) as Envelope; return detail(body) } catch { return text || response.statusText || '请求失败' }
}
async function transport(path: string, init: RequestOptions, platform = false): Promise<Response> {
  const token = identity(); const headers = new Headers(init.headers)
  if (!platform && !sessionReady) throw new DOMException('请先恢复当前平台会话', 'AbortError')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const mutating = !['GET', 'HEAD'].includes((init.method ?? 'GET').toUpperCase())
  const slot = `${token ?? ''}:${init.method ?? 'GET'}:${path}`
  let actualBody = init.body
  if (mutating) {
    const pending = uncertain.get(slot)
    if (pending && !sameBody(pending.body, init.body)) throw new ApiError(409, '上次请求结果未确认，请先重试原操作')
    headers.set('Idempotency-Key', pending?.key ?? init.idempotencyKey ?? newCommandKey())
    actualBody = pending?.body ?? init.body
    if (!pending && actualBody instanceof FormData) { const frozen = new FormData(); for (const [name, value] of actualBody.entries()) frozen.append(name, value); actualBody = frozen }
    uncertain.set(slot, { body: actualBody, key: headers.get('Idempotency-Key')! })
  }
  const response = await fetch(`${platform ? PLATFORM_PREFIX : API_PREFIX}${path}`, { ...init, body: actualBody, headers, credentials: 'same-origin' })
  assertSession(token)
  if (!response.ok) {
    if (mutating && response.status < 500) uncertain.delete(slot)
    if (response.status === 401) tokenStore.clear()
    throw new ApiError(response.status, await readErrorDetail(response))
  }
  return response
}
export async function request<T>(path: string, init: RequestOptions = {}): Promise<T> {
  const token = identity(); const response = await transport(path, init)
  if (response.status === 204) return undefined as T
  const body = await response.json() as Envelope
  assertSession(token)
  if (body.code !== undefined && body.code !== 200) {
    if (body.code < 500) uncertain.delete(`${token ?? ''}:${init.method ?? 'GET'}:${path}`)
    if (body.code === 401) tokenStore.clear()
    throw new ApiError(body.code, detail(body))
  }
  uncertain.delete(`${token ?? ''}:${init.method ?? 'GET'}:${path}`)
  return (init.table ? body : body.rows !== undefined ? body.rows : body.code !== undefined ? body.data : body) as T
}
export async function platformRequest<T>(path: string, init: RequestOptions = {}): Promise<T> {
  const token = identity(); const response = await transport(path, init, true); const body = await response.json() as Envelope
  assertSession(token)
  if (body.code !== undefined && body.code !== 200) throw new ApiError(body.code, body.msg ?? '平台请求失败')
  if (path === '/getInfo') sessionReady = true
  return body as T
}
export async function requestBinary(path: string, init: RequestOptions = {}): Promise<Response> {
  const response = await transport(path, init)
  if (response.headers.get('Content-Type')?.includes('application/json')) {
    const body = await response.clone().json() as Envelope
    if (body.code !== undefined && body.code !== 200) {
      if (body.code === 401) tokenStore.clear()
      throw new ApiError(body.code, detail(body))
    }
    throw new ApiError(502, '文件接口返回了非文件内容')
  }
  return response
}
export async function requestStream(path: string, signal: AbortSignal): Promise<Response> {
  return transport(path, { signal, headers: { Accept: 'text/event-stream' } })
}
