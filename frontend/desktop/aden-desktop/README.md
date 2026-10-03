# Aden Desktop

源码版本 0.3.0。需求与页面设计唯一进入[统一设计文档](../../../文档/项目/智能体桌面端项目/产品/统一设计文档.md)。当前实现提供默认业务范围登录、统计工作台、采集任务、商品结果、导出记录和简洁设置；Chrome 七区先提取预览、统一确认保存。手动新增商品、商品删除、回收站和其他任务入口已停止，旧数据与历史证据保留。

用户自行搜索、登录并打开商品页；程序不自动搜索、翻页、滚动或切规格。源码、离线测试、实际安装版和真实京东验证分别记录；不得从构建或入口存在推断业务已经通过。

## 技术基线

- Vue 3 + TypeScript + Vite：与仓库现有前端保持同一技术体系。
- Electron：只承载窗口、托盘和后续受限本机 IPC；渲染进程启用 sandbox、
  `contextIsolation`，并关闭 `nodeIntegration`。
- RuoYi：通过版本化 API 提供身份、权限、任务、回执和只读投影；Token 仅驻留 main 内存。
- Python Runner：作为独立低权限进程接入；不会把任意 shell、路径或脚本能力暴露给页面。

## 本地技术检查与安装版

在已有依赖环境运行 `npm test`、`npm run typecheck`、`npm run build`；这些是源码检查。用户日常启动和页面验收只使用 `C:/Users/admin/AppData/Local/Programs/Aden Local Test/Aden Local Test.exe`，沿用原资料目录及原数据库。启动方法见[四项目指南](../../../文档/若依框架/四项目并行开发启动指南.md)。

`start-installed-local-test.ps1` 已改为仅打开这个安装版，不再启动合成服务、建库、创建资料或结束数据库。旧“含服务”快捷方式的名称保留，入口行为也为安装版启动，中心服务单独恢复。源 Electron 窗口不能代替用户运行版本。

修改桌面后先退出已核实目标窗口，执行 `npm run sync:local`。默认构建并同步 `resources/app/out`，保留带时间戳的旧产物；同一次已验证且源码未变的构建才可 `-- -SkipBuild`。修改插件或 Native Host 时先构建 Host，再执行独立组件同步：

```powershell
pwsh -NoProfile -File scripts/build-collector-host.ps1
pwsh -NoProfile -File scripts/sync-local-collector.ps1
```

组件同步保存旧 collector 目录、核对新文件哈希，保留本安装注册清单，不改系统注册项，不覆盖另一安装或结束其进程。实际 Chrome 需加载/重新加载目标插件，并核对其 Host 注册；冲突时明确受阻。仅同步桌面 out 不能证明插件或后台已更新。

`run-synthetic-e2e.ps1` 的历史名称保留，现为已有安装版的定向只读检查入口，不建数据库、不登录合成账号、不自动写商品：

```powershell
pwsh -NoProfile -File scripts/run-synthetic-e2e.ps1 -EvidenceDirectory <已保存手工验收清单.md的本轮证据绝对目录>
```

入口检查真实安装版登录/设置在 1024/1440 下的画面及本机目录复制，保留原 profile；数据库、真实账号、保存、导出和实际 Chrome 链路须另满足已有验收前提。完整十八项清单与实际结果在[当前功能记录](../../../文档/项目/智能体桌面端项目/功能/FEAT-ADEN-003-京东搜索商品采集/实现与验证记录.md)。旧脚本、快捷方式和本文修改前原文已保存于工作区忽略的实施备份，不使用 Git HEAD 覆盖未提交内容。

安装版只读取 `resources/app/local-test.json`，当前中心必须为 `http://127.0.0.1:18093`。release 要求 HTTPS；两渠道均拒绝公网 HTTP、凭据、query、fragment。renderer CSP 禁止业务网络，REST/SSE 由 main 向冻结 Origin 发起，preload 不暴露任意 URL、header、Token 或 IPC channel。

## Windows 安装包

已有独立 Python 构建环境与锁定 PyInstaller 时运行 `npm run pack:local`。该入口先构建 Host、桌面，再生成 `dist/local-test/Aden-Local-Test-0.3.0-Setup.exe`。安装器更新会覆盖安装目录，使用前核对版本，不用旧安装器覆盖最新同步文件。依赖新增、全局安装及发布按已有授权执行。

