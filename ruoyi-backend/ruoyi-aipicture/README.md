# AI 图片编辑 Java 业务模块

本模块持有素材、会话、文档与 20 条历史、选区、计划、运行、逐项批量、导出和评测的正式状态。图片生成、SAM、OCR、修补、调色、渲染与指标计算由 `python/aipicture-runtime` 执行。产品与实施事实唯一记录位于 `文档/项目/AI图片编辑项目/`。

默认 `aipicture.enabled=false`。启用后沿用用户指定的既有若依 MySQL `dynamicDataSource`，仅使用 `aip_*` 表；配置显式 JDBC URL 时也须指向已批准的现有连接。本模块不会创建数据库、账号或自动应用 DDL。`V001` 是业务表候选 SQL，`V002` 是平台菜单候选 SQL；`AiPictureSchemaGuard` 只读核对结构。

资产目录必须是本项目私有绝对路径，不能在公开的 `ruoyi.profile` 内。浏览器通过共享若依登录及 owner 鉴权下载；Worker 通过精确内部 HMAC 路径和 run/attempt/lease 限定冻结素材读取。Worker 只能上传产物、报进度和提交回执，不能写业务库或更换 owner。服务密钥只用环境配置引用。

公开 API 在 `/aipicture`，使用若依 AjaxResult / TableDataInfo、`@ss` 和 `@Log`。保留原 React snake_case 业务字段；额外写入元数据为 `schemaVersion=1`、`toolRegistryVersion=2026.10-r2`、`idempotencyKey` 和会话写入 `expectedVersion`。列表为 rows/total。生成、像素工具、选区和导出返回可轮询 run，原图下载仍为 blob；完成导出用 `result.export_id` 下载。

22 个工具的参数唯一来源为打包到 classpath 的 `contracts/aipicture/v1/tools.schema.json`。Java 执行九个文档命令，Python 执行十三个图像工具。同步文档命令会创建成功的正式 run，noop 不增加历史、不切断 redo；撤销恢复内容但 revision 单调递增。资产最新文档单独持久化，不依赖历史窗口。

工具结果仅新增图片墙时也保存动作、参数、输出素材和文档快照历史，并增长 row_version；画布 revision 与已有选区保持。只有真正附加新素材才截断 redo、占用原 20 条历史窗口；重复回执与已附加产物不再记录。选区和导出不会进入这类历史。

执行领取在 MySQL 原子分配 attempt/lease，进度和事件持久化；相同回执不会重复采用，旧 attempt 拒绝。确定性任务租约过期有限重试；已知异步生成 `provider_task_id` 仅查询原任务，未知付费结果进入 `external_result_unknown`。计划固定遮罩宽高，画幅变化后拒绝旧选区；成功步骤提交后下一步骤才进入独立事务，下一步失败不会撤销已有成功步骤。批量每张输入独立 run，可保留部分成功与只重试失败项。

公共 run、智能助手和批量页取消统一停止尚未完成的子任务；迟到回执与汇总不能恢复已取消母任务。用户明确重试已知失败项、成功创建新 run 后才恢复母任务执行状态，成功项与取消项保持，重复幂等键不会再派发。MySQL 1213 死锁只对已经回滚的纯数据库短事务最多重试三次；嵌套事务、提交后的错误、非 1213 错误不重试，计算、Provider、规划 HTTP 和产物文件写入均不在此重试范围内。

用户上传保持最短边 32 像素，私有分割图层产物允许真实 1 像素以上包围框，均限制单图 20 MB、5000 万像素。ZIP 最多选择 200 个不同正式素材；纯非当前素材 ZIP 只冻结所选素材，选中当前素材才补入实际画布合成依赖。已经选择的图层素材不重复计预算，额外依赖最多 64 个，冻结引用最多 264 个；PNG 与空清单 ZIP 只读取画布图层依赖，普通计算仍最多引用 64 个素材。当前素材使用会话画布合成，其余素材保留原始字节，选区不会占用导出引用，Worker 原像素和总字节限制保持。供应商已有异步任务号但结果暂未可取时，同一 run 最多恢复两次原任务查询；同步请求结果不明以及批量不明项均拒绝自动重新付费。

定向技术检查：

```powershell
mvn -o -f ruoyi-backend/ruoyi-aipicture/pom.xml test
```

