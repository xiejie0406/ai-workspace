export type TransportErrorCode =
  | 'INVALID_REQUEST'
  | 'UNAUTHENTICATED'
  | 'FORBIDDEN'
  | 'RATE_LIMITED'
  | 'SERVER_ERROR'
  | 'HTTP_ERROR'
  | 'RUOYI_ERROR'
  | 'REDIRECT_REJECTED'
  | 'TIMEOUT'
  | 'ABORTED'
  | 'INVALID_CONTENT_TYPE'
  | 'RESPONSE_TOO_LARGE'
  | 'INVALID_RESPONSE'
  | 'DEFAULT_WORKSPACE_UNAVAILABLE'
  | 'NETWORK_ERROR'

export class AdenTransportError extends Error {
  constructor(
    readonly code: TransportErrorCode,
    message: string,
    readonly status: number | null,
    readonly correlationId: string,
    readonly retryable: boolean
  ) {
    super(message)
    this.name = 'AdenTransportError'
  }
}

export function mapHttpError(status: number, correlationId: string): AdenTransportError {
  if (status === 401) return new AdenTransportError('UNAUTHENTICATED', '登录已失效', status, correlationId, false)
  if (status === 403) return new AdenTransportError('FORBIDDEN', '没有访问该资源的权限', status, correlationId, false)
  if (status === 429) return new AdenTransportError('RATE_LIMITED', '请求过于频繁', status, correlationId, true)
  if (status >= 500) return new AdenTransportError('SERVER_ERROR', '服务暂时不可用', status, correlationId, true)
  return new AdenTransportError('HTTP_ERROR', `请求失败（HTTP ${status}）`, status, correlationId, false)
}

/** 只暴露稳定、安全字段，不把响应正文、URL、header 或凭据送入 renderer。 */
export function serializeTransportError(error: unknown): Record<string, unknown> {
  if (error instanceof AdenTransportError) {
    return {
      code: error.code,
      message: error.message,
      status: error.status,
      correlationId: error.correlationId,
      retryable: error.retryable
    }
  }
  if (error instanceof TypeError || error instanceof RangeError) {
    return {
      code: 'INVALID_REQUEST',
      message: error.message.slice(0, 200),
      status: null,
      correlationId: null,
      retryable: false
    }
  }
  return {
    code: 'NETWORK_ERROR',
    message: '桌面端请求失败',
    status: null,
    correlationId: null,
    retryable: false
  }
}
