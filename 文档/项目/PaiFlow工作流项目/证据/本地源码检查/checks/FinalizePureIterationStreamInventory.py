"""单次同步受限纯迭代调试流的索引说明；不运行服务、Maven或业务测试。"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import py_compile
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[6]
DOC = ROOT / "文档/项目/PaiFlow工作流项目"
OUT = DOC / "证据/本地源码检查"
BACKUP = OUT / "checks/纯迭代调试流索引更新前原文"
INV = ROOT / "contracts/paiflow/source-api-inventory.json"
AUD = DOC / "附录/接口覆盖核对.json"
GEN = ROOT / "contracts/paiflow/generate-source-api-inventory.py"
INDEX = DOC / "源码覆盖索引.md"
OLD_FREEZE = OUT / "调试接续接口索引冻结核对.json"
FREEZE = OUT / "纯迭代调试流接口索引冻结核对.json"
FIXTURE = ROOT / "contracts/paiflow/fixtures/pure-iteration-legacy-stream.json"
FRONT_DIFF = DOC / "附录/原前端接入差异.json"
RECORD = DOC / "设计资料核对记录.md"
README = ROOT / "ruoyi-backend/ruoyi-paiflow/README.md"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def entry(path):
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)}


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def delta(before, after):
    return [{"path": p, "before": before.get(p), "after": after.get(p)}
            for p in sorted(before.keys() | after.keys()) if before.get(p) != after.get(p)]


def copy_once(path, destination):
    assert not destination.exists(), "保全原文或阶段快照不得覆盖"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, destination)
    assert sha(path) == sha(destination)
    return entry(destination)


def current_sets():
    module = ROOT / "ruoyi-backend/ruoyi-paiflow"
    java = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted((module / "src/main/java").rglob("*.java"))}
    front = ROOT / "frontend/paiflow-web"
    frontend_files = [p for p in (front / "src").rglob("*")
                      if p.is_file() and p.suffix in {".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"}]
    frontend_files += [front / n for n in ("package.json", "package-lock.json", "index.html", "vite.config.ts", "tsconfig.json")
                       if (front / n).is_file()]
    frontend = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(frontend_files)}
    supporting_files = [p for folder in ("src/main/resources", "src/test", "sql")
                        for p in (module / folder).rglob("*") if p.is_file()] + [module / "pom.xml"]
    supporting = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(supporting_files)}
    return {"java": java, "frontend": frontend, "supporting": supporting}


def front_import_check():
    manifest = read(DOC / "附录/原前端引入清单.json")
    differences, missing, reference_changed = [], [], []
    before = {}
    for item in manifest["files"]:
        source = ROOT / manifest["source"] / item["path"]
        target = ROOT / manifest["target"] / item["path"]
        if not target.is_file():
            missing.append(item["path"])
            continue
        before[target.relative_to(ROOT).as_posix()] = sha(target)
        before[source.relative_to(ROOT).as_posix()] = sha(source)
        if sha(source) != item["sha256"]:
            reference_changed.append(item["path"])
        if sha(target) != item["sha256"]:
            differences.append({"path": item["path"], "currentSha256": sha(target), "originalSha256": item["sha256"]})
    recorded = read(FRONT_DIFF)
    assert len(manifest["files"]) == 1703 and not missing and not reference_changed
    assert len(differences) == 12 and 1703 - len(differences) == 1691
    assert {d["path"]: d["currentSha256"] for d in differences} == {d["path"]: d["currentSha256"] for d in recorded["changes"]}
    return before, {"filesIntroduced": 1703, "byteIdentical": 1691, "existingAdaptations": 12,
                    "missing": missing, "referenceChanged": reference_changed,
                    "adaptationPaths": [d["path"] for d in differences],
                    "manifest": entry(DOC / "附录/原前端引入清单.json"), "differenceRecord": entry(FRONT_DIFF)}


def run_once(command, log):
    assert not log.exists(), "单次生成日志不得覆盖"
    started = datetime.now(timezone.utc).isoformat()
    process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    log.write_text(process.stdout + process.stderr, encoding="utf-8")
    result = {"command": command, "startedAt": started, "endedAt": datetime.now(timezone.utc).isoformat(),
              "exitCode": process.returncode, "log": entry(log)}
    assert process.returncode == 0, result
    return result


def main():
    assert not BACKUP.exists() and not FREEZE.exists(), "此动作仅执行一次，失败后须保全诊断而非重新生成"
    assert sha(INV) == "6fd861415232ff0db7f0a71d76fdd5daa196cb5aca609d9240da59fc20f3103a"
    assert sha(RECORD) == "be5f149707ce9d2abedec190ddded5a5738ef3bea192fb9db1502de815c783e1"
    assert sha(FIXTURE) == "a694b3776c2b41d565b307df2c1d97f6fc12e4d455982ffb7cfa4f21f7b09792"
    previous, previous_freeze = read(INV), read(OLD_FREEZE)
    old_front_diff_sha = sha(FRONT_DIFF)
    before = current_sets()
    assert tuple(len(before[k]) for k in ("java", "frontend", "supporting")) == (105, 562, 89)
    imported_before, front_counts = front_import_check()
    protected = {p.relative_to(ROOT).as_posix(): sha(p) for p in (RECORD, README, FIXTURE, FRONT_DIFF)}
    technical_path = OUT / "纯迭代调试流本地源码检查.json"
    technical = read(technical_path)
    ledger_path = OUT / "纯迭代调试流两次检查用例沿用核对.json"
    ledger = read(ledger_path)
    assert technical["java"]["currentIdentityCount"] == ledger["currentTests"] == 554
    assert technical["java"]["carriedUnchangedFromFirst"] == len(ledger["carriedUnchanged"]) == 502
    assert technical["java"]["rerunAffected"] == len(ledger["rerunAffected"]) == 52
    assert technical["java"]["sourceInputCountEach"] == 219
    assert technical["java"]["sourceWindowChangesEach"] == [[], []]
    assert all(sha(ROOT / e["path"]) == e["sha256"] for e in technical["sourceInputs"])
    copies = [copy_once(p, BACKUP / p.name) for p in (INDEX, GEN, INV, AUD, OLD_FREEZE, FRONT_DIFF)]
    prep = {"preparedAt": datetime.now(timezone.utc).isoformat(), "scope": "单层纯迭代调试流3条说明更新前未提交原文保全",
            "copies": copies, "sourceSetsAtPrepare": before, "protected": protected,
            "frontendImportCheck": front_counts, "technicalSummary": entry(technical_path),
            "identityLedger": entry(ledger_path), "businessVerified": False}
    dump(BACKUP / "输入.json", prep)

    replacements = {
        "回执与固定版本/输入/requestKey创建或重读同一Run，SSE runId:seq及两个真实绑定头；仅纯顶层和静态QA，模型/资源/迭代全图503。隔离H2/MVC与原AST消费不等于真实JWT/Redis/HTTP/Worker/页面验收。":
        "回执与固定版本/输入/requestKey创建或重读同一Run，SSE runId:seq及两个真实绑定头；支持纯顶层、静态QA或固定输入的单层纯迭代。迭代以同一固定DSL/input核节点及owner:批次索引，24个原节点帧不制造全局结束，中心终态才完成；模型/资源混合、嵌套和迭代问答仍未准入。已有隔离H2/MVC与原消费者AST证据，未验证真实JWT/Redis/HTTP/Worker/页面。",
        "同run、真实CP、最初revision和固定replyKey核对；reply-only返回真实已受理状态，后续SSE仍同Run。WAITING须同时期限/租约有效才暂停EOF；ACK不是中心terminal，缺正式联机验证。":
        "仅静态QA核同run、真实CP、最初revision和固定replyKey；reply-only返回真实已受理状态，后续SSE仍同Run。单层纯迭代chat准入不包含迭代问答或resume；WAITING须同时期限/租约有效才暂停EOF，ACK不是中心terminal，缺正式联机验证。",
        "普通或comparison同次回执严格绑定真实Run，返回现有RunService状态；CANCEL_REQUESTED只表示请求受理，已terminal保真实状态，不伪造Provider停止或本地detach为取消。":
        "普通或comparison同次回执、固定快照与输入严格绑定真实Run，包含已准入的单层纯迭代，返回现有RunService状态；CANCEL_REQUESTED只表示请求受理，已terminal保真实状态，不伪造Provider/Worker已停止或本地detach为取消。",
    }
    original_generator = GEN.read_bytes()
    changed_generator = original_generator
    for old, new in replacements.items():
        old_bytes, new_bytes = old.encode("utf-8"), new.encode("utf-8")
        assert changed_generator.count(old_bytes) == 1
        changed_generator = changed_generator.replace(old_bytes, new_bytes)
    GEN.write_bytes(changed_generator)
    generator_metadata = [{"before": old, "after": new} for old, new in replacements.items()]
    with tempfile.TemporaryDirectory(prefix="paiflow-iteration-index-syntax-") as temp:
        py_compile.compile(str(GEN), cfile=str(Path(temp) / "generator.pyc"), doraise=True)
    read_started = datetime.now(timezone.utc).isoformat()
    generation_before = current_sets()
    assert generation_before == before
    generator = run_once([sys.executable, "-X", "utf8", "-B", "contracts/paiflow/generate-source-api-inventory.py"],
                         OUT / "纯迭代调试流接口索引-生成.log")
    checker = run_once([sys.executable, "-X", "utf8", "-B", "contracts/paiflow/check-source-api-inventory.py", "--output", str(FREEZE)],
                       OUT / "纯迭代调试流接口索引-冻结核对.log")
    current, check = read(INV), read(FREEZE)
    after = current_sets()
    assert before == after and check["status"] == "source_snapshot_consistent_business_unverified"
    assert check["currentInputCounts"] == {"java": 105, "frontend": 562, "supporting": 89,
                                          "supportingByKind": {"module_pom": 1, "SQL": 18, "resources": 15, "tests": 55}}
    originals = lambda d: {e["id"]: {k: v for k, v in e.items() if k not in {"migrationStatus", "targetMatches"}} for e in d["originalEndpoints"]}
    assert originals(previous) == originals(current) and len(current["originalEndpoints"]) == 389
    assert check["originalBaseline"] == previous_freeze["originalBaseline"]
    summary = current["summary"]
    assert (summary["frontendServiceExports"], summary["baseline23DirectCalls"], summary["targetPaiflowEndpointVariants"],
            summary["businessRuntimeVerifiedOriginalRoutes"]) == (365, 23, 172, 0)
    assert summary["baseline365Missing"] == summary["baseline365New"] == []
    route_map = lambda d: {(e["method"], e["path"]): e for e in d["targetPaiflowEndpoints"]}
    old_routes, new_routes = route_map(previous), route_map(current)
    assert old_routes.keys() == new_routes.keys()
    assert all(e["migrationStatus"] == old_routes[key]["migrationStatus"] for key, e in new_routes.items())
    scope_paths = {"/paiflow/legacy/workflow/chat", "/paiflow/legacy/workflow/resume", "/paiflow/legacy/workflow/cancel"}
    changed_descriptions = []
    for key, value in new_routes.items():
        old_boundaries = old_routes[key].get("declaredImplementationBoundaries", [])
        new_boundaries = value.get("declaredImplementationBoundaries", [])
        if [b["description"] for b in old_boundaries] != [b["description"] for b in new_boundaries]:
            changed_descriptions.append({"method": key[0], "path": key[1], "before": old_boundaries, "after": new_boundaries})
    assert {c["path"] for c in changed_descriptions} == scope_paths
    assert all("unverified" in new_routes[("POST", p)]["migrationStatus"] for p in scope_paths)
    old_sets = {"java": previous["targetSnapshotHashes"], "frontend": previous["targetFrontendSnapshotHashes"],
                "supporting": previous["targetSupportingSnapshotHashes"]}
    previous_deltas = {k: delta(old_sets[k], before[k]) for k in before}
    new_supporting = [d for d in previous_deltas["supporting"] if d["before"] is None]
    expected_added = {"WorkerRunControllerJsonBindingTest.java", "LegacyPureIterationDebugPersistenceTest.java", "PureIterationEventScopeTest.java"}
    assert {Path(d["path"]).name for d in new_supporting} == expected_added
    assert previous_deltas["frontend"] == []
    assert sha(FRONT_DIFF) == old_front_diff_sha
    assert all(sha(ROOT / p) == h for p, h in imported_before.items())
    assert all(sha(ROOT / p) == h for p, h in protected.items())
    snapshot_copies = [copy_once(INV, OUT / "纯迭代调试流接口源码快照.json"),
                       copy_once(AUD, OUT / "纯迭代调试流接口覆盖快照.json")]
    index_original = INDEX.read_bytes()
    paragraph = next(line for line in index_original.decode("utf-8-sig").splitlines() if line.startswith("当前调试接续源码快照："))
    new_paragraph = "当前调试接续源码快照：机器清单保全原389条接口、68个接口来源文件、365个服务导出及23处直接请求基线；目标为172条路由、105个Java源、562个前端源/配置与89个Java支持文件。build、comparison、chat、resume、cancel只标受限源码及隔离技术证据，业务验证仍为0；chat现支持固定输入的单层纯迭代，resume仍仅静态QA，cancel只回报真实同次Run状态。模型/资源混合、嵌套或问答迭代SSE与正式App仍未接通。原167条路由和上一调试快照原文保留，原React框架和页面布局继续沿用；当前引入1703个原文件中1691个逐字保留、12个为已授权必要适配。完整字段契约与真实联机不能由本索引推定。详见[机器接口覆盖核对](附录/接口覆盖核对.json)、[本批冻结核对](证据/本地源码检查/纯迭代调试流接口索引冻结核对.json)和[原前端实际差异](附录/原前端接入差异.json)。"
    assert index_original.count(paragraph.encode("utf-8")) == 1
    INDEX.write_bytes(index_original.replace(paragraph.encode("utf-8"), new_paragraph.encode("utf-8")))
    assert INDEX.read_bytes().replace(new_paragraph.encode("utf-8"), paragraph.encode("utf-8")) == index_original
    links = re.findall(r"\[[^\]]+\]\(([^)]+)\)", new_paragraph)
    bad_links = [link for link in links if not (INDEX.parent / link).resolve().exists()]
    assert not bad_links
    after_index = current_sets()
    assert before == after_index and all(sha(ROOT / p) == h for p, h in protected.items())
    check.update({
        "finalizedAt": datetime.now(timezone.utc).isoformat(),
        "execution": {"generator": generator, "checker": checker, "generatorExecutions": 1, "checkerExecutions": 1,
                      "workingDirectory": str(ROOT), "syntaxOutput": "private TemporaryDirectory only; no target or shared pyc writes"},
        "retainedPreviousDebugSnapshot": {"preparation": entry(BACKUP / "输入.json"),
                                          "priorFreezeCopy": entry(BACKUP / OLD_FREEZE.name),
                                          "java": 105, "frontend": 562, "supporting": 86, "routes": 172},
        "currentSnapshotCopies": snapshot_copies,
        "metadataChange": {"generatorDescriptions": generator_metadata, "routeBoundaries": changed_descriptions,
                           "migrationStatusesUnchanged": True, "newRoutes": [], "removedRoutes": [],
                           "index": entry(INDEX), "onlyOneIndexParagraphChanged": True},
        "deltaFromPreviousDebugSnapshot": previous_deltas,
        "addedSupportingTestSources": new_supporting,
        "independentContractFixture": {**entry(FIXTURE), "notIncludedInSupportingSet": True,
                                       "reason": "沿既有resources/tests/sql/pom支持集合；contracts控制样例单独列证，不扩分母"},
        "frontendImport": front_counts,
        "readAndGenerationWindow": {"startedAt": read_started, "endedAt": datetime.now(timezone.utc).isoformat(),
                                    "counts": {k: len(v) for k, v in before.items()},
                                    "sourceChanges": {k: delta(before[k], after_index[k]) for k in before},
                                    "originalAndIntroducedFrontendFilesChecked": 3406,
                                    "frontendImportWindowChanges": [],
                                    "protectedUnchanged": [entry(ROOT / p) for p in protected],
                                    "sameAsMaven219Window": False},
        "carriedTechnicalEvidence": {"summary": entry(technical_path), "identityLedger": entry(ledger_path),
                                     "currentTestIdentities": 554, "carriedFromFirst": 502, "affectedRerun": 52,
                                     "mavenSourceInputsEach": 219, "mavenSourceWindowChangesEach": [[], []],
                                     "testsExecutedByThisIndexAction": 0, "businessVerified": 0},
        "localIndexLinks": {"checked": len(links), "bad": bad_links},
        "limits": ["仅chat单层纯迭代固定DSL/input准入；resume仍静态QA，未接迭代问答。",
                   "cancel真实同次Run关联与CANCEL_REQUESTED不能证明Worker或Provider已停止。",
                   "当前554是502沿用+52受影响复验，本索引未运行Maven/测试/Engine。",
                   "来源集合与哈希只证明时点；无真实DB/Provider/Redis/HTTP/Worker/浏览器、发布或迁移完成。"],
        "businessVerified": False, "migrationComplete": False,
    })
    dump(FREEZE, check)
    result = {"freeze": entry(FREEZE), "inventory": entry(INV), "audit": entry(AUD), "index": entry(INDEX),
              "counts": check["currentInputCounts"], "routes": 172, "originalRoutes": 389,
              "generatorMetadataDescriptionsChanged": 3, "addedSupportingTests": len(new_supporting),
              "previousSnapshotDeltas": {k: len(v) for k, v in previous_deltas.items()},
              "frontendImport": front_counts, "sourceWindowChanges": check["readAndGenerationWindow"]["sourceChanges"],
              "localLinksBad": len(bad_links), "businessVerified": False}
    dump(OUT / "checks/纯迭代调试流索引局部同步结果.json", result)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
