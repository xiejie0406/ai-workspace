# 演示文稿算法 Worker

当前业务前端按用户2026-10-03决定保留原React工程，维护在`frontend/aippt-web/`；原页面、全屏编辑布局、React Router、TanStack Query、Zustand、Tailwind及Recharts继续使用。Worker任务/租约及算法接口不因前端恢复原React而改变，不向浏览器暴露内部身份或数据库。具体文件和迁移方法见[完整迁移设计](../../文档/项目/AI演示文稿项目/完整迁移设计.md)第2节；旧前端重写结果是历史证据，不能算React联机通过。


本工程将参考项目的 `domain / ingest / llm / workflows / images / render / regression` 算法迁入独立进程。Java 中心拥有项目、来源、提纲、正式页面、提案、任务、权限、资产和审计。Worker 不包含原 FastAPI 登录/项目入口、数据库模型、SQLAlchemy、ARQ 或 Redis 队列，也不持有 COS 正式存储凭据。

## 本地准备和验证

WebP 正式资产保留原字节与 hash；PPTX 嵌图入口完整解码并转 PNG，以支持原生图片形状和原 cover 裁剪。回读按真实图片块 ID 核对原生图片，漏图不能静默以占位图通过。

PPTX回读同时逐页核对演讲备注文本，源缺省/None对应空备注，删除或改字会阻断下载；备注段落、空格和缩进不因主题切换改写。

离线准备器只按字段名递归脱敏，不改正文字符串。排除 password/hashed_password/password_hash、token/access_token/refresh_token、jwt_secret、llm_api_key/image_api_key/unsplash_access_key/image_workspace_id、cos_secret_id/cos_secret_key、worker_key，以及通用 api_key/access_key/secret/secret_id/secret_key（忽略字段名大小写、下划线、连字符）；若依 owner 映射只接受 1 至 Long.MAX_VALUE 的 JSON 整数，不接受布尔值、浮点数或字符串。

未完成的 pending/generating/failed 灵活页仅在 `blocks=[]` 且 `layout_tree=null` 时允许作为原存根保留；生成中页转 pending、提纲转 draft 并保存 legacy_status，错误与旧页保留，旧job不恢复。ready或非空灵活页缺树、重复块/槽位/节点ID、重复/丢失leaf引用和原结构验证error阻断准备；capacity/overflow等警告保存在 `audit.structure_issues`，不删页或改文。导入后的质量报告仍需重新执行。

字体资源只在批准后自行提供，不自动下载。`AIPPT_FONT_MANIFEST_PATH`（默认字体目录的 `manifest.json`）格式为 `{"version":"aippt-fonts-1","files":[{"role":"cjk","path":"NotoSansSC-Regular.otf","size":实际正整数,"sha256":"实际64位小写哈希"},{"role":"latin","path":"NotoSans-Regular.ttf","size":实际正整数,"sha256":"实际64位小写哈希"}]}`，无额外字段。`files` 按 role 升序排序后，整个 JSON 按键排序、紧凑分隔、UTF-8 编码的 SHA256 为资源身份。manifest 不表示字体许可已经批准。

字体准备仍使用原 `scripts/fetch_fonts.py` 入口，从源码或解包后的 sdist 根目录运行。默认仅打印 JSON 计划，不联网、不创建目标目录/锁/staging、不写资源；读取与 Worker 相同的 Settings/`.env` 中 `AIPPT_FONTS_DIR` 和 `AIPPT_FONT_MANIFEST_PATH`，显式 CLI 目标优先且不会改写配置。wheel 只提供 Worker 算法与运行入口，不包含该脚本或本 README；安装 wheel 后如需准备字体，应使用匹配版本的源码/sdist 工具，将准备好的完整资源目录配置给 Worker。

```powershell
# 当前授权可执行：只审查计划。
.venv/Scripts/python.exe scripts/fetch_fonts.py
.venv/Scripts/python.exe scripts/fetch_fonts.py --fonts-dir var/fonts-candidate
# 下条仅说明已另获设计3.6下载授权后的用法，本轮未执行真实下载。
.venv/Scripts/python.exe scripts/fetch_fonts.py --fonts-dir var/fonts-candidate --apply
```

