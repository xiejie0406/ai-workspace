# PaiFlow 独立 Java Worker

本工程按已授权完整迁移设计引入 `参考项目/PaiFlow-main/core-workflow-java` 的执行算法，独立采用 Java 21、Spring Boot 3.5.4 和 Spring AI 1.1.2。它没有加入若依聚合 POM，也不装入共享 `ruoyi-admin`。正式工作流、空间、工具、会话、任务、租约与成果由中心持有。

当前是迁移实施中的源码，**尚未完成中心与 Worker 的真实服务联机，不能据此认定完整运行链路已可用**。默认 `paiflow.worker.enabled=false`，本轮没有启动 Web 服务、连接业务数据库或调用供应商；真实中心类与 Worker 的随机 H2 内存库隔离检查单列于下文。

## 引入与调整

`upstream-manifest.json` 保存 73 个引入文件的来源、原始 SHA256 和排除范围。保留原四执行器（开始、结束、LLM、插件）、顺序和并行编排、变量引用、模型消息与思考流、讯飞 WebSocket 签名/音频组装、Qwen 分片与 WAV 合并、OpenAPI 参数组装和 HTTP 请求算法；`LICENSE`、`NOTICE` 保留原项目许可及来源。[当前引入审计](checks/source-import-audit.json)区分原文件与 25 个已改编文件，不能将当前工程全部文件称为与上游逐字一致。

排除旧 `WorkflowApplication`、正式 `WorkflowMapper/ToolMapper`、数据库配置、旧 CRUD/管理 Controller、Redis 正式状态和旧部署/真实配置。本工程仅保留引擎所需 `NodeDebugRespVo`，未暴露原管理入口。原 MySQL/MyBatis/Redis 依赖已移除，Spring AI 的 OpenAI starter 改为同一原 BOM 内 `spring-ai-openai` 库，避免启动时生成第二个 Provider 控制入口；其余算法依赖仍声明原精确版本，未升级。

执行边界新增：

1. `POST /internal/paiflow/v1/worker/execute` 只接收 `runId/leaseId/attempt`，必须有中心独立服务身份。标准 Spring Filter 在 MVC 读取正文前认证，再按已声明长度和实际读取字节执行 16KiB 接收预算；未认证正文不读取，编码路径、上下文路径及路径参数也按同一路由保护。DSL、输入、资源、期限、批准引用与事件起始序号均从中心租约核对响应获取。
2. Assignment 对应 [worker.schema.json](../../contracts/paiflow/worker.schema.json)。Worker直接解码中心 `dslCanonicalBase64` 并核对其 SHA256，再核对 JSON 对象与 `dsl` 相等；不重新排序 DSL 后冒充兼容哈希。
3. 每个运行有独立变量池、临时会话缓存和可清除的执行上下文；上下文在线程池复用时显式传递和恢复。启用历史的节点必须获得中心 `kind=conversation-history/nodeId/history` 资源快照，缺少时明确拒绝，不能用本地空历史冒充完整会话。
4. 心跳失效、取消与期限阻止后续节点及外调。所有模型/工具/TTS/对象访问须匹配中心资源快照 endpoint 和操作白名单，并逐次向中心获取授权；批准引用本身不能授权。每次授权 grant 保留未确认记录，真实响应只生成脱敏的 SHA256/长度等回执，经中心 `effects/complete` 明确确认同一 grant/state 后才清除。本地请求完成、结束节点成功均不能清除未知副作用；未知外部结果报 `EFFECT_UNKNOWN`，不能报成功或取消完成。
5. 并行线程池和执行槽有上限，跳过分支继续传播到汇合，结束节点未执行成功不能产生完成结果。回调送达失败会使执行失败；终态由中心 CAS 裁决，本地没有正式任务队列或持久结果。原对象事件保留完整生命周期数据，未来字符串流统一为 `{delta: ...}` 对象，发送端拒绝标量 payload；原字符串队列分支目前未启用，不能把这项预防适配说成已修复真实流故障。
6. 对象写入改为中心分配临时上传 grant、HTTP PUT 后提交实际 SHA256/长度。Worker不创建桶、不更改公共读策略、不自行指定正式桶或签发任意长期 URL。
7. Qwen 原 2.22.3 SDK 的 `MultiModalConversationParam`、`HalfDuplexRequest`、`OkHttpHttpClient` 和结果转换继续使用，实际请求 URL 从完整配置构造并核对，客户端关闭重定向与连接重试。LLM 沿用原供应商兼容路径，但按最终请求 URL 授权，使用禁止重定向的 JDK transport，并将 Spring AI 重试次数限定为 1；不会只检查一个声明地址后由 SDK 请求另一个地址。
8. 所有引擎执行还必须取得中心 `execution/begin` 一次性许可。先核对真实快照、原字节哈希和本地节点，再请求精确 `{leaseId,attempt}`；ACK 的 begun、运行、租约、attempt、固定版本和 DSL 哈希全部相同才继续。409、超时、未知或不完整 ACK 均不执行、不重试，也不提交终态，避免重复方结束持许可方的运行；含 approved_write 能力的此边界只返回 `EFFECT_UNKNOWN` 错误等待中心核对。确认许可后再检查关闭、中断和期限，响应期间到期也不得进入原引擎。
9. 原引擎 Future 真正完成且没有未确认副作用后，提交完成前再同步 verify 最新取消和版本/hash。中心明确取消时，只有已停止且没有未知副作用才提交需 ACK 的 `CANCELLED`；已发生的节点、费用与已确认副作用事实保留，取消不表示回滚。未确认副作用始终保持 `EFFECT_UNKNOWN`。verify 与 result 之间发生竞争时，最终 409/未知 ACK 明确传播，不重试，不转成假取消或完成。

