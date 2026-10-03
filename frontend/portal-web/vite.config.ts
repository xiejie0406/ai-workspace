import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 独立联调可覆盖代理目标；默认仍使用本地若依主服务。
const backendUrl = process.env.INTERVIEW_DEV_BACKEND_URL || 'http://127.0.0.1:8081'

export default defineConfig({
  cacheDir: process.env.PROJECT_VITE_CACHE_DIR || 'node_modules/.vite',
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: backendUrl,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api(?!\/v1)/, ''),
      },
      '/ws': {
        target: backendUrl.replace(/^http/, 'ws'),
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
