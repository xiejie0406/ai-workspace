"""原调试构建→聊天/问答续接只读来源及实施建议；不建立业务状态。"""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

CHECKS = Path(__file__).resolve().parent
ROOT = CHECKS.parents[5]
ORIGINAL = "参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/"
CENTRE = "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/"
FLOW = "frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts"
SOURCES = [
    ORIGINAL + "controller/workflow/WorkflowController.java",
    ORIGINAL + "service/workflow/WorkflowService.java",
    ORIGINAL + "service/extra/CoreSystemService.java",
    ORIGINAL + "entity/dto/WorkflowReq.java",
    ORIGINAL + "entity/dto/WorkflowComparisonReq.java",
    ORIGINAL + "entity/biz/workflow/ChatBizReq.java",
    ORIGINAL + "entity/biz/workflow/ChatResumeReq.java",
    FLOW,
    "frontend/paiflow-web/src/components/workflow/store/use-chat-store.ts",
    "frontend/paiflow-web/src/components/workflow/drawer/chat-debugger/index.tsx",
    "frontend/paiflow-web/src/components/workflow/drawer/chat-debugger/components/chat-content.tsx",
    "frontend/paiflow-web/src/services/flow.ts",
    "frontend/paiflow-web/src/components/workflow/utils/index.ts",
    "frontend/paiflow-web/src/utils/http.ts",
    "frontend/paiflow-web/src/utils/workflow-revision.ts",
    "frontend/paiflow-web/node_modules/@microsoft/fetch-event-source/lib/esm/fetch.js",
    CENTRE + "controller/RunController.java",
    CENTRE + "controller/CheckpointController.java",
    CENTRE + "controller/WorkflowVersionController.java",
    CENTRE + "service/RunService.java",
    CENTRE + "service/CheckpointService.java",
    CENTRE + "service/WorkflowService.java",
    CENTRE + "mapper/CheckpointMapper.java",
    CENTRE + "adapter/legacy/LegacyWorkflowController.java",
    CENTRE + "adapter/legacy/LegacyWorkflowVersionController.java",
    CENTRE + "adapter/legacy/LegacyWorkflowScopeFacade.java",
    CENTRE + "adapter/legacy/LegacyWorkflowStreamCodec.java",
]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

