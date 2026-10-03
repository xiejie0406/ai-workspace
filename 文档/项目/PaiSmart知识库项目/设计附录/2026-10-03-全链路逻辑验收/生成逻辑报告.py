"""将本轮人工源码核对记录生成静态中文 HTML；不执行业务或测试。"""
from pathlib import Path
from html import escape
import hashlib, json, os, re
from urllib.parse import quote

out = Path(__file__).resolve().parent
root = out.parents[4]
B = 'ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/'
F = 'frontend/admin-web/src/views/paismart/'
java = {p.stem:p.relative_to(root).as_posix() for p in (root/B).rglob('*.java')}
rows = []
def item(n,entry,identity,data,work,result,recovery,limit,sources):
    rows.append(dict(number=n,entry=entry,identity=identity,data=data,work=work,result=result,recovery=recovery,limit=limit,sources=sources))

item(1,'平台登录/注册 → getRouters → 若依 Layout、TopNav/TopBar、TagsView → /paismart/*。页面请求复用 utils/request。',
 '平台 LoginUser 提供稳定 userId、权限和 root 特权；后端 @PreAuthorize 再校验。知识资格与平台角色分开，不建立第二 JWT、独立登录或自动管理员。',
 '平台注册链路选中至多一个 RegistrationExtension；邀请码支持时复用原 registerUser 与同一事务。sys_user/角色/菜单仍属共享平台，ps_member 属知识库。',
 'PaismartEnabled 控制本模块装配；业务服务通过共享 JDBC/事务访问原数据库。菜单脚本只是准备，未自动执行或授予角色。',
 '前端权限指令及动态菜单使用原平台机制；接口保持 AjaxResult/TableDataInfo，错误通过共享请求处理。',
 '无匹配扩展的邀请注册明确拒绝；多个扩展冲突拒绝；资格/登录会话失效由服务端检查。既有普通注册不因模块存在自动变更。',
 '三个 navType、平台真实登录与撤销会话、菜单/角色装配未运行；数据库54表已补建这一既有事实不证明导航可用。',
 ['PaismartIdentity','PaismartRegistrationExtension','PaismartConfiguration','api/none', 'frontend/admin-web/src/api/paismart/client.ts','ruoyi-backend/ruoyi-framework/src/main/java/com/ruoyi/framework/web/service/SysRegisterService.java'])
item(2,'/paismart/invites：单个/批量创建、编辑、启停、兑换；平台邀请注册也调用同一兑换服务。',
 '管理动作由邀请码权限限制；业务资格申请绑定当前平台用户，不接收客户端指定新 owner，不写平台管理员角色。',
 '邀请码规范大小写、用途、空间、到期时间、总次数与初始两类 Token；ps_invite、ps_invite_redemption、ps_member、成员及额度账本在同事务更新。',
 '先锁平台用户，再锁邀请码；检查有效/未耗尽及既有兑换回执，空间成员与资格、额度通过已有服务完成。',
 '前端列表筛选/批量入口与后端字段对应；失败返回实际拒绝原因，兑换回执避免相同用户重复领取和计数。',
 '禁用/到期/无效空间拒绝；编辑已消费码的限制保留；并发依赖行锁与唯一约束。事务失败不留半份资格或额度。',
 '实际并发、到期、真实注册开关与额度入账未运行；仅核对源码事务、锁序及DDL声明。',
 ['InvitationService','InviteController','MembershipService','QuotaService',F+'operations/invites/index.vue'])
item(3,'/paismart/spaces、/paismart/members：组织树、个人空间、主空间、成员、上传与治理授权。',
 '资格、平台权限和空间管理范围逐层检查；私人资料限 owner/root；子组成员可读祖先共享，普通祖先成员不自动读子组。管理资格对后代按现有规则扩展。',
 'ps_space、ps_member_space、ps_member 是正式事实，dept_id 只是关联；API对 rowVersion/expectedVersion 做更新检查。',
 '树保存检测父环；个人空间显式建立而不自动发资格；上传数/尺寸由 SpaceService 对当前成员和正式文档重新裁决。成员变更与邀请使用同一用户锁序。',
 '树/成员筛选、描述、默认组织保护与今日用量已有接口和页面；主空间保存正式关系。',
 '在用空间与非法移动拒绝；撤销成员后新读取/检索重新算范围；旧后台任务正式提交仍校验当前身份、版本与租约。',
 '真实多角色权限矩阵、撤销后的在途调用和跨会话行为未运行。',
 ['SpaceService','SpaceController','MemberController',F+'spaces/index.vue',F+'members/index.vue'])
item(4,'文档页 → UploadDialog/useChunkUpload → uploads 创建/发现续传 → chunks → merge。',
 '上传所属用户、空间及可见性绑定请求；服务端限制白名单、总大小、片大小64KiB—10MiB、最多10000片，检查全文件和每片SHA-256。',
 'ps_upload、ps_upload_chunk 与对象键保存manifest；数据库裁决缺片，Redis缓存不作为唯一事实。相同用户/范围复用回执，不按跨用户hash泄露对象。',
 '前端重选文件重算摘要并只补缺片；服务端读回对象存在/尺寸，丢失片允许同摘要修复；合并顺序流读各片并核对最终摘要。',
 '正式文档、处理任务及outbox在同事务形成；前端区分上传/合并/排队/READY，合并不宣称可检索。提交后有限回收临时片。',
 '缺片、片/全文件hash不符、范围变化拒绝；重复merge恢复原文档；暂停只中止前端传输，刷新可继续；片回收失败保留后续清理依据。',
 '实际MinIO续传、网络中断、重复merge与并发限额未运行。',
 ['UploadService','UploadController','DocumentService',F+'composables/useChunkUpload.ts',F+'documents/components/UploadDialog.vue'])
item(5,'uploads/supported-types 提供声明格式 → 同一上传与正式解析链路。',
 '文件名取安全basename，拒绝路径穿越、非法扩展与预算越界；容器/正文在ParserWorker继续检查，不能仅凭扩展名成功。',
 '原对象属于知识库对象存储；解析输出进入正式文档正文/页/块，不新增用户可指定主机路径或外部URL读取。',
 'PDFBox文本页；Tika处理Office/OpenDocument/EPUB/文本等；现代Pages/Numbers/Keynote使用有界IWA/Snappy/protobuf正文适配；RTF有JDK有界回退。XML外部实体/DTD外取与外部解析器禁用。',
 '空正文/损坏/不支持拒绝；现代Apple读取存储文字和单元格值，不承诺图表绘制、视觉分页或公式执行。',
 '解析退出码、JSON、页数、字符及文件预算核对；毒文件有限重试后终态，源文件保留供排查。',
 '历史19份CLI和54项解析检查只覆盖固定样例；本轮新解析0份。所有文件版本、复杂布局及语言包可用性未验证，OS沙箱未实现。',
 ['UploadService','ParserWorker','IworkBodyExtractor','IsolatedDocumentParser','DocumentIntelligenceService'])
