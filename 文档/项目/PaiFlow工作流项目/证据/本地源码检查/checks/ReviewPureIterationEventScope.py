"""源外只读评估：读取冻结代码和已保存原回调，不导入引擎、不运行Java或访问数据库。"""
import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[6]
checks = Path(__file__).resolve().parent
source_paths = [
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/PureIterationPolicy.java",
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunLeaseService.java",
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunService.java",
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowStreamCodec.java",
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyDebugBridgeService.java",
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyDebugStreamService.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/RunLeasePolicyTest.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/RunPersistenceContractTest.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/PureIterationRunPersistenceTest.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/WorkflowDebugTestFixture.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/LegacyDebugBridgePersistenceTest.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowStreamCodecTest.java",
    "python/paiflow-runtime/worker/engine_adapter.py",
    "python/paiflow-runtime/worker/node_context.py",
    "python/paiflow-runtime/workflow/engine/nodes/iteration/iteration_node.py",
    "python/paiflow-runtime/workflow/engine/callbacks/callback_handler.py",
    "python/paiflow-runtime/verification/pure-iteration-java-assignment-check.json",
    "contracts/paiflow/fixtures/pure-iteration.json",
    "contracts/paiflow/events.schema.json",
    "文档/项目/PaiFlow工作流项目/证据/本地源码检查/纯迭代原画布中心实际领取任务-重试夹具修正后.json",
    "文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/原纯迭代实际回调原React消费独立核对.json",
]
sha = lambda b: hashlib.sha256(b).hexdigest()
before = {p: sha((root / p).read_bytes()) for p in source_paths}
artifact_path = source_paths[-2]
task = json.loads((root / artifact_path).read_text(encoding="utf-8-sig"))
original = json.loads((root / source_paths[16]).read_text(encoding="utf-8-sig"))
frames = original["originalFactory"]["originalCallbackFrames"]
nodes = {n["id"]: n for n in task["dsl"]["nodes"]}
next_nodes = {n: set() for n in nodes}
for e in task["dsl"]["edges"]:
    next_nodes[e["sourceNodeId"]].add(e["targetNodeId"])
def reachable(start):
    pending, seen = [start], set()
    while pending:
        n = pending.pop()
        if n not in seen:
            seen.add(n)
            pending.extend(next_nodes[n])
    return seen
main_start = next(n for n in nodes if n.startswith("node-start::"))
owners = {n: "main" for n in reachable(main_start)}
batches = {}
for owner, node in nodes.items():
    if not owner.startswith("iteration::"):
        continue
    start = node["data"]["nodeParam"]["IterationStartNodeId"]
    for n in reachable(start):
        if n in owners:
            raise RuntimeError("已保存样例存在重复scope，不能静态关联")
        owners[n] = owner
    ref = node["data"]["inputs"][0]["schema"]["value"]["content"]
    if ref["nodeId"] != main_start:
        raise RuntimeError("样例不是原已准入Start直接数组")
    actual = task["input"][ref["name"]]
    if not isinstance(actual, list):
        raise RuntimeError("样例固定input不是数组")
    batches[owner] = len(actual)
rows, errors = [], []
for i, frame in enumerate(frames):
    raw = frame["raw"]
    node = raw["workflow_step"]["node"]
    node_id, tag = node["id"], frame.get("iterationId")
    owner = owners[node_id]
    if owner == "main":
        valid = tag is None
    else:
        prefix = owner + ":"
        suffix = tag[len(prefix):] if isinstance(tag, str) and tag.startswith(prefix) else ""
        valid = suffix.isascii() and suffix.isdecimal() and str(int(suffix)) == suffix and int(suffix) < batches[owner]
    valid = valid and raw["id"] == task["runId"]
    rows.append({"ordinal": i + 1, "nodeId": node_id, "iterationId": tag, "inferredOwner": owner, "validAgainstSavedInput": valid})
    if not valid:
        errors.append(rows[-1])
