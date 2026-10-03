"""只读盘点中心现有绑定/事务/鉴权复用；候选SQL仅作为证据文字，永不执行。"""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

CHECKS = Path(__file__).resolve().parent
ROOT = CHECKS.parents[5]
BASE = "ruoyi-backend/ruoyi-paiflow/src/main/"
JAVA = BASE + "java/com/ruoyi/paiflow/"
FRAMEWORK = "ruoyi-backend/ruoyi-framework/src/main/java/com/ruoyi/framework/"
SOURCES = [
    JAVA + path for path in (
        "service/WorkflowService.java", "service/RunService.java", "service/CheckpointService.java",
        "service/AgentService.java", "service/IdentityMappingService.java", "service/ResourceAccessService.java",
        "service/RunLeaseService.java", "mapper/WorkflowMapper.java", "mapper/RunMapper.java",
        "mapper/CheckpointMapper.java", "domain/WorkflowVersionRecord.java", "domain/RunRecord.java",
        "domain/AgentRecord.java", "controller/RunController.java", "controller/CheckpointController.java",
        "adapter/legacy/LegacyWorkflowScopeFacade.java", "adapter/legacy/LegacyWorkflowStreamCodec.java",
    )
] + [
    BASE + "resources/mapper/paiflow/WorkflowMapper.xml",
    BASE + "resources/mapper/paiflow/RunMapper.xml",
    BASE + "resources/mapper/paiflow/AgentMapper.xml",
    FRAMEWORK + "web/service/TokenService.java",
    FRAMEWORK + "config/ThreadPoolConfig.java",
    FRAMEWORK + "security/filter/JwtAuthenticationTokenFilter.java",
    "ruoyi-backend/ruoyi-common/src/main/java/com/ruoyi/common/utils/SecurityUtils.java",
    "ruoyi-backend/ruoyi-paiflow/sql/002-paiflow-workflow.sql",
    "ruoyi-backend/ruoyi-paiflow/sql/003-paiflow-run.sql",
    "frontend/paiflow-web/src/utils/workflow-revision.ts",
    "frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts",
]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

