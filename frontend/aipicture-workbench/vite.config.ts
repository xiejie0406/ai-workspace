import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // 库模式不会自动替换依赖内的 NODE_ENV；产物需直接在若依浏览器宿主运行。
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    lib: { entry: fileURLToPath(new URL('./src/embed.tsx', import.meta.url)), formats: ['es'], fileName: () => 'aipicture-workbench.js' },
    cssCodeSplit: false,
    sourcemap: false,
    rollupOptions: { output: { inlineDynamicImports: true } },
  },
})
