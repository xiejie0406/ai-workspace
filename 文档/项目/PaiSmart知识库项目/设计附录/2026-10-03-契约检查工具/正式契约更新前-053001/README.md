# PaiSmart 知识库接口与事件契约

> 文档状态：当前源码正式契约  
> 契约版本：1.0.0（v1）  
> 更新时间：2026-10-03  
> 事实依据：当前 `ruoyi-paismart` Controller、Service、WebSocket handler、Kafka runtime，以及管理端 PaiSmart API 调用。  
> 验证范围：源码映射、字段与媒体类型、引用解析和协议样例静态检查；未执行生成客户端或真实数据库、Kafka、模型、OCR、微信支付联机验收。

本目录记录当前实现的公共协议。后续修改路径、字段、枚举、授权或恢复语义时，在对应源码变更中同步更新契约；不从候选设计推断接口已经存在。业务实体标识使用 UUID 字符串，平台 `userId` 使用 int64。契约将 Token、金额（分）、版本和页码声明为整数；消费者按此类型提交，充值及额度入口由服务端校验精确整数。

| 文件 | 使用范围 |
| --- | --- |
| [knowledge-v1.openapi.yaml](knowledge-v1.openapi.yaml) | 当前 `/paismart` 全部 Controller HTTP 映射与兼容别名；请求、响应、授权、分页、版本、文件媒体和支付通知。 |
| [jobs-v1.asyncapi.yaml](jobs-v1.asyncapi.yaml) | 实际 `paismart.jobs` 和 `paismart.jobs.dlq` Kafka 通知；附 MySQL 中央任务载荷和处理结果定义。 |
| [stream-v1.schema.json](stream-v1.schema.json) | 聊天 WS 客户端帧、无事件字段的生成快照、持久事件、连接与心跳帧。 |

HTTP 使用共享若依 `Authorization: Bearer <token>`，开发环境经既有 `/dev-api` 代理转发；契约服务器地址为后端根地址。平台按钮权限只是入口，后端仍检查业务资格、文档可读范围、组织管理范围、所有者和行版本。文件与审批范围不会因操作人看到按钮而扩大。会话审计全文另有独立服务端范围校验，个人会话不能因组织管理权限被查看。

对象结果使用共享 `AjaxResult`，列表使用共享 `TableDataInfo`，本业务成功列表统一 `code=200`、`msg=查询成功`。消费者同时检查 HTTP 状态与 JSON `code`，因为部分共享 `ServiceException` 仍可通过 HTTP 200 返回业务失败。HTTP 429 含 `errorCode=RATE_LIMITED`、`retryAfter` 和 `Retry-After`；其他错误不虚构统一机器错误码枚举。未声明分页参数的配置列表返回全量有限集合，不能从 TableDataInfo 外形推断已实现 SQL 分页。

上传创建和文本创建需要原 `requestKey` 或 `Idempotency-Key`，重复动作保留原键；分片编号从 0 开始，raw 分片使用 `application/octet-stream` 与 `X-Chunk-SHA256`，兼容 multipart 分片的 `file`、`sha256` 字段。合并只使用上传清单，不接收另一套版本或合并幂等参数。文本创建、合并、删除、恢复和重处理均返回真实 `Document`，其中可空 `jobId` 为当前处理指针；需要展示进度时另查 Job。`rowVersion` 是修改并发版本，`contentVersion` 是预览与证据版本，二者不可替换。

`GET /uploads` 在本人范围内分页发现未完成上传，可用内容摘要、空间、可见性及关键词收窄范围；输出包含原清单的 `spaceId/visibility/createdAt/expiresAt`。同本人、同内容、同范围的新请求可以发现并复用原清单，各请求键仍单独保存输入摘要；不同输入复用相同请求键拒绝。清单宣称存在但存储已缺失的分片会从已完成集合移除，重新上传时修复该片，不据旧回执伪造合并成功。

文档 content 成功直接返回 `text/plain;charset=UTF-8`，PDF pages 成功返回 `application/pdf` 单页（页码从 1 开始），download 成功返回 `application/octet-stream` 与 UTF-8 附件文件名；这些成功响应没有 Ajax 包裹。PDF 未解析的 `pageCount` 可为空，原件 PDF 预览上限 100 MiB。content/pages 可传 `contentVersion` 防止引用版本错位，download 没有该参数。文档媒体均 `no-store`、`nosniff`；支付二维码为 `image/png`、`no-store`，仅当前用户原订单且有效可支付状态允许读取。

成员空间批量保存接受 `assignments` 中显式 `uploadAllowed`、`managerAllowed` 和可选 `primarySpaceId`。版本优先 `If-Match`，其次 body `rowVersion`，再 `expectedVersion`；初始无版本记录为 0，成功递增真实成员空间版本。后台在一个事务中替换可管理组织关系、保留范围外及个人关系，越界主空间覆盖拒绝。个人空间只允许 PRIVATE，不参与组织父空间或管理成员分配。

