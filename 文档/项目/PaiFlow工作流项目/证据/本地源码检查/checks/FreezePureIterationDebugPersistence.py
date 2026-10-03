"""保全新增H2测试/24帧fixture候选；确认先前四源码冻结未动，不编译不写target。"""
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[6]
checks = Path(__file__).resolve().parent
prior_gate = checks.parent / "纯迭代事件作用域源码冻结输入.json"
prior = json.loads(prior_gate.read_text(encoding="utf-8"))
saved = checks / "纯迭代真实持久化候选冻结原文"
gate = checks.parent / "纯迭代调试流真实持久化源码冻结输入.json"
if saved.exists() or gate.exists():
    raise RuntimeError("候选冻结证据已存在，禁止覆盖")
saved.mkdir()
inputs = []
for entry in prior["inputs"]:
    raw = (root / entry["path"]).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == entry["sha256"]
    inputs.append({"path": entry["path"], "sha256": entry["sha256"], "bytes": len(raw), "priorGate": prior_gate.relative_to(root).as_posix(), "testCount": entry["testCount"]})
new_paths = [
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/LegacyPureIterationDebugPersistenceTest.java",
    "contracts/paiflow/fixtures/pure-iteration-legacy-stream.json",
]
for p in new_paths:
    raw = (root / p).read_bytes()
    backup = saved / Path(p).name
    backup.write_bytes(raw)
    assert backup.read_bytes() == raw
    tests = re.findall(r"@Test\s+void\s+(\w+)\s*\(", raw.decode("utf-8-sig"))
    inputs.append({"path": p, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw), "savedSource": backup.relative_to(root).as_posix(), "testCount": len(tests), "testMethods": tests})
assert inputs[-2]["testCount"] == 8
fixture = json.loads((root / new_paths[1]).read_text(encoding="utf-8"))
for source in fixture["sources"]:
    assert hashlib.sha256((root / source["path"]).read_bytes()).hexdigest() == source["sha256"]
report = json.loads((root / fixture["sources"][0]["path"]).read_text(encoding="utf-8"))
assignment = json.loads((root / fixture["sources"][1]["path"]).read_text(encoding="utf-8"))
assert fixture["originalCallbackFrames"] == report["originalFactory"]["originalCallbackFrames"]
assert fixture["execution"] == assignment["dsl"] and fixture["input"] == assignment["input"]
assert len(fixture["originalCallbackFrames"]) == 24
ids = {n["id"] for n in fixture["execution"]["nodes"]}
assert all(f["raw"]["workflow_step"]["node"]["id"] in ids for f in fixture["originalCallbackFrames"])
read_only = [
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/WorkflowDebugTestFixture.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/StaticQuestionAnswerTestFixture.java",
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyDebugBridgeService.java",
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowStreamCodec.java",
]
record = {
    "schemaVersion": "paiflow.local-source-freeze.v1", "frozenAt": datetime.now(timezone.utc).isoformat(),
    "status": "candidate frozen; no Maven/JUnit execution; no actual SSE response artifact yet",
    "inputs": inputs,
    "priorGate": {"path": prior_gate.relative_to(root).as_posix(), "sha256": hashlib.sha256(prior_gate.read_bytes()).hexdigest(), "fourOwnedSourcesUnchanged": True},
    "readOnlyInputs": [{"path": p, "sha256": hashlib.sha256((root / p).read_bytes()).hexdigest()} for p in read_only],
    "candidateTests": {"RunLeasePolicyTest": 13, "PureIterationEventScopeTest": 13, "LegacyPureIterationDebugPersistenceTest": 8, "total": 34, "newVersusOldEightLeaseMethods": 26},
    "fixtureSourceChecks": {"exactOriginalClaimExecutionAndInput": True, "exactOriginalTwentyFourFrames": True, "allRawNodesBelongToSameOriginalGraph": True, "frames": 24, "sourceReportSha256": fixture["sources"][0]["sha256"], "sourceClaimSha256": fixture["sources"][1]["sha256"]},
    "executionScope": "继承无@Test的真实H2 WorkflowDebug/StaticQA夹具，实际Agent/WF/debugBuild/chat/claim/begin/append/page/result；调度和token仅受控边界，IO独立有限线程。旧坏行以明确H2故障种子保全，不冒充新append批准。",
    "actualArtifactToBeGeneratedOnlyByRootMavenTest": "ruoyi-backend/ruoyi-paiflow/target/pure-iteration-debug-actual-response.json",
    "expectedArtifactBoundary": "原24帧仅raw.id重绑本次真实Run，公开nodeID/顺序/step.seq/nullable不变；outer seq/lease/attempt来自新许可；正式result前不完成，第25中心终态才stop。无实际Python/Worker重新执行。",
    "executionLimits": {"MavenRuns": 0, "javacRuns": 0, "JUnitRuns": 0, "targetWrites": 0, "EngineRuns": 0, "liveDatabaseConnections": 0, "providerCalls": 0},
    "notChanged": "原四owned源/所有生产SQL/README/Doc25/前端未改；新增测试与可追溯fixture仅候选，不能称技术或业务通过。"
}
gate.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"gate": gate.relative_to(root).as_posix(), "sha256": hashlib.sha256(gate.read_bytes()).hexdigest(), "inputs": [{"path": i["path"], "sha256": i["sha256"], "testCount": i["testCount"]} for i in inputs], "candidateTests": record["candidateTests"]}, ensure_ascii=True))
