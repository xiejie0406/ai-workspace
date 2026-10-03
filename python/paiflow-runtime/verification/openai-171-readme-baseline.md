# PaiFlow Python 执行器

本工程已经引入原 `core/workflow`、`agent`、`plugin/link`、`plugin/aitools`、`common` 中 427 个算法及 DTO 源文件，原 21 类节点注册表和各节点类均保留。没有引入原服务 `main.py`、管理 API 路由、部署脚本、env 或原外部联机测试；完整来源、引入 hash、13 处剥离的可执行示例和 130 个排除文件的理由在 `import-manifest.json`。Apache 2.0 `LICENSE`、`NOTICE` 沿用原项目。

真实原 `WorkflowEngineFactory` 已通过 `worker/engine_adapter.py` 接入 `TaskRunner`。原 `WorkflowDSL`、`Chains`、`VariablePool`、节点工厂、DFS/并行、分支、迭代引擎及原 callback 排序算法实际参与离线运行；没有编写替代的四节点引擎。当前恢复 9 类纯节点路径：Start、End、if-else、text-joiner、node-variable、iteration、iteration-node-start/end、message；增加 12 类受控消费者：ifly-code、spark-llm、extractor-parameter、decision-making、question-answer、knowledge-base、flow、plugin、knowledge-pro-base、agent、database、rpa。完整 21 类均由原类和原 Factory 注册；这只表示原节点算法与中心协议消费者已接入，不代表 21 类正式能力已就绪。变量要求真实持久作用域，模型/代码/知识/子流程/插件/Agent/数据/RPA 要求中心受控端口和真实 Producer，缺失时明确拒绝。问答当前仅适配顶层节点；子流程及新五类操作缺中心实际迭代实例归属时拒绝。数据库 CUSTOM SQL、原 Agent 的独立 ReAct Producer 等仍未恢复，不以部分算法代替这些范围。

注册入口是 `create_task_runner(center, ...)`；没有启动原管理 API、业务服务或 Worker 常驻领取循环。构造 `TaskRunner(center, None)` 仍会拒绝。离线测试和源文件存在不能证明完整 21 节点已联机运行。

## 已实现行为