成员列表支持有管理范围的 `spaceId` 和平台账户 `status=0/1`，先在共享用户 `DataScope` 内过滤再分页，不把业务资格 `ACTIVE/INELIGIBLE` 当作平台账户状态。个人及成员用量返回 `llm/embedding`：`DAILY` 的剩额依据今日已用与预留计算，`BALANCE` 的 `limitTokens=null`、剩额为实际余额。无 `userId` 的 `/usage/overview?days=7|30` 返回补零趋势、今日前五排行及独立的每日/余额预警；指定 `userId` 返回该成员用量。日期按 Asia/Shanghai 业务日计算。

聊天先通过共享 Bearer 请求 `/paismart/chat/ws-ticket`，票据 60 秒、单次、绑定准确浏览器 Origin 和原平台登录会话。WS 连接 `/paismart/ws/chat?ticket=...` 不在 URL 中传长期 Bearer。`connected` 返回 `protocolVersion=1`；初次提交和 resume 立即返回 `GenerationSnapshot`，它没有 `kind` 或 `sequence`。持久事件才有递增 `sequence`，使用 `kind=tool/delta/summary/references/done/stopped/error`。断线不取消作业、不重新提交问题，resume 根据原生成 ID 和 offset 重放；终态不继续订阅。每帧和重放周期复核登录撤销、权限、资格。客户端/服务器方向可分别使用 schema 的 `$defs.ClientFrame`、`$defs.ServerFrame` 校验。

Kafka 通知只有 `{jobId}`，MySQL `ps_job`、`ps_outbox` 和检查点持有任务类型、业务载荷、租约与结果真相。AsyncAPI 的 `x-mysql-job-types` 是内部持久任务定义，并非新增 Kafka 消息主题。确定坏通知死信只保存位置、脱敏 SHA-256 与原因，先落库再提交该分区 offset；数据库或提交失败才 seek。成功发布通知重置发送尝试计数，QUEUED 通知恢复不会因成功重发而累计为失败。任务状态与聊天生成状态分别解释，`SUCCEEDED` 任务不替代 `COMPLETE` 生成终态。

充值订单采用服务端不可变权益快照和精确整数金额；`PREPAY_STARTED`、`UNCERTAIN` 保留原订单号供历史查询与核对，不能自动创建第二笔预支付。仅 `NOT_PAY`、`PAYING` 未过期允许展示二维码，`SUCCEEDED` 为支付成功及权益入账。微信回调虽无需平台 Bearer，但必须使用官方 v3 签名通知信封和四项签名头；官方 SDK 验签、解密后还核对商户、应用、CNY、金额和唯一交易号，成功返回 HTTP 204 无响应体。

订单列表的 `state/status` 在本人范围内先过滤再分页，兼容 `SUCCEED/FAIL` 到 `SUCCEEDED/FAILED`，两个参数冲突时拒绝。套餐的 `description/benefit/sortOrder` 与额度一并固化到订单快照。邀请码列表可按 `enabled` 筛选，未消费的自定义码允许修改并统一大小写；已有消费不能改变码身份，显式空有效期表示永久。

Provider 只传托管用途 `secretRef`，不传明文 key；`config.providerCode` 显式值优先。完整限流包含五组原有窗口规则和新增上传/个人日预算防护，聊天规则输出字段为 `rules.chatMessage.max`。索引、评测及运维创建任务返回的 `JobSummary` 与管理端 Job 记录不同。运维计划审批要求另一名有该资源范围的授权人，执行绑定不可变 `planVersion`；路径存在不代表默认关闭的真实执行已经开启。

Provider 测试可用 `id` 指定同用途的单条配置，省略时保持原激活配置语义。评测逐例保留检索命中/排名/覆盖、生成及裁判失败、人工抽样原因和诊断；总样本数保留失败项，检索与裁判指标分别明确其分母，未知值为 `null`。最新人工修订参与 `cohensKappa/meanAbsoluteScoreDiff` 等一致性计算，配置阈值、样本不足及重新校准警示分别保留。引用的 `text` 与 `parentContext` 区分命中原文和授权父块事实，核验失败保持未核验。

运维清理清单含 `documents/uploads`，两者合计最多 1000 项；上传项冻结过期时间、状态、摘要和具体分片键。执行逐项复核权限、租约、清单摘要及正式回执，不以审批覆盖整个命名空间。旧桶复制只从批准配置及精确 `legacySourceKey` 读取，目标写入仍限本项目，源对象不删除。

静态核对可沿各 HTTP operation 的 `x-source` 定位 Controller，再核对 [管理端 API](../../frontend/admin-web/src/api/paismart)、[PaiSmart HTTP 模块](../../ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/api)、[中央作业服务](../../ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/application/jobs/JobService.java)、[Kafka runtime](../../ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/infrastructure/PaismartKafkaRuntime.java)、[聊天 WS handler](../../ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/infrastructure/intelligence/PaismartChatWebSocket.java) 和 [完整迁移设计](../../文档/项目/PaiSmart知识库项目/完整迁移设计.md)。开放 Map（评测报告、供应商配置、运维 manifest）允许当前扩展字段；契约已列可确认字段，并不宣称其动态数据来自代码生成消费者验证。
