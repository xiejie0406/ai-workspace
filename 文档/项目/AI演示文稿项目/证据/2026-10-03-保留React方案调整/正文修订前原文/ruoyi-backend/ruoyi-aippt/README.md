# AI 演示文稿中心模块

本模块实现批准迁移方案的正式 MySQL 中心。Spring 启动复用若依用户、菜单权限、`dynamicDataSource`、MyBatis 和平台事务，业务 Mapper 扫描只覆盖本模块。用户 API 位于 `/aippt`，Worker API 位于 `/internal/aippt/tasks`。共享 API、内容和导入清单位于仓库 `contracts/aippt/`；结构 Schema 是第一层，中心进一步核对所有者、版本、租约、稳定引用和对象字节。

## 默认关闭与运行前提

`aippt.enabled`、`worker.enabled`、`provider.enabled`、`image.enabled`、`cleanup.enabled` 均默认 false。关闭时不注册业务持久层或定时任务，独立 internal 安全链始终拒绝访问。启用 Worker 必须设置至少32字符的 `X-Aippt-Worker-Key`，用户 JWT 不能访问该机器通道。图片 Provider 同时需要中心任务 `image_enabled=true` 和 Worker 自身图片开关，不影响私有上传图片读取。

目录唯一源为 `contracts/aippt/catalog`，Maven复制进制品，启动按 `catalog-manifest.json` 的版本、数量、大小、SHA-256逐文件校验。受控外部 `catalog-path` 必须在其同级提供 manifest。Worker和中心目录版本均为 `aippt-1-90c52568ea7d`。

本地存储需要显式私有 `storage-path`，拒绝平台公开 profile 子目录、非法对象键和符号链接。COS 使用官方 `cos_api:5.6.227`，对象 ACL 显式 Private；秘密通过现有环境管理注入。用户读取要求 Bearer 与所属权限；5分钟下载票据还核对 owner/project/asset/到期。PNG/JPEG/WebP先魔数再完整解码；WebP使用 `imageio-webp:3.13.1`，大小8MiB、最多四千万像素。文档10MiB，PPTX100MiB，ZIP遍历/数量/解压总量均受限。

清理建议保留 `cleanup.orphan-hours=24`、`deleted-days=7`、`export-days=30`、`event-days=7`，这些数值待环境审核；消费者必须另开 `cleanup.enabled=true` 才执行。活动任务的快照和孤儿对象延后清理。过期导出/快照先逻辑删除、引用归零后异步清理。对象删除失败保留补偿任务，不删除清单。

上传前要求可写的绝对私有 `compensation-path`；未单设时使用已明确配置的 `storage-path/.aippt-compensation`。COS也必须具备该本地持久路径，缺失时在对象写入前拒绝。清单只含中心对象身份、类型、大小与哈希，在 put 前刷盘，正式事务 afterCommit 后标记 registered；回滚或补偿删除失败保留 stage_started/cleanup_pending。默认关闭清理不会消费清单。显式启用后按临时保留时间进入现有 outbox 重试，查询同ID/同key正式资产行作保留证据，并在项目锁内封住迟到登记。进程崩溃后的未完成记录可据此恢复；不扫描其他目录、不记录材料正文或凭据。

`GET /aippt/operations/health` 要求既有运维查看权限，复用平台 `dynamicDataSource` 与 `RedisConnectionFactory`。配置开关、数据库 `SELECT 1` 可达、14表关键列/dispatch gate及独立V1历史checksum可用、Redis `PING` 分别反馈。状态仅返回固定代码与检查时间，不返回连接串或底层异常。探测使用2条私有daemon线程与有界队列，连接获取和命令合计等待最多2秒，SQL语句另设1秒超时；结果缓存5秒，饱和返回busy，关闭时停止线程。健康检查不写数据库/Redis、不执行DDL、不联系Provider；本轮只用Fake依赖验证，真实环境可达性仍待授权核对。

## Redis 通知与数据库降级

配置入口为 `ruoyi-admin/src/main/resources/application-aippt.yml`。`aippt.environment` 默认空，启用主模块且保留 `aippt.redis.enabled=true` 前，必须核定运行通道并显式注入 `AIPPT_ENVIRONMENT`；允许1–32位字母、数字、下划线或连字符，首字符须为字母或数字；通知命名空间固定为 `aippt:<environment>:`。不接受请求或消息指定环境。主模块默认关闭时不创建派发或订阅组件；可另设 `AIPPT_REDIS_ENABLED=false`，通知组件不连接Redis，任务与进度继续按权威数据库运行。该开关只控制本模块后台通知/订阅，不关闭若依共享Redis；有权限操作者显式请求 `GET /aippt/operations/health` 仍会有界只读PING平台Redis，不写入。

项目事务只向现有 outbox 写入 `task_created`、`event_ready`，通知只含任务ID或项目ID/事件序号。后台使用平台 `RedisConnectionFactory`，在数据库事务外以有界线程、队列和等待发布，成功后按原版本CAS确认；失败保留pending并退避重试，可能重复通知。Redis不存业务正文、租约或第二套任务状态，不提供 exactly-once 保证。订阅失败不阻止中心启动。