- `worker/center_client.py` 用配置的中心地址和 Worker 服务身份调用 `lease/verify`、`execution/begin`、`heartbeat`、`events`、`result`、运行资源和操作接口。只接受若依 `code=200/data`，拒绝重定向及异常信封；没有中心成功确认不会返回业务成功，不自动重试费用/写入操作。
- `worker/task_runner.py` 读取完整 `paiflow.worker.v1` 快照并核对 hash、引擎、节点和策略，再向中心核对租约、版本和事件序号。运行期间有心跳、容量限制、截止时间和中心取消；失去租约只停止执行，不自建本地业务终态。副作用逐次交给中心授权和去重，本地 `approved_write` 字段不能代替中心审批。
- 初次 verify 必须返回完整 `assignment`，逐字段核对 input、resourceSnapshots、effectPolicy、规范化字节和全部固定任务字段；仅同 DSL hash/版本不足以接受外来参数或策略。完成前再次验证租约，正式终态仍由中心 CAS 保存。完成前 verify 必须包含 Boolean cancelRequested；若中心已先请求取消则转真实停止路径，不提交成功。COMPLETED 的 CAS 拒绝仅读一次中心租约，只有仍有效且明确取消才清理后确认取消；其他拒绝不造取消。未确认外部 effect 或停止仍保留 EFFECT_UNKNOWN，已发生节点/用量不撤回。
- `execution/begin` 是中心行锁发放的一次性执行许可，verify 不授予执行。Assignment/本地能力/完整 verify/归属校验后，Worker 只请求一次并严格核对 begun/runId/leaseId/attempt/workflowVersionId/dslHash 全 ACK，才进入原 Factory。409、超时、ACK 丢失/不完整/绑定变化都不进入引擎、不自动重试、不提交 events/result；有 approved_write 能力报告 EFFECT_UNKNOWN 错误交中心核对，纯图也拒绝重复。这样重复方不能关闭真正持许可方的在途 run。QA 当前在途引擎心跳继续只 begin 一次；跨进程重建不获重复许可。
- `worker/events.py` 串行编号并等待中心 ACK；断线只保留一个未确认事件，核对时重发同一 seq/body，不改变序号继续运行。业务事件和结果只由中心保存。
- `worker/engine_adapter.py` 把原节点生命周期、文本/推理增量和 usage frame 转成中心事件，保留原 frame 数据与排序。迭代附稳定批次 `iterationId`，每批保留节点开始/完成。消费者异常会停止真实引擎，排序队列有界；中心取消/失租约沿实际 DFS/迭代子任务清理，没有用本地假终态结束。
- 原 Span 改为有界、无 DB/OSS/OTLP 初始化的诊断接口；原 WorkflowLog/NodeLog 的聚合逻辑保留，超预算 trace 明确需要中心 artifact。正式节点事件仍实时等待中心 ACK。原可执行 pickle 引擎缓存停用，反序列化立即拒绝；测试证明恶意 pickle 不能创建宿主机文件。
- `worker/variables.py`、`worker/history.py` 把原正式 Redis/SQL 写入迁为当前租约绑定的中心端口，要求 revision/ACK；变量写入先读中心 revision 后 CAS，原节点重试复用同一逻辑写请求和幂等键。Worker 不保存正式变量/历史；尚未绑定 scope、端口不可用或拒绝时停止。
- 旧 workflow/Agent/Link repository、SQL 数据库、计量认证和原 history/app/flow 控制入口通过导入约束封禁。公共服务取得数据库 Session 或创建数据库服务也直接拒绝。这个约束不是操作系统沙箱，不能用于允许不可信 Python 在宿主机执行。
- 原 `LocalExecutor` 和所有 `CodeExecutorFactory` 别名改为 `sandbox/executor.py` 的中心操作代理，代码不在本进程 `exec`。真实受限 Runner 尚未装配时明确不可用，不降级到宿主机运行。原代码节点保留参数/输出算法，只调整执行委托并移除完整代码遥测。
- `worker/model_bridge.py` 复用原 Spark/OpenAI SDK 类的帧解码、Spark FunctionCall 解析；原 LLM JSON、参数提取修正、Decision 三种模式与分支算法实际运行。只接受固定 modelResourceId 和中心一致的 provider/version/modelName/enabled；原节点 URL/apiKey/apiSecret/env 拒绝，模型费用操作仍须中心逐次审批。模型正式历史快照和授权图片对象未接通时明确拒绝。
- 模型操作草案保存于 `protocols/model-operations-draft.schema.json`。frames 必须回读中心 provider/resourceVersion/游标/完成状态，原终帧等待中心 COMPLETED；取消没有 stopped=true 时保留 EFFECT_UNKNOWN。中心授权/协议/传输失败是粘性边界失败，不会被原 Decision DEFAULT 吞掉。明确 failureKind=provider、已结束且原合法错误帧匹配时，保留原 Spark CodeConvert 错误映射和 Decision 默认分支；操作审计仍由中心保存 FAILED。原 OpenAI length/content_filter/function_call/tool_calls 结束错误沿原 OPEN_AI_REQUEST_ERROR，未知帧仍拒绝。
- `worker/checkpoints.py` 与原 QuestionAnswerNode 对接中心 create/poll/consume，原 EventRegistry 导入仍封禁。保留原 option 列表顺序、显示名/路由 id 区别、content_type、direct 回复、needReply 下 ignore 拒绝、abort 失败和 LLM 槽位补全/重问次数。题面模板仍由原节点替换；暂停帧转成 ACK 关联的 checkpoint 事件。正式回复、revision、到期/取消、消费去重均由中心保存。
- `worker/knowledge_bridge.py` 对接原普通 KnowledgeNode/KnowledgeClient，保留原 query/topN/ragType/match/history payload 和完整 data.results/metadata。repoId/docIds/flowId 只允许中心固定资源绑定，版本/撤权由中心实时核对；中心缺 knowledge.retrieve、真实 AIUI Producer 或已启用的历史 scope 时拒绝。只有合法 Provider 失败沿原 KNOWLEDGE_REQUEST_ERROR。普通与专业 RAG 仍有不同算法和资源要求。
- `worker/flow_bridge.py` 对接原 FlowNode，保留输入、SSE delta/reasoning/usage、三种输出模式与原错误码。中心固定 flowResourceId/子版本/hash/输出模式，持有正式 childRunId、父子关系和操作状态；旧数据库 App 凭据/HTTP 入口封禁。子终帧必须等待中心完成，取消须确认 stopped=true，否则 EFFECT_UNKNOWN；缺 workflow.invoke 不在本地递归造子run。
- `knowledge/processor.py` 实际抽取 UTF-8 文本、Markdown、HTML，剥离 HTML script/style 等内容，保存可回查的规范化文本 offset 和原文档 hash。PDF、Office、OCR、任意 URL/磁盘读取未接通，明确拒绝。
- `knowledge/index_store.py` 对实际传入 embedding 做规范化和余弦检索，保存不可变版本派生文件。没有随机或固定 embedding 假响应；检索需要中心当前授权文档版本、索引版本和内容 hash，撤权/删除后旧文件仍在也不能返回对应片段。索引文件不保存中心资源/索引任务状态。

## 离线检查

已获授权在本工程独立 `.venv` 恢复原 Workflow 锁中的 12 个最小纯运行依赖。`requirements-pure.lock` 和 `verification/dependency-restoration.json` 保存确切版本、Python 3.12.4、锁来源、requirements hash、安装版本和许可元数据。为导入原 Spark 帧/FC 类，再按原锁恢复 websockets 12.0、tenacity 9.1.2；版本、实际下载/安装和许可事实保存于 `requirements-model-codecs.lock`、`verification/model-codec-restoration.json`，共 14 包。没有统一升级，也没有安装 SQL/OpenAI/aiohttp/OTLP 的完整环境；原 SDK 网络 Producer 尚未装配。`.venv` 不纳入源码引入或扫描。重复检查不安装依赖、不启动服务、不连接数据库或 Provider：

```powershell
& '.venv/Scripts/python.exe' -B scripts/run_offline_checks.py
```

当前测试数量和实际通过情况保存在 `verification/unittest.log`，具体命令、退出码和时间保存在 `verification/checks.json`。`tests/test_original_engine.py` 使用真实原引擎，覆盖实际 Start/End、true/fallback 分支、嵌套数组引用、文本拼接/拆分、三批迭代顺序与关联、Message/Prompt End、中心变量 CAS、缺 scope/拒绝 ACK、独立变量池及实际子任务取消清理。迭代开始输出 required=true 的回归发现原 `NodeExecutionTemplate` 未沿用 Start 写回分支，已按原 Start 校验/写回逻辑覆盖 iteration-node-start，没有降低 required 来规避失败。

