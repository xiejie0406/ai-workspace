# PaiCLI 若依联机工程

本目录按[完整迁移设计](../../文档/项目/PaiCLI智能编码项目/完整迁移设计.md)实现。实际检查与未完成范围唯一维护在[实施记录](../../文档/项目/PaiCLI智能编码项目/开发记录/2026-10-03-完整迁移实施记录.md)。参考项目只读；此工程的构建不迁移真实数据库，不启动模型、微信或 Docker 评测。

| 模块 | 用途 | 正式状态归属 |
|---|---|---|
| paicli-api | 若依账号、菜单权限、MySQL 会话/任务/批准/回执/记忆/连接配置 | 中心 MySQL |
| paicli-core | 原工具、模型协议、三种算法、上下文、检索、浏览器与MCP实现 | 不持有第二套正式状态 |
| paicli-runner | 设备会话、租约、单次许可、回执重传、原生执行适配、Runtime兼容接口 | 中心决定，独立进程执行 |
| paicli-cli | 原终端渲染与联机命令入口 | 与Web共享中心会话 |
| paicli-eval | 原评测代码、fixtures、validators、容器配方 | 隔离评测，不自动运行真实外部任务 |

本机验收登录恢复统一见[共用操作说明](../../文档/若依框架/多项目并行开发启动指南.md#本机验收登录恢复)。全部本机验收通道启用辅助能力；先复用有效会话，失效时仅用已授权账号凭据辅助填写当前验证码并正常登录。浏览器无法控制或账号凭据未获授权时单独报告所需条件；恢复登录后回到待验步骤，既有业务结果与证据保持原判定。

## 构建与检查

在仓库根目录使用已配置的 JDK 和 Maven；目标字节码为 Java 17。中心 API 继承若依依赖，原生模块独立管理 Jackson 2、OkHttp、JLine、Lanterna 等原依赖。

```powershell
mvn -f ruoyi-backend/pom.xml -pl ruoyi-paicli/paicli-api -am -DskipTests=false test
mvn -f ruoyi-backend/ruoyi-paicli/pom.xml -pl paicli-core,paicli-runner,paicli-cli,paicli-eval -Pquick -fae test
mvn -f ruoyi-backend/ruoyi-paicli/pom.xml -pl paicli-cli -am -DskipTests package
mvn -f ruoyi-backend/ruoyi-paicli/pom.xml -pl paicli-eval -am -DskipTests package
```

`quick` 沿用原项目五项慢测试排除，完整检查使用不带该profile的 `test`。定向测试、原失败与修复后的结果见实施记录。原生终端包为 `paicli-cli/target/paicli-cli-3.9.2.jar`，独立评测入口为 `paicli-eval/target/paicli-eval-3.9.2.jar`；终端包不含独立Eval模块。评测另产出 `paicli-eval-3.9.2-agentbench-runner.jar`，供受信评测中继使用，不作为普通终端入口。不向已运行若依服务复制或发布文件。

仅检查评测命令帮助可使用：

```powershell
java -jar paicli-eval/target/paicli-eval-3.9.2.jar --help
```

真正执行评测仍须先满足配方、文件安全、工具链与隔离前提；帮助入口成功不能证明正式评测通过。

## 受控运行

中心默认 `paicli.enabled=false`。真实启用前须确认现有业务数据库名称、中心控制密钥、若依私密产物存储、平台菜单权限、个人执行宿主及可用模型，并审核 `paicli-api/src/main/resources/db/paicli-migration/` 中的业务表和菜单脚本；脚本不在启动时自动执行，不创建或克隆数据库。新版不再创建工作区或成员，所有权来自若依稳定用户ID。供应商 Key 复用受控 secretRef，不把原 config.json 的密钥复制为普通业务JSON。个人入口的实际检查及运行版本唯一见[本功能实施记录](../../文档/项目/PaiCLI智能编码项目/功能/FEAT-PAICLI-003-个人目录与简化使用/实施记录.md)。

终端使用 `PAICLI_CENTER_URL`（HTTPS或明确的本机回环HTTP）、`PAICLI_USER_TOKEN`（若依用户JWT），可选 `PAICLI_DIRECTORY_CONTEXT_ID`、`PAICLI_MODEL_ID`。未指定目录或模型时由中心选择本人默认值，并路由对应受信宿主；用户不必登记Runner。凭证从安全输入提供，不写入脚本、命令参数或版本库。旧工作区客户端须升级，不能用旧workspace参数静默执行。

```powershell
java -jar paicli-cli/target/paicli-cli-3.9.2.jar --help
java -jar paicli-cli/target/paicli-cli-3.9.2.jar
java -jar paicli-cli/target/paicli-cli-3.9.2.jar --directory <本人已核实目录ID>
```

`PAICLI_RENDERER=inline|lanterna|plain` 选择原终端渲染器；斜杠命令交中心裁决，未知命令不会转发模型。中心不可用时不回退到原本地可写控制面。

独立设备进程由部署者配置 `PAICLI_PERSONAL_ROOT`、`PAICLI_OWNER_USER_ID` 与个人文件目录外的专用 `PAICLI_RUNNER_STATE`。运行身份在 `<个人根>/users/<稳定用户ID>/runtime`，文件在同一用户的 `files`；新对话使用 `files/conversations/<sessionId>`。宿主运行身份和任务实际cwd分别核对，不把浏览器任意路径当作授权。首次从安全输入提供 `PAICLI_RUNNER_TOKEN` 单次绑定码；码五分钟有效，成功后保存受限设备凭证到当前本机用户独占的 `device-credentials/` 子目录，后续可由该缓存续期。新的绑定码可替换已撤销设备的缓存；缓存按中心地址与固定宿主身份分开。普通用户页面不显示这些部署表单。

```powershell
java -jar paicli-cli/target/paicli-cli-3.9.2.jar runner
java -jar paicli-cli/target/paicli-cli-3.9.2.jar runner --once
```

Runner按可信宿主运行，执行时核对任务冻结的本人真实目录；相同或重叠物理目录的锁不因另一个会话而放行。读工具允许受控并行，写操作串行且核对资源摘要。纯文字任务没有文件目录时禁用全部工具、MCP和快照。缓存只保存派生索引、快照、设备凭证和待确认回执；重启不会把未知副作用改回可执行队列。命令执行要求中心核验与本机 `REQUIRED` 隔离同时成立；Windows没有有效隔离时拒绝执行命令。

工作根锁检查真实home/`.cache`的可写边界及PaiCLI私有容器、锁目录和锁文件权限；状态目录另由进程排他锁保护，不能让不同工作根共用同一实际状态目录。状态、凭证、回执和完整结果目录核对实际owner、权限及完整父链；既有不安全目录会拒绝启动，不自动修改共享目录权限。新目录和暂存文件在创建时即具有私有权限，正文写入前完成核对。完整工具结果按每次执行两份证据合计512MB、累计2GB及256文件限定，目录内所有实例共享剩余容量预留并核对实际磁盘空间，最多4个受控读工具共同预留；JSON最坏转义空间不等于允许无界MCP响应。缓存或磁盘不足时不消费许可、不执行新副作用，旧证据须先对账。

正式数据冻结继续要求`SecureDirectoryStream`已捕获父目录的相对移动和清理能力；Windows JDK不能提供该能力时明确拒绝，不能以路径重检代替。Eval工具链及Python verifier/Docker按原用途独立准备，本机技术fixtures不等于正式评测已经运行。

图片在解码前核对实际格式和最多4000万像素，保留50MB源与5MB输出base64边界。无WebP codec时支持经过容器/像素头核对的静态小图直通，动画和不支持结构明确拒绝。Harness三份报告使用Runner已批准状态目录的私有派生缓存，不默认在工作区生成文件。

Runtime兼容接口显式启动，绑定回环地址、固定工作区/Runner/模型，并另需至少16字符的 `PAICLI_RUNTIME_API_KEY`；它将线程、任务、增量事件和取消转交中心。

```powershell
java -jar paicli-cli/target/paicli-cli-3.9.2.jar serve --port <空闲回环端口>
```

原本机历史导入先使用离线导出器，从明确指定的原 `.paicli` 资料目录复制一致快照进行只读解析。导出器核对 SQLite/WAL 完整性及转换前后的源摘要，原进程继续写入会使导出拒绝；不会初始化或重置原 SQLite，也不会将运行中任务复活。敏感凭证、Cookie、环境变量和授权内部字段不进入导出资料包。

```powershell
java -jar paicli-cli/target/paicli-cli-3.9.2.jar export-legacy --source <原资料目录> --output <新资料包路径>
```

生成的 `paicli-legacy-v1` 资料包经Web上传后先预检，再由明确的中心导入动作写入当前用户及工作区；回撤检查导入后的新增修改，存在冲突则保留。导出文件不包含原项目密钥，历史账号必须由用户在中心重新核对绑定。

真实模型、微信、MCP启动代码、共享浏览器、数据库迁移和正式评测的环境与副作用须按主设计准备并验证。本机编译或fixture通过不代表这些通道已经联机，也不代表用户已接受或已经发布。原参考目录尚未核明独立许可证，不能据本地实现推断已获公开再分发许可。

## 中心持久化与协议边界

`paicli-api` 在共享若依MySQL内维护 `pc_` 表，复用平台JWT、菜单按钮权限、MyBatis、PageHelper和受控密钥服务。`/paicli/v1/**` 使用平台用户权限；`/paicli/runner/v1/**` 使用独立Spring Security过滤链和 `X-PaiCLI-Runner-Token`，设备凭证仅交换会话，微信凭证仅访问微信通道。默认关闭时中心Controller与设备过滤链均不装配。

机器契约分别为[操作者接口](../../contracts/paicli/operator-v1.openapi.json)、[设备与微信通道接口](../../contracts/paicli/runner-v1.openapi.yaml)和[中心事件/原始账本](../../contracts/paicli/events-v1.schema.json)。`python contracts/paicli/verify-contracts.py`从仓库根目录只读核对实际Controller路由、权限、HTTP字段、注册事件、OpenAPI结构及本地引用；明确更新协议时才使用`--write`。需要现有Python的`jsonschema`、`openapi-spec-validator`和`PyYAML`，不由该脚本安装依赖。结构检查不能代替服务端成员、owner、许可/CAS或真实业务验证。

正式启用必须显式配置 `paicli.expected-database`，启动仅检查MySQL目录与独立 `pc_flyway_schema_history` 最新版本7，不自动迁移。迁移工具 `com.ruoyi.paicli.migration.PcMigrationCli` 支持 `info/validate/migrate <已批准库名>`，使用 `PAICLI_DB_URL/USER/PASSWORD`，禁止clean和自动baseline。既有若依库首次登记须另行明确执行 `baseline-existing <库名>` 并提供 `PAICLI_BASELINE_CONFIRM=BASELINE0:<库名>`；这不会创建另一数据库。迁移按V1业务表、V2菜单、V3导入批次及读取权限、V4业务字段/索引/约束、V5幂等权限核对、V6原网页终端、V7个人所有权与历史冻结顺序执行，回撤需先核对已有数据和批准范围。真实执行与备份摘要统一见实施记录。

控制凭证响应在MySQL中以AES-GCM保存，复用平台固定用途 `platform.paicli.control-key` 的32字节Base64密钥。供应商、搜索和Embedding只接受本人 `ai.paicli.user.<ownerUserId>.*` 的AI密钥引用，微信账号使用 `platform.paicli.wechat.<bindingId>`。短期使用值仅在有效租约或微信通道下发，响应禁止缓存；`/provider-context` 续期保持任务原模型、供应商定义及密钥版本，版本变化要求停止并重新核对。控制密钥目前按固定用途读取，轮换该值前须保留原值并处理尚在保留期内的加密幂等响应。

MCP配置使用 `envSecretRefs`（STDIO环境名到固定用途引用）或 `headerSecretRefs`（HTTP请求头名到固定用途引用）；引用也属于需重新批准的配置哈希。非空环境值及凭证请求头不能直接保存，普通HTTP头仅接受 `Accept`、`Content-Type`、`MCP-Protocol-Version`；HTTP端点不接受query或fragment，启动参数拒绝认证开关及其明文值，包含托管请求头的HTTP连接仅允许HTTPS。执行器身份、路径、动态加载和代理环境不能被引用覆盖。`POST /paicli/runner/v1/mcp-context` 必须携带当前有效租约、已消费的工具许可及冻结配置ID/版本/哈希，才能获得不超过60秒且不超过租约截止的内存使用值。该接口不把明文响应保存到幂等表、配置、审计或事件；每次RPC重新核对引用、用途密钥版本和信任。Authorization的托管用途为bearer时由中心构造Bearer头，其他头按用途值派发。HTTP连接及STDIO子进程在短期授权过期时关闭；后台能力变更通知没有当前消费许可时拒绝，未知RPC结果停止该租约。密钥轮换、配置修改或信任撤销后旧任务不能静默切换到新定义。中心已通过H2/MyBatis事务及Mock托管服务边界检查；Runner本机HTTP fixture由原生模块定向执行并另报结果。尚未执行真实托管写入或外部MCP调用。

MCP发现帧及规范化tools/resources/prompts各限定16MB；普通工具、资源或提示结果限定64MB，内容块最多128、图片最多10张。stderr和通知日志累计限定1MB。控制汇总约113MB，事件与返回结果各自的JSON文本包装最多约2倍，总量约452MB加固定身份信息，处于512MB预留内。凭据脱敏不放大正文长度，字段名脱敏后冲突会拒绝。超过边界、结果未知或网络上报失败时停止租约、保留可确认的实际结果及已消费记录，不重执行。

512MB是本机证据预留，中心单个JSON请求仍限64MB。完整工具文字产物使用4MiB分块、最大128MiB，并核对当前已消费许可、任务、会话、lease/fence及完整SHA-256；中心按同lease累计512MiB约束。上传失败先私有保全完整证据并暂停，不重执行。用户侧 `/paicli/v1/artifacts/{id}/text` 在原 `artifact:read`、owner及会话权限内按UTF-8字节分页，单页4字节至1MiB，CLI `/output` 核对全部字节和最终摘要。分块及分页的技术检查、真实multipart/联机执行分别见实施记录，不能据本机容量推断所有最大MCP元数据均已登记成功；大型能力清单的正式元数据链路仍在补齐。

脱敏改变MCP标准字段或结构标签时，结果按不完整处理，不能解析为成功空结果。异常数据回到当前已消费许可的执行线程后，先私有保存完整脱敏证据，再暂停；自定义键冲突以键值数组保留各项，避免覆盖已收到内容。错误消息只显示固定说明，不输出错误data或凭据。

| 内容 | 实际边界 |
|---|---|
| 单个工具文本字段 | UTF-8最多5MB，保留原write/edit能力 |
| 中心规范JSON | 最多64MB；edit两个5MB字段包含控制字符时转义可接近60MB，部署代理与Runner须使用相同请求边界 |
| 普通用户输入 | UTF-8最多64KB |
| 单任务输出和事件 | 累计最多20MB；大结果保留完整私密产物及SHA-256引用，不静默截断 |
| 图片附件 | 每张最多5MB、每轮最多10张、最多四千万像素；上下文只派发引用，有效租约按需读取单张 |
| 私密普通产物 | 最多50MB；分页文本读取最多2000行、每段最多2MB |
| 离线历史bundle | 独立最多16MB、10000条；未知schema、未映射身份或明文凭证阻止导入 |

私密产物默认保存到若依profile目录同级的 `<profile>.paicli-private/artifacts`，避免落入现有匿名 `/profile/**` 静态根。正式下载经中心成员、归属、独立按钮权限及内容哈希检查；Runner引用另绑定任务用户、工作区和会话。测试只写临时目录，尚未创建真实产物目录。当前共享 `application.yml` 的multipart单文件限制为10MB、单请求20MB（aipicture通道为20MB/24MB）；这轮未修改真实配置，16MB导入及50MB普通产物是服务层边界，尚未在当前上传配置下联机验证。启用前需审核共享上传行为的影响并提供批准配置。64MB是受限协议最大值，历史和原始账本按固定截止序列分页，每页最多200条、目标16MB、单条最多20MB且不截断；超大记录须完整产物引用。部署需据此设置代理请求上限、进程内存及流量控制；图片按需派发避免多图Base64堆进整段账本。

变更请求使用 `Idempotency-Key`，人工修改另用 `If-Match`；工具许可绑定参数、资源、根目录、策略、计划、任务版本和fencing token。只读工具也必须消费单次permit并提交回执。心跳及工具回执不提升任务控制版本，取消和控制状态变化会使旧执行请求失效。租约失效进入待核对，不能自动重放未知副作用；已在原attempt消费的迟到回执可保存为 `LATE_ARCHIVED` 并返回原任务、调用、租约及fencing token的确认，归档不推进当前任务。未消费或未知的本机执行证据继续保留待人工对账，再次提交使用新的任务ID。

模型提供的工具编号仅供消息关联，每次实际调用使用新的中心UUID并重新取得单次许可。提案和收据正式payload保留`modelToolCallIdHash`，它不参与授权选择；同键重传保留原摘要。已开始的中心调用ID不再执行，不能靠模型跨轮重复编号重放consume幂等成功响应。

记忆在中心执行原确定性去重和冲突规则；相似但数字版本不同的内容不自动覆盖，替换目标及保留两条的选择须来自用户或绑定批准参数。自动候选最多三条且必须来自当前用户原文，不自行验证为正式事实。清空与压缩保留原始账本，后续上下文从正式起始序列和已核验派生摘要重建。原提示词正文及审计详情分别有独立服务器权限，普通元数据接口不能读取这些原文。

沙箱管理记录必须匹配已上报Linux/bubblewrap或macOS/seatbelt并有五分钟内的核验证据，Windows或未知平台不允许标记为已隔离；Runner仍须通过本机REQUIRED实际探测。查询结果按原Runner实际路径、片段、相似度及关系归一化，未上报的行号和文件哈希返回空值。正式评测保持暂停，历史导入与Harness结果不能自动取得正式评分或可发布状态。

`/snapshot clean current` 先排队生成完整清理预览，再用新任务请求批准。预览冻结当前缓存的HEAD、refs、全部commit（含未登记派生commit）、正式快照目标及文件SHA-256清单；批准后任何变化都会拒绝清理。每个root最多十万个缓存节点，完整清单和正式预览分别最多16MiB，超限保留全部原缓存，不截断后继续。`/snapshot clean all` 仅聚合当前用户有权限的各工作区任务，每项仍只清理自己的root；CLI逐任务查看真实终态，并单列离线或无权限工作区。

成功清理将完整原缓存原子归档到批准Runner状态目录的 `snapshot-archives/<archiveId>/repository`，同级 `manifest.json` 保存原文件清单；回执包含真实备份清单SHA、原缓存摘要、文件数和字节数。工作区文件不会随清理删除，归档确认后才建立空active仓库。归档不等于已执行保留期垃圾回收，也不提供自动重新执行任务。恢复归档须先停止该root的Runner、保护后来新增active缓存，按中心清理任务中的Runner/root归属核对备份ID和清单SHA，再逐文件核对归档清单中的路径、大小及SHA，最后将整个已确认仓库恢复到原快照缓存位置；发现后来修改或摘要不符时停止，保留两份。这里是管理员恢复说明，本轮没有在真实用户状态目录执行恢复演练。
