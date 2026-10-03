# PaiSmart 知识库模块运行说明

> 类型：工程使用说明；维护日期：2026-10-03；维护者：项目执行者。
> 适用范围：`ruoyi-paismart` 的构建、配置核对、独立解析及迁移准备。
> 业务方案、实施进展、检查结果和验收状态唯一维护在[完整迁移设计及实施记录](../../文档/项目/PaiSmart知识库项目/完整迁移设计.md)。本文件只说明实际源码的使用方式。

模块由 `ruoyi-admin` 装配，复用若依身份、权限、托管密钥和既有 `dynamicDataSource`，不提供另一套账户、数据源或独立 HTTP 启动器。业务数据使用显式 `ps_*` 表，文档对象使用 `paismart/` 命名空间。默认不启用模块、消费任务、解析进程、对象操作、外部模型、OCR、支付或运维执行。

## 构建与离线检查

需要与父工程一致的 Java 17 和 Maven。以下命令在 `ruoyi-backend` 下执行，仅用于源码构建及本地测试；依赖下载按当前 Maven 配置执行，不启动服务。

```powershell
$paismartTests = (Get-ChildItem -LiteralPath 'ruoyi-paismart/src/test/java' -Recurse -Filter '*Test.java' | ForEach-Object BaseName) -join ','
if (-not $paismartTests) { throw '未找到PaiSmart定向测试类' }
mvn -pl ruoyi-paismart -am "-Dtest=$paismartTests" -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl ruoyi-admin -am -DskipTests package
```

Python 迁移准备测试只需要标准库，可在工作区根目录执行：

```powershell
python -m unittest discover -s ruoyi-backend/ruoyi-paismart/scripts/tests -v
```

Java 定向检查使用进程内 H2、内存对象和测试替身。测试中的 DDL 只针对临时内存库；这些检查不证明真实 MySQL、Kafka、MinIO、模型、OCR、微信支付、浏览器界面或切换流程已经通过。

## 配置与启用边界

默认样例为 [application-paismart.yml](src/main/resources/application-paismart.yml)，由 admin 的可选配置导入装配。样例没有业务库连接或凭据，不创建、克隆或切换数据库。环境配置覆盖这些键前，应先确认该项目所属的现有 schema、运行通道、用户数据范围和外部操作授权。

| 配置键 | 源码/样例默认值 | 实际作用 |
|---|---|---|
| `paismart.enabled` | `false` | 知识库组件及 HTTP 接口装配开关 |
| `paismart.object-store.enabled` | `false` | 允许访问明确配置的对象存储 |
| `paismart.object-store.writes-enabled` | `false` | 额外允许上传、复制及删除对象 |
| `paismart.object-store.probe-enabled` | `false` | 额外允许状态入口对配置桶发起只读 HEAD；缺配置或关闭时不发请求 |
| `paismart.jobs.kafka-enabled` | `false` | 显式启动本模块 Kafka outbox 发送和任务消费 |
| `paismart.intelligence.external-calls-enabled` | `false` | 允许受控 HTTP 模型、ES及云 OCR 调用 |
| `paismart.intelligence.parse.process-enabled` | `false` | 允许每次解析启动独立进程 |
| `paismart.intelligence.parse.ocr-enabled` | `false` | LiteParse 本地 OCR 选项 |
| `paismart.intelligence.ocr.aliyun-enabled` | `false` | 允许使用受控云 OCR；仍需外部调用开关及托管密钥 |
| `paismart.payment.enabled` | `false` | 微信支付真实调用开关 |
| `paismart.operations.execute-enabled` | `false` | 独立批准计划的对象清理、搬迁、内置导入执行开关；仍需对象写入开关 |
| `paismart.operations.legacy-source.enabled` | `false` | 额外允许从明确批准的原PaiSmart存储只读复制对象；不提供旧源删除操作 |

`contextual-enabled`、`rerank-enabled`、`citation-verification-enabled`、`conflict-llm-confirmation-enabled` 默认均为 `false`。开启模块不自动创建桶、索引、管理员、菜单或内置知识，也不自动运行迁移 SQL。

开启上下文后，每组最多八个目标块依次生成说明；`intelligence.contextual.neighbour-chunks/max-window-chars/max-context-chars` 默认分别为 2、6000、200，窗口包含标题和标记，总窗口与单块说明有界。模型输出仅接受本批编号，说明失败使用原文；正式调用账本阻止结果未知的收费请求自动重送。关闭开关时保留已有说明以供恢复，但 Embedding 与检索文本只使用原文。向量缓存的输入摘要同时绑定原文、实际说明及 Provider 快照，摘要或正式用量账本不符时不会冒充可复用缓存。