`tests/test_controlled_nodes.py` 实际运行原 CodeNode 模板/参数注入/JSON 与非 JSON 输出、类型失败及宿主机代码阻断；模型测试实际运行 Spark/OpenAI 原帧解码/usage、LLM JSON、提取 Prompt/FC 修正、Decision FC/Prompt/Normal 路由和 Prompt End 流。固定资源变更、旧 URL/凭据、只读费用、坏游标、中心拒绝、非法 Provider 错误和停止未确认均有定向测试。原 Provider 错误映射与合法 Decision DEFAULT、OpenAI length 终止也有独立证据。测试中心仅提供保存的原 SDK fixture，不能证明真实 Model/沙箱 Producer 已联机；此前 53 项纯引擎通过基线保存在 `verification/pure-engine-53-baseline.log`，包含 QA/Knowledge/Flow 后的 105 项通过基线在 `verification/flow-knowledge-105-baseline.log`；许可接入后的 113 用例集合另行实际运行并保存 `verification/execution-begin-113-baseline.log` 和 `verification/execution-begin-baseline.json`；新增 Flow 迭代拒绝后的取消修复前 114 项通过日志在 `verification/pre-cancel-114-baseline.log`。Flow 测试 fixture 遗漏原必填声明的首个失败证据在 `verification/flow-first-full-failure.log`，修正只补测试声明，没有放宽原节点要求。

`tests/test_question_answer.py` 运行真实原问答节点，覆盖 direct/option、原默认选项顺序、ignore/abort、完整队列回复包装、两轮槽位补全、最大重问次数、原超时错误码、中心拒绝/坏 metadata、等待心跳及取消、消费与到期/取消竞争、迭代归属拒绝、revision 从 0 开始、共享 HTTP/schema 同字段、安全幂等键及已停止检查点不重开。保存的回复 fixture 不是正式用户输入。模型或沙箱请求已经发出但 ACK/操作标识还未回读时取消，不能确认 Producer 停止，明确提交 EFFECT_UNKNOWN；这也有实际 TaskRunner 定向测试。

`tests/test_knowledge_node.py` 实际运行原普通知识 query/results 算法，验证完整 metadata、固定 repo/doc/flow/version、缺中心 Producer、不用旧 env、拒绝无 scope 历史及 Provider/授权错误区别。`tests/test_flow_node.py` 实际运行原子流程三种输出、推理/usage、原错误/无 choices 终止、固定子版本/childRunId/游标、未完成终帧拒绝、只读/历史拒绝与确认停止。fixture 不创建正式子run或外部检索请求。算法与 ACK 草案不代表资源生产端已联机。

`tests/test_execution_begin.py` 检查共享 ExecutionBeginAck、固定请求、完整绑定/JSON 类型、409/超时/坏 ACK、前置校验顺序、实际 Factory 未调用、跨两个 TaskRunner 重复许可拒绝而首个执行继续，以及 begin 期间取消不执行/不写终态；同时覆盖最终 verify 取消、result 行锁取消竞争、未知 effect 不假报取消、非取消冲突和错误 cancel flag 拒绝。离线 BeginCenter 仅模拟中心许可 ACK 和拒绝，不承担正式运行真相。

原有用例继续覆盖中心信封/固定目标/拒绝语义、连续事件与重发同一 ACK、租约/取消/截止时间、终态存储失败、重复活跃租约、资源/只读策略、原 SQL/repository 和宿主机代码阻断，以及真实文本切分/向量排序/不可变索引与撤权。共享机器 schema、中心生成的 Unicode/指数浮点/负零 fixture 仍实际验证：使用 `dslCanonicalBase64` 原字节计算 hash 后核对 DSL，没有 Python 重新序列化 fallback；只有中心生成 run 终态事件。测试端口 `RecordingCenter`、`VariableCenter` 和旧边界用例的 `ExercisingEngine` 不是正式中心/引擎。`source-verification.json` 保存当前源 hash、语法和静态扫描边界；检查不能证明全部外部调用安全或业务已通过。

## 后续必须接续的完整范围

1. 原五个 pyproject/uv/poetry 锁保存在 `upstream-dependencies/`，均保持原内容。Workflow、Link、Agent 对 aiohttp/Pydantic/OTLP 等存在冲突，当前只恢复最小纯引擎环境，没有声称五个完整环境已统一。需要实际规划独立能力环境/进程，保护原锁并验证所用依赖与许可。
2. 纯路径已接入原 `WorkflowEngineFactory`/DFS/分支/并行/迭代/变量池及 callback，正式持久变量/history 端口仍等待中心真实绑定与表；checkpoint 的正式 QA 图派发、迭代归属和跨进程交互恢复、子流程正式资源/Producer 仍需继续改造。不能绕过 guard 或启动原服务。
3. 专业知识、Agent、Link HTTP/MCP、结构化数据和 RPA 的原 Workflow 节点已增加中心消费者；其正式资源、原独立算法服务/Runner、审批、effect ledger 和取消 Producer 仍需接通。数据库 CUSTOM SQL 的解析/翻译尚未恢复，不能将结构化 mode1..4 当作原全部 SQL 能力。文件/对象、多模态与播客的实际客户端仍需按中心协议改造。模型已恢复原受控节点算法和协议适配，但 model.invoke/frames/cancel、真实 SDK Producer 及模型资源接口尚未落地；沙箱同样缺真实 Runner。这里保留了源码但尚未正式联机或副作用验证，专业知识不能当成普通知识别名。
4. 中心 `/internal/paiflow/v1` 的租约/一次性许可/事件/结果/顶层检查点源码已由中心模块接续实现；没有启动服务或连库验证。资源/操作及本工程持久端口草案仍需逐能力完成。联合 schema/规范化字节已离线验证；真实领取/心跳/恢复/去重/CAS 联机仍未验证，客户端拒绝不可用协议。
5. 运行的 execution_started_at 增量 SQL 和历史在途保守回填由中心保存，尚未执行 DDL；不能把源码 gate 已实现当成既有数据库升级完成。真正的受限沙箱必须验明只读挂载、禁网、CPU/内存/时间限制和停止回读；知识任务还需完整文件格式、真实 embedding、对象分发、索引注册和任务进度。中心业务状态、配额和审批仍由若依管理。

