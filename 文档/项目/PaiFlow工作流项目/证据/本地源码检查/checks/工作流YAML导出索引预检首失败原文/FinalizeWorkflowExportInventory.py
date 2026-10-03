"""单次同步原工作流YAML导出路由；必须等root最终证据和开始信号，准备时不执行。"""
from datetime import datetime, timezone
import argparse
import importlib.util
import json
from pathlib import Path
import py_compile
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[6]
DOC = ROOT / "文档/项目/PaiFlow工作流项目"
OUT = DOC / "证据/本地源码检查"
spec = importlib.util.spec_from_file_location("existing_inventory_helpers", Path(__file__).with_name("FinalizePureIterationStreamInventory.py"))
prior = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prior)
sha, entry, read, dump, delta = prior.sha, prior.entry, prior.read, prior.dump, prior.delta
copy_once, current_sets, front_import_check, run_once = prior.copy_once, prior.current_sets, prior.front_import_check, prior.run_once
GEN = ROOT / "contracts/paiflow/generate-source-api-inventory.py"
CHECKER = ROOT / "contracts/paiflow/check-source-api-inventory.py"
INV = ROOT / "contracts/paiflow/source-api-inventory.json"
AUD = DOC / "附录/接口覆盖核对.json"
INDEX = DOC / "源码覆盖索引.md"
FRONT_DIFF = DOC / "附录/原前端接入差异.json"
OLD_FREEZE = OUT / "调试显示历史接口索引冻结核对.json"
FREEZE = OUT / "工作流YAML导出接口索引冻结核对.json"
BACKUP = OUT / "checks/工作流YAML导出索引更新前原文"
PREPARED = OUT / "checks/工作流YAML导出索引准备原文/保全输入.json"
HTTP = ROOT / "frontend/paiflow-web/src/utils/http.ts"
STATUS = "source_route_declared_business_and_runtime_unverified"
BOUNDARY = ("GET", "/paiflow/legacy/workflow/export/{id}", STATUS,
    "原Long内部ID只读当前草稿：现行workflow:query/真实空间与主体末次复核，默认域只读取已有表示；七字段flowMeta加dslVersion v1，原Biz typed已知字段与设计2.2画布未知/layout/null保全。仅副本清空节点nodeParam.uid非空String并附中文安全路径注释，秘密仍沿唯一JSON边界拒绝；32MiB wrapper及有界UTF8 YAML，不编译、建版本、派发或调用资源。实际隔离H2/Mapper/MVC字节及原下载消费者有技术证据，原导入、真实MySQL/JWT/Redis/浏览器下载尚未验证。",
    "service/WorkflowExportService.java", "public Export export(")


def path(value):
    result = Path(value)
    return result if result.is_absolute() else ROOT / result


def update_http_difference_record():
    """只替换http现SHA和追加reason；其余11条、旧正文/时间戳原字节保全。"""
    original = FRONT_DIFF.read_bytes()
    old = read(FRONT_DIFF)
    selected = [item for item in old["changes"] if item["path"] == "src/utils/http.ts"]
    assert len(selected) == 1 and len(old["changes"]) == 12
    item = selected[0]
    old_sha, new_sha = item["currentSha256"], sha(HTTP)
    assert old_sha != new_sha, "本批只有既有http适配修正，不能凭无变化描述增加差异"
    extra = "；原工作流附件下载仅新增application/json MIME拒绝，避免错误JSON被当YAML文件保存，保留原请求、框架和页面"
    old_reason = json.dumps(item["reason"], ensure_ascii=False).encode("utf-8")
    new_reason = json.dumps(item["reason"] + extra, ensure_ascii=False).encode("utf-8")
    assert original.count(old_sha.encode()) == 1 and original.count(old_reason) == 1
    revised = original.replace(old_sha.encode(), new_sha.encode()).replace(old_reason, new_reason)
    assert revised.replace(new_sha.encode(), old_sha.encode()).replace(new_reason, old_reason) == original
    FRONT_DIFF.write_bytes(revised)
    fresh = read(FRONT_DIFF)
    expected = json.loads(json.dumps(old))
    expected_item = next(row for row in expected["changes"] if row["path"] == "src/utils/http.ts")
    expected_item["currentSha256"] = new_sha
    expected_item["reason"] += extra
    assert fresh == expected
    return {"path": "src/utils/http.ts", "beforeSha256": old_sha, "afterSha256": new_sha,
            "reasonAppended": extra, "otherElevenAndAllPreviousMetadataUnchanged": True,
            "reversePatchExactlyRestoresOriginal": True, "record": entry(FRONT_DIFF)}


