import { ApiError } from '@/api/client'

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

/** 只认共享 request 的正式业务状态或 HTTP 状态，不猜测错误文字。 */
function readErrorStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object') return undefined
  const preserved = error as { status?: unknown; response?: { status?: unknown; data?: { code?: unknown } } }
  if (typeof preserved.status === 'number') return preserved.status
  const code = preserved.response?.data?.code
  if (typeof code === 'number' && code !== 200 && code !== 0) return code
  return typeof preserved.response?.status === 'number' ? preserved.response.status : undefined
}

export function isNotFoundError(error: unknown): boolean {
  return readErrorStatus(error) === 404
}

/** GET 网络中断或明确的 5xx 可沿原间隔回读；鉴权、不存在和业务拒绝不自动循环。 */
export function isRetryableReadError(error: unknown): boolean {
  if (!error || (error instanceof Error && error.name === 'AbortError')) return false
  const status = readErrorStatus(error)
  return status === undefined || (status >= 500 && status < 600)
}