## 原引擎恢复的依赖事实与接续顺序

`dependency-port-report.json` 从本工程保留的 pyproject 与锁文件读取实际版本和源码静态依赖链，可用 `python -B scripts/analyze_port_dependencies.py` 重建。锁定基线不能直接混装：Workflow 为 aiohttp 3.10.10 / OpenAI 1.60.2 / Pydantic 2.9.2 / OTLP 1.25.0；Agent 为 3.11.14 / 1.69.0 / 2.11.1 / 1.27.0；Link 为 aiohttp 3.12.15 / Pydantic 2.11.7；AITools 为 aiohttp 3.12.15 / OpenAI 1.106.1 / Pydantic 2.9.2。Workflow 的 SQLModel 0.0.19 和其他组件 0.0.24/0.0.25 也不同，且 SQLModel 不应继续用于正式库。Common 自身 poetry/uv 锁的 Pydantic/OTLP 也不一致，不能声称已经有一个唯一环境基线。

恢复前，系统环境只读导入原 `workflow.engine.dsl_engine` 首个错误是缺少 `opentelemetry`。当前纯引擎已移出强制 OTLP/DB/OSS 初始化，并在最小锁环境成功导入及真实执行；没有关闭 guard。原 `FlowNode → domain.models.ai_app/database.utils` 链已移出正式节点路径，旧 `_assemble_request` 明确封禁；原 `QuestionAnswerNode → cache.event_registry` 已改为中心检查点路径，旧缓存直接导入仍拒绝。机器报告包含方法内懒加载可达链，不等于已执行。

1. 继续从完整 21 类显式懒加载注册表逐类恢复真实资源适配。已恢复纯节点不能代替模型、专业知识、Agent、子流程、交互、数据库、RPA 或代码沙箱节点。扩展引擎 `supported_types` 前必须完成对应中心绑定、真实算法测试和失败/停止验证。
2. 原历史 `history_v1/history_v2`、多模态消息处理/裁剪算法保留；当前纯图没有历史输入，并不宣称模型记忆已完成。下文草案定位原 scope；正式历史、checkpoint/会话和版本快照由中心持有。
3. `FlowNode` 已按中心固定资源描述恢复原输出模式/帧算法，正式子版本授权、子run Producer、父子关系与循环/深度限制仍等待中心真实实现。`QuestionAnswerNode` 已改中心 checkpoint/revision/CAS 一次性消费，没有额外恢复令牌；迭代内问答、跨进程引擎重建、原缺失 resume 页面/API 兼容仍未接。不能绕过 guard 的原 DB/cache 路径；取消沿原任务树传播并按实际中心停止确认回归。
4. 原模型/Agent/Link/AITools 优先保留各自原环境和协议，由已登记受控能力端口连接。Worker 节点的 URL/env/secret 都映射到中心授权资源与操作，仍保留原 provider/专业检索/多模态算法差异。无需为了同进程导入而未经验证统一升级整个依赖栈。

这只是原依赖事实和继续实施入口，不改变完整迁移目标；21 类节点、Agent、Link、AITools、知识及正式运行仍需全部接通并验证。

## 原变量与历史的精确作用域

机器草案在 `protocols/persistence-draft.schema.json`，明确标为尚未部署，不能当成共享正式契约。verify 可选 `executionContext` 必须来自中心真实绑定，字段为 `flowId/userId/appId/chatId`，`chatId` 的 null 与空串保留区别。Worker 检查原 scope 与绑定一致，接口 body 不接受自由 uid/app/chat/flow。没有正式 app/chat 绑定时中心不应返回该字段；Start/End 等纯节点不依赖它，node-variable/history 明确缺资源。

原 `core/workflow/engine/nodes/global_variables/global_variables_node.py:VariablesManage` 的 add/get/get_all/clear 使用同一个 Redis hash：`chat_id is None` 时为 flow_id + uid + app_id，否则再加 chat_id（包括空字符串）；没有 TTL。GlobalVariablesNode 的 flow 来自 `ParamKey.FlowId`，uid/app 来自 Span，chat 来自 VariablePool。原 `chat_service.py:531` 用 `chat_vo.flow_id` 设置 FlowId，原 `VariablePool.add_init_variable` 从 Span.chat_id 取得 chat。迁移不把版本号或 runId 改成这些归属，也不改成每次运行私有变量。