item(6,'上传/文本/重处理 → ps_job + ps_outbox → PaismartKafkaRuntime → DocumentIntelligenceService → 独立ParserWorker。',
 '当前资格与处理绑定在工作执行前核对；正式写入使用任务租约、documentId、contentVersion和process_job_id，旧Worker不能越过取消/换手。',
 'MySQL任务/通知/检查点与正式文档、页元数据、chunk持有真相；Kafka只是通知。租约、恢复次数及脱敏死信在中心维护。',
 '受控并发及内存高水位暂停/恢复；TextChunker保留句词边界、重叠和小块合并。进程默认预算100MiB输入、1000页、4MiB文本、300秒、256MiB堆；结果另限16MiB，stdout/stderr直接丢弃。',
 '阶段与块/页/检查点落库，JobDialog读正式状态。本次修复通知发送耗尽/任务恢复超限时同步当前文档FAILED，避免永远QUEUED/PARSING/INDEXING。',
 '异常退避、重复通知领取保护、有限恢复、死信；中断停止后代进程并回收临时目录；终态回调不覆盖READY、删除文档或后来处理任务。',
 '未运行高水位、崩溃/取消、Kafka重复与本轮修复故障场景。资源限制属于源码机制，不能替代OS低权限和网络隔离。',
 ['PaismartKafkaRuntime','JobService','PaismartJobHandler','DocumentService','DocumentIntelligenceService','TextChunker','IsolatedDocumentParser'])
item(7,'PDF页预览 → documents/{id}/pages/{number}；解析PDF选择pdfbox/liteparse；启用OCR时中心签发内部作业ticket。',
 '页内容复用文档范围/版本检查；OCR ticket只存hash，绑定job/document/version/lease、300秒期限及页数预算。只接受受限PNG/JPEG，不能任意匿名上传付费。',
 'ps_document保存完整正文与页数，ps_document_chunk保存页码/文本；ps_ocr_ticket/ps_ocr_call保存STARTED/COMPLETED/UNCERTAIN与供应商请求号；托管OCR用途解析，云费用独立核账，不假造Token退款。',
 'LiteParse须配置绝对执行路径，本机callback地址受限；阿里RecognizeAllText结果适配页块坐标/置信度与阅读顺序。图片大小/像素、页数及HTTP超时均有限制。',
 '单页API只返回所请求页；OCR配置健康与真实调用健康分列，不将CONFIGURED_NOT_TESTED显示为已真实识别。',
 '当前租约/版本失效拒绝；未知远端结果冻结UNCERTAIN并禁止原请求重发，按实际计费页人工核对后用明确的新处理请求；进程超时清理。',
 '本轮未启用或调用本地/云OCR，扫描/混合PDF页锚点、语言包和供应商账单未核验。',
 ['OcrService','IsolatedDocumentParser','DocumentController',F+'documents/components/PdfPage.vue','DependencyStatusService'])
item(8,'文档页解析/重索引/向量化重试 → documents/{id}动作 → DOCUMENT_PROCESS/DOCUMENT_PARSE/DOCUMENT_REINDEX。',
 '管理范围、行版本和请求键校验；执行前当前平台身份/资格检查，正式保存时检查租约/当前处理指针和内容版本。',
 'ps_document_chunk向量与Embedding revision、ps_document_chunk.embedding_input_hash、ps_model_call及额度账本关联；正式READY由实际索引完成条件决定。',
 '分批向量化（批16），维度及模型快照检查；缓存键包含Provider/revision/实际输入hash，同输入复用已完成结果；原文与增强说明分别保存。',
 'ES写入/refresh后才能READY，Token显示来自正式调用结算。本次补齐排队任务通知耗尽的文档失败状态；不回退已READY结果。',
 '按块/检查点恢复；失败或取消不双扣既有已完成调用。STARTED/UNCERTAIN模型/OCR不自动重发；手动重试复核当前任务与资格。',
 '真实Embedding、维度异常、跨版本ES写入、并发额度/失败重试未运行；本轮没有新模型调用或业务数据写入。',
 ['DocumentService','DocumentIntelligenceService','ModelCallService','ModelGateway','JobService',F+'documents/index.vue'])
item(9,'/paismart/documents：可访问/仅本人、筛选分页、详情/正文、下载/PDF页、软删与恢复。',
 '列表/正文/下载/页统一 getReadable；owner/治理范围再裁决删除/恢复，引用及预览带内容版本。同名使用稳定ID区分。',
 'ps_document及ps_document_chunk为正式数据；下载对象从已授权文档取得，不接受任意对象key。删除保留原文及账务/tombstone。',
 '删除后检索以MySQL二次过滤立即排除；删除更新文档版本并使旧处理无效；30天内恢复发当前版本重处理，不把恢复动作直接视为READY。',
 '前端预览/下载类型对应真实API，分页响应使用共享TableDataInfo；私有元数据读取按现有审计记录。',
 '版本变更拒绝旧操作；超期/已物理清理不能假恢复；跨用户请求不返回正文，旧引用版本失效拒绝。',
 '实际下载字节、所有预览类型、权限撤销和软删恢复的索引一致性未运行。',
 ['DocumentController','DocumentService','SearchService',F+'documents/index.vue',F+'documents/components/DocumentPreview.vue'])
item(10,'文档搜索弹窗 → GET search/hybrid；问答工具也复用同一SearchService。',
 '资格、TopK范围与空间输入检查；ES先过滤owner/可见空间/public，返回候选再读MySQL确认权限、READY、contentVersion。',
 '索引revision与provider维度绑定；MySQL是权限/文档正式状态事实，ES只做检索投影。统计不把无权ES数量外露。',
 'BM25/KNN并行召回，RRF融合、去重；rerank启用时按独立模型得分/阈值处理。可选Embedding/KNN失败显式降级关键词，ES整体失败仍报错。',
 '响应包含scoreType、degraded/reason及indexRevision；前端按实际得分类型展示，TopK是上限，阈值后可不足。',
 '有界超时/输入/候选；关键词降级仍保留同权限过滤。索引旧投影缺原文字段时明确要求重建，不静默混用。',
 '真实检索排序、人工标注精度、延迟、供应商/ES故障和权限时序未运行。',
 ['SearchService','RrfFusion','IntelligenceController',F+'documents/components/SearchDialog.vue'])
item(11,'文档处理的contextual-enabled控制增强；检索和引用依据当前查询配置选原文/增强字段。',
 '生成说明仍按文档owner、资格、正式模型预算和托管用途处理；生成文本视为派生资料，不作为原始来源。',
 '正式chunk正文、chunkContext与模型输入摘要分别持久；Embedding缓存包含真正输入，开关变化不会错误复用不同输入。',
 '每块生成说明有界；说明失败退回原文并记录；ES保留原文与搜索字段，关闭增强查询原文，不继续用旧说明。',
 '检索/问答展示原文出处与小块引用，说明只用于召回和上下文；消耗记录进入统一调用账本。',
 '失败后原文保留；未知调用状态按ModelCallService冻结而非无限重发；旧投影不兼容要求新索引重建。',
 '开关对真实检索质量及费用的影响未运行；生成说明的正确性不能由静态核对确认。',
 ['DocumentIntelligenceService','SearchService','ModelCallService','CitationService'])
