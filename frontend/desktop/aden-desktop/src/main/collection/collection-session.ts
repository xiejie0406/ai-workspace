import { randomUUID } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { validateValues, type CollectionEntry, type CollectionSnapshot, type ProductValues } from '../../shared/contracts/collection'
import { collectProduct } from './extract-product'
import { CollectionError, normalizeProductUrl } from './public-http'

/** 仅为前台采集操作；不是中心任务/审批/审计账本，不在后台恢复任务。 */
export class CollectionSession {
  #entries: CollectionEntry[] = []
  #controller: AbortController | null = null
  #completion: Promise<void> = Promise.resolve()
  constructor(readonly collect = collectProduct, readonly intervalMs = 1000) {}
  snapshot(): CollectionSnapshot {
    return structuredClone({ schemaVersion: 'aden-collection-1', entries: this.#entries, running: this.#controller !== null })
  }
  start(input: string, externalSignal?: AbortSignal): CollectionSnapshot {
    if (this.#controller) throw new TypeError('采集正在运行，请先取消')
    const lines = input.split(/\r?\n/).map(s => s.trim()).filter(Boolean)
    if (!lines.length || lines.length > 50) throw new TypeError('一次请输入1至50个商品链接，每行一个')
    const urls = [...new Set(lines.map(normalizeProductUrl))]
    if (this.#entries.length + urls.filter(url => !this.#entries.some(e => e.url === url)).length > 50) throw new TypeError('当前草稿最多50个来源，请保存后重新开始')
    for (const url of urls) if (!this.#entries.some(e => e.url === url)) this.#entries.push({ id: randomUUID(), url, originalUrl: lines.find(line => normalizeProductUrl(line) === url), state: 'queued', message: '等待采集', skus: [] })
    this.#run(externalSignal)
    return this.snapshot()
  }
  retry(externalSignal?: AbortSignal): CollectionSnapshot {
    if (this.#controller) throw new TypeError('请等待当前采集结束')
    this.#entries.filter(e => ['failed', 'cancelled'].includes(e.state)).forEach(e => { e.state = 'queued'; e.message = '等待显式重试' })
    this.#run(externalSignal)
    return this.snapshot()
  }
  cancel(): CollectionSnapshot { this.#controller?.abort(); return this.snapshot() }
  reset(): void { this.#controller?.abort(); this.#controller = null; this.#entries = [] }
  async finished(): Promise<void> { await this.#completion }
  update(entryId: string, skuId: string, values: unknown): CollectionSnapshot {
    if (this.#controller) throw new TypeError('请在采集结束后编辑')
    const sku = this.#entries.find(e => e.id === entryId)?.skus.find(s => s.id === skuId)
    if (!sku) throw new TypeError('商品或SKU不存在')
    sku.values = validateValues(values)
    return this.snapshot()
  }
  load(value: unknown): CollectionSnapshot {
    if (this.#controller) throw new TypeError('请先停止采集')
    const draft = parseDraft(value)
    this.#entries = draft.entries
    return this.snapshot()
  }
  #run(externalSignal?: AbortSignal): void {
    if (!this.#entries.some(e => e.state === 'queued')) return
    const controller = new AbortController()
    const abort = (): void => { controller.abort() }
    externalSignal?.addEventListener('abort', abort, { once: true })
    if (externalSignal?.aborted) controller.abort()
    this.#controller = controller
    const entries = this.#entries
    this.#completion = (async () => {
      try {
        for (const entry of entries) {
          if (entry.state !== 'queued') continue
          if (controller.signal.aborted) break
          entry.state = 'reading'; entry.message = '正在读取公开商品'
          try {
            const result = await this.collect(entry.originalUrl ?? entry.url, controller.signal)
            controller.signal.throwIfAborted()
            if (result.skus.length > 250 || entries.reduce((n, e) => n + e.skus.length, 0) + result.skus.length > 2000) throw new CollectionError('ROW_LIMIT', '本地草稿最多2000个SKU，每个来源最多250个')
            result.skus.forEach(s => { s.original = validateValues(s.original); s.values = validateValues(s.values) })
            // 同域名真实SKU去重，保留空结果来源及解释，不覆盖先前人工修正。
            const seen = new Set(entries.filter(e => e !== entry && e.state === 'collected' && new URL(e.url).hostname === new URL(entry.url).hostname)
              .flatMap(e => e.skus.map(s => s.original.platformSkuId).filter(Boolean)))
            entry.source = result.source
            entry.skus = result.skus.filter(s => !s.values.platformSkuId || !seen.has(s.values.platformSkuId))
            entry.state = 'collected'; entry.message = entry.skus.length ? `取得${entry.skus.length}个真实SKU，待复核` : '该来源SKU已在已有商品中收录'
          } catch (error) {
            entry.state = controller.signal.aborted ? 'cancelled' : 'failed'
            entry.message = controller.signal.aborted ? '用户已取消' : error instanceof CollectionError ? `${error.code}：${error.message}` : '读取或结构校验失败，可检查来源后重试'
          }
          if (!controller.signal.aborted) await delay(this.intervalMs, undefined, { signal: controller.signal }).catch(() => {})
        }
      } finally {
        if (controller.signal.aborted) entries.filter(e => ['queued', 'reading'].includes(e.state)).forEach(e => { e.state = 'cancelled'; e.message = '采集已停止' })
        externalSignal?.removeEventListener('abort', abort)
        if (this.#controller === controller) this.#controller = null
      }
    })()
  }
}

export function parseDraft(input: unknown): CollectionSnapshot {
  const obj = input as CollectionSnapshot
  if (!obj || obj.schemaVersion !== 'aden-collection-1' || !Array.isArray(obj.entries) || obj.entries.length > 50) throw new TypeError('不是兼容的Aden商品草稿')
  const entryIds = new Set<string>()
  const entries = obj.entries.map(entry => {
    if (!entry || typeof entry.id !== 'string' || !/^[a-zA-Z0-9-]{1,80}$/.test(entry.id) || entryIds.has(entry.id)
      || !Array.isArray(entry.skus) || entry.skus.length > 250 || typeof entry.message !== 'string' || entry.message.length > 500) throw new TypeError('草稿来源结构无效')
    entryIds.add(entry.id)
    const url = normalizeProductUrl(entry.url)
    const state = ['collected', 'failed', 'cancelled'].includes(entry.state) ? entry.state : 'cancelled'
    const skuIds = new Set<string>()
    const skus = entry.skus.map(sku => {
      if (!sku || typeof sku.id !== 'string' || !sku.id || sku.id.length > 128 || skuIds.has(sku.id)
        || !['in_stock', 'out_of_stock', 'unknown'].includes(sku.availability) || typeof sku.evidence !== 'string' || sku.evidence.length > 500) throw new TypeError('草稿SKU结构无效')
      skuIds.add(sku.id)
      return { id: sku.id, original: validateValues(sku.original), values: validateValues(sku.values), availability: sku.availability, evidence: sku.evidence }
    })
    let source: CollectionEntry['source']
    if (entry.source) {
      const s = entry.source
      if (s.dataKind !== 'public_observation' || !/^[a-f0-9]{64}$/.test(s.sha256) || typeof s.adapter !== 'string' || s.adapter.length > 80
        || !Number.isFinite(Date.parse(s.collectedAt))) throw new TypeError('草稿来源证据无效')
      normalizeProductUrl(s.originalUrl)
      source = { originalUrl: s.originalUrl, url: normalizeProductUrl(s.url), evidenceUrl: normalizeProductUrl(s.evidenceUrl),
        collectedAt: s.collectedAt, adapter: s.adapter, sha256: s.sha256, dataKind: s.dataKind }
    }
    if (skus.length && !source) throw new TypeError('草稿SKU缺少来源')
    if (entry.originalUrl) normalizeProductUrl(entry.originalUrl)
    return { id: entry.id, url, originalUrl: entry.originalUrl, state, message: entry.message, source, skus } as CollectionEntry
  })
  if (entries.reduce((n, e) => n + e.skus.length, 0) > 2000) throw new TypeError('草稿超过2000个SKU')
  return { schemaVersion: 'aden-collection-1', entries, running: false }
}

export function corrections(original: ProductValues, values: ProductValues): Record<string, { before: string; after: string }> {
  return Object.fromEntries([...new Set([...Object.keys(original), ...Object.keys(values)])]
    .filter(key => original[key as keyof ProductValues] !== values[key as keyof ProductValues])
    .map(key => [key, { before: original[key as keyof ProductValues] ?? '', after: values[key as keyof ProductValues] ?? '' }]))
}