`POST /runs/{runId}/variables` 请求包含 leaseId/attempt/nodeId/operation。get_all ACK 为 accepted=true/revision/variables；get 包含 found 和可选 value。set 必填 variableName/value/expectedRevision/idempotencyKey，clear 必填 expectedRevision/idempotencyKey；写入 ACK 的 revision 必须大于原预期 revision。写前读取中心当前 revision，写入及去重由中心 CAS 承担；Worker 的锁和原请求缓存仅用于当前运行并行/重试关联。同键同请求返回同 ACK，异请求拒绝。

原 `history_service.py:add_history` 保存完整 raw_question/raw_answer JSON 字典，归属为 flow_id/node_id/uid、可空 chat_id；原节点公共写回调用未显式传 chat_id，因此原路径默认为 null。原 get_history 按 flow+uid、每 node 最近 10 条读取（默认），保留既有预算过滤和消息顺序；chat 参数并没有改成强制读取条件。原 `HistoryItem` 是 role/content:string/content_type(text/image/audio)，其他消息字典/多模态 content 列表仍按原 JSON 保留。

`POST /runs/{runId}/history` 请求保留 question/answer 完整对象，包含 leaseId/attempt/nodeId/iterationId?/idempotencyKey；ACK 必须 stored=true 且 revision 为正整数。同 run/attempt/node/iteration 键、同 JSON 去重，异内容拒绝。此轮未实现中心历史读取端口或宣称模型记忆恢复；启用历史的原模型/Agent/交互节点须取得正式授权历史快照后继续接入，普通 direct 问答不依赖虚构历史。

## 原问答检查点的精确协议与限制

请求/响应唯一机器契约为 `../../contracts/paiflow/checkpoints.schema.json`；`protocols/checkpoints-draft.schema.json` 的八个定义只引用共享契约，保存原 source 与迁移差异。原 `EventRegistry` 将回复存为 `{message: JSON字符串{event_type,content}, metadata:{retries,timestamp}}`，迁移原样交给节点解析。event_type 仅 resume/ignore/abort、content 是完整字符串；原首回复 retries=0，后续递增，中心按 ordinal-1 生成该值及 Unix 秒 timestamp。没有原 nonce 业务约束；Worker 服务身份、当前 run/lease/attempt/checkpoint/revision 与中心 CAS 绑定消费者，不增加 token 或签名密钥。

create 请求包含固定 nodeId、ordinal、question、needReply、timeoutSeconds、idempotencyKey；问答参数仍位于 `dsl.nodes[i].data.nodeParam`。原 timeout 输入 1..5 分钟，每次 fetch 等待 timeout*60 秒；迁移保留分钟字段，避免节点重试把已转换秒数再次乘 60。中心 deadline 取 run 截止时间与本次等待上限的较早值。原 direct.handleResponse 的 maxRetryCounts 为 1..5，current_retries 从 1 开始，后续使用原 metadata.retries+1；需要重问时中心新建 ordinal+1 的检查点。

ACK 保留 checkpointId/revision/state/deadline/stopped；中心 revision 从 0 开始（创建 0、回复 1、消费 2）。poll body 为 leaseId/attempt/afterRevision，不返回回复；consume body 为 leaseId/attempt/expectedRevision/idempotencyKey，只有 RESUMED 可一次消费。成功返回 consumed=true/state=CONSUMED/stopped=true 和完整原 resumeData；消费与取消/过期并发时返回 consumed=false/state=CANCELLED|EXPIRED/stopped=true，不能当用户回复。幂等键为安全前缀加 JSON tuple SHA256，满足共享 8..128 安全字符约束并保留 null/空字符串区别；create 返回已有 CONSUMED/EXPIRED/CANCELLED 时显式停止，不重开或再次消费。同键同完整请求回原回执，异请求、旧 attempt、跨 run、未回复和旧 revision 由中心拒绝。

中心检查点源码由中心模块接续实现，但未启动/连库/打开原 QA 页面验证，也未开放 QA 图的正式派发。当前适配只处理顶层问答，迭代内归属明确拒绝；原动态选项方法本身未实现，迁移不伪造该能力。已消费后的跨进程流程重建尚无完整恢复协议，当前不会宣称原交互恢复或完整 21 节点上线就绪。

## 普通知识与子流程的精确草案及未接通范围

`protocols/knowledge-draft.schema.json` 固定 knowledgeResourceId 与 kind/provider/version/enabled/repoIds/docIds/flowId，`knowledge.retrieve` 参数保留原 legacyPayload；中心回执核对资源/版本/原 ragType，成功 data.results 完整保留。纯图缺正式 app/chat 时不能造旧历史；原 pool 的 flowId 为空只允许从已核中心资源填入真实绑定值，有值必须一致。正式文档/索引版本、hash、撤权、配额和 Producer 仍由中心实现。原 AIUI 专业 achat/SSE/模型参数算法未按普通检索别名实现。

`protocols/flow-draft.schema.json` 请求固定 resourceId/resourceVersion/workflowVersionId/dslHash/outputMode/input/timeoutSeconds?，中心返回 RUNNING/kind=workflow/operationId/childRunId/同绑定。frames 回执持续绑定这些字段与 nextCursor/done/state，原 SSE JSON 不压缩成字符串。中心必须从正式父run推导空间/身份/审批，持久关联子run，拒绝祖先循环和超深度并停止截止/取消的子树；这些 Producer 能力尚未实现，Worker 不用旧 flowId/uid/appId 直接路由或授权。合法已结束 child_run 错误回原 WORKFLOW_EXECUTION_ERROR，原变量输出 JSON 错误回 WORKFLOW_EXEC_RESP_FORMAT_ERROR，其他失败来源保持边界拒绝。

