import { createHash } from 'node:crypto'
import type { CollectedSku, CollectionSource, ProductValues } from '../../shared/contracts/collection'
import { CollectionError, normalizeProductUrl, readPublicPage, type PublicReader } from './public-http'

type Json = Record<string, unknown>
const record = (v: unknown): Json => v && typeof v === 'object' && !Array.isArray(v) ? v as Json : {}
const text = (v: unknown): string => typeof v === 'string' ? v.trim() : ''
function id(v: unknown): string {
  if (typeof v === 'string') return v.trim()
  if (typeof v === 'number' && Number.isSafeInteger(v) && v >= 0) return String(v)
  return ''
}
function imageUrl(v: unknown, base: string): string {
  const raw = text(v) || text(record(v).url) || text(record(v).src)
  try { const u = new URL(raw, base); return raw && u.protocol === 'https:' && !u.username && !u.password ? u.href : '' } catch { return '' }
}
function amount(v: unknown): string {
  const raw = typeof v === 'number' ? String(v) : text(v)
  return /^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(raw) ? raw : ''
}
export function jsonLdProducts(html: string): Json[] {
  const roots: unknown[] = []
  for (const match of html.matchAll(/<script\b[^>]*type\s*=\s*["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script\s*>/gi)) {
    try { roots.push(JSON.parse(match[1])) } catch { /* 损坏的结构化数据不得执行或猜测。 */ }
  }
  const products: Json[] = []
  function visit(value: unknown, depth = 0): void {
    if (depth > 12) return
    if (Array.isArray(value)) { value.slice(0, 1000).forEach(v => visit(v, depth + 1)); return }
    const r = record(value)
    const types = Array.isArray(r['@type']) ? r['@type'] : [r['@type']]
    if (types.some(t => t === 'Product' || t === 'ProductGroup')) products.push(r)
    if (r['@graph']) visit(r['@graph'], depth + 1)
    if (r.mainEntity) visit(r.mainEntity, depth + 1)
  }
  roots.forEach(v => visit(v))
  return products
}
function availability(value: unknown): CollectedSku['availability'] {
  if (value === true || /(?:^|\/)InStock$/.test(text(value))) return 'in_stock'
  if (value === false || /(?:^|\/)(?:OutOfStock|SoldOut)$/.test(text(value))) return 'out_of_stock'
  return 'unknown'
}
export function parseShopify(product: unknown, url: string, at: string, currency = ''): CollectedSku[] {
  const p = record(product)
  if (!id(p.id) || !text(p.title) || !Array.isArray(p.variants)) throw new CollectionError('STRUCTURE_CHANGED', '公开商品结构不完整，无法确认真实SKU')
  if (p.variants.length >= 250) throw new CollectionError('VARIANT_LIMIT', '商品变体达到接口上限，请人工核对是否完整')
  const options = Array.isArray(p.options) ? p.options.map(o => text(record(o).name) || text(o)) : []
  const color = options.findIndex(o => /^(color|colour|颜色|色彩)$/i.test(o))
  const size = options.findIndex(o => /^(size|尺码|尺寸)$/i.test(o))
  return p.variants.map((variant, index) => {
    const v = record(variant); const variantId = id(v.id)
    if (!variantId) throw new CollectionError('UNSAFE_ID', '平台SKU ID缺失或超出精确整数范围')
    const option = (atIndex: number): string => atIndex < 0 ? '' : text(v[`option${atIndex + 1}`])
    const images = Array.isArray(p.images) ? p.images.map(i => imageUrl(i, url)).filter(Boolean) : []
    const price = typeof v.price === 'number' && Number.isSafeInteger(v.price) && v.price >= 0 ? (v.price / 100).toFixed(2) : ''
    const values: ProductValues = {
      platform: 'Shopify', shopName: new URL(url).hostname, supplierName: text(p.vendor),
      platformProductId: id(p.id), platformSkuId: variantId, sourceUrl: url, collectedAt: at,
      name: text(p.title), skuCode: text(v.sku), barcode: text(v.barcode), categoryPath: text(p.type),
      colorName: option(color), sizeCode: option(size), sizeName: option(size),
      sourceMainImageUrl: imageUrl(p.featured_image, url) || images[0] || '',
      sourceColorImageUrl: imageUrl(v.featured_image, url), sourceDetailImageUrls: images.slice(1, 21).join('\n'),
      observedPrice: price, observedCurrency: /^[A-Z]{3}$/.test(currency) ? currency : '', observedAt: price ? at : ''
    }
    return { id: variantId, original: { ...values }, values, availability: availability(v.available), evidence: `product.variants[${index}]` }
  })
}
export function parseJsonLd(products: Json[], url: string, at: string): CollectedSku[] {
  const group = products.find(p => p['@type'] === 'ProductGroup' && Array.isArray(p.hasVariant))
  const variants = group ? (group.hasVariant as unknown[]).map(record) : products.filter(p => p['@type'] !== 'ProductGroup')
  const rows: CollectedSku[] = []
  for (const [index, product] of variants.entries()) {
    const offers = Array.isArray(product.offers) ? product.offers.map(record) : [record(product.offers)]
    // 聚合价格不代表单个SKU价格。只有明确的Product规格生成一行，不组合颜色和尺码数组。
    const offer = offers.find(o => !Array.isArray(o['@type']) && o['@type'] !== 'AggregateOffer') ?? {}
    const skuId = id(product.sku) || id(product.productID)
    if (!skuId) continue
    const images = Array.isArray(product.image) ? product.image : [product.image]
    const jd = /(^|\.)jd\.com$/.test(new URL(url).hostname)
    const values: ProductValues = {
      platform: jd ? 'JD' : new URL(url).hostname, shopName: text(record(offer.seller).name),
      platformProductId: id(group?.productGroupID) || id(product.productID), platformSkuId: skuId,
      sourceUrl: url, collectedAt: at, name: text(product.name) || text(group?.name),
      skuCode: id(product.sku), brand: text(record(product.brand).name) || text(product.brand) || text(record(group?.brand).name),
      colorName: text(product.color), sizeCode: text(product.size) || text(record(product.size).name),
      material: text(product.material), categoryPath: text(product.category),
      sourceMainImageUrl: imageUrl(images[0], url), sourceDetailImageUrls: images.slice(1, 21).map(i => imageUrl(i, url)).filter(Boolean).join('\n'),
      observedPrice: amount(offer.price), observedCurrency: /^[A-Z]{3}$/.test(text(offer.priceCurrency)) ? text(offer.priceCurrency) : '',
      observedAt: amount(offer.price) ? at : '', jdItemId: jd ? new URL(url).pathname.match(/\/(\d+)\.html/)?.[1] ?? '' : '', jdUrl: jd ? url : ''
    }
    rows.push({ id: skuId, original: { ...values }, values, availability: availability(offer.availability), evidence: `JSON-LD.Product[${index}]` })
  }
  return rows.filter((row, index) => rows.findIndex(r => r.id === row.id) === index)
}
export async function collectProduct(input: string, signal: AbortSignal, read: PublicReader = readPublicPage): Promise<{ source: CollectionSource; skus: CollectedSku[] }> {
  const url = normalizeProductUrl(input)
  const page = await read(url, signal)
  const title = page.body.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1] ?? ''
  if (/captcha|验证码|安全验证|访问受限|access denied|robot check|^\s*(?:登录|login|sign in)/i.test(title)
    || /<form[^>]*(?:captcha|challenge-form)/i.test(page.body)) throw new CollectionError('ACCESS_REQUIRED', '来源需要登录或验证码，请用户处理；未尝试绕过')
  const at = new Date().toISOString()
  const products = jsonLdProducts(page.body)
  let skus: CollectedSku[]; let adapter = 'json-ld-v1'; let evidenceUrl = page.url; let raw = page.body
  const productPath = new URL(page.url).pathname.match(/^(.*\/products\/[^/]+)\/?$/)
  if (productPath && /(?:cdn\.shopify\.com|Shopify\.shop|Shopify\.theme)/.test(page.body)) {
    evidenceUrl = new URL(`${productPath[1]}.js`, page.url).href
    const json = await read(evidenceUrl, signal)
    raw = json.body; adapter = 'shopify-ajax-v1'
    let payload: unknown
    try { payload = JSON.parse(json.body) } catch { throw new CollectionError('STRUCTURE_CHANGED', '商品公开接口未返回有效JSON') }
    const currency = page.body.match(/Shopify\.currency\s*=\s*\{\s*["']active["']\s*:\s*["']([A-Z]{3})["']/)?.[1]
      ?? text(record(products[0]?.offers).priceCurrency)
    skus = parseShopify(payload, page.url, at, currency)
  } else skus = parseJsonLd(products, page.url, at)
  signal.throwIfAborted()
  if (!skus.length) throw new CollectionError('NO_VERIFIED_SKU', '页面未提供可确认的商品SKU；可能需要登录、动态页面或人工补充，未编造规格')
  return { source: { originalUrl: input, url: page.url, collectedAt: at, adapter, evidenceUrl,
    sha256: createHash('sha256').update(raw).digest('hex'), dataKind: 'public_observation' }, skus }
}