before = {name: sha(ROOT / name) for name in SOURCES}
previous = CHECKS / "原调试构建聊天续接只读核对.json"
previous_hash = sha(previous)
assert previous_hash == "1fd03375df1754d52e29555aed9c782e7feb63febc719fb85037e031ff4b27b7"
facts = [
    {"source": JAVA + "domain/WorkflowVersionRecord.java:5", "fact": "现版本已有固定workflow/space/flow/snapshotRevision/canvas/execution/resources/codec/hash和createBy；没有debugMode/comparisonId/build key/actor。ownerUserId继承Workflow owner，createBy是用户名，不等价于构建发起者的稳定平台ID。"},
    {"source": JAVA + "service/WorkflowService.java:101", "fact": "saveInternal按space→WF锁与revision核对，保存原canvas并同步真实Agent；save会WF revision+1。普通build可在外层中心事务复用该路径后冻结版本，不调用selectVersion，Agent同步失败一起回滚。"},
    {"source": JAVA + "service/WorkflowService.java:174", "fact": "现createVersion复用validator/compiler/codec并写版本，但201 selectVersion有选中版本副作用。最小局部提取snapshot插入步骤供normal version、ordinary debug、comparison debug复用，比较路径仅锁/核revision，绝不save/selectVersion/workflowSaved。"},
    {"source": BASE + "resources/mapper/paiflow/WorkflowMapper.xml:38", "fact": "现versions/count/latest查询不分debug类型；normal detail/restore/runtimeSnapshot也未识别debug owner。新增debug row必须同步隔离列表/计数/suggest/latest/detail/restore，不能让调试构建增加普通历史版本或选中状态。versionByName仅作唯一性检查，应继续匹配全表真实unique约束，不能过滤后产生SQL唯一冲突500。"},
    {"source": JAVA + "service/WorkflowService.java:220", "fact": "runtimeSnapshot目前只按普通workflow query/space关系读取版本并核固定hash。debug版本需要数字debugOwner==当前actor，否则直接标准Run.create可能绕过thin receipt。普通非debug版本原读取规则保留。"},
    {"source": JAVA + "service/WorkflowService.java:288", "fact": "metadata接受所有非CONTROLLED字段；versionNum/name/description可从正常version流程产生，不能当debug权威。新debug绑定要用独立服务端字段，reserved receipt显示字段从旧metadata投影中剥离或由实际记录覆盖；未知raw canvas仍保全。"},
    {"source": JAVA + "service/AgentService.java:138", "fact": "实际Agent绑定由byWorkflow及consistent(tuple)处理，锁顺序space→WF→Agent；workflowSaved修改真实Agent metadata并增加Agent revision。bindingView154仅返回真实botId/revision，但自身不核space consistency/权限，不应直接作为新的独立授权入口。"},
    {"source": JAVA + "service/RunService.java:53", "fact": "既有Run按actor+运行key唯一，保存固定workflowVersionId、inputHash、assignment、状态、lease/attempt、lastSeq/revision/result。mode/comparison可经固定version关联读到，无需第二任务表或复制到Run metadata；运行key与构建key语义不同。"},
    {"source": JAVA + "controller/RunController.java:24", "fact": "现Map input→JSON.toJSONString未显式WriteMapNullValue，会在Service前省略map中的null。新thin vertical须保原input null/absent区别，修实际接入转换后再复用RunService，不能在HTTP边界偷偷改变输入/幂等hash。"},
    {"source": JAVA + "service/RunService.java:47", "fact": "现events只单独鉴权后查event_json数组，未提供与同一run watermark一致的page；新SSE先find到lastSeq再查events时可能读到随后提交的合法新事件。需短事务固定watermark/实际row seq或显式上界，不误报合法事件越界。"},
    {"source": BASE + "resources/mapper/paiflow/RunMapper.xml:32", "fact": "SQL按表seq排序但当前只返回event_json，缺上界。为SSE增加/提取真实(seq,event_json)只读投影，并核JSON seq==SQL row seq；没有新增事件/审计状态。append的现中心事务已保证顺序+重复相同内容。"},
    {"source": JAVA + "service/CheckpointService.java:155", "fact": "用户reply现有真实owner/permission/space→Run→CP锁、活租约/deadline/revision/key/hash一次性规则可直接复用。原eventId映射actual CP后仍要核run；重试请求必须带第一次固定expectedRevision/key，不能重取新revision重发。"},
    {"source": JAVA + "service/CheckpointService.java:165", "fact": "detail会closeIfEnded修改到期状态，不能把它当纯投影查询大量调用。SSE短读事务先按run授权，再读同run当前CP供既有Codec，不借读取伪造回复/到期/成功。"},
    {"source": FRAMEWORK + "web/service/TokenService.java:78", "fact": "已有getLoginUserByToken(rawToken)复用原JWT解析和Redis session，注销清同session。SSE每轮可用原私有token取新LoginUser并核初始actor/session；不能只复用捕获的旧Authentication。"},
    {"source": JAVA + "service/IdentityMappingService.java:33", "fact": "current从SecurityUtils/SecurityContext取已认证主体，清本次SqlSession缓存，重新查询账号及菜单权限。配合ResourceAccess.requireSpace重复查实际membership，才能发现账号/菜单/空间撤销；这仍不能独自发现session注销。"},
    {"source": FRAMEWORK + "config/ThreadPoolConfig.java:49", "fact": "已有共享scheduledExecutorService及threadPoolTaskExecutor，不含PaiFlow任务身份传播；用共享scheduler每连接单一不重叠任务，逐轮新SecurityContext，finally恢复/clear，不新建线程池/认证/session。"},
    {"source": JAVA + "adapter/legacy/LegacyWorkflowStreamCodec.java:29", "fact": "Codec核固定run、attempt、lease、node、资源边界与官方终态，checkpoint核实际CP。seq必须<=该run.lastEventSeq；故dispatcher需一致read page。被过滤辅助事件只推进连接scan，不发原UI陌生cursor JSON。"},
]
fields = {
    "reuseVersion": {"workflowId/spaceId/flowId": "稳定来源tuple", "snapshotRevision": "普通build为save后的revision；比较为原未变revision，也是重放响应冻结值", "canvas/execution/resources/codec/hash": "唯一不可变运行快照", "versionNum/name": "仍是中心版本识别/显示，不能编码debug权限"},
    "proposedVersionNullableFields": {
        "debugMode": "ordinary|comparison；普通人工版本NULL；由route确定，不由metadata授权",
        "debugOwnerUserId": "本次构建的真实平台actor，不能复用WF owner或username",
        "debugComparisonId": "comparison原临时UUID，ordinary NULL；与snapshot固定关联，不当workflowVersionId",
        "debugRequestKey": "构建重试幂等key，8..128 ASCII；独立于Run运行key",
        "debugRequestHash": "规范完整构建意图hash，包括route mode、WF/space、原expectedRevision、实际提交画布/参数/比较UUID；null/absent保留",
        "debugAgentId": "nullable实际绑定ID；新chat和重放fresh实际Agent→WF/space tuple必须相同；NULL也表示当时确无绑定",
    },
    "notRequiredForMinimalWire": ["debugWireJson", "debugAgentRevision硬门", "debugSelectedVersionId", "新增run状态表", "复制App/chat正式scope", "默认签名密钥/新token"],
    "reuseRun": ["workflowVersionId关联mode/comparison/来源", "ownerUserId运行发起者", "idempotencyKey一次运行意图", "inputHash同key异内容拒绝", "state/lease/attempt/seq/result唯一真相"],
}
wire = {
    "minimalData": {"id": "version.workflowId", "flowId": "version.flowId", "spaceId": "实际域的原legacy映射；默认个人域仍null", "revision": "version.snapshotRevision（固定值）", "debugReceipt": "本次实际versionId/DSL hash/codec/engine/mode/comparison关联；不授予费用/App/发布"},
    "headers": "ETag与snapshotRevision一致",
    "whyNoWireJson": "原build/addComparisons只有一个调用点且then忽略旧data；已迁transport只需id/flow/space/revision。最小wire不返回current latestVersionId/time/AgentRevision，因此无需存这些冻结字段。若以后扩完整响应，要新增明确冻结出处，不能读当前row替代。",
    "repeat": "按实际actor+debugRequestKey查固定snapshot，完整requestHash相同才返回相同wire。当前WF/space/Agent仍fresh鉴权，但不返回较新WF revision给旧画布解锁；一般body echo不得覆server字段。",
    "ordinary": "save增revision一次，snapshot不selectLatest；沿现write ticket接受递增值。",
    "comparison": "不save且snapshotRevision等于本次读过原revision；独立receipt ticket，只接收同次请求回执，不提升WF revision缓存。",
}
transactions = [
    "中心外层@Transactional READ_COMMITTED：先syntax/budget→平台actor→实际域SPACE锁→WF锁核expectedRevision→实际Agent绑定核对/必要Agent锁。不得以ext.botId或metadata替代tuple。",
    "持相同SPACE/WF锁检查debugRequest(actor,key)。重复先核完整hash/原来源tuple，再返回固定wire；不要因当前revision较新而更新旧snapshot或重新save。不同请求409。",
    "ordinary首次复用saveInternal(...synchronizeAgent=true)，保存原canvas并保留Agent同步；之后用已保存真实row提取固定snapshot。comparison首次只用提交data生成固定snapshot，保原current draft/latest，不调用Agent metadata同步。",
    "共用唯一编译/validator/codec步骤、immutable insertVersion；debug特征单独列写入，normal版selectVersion仍保原规则。snapshot未提交/回执未知不交成功。",
    "原Agent metadata修改的revision不作为debug硬门；fresh binding ID+workflow/space要一致。若业务以后需要binding epoch/审批失效，再用真实字段支持，当前不猜App绑定。",
    "chat服务核debug actor/actual version/mode/comparison/flow/space/Agent tuple后复用现Run.create；运行key复用同次用户意图，重连不改key。repeat Attach只读已存Run，不能再次Factory/begin、不能按latest推version。",
    "checkpoint回复直接现reply一次性CAS，scope仍SPACE→Run→CP；不会新增恢复状态。SSE read page不持事务等待浏览器或下一timer。",
]
sse = {
    "identity": [
        "只在私有连接上下文保初始原Bearer token、actorID及原LoginUser.token session标识；不得入receipt/assignment/events/DB/log。",
        "每轮先TokenService.getLoginUserByToken：null/session变化/actor不等立即终止附着，不生成run成功/失败/取消事件。",
        "构造该轮新SecurityContext/Authentication，调用现IdentityMapping.current与Access以原平台菜单及实际成员fresh核对；发送较长batch前再次核权限。finally恢复/clear，避免线程复用泄漏到下一连接。",
        "不新建token/线程池；共享scheduledExecutorService单连接无重叠轮询。completion/error/timeout取消future并清私有上下文；不借连接活动无限刷新session。",
    ],
    "consistentPage": [
        "短Service事务按SPACE→Run锁取真实run及lastEventSeq watermark，同事务读取after<seq<=watermark的实际SQL row；需要的CP也在同run锁下只读取得。释放事务后才send。",
        "Mapper返回seq/eventJson，核json.seq==row.seq、同run、严格递增、无越watermark；现append连续/CAS事实保持。可保标准events外部数组形状，用共同内部page helper避免第二规则。",
        "官方run终态与这一read page一致；页内只读完整当前scope记录，不用之前缓存的Run去验证后来追加的合法新事件。若不加锁，至少加固定SQL upper bound并重核状态，不能混两个watermark。",
    ],
    "cursor": [
        "SSE id建议runId:actualSeq；原UI只读data，流库把id当opaque header回传。重连严格核同run、Long范围seq>=0且<=watermark，非零须实际存在event row；其他run的ID不用于当前查询。",
        "首chat若断在第一帧前仍靠相同运行key附着已有run；cursor为0，不能重新claim/新运行。新resume fetch需要在同debug context携带上一流的cursor，而不是从当前flow猜。",
        "只对实际投影data帧发该真实seq；null辅助投影仅advance本连接scan。不发cursor JSON、不填造choices/finish。heartbeat只能SSE注释。",
        "EOF/localclose不是终态；只有官方run.*与结果一致才project flow stop。权限/session撤销停止数据交付，不冒称撤销已经发生的节点/effect。",
    ],
}
minimal_changes = [
    {"files": [JAVA + "service/WorkflowService.java"], "change": "共用snapshot插入helper；ordinary/comparison debug事务；normal版本读/restore隔离；runtimeSnapshot debugOwner门；reserved receipt显示剥离与fresh绑定"},
    {"files": [JAVA + "domain/WorkflowVersionRecord.java", JAVA + "mapper/WorkflowMapper.java", BASE + "resources/mapper/paiflow/WorkflowMapper.xml"], "change": "nullable真实debug列、actor/key查固定snapshot、normal list/count/latest过滤；suggest用normal count，name唯一性检查保真实全表约束；不另建Run表"},
    {"files": [JAVA + "adapter/legacy/LegacyWorkflowScopeFacade.java", "候选thin LegacyWorkflowDebugController/Service（最小路由边界，非第二状态源）"], "change": "复用默认个人域选择与wire；/build、比较、chat、resume、停止路由仅转既有服务及snapshot绑定，不增加业务运行状态"},
    {"files": [JAVA + "service/RunService.java", JAVA + "mapper/RunMapper.java", BASE + "resources/mapper/paiflow/RunMapper.xml", JAVA + "controller/RunController.java"], "change": "fixed version debug约束、实际EventRow+watermark读helper、Map null保全；原Run表/状态/请求key规则保留"},
    {"files": ["thin SSE dispatch（复用TokenService/scheduledExecutorService/现Codec）"], "change": "逐轮原session+菜单+member核对，SecurityContext finally清理，真实runId:seq续读；无新认证/线程池/事件真相"},
    {"files": ["frontend原flow-chat-function.ts/已有transport的局部调用适配"], "change": "同次receipt/key/cursor/space链；comparison同次mode一致；原组件/ReactFlow/store架构保留；本轮不修改"},
]
candidate_sql = """-- 仅候选文本，未执行；原共享业务MySQL，无新库/新运行表；需核原库结构、备份和授权。
ALTER TABLE paiflow_workflow_version
  ADD COLUMN debug_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN debug_owner_user_id BIGINT NULL,
  ADD COLUMN debug_comparison_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN debug_request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN debug_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN debug_agent_id BIGINT NULL,
  ADD UNIQUE KEY uq_paiflow_debug_request(debug_owner_user_id,debug_request_key);
-- 历史普通版本保NULL；所有非NULL debug字段仅中心debug事务赋值，不从metadata回填。
-- Service严格校验ordinary/comparison及nullable组合、UUID、key/hash长度、数字actor/Agent tuple。
-- 可按实际MySQL支持补CHECK约束；这里不声称NULL组合由上述DDL自动保证。
-- normal list/count/latest: WHERE workflow_id=? AND debug_mode IS NULL；suggest用normal count。
-- debug name由中心生成；versionByName保全表真实name唯一性检查，不作debug授权。
-- normal detail/restore明确拒debug，runtimeSnapshot debug只实际debug owner可读。
-- 不DROP回退/删除已被Run引用的debug快照；旧版本hash/codec不重编译。
"""
checks = [
    "普通build写一次canvas/Agent同步、WFrev+1、不改selectedLatest；比较build WF/Agent/canvas/latest/revision全不变。",
    "raw null/unknown保留、DSL canonical/hash正确；debug字段客户端metadata/名称/人工version不能授予权威。",
    "同actor/buildkey/hash重试固定snapshot/wire，WF后来revision更高不unlock旧画布；不同内容同key409。",
    "debug快照normal list/count/latest/suggest/detail/restore全部隔离，normal旧snapshot/Run语义保持。",
    "跨actor直接标准Run.create(debug version)拒；Agent解绑/换绑定/跨space拒，单纯Agent描述变动不伪造binding失效。",
    "同runkey重复chat、第一帧前断线、Last-Event-ID其他run/未来seq/不存在row均不多建run/不二次begin。",
    "append与SSE读页并发，无合法越界误报；真实row seq与payload绑定一致、过滤辅助事件scan不重复投text。",
    "共享线程先后两个不同actor：身份不串值；logout/session过期、账号停用、menu撤销、membership撤销及时终止数据交付。",
    "resume固定CP/key/revision只提交一次；取消/到期/消费并发不重开；abort原FAILED与Run.cancel分开。",
]
after = {name: sha(ROOT / name) for name in SOURCES}
changed = [name for name in SOURCES if before[name] != after[name]]
for fact in facts:
    source, line = fact["source"].rsplit(":", 1)
    assert (ROOT / source).is_file()
    assert int(line) <= len((ROOT / source).read_text(encoding="utf-8-sig").splitlines())
