# 若依 AI 图片编辑计算 Runtime

这是独立 Python 3.13 计算进程。正式素材归属、若依账号、会话文档/历史、选区修订、计划确认、任务/租约、批量恢复、导出和评测记录全部由 Java/MySQL 承载。这里没有 SQLAlchemy、PostgreSQL、用户 Cookie/JWT、业务 Redis 队列、S3 bucket 创建或第二套正式状态。

## 安装与检查

在本目录使用已安装的 Python 3.13：`uv sync --locked --python 3.13`。基础和 Agent 依赖由 `uv.lock` 锁定；`uv run --locked pytest -q` 执行显式离线测试。测试配置在 `tests/conftest.py`，仅此场景启用 `mock/corner/none`；不会领取业务任务、下载模型权重或调用真实 Provider。

可选视觉依赖位于 `pyproject.toml` 的 `cv` 分组。真实 SAM、rembg、OCR 需要经批准的本地权重及对应依赖；前期未获批准时没有安装或下载的记录是历史事实，当前授权、安装和真实推理结果由[唯一实施记录](../../文档/项目/AI图片编辑项目/开发记录/2026-10-03-完整迁移实施.md#视觉资源授权与执行)维护。锁文件只保留 RapidOCR 使用的 `opencv-python` 分发，避免再安装共享 `cv2` 命名空间的 headless 分发。OCR 1.4.4 wheel 自带模型，准备脚本先核对锁定 wheel SHA256 和模型 RECORD，再复制到私有目录；运行时显式传入模型路径，不自动下载。

私有 HTTP：`.venv\Scripts\python.exe -m aipicture_runtime serve --host 127.0.0.1 --port 7405`，ASGI 入口 `aipicture_runtime.main:app`。正式领取循环：`.venv\Scripts\python.exe -m aipicture_runtime worker`。二者职责不同，HTTP 是 Java 的能力/规划/选区/计算调用入口；领取进程从中心执行服务领任务、心跳、逐图上传、回执，不保存任务账本。

复制 `.env.example` 后通过进程环境或已有托管配置加载变量（本工程不会自动加载文件并启用）。至少设置 `AIPICTURE_SERVICE_SECRET`（32 字节以上，与 Java 相同）、`AIPICTURE_CENTER_URL`（批准的中心地址）。自身 `AIPICTURE_SERVICE_NAME=aipicture-worker`，允许调用 HTTP 的中心 `AIPICTURE_ALLOWED_CALLER_SERVICE=aipicture-java`，两个身份不能混用。默认 `AIPICTURE_ENABLED=false`，只能经 HMAC 读取能力，真实计算接口返回 503；测试模式也禁止领取真实中心任务。

## 原能力完整对应

领取循环还必须配置 `AIPICTURE_DELIVERY_SPOOL_DIRECTORY` 为当前 Worker 专用的私有绝对目录；HTTP 能力/计算入口不要求此目录。该目录含原图产物、租约凭据和冻结输入，须由已有主机账号权限保护，不置于公共下载目录；本轮没有修改运行配置或启动领取进程。目录不能经过符号链接/目录联接，同一目录通过操作系统锁只允许一个领取进程。

计算前先耐久保存领取身份，计算完成后原子保存同一份 bytes/产物ID/结果，上传结束后保存完整回执。SHA256校验与中心地址/Worker身份绑定用于检测意外损坏或错误切换；这不是加密或替代服务认证。重启优先读已完成副本，每次仍由Java核对原run/attempt/lease/token及取消状态；有效租约内重交原产物，已提交而丢响应的同一回执可回放。过期、拒绝或取消的副本隔离，未耐久算完的副本也隔离，禁止重跑模型；租约过期后的正式结论仍由中心裁决。写入完成前崩溃不能保证保住内存中的最后结果。没有新任务队列或Python正式任务账本。

缓存最多4份，隔离副本也占名额；单份上限为 `2*max_output_bytes+max_request_bytes`，每份最多一个同名原子替换临时文件。容量满、损坏或身份不匹配时停止领取并保留原文，待人工核对和释放，不丢掉未知外部结果以继续运行。有效副本每次沿用原交付的1s/2s退避，循环退避最多30s；不会重复计算或付费。只有中心确认回执后删除传输副本；隔离文件不自动删除。目录/文件fsync及原子替换是软件措施，实际断电、文件系统/空间、耗时与资源占用未演练。

Java私有对象改为完整临时写入后重命名，锁保持到所属SQL事务结束；登记回滚且确认无数据库引用时移除本次新文件。领取入口另外触发本项目24h无引用对象清理，每分钟至多一轮、每轮最多检查500个目录项，以游标继续后续项；仅处理 `assets/<owner>/<UUID>.*`、`staging/<run>/<attempt>/<UUID>.*` 及本实现生成的临时文件。文件锁与写入互斥，任何 `aip_asset` 或 `aip_export` 引用都会保留，不删除已登记的staged/历史记录。引用查询或文件操作异常保留原文件并记录简短警告；这不是正式素材自动过期策略。重启、数据库中断、并发和实际清理均未在本轮执行。

唯一注册表来自源 Pydantic 参数的纯 DTO 版本 `aipicture_runtime/params.py`，`registry.py` 统一校验和导出 22 工具。Java 与 React 消费 `contracts/aipicture/v1/tools.schema.json`，禁止维护第二份参数表。

| 原能力 | 本工程实现 | 检查位置 |
|---|---|---|
| 生图、参考图、seed、反向提示词、候选 | `engine.py` / `providers/{base,dashscope,mock}.py` | `tests/test_worker.py` 全计算分支；`test_provider_boundaries.py` 原生 payload 与异步恢复 |
| 换背景、扩图、增强 | `engine.py` 保留单图采用/多图和分层只上墙 | 全分支、分层不覆盖测试 |
| 去背景、11 项调色、PNG/JPG、letterbox/resize | 原 `edits/pixels.py`、`ratios.py` | 迁移的 `test_source_edits.py`、`test_source_eval.py` 和批量六操作 |
| 点选 SAM、embedding LRU=4、笔刷/闭环/选区合并 | `edits/{segment,mask}.py` / `engine.control` | 缓存复用/淘汰、选区 HTTP 与真实修订号测试 |
| 区域消除/替换、图层×遮罩、透明度保护 | `geometry.py` 中心逆仿射与 `apply_masked` | 平移/旋转/缩放/翻转、alpha命中、遮罩外逐像素不变测试 |
| 背景修补、主体/文字拆层、对象提升/防重复 | 原 `edits/{split,ocr}.py` / `engine.split/promote` | 已拆层跳过、OCR 明确测试替身、变换图层提取/重修测试 |
| 裁剪、翻转、透明度、显隐、改文案、顺序、缩放、旋转、移动 | 原九种 `edits/document.py` 纯算法保留 | 九命令对照测试；正式采用仍由 Java 做 |
| 营销四类、文案、比例；投放尺寸 | `engine.py` 营销/投放分支 | wall-only、无裁切分支与真实输出尺寸测试 |
| 批量六操作、格式分支、部分成功 | `engine.batch_op/batch` | 六操作逐项、PNG/JPG、部分失败；Java按单项登记/重试/取消 |
| 画布合成、白底/透明选项、ZIP清单 | 原 `edits/render.py` / `engine.control('export')` | alpha默认、显式透明、ZIP文件与hash清单测试 |
| LangChain/LangGraph候选计划、22工具绑定 | `agent/{llm,graph,plan}.py` | 隐藏服务字段、8步、重复ID/环、中文单/多步回复；正式计划状态由 Java 做 |
| 评测六任务五指标、CLI退出码 | 原 `eval/{tasks,metrics,runner,cases,__main__}.py` | 原评测测试、退出0/1/2、路径边界和HTTP控制任务指标 |
| HMAC、限量输入/输出、领取心跳回执 | `security.py`、`artifacts.py`、`execution.py` | nonce重放、过期/篡改、资产hash/地址/租约绑定、ID重映射、取消/结果未知 |

源 `providers/dashscope.py` 的 `qwen-image-3.0-pro`、`qwen-image-edit-max`、`qwen-plus` 和原生生成/编辑协议完整保留。真实配置需要 `AIPICTURE_DASHSCOPE_API_KEY`、批准结果下载域名 JSON 数组 `AIPICTURE_PROVIDER_OUTPUT_HOSTS`。未批准域名时在提交收费请求前拒绝。图片生成与普通编辑仍选原 DashScope，未启用或未配置时不调用。原增强实际走图像编辑且尺寸被 Provider 限制，并非真正超分模型。

`upscale_image` 现在默认走本地 ONNX 严格超分接入；未提供对应倍率权重时明确拒绝。`AIPICTURE_ENHANCEMENT_PROVIDER=image_edit` 可显式沿用原生成式增强，其结果仍报告 `true_super_resolution=false`，不能通过严格超分评测。软件接入完成不表示权重选型、模型效果或服务已经更新。

本地配置使用 `AIPICTURE_SUPER_RESOLUTION_MODELS`（JSON 对象，键为 `2`/`3`/`4`，值为本地绝对路径）和 `AIPICTURE_SUPER_RESOLUTION_SHA256`（同倍率的完整 SHA256）。必须提供经核实来源、许可及训练用途的自包含 ONNX 超分图；不自动下载、不自动换模型。只接受单输入/单输出 float32 NCHW RGB、batch=1、RGB 范围 [0,1]；空间轴可动态，固定尺寸必须与原图/目标尺寸一致，不预缩放。程序按倍率查权重、校验实际模型字节哈希，以 CPUExecutionProvider 加载，输出必须精确为原图的 2/3/4 倍且数值有限。RGB 完全来自模型结果；仅覆盖用 alpha 随画幅映射，输出保留 PNG 透明度。接入使用[ONNX Runtime 官方 Python API](https://onnxruntime.ai/docs/api/python/api_summary.html)，不是新的图像生成供应商。

模型文件和工作区沿用现有输入、像素与输出字节预算；模型文件不得超过 `max_request_bytes`，工作区估计超限时拒绝。估计不等于已测峰值，ORT 中间张量、最多三个倍率实例及权重常驻开销仍须后续资源验证。加载期两次路径指纹和实际 bytes 指纹一致才发布实例；正式结果记录实际权重 SHA256、倍率、加载及推理来源，`quality_verified=false`。能力查询只检查依赖和文件指纹，`ready` 表示某个倍率资源齐备，具体看 `scales`；能力页的 `true_super_resolution=false` 不会提前宣称已推理。只有实际完成模型推理的正式产物可带 `true_super_resolution=true`，严格评测仍要求精确尺寸、来源证据、独立指标及人工质量。

真实去背景、SAM、OCR 缺权重/依赖明确失败，不会自动四角抠图、圆形选区或空 OCR 列表。离线算法测试并不证明真实模型效果。预修背景保留原 OpenCV/本地模糊处理，再由图像 Provider 重修；正式语义拆层需真实 matting/Provider，识字分支还需 OCR。

SAM embedding 缓存按实际 encoder 输入的 RGB 像素与宽高、固定预处理版本、成功加载模型的固定 namespace 和加载期权重指纹组成键；同像素的不同 PNG 压缩/元数据可以复用，不同 encoder 实例不能串缓存。像素 hash 按条带或行内块读取，每块不超过1MiB，不为 hash 另建整幅RGB字节副本；同键 Future 共享一次冷编码，失败释放后可重试，不同键保持并行，LRU仍保留4项。模型构造前后各做新鲜流式文件指纹核对，不一致则不发布实例；这只记录两次加载期文件观察一致，不能绝对证明期间没有替换再恢复，也不冒称ORT已加载字节的精确SHA。已加载实例的缓存身份随后固定，不从当前路径重新取hash，能力页的模型文件hash仍表示当前磁盘文件。保留路径构造，不使用会令锁定ORT长期持有原始模型bytes的加载方式；没有增加热重载、配置项或固化尚未批准的缓存字节上限/并发预算。受控缓存检查不证明真实SAM质量或峰值内存。

现有文字层沿用 `text/font_size/fill/width/height`，按锁定Konva的 `word` 换行、`lineHeight=1`、水平居中、垂直居中和高度截断合成，保留显式换行及可容纳的行内空白。原算法只居中裁切单行且静默回退字体，这些不足按已批准的画布/导出一致性要求补齐；没有增加字体样式工具或文档字段。`AIPICTURE_FONT_PATH` 可指向本机已有字体，显式缺失、坏文件或不含当前文字字形会明确失败；只有可见非空文字层需要字体，纯图片及隐藏/空文字仍可计算。未配置时依次发现本机微软雅黑、苹方、文泉驿Micro Hei或Noto CJK路径；不复制或分发系统字体。浏览器文字的受控字体族配置应与此文件实际family配对，Windows默认 `Microsoft YaHei`；不同系统字体/渲染引擎仍需实际截图与导出回读，不承诺跨平台逐像素一致。`text_rendering` 能力独立报告字体family、文件hash/大小、加载与基础中英字形探针；坏字体只令此项 `ready=false`，探针不代替全部文字或排版质量验收。探针固定32px并有界缓存布尔结果，实际渲染仍保留原8–400字号与500字约束。

## 跨端协议与边界

`schema_version=1`、`tool_registry_version=2026.10-r2`。外层和文档/参数使用源 `snake_case`。`input_version` 是 Java CAS 的 `row_version`；`input_revision` 才是画布/选区修订号。两者可能因改名/只上墙不同，不能互换。Java 注入 `revision/mask_asset_id`，Worker先抽取服务字段再按工具schema校验，模型不可提供它们。

选区笔刷省略 `radius` 时沿用原API的短边比例 `0.03`，与页面显式参数一致；允许范围仍为 `0.005–0.12`，显式其他合法比例按原值处理。

图层 `width/height` 是画布逻辑框，素材引用的实际尺寸可与它不同。调色、去背景与局部编辑沿用原素材像素尺寸，区域遮罩通过逻辑框的中心仿射映射到原素材网格并与原alpha相交；提升物体时已有背景/主体也保留原网格及遮罩外像素。文档框、变换及整幅合成/点选/导出仍使用逻辑画布尺寸，不以局部像素处理改写布局。

HMAC 原文为 `METHOD\nPATH\nTIMESTAMP\nNONCE\nSHA256(raw_body)`；时间戳秒，PATH 不包含 query，nonce 单次使用，窗口默认 120 秒。四个头为 `X-Aipicture-Service`、`X-Aipicture-Timestamp`、`X-Aipicture-Nonce`、`X-Aipicture-Signature`。GET 资产读取 query 仅含 `run_id/attempt/lease_token`，必须与冻结请求一致；Java再次验证租约及资产属于本次冻结引用，不因服务签名扩大读取权限。

Worker只接受内联 base64 或已配置中心 `/aipicture/internal/v1/execution/assets/{id}`，不接受本地路径/任意网址。产物含实际尺寸、格式、hash、bytes、alpha 和 Provider 标记，逐项 JSON base64 上传中心 `/artifacts`；对象键及正式资产由中心登记，ZIP只存不解压。回执仅引用登记id，无二进制；中心重映射后才采用文档。租约丢失或取消停止未来步骤/上传/采用；运行中第三方请求可能仍产生费用。

ZIP清单保留实际导出时刻的UTC `created_at`，结果 `filename` 使用同一UTC日期和源安全标题规则；PNG返回安全标题名。过滤目录/控制字节/Windows不合法字符并避免设备名，不以构建日期替代导出时间，也不改变导出像素。

已知异步生成 `provider_task_id` 立即发送心跳；`resume_generate` 只查询已有任务。轮询未完成或结果下载失败回执带 `PROVIDER_TASK_PENDING/provider_task_id`，Java可以有限派发查询原任务。同步请求超时/网络中断、付费结果下载失败归为 `external_result_unknown`，包括批量内部的付费步骤，禁止当作普通失败重做调用。单图批量普通错误保留原逐项信息；已失败项的临时产物会回滚，不作为成功项上传。

计算结束后的中心产物上传和最终回执发生HTTP 5xx、HTTP 200中的业务500/503等5xx、超时/网络中断/远端响应断开时，仅在当前活跃进程内持有原计算结果，原发送后最多按1秒/2秒退避重交两次。重交沿用同run/attempt/lease、同artifact ID与bytes、同result/receipt，每次使用新签名nonce并先由心跳确认明确 `status=running`；不重新计算或调用Provider。401/403/409/400/422等明确拒绝立即停止；租约无法确认、取消或重交耗尽停止后续上传，HTTP200空心跳数据也不能授权交付，交付故障不发送 `COMPUTE_FAILED` 覆盖成功外部效果。回执提交成功但响应丢失时，中心同token终态仅允许回放相同终态回执，不重新上传。取消仍沿用原取消回执。该有界重交只恢复本进程尚在内存中的产物，不持久化另一套任务或队列；进程崩溃后仍由原中心租约、结果未知与已知Provider任务查询规则裁决，不能视为跨进程产物恢复完成。

资源预算：单素材20MB、5000万像素；用户上传最短边32px，内部裁出图层可小到1px，私有 `inspect_image.params.minimum_side` 只允许1/32且默认32；普通计算最多64输入引用；导出最多200个互异所选素材，仅合成所选当前素材或整幅画布时可增加最多64个必要画布依赖，总引用最多264且禁止无关引用，仍受输入总128MB限制；200层，原始/实际缩放/旋转中间位图均受单层5000万像素限制，拒绝非有限或超大尺寸，保留原超宽图片在像素预算内的支持；请求70MB；最多200输出、总128MB；HTTP计算并发2。内部辅助任务 `inspect_image/selection/prepare_selection/export/evaluation` 不混入22工具注册表。ZIP所选当前素材使用冻结正式画布合成，其他素材保留原字节；选区控制任务由Java注入 `selection_markers`，点选追加联合所有标点重新SAM分割，连续笔刷在已有遮罩上累加。

## 真实视觉资源启用清单

原项目没有附带模型。准备清单时曾只读核对源 `backend/models`、源 `backend/.venv/Lib/site-packages/rapidocr_onnxruntime/models`、本机 `C:/Users/admin/.u2net`、`C:/Users/admin/.cache/rembg`、`C:/Users/admin/.cache/onnx`，当时均未发现可复用文件；这些是安装前的历史事实。当前资源批准、实际安装及推理结果由[唯一实施记录](../../文档/项目/AI图片编辑项目/开发记录/2026-10-03-完整迁移实施.md#视觉资源授权与执行)维护，不能从此来源清单推定已就绪或质量通过。

| 原能力及CPU模型 | 需放入本地的文件 | 公开来源及预计资源 |
|---|---|---|
| rembg 默认 U2Net 抠图 | `u2net.onnx` | [rembg 发布清单](https://github.com/danielgatis/rembg/releases/expanded_assets/v0.0.0)，175,997,641字节；[2.0.85模型源码](https://github.com/danielgatis/rembg/blob/v2.0.85/rembg/sessions/u2net.py)固定MD5 `60024c5c889badc19c04ad937298a77b` |
| 原量化 SAM ViT-B 正点分割 | `sam_vit_b_01ec64.encoder.quant.onnx`、`sam_vit_b_01ec64.decoder.quant.onnx` | 同一发布源，分别108,836,115与8,755,366字节；[2.0.85校验表](https://github.com/danielgatis/rembg/blob/v2.0.85/rembg/sessions/sam.py)固定MD5分别为 `26fc0e01d2fa34ed2d3f91259118482d`、`45391530307d1aee79b2a1507769e6c7` |
| RapidOCR 1.4.4 中文/英文 | `ch_PP-OCRv4_det_infer.onnx`、`ch_PP-OCRv4_rec_infer.onnx`、`ch_ppocr_mobile_v2.0_cls_infer.onnx` | 原 `rapidocr-onnxruntime` wheel 携带模型，锁定下载包14,915,192字节（约14.2MiB），模型名按[官方1.4.4配置](https://github.com/RapidAI/RapidOCR/blob/v1.4.4/python/rapidocr_onnxruntime/config.yaml)；安装后模型位于本工程 `.venv/Lib/site-packages/rapidocr_onnxruntime/models/` |

三项视觉权重/携带模型包合计308,504,314字节（约309MB / 294MiB），此外有ONNX/OpenCV/Numpy/Scipy等依赖；建议先预留1–2GB磁盘空间和至少4GB可用进程内存，此为部署预算估计，实际峰值与耗时需要模型运行后测量。CPU模式已经实现：SAM显式使用 `CPUExecutionProvider`，OCR源配置 `use_cuda=false/use_dml=false`；无需下载CUDA或PyTorch大模型。既有 SAM、OCR 和抠图权重不能用于超分；新增超分接入的真实权重与质量仍未验证。

rembg库采用[MIT许可](https://github.com/danielgatis/rembg/blob/main/LICENSE.txt)，U2Net采用[Apache-2.0](https://github.com/xuebinqin/U-2-Net/blob/master/LICENSE)，SAM采用[Apache-2.0](https://github.com/facebookresearch/segment-anything/blob/main/LICENSE)，RapidOCR采用[Apache-2.0](https://github.com/RapidAI/RapidOCR/blob/v1.4.4/LICENSE)。分发需保留对应许可及来源，ONNX转换模型沿用上述来源归属；原图片编辑项目本身未附LICENSE，这些依赖许可不能替代原项目的权属确认。

仓库入口 `scripts/aipicture/准备图片视觉资源.ps1` 默认只读，调用本工程 `scripts/prepare_cv_resources.py`：读取锁文件、已安装包元数据、六个模型文件和相关配置变量的存在性，不建目录、不导入模型、不打印密钥。模型原始下载地址固定为 `https://github.com/danielgatis/rembg/releases/download/v0.0.0/<上述文件名>`；该发布的SHA256 digest为空，因此以2.0.85源码的官方MD5及精确大小固定文件，同时记录本地SHA256，不能把本地SHA256冒称发布方校验。

取得资源批准且先停止本项目Python serve/worker后，显式 `-Apply` 才下载上述三模型、锁定OCR wheel及许可文本，再执行 `uv sync --locked --extra cv --no-python-downloads` 安装本工程依赖。两个入口在写入前均枚举 `-m aipicture_runtime serve/worker` 进程并核对通道清单；清单缺失、虚拟环境子进程使用基础Python也不能绕过检查，无法核对时停止。安装子进程剔除继承的 `UV_*` 配置，并明确固定本工程 `--project` 和 `.venv` 的 `UV_PROJECT_ENVIRONMENT`，拒绝虚拟环境解析到工程外。缓存、模型、wheel、许可、安装前包版本清单和资源来源清单仅写入 `.runtime/aipicture/vision/`；不建立业务数据库，不自动启动通道，不下载Python。已有模型不符校验或生成配置存在用户改动时停止且保留文件；失败保留本次私有 `.part`，不采用未校验资源。

准备后生成 `.runtime/aipicture/vision/runtime.env`，不含Provider或数据库秘密。通过已有启动脚本的 `-RuntimeConfigPath` 合并该文件。六模型统一位于此私有 `models` 目录；[rembg 2.0.85路径限制](https://github.com/danielgatis/rembg/blob/v2.0.85/rembg/sessions/base.py)要求U2Net自定义路径位于 `U2NET_HOME/REMBG_HOME`，两个变量必须与此目录一致。以下示例在工作区根目录执行，要求原Java中心和前端已运行，并已取得本工程资源安装与Python短停/恢复授权；实际授权和结果沿用上述唯一记录。

```powershell
# 默认只读检查
./scripts/aipicture/准备图片视觉资源.ps1
# 资源批准后，仅停止本项目Python，保留原Java和前端：
./scripts/aipicture/停止图片本地通道.ps1 -PythonOnly
./scripts/aipicture/准备图片视觉资源.ps1 -Apply
# 已准备资源后，只做本地真实CPU初始化和推理；禁止联网，不使用mock：
./scripts/aipicture/准备图片视觉资源.ps1 -SelfCheck
# 仅恢复Python到原中心；不另起Java、前端或替换服务凭据：
./scripts/aipicture/启动图片本地通道.ps1 -PythonOnly -RuntimeConfigPath .runtime/aipicture/vision/runtime.env
```

资源配置不启用付费图像Provider，`AIPICTURE_ENABLED`仍由批准运行通道决定。`-SelfCheck` 对已校验资源执行实际U2Net、SAM与OCR；在独立进程禁止socket联网，验证六个ONNX会话的 `CPUExecutionProvider`，保存抠图、SAM选区、OCR输入与结果、原样本alpha IoU、英文识别比对及耗时到私有 `selfcheck/<本次ID>/`。缺模型或依赖直接停止，真实执行与退出结果见唯一实施记录。原商品图和单行英文只提供有限技术与推理证据，结果始终 `quality_accepted=false`，仍需复杂商品/中文、人工遮罩及人工回读产物验收。

签名 `/internal/v1/capabilities` 报告依赖、模型hash/大小与当前服务进程 `loaded` 状态；独立自检的已加载状态不会冒充正式服务进程状态。`validation=files_and_dependencies_only` 的 `ready`不是语义质量结论。恢复时先停止本项目Python进程，移除本次启动的 `-RuntimeConfigPath` 并沿用原配置；需要恢复基础依赖时经批准在本工程执行 `uv sync --locked --no-python-downloads` 移除可选cv组，核对安装前包版本清单。保留私有资源与失败产物供核查，不自动删除、不改其他项目进程/密钥/库。

## 契约与确定性评测

`uv run --locked python scripts/export_contracts.py` 从唯一模型重建四份本项目契约；导出脚本使用纯 DTO，不导入数据库。`scripts/import_algorithms.py` 仅保留来源抽取记录，已经存在迁移代码时禁止覆盖安全修复。原始文件SHA256位于 `source-fingerprints.json`。

`uv run --locked python -m aipicture_runtime.eval aipicture_runtime/eval/dataset` 可运行原三组样本。去背景样本需要真实模型；纯离线核对显式设置 `AIPICTURE_OFFLINE_TEST=true`、`AIPICTURE_IMAGE_PROVIDER=mock`、`AIPICTURE_MATTING_PROVIDER=corner`、`AIPICTURE_OCR_PROVIDER=none`。退出0表示本次指标满足阈值，1表示数据/工具失败或指标未满足，2表示清单不存在/格式不合法；这不替代业务验收或用户接受。