after = {p: sha((root / p).read_bytes()) for p in source_paths}
changes = [p for p in source_paths if before[p] != after[p]]
report = {
    "checkedAt": datetime.now(timezone.utc).isoformat(),
    "scope": "只读代码与已保存原24回调的字段关联；没有执行正式Java guard/Mapper/Engine/HTTP，不是业务验证",
    "sourceHashesBefore": before, "sourceHashesAfter": after, "sourceWindowChanges": changes,
    "actualSavedArtifact": {"path": artifact_path, "sha256": before[artifact_path], "originalDeadline": task["deadline"], "deadlineUnmodified": True, "notExecutionAuthority": True},
    "savedCallbackAssociations": {"count": len(frames), "nodeOwners": owners, "fixedBatchSizes": batches, "mainFrames": sum(r["inferredOwner"] == "main" for r in rows), "innerFrames": sum(r["inferredOwner"] != "main" for r in rows), "errors": errors, "rows": rows},
    "currentSourceFacts": [
        {"file": source_paths[0], "lines": [27, 28, 54, 87, 88, 90, 96, 100, 113, 145], "fact": "Budget四字段未含node归属；validate完整计算main/inner scope与固定数组批次，Scope是私有record，来源是IterationStartNodeId及去重连线，不是parentId。"},
        {"file": source_paths[1], "lines": [169, 174, 179, 180, 183, 188, 190], "fact": "append校协议/预算/租约/begin/节点存在和序号，但iterationId仅要求非空字符串，尚无owner/batch绑定。"},
        {"file": source_paths[6], "lines": [100, 109], "fact": "旧optionalEventIds测试明确接受非迭代图batch-0；不能不说明行为改变而删掉该assert。"},
        {"file": source_paths[3], "lines": [24, 30, 43, 78, 82, 105], "fact": "投影先全图拒三迭代类型；另外拒非null iterationId，辅助usage/reasoning直接不投影。checkpoint仍仅top-level QA，不应因纯迭代扩大。"},
        {"file": source_paths[4], "lines": [64, 72, 75, 78, 99, 113, 120, 122, 123, 137], "fact": "snapshot的二参门被chat/resume/cancel共用，page也在锁boundRun前调用二参门；开启后需要把固定input检查放到真实输入可得的位置，避免错误空input或令cancel仍503。"},
        {"file": source_paths[12], "lines": [34, 42, 43, 65, 69, 134], "fact": "原typed frame保nullable，worker私有tag随原排序队列到event外层；started用(nodeId,iterationId)区分，不将scope字段加到原公开raw。"},
    ],
    "minimumSharedRuleProposal": {
        "notImplemented": True,
        "budgetUnchanged": "保留现Budget(expandedNodes,pathCount,maxPathLength,batchSizes)及所有预算",
        "oneAnalysis": "把validate现主体提取唯一私有Analysis(Budget,nodeOwners)，validate仍返回Budget；新增requireEventScope(execution,input,nodeId,iterationId)共用Analysis，不建表/cache/第二truth。",
        "fixedSource": "只取Run.assignment.dsl与input；禁止current草稿、parentId、rawFrame、客户端batch作为owner依据。",
        "scope": "main含容器只能无iterationId；inner含start/end必须owner+':'+canonical无前导零十进制index且0<=index<batch。以已知owner前缀切后缀，不能首冒号split；禁止负号、+号、浮点、空白、超大数、嵌套'/'。emptybatch没有合法innerindex。",
        "ordinaryGraph": "无迭代图保节点存在原规则，不强迫旧start-only技术fixture跑新全图验证；但新事件main不得带iterationId。iterationId存在时nodeId不可缺，run-level无node无tag辅助事件按旧合同保留。",
        "firstWrite": "append拿run锁并完成同lease/实时权限/active/begin后，在新seq insertEvent/update之前校同scope；若legacyFrame存在，还应核其runId/nodeId与外层一致，不从frame推scope。",
        "replay": "既有seq原lease/attempt/canonical内容完全相同的receipt不产生写，保持历史幂等；新seq严格绑定。project对存量坏tag拒绝，不补tag/不重写旧事件。",
        "readDefense": "project在任何node/aux投影分支前对存在node/tag做同scope核对；terminal仅中心且不可含node/iteration伪装，不新设node生命周期顺序。",
        "debugGate": "新三参requireSupportedExecution(execution,resources,input)在完整PureIterationPolicy通过且无资源时开3迭代类型；原二参保持保守。chat input形状检查后、Run.create前调用；page从boundRun的固定assignment取input；cancel/resume移到boundRun后核固定assignment，不能留snapshot二参拒迭代挡住真实cancel。",
        "scopeLimit": "只Python现已准入单层五pure+三迭代；模型/QA嵌迭代/变量/外部schema/activeRetry仍503；checkpoint迭代规则不扩大。",
    },
    "testImpactAndExecutableEntries": [
        "RunLeasePolicyTest:109接受batch-0改为新seq400+neverInsert/revision不动，另普通无iterationId合法仍accepted；历史原exactReplay单独保receipt。",
        "PureIterationRunPersistenceTest已有真实Space/Workflow/RunMapper与RC代理、29项、typedcanvas版本→Run→claim/begin入口；优先复用该fixture测试missing/wrongowner/前导零/负号/overflow/idx==batch/emptybatch拒绝零event且lastSeq/revision不变，default/present覆盖及两个容器异长绑定。",
        "LegacyWorkflowStreamCodecTest当前wholeFixedGraphRejectsIteration拆分：合法单层/固定input通过，孤儿/交叉/nested/resource仍拒；保stop仅节点、choices.null、辅助事件不重复和中心run终态条件。",
        "WorkflowDebugTestFixture是真实AgentBinding/DefaultScope/版本夹具且无@Test；新独立test可复用ordinary typedcanvas→chat→claim/begin→24保存原frames经leases.append→bridge.page分页→真实result，保持scopeid/nodeId/raw order/聚合不变，不继承含@Test类重计。",
        "已保存24frames的raw.id属于旧run，未来新H2 run只显式rebind raw.id并将外层lease/attempt/seq换为实际新run；保其node.id、原iterationId、inputs/outputs/null/choices不变并保存投影，明确不是重跑Factory或真实Provider。",
        "已保存原帧ordinal6是主End started而inner joiner还未完成；不得新增串行批次或跨图生命周期顺序以制造通过。",
        "回归原Run事件gap/replay/事务回滚/取消/过期/权限撤销与RunJsonCodec NULL/V1/V2、旧Pure29及LegacyDebug桥/流；SSE只节点stop不terminal，真实RunResult最后一帧stop，取消不伪造回滚。",
    ],
    "validationPerformed": {"savedFrameAssociationOnly": True, "javaGuardExecuted": False, "MavenRuns": 0, "H2Connections": 0, "engineInvocations": 0, "targetWrites": 0, "javaSqlTestContractEdits": 0, "uniqueDesignOrRecordEdits": 0, "businessVerified": False},
}
out = checks / "单层纯迭代调试流作用域复用只读评估.json"
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
if changes or errors:
    raise RuntimeError("读取窗口或已保存帧关联不一致")
print(json.dumps({"evidence": str(out), "sha256": sha(out.read_bytes()), "sources": len(before), "changes": changes, "savedFrames": len(frames), "main": 6, "inner": 18, "scopeErrors": errors, "officialGuardExecuted": False}, ensure_ascii=True))
