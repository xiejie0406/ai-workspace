> **历史设计，已停止现行维护。** 2026-10-01 归一为[智能选品系统设计方案](../../../../架构/技术架构设计.md#runtime)。下方版本、批准、计划和结果仅保留原时点含义；精确原文字节见上级原文备份，迁移与恢复见[归一记录](../../../../开发记录/2026-10-01-设计文档归一与恢复.md)。

# 服装智能选品与报价 Python 智能体架构设计

> 文档类型：技术设计  
> 版本：0.4 · 文档状态：Draft  
> 风险等级：L2（新增 AI 运行时及跨服务契约；不涉及本轮真实账号、付费模型、生产数据或发布）  
> owner：用户负责技术路线与生产接入批准；Codex 负责架构设计、事实核查与后续实现建议  
> 创建日期：2026-09-11 · 更新日期：2026-09-12  
> 上游：[产品需求文档](../../../../产品/产品需求文档.md)、[数据库设计](数据库设计.md)、[Python 智能体框架与生产架构调研](../../../../调研/2026-09-11-Python智能体框架与生产架构调研.md)  
> 任务事实源：[FEAT-FASHION-001 任务清单](../../../../功能/FEAT-FASHION-001-服装智能选品生产首版/任务清单.md) · 验证事实源：[FEAT-FASHION-001 验证记录](../../../../功能/FEAT-FASHION-001-服装智能选品生产首版/验证记录.md)  
> 决策状态：单一 Python AI Runtime 与 Java/Python 职责边界已确认；`IMP-01`～`IMP-10` 执行包待用户审核，Provider、服务认证和生产部署方案仍须按任务取得证据

## 1 结论先行

本项目不采用“全部改成 Python”，也不采用“Java 里塞一套自由自治 Agent”。建议使用清晰的双运行时边界：

1. Java / Spring Boot / RuoYi 是业务控制面和唯一权威入口，继续负责登录、权限、客户、商品、价格、库存、方案、图片任务、报价、文件、审计、事务和 MySQL。
2. 独立 Python AI Runtime 是模型执行面，负责需求理解、视觉理解、候选排序、搭配推理、文案解释、模型与图片供应商适配、结构化输出校验、Agent Loop、评测和模型侧可观测性。
3. 模型只能通过白名单 Tool 请求业务能力，不能直接访问业务数据库、任意 URL、文件系统、Shell 或内部管理接口。
4. 金额、库存、权限、版本、SKU 存在性和正式写入始终由 Java 确定性校验。Agent 输出只是候选；人工采用后，Java 再校验并写入业务表。
5. Python 首期保持无业务状态、可横向扩容。对话、Run、Step、审批、幂等和任务状态由 Java 的 Agent Control 持久化；Python 不另建一套会话真相库。
6. 首选 Python 内核为 FastAPI + Pydantic v2 + PydanticAI Core + 显式 Workflow + HTTPX + OpenTelemetry + pytest/Pydantic Evals。首期不同时叠加 LangGraph、CrewAI 或另一套 checkpoint。
7. 生图是付费、长时间、可出现“结果未知”的外部任务。它必须由 Java 先建立持久任务和预算预留，再由 Python 适配供应商；不能在一个 HTTP Agent 循环里等待到底。
8. 服装 AI 只维护一个 `python/fashion-ai-runtime/` Python 代码项目；需求解析、选品搭配、视觉理解、生图适配和评测均在该项目内按包分层，不能为每个 Agent 再拆一个服务或仓库。需要独立伸缩时，可由同一镜像启动 API 与 Worker 两种进程。

因此，“AI 相关都用 Python”需要改成更准确的工程表述：

> 模型交互、Agent 编排和 AI 评测优先由 Python 承担；业务事实、权限、一致性、副作用和对外 API 继续由 Java 承担；长任务可靠性由业务任务状态机负责。语言按责任边界选择，而不是按“是否叫 AI”选择。

## 2 目标、非目标与约束

### 2.1 设计目标

- 支持中文自然语言需求解析，例如“100 人，四个价格档，每档 3 套，每套 1–4 个品类，预算 300 元”。
- 支持在真实商品、价格和库存约束内完成候选检索、排序、搭配解释和缺口说明。
- 支持视觉标签提取、生图请求、结果回收、人工复核、采用与版本失效。
- 支持结构化、可恢复、有预算、有截止时间的 Agent Run。
- 支持切换 LLM、视觉、Embedding、Rerank 和图片供应商，而不改变业务领域契约。
- 支持逐步上线：先单 Agent 和只读工具，再引入多 Agent、由业务任务状态机承载的外部长任务及更复杂 Workflow。
- 每个结论能追溯到 Agent 版本、配置、模型、工具调用、业务快照、人工决定和评测结果。

### 2.2 非目标

- 不让 Agent 自主修改商品价格、库存、客户权限或已确认报价。
- 不让多个角色 Agent 自由讨论直到“看起来满意”。
- 不把 Prompt、向量库、会话摘要或框架 Memory 当成业务事实源。
- 不在首期建设通用低代码 Agent 平台、插件市场或任意脚本执行平台。
- 不在首期引入 GPU 自托管模型、独立向量数据库或完整 Kubernetes 平台。
- 不承诺框架功能存在就等于本项目模型效果、成本、延迟或恢复已达标。

### 2.3 已知项目约束

| 约束 | 架构响应 |
| --- | --- |
| 主工程采用 Vue 3、TypeScript、Spring Boot/RuoYi | 浏览器只访问 Java；Python 作为私有内部服务 |
| 本独立业务项目以 MySQL 为权威数据库 | 业务及 Agent Control 记录均由 Java 写 MySQL |
| 商品候选每轮最多 12 个，30 秒内返回或进入可恢复异步状态 | Agent 有步数、工具数和 deadline；超时转可恢复状态 |
| 正式金额必须精确、可复算 | quote_calculate 只由 Java 确定性实现 |
| 图片供应商可能收费、超时或晚到 | 预算预留、幂等请求、异步回查、UNKNOWN 状态和人工复核 |
| 真实模型、账号和供应商尚未批准接入 | 本设计只定义边界；实施前另做版本/许可/供应商 Spike |

## 3 架构原则

### 3.1 Agent 是不可信建议器

模型输出、工具参数、摘要、图片标签和自然语言解释全部视为不可信输入。Pydantic 校验只能证明结构符合 Schema，不能证明 SKU 存在、用户有权访问、金额正确或图片具有使用许可。所有业务结论在 Java 服务端重新验证。

### 3.2 Workflow 包住 Agent，而不是 Agent 吞掉 Workflow

需求理解、审美排序和解释属于开放式问题，可由 Agent 决策；权限校验、硬过滤、库存汇总、金额计算、审批和落库属于确定性步骤。完整流程由显式 Workflow 固定关键顺序，局部节点才运行 Agent。

### 3.3 单一事实源

- MySQL：商品、库存、价格、客户、方案、报价、任务、Run、Step 和人工决定。
- 对象存储：原图、候选图、PPTX、ZIP 等二进制产物；数据库只保存稳定键、版本、摘要和权限。
- Python 内存：当前调用的临时上下文和模型循环，不是长期事实。
- 向量或搜索索引：可重建的派生数据，不得成为商品价格、库存或权限的唯一来源。

### 3.4 默认有界

每次 Run 必须绑定 deadline、最大 Agent 步数、最大模型调用数、最大 Tool 调用数、最大并发、token/金额预算、允许工具集合和最大 handoff 深度。达到任一边界立即结束或暂停，禁止无限循环。

### 3.5 副作用由业务系统拥有

Agent Tool 不直接提交报价、发布商品、发送客户文件或采用图片。需要产生副作用时，Agent 只能创建“建议”或“待审批动作”；实际用户命令进入 Java 后重新鉴权、检查版本并执行。

### 3.6 供应商可替换，能力显式声明

领域层只表达“文本生成、视觉理解、Embedding、Rerank、生图”等能力。Provider Adapter 报告支持的结构化输出、工具调用、图片输入、上下文、速率、地域和计费特性；不假设所有模型能力等价。

## 4 系统上下文与部署边界

~~~text
销售 / 商品运营 / 管理员
          │
          ▼
Vue 3 / TypeScript Web
          │ HTTPS
          ▼
Java / Spring Boot / RuoYi
  ├─ API/BFF、认证、RBAC、客户归属
  ├─ Catalog / Inventory / Planning / Quotation
  ├─ Image Job / Export / Audit / Run-Step有限事件
  ├─ Agent Control：Conversation / Run / Step / Approval
  ├─ MySQL（唯一业务事实源）
  └─ 受控对象存储
          │ 私网 HTTPS，服务身份，版本化契约
          ▼
Python AI Runtime / FastAPI
  ├─ Run Coordinator / Workflow
  ├─ Agent Registry / Prompt & Config Resolver
  ├─ PydanticAI Agents / Toolsets / Output Validators
  ├─ Java Tool Gateway Client
  ├─ LLM / Vision / Embedding / Rerank Adapters
  ├─ Image Provider Adapters
  └─ Guardrails / Events / OpenTelemetry / Evals
          │
          ├─ LLM / 视觉 / Embedding / Rerank Provider
          └─ 生图 Provider
~~~

### 4.1 为什么浏览器不直连 Python

- Java 已掌握用户身份、角色、客户归属和领域版本。
- 可防止客户端伪造 allowedTools、role、budget 或 agentVersion。
- 对外 API、限流、审计、错误语义和前端兼容保持一个入口。
- Python 可在私网独立伸缩、升级或回滚，不暴露模型密钥和内部工具面。

### 4.2 为什么 Python 不直连业务库

- 避免 Java 与 Python 同时写同一事务边界形成双写和规则漂移。
- 权限、字段投影、乐观锁、幂等和审计只实现一次。
- Tool 契约可在不暴露表结构的情况下稳定演进。
- 将 Prompt Injection 的影响限制在白名单能力内。

只有当未来压测证明 Tool 往返成为明确瓶颈，并形成专项 ADR、只读副本一致性模型和权限投影后，才评估 Python 只读数据面；首期不开放。

## 5 责任矩阵

| 能力 | Java 业务控制面 | Python AI Runtime | 模型 | 人工 |
| --- | --- | --- | --- | --- |
| 身份、角色、客户归属 | 唯一判定 | 接收最小 actor reference | 不参与 | 通过登录表达 |
| 商品/价格/库存事实 | 唯一读写与版本 | 通过 Tool 获取投影 | 只能使用返回值 | 运营维护 |
| 需求理解 | 保存原文与确认字段 | 结构化解析、歧义列表 | 提出字段与问题 | 确认或修正 |
| 硬过滤 | 执行并返回排除原因 | 请求过滤 | 不得放宽 | 可修改需求后重跑 |
| 候选排序/搭配 | 提供候选并最终校验 | 编排、排序、解释 | 产生建议 | 锁定、替换、采用 |
| 金额、税费、库存聚合 | 唯一计算 | 调用 quote_calculate | 只能解释结果 | 确认商务参数 |
| 生图任务 | 鉴权、预算、任务状态、采用 | Provider 适配、提示构造、结果解析 | 生成候选 | 审核并采用 |
| 对话/Run/Step | 唯一持久化和查询 | 产生结构化事件 | 不持久化 | 查看、取消、审批 |
| Prompt/Agent 版本 | 发布、启停、审计 | 按指定版本加载 | 使用已解析内容 | 管理员批准发布 |
| 正式业务写入 | 唯一执行 | 不直接执行 | 不执行 | 发出明确命令 |

## 6 Python 技术栈与选型

### 6.1 建议技术栈

| 层 | 首选 | 用途与边界 |
| --- | --- | --- |
| 服务入口 | FastAPI + ASGI Server | 私网 REST、NDJSON/SSE、健康检查；不承载业务权限判断 |
| Schema/配置 | Pydantic v2 + pydantic-settings | API、Tool、模型输出、配置的类型校验 |
| Agent 内核 | PydanticAI Core | typed deps、toolsets、typed output、Agent Loop 和 Provider 抽象 |
| Workflow | 首期普通 async Python 状态机；必要时 pydantic-graph | 显式步骤、分支、并行、暂停点；状态仍由 Java 持久化 |
| HTTP | HTTPX | Java Tool Gateway 与外部 Provider Adapter |
| 重试 | 明确分类的有限退避策略 | 只重试可证明安全的瞬时失败；不让 SDK 多层重试相乘 |
| 可观测性 | OpenTelemetry | trace、span、metrics、关联 runId；不记录隐藏思维链 |
| 测试与评测 | pytest + Pydantic Evals 或等价 code-first harness | Schema、Tool、状态机、Golden Set 和回归 |
| 包与质量 | pyproject.toml + 锁文件 + Ruff + Pyright/Mypy | 版本、静态检查、供应链可复现 |

正式实现前必须通过最小 Spike 冻结 Python 版本、PydanticAI 版本、Provider SDK、许可证和锁文件。本设计不凭当前文档直接锁具体版本。

### 6.2 为什么首选 PydanticAI

- 商品候选、搭配、缺口、图片请求和解释都需要严格结构化输出。
- Agent[DepsT, OutputT] 和 RunContext 可把依赖、工具与输出边界写进类型。
- Model/Provider/Profile 分层适合尚未锁定供应商的项目。
- Deferred Tool 能表达待审批；外部长任务不挂住 Agent Run，而由 Java 业务任务状态机承载。
- OpenTelemetry 与 code-first Evals 便于纳入现有工程证据。

### 6.3 为什么首期不选或不叠加其他框架

| 候选 | 不作为首期内核的原因 | 重新评估条件 |
| --- | --- | --- |
| OpenAI Agents SDK | OpenAI 路径很完整，但本项目仍需多 Provider 和独立生图适配 | 明确全量采用 OpenAI，且其 tracing/evals/hosted tools 带来显著收益 |
| LangGraph | checkpoint 和复杂图强，但会与既有 Java Run/Step 状态形成双状态问题 | 分支、并行、长暂停和重放复杂度超过普通状态机，并完成统一 Checkpointer 设计 |
| Google Agent Development Kit（ADK） | Runtime、Event、Session、Artifact 和图能力完整，但当前没有 Google Cloud 路线 | 确定 Gemini/Google Cloud 为主要平台 |
| Microsoft Agent Framework | Azure/Microsoft 生态集成强，当前平台路线未定 | 确定 Azure/Foundry 为主要生产环境 |
| CrewAI | 角色化原型直观，但关键交易路径需要更强确定性和类型边界 | 仅用于隔离的探索性研究，工具只读且有固定预算 |

首期禁止同时使用 PydanticAI + LangGraph + CrewAI 表达同一条流程。框架越多，不代表能力越强，反而会增加事件、状态、重试和恢复语义的歧义。

### 6.4 三层能力必须区分

PydanticAI Core 负责 Agent Loop 和类型化工具，不等于通用耐久工作流；会话 message history 不等于长期记忆；Harness 或 StepPersistence 也不能自动消除副作用重放。若未来出现跨天等待、复杂定时器、批量 fan-out/fan-in 或跨进程任意节点恢复，再专项评估 Temporal、DBOS、Restate、Prefect 等 durable engine，并确保它只承担工作流可靠性，不复制业务事实。

## 7 Python 工程结构

实际唯一工程目录为 `python/fashion-ai-runtime/`，真实 Python 包名为 `fashion_ai`；不得按旧示意另建 `ai-runtime/src/fashion_agent` 第二套目录：

~~~text
python/fashion-ai-runtime/
├─ pyproject.toml
├─ uv.lock / 选定工具的等价锁文件
├─ README.md
├─ src/fashion_ai/
│  ├─ main.py
│  ├─ settings.py
│  ├─ api/
│  │  ├─ routes_runs.py
│  │  ├─ routes_health.py
│  │  ├─ schemas.py
│  │  └─ error_mapping.py
│  ├─ application/
│  │  ├─ run_coordinator.py
│  │  ├─ context_builder.py
│  │  ├─ event_emitter.py
│  │  └─ workflows/
│  │     ├─ requirement_workflow.py
│  │     ├─ selection_styling_workflow.py
│  │     ├─ image_workflow.py
│  │     └─ conversation_workflow.py
│  ├─ agents/
│  │  ├─ registry.py
│  │  ├─ requirement_agent.py
│  │  ├─ selection_agent.py
│  │  ├─ styling_agent.py
│  │  ├─ image_coordinator.py
│  │  └─ orchestrator_agent.py
│  ├─ domain/
│  │  ├─ run_state.py
│  │  ├─ messages.py
│  │  ├─ candidates.py
│  │  ├─ outfits.py
│  │  └─ errors.py
│  ├─ ports/
│  │  ├─ business_tools.py
│  │  ├─ model_gateway.py
│  │  ├─ image_gateway.py
│  │  └─ event_sink.py
│  ├─ providers/
│  │  ├─ disabled.py
│  │  ├─ router.py
│  │  ├─ llm/
│  │  ├─ vision/
│  │  └─ image/
│  ├─ tools/java_gateway/
│  │  ├─ client.py
│  │  ├─ auth.py
│  │  └─ error_mapping.py
│  ├─ guardrails/
│  ├─ telemetry/
│  └─ prompts/
│     ├─ loader.py
│     └─ templates/
├─ evals/
│  ├─ datasets/
│  ├─ evaluators/
│  └─ experiments/
└─ tests/
   ├─ unit/
   ├─ contract/
   ├─ integration/
   ├─ fault_injection/
   ├─ security/
   └─ eval/
~~~

目录表达依赖方向：API 和基础设施可以依赖 application/domain/ports，领域层不能反向依赖 FastAPI、某个模型 SDK 或 Java DTO。Prompt 是有版本的运行配置，不散落在路由和 Tool 代码中。

## 8 核心组件

### 8.1 API Ingress

验证服务身份、契约版本、runId、executionLeaseId、fencingToken、deadline、输入大小和 trace context。它不信任 body 中的角色或权限，只接受 Java 签发的短期委托授权。重复 runId + runAttempt 按幂等规则返回已有状态或拒绝冲突；租约过期或 fencingToken 落后的请求不得继续调用模型、Tool 或 Provider。

### 8.2 Run Coordinator

根据 runType 选择 Workflow，加载已发布 Agent 版本和预算，推动状态转换，捕获取消与 deadline，生成单调递增事件。它不自行决定业务权限和金额。

### 8.3 Context Builder

从 Java 提供的引用和 Tool 投影构建最小上下文，分离：

- system policy：不可被用户或商品内容覆盖的安全与业务边界；
- confirmed facts：Java 返回并带版本的事实；
- user input：可能包含 Prompt Injection 的原始输入；
- retrieved content：商品文案、OCR、图片标签等不可信内容；
- working state：当前 Run 的有限结构化状态。

禁止把完整客户库、全量商品库或其他客户对话塞入 Prompt。

### 8.4 Agent Registry 与 Prompt/Config Resolver

Java 从 fq_ai_agent_version 读取已发布版本，为每个 Run 构造 ResolvedAgentSpec：agentVersionId、configHash、systemInstruction、modelProfile/modelConfig、tools、handoffs、input/output Schema、guardrails、预算和超时。execute 请求携带该完整快照及 Java 签名，Python 规范化后复算 configHash 并拒绝不一致内容。运行中的 Run 固定版本；发布新版本只影响新 Run，回放时能取得原始配置摘要。

Python 的 prompts/templates 只保存渲染器、开发 fixture 和不可变内置安全前缀，不是生产 Prompt 的第二事实源；生产环境缺少 ResolvedAgentSpec 时直接拒绝执行，不从本地文件静默兜底。

### 8.5 Model Router

按能力而不是品牌选择 profile，例如 text_reasoning、vision_tagging、rerank、image_generation。路由输入由管理员已发布策略决定，模型不能选择更贵 Provider。Fallback 只对声明为等价且通过评测的 profile 生效。

### 8.6 Tool Gateway Client

所有业务工具通过 Java 内部接口调用。客户端附带 runId、toolCallId、delegatedActorToken、幂等键、deadline 和 traceparent；验证响应 Schema 和 `quoteRowVersion/comboVisualHash` 等业务版本；将错误转换成稳定的 Agent 可见错误码，不把堆栈、SQL 或秘密返回模型。

### 8.7 Guardrails 和 Output Validators

输入侧限制文件类型、大小、租户/客户引用和明显注入内容；工具侧限制名称、参数、权限、预算和调用频率；输出侧验证 Schema、SKU 引用、槽位、数量、解释字段和敏感信息。Guardrail 不是认证系统，最终授权仍由 Java 判定。

### 8.8 Event Emitter

每个模型、Tool、handoff、审批、checkpoint 和终态都可生成结构化事件。Python 生成可去重的 clientEventId，但不决定 Run 内最终序号；Java 在 StepExecutionGrant 中预分配 stepNo，只把状态、用量、外部受理 ID 和终态变化写入现有 Run/Step 字段，中间进度可实时转发但不逐条建表。同步结果由 Java 接收并事务落库；异步图片状态由 `fq_quote_image` 轮询更新。事件正文脱敏，不包含隐藏思维链。

### 8.9 核心接口伪代码

以下仅说明依赖方向与类型边界，不锁定框架版本或构成可运行实现：

~~~python
class RunDeps:
    tools: BusinessToolPort
    events: EventPort
    policy: RunPolicy
    delegated_actor_token: str
    run_id: str
    quote_row_version: str

class SelectionOutput:
    ranked_candidates: list[CandidateRef]
    gaps: list[ConstraintGap]
    explanation: str

class BusinessToolPort:
    async def product_search(
        self, query: ProductSearchQuery, call: ToolCallContext
    ) -> ProductSearchResult: ...

    async def combo_validate(
        self, draft: OutfitDraft, call: ToolCallContext
    ) -> ComboValidationResult: ...

selection_agent = Agent(
    model=resolved_model_profile,
    deps_type=RunDeps,
    output_type=SelectionOutput,
    instructions=published_prompt,
    toolsets=[business_read_tools],
)

async def execute_selection(command: ExecuteRun) -> RunResult:
    context = validate_and_build_context(command)
    filtered = await context.deps.tools.product_search(
        context.search_query, context.next_tool_call()
    )
    result = await selection_agent.run(
        context.render_prompt(filtered),
        deps=context.deps,
        usage_limits=context.usage_limits,
    )
    validated = validate_against_business_refs(result.output, filtered)
    return context.to_proposed_result(validated)
~~~

这里的 BusinessToolPort 只指向 Java Tool Gateway；Agent 定义不导入数据库驱动、RuoYi DTO 或具体 Provider SDK。真实代码必须按 `IMP-01` 最小 Spike 锁定的 PydanticAI API 调整。

## 9 Agent 角色设计

数据库中的 Agent 类型表示逻辑能力和版本，不等于必须部署多个微服务。首期多个 Agent 在同一 Python Runtime 内运行。

| Agent/模型能力 | 输入 | 允许动作 | 结构化输出 | 禁止事项 |
| --- | --- | --- | --- | --- |
| Requirement Agent | 用户原文、已确认字段、业务词典 | 提取字段、列出歧义、建议追问 | ConfirmedBriefDraft、ambiguities、confidence | 自行确认价格、库存、客户权限 |
| Selection Agent | 已确认 brief、Java 硬过滤结果 | 对最多 12 个真实候选排序和解释 | rankedCandidates、reasons、gaps | 发明 SKU、放宽硬约束 |
| Styling Agent | 真实候选、锁定项、槽位规则 | 组合 1–4 品类、说明风格 | outfitDrafts、slotRefs、rationale | 绕过 combo_validate、直接采用 |
| Vision Tagger | 受控图片引用、标签词典 | 输出视觉标签和置信度 | colors/styles/categories/quality flags | 推断价格、库存、材质成分为事实 |
| Image Coordinator | 已校验组合、图片版本、预算状态 | 构造受控生图请求、解析 Provider 结果 | providerRequest、resultRefs、warnings | 自批费用、自采用图片 |
| Quote Explainer | quote_calculate 的确定性结果 | 生成不含自由金额的说明片段和 amountRef | statementItems、amountRefs、warnings | 计算金额、在自由文本复述金额 |
| Orchestrator Agent | 多意图请求、可用专业能力 | 选择专业 Agent-as-tool 并汇总 | intentPlan、delegations、finalDraft | 无限制 handoff、直接业务写入 |

首期默认使用单 Agent + 受限 Tool。只有一句话确实包含多个意图时才进入 Orchestrator；专业 Agent 作为工具调用后把控制权交回总控。首期不使用自由 group chat。

多 Agent 不在父 Run 内偷偷更换 Prompt。Orchestrator 只能选择父版本 handoffs_json 允许的 agentCode；Java 为每次委派创建独立 child Run，设置 parentRunId，并从目标 Agent 的已发布版本生成独立 ResolvedAgentSpec、configHash、预算和执行租约。父 Run 的 handoff Step 保存 targetAgentId、childRunId 和 parentStepId；子 Run 的用量、状态和错误单独记录，完成后只把结构化最小结果交回父 Run。

## 10 Agent Loop 与停止逻辑

每次 Agent 节点遵循以下确定逻辑：

~~~text
1. Java 创建 Run，固定 agentVersion、allowedTools、预算、deadline 和输入快照。
2. Python 校验请求并创建内存态 RunContext。
3. 组装最小上下文，调用指定模型。
4. 如果模型返回结构化最终输出：
   a. Pydantic 校验；
   b. 运行领域 Output Validator；
   c. 校验通过则产生 PROPOSED_RESULT，结束 Agent 节点。
5. 如果模型返回 Tool Call：
   a. 检查工具白名单、参数 Schema、权限 capability、预算和次数；
   b. 若需要人工批准，输出 checkpoint 并进入 WAITING_APPROVAL；
   c. 外部付费长任务只由确定性 Workflow 在 Java 已建业务任务后提交，Agent Run 不原地等待；
   d. 其他 Tool 调用 Java Tool Gateway；
   e. 记录结果摘要，将结果回灌模型，返回步骤 3。
6. 如果模型请求 Handoff：
   a. 检查允许的目标与最大深度；
   b. 传递最小上下文；
   c. 专业 Agent 完成后返回总控。
7. 达到 deadline、maxSteps、maxToolCalls、预算或取消标记时立即停止。
8. Python 返回终态事件；Java 保存并展示候选。任何正式采用都由后续用户命令触发。
~~~

严禁把模型的自由文本当成工具名、URL、SQL、文件路径或权限表达式执行。

## 11 业务 Workflow

### 11.1 需求解析

1. Java 保存用户原文和当前 `quoteId + quoteRowVersion`，创建 REQUIREMENT_PARSE Run。
2. Requirement Agent 输出字段草稿：人数、档位、每档套数、品类数、预算、风格、颜色、尺码、交期和歧义。
3. Java 校验字段范围和客户访问权。
4. 缺少阻断字段时返回“待用户确认”，不猜测关键商务参数。
5. 用户确认后，Java 新建或更新尚未确认的 `fq_quote`，并递增 `row_version`；已确认报价需复制为新的 `fq_quote.version_no`，模型草稿不能覆盖已确认 brief。

### 11.2 选品与搭配

1. Java 对确认 brief 执行 product_search：状态、品类、价格、库存、来源权限等硬过滤。
2. Selection Agent 只对真实候选排序，最多返回 12 个，并为缺口给出原因。
3. Styling Agent 依据 1–4 个互异槽位、锁定项和候选生成组合草稿。
4. 每个组合调用 combo_validate；Java 检查 SKU、数量、共享库存、预算、图片可用性和 `quoteRowVersion/comboVisualHash`。
5. 校验失败只允许修正建议或返回无解，禁止模型自行放宽硬约束。
6. 通过的结果以候选保存；用户锁定、替换或采用时再次校验当前数据版本。

### 11.3 生图与复核

1. 用户选择已校验组合并明确发起生图。
2. Java 检查权限、素材许可、图片版本、费用策略和剩余额度，创建 `fq_quote_image`（`quoteImageId`）并记录预算状态。
3. Java 先创建 IMAGE_SUBMIT Run 及一次 tool Step 尝试（attemptNo），持久化 inputHash、externalRequestKey、预算与 queued 状态，再发放 StepExecutionGrant 和短期租约。
4. Python Image Coordinator 只凭该 Grant 通过 Provider Adapter 提交幂等请求，立即返回 providerJobId 或明确失败。
5. Provider 明确受理后，Java 原子记录 providerJobId，IMAGE_SUBMIT Run 以“已受理”成功结束；长时间执行状态继续由 fq_quote_image 记录，而不是新增一个 WAITING_EXTERNAL Agent 状态。
6. Java 按持久计划触发短时 Provider 状态回查；提交是否受理不明时，Run 和图片任务进入 UNKNOWN 并先按原 externalRequestKey 查证。
7. 获得结果后，文件先进入隔离区进行格式、大小、安全和引用检查；需要模型质检时创建新的、短时 IMAGE_RESULT_REVIEW Run。
8. Python 可生成质量标签，Java 保存为“待人工复核”；供应商 success 不等于业务 adopted。
9. 人工逐项检查缺件、变款、颜色、Logo、比例和来源后采用；Java 再核对 `comboVisualHash` 与 `inputImageVersion`。
10. 晚到、旧版本、费用未知或被取消的结果隔离，不自动覆盖当前采用图。

### 11.4 报价说明

1. Java quote_calculate 根据当前 `quoteId + quoteRowVersion`、价格、税费、折扣、费用、配比和库存产生确定性结果。
2. Quote Explainer 只能生成语义说明片段、假设、风险提示和指向 Java 结果字段的 amountRef，不在自由文本中自行写金额、折扣率或税率。
3. Java 用确定性模板把 amountRef 替换为已格式化金额；若确需模型复述数字，则对所有币种、百分比和数量做引用级校验，任一不一致就弃用整段模型文案并记录验证失败。
4. 正式确认仍由 Java 锁定一组 `fq_quote + fq_quote_combo + fq_quote_detail` 作为不可变的已确认报价版本；不另建报价快照表，Agent 也不能调用 confirm。

### 11.5 多意图会话

当用户同时提出“分析需求、给我四档搭配并生成图片”时，总控先产生 intentPlan。Workflow 仍强制：

~~~text
解析并确认需求
  → Java 硬过滤
  → AI 排序/搭配
  → Java 组合校验
  → 人工采用
  → Java 费用批准与任务建立
  → 外部生图
  → 人工复核
~~~

总控 Agent 不能因为自然语言要求“一步完成”而跳过确认、预算或人工复核。

## 12 Run、Step 与状态机

### 12.1 标识层级

| 标识 | 含义 | 生命周期 |
| --- | --- | --- |
| conversationId | 同一用户围绕同一业务目标的交互容器 | 多个用户 turn |
| runId | 一次用户输入或系统事件触发的有界执行 | 一个终态或人工审批恢复链 |
| runAttempt | 同一 Run 的 Worker 调度代次 | 首次为 0；租约失效后接管递增 |
| stepId | 一次模型、Tool、handoff、guardrail、checkpoint 或审批 | Run 内唯一 |
| toolCallId | 模型请求的一次工具调用 | 与工具结果或审批严格对齐 |
| quoteRowVersion / comboVisualHash | 报价草稿和组合视觉输入的并发基线 | 由 Java 领域对象维护 |

### 12.2 状态

~~~text
QUEUED
  → RUNNING
      → WAITING_APPROVAL → RUNNING
      → SUCCEEDED
      → FAILED
      → CANCEL_REQUESTED → CANCELLED
      → UNKNOWN
~~~

上图只展示主路径，实际合法转换与数据库设计保持一致：

| 当前状态 | 允许下一状态 | 说明 |
| --- | --- | --- |
| QUEUED | RUNNING / CANCELLED | 未开始可取消；领取时发放执行租约 |
| RUNNING | WAITING_APPROVAL / SUCCEEDED / FAILED / CANCEL_REQUESTED / UNKNOWN | 只有结构化结果和最终校验通过才能成功 |
| WAITING_APPROVAL | RUNNING / FAILED / CANCEL_REQUESTED | 拒绝或过期以稳定错误结束；批准后重新鉴权并发新租约 |
| CANCEL_REQUESTED | CANCELLED / SUCCEEDED / UNKNOWN | 外部动作可能已完成或状态不明，不虚构取消成功 |
| UNKNOWN | RUNNING / SUCCEEDED / FAILED / CANCELLED | 必须先按原外部标识回查，再进入查明后的状态 |
| SUCCEEDED / FAILED / CANCELLED | 无 | 执行终态不回退；业务修订创建新 Run |

- SUCCEEDED、FAILED、CANCELLED 是终态，不能倒退。
- UNKNOWN 表示无法确认外部副作用是否已受理，必须先查证，不能盲目重提。
- WAITING_APPROVAL 是人工介入（Human-in-the-loop，HITL）状态，保存批准对象、参数摘要、过期时间和 checkpoint；恢复时重新鉴权并检查 `quoteRowVersion` 等业务版本。
- CANCEL_REQUESTED 不等于外部 Provider 已取消或退款；晚到结果仍按隔离规则处理。
- 图片 Provider 的排队、执行、结果和业务复核状态属于 `fq_quote_image`，不扩展 Agent Run 状态。一次提交 Run 完成后，回查和质检使用新的有界步骤或 Run，并通过 `quoteImageId`、`providerJobId` 和原 `aiRunId` 关联。

### 12.3 Step 类型

运行时事件映射到数据库已有的六类 step_type，不新增第二套枚举：

| 运行时事件 | fq_ai_run_step.step_type |
| --- | --- |
| model_request / model_result | model |
| tool_call / tool_result / Provider提交或回查 | tool |
| Agent-as-tool / handoff 与 child Run | handoff |
| input/output/final validation | guardrail |
| approval requested/approved/rejected/expired | human_approval |
| 有限可恢复状态落点 | checkpoint |

每个 Step 保存可审计输入/输出摘要、Schema 版本、耗时、用量和错误码，不保存模型隐藏思维链；同一 callKey 的多次 attemptNo 分行留证。图片外部等待记录在 fq_quote_image，不伪装成仍占用 Worker 的 Agent Step。

### 12.4 与数据库设计的映射

| 运行时概念 | 现有事实源 | 规则 |
| --- | --- | --- |
| Agent Registry | fq_ai_agent | 稳定逻辑身份和当前发布指针 |
| Prompt/Tool/Model 配置 | fq_ai_agent_version | 不可变发布版本；Run 固定 versionId |
| Conversation | fq_ai_conversation | 用户和业务上下文容器 |
| Message | fq_ai_message | 用户、助手和受控摘要；不存隐藏思维链 |
| Run / checkpoint | fq_ai_run | 当前状态和最新有限 checkpoint 的权威副本 |
| Step / Tool ledger | fq_ai_run_step | 模型、Tool、handoff、校验、审批的不可变历史证据 |
| 图片外部任务 | fq_quote_image | Provider 执行、候选、费用、复核和采用；不复制进 Agent Memory |

首期 AI 运行只使用 `fq_ai_agent`、`fq_ai_agent_version`、`fq_ai_conversation`、`fq_ai_message`、`fq_ai_run`、`fq_ai_run_step` 六张表，不新增 `ai_feedback`、`ai_checkpoint`、`ai_memory`、`ai_trace`、`ai_tool` 或 `ai_image_result` 表。采用/拒绝由 `fq_ai_run.apply_status` 表达，图片复核由 `fq_quote_image` 表达；独立反馈入口与训练数据治理延期到 P1。详细字段和事务继续以[数据库设计 v2.3](数据库设计.md)为唯一事实源。Python 不直连这些表，所有状态转换都由 Java Agent Control 或对应业务服务执行。

## 13 RunContext 和数据契约

Python 只接收完成当前 Run 所需的最小数据。建议 RunContext 至少包含：

| 字段 | 说明 |
| --- | --- |
| runId、runAttempt、conversationId、parentRunId | 关联、父子 Agent 与幂等 |
| runType、triggerType | 选择 Workflow，不由模型自由解释 |
| resolvedAgentSpec、agentVersionId、configHash | Java 提供并签名的完整不可变 Agent 配置快照 |
| executionLeaseId、fencingToken | Java 先持久化后发放的执行权；拒绝过期实例 |
| delegatedActorToken | Java 签发的短期、限范围 Tool 委托；不传可伪造 role |
| customerRef、quoteRef、quoteRowVersion、comboRef、comboVisualHash | 业务引用，不携带无关完整对象 |
| input | 已版本化的结构化输入和原始用户文本 |
| allowedTools | Java 与 Agent 版本交集后的白名单 |
| deadline、maxSteps、maxModelCalls、maxToolCalls、maxHandoffs、maxParallelTools | 有界运行 |
| tokenBudget、costBudget、imageBudget | 用量与费用边界 |
| contextVersionRefs、contextHash | 可追溯的输入版本引用和摘要，不表示独立快照表 |
| priorMessages | 经服务端选择和裁剪的历史 |
| checkpoint | 仅用于人工审批等 Agent Run 内恢复的有限状态 |
| traceContext | W3C traceparent/tracestate |

不得放入模型 Provider Secret、数据库凭据、完整客户联系方式、其他客户历史、内部成本底价或模型无须知道的权限明细。

## 14 Java 与 Python 内部 API

正式开发时以单一 OpenAPI/JSON Schema 生成两端类型；以下只定义职责，不作为第二套机器契约。

### 14.1 Java 调用 Python

| 候选接口 | 用途 |
| --- | --- |
| POST /internal/v1/agent-runs:execute | 执行短 Run；支持 JSON 结果或 NDJSON/SSE 事件流 |
| POST /internal/v1/agent-runs/{runId}:resume | 携带人工批准/拒绝决定恢复同一 Run |
| POST /internal/v1/agent-runs/{runId}:cancel | 通知取消；返回已观察到的状态 |
| POST /internal/v1/image-provider-jobs:submit | Workflow-only 生图提交；要求 Java 已建立业务任务、预算和幂等键 |
| POST /internal/v1/image-provider-jobs/{jobId}:refresh | Workflow-only 状态回查；返回标准化 Provider 状态 |
| POST /internal/v1/image-provider-events:normalize | P1 可选回调规范化；只有补齐回调原文、请求头白名单、事件幂等和保留策略后才启用 |
| GET /internal/v1/health/live | 仅判断进程存活 |
| GET /internal/v1/health/ready | 校验配置、契约和必要 Provider 可用性，不调用付费动作 |

首期只启用轮询，Python 始终留在私网，当前 16 表设计不承诺 Provider 回调收件箱。若未来 Provider 必须回调，应先形成 P1 设计，明确原始字节与必要请求头的保存位置、`providerEventId` 幂等、保留/删除和重放规则，再开放 Java/API Gateway 的窄入口；不能把整个 Python 智能体 API 暴露公网。

execute 请求至少携带：

~~~json
{
  "contractVersion": "1",
  "runId": "stable-id",
  "runAttempt": 0,
  "idempotencyKey": "stable-key",
  "correlationId": "business-correlation",
  "executionLeaseId": "signed-lease-jti",
  "fencingToken": 0,
  "runType": "SELECTION_STYLING",
  "agentVersionId": "published-version",
  "configHash": "sha256",
  "resolvedAgentSpec": {
    "systemInstruction": "published-content",
    "modelProfile": {},
    "modelConfig": {},
    "tools": ["product_search", "combo_validate"],
    "handoffs": [],
    "inputSchema": {},
    "outputSchema": {},
    "guardrails": {}
  },
  "deadlineAt": "RFC3339",
  "delegatedActorToken": "short-lived-signed-token",
  "allowedTools": ["product_search", "combo_validate"],
  "budgets": {
    "maxSteps": 12,
    "maxModelCalls": 6,
    "maxToolCalls": 20,
    "maxHandoffs": 2,
    "maxParallelTools": 4,
    "tokenBudget": {"maxTotalTokens": 30000},
    "costBudget": {"currency": "CNY", "maxAmount": "2.00"},
    "imageBudget": {"enabled": false, "maxRequests": 0, "maxAmount": "0.00"}
  },
  "businessRefs": {},
  "input": {},
  "checkpoint": null
}
~~~

### 14.2 Python 调用 Java Tool Gateway

候选接口为 POST /internal/v1/ai-tools/{toolName}:invoke。每次模型、Tool 或 Provider 外部尝试前，Python 先向 Java 申请 StepExecutionGrant；Java 在 `fq_ai_run_step` 持久化输入摘要、callKey、attemptNo、externalRequestKey、runAttempt、executionLeaseId、fencingToken、deadlineAt 和 queued 状态后，才返回 stepId、stepNo 与有效执行权。实际 Tool 请求包括 contractVersion、runId、stepId、callKey、attemptNo、toolCallId、idempotencyKey、deadlineAt、delegatedActorToken、executionLeaseId、fencingToken、quoteRowVersion、arguments 和 trace context。

Java 必须按当前真实身份与对象范围重新鉴权，不能因为 Python 已通过 guardrail 就信任调用。响应包括：

- resultStatus：SUCCEEDED、REJECTED、CONFLICT、WAITING、UNKNOWN 或 FAILED；
- result：经过字段投影的结构化数据；
- businessVersion（如 quoteRowVersion/comboVisualHash）；
- retryable 和稳定 errorCode；
- usage/budget change；
- 不含内部堆栈、SQL、Secret 或越权字段。

### 14.3 事件信封

Python 发出的事件包含 clientEventId、runId、runAttempt、stepId、stepNo、callKey、attemptNo、executionLeaseId、fencingToken、eventType、occurredAt、status、payloadSchemaVersion、redactedPayload、usage、errorCode 和 traceId。中间进度可实时转发但不逐条落业务库；只有改变 Step 状态、用量、外部受理 ID 或终态的事件才持久化。Java 校验租约、栅栏、Step 归属和合法状态后，以 `fq_ai_run_step.last_client_event_id + event_version` 幂等接受，并在同一事务递增 `fq_ai_run.last_event_seq`；序号只要求单个 Run 内单调递增，不暗示存在独立事件表。

标识与数据库字段的映射固定如下：

| 契约字段 | 数据库语义 |
| --- | --- |
| idempotencyKey（execute） | fq_ai_run.request_key，一次用户意图 |
| runAttempt | fq_ai_run.run_attempt，Worker 调度代次；首次为 0，接管后递增 |
| callKey | fq_ai_run_step.call_key，一个逻辑步骤，重试沿用 |
| attemptNo | fq_ai_run_step.attempt_no，一次真实外部尝试 |
| toolCallId | fq_ai_run_step.tool_call_id，模型产生的调用标识 |
| clientEventId | fq_ai_run_step.last_client_event_id，仅保存最近一次被接受的状态变更事件标识 |
| eventVersion | fq_ai_run_step.event_version，本 attempt 的状态变更序号 |
| eventSeq | fq_ai_run.last_event_seq，本 Run 已接受状态变更的单调序号 |
| fencingToken | fq_ai_run/fq_ai_run_step.fencing_token；旧 Worker 不能继续产生新副作用 |

### 14.4 服务认证

必须同时区分两层身份。

第一层是 Java 与 Python 进程之间的服务认证，生产至少采用以下一种经过平台批准的机制：

- mTLS + 短期服务 JWT；
- OAuth2 client credentials + audience 限定；
- 云平台 workload identity。

第二层是 Python 调 Java Tool Gateway 时携带的 delegatedActorToken。它由 Java 短期签发，至少绑定 jti、runId、executionLeaseId/fencingToken、用户、客户/报价范围、allowedTools、quoteRowVersion、expiresAt 和 Tool Gateway audience，并与第一层服务身份联合校验。它不是用户登录 Cookie，Python 不持久化或转交模型；过期、撤销、Run 终态或租约接管后都不得继续使用。

## 15 Tool 设计与策略矩阵

### 15.1 Tool 契约要求

每个 Tool 必须有稳定名称、版本、输入/输出 Schema、调用权限、是否有副作用、审批策略、幂等语义、超时、重试条件、错误码、数据分级和审计字段。Tool description 只说明用途，不能替代服务端校验。

### 15.2 首期能力白名单

| 能力 | 类型 | 暴露给模型 | 核心约束 |
| --- | --- | --- | --- |
| customer_brief_read | 只读 Tool | 是 | 只返回当前有权方案的必要字段 |
| product_search | 只读/确定性 Tool | 是 | Java 执行硬条件，最多返回规定候选 |
| product_detail_read | 只读 Tool | 是 | 批量、字段投影、版本化 |
| inventory_check | 只读 Tool | 是 | 返回 asOf、仓库范围和可用量 |
| price_read | 只读 Tool | 是 | 返回币种、税口径、asOf 和版本 |
| combo_validate | 确定性 Tool | 是 | 校验槽位、SKU、数量、预算、共享库存 |
| quote_calculate | 确定性 Tool | 是 | Java 唯一金额算法，输出不可由模型改写 |
| image_job_status | 只读 Tool | 是 | 只查当前 Run/方案所属业务任务 |
| artifact_stage | Workflow-only 文件能力 | 否 | 仅预签名 put/get，限制类型、大小、摘要和过期 |
| provider_image_submit | Workflow-only 外部副作用 | 否 | Java 已建任务、完成批准和预算预留；幂等键映射 provider request |
| provider_image_status | Workflow-only 外部查询 | 否 | 按 providerJobId 回查，不由模型决定轮询频率 |

模型可见 Tool 仅限表中标为“是”的能力；Workflow-only 能力由 Run Coordinator 的确定性节点调用。模型可以输出 ProposedImageAction，但不能直接调用付费 Provider。“proposal_apply、quotation_confirm、import_publish、customer_send、export_publish”也不作为 Agent Tool。用户在 UI 发起这些业务命令时，Java 重新鉴权和校验。

### 15.3 永久禁止的通用工具

首期不向模型暴露 arbitrary_http、sql_execute、shell、eval、filesystem、browser、任意 URL 抓取或动态安装依赖。确需新增能力时，封装为窄 Tool 并完成威胁建模、字段投影和审批策略。

## 16 幂等、超时、重试与补偿

### 16.1 重试分类

| 情况 | 行为 |
| --- | --- |
| Schema/业务校验失败 | 可向同一模型反馈一次纠正；仍失败则结束，不换规则 |
| 429/瞬时网络/明确未受理 | 有限指数退避，受 deadline 和预算约束 |
| Java 返回 401/403/409/410 | 不自动重试；返回用户处理或重新确认 |
| 外部提交超时、受理状态不明 | 标记 UNKNOWN，先按幂等键或 providerJobId 查询 |
| 已产生副作用后结果校验失败 | 不重复执行；记录结果并进入人工处理 |
| Provider 明确失败 | 按策略有限切换等价 Provider或降级；费用和质量分别记录 |

Provider SDK、HTTP 客户端、Agent 框架和 Workflow 不得各自默认多次重试，避免重试倍增。最终有效尝试次数必须从一个策略入口可计算。

### 16.2 幂等键

- Agent Run：requestKey 创建唯一 runId；Worker 重调度沿用 runId 并递增 runAttempt；
- Tool 逻辑调用：runId + callKey + toolVersion；模型原始 toolCallId 另行留证；
- Tool 真实尝试：runId + callKey + attemptNo；只有确定未受理才递增 attemptNo；
- 生图外部请求：providerCode + externalRequestKey，其中 key 由 `comboVisualHash + inputImageVersion + promptConfigHash + requestedVariant + requestIntent` 规范化得到；
- 采用：`quoteImageId + resultNo + expectedComboVisualHash`；
- 报价：`quoteId + quoteRowVersion + businessParametersHash`；
- 导出：`confirmedQuoteId + quoteHash + templateVersion + outputKind`。

幂等保证“相同意图不重复执行”，不表示不同用户主动请求的新候选应被合并。

### 16.3 补偿

金额、库存和正式报价采用数据库事务或新版本修订，不用模型生成补偿动作。生图等不可回滚外部调用只能记录费用、隔离晚到结果、释放未消耗预留并提供人工处理，不承诺取消即退款。

### 16.4 队列、背压、熔断与死信

- 异步任务和事件按“至少一次投递 + 消费端幂等”设计，不宣称跨数据库与第三方 Provider 的 exactly-once。
- Java 以持久任务、lease 和 nextAttemptAt 调度；Python 进程内队列不能作为唯一任务来源。
- 单 Provider 按模型 profile 设置并发、速率和熔断器。连续超时或限流达到阈值时停止新请求，进入降级路径，半开探测不得调用高费用批量任务。
- 队列积压触发背压：先拒绝或延后低优先级重生成，不把排队长度转化为无界 Python 并发。
- 重试耗尽、无法解析的供应商响应/事件、归属不明的对象和长期 UNKNOWN 进入人工处理视图；修复后以原幂等键回查或重放，不能新造请求绕过对账。未来启用回调时再增加回调专用验证和证据。

### 16.5 执行租约与 Fencing

Python 无状态且可多实例，所有可能调用模型、Tool 或 Provider 的动作采用以下顺序：

1. Java 通过行锁/CAS 领取 `fq_ai_run`，设置 lease_until；首次 runAttempt 为 0，租约过期后的新 Worker 接管时递增 `run_attempt`。
2. Java 签发短期 executionLeaseId，fencingToken 等于当前 runAttempt，并绑定 runId、过期时间和执行范围。
3. 每个真实外部调用前，Java 先插入 fq_ai_run_step attemptNo 和 externalRequestKey，再签发 StepExecutionGrant；没有已持久化 Grant，Python 不得调用外部系统。
4. Python 的 Tool、Provider 请求和事件都回传 executionLeaseId/fencingToken。Java 只允许当前租约发起新的副作用，旧 Worker 的后续请求返回 LEASE_FENCED。
5. 已经发出的外部请求可能在租约切换后晚到。Java 不盲目丢弃同 externalRequestKey 的 providerJobId/账单事实，而是隔离后原子对账；新 Worker 只能先查询原 key，不能重新提交付费动作。
6. 租约续期、接管、失效和 UNKNOWN 回查都写 Step/审计；进程内锁、请求超时或负载均衡粘性不能代替数据库租约。

## 17 状态、记忆、RAG 与文件

### 17.1 四类信息必须分开

| 类型 | 示例 | 存储与可信度 |
| --- | --- | --- |
| 权威业务事实 | SKU、价格、库存、权限、报价 | Java/MySQL，最高可信，带版本 |
| 会话上下文 | 用户本轮和历史消息 | Java Conversation/Message，裁剪后传 Python |
| 工作记忆/Checkpoint | 已完成步骤、待审批 Tool、有限中间结果 | Java Run/Step，结构化且可恢复 |
| 知识/RAG | 风格词典、搭配规则、品牌公开指南 | 可重建索引，来源和版本可追溯，不覆盖业务事实 |

首期不启用模型自主写长期记忆。用户偏好只有在 UI 明确确认后才写入客户/方案字段；Agent 可提出偏好建议，不能把一次对话猜测永久保存。

### 17.2 RAG 方案

首期优先用 Java 硬过滤 + 小规模候选排序，不为 10 万 SKU 直接把全量商品文本塞给模型。若需要语义检索：

1. 从已授权商品/规则生成带 sourceVersion 的 embedding；
2. 索引仅保存派生向量、稳定业务引用和最小可检索字段；
3. 权限过滤和有效性检查在 Java 查询路径执行；
4. 返回候选后再次读取当前商品事实；
5. 索引可按 sourceVersion 全量重建。

是否使用 MySQL 向量能力、独立搜索引擎或向量数据库属于后续 ADR，本轮不新增第二个权威数据库。

### 17.3 文件与图片

Python 不接收任意本地路径。Java 生成短期、限对象、限方法、限大小的预签名 URL；下载后验证 MIME、魔数、像素、大小和摘要。输出先进入隔离前缀，检查通过后由 Java登记稳定对象键。URL 过期可重签，URL 本身不是业务主键。

## 18 安全设计

| 威胁 | 场景 | 控制 |
| --- | --- | --- |
| Prompt Injection | 商品标题、OCR、图片元数据要求忽略规则或调用工具 | 不可信内容分区；Tool 白名单；服务端鉴权；不执行任意 URL/命令 |
| 越权与横向访问 | 模型猜测其他 customerId/quoteId | delegatedActorToken 绑定范围；Java 每次 Tool 重鉴权和字段投影 |
| 工具参数投毒 | 负数、超量、伪造版本、超长查询 | Pydantic + Java 双重范围/版本校验 |
| SSRF/恶意文件 | 模型返回 URL 或伪装图片 | 只用 Java 签发对象引用；域名白名单；MIME/魔数/大小/病毒检查 |
| 敏感信息泄露 | Prompt、trace 或模型日志包含客户联系方式/底价 | 最小上下文、字段分级、脱敏、受控 trace、保留期 |
| 供应商数据使用 | 图片/客户信息被第三方留存或训练 | 上线前审批地域、保留、训练政策和 DPA；发送最少数据 |
| 成本与拒绝服务 | 无限循环、并行生图、超长文件 | run/tool/token/cost 限额、队列、速率、80%告警和超额阻断 |
| 供应链风险 | 未锁版本、恶意依赖、SDK 突变 | 锁文件、来源/许可/SBOM、漏洞扫描、分阶段升级 |
| 模型输出污染 | 虚构 SKU、金额或图片来源 | 结构校验 + Java 事实重查 + 人工采用 |
| 展示与文件注入 | 模型文本进入 Vue、PPTX 或 CSV 后触发 XSS、链接或公式 | 默认纯文本；受限 Markdown 白名单；禁止原始 HTML；转义 CSV 公式前缀；限制长度、链接协议和 PPTX 字段 |

审批只能表达“当前用户同意执行该动作”，不能替代用户是否有权限、数据是否仍为当前版本、预算是否可用或参数是否合法。

## 19 性能、容量与可靠性

### 19.1 首期建议默认边界

以下是待压测确认的 Proposed 值，不是已经验证的 SLA：

- 单 Run 最多 12 个 Agent Step；
- 最多 20 次 Tool Call；
- 最多 2 层 handoff；
- 只读 Tool 最多 4 路并发；
- 同一模型纠正最多 1 次；
- 同一可安全重试外部请求最多 2 次；
- 同步 Agent deadline 默认 30 秒；需要更长则转异步；
- Python 请求硬超时不超过 120 秒；
- 生图、批量视觉和导出始终使用持久异步任务。

### 19.2 降级顺序

1. Rerank/LLM 不可用：返回 Java 硬过滤候选及明确“未做 AI 排序”。
2. Styling Agent 不可用：保留人工搭配与规则校验。
3. 生图不可用或预算关闭：使用商品原图拼版/上传图路径，不伪装为模型生成成功。
4. Trace 后端不可用：业务 Run 仍可完成，但 Java 保留最小审计；敏感数据不落本地临时日志。
5. Python 整体不可用：商品、导入、人工方案、确定性报价和已确认报价版本导出不应全部瘫痪。

### 19.3 健康与关闭

liveness 只检查事件循环；readiness 检查配置、Java 契约兼容、必要模型 profile 和连接池，但不发起付费调用。关闭时停止接收新 Run，等待有界短任务，未完成任务返回 checkpoint 或由 Java 标记可恢复；不能只靠进程内 background task。

## 20 可观测性、评测与审计

### 20.1 Trace

统一传播 traceId，并关联 conversationId、runId、runAttempt、stepId、callKey、attemptNo、toolCallId、agentVersionId、configHash、modelProfile、providerRequestId、quoteId、quoteRowVersion 和 comboVisualHash。模型请求、Tool、handoff、guardrail、等待和最终校验分别形成 span。

不记录隐藏思维链。可保留：

- 经脱敏的用户输入摘要；
- 模型结构化输出；
- Tool 名、参数摘要、结果摘要与错误码；
- token、估算/实际费用、延迟、重试；
- 采用、驳回、人工修改和原因。

### 20.2 指标

| 类型 | 指标 |
| --- | --- |
| 可靠性 | Run 成功/失败/未知率、恢复率、重复副作用数、终态一致性 |
| 性能 | p50/p95/p99 Run、模型、Tool 延迟；队列等待 |
| 质量 | Schema 失败率、虚构 SKU 拦截数、硬约束违反率、候选采用率、人工修改率 |
| 图片 | 提交/成功/可交付/采用率、旧版本隔离率、缺件/变款/色差/Logo 问题 |
| 成本 | 每 Run token/费用、每可采用图片成本、预算告警与阻断 |
| 安全 | Guardrail 拒绝、越权 Tool、注入测试命中和敏感字段脱敏失败 |

### 20.3 离线 Golden Set

至少建立：

- 需求解析：标准、口语、缺字段、冲突、恶意注入；
- 选品：有解、无解、库存不足、预算边界、旧版本；
- 搭配：1–4 品类、锁定项、共享库存、禁止组合；
- 报价解释：折扣、税费、费用、四舍五入，但金额由确定性 oracle 校验；
- 视觉：按 PRD 的 40 个任务、每轮 3 个候选、最多一次重试；逐品类统计可交付率和严重缺陷。

发布 Agent/Prompt/模型新版本前跑固定集，与当前生产基线比较；若硬约束、安全或金额解释出现回归则阻断发布。在线采用率只能作为信号，不能替代离线正确性。

## 21 部署、配置与发布

### 21.1 运行单元

- 一个独立 Python 容器/进程，首期 CPU 即可，模型通过受控 Provider API 调用。
- 私网访问，仅 Java 服务身份可调用；不暴露公网管理端。
- API 与 Worker 可先同仓同包、分进程部署；生图轮询量上升后再拆 Worker。
- 通过无状态实例横向扩容；Run 状态在 Java，不依赖粘性会话。

### 21.2 配置

配置分三类：

- 非秘密部署配置：端口、并发、超时、日志级别；
- 已发布 Agent 配置：Prompt、模型 profile、Tool、预算、guardrail，由 agentVersionId 固定；
- Secret：Provider key、服务身份和对象存储凭据，只来自秘密管理系统，不进入数据库正文、Prompt、日志或 Git。

### 21.3 契约兼容

Java 与 Python 使用 contractVersion 和 Schema 版本。新增可选字段先双端兼容，再切换生产；删除或改变语义需新 major 版本。部署顺序优先“消费者先兼容、提供者后切换”，并保留上一兼容镜像和 Agent 配置以便回滚。

### 21.4 发布门

1. 依赖/许可/漏洞/Provider 数据政策通过审查；
2. 单元、契约、集成、故障注入和 Golden Set 达标；
3. 预发使用非生产或脱敏数据，通过预算上限与恢复演练；
4. 小流量只读建议模式；
5. 观察质量、成本、越权、未知状态和人工修改；
6. 才逐步开放图片付费动作，正式采用仍保持人工确认。

## 22 端到端时序示例

### 22.1 “100 人、四档、每档三套、预算 300”

~~~text
用户 → Java：提交自然语言需求
Java：鉴权，保存消息，创建 REQUIREMENT_PARSE Run
Java → Python：execute（固定版本/预算/工具）
Python → LLM：结构化解析
Python → Java：歧义或 ConfirmedBriefDraft
Java → 用户：展示字段并确认
用户 → Java：确认需求，写入当前 quoteId/quoteRowVersion
Java：按四档分别执行硬过滤
Java → Python：SELECTION_STYLING Run + 四档合计最多12个候选、每档最多3个
Python → Selection/Styling Agent：排序与组合
Python → Java Tool：combo_validate
Java：校验SKU、价格、库存、槽位、数量和 quoteRowVersion
Java → Python：通过/逐项拒绝原因
Python → Java：结构化候选与解释
Java：保存为候选，不写确认报价
用户：锁定、替换、采用
Java：再次按当前 quoteRowVersion 重验并保存
~~~

### 22.2 生图异步任务与结果回收

~~~text
用户 → Java：对已采用 combo 请求生成3张图
Java：权限/素材/额度检查，创建 fq_quote_image（quoteImageId），记录预算状态
Java：创建IMAGE_SUBMIT Run及tool Step，先保存externalRequestKey
Java → Python：携带StepExecutionGrant与fencingToken执行提交
Python → Provider：原样携带稳定externalRequestKey提交
Provider → Python：providerJobId
Python → Java：携带当前fencingToken返回“已受理”
Java：原子保存providerJobId，IMAGE_SUBMIT Run成功
Java：fq_quote_image继续记录执行状态，并按退避计划触发状态回查
Java：先建回查Step，再向Python发短期Grant
Java → Python：provider status refresh（固定jobId，不经过模型）
Python → Provider：查询providerJobId
Provider → Python Adapter：结果或失败
Python → Java：标准化外部结果事件
Java：更新fq_quote_image并登记隔离区对象
Java → Python：创建短时IMAGE_RESULT_REVIEW Run（如需要模型质检）
Python → Java：质量标签、候选对象引用与警告
Java：登记“待人工复核”
用户：采用或驳回
Java：重验 comboVisualHash/inputImageVersion 后写采用记录
~~~

### 22.3 审批

~~~text
Orchestrator 返回 ProposedImageAction
  → Java Policy 判定费用动作需要审批
  → Java 保存 DeferredAction + 参数摘要
  → Java 保存 WAITING_APPROVAL
  → 用户在 UI 批准
  → Java 重新鉴权、检查版本/额度/过期
  → Java 创建真实任务
  → Java先持久化tool Step并签发StepExecutionGrant
  → Python Workflow凭Grant调用provider_image_submit
  → Java 保存业务图片任务的排队、执行和费用状态
  → Agent审批Run在提交受理后结束
~~~

用户拒绝、审批过期或业务版本变化时，原动作结束；模型不能把拒绝改写为另一个等价付费调用。

## 23 验证策略与验收矩阵

| 层级 | 必须验证 | 证据 |
| --- | --- | --- |
| Unit | Schema、状态转换、预算、错误映射、Output Validator、Prompt 渲染 | pytest 报告 |
| Contract | Java/Python 请求、事件、Tool、版本兼容、错误码 | 双端生成类型与契约测试 |
| Integration | Mock Provider、Java Tool Gateway、审批暂停/恢复、图片任务回查、取消、晚到结果 | 运行日志和状态表断言 |
| Fault injection | 超时、429、重复轮询结果、进程重启、UNKNOWN、trace故障 | 故障场景记录和无重复副作用证明；Provider 回调启用后另补回调重放测试 |
| Security | 越权引用、Prompt Injection、SSRF、恶意文件、秘密扫描 | 安全测试记录 |
| Eval | 需求、选品、搭配、视觉 Golden Set；新旧版本对比 | 数据集、评估报告和发布判定 |
| Performance | 10万SKU业务检索、候选p95、并发Run、队列和Provider限流 | 实际压测报告 |
| Recovery | Java/Python重启、等待态恢复、数据库与对象恢复 | RPO/RTO演练证据 |

设计审查通过不等于这些测试通过。真实实现结果必须追加到 [FEAT-FASHION-001 验证记录](../../../../功能/FEAT-FASHION-001-服装智能选品生产首版/验证记录.md)。

## 24 实施阶段映射

可执行任务、状态和退出门只在 [FEAT-FASHION-001 任务清单](../../../../功能/FEAT-FASHION-001-服装智能选品生产首版/任务清单.md)维护。本架构不再保留另一套 M0～M4 里程碑，只说明 Python 能力落在哪些统一实施阶段：

| 实施阶段 | Python / AI 架构交付 |
| --- | --- |
| IMP-01 | 框架 Spike、双向契约、服务认证、解析前 body limit、依赖与锁文件 |
| IMP-02 | Java/MySQL Agent 六表持久化底座；Python 不建库 |
| IMP-05 | Requirement Agent、Run/Step 租约与 fencing、人工确认 |
| IMP-06 | Java 冻结候选、Selection/Styling Agent、Tool Gateway 与采用校验 |
| IMP-07 | Vision/Image Adapter、图片任务回查、复核、费用和降级 |
| IMP-08～09 | Python 只提供解释或受控文案建议；金额、版本、文件仍由 Java 确定 |
| IMP-10 | 真实 Provider 质量/费用、安全、恢复和生产候选证据 |

## 25 待决策项

以下决定会实质影响实现，不能由框架默认值代替：

1. 首期 LLM、视觉、Embedding/Rerank 和生图 Provider，以及数据地域、保留和训练政策。
2. Python 版本、PydanticAI 具体版本及是否允许使用仍处 0.x 的高层 Harness 能力。
3. 私网服务认证采用 mTLS、OAuth2 client credentials 还是工作负载身份。
4. 对象存储、队列/回调设施和部署平台的现状。
5. 真实模型月度/单任务费用上限、币种、告警接收人和超额处理。
6. 首期是否只启用 Requirement + Selection/Styling，Orchestrator 和长期记忆继续关闭。
7. 何种复杂度或可靠性证据会触发 LangGraph/Temporal/DBOS 等升级。

建议默认批准第 6 项：先启用单 Agent 与局部显式 Workflow，不开启自由多 Agent 和自主长期记忆。其余项在 `IMP-01` 或进入 `IMP-10` 真实试点前依据实际基础设施集中决定。

## 26 停止、回滚与退出条件

- 框架不能稳定产生结构化输出、Tool 权限难以封闭或依赖风险不可接受：停止该框架，保留公共契约，切换候选内核。
- Golden Set 出现硬约束、越权或金额相关回归：阻断 Agent 版本发布，回滚上一已验证版本。
- Provider 费用、延迟或数据政策不满足：关闭对应 profile，降级到规则/人工路径。
- Python 服务故障：不影响 Java 的商品、导入、人工编辑、确定性报价和已确认报价版本读取。
- 生图出现费用状态 UNKNOWN：暂停新重试，先对账和查证；不通过重复调用“试试看”。
- 需要退出 Python Runtime 时，Java 保存的业务事实、Run/Step、Schema 和 Tool Gateway 保持可用，替换模型内核不应迁移商品、报价或客户数据。

## 27 当前完成范围与限制

截至 2026-09-12，市场框架调研、项目化技术设计、`python/fashion-ai-runtime/` 的 fail-closed 契约骨架，以及 `contracts/fashion/ai-runtime.openapi.yaml` 已落盘。依赖只安装在工作区隔离环境，默认 Provider 固定为 disabled；定向测试结果与命令见 [FEAT-FASHION-001 验证记录](../../../../功能/FEAT-FASHION-001-服装智能选品生产首版/验证记录.md)。这只证明当前模型、路由、错误信封和提交契约在已测输入下工作，不代表真实 Agent 或 Provider 可用。

尚未完成：

- `ruoyi-backend/ruoyi-fashion`、`admin-web` 服装页面和 MySQL 迁移；
- Java/Python 服务认证、解析前请求体上限、通用 Agent Run 租约、fencing、幂等持久化和预算闭环；
- 真实 PydanticAI Analyzer、模型及图像 Provider、业务 Tool Gateway 和评测集；
- 启动共享网络服务、使用真实商品/客户/图片账号或发生供应商费用；
- 容量、RPO/RTO、桌面兼容、生产安全、用户验收、上线批准或发布。

下一步先由用户审核生产任务清单的 `IMP-01`～`IMP-10`；通过后启动 `IMP-01` 安全与契约基础，再按退出门依次实施“自然语言需求 → Java 硬过滤 → Python 类型化排序/搭配 → Java 复核 → 人工采用 → 确定性报价预览”。