对象存储配置使用 `paismart.object-store.endpoint/bucket`，两项均默认为空。Access Key 与 Secret Key 分别通过 `platform.paismart.minio.access-key`、`platform.paismart.minio.secret-key` 的平台托管用途引用解析；源码与样例只保存引用，不填写真实 Key。只读状态探测检查配置桶是否存在且可 HEAD，不读取文档内容、不写对象，也不能证明对象完整性和写入能力。

旧桶复制另用 `paismart.operations.legacy-source.endpoint/bucket`，默认为空，`access-key-secret-ref/secret-key-secret-ref` 默认复用上述本项目托管用途，必要时换成已批准的 `platform.paismart.*` 专用引用。来源地址、桶、密钥来自部署配置，运维请求只提交精确来源键及摘要，不能指定任意主机、桶或凭据。它仅接受原 `merged/<md5或文件名>` 和 `chunks/<md5>/<片号>`；实际源键还须与已迁入文档或续传元数据完全匹配。原桶配置与真实来源读取需另外核对授权，源码准备不会启用或访问旧桶。

Kafka 使用 `paismart.jobs.bootstrap-servers`；源码主题为 `paismart.jobs` 和 `paismart.jobs.dlq`，消费组为 `paismart-center-workers`。默认 `security-protocol=SSL`；明文协议还需 `allow-plaintext=true`。SASL 配置使用 `sasl-mechanism` 和 `jaas-secret-ref`；信任库路径与密码引用分别为 `truststore-location`、`truststore-password-ref`。密钥用途限定本项目。任务正式状态、重试、租约和 checkpoint 保存在 MySQL，Kafka 只承载通知；消费者停止时不能把排队任务当已完成。

模型及 ES 地址先纳入 `paismart.intelligence.allowed-hosts`，默认为空；默认仅 HTTPS，HTTP 还需显式 `allow-http`。Provider 的模型、能力、维度、启用状态及 `secret_ref` 保存在业务表；托管密钥须属于项目 `paismart` 且能力/供应商匹配。ES 配置为 `intelligence.elasticsearch.endpoint/secret-ref/analyzer`，默认端点为空、分析器 `standard`。更换 Embedding 配置须经过索引重建和切换流程。

云 OCR 的密钥默认引用 `ai.paismart.aliyun.ocr`。健康 GET 如实返回配置状态，不发送示例图片；真实图片测试走显式授权的 POST。LiteParse OCR 回调地址 `parse.ocr-callback-base` 仅允许本机中心地址，票据绑定任务、内容版本、租约、使用次数及有效期。

支付商户、应用、序列号、回调地址及私钥/APIv3密钥引用均须配置到批准环境。关闭或缺配置时明确拒绝真实支付，不生成假支付成功。历史迁入订单不能再次充值入账。

上传静态上限 `paismart.upload.max-file-bytes` 默认为 512 MiB；服务端同时取数据库 `maxFileBytes` 策略的较小值（默认 100 MiB），并核对组织的非管理人员单文件大小上限。该值与解析的字节预算是两项限制。默认分片为 1 MiB，允许 64 KiB 至 10 MiB，最多 10000 片，片号从 0 开始；上传 manifest 有效期 72 小时。个人空间由 `POST /paismart/spaces/personal` 显式申请并登记本人成员关系；没有主空间时以个人空间为主，已有主空间保持原选择。新增或修复个人关系以及创建组织的管理关系都会递增真实成员版本，但不会授予业务资格；空间列表保持只读。

## 独立解析 Worker

[prepare-parser-worker.ps1](scripts/prepare-parser-worker.ps1) 在一次 reactor 中构建普通模块 JAR，将各模块运行依赖复制到各自独立的 `target/parser-worker-<本次编号>/lib/`，将主 JAR 同样保存到本批目录，输出本模块的绝对 classpath，并保存依赖列表及主 JAR/依赖的哈希。后续构建不会改变该批主 JAR。旧目录保留，不复用累积依赖；`-am` 中其他模块的依赖不会混入本模块目录。它不启动 Worker，不连接业务库，不清理文件。实际准备及本地解析结果记录在项目唯一实施记录的设计附录，真实部署环境的解析进程切换尚未执行。

