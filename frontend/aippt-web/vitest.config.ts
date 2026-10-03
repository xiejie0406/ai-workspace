import path from 'node:path'
// 复用工作区已有技术检查器，不修改原 React 锁文件或新增运行时依赖。
export default {
  resolve: { alias: { '@': path.resolve(import.meta.dirname, 'src'), vitest: path.resolve(import.meta.dirname, '../admin-web/node_modules/vitest') } },
  esbuild: { jsx: 'automatic', jsxImportSource: 'react' },
  test: { environment: 'jsdom', include: ['tests/unit/**/*.spec.ts', 'tests/unit/**/*.spec.tsx'], maxWorkers: 1, fileParallelism: false },
}
