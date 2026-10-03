"""固定合法retry夹具复验后的当前索引，保留旧378索引与Factory失败。"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import py_compile
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[6]
EVIDENCE = Path(__file__).resolve().parent.parent


def read(p):
    return json.loads(p.read_text(encoding="utf-8-sig"))


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def entry(p):
    return {"path": p.relative_to(ROOT).as_posix(), "sha256": sha(p)}


def differences(before, after):
    return [{"path": key, "before": before.get(key), "after": after.get(key)} for key in sorted(before.keys() | after.keys()) if before.get(key) != after.get(key)]


def main():
    check_path = EVIDENCE / "纯迭代重试夹具接口索引冻结核对.json"
    current_path = ROOT / "contracts/paiflow/source-api-inventory.json"
    audit_path = ROOT / "文档/项目/PaiFlow工作流项目/附录/接口覆盖核对.json"
    current, check = read(current_path), read(check_path)
    old_check_path = EVIDENCE / "纯迭代画布编译接口索引冻结核对.json"
    old_check = read(old_check_path)
    assert sha(old_check_path) == "24bfc26d09bc35a105e9e3ad4aa7c3b91d2801c2794ff20413540dc74056d164"
    old_source_path = EVIDENCE / "纯迭代画布编译接口源码快照.json"
    old_source = read(old_source_path)
    assert sha(old_source_path) == old_check["inventorySha256"]
    assert check["status"] == "source_snapshot_consistent_business_unverified"
    original = lambda value: {e["id"]: {k: v for k, v in e.items() if k not in {"migrationStatus", "targetMatches"}} for e in value["originalEndpoints"]}
    assert original(old_source) == original(current)
    java_delta = differences(old_source["targetSnapshotHashes"], current["targetSnapshotHashes"])
    frontend_delta = differences(old_source["targetFrontendSnapshotHashes"], current["targetFrontendSnapshotHashes"])
    support_delta = differences(old_source["targetSupportingSnapshotHashes"], current["targetSupportingSnapshotHashes"])
    assert not java_delta and not frontend_delta
    fixture = "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/PureIterationRunPersistenceTest.java"
    assert support_delta == [{"path": fixture, "before": "102454368790edb0a9a8af1f4ab0eced0a43f63c0cd773bf51309a1e8416e272", "after": "d819d83dc59bec52ad5eb1eeff9959d493338ca84040f6669dcef31b294ab6b4"}]
    manifest_path = EVIDENCE / "纯迭代重试夹具定向复验输入.json"
    manifest = read(manifest_path)
    manifest_delta = [{"path": v["path"], "before": v["sha256"], "after": sha(ROOT / v["path"])} for v in manifest["inputs"] if sha(ROOT / v["path"]) != v["sha256"]]
    assert all(v["path"] == "contracts/paiflow/source-api-inventory.json" for v in manifest_delta)
    directed_path = EVIDENCE / "纯迭代重试夹具定向复验结果.json"
    directed = read(directed_path)
    assert directed["summary"] == {"tests": 29, "failures": 0, "errors": 0, "skipped": 0}
    cross_path = ROOT / "python/paiflow-runtime/verification/pure-iteration-java-assignment-check.json"
    cross = read(cross_path)
    assert cross["result"] == "PASS" and not cross["sourceChanged"] and not cross["taskRunnerCalled"] and not cross["runtimeAuthorityValidated"]
    assert cross["artifactSha256"] == "59507f49972d93e8d207c3e746c1caf7acd2b7981793303c7a202d346297e816"
    source_diff = []
    for source, expected in cross["sourceHashesAfter"].items():
        p = Path(source)
        if not p.is_absolute():
            p = ROOT / "python/paiflow-runtime" / p
        if sha(p) != expected:
            source_diff.append({"path": str(p), "recorded": expected, "current": sha(p)})
    assert not source_diff, source_diff
    syntax_inputs = [ROOT / "contracts/paiflow" / p for p in ("generate-source-api-inventory.py", "check-source-api-inventory.py")]
    with tempfile.TemporaryDirectory(prefix="paiflow-inventory-syntax-") as directory:
        for i, source in enumerate(syntax_inputs):
            py_compile.compile(str(source), cfile=str(Path(directory) / f"syntax-{i}.pyc"), doraise=True)
    copies = []
    for p, name in ((current_path, "纯迭代重试夹具接口源码快照.json"), (audit_path, "纯迭代重试夹具接口覆盖快照.json")):
        target = EVIDENCE / name
        shutil.copyfile(p, target)
        copies.append(entry(target))
    check.update({
        "execution": {"generatorCommand": "python -X utf8 -B contracts/paiflow/generate-source-api-inventory.py", "generatorExitCode": 0, "generatorLog": entry(EVIDENCE / "纯迭代重试夹具接口索引-冻结生成.log"), "checkCommand": "python -X utf8 -B contracts/paiflow/check-source-api-inventory.py --output 文档/项目/PaiFlow工作流项目/证据/本地源码检查/纯迭代重试夹具接口索引冻结核对.json", "checkExitCode": 0, "checkLog": entry(EVIDENCE / "纯迭代重试夹具接口索引-冻结核对.log"), "syntaxMethod": "py_compile.compile(..., doraise=True) 输出仅TemporaryDirectory，不覆盖旧manifest中的pyc", "syntaxInputs": [entry(p) for p in syntax_inputs], "syntaxExitCode": 0, "workingDirectory": str(ROOT)},
        "frozenSnapshotCopies": copies,
        "retainedHistorical378Snapshot": {**entry(old_check_path), "inventory": entry(old_source_path), "audit": entry(EVIDENCE / "纯迭代画布编译接口覆盖快照.json"), "modified": False},
        "deltaFromHistorical378Snapshot": {"java": java_delta, "frontend": frontend_delta, "supporting": support_delta, "originalFactsChanged": 0, "originalIdsChanged": 0, "targetRoutesChanged": 0},
        "regressionManifestReconciliation": {**entry(manifest_path), "inputCount": len(manifest["inputs"]), "approvedInventoryGeneration": manifest_delta, "unexpectedDifferences": [], "originalManifestNotOverwritten": True},
        "originalFrontendCompletenessBaseline": old_check["originalFrontendCompletenessBaseline"],
        "pythonIterationStageReconciliation": old_check["pythonIterationStageReconciliation"],
        "crossLanguageActualFactoryEvidence": {**entry(cross_path), "artifact": entry(EVIDENCE / "纯迭代原画布中心实际领取任务-重试夹具修正后.json"), "canonicalSha256": cross["canonicalSha256"], "budget": cross["budget"], "originalFactory": cross["originalFactory"], "sourceCount": len(cross["sourceHashesAfter"]), "sourceDifferences": source_diff, "runtimeAuthorityValidated": False, "taskRunnerCalled": False, "centerBeginCalls": 0, "centerResultCalls": 0, "centerEventCalls": 0, "centerOperationCalls": 0, "providerCalls": False, "realDatabaseWrites": False, "full247Rerun": False, "firstFailureRetained": entry(ROOT / "python/paiflow-runtime/verification/pure-iteration-java-assignment-first-failure.json")},
        "technicalEvidence": {"currentCombination": {"total": 378, "carriedForwardFromActual378FullRun": 349, "newlyExecutedDirected": 29, "isFullModuleRerun": False, "failures": 0, "errors": 0, "skipped": 0}, "priorActualFullRun": old_check["technicalEvidence"]["javaFullModule"], "actualDirectedResult": entry(directed_path), "actualDirectedExecution": entry(EVIDENCE / "纯迭代重试夹具-定向复验执行.json"), "businessVerified": 0},
        "generatorHashes": read(audit_path)["generatorHashes"],
        "finalizedAt": datetime.now(timezone.utc).isoformat(),
    })
    check_path.write_text(json.dumps(check, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"freeze": entry(check_path), "inventory": entry(current_path), "audit": entry(audit_path), "status": check["status"], "routes": current["summary"]["targetPaiflowEndpointVariants"], "originalEntries": len(current["originalEndpoints"]), "productionSourceDelta": len(java_delta), "testDelta": len(support_delta), "manifestUnexpectedDifferences": 0}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