Worker `claim` 支持可选 `waitMillis`（整数0–10000，省略为0），等待不持有数据库事务；可选 `types` 省略领取全部七类，空数组不领取。通知唤醒后仍由数据库行锁/CAS分配租约。SSE通知只促发权威事件回读，每秒数据库轮询和有界历史重放始终保留。事件可附 `delivery`：connected/REDIS_CONNECTED、degraded/REDIS_UNAVAILABLE、disabled/REDIS_DISABLED；纯delivery帧沿用当前序号且不改变业务状态。客户端据此提示通知降级，业务终态仍由task/deck决定。真实Redis连接、恢复及多实例行为尚需核定运行通道后联机验证。

## 数据库迁移：仅显式工具

`src/main/resources/db/aippt-migration/V1__create_aippt_tables.sql` 和 `../sql/ruoyi-aippt-menu.sql` 只是待审核脚本，应用启动不执行DDL，也不写迁移历史。本DDL使用 `utf8mb4_0900_ai_ci`，**要求 MySQL 8；尚未核定真实目标版本**。独立历史表为 `aippt_schema_history`，不提供 clean、自动baseline或创建数据库。已有任一受管表且无可信历史时拒绝接管；部分DDL失败记录 failed，MySQL DDL不能整体事务回滚，需按备份人工恢复。

离线 main 不启动 Spring、Web 或 Scheduler。用经构建的运行时 classpath 调用（包含本模块、平台依赖和 MySQL 驱动）；`$AipptClasspath` 是操作者指定的类路径：

```powershell
java -cp $AipptClasspath com.ruoyi.aippt.migration.AipptSchemaCli
java -cp $AipptClasspath com.ruoyi.aippt.migration.AipptLegacyImportCli --manifest import-manifest.json --object-root D:\已核账对象 --owners owner-map.json --source-snapshot snapshot.json
```

上述默认只计划和离线验证，不连接数据库/对象存储。执行迁移需另加 `--apply` 并明确设置 `AIPPT_MIGRATION_ENABLED=true`；执行导入需另加 `--apply` 与 `AIPPT_IMPORT_ENABLED=true`。两者还要求 `AIPPT_JDBC_URL`、`AIPPT_JDBC_USER`、`AIPPT_JDBC_PASSWORD`、`AIPPT_TARGET_DATABASE`，目标库名称必须和 JDBC 实际 catalog 相同；不沿用隐式默认库，不打印秘密。导入 additionally 需要 `AIPPT_STORAGE_PATH`，或 `AIPPT_STORAGE_DRIVER=cos` 与 `AIPPT_COS_BUCKET/REGION/SECRET_ID/SECRET_KEY`。

## 旧数据导入与撤回

Python `app/worker/migration.py` 读取已提供的离线源快照、显式 owner映射和对象清单，准备并核账 `aippt-import-1` 清单（结构见 `contracts/aippt/schemas/import-manifest.schema.json`）；它不连接旧数据库，也不自行导出源库。中心再次拒绝全清单凭据字段，核对源快照哈希、owner映射、目标 `sys_user` 存在、主题/版式/块/布局树/稳定引用和每份对象大小哈希。旧位置引用只能由已证明源顺序的准备器改写。

原项目、材料、提纲、页面（以及提供日期的小节）的 `created_at/updated_at` 保留原带时区ISO字符串，正式排序列按同一瞬间以UTC写入，列精度沿用已有MySQL定义。迁入时间单独记 `imported_at`，新增资产和审计使用迁入时间。日期省略时沿用兼容默认迁入时间；日期已提供却为空、非法、超出MySQL范围或没有明确时区时拒绝准备，不猜旧时区。重复清单不刷新已导入日期，后续用户修改仍按普通保存流程更新正式 `updated_at`。

正式页面顺序为1起。备注、人工锁、内容和原revision保留；生成中页转pending，不带旧job或租约。旧确认提纲变draft，保留legacy签名，按新来源重算指纹，用户重新确认后可以续跑，无需强制再次请求Provider。项目 owner继承显式映射，旧图片跨项目共用时映射成各项目私有资产。

对象在数据库事务外用本次随机UUID键暂存，提交前再次校验字节。暂存开始前强制写入并刷盘 `aippt-import-compensation.jsonl`，提交后追加 committed；进程意外中止时可核对 journal 中仍未结束的对象键，必须先查询正式资产引用后再清理。项目短事务持有中心gate，再次核对批次/owner/源快照/规范化entry与对象规格的检查点；重复执行只跳过一致项目，不覆盖导入后人工写入。失败回滚本项目正式行，并且只删除本次stage；删除失败追加 cleanup_pending。每次已成功项目保留检查点，后续失败可从未完成项目续跑。

显式 `--rollback` 同样需要 import门禁和源清单，逐项目锁定后要求每一行仍属于同批次/源哈希且 revision 未变化，任何新增任务/事件/人工写入都拒绝整批撤回。撤回正式行后保留私有对象，按已审核对象清单另行清理，避免误删后来写入。

## 技术检查与真实环境范围

```powershell
cd ruoyi-backend
mvn -o -pl ruoyi-aippt -am '-Dtest=Aippt*Test,ProposalOperationsTest,AssetValidationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

隔离测试涵盖 JSON/样式/图表/树/锁定、逐操作采纳撤回、目录篡改、真实H2 MyBatis事务和并发CAS、有效软取消/失效fencing/硬期限/重复回调、文件验证和导入准备。它们不等于真实MySQL迁移、Redis通知与断线恢复、COS联机、Provider成本、字体安装或浏览器业务验收。正式开启仍需批准目标库/DDL/菜单权限、存储目录或COS权限、Worker机器钥匙、Provider与字体环境，以及前后端联机核对。
