import { resolve } from 'node:path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'electron-vite'

export default defineConfig({
  main: {
    define: {
      __ADEN_PACKAGE_CHANNEL__: JSON.stringify(process.env.ADEN_PACKAGE_CHANNEL === 'local-test' ? 'local-test' : 'release')
    },
    build: {
      rollupOptions: {
        input: resolve(__dirname, 'src/main/index.ts')
      }
    }
  },
  preload: {
    build: {
      rollupOptions: {
        input: resolve(__dirname, 'src/preload/index.ts'),
        output: {
          format: 'cjs',
          entryFileNames: 'index.cjs'
        }
      }
    }
  },
  renderer: {
    define: {
      // 仅本地测试安装版预填原库已有账号，正式渠道保持空表单。
      __ADEN_TEST_LOGIN__: JSON.stringify(process.env.ADEN_PACKAGE_CHANNEL === 'local-test'
        ? { username: 'admin', password: 'admin123' } : null)
    },
    root: resolve(__dirname, 'src/renderer'),
    plugins: [vue()],
    resolve: {
      alias: {
        '@renderer': resolve(__dirname, 'src/renderer/src')
      }
    }
  }
})