在模块目录执行准备命令：

```powershell
powershell -NoProfile -File .\scripts\prepare-parser-worker.ps1 -MavenCommand mvn
```

脚本输出的 classpath 由本次 `target/parser-worker-<本次编号>/ruoyi-paismart-*.jar` 与同目录 `lib/*` 组成。将该完整绝对值配置到 `paismart.intelligence.parse.worker-classpath`；Windows 路径分隔符为 `;`，Linux 为 `:`。Spring Boot 可执行 fat JAR 内的嵌套库不能直接充当这个 classpath。

中心业务解析由 `IsolatedDocumentParser` 启动 `com.ruoyi.paismart.infrastructure.intelligence.ParserWorker`，参数依次为输入文件、尚不存在的输出 JSON、页数预算、字符预算。以下是只解析已批准本地样例的手工入口示意，变量路径需按真实交付位置填写；命令不会启动 Spring 或装配业务数据库。

```powershell
$workerClasspath = 'C:\approved\ruoyi-paismart-3.9.2.jar;C:\approved\parser-worker\lib\*'
$workerInput = 'C:\approved\samples\sample.txt'
$workerOutput = 'C:\approved\samples\sample-output-new.json'
$workerTemp = 'C:\approved\parser-temp'
& java -Xmx256m "-Djava.io.tmpdir=$workerTemp" -cp $workerClasspath `
  com.ruoyi.paismart.infrastructure.intelligence.ParserWorker `
  $workerInput $workerOutput 1000 4194304
```

PDF 样例配置使用 `parse.pdf-engine=pdfbox`，不启用 OCR；扫描 PDF 没有文本时明确失败。选择 `liteparse` 时必须另外提供 `parse.liteparse-command` 的绝对可执行文件路径并审核其安装来源、许可与依赖，源码不会下载或安装该程序。业务 YAML 样例采用 `pdfbox`，代码未覆盖配置时的 PDF 引擎回退值为 `liteparse`，因此配置文件必须随交付核对。

业务包装器默认最多 2 个解析并发、100 MiB 输入、1000 页、4194304 字符、300 秒超时、16 MiB 输出 JSON；Java Worker 堆上限为 256 MiB。业务调用创建独立临时目录，清除环境变量后只保留 `PATH/SystemRoot/TMP/TEMP`，超时强制终止进程树，结束后清理临时文件。Tika Worker 禁止外部实体、嵌入附件、外部解析器和 Tesseract；PDF 文本由 PDFBox 处理。

新版 Pages、Numbers、Keynote 的 `Index/*.iwa` 或 `Index.zip` 由独立、有界的 IWA/Snappy/protobuf 正文适配处理；没有现代格式标识的容器继续由原 Tika 路径识别。适配默认限制 4096 个 ZIP 条目、32 MiB 实际容器读取、32 MiB 解码、50000 个对象、250000 个字段及本次字符预算；损坏、空正文或越界明确拒绝。它读取已存储正文和表格单元格值，不提供视觉分页、图表渲染或公式执行。旧 Apple XML 容器及其他文档仍沿用原解析路径。有效最小 RTF 的 Tika 正文为空时使用 JDK 有界正文回退，保留 Unicode。

这些措施是进程、内存、时间和环境限制，**源码没有实现操作系统沙箱**。手工 CLI 也不会自动清理父进程环境。部署前须为 Worker 落实低权限运行身份、文件范围、网络访问和资源限制，按授权的安全样例逐种格式检查；不能仅凭子进程存在声称毒文件已被操作系统隔离。

## 结构检查与显式 SQL

SQL 位于 [db/paismart](src/main/resources/db/paismart)，需要在已确认的现有目标 MySQL schema 按 `V001 → V002 → V003 → V004 → V005` 审核执行。每个脚本仅在该版本末尾写入 `paismart_schema_history` 标记。没有 Flyway/Liquibase 自动执行、JPA `ddl-auto` 或启动建表入口，SQL 文件存在不表示已应用。菜单增量另见 [菜单增量预演.sql](src/main/resources/db/paismart-menu/菜单增量预演.sql)，应复用平台权限、菜单与 `navType` 机制。