迭代内 Flow 当前明确拒绝：通用操作请求尚无中心实际轮次实例归属，Worker 幂等键中的轮次字符串不能授予子run调用。后续正式 OperationRequest.iterationId 必须与中心已记录的实际轮次、父子运行关联后再恢复；原迭代引擎和 FlowNode 算法仍保留。

## 剩余五类原节点的受控消费者与精确草案

`protocols/remaining-capabilities-draft.schema.json` 是这五类能力的统一草案，标记 draft-not-deployed；它引用共享 Assignment 根 schema、ExecutionBeginAck 和 Checkpoint CreateAck，不复制正式契约。中心当前没有资源/operations/frames/cancel Producer，不可联机。`worker/node_capabilities.py` 负责固定节点/资源绑定和原参数委托，`worker/legacy_operations.py` 负责中心操作回执、原帧、游标、超时与停止事实；未建立第二套状态或本地任务缓存。

| 原节点 | 中心固定描述与操作 | 原算法保留范围 | 未接通或明确拒绝范围 |
| --- | --- | --- | --- |
| plugin | toolResourceId；kind=tool/provider=spark-link/version/enabled/pluginId/operationId/legacyVersion/legacyAppId/openApiSchema/effectClass。read 走 tools.read，收费或写入走 tools.invoke | Link 解析 paths/methods；x-from/action/business/default 的 header/query/path/JSON body；base64；BFS businessInput；CodeConvert.sparkLinkCode；原输出缺项/错类型默认值 | 原任意 Schema URL/run URL 已实际封禁。中心须校验固定 schema 的目标与权限；read 分类须保证无费用和写入，不能用 HTTP 方法自行授权。原 JSON-only body 与 required 校验 TODO 的限制保留 |
| knowledge-pro-base | knowledgeResourceId；kind=knowledge-pro/provider=AIUI-RAG2 或 CBG-RAG/ragMode=DeepSearch 或 LongRAG/version/enabled/repoIds/docIds/modelResourceId。knowledge.answer 为费用能力 | 原 gen_req_payload 的 prompt/rag/messages/meta_data，CBG 必须 docIds；SSE answer/knowledge_metadata/[DONE] 与原错误码；每个非metadata帧会清空metadata 的原行为也验证 | 专业 achat Producer 和模型绑定未实现，不能别名普通 knowledge.retrieve。旧 url/apiKey/apiSecret 拒绝，凭据和 base_url 不进入请求；模型只引用已核 kind/modelName/provider/version |
| agent | agentResourceId；kind=agent/provider=original-agent/strategy/modelResourceId/legacyPlugin/dependencySelections/dependencyBindings。agent.invoke 为费用/潜在写入能力 | Workflow 原 HTTP 节点的 reasoning/answer/query 模板、tools 字符串归一化 V1.0、snake_case knowledge、delta/reasoning/tool_calls Markdown、usage、REASONING_CONTENT | 这是原 Workflow Agent 节点代理，独立 core/agent 的 ReAct/工具调用 Producer 仍未恢复或混装。每个旧 tool/version、MCP ID、workflow ID、知识列表索引须映射到固定受控资源；mcpServerUrls/modelConfig.api/旧凭据拒绝；历史缺正式 scope 拒绝 |
| database | dataResourceId；kind=database/provider=center-data/version/enabled/legacyDbId/legacySpaceId/tables。mode3 走 data.query，mode1/2/4 走 data.mutate | 原模式、第一case、AND/OR、LIKE %包装、NULL/NOT NULL 的 ZERO 语义、select columns/order/limit=0、写后输出 required/defaults、DELETE 无 outputList；SQL builders 原文仍保留为方法内懒加载 | CUSTOM mode0 尚无中心解析/翻译，明确拒绝。表/列仅逻辑标识符且须中心授权；结构化参数不能携带 SQL。ADD last_inserted_row/UPDATE matched_rows 要由中心同一审计事务返回，不能由 Worker 补 SQL 查询。原 PGSqlClient.exec_dml 实际阻断，未连接任何库 |
| rpa | rpaResourceId；kind=rpa/provider=original-rpa/version/enabled/projectId/execPosition/limits.timeoutSeconds/stopSupported=true。rpa.execute 必须审批 | 原 project_id/sid/exec_position/params；原 data:{code,message,sid,data} Pydantic 解析、最后 data 作为输出、None 转空 dict、原 RPA_REQUEST_ERROR | 原 header 认证和 RPA_BASE_URL 拒绝。真实 Runner/账号/费用和 host 控制未实现；无本地 fakeRunner。旧 24 小时上限只作原事实，实际固定限制和 run deadline 同时生效 |

所有操作请求仍是当前 run 的 leaseId/attempt/nodeId/operation/parameters/idempotencyKey。parameters 精确带 kind/provider/resourceId/resourceVersion/resourceBindings/legacyPayload，流操作另带 timeoutSeconds/readTimeoutSeconds?；安全 key 是 operation_ 加固定 run/lease/attempt/node/operation/参数 hash 的 SHA256。中心必须对同键同完整请求返回同回执、异内容拒绝，审批、配额、收费和 effect 真相仍由中心持有。本地字段不能授予正式调用。