候选固定为官方 `notofonts/noto-fonts@ffebf8c1ee449e544955a7e813c54f9b73848eac` 的 `hinted/ttf/NotoSans/NotoSans-Regular.ttf`（569208 bytes）及同提交 `LICENSE`（4377 bytes），与 `notofonts/noto-cjk@f8d157532fbfaeda587e826d4cd5b21a49186f7c` 的 `Sans/SubsetOTF/SC/NotoSansSC-Regular.otf`（8331336 bytes）及同提交 `Sans/LICENSE`（4301 bytes）。仅这4个固定 HTTPS URL，拒绝重定向和其他地址，HTTP不重试，每资源网络/总下载期限默认30秒（可选1至120秒）；字体各64MiB、许可证各256KiB以内且必须与固定来源的大小/Git blob SHA1一致。Git blob SHA1包含 `blob <size>\0` 头，是来源校验值；manifest 的 SHA256 只由实际字体字节生成。

`--apply` 在同级独占锁内把两字体、两份 OFL 1.1 许可证、独立 `font-sources.json` 来源/commit/字体内部版本/实际SHA256元数据和严格 manifest 准备至随机 staging，完成来源字节与 Worker 字体解析校验后一次目录 rename 发布。已有同基准合法目录仅校验复用；其他非空、部分、损坏或来源不同的目录拒绝覆盖。仅空目录/零字节 `.gitkeep` 空壳可备份后发布，发布失败恢复空壳；锁冲突或目标途中变化停止，不自动删除遗留锁。manifest 必须位于字体目录根内，外置配置可打印计划但 apply 拒绝跨路径多步发布；可通过显式目标选择新的完整目录。原参考未固定字体版本，本候选是新的可追溯测量基准，不能宣称与历史字体像素一致。保留许可证与来源记录不扩大下载、系统安装、部署或发布授权。

涉及字体的任务将中心 `font_manifest_hash` 与本机 manifest、两字体实际字节校验，任务内部只使用已校验字节快照，并发上下文互不干扰；输出实际 `font_manifest_hash` 与 `font_mode=verified`。中心指定 `unavailable` 时强制估算并输出 `font_mode=estimate`，即使本机有字体也不能升为 verified。verified 表示资源身份已核对；缺字、emoji 或非度量字体仍可能使用估算，不表示全部字形精确。

Python 要求 3.12。`requirements.lock` 为原 `uv.lock` 的必要算法/测试依赖闭包，精确固定64个版本；`upstream.uv.lock` 仅作来源对照，不能当本工程自动解析后的锁文件。安装只使用本工程虚拟环境：

```powershell
uv venv .venv --python 3.12
uv pip install --python .venv/Scripts/python.exe -r requirements.lock
.venv/Scripts/python.exe -m pytest -q tests
.venv/Scripts/python.exe -m app health
.venv/Scripts/python.exe -m app regression --output var/regression
```

