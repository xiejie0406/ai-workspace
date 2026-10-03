import { defineConfig, loadEnv } from 'vite'
import path from 'path'
import { stat } from 'node:fs/promises'
import createVitePlugins from './vite/plugins'

const baseUrl = process.env.ADMIN_DEV_BACKEND_URL || 'http://127.0.0.1:8081' // 若依平台后端
const interviewBaseUrl = baseUrl // RuoYi 唯一后端

// 原 React 以独立产物同源访问，开发时也保留其 BrowserRouter 深链。
const aipptHistoryFallback = {
  name: 'aippt-react-history-fallback',
  configureServer(server) {
    server.middlewares.use(async (request, response, next) => {
      const pathname = (request.url || '').split('?')[0]
      if (!pathname.startsWith('/aippt-web/')) return next()
      if (request.method === 'GET' && request.headers.accept?.includes('text/html')
          && /^\/aippt-web\/(?:projects(?:\/[^/.]+)?|create|login)?\/?$/.test(pathname)) {
        request.url = '/aippt-web/index.html'
      }
      // 防止 Vite 的全站 SPA 回退把丢失的 React 静态文件返回为管理端 HTML。
      const reactRoot = path.resolve(__dirname, 'public/aippt-web')
      try {
        const file = path.resolve(reactRoot, '.' + (request.url || '').split('?')[0].slice('/aippt-web'.length))
        if (!file.startsWith(reactRoot + path.sep) || !(await stat(file)).isFile()) {
          response.statusCode = 404
          response.end()
          return
        }
        next()
      } catch {
        response.statusCode = 404
        response.end()
      }
    })
  }
}

// https://vitejs.dev/config/
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd())
  const { VITE_APP_ENV } = env
  return {
    cacheDir: process.env.PROJECT_VITE_CACHE_DIR || 'node_modules/.vite',
    // 部署生产环境和开发环境下的URL。
    // 默认情况下，vite 会假设你的应用是被部署在一个域名的根路径上
    // 例如 https://www.ruoyi.vip/。如果应用被部署在一个子路径上，你就需要用这个选项指定这个子路径。例如，如果你的应用被部署在 https://www.ruoyi.vip/admin/，则设置 baseUrl 为 /admin/。
    base: VITE_APP_ENV === 'production' ? '/' : '/',
    plugins: [...createVitePlugins(env, command === 'build'), aipptHistoryFallback],
    resolve: {
      // https://cn.vitejs.dev/config/#resolve-alias
      alias: {
        // 设置路径
        '~': path.resolve(__dirname, './'),
        // 设置别名
        '@': path.resolve(__dirname, './src')
      },
      // https://cn.vitejs.dev/config/#resolve-extensions
      extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue']
    },
    // 打包配置
    build: {
      // https://vite.dev/config/build-options.html
      sourcemap: command === 'build' ? false : 'inline',
      outDir: 'dist',
      assetsDir: 'assets',
      chunkSizeWarningLimit: 2000,
      rollupOptions: {
        output: {
          chunkFileNames: 'static/js/[name]-[hash].js',
          entryFileNames: 'static/js/[name]-[hash].js',
          assetFileNames: 'static/[ext]/[name]-[hash].[ext]'
        }
      }
    },
    // vite 相关配置
    server: {
      port: 5173,
      host: true,
      open: false,
      proxy: {
        // https://cn.vitejs.dev/config/#server-proxy
        '/dev-api': {
          target: baseUrl,
          ws: true,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/dev-api/, '')
        },
        '/api/v1': {
          target: interviewBaseUrl,
          changeOrigin: true,
          secure: false
        },
         // springdoc proxy
         '^/v3/api-docs/(.*)': {
          target: baseUrl,
          changeOrigin: true,
        }
      }
    },
    css: {
      postcss: {
        plugins: [
          {
            postcssPlugin: 'internal:charset-removal',
            AtRule: {
              charset: (atRule) => {
                if (atRule.name === 'charset') {
                  atRule.remove()
                }
              }
            }
          }
        ]
      }
    }
  }
})
