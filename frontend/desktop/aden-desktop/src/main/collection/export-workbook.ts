import { COLLECTION_FIELDS, missingFields, type CollectionSnapshot } from '../../shared/contracts/collection'
import { PRODUCT_IMPORT_CONTRACT_SHA256 } from '../../shared/generated/product-import-contract'
import { corrections } from './collection-session'

const xml = (s: string): string => s.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
function column(n: number): string { let result = ''; for (n++; n; n = Math.floor((n - 1) / 26)) result = String.fromCharCode(65 + (n - 1) % 26) + result; return result }
function sheet(rows: string[][]): string {
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetData>'
    + rows.map((row, i) => `<row r="${i + 1}">${row.map((v, j) => `<c r="${column(j)}${i + 1}" t="inlineStr"><is><t xml:space="preserve">${xml(v)}</t></is></c>`).join('')}</row>`).join('') + '</sheetData></worksheet>'
}
function crc32(bytes: Buffer): number {
  let crc = 0xffffffff
  for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0) }
  return (crc ^ 0xffffffff) >>> 0
}
/** 无宏、无公式、无外链关系；标准ZIP存储模式，规模受草稿上限约束。 */
function zip(files: Record<string, string>): Buffer {
  const parts: Buffer[] = []; const directory: Buffer[] = []; let offset = 0
  for (const [path, text] of Object.entries(files)) {
    const name = Buffer.from(path); const data = Buffer.from(text); const crc = crc32(data)
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6)
    local.writeUInt16LE(33, 12); local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(name.length, 26)
    const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8)
    central.writeUInt16LE(33, 14); central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24); central.writeUInt16LE(name.length, 28); central.writeUInt32LE(offset, 42)
    parts.push(local, name, data); directory.push(central, name); offset += local.length + name.length + data.length
  }
  const dir = Buffer.concat(directory); const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50)
  end.writeUInt16LE(Object.keys(files).length, 8); end.writeUInt16LE(Object.keys(files).length, 10); end.writeUInt32LE(dir.length, 12); end.writeUInt32LE(offset, 16)
  return Buffer.concat([...parts, dir, end])
}
export function sourceManifest(draft: CollectionSnapshot): object {
  return { schemaVersion: 'aden-source-1', templateCode: 'fashion-product', templateVersion: '2.0', contractSha256: PRODUCT_IMPORT_CONTRACT_SHA256,
    exportedAt: new Date().toISOString(), inventory: 'unknown_no_quantity_exported', salesPrice: 'not_exported',
    entries: draft.entries.map(e => ({ url: e.url, state: e.state, message: e.message, source: e.source,
      skus: e.skus.map(s => ({ id: s.id, availability: s.availability, original: s.original, values: s.values, evidence: s.evidence,
        corrections: corrections(s.original, s.values), missingRequired: missingFields(s.values) })) })) }
}
export function productWorkbook(draft: CollectionSnapshot): Buffer {
  const rows = draft.entries.flatMap(e => e.skus.map(s => ({ e, s })))
  if (!rows.length) throw new TypeError('没有可导出的商品SKU')
  const products = [COLLECTION_FIELDS.map(f => f.label), ...rows.map(({ s }) => COLLECTION_FIELDS.map(f => s.values[f.key] ?? ''))]
  const sources = [['来源链接', '平台SKU ID', '采集时间', '提取方式', '可售状态（非库存）', '待补充必填项', '修正记录', '响应SHA256', '数据类型'],
    ...rows.map(({ e, s }) => [e.url, s.values.platformSkuId ?? '', e.source?.collectedAt ?? '', e.source?.adapter ?? '', s.availability,
      missingFields(s.values).join('、'), JSON.stringify(corrections(s.original, s.values)), e.source?.sha256 ?? '', '真实公开观察值，用户载入文件需自行核对来源'])]
  const ns = 'http://schemas.openxmlformats.org/package/2006/relationships'
  return zip({
    '[Content_Types].xml': '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
    '_rels/.rels': `<Relationships xmlns="${ns}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`,
    'xl/workbook.xml': '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="商品资料" sheetId="1" r:id="rId1"/><sheet name="来源与缺失" sheetId="2" r:id="rId2"/></sheets></workbook>',
    'xl/_rels/workbook.xml.rels': `<Relationships xmlns="${ns}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>`,
    'xl/worksheets/sheet1.xml': sheet(products), 'xl/worksheets/sheet2.xml': sheet(sources)
  })
}
