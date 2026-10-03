import request from '@/utils/request'
import type { AxiosRequestConfig } from 'axios'
import type { Id, Page, Query, Result } from '@/types/paismart'

// 业务协议只适配共享 request，认证和错误提示仍由若依处理。
export function call<T>(url: string, config: AxiosRequestConfig = {}): Promise<T> {
  return request({ url: `/paismart${url}`, ...config }) as unknown as Promise<T>
}
export const get = <T>(url: string, params?: Query, signal?: AbortSignal) => call<Result<T>>(url, { params, signal })
export const list = <T>(url: string, params?: Query, signal?: AbortSignal) => call<Page<T>>(url, { params, signal })
export const post = <T>(url: string, data?: unknown, key?: string) => call<Result<T>>(url, { method: 'post', data, headers: key ? { 'Idempotency-Key': key, repeatSubmit: false } : undefined })
export const put = <T>(url: string, data: unknown, version?: number) => call<Result<T>>(url, { method: 'put', data, headers: version === undefined ? undefined : { 'If-Match': String(version) } })
export const remove = <T>(url: string, version: number) => call<Result<T>>(url, { method: 'delete', headers: { 'If-Match': String(version) } })
export const pathId = (id: Id) => encodeURIComponent(String(id))
export const newKey = () => crypto.randomUUID()