item(12,'SearchService.expand → 问答证据上下文；小块引用继续指向原chunk。',
 '每个扩展文档/块复核当前可读版本及空间，不能绕过撤销成员或私人范围。',
 '同一文档/版本相邻正式ps_document_chunk组装临时父上下文；没有新增持久Parent-Child数据模型。',
 '邻居数量及总字数预算受限；邻接小块文本去重合并，覆盖相同区间的父上下文复用且只输出一次，独立命中的小块编号保留。',
 '模型得到扩展上下文，来源预览仍定位原始命中小块与页锚点，不把临时父块当新文档。',
 '无权/旧版本块不纳入；越预算截取有界；恢复从正式块读取，不依赖会话内存缓存。',
 '跨块回答质量和生产大文档预算未运行；原项目规划中的持久父子Chunker仍是后续设计，不计已实现。',
 ['SearchService','ChatService','CitationService','TextChunker'])
item(13,'/paismart/chat：问题 → generations → CHAT_GENERATE → ReAct工具与最终回答。',
 '当前身份/资格、会话owner、组织范围、请求键及限流检查；执行过程仍查会话/平台身份。模型传来的工具名和参数不能扩大服务端知识范围。',
 '正式会话/消息/生成/反馈及引用在MySQL；模型调用、Token预留结算和工具事件进入统一记录。',
 '固定search_knowledge/generate_summary/submit_feedback/knowledge_stats四工具；最多4轮8调用。摘要内部生成不递归规划，反馈需用户明确意图，统计仅授权MySQL事实；最终输出预算2000。',
 '自然回答流式持久、工具状态逐条事件、摘要与反馈正式保存；组织会话工具证据限选定共享范围。',
 '未知工具/参数、越权空间拒绝；工具故障有明确结果；达到预算终止后使用已有证据；未知模型结果冻结待核对，不生成虚假完成。',
 '真实模型工具遵循率、资料注入、轮次预算和回答质量未运行。',
 ['ChatService','SearchService','ModelGateway','ModelCallService','IntelligenceController',F+'chat/index.vue'])
item(14,'POST chat/ws-ticket → WS /paismart/ws/chat → submit/subscribe/resume/stop；useChatStream按序号恢复。',
 'ticket60秒单次、Origin与平台会话绑定；连接及生成过程中重查资格/平台会话；owner限定恢复，最多2个订阅，不能使用第二JWT。',
 'ps_chat_generation/message/event持久，事件seq与request key用于去重；多标签重连只回放，不重复提交原问题。',
 '流式工具/引用/回答事件先保存；轮询回放持久seq。停止保存已有内容与终态；中心崩溃后RUNNING标中断，按正式核账规则恢复而非假续算。',
 '本次补齐CHAT_GENERATE通知/恢复耗尽时generation和message FAILED并写终态事件，已COMPLETE/STOPPED/INTERRUPTED内容保持。',
 '取消/租约换手阻止旧写入；新连接用afterSeq去重；原STARTED/UNCERTAIN先核对；重试保留先前内容、引用和账务。',
 'WS实际断网、多窗口、撤销会话、崩溃与新增失败回调未运行；本轮不是流式业务验收。',
 ['ChatService','WebSocketTicketService','PaismartChatWebSocket','JobService',F+'composables/useChatStream.ts'])
item(15,'会话侧栏及/paismart/history：创建、切换、归档/恢复；审计入口按用户/日期/关键词筛选。',
 '个人会话按owner；审计需独立权限且限制root、owner或空间管理范围，私人对话不因泛用权限自动公开。',
 'ps_conversation、ps_chat_message及正式引用关联持久；归档只改标记，列表/消息分页不靠前端缓存作为真相。',
 '生成上下文仅取所属会话尾部与预算；日期过滤及总数在服务端，切换/归档更新行版本。',
 '重新登录可通过正式API回读历史、引用和工具事件；审计附操作人/时间。',
 '伪造会话ID/越权审计拒绝；并发变更用版本保护；会话归档不物理删除账务或消息。',
 '真实账号、历史分页边界与管理范围联机未运行。',
 ['ChatService','IntelligenceController',F+'chat/components/ConversationList.vue',F+'history/index.vue'])
item(16,'消息引用编号 → references/{id} → 内容/PDF页；可选引用核验状态随消息/历史回读。',
 '引用绑定documentId/contentVersion/chunk/page/anchor；预览实时校验文档可读，撤销或版本失效拒绝正文。',
 'ps_reference及message引用映射正式保存；跨多轮工具累积同一编号映射，不使用标题推断来源。',
 'CitationExtractor解析文本编号；CitationService登记原文锚点，可选按句/引用对核验；模型只看有界原始证据，增强说明不当原证。',
 '跨会话历史保留编号及可疑标记；ReferencePreview/PdfPage安全渲染来源，内容版本传入API。',
 '不支持/可疑结论保留；外部核验失败不伪造支持；未知模型调用照正式核账处理。',
 '引用定位精度、PDF视觉锚点及付费核验未运行；历史组件截图只属旧版本固定夹具。',
 ['CitationService','CitationExtractor','ChatService',F+'chat/components/ReferencePreview.vue',F+'composables/referencePresentation.ts'])
item(17,'当前版本文档索引完成 → CONFLICT_DETECT → /paismart/conflicts候选列表。',
 '索引锁/任务租约及当前READY文档；候选双方按发起owner当前可见范围重查，不只依赖向量筛选。',
 'ps_document_conflict保存双方文档/内容版本、chunk、pairKey及原因；重复pair唯一，ES只用于候选。',
 '相似向量候选后做键值/掩码文本相似分析、候选/块数上限；可选LLM确认只补理由或排除，不自动删除。',
 '列表返回具体片段、事实差异及相似度，LLM失败标需要复核；无权限文档候选不返回。',
 '文档/索引版本变化拒绝旧候选；重复通知/索引不会新增同一pair；检测失败可走有租约的正式任务恢复。',
 '真实跨范围冲突召回与人工标注准确度未运行。',
 ['GovernanceService','KeyValueConflictAnalyzer','SearchService','DocumentIntelligenceService',F+'governance/conflicts/index.vue'])
