import type { HostRequest } from '@/host/HostContract'
import type { HostRuntime } from '@/host/HostRuntime'
import { normalizeBusinessDto } from './normalize'

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) { super(message); this.status = status }
}

/** 兼容共享 request 的 AjaxResult 与 TableDataInfo。 */
export function unwrap<T>(value: unknown): T {
  if (value && typeof value === 'object') {
    const result = value as { code?: number; msg?: string; data?: unknown; rows?: unknown; total?: number }
    if (result.code !== undefined && result.code !== 200 && result.code !== 0) throw new ApiError(result.code, result.msg || '请求失败')
    if ('rows' in result) return result.rows as T
    if ('code' in result) return result.data as T
  }
  return value as T
}

export function createClient(runtime: HostRuntime) {
  async function request<T>(path: string, method: HostRequest['method'], body?: unknown, permission?: string, capturedExpectedVersion?: number): Promise<T> {
    if (permission) runtime.requirePermission(permission)
    const data = method === 'GET' ? undefined : runtime.metadata(path, body, capturedExpectedVersion)
    const headers = data ? { 'Idempotency-Key': String(data.idempotencyKey) } : undefined
    const raw = await runtime.request({ url: `/aipicture${path}`, method, data, headers, responseType: 'json' })
    return runtime.hydrate(normalizeBusinessDto(unwrap<T>(raw)))
  }
  return {
    get: <T>(path: string) => request<T>(path, 'GET'),
    post: <T>(path: string, body?: unknown, permission?: string, capturedExpectedVersion?: number) => request<T>(path, 'POST', body, permission, capturedExpectedVersion),
    patch: <T>(path: string, body?: unknown, permission?: string) => request<T>(path, 'PATCH', body, permission),
    delete: <T>(path: string, permission?: string) => request<T>(path, 'DELETE', undefined, permission),
    upload: async <T>(file: File) => {
      runtime.requirePermission('aipicture:asset:upload')
      const body = new FormData(); body.append('file', file)
      const raw = await runtime.request({ url: '/aipicture/assets', method: 'POST', data: body, responseType: 'json' })
      return runtime.hydrate(normalizeBusinessDto(unwrap<T>(raw)))
    },
    blob: async (path: string) => {
      runtime.requirePermission('aipicture:export:download')
      const blob = await runtime.request<Blob>({ url: `/aipicture${path}`, method: 'GET', responseType: 'blob' })
      if (!(blob instanceof Blob)) throw new ApiError(502, '下载响应不是文件')
      if (blob.type.includes('json')) {
        let message = '下载失败，请重试'
        try { const error = JSON.parse(await blob.text()); message = error.msg || error.detail || message } catch { /* 保留默认错误 */ }
        throw new ApiError(400, String(message))
      }
      return blob
    },
  }
}
export type ApiClient = ReturnType<typeof createClient>
