"""从既有实际Factory报告提取24原帧与当时固定执行图；不执行Engine。"""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[6]
checks = Path(__file__).resolve().parent
review = root / "文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/原纯迭代调试SSE来源独立核对.json"
evidence = json.loads(review.read_text(encoding="utf-8"))["actualExistingSample"]
report_path = root / evidence["path"]
report_raw = report_path.read_bytes()
assert hashlib.sha256(report_raw).hexdigest() == evidence["sha256"]
report = json.loads(report_raw)
assignment_path = Path(report["copyPath"])
assignment_raw = assignment_path.read_bytes()
assert hashlib.sha256(assignment_raw).hexdigest() == report["artifactSha256"]
assignment = json.loads(assignment_raw)
frames = report["originalFactory"]["originalCallbackFrames"]
assert len(frames) == 24
assert {frame["raw"]["id"] for frame in frames} == {assignment["runId"]}
assert sum(frame["iterationId"] is not None for frame in frames) == 18
assert all(frame["raw"]["choices"][0]["finish_reason"] is None for frame in frames)
fixture = root / "contracts/paiflow/fixtures/pure-iteration-legacy-stream.json"
if fixture.exists():
    raise RuntimeError("控制样例已存在，禁止覆盖")
value = {
    "schemaVersion": "paiflow.pure-iteration.legacy-stream.fixture.v1",
    "status": "existing actual OriginalFactory callbacks reused as controlled input; no new Python or Worker execution",
    "sources": [
        {"path": report_path.relative_to(root).as_posix(), "sha256": evidence["sha256"], "field": "originalFactory.originalCallbackFrames"},
        {"path": assignment_path.relative_to(root).as_posix(), "sha256": report["artifactSha256"], "fields": ["dsl", "input"]},
        {"path": review.relative_to(root).as_posix(), "sha256": hashlib.sha256(review.read_bytes()).hexdigest()},
    ],
    "replayBoundary": {
        "oldRunId": assignment["runId"],
        "rawFrameChange": "only raw.id is explicitly rebound to the newly created actual H2 Run ID; all other source-frame fields remain unchanged",
        "outerEventBinding": "new actual Run runId/leaseId/attempt, contiguous database seq and current isolated clock timestamp; iterationId copied from original private tag",
        "rawFrameSeq": "original workflow_step.seq=0 retained; SSE cursor comes from database runId:seq",
        "noEngineRerun": True, "noProvider": True, "noLiveCenter": True,
    },
    "execution": assignment["dsl"], "input": assignment["input"],
    "expectedOutput": report["originalFactory"]["outputs"],
    "originalCallbackFrames": frames,
    "budget": report["budget"],
}
fixture.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
saved = json.loads(fixture.read_text(encoding="utf-8"))
assert saved["originalCallbackFrames"] == frames
assert saved["execution"] == assignment["dsl"] and saved["input"] == assignment["input"]
record = {"fixture": fixture.relative_to(root).as_posix(), "sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
          "sourceReportSha256": evidence["sha256"], "sourceAssignmentSha256": report["artifactSha256"],
          "frames": len(frames), "rawFramesSemanticallyUnchanged": True,
          "newExecution": False, "sourceFilesChanged": False, "MavenRuns": 0, "targetWrites": 0}
proof = checks / "纯迭代原流控制样例提取检查.json"
if proof.exists():
    raise RuntimeError("提取证据已存在，禁止覆盖")
proof.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(record, ensure_ascii=True))
