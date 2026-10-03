import { copyFile, mkdir, readFile, stat } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'

// 独立 React 工具链产物复制到固定同源目录，不把 React 插件加进若依 Vite。
const adminRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const source = resolve(adminRoot, '../aipicture-workbench/dist/aipicture-workbench.js')
const target = resolve(adminRoot, 'public/aipicture/aipicture-workbench.js')
const information = await stat(source).catch(() => null)
if (!information?.isFile() || information.size === 0) {
  throw new Error('图片业务库尚未构建，请先在 frontend/aipicture-workbench 执行 npm run build')
}
await mkdir(dirname(target), { recursive: true })
await copyFile(source, target)
const hash = buffer => createHash('sha256').update(buffer).digest('hex')
const [sourceBytes, targetBytes] = await Promise.all([readFile(source), readFile(target)])
if (hash(sourceBytes) !== hash(targetBytes)) throw new Error('图片业务制品同步校验失败')
console.log(`图片业务制品同步完成，SHA256=${hash(targetBytes)}；${information.size} 字节`)
