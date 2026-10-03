"""只读保存原问答交互、原SSE与当前中心可复用事实；不声称已实现桥接。"""
from __future__ import annotations
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[6]
EVIDENCE = Path(__file__).resolve().parent.parent
FRONT = "参考项目/PaiFlow-main/console/frontend/src/"
TOOLKIT = "参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/"
COMMONS = "参考项目/PaiFlow-main/console/backend/commons/src/main/java/com/iflytek/astron/console/commons/"
CENTER = "ruoyi-backend/ruoyi-paiflow/src/main/"
WORKER = "python/paiflow-runtime/worker/"
SOURCES = {
    "originalFlowChat": FRONT + "components/workflow/store/flow-chat-function.ts",
    "currentFlowChat": "frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts",
    "originalFlowService": FRONT + "services/flow.ts",
    "originalFlowUtils": FRONT + "components/workflow/utils/index.ts",
    "currentFlowUtils": "frontend/paiflow-web/src/components/workflow/utils/index.ts",
    "originalChatContent": FRONT + "components/workflow/drawer/chat-debugger/components/chat-content.tsx",
    "originalChatDrawer": FRONT + "components/workflow/drawer/chat-debugger/index.tsx",
    "originalChatTypes": FRONT + "components/workflow/types/drawer/chat-debugger.ts",
    "originalController": TOOLKIT + "controller/workflow/WorkflowController.java",
    "originalWorkflowService": TOOLKIT + "service/workflow/WorkflowService.java",
    "originalChatRequest": TOOLKIT + "entity/biz/workflow/ChatBizReq.java",
    "originalResumeRequest": TOOLKIT + "entity/biz/workflow/ChatResumeReq.java",
    "originalChatResponse": TOOLKIT + "entity/core/workflow/sse/ChatResponse.java",
    "originalChoice": TOOLKIT + "entity/core/workflow/sse/Choice.java",
    "originalSseListener": TOOLKIT + "sse/WorkflowSseEventSourceListener.java",
    "originalJackson": TOOLKIT + "util/JacksonUtil.java",
    "originalSseUtil": COMMONS + "util/SseEmitterUtil.java",
    "originalPythonDebug": "参考项目/PaiFlow-main/core/workflow/api/v1/chat/debug.py",
    "originalPythonChatService": "参考项目/PaiFlow-main/core/workflow/service/chat_service.py",
    "runController": CENTER + "java/com/ruoyi/paiflow/controller/RunController.java",
    "checkpointController": CENTER + "java/com/ruoyi/paiflow/controller/CheckpointController.java",
    "runRecord": CENTER + "java/com/ruoyi/paiflow/domain/RunRecord.java",
    "runService": CENTER + "java/com/ruoyi/paiflow/service/RunService.java",
    "runLeaseService": CENTER + "java/com/ruoyi/paiflow/service/RunLeaseService.java",
    "checkpointService": CENTER + "java/com/ruoyi/paiflow/service/CheckpointService.java",
    "workflowService": CENTER + "java/com/ruoyi/paiflow/service/WorkflowService.java",
    "legacyWorkflowScope": CENTER + "java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowScopeFacade.java",
    "runMapper": CENTER + "resources/mapper/paiflow/RunMapper.xml",
    "workflowMapper": CENTER + "resources/mapper/paiflow/WorkflowMapper.xml",
    "checkpointMapper": CENTER + "resources/mapper/paiflow/CheckpointMapper.xml",
    "workerFrames": WORKER + "engine_adapter.py",
    "workerCheckpoints": WORKER + "checkpoints.py",
    "workerTaskRunner": WORKER + "task_runner.py",
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    before = {key: sha(ROOT / path) for key, path in SOURCES.items()}
    texts = {key: (ROOT / path).read_text(encoding="utf-8-sig").splitlines() for key, path in SOURCES.items()}

    def ref(key, marker):
        matching = [(i, line) for i, line in enumerate(texts[key], 1) if marker in line]
        if not matching:
            raise AssertionError((key, marker))
        i, line = matching[0]
        return {"source": key, "path": SOURCES[key], "line": i, "marker": marker, "text": line.strip()}

    facts = []

    def fact(identifier, statement, anchors, qualification="源码事实，未执行页面或正式中心业务"):
        facts.append({"id": identifier, "statement": statement, "references": [ref(*anchor) for anchor in anchors], "qualification": qualification})

    fact("debug-build-then-chat", "普通调试先提交原build数据；其成功回调再执行chat。原chat包含flow_id/inputs/chatId/regen，普通分支没有numeric workflowVersionId、runId、expectedRevision或idempotencyKey。", [("originalFlowService", "http.post('/workflow/build'"), ("originalFlowChat", "const url = getFixedUrl('/workflow/chat')"), ("originalFlowChat", "chatId: get().chatIdRef"), ("originalFlowChat", "api = buildFlowAPI")])
    fact("comparison-token", "历史调试先生成客户端UUID作为version，提交add-comparisons，再将同token和promptDebugger=true发chat/resume；它是原临时比较标识，不能直接当中心numeric版本ID。", [("originalFlowChat", "get().versionId = uuid()"), ("originalFlowChat", "api = addComparisons"), ("originalFlowChat", "params.promptDebugger = true"), ("originalController", '@PostMapping("/add-comparisons")')])
    fact("transport", "chat/resume/abort直接调用fetchEventSource，Content-Type=application/json，Bearer localStorage accessToken，openWhenHidden=true；不经过Axios请求拦截器，没有原space或If-Match条件头。getFixedUrl仅拼baseURL+path。", [("originalFlowChat", "fetchEventSource(url"), ("originalFlowChat", "Authorization: getAuthorization()"), ("originalFlowUtils", "return `${baseURL}${path}`"), ("currentFlowUtils", "return `Bearer ${localStorage.getItem('accessToken')}`")])
    fact("session-identifiers", "chatIdRef是无连字符UUID，用于一轮对话及原dialog/history关联；data.id另存sid；中断event_data.event_id另存eventId。三者用途不同，不能把chatId/SID/checkpointId合为同一标识。", [("originalFlowChat", "chatIdRef: uuid().replace"), ("originalFlowChat", "get().chatInfoRef.sid = data?.id"), ("originalFlowChat", "eventId: data?.['event_data']?.event_id"), ("originalPythonDebug", "event_id=str(get_id())")])
    fact("resume-payload", "resume body使用flow_id、eventId、eventType以及content；非空content选resume，空字符串选ignore；可追加version/promptDebugger。原direct输入/option按钮都复用此函数。", [("originalFlowChat", "const handleResumeChat"), ("originalFlowChat", "eventType: content ? 'resume' : 'ignore'"), ("originalChatContent", "handleResumeChat('')")])
    fact("option-real-consumer", "option实际展示并点击id/text/content_type，默认id=default被过滤；点击发option id。声明OptionItem的label/value不能作为真实wire契约。", [("originalChatContent", "handleResumeChat(item?.id)"), ("originalChatContent", "item?.content_type"), ("originalFlowChat", "item => item.id !== 'default'"), ("originalChatTypes", "interface OptionItem")])
    fact("abort-missing-content", "停止中断轮次发送eventType=abort但不含content；原Java resume断言content非null，因此原请求与服务自身冲突。允许的最小兼容是只对abort缺content补空字符串，resume/ignore依原及中心字段检查。", [("originalFlowChat", "eventType: 'abort'"), ("originalWorkflowService", "Assert.notNull(bizReq.getContent())"), ("checkpointService", "Set.of(\"resume\",\"ignore\",\"abort\")")])
    fact("abort-signal-race", "停止函数还立即更换chatId、清flowResult；drawer在flowResult为空时abort同一controller。普通流关闭/abort是浏览器连接终止，不能证明中心接受取消或Provider停止；中断abort POST可能与信号终止竞态。", [("originalFlowChat", "const handleStopConversation"), ("originalChatDrawer", "if (!flowResult?.status)"), ("originalChatDrawer", "controllerRef?.abort()"), ("runService", 'run.setState("CANCEL_REQUESTED")')], "源码推导的竞态可能性；未执行浏览器竞态复现")
    fact("original-sse-routes", "原Java chat/resume均POST，text/event-stream;charset=UTF-8，设置X-Accel-Buffering=no。原Python/debug/resume是实际实现，不能继续称原resume空端点。", [("originalController", '@PostMapping(path = "/chat"'), ("originalController", '@PostMapping(path = "/resume"'), ("originalController", '"X-Accel-Buffering"'), ("originalPythonDebug", "async def resume_debug")])
    fact("original-remote-ownership", "原chat按flow_id取workflow，使用workflow App AK/SK外调，并从已认证uid构造sysReq；resume按eventId在原EventRegistry取固定flow/app/uid/chatId，再写回复。正式迁移应复用中心run/checkpoint身份，不引入原AK/SK和第二Redis真相。", [("originalWorkflowService", "appService.remoteCallAkSk(workflow.getAppId())"), ("originalWorkflowService", "sysReq.setUid(uid)"), ("originalPythonDebug", "EventRegistry().get_event(event_id=event_id)"), ("originalPythonDebug", "span.uid = event.uid")])
    fact("sse-frame-branches", "页面只处理JSON data；choices[0].finish_reason===null执行节点进度，interrupt呈现问答，stop结束流程；21103单独走审核失败。正文/推理在choices.delta；workflow_step.node包含id/finish_reason/inputs/outputs/error_outputs/ext/usage/executed_time。", [("originalFlowChat", "if (!e.data || !isJSON(e.data)) return"), ("originalFlowChat", "flowResult === null"), ("originalFlowChat", "flowResult === 'interrupt'"), ("originalFlowChat", "flowResult === 'stop'"), ("originalFlowChat", "data?.code === 21103")])
    fact("sse-terminal-shape", "页面结束依据code=0成功，否则failed，读取executedTime和usage.total_tokens。原ChatResponse异常构造只有code=-1/message；没有choices.stop的错误帧不会进入原stop分支。桥接应根据真实中心run terminal持久事件投影安全legacy终帧，不能将连接EOF或任意Worker flow_obj帧当正式完成。", [("originalFlowChat", "status: data.code === 0 ? 'success' : 'failed'"), ("originalChatResponse", "public ChatResponse(String content)"), ("workerFrames", "正式 run.* 只由中心 CAS 生成"), ("runLeaseService", "static JSONObject terminalEvent")])
    fact("sse-data-not-done", "原workflow listener直接SseEmitter.send(ChatResponse)，中断后原Python返回当前stream，resume另开stream；普通流程终止由choices.stop。commons另有sendEndAndComplete命名end事件，但本次读取的WorkflowSseEventSourceListener未调用它，不能把[DONE]/end/complete当原页面协议要求。", [("originalSseListener", "SseEmitterUtil.sendMessage(sseId, chatResponse)"), ("originalPythonChatService", "if response and response.event_data"), ("originalSseUtil", "public static void sendEndAndComplete")], "listener源码未调用sendEndAndComplete；未断言整个项目不存在该工具调用")
    fact("null-projection", "Python原帧exclude_none=True会省略finish_reason=null，当前Worker legacyFrame也同样省略；页面严格null分支需桥按原声明形状显式呈现null。原Javaparse为typed ChatResponse且工具ALWAYS ObjectMapper存在，但实际MVC序列化空字段策略尚未联机核对。", [("originalPythonChatService", "response.model_dump(exclude_none=True)"), ("workerFrames", 'frame.model_dump(mode="json", exclude_none=True)'), ("originalFlowChat", "flowResult === null"), ("originalJackson", "ALWAYS_OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.ALWAYS)")], "前端严格比较与当前帧省略是源码事实；原Servlet converter实际wire未经业务抓包")
    fact("single-run-truth", "当前RunRecord/Mapper已有workflowId/workflowVersionId/spaceId/ownerUserId、固定assignment、request幂等键、lease/attempt、eventseq、revision与正式result。events按runId+seq分页，适合SSE只读消费；现无chatId/build-ticket/session上下文字段或按chat/flow找run映射。", [("runRecord", "private Long workflowVersionId"), ("runMapper", '<select id="byRequest"'), ("runMapper", '<select id="events"'), ("runService", "public List<JSONObject> events")])
    fact("immutable-version-source", "工作流已有findByFlowId、version、latestVersion/versionByName及受控runtimeSnapshot。但latest和name查询本身不能证明原build/chat来自同一窗口，原comparison UUID也没有现成持久映射。", [("workflowMapper", '<select id="findByFlowId"'), ("workflowMapper", '<select id="versionByName"'), ("workflowMapper", '<select id="latestVersion"'), ("workflowService", "public RuntimeSnapshot runtimeSnapshot")])
    fact("checkpoint-durable-context", "中心checkpoint已有run/lease/attempt/node/ordinal、question、revision/deadline、回复幂等键/哈希与RESUMED/CONSUMED持久CAS。Worker返回该checkpoint UUID作为原event_id并验证中断帧与ACK一致；resume可由checkpoint关联run，再核workflow flow_id，不能由请求替换其归属。", [("checkpointMapper", '<select id="byPosition"'), ("checkpointService", 'value.setState("RESUMED")'), ("workerFrames", 'self.context.checkpoint_acks.get(event_data.get("event_id"))'), ("checkpointService", "只有运行发起者可处理此问答")])
    fact("checkpoint-read-condition", "标准reply须expectedRevision/idempotencyKey/event_type/content，并核当前platform权限、space、owner、run/lease/deadline/WAITING。legacy中断data本来只有eventId，桥应把当时已投影的checkpoint revision与seq/固定上下文持久关联；不能在写时回读最新revision填入旧回复。", [("checkpointService", 'Set.of("expectedRevision","idempotencyKey","event_type","content")'), ("checkpointService", 'expected!=value.getRevision()'), ("checkpointController", '@PreAuthorize("@ss.hasPermi(\'paiflow:run:resume\')")')])
    fact("current-qa-not-admitted", "Worker实际注册question-answer，但当前RunService只放行纯节点/单层纯迭代/固定spark-llm；其余503。存在checkpoint和原Node并不等于原页面已可派发QA。", [("workerFrames", '"question-answer"'), ("runService", "中心资源/运行适配尚未接通")])
    fact("execution-context-gap", "Worker可解析中心verify可选executionContext(flowId,userId,appId,chatId)，其中前三个非空；当前RunLease.verify未提供该对象。直接/option无模型的QA不能因此伪造正式App，模型/hist/global/node-variable等缺真实资源仍须明确拒绝。", [("workerTaskRunner", 'binding = lease.get("executionContext")'), ("workerTaskRunner", 'set(binding) != {"flowId", "userId", "appId", "chatId"}'), ("runLeaseService", 'public JSONObject verify'), ("workerFrames", "模型正式历史快照尚未接通")])
    fact("dialog-dependent-paths", "现原调试页还调用dialog保存/列表/清除、add/delete-comparisons和可选nextQuestionAdvice/发布同步。SSE+resume桥单独实现不能称整个原调试页面闭环。原regen删除latest workflowDialog不限定当前用户，迁移不应保留该跨用户副作用。", [("originalFlowService", "http.post('/workflow/dialog'"), ("originalFlowService", "`/workflow/dialog/list?workflowId="), ("originalController", '@PostMapping("/delete-comparisons")'), ("originalWorkflowService", "workflowDialogMapper.delete")])
    after = {key: sha(ROOT / path) for key, path in SOURCES.items()}
    differences = [{"source": key, "before": before[key], "after": after[key]} for key in before if before[key] != after[key]]
    proposals = [
        {"step": 1, "scope": "沿原 /workflow/build + /workflow/add-comparisons 形成受权限和已读revision保护的不可变version及持久build receipt；原flow_id先经唯一WorkflowMapper映射同域资源。", "condition": "普通chat不携receipt，纯后端不能无歧义对应同actor同flow并发窗口；不得使用latest/recent猜版本。需协调最小transport层发送固定receipt/每次请求key，保留组件/store/router/页面及原body形状，或明确拒绝无法固定版本的请求。"},
        {"step": 2, "scope": "Run单一中心真相保存原chatId与固定版本/actor/space的legacy上下文；如果使用辅助表，仅作为run/version foreign关联与传输cursor/receipt，不另建执行状态。", "condition": "一chatId可能多round、多run，不能永久唯一chatId→run；同输入重试与用户再次执行也不能靠相同chatId+hash猜测，须稳定的请求级幂等键。无真实App不得造AppID/secret或迁入原AK/SK。"},
        {"step": 3, "scope": "最薄POST chat/resume SSE Controller使用RunService/CheckpointService及RunMapper.events，按seq单调游标投影legacyFrame。只有真实center run.completed/failed/cancelled映射flow级stop；中断仅来自已持久checkpoint ACK匹配。", "condition": "每次订阅和续页重新核当前身份/功能/空间权限；等待期间不持DB事务或锁。数据预算/分页/超时严格，断线不生成成功；不拿模型usage或Worker停止声明冒充实际Provider结算/停止。"},
        {"step": 4, "scope": "resume eventId→checkpoint→run→version/workflow原flowId，严格匹配请求flow_id和可选comparison token；将原camel字段映射中心event_type，并仅abort缺content补空字符串。", "condition": "需原中断时已读revision，不可借最新服务端状态绕CAS；不同回复409、未知/过期410/404、外域403/404，幂等匹配真实持久key/hash。不因为abort HTTP返回或连接断开宣布CANCELLED。"},
        {"step": 5, "scope": "只先启用顶层、无模型/无history/无变量/无外部资源的QuestionAnswer直接与静态选项原语义；原算法保持，中心负责固定结构和许可预检。", "condition": "需要补真实Factory→center checkpoint回复→消费→run terminal和原消费者SSE回放检查。页面手工/真实MySQL/Provider仍另验，缺App/模型/通知等独立503边界保留。"},
    ]
    output = {"schemaVersion": "paiflow.original-qa-bridge-source-review.v1", "checkedAt": datetime.now(timezone.utc).isoformat(), "status": "readonly_source_review_bridge_not_implemented", "sourceHashes": [{"source": key, "path": path, "sha256": before[key]} for key, path in SOURCES.items()], "sourceWindowChanges": differences, "originalFlowChatByteIdenticalToCurrent": before["originalFlowChat"] == before["currentFlowChat"], "facts": facts, "minimalBridgeProposal": proposals, "noProductionSourceModified": True, "noMavenOrTargetWrite": True, "network": False, "realDatabaseWrites": False, "servicesStarted": False, "providerCalls": False, "businessVerified": False, "limitations": ["仅代码来源与最小桥接建议；未实现所列新路由/receipt/context。", "未运行页面、Servlet SSE抓包、真实MySQL或Provider；本JSON不是页面/业务通过证据。", "原SSE传输之外的构建/比较/对话/建议/发布依赖逐项保留，不能从中心run/checkpoint存在推断已迁好。"]}
    assert not differences, differences
    path = EVIDENCE / "原问答页面与中心续接来源核对.json"
    path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path), "sourceCount": len(SOURCES), "facts": len(facts), "windowChanges": differences, "flowChatUnchanged": output["originalFlowChatByteIdenticalToCurrent"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