可在同一 Host 构建环境中运行 `npm run pack:release-candidate` 构建使用 ASAR 的未签名发布形态候选包。
它要求明确的 HTTPS `ADEN_API_BASE_URL` 才能启动；签名、更新源和真实服务验收仍是后续发布条件。
两种安装包均包含 Electron UI、独立 `AdenCollectorHost.exe`、管道代理及注册／卸载脚本，位于
`resources\collector`。**安装端不需要另外安装 Python 或 PyInstaller。** 安装器在当前用户范围注册 Host；
注册冲突会明确提示，不覆盖另一安装的注册。包内没有 RuoYi、MySQL、Redis 或完整的合成任务 Python Runner。

## 启用商品采集

1. 后端通过 Aden 专用迁移入口完成 V5 迁移，加载 `ruoyi-backend/sql/aden-collection-permissions.sql` 的权限定义，
   按实际角色授予 `aden:collection:read/capture/create/edit/delete/restore/export` 中需要的权限，并具备对应 Workspace 成员资格。
   权限 SQL 不自动给正式角色授权；普通应用启动只校验 Schema，不自动迁移。
2. 配置后端采集功能与受控存储目录。附件保存到服务端数据目录，不能放进桌面安装目录：

   ```yaml
   aden:
     collection:
       enabled: true
       storage-root: D:/AdenData/collection
       workspace-quota-bytes: 1073741824
   ```

   采集按已授权原通道配置开启。`fixture-origin` 只允许在明确批准的合成页面技术验证中填写一个精确的
   `http://127.0.0.1:端口` 或 `http://localhost:端口`；正式环境留空。
3. 安装 Aden 并登录、选择工作空间。当前 Chrome 扩展使用开发通道，需要用户在 Chrome 的扩展管理页启用开发者模式，
   “加载已解压的扩展程序”选择相邻的 `frontend/desktop/aden-collector-extension` 目录。
   安装 Aden 不会静默安装扩展；开发 ID 和 Host 信息见[扩展说明](../aden-collector-extension/README.md)。
4. 用户打开支持的商品详情页，点击扩展工具栏授权当前页，在面板连接 Aden 并完成首次配对，之后点击采集。
   商品库只把服务端已确认的内容显示为已保存，图片失败或未加载内容会显示缺失；不会自动绕过验证或限制。

真实京东页面、来源允许范围和实际图片下载能力尚未完成验收。安装版与合成页面的检查不能代替真实平台试点。

## 云端候选构建

云端候选包使用仓库的 `.github/workflows/aden-desktop-windows-package.yml`：在相关变更进入 GitHub 后，
从 Actions 手动运行 `Aden desktop Windows package`，下载该次运行的安装器与 `SHA256.txt`。
工作流使用 Windows 2022、Node.js 24、Python 3.12，在 runner 临时目录创建虚拟环境，安装
`scripts/requirements-collector-host.txt`，通过 `GITHUB_ENV` 设置 `ADEN_HOST_BUILD_PYTHON` 后再打包。
包检查要求同时存在 ASAR、Host 可执行文件及配套脚本；文件存在检查不等于安装与协议运行验收。
工作流仅 `workflow_dispatch` 触发，只上传保留 7 天的 artifact，不签名、不部署、不创建 Release。
本次修改没有运行线上 CI，其成功状态仍需实际运行记录确认。

CORE 合成任务与商品采集库是独立能力；真实 Provider、微信／ERP 连接器、UI Automation、
签名、发布和真实业务数据验收不能从当前采集库实现推断完成。

## 2026-10-02 本地测试版登录预填恢复

用户要求恢复账号密码预填，并确认停用隔离数据库。local-test编译渠道预填原库已有的admin及其现有本地测试密码，密码初始遮罩、可修改且不自动提交；正式渠道保持空表单，不携带该预设。预填本身不创建平台账号、工作空间或权限，不修改原库账号密码。中心服务使用原ry-vue数据库，安装版连接地址仍为http://127.0.0.1:18093。
