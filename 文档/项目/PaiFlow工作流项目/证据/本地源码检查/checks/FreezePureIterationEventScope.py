"""仅保存owned源码候选与读取哈希，检查原方法保留；不编译、不写target。"""
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[6]
checks = Path(__file__).resolve().parent
old = checks / "纯迭代事件作用域修改前原文"
saved = checks / "纯迭代事件作用域候选冻结原文"
gate = checks.parent / "纯迭代事件作用域源码冻结输入.json"
if saved.exists() or gate.exists():
    raise RuntimeError("本批冻结证据已存在，禁止覆盖")
paths = [
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/PureIterationPolicy.java",
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunLeaseService.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/RunLeasePolicyTest.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/PureIterationEventScopeTest.java",
]
saved.mkdir()
inputs = []
for p in paths:
    raw = (root / p).read_bytes()
    backup = saved / Path(p).name
    backup.write_bytes(raw)
    assert backup.read_bytes() == raw
    methods = re.findall(r"@Test\s+void\s+(\w+)\s*\(", raw.decode("utf-8-sig"))
    inputs.append({"path": p, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
                   "savedSource": backup.relative_to(root).as_posix(), "testMethods": methods,
                   "testCount": len(methods)})
old_tests = re.findall(r"@Test\s+void\s+(\w+)\s*\(", (old / "RunLeasePolicyTest.java").read_text(encoding="utf-8-sig"))
current_tests = inputs[2]["testMethods"]
assert len(old_tests) == 8 and len(current_tests) == 13 and len(inputs[3]["testMethods"]) == 13
assert set(old_tests).issubset(current_tests)
original_policy = (old / "PureIterationPolicy.java").read_text(encoding="utf-8-sig")
current_policy = (root / paths[0]).read_text(encoding="utf-8-sig")
original_body = original_policy.split("public static Budget validate(JSONObject execution,JSONObject input) {", 1)[1].split("    private static Scope scope", 1)[0]
current_body = current_policy.split("private static Analysis analyze(JSONObject execution,JSONObject input) {", 1)[1].split("    private static Scope scope", 1)[0]
expected_body = original_body.replace("return new Budget(expanded,paths,longest,Map.copyOf(batchSizes));", "return new Analysis(new Budget(expanded,paths,longest,Map.copyOf(batchSizes)),Map.copyOf(owner));")
assert current_body == expected_body
fixture = "contracts/paiflow/fixtures/pure-iteration.json"
record = {
    "schemaVersion": "paiflow.local-source-freeze.v1", "frozenAt": datetime.now(timezone.utc).isoformat(),
    "status": "candidate frozen; source-only checks; no Java compilation or JUnit execution",
    "inputs": inputs,
    "readOnlyInputs": [{"path": fixture, "sha256": hashlib.sha256((root / fixture).read_bytes()).hexdigest()}],
    "originalSourceEvidence": (old / "输入.json").relative_to(root).as_posix(),
    "sourceChecks": {"existingFullAnalysisBodyUnchangedExceptPrivateResult": True,
                     "allEightExistingLeaseTestMethodsRetained": True,
                     "oldExactStoredReplayBeforeNewScopeCheck": True,
                     "changedOldAcceptance": "原非迭代batch-0仅通过非空字段检查；本批新seq拒400，已存相同canonical历史回执不重写。"},
    "candidateTests": {"RunLeasePolicyTest": 13, "PureIterationEventScopeTest": 13,
                       "total": 26, "existingMethods": 8, "newMethods": 18,
                       "existingChangedBehaviorMethods": ["optionalEventIdsMustFollowPublishedSchemaWithoutCoercion"]},
    "interface": "public static void requireEventScope(JSONObject execution,JSONObject input,String nodeId,String iterationId)",
    "immutablePublicBudget": ["expandedNodes", "pathCount", "maxPathLength", "batchSizes"],
    "executionLimits": {"MavenRuns": 0, "javacRuns": 0, "JUnitRuns": 0, "targetWrites": 0,
                        "EngineRuns": 0, "databaseConnections": 0, "providerCalls": 0},
    "notChanged": "SQL/contract/README/唯一记录/前端/Root-owned LegacyCodec、Bridge、Controller与H2测试不在owned修改中；不声称技术或业务通过。"
}
gate.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"gate": gate.relative_to(root).as_posix(), "gateSha256": hashlib.sha256(gate.read_bytes()).hexdigest(),
                  "inputs": [{"path": i["path"], "sha256": i["sha256"], "testCount": i["testCount"]} for i in inputs],
                  "candidateTests": record["candidateTests"]}, ensure_ascii=True))
