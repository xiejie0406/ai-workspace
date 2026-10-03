"""只读核对原参考、迁移映射及目标存在性；不把文件存在视为业务通过。"""
import csv
import hashlib
import json
from pathlib import Path

evidence = Path(__file__).resolve().parent
root = evidence.parents[4]
baseline = evidence.parents[1] / "设计附录" / "源码核查清单.csv"
manifest = root / "ruoyi-backend/ruoyi-paicli/paicli-core/source-migration-manifest.json"
failures = []
with baseline.open(encoding="utf-8-sig", newline="") as file:
    original = list(csv.DictReader(file))
for row in original:
    source = root / "参考项目/paicli-main" / row["来源路径"]
    if not source.is_file():
        failures.append({"kind": "SOURCE_MISSING", "path": row["来源路径"]})
    elif hashlib.sha256(source.read_bytes()).hexdigest().upper() != row["SHA256"].upper():
        failures.append({"kind": "SOURCE_CHANGED", "path": row["来源路径"]})

entries = json.loads(manifest.read_text(encoding="utf-8-sig"))
changed = []
targets = set()
for entry in entries:
    source, target = root / entry["source"], root / entry["target"]
    if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != entry["sourceSha256"].lower():
        failures.append({"kind": "MANIFEST_SOURCE_MISMATCH", "path": entry["source"]})
    if entry["target"] in targets:
        failures.append({"kind": "DUPLICATE_TARGET", "path": entry["target"]})
    targets.add(entry["target"])
    if not target.is_file():
        failures.append({"kind": "TARGET_MISSING", "path": entry["target"]})
    elif hashlib.sha256(target.read_bytes()).hexdigest() != entry["sourceSha256"].lower():
        changed.append(entry["target"])
report = {"sourceBaselineCount": len(original), "migrationEntryCount": len(entries),
          "sourceUnchanged": not any(f["kind"] != "TARGET_MISSING" for f in failures),
          "targetAdaptationCount": len(changed), "adaptedTargets": changed,
          "failures": failures, "businessPassed": 0}
(evidence / "源码完整性检查.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({key: value for key, value in report.items() if key != "adaptedTargets"}, ensure_ascii=False))
raise SystemExit(bool(failures))