开始输入优先保留中心实际赋值，不能被节点默认字面量覆盖；嵌套数组/null 和实例隔离已补充断言。分支执行保留原正常/异常分支模型，补齐跳过后继的传播与顺序递归返回后的状态复核，避免跳过链路仍执行后继，或汇合点被再次执行。

## 中心接口与配置

入口为 `PaiflowWorkerApplication` → 条件启用的 `WorkerRuntimeConfiguration` → `WorkerTaskController.execute` → `WorkerTaskRunner.execute` → 中心 verify/一次性 begin → 原 `ParallelWorkflowEngine`。另有显式启用的 `WorkerClaimLoop`，调用同一 Runner 的 `claimAndExecute`：先保留已有执行槽，再调用中心 `/runs/claim`，只提取 `runId/leaseId/attempt` 并重新通过 `verify` 校验当下租约和快照，再获得 begin 许可；领取响应中的 DSL 不直接进入引擎。原顺序引擎和节点调试算法也已引入，当前 HTTP 入口没有另行暴露管理或节点调试接口。

领取循环由 Spring `SmartLifecycle` 管理，`paiflow.worker.polling.enabled=false` 默认不创建循环；只有 Worker 和 polling 两项均显式启用才开始。单个领取线程串行执行已领取任务，与 HTTP 入口共享四个运行槽，槽满不领取。空任务按默认 1000ms 继续轮询，异常指数退避至默认 30000ms；有效配置限于 100–60000ms。关闭取消等待/当前调用并中断运行，旧生命周期回调不能重启领取；中心回执失败或真实失租约后不在本地重试所持任务，也不写本地正式结果，等待中心裁决或租约失效。后续轮询只向中心领取新租约，中心仍是唯一任务真相。本次没有启动该循环访问真实中心。

Runner 还有容量为 4096 的进程内接收保护，以 `runId/leaseId/attempt` 去重，取得有效 Assignment 后只保留至中心固定 `deadline`；未能取得有效快照时保守保留 24 小时。容量满拒绝新执行和领取，不淘汰仍有效记录来重跑旧尝试。它不保存审批/正式运行状态或结果，**该内存表本身没有跨重启幂等保证**。跨进程重放由中心行锁与持久化 `execution_started_at` 的一次性许可拒绝；Worker 不把许可保存成第二套执行真相。中心已签发而 ACK 丢失的许可也不会由 Worker 重试，正式副作用仍须尚未接通的中心许可账本和真实回执核对。只有新领取的首次 attempt 在进入引擎前收到中心明确取消时，Worker 才能确认尚未执行节点并提交 `CANCELLED`；终态仍须中心 ACK，旧接收/后续 attempt 不以此推断旧副作用不存在。