安全修复偏差：只将传递依赖 `urllib3` 从原锁 `2.7.0` 固定到 `2.8.0`，兼容现有 requests 的 `>=1.26,<3` 约束；原 `upstream.uv.lock` 保持原文。官方公告涵盖 [分块流内存耗用](https://github.com/urllib3/urllib3/security/advisories/GHSA-vxq7-64xx-v4gw)、[Deflate 流死循环](https://github.com/urllib3/urllib3/security/advisories/GHSA-gh4c-6fx4-qh6g) 与 [HTTPS 代理 TLS 策略](https://github.com/urllib3/urllib3/security/advisories/GHSA-8988-9cw3-xx77)。扫描使用临时环境 `pip-audit==2.10.1` 和 PyPI 数据源，命令为 `uv tool run --from pip-audit==2.10.1 pip-audit -r requirements.lock --no-deps --disable-pip -s pypi -f json -o var/dependency-audit.json --strict`；扫描结果只表示当次已知漏洞数据覆盖范围，不保证没有未知漏洞。

`health` 只检查本地目录、模型依赖和字体。`regression` 按两份固定语料×四主题实际生成八份PPTX、回读可编辑性、比较内容和溢出，输出 `regression-report.json`。缺字体时报告估算路径和警告，不宣称字体精确就绪。语料11页覆盖全部固定布局；灵活布局、九个AI工具、块样式和四图表另有纯算法测试。

原 `scripts/compare_deck_metrics.py` 只读统计文稿填充率、块数/类型、皮肤、最窄文字栏、配图和氛围图元，默认使用共享目录的 `sample-deck.json`，也接受一个符合原 `Deck` 模型的 JSON 文件；来源/项目快照需先明确转换为 Deck，不能把任务 envelope 当成输入。它不生成 PPTX、不下载图片/字体，不连接中心或 Provider，窄栏字符数是原等宽粗估，不代表真实字体排版。保留原 `== sample-deck/文件名 ==` 标题及 JSON 指标输出，不新增第二个 CLI：

```powershell
# 从源码或解包 sdist 根目录，用已准备的本机Python依赖运行；不使用uv run重新解析/安装。
.venv/Scripts/python.exe -B scripts/compare_deck_metrics.py
.venv/Scripts/python.exe -B scripts/compare_deck_metrics.py path/to/deck.json
```

源码默认读取本工作区 `contracts/aippt/catalog`；脚本与 Worker 使用同一 Settings/`.env` 的 `AIPPT_CATALOG_DIR`。sdist/wheel不打包第二份catalog，解包 sdist 后请使用外部已准备的Python环境（以其实际解释器路径替换上例）并显式把 `AIPPT_CATALOG_DIR` 指向同版本catalog，在该目录的父级提供匹配 `catalog-manifest.json`。入口复用 `initialize_catalog` 校验所有清单字节并预热现有算法缓存，目录缺失/损坏或版本不符即失败，不回退到原reference。wheel不包含该统计脚本；使用匹配的源码/sdist工具即可。该统计与本地技术执行不构成Office或真实业务验收通过。

另外两项原辅助入口也从源码或解包 sdist 根目录使用已准备的Python环境执行，不使用 `uv run` 触发依赖解析：

```powershell
.venv/Scripts/python.exe -B scripts/run_regression.py
.venv/Scripts/python.exe -B scripts/dump_ambient_fixture.py
.venv/Scripts/python.exe -B scripts/dump_ambient_fixture.py --output var/ambient-candidate/theme-cases.json
```

`run_regression.py` 先用同一 `initialize_catalog` 校验和预热，保留原八组合固定回归、中文报告及通过/未通过退出码，只在内存生成和回读PPTX，不保存PPTX文件；需要保存八份文件与JSON报告时使用上文现有 `python -m app regression --output`。这些都是离线算法技术检查，不连接中心、Provider或下载字体，缺获许可字体时仍为估算。

`dump_ambient_fixture.py` 保留原四主题×三种布局作用域×空/两栏占位，共24个氛围几何用例。入口先用同一 `initialize_catalog` 校验和预热；默认输出本工具所在Worker根的 `var/ambient-fixtures/theme-cases.json`，显式 `--output` 可选择其他本地JSON。它会创建输出父目录并写入文件，拒绝输出到当前共享catalog（含解析后的符号链接）或覆盖其父目录manifest；不能把辅助生成操作当成更新正式catalog版本。先在本地核对生成结果，正式目录或版本更新另行核定。wheel不包含这两脚本，使用匹配源码/sdist；sdist仍须显式提供前段所述的外部catalog及manifest。

## 独立 Worker 容器

`Dockerfile` 只装配本工程的64项锁定依赖、90份算法源码和两份固定语料，最终以 `10001:10001` 运行，不提供业务 API 或数据库/Redis/COS 编排。基础镜像及完整 SHA256 digest 需另核定，必须是已批准的 Linux Python 3.12 镜像；构建无默认镜像，先将批准引用拆为镜像名和64位小写 digest。以下 PowerShell 命令仅为用法，变量由核定的运行通道提供，本轮未执行：

```powershell
# 从源码或解包后的 sdist 根目录执行；不在仓库填写真实配置。
docker build --build-arg "PYTHON_BASE_IMAGE=$approvedPythonImage" --build-arg "PYTHON_BASE_DIGEST=$approvedPythonDigest" -t "$approvedWorkerImage" .
# 默认只执行本地 health；禁止联网，文件系统只读，临时文件仅在 /tmp。
docker run --rm --read-only --network none --tmpfs /tmp:rw,nosuid,noexec,size=256m,mode=1777 --mount "type=bind,source=$approvedCatalogBundle,target=/opt/aippt-catalog,readonly" "$approvedWorkerImage"
# 真实任务须已核定中心/网络/凭据；仅按环境变量名注入，显式追加 run。
docker run --rm --read-only --network "$approvedNetwork" --tmpfs /tmp:rw,nosuid,noexec,size=256m,mode=1777 --mount "type=bind,source=$approvedCatalogBundle,target=/opt/aippt-catalog,readonly" --env AIPPT_CENTER_URL --env AIPPT_WORKER_KEY "$approvedWorkerImage" run
```

`$approvedCatalogBundle` 必须是同版本完整资源包的父目录，包含 `catalog/` 和匹配的 `catalog-manifest.json`，且允许 UID 10001 读取；容器固定读取 `/opt/aippt-catalog/catalog`。字体只使用已获许可的完整资源；可在镜像名之前加 `--mount "type=bind,source=$approvedFontsBundle,target=/opt/aippt-fonts,readonly"`，目录须包含两字体和严格 `manifest.json`。没有获批字体时保留 `unavailable/estimate`，中心任务的字体身份须与实际资源一致。Provider 凭据也只在获批后通过 `--env` 名称注入，不放入构建参数、示例值或镜像；外部图源仍受两端开关约束。

当前本机缺少 `docker`/`podman` 命令，容器尚未构建或运行；基础镜像、Linux 二进制依赖可用性、挂载权限和运行通道仍待验证。静态文件检查不代表镜像可运行或业务通过。[Dockerfile 官方语义](https://docs.docker.com/reference/dockerfile/)与[构建上下文排除规则](https://docs.docker.com/build/concepts/context/)为该入口依据；源码、实际资源和正式状态仍沿用现有边界。

## 中心任务协议

外部生图与图库须同时满足本机 `AIPPT_IMAGE_ENABLED=true` 和中心任务 `payload.image_enabled` 严格为 JSON `true`。本机默认关闭，配置密钥不会自动开启；关闭或中心许可缺失时保留占位图，不请求 Provider/Unsplash。已上传的中心私有图片读取、质量检查和原生 PPTX 导出继续可用。

成功 `output.execution` 和失败 `/fail.execution` 回传任务摘要：实际单调钟耗时、中心文本逻辑请求数/实际返回模型、原提示词模块代码版本及非敏感参数版本 hash、可观察的工具轮数。正式运行token仅来自中心规范响应的真实 `usage.input_tokens/output_tokens/total_tokens`；保留原离线注入时的LangChain回调。仅完整覆盖全部调用的字段才汇总；缺项、异常值或总数超中心上限时省略字段，以 `usage_status` 和固定原因说明，不填假0或计算费用。中心请求结果未知时，该次请求仍保留在摘要中，用量未知。参数版本关联中心返回的实际 `routeVersion/wireApi/model`，不把Worker遗留的DeepSeek默认值当作当前中心模型。ContextVar 隔离并发任务，摘要不保存正文/提示词/密钥，也不冒充图片供应商计费记录。

从既有私有配置注入经批准的中心运行通道、Worker运行标识及中心/Worker一致的至少32字符机器凭据 `AIPPT_WORKER_KEY`；首次准备时可参考 `.env.example`，本机配对配置应保存在批准的私有运行位置。Worker不配置长期文本供应商key、自由出站地址或中心精确模型。以下命令会连接真实中心，不属于纯算法测试：

```powershell
.venv/Scripts/python.exe -m app run
.venv/Scripts/python.exe -m app run --once
```

所有机器请求带 `X-Aippt-Worker-Key`。领取 `/internal/aippt/tasks/claim` 使用 `workerId/catalogVersion/waitMillis/types`；Worker标识按中心UTF-16长度限制为1至128，等待参数为0至10000毫秒整数。常驻槽位默认请求最多10秒的HTTP长轮询，`run --once` 显式请求0，不因无任务多等10秒；领取、心跳、资产HTTP超时仍为30秒。中心返回null后沿用 `AIPPT_CLAIM_INTERVAL_SECONDS` 的默认2秒退避，并发槽位配置沿用默认3，本机初始配置使用1。`AIPPT_CLAIM_TYPES` 是JSON数组，默认 `["parse_source","quality","export"]`，只接受下表七类；中心文本通道获准启用后显式加入四类文本任务，空数组不领取。常驻和once入口均传递该范围，不因为存在旧Provider key自动扩大范围。Worker仍只通过HTTP领取，不增加RedisSDK或Redis凭据。中心Redis唤醒属于非权威提示，丢失提示时仍由中心有界重查正式数据库维持正确性。续约、进度、终态和临时资产请求均携带 `attempt/leaseToken/fencingToken`。目录逐文件校验 `contracts/aippt/catalog-manifest.json`，不能凭目录名相同接受旧算法。所有输入是中心快照，业务模型字段为原 `snake_case`。`health` 仅本地校验目录、报告中心机器配置是否存在、`text_transport=center` 和领取范围，不连接中心，也不声明Provider已联机。

Worker启动前后校验同一目录字节并预热主题、布局和预设缓存；每任务在算法开始前重新校验所有目录文件。运行期间部署目录须只读且不可变，更新使用新进程/版本，不原地替换JSON。该边界避免首任务懒加载不同字节；本地任意进程在检查间隙篡改文件不属于受信部署条件。

| 类型 | 输入与输出 |
|---|---|
| `parse_source` | source kind/text/filename/asset_id；输出稳定UUID小节、ref、定位、警告、字数与解析器版本 |
| `generate_outline` | project/sources；输出完整提纲页、来源裁剪提示 |
| `generate_slide` | project/outline_page/slide/sources/neighbors；输出slide候选、issues、geometry和图片资产manifest |
| `ai_edit` | project/slide/instruction/history；输出operations/discarded/warnings/candidate_slide，正式内容不变 |
| `relayout` | project/slide；输出最多三份结构合法、签名去重的灵活树候选 |
| `quality` | snapshot.project/slides及可选slide_sources/slide_roles；输出issues/can_export/fonts_available |
| `export` | 同一不可变snapshot；错误门禁阻断，原生PPTX回读通过后才上传，返回verify/quality/hash/filename |

生成提纲保留12,000/单节2,000字、单页保留6,000/单节1,500字预算。四类文本任务在原生成器中注入中心结构化客户端，保留LangGraph及原提示词/业务结构检查；`ai_edit`沿用原九工具及最多4轮循环，通过JSON信封表示工具调用，严格校验工具名称、ID、数量和原参数schema，工具仍只修改原提案副本。历史和工具结果以JSON消息数据编码，不能改变系统约束。单轮最多64个工具调用，ID在该轮唯一，长度1至128且只含字母、数字、下划线或连字符。`AIPPT_LLM_MAX_CALLS=8` 在逻辑请求前扣除任务预算，允许调小；中心独立执行最多8次预算并决定真实输出上限。`AIPPT_TOOL_ROUNDS_MAX=4`、`AIPPT_REPAIR_ROUNDS_MAX=1`、`AIPPT_EDIT_HISTORY_MAX=5` 保留原上限且允许调小。

`POST /internal/aippt/tasks/{id}/text` 携带固定UUID `callId`、`attempt/leaseToken/fencingToken/system/user/purpose`，不带供应商地址、模型或长期key。单次文本HTTP使用独立 `AIPPT_TEXT_REQUEST_TIMEOUT_SECONDS`（固定90秒，只接受90），不阻塞其他心跳连接，中心出站超时最高80秒，保留中心提交终态的余量。请求和响应分别限制2MiB，system最多65,536字符、purpose最多100字符。一次逻辑调用只生成一次callId，无应用层/SDK传输重试；结果未知、超时、中心拒绝均停止当前文本流程，不换ID重发，不把收费回合作为任务自动重做。软取消阻止后续文本回合，失租停止候选写回，已经发出的请求不能承诺撤回费用。任务默认900秒并取中心deadline更小值。本地任务没有发生文本请求时保留原可重试恢复规则。原 `create_chat_model/StructuredChatClient` 与直接模型注入仅保留给离线fixture/原工具，不在正式 `run` 通道调用，其遗留 `AIPPT_LLM_API_KEY/BASE_URL/MODEL/MAX_RETRIES` 不影响中心文本出口。六替换工具与flex三结构工具不访问外部任意工具，锁定块副本在工具入口和候选出口双重检查。

固定页解锁使用叶子 `rect_override` 保存原槽位的全页归一化坐标；首帧质量计算和原生PPTX沿用原框。矩形必须为有限值且完全位于画布内。成功人工移动、AI结构编辑或自动重排清除覆盖，后续按树权重计算；文字替换和拒绝的结构操作保留原框。

图片由生成→图库→占位降级，坏字节也继续下一来源。外部图源必须HTTPS、公网IP、批准域名（配置时）、无凭据URL/重定向；流式响应限额，PNG/JPEG/WebP经完整解码校验。Unsplash带凭据下载回调限制固定API域名。实际TCP连接绑定同次校验的公网IP，TLS仍验证原域名，防止DNS重绑定。部署层出站防火墙可作为额外边界，Worker不绕过网络策略。材料ZIP成员/解压体积/压缩比及PDF页数限额；不可信文档在独立子进程解析，超时终止。预算超限明确失败，不偷偷删字段或页。

Worker不下载任意业务图片URL。Java私有资产在租约绑定端点读取，任务内ContextVar缓存供质量/PPTX算法使用；生成图片/导出上传绑定role/mime/size/hash与租约。中心校验任务快照、归属和临时资产引用后提交正式结果。心跳失效丢弃迟到候选；软取消由中心停止领取新页，已领取页可完成。运行时日志只记录任务号、类型及异常类型，不打印正文、凭据或Provider完整响应。

## 离线旧数据准备

核账同时输出 `audit.field_preservation`：独立比较原候选全部字段的哈希、数组长度和页序，覆盖正文、备注、锁定、样式、修订与资产 hash/size/mime；已定义的引用、资产地址、owner、状态及旧队列/签名转换列在 `expected_transforms`。非预期字段变化使项目进入 rejected。`manual_review_required=true` 明确要求人工核对原快照和清单；自动字段一致、计数或清单 `complete` 只证明准备检查，不表示真实源库导入、用户接受或逐字段业务验收已通过。

`prepare-import` 只处理用户授权提供的只读快照与对象清单，不连接任何数据库、不写正式中心。示例：

```powershell
.venv/Scripts/python.exe -m app prepare-import --snapshot var/source-snapshot.json --owners var/owner-map.json --objects var/source-objects.json --output var/import-batch
```

快照格式为 `{source_system,source_order_verified,projects:[{id,user_id,...,sources:[...],outline:{pages:[...]},slides:[...]}]}`；必须证明数组材料顺序与原 `S1:1` 引用顺序一致。owner map 为 `{旧UUID:若依user_id}`；对象清单为 `{objects:[{key,path,sha256,size,mime}]}`，对象路径必须在清单目录内。

输出幂等 `import-manifest.json`：逐项目正式候选、资产、原ref→稳定section UUID映射、页序、块数、锁定列表与核账。账号密码、Token、Provider密钥不导入；缺owner、未证材料顺序、丢失/坏对象、非法页面进入rejected，不能以总数相同宣布完整。原未完成任务不恢复活跃；旧确认签名保留为legacy，但必须中心重算确认。实际数据库导入、停写、对象staging和恢复需按批准范围在中心执行。

## 制品和边界

Python wheel可由 `uv build` 构建；部署需同时携带同版本catalog及manifest、经许可字体、中心机密配置，不在wheel中复制第二套catalog。`.venv/.env/var` 不进入Git。当前纯fixture测试不证明真实模型、图库/COS、旧实库导入和页面业务验收；这些条件与结果由所属项目唯一实施/验收记录维护。
