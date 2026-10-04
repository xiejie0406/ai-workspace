import { readFileSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'

const source = process.argv[2]
if (!source) throw new Error('请指定选品项目已交接的 product-import-v2.json 路径')
const bytes = readFileSync(source)
const contract = JSON.parse(bytes)
const fields = [...contract.baseFields, ...contract.extensionFields]
if (contract.templateCode !== 'fashion-product' || contract.templateVersion !== '2.0'
  || fields.length !== 54 || new Set(fields.map(f => f.key)).size !== 54
  || fields.filter(f => f.required).length !== 11 || contract.rules.inventory !== 'No inventory quantity in product import.') {
  throw new Error('选品契约版本、字段或数据边界与本消费者不兼容')
}
const output = resolve(import.meta.dirname, '../src/shared/generated/product-import-contract.ts')
writeFileSync(output, '// GENERATED FROM contracts/fashion/product-import-v2.json — DO NOT EDIT\n'
  + `export const PRODUCT_IMPORT_CONTRACT_SHA256 = '${createHash('sha256').update(bytes).digest('hex')}'\n`
  + `export const PRODUCT_IMPORT_CONTRACT = ${JSON.stringify(contract, null, 2)} as const\n`)
console.log(JSON.stringify({ fields: fields.length, sha256: createHash('sha256').update(bytes).digest('hex'), status: contract.status }))