已完整应用当前 `V001—V004` 的目标结构，升级受控评测样本时只审核执行 [V005__evaluation_datasets.sql](src/main/resources/db/paismart/V005__evaluation_datasets.sql)，不重跑旧版建表脚本。`V005` 新增 `ps_evaluation_dataset`，保存样本 owner、服务端对象键、SHA-256、字节数和用例数；样本文件与知识文档、解析任务和知识索引分离。结构升级不上传样本、不初始化对象桶，也不启用外部调用。若旧四版结构已有字段差异，先按差异审核精确增量，不能用 history 标记掩盖缺列。

当前审核结构还包含 `ps_space.description/system_key`、`ps_upload_request`、`ps_recharge_package.benefit`、`ps_reference.metadata_json`、`ps_document_chunk.embedding_input_hash`、`ps_index_revision.validation_json`、`ps_evaluation.job_id` 和 `ps_bootstrap_document`。其中默认组织标识防止误删；上传请求别名保留每个幂等请求的摘要；内置知识逻辑绑定区分重复导入、版本替换和修复。若已有早先审核版的表结构，不可重跑含 `CREATE TABLE` 的整份脚本覆盖它；应在批准演练环境核对版本差异并审核精确的增量 DDL。版本标记齐全但字段缺失仍为 `NOT_READY`，本模块不会补表或降级运行。

`SchemaReadiness` 只检查当前连接的表、全部必需字段和 history 的 1 至 5 版本标记，缺表返回表名、缺列返回 `table.column`、缺版本返回 `paismart_schema_history.version:N`。它不执行 DDL，不自动修复半途失败的脚本；就绪检查也不代替字段类型、唯一约束、索引、账务数据、权限矩阵或外部服务验证。

成员管理列表复用平台用户 `DataScope`，非平台 root 只看到自己管理组织内的关系，本人资格入口保留完整关系。批量空间保存按目标平台用户行串行化，并以 `ps_member.membership_version` 做真实乐观版本检查，初值为 0；`If-Match` 优先于正文 `rowVersion/expectedVersion`。版本失效整批拒绝。为记录版本创建的 `INELIGIBLE` 行不能授予业务资格，只有 `ACTIVE` 状态表示已启用；组织分配保留范围外组织关系和个人空间。

## 受控评测样本

评测可使用 `inlineJsonl` 或受控 `datasetId`，每次创建必须且只能提供其中一种。内联、上传和已登记资源均限有效 UTF-8、900000 字节及最多 500 个用例；`seed` 默认为 42，只接受 `-9007199254740991` 至 `9007199254740991` 的精确整数，`topK` 默认为 5、范围为 1—20。正式评测冻结样本内容、摘要、字节数、用例数及配置，随后仍按执行用户的知识权限检索；上传答案文件不会创建知识文档、解析任务或索引。

`GET /paismart/evaluation-datasets` 列出当前用户可用样本；`POST /paismart/evaluation-datasets` 接收 multipart 的 `file`、可选 `label` 与 `Idempotency-Key`，需业务资格、评测运行权限和对象存储写入开关。上传对象归属当前 owner，服务端生成 `paismart/evaluation-datasets/{owner}/{uuid}.jsonl` 并保存正式摘要；非 owner 无权选择私人样本，平台 root 保留设计中的管理特权。列表返回资源 ID 与业务元数据，不返回对象键或服务器路径。

服务端注册通过 `paismart.intelligence.eval.dataset-resources-json`，每项只接受 `id/label/resource/sha256/visibility/ownerId`。`resource` 必须指向打包 `eval/` 内安全命名的 JSONL，读取时核对登记 SHA-256；`SHARED` 项不设置 owner，`PRIVATE` 项必须登记正整数 `ownerId` 并限制使用范围。调用方只传 `builtin:<id>`，不能指定主机文件、任意 URL 或未登记资源路径。默认 `builtin:paismart-sample` 是脱敏的共享原项目示例，来源及许可见 [eval/NOTICE.txt](src/main/resources/eval/NOTICE.txt)。它需要对应版本的 `paismart.pdf` 才能核对标注，不自动导入该文档，也不保证当前索引与标注匹配；缺少对应知识应如实计为未命中。

管理端本地上传使用用户填写的评测名称作为短标签，保留原 `.jsonl` 文件名；同文件、同标签失败重试沿用上传请求键。中央作业的取消、重试会按正式 `job_id`、owner 和作业版本同步评测状态，已有逐例结果、冻结配置及账本均保留。状态、逐例结果和报告写入在短事务内复核当前租约及关联，过期 Worker 不能覆盖取消；检索和模型调用在这些事务外执行。尚有 `STARTED/UNCERTAIN` 模型调用时，重试拒绝并保留原作业，先按既有用量核对流程处理，不能据重试自动重发。