item(18,'冲突比较页 → KEEP_NEW/KEEP_EXISTING/IGNORED + note/expectedVersion；文档页可恢复软删文档。',
 '双方可读、处置目标治理权限、候选双方内容版本与冲突行版本全部核对；不是单凭拥有页面按钮删除。',
 '冲突裁决、整篇文档软删、相关候选INVALIDATED与审计在正式事务处理，保留原文对象/账务。',
 '沿用原业务整篇退出检索语义；忽略只保存裁决，不删除。检索MySQL二次过滤排除已删文档。',
 '页面确认提示整篇影响，保存动作与理由；恢复进入正常重新处理，裁决历史不抹除。',
 '两次裁决版本冲突拒绝；恢复需30天/对象前提，不承诺恢复已物理清理资料。',
 '真实并发裁决、退出/恢复索引与影响提示操作未运行。',
 ['GovernanceService','DocumentService','SearchService',F+'governance/conflicts/index.vue'])
item(19,'/paismart/indexes → rebuild续建/全新、rollback、recover → GovernanceService + IndexCoordinator。',
 '索引权限、请求键、Provider启用/维度/配置快照、回滚行版本和旧凭证；中心KNOWLEDGE_INDEX租约/fence串行正式切换。',
 'ps_index_revision、checkpoint/sourceWatermark/validation及ps_index_switch保存阶段；ES别名与MySQL ACTIVE + Provider必须一致。',
 '重建按正式文档逐步保存，增量追平；正式chunk数及冻结查询/KNN抽样校验后进入切换：PREPARED→ES别名读回→ALIAS_CHANGED→ACTIVE/COMPLETED。',
 '进度/校验细项可读。本次取消或最终失败将非活动BUILDING/VALIDATED标FAILED，可从原检查点续建；重试置BUILDING，保留旧有效索引。',
 '别名不明留RECOVERY_REQUIRED并阻止普通查询；recover按ES读回选择目标/原版本，禁止盲目覆盖。本次终态回调保留pending switch、ACTIVE/RETIRED及另行续建任务，旧作业不得覆盖新作业。',
 '本轮未重建/回滚/切换ES，也未运行新取消恢复场景；别名一致性、更新追平与容量未验证。',
 ['GovernanceService','IndexCoordinator','JobService','SearchService',F+'governance/indexes/index.vue'])
item(20,'/paismart/evaluations：inlineJsonl、本地上传datasetId、登记资源 → EVALUATION_RUN → report。',
 '评测运行权限/资格和数据集owner；只收受控JSONL及资源ID，不收主机路径/任意URL。UTF-8、900000字节、500例、TopK1—20、seed安全整数检查。',
 '冻结样本内容/hash、索引/Provider/seed/config，ps_evaluation/case/dataset与正式job绑定。数据集不是知识文档，不隐式索引。',
 '逐例授权检索及指标、90秒回答/60秒Judge预算；严格Judge解析并保存失败/诊断；短事务当前租约保存案例/checkpoint/report，已存案例重试复用。',
 '完整报告未生成也可回读正式已存案例；total/processed/pending分开，无样本指标为空。本次最终作业失败同步评测FAILED，已COMPLETE/COMPLETED_WITH_ERRORS报告不被取消/失败改写。',
 '取消/重试联动jobId/owner/行版本，保留配置、案例、账务；STARTED/UNCERTAIN拒绝自动重发。终态回调属于同一中心事务。',
 '真实JSONL上传/登记权限、指标独立复算、Judge外发和新增失败场景未运行。',
 ['GovernanceService','EvaluationAlgorithms','IntelligenceController','JobService',F+'governance/evaluations/index.vue',F+'composables/evaluationDataset.ts'])
item(21,'/paismart/review：报告逐例复核、支持/不支持/不确定及理由；抽样与一致性结果。',
 '评测/复核权限、owner/治理范围，caseId归属及expectedVersion检查，操作人来自平台身份。',
 'ps_evaluation_review与修订历史持久，原Judge结果/样本冻结不因人工复核改写；报告读取当前复核。',
 'HumanReviewSampler按确定性种子/阈值抽样；EvaluationAlgorithms计算agreement/kappa/分歧及分数差，可靠性最少5个可计算样本。',
 '报告显示样本不足提示、Judge与人工对比、修订操作人；可以导出正式报告快照。',
 '并发复核行版本冲突拒绝，不抹掉原修改；不可计算/不足样本不伪造可靠。',
 '当前规则下真实多复核员并发、抽样质量及公式与独立样本复算未运行。',
 ['GovernanceService','EvaluationAlgorithms','HumanReviewSampler',F+'governance/evaluations/review.vue',F+'composables/evaluationPresentation.ts'])
item(22,'/paismart/providers：LLM/EMBEDDING/RERANK配置、启停/激活/指定Provider测试。',
 'Provider独立管理/测试权限；用途绑定托管secretRef，不接受明文Key字段；保存空引用沿用既有值，不读回密钥。',
 'ps_provider保存配置/版本和活动scope；配置快照参与模型输入账务和索引revision；实际Key属已有托管系统。',
 'ControlledHttp检查白名单host/HTTPS、无userinfo/query和重定向，完整操作URL规范化；调用预算/输入/输出/超时受限，测试也走正式调用网关。',
 '前端显示脱敏引用与真实测试结果；Embedding已有活动索引时不能直接激活，必须正式重建后切换。',
 '缺密钥/用途或白名单拒绝；错误脱敏；调用不明保留UNCERTAIN，不能把测试重试当免费重复发送。',
 '托管密钥解析、供应商协议、真实SSRF/网络边界和测试费用未运行；仅静态核对受控出口。',
 ['ProviderService','ModelGateway','ControlledHttp','ModelCallService',F+'operations/providers/index.vue'])
item(23,'所有LLM/Embedding经ModelCallService → QuotaService + ModelBudgetService，日额度/余额两策略。',
 '平台用户当前有效及业务资格、限流、输入指纹/operationId幂等；不信任前端估算或用量字段。',
 'MySQL account/reservation/ledger/model_call 持有余额、冻结及调用状态；DAILY按Asia/Shanghai日窗口，BALANCE以真实余额为准，Redis不持有资金真相。',
 '同用户锁序、预留预算及全局三组模型预算；完成实际usage结算，STOPPED保存已发生用量，释放先偿债；幂等回执防重复结算。',
 '页面显示实际剩余/冻结/债务；缓存故障不静默不限额；既有COMPLETE/STOPPED返回已存响应。',
 'STARTED/UNCERTAIN保留冻结并拒绝自动重发；管理员按供应商实际Token及理由核对，核账不制造模型回答或退款。',
 '真实多实例并发、跨日/故障/供应商账单及付款后的额度一致性未运行。',
 ['QuotaService','ModelBudgetService','ModelCallService','ModelGateway','IntelligenceController'])
item(24,'/paismart/usage：本人流水/余额、管理7/30日趋势排行预警；成员额度弹窗追加两类Token。',
 '本人入口绑定current user；看他人需usage:audit/成员额度管理权限，管理范围与理由检查。',
 '额度账本、reservation与正式调用聚合；追加credits请求键唯一，不直接改Redis余额。',
 'DAILY剩余额度按真实当日使用及冻结计算；管理趋势/排行按正式记录聚合，Token两类分开，追加由统一credit服务。',
 '前端个人概览与管理汇总分别消费实际字段；流水分页/日期过滤及总数可回读。',
 '重复追加恢复同一回执；非法整数/范围拒绝；无权读取他人流水拒绝；债务偿还保留正式流水。',
 '真实SQL独立聚合、重复请求和管理范围联机未运行。',
 ['UsageController','QuotaService','MemberController',F+'usage/index.vue',F+'usage/components/UsageOverview.vue'])
