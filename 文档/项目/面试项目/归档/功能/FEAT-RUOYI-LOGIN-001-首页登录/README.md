# AI Interview Coach 主页 RuoYi 登录迁移

> 设计引用整理（2026-10-01）：本文保留原历史事实与结论。被删除旧设计的原名、章节和版本属于当时上下文；迁移后的设计链接只用于查阅当前统一设计，不替代当时的验收基线。原文及对应见[统一记录](../../../开发记录/2026-10-01-设计文档统一与清理.md)。

> 文档类型：Feature 控制页
> 文档状态：Draft
> owner / 责任边界：platform / identity / frontend；用户验收由业务 owner 负责
> 创建时间：2026-08-23
> 更新时间：2026-08-23
> Feature ID：FEAT-RUOYI-LOGIN-001
> 风险等级：L3
> 当前阶段：7 开发实现
> 阶段状态：Blocked
> 关联：[`功能规格.md`](功能规格.md)、[`技术设计.md`](../../../架构/面试项目完整设计.md#security)、[`任务清单.md`](任务清单.md)、[`验证记录.md`](验证记录.md)、[`../../architecture/若依平台收敛设计.md`](../../../架构/面试项目完整设计.md#data)

## 目标与非目标

- 目标：主页 `frontend` 唯一使用 RuoYi `/login`、`/captchaImage`、`/getInfo`、`/getRouters`、`/logout`；刷新恢复 Token；401 清理并回到登录；403 保持权限拒绝。
- 非目标：不新增账号系统、Session、Cookie、TokenService、SecurityFilterChain、数据库迁移或实际发布；不删除历史旧入口。

## 产物与状态

| 产物 | 状态 | 说明 |
|---|---|---|
| Feature Spec | Draft | 现状、冲突、REQ/AC 与流程 |
| 技术设计 | Draft | 前端契约、存储、代理和恢复设计 |
| 任务 | Draft | 本轮实现边界与验证命令 |
| 验证 | Draft / NotRun | 代码审查与实际证据待补 |
| 用户验收 | Draft / Blocked | [`验收记录.md`](验收记录.md)；需用户/业务 owner 逐场景确认 |
| HTML 验收报告 | Draft / Blocked | [`验证报告.html`](验证报告.html)；含截图、功能点和 Network 摘要 |

## 当前控制信息

- 已授权：本轮本地源码/配置修改，以及用户明确列出的定向构建、后端启动和浏览器验证。
- 未授权：数据库写入、删除/移动历史文件、发布、回滚、`git add/commit/push`。
- 下一阶段门：完成实现后进入阶段 8，前提是源码无旧认证调用且静态检查可定位；浏览器真实登录、401/403 和退出仍需实际证据。
- 当前阻塞：RuoYi 运行验证需要 `RUOYI_DB_PASSWORD`，当前 shell 未提供；真实验证码、登录、刷新恢复、401/403 和退出的浏览器证据尚未取得。
- 发布事实：NotReleased。