完整报告尚未生成时，报告按冻结样本顺序读回正式已存案例和配置；`summary.totalCases` 为冻结总数，`processedCases/pendingCases` 为完成与待处理数。检索、裁判及人工一致性只统计已存的可计算案例，未运行样本不补零或算作失败。已有完整报告沿用原摘要，返回状态仍以当前正式评测状态为准；取消或重试不会清空报告和已完成案例。

## 离线迁移准备与复核

[prepare-legacy-migration.py](scripts/prepare-legacy-migration.py) 只读取本地 JSON，输出可审核 SQL 和批次 hash，不连接任何数据库、Redis、对象存储或模型。不自动创建目标用户，不执行 SQL，不搬迁对象。使用明确批准的脱敏资料目录，在模块目录生成导出模板：

```powershell
python scripts/prepare-legacy-migration.py --export-template --output 'C:\approved\paismart-migration\export-plan'
```

该模板只列原项目的明确业务表与允许字段，不导出密码、JWT 或 Provider Key。真正导出必须由获授权的只读账号手工执行，并结合原库 schema 核对；未知或缺失表结构不会被工具猜测。原 MySQL 与 Redis 需保持同一停写时点，另外准备对象摘要/大小、评测报告、人工复核和索引元信息。原 Redis 两种余额及总请求数是迁移余额的前提，不能只由历史消费记录反推剩余额度。

输入 snapshot 使用 `schemaVersion=1`，包含 `snapshotId/writeFrozenAt/tables` 以及 `redisBalances/objectManifest/evaluationReports/humanReviews/indexMetadata`；`tables` 必须完整包含工具的 15 个已核实来源表，即使表为空也应保留空数组。人工 mapping 需要 `approved=true`、`operatorUserId`、明确 `sourceUtcOffset/targetUtcOffset`、每个旧用户到既有平台用户的确认、每个旧组织标签的归属与个人空间 owner。可见范围、跨来源引用、冲突、评测 owner、Provider 托管密钥引用及多窗口限流按实际数据提供确认映射；未知字段、重复标识、无映射、缺 Redis 冻结余额或来源关联缺失时拒绝产出。原组织简介、默认组织标识及套餐权益说明分别进入正式字段，不因重命名而丢失。

未完成上传可以保留续传：对应 `objectManifest` 项使用 `kind=UPLOAD`，提供 `verified=true/fullFileHashConfirmed=true`、独立完整文件 `sha256/byteSize`、`chunkSize/totalChunks` 和经批准的 `expiresAt`，每个已上传片提供 `chunkInfoId/chunkNo/verified/sha256/byteSize/sourceKey/targetKey`。原库只有片 MD5、没有完整文件 SHA-256 和尺寸事实，缺这些证据时工具拒绝猜测。它生成 `ps_upload/ps_upload_chunk`，原 `MERGING` 恢复为可续传 `UPLOADING`，不伪造正式文档或处理成功；manifest 的 `objectOperations` 提供逐片受控复制清单。明确选择终止才使用 `mapping.terminateIncompleteUploads=true` 保留来源归档。

```powershell
python scripts/prepare-legacy-migration.py `
  --snapshot 'C:\approved\paismart-migration\snapshot.json' `
  --mapping 'C:\approved\paismart-migration\approved-mapping.json' `
  --batch 'approved-batch-id' --target-schema 'approved_existing_schema' `
  --output 'C:\approved\paismart-migration\review'
```

输出包括 `migration-manifest.json`、`reviewed-import.sql`、`readonly-verify.sql`。清单保存来源、映射、批次和逐行 hash，以及目标数量和需人工启用事项；`executed=false` 表示仅已准备。目标实体 ID 确定性生成，来源行与批次收据防止重复导入，同 hash 重试不覆盖后续业务写入。正式账号、文档/块、空间关系、会话/引用、两类账务、订单、冲突、评测/复核、限流、关闭的 Provider 和脱敏来源归档均需按清单逐项回读。