item(25,'/paismart/limits：五类规则；登录注册平台扩展、聊天入口、模型LLM/Embedding出口。',
 '配置权限、规则kind/mode/window/limit/行版本检查；平台已有验证码与登录保护继续执行。',
 'ps_rate_limit_policy是策略；Redis计数只用paismart命名空间，不替换共享Redis、账号认证或其他业务键。',
 'SINGLE/DUAL LUA原子计数，在分钟/日窗口组合判定；使用平台防护扩展接入而不建立第二套登录。',
 '超限服务端拒绝并给retryAfterSeconds，前端展示实际提示；拒绝在模型外发前，不产生新付费调用。',
 'Redis不可用拒绝而不静默放行；配置重读仍按正式版本；跨实例由同一Redis脚本裁决。',
 '真实Redis故障、窗口时钟及多实例并发未运行。',
 ['RateLimitService','RateLimitController','PaismartRateLimitConfiguration',F+'operations/limits/index.vue'])
item(26,'/paismart/recharge：套餐/自定义整数分订单 → 二维码/原单轮询 → 主动查单或微信回调。',
 '当前资格与订单owner、Idempotency-Key、精确amountFen与最大金额；匿名callback走验签/解密并核对商户、订单和金额。',
 'ps_recharge_order固化tradeNo、套餐价格/权益快照；支付事件/transaction唯一，两类Token使用同一正式额度账本入账。',
 '预支付/查单CAS避免并发重复外发；签名/解密/状态由WechatPaymentGateway处理；SUCCESS只入账一次。NOTPAY和USERPAYING保留不同语义。',
 '前端保留原未决订单，显示实际REVOKED/PAYERROR等状态及过期二维码；关闭支付明确不可支付，不用Mock成功替代。',
 '支付结果不明不自动新建/重发预支付；错误金额/签名拒绝，重复/乱序回调不重复credit；迁入历史成功订单不重放充值。',
 '本轮微信、预支付、查单、回调及账单均未调用；验签/金额幂等只做源码核对。',
 ['RechargeService','WechatPaymentGateway','RechargeController','QuotaService',F+'recharge/index.vue'])
item(27,'/paismart/packages：套餐创建/编辑/排序/启停/删除；充值页只选择有效套餐。',
 'recharge:manage权限，If-Match行版本与价格/Token精确整数、名称/说明/权益范围；用户端包列表需资格。',
 'ps_recharge_package正式版本；订单创建时保存快照，后续套餐修改不回写历史订单。',
 '既有套餐服务规范字段和排序；自定义额度按已确定折算规则整数计算，并检查溢出。',
 '运营页面完整显示权益和启用状态；停用/删除不销毁历史充值快照。',
 '并发更新版本冲突拒绝；价格或权益非法拒绝；旧订单查单依赖原快照不依赖当前套餐。',
 '真实套餐CRUD及旧订单权益核对未运行。',
 ['RechargeService','RechargeController',F+'operations/packages/index.vue',F+'recharge/index.vue'])
item(28,'/paismart/jobs：组件状态、任务、未知调用核账；documents/text；用户活动/会话审计。',
 '运维查看/执行、文档文本写入和审计权限分开；任务/计划按owner/触及文档管理范围检查，不仅靠列表筛选。核账限平台root及正式理由。',
 'ps_job/outbox/checkpoint、ps_audit_event与正式业务对象；SchemaReadiness只读核对原库结构，系统探测不代替业务结果。',
 'DependencyStatusService按开关探测Redis/ES/存储/消费循环或仅检查路径配置；文本上传进入原对象/文档处理链路。本次统一final failure回调，包括通知耗尽、处理重试耗尽和恢复上限。',
 '状态区明确tested/scope/reason，Worker路径存在不称解析成功；作业FAILED与关联文档/生成/评测/计划终态可对齐；原真实失败历史保留。',
 '失败回调与中心任务终态同事务，失败则保留待处理通知；死信只含ID/脱敏原因；后台取消/重试用当前任务和业务版本，不做模型退款或破坏收据。',
 '本轮没有请求任何状态/核账接口或启动服务；最新系统联机健康与本次新增失败场景未验证。',
 ['OperationController','OperationAccessService','DependencyStatusService','JobService','PaismartKafkaRuntime','DocumentController',F+'operations/jobs/index.vue'])
item(29,'/paismart/jobs运维计划：bootstrap-import/object-migrations/cleanup-plans → 清单审批 → execute → OPERATION_EXECUTE。',
 '清单只允许知识库命名空间/配置只读旧源，管理范围逐项预检；摘要固定，审批人不同于创建人；执行权限和operations/object writes开关共同限制。',
 'ps_operation_plan/item/checkpoint与正式文档/上传对象绑定；每项payload hash及SUCCEEDED/UNCERTAIN回执。源对象保留，清理仅精确快照与30天软删策略。',
 '内置导入按logicalKey绑定同一文档，当前hash/页块/索引不全才修复；迁移核对完整hash/大小，正式指针切换在短事务当前租约下提交。旧桶只读，源不删除。',
 '计划完成不等于导入文档READY；本次补齐FAILED/CANCELED/QUEUED回调，前端新增取消筛选/重试入口，正式契约枚举同步。',
 '保留审批与已成功条目；取消/换手后旧Worker不能改EXECUTING/FAILED；恢复先核对已有目标hash，UNCERTAIN不盲目覆盖。已SUCCEEDED计划不因中心晚失败改写；物理清理后只可从备份恢复。',
 '本轮没有创建/批准/执行计划或搬迁、清理对象；实际恢复、存储一致性与新回调并发未运行。',
 ['OperationController','OperationExecutionService','OperationAccessService','UploadService','LegacyMinioObjectSource',F+'operations/jobs/index.vue','contracts/paismart/knowledge-v1.openapi.yaml'])
item(30,'完整设计第1.3节来源能力映射 → 31业务点 → 本报告源码入口；若依登录/安全功能沿用共享平台。',
 '原独立账号/JWT/导航和短信登录/重置/微信绑定占位不作为正式迁移能力；不能将无实现页面写为成功操作。',
 '不迁旧密码/JWT；正式业务真相集中MySQL，Python仅离线转换、独立Java Worker仅解析结果；不引入第二控制面。',
 '保留已有BM25/KNN/RRF、分块邻居扩展、ReAct、冲突键值、评测/人工复核算法；持久Parent-Child/格式专用新Chunker仍属计划。',
 '管理页面覆盖原声明业务能力，来源映射区分原有API/页面/占位与目标；独立测试Controller/演示页未装配正式业务。',
 '未完成环境/OS隔离/容量明确保留；构建或文件存在只证明编译装配，不证明用户可用或真实迁移完成。',
 '本轮31项全链路逻辑核对完成，31真实业务均未执行；完整生产能力、用户接受和发布均无结论。',
 ['文档/项目/PaiSmart知识库项目/完整迁移设计.md','PaismartConfiguration','PaismartIdentity','ParserWorker','contracts/paismart/README.md'])
