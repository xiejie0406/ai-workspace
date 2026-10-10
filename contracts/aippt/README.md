# AI 演示文稿公共契约

> 版本：v1；创建时间：2026-10-03；用途：若依中心、原React H5及独立Worker 的协议和唯一设计目录。

业务 HTTP 接口复用若依身份、权限和 AjaxResult/TableDataInfo。业务字段保留原模型的 snake_case；Worker 任务信封采用 camelCase，内部服务身份不传到浏览器。完整规则和验收要求见[迁移设计](../../文档/项目/AI演示文稿项目/完整迁移设计.md)。

任务GET与任务SSE先核本人归属，再按实际任务类型使用既有平台权限：材料解析用`source:query`、提纲用`outline:query`、生成页用`deck:query`、AI/换排布用`ai:edit`、普通质量用`export:check`、导出用`export:download`；仅本人`status=sample`样例质量使用`design:query`。外层拥有任一权限不能读取其他类型，未知类型拒绝，他人任务返回404；Worker身份继续只走内部服务协议。

样例GET及质量GET均用`theme_id`查询参数，默认ivory。样例质量的排队接收与当前缓存响应顶层必有`catalog_version`和`sample_slide_ids`，后者是正式页UUID到原目录样例页ID的明确映射，1–40项；中心核对目录版本、完整页数、唯一身份与1起位置关系。目录缺失/变化或关系不完整返回409并保留已有样例。前端异步结束后回读当前报告并核目录版本，再用映射定位具体页；辅助字段不写入普通质量、正式任务、SSE或审计，不改原报告的UUID或正文。

`catalog/` 保存四主题、11 固定布局、18 灵活预设、样例及布局语料的唯一文件，来源与 SHA256 见 [catalog-manifest.json](catalog-manifest.json)。构建只能由此目录生成只读资源，不各自编辑一份主题。Worker 和中心领取时检查 catalogVersion 一致；字体文件另按许可清单配置。

JSON Schema 描述结构，项目归属、租约、版本、锁定、树引用集合、上传预算和表格/图表维数仍须由服务端执行语义校验。`worker.schema.json` 的 payload 随 type 由应用处理器检查，未校验任务不得正式提交。详细输入/输出和授权见 OpenAPI。

固定页解锁后，`FlexLeaf.rect_override` 保存全页归一化的 `{x,y,w,h}` 首帧矩形，原React与Worker 使用相同坐标。正文和样式编辑保留该值，人工树变更、套用预设及自动重排清除该值后恢复标准树求解。固定页整页撤销沿用 `flex-state` 原子命令，明确传 `layout_mode: fixed`、原 `layout_id`、`blocks` 和 `layout_tree: null`。

SSE 统一输出 `eventSeq/type/data`；任务流附任务身份及状态，整稿流附项目身份、状态和完成数量。文件在任务完成时用 `uploadId/mime/sha256/size/role` 清单绑定当前租约，React通过原api/client.ts复用平台身份读取中心私有Blob，不公开存储地址或 Worker 凭据。

领取请求可带 `waitMillis`（0–10000整数）；省略或0保持立即查询，正值仅在事务外有界等待非权威通知，再执行原正式任务/并发/租约校验。可选`types`只筛选已声明七类任务，空数组不领取，省略维持全部类型。Redis使用中心配置的`aippt:<environment>:`命名空间；只广播对象ID与事件序号，不存放正文、令牌或正式任务真相。成功发布后确认对应outbox，失败保留待派发；重复通知不绕过数据库领取。

SSE可选`delivery`对象如实说明通知通道：`connected/REDIS_CONNECTED`、`degraded/REDIS_UNAVAILABLE`、`disabled/REDIS_DISABLED`，旧帧可以省略。纯`type=delivery`帧沿用当前`eventSeq`、`data={}`且没有业务`state`；前端先处理通道提示，再按业务序号去重，不替换任务快照或触发业务回读。Redis订阅失效时仍定时读取正式状态，提示降级；恢复不重置页内容或业务进度。Schema与离线夹具检查不证明真实Redis联机、隔离环境配置或故障演练已通过。

Worker 成功及失败回执可携带 `execution` 摘要，字段由 OpenAPI 的 `ExecutionSummary` 限定。用量只取实际供应商响应，缺失或超过上限时省略并给出 `usage_status` 和固定原因；不将缺失用量补零、不计算费用，也不在摘要保存正文、提示词或凭据。

