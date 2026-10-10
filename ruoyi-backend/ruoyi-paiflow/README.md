# PaiFlow 若依业务模块

当前已进入完整迁移的本地源码实施：21类节点目录、原画布/执行DSL转换、结构预检、平台认证委托、受限资料与明确旧UID映射、个人/团队空间及成员、邀请/申请、工作流草稿/不可变版本，以及中心运行/租约/事件/终态、问答检查点和一次性执行登记。原React业务页面、画布、布局和状态库保留；业务Controller复用若依权限与AjaxResult，legacy入口保留原code=0格式。只有`paiflow.enabled=true`时装配；现有查看环境使用旧中心包，最新源码尚未完整部署和业务验收。

源码和历史定向测试不代表业务中心已全量完成。真实套餐/容量、全部旧接口、正式应用/对话、完整history/变量、混合资源与嵌套迭代/跨进程恢复、知识/工具/MCP/RPA、多模态与渠道闭环仍按唯一完整迁移方案持续补齐。顶层Python模型已有中心托管凭据、逐次审批/预算及SSE/WSS生产实现，V4固定版本新增参数提取Prompt/FunctionCall、决策三模式和动态问答连续检查点消息来源核验，保留原提示词及分支算法。V4只用于已接通范围，027 codec约束增量及真实profile尚未执行/配置时不能宣称联机可用；旧V1至V3字节及准入继续保留。Python V2会话/Decision历史、主动重试、未绑定外部JSON Schema引用、多模态和迭代内模型仍拒绝，不能以关闭保护代替迁移。数据节点已有固定目录、环境、中心操作与原接口接线，026及运行配置未执行/启用时仍明确拒绝。当前实现和验证范围以[唯一核对记录开头](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md)为准，不能以空快照或历史通过冒称完整迁移。

Java顶层普通LLM历史已接中心固定会话及033独立增量：Worker从正式租约verify响应顶层读取executionContext，固定领取任务、DSL字节和hash不变。`/history/java/open`只接受租约、attempt和节点；中心冻结原最近十轮及本次消息，正式模型候选绑定私有历史摘要，Worker不自行写正式问题或答案。原当前SYSTEM、旧USER/首ASSISTANT、当前USER顺序、零轮仍记录本轮及失败半轮语义保留。模型准确终态先保存，历史回填失败保留OPEN并拒绝完成输出；读帧可同步继续同次回填，不重派Provider。旧零历史完成记录只在无033轮、无新proof且旧完整绑定成立时只读兼容。字段见[Java历史契约](../../contracts/paiflow/java-history.schema.json)，本次实际检查、原失败及尚未验证范围仍只维护在唯一核对记录；033未应用真实MySQL，不表示页面会话历史已经验收。

Python普通顶层旧LLM历史已接入中心冻结快照及034来源证据表，读取/追加分别保持原Pydantic布尔和raw真值。旧历史只供原调试展示，Provider仍当前SYSTEM/USER；追加须正式模型批准、可信输入、全帧、格式化成功和真实结束，不能只凭Worker问答。同模型操作换key不能制造新历史轮次，原key重放保持原ACK。`/history/python/read`每Run/attempt冻结一次，旧最近十轮缺来源证据明确503，旧显示读保留记录；协议见[Python历史契约](../../contracts/paiflow/python-history.schema.json)。V2会话列表仍须从实际显示轮次及关联Run来源核对，不能套旧节点问答。034未应用真实MySQL，实际中心联通与完整回归以唯一核对记录为准，不称页面历史验收通过。