item(31,'prepare-legacy-migration.py读取批准本地snapshot/mapping → reviewed-import.sql/manifest/readonly-verify；对象复制另由受控计划。',
 '明确approved用户映射、operator、源/目标时区、旧组织/个人归属；15来源表及Redis冻结余额、对象hash、评测/复核、索引元信息完整，无自动合并同名账号。',
 '目标仍原ry-vue，工具不连接/写库；V001—V005、正式业务记录和ps_legacy_archive/receipt关联，旧秘密脱敏，Provider默认不激活、账务不重放充值。',
 '稳定UUID映射，来源/映射/批次/转换指纹；SQL在首个插入前检查history每一版、所有既有回执归档与身份关系，相同来源不同转换拒绝；同转换换批次恢复收据。',
 '输出executed=false，保留未完成上传manifest并将MERGING恢复UPLOADING；导入正文元数据可从同原对象补齐并保留旧chunk/引用ID，复制完成再开放消费。',
 '遇错停止并回滚，不补旧裸归档指纹；切换前备份/冻结，回退前独立导出新增并核账，不覆盖后来文档/订单/Token。不存在自动财务补偿或一键真实回退。',
 '54表已补建/结构回读为既有跨项目证据，本轮未重复DDL。17项离线守卫及19份解析CLI只按未变部分历史证据引用，实际旧数据导入、对象复制与切换/回退未执行。',
 ['ruoyi-backend/ruoyi-paismart/scripts/prepare-legacy-migration.py','SchemaReadiness','OperationExecutionService','DocumentIntelligenceService','ruoyi-backend/ruoyi-paismart/README.md'])

design_path = root/'文档/项目/PaiSmart知识库项目/完整迁移设计.md'
design = design_path.read_text(encoding='utf-8-sig')
names = {int(n):name.strip() for n,name in re.findall(r'^\| (\d+)\. ([^|]+)\|',design,re.M)}
fixed = {6,8,14,19,20,28,29}
source_manifest=[]
symbols = {
    'JobService':'public void failUndeliverable(',
    'PaismartKafkaRuntime':'void recoverNotifications(',
    'PaismartJobHandler':'default void onFailed(',
    'DocumentService':'public void onProcessingFailed(',
    'DocumentIntelligenceService':'public Map<String,Object> processJob(',
    'ChatService':'@Override public void onFailed(',
    'GovernanceService':'@Override public void onCancelled(',
    'OperationExecutionService':'@Override public void onFailed(',
    'SearchService':'public List<Evidence> expand(',
    'IndexCoordinator':'public class IndexCoordinator',
    'InvitationService':'public class InvitationService',
    'SpaceService':'public class SpaceService',
    'UploadService':'public class UploadService',
    'CitationService':'public class CitationService',
    'ModelCallService':'public class ModelCallService',
    'OcrService':'public Map<String,Object> recognize(',
    'DependencyStatusService':'public List<Map<String,Object>> components(',
    'OperationController':'public AjaxResult retry(',
    'PaismartIdentity':'public final class PaismartIdentity',
    'contracts/paismart/knowledge-v1.openapi.yaml':'    OperationPlan:',
    F+'operations/jobs/index.vue':"CANCELED",
}
def resolve_source(name):
    if name=='api/none': return None
    path=java.get(name,name)
    p=root/path
    if not p.is_file(): raise ValueError('引用不存在：'+path)
    text=p.read_text(encoding='utf-8-sig')
    symbol=symbols.get(name, 'public class '+name if name in java else None)
    if name in ('PaismartJobHandler','DocumentObjectStore'):symbol='public interface '+name
    if symbol and symbol in text: line=text[:text.index(symbol)].count('\n')+1
    else:line=1
    source_manifest.append({'path':path,'line':line,'SHA256':hashlib.sha256(p.read_bytes()).hexdigest()})
    href=quote(os.path.relpath(p,out).replace('\\','/'),safe='/')+'#L'+str(line)
    return f'<a href="{href}" title="{escape(path)}">{escape(p.name)}:{line}</a>'

labels=[('entry','入口与参数'),('identity','身份、权限与拒绝条件'),('data','API与正式数据归属'),('work','任务、算法与Worker'),('result','保存结果与页面反馈'),('recovery','异常、取消、重试与恢复'),('limit','运行前提与未验证范围')]
cards=[]
md=['# 全链路逻辑核对的源码对应','', '这是本轮HTML的同源位置索引，实施状态只维护在完整迁移设计第6节。所有结论限定源码/编译范围，实际业务未执行。','']
for r in rows:
    n=r['number'];r['name']=names[n];r['status']='修复后逻辑复核（未运行）' if n in fixed else '逻辑成立（静态范围）'
    anchors=[x for x in (resolve_source(s) for s in r['sources']) if x]
    category='fixed' if n in fixed else 'logic'
    fields=''.join(f'<dt>{title}</dt><dd>{escape(r[key])}</dd>' for key,title in labels)
    cards.append(f'<article id="point-{n}" data-kind="{category}"><div class="cardhead"><h2>{n}. {escape(r["name"])}</h2><span class="badge {category}">{r["status"]}</span></div><p class="rule">规则来源：完整迁移设计第5节第{n}项，及第1.2、第3节相关业务边界。实际业务：未执行（本轮限定逻辑核对）。</p><dl>{fields}</dl><details><summary>源码与契约对应（{len(anchors)}处）</summary><p class="sources">'+ ' · '.join(anchors)+'</p></details></article>')
    md += [f'## {n}. {r["name"]}', '',r['status']+'；真实业务未执行。','']
    md += [f'- [{(root/java.get(s,s)).name}](../../../../../{java.get(s,s)})' for s in r['sources'] if s!='api/none']+['']
    for key,title in labels:md += [f'**{title}：**{r[key]}','']