Worker文本请求沿 `POST /internal/aippt/tasks/{taskId}/text` 和原机器安全链，使用 `WorkerTextRequest/WorkerTextResponse`。四类文本任务领取时冻结中心路由及本项目托管密钥版本；最多8次逻辑调用、完整请求UTF-8最多2MiB。相同callId/正文的完成调用仅回读，在途或未知结果禁止重发；曾调用文本的失败任务不自动重排。中心显式适配Chat Completions或Responses，Worker不能自选模型、地址、凭据；真实配置缺失时不外呼。AI编辑客户端将原历史与工具Schema编码成JSON工具选择请求，严格解析后交给原工具循环，业务工具及候选副本规则保持原实现。资源/编译通过不能证明真实模型兼容或费用已验证。

PPTX 回读失败仍阻止文件上传及下载，`export` 任务可在 `code=PPTX_VERIFY_FAILED`、`retryable=false` 的失败回执携带 `verification_failure`。字段沿用原回读报告的 `passed/issues/slide_count/expected_slide_count`，每个问题保留检查项、原1起页号、形状和消息；另给出 `diagnostics_truncated/total_issues`，明确是否因条数或字符串上限只展示部分诊断。检查项限原11类、问题最多2000条、消息1000字符、形状200字符，字符按Unicode码点计算。中心再核实际导出类型、租约、快照页数及计数，将失败报告留在任务和对应导出记录；浏览器展示具体页/检查/原因。报告可能含用户正文，读取沿用本人权限，不进入审计摘要、日志或SSE。旧的普通失败回执可省略此字段。

生成页面、AI改稿、换排布、质量和导出任务绑定 `font_manifest_hash`。未核定字体使用 `unavailable`，Worker强制估算；配置实际hash后，按[字体清单结构](schemas/font-manifest.schema.json)核对两文件实际大小、字节哈希及可解析性，任务内使用字节快照。输出 `font_manifest_hash/font_mode` 由中心再核对，`verified` 只表示资源身份通过，不能冒称全部字形精确或字体许可已批准。`fixtures/font-manifest-metadata.json` 仅验证结构及canonical hash；其中大小/hash为虚构值，不含可用字体，不可直接用于真实任务。

契约校验不调用数据库或模型；目录文件不得因为测试失败偷偷更改。实际迁移、用户数据导入、真实 Provider 和发布结果须在项目实施记录中单独确认。

[导入清单结构](schemas/import-manifest.schema.json)核对批次、源哈希、目录版本、owner、资产与核账数据；`complete=true` 时不允许拒绝项或失败对象。项目内部保留原业务和 legacy 字段，中心另执行块/树/稳定引用、凭据禁入、真实对象哈希及显式 owner 映射等语义检查。结构通过不表示真实导入完成。

准备器的 `audit.field_preservation` 逐字段对照原候选与转换结果的哈希，明确列出允许的引用、地址及状态转换；其他字段变化进入拒绝项。输出始终标记 `manual_review_required=true`，实际源数据仍需人工核账。

`fixtures/import-*.json` 是独立脱敏夹具：虚构账号映射、一个项目、一份材料、五页及十五个块，无私有对象。manifest 由 Worker 的真实 `prepare-import` 入口生成，可供中心离线校验；不在真实库中创建该账号或写入夹具。

## 提纲来源预算的只读回读

Worker保留原来源裁剪算法，generate_outline输出pages以及固定数值摘要warnings/source_budget；中心将其保存到同一正式outline JSON及任务output/result，不新增表或另一套任务状态。旧pages-only完成回执继续兼容；正式GET即使空/旧记录也补warnings=[]，预算不存在则省略而非null。OpenAPI的GenerateOutlineOutput/OutlineSourceMetadata/OutlineSourceBudget声明类型、边界和成对字段；used<=original、摘要两数及快照Unicode码点身份由中心实际租约/H2回归证明，不把JSON Schema类型通过当作语义通过。摘要不含来源正文、章节标题或凭据。

复核同时纠正提纲命令Schema误套无ID模型草稿的问题：OutlinePage沿原字段增加可选UUID id，供OutlineSave和GenerateOutlineOutput复用；原纯算法的OutlinePageDraft不改。中心仍负责页ID唯一、合法来源/布局和业务状态校验。