真实 MySQL 技术检查为显式 opt-in：向测试进程注入现有 `RUOYI_DB_URL`、`RUOYI_DB_USERNAME`、`RUOYI_DB_PASSWORD` 与 `AIPICTURE_MYSQL_INTEGRATION=true`，运行 `-Dtest=AiPictureMySqlIntegrationTest test`。它不执行 DDL，不建库/账号，只读写并清理自己生成的随机 UUID 技术记录及临时文件；定向领取不会消费其他任务。批量 fixture 在创建事务内推迟自身派发，再在受控领取事务内精确恢复该 run 的 available_at，不暂停运行 Worker、不修改生产调度。受控 PNG 回执只验证持久性和协议，不能证明真实 Provider/SAM/OCR 的效果。

完整源接口对应：assets / library → `AssetService`；sessions / patch / history / undo / redo / selection → `SessionService`、`RunService`；generations / runs / events → `RunService`；messages / confirm / cancel / retry → `PlanService`；batches / 单项恢复 / export、session exports、Web evaluation → `BatchExportEvaluationService`；health / capabilities → 私有 Worker 能力回读。原账号 API 由共享若依替代。源 SSE 进度在当前客户端改为鉴权轮询及持久事件查询，不在业务前缀开放匿名 SSE。

本机验收登录恢复统一见[共用操作说明](../../文档/若依框架/多项目并行开发启动指南.md#本机验收登录恢复)。全部本机验收通道启用辅助能力；先复用有效会话，失效时仅用已授权账号凭据辅助填写当前验证码并正常登录。浏览器无法控制或账号凭据未获授权时单独报告所需条件；恢复登录后回到待验步骤，既有业务结果与证据保持原判定。

## 一次性原数据导入

`migration/ImportManifest.java`、`ImportVerifier.java` 和 `AiPictureImportTool.java` 是独立人工工具，不注册 Spring Bean、HTTP 接口或启动任务。它读取人工导出的本地 JSON 包和同根图片，默认 `verify` 不构造数据源、不复制文件。源业务表完整对应为：

| 原数据 | 目标数据 | 转换边界 |
| --- | --- | --- |
| `assets` | `aip_asset` | 保留 UUID、kind/source、像素及原字节；对象放入私有 `imports/<数据集摘要>/<UUID>.<格式>` |
| `edit_sessions`、`session_assets` | `aip_session`、`aip_session_asset` | 保留墙顺序、当前/原图、文档、原历史指针；目标版本从 0 开始 |
| `edit_history` | `aip_history` | 保存原参数/结果及 before/after 文档；原初始化不可撤销，最多 20 条窗口不得丢失首个可撤销快照 |
| `tool_runs` | `aip_run`，批量另有 `aip_batch`、`aip_batch_item` | 保存原进度、结果、错误、时间、批量来源顺序和部分成功；开放任务与可能收费的源失败任务隔离为 `external_result_unknown`，不创建 dispatch |
| `agent_runs` | `aip_agent_turn`、`aip_plan_step` | 保留目标、回复、完整原计划、依赖、步骤状态及关联 run；开放步骤隔离，不自动推进 |

源 `users` 只提供 UUID 到**既有**若依 `user_id` 的显式 owner 映射。包内禁止密码、JWT、token、API key 和数据库 URL；工具不创建账号。原库没有独立导出或评测表，不能凭工具调用伪造相应源记录。`asset_documents` 是可选明确来源的补录；缺失时按当前文档、原保留历史、原 raw fallback 的顺序生成墙快照，并报告出处，不声称恢复不存在的长期历史。`selections` 可选承接人工导出的 Redis mask/markers/expiry；没有导出就报告需要重选。

manifest 顶层固定为以下结构，版本是字符串 `"1"`。六个源数组中的字段按对应原模型完整导出，UUID 使用完整形式；所有时间采用含时区的 ISO8601 字符串。`session_assets` 保持原三字段 `session_id/asset_id/position`；`edit_history.params.before` 和 `result.document/current_asset_id/revision` 必须完整保留。任何关联断层、未映射 owner、跨 owner 引用、重复 UUID、历史窗口缺口或目标字段无法容纳的原值，都会阻止**整个批次**写入，而不会部分入库后留下断层。目标助手回复是 MySQL TEXT，按 UTF8 字节检查至多 65535；超限源回复保留原包并拒绝该批次，不能截断文字冒充完整导入。

```json
{
  "version": "1",
  "source_dataset": "picture-original-export-20261003",
  "import_batch_id": "0fca6f2e-e0c9-4dfa-92ec-5b8b34481af1",
  "exported_at": "2026-10-03T10:00:00+08:00",
  "owners": [{"source_user_id": "8dc425ce-4864-4ea4-b56d-0c9f91ff0f85", "ruoyi_user_id": 123}],
  "assets": [],
  "edit_sessions": [],
  "session_assets": [],
  "edit_history": [],
  "tool_runs": [],
  "agent_runs": [],
  "asset_documents": [],
  "selections": []
}
```

每个 asset 在原模型字段之外必须提供 `file`（包根下相对文件路径）和 `sha256`；原 `storage_key` 必须是 `users/<源owner UUID>/<asset UUID>.<jpg/png/webp>`。文件限 20 MB、5000 万像素，需真实解码且格式、宽高、alpha、字节数、hash 与声明一致。用户上传短边保持 32，内部图层或遮罩允许 1。路径不能绝对、上溯或通过链接逃出包根。ImageIO 没有 reader（例如常见 JDK 的 WebP）时，必须显式传入**已安装**且含 Pillow 的 Python 绝对路径；固定 `-I -c` helper 只读本地像素，不执行源脚本，不使用 Worker HTTP、模型或网络。缺少 decoder 时拒绝包，不能以文件头检查代替。

人工执行入口为 `com.ruoyi.aipicture.migration.AiPictureImportTool`，使用已构建模块及其现有依赖的 classpath：

```text
verify <manifest.json> [现有Python.exe绝对路径]
apply <manifest.json> <私有对象绝对目录> <现有公开profile绝对目录> [现有Python.exe绝对路径]
recovery-check <recovery-批次UUID.json> <私有对象绝对目录> <现有公开profile绝对目录>
```

`apply` 与 `recovery-check` 必须另经实际数据范围批准，再向进程显式注入既有 `RUOYI_DB_URL`、`RUOYI_DB_USERNAME`、`RUOYI_DB_PASSWORD`；工具不读取 `.env`、不猜数据库、不执行 DDL。两种模式还必须显式给出既有若依公开 `profile` 的绝对真实目录，私有对象路径的词法位置、实际位置和现有祖先不得落入其中；CLI 不启动 Spring，不能靠未初始化的静态配置跳过此守卫。写入前完整校验并只读确认 `sys_user` 未删除 owner。正式业务行和 `aip_import_map` 在同一事务提交，文件使用不可变 key 和 create-new/hash 校验，绝不覆盖碰撞对象。输出 `inserted/replayed` 是实体映射数，包含 owner 映射，不是新建账号数。

源 `failed` 不证明没有收费：九个可能外部调用的工具（生成、换背景、扩图、超分、区域消除/替换、拆层、物体提升、营销图）在源失败时也保守隔离为未知。含外部操作的失败批量项同样隔离，原状态、错误和结果保存在母 run 的源快照/原结果；成功项继续保留并可导出，关联未知 run 的旧计划也不能重试。纯确定性失败保留 `failed`。工具没有通过人工声明“未收费”放宽守卫的入口。

相同源数据重放仅在映射、owner、行指纹及对象 hash 全部一致时接受；已存在而无本源映射的 UUID 或后续编辑会被拒绝。导入自己的 JSON 复制、指纹和侧车保留 NULL 列，恢复检查可识别原 NULL 后来被写值的变化，不修改平台全局 JSON 配置。复制前在包根保存 `recovery-<批次UUID>.json`，记录 manifest/行/对象指纹与本批次创建范围；文件复制或事务失败时保留侧车和对象，供明确定位，不能将侧车失败解释为正式事务一定未提交。`recovery-check` 只读核对后续编辑、新引用、映射断层和对象变化，**没有自动删除/覆盖功能**；实际恢复仍需冻结后续写入并批准步骤。

当前导入工具只用人工文件与数据库替身单元验证调用、幂等及故障路径；这些检查不能代替真实 MySQL 事务、实际原数据库/对象包完整性或一次性数据迁移验收。本轮没有执行真实导入。
