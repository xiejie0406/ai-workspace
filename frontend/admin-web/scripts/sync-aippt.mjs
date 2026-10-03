import { copyFile, lstat, mkdir, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises'
import { resolve, dirname, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'

const adminRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const workspace = resolve(adminRoot, '../..')
const source = resolve(adminRoot, '../aippt-web/dist')
const target = resolve(adminRoot, 'public/aippt-web')
const recordRoot = resolve(workspace, 'output/aippt-react-distribution', new Date().toISOString().replace(/[:.]/g, '-'))
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const contained = (parent, path) => path.startsWith(parent + sep)

async function inventory(root) {
  const files = []
  async function visit(directory) {
    for (const name of await readdir(directory)) {
      const path = resolve(directory, name)
      if (!contained(root, path)) throw new Error('同步路径越出指定目录')
      const info = await lstat(path)
      if (info.isSymbolicLink()) throw new Error('同步目录含链接，停止覆盖')
      if (info.isDirectory()) await visit(path)
      else if (info.isFile()) {
        const bytes = await readFile(path)
        files.push({ path: relative(root, path).replaceAll('\\', '/'), bytes: bytes.length, sha256: hash(bytes) })
      } else throw new Error('同步目录含特殊文件')
    }
  }
  await visit(root)
  return files.sort((a, b) => a.path.localeCompare(b.path))
}

async function copyVerified(from, to, files) {
  for (const file of files) {
    const destination = resolve(to, file.path)
    if (!contained(to, destination)) throw new Error('复制路径越界')
    await mkdir(dirname(destination), { recursive: true })
    await copyFile(resolve(from, file.path), destination)
    if (hash(await readFile(destination)) !== file.sha256) throw new Error(`同步哈希不符：${file.path}`)
  }
}

if (await realpath(adminRoot) !== adminRoot || await realpath(source) !== source) throw new Error('工程路径含链接，停止同步')
if (!contained(resolve(workspace, 'frontend'), source) || target !== resolve(adminRoot, 'public/aippt-web')) throw new Error('同步范围不符')
const parent = dirname(target)
await mkdir(parent, { recursive: true })
if (await realpath(parent) !== parent) throw new Error('目标父目录含链接')
const files = await inventory(source)
if (!files.some(file => file.path === 'index.html') || !(await readFile(resolve(source, 'index.html'), 'utf8')).includes('/aippt-web/')) throw new Error('原 React 生产构建不存在或 base 不符')
await mkdir(recordRoot, { recursive: true })
const oldInfo = await lstat(target).catch(error => { if (error.code === 'ENOENT') return null; throw error })
let before = []
if (oldInfo) {
  if (!oldInfo.isDirectory() || oldInfo.isSymbolicLink() || await realpath(target) !== target) throw new Error('目标不是指定普通目录')
  before = await inventory(target)
  await copyVerified(target, resolve(recordRoot, 'before'), before)
  await writeFile(resolve(recordRoot, 'before-manifest.json'), JSON.stringify(before, null, 2))
  // 备份完成后再次核对删除范围，只清理本业务的固定同源产物目录。
  if (target !== resolve(adminRoot, 'public/aippt-web') || !contained(parent, target) || await realpath(target) !== target) throw new Error('最终删除范围不符')
  await rm(target, { recursive: true })
}
await mkdir(target, { recursive: true })
await copyVerified(source, target, files)
const copied = await inventory(target)
if (JSON.stringify(files) !== JSON.stringify(copied)) throw new Error('同步后文件清单不一致')
await writeFile(resolve(recordRoot, 'sync-result.json'), JSON.stringify({ source, target, before, files, status: 'copied_and_hash_verified', published: false }, null, 2))
console.log(`原 React 制品已同步至固定同源目录：${files.length} 文件；记录 ${recordRoot}；未发布`)