中心接口前缀为 `/internal/paiflow/v1/runs/{runId}`，以独立 `Authorization: Bearer` 服务身份调用，读取中心 `AjaxResult(code=200,data=...)`。`HttpCenterClient` 无自动重试、无自动重定向，错误响应不会作为成功数据处理。事件 timestamp 在实际发送正文中明确写为 `Instant.toString()`，不依赖调用者 Jackson 的日期开关。其 2MiB 限制仅用于响应读取，请求没有小于中心 4MiB 事件预算的本地上限；3MiB 正文已通过内存拦截的实际 BodyPublisher 完整核对。

| POST 后缀 | 请求与核对 |
| --- | --- |
| `POST /internal/paiflow/v1/runs/claim`（不带 runId） | 使用同一独立服务身份，正文 `{}`；`data=null` 表示暂无任务，其他响应须满足严格 Assignment 字段/类型和 Java 引擎绑定；客户端仅返回租约标识给 Runner。 |
| `lease/verify` | `{leaseId,attempt}`；读取 `data.valid/cancelRequested/dslHash/workflowVersionId/lastEventSeq/assignment`，必填字段和类型严格核对，元数据必须与 Assignment 一致。 |
| `execution/begin` | 精确 `{leaseId,attempt}`；一次请求，严格核对 `data={begun:true,runId,leaseId,attempt,workflowVersionId,dslHash}` 六字段及类型/绑定，未知或重复拒绝后不执行、不重试、不写终态。 |
| `heartbeat` | `{leaseId,attempt}`；必须显式返回布尔 `valid/cancelRequested`。 |
| `events` | 完整 `paiflow.events.v1` 事件；ACK 必须 `accepted=true` 且 `seq` 与提交一致。 |
| `result` | `{leaseId,attempt,state,output?,error?}`；ACK 必须 `accepted=true` 且 `state` 一致。 |
| `effects/authorize` | 当前草案 `{leaseId,attempt,operation,resourceId,target,approvalRef?}`；需明确 authorized 与本次 grantId。 |
| `effects/complete` | 当前草案 `{leaseId,attempt,grantId,state,receipt}`；ACK 必须 `accepted=true` 且 grantId/state 一致，失败保留未知。 |
| `artifacts/prepare` | 当前草案 `{leaseId,attempt,objectKey,contentType,size}`，取得中心上传/读取 grant。 |
| `artifacts/commit` | 当前草案 `{leaseId,attempt,artifactId,sha256,size}`；ACK 必须 `accepted=true` 且 artifactId 一致。 |

中心的租约、事件与终态已有共享业务模块实现源码，本工程尚未与其联机。额外 effects 与 artifacts 接口仍是草案，不能声称已可用。正式副作用还缺少实际参数 hash 的统一编码和中心 grant 绑定；当前 `Effect` 的 endpoint/operation 核对不能代替该绑定。

配置使用 `paiflow.worker.center.endpoint/service-token`、`paiflow.worker.receive-service-token`、`paiflow.worker.max-run-seconds/heartbeat-seconds`、`paiflow.worker.polling.enabled/interval-millis/max-backoff-millis` 和 `paiflow.runtime.*`。模型/插件/语音真实地址与凭据未保存到本工程；被选用的配置值必须同时属于本次中心资源快照，未配置不能自动回退到旧地址。`server.port=0` 仅是默认关闭时的占位配置，不是已批准运行端口；本次未启动。

## 本地检查

已按授权恢复原精确版本依赖，随后执行独立工程 `mvn -o test`，实际编译全部引入算法和执行边界；一次性许可、完成前取消与 ISO 时间协议修正后的最终整轮通过 54 个 JUnit 测试，0 失败、0 错误、0 跳过，见[当前最终日志](checks/maven-test-handshake-final.log)。完成前取消的 [18 项 Runner 定向检查](checks/maven-test-cancel-completion-targeted.log)和实际发送协议的 [33 项定向检查](checks/maven-test-handshake-protocol-targeted.log)也通过。一次性许可初次接入后的 51 项见[执行许可日志](checks/maven-test-execution-begin-final.log)，其 [30 项定向检查](checks/maven-test-execution-begin-targeted.log)另存。此前入口过滤器后的 44 项见[Worker 日志](checks/maven-test-worker-final.log)，领取与有界接收保护 39 项见[领取日志](checks/maven-test-delivery-capacity-final.log)，26 项协议复验见[协议日志](checks/maven-test-protocol-recheck.log)，23 项基线见[引擎 Maven 日志](checks/maven-test-final.log)。没有升级版本、执行 package/repackage 或启动应用。此前离线缓存失败、编译失败与分支测试失败保留在 `checks/` 的原日志中，最终成功不覆盖原失败证据。

