import request from '@/utils/request'

export const FASHION_API_PREFIX = '/fashion' as const

export type FashionHttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete'
export type FashionQuery = Record<string, string | number | boolean | null | undefined>

export interface FashionRequestOptions<TBody = unknown> {
  path: string
  method?: FashionHttpMethod
  params?: FashionQuery
  body?: TBody
  signal?: AbortSignal
  timeoutMs?: number
  correlationId?: string
  idempotencyKey?: string
  ifMatch?: string
  responseType?: 'json' | 'blob' | 'arraybuffer'
}

export interface FashionApiErrorDetails {
  status: number
  code: string
  correlationId?: string
  retryable: boolean
}

type UnknownRecord = Record<string, unknown>

export class FashionApiError extends Error implements FashionApiErrorDetails {
  readonly status: number
  readonly code: string
  readonly correlationId?: string
  readonly retryable: boolean

  constructor(message: string, details: FashionApiErrorDetails) {
    super(message)
    this.name = 'FashionApiError'
    this.status = details.status
    this.code = details.code
    this.correlationId = details.correlationId
    this.retryable = details.retryable
  }
}

function asRecord(value: unknown): UnknownRecord {
  return value !== null && typeof value === 'object' ? value as UnknownRecord : {}
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function optionalBoolean(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined
}

function optionalNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function createCorrelationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `fashion-admin-${Date.now()}-${Math.random().toString(36).slice(2, 14)}`
}

function normalizeOptionalHeader(value: string | undefined, name: string): string | undefined {
  if (value === undefined) return undefined
  const normalized = value.trim()
  if (!normalized || /[\r\n]/.test(normalized)) {
    throw new Error(`${name} 不能为空且不能包含换行符`)
  }
  return normalized
}

function normalizeTimeout(timeoutMs: number | undefined): number | undefined {
  if (timeoutMs === undefined) return undefined
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 120_000) {
    throw new Error('timeoutMs 必须是 1～120000 之间的整数')
  }
  return timeoutMs
}

export function resolveFashionApiPath(path: string): string {
  const normalized = path.trim()
  if (!normalized || normalized.includes('\\') || normalized.includes('?') || normalized.includes('#')) {
    throw new Error('Fashion API path 必须是不含查询串的相对路径')
  }
  if (/^[a-z][a-z\d+.-]*:/i.test(normalized) || normalized.startsWith('//')) {
    throw new Error('Fashion API 禁止使用绝对地址')
  }

  const relativePath = normalized.replace(/^\/+/, '')
  const segments = relativePath.split('/')
  const containsUnsafeSegment = segments.some(segment =>
    !segment
    || segment === '.'
    || segment === '..'
    || !/^[A-Za-z0-9][A-Za-z0-9._~:@-]*$/.test(segment)
  )
  if (containsUnsafeSegment) {
    throw new Error('Fashion API path 只能包含非空 ASCII 安全路径段')
  }

  return `${FASHION_API_PREFIX}/${relativePath}`
}

export function normalizeFashionApiError(error: unknown): FashionApiError {
  if (error instanceof FashionApiError) return error

  const source = asRecord(error)
  const response = asRecord(source.response)
  const payload = asRecord(response.data)
  const envelope = asRecord(payload.error)
  const headers = asRecord(response.headers)
  const status = optionalNumber(source.status)
    ?? optionalNumber(payload.status)
    ?? optionalNumber(payload.code)
    ?? optionalNumber(response.status)
    ?? 0
  const code = optionalString(envelope.code)
    ?? optionalString(payload.errorCode)
    ?? optionalString(source.code)
    ?? 'UNKNOWN_ERROR'
  const correlationId = optionalString(source.correlationId)
    ?? optionalString(envelope.correlationId)
    ?? optionalString(envelope.correlation_id)
    ?? optionalString(payload.correlationId)
    ?? optionalString(payload.correlation_id)
    ?? optionalString(headers['x-correlation-id'])
  const retryable = optionalBoolean(envelope.retryable)
    ?? optionalBoolean(source.retryable)
    ?? false
  const message = optionalString(envelope.userMessage)
    ?? optionalString(envelope.user_message)
    ?? optionalString(envelope.message)
    ?? optionalString(payload.msg)
    ?? (error instanceof Error ? error.message : undefined)
    ?? '智能选品业务接口请求失败'

  return new FashionApiError(message, { status, code, correlationId, retryable })
}

export async function fashionRequest<TResponse, TBody = unknown>(
  options: FashionRequestOptions<TBody>
): Promise<TResponse> {
  const correlationId = normalizeOptionalHeader(options.correlationId, 'X-Correlation-ID') ?? createCorrelationId()
  const idempotencyKey = normalizeOptionalHeader(options.idempotencyKey, 'Idempotency-Key')
  const ifMatch = normalizeOptionalHeader(options.ifMatch, 'If-Match')
  const timeout = normalizeTimeout(options.timeoutMs)
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Correlation-ID': correlationId
  }
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
  if (ifMatch) headers['If-Match'] = ifMatch
  // 覆盖若依全局 JSON 默认值，由 Axios 为文件上传生成 multipart boundary。
  if (typeof FormData !== 'undefined' && options.body instanceof FormData) {
    headers['Content-Type'] = 'multipart/form-data'
  }

  try {
    return await request({
      url: resolveFashionApiPath(options.path),
      method: options.method ?? 'get',
      params: options.params,
      data: options.body,
      signal: options.signal,
      timeout,
      responseType: options.responseType,
      headers,
      preserveBusinessError: true
    }) as TResponse
  } catch (error) {
    throw normalizeFashionApiError(error)
  }
}
