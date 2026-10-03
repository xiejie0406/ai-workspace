import { build } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'

await build({
  configFile: false, plugins: [react(), tailwindcss()],
  // 默认仍测生产库；仅定向生命周期检查显式启用 React 开发版的 Effect 重放。
  define: { 'process.env.NODE_ENV': JSON.stringify(process.env.AIPICTURE_TEST_REACT_MODE === 'development' ? 'development' : 'production') },
  resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
  build: {
    outDir: '.test-dist', emptyOutDir: true, sourcemap: false,
    lib: { entry: fileURLToPath(new URL('./entry.ts', import.meta.url)), formats: ['es'], fileName: () => 'entry.js' },
    cssCodeSplit: false,
  },
})
const productionBundle = await readFile('dist/aipicture-workbench.js', 'utf8')
if (/\bprocess\.env\b/.test(productionBundle)) throw new Error('浏览器产物仍依赖 Node process.env')
const result = spawnSync(process.execPath, ['--test', ...process.argv.slice(2), 'tests/lifecycle.test.mjs'], { stdio: 'inherit' })
process.exitCode = result.status ?? 1