流 ACK 持续核对相同 kind/provider/resourceId/resourceVersion/resourceBindings、nextCursor=原cursor+帧数、done/state；每批最多64、总帧10000、8MiB。Agent 与专业知识原总等待30分钟，原 retry.timeout 作为逐帧间隔；RPA 使用固定资源执行上限且不超过原24小时，全部同时受 run deadline/取消/租约限制。只有原终帧和中心 COMPLETED/done 同时确认才完成；RPA 原无显式终帧，采用中心 COMPLETED/done 确认流已结束。Agent 原代码先 JSON 解析后检查 [DONE]，此处未虚称支持该终止标记；choices 空或 stop 的原终止方式保留。

Provider 的合法非零原错误帧只在资源/版本/绑定匹配、FAILED/failureKind=provider/done=true 时交原节点映射。授权、协议、传输、deadline 或无帧失败保持粘性拒绝，不能被原默认值/重试吞掉。原插件的外层 PLUGIN_NODE_EXECUTION_ERROR 包装也保持。同步写入或费用流已发出但 ACK/operationId 未回读时取消，或者 cancel 未获 stopped=true，保留 EFFECT_UNKNOWN；停止未知不会伪称取消，中心 FAILED 审计也不会被节点返回修改。

Agent 原独立 builder 的 repo_type 1/2/3 映射分别 AIUI-RAG2/CBG-RAG/Ragflow-RAG（agent/service/builder/workflow_agent_builder.py:_create_knowledge_tasks）；Worker 逐项核对该区别、原 repo/doc 选择与中心依赖资源。知识配置的 null docIds 保留，不能为了通过资源校验降级为空历史或普通检索。原独立 Agent/Link/AITools 锁继续保护，尚未宣称其完整服务环境恢复。Workflow SQLAlchemy 原锁确切2.0.31，当前未安装；原 SQL literal 编译器因此不在本次真实执行覆盖内，正式结构化路径也不调用它。没有新增或升级14包之外依赖。

`tests/test_remaining_nodes.py` 实际调用原 Factory 和五类原节点，检查原参数/输出/错误、Plugin base64 与默认值、专业模式与 metadata 顺序、Agent 模板/工具 Markdown/usage、数据模式/条件/写后投影、RPA 最后帧、资源版本/撤权、明文凭据与旧目标拒绝、费用/只读、合法 Provider 错误和授权区别、停止确认/未知以及共享 schema 引用。保存的中心响应只是原算法 fixture，不是正式 Producer。

原失败保留在 verification/remaining-nodes-first.log（19项中的2个DB fixture漏必填列）、remaining-nodes-full-first-failure.log（140项中的旧未注册Agent断言）、remaining-nodes-schema-ref-failure.log（25项中的草案误指不存在Assignment def）；原 Worker Assignment 实际是共享 schema 根，已经修成根 $ref，Begin/Checkpoint仍引用其实际 defs。正常fixture已补列，另有原缺必填列失败回归；当前完整结果只以 unittest.log/checks.json 为准。一次从工作区根启动单模块测试因 cwd 不对报 ModuleNotFoundError，修正为本工程目录后执行；这次失败不记任何算法通过。复制日志时也出现过错误编码读取，随后按实际字节格式保存，未改失败结果。


## 最小 Spark Chat Producer 的离线实现与中心接续端口

`worker/producers/spark_chat.py` 已局部实现独立 Producer；没有接入默认 Model、21类消费者或 TaskRunner 注册，没有真实中心实现或网络 transport。`SparkChatProducer(center, private_transport).produce(operationId)` 只接收安全操作标识，不能从 assignment/前端传入 grant、URL或凭据。每个实例只尝试一次，跨进程重复领取必须由中心 acquire CAS 拒绝，不能靠本地瞬时标记持有正式操作真相。

复用原 `SparkChatAi.assemble_payload/decode_message`、`SparkChatHmacAuth.create_url` 和同连接 `recv_with_retry`；没有调用原 achat/assemble_url 的 trace/日志入口。Spark parameter/header/message/patch/web_search、reasoning和usage原形状保留，通用 extraParams/respFormat 并不被原Spark请求自动采用。quicklyThink 改由中心固定公开 profile 的 quickly_think 显式决定，只在开启时增加 enable_thinking=false；不读取 QUICKLY_THINK_* 临时环境。Provider合法非零code的原CodeConvert映射保留为已确认FAILED/provider回执，不能把授权/协议/传输/deadline失败冒认为Provider内容错误。

SparkPublicConfig 是不可变资源描述：resource_id/kind=model/provider=xinghuo/version/model_name/enabled=true/options_json/quickly_think/limits。固定 options_json 与 operation 参数原JSON解析后逐项严格一致；options保留temperature/maxTokens/topK/patchId/respFormat/searchDisable/extraParams。操作parameters_json原字节sha256核对parameters_sha256，不重新序列化算hash；只允许当前 model.invoke/chat 的原参数形状。私有SparkGrant同时绑定operation/run/lease/attempt/node/workflowVersion/dslHash/deadline及公开配置，含仅中心私有通道提供的认证材料，不进assignment/events/log。材料和grant默认repr不展开；错误仅固定code/来源/发送与清理事实，Provider帧或messages回显私有目标/密钥会拒绝。没有真实凭据、管理API、数据库、自动Provider连接或依赖安装。

