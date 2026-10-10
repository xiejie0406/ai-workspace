# Worker 收口与中心模型接线交接

> 记录类型：本次源码证据；日期：2026-10-04；状态：按用户要求收口、暂停后续迁移。

本记录只说明 Worker 子范围和刚开始但未完成的中心模型工作，不替代项目统一控制记录。机器清单见 [Worker本轮修改清单](Worker本轮修改清单.json)、[Worker迁移代码核对](Worker迁移代码核对.json)。原当前未提交版本保存在 `修改前原文/worker/`，没有用 Git HEAD 覆盖用户修改。

## 已保留的源码修改

共有 49 个既有文件修改、2 个生产入口/适配文件新增（不含证据脚本）；每个文件的前后 SHA256 在机器清单。新增 `worker/__main__.py` 为真实中心领取循环，新增 `worker/agent_factory.py` 为原 Agent Builder/Chat/CoT/ReAct 算法的受控工厂。已有调整包括 CenterClient 的 Python 领取、四类原模型消费者协议及 history/read，EngineAdapter 的正式历史与真实 flowId、顶层问答混合图的固定资源准入、Agent 的安全导入与依赖工厂。Java 的中心资源/操作 HTTP 接线和 AITools 原参数组装保持真实租约/版本/审批/终态回执约束。具体语义已经同步 `python/paiflow-runtime/README.md` 末节。

Agent 受控算法分支要求固定 `executionMode=worker-original`、准确七项 `agentModelOptions` 和逐项工具/MCP/流程/知识版本；每个子调用仍须中心正式端口。配置、schema、许可或 Producer 缺失均明确拒绝，不能将新增消费者或原方法存在说成完整 Agent 联机。独立 Agent/Link/AITools 原依赖锁仍保留，未安装和混装。

## 源码核对与检查事实

AST 只解析 460 个生产 Python 源文件，无语法错误；原 21 类节点的直接方法名称均有目标对应，方法缺失数 0。包内导入修正为 33 文件、112 处，有单独机器清单和原文。旧被封禁 repository 等不在生产可达路径，其控制面仍应在中心迁移。

代码量按 production/test/total 分开，排除构建、缓存、依赖原文和 `_reference`。例如 Agent 原 production 为 71 文件/6376 文本行，tests 为 35 文件/14145 行；目标 production 为 64 文件/5040 行。剥离原测试、旧控制面和原启动不能根据总行数下降记业务功能删除；21 节点逐方法与受控端口另列。

独立 Java Worker 本轮 `mvn -o -DskipTests compile` 实际编译 87 份源成功。收口重复同命令于 16:41:11 完成，退出 0、BUILD SUCCESS、4.755 秒，提示已编译类均最新。未运行产品测试、真实 API、Provider、数据库、服务、依赖安装或 Git。历史 271 项等测试不能证明本轮源码通过。中心本轮 History/Variables 等其他执行者改动的编译由主执行者单独核实。

## 已撤回的未完成中心修改

用户要求停止扩展时，中心模型正开始补真实配置和托管引用，还未完成 Producer、消息来源及四类接线。以下 6 个文件当时完整源码已保存于 `未完成模型接线原文/ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/`，仅供下次承接，不在 Maven 活跃源码：

- 新增的 `ModelRuntimeProperties.java`、`ManagedModelRuntime.java` 已移出活跃源码；未完成文本保留。
- `ModelRuntimeCatalog.java`、`ModelOperationPorts.java`、`WorkflowModelCompiler.java`、`ModelOperationOptions.java` 已恢复本轮修改前的当前原文，4 个恢复文件 SHA256 与备份逐一相同。

未修改 ModelService、ModelOperationService、History、Variables、RunService、LegacyFlowStream 或 WorkflowService；025 DDL 未新建或执行。撤回只针对本执行者刚开始的模型工作，不覆盖主执行者和其他子执行者修改。

## 下次中心模型必须完成的具体代码

当前活跃中心仍限顶层 `spark-llm` OpenAI chat。ModelRuntimeCatalog.ProfilePort、ModelService.CredentialReferencePort/ProviderValidationPort/LocalDeploymentPort、ModelOperationPorts.InvocationPolicy/CredentialMaterialPort/ProducerBridge 仍无正式实现。缺口属于代码，不应改称配置缺失或测试失败。

运行批准沿用原 authenticated RunService.create 指定固定版本/输入的真实执行请求，结合当前平台运行权限、空间、配置的用途/Provider/预算和参数来源核对，持久化精确 permit；逐次调用必须重新核对当前权限和来源，不默认返回 true，不增加原页面没有的每次手工批准步骤。

固定版本的托管材料须使用 ManagedSecretService 的 ACTIVE 精确版本、项目 paiflow、供应商、chat 用途和认证模式。运维配置必须精确绑定 provider/domain/endpoint/env 别名到托管 alias，不读取任意环境或文件。真实 Hello/本地部署须取真实回执再用原 commitValidated 事务，不能制造 READY/PENDING。Producer 必须在事务外实际发送、每帧中心 ACK、引用真实 finish/usage、取消停止无法证明时保留 EFFECT_UNKNOWN；Spark chat/function_call 与 OpenAI chat 的原参数、HMAC、错误帧保持各自协议。

四类模型节点合同为：`spark-llm` chat；`extractor-parameter` reasonMode=1 为 chat，否则原 Spark function_call；`decision-making` reasonMode=1 或 useFunctionCall=false 为 chat，否则原 Spark function_call；`question-answer` 只在真实 resume + direct + directAnswer.handleResponse 的分支执行 chat 填槽。FC options 另含 questionType/functionChoice 和固定 functions schema。不得把供应商别名或默认模式当真实支持。动态 QA 消息应从实际 CONSUMED 检查点题面/用户回复和原重试链复原；不能只相信 Worker 自报 messages。V2 正式消息/对象、嵌套迭代、Agent 逐子资源许可尚需独立接续。

知识、专业 RAG、Link/MCP、父子 run、正式数据表事务、RPA、隔离代码执行仍缺真实中心 Producer，精确资源和 ACK 合同已交主执行者并在 Worker README 保留；中心缺实现时维持明确拒绝。
