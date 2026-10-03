import { createHash, randomUUID } from 'node:crypto'
import { readFile, stat, writeFile } from 'node:fs/promises'
import { dialog, nativeImage } from 'electron'
import { AdenApiClient, isRecord } from '../transport/api-client'
import type { SessionController } from '../auth/session-controller'
import type { CollectionOperation, CollectionDetail, CollectionOverview, CollectionExportRecord } from '../../shared/contracts/collection'

const id = (value: unknown): string => {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9-]{1,64}$/.test(value)) throw new TypeError('对象标识不合法')
  return value
}
export class CollectionService {
  constructor(readonly api: AdenApiClient, readonly session: SessionController) {}
  path(workspaceId: string): string { return `/api/v1/aden/collection/workspaces/${id(workspaceId)}` }
  async execute(operation: CollectionOperation): Promise<unknown> {
    const context = this.session.context()
    if (!context.authenticated || context.workspaceId !== operation.workspaceId || context.sessionEpoch !== operation.sessionEpoch || context.workspaceEpoch !== operation.workspaceEpoch) throw new Error('采集库上下文已变化，请刷新')
    const base = this.path(operation.workspaceId)
    const input = operation.input
    if (!isRecord(input)) throw new TypeError('采集库参数必须是对象')
    const request = async <T>(path: string, method: 'GET' | 'POST' | 'PUT' = 'GET', body?: unknown): Promise<T> => {
      if (!this.session.isCurrent(context)) throw new Error('采集库上下文已变化，请重新操作')
      return (await this.api.request<T>({ path: base + path, method, body })).data
    }
    switch (operation.action) {
      case 'overview': {
        const overview = await request<CollectionOverview>('/overview')
        if (!overview || overview.timezone !== 'Asia/Shanghai' || !overview.updatedAt ||
            [overview.savedProductCount, overview.todayCaptureCount, overview.pendingTaskCount].some(value => !Number.isSafeInteger(value) || value < 0) ||
            !Array.isArray(overview.recentItems) || !Array.isArray(overview.recentTasks) || !Array.isArray(overview.pendingTasks)) throw new Error('工作台数据格式无效，请重试。')
        return overview
      }
      case 'exports': {
        const page = Number(input.page ?? 1)
        if (!Number.isSafeInteger(page) || page < 1) throw new TypeError('页码不合法')
        return request(`/exports?page=${page}&pageSize=20`)
      }
      case 'exportDetail': return request(`/exports/${id(input.exportId)}`)
      case 'downloadExport': {
        const exportId = id(input.exportId)
        const record = await request<CollectionExportRecord>(`/exports/${exportId}`)
        if (!record.downloadAvailable || !['XLSX', 'ZIP'].includes(record.format)) throw new Error(record.unavailableReason || '此导出文件已不可下载，请重新生成。')
        const extension = record.format.toLowerCase()
        const target = await dialog.showSaveDialog({ title: '保存导出文件', defaultPath: `商品采集.${extension}`, filters: [{ name: record.format, extensions: [extension] }] })
        if (target.canceled || !target.filePath) return { canceled: true }
        if (!this.session.isCurrent(context)) throw new Error('会话已变化，已取消下载')
        const file = await this.api.request<Uint8Array>({ path: `${base}/exports/${exportId}/download`, expectedContentType: 'binary', maxResponseBytes: 120 * 1024 * 1024 })
        if (!this.session.isCurrent(context)) throw new Error('会话已变化，已取消下载')
        await writeFile(target.filePath, file.data)
        return { canceled: false, saved: true, filePath: target.filePath }
      }
      case 'list': {
        const page = Number(input.page ?? 1)
        if (!Number.isSafeInteger(page) || page < 1) throw new TypeError('页码不合法')
        return request(`/items?q=${encodeURIComponent(String(input.q ?? '').slice(0, 200))}&deleted=${input.deleted === true}&page=${page}&pageSize=20`)
      }
      case 'detail': return request(`/items/${id(input.itemId)}`)
      case 'tasks': {
        const page = Number(input.page ?? 1)
        if (!Number.isSafeInteger(page) || page < 1) throw new TypeError('页码不合法')
        const status = String(input.status ?? 'ALL')
        if (!['ALL', 'SAVING', 'SAVED', 'PARTIAL'].includes(status)) throw new TypeError('采集状态不合法')
        const fromDate = collectionDate(input.fromDate)
        const toDate = collectionDate(input.toDate)
        if (fromDate && toDate && fromDate > toDate) throw new TypeError('开始日期不能晚于结束日期')
        return request(`/tasks?q=${encodeURIComponent(String(input.q ?? '').slice(0, 200))}&status=${status}&page=${page}&pageSize=20${fromDate ? '&fromDate=' + fromDate : ''}${toDate ? '&toDate=' + toDate : ''}`)
      }
      case 'taskDetail': return request(`/tasks/${id(input.taskId)}`)
      case 'versions': {
        const page = Number(input.page ?? 1)
        if (!Number.isSafeInteger(page) || page < 1) throw new TypeError('版本页码不合法')
        return request(`/items/${id(input.itemId)}/snapshots?page=${page}&pageSize=20`)
      }
      case 'snapshot': return request(`/items/${id(input.itemId)}/snapshots/${id(input.snapshotId)}`)
      case 'create': {
        if (typeof input.title !== 'string' || !input.title.trim() || input.title.length > 500) throw new TypeError('请输入商品标题')
        if (input.price !== undefined && input.price !== '' && !/^\d{1,12}(\.\d{1,4})?$/.test(String(input.price))) throw new TypeError('价格应为非负金额')
        return request('/items', 'POST', { idempotencyKey: id(input.idempotencyKey), title: input.title, price: input.price || undefined, sourceUrl: input.sourceUrl || undefined, notes: input.notes || undefined })
      }
      case 'curation': return request(`/items/${id(input.itemId)}/curation`, 'PUT', input.curation)
      case 'trash': case 'restore': {
        if (!Array.isArray(input.itemIds) || input.itemIds.length < 1 || input.itemIds.length > 100) throw new TypeError('请选择 1 至 100 个商品')
        return request(`/items/${operation.action}`, 'POST', { itemIds: input.itemIds.map(id) })
      }
      case 'image': {
        const response = await this.api.request<Uint8Array>({ path: `${base}/assets/${id(input.assetId)}${input.thumbnail === true ? '/thumbnail' : ''}`, expectedContentType: 'binary', maxResponseBytes: (input.thumbnail === true ? 1 : 10) * 1024 * 1024 })
        const mime = response.headers.get('content-type')?.split(';')[0]
        if (!mime || !['image/jpeg', 'image/png', 'image/webp'].includes(mime)) throw new Error('不支持的图片类型')
        return `data:${mime};base64,${Buffer.from(response.data).toString('base64')}`
      }
      case 'upload': {
        const itemId = id(input.itemId)
        const selection = await dialog.showOpenDialog({ title: '添加本地商品图片', properties: ['openFile', 'multiSelections'], filters: [{ name: '商品图片', extensions: ['jpg', 'jpeg', 'png', 'webp'] }] })
        if (selection.canceled) return { canceled: true, uploaded: 0 }
        if (!this.session.isCurrent(context)) throw new Error('会话已变化，已取消图片上传')
        if (selection.filePaths.length > 50) throw new Error('一次最多添加 50 张图片')
        const sizes = await Promise.all(selection.filePaths.map((path) => stat(path)))
        if (sizes.some((s) => !s.isFile() || s.size > 10 * 1024 * 1024) || sizes.reduce((n, s) => n + s.size, 0) > 100 * 1024 * 1024) throw new Error('图片超出单张 10 MiB 或总计 100 MiB 限制')
        const detail = await request<CollectionDetail>(`/items/${itemId}`)
        const snapshotId = id(input.snapshotId ?? detail.snapshotId)
        if (snapshotId !== detail.snapshotId) throw new Error('历史快照只读，请选择当前版本后添加图片')
        const generation = detail.generation
        let uploaded = 0
        for (const path of selection.filePaths) {
          if (!this.session.isCurrent(context)) throw new Error('会话已变化，已停止图片上传')
          const bytes = await readFile(path)
          if (bytes.length > 10 * 1024 * 1024) throw new Error('图片超出大小上限')
          const mimeType = imageMime(bytes)
          if (nativeImage.createFromBuffer(bytes).isEmpty()) throw new Error('图片无法解码')
          const sha256 = createHash('sha256').update(bytes).digest('hex')
          const imageId = randomUUID()
          for (let offset = 0; offset < bytes.length; offset += 128 * 1024) {
            await request(`/items/${itemId}/images/chunks`, 'POST', { imageId, snapshotId, generation, offset, totalSize: bytes.length, sha256, mimeType, dataBase64: bytes.subarray(offset, offset + 128 * 1024).toString('base64') })
          }
          uploaded++
        }
        return { canceled: false, uploaded }
      }
      case 'export': {
        if (!['XLSX', 'ZIP'].includes(String(input.format)) || !Array.isArray(input.itemIds) || !input.itemIds.length || input.itemIds.length > 100) throw new TypeError('请选择 1 至 100 个商品与导出格式')
        const format = String(input.format)
        const target = await dialog.showSaveDialog({ title: '导出商品', defaultPath: `商品采集.${format.toLowerCase()}`, filters: [{ name: format, extensions: [format.toLowerCase()] }] })
        if (target.canceled || !target.filePath) return { canceled: true }
        if (!this.session.isCurrent(context)) throw new Error('会话已变化，已取消导出')
        const itemIds = input.itemIds.map(id)
        const snapshotIds = input.snapshotId && itemIds.length === 1 ? { [itemIds[0]]: id(input.snapshotId) } : undefined
        const job = await request<{ exportId: string }>('/exports', 'POST', { itemIds, format, snapshotIds })
        const file = await this.api.request<Uint8Array>({ path: `${base}/exports/${id(job.exportId)}/download`, expectedContentType: 'binary', maxResponseBytes: 120 * 1024 * 1024 })
        if (!this.session.isCurrent(context)) throw new Error('会话已变化，已取消导出')
        await writeFile(target.filePath, file.data)
        return { canceled: false, saved: true, filePath: target.filePath }
      }
      default: throw new TypeError('未知采集库操作')
    }
  }
}

function collectionDate(value: unknown): string {
  if (value === undefined || value === '') return ''
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new TypeError('日期格式不合法')
  const date = new Date(value + 'T00:00:00Z')
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value) throw new TypeError('日期不存在')
  return value
}

export function imageMime(bytes: Uint8Array): string {
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg'
  if (Buffer.from(bytes.subarray(0, 8)).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return 'image/png'
  if (Buffer.from(bytes.subarray(0, 4)).toString() === 'RIFF' && Buffer.from(bytes.subarray(8, 12)).toString() === 'WEBP') return 'image/webp'
  throw new Error('仅支持真实 JPEG、PNG、WebP 图片')
}