def main(args):
    assert args.execute_after_parent_release, "没有root开始信号时只准备脚本，不执行索引动作"
    assert not BACKUP.exists() and not FREEZE.exists(), "单次原文保全/生成；失败不得覆盖旧证据"
    assert sha(INV) == "69ee46090f0bac4b9750f2b9721a1610eca536bd4d2bc25f1143ea9d0a8306d9"
    assert sha(OLD_FREEZE) == "9d87e16ce78ab31f66fc209f30d43a22fb5fb40015cbe756739cc99ace5d7cf4"
    prepared = read(PREPARED)
    assert all(sha(ROOT / item["copy"]) == item["sha256"] for item in prepared["inputs"])
    previous, previous_freeze = read(INV), read(OLD_FREEZE)
    before = current_sets()
    assert tuple(len(before[k]) for k in ("java", "frontend", "supporting")) == (111, 562, 95)
    # root会提供最终成品路径；这些结果只沿用，不由本脚本运行或重写。
    ledger_path, first_path, repair_path, compile_path = map(path, (args.ledger, args.first_result, args.repair_result, args.compile_result))
    ledger, first, repair, compile_result = read(ledger_path), read(first_path), read(repair_path), read(compile_path)
    assert ledger["currentTests"] == 597
    assert (len(ledger["carriedUnchanged"]), len(ledger["rerunExisting"]), len(ledger["newlyExecuted"])) == (545, 32, 20)
    assert first["totals"] == {"tests": 52, "failures": 1, "errors": 0, "skipped": 0}
    assert repair["totals"] == {"tests": 1, "failures": 0, "errors": 0, "skipped": 0}
    assert first["sourceInputCount"] == 231 and first["sourceWindowChanges"] == repair["sourceWindowChanges"] == []
    assert compile_result["totals"]["tests"] == 0 and compile_result["exitCode"] != 0
    assert compile_result["sourceInputCount"] == 231 and compile_result["sourceWindowChanges"] == []
    assert ledger["actualTestExecutions"] == 53 and ledger["identityUnionProvenAgainstCurrentSource"] is True
    merged_path = OUT / "工作流YAML导出定向检查合并结果.json"
    merged = read(merged_path)
    assert merged["latestIdentityTotals"] == {"tests": 52, "failures": 0, "errors": 0, "skipped": 0}
    assert merged["actualTestExecutions"] == 53 and merged["freshUniqueIdentities"] == 52 and merged["whole52RerunAfterAssertionFix"] is False
    final_gate_path = path(args.final_gate)
    final_gate = read(final_gate_path)
    assert all(sha(ROOT / item["path"]) == item["sha256"] for item in final_gate["inputs"])
    artifact_path = OUT / "工作流YAML导出实际MVC响应.json"
    golden_path = OUT / "checks/工作流原YAML导出合同金样.json"
    artifact, golden = read(artifact_path), read(golden_path)
    assert artifact["actualStatus"] == 200 and artifact["beforeTruth"] == artifact["afterTruth"]
    assert artifact["bodyUTF8Bytes"] > 0 and artifact["businessVerified"] is False
    assert sha(golden_path) == "8e7b99afc4e99781d7d9c9bfeb4786f4339203623eabd854d7ff9fb3bb0e97c8"
    assert len(golden["fixtures"]) == 13
    build_path = OUT / "工作流YAML下载原React生产构建结果.json"
    build = read(build_path)
    assert build["exitCode"] == 0 and build["sourceInputCount"] == 1703 and build["sourceWindowChanges"] == [] and build["outputCount"] == 242
    range_path = OUT / "checks/工作流YAML导出局部范围保全核对.json"
    preserved = read(range_path)
    assert len(preserved["sourceHashesBefore"]) == 4095 and preserved["sourceReadWindowChanges"] == []
    assert preserved["previousJavaSourcesCompared"] == 109 and preserved["previousJavaChanges"] == []
    assert preserved["previousSupportingCompared"] == 93 and preserved["previousSupportingChanges"] == []
    assert preserved["runtimeCompared"] == 478 and preserved["runtimeChangesFromLast271Execution"] == []
    assert (preserved["originalFrontendImported"], preserved["originalFrontendByteIdentical"], preserved["existingAdaptedFrontendFiles"]) == (1703,1691,12)
    consumer_path = OUT / "checks/工作流YAML导出实际MVC原React下载消费核对.json"
    consumer = read(consumer_path)
    assert consumer["positiveDownloadEffectsEqual"] is True and consumer["sourceWindowChanges"] == []
    copies = [copy_once(item, BACKUP / item.name) for item in (GEN, CHECKER, INV, AUD, INDEX, FRONT_DIFF, OLD_FREEZE)]
    docs = [DOC / "完整迁移设计.md", DOC / "设计资料核对记录.md", ROOT / "ruoyi-backend/ruoyi-paiflow/README.md"]
    doc_copies = [copy_once(item, BACKUP / "当前文档原字节" / item.name) for item in docs]
    prep_path = BACKUP / "输入.json"
    dump(prep_path, {"preparedAt": datetime.now(timezone.utc).isoformat(), "copies": copies, "docsAtPrepare": doc_copies,
                    "sourceSetsAtPrepare": before, "priorFreeze": entry(OLD_FREEZE), "finalSourceGate": entry(final_gate_path),
                    "scope": "仅本批接口索引/原前端差异元数据；root独占唯一Doc27/README，本脚本不修改正文", "businessVerified": False})
    http_delta = update_http_difference_record()
    introduced_before, frontend_counts = front_import_check()
    original_generator = GEN.read_bytes()
    insertion = ("        " + repr(BOUNDARY) + ",\n").encode("utf-8")
    anchor = b'        ("POST", "/paiflow/legacy/workflow/dialog",'
    assert original_generator.count(anchor) == 1
    GEN.write_bytes(original_generator.replace(anchor, insertion + anchor))
    assert GEN.read_bytes().replace(insertion, b"") == original_generator
    with tempfile.TemporaryDirectory(prefix="paiflow-export-index-syntax-") as temp:
        py_compile.compile(str(GEN), cfile=str(Path(temp) / "generator.pyc"), doraise=True)
    started = datetime.now(timezone.utc).isoformat()
    generator = run_once([sys.executable, "-X", "utf8", "-B", "contracts/paiflow/generate-source-api-inventory.py"], OUT / "工作流YAML导出接口索引-生成.log")
    checker = run_once([sys.executable, "-X", "utf8", "-B", "contracts/paiflow/check-source-api-inventory.py", "--output", str(FREEZE)], OUT / "工作流YAML导出接口索引-冻结核对.log")
    current, check = read(INV), read(FREEZE)
    after = current_sets()
    assert before == after and check["status"] == "source_snapshot_consistent_business_unverified"
    assert check["currentInputCounts"] == {"java": 111, "frontend": 562, "supporting": 95,
            "supportingByKind": {"module_pom": 1, "SQL": 19, "resources": 16, "tests": 59}}
    originals = lambda value: {row["id"]: {k: v for k, v in row.items() if k not in {"migrationStatus", "targetMatches"}} for row in value["originalEndpoints"]}
    assert originals(previous) == originals(current) and len(current["originalEndpoints"]) == 389
    assert check["originalBaseline"] == previous_freeze["originalBaseline"]
    summary = current["summary"]
    assert (summary["frontendServiceExports"], summary["baseline23DirectCalls"], summary["targetPaiflowEndpointVariants"], summary["businessRuntimeVerifiedOriginalRoutes"]) == (365, 23, 176, 0)
    assert summary["baseline365Missing"] == summary["baseline365New"] == []
    routes = lambda value: {(row["method"], row["path"]): row for row in value["targetPaiflowEndpoints"]}
    old_routes, new_routes = routes(previous), routes(current)
    route = (BOUNDARY[0], BOUNDARY[1])
    assert set(new_routes) - set(old_routes) == {route} and not set(old_routes) - set(new_routes)
    assert all(new_routes[key]["migrationStatus"] == row["migrationStatus"] for key, row in old_routes.items())
    assert all([b["description"] for b in new_routes[key].get("declaredImplementationBoundaries", [])] == [b["description"] for b in row.get("declaredImplementationBoundaries", [])] for key, row in old_routes.items())
    assert new_routes[route]["migrationStatus"] == STATUS
    old_sets = {"java": previous["targetSnapshotHashes"], "frontend": previous["targetFrontendSnapshotHashes"], "supporting": previous["targetSupportingSnapshotHashes"]}
    previous_deltas = {kind: delta(old_sets[kind], before[kind]) for kind in before}
    assert {Path(item["path"]).name for item in previous_deltas["java"] if item["before"] is None} == {"WorkflowExportService.java", "LegacyWorkflowExportController.java"}
    assert {Path(item["path"]).name for item in previous_deltas["supporting"] if item["before"] is None} == {"WorkflowExportPersistenceTest.java", "LegacyWorkflowExportContractTest.java"}
    assert [item["path"] for item in previous_deltas["frontend"]] == ["frontend/paiflow-web/src/utils/http.ts"]
    assert all(sha(ROOT / rel) == expected for rel, expected in introduced_before.items())
    snapshot_copies = [copy_once(INV, OUT / "工作流YAML导出接口源码快照.json"), copy_once(AUD, OUT / "工作流YAML导出接口覆盖快照.json")]
    old_index = INDEX.read_bytes()
    paragraph = next(line for line in old_index.decode("utf-8-sig").splitlines() if line.startswith("当前调试接续源码快照："))
    replacement = "当前调试接续源码快照：机器清单保全原389条接口、68个接口来源文件、365个服务导出及23处直接请求基线；目标为176条路由、111个Java源、562个前端源/配置与95个Java支持文件。build、comparison、chat、resume、cancel和显示历史接口保留上一批受限声明；新增YAML export按内部Long ID只读当前草稿，同域查询权限与末次身份/成员核验，不编译、建版本、派发或调用Provider。原Biz已知字段类型与七字段flowMeta加v1保留，设计2.2合法未知/layout/null扩展保全；节点发起者UID只在副本清空并中文注记，秘密沿唯一边界拒绝，有界UTF8输出不扩大导入能力。type=2/App、旧历史导入及模型/资源混合、嵌套或问答迭代SSE仍未接通，业务验证为0。原React框架/页面继续沿用，引入1703个原文件仍1691逐字保留加12个已有适配；本批只修既有http附件JSON-MIME拒绝，其他11个适配不变。首Java编译0项执行后，52项检查有51通过/1附件头断言失败，该1项修后独立复验，共53次技术执行；当前597项身份为545沿用加52本批身份（32已有+20新增），不是597全量重跑或52一次全通过。详见[机器接口覆盖核对](附录/接口覆盖核对.json)、[本批冻结核对](证据/本地源码检查/工作流YAML导出接口索引冻结核对.json)、[定向检查身份合并结果](证据/本地源码检查/工作流YAML导出定向检查合并结果.json)、[原导出合同金样](证据/本地源码检查/checks/工作流原YAML导出合同金样.json)、[实际MVC字节](证据/本地源码检查/工作流YAML导出实际MVC响应.json)和[原前端实际差异](附录/原前端接入差异.json)。"
    assert old_index.count(paragraph.encode("utf-8")) == 1
    INDEX.write_bytes(old_index.replace(paragraph.encode("utf-8"), replacement.encode("utf-8")))
    assert INDEX.read_bytes().replace(replacement.encode("utf-8"), paragraph.encode("utf-8")) == old_index
    links = re.findall(r"\[[^\]]+\]\(([^)]+)\)", replacement)
    bad = [link for link in links if not (INDEX.parent / link).resolve().exists()]
    assert not bad and current_sets() == before
    check.update({"finalizedAt": datetime.now(timezone.utc).isoformat(),
        "execution": {"generator": generator, "checker": checker, "generatorExecutions": 1, "checkerExecutions": 1, "testsExecutedByThisIndexAction": 0},
        "retainedPreviousSnapshot": {"preparation": entry(prep_path), "priorFreezeCopy": entry(BACKUP / OLD_FREEZE.name), "routes": 175, "java": 109, "frontend": 562, "supporting": 93},
        "currentSnapshotCopies": snapshot_copies, "metadataChange": {"addedRouteBoundary": new_routes[route]["declaredImplementationBoundaries"][0], "existingRouteStatusesAndDescriptionsUnchanged": True, "allStatusesBusinessUnverified": True, "onlyOneIndexParagraphChanged": True, "index": entry(INDEX)},
        "deltaFromPreviousSnapshot": previous_deltas, "frontendImport": frontend_counts, "existingHttpAdapterUpdate": http_delta,
        "readAndGenerationWindow": {"startedAt": started, "endedAt": datetime.now(timezone.utc).isoformat(), "counts": {kind: len(values) for kind, values in before.items()}, "sourceChanges": {kind: delta(before[kind], after[kind]) for kind in before}, "originalAndIntroducedFrontendFilesChecked": 3406, "frontendImportWindowChanges": [], "sameAsMaven231Window": False},
        "docsSnapshotsAtPrepare": doc_copies, "earlierDoc26AndIndexPreparationRaw": entry(PREPARED), "docsOwnerBoundary": "root独占Doc27/README；准备阶段已保护当时Doc26和索引原文，本动作不编辑正文，后续批准文档变化不算Java/front/support读窗漂移",
        "carriedTechnicalEvidence": {"identityLedger": entry(ledger_path), "mergedLatestIdentities": entry(merged_path), "firstCompileFailure": entry(compile_path), "first52Result": entry(first_path), "oneMethodRepairResult": entry(repair_path), "finalSourceGate": entry(final_gate_path), "currentTestIdentities": 597, "carriedUnchanged": 545, "freshIdentities": 52, "rerunExistingIdentities": 32, "newIdentities": 20, "actualTestExecutions": 53, "mavenExecutionsByThisIndexAction": 0},
        "actualArtifacts": [entry(artifact_path), entry(golden_path), entry(consumer_path)], "existingFrontendBuildEvidence": entry(build_path), "independent4095SourceAnd478RuntimePreservation": entry(range_path), "localIndexLinks": {"checked": len(links), "bad": bad},
        "limits": ["只读当前草稿YAML导出，非原导入/App/资源迁移完成。", "隔离H2/Mapper/MVC与原DTO/下载AST不替代真实MySQL/JWT/Redis/浏览器/发布验收。", "597身份组合，首编译0执行、52首验51通过1失败、1项修后复验，实际53技术执行；索引动作不运行测试。"], "businessVerified": False, "migrationComplete": False})
    dump(FREEZE, check)
    result = {"freeze": entry(FREEZE), "inventory": entry(INV), "audit": entry(AUD), "index": entry(INDEX), "frontendDifference": entry(FRONT_DIFF), "counts": check["currentInputCounts"], "routes": 176, "originalRoutes": 389, "addedBoundaries": 1, "generatorExecutions": 1, "checkerExecutions": 1, "sourceWindowChanges": check["readAndGenerationWindow"]["sourceChanges"], "existingFrontendAdapterChanges": 1, "newFrontendAdaptationPaths": 0, "localLinksBad": len(bad), "businessVerified": False}
    dump(OUT / "checks/工作流YAML导出索引局部同步结果.json", result)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-after-parent-release", action="store_true")
    for option in ("ledger", "first-result", "repair-result", "compile-result", "final-gate"):
        parser.add_argument("--"+option, required=True)
    main(parser.parse_args())
