/** 若依持久字段与源业务 DTO 的显式兼容，状态统一后才进入页面逻辑。 */
export function normalizeBusinessDto<T>(value: T): T {
  const visit = (item: unknown): unknown => {
    if (Array.isArray(item)) return item.map(visit)
    if (!item || typeof item !== 'object') return item
    const result = Object.fromEntries(Object.entries(item).map(([key, entry]) => [key, visit(entry)]))
    const state = result.status
    // planning 是 Java 已保存的正式规划状态，保留原义，不折叠成工具执行中。
    if (state === 'executing') result.status = 'running'
    else if (state === 'cancelled') result.status = 'canceled'
    // 批量子项数据库沿用 queued；源页面把未开始项称为 pending。
    if ('source_asset_id' in result && result.status === 'queued') result.status = 'pending'
    if ('goal' in result && !('error' in result)) result.error = result.message ?? null
    if ('markers' in result && result.markers == null) result.markers = []
    // 文档图层同样有 id/kind/width/height，不能当作素材下载 DTO 校验。
    const assetDto = 'kind' in result && 'width' in result && 'height' in result && 'id' in result
      && !('transform' in result) && !('asset_id' in result)
      && ['image_format', 'format', 'size_bytes', 'sha256', 'source'].some((key) => key in result)
    if (assetDto) {
      if (!result.image_format && result.format) result.image_format = String(result.format).toUpperCase()
      if (result.kind === 'image') {
        const source = String(result.source || '')
        result.kind = source === 'upload' ? 'original' : source === 'generate_marketing' ? 'marketing' : source === 'prepare_delivery_sizes' ? 'export' : 'generated'
      }
      if (result.has_alpha === 0 || result.has_alpha === 1) result.has_alpha = Boolean(result.has_alpha)
      if (!result.image_format || !Number.isFinite(Number(result.width)) || !Number.isFinite(Number(result.height))) throw new Error('素材元数据缺少格式或合法尺寸')
    }
    for (const key of ['created_at', 'updated_at']) {
      if (typeof result[key] === 'number') result[key] = new Date(result[key] as number).toISOString()
    }
    return result
  }
  return visit(value) as T
}