本机验收登录恢复统一见[共用操作说明](../../文档/若依框架/多项目并行开发启动指南.md#本机验收登录恢复)。全部本机验收通道启用辅助能力；先复用有效会话，失效时仅用已授权账号凭据辅助填写当前验证码并正常登录。浏览器无法控制或账号凭据未获授权时单独报告所需条件；恢复登录后回到待验步骤，既有业务结果与证据保持原判定。

唯一方案与实际记录位于 `文档/项目/PaiFlow工作流项目/`。真实数据库写入、外部调用、发布和Git写操作仍须对应授权。

数据Worker入口按固定节点、Run输入及当前attempt已接受上游完成事件重建原PGSql完整请求；ADD值、UPDATE条件和赋值、CUSTOM替换后的SQL、返回模式及ENV1/2须逐值一致，再进入逻辑表权限及SQL结构检查。原省略SEARCH数量默认0、Python字面量与tuple的CUSTOM字符串保持；Unicode15名称别名作为含许可的本地资源加载，不在运行时访问网络。此来源校验不把任意已接受Worker输出等同真实模型/工具生产证据，上游生产完成值的共同核验仍在接续。协议见[数据操作契约](../../contracts/paiflow/data-operations.schema.json)，当前实现、限制和实际证据只维护在上述唯一核对记录。

数据操作和节点完成事实在同一中心事务保存，031独立增量尚未应用真实库。完成事实固定run/lease/attempt/node、资源/环境、节点与描述摘要以及实际操作回执；后续读取完成时结果，不通过重新查询当前行推断旧输出。同节点重试沿用原回执，缺节点来源的旧通用回执不重新归属；旧记录不伪回填。原PGSql输出投影及全引用链验证仍须与实际原Factory和业务流程核对，不能仅凭新表或方法存在认定数据节点迁移完成。

中心应用登记已接同一`ApplicationService`：标准`/paiflow/applications`及旧`/paiflow/legacy/publish-api/create-user-app`、`app-list`保存真实owner/空间，保留原活跃名称全局唯一。固定绑定只接受同智能体的普通不可变版本；私有平台调用沿用Run准入、ENV2和权限，Run与应用请求关联同事务。028增量尚未应用真实库。READY只表示绑定，不宣称外部发布；API key、正式聊天和附件仍未接完整链路，旧create-bot-api明确503，不返回假凭据。字段源见[应用登记契约](../../contracts/paiflow/application.schema.json)，实际检查仅维护于项目核对记录。

发布准备新增/paiflow/publications/preparations，绑定真实专用调试Run完成证据与相同内容普通候选版本；普通/调试revision各自保存，自动保存或建相同内容版本不误拒。SQL029仅隔离H2，七渠道一律未配置、isPublished=false，准备与撤回不冒称外部渠道生效/下线，也不替代当前资源预检、密钥、配额、正式聊天或附件。字段见[发布准备契约](../../contracts/paiflow/publication-preparation.schema.json)，最新证据及当前整合缺口只在核对记录维护。

`/user-info/me`只读取平台当前有效账号与真实协议偏好；昵称/头像更新委托平台Service，不复制密码或角色，协议同意写入业务偏好表。旧UID仅凭显式映射或`ruoyi:<userId>`解析，不按同名/管理员回退。平台权限与空间成员关系同时检查；最后所有者不能退出/删除，个人空间不能转让。

Mapper通过本模块配置复用现有sqlSessionFactory和DataSource。2026-10-03此前建表工作已将001至019应用到既有`ry-vue`并实际回读25表335列69索引97约束，见[建表记录](../../文档/若依框架/证据/2026-10-03-参考项目业务建表/建表与接续记录.md)。后续通知、运行历史/变量、提示词、数据资源、V4编码及应用登记增量仍未应用真实库，不能并入该25表结果；应用不自动初始化库。旧数据来源/UID及导入仍未验证，隔离H2/事务/MVC检查不是MySQL业务证明，实际检查结果只在项目核对记录维护。

团队与空间分别持久化；成员角色、所有者保护、邀请七天有效期/一次接受、申请审核和实际成员写入由中心Service事务处理。`SpaceService.CapacityPolicy`没有默认实现，原团队创建、邀请和容量查询缺真实套餐/容量时明确503且零写入。邀请及个人站内通知现同事务保存，`notificationStatus=stored_in_center`仅表示入库、不表示已读或外部送达，020两表未执行前不能启用该路径；批量Excel查人/结果成果、商业认证和删除验证码仍未接通。

内部Worker路由复用共享Anonymous标记的受限入口能力，并在每次访问先验证独立短期服务身份。配置`paiflow.workers.identities.<workerId>`的engineProfile、tokenSha256与expiresAt；无默认有效身份，中心不保存明文token。实际Worker token格式为`workerId.secret`，服务身份还须匹配中心租约、引擎和仍有效的发起者权限；用户token不能代替。协议见工作区`contracts/paiflow/internal.openapi.json`和共享schema。

中心保存QUEUED任务，领取时生成租约，60秒有界续期；事件连续、同号同内容幂等、异内容拒绝；结果和中心终态事件在同一事务保存。排队取消直接落终态，已运行取消先记CANCEL_REQUESTED并等待Worker真实结果。领取流程和现有若依Quartz可调用入口`paiflowRunTask.reconcile()`有界核对过期运行及遗留检查点；没有创建/启用真实sys_job或新的调度器。无自动重放费用/写入，正式副作用未知保留EFFECT_UNKNOWN。容量/配额、断线恢复、effects参数绑定与真实资源端点仍需补齐，不能当调度发布版本启用。

Worker只读verify快照后，在本地预检通过、进入原引擎前调用`POST /internal/paiflow/v1/runs/{runId}/execution/begin`。中心行锁/CAS一次保存`execution_started_at`，首次ACK绑定运行/租约/attempt/版本/hash；重复409。ACK丢失、未知或拒绝均不得执行或由该路径提交result，中心后续核对租约；登记不是节点成功或副作用回执。节点事件、COMPLETED和检查点创建要求已经登记。006草案对旧attempt>0记录保守回填，真正切换前须暂停派发并核对旧运行，不清空字段重放。

顶层原`question-answer`检查点保存固定题面/等待策略、中心真实回复和一次消费回执。用户回复入口继续检查平台功能、空间关系、当前运行发起者，并在等到运行行锁后读取实时平台权限；Worker不能代替用户回复。WAITING/RESUMED/CONSUMED/EXPIRED/CANCELLED有revision与CAS，取消/超时竞争返回停止事实；原resumeData的message JSON字符串与中心metadata保持。问答资源图派发、迭代归属、页面等待恢复和跨进程引擎恢复尚未联机。

独立Java21 Worker与Python Worker工程已引入原算法并做隔离检查；中心实际MySQL、双Worker领任务、供应商/渠道和页面尚未联机验证。测试中的平台账号、外部依赖和部分端口是明确隔离夹具，不是真实业务验收证据。

文本分隔符沿用原个人偏好目录与原 React 接口，不按空间复制。系统种子返回数字 uid=-1，普通条目按平台用户归属；默认项不可修改，跨用户不可访问，保存/删除不改历史画布。008用 hex固定原标准 MySQL转义后的 TAB/LF，用户字面反斜杠+n保持原样；正式迁移还需核对旧库实际值与SQL mode。新增10项Mapper/事务和5项MVC与Agent/WF共51项通过，仅为隔离本地技术检查，见[唯一实施记录](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#10-2026-10-03文本分隔符目录补迁移)。

原默认个人区继续使用缺省或空 `space-id`。中心仅在明确创建/访问动作中建立当前平台用户的内部 `default_personal` 正ID；只读缺映射返回实际空页或404，legacy资源返回null空间，标准接口与持久化保持正ID，不选最近具名空间。工作流/智能体首次创建与默认表示在同一事务回滚；模型先完成候选与所需端口检查，再独立短事务准备默认表示，authorize/Hello/deploy在事务外，最终保存仍核当前权限/主体、引用、时效与CAS。原NULL空间旧资源及UID映射尚未导入。

2026-10-03 08:35:06默认区与既有资源集中182项隔离检查通过：工作流32、智能体42、模型44、身份/空间/成员/默认基础49、文本15。前后各次有重叠，不相加为独立业务通过数。模型默认启停/删除使用真实事务，版本写失败回滚；缺实际Provider/部署端口时不会写成功模型。检查与输入哈希仍在同一[实施记录](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md)及其证据中；未启动服务、执行DDL或操作页面。

固定模型编译保留原画布，另保存经当前平台权限核对的不可变模型资源快照；Python采用原Pydantic转换，Java V2普通顶层OpenAI文本chat采用原Java模板及nullable八项选项。实际接通范围见[项目当前核对记录](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#当前接续与登录循环修复)；原Spark、历史、图片、嵌套迭代等迁移目标仍保留，缺运行接入时明确拒绝。模型Hello或版本记录不代表费用许可。中心模型操作绑定运行、租约、attempt、节点、版本及完整消息来源，私有引擎从固定Run读取，只允许一次受控派发；明文材料不交Worker或写操作表。原始流按实际序号、内容和字节预算保存，真实finish与usage尾帧、供应商停止证据及未知结果分别处理，协议见工作区`contracts/paiflow/model-operations.schema.json`。

Java原节点未设置`maxTokens`时，运行批准需要对应部署Profile明确配置`paiflow.model-runtime.profiles[].provider-default-max-tokens`（正整数、无默认值）；该值仅用于预算，不补写Worker原参数或供应商请求。缺节点上限且缺此配置时在Run入库前拒绝。`n`的全部候选计入输出预算，逐次调用还核对已用额度与当前消息；Profile预算变化使原批准失效。仅有配置不代表真实Provider、费用或服务启动已授权，本次未修改真实运行配置。

账号、菜单、成员与模型启用复查在PaiFlow读取边界清理当前MyBatis会话缓存，继续复用平台用户、菜单与PermissionService；不改变全局认证或共享MyBatis配置。源码检查与隔离撤权用例将在同一实施记录记录实际结果，不能以本段说明代替验证。

原画布的纯字段转换使用版本化执行副本：非QA新版本采用`paiflow.execution.v2`，保留原数组类型、属性投影、别名、连线handle、禁用重试配置与明确null的实际转换规则；原画布不反写。015的nullable `execution_codec` 不设默认值或回填，旧NULL版本按完整V1转换与规范字节重建；未知标识或损坏快照拒绝409。V2规范字节保留null，运行和Worker从保存的Base64原字节核对SHA256及JSON一致性，不按当前画布算法重新解释旧快照。恢复旧版本只恢复草稿，后续显式创建新版本才生成V2；旧typed-array画布若缺原执行转换仍不能运行，不替历史版本悄悄补写。

Python单层纯迭代准入以`IterationStartNodeId`和原连线推导归属，要求唯一主开始/结束及内部开始/结束，无跨容器连线、孤儿、普通循环、资源、全局变量或主动重试；`parentId`不构成授权。批次只允许主开始声明的数组直接引用，实际数组或原有效默认数组计入64批、128图节点、4096展开节点、128总路径、64路径长度预算。路径通过去重邻接与动态规划计算，在进入原Chains路径枚举前拒绝超限图；原变量池的输入/输出复合地址及隐式errorCode/errorMessage地址同时防碰撞。详细item/schema验证仍交原Factory，结构准入不代表节点必然成功。嵌套迭代、混合模型/资源/QA及变量能力继续按已批准完整迁移目标补齐。

本批集中378项JUnit全部通过，包含重新执行的旧336项及新增Canvas转换11项、纯迭代真实Mapper/Run事务29项、固定模型兼容2项。首次真实领取任务被原Factory拒绝22101：disabled retry仍先校验customOutput，happy夹具的字段不符合节点输出声明；首产物、源码与失败日志均保全。只修正夹具后29项定向全通过，当前378项证据为349项未受影响沿用加29项复验，未重跑新全量。修正后的WF创建版本→Run→claim→begin实际任务保存在`target/pure-iteration-canvas-assignment.json`；Python按同一原规范字节核对hash/null后调用原Factory，实际成功输出三个item并保留原输入顺序。这次Python探针没有调用TaskRunner或真实中心租约/事件/终态接口；实际结果和限制见[唯一实施记录第20节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#20-2026-10-03原画布执行编译与单层纯迭代准入)。未启动中心、执行MySQL迁移、调用真实Provider或做页面验收。

顶层静态`question-answer`现补中心参数编译和队列准入：整图限五个已接纯节点加QA、128节点/128路径/64路径长度，无迭代、资源、global变量、history或主动重试。direct只允许handleResponse=false，option保持原静态分支及选项顺序；needReply=false仍等待真实检查点回复/ignore/超时，abort保留原FAILED。新QA版本选择`paiflow.execution.v3`，非QA继续V2，旧NULL/V1/V2不可变版本不跟随新QA转换；缺domain/appId只在执行副本补空str，无鉴权或App关系。真实Checkpoint装配缺失时零运行写入，公开SSE/原页面等待闭环尚未接入。

运行JSON与DSL编译标记分开：017的nullable run_json_codec在insert时固定，update不可改变；旧NULL/V1保持原request/event/result hash与幂等，新运行V2保留输入、事件、结果及任意输出的明确null，未知marker拒绝409。旧行不回填或改写；每个新assignment保全固定DSL的null与canonical原bytes，Checkpoint独立回执仍用原V1规则。016/017未执行，实际切换须核对旧运行与当前约束。

本次首445检查为416通过/3失败/26错误；修rawCanvas夹具及正式null键marker预算后242定向为241通过/1对象比较失败；隔离探针确认BigDecimal与Double的规范字节相同，只修测试为golden hash/bytes/null断言，最新单项通过。当前445技术组合为203首验沿用+241定向沿用+1最新复验，并未重跑修后全套。真实QA领取任务已由Python按同一原bytes/hash/deadline核对后进入OriginalEngine，内存Checkpoint创建/轮询/消费各1，输出answer=真实回复、7个本地事件；无TaskRunner或真实中心HTTP/结果闭环；失败源码/日志及当前24输入冻结均保全在[唯一实施记录第21节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#21-2026-10-03原静态问答与运行json兼容接续)。本次没有改前端或做页面/业务验收。

原工作流输入类型同步现通过真实Agent/Workflow绑定读取草稿首Start.outputs，按原Toolkit四个Biz DTO投影并返回Hub12 nullable字段数组；不编译DSL或改原画布。AGENT_USER_INPUT保留响应、先从元数据排除；额外输入JSON字符串、首文件类型、array-string旧筛选及未知类型顺序沿原Maas纯本地逻辑。非固定输入的空allowed/首项null/无schema明确400，派生动态秘密字段名仍拒绝，Agent配置1MiB超限413零写。真实同步要求Agentedit+WFquery、Space→WF→Agent锁与当前平台/成员/actor复查，CAS保存来源ID/revision，相同来源和元数据不写；后续画布修改使旧来源状态stale。复制清来源，旧工作流表单只能回显同值；指令型supportUpload沿原字段，元数据不代表上传或App能力。

同步原请求无需新revision，回执仍原数组并带真实Agent ETag及ID。已有transport仅清理落后的Agent条件，不推进旧表单或Workflow条件；原page/store/service未变。前端条件60项、独立strict TS通过，同依赖类型新增0但全项目类型仍有3710项原诊断，未做浏览器/生产构建。Java首轮编译0测试、第二66含15H2初始化错误、第三22含2删除清理权限错误均保留；修后当前68技术身份为46沿用+20沿用+2最新复验，全模块471为403原QA沿用加68，没有重跑修后全套。真实隔离MVC字节经原消费者13项核对通过，chatSpy非真实聊天。准确来源、失败与限制见[唯一实施记录第22节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#22-2026-10-03原工作流输入类型同步接续)。本节无新增/执行DDL，没有真实数据库、Provider、App、文件上传、SSE或页面验收。


原调试构建与本次运行接续现有本地中心实现，更新上文顶层纯图/静态QA的历史未接入范围。普通build复用原保存/编译规则，保存一次后固定本次快照；comparison不改草稿、Agent或当前版本。请求使用X-PaiFlow-Build-Key和真实revision条件，回执绑定十字段固定版本及debug发起者/真实Agent关系，重放不取最新草稿。调试快照与普通业务版本隔离，原空space-id继续默认个人区/null响应。SQL018增加六nullable来源字段及唯一/完整性约束，001至018仍是未执行MySQL草案；只在随机H2夹具加载。

原chat/resume/SSE薄桥复用同一Run/事件/Checkpoint，要求本次回执与当前用户/空间/版本一致；支持门仍仅顶层五纯节点和静态QA。后台复用TokenService与共享线程池，当前权限/成员重查，关闭只释放传输而不取消运行；共享池CallerRunsPolicy可能内联任务的真实反例已修为读/发送前拒绝，共享ThreadPoolConfig未改。模型/资源/迭代调试流、正式App/chat/history/变量scope仍未接通，Worker预检另在接续，不由空appId或每run私有状态代替。

首次全模块519项、47类全部通过，旧471项本次全部新执行。后续调度修复十一项为十通过一代理断言错误，单项修复复验通过；当前520身份为509首轮沿用+十项复验+一项最新复验，没有重跑当前全套。前端当前110项Node通过，类型3712→3698新增0但整工程类型未通过；242份生产输出仅对应较早源码，最新历史选择/断开本地视图修复未重建或部署。实际隔离MVC字节经原消费者九项核对，499仅AST/spy观察，不是页面UAT。准确原文、失败与复验见[唯一实施记录第23节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#23-2026-10-03原调试构建同次运行与问答流接续)及[单一本地检查汇总](../../文档/项目/PaiFlow工作流项目/证据/本地源码检查/调试聊天接续本地源码检查.json)。default OFF；未访问真实业务库/Provider/Redis、启动服务、执行真实DDL、发布或写Git，完整业务验收仍未执行。


单层纯计算迭代的原调试流现已局部接续：chat在真实输入下准入；page/resume/cancel核固定Run副本与同次回执；写入和投影复用真实节点归属/批次数。主图无迭代标签，内部只接受所属容器及规范有效索引；旧exact事件只重取回执、不重写，不授权非法下一事件。MVC输入数组深JSON化、Worker Map显式null保全；事件4MiB/结果2MiB预算未变。读取拒字符串原帧、错scope辅助及带节点元数据的伪中心终态，模型/资源/QA混合或嵌套仍未准入，resume仍静态QA。

本批首50类554检查550通过/1断言失败/3错误；只修Bridge数组边界与错误测试，受影响4类52全部复验通过。当前554身份为502首轮未受影响沿用+52本次新执行，修后未重跑全套。真实H2/MockMvc SSE保留原24节点帧、直到第25中心正式结果才结束，原/current React AST消费一致；24帧只raw.id重绑本次H2 Run，不是重跑Python Worker或页面业务验收。前端生产文件零改，Python478冻结与前271一致且未再执行。完整原文、失败/复验和限制见[唯一实施记录第25节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#25-2026-10-03原单层纯迭代调试流接续)及[本批单一汇总](../../文档/项目/PaiFlow工作流项目/证据/本地源码检查/纯迭代调试流本地源码检查.json)。default OFF；未启动服务、执行真实MySQL/Provider、发布或写Git，完整迁移及业务验收仍未完成。


原工作流type=1调试显示历史现接通保存、最近10条与本人软清除，返回原chatId字符串/null、13字段行及Integer计数。每POST追加一段，不按SID/内容去重，也不要求Run结束；中心核实际当前用户Run、空间、工作流和固定debug版本，显示正文不替代运行结果。原React前端本批零改。显示整体20MiB、单字段UTF-8 MEDIUMTEXT边界与Run结果2MiB/控制16KiB分开；实际2,200,519字节原显示请求成功、整包/字段超限413零新增。SQL019及001至018均是未执行MySQL草案，旧UID/App/Spark SID与原LONGTEXT历史未导入，不可截断。

本批8类97项实际全部通过（新增23、既有74重跑），227输入窗0；当前577为480沿用加97本轮执行，未重跑577全套。实际H2/Mapper/事务/MVC覆盖多段追加、个人隔离、撤权回滚与clear 3后0，Run/事件/等待Checkpoint保持；原/current AST各6与React叶子SSR各6一致，受控叶子依赖不代表浏览器业务验收。准确产物、源码/私有反例及范围见[唯一实施记录第26节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#26-2026-10-03原工作流调试显示历史接通)和[单一本地检查汇总](../../文档/项目/PaiFlow工作流项目/证据/本地源码检查/调试显示历史本地源码检查.json)。default OFF；无真实数据库/Provider/Redis、服务启动、发布或Git写入，完整迁移和业务验收仍未完成。
