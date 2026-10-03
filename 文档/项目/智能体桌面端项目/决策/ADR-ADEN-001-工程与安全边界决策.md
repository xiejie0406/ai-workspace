# ADR-ADEN-001 工程与安全边界决策

> 文档类型：架构决策记录  
> 版本：1.0.0；文档状态：Approved  
> Feature：FEAT-ADEN-001；风险等级：L2（仅合成 CORE）  
> owner：用户负责产品范围、关键技术边界与执行授权；Codex 负责按本决策实施和验证  
> 决策日期：2026-09-12；创建 / 更新：2026-09-12  
> 批准依据：2026-09-12 用户批准十阶段执行包并明确要求按十个阶段进入编码实现  
> 上游：[十阶段实施任务清单](../产品/统一设计文档.md#implementation)、[功能规格](../产品/统一设计文档.md#core-rules)、[总技术设计](../产品/统一设计文档.md#backend)<br>
> 适用范围：`contracts/aden/`、`ruoyi-backend/ruoyi-aden/`、`frontend/desktop/aden-desktop/`、`python/aden-runner/`、`python/aden-agent-runtime/`
> 状态边界：本文记录已批准的稳定取舍，不维护任务进度或验证结论；实施状态以任务清单为准，实际命令与结果以 Feature 验证记录为准

## 1. 决策摘要

FEAT-ADEN-001 采用 RuoYi / MySQL 单一控制面、四个实现单元加一个机器契约目录。数据库迁移只能由 Aden 专属一次性 Flyway CLI 显式执行；普通应用启动不迁移。Operator、Runner 与未来 Agent Worker 使用互不兼容的身份和安全链。由于现有 RuoYi `ResourcesConfig` 对 `/**` 注册了宽泛 CORS，Aden API 额外实行 fail-closed Origin Guard，当前不允许浏览器跨源调用。Electron 的所有网络和 Token 都留在 main，Python Agent 默认 Provider disabled，并以自有 `ModelAgentPort` 后的 `FakeModelPort` 完成本期离线验证。

| 决策 ID | 状态 | 决定 |
| --- | --- | --- |
| ADEN-ADR-001 | Accepted | RuoYi 是唯一中心控制面；源码按四个实现单元和一个契约目录组织 |
| ADEN-ADR-002 | Accepted | 使用 Aden 专属 Flyway CLI；普通 `ruoyi-admin` 启动只校验 Schema，不执行 migration |
| ADEN-ADR-003 | Accepted | Operator、Runner、Agent Worker 身份隔离；Aden API 默认拒绝所有浏览器 Origin 和 CORS preflight |
| ADEN-ADR-004 | Accepted | Electron main 独占网络、Token 和重定向裁决；preload / renderer 不获得通用网络能力 |
| ADEN-ADR-005 | Accepted | Python Agent 保持 Provider disabled；本期只实现自有端口后的确定性 `FakeModelPort` |

## 2. 上下文与约束

当前工作区已有随 `ruoyi-admin` 运行的 `ruoyi-aden` Maven 骨架和一个 `aden-desktop` Electron 骨架，但 `contracts/aden/`、`python/aden-runner/`、`python/aden-agent-runtime/`、Aden 业务表和跨进程 API 尚待实现。当前 Feature 只交付合成 CORE：不使用真实账号、真实业务数据、模型 Provider、Windows UIA、浏览器控制、Shell、任意文件访问或外部发送。

现有 RuoYi 平台已经拥有账号、JWT / Redis 登录事实、角色和功能权限；项目规范同时要求 RuoYi 成为智能体桌面端唯一中心控制面。另建 Python 或 Electron 业务后端会产生任务、权限、审批、事件和审计的双重真相，无法在失败恢复时确定谁拥有最终状态。

数据库方面，RuoYi 基线不是由 Aden 创建的空库。Aden 需要能在全新隔离库和已有 RuoYi Schema 上显式、可审计地演进，又不能让普通应用启动静默修改数据库或误扫其他项目的 migration。

安全方面，当前 `ruoyi-backend/ruoyi-framework/.../ResourcesConfig.java` 通过 `addAllowedOriginPattern("*")`、任意 header / method 和 `/**` 注册全局 `CorsFilter`。当前 Electron main 和 Python 进程不需要浏览器 CORS；若直接继承该全局行为，任意网页 Origin 可能向 `/api/v1/aden/**` 发起带用户 Token 的跨源请求或探测认证边界。因此 Aden 必须在不改变共享 RuoYi 行为的前提下建立更窄且优先执行的拒绝门。

## 3. ADEN-ADR-001：唯一控制面与工程数量

### 3.1 决定

RuoYi / `ruoyi-admin` 是唯一服务端控制面，MySQL `aden_*` 表族是当前业务事实源。Task、Workspace、Runner、Event、Outbox、Inbox 和 Audit 的状态、权限、幂等、租约、fencing 与最终裁决都由 `ruoyi-aden` 在 MySQL 事务内持有。Electron、Runner 和 Agent Runtime 只消费契约、提交意图或候选结果，不拥有第二套业务终态。

源码固定为四个实现单元与一个非运行时机器契约目录：

| 落点 | 类型 | 职责边界 |
| --- | --- | --- |
| `ruoyi-backend/ruoyi-aden/` | 既有 Java Maven 模块 | Aden 领域、应用、REST / SSE、身份适配和 MySQL 持久化；由现有 `ruoyi-admin` 承载 |
| `frontend/desktop/aden-desktop/` | 既有 Electron 工程 | main / preload / renderer 三个信任层；用户操作与只读投影 |
| `python/aden-runner/` | 新 Python 工程 | 当前仅无权限 Runner simulator；未来高权限 Runner 需另开 L3 Feature |
| `python/aden-agent-runtime/` | 新 Python 工程 | 当前仅离线、Provider-disabled 的候选推理骨架 |
| `contracts/aden/` | 非运行时契约目录 | OpenAPI 3.1、JSON Schema 2020-12、examples 与三端生成 / 校验入口 |

不新增 Java Maven 子模块，不把 `contracts/aden/` 计为服务，不把 Python 的包边界写成多个部署单元。两个 Python 工程可以复用纯契约生成方法，但不得合并发行物、凭据或运行权限。

### 3.2 后果

- 任务恢复、权限、审计和最终状态只有一个权威来源，跨进程失败通过租约、幂等和服务端快照收敛。
- Java 模块内部需要保持 `api / application / domain / infrastructure / configuration` 依赖方向，避免单模块退化为无边界的大包。
- Python 和 Electron 不能为了快速联调直连数据库、复制状态机或自行把候选结果晋升为业务事实。
- 单个 `ruoyi-admin` 是本期验证拓扑；多实例 fan-out、独立控制服务和生产 HA 不由本决策宣称已解决。

### 3.3 未采用方案

- 未采用 FastAPI / Python 第二业务后端：它会复制身份、Task、审批和审计真相。
- 未采用首版微服务拆分或新增多个 Java Maven 模块：当前合成闭环没有足够的独立伸缩或组织边界证据。
- 未采用把 Agent 或 Runner 嵌入 Electron：这会混合用户 UI、模型 Secret 和未来桌面高权限。

### 3.4 恢复与重开条件

若 `ruoyi-aden` 可测边界无法维持，先通过包依赖测试和应用端口收敛，不直接拆服务。只有出现可量化的独立扩缩容、故障域、发布节奏或合规隔离需求，并形成业务真相迁移、兼容、双写禁止和回退方案后，才以新 ADR 重开工程数量。恢复当前方案时，停用新入口、让未完成租约安全失效，并以 MySQL 权威快照重新引导消费者；不得从客户端或 Python 数据倒灌业务终态。

## 4. ADEN-ADR-002：专属 Flyway 与启动边界

### 4.1 决定

Aden 使用独立 Flyway 配置：migration location 固定为 `classpath:db/aden-migration`，history table 固定为 `aden_flyway_schema_history`，`cleanDisabled=true`，`baselineOnMigrate=false`。Schema 变更只允许由短生命周期 `AdenMigrationCli` 以显式 `baseline`、`migrate` 或 `validate` 模式执行。

普通 `ruoyi-admin` 启动永不执行 Aden migration，只检查 Aden Schema 是否达到应用期望版本；启用 Aden 而 Schema 缺失、落后、checksum 不符或数据库身份不符时 fail-closed，不注册带病业务能力。CLI 在执行前必须验证目标数据库名、连接信息来源和 RuoYi 标志表；对已有 RuoYi 非空库只能显式建立 baseline version `0`，不能依靠 `baselineOnMigrate` 猜测。CLI 运行期间关闭普通应用 Schema guard，完成 migration 后再执行 validate。

已执行 migration 不回写、不改 checksum，修复只增加 forward-only migration。测试 seed、合成用户、Workspace、membership 和 Runner secret 都不进入 migration。`flyway clean` 在任何环境均不作为恢复手段。

### 4.2 后果

- 部署或本地联调多一个明确的迁移步骤，但启动行为可预测，也不会因一次应用重启意外改库。
- Aden migration 不会扫描默认 `db/migration` 或面试项目、智能选品项目的数据库脚本。
- fresh database 与已有 RuoYi database 都必须分别验证；“应用能启动”不等于迁移正确。
- Schema 未就绪会阻断 Aden 能力，而不是降级到部分表或运行时自动建表。

### 4.3 未采用方案

- 未采用普通 Spring Boot 启动自动 migrate：无法区分应用扩容、健康检查与受控数据库变更窗口。
- 未采用 `baselineOnMigrate=true`：可能把连错的非空库静默登记为合法基线。
- 未采用共享默认 history table / location：会混淆不同业务模块的版本和恢复责任。
- 未采用手工执行散装 SQL 或修改已执行脚本：不可重复验证且破坏 checksum 证据。

### 4.4 恢复与重开条件

Migration 失败时保留日志和 Schema 状态，在隔离测试环境销毁专用数据库后重建，或新增 forward-fix；不得 `clean` 共享库。若未来平台建立统一且具备互斥锁、审批、目标库证明和模块隔离的迁移编排器，可用新 ADR 评估替换 CLI；替换前旧 CLI 与 history table 仍是唯一 Aden 迁移入口。若目标数据库不是已确认的隔离测试库或批准环境，立即停止，不以本机 MySQL 服务代替。

## 5. ADEN-ADR-003：身份隔离与 Aden Origin Guard

### 5.1 决定

外部主体固定分为三类，凭据、principal、路由和权限不可互换：

| 主体 | 当前身份与入口 | 权限边界 |
| --- | --- | --- |
| Operator | 现有 RuoYi Bearer JWT，经 Aden Operator chain 适配为 Operator principal | RuoYi `aden:*` 功能权限 + Workspace membership + role + resource workspace 四层门禁 |
| Runner | `AdenCredential credentialId.secret` 换取短期 `AdenRunner sessionId.secret`；服务端只存带 pepper key id 的 keyed digest | 仅 `/api/v1/aden/runner/**`；lease、session epoch、fencing 与 receipt 约束 |
| Agent Worker | 后续独立 namespace 的 Worker credential / short session | 当前 OpenAPI 不发布入口，本期 Agent Fake 不获得服务端身份 |

Spring Security 链顺序固定为 Runner `@Order(1)`、Aden Operator `@Order(2)`、RuoYi 默认链。Runner matcher 为 `/api/v1/aden/runner/**`，只安装 Runner filter / provider；Aden Operator matcher 为 `/api/v1/aden/**`，复用 RuoYi JWT filter，但使用 Aden 专属 entry point、access denied handler 和 package-limited advice。两条 Aden chain 均 stateless，关闭 CSRF、request cache、form login 与 HTTP Basic，保留必要安全响应头。用户 JWT 调 Runner API、Runner token 调 Operator API都返回各自真实 HTTP 401，不尝试另一类 principal。

在上述认证链之前增加 **Aden Origin Guard**，只匹配 `/api/v1/aden/**`，并且必须在现有全局 `CorsFilter` 之前执行：

1. 只要请求带有 `Origin` header，不论值是远程站点、loopback、`file://` 派生的 `null`、空白、同源字符串或任何其他值，一律返回真实 HTTP `403` 与 Aden `ErrorEnvelope`，不回显 Origin，不附加 `Access-Control-Allow-Origin`。
2. 所有 CORS preflight 一律返回同样的 `403`；至少按 `OPTIONS` + `Origin` + `Access-Control-Request-Method` 识别。不得让宽泛全局 `CorsFilter` 提前以成功 preflight 短路。
3. 不带 `Origin` 的 Electron main、Runner simulator、Agent / CLI 的 `httpx` 请求可以继续进入对应认证链；“无 Origin”只跳过浏览器来源门，不等于通过认证或授权。
4. Guard 不修改、不替换共享 `ResourcesConfig`，不影响非 Aden RuoYi API。其过滤器顺序、async / error dispatch 行为以及与三个 SecurityFilterChain 的组合必须通过真实 filter-chain 集成测试证明，不能只测一个工具方法。

当前 Feature 因此没有可用的浏览器 CORS Origin，也不为 `admin-web` 开放 Aden API。未来即使某个网页获得 Operator Token，也不能从浏览器直接调用 `/api/v1/aden/**`。

### 5.2 后果

- 现有 RuoYi 全局宽泛 CORS 不再自动扩张 Aden 的攻击面，且无需在本 Feature 修改共享框架行为。
- Electron renderer 不能直连 API；全部调用必须经过受控 preload 方法进入 main。
- Postman、curl、Electron main 和 Python 客户端默认不发送 Origin，仍需正常完成身份、权限、membership、幂等和版本校验。
- 浏览器开发工具若自动附带 Origin 会收到 403，这是预期拒绝，不应通过放宽 Origin 来“修复”。
- 错误外形必须在 filter、security handler 和 controller 层保持同一个 Aden `ErrorEnvelope`，不能回落到 RuoYi 的 HTTP 200 错误包。

### 5.3 未采用方案

- 未采用继承 `ResourcesConfig` 的 `*` Origin / header / method：Aden 当前没有浏览器调用需求。
- 未采用在共享 `ResourcesConfig` 中删除全局 CORS：影响所有 RuoYi 和其他业务模块，超出本 Feature 授权。
- 未采用“允许精确 Electron renderer Origin”：打包态 `file://` 的 Origin 为 `null`，无法作为可靠授权边界；renderer 本来也不应持有 Token。
- 未采用仅依赖 CORS：CORS 是浏览器策略，不是服务端身份或资源授权；因此无 Origin 请求仍要走完整安全链。

### 5.4 恢复与重开条件

若未来批准 `admin-web` 或其他浏览器客户端调用 Aden API，必须先以新 ADR 指定精确部署 Origin、凭据形态、CSRF 策略、允许 method / header、preflight 缓存、撤销步骤和负向测试，再仅为所需路径替换 deny-all 规则；不能直接删除 Guard 或退回通配 CORS。若 Guard 顺序无法可靠领先全局 `CorsFilter`，Aden HTTP 接口保持 fail-closed / 不启用，直到通过容器级过滤器顺序测试；不得靠客户端约定绕过。

## 6. ADEN-ADR-004：Electron main 网络、Token 与重定向边界

### 6.1 决定

Electron `main → preload → renderer` 是信任逐层收窄，不是三个服务。main 是唯一网络代理和会话持有者：

- main 只连接启动时校验并冻结的单一 RuoYi API base URL。打包态仅允许精确 HTTPS Origin；只有显式 dev / test 且应用未打包时才允许精确 loopback HTTP。URL 禁止 userinfo、query、fragment和非预期 path。
- RuoYi 登录继续使用 `/captchaImage → /login → /getInfo → /logout`。密码和验证码只在一次受控 IPC / 请求生命周期内存在；Bearer Token 默认仅在 main 内存，不进入 renderer、localStorage、Pinia 持久化、URL、日志或 Python 进程。本期无 refresh token 和“记住登录”。
- main 的 REST 与 SSE 请求全部设置 `redirect: 'manual'`；任何 `3xx`，包括 301、302、303、307、308，无论 Location 是否同源，都在重放凭据前拒绝。当前固定 API 没有合法跳转。
- main 维护 `sessionEpoch` 与 `workspaceEpoch`。401、logout 或切 Workspace 时先递增 epoch 并 Abort 旧 REST / SSE，再清状态和重新 bootstrap；迟到响应和事件若 workspace / epoch 不匹配必须丢弃。
- SSE 由 main 使用带 Authorization header 的 `fetch` stream 读取，通过有界 parser、队列和 renderer ack 后才推进本地 ack cursor；Token 禁止放 query。网络错误、错误 content-type、超大 / 半帧、慢消费者或 ack timeout 都终止当前流并回到服务端 bootstrap。
- preload 只暴露逐方法、Schema 校验的 API；main 二次校验 sender、main frame、输入、Workspace 与当前 epoch。禁止通用 `invoke(channel)`、`request(url, options)`、Node 对象和 `IpcRendererEvent` 进入 renderer。

Renderer 只加载本地打包内容，使用 `createMemoryHistory()`；生产 CSP 目标为 `connect-src 'none'`。打包态 IPC sender 比较规范化、无 query / fragment 的固定 `index.html` file URL，不能把 `origin === "null"` 当授权。导航、新窗口、权限请求、远程页面和 webview 默认拒绝。

### 6.2 后果

- renderer XSS 仍是缺陷，但无法直接读取 Token、指定任意目标或调用通用 Node / 网络 API，攻击面显著缩小。
- main 成为高价值组件，需要对 URL canonicalization、redirect、日志脱敏、IPC sender、生命周期和队列背压做专门负向测试。
- 应用重启会要求重新登录；这是当前安全边界，不用未设计的持久化会话换取便利。
- Electron main 请求不发送 Origin，与 ADEN-ADR-003 的 deny-all browser Origin 兼容；若运行库未来开始自动附带 Origin，应被 403 并作为兼容回归处理。

### 6.3 未采用方案

- 未采用 renderer `fetch` / `EventSource`：会暴露 Token、受宽泛 CORS 影响，并诱发 query token。
- 未采用自动跟随 redirect 后检查最终 URL：307 / 308 可能在检查前重放 Authorization、密码或验证码。
- 未采用通配 HTTPS、证书错误降级或生产 HTTP：无法证明请求仍到批准服务。
- 未采用当前持久化 Token、密码或 refresh token：平台没有对应撤销与威胁模型证据。
- 未采用通用 IPC / Node bridge：会把 main 的全部权限转交给 renderer 内容。

### 6.4 恢复与重开条件

发现 base URL、证书、Origin、redirect、sender 或 CSP 不满足约束时，客户端必须停止请求、清理内存会话并展示阻断状态，不降级协议。若未来需要登录持久化、合法重定向、自定义 `app://`、多服务 Origin 或远程内容，必须分别给出威胁模型、撤销、迁移和负向测试并用新 ADR 重开。恢复当前边界时删除新增持久化材料、撤销会话、恢复固定 URL / deny redirect，并从服务端 bootstrap 重建视图；不得从 renderer 缓存恢复业务真相。

## 7. ADEN-ADR-005：Python Provider-disabled 与 FakeModelPort

### 7.1 决定

`aden-agent-runtime` 是低权限候选计算进程，不是业务后端。业务代码只依赖自有 `ModelAgentPort`；本期唯一必需实现是确定性的 `FakeModelPort`，以本地 fixture 输入产生固定、Schema-valid 的 Candidate。默认配置固定为 `provider_disabled`，未显式批准的 Provider adapter 不实例化、不读取 API Key、不解析 Provider host，也不发起 DNS、HTTP 或其他外部网络请求。

本期 Agent Runtime 只验证 RunSpec / ContextView / ToolManifest / Candidate 类型边界、取消、deadline、预算、失租、旧 fencing、重复提交和未授权工具拒绝。它使用 fake transport 或本地 fixture，不发布 FastAPI 控制面，不开放监听端口，不访问 MySQL，不推进 Task 终态，不执行 UIA、浏览器、Shell、任意文件或外部写工具。AgentJob 服务端 API 与 Worker credential 本期仍为 experimental、未发布。

PydanticAI 可以在自有端口之后作为后续可替换 adapter，并可用其测试开关补充“禁止真实模型请求”，但 SDK 类型、session、approval、retry 或 persistence 都不能成为 Aden 业务契约。当前不因框架选型安装或启用真实 Provider SDK；`FakeModelPort` 测试不得依赖云账号或网络可用性。

`aden-runner` 与 `aden-agent-runtime` 是两个独立工程：Runner simulator 只出站访问本地 RuoYi 测试服务，Agent Fake 默认零外联。Runner credential、未来 Agent Worker credential 和 Operator JWT 使用不同 namespace，任何一类 Secret 都不进入 job、fixture、日志、trace、证据或版本库。

### 7.2 后果

- IMP-01 / IMP-07 可以在没有模型账号、费用和网络的情况下验证类型、生命周期和错误语义。
- Agent 结果始终是候选，Java 必须重新做 Schema、版本、权限、预算和确定性晋升检查。
- 真实模型的质量、延迟、价格、区域、数据保留和工具行为都没有被本期 Fake 结果证明。
- 自有端口增加一层 mapping，但避免 PydanticAI 或某个 Provider 的 SDK 类型渗入机器契约和业务状态机。

### 7.3 未采用方案

- 未采用在本期选择或调用真实模型：用户尚未批准 Provider、账号、数据类别、地域和费用。
- 未采用 Agent SDK 持有审批、checkpoint 或业务 session：这些责任与 RuoYi / MySQL 唯一控制面冲突。
- 未采用 LangGraph / Temporal 主流程：当前固定合成链不需要第二套持久化执行图。
- 未采用 Agent Runtime 与 Runner 合并：未来桌面执行权限会污染模型进程的最小权限边界。
- 未采用 FastAPI 对 Electron 开放：桌面只连接 RuoYi 控制面。

### 7.4 恢复与重开条件

任何配置、依赖或测试观察到真实 Provider 尝试时立即 fail-closed，清除进程内 Secret 并记录失败；不得以捕获网络异常冒充“Provider disabled”。若未来启用真实 Provider，必须新建或更新 ADR，明确 Provider / 模型精确版本、允许 Origin、数据分类与地域、费用 / token / deadline、Secret 注入与轮换、日志保留、金标评测、故障降级和撤销开关。撤销时应能仅切回 `provider_disabled + FakeModelPort`，不修改 Task 契约或恢复第二套状态。

## 8. 跨决策不变量与验证门

以下约束同时适用于五项决定：

1. `contracts/aden/` 是跨 Java、TypeScript、Python 的字段和枚举事实源；内部 DomainCommand 不进入 Operator OpenAPI。
2. 所有可能增长到 64-bit 的公开数值使用 canonical 十进制字符串，三端不得经 JavaScript `number` round-trip。
3. Aden API 返回真实 HTTP status 与统一 `ErrorEnvelope`；不得用 RuoYi HTTP 200 body code 代替 4xx / 5xx。
4. 无 Origin、无 redirect、Provider disabled 都只是单项安全条件，不替代身份、Workspace、权限、幂等、版本、租约和 fencing 校验。
5. 当前验证只能使用 loopback、隔离 MySQL / Redis 和合成 fixture。Git、生产库、真实账号、模型费用、桌面高权限、安装包、部署和发布不在本决策授权内。

对应最低验证包括：

- 工程 / 契约：四实现单元与契约目录依赖方向、current / experimental Schema、三端 example round-trip。
- Flyway：fresh database、已有 RuoYi database + 显式 baseline 0、重复 migrate、checksum、错库和普通启动不迁移。
- 身份：三种 token 互换全部失败；Aden filter-chain 返回真实 401 / 403；Workspace 负向矩阵成立。
- Origin Guard：任意远程 / loopback / `null` / 空白 Origin、合法与畸形 preflight 均为真实 403 且无 ACAO；不带 Origin 的 Electron main / `httpx` 请求继续进入正确认证链；非 Aden API 行为不变。
- Electron：301 / 302 / 303 / 307 / 308 不重放凭据，renderer 无 Token / Node / 通用网络 / 任意 IPC，切 Workspace 与 401 会取消旧请求并拒绝迟到数据。
- Python：`FakeModelPort` 确定性、Schema invalid / 失租 / 取消 / 未授权工具拒绝和运行期零真实 Provider 网络。

实际结果必须记入 Feature 的唯一验证记录；在证据形成前，本文的 `Accepted` 只表示决定已批准，不表示实现或验证已经通过。

## 9. 决策变更规则

本记录不直接覆盖已批准决定。边界变化时新增 ADR 或将受影响决策标为 `Superseded`，并记录日期、原因、兼容影响、迁移顺序、失败恢复和关联 `REQ / AC / TASK`。普通实现缺陷回到对应 IMP 修复；契约、身份、数据库迁移、Origin、Token 或 Provider 边界改变时至少重开 `IMP-01`，产品范围或真实外部副作用改变时退回需求阶段并重新评估为 L3。

