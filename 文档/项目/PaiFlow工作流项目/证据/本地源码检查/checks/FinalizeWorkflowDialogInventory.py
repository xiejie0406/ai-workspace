"""单次同步调试显示历史三条索引边界；不运行Maven、服务、DB或业务检查。"""
from datetime import datetime, timezone
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
spec = importlib.util.spec_from_file_location("prior_inventory_helpers", Path(__file__).with_name("FinalizePureIterationStreamInventory.py"))
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
OLD_FREEZE = OUT / "纯迭代调试流接口索引冻结核对.json"
FREEZE = OUT / "调试显示历史接口索引冻结核对.json"
BACKUP = OUT / "checks/调试显示历史索引更新前原文"


def main():
    assert not BACKUP.exists() and not FREEZE.exists(), "保全和生成只执行一次；失败须诊断且不覆盖旧证据"
    assert sha(INV) == "a334a474f3d6586e111ff669f15b41de6fa84da6ce72fc3c7b1ec0eebf67f611"
    assert sha(OLD_FREEZE) == "4e8fbdc2e82228a10dfb5b691419a2f2f6f85570e5d71f0a4480e798dc22d3ea"
    previous, previous_freeze = read(INV), read(OLD_FREEZE)
    before = current_sets()
    assert tuple(len(before[k]) for k in ("java", "frontend", "supporting")) == (109, 562, 93)
    introduced_before, frontend_counts = front_import_check()
    result_path = OUT / "调试显示历史八类定向检查结果.json"
    ledger_path = OUT / "调试显示历史检查用例沿用核对.json"
    technical, ledger = read(result_path), read(ledger_path)
    assert technical["totals"] == {"tests": 97, "failures": 0, "errors": 0, "skipped": 0}
    assert technical["sourceInputCount"] == 227 and technical["sourceWindowChanges"] == []
    assert ledger["currentTests"] == 577
    assert (len(ledger["carriedUnchanged"]), len(ledger["rerunExisting"]), len(ledger["newlyExecuted"])) == (480, 74, 23)
    maven_inputs_path = OUT / "调试显示历史八类定向检查输入.json"
    maven_inputs = read(maven_inputs_path)["files"]
    assert len(maven_inputs) == 227
    assert all(sha(ROOT / path) == expected for path, expected in maven_inputs.items())
    # 六份索引相关原文、上一冻结parent及当前文档原字节；不碰root负责的追加目录。
    copies = [copy_once(path, BACKUP / path.name) for path in (GEN, CHECKER, INV, AUD, INDEX, FRONT_DIFF, OLD_FREEZE)]
    docs = [DOC / "完整迁移设计.md", DOC / "设计资料核对记录.md", ROOT / "ruoyi-backend/ruoyi-paiflow/README.md"]
    doc_copies = [copy_once(path, BACKUP / "当前文档原字节" / path.name) for path in docs]
    prep_path = BACKUP / "输入.json"
    dump(prep_path, {"preparedAt": datetime.now(timezone.utc).isoformat(), "scope": "显示历史索引更新前未提交原文；root可独立追加Doc26和README，本动作不编辑它们",
                     "copies": copies, "docsAtPrepare": doc_copies, "sourceSetsAtPrepare": before,
                     "frontendImport": frontend_counts, "priorFreeze": entry(OLD_FREEZE),
                     "mavenResult": entry(result_path), "identityLedger": entry(ledger_path), "businessVerified": False})
    status = "source_route_declared_business_and_runtime_unverified"
    boundaries = [
        ("POST", "/paiflow/legacy/workflow/dialog", status,
         "原8字段请求返回chatId字符串或null；type=1仅同域当前用户真实owned Run及固定版本关联，每POST追加一段显示记录，静态QA非终态亦可保存。客户端管理字段不授权，显示正文不成为Run结果；整体20MiB及单字段MEDIUMTEXT UTF8边界、H2事务和MVC已有技术检查。type=2正式App仍503，旧UID/Spark SID历史未导入，未验证真实登录/数据库/页面。", "service/WorkflowDialogService.java", "public String save("),
        ("GET", "/paiflow/legacy/workflow/dialog/list", status,
         "同域当前平台与工作流查询权限后仅本人type=1最近10条显示记录，按createTime DESC/id DESC稳定排序，返回原13 nullable字段且不泄内部Run/版本绑定。省略type仍核权限后返回空；type=2/App仍503，隔离Mapper/MVC不等于真实历史数据迁移或页面通过。", "service/WorkflowDialogService.java", "public List<View> list("),
        ("GET", "/paiflow/legacy/workflow/dialog/clear", status,
         "同域当前工作流编辑权限后只软删本人type=1显示记录，返回Integer实际删除计数，重复清除为0；Run/事件/检查点/正式结果不变。省略type仍核权限后返回0，type=2/App仍503，不能用该请求取消Run或清除其他成员历史；旧数据未导入。", "service/WorkflowDialogService.java", "public int clear("),
    ]
    original_generator = GEN.read_bytes()
    insertion = "".join("        " + repr(item) + ",\n" for item in boundaries).encode("utf-8")
    anchor = b'        ("GET", "/paiflow/legacy/textNode/config/list",'
    assert original_generator.count(anchor) == 1
    GEN.write_bytes(original_generator.replace(anchor, insertion + anchor))
    assert GEN.read_bytes().replace(insertion, b"") == original_generator
    with tempfile.TemporaryDirectory(prefix="paiflow-dialog-index-syntax-") as temp:
        py_compile.compile(str(GEN), cfile=str(Path(temp) / "generator.pyc"), doraise=True)
    started = datetime.now(timezone.utc).isoformat()
    generator = run_once([sys.executable, "-X", "utf8", "-B", "contracts/paiflow/generate-source-api-inventory.py"], OUT / "调试显示历史接口索引-生成.log")
    checker = run_once([sys.executable, "-X", "utf8", "-B", "contracts/paiflow/check-source-api-inventory.py", "--output", str(FREEZE)], OUT / "调试显示历史接口索引-冻结核对.log")
    current, check = read(INV), read(FREEZE)
    after = current_sets()
    assert before == after and check["status"] == "source_snapshot_consistent_business_unverified"
    assert check["currentInputCounts"] == {"java": 109, "frontend": 562, "supporting": 93, "supportingByKind": {"module_pom": 1, "SQL": 19, "resources": 16, "tests": 57}}
    originals = lambda value: {row["id"]: {k: v for k, v in row.items() if k not in {"migrationStatus", "targetMatches"}} for row in value["originalEndpoints"]}
    assert originals(previous) == originals(current) and len(current["originalEndpoints"]) == 389
    assert check["originalBaseline"] == previous_freeze["originalBaseline"]
    summary = current["summary"]
    assert (summary["frontendServiceExports"], summary["baseline23DirectCalls"], summary["targetPaiflowEndpointVariants"], summary["businessRuntimeVerifiedOriginalRoutes"]) == (365, 23, 175, 0)
    assert summary["baseline365Missing"] == summary["baseline365New"] == []
    route_map = lambda value: {(row["method"], row["path"]): row for row in value["targetPaiflowEndpoints"]}
    old_routes, new_routes = route_map(previous), route_map(current)
    expected_routes = {(b[0], b[1]) for b in boundaries}
    assert set(new_routes) - set(old_routes) == expected_routes and not set(old_routes) - set(new_routes)
    assert all(new_routes[key]["migrationStatus"] == row["migrationStatus"] for key, row in old_routes.items())
    assert all([b["description"] for b in new_routes[key].get("declaredImplementationBoundaries", [])] == [b["description"] for b in row.get("declaredImplementationBoundaries", [])] for key, row in old_routes.items())
    assert all(new_routes[key]["migrationStatus"] == status for key in expected_routes)
    old_sets = {"java": previous["targetSnapshotHashes"], "frontend": previous["targetFrontendSnapshotHashes"], "supporting": previous["targetSupportingSnapshotHashes"]}
    previous_deltas = {kind: delta(old_sets[kind], before[kind]) for kind in before}
    new_java = [item for item in previous_deltas["java"] if item["before"] is None]
    new_supporting = [item for item in previous_deltas["supporting"] if item["before"] is None]
    assert {Path(item["path"]).name for item in new_java} == {"WorkflowDialogRecord.java", "WorkflowDialogMapper.java", "WorkflowDialogService.java", "LegacyWorkflowDialogController.java"}
    assert {Path(item["path"]).name for item in new_supporting} == {"WorkflowDialogMapper.xml", "019-paiflow-workflow-dialog.sql", "WorkflowDialogPersistenceTest.java", "LegacyWorkflowDialogContractTest.java"}
    assert previous_deltas["frontend"] == []
    assert sha(FRONT_DIFF) == sha(BACKUP / FRONT_DIFF.name)
    assert all(sha(ROOT / path) == expected for path, expected in introduced_before.items())
    snapshot_copies = [copy_once(INV, OUT / "调试显示历史接口源码快照.json"), copy_once(AUD, OUT / "调试显示历史接口覆盖快照.json")]
    original_index = INDEX.read_bytes()
    paragraph = next(line for line in original_index.decode("utf-8-sig").splitlines() if line.startswith("当前调试接续源码快照："))
    replacement = "当前调试接续源码快照：机器清单保全原389条接口、68个接口来源文件、365个服务导出及23处直接请求基线；目标为175条路由、109个Java源、562个前端源/配置与93个Java支持文件。build、comparison、chat、resume、cancel和新增显示历史保存/查询/清除只标受限源码及隔离技术证据，业务验证仍为0；chat支持固定输入的单层纯迭代，resume仍仅静态QA，cancel只回报真实同次Run状态。显示历史type=1绑定本人真实Run和固定版本，每POST追加；本人最近10条保原13字段，清除只回本人显示记录实际计数，不更改Run、事件或检查点，静态QA非终态可保存。type=2、正式App及旧历史数据导入仍未接通，模型/资源混合、嵌套或问答迭代SSE仍未准入。上一172条路由快照原文保留；原React框架和页面布局继续沿用，引入1703个原文件中1691个逐字保留、12个为已有必要适配，本批前端新增差异为0。本批97项技术检查实际执行，当前577项身份为480沿用加97本轮执行，不表示577全量重跑或业务通过。详见[机器接口覆盖核对](附录/接口覆盖核对.json)、[本批冻结核对](证据/本地源码检查/调试显示历史接口索引冻结核对.json)、[定向技术检查结果](证据/本地源码检查/调试显示历史八类定向检查结果.json)、[原显示历史合同金样](证据/本地源码检查/checks/工作流原调试对话历史合同金样.json)、[实际MVC响应](证据/本地源码检查/调试显示历史实际MVC响应.json)和[原前端实际差异](附录/原前端接入差异.json)。"
    assert original_index.count(paragraph.encode("utf-8")) == 1
    INDEX.write_bytes(original_index.replace(paragraph.encode("utf-8"), replacement.encode("utf-8")))
    assert INDEX.read_bytes().replace(replacement.encode("utf-8"), paragraph.encode("utf-8")) == original_index
    links = re.findall(r"\[[^\]]+\]\(([^)]+)\)", replacement)
    bad_links = [link for link in links if not (INDEX.parent / link).resolve().exists()]
    assert not bad_links and current_sets() == before
    check.update({
        "finalizedAt": datetime.now(timezone.utc).isoformat(),
        "execution": {"generator": generator, "checker": checker, "generatorExecutions": 1, "checkerExecutions": 1, "testsExecutedByThisIndexAction": 0, "syntaxOutput": "private TemporaryDirectory only"},
        "retainedPreviousSnapshot": {"preparation": entry(prep_path), "priorFreezeCopy": entry(BACKUP / OLD_FREEZE.name), "routes": 172, "java": 105, "frontend": 562, "supporting": 89},
        "currentSnapshotCopies": snapshot_copies,
        "metadataChange": {"addedRouteBoundaries": [new_routes[key]["declaredImplementationBoundaries"][0] for key in sorted(expected_routes)], "existingRouteStatusesAndDescriptionsUnchanged": True, "allStatusesBusinessUnverified": True, "index": entry(INDEX), "onlyOneIndexParagraphChanged": True},
        "deltaFromPreviousSnapshot": previous_deltas, "addedJavaSources": new_java, "addedSupportingSources": new_supporting,
        "frontendImport": frontend_counts,
        "readAndGenerationWindow": {"startedAt": started, "endedAt": datetime.now(timezone.utc).isoformat(), "counts": {kind: len(values) for kind, values in before.items()}, "sourceChanges": {kind: delta(before[kind], after[kind]) for kind in before}, "originalAndIntroducedFrontendFilesChecked": 3406, "frontendImportWindowChanges": [], "sameAsMaven227Window": False},
        "docsSnapshotsAtPrepare": doc_copies,
        "docsOwnerBoundary": "root may independently append unique Doc26 and README; this index action does not modify either or treat those approved document changes as Java/front/support read-window drift",
        "carriedTechnicalEvidence": {"mavenResult": entry(result_path), "identityLedger": entry(ledger_path), "mavenSourceInputs": 227, "mavenSourceWindowChanges": [], "currentTestIdentities": 577, "carriedUnchanged": 480, "freshTests": 97, "rerunExisting": 74, "newTests": 23, "mavenExecutionsByThisIndexAction": 0},
        "actualArtifacts": [entry(OUT / "调试显示历史实际MVC响应.json"), entry(OUT / "调试显示历史实际大答案预算.json")],
        "localIndexLinks": {"checked": len(links), "bad": bad_links},
        "limits": ["仅显示记录持久化，非正式App会话、Run结果或变量真相。", "无旧UID/Spark SID历史导入；type2明确503。", "当前577=480沿用+97本轮执行，索引动作未运行Maven/测试。", "源码/AST、H2/MVC及原消费者证据均不能替代真实MySQL、JWT、Redis、Provider、浏览器验收、发布或完整迁移。"],
        "businessVerified": False, "migrationComplete": False,
    })
    dump(FREEZE, check)
    result = {"freeze": entry(FREEZE), "inventory": entry(INV), "audit": entry(AUD), "index": entry(INDEX), "counts": check["currentInputCounts"], "routes": 175, "originalRoutes": 389, "addedBoundaries": 3, "generatorExecutions": 1, "checkerExecutions": 1, "sourceWindowChanges": check["readAndGenerationWindow"]["sourceChanges"], "deltaCountsFromPrevious": {kind: len(items) for kind, items in previous_deltas.items()}, "frontendNewChanges": 0, "localLinksBad": len(bad_links), "businessVerified": False}
    dump(OUT / "checks/调试显示历史索引局部同步结果.json", result)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
