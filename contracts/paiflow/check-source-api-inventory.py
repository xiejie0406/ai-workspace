"""只读核对接口快照、原389条事实和当前输入；不调用服务、DB或Provider。"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BASELINE = {
    "sourceCount": 389,
    "sourceIdSha256": "846d62e2729d13ae4e31929d4b8e4023b64d165cc45d04468d717b405490b607",
    "sourceFactsSha256": "d60b074e04870cf08add92b4f76adc444fb544ee0f28c47b28596aa164ce3ba5",
    "sourceFileCount": 68,
    "sourceFileHashesSha256": "cb884b7700433035533de9389a83cb7c7e79b53641cb84272cb7bec6050da211",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def json_sha(value, sort_keys=False) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=sort_keys).encode()).hexdigest()


def differences(recorded: dict, current: dict) -> list[dict]:
    return [{"path": path, "recordedSha256": recorded.get(path), "currentSha256": current.get(path)}
            for path in sorted(recorded.keys() | current.keys()) if recorded.get(path) != current.get(path)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="可选：保存此次执行结果，默认只输出JSON")
    args = parser.parse_args()
    inventory_path = HERE / "source-api-inventory.json"
    audit_path = ROOT / "文档/项目/PaiFlow工作流项目/附录/接口覆盖核对.json"
    inventory = json.loads(inventory_path.read_text(encoding="utf-8-sig"))
    audit = json.loads(audit_path.read_text(encoding="utf-8-sig"))
    endpoints = inventory["originalEndpoints"]
    source_files = {p: sha(ROOT / p) for p in sorted({e["file"] for e in endpoints})}
    facts = sorted([{k: v for k, v in e.items() if k not in {"migrationStatus", "targetMatches"}}
                    for e in endpoints], key=lambda e: e["id"])
    actual_baseline = {
        "sourceCount": len(endpoints),
        "sourceIdSha256": json_sha(sorted(e["id"] for e in endpoints)),
        "sourceFactsSha256": json_sha(facts, True),
        "sourceFileCount": len(source_files),
        "sourceFileHashesSha256": json_sha(source_files, True),
    }
    # 与生成器main内的集合定义一致；枚举当前集合使新增/删除输入也使快照失效。
    # 下方同时核对audit保存的生成器哈希，集合定义修改后不能沿用旧核对结果。
    java_root = ROOT / "ruoyi-backend/ruoyi-paiflow/src/main/java"
    java = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(java_root.rglob("*.java"))}
    frontend_root = ROOT / "frontend/paiflow-web"
    frontend_files = [p for p in (frontend_root / "src").rglob("*") if p.is_file() and p.suffix in {".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"}]
    frontend_files += [frontend_root / name for name in ("package.json", "package-lock.json", "index.html", "vite.config.ts", "tsconfig.json") if (frontend_root / name).is_file()]
    frontend = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(frontend_files)}
    target_root = ROOT / "ruoyi-backend/ruoyi-paiflow"
    supporting_files = [p for folder in ("src/main/resources", "src/test", "sql") for p in (target_root / folder).rglob("*") if p.is_file()]
    if (target_root / "pom.xml").is_file():
        supporting_files.append(target_root / "pom.xml")
    supporting = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(supporting_files)}
    mismatches = {
        "java": differences(inventory["targetSnapshotHashes"], java),
        "frontend": differences(inventory["targetFrontendSnapshotHashes"], frontend),
        "supporting": differences(inventory["targetSupportingSnapshotHashes"], supporting),
    }
    diagnostics = inventory["summary"]["parseDiagnosticCounts"]
    windows = {k: inventory["summary"][k] for k in ["targetFilesChangedDuringParse", "targetFrontendFilesChangedDuringParse", "targetSupportingFilesChangedDuringParse"]}
    baseline_mismatches = {k: {"expected": BASELINE[k], "actual": actual_baseline[k]}
                           for k in BASELINE if BASELINE[k] != actual_baseline[k]}
    audit_mismatches = []
    if audit["inventorySha256"] != sha(inventory_path):
        audit_mismatches.append("audit.inventorySha256")
    if audit["summary"] != inventory["summary"]:
        audit_mismatches.append("audit.summary")
    for path, expected in audit["generatorHashes"].items():
        if sha(ROOT / path) != expected:
            audit_mismatches.append(path)
    passed = not (baseline_mismatches or any(mismatches.values()) or any(diagnostics.values()) or any(windows.values()) or audit_mismatches)
    result = {
        "schemaVersion": "paiflow.api-inventory-freeze-check.v1",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "inventoryGeneratedAt": inventory["generatedAt"],
        "status": "source_snapshot_consistent_business_unverified" if passed else "requires_regeneration_or_review",
        "originalBaseline": actual_baseline, "originalBaselineMismatches": baseline_mismatches,
        "currentInputCounts": {"java": len(java), "frontend": len(frontend), "supporting": len(supporting),
                               "supportingByKind": dict(Counter("SQL" if p.endswith(".sql") else "tests" if "/src/test/" in p else "resources" if "/src/main/resources/" in p else "module_pom" for p in supporting))},
        "currentSnapshotMismatches": mismatches, "parseDiagnostics": diagnostics,
        "generationWindowChanges": windows, "auditMismatches": audit_mismatches,
        "inventorySha256": sha(inventory_path), "auditSha256": sha(audit_path),
        "checkerSha256": sha(Path(__file__)), "summary": inventory["summary"],
        "businessVerified": False, "network": False, "servicesStarted": False,
        "realDatabaseWrites": False, "sqlExecuted": False,
        "limitations": ["AST路由及静态方法/path匹配不是字段、序列化、运行装配或业务通过证据。",
                        "本核对只覆盖生成器声明的文件集合；Python运行实现另以其实际离线日志和冻结哈希核对。",
                        "当前输入一致仅证明记录时点，任一后续输入变更必须重新生成或明确保留阶段快照。"],
    }
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(output, encoding="utf-8")
    print(output, end="")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
