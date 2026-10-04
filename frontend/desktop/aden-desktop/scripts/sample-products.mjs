import { build } from 'esbuild'
import { pathToFileURL } from 'node:url'
import { resolve, join } from 'node:path'
import { mkdir, readFile, writeFile } from 'node:fs/promises'

const [inputFile, outputDirectory] = process.argv.slice(2)
if (!inputFile || !outputDirectory) throw new Error('用法：node scripts/sample-products.mjs 商品链接.txt 输出目录（仅真实公开采集）')
const output = resolve(outputDirectory)
await mkdir(output, { recursive: true })
const bundle = resolve('test-results/collection-runtime.mjs')
await mkdir(resolve('test-results'), { recursive: true })
await build({ stdin: { contents: 'export * from "./src/main/collection/collection-session"; export * from "./src/main/collection/export-workbook";', resolveDir: process.cwd() }, bundle: true, platform: 'node', format: 'esm', outfile: bundle })
const { CollectionSession, productWorkbook, sourceManifest } = await import(pathToFileURL(bundle).href)
const session = new CollectionSession()
session.start(await readFile(inputFile, 'utf8'))
const timer = setInterval(() => { const s = session.snapshot(); console.log(JSON.stringify({ finished: s.entries.filter(e => !['queued','reading'].includes(e.state)).length, total: s.entries.length, skus: s.entries.reduce((n,e) => n+e.skus.length,0) })) }, 10000)
try { await session.finished() } finally { clearInterval(timer) }
const snapshot = session.snapshot()
await writeFile(join(output, '真实采集草稿.json'), JSON.stringify(snapshot, null, 2))
await writeFile(join(output, '商品来源清单.json'), JSON.stringify(sourceManifest(snapshot), null, 2))
const skuCount = snapshot.entries.reduce((n,e) => n+e.skus.length,0)
if (skuCount) await writeFile(join(output, '商品资料-v2.xlsx'), productWorkbook(snapshot))
const summary = { completedAt: new Date().toISOString(), scope: '真实公网GET；CLI直接调用同一采集模块，不证明RuoYi登录和Electron IPC联机', products: snapshot.entries.filter(e => e.skus.length).length, skus: skuCount, failures: snapshot.entries.filter(e => e.state !== 'collected').map(e => ({url:e.url,state:e.state,message:e.message})) }
await writeFile(join(output, '采集结果.json'), JSON.stringify(summary, null, 2)); console.log(JSON.stringify(summary))