| 精确Python端口 | 中心实现必须保证的事实与ACK |
| --- | --- |
| CenterSparkPort.acquire(operation_id) -> SparkGrant | 当前独立Producer身份、活run/lease/attempt、execution begin、固定node分支/版本、资源启用/撤权、费用逐次审批后，一次CAS领取执行许可。绑定grant全部字段；Extractor/Decision只授实际chat分支，QA只授resume+direct+handleResponse分支。当前无真实迭代实例时拒嵌套，不能从不透明key推定归属。同操作重复领取拒绝，ACK丢失不重授；grant只走私有通道 |
| assert_active(grant) -> None | 发送前、每次收帧前后核对当前许可/租约/取消/撤权；失败抛明确边界异常。总deadline同时约束此等待 |
| commit_frame(grant, sequence, frame, terminal=..., provider_error_code=...) -> FrameCommit | sequence为独立模型帧的1起单调序号，不是run event seq；持久完整原帧，同seq同帧幂等、异帧拒绝。ACK精确operation_id/resource_id/resource_version/provider=xinghuo/sequence/state/done/failure_kind。非终帧RUNNING/done=false；原status2成功COMPLETED/done=true；合法原非零codeFAILED/done=true/failure_kind=provider。只有中心CAS ACK成功才给出ProducerOutcome；不能生成run终态或新模型调用 |
| confirm_stop(grant, reason) -> StopConfirmation | ACK精确operation_id/resource_id/resource_version/provider/state/provider_stopped/proof_id。只有同绑定、已终止CANCELLED/COMPLETED/FAILED、布尔true和非空真实Provider证据引用才确认；本地close不能生成该证明。原Spark没有上游停止接口，需真实已终止/停止证据或保留EFFECT_UNKNOWN |
| record_failure(grant, FailureFacts) -> None | 固定failure_kind/code/send_attempted/local_closed/provider_stop_confirmed，不能附异常原文/URL/认证材料。中心持有费用、状态、effect和审计；不覆盖已提交的Provider终态，不能造回滚 |
| PrivateSparkTransport.open(authenticated_url, connect_timeout=..., close_timeout=...) -> PrivateSparkStream | 私有受控出口负责真实TLS、禁redirect、固定出口及取消清理未交付句柄，禁止日志和任意代理覆盖。stream为send/recv/close异步端口。源码只定义此端口，没有默认WebSocket连接器；离线fixture不是正式transport |

模型原帧数/单帧/总量和请求预算由固定limits约束，上限分别10000帧、8MiB单帧及总量、2MiB请求；总等待不超过固定total_seconds或中心绝对deadline，连接/read/close各自有界，原较小timeoutSeconds仍有效。原websockets12的timeout=60是旧close_timeout别名，与close_timeout=1同时设置会被忽略；适配使用显式连接边界，不能把原注释当首帧保证。transport/中心端口须支持协作取消；离线协作fixture不能证明任意阻塞实现或真实网络进程能被强制停止。

中心领取ACK未知/超时/取消/不完整/绑定不一致均不打开连接、不重领许可、不提交伪终态。send仅调用一次，send异常/取消也记send_attempted=true；只重试原同一连接recv，不重新invoke。所有已交付stream都会尝试有界close。本地close成功只证明本地清理，未获真实Provider停止确认时错误为EFFECT_UNKNOWN；已知Provider终态后close失败为PRODUCER_CLEANUP_UNKNOWN，不回写伪CANCELLED或覆盖中心COMPLETED/FAILED。

本次新增离线测试`tests/test_spark_producer.py`，首次24项PASS证据保留`verification/spark-producer-first.log`，补许可领取中取消/不完整grant/公开启用与类型预算后27项实际PASS在`verification/spark-producer-current.log`。测试使用保存的原帧和私有无网络transport，仅证明原组装/HMAC/解码、单次发送、许可与绑定拒绝、固定思考开关、帧/总量预算、连接/read/总deadline、流关闭及停止未知边界。原144项基线另存`verification/producer-144-unittest-baseline.log`和`producer-144-checks-baseline.json`，原README原文保存在`producer-144-readme-baseline.md`；本次只追加README事实，其余冻结16项代码/测试/协议不修改。本次完整集合171项实际PASS（144原用例+27新增），当前源码467个Python语法检查通过，427引入文件无缺失，生产源码可疑字面凭据/可执行示例/host exec/eval均为0；依赖分析与pip check退出0，仍是原14包。精确命令/退出码/当前hash在verification/spark-producer-port.json与checks.json；原144项日志保留，不把基线或合并技术测试冒称新Producer的联机或业务验收。

node_id前缀只作补充检查，不能授予资源/分支权限。中心须从不可变DSL逐项核实Extractor reasonMode==1的Prompt/chat、Decision reasonMode==1的Prompt/chat或非FC Normal/chat、QA的resume+direct+handleResponse；动态messages必须来自固定模板和已授权输入引用，多模态对象分发也须真实中心绑定。嵌套迭代尚无正式实例归属时统一拒绝，不能靠幂等key字符串绕过。

中心resources/operations/frames/cancel、Producer许可分发、真正私有出口、模型费用/效果账本和Provider停止核对尚未实现，当前Producer不能接实际任务派发。原OpenAISDK仍缺11个Workflow原锁包（openai1.60.2/anyio4.4.0/certifi2024.7.4/distro1.9.0/h11 0.14.0/httpcore1.0.5/httpx0.27.0/idna3.7/jiter0.10.0/sniffio1.3.1/tqdm4.67.1），没有安装或混装独立Agent/Link/AITools锁，完整21类正式能力仍需继续接续。
