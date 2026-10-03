"""补充既有接口冻结检查的执行与来源证据；不操作编译产物或业务数据。"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import py_compile
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[6]
EVIDENCE = Path(__file__).resolve().parent.parent


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def changes(before: dict, after: dict):
    return [{"path": p, "before": before.get(p), "after": after.get(p)}
            for p in sorted(before.keys() | after.keys()) if before.get(p) != after.get(p)]


def main():
    inventory_path = ROOT / "contracts/paiflow/source-api-inventory.json"
    audit_path = ROOT / "文档/项目/PaiFlow工作流项目/附录/接口覆盖核对.json"
    check_path = EVIDENCE / "纯迭代画布编译接口索引冻结核对.json"
    result, current = read(check_path), read(inventory_path)
    previous_path = EVIDENCE / "实时权限模型公开配置接口源码快照.json"
    previous = read(previous_path)
    historical = read(EVIDENCE / "实时权限模型公开配置接口索引冻结核对.json")
    assert sha(previous_path) == historical["inventorySha256"]
    assert result["status"] == "source_snapshot_consistent_business_unverified"
    originals = lambda d: {e["id"]: {k: v for k, v in e.items() if k not in {"migrationStatus", "targetMatches"}} for e in d["originalEndpoints"]}
    old, new = originals(previous), originals(current)
    added, removed = sorted(new.keys() - old.keys()), sorted(old.keys() - new.keys())
    facts_changed = sorted(p for p in old.keys() & new.keys() if old[p] != new[p])
    assert not (added or removed or facts_changed)
    snapshot_files = []
    for source, name in ((inventory_path, "纯迭代画布编译接口源码快照.json"), (audit_path, "纯迭代画布编译接口覆盖快照.json")):
        target = EVIDENCE / name
        shutil.copyfile(source, target)
        snapshot_files.append({"path": name, "sha256": sha(target), "role": "378项全模块离线检查后、167入口冻结时点原文保全"})
    syntax_inputs = [ROOT / "contracts/paiflow" / name for name in ("generate-source-api-inventory.py", "check-source-api-inventory.py")]
    with tempfile.TemporaryDirectory(prefix="paiflow-inventory-syntax-") as temporary:
        for i, source in enumerate(syntax_inputs):
            py_compile.compile(str(source), cfile=str(Path(temporary) / f"syntax-{i}.pyc"), doraise=True)
    manifest_path = EVIDENCE / "纯迭代与画布编译联合回归输入.json"
    manifest = read(manifest_path)
    manifest_changes = [{"path": v["path"], "before": v["sha256"], "after": sha(ROOT / v["path"])} for v in manifest["inputs"] if sha(ROOT / v["path"]) != v["sha256"]]
    expected = [v for v in manifest_changes if v["path"] == "contracts/paiflow/source-api-inventory.json"]
    unexpected = [v for v in manifest_changes if v["path"] != "contracts/paiflow/source-api-inventory.json"]
    assert not unexpected, unexpected
    stage_path = ROOT / "python/paiflow-runtime/verification/pure-iteration-stage.json"
    stage = read(stage_path)
    python_inputs = []
    for path, expected_hash in stage["sourceSha256"].items():
        source = ROOT / "python/paiflow-runtime" / path
        python_inputs.append({"path": source.relative_to(ROOT).as_posix(), "recordedSha256": expected_hash, "currentSha256": sha(source)})
    for path, entry in stage["unchangedFrozen"].items():
        source = ROOT / "python/paiflow-runtime" / path
        python_inputs.append({"path": source.relative_to(ROOT).as_posix(), "recordedSha256": entry["after"], "currentSha256": sha(source)})
    source = (stage_path.parent.parent / stage["sharedFixture"]["path"]).resolve()
    python_inputs.append({"path": source.relative_to(ROOT).as_posix(), "recordedSha256": stage["sharedFixture"]["sha256"], "currentSha256": sha(source)})
    python_differences = [v for v in python_inputs if v["recordedSha256"] != v["currentSha256"]]
    assert not python_differences, python_differences
    full_result = read(EVIDENCE / "纯迭代与画布编译联合回归结果.json")
    assert full_result["summary"] == {"tests": 378, "failures": 0, "errors": 0, "skipped": 0}
    frontend_baseline = ROOT / "文档/项目/PaiFlow工作流项目/附录/前端源码完整性核对.json"
    result.update({
        "execution": {
            "generatorCommand": "python -X utf8 -B contracts/paiflow/generate-source-api-inventory.py",
            "generatorExitCode": 0,
            "generatorLog": "纯迭代画布编译接口索引-冻结生成.log",
            "generatorLogSha256": sha(EVIDENCE / "纯迭代画布编译接口索引-冻结生成.log"),
            "checkCommand": "python -X utf8 -B contracts/paiflow/check-source-api-inventory.py --output 文档/项目/PaiFlow工作流项目/证据/本地源码检查/纯迭代画布编译接口索引冻结核对.json",
            "checkExitCode": 0,
            "checkLog": "纯迭代画布编译接口索引-冻结核对.log",
            "checkLogSha256": sha(EVIDENCE / "纯迭代画布编译接口索引-冻结核对.log"),
            "syntaxMethod": "py_compile.compile(..., doraise=True)，输出仅在TemporaryDirectory；没有覆盖联合回归记录内的原pyc",
            "syntaxInputs": [{"path": p.relative_to(ROOT).as_posix(), "sha256": sha(p)} for p in syntax_inputs],
            "syntaxExitCode": 0,
            "workingDirectory": str(ROOT),
        },
        "frozenSnapshotCopies": snapshot_files,
        "originalFactsComparedWithHistoricalSnapshot": {"path": previous_path.name, "sha256": sha(previous_path), "entries": len(new), "added": added, "removed": removed, "changedFacts": facts_changed, "ignoredOnly": ["migrationStatus", "targetMatches"]},
        "originalFrontendCompletenessBaseline": {"path": frontend_baseline.relative_to(ROOT).as_posix(), "sha256": sha(frontend_baseline), "modified": False, "serviceExports": 365, "outsideServiceCalls": 23, "missing": current["summary"]["baseline365Missing"], "new": current["summary"]["baseline365New"], "manualReviewRequired": current["summary"]["baseline23ManualReviewRequired"]},
        "targetDeltaFromPrevious167": {
            "previousTargetRoutes": previous["summary"]["targetPaiflowEndpointVariants"], "currentTargetRoutes": current["summary"]["targetPaiflowEndpointVariants"],
            "java": changes(previous["targetSnapshotHashes"], current["targetSnapshotHashes"]),
            "frontend": changes(previous["targetFrontendSnapshotHashes"], current["targetFrontendSnapshotHashes"]),
            "supporting": changes(previous["targetSupportingSnapshotHashes"], current["targetSupportingSnapshotHashes"]),
        },
        "regressionManifestReconciliation": {"path": manifest_path.name, "sha256": sha(manifest_path), "count": len(manifest["inputs"]), "approvedInventoryGeneration": expected, "unexpectedDifferences": unexpected, "originalManifestNotOverwritten": True},
        "pythonIterationStageReconciliation": {"path": stage_path.relative_to(ROOT).as_posix(), "sha256": sha(stage_path), "inputs": python_inputs, "count": len(python_inputs), "unexpectedDifferences": python_differences, "testScope": "247项由Python执行；本次只独立核冻结输入，未另跑247项，也无真实中心/页面/Provider验证"},
        "technicalEvidence": {"javaFullModule": {"path": "纯迭代与画布编译联合回归结果.json", "sha256": sha(EVIDENCE / "纯迭代与画布编译联合回归结果.json"), "execution": "纯迭代与画布编译-联合回归执行.json", "executionSha256": sha(EVIDENCE / "纯迭代与画布编译-联合回归执行.json"), "suites": len(full_result["suites"]), **full_result["summary"]}, "independentWorkerReview": {"path": "纯迭代Worker独立差分复核.json", "sha256": sha(EVIDENCE / "纯迭代Worker独立差分复核.json")}, "businessVerified": 0},
        "generatorHashes": read(audit_path)["generatorHashes"],
    })
    write(check_path, result)
    print(json.dumps({"status": result["status"], "snapshot": check_path.relative_to(ROOT).as_posix(), "sha256": sha(check_path), "originalEntries": len(new), "currentInputCounts": result["currentInputCounts"], "targetRoutes": current["summary"]["targetPaiflowEndpointVariants"], "manifestUnexpectedDifferences": len(unexpected), "pythonUnexpectedDifferences": len(python_differences)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
