import { createServer } from 'vite'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// 使用原锁文件已有Vite执行原五份断言，不安装tsx、不启动监听服务。
const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const checks = ['flex-parity.mts', 'flex-normalize-parity.mts', 'ambient-parity.mts', 'flex-edit-check.mts', 'bullets-edit-check.mts']
const server = await createServer({
  root: projectRoot,
  configFile: false,
  resolve: { alias: { '@': resolve(projectRoot, 'src') } },
  server: { middlewareMode: true, watch: null, hmr: false },
  optimizeDeps: { noDiscovery: true, include: [] },
})
const results = []
try {
  for (const name of checks) {
    try {
      await server.ssrLoadModule('/scripts/' + name)
      results.push({ name, status: 'passed' })
    } catch (error) {
      results.push({ name, status: 'failed', error: error.message })
    }
  }
} finally { await server.close() }
console.log(JSON.stringify({ original_assertions: true, services_listened: false, results }))
if (results.some(result => result.status !== 'passed')) process.exitCode = 1