assert not changed
assert sha(previous) == previous_hash
result = {
    "kind": "existing checks内只读来源/候选，不是唯一设计或第二业务状态",
    "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
    "priorEvidence": {"path": str(previous), "sha256": previous_hash, "fetchProbeRerun": False},
    "scope": {"productionWrites": 0, "contractWrites": 0, "targetWrites": 0, "database": 0, "maven": 0, "provider": 0, "services": 0, "actualRuntimeOrThreadProbe": 0},
    "sourceWindow": {"meaning": "生成器hash前后窗口，非整个调查时段冻结声明", "count": len(SOURCES), "changed": changed, "before": before, "after": after},
    "sourceFacts": facts, "bindingFields": fields, "wireAndReplay": wire, "transactionSequence": transactions,
    "sseReuse": sse, "minimalSourceChanges": minimal_changes, "candidateSqlNotExecuted": candidate_sql,
    "meaningfulChecksForNextImplementation": checks,
    "limits": ["候选字段/SQL/事务/SSE helper尚未实施。", "本轮只执行stdlib证据生成与引用/hash检查，未跑前次fetch探针、Maven、目标产物、实际身份线程、HTTP/DB/Provider或QA。", "所有运行/租约/事件/CP/result继续来自既有中心表；receipt只有来源关联，没有App、发布、费用或Provider能力。"],
}
output = CHECKS / "原调试构建运行绑定中心复用只读核对.json"
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"report": str(output), "sha256": sha(output), "sources": len(SOURCES), "changed": changed, "sourceFacts": len(facts), "candidateSqlExecuted": False}, ensure_ascii=False))
