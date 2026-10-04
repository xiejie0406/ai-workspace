import { describe, expect, it, vi } from 'vitest'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { normalizeProductUrl, isPublicIpv4, CollectionError } from '../../src/main/collection/public-http'
import { collectProduct, parseShopify, parseJsonLd, jsonLdProducts } from '../../src/main/collection/extract-product'
import { CollectionSession, parseDraft } from '../../src/main/collection/collection-session'
import { productWorkbook, sourceManifest } from '../../src/main/collection/export-workbook'
import { COLLECTION_FIELDS, missingFields, validateValues } from '../../src/shared/contracts/collection'

const url = 'https://fixture.example.com/products/tee'
const at = '2026-09-26T00:00:00Z'
const fixture = { id: '001', title: '合成测试T恤', vendor: '合成供应商', options: [{ name: 'Color' }, { name: 'Size' }],
  variants: [{ id: '00011', sku: '00001', option1: '白', option2: 'S', price: 1234, available: true },
    { id: '00012', sku: '00002', option1: '黑', option2: 'M', price: 1500, available: false }], images: [] }
const result = () => ({ source: { originalUrl: url, url, evidenceUrl: `${url}.js`, collectedAt: at, adapter: 'synthetic-unit-fixture', sha256: 'a'.repeat(64), dataKind: 'public_observation' as const }, skus: parseShopify(fixture, url, at, 'USD') })
describe('商品采集：合成规则测试，不证明真实采集', () => {
  it('仅移除营销参数，保留SKU参数及前导零', () => {
    expect(normalizeProductUrl(`${url}?variant=001&utm_source=x#top`)).toBe(`${url}?variant=001`)
    for (const value of ['http://shop.example.com/a', 'https://user:pass@shop.example.com/a', 'https://127.0.0.1/a', 'https://[::1]/a', 'file:///tmp/a', 'https://shop.example.com:8080/a']) expect(() => normalizeProductUrl(value)).toThrow()
  })
  it('拒绝所有私网、链路本地、保留IPv4及IPv6映射', () => {
    for (const address of ['10.1.1.1', '127.0.0.1', '169.254.169.254', '172.31.1.1', '192.168.1.1', '100.64.1.1', '198.18.0.1', '203.0.113.1', '224.0.0.1', '::ffff:127.0.0.1']) expect(isPublicIpv4(address)).toBe(false)
    expect(isPublicIpv4('8.8.8.8')).toBe(true)
  })
  it('保留实际变体，不做颜色尺码组合、不编库存、未知字段留空', () => {
    const skus = parseShopify(fixture, url, at, 'USD')
    expect(skus.map(s => [s.values.platformSkuId, s.values.colorName, s.values.sizeCode])).toEqual([['00011','白','S'], ['00012','黑','M']])
    expect(skus[0].values.observedPrice).toBe('12.34')
    expect(skus[1].availability).toBe('out_of_stock')
    expect(skus[0].values.season).toBeUndefined()
    expect(JSON.stringify(skus)).not.toContain('quantity')
    expect(missingFields(skus[0].values)).toContain('季节')
  })
  it('不安全整数ID拒绝，不静默四舍五入', () => {
    expect(() => parseShopify({ ...fixture, variants: [{ id: 9007199254740992 }] }, url, at)).toThrow(/ID/)
  })
  it('JSON-LD聚合价格不作为SKU价格，没有标识不生成SKU', () => {
    const html = '<script type="application/ld+json">{"@graph":[{"@type":"Product","sku":"0001","name":"合成","offers":{"@type":"AggregateOffer","lowPrice":"1.00"}},{"@type":"Product","name":"无标识"}]}</script>'
    const rows = parseJsonLd(jsonLdProducts(html), url, at)
    expect(rows).toHaveLength(1); expect(rows[0].values.observedPrice).toBe('')
  })
  it('登录/验证码页面不继续读取商品接口', async () => {
    const read = vi.fn(async () => ({ url, body: '<title>安全验证</title><script src="https://cdn.shopify.com/x"></script>', contentType: 'text/html', status: 200 }))
    await expect(collectProduct(url, new AbortController().signal, read)).rejects.toThrow(/登录或验证码/)
    expect(read).toHaveBeenCalledTimes(1)
  })
  it('URL去重、失败保留、显式重试、跨来源真实SKU去重', async () => {
    let failure = true
    const collect = vi.fn(async (input: string) => { if (input.endsWith('bad') && failure) throw new CollectionError('TIMEOUT', '合成超时'); return result() })
    const session = new CollectionSession(collect, 0)
    session.start(`${url}\n${url}?utm_source=unit\n${url}-bad`)
    await session.finished()
    expect(session.snapshot().entries.map(e => e.state)).toEqual(['collected','failed'])
    expect(collect).toHaveBeenCalledTimes(2)
    failure = false; session.retry(); await session.finished()
    expect(session.snapshot().entries[1].skus).toHaveLength(0)
  })
  it('取消中止当前和排队；迟到结果不能覆盖重置后的新会话', async () => {
    const collect = vi.fn((_url: string, signal: AbortSignal) => new Promise<ReturnType<typeof result>>((_resolve, reject) => signal.addEventListener('abort', () => reject(new Error('cancel')), { once: true })))
    const session = new CollectionSession(collect, 0)
    session.start(`${url}\n${url}-next`); session.cancel(); await session.finished()
    expect(session.snapshot().entries.map(e => e.state)).toEqual(['cancelled','cancelled'])
    expect(collect).toHaveBeenCalledTimes(1)
    session.reset(); expect(session.snapshot().entries).toHaveLength(0)
  })
  it('草稿修正不覆盖原值；损坏载入保持现有数据', async () => {
    const session = new CollectionSession(async () => result(), 0)
    session.start(url); await session.finished()
    const e = session.snapshot().entries[0]
    session.update(e.id, e.skus[0].id, { ...e.skus[0].values, colorName: '人工修正' })
    expect(session.snapshot().entries[0].skus[0].original.colorName).toBe('白')
    expect(() => session.load({ schemaVersion: 'bad' })).toThrow()
    expect(session.snapshot().entries[0].skus[0].values.colorName).toBe('人工修正')
    expect(parseDraft(JSON.parse(JSON.stringify(session.snapshot())))).toEqual(session.snapshot())
  })
  it('字段白名单、格式和长度严格校验，未知属性不能伪造数量字段', () => {
    for (const fields of [{ quantity: '2' }, { observedPrice: '-1' }, { observedPrice: '1.001' }, { sourceUrl: 'javascript:alert(1)' }, { collectedAt: '2026-09-26' }, { observedCurrency: 'usd' }, { sizeSystem: 'XX' }]) expect(() => validateValues(fields)).toThrow()
    expect(validateValues({ observedPrice: '0', platformSkuId: '0001' }).platformSkuId).toBe('0001')
    expect(COLLECTION_FIELDS).toHaveLength(54)
  })
  it('XLSX标准ZIP文本单元格，来源清单记录修正和缺失，无价格库存写入字段', async () => {
    const session = new CollectionSession(async () => result(), 0); session.start(url); await session.finished()
    const draft = session.snapshot(); draft.entries[0].skus[0].values.name = '=HYPERLINK("evil")'
    const bytes = productWorkbook(draft)
    expect(bytes.subarray(0, 2).toString()).toBe('PK'); expect(bytes.toString()).toContain('t="inlineStr"'); expect(bytes.toString()).not.toContain('<f>')
    const source = JSON.stringify(sourceManifest(draft)); expect(source).toContain('unknown_no_quantity_exported'); expect(source).toContain('missingRequired')
    if (process.env.ADEN_COLLECTION_TEST_OUTPUT) {
      writeFileSync(join(process.env.ADEN_COLLECTION_TEST_OUTPUT, 'synthetic-export.xlsx'), bytes)
      writeFileSync(join(process.env.ADEN_COLLECTION_TEST_OUTPUT, 'synthetic-draft.json'), JSON.stringify(draft, null, 2))
    }
  })
})