执行审核 SQL 前须已显式应用五版结构、备份目标及来源、冻结旧写入、独立核对所有人工用户映射与对象摘要。生成 SQL 会在首个业务插入前校验 history 的 1—5 每个版本；只有四个标记、缺中间版本或用其他版本凑数量均拒绝。SQL 客户端必须遇错立即停止并回滚，不可开启忽略错误或继续提交；批次的 schema、用户、来源 hash 与目标主键检查不是执行授权。目标 MySQL 的事务引擎、SQL mode 和约束行为须在批准演练环境验证。SQL 导入不会复制对象或直接启用 Provider、支付、任务消费及外部调用。

对象搬迁、内置知识导入和清理通过批准的运维计划执行，清单 hash 固定，审批人员须与创建人员不同；每项保存执行收据，重试按收据核对输入。执行同时受运维执行与对象写入开关约束，缺配置明确拒绝。普通对象复制在同一项目配置存储内进行，验证 SHA-256 与大小后切换文档指针并保留源对象；迁入旧对象另用已配置的只读来源，按离线产物的 `objectOperations` 分批提交 `object-migrations.documents`，每批总计不超过 1000 项。正式项包含 `id/legacySourceKey/targetKey/sha256/byteSize`，分片项包含 `uploadId/chunkNo/legacySourceKey/targetKey/sha256/byteSize`。目标已有对象也须验证完整摘要，不能用存在性冒充成功；原桶从不删除。

先审核导入元数据和续传 manifest，保持 Kafka 消费及普通上传入口关闭；再独立批准来源复制并回读每个对象的 SHA-256/大小，确认成功后开放相应消费或续传。导入 SQL 本身不复制对象；仅有新对象指针不表示文件可用。续传清单通过 `GET /paismart/uploads` 在 owner 范围分页发现，选择相同本地文件后核对摘要/大小，分片以数据库及对象读回结果裁决，已丢失片可重发同摘要修复。迁入正式文档虽保留旧块与引用，缺少完整正文或真实页数时仍受控解析同原对象补齐元数据，保留旧块 ID；已有完整元数据不会重复解析，解析/模型/OCR 的开关及账务仍按正常链路裁决。

内置导入只读取批准的 `paismart/imports/` 对象并进入正常上传/处理链路。`logicalKey` 默认为文件名，同 owner/空间/逻辑标识在不同计划中保留同一绑定；相同摘要不重复建立文档，当前失败、页块缺失或 ES 当前索引计数不符会提交真实修复任务，显式 `repair=true` 也可请求重建。新版替换将旧文档软删并保留原文对象与账务，前一版本仍处理时拒绝替换；后续处理任务状态须单独查看，导入计划完成不代表新文档已经索引就绪。

已合并上传的本次临时片在事务提交后有限回收；回滚不会触发删除，回收失败保留数据库回执，供批准计划重试。过期未完成片及失败回收片由 `cleanup-plans.uploads` 的精确快照清理，状态和片摘要变更会拒绝旧计划；每片删除前后检查任务租约，清理与执行收据同事务。原文对象仅在清单中超过 30 天软删后物理清理，保留 tombstone 审计与账务记录，不接受任意主机路径或跨项目清空。

## 停止与回退准备

1. 切换前保存本项目数据库、对象 manifest、旧索引/Provider 快照和配置开关，并记录停写点及独立核对结果；旧系统停止本项目写入后再切换读写入口，平台账户和其他项目通道不随之改变。
2. 发生版本、对象 hash、引用、权限或账务差异时停止相应消费及外部写入，保留任务、checkpoint、执行收据和原对象。未完成事务由审核执行客户端回滚，不能把部分导入视为迁移成功。
3. 对象指针切换可依据清单恢复到保留的源对象，恢复前重新核对其 hash、大小与权限。已清理对象只能从事前备份恢复，软删期限和 tombstone 本身不能恢复已被物理删除的内容。
4. 切换后产生的新会话、订单、Token 流水、文档及成员关系必须独立导出和对账，审核补偿后再回退入口；不能直接覆盖目标、清空收据、删除账务或重放历史充值。关闭的旧 Provider/索引须另经正式重建与批准切换，不因导入存在而自动可用。
5. 源码回退只处理本模块新增和精确共享增量，保留用户及其他项目的后来修改；真实数据库、菜单和对象回退需独立授权与演练，不能用删除 SQL 或模块目录代替。

**真实业务库迁移、对象搬迁、支付、生产切换和回退演练均未执行。** 本说明提供准备步骤；真实运行结果仍以唯一实施记录及对应业务证据为准。
