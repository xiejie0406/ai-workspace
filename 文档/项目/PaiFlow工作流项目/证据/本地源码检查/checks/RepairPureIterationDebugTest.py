"""仅修新H2测试错表名并加真实输入保全断言，保首554与1079源码，不运行检查。"""
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[6]
checks = Path(__file__).resolve().parent
source_path = "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/LegacyPureIterationDebugPersistenceTest.java"
source = root / source_path
old_gate = checks.parent / "纯迭代调试流真实持久化源码冻结输入.json"
result = checks.parent / "纯迭代调试流首轮全模块检查结果.json"
saved = checks / "纯迭代调试流首轮失败测试修复前原文"
new_gate = checks.parent / "纯迭代调试流H2表名与输入断言修复冻结输入.json"
if saved.exists() or new_gate.exists():
    raise RuntimeError("首失败修复证据已存在，禁止覆盖")
raw = source.read_bytes()
assert hashlib.sha256(raw).hexdigest() == "1079eddac4a1b25d53a273b767bef4d529cad6cc4cbbcfcac903a0eb01657ada"
assert hashlib.sha256(old_gate.read_bytes()).hexdigest() == "de0e2330f9dbbf92106cf20e0365de271fcb6f9769fdbf6a864460ff73ee4cf7"
saved.mkdir()
for p in (source, old_gate, result):
    (saved / p.name).write_bytes(p.read_bytes())
    assert (saved / p.name).read_bytes() == p.read_bytes()
text = raw.decode("utf-8")
assert text.count('rows("paiflow_checkpoint")') == 2
text = text.replace('rows("paiflow_checkpoint")', 'rows("paiflow_run_checkpoint")')
needle = '        assertNotNull(task);assertEquals(attachment.runId(),task.get("runId"));'
assert text.count(needle) == 1
assertion = '        assertEquals(RunJsonBoundary.canonicalFull(body.getJSONObject("inputs")),RunJsonBoundary.canonicalFull(task.getJSONObject("input")),"HTTP或直接路径的数组、null及falsy输入必须与实际领取任务一致");'
newline = "\r\n" if "\r\n" in text else "\n"
text = text.replace(needle, assertion + newline + needle)
source.write_bytes(text.encode("utf-8"))
old_methods = re.findall(r"@Test\s+void\s+(\w+)\s*\(", raw.decode("utf-8"))
new_methods = re.findall(r"@Test\s+void\s+(\w+)\s*\(", text)
assert old_methods == new_methods and len(new_methods) == 8
prior = json.loads(old_gate.read_text(encoding="utf-8"))
inputs = []
for entry in prior["inputs"]:
    current = (root / entry["path"]).read_bytes()
    current_sha = hashlib.sha256(current).hexdigest()
    if entry["path"] != source_path:
        assert current_sha == entry["sha256"]
    inputs.append({"path": entry["path"], "sha256": current_sha, "bytes": len(current), "testCount": entry["testCount"]})
record = {
    "schemaVersion": "paiflow.local-source-freeze.v1", "frozenAt": datetime.now(timezone.utc).isoformat(),
    "status": "candidate repair frozen; no Maven/JUnit execution by this agent",
    "inputs": inputs,
    "priorGate": {"path": old_gate.relative_to(root).as_posix(), "sha256": hashlib.sha256(old_gate.read_bytes()).hexdigest()},
    "firstFullResult": {"path": result.relative_to(root).as_posix(), "sha256": hashlib.sha256(result.read_bytes()).hexdigest(), "reportedTests": 554, "reportedPass": 550, "reportedFailure": 1, "reportedError": 3},
    "savedFirstSource": {"path": (saved / source.name).relative_to(root).as_posix(), "sha256": hashlib.sha256(raw).hexdigest()},
    "changes": ["仅两处行数断言表名paiflow_checkpoint修为真实paiflow_run_checkpoint", "claimAndBegin比较actual task.input与body.inputs完整canonicalFull，覆盖HTTP/direct数组/null/falsy保全"],
    "methodsUnchanged": new_methods,
    "notChanged": "原四owned源码、原24帧fixture与全部生产源码未改；HTTP输入生产边界由Root单独修复；首轮错误与失败保留，不改低标准。",
    "executionLimits": {"MavenRuns": 0, "JUnitRuns": 0, "javacRuns": 0, "targetWrites": 0}
}
new_gate.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"gate": new_gate.relative_to(root).as_posix(), "gateSha256": hashlib.sha256(new_gate.read_bytes()).hexdigest(), "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(), "methods": len(new_methods), "firstResultSha256": hashlib.sha256(result.read_bytes()).hexdigest()}, ensure_ascii=True))