before = {name: digest(ROOT / name) for name in SOURCES}
probe_name = "原调试构建聊天续接-fetch-event-source内存探针.json"
probe = json.loads((CHECKS / probe_name).read_text(encoding="utf-8"))
assert probe["passed"] == probe["total"] == 3 and not probe["source"]["changed"]
facts = [
    {"id": "original-build", "source": ORIGINAL + "service/workflow/WorkflowService.java:1310", "fact": "build先saveLocal草稿并以其appId/flowId调用旧远端save/build；方法返回ApiResult<Void>，没有workflowVersionId、runId或构建回执。WorkflowReq没有version字段。", "kind": "source"},
    {"id": "original-build-ack-gap", "source": ORIGINAL + "service/workflow/WorkflowService.java:1347", "fact": "旧onFailure只countDown；随后1365仅message非空才核错误码，空message仍1371 success。这是来源上的未确认回执缺口，未调用旧远端复现；新本地build必须依真实中心固定快照提交结果返回，不能继承空回执成功。", "kind": "source-derived-risk"},
    {"id": "original-comparison", "source": ORIGINAL + "service/workflow/WorkflowService.java:3740", "fact": "addComparisons仅转换本次data并交CoreSystemService；该链不saveLocal。CoreSystemService315..320发flow_id/version/data；version来自前端临时UUID。", "kind": "source"},
    {"id": "build-result-ignored", "source": FLOW + ":674", "fact": "718 api(params).then(()=>...)不读返回值，735立即runDebugger，744同步输入类型且不await。普通build带id/flowId/name/description/data；比较带flowId/name/data/version。", "kind": "source"},
    {"id": "comparison-store-mismatch", "source": FLOW + ":657", "fact": "657取manager.historyVersion，但677判断chat-store get().historyVersion；initialStatus和useChatStore未定义该字段，后续chat606判断manager.historyVersion。原源与迁入源同源。可能造成普通build后chat使用空/旧比较version；未执行页面，不把分支定义当比较联机成功。", "kind": "source-derived-risk"},
    {"id": "original-chat-app", "source": ORIGINAL + "service/workflow/WorkflowService.java:3070", "fact": "原chat从真实workflow.appId取AK/SK，sysReq含flow_id/parameters/uid/version；chatId只作原SSE emitter及对话关联。history/regen会读取或删除旧对话，这些正式历史端口尚未迁通，不能复制为本地状态。", "kind": "source"},
    {"id": "original-resume", "source": ORIGINAL + "service/workflow/WorkflowService.java:3147", "fact": "原resume要求eventId及非null content，旧核心只收到event_id/event_type/content。原UIabort不带content，与此要求存在原缺口。原QAabort为23802/FAILED，不是Run.cancel的CANCELLED。", "kind": "source"},
    {"id": "stop-order", "source": FLOW + ":880", "fact": "停止先887刷新chatIdRef，中断时889..907发abort SSE，再929清flowResult.status。drawer157..178对此状态触发controller.abort及deleteComparisons；abort请求可能被本地效果清理取消。非中断停止没有现成中心cancel请求。", "kind": "source-derived-risk"},
    {"id": "current-revision-transport", "source": "frontend/paiflow-web/src/utils/workflow-revision.ts:136", "fact": "现条件版本transport把/build认作write，必须带已读If-Match或显式revision；accept要求data.id/flowId/spaceId/revision匹配且revision递增。纯Void或仅version DTO不足以通过该已批准transport；build回执应保留工作流wire字段和ETag，新增回执可放独立字段。", "kind": "source"},
    {"id": "current-version-side-effect", "source": CENTRE + "service/WorkflowService.java:174", "fact": "createVersion锁工作流/核revision，编译不可变快照并保存canonical字节和hash，201还selectVersion。comparison原未改变草稿/选中版本，不能直接调用公开createVersion替代；宜复用同一编译/版本表的内部不可变snapshot步骤并明确不改变选中关系。", "kind": "source-derived-proposal"},
    {"id": "current-run-idempotency", "source": CENTRE + "service/RunService.java:53", "fact": "当前create按actor+8..128 ASCII运行key查已有run，核version/engine/input/timeout真实request hash；同key异请求409。新run只由runtimeSnapshot的固定version和已准入profile生成。该既有规则可承接重复SSE POST，不可按chatId或regen布尔推导唯一运行。", "kind": "source"},
    {"id": "current-checkpoint-cas", "source": CENTRE + "service/CheckpointService.java:155", "fact": "用户reply仅当前run发起者+平台权限+space允许；精确body expectedRevision/idempotencyKey/event_type/content，活run/lease/deadline、WAITING及revision CAS；同key同完整request可重取已提交回执，不同内容409。原reply metadata.retries由ordinal-1、timestamp由中心真实时间生成。", "kind": "source"},
    {"id": "current-stream-is-projection", "source": CENTRE + "adapter/legacy/LegacyWorkflowStreamCodec.java:20", "fact": "当前codec声明不公开路由、不创建运行；只投影已核固定run事件，资源/迭代拒绝。RunController无stream路由，legacy workflow无build/chat/resume/add-comparisons路由。因此当前尚无原UI调试SSE联机入口。", "kind": "source"},
    {"id": "current-run-cancel", "source": CENTRE + "service/RunService.java:124", "fact": "QUEUED取消可立即中心确认CANCELLED；已派发仅CANCEL_REQUESTED，后续确认才终态。其他成员取消另需管理员级space关系；关闭网络不可据此冒称停止。", "kind": "source"},
]
requests = [
    {"name": "普通构建", "source": FLOW + ":696", "method": "POST", "path": "/workflow/build", "body": ["id", "flowId", "name", "description", "data.nodes", "data.edges"], "transforms": ["去nodeType", "data.updatable=false", "删除data.debuggerResult", "edges去style/animated"], "resultConsumer": "718忽略data；已迁transport另核workflow wire + ETag"},
    {"name": "比较构建分支定义", "source": FLOW + ":677", "method": "POST", "path": "/workflow/add-comparisons", "body": ["flowId", "name", "data.nodes", "data.edges", "version"], "transforms": ["version=本次uuid()", "去nodeType", "data.updatable=false", "edges去style/animated", "未删除debuggerResult"], "executionStatus": "分支状态来源不一致，仅静态来源"},
    {"name": "普通/比较聊天共用第一fetch", "source": FLOW + ":574", "method": "POST", "path": "/workflow/chat", "body": ["flow_id", "inputs", "chatId", "regen"], "conditional": "manager.historyVersion真另加version/promptDebugger=true", "headers": ["Content-Type", "Authorization"], "absent": ["space-id", "If-Match", "buildReceipt", "runId", "requestKey"], "signal": "本次新AbortController", "openWhenHidden": True},
    {"name": "问答继续第二fetch", "source": FLOW + ":516", "method": "POST", "path": "/workflow/resume", "body": ["flow_id", "eventId", "eventType", "content"], "eventType": "content truthy => resume，否则ignore", "conditional": "chat-store.versionId truthy另加version/promptDebugger=true", "headers": ["Content-Type", "Authorization"], "absent": ["chatId", "runId", "expectedRevision", "idempotencyKey", "space-id"], "signal": "同chat AbortController"},
    {"name": "中断停止第三fetch", "source": FLOW + ":880", "method": "POST", "path": "/workflow/resume", "body": ["flow_id", "eventId", "eventType=abort"], "absent": ["content", "version", "promptDebugger", "chatId", "runId", "space-id"], "signal": "同chat AbortController，之后drawer effect会abort"},
]
interfaces = [
    {"method": "POST", "path": "/paiflow/runs", "permission": "paiflow:run:create", "request": ["workflowVersionId", "engineProfile", "input", "idempotencyKey", "timeoutSeconds"], "reply": ["runId", "workflowVersionId", "state", "attempt", "lastEventSeq", "revision", "deadline", "result"], "status": "源码已实现，不在本核对调用"},
    {"method": "GET", "path": "/paiflow/runs/{id}", "permission": "paiflow:run:query", "status": "源码已实现；space关系允许读取，未限制为run owner"},
    {"method": "GET", "path": "/paiflow/runs/{id}/events?after=0&limit=100", "permission": "paiflow:run:query", "constraints": "after>=0,1<=limit<=1000；按实际seq续读", "status": "源码已实现，JSON数组而非SSE"},
    {"method": "POST", "path": "/paiflow/runs/{id}/cancel", "permission": "paiflow:run:cancel", "status": "源码已实现；响应state未必已停止"},
    {"method": "GET", "path": "/paiflow/runs/{runId}/checkpoints/{checkpointId}", "permission": "paiflow:run:query", "status": "源码已实现；实际owner＋space＋当前attempt/lease归属"},
    {"method": "POST", "path": "/paiflow/runs/{runId}/checkpoints/{checkpointId}/reply", "permission": "paiflow:run:resume", "request": ["expectedRevision", "idempotencyKey", "event_type", "content"], "status": "源码已实现；一次性回复/CAS，不是新run"},
    {"method": "未装配", "path": "legacy /workflow/build,/workflow/add-comparisons,/workflow/chat,/workflow/resume 或标准 /stream", "status": "缺thin路由/绑定/游标dispatch；仅LegacyWorkflowStreamCodec存在"},
]
identities = [
    {"field": "workflow.id", "meaning": "中心工作流实体long", "authority": "中心实际space/WF/Agent绑定"},
    {"field": "origin flowId / flow_id", "meaning": "原工作流稳定字符串别名", "authority": "中心requireFlow与固定version关联，不作runId"},
    {"field": "chatIdRef / chatId", "meaning": "原前端uuid去横线，多轮对话可复用，停止/清对话会刷新", "authority": "仅客户端显示/关联标签，不作运行幂等或正式App/chat scope"},
    {"field": "comparison version", "meaning": "原uuid()带横线，对比临时图标签", "authority": "不能当中心workflowVersionId、版本名或发布状态"},
    {"field": "workflowVersionId", "meaning": "中心不可变版本实体long", "authority": "runtimeSnapshot核canonical bytes/hash/实际workflow+space"},
    {"field": "runId", "meaning": "中心真实一次运行UUID", "authority": "所有事件、begin、lease、CP及result共同归属"},
    {"field": "eventId", "meaning": "原event_data.event_id问答中断标识", "authority": "新projection只投中心实际checkpointId；不能从data.id/sid/nodeId推导"},
    {"field": "checkpointId", "meaning": "中心一次问答等待实体标识", "authority": "绑定run/lease/attempt/node/ordinal，原UI未携带的revision与key需适配"},
    {"field": "data.id / chatInfoRef.sid", "meaning": "原流帧sid，现可投实际runId", "authority": "不能替代checkpointId，也不能被错误用作源flowId"},
]
recommendations = [
    {"order": 1, "scope": "中心build与比较receipt", "proposal": "以同一WorkflowVersion/编译/固定hash为快照真相。普通build在当前真实space/WF/Agent锁与expected revision CAS下保存提交画布并生成不可变debug快照；comparison复用编译和版本存储内部步骤但不覆盖当前草稿/选中版本。保留原画布字节，范围仅top五pure＋静态QA/V3。缺能力或未准入图503/0run/0operation，不调用原远端build。", "reviewPoint": "公开createVersion有selectVersion副作用；内部helper必须复用验证而非复制第二套规则。build重复请求的快照幂等关联需存同一版本真相，不能每次网络重试多建版本。"},
    {"order": 2, "scope": "receipt字段与当前transport兼容", "proposal": "build响应保留data.id/flowId/spaceId/revision＋匹配ETag；独立debugReceipt描述workflowVersionId/dslHash/engineProfile/executionCodec以及来源mode/comparisonId。receipt仅指向中心快照，不是费用、发布或App授权。服务端以实际版本记录重新核全部关联，不能信任浏览器echo或metadata。若需要额外不可伪造receipt，须有真实中心绑定记录或已配置签名能力，缺项明确拒绝，不造默认token/密钥。", "reviewPoint": "实际WorkflowVersion目前没有独立debug mode/comparisonId绑定，需明确保存位置；不能只把客户端UUID塞进普通可写metadata当server事实。"},
    {"order": 3, "scope": "原React最小调用适配", "proposal": "沿api(params).then(receipt)=>runDebugger这一既有调用链传本次receipt与本次运行key；普通/比较mode都从同次已读状态取值。不建按flowId/global latestbuild缓存；receipt只活在该调试请求上下文，session/space/workflow/drawer切换失效。直接fetch路径补同次space上下文并核echo，避免Axios build选space而SSE无space歧义。界面、原节点组件、ReactFlow/store框架保留。", "reviewPoint": "当前原三fetch无receipt、key、space header；纯后端无法从普通chat恢复哪个build是本次来源。当前revisiontransport要求build响应workflow字段。"},
    {"order": 4, "scope": "chat创建一次＋附着同run", "proposal": "同次用户运行意图生成8..128运行key，首chat用receipt固定version及原inputs调用现RunService.create；相同body/key重复POST只返回/附着同run，同key不同version/input/timeout409。chatId只关联显示。显式用户regen/新轮次分配新key；断线/库timer/隐藏切换保同key，不删除对话或重建运行。build成功不表示原引擎或Provider已运行成功。", "reviewPoint": "单次成本/批准仍由当前Run/Model权威，不用receipt自授；本批静态QA无需正式App但不造App/chat绑定。"},
    {"order": 5, "scope": "SSE只投已持久事实", "proposal": "新增thin dispatch读取既有runs.events，以实际seq作SSE id，核同run的Last-Event-ID/after单调游标，读取当前run与真实checkpoint行交既有LegacyWorkflowStreamCodec。只对已投影帧交data；被过滤的辅助事件仅内部推进扫描，勿新增原UI无法消费的cursor JSON。连接重开不调用Factory、不claim新任务；空EOF/本地close没有成功含义，正式run.*终态才投flow stop。终态以后只续读既有结果。", "reviewPoint": "目前没有SSE路由；snapshot/resources/iteration限制与codec保持，不把模型图投影支持推定为已具备。会员角色可读Run events不等于可回复CP，旧调试receipt建议绑定当前发起者/实际space并重复鉴权。"},
    {"order": 6, "scope": "resume转换为一次性CP reply＋原run续读", "proposal": "将eventId核为该receipt/run的真实checkpointId，并核origin flowId/comparison关联。取真实WAITING checkpoint revision；本次回复key及expectedRevision必须在首次发送前固定，重复SSE POST使用完全同请求回执，不能重读较新revision后重发回复。以原eventType/content调用现reply，再附着原run游标，不新建run、不二次begin，不造metadata。空串ignore与needReply真实校验，option传原token，顺序/DEFAULT由原算法保留。", "reviewPoint": "不按flowId查最新问答、不从nodeId猜CP、不同内容重复key409。已RESUMED/CONSUMED/EXPIRED/CANCELLED不重开等待。当前reply不返回原消息，而状态projection须来自已持久事件。"},
    {"order": 7, "scope": "用户停止及原abort缺content", "proposal": "普通用户停止显式调用既有Run.cancel，以独立短请求避免被同流AbortController取消，并继续按权威state确认停止。问答abort保留原23802/FAILED业务语义；若兼容旧缺content请求，仅在精确eventType=abort时补空字符串给现reply，resume/ignore缺content仍400。此兼容需明确记录原缺口，不能把abort改成正式CANCELLED。先等实际reply ACK再关闭原流或独立发送，不能只本地abort认为中心收到。", "reviewPoint": "原setFlowResult.status清空触发effect立即abort同controller。transport失败或停流未知保留未确认状态，不伪造成功、费用0或Provider停止。"},
]
tests_needed = [
    "两tab/同flow并行build，响应反序，ordinary与comparison各自receipt绑定；旧revision/space/session/Agent解绑拒绝且0run。",
    "comparison不改draft/选中版本；原画布bytes/hash与固定V3执行副本分开核；重复build key不创建额外快照。",
    "重复chat POST、onerror-abort仍触发timer、相同body Last-Event-ID重连均同run/一次begin；key不同input或version409。",
    "空EOF不投stop；node完成只paintnode stop，choices.finish_reason null保留；只有官方run terminal投flow stop。",
    "reply同key完全相同内容幂等；跨run/跨space/旧attempt/不同内容/新revision/已消费均拒，取消/到期并发不重开。",
    "needReply=false仍真实等待；空ignore/default/option token与原顺序；原abort23802/FAILED并核旧缺content最窄兼容。",
    "非中断停止真实cancel请求不被SSE signal取消；CANCEL_REQUESTED不冒称CANCELLED；租约未知不伪成功。",
    "unsupported model/history/QA处理模型/iteration/resource图503且0run/operation；缺正式App不产生假发布/上传/接口App。",
]
after = {name: digest(ROOT / name) for name in SOURCES}
changed = [name for name in SOURCES if before[name] != after[name]]
result = {
    "documentKind": "既有checks内只读证据与候选接续建议，不是第二套设计/任务/运行状态",
    "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
    "scope": {"productionSourceWrites": 0, "evidenceCheckFilesCreated": 4, "contractWrites": 0, "targetWrites": 0, "mavenRuns": 0, "qaOrProviderTests": 0, "realHttp": 0, "database": 0, "servicesStarted": 0, "browserRuns": 0, "actualLibraryMemoryCases": 3},
    "authoritativeDesignReference": "文档/项目/PaiFlow工作流项目/完整迁移设计.md 原前端SSE最小适配及静态QA调试接续；本checks仅记录来源/候选，设计由root唯一维护",
    "sourceWindow": {"meaning": "本报告生成器读取来源hash的前后窗口，不冒称此前整个调查窗口无变化", "count": len(SOURCES), "changed": changed, "before": before, "after": after},
    "facts": facts,
    "originalRequests": requests,
    "centreInterfaces": interfaces,
    "idNamespaces": identities,
    "actualMemoryProbe": {"file": str(CHECKS / probe_name), "sha256": digest(CHECKS / probe_name), "passed": 3, "failureBaselineMeaning": "三项证明原生命周期风险/事实，不是业务链通过", "result": probe},
    "candidateReceiptBinding": recommendations,
    "meaningfulChecksForImplementation": tests_needed,
    "limits": ["没有修改原React/Python/Java/共享contract。", "没有运行页面、中心HTTP、实际用户run/CP、Provider、DB或已通过QA全套。", "原comparison状态来源缺口与stop effect时序为源码推断，未把推断冒称页面实际结果。", "只有已安装fetch-event-source3项内存生命周期探针为本次实际执行。", "当前公开SSE路由及receipt关联未实施；可复用Service/Codec不等于已联机。"],
}
assert not changed
destination = CHECKS / "原调试构建聊天续接只读核对.json"
destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"report": str(destination), "sha256": digest(destination), "sources": len(SOURCES), "changed": changed, "sourceFacts": len(facts), "requestShapes": len(requests), "candidateSteps": len(recommendations), "actualMemoryCases": 3}, ensure_ascii=False))