def local_link(path,label=None):return f'<a href="{quote(path,safe="/")}">{escape(label or path)}</a>'
build=json.loads((out/'私有交付产物核对.json').read_text(encoding='utf-8'))
defects=[
 ('通知/恢复耗尽只改中心作业','Kafka累计通知失败或恢复超限时原代码只将ps_job改FAILED，未进处理器，因此文档、问答、评测或运维计划可能永久留在排队/处理中。','新增onFailed，统一有限失败入口；通知耗尽先锁当前QUEUED/RETRY并重查attempts≥12，回调与业务终态/死信同事务，再标通知FAILED。','PaismartJobHandler','JobService','PaismartKafkaRuntime'),
 ('索引取消后revision仍BUILDING','取消中心INDEX_REBUILD并未更新索引revision，可能继续显示重建中并阻止新全新重建。','仅本任务对应的非活动revision转FAILED，续建/重试保留检查点；另行活跃续建、活动/退役版本、未决switch记录不覆盖。','GovernanceService'),
 ('评测已保存结果被晚到终态覆盖的风险','评测回调原UPDATE未限制已COMPLETE/COMPLETED_WITH_ERRORS状态；业务报告先保存但中心完成尚未记录时，取消可能改写已完成评测状态。','绑定jobId/user/当前中心版本，并仅更新QUEUED/RUNNING/FAILED/CANCELED；已完成报告保留。','GovernanceService'),
 ('运维计划取消/失败/重试不同步','计划没有中心生命周期回调；取消后可能仍QUEUED/EXECUTING，旧Worker异常路径又能无租约覆盖后续状态。','按planId/jobId联动FAILED/CANCELED/QUEUED；执行入口和异常写入均短事务requireLease，保留审批/成功条目与已SUCCEEDED计划。','OperationExecutionService'),
 ('取消计划页面及契约缺枚举','运营计划状态筛选无CANCELED，重试按钮仅FAILED，正式OperationPlan也未声明取消状态。','原Element Plus页面新增取消筛选与取消计划的既有retry动作；后端筛选及OpenAPI同一枚举同步，不新增路由/DDL。','OperationController',F+'operations/jobs/index.vue','contracts/paismart/knowledge-v1.openapi.yaml')]
defect_html=[]
for number,d in enumerate(defects,1):
    title,before,after,*sources=d
    defect_html.append(f'<section class="defect"><h3>{number}. {escape(title)}</h3><p><b>修复前：</b>{escape(before)}</p><p><b>修复后：</b>{escape(after)}</p><p><b>证据范围：</b>当前源码复核及私有编译通过；未运行故障/取消/重试场景。</p><p class="sources">'+ ' · '.join(resolve_source(s) for s in sources)+'</p></section>')

