import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import commonjs from 'vite-plugin-commonjs';
import { CodeInspectorPlugin } from 'code-inspector-plugin';

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const isDev = mode === 'development';
  const centre = loadEnv(mode, process.cwd(), '').VITE_PAIFLOW_CENTRE_URL;
  const proxy = centre ? Object.fromEntries(
    ['^/paiflow(?:/|\\?|$)', '/captchaImage', '/logout'].map(path => [path, {
      target: centre, changeOrigin: true, ws: true,
    }])
  ) : {};

  return {
    cacheDir: process.env.PROJECT_VITE_CACHE_DIR || 'node_modules/.vite',
    envPrefix: ['CONSOLE_', 'VITE_'],
    build: {
      rollupOptions: {
        maxParallelFileOps: 1, // 限制并行文件操作数为1
      },
      commonjsOptions: {
        strictRequires: true, // 强制所有 CommonJS 模块都被严格处理
      },
    },
    plugins: [
      commonjs(),
      isDev &&
        CodeInspectorPlugin({
          bundler: 'vite',
          editor: 'cursor',
        }),
      react(),
    ].filter(Boolean),
    resolve: {
      alias: {
        '@': '/src',
      },
    },
    server: {
      port: 3000,
      strictPort: true,
      proxy,
    },
  };
});
