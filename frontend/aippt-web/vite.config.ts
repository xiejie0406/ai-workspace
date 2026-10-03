import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  base: '/aippt-web/',
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    // 端口统一使用 39xxx 段，避开 Vite 默认的 5173 等常见端口
    host: '127.0.0.1',
    port: 39173,
    strictPort: true,
    // 布局与主题定义位于仓库根的 contracts/aippt/catalog，在 frontend 之外，需显式放行
    fs: { allow: ['../..'] },
    // 走代理而非直连，前端代码里所有请求都用同源相对路径，避免 CORS 与环境变量分叉
    proxy: {
      '/dev-api': {
        target: process.env.ADMIN_DEV_BACKEND_URL || 'http://127.0.0.1:8081',
        changeOrigin: true,
        rewrite: (value) => value.replace(/^\/dev-api/, ''),
      },
    },
  },
})
