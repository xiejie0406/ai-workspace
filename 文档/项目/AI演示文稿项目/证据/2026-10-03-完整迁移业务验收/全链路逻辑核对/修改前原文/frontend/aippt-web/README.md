# AI 演示文稿原 React 前端

迁移源：`参考项目/ai-ppt-generator-main/frontend`。保留React19、React Router8、TanStack Query5、Zustand5、Tailwind4、Recharts3、TypeScript与Vite，以及原页面、编辑器、放映与纯TS几何算法。业务数据由若依Java/MySQL中心负责；Python保留生成、解析和PPTX算法。

唯一迁移方案：[完整迁移设计](../../文档/项目/AI演示文稿项目/完整迁移设计.md)第2节。保留`/projects`、`/create`、`/projects/:projectId`，按同源`/aippt-web/`基路径接入平台授权菜单；`/login`改平台登录引导。平台身份、API响应、SSE、版本/幂等和私有文件适配集中在原封装，不能另建Vue业务页或新的用户/JWT。

原脚本为`npm run build`（类型检查与Vite构建）、`npm run lint`、`npm run dev`、`npm run preview`。实际运行/代理/端口以核定环境和当前配置为准，dev/preview不作为业务验收通过；不据默认端口或文件存在宣布真实联机。

原五份几何、预设、氛围及树归一化断言通过 `npm run check:original` 离线执行，读取唯一的 `contracts/aippt/catalog`；没有安装另一份 `tsx` 或复制目录数据。定向单元检查复用工作区已有检查器：`node ../admin-web/node_modules/vitest/vitest.mjs run --config vitest.config.ts`。原锁文件版本保持不变，依赖按 `npm ci --ignore-scripts` 安装到本工程；字体包是原网页依赖，不等同于 Worker 的 PPTX 度量字体已准备。

开发 API 默认 `/dev-api`，生产默认 `/prod-api`。`VITE_APP_BASE_API` 只接受同源相对前缀；开发代理沿用 `ADMIN_DEV_BACKEND_URL`，没有设置时指向 `http://127.0.0.1:8081`。本地界面夹具不会启动这个中心或任何数据库。构建后的 `dist/` 由管理端现有同步脚本送到同源 `/aippt-web/`，生产发布仍需对应环境授权。

身份从平台 `Admin-Token` Cookie 与 `/getInfo` 恢复；没有会话时跳到平台 `/login?redirect=<安全的React相对路径>`。正常深链可保留 query/hash，凭据参数、外域、控制字符和编码反斜线会拒绝。原 `/login` 页面仅作平台引导，没有独立注册或长期本地 token。功能动作读取平台权限；私有图片与下载经同一 API 鉴权获取 Blob。

项目材料和参数从原页头或卡片菜单进入；失败创建保留项目及已成功材料。未知保存回执重放相同正文与幂等键，版本冲突需核对后重新应用实际修改或明确放弃。既有正式 1–40 页读取原样显示，未改页数不提交；新的生成目标仍为 5–20。主题即时预览并在320ms后串行保存，页编辑、主题以及离开保护复用现有保存桥接。样例弹层位于主题面板，质量结论与异步导出由中心返回，夹具下载不能证明真实 PPTX 回读或 Office 编辑通过。

来源差异清单、实际检查与缺口统一回写[完整迁移实施记录](../../文档/项目/AI演示文稿项目/开发记录/完整迁移实施记录.md)。仅文档同步不证明当前源码已经实现全部设计。