新增接收过滤器后的首轮 44 项中，顺序分支的 5 秒期限包含了首次 Mockito inline 初始化而超时，失败保留在 [首轮日志](checks/maven-test-receive-boundary-final.log)。该图禁止任何中心调用，改用调用即失败的 JDK 代理替身后，保留原 5 秒期限和完整跳过/汇合断言；两分支及五项 Filter 的 [7 项定向复验](checks/maven-test-receive-boundary-targeted-recheck.log)通过，随后整轮 44 项及新增许可后的 51 项通过。未修改生产引擎或放宽时间断言。

`scripts/test-boundary-offline.ps1` 用已安装 JDK 21 直接编译实际协议、租约上下文和 canonical hash 验证类，执行 28 项纯内存检查：无租约/期限/取消/租约丢失拒绝、操作白名单、地址与路径、中心拒绝、并行未确认副作用、中心拒绝回执后仍保留未知、线程上下文复用、Unicode/浮点原字节和篡改哈希拒绝。脚本直接消费中心共享 fixture，其 `FakeCenter` 禁止任务、网络、一次性许可和文件访问；最新结果见[纯 JDK 边界日志](checks/offline-boundary-execution-begin.txt)，早期结果另存。领取接口、一次性许可与进程内保护本身由上述 JUnit 验证，不混入 28 项计数。这些本地检查不能代替真实 Worker 联机。

54 个 JUnit 测试包含：变量池 4 项、中心协议/ACK/请求正文/claim/begin 15 项、原顺序/并行引擎 2 项、跳过分支与汇合 2 项、真实 WorkerTaskRunner 内存闭环/领取/取消/期限/失租约/有界接收保护/一次性许可/完成竞争 18 项、Spring 生命周期领取循环与退避/中断 3 项、接收入口认证前置/实际字节预算/路径保护 5 项、共享 canonical fixture 1 项、Qwen SDK 请求构造 2 项、LLM 最终地址兼容 1 项和四执行器独立容器装配 1 项。两个独立 Runner 实例共享模拟中心验证临时去重表清空后仍被中心拒绝，只进入一次真实节点；它不是两个真实进程或数据库联机证据。没有 `SpringBootTest` 或 HTTP 服务器；容器装配只创建本地 Bean 后关闭。这 54 项的所有中心交互由内存 Fake/Mockito、禁止调用的 JDK 代理或注入的 `Exchange` 拦截，没有真实 Provider/HTTP/TTS/对象/数据库调用。Boot 插件不在 `test` 阶段执行入口，编译阶段仅执行原 Lombok 注解处理器。

`scripts/verify-import.py` 核对原来源 SHA256、引入文件保全、许可与本地包 import。当前 73 个引入文件齐全，305 个本地 import 全部可解析，没有正式 Mapper、数据库或 Redis 状态设施残留；该审计不是业务验收。

中心只读复核曾用实际 Java Runner 和中心 `RunJsonBoundary` 复现：300KiB 合法输入在原 256KiB 事件预算下失败，见[首失败日志](checks/center-budget-probe.txt)。中心将事件预算修正为 4MiB 后，未裁剪原生命周期，615126 字节的最大真实事件得到保全且运行 `COMPLETED`，见[预算复验日志](checks/center-budget-probe-recheck.txt)；许可接入前的同一 Probe [阶段复验](checks/center-budget-probe-final.txt)再次得到相同指标。后续补齐 begin 内存 Fake 的 [预算常量复验](checks/center-budget-probe-execution-begin.txt)也通过，但该 Probe 只校实际预算常量，不能作为真实持久接口、完整协议或当前许可握手证据。原可选事件ID被中心接受错误类型的内存复现保存在 `checks/center-optional-event-fields.txt`，对应中心源码已改为存在时严格 String/非空检查；没有修改中心文件。

