import type { AiPictureHost, HostRequest } from './HostContract'
import { ApiError } from '@/api/client'

/** 每个 mount 独立管理请求、授权图片和资源，不与其他标签页共享正式状态。 */
export class HostRuntime {
  host: AiPictureHost
  mountRoot?: HTMLElement
  private controller = new AbortController()
  private disposed = false
  private assets = new Map<string, Promise<string>>()
  private blobUrls = new Set<string>()
  private versions = new Map<string, number>()
  private subscriptions = new Set<() => void>()
  constructor(host: AiPictureHost) { this.host = host }
  get active() { return !this.disposed && this.host.active !== false }
  // 同一实例切路由/身份会替换 controller；等待任务必须固定原上下文的 signal。
  get signal() { return this.controller.signal }
  requirePermission(permission: string) {
    if (!this.host.hasPermission(permission)) throw new ApiError(403, '当前账号没有执行此操作的权限')
  }
  async request<T = unknown>(config: HostRequest): Promise<T> {
    if (!this.active) throw new DOMException('页面已失活或卸载', 'AbortError')
    const signal = this.controller.signal
    const response = await this.host.request<T>({ ...config, signal })
    if (signal.aborted || !this.active) throw new DOMException('请求已取消', 'AbortError')
    return response
  }
  metadata(path: string, input?: unknown, capturedExpectedVersion?: number): Record<string, unknown> {
    const body = input && typeof input === 'object' && !(input instanceof FormData) ? { ...input } : {}
    const session = /^\/sessions\/([^/?]+)/.exec(path)?.[1]
    if (capturedExpectedVersion !== undefined && (!Number.isSafeInteger(capturedExpectedVersion) || capturedExpectedVersion < 0)) {
      throw new ApiError(400, '会话版本无效，请重新读取画布')
    }
    // 文档命令固定用户提交时的正式版本；重读不能悄悄把旧意图换成新版本写入。
    const version = capturedExpectedVersion ?? (session ? this.versions.get(session) : undefined)
    return { ...body, ...(version === undefined ? {} : { expectedVersion: version }), idempotencyKey: crypto.randomUUID(), schemaVersion: '1', toolRegistryVersion: '2026.10-r2' }
  }
  async hydrate<T>(value: T): Promise<T> {
    const walk = async (item: unknown): Promise<unknown> => {
      if (Array.isArray(item)) return Promise.all(item.map(walk))
      if (!item || typeof item !== 'object') return item
      const object = item as Record<string, unknown>
      if (object.id && ('document' in object || 'original_asset_id' in object)) {
        const version = Number(object.row_version ?? object.revision)
        if (Number.isFinite(version)) this.versions.set(String(object.id), Math.max(this.versions.get(String(object.id)) ?? 0, version))
      }
      if (object.id && 'image_format' in object && 'width' in object && 'kind' in object) {
        const id = String(object.id)
        let url = this.assets.get(id)
        if (!url) {
          url = this.request<Blob>({ url: `/aipicture/assets/${encodeURIComponent(id)}/download`, method: 'GET', responseType: 'blob' })
            .then((blob) => {
              if (!(blob instanceof Blob) || blob.type.includes('json')) throw new ApiError(502, '无法读取授权图片')
              const result = URL.createObjectURL(blob); this.blobUrls.add(result); return result
            }).catch((error) => { this.assets.delete(id); throw error })
          this.assets.set(id, url)
        }
        return { ...object, url: await url }
      }
      return Object.fromEntries(await Promise.all(Object.entries(object).map(async ([key, entry]) => [key, await walk(entry)])))
    }
    return await walk(value) as T
  }
  subscribe(runId: string, onSnapshot: (snapshot: unknown) => void, onError: (error: unknown) => void) {
    if (!this.host.subscribeRun || !this.active) return undefined
    const raw = this.host.subscribeRun(runId, (value) => { if (this.active) onSnapshot(value) }, onError)
    const close = () => { raw(); this.subscriptions.delete(close) }
    this.subscriptions.add(close); return close
  }
  reset() {
    this.controller.abort(); this.controller = new AbortController()
    for (const close of [...this.subscriptions]) close()
    for (const url of this.blobUrls) URL.revokeObjectURL(url)
    this.blobUrls.clear(); this.assets.clear(); this.versions.clear()
  }
  dispose() { this.disposed = true; this.reset() }
}
