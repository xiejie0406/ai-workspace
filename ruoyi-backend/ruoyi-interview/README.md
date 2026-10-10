# ruoyi-interview 迁移模块

> 文档类型：实现参考
> 文档状态：Draft
> owner / 责任边界：platform / interview module
> 更新时间：2026-10-05
> 适用阶段：5–8

`ruoyi-interview` 是 `ruoyi-backend` 的正式 Maven 业务子模块，由
`ruoyi-admin` 的唯一 `com.ruoyi.RuoYiApplication` 进程聚合加载。旧 `backend/` 仅是
migration compatibility source，不能作为正式启动入口。

本机验收登录恢复统一见[共用操作说明](../../文档/若依框架/多项目并行开发启动指南.md#本机验收登录恢复)。全部本机验收通道启用辅助能力；先复用有效会话，失效时仅用已授权账号凭据辅助填写当前验证码并正常登录。浏览器无法控制或账号凭据未获授权时单独报告所需条件；恢复登录后回到待验步骤，既有业务结果与证据保持原判定。

## 边界

- `domain`：纯 Java 业务规则；不依赖 Spring、MyBatis、JDBC 或 Provider SDK。
- `application`：用例编排、状态、幂等和端口；只依赖 domain 与 port。
- `infrastructure`：PostgreSQL、Provider、存储、加密和任务适配；不拥有认证。
- `controller`：REST、SSE/WebSocket 协议适配；主体来自 RuoYi `SecurityContext`。
- `configuration`：模块 bean 装配；不声明第二个 `SecurityFilterChain` 或登录入口。

## 模块迁移映射表

| 旧模块/文件 | 目标 ruoyi-interview package | 处理方式 | 依赖 | 状态 |
|---|---|---|---|---|
| `backend/interview-domain` | `domain` | 迁移（排除 identity） | 无框架依赖 | 已实现，待运行验证 |
| `backend/interview-application` | `application` | 迁移（排除 identity） | domain、port | 已实现，待运行验证 |
| REST Controller | `controller.rest` | 改造为 RuoYi 上下文/`AjaxResult` 兼容边界 | Web、Security、application | 代码已迁入，端点开关待启用验证 |
| SSE/流式协议 | `controller.InterviewStreamController` / application stream port | 保留 RuoYi Token 主体；不得创建第二登录态 | Web、Security、PostgreSQL | 统一入口已存在；durable SSE 仍明确未就绪 |
| WebSocket | `controller.websocket` | 握手只接受 RuoYi `SecurityContext`，ticket 仅作业务连接票据 | WebSocket、Security、application voice | 迁移 handler/wiring 已接入，真实握手与 PG/Provider 待验证 |
| PostgreSQL Repository | `infrastructure.persistence` | JDBC 适配到独立 PG DataSource/事务管理器 | PostgreSQL、JDBC | 已迁入；新增 profile 业务基线，旧全量 schema/回填未完成 |
| Provider/ASR/TTS | `infrastructure.provider` | 通过 application port，缺配置显式失败 | Provider SDK/HTTP | 已迁入，真实调用未授权/未验证 |
| Job/Outbox/Worker | `infrastructure.job` / application platform | RuoYi Quartz/调度容器；不启动第二 Worker | PostgreSQL、Quartz | 占位适配已迁入，调度接入未完成 |
| 独立 Boot main | 不迁移 | 停用保护；仅兼容源 | 第二启动入口 | 已增加启动保护 |
| 旧 `SecurityConfiguration` | 不迁移 | 停用候选 | 第二安全入口 | `backend/` 已删除；旧 main 默认拒绝启动 |
| `IdentityController` / `AIC_SESSION` | 不迁移 | 停用 | 第二账号/Session 体系 | 未迁入 |

## 身份、权限与数据所有权

- 当前主体只从 `SecurityUtils.getLoginUser()` / `SecurityContext` 读取。
- AI Controller 不接受前端 `user_id`、`role`、`tenant` 作为权威值。
- 权限事实属于 MySQL `sys_menu.perms`，Controller 使用 `@PreAuthorize`；业务代码不复制角色/权限。
- PostgreSQL 只保存 AI 业务事实，用户引用统一为 `ruoyi_user_id BIGINT`；不保存密码、Token、Cookie 或第二套用户主表。
- MySQL 只保存 RuoYi 用户、角色、菜单、部门、权限及系统数据。

## 当前限制

`mvn -DskipTests package` 和模块编译已通过，但这不等同于业务迁移完成。当前仍需完成：PG 全量 Flyway/`ruoyi_user_id` 数据迁移、全部 REST/SSE/WebSocket 业务验证、执行并绑定权限菜单、Provider/Quartz 接入、端到端前端验收和旧身份 0 写入证据。