一次性许可接入后另以 [CenterBudgetHandshakeProbe](checks/CenterBudgetHandshakeProbe.java)执行当前隔离闭环：复用中心已有 `RunPersistenceContractTest` 的随机 `jdbc:h2:mem:<UUID>` fixture，真实中心身份、Controller、事务服务和 Mapper；中心与 Worker 使用独立 URLClassLoader，保留各自原依赖。请求和响应经过实际 `HttpCenterClient` BodyPublisher/JSON/严格 ACK，没有启动 HTTP 服务。初次探针的反射检查编译问题保留在 `checks/center-budget-handshake-compile.txt`，Mockito 插件跨类加载器冲突保留在 `checks/center-budget-handshake-classloader-failure.txt`；修正检查代码线程上下文后，普通 Jackson 将事件时间发成数字而被真实中心拒绝，见[时间协议首失败](checks/center-budget-handshake-final.txt)。Worker wire 明确写 ISO 字符串后，[当前隔离闭环](checks/center-budget-handshake-iso-recheck.txt)通过：claim→verify→begin 顺序正确、begin 请求一次、严格 ACK、许可已持久化、原生命周期最大实际事件 615161 字节、6 条持久事件、引擎与数据库终态均为 `COMPLETED`。这项隔离检查不混入 54/28 的计数，不代表业务 MySQL、HTTP 服务、真实资源或供应商已联机；旧预算 Probe 通过也不代替当前许可握手。

新增中心问答检查点的只读复核也保留了实际编译类的内存证据：原选项 JSON 字符串被接受且原字段保持 String，跨运行检查点在返回拒绝前仍发起外域行锁，见[原复现](checks/center-checkpoint-shape.txt)。对应源码已补选项真实数组检查和读取归属后再锁定/重查，避免错误跨运行请求与批清理组成反向依赖；[第一次复验](checks/center-checkpoint-shape-recheck.txt)及最新编译类的同一 Probe [最终复验](checks/center-checkpoint-shape-final.txt)均得到 `stringEncodedOptionAccepted=false/foreignCheckpointLockAttempts=0`。最新源码已确认用户具体 query/resume 功能权限在取得空间/运行锁后，用当前平台菜单再次收窄；仅在锁等待前读取权限，即使事务使用 READ_COMMITTED，也不能让该已读值自动反映等待期间的撤销。这些复核不证明真实 MySQL 或 Python 问答联机。

## 未完成内容

中心内部端点、资源快照/短期 secret 的实际供给、参数 hash 与 grant 的绑定、模型/工具/语音授权及对象 grant 尚未联机。原播客全链路、真实供应商错误/取消和费用边界、两种音频真实格式、对象读写与中心会话历史/成果持久化仍需按真实配置和授权验证。Qwen SDK 的原版本构造与请求地址已对照 bytecode 并用其公共 API 作纯内存验证；供应商真实行为尚未执行。

Java Worker 的节点检查点/副作用恢复尚未实现：对 `attempt>1` 的 `approved_write` Assignment 明确拒绝，不能重跑已有写操作冒充恢复。当前入口只接受中心重新分配的整次执行，不做节点检查点续跑。中心新增的问答检查点由 Python 的原问答节点消费，不因此将其声明为 Java 已支持。节点调试的原算法已引入，但未通过中心任务边界装配为调试模式。中心会话历史消费、成果索引和节点输出的数据保全仍需联机闭环。本工程不扩大 Java 的原四节点执行范围，其余节点由 Python 引擎覆盖。构建和内存边界通过不能据此标记完整迁移、业务验收或上线完成。

## 2026-10-03 本次停止确认修正

WorkerTaskRunner不再把Future.cancel(true)或Future.done当作实际并行节点已退出。ExecutionScope登记排队/执行子活动，主任务和子活动均实际退出后才确认终止；两秒内不能确认停止时保留EFFECT_UNKNOWN。登记后提交被拒可能留下活动登记，当前保守记未知，未通过故障演练验证。原分支/并行算法与四类执行器范围保持，中心当前Java仍仅准入START/END。

最终Java21编译及Boot JAR私有打包成功，使用-Dmaven.test.skip=true，未编译或运行测试，未启动Worker/真实模型/TTS/工具。制品在工作区.runtime/paiflow-delivery-20261003/java-worker/target，未覆盖共享admin产物或安装/部署。历史内存检查不是本次修改后业务证明；真实停止、跨进程恢复和资源链路仍需接续。实际记录、日志与制品SHA256见[项目核对记录第27节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#27-2026-10-03-通知运行查询停止确认与源码逻辑报告)。