history='''<tr><td>后端362项（359+3）</td><td>既有分批通过证据及原失败日志保留。本轮新执行0项；JobService、文档/问答/治理/运维及通知运行时已变，历史通过不能证明本次状态修复。</td></tr><tr><td>前端72项</td><td>既有固定夹具/组件结果保留；运维jobs页面已变，未用旧72项宣称新版本通过。本轮仅类型检查和构建。</td></tr><tr><td>离线转换17项</td><td>转换脚本本轮未改，按源码摘要记录历史守卫依据；本轮新执行0项，不证明真实MySQL导入。</td></tr><tr><td>解析CLI19份</td><td>ParserWorker/IsolatedDocumentParser/Iwork正文源码本轮未改，主JAR因业务代码变更重新打包。本轮新执行0份，不扩大格式、OS隔离或容量结论。</td></tr>'''
sha=build['module']['sha256']
html='''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PaiSmart全链路逻辑验收报告</title><style>
:root{--ink:#172b45;--muted:#53657a;--line:#d9e2ec;--blue:#225f9f;--bg:#f1f5f9}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.7 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1180px;margin:auto;padding:30px 24px 80px}header,.panel,article{background:white;border:1px solid var(--line);border-radius:12px;padding:24px;margin:20px 0}header{border-top:5px solid var(--blue)}h1{font-size:30px;line-height:1.3;margin:0 0 12px}h2{font-size:21px;margin:0}h3{font-size:18px}a{color:#165d9b;overflow-wrap:anywhere}p{margin:10px 0}.muted,.rule{color:var(--muted)}.counts{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.count{background:#edf4fb;border-radius:8px;padding:16px}.count strong{font-size:28px;display:block}.count span{font-size:14px}.flow{display:flex;flex-wrap:wrap;gap:8px;margin:18px 0}.flow span{background:#eff6ff;border:1px solid #bed2e8;padding:7px 12px;border-radius:6px}.toolbar{display:flex;gap:12px;flex-wrap:wrap;padding:14px 0}input,select,button{font:inherit;border:1px solid #b6c5d6;background:white;color:var(--ink);border-radius:6px;padding:8px 12px}input{flex:1;min-width:230px}button{cursor:pointer}.cardhead{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}.badge{font-size:13px;border-radius:5px;padding:4px 9px;white-space:nowrap}.logic{background:#e7f1fc;color:#225f9f}.fixed{background:#fff1db;color:#895910}.rule{font-size:14px}dl{display:grid;grid-template-columns:190px 1fr;gap:9px 18px}dt{font-weight:600}dd{margin:0;overflow-wrap:anywhere}.sources{font-size:14px;overflow-wrap:anywhere}details{border-top:1px solid var(--line);padding-top:10px}summary{cursor:pointer;color:var(--blue)}table{width:100%;border-collapse:collapse;font-size:14px}td,th{text-align:left;vertical-align:top;border:1px solid var(--line);padding:10px}th{background:#edf4fb}.defect{border-left:3px solid #d69b45;padding:0 18px;margin:24px 0}.toc{display:flex;gap:8px 15px;flex-wrap:wrap;font-size:14px}.notice{background:#fff5e4;border:1px solid #e9d2a7;padding:14px;border-radius:7px}code{font:13px/1.6 Consolas,monospace;overflow-wrap:anywhere}article{scroll-margin-top:20px}.hidden{display:none}@media(max-width:780px){main{padding:12px}.counts{grid-template-columns:repeat(2,1fr)}dl{grid-template-columns:1fr;gap:3px}dd{margin-bottom:12px}.cardhead{flex-direction:column}h1{font-size:25px}.panel,header,article{padding:17px}}@media print{body{background:white}main{max-width:none;padding:0}.toolbar{display:none}article{break-inside:avoid}.hidden{display:block}details>p{display:block}header,.panel,article{border-radius:0}}
</style></head><body><main><header><h1>PaiSmart 全链路逻辑验收报告</h1><p>2026-10-03 · 当前源码、契约与私有编译产物 · 唯一实施状态：完整迁移设计第6.4节</p><p><b>31个业务点均已按入口到恢复路径核对。确认的状态同步缺口已修复并完成静态复核、类型检查与私有打包。</b>本报告的“逻辑成立”只表示所列源码链路可追踪且相关约束可定位，运行结果仍未验证。</p><p class="notice">本轮真实业务执行0项；单元/组件/集成/浏览器业务/解析CLI/迁移测试0项。未启动服务、调用付费模型/OCR/微信、重跑DDL、授予菜单账号权限、导入旧数据或执行对象运维；未进行Git操作或使用worktree。未取得用户接受或发布结论。</p><div class="counts"><div class="count"><strong>31</strong><span>全链路源码核对</span></div><div class="count"><strong>24</strong><span>逻辑成立：静态范围</span></div><div class="count"><strong>7</strong><span>受修复影响并静态复核</span></div><div class="count"><strong>0</strong><span>本轮真实业务执行</span></div></div><div class="flow"><span>入口/参数</span>→<span>身份/权限</span>→<span>API/数据归属</span>→<span>任务/算法/Worker</span>→<span>保存/展示</span>→<span>异常/取消/重试/恢复</span></div></header>
<section class="panel"><h2>结论分类与数据库事实</h2><p>① 逻辑成立：24项在所列静态范围内可追踪；② 确认缺陷：下列五组机制缺口；③ 修复后复核：影响原第6、8、14、19、20、28、29项，共7项，只有源码/编译证据；④ 运行前提未验证：每项单列真实环境、样本及外部系统限制，这不改写成业务通过。</p><p>已知原MySQL localhost:3306/ry-vue、MySQL 8.0.46，V001—V005共53张业务/控制表加history为54表，468列、94索引、76约束。这是用户最新接续及既有建表回读事实，本轮未连接库、重复建表、初始化或克隆数据。它只更新此前“结构未建”的当时状态，不证明真实业务可用。</p>BUILD_LINKS</section>
<section class="panel"><h2>确认缺陷与局部修复</h2><p>修改前当前原文保存在本证据目录“修改前原文”，不使用Git HEAD替代未提交内容；本次未改算法、数据库结构、授权菜单或共享认证机制。所有修复都在原处理器/页面/契约内完成。</p>DEFECTS</section>
<section class="panel"><h2>本轮技术检查与交付边界</h2><table><thead><tr><th>检查</th><th>实际结果与证明范围</th></tr></thead><tbody><tr><td>后端私有装配</td><td>23模块admin构建成功，含最终评测终态保护；离线Maven，test源码未复制，maven.test.skip=true/skipTests；主机JDK21，项目source/target17。只证明编译/打包。</td></tr><tr><td>前端</td><td>typecheck:paismart成功；build:prod输出私有frontend-dist成功。共享管理端构建包含其他业务已有装配资源，不代表其业务通过；既有大chunk/弃用警告保留在日志。</td></tr><tr><td>Worker包</td><td>普通模块、私有admin内嵌模块与私有Worker主JAR SHA256相同；依赖240个，BC四组件均1.85且无旧jdk15on；Worker没有启动。</td></tr><tr><td>配置/资源</td><td>私有包包含五版结构、评测资源、解析类及许可载体；既有业务/写入/外部调用默认关闭配置保留，不安装、启用或切换运行通道。</td></tr><tr><td>隔离与摘要</td><td>后端main源码与私有构建快照逐文件匹配；共享ruoyi-admin.jar与frontend/admin-web/dist/index.html构建前后SHA256不变。只观察这两个入口，不据此声称其他并行工作没有变化。</td></tr></tbody></table><p>主模块SHA256：<code>MODULE_SHA</code></p><p>本轮产物路径、完整摘要、依赖与配置：ARTIFACT_LINK；SOURCE_LINK。构建日志：BACKEND_LOG · FRONTEND_LOG · TYPE_LOG · WORKER_LOG。</p></section>
<section class="panel"><h2>历史证据与本次版本关系</h2><table><thead><tr><th>历史证据</th><th>沿用范围</th></tr></thead><tbody>HISTORY</tbody></table><p>历史失败、修复前日志与截图均保留。历史数量不计入本轮新增检查；有变化的功能不以旧测试证明新版本。当前包许可载体核对不等于供应链漏洞扫描或商用许可结论。</p></section>
<section class="panel"><h2>按原顺序查看31个业务点</h2><div class="toolbar"><input id="query" type="search" placeholder="搜索业务名称、状态、接口或表"><select id="kind"><option value="all">全部31项</option><option value="logic">逻辑成立（静态范围）</option><option value="fixed">修复后逻辑复核</option></select><button id="reset" type="button">清空筛选</button></div><p id="visible" class="muted" aria-live="polite">显示31项；真实业务未执行</p><nav class="toc">TOC</nav></section>CARDS
<section class="panel"><h2>保留的实质限制</h2><p>平台菜单及navType、真实身份/资格与角色范围、Redis/Kafka/MinIO/ES、供应商/OCR/微信、旧数据与对象搬迁、索引切换/回退、OS低权限及网络隔离、容量与真实资料质量，均未在本轮运行验证。参考源码已保留原算法与准备能力，生产全迁移及运行恢复没有被本报告提前判定完成。</p><p>本轮请求的必要实现、静态逻辑核对、类型/编译/打包与中文报告已完成。真实业务结果、用户接受和发布是独立事实；本报告不要求用户立刻批准或执行这些动作。</p></section></main><script>const cards=[...document.querySelectorAll('article')],q=document.getElementById('query'),kind=document.getElementById('kind');function filter(){let n=0;const term=q.value.trim().toLowerCase();for(const card of cards){const show=(kind.value==='all'||kind.value===card.dataset.kind)&&(!term||card.textContent.toLowerCase().includes(term));card.classList.toggle('hidden',!show);if(show)n++;}document.getElementById('visible').textContent='显示'+n+'项；真实业务未执行';}q.addEventListener('input',filter);kind.addEventListener('change',filter);document.getElementById('reset').addEventListener('click',()=>{q.value='';kind.value='all';filter();});</script></body></html>'''
links='<p>'+local_link('../../完整迁移设计.md','完整迁移设计（唯一状态）')+' · '+local_link('源码链路对应.md','源码链路对应')+' · '+local_link('../../../若依框架/证据/2026-10-03-参考项目业务建表/建表与接续记录.md','既有建表接续事实')+'</p>'
# 建表证据从本目录回到文档根需四级。
links=links.replace('../../../%E8%8B%A5', '../../../../%E8%8B%A5')
replacements={'BUILD_LINKS':links,'DEFECTS':''.join(defect_html),'MODULE_SHA':sha,'ARTIFACT_LINK':local_link('私有交付产物核对.json'),'SOURCE_LINK':local_link('当前源码摘要.csv'),'BACKEND_LOG':local_link('完成评测终态保护后私有构建.log','后端最终构建'),'FRONTEND_LOG':local_link('前端私有构建.log'),'TYPE_LOG':local_link('前端类型检查.log'),'WORKER_LOG':local_link('Worker私有依赖打包.log'),'HISTORY':history,'TOC':' '.join(f'<a href="#point-{r["number"]}">{r["number"]}. {escape(r["name"])}</a>' for r in rows),'CARDS':''.join(cards)}
for key,value in replacements.items():html=html.replace(key,value)
(out/'全链路逻辑验收报告.html').write_text(html,encoding='utf-8')
(out/'源码链路对应.md').write_text('\n'.join(md)+'\n',encoding='utf-8')
(out/'逻辑核对条目.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
(out/'报告源码位置与摘要.json').write_text(json.dumps(source_manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'业务点':len(rows),'修复影响':len(fixed),'报告':str(out/'全链路逻辑验收报告.html'),'源码链接':len(source_manifest)},ensure_ascii=False))
