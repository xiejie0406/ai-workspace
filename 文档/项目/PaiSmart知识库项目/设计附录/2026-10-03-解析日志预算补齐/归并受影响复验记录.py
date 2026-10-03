"""保留首轮35类通过原文，加入受影响类实际复验；明确来源，不冒充整批重跑。"""
import csv
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

ROUND = Path(__file__).resolve().parent
ROOT = ROUND.parents[4]
MODULE = ROOT / "ruoyi-backend/ruoyi-paismart"
BASE = ROUND / "首修全模块失败报告"
BASE_CSV = ROUND / "解析日志-复验前源码哈希.csv"
current_csv = Path(sys.argv[1]).resolve()
targeted = Path(sys.argv[2]).resolve()
targeted_log = Path(sys.argv[3]).resolve()
output = ROUND / "当前通过报告"
affected = "com.ruoyi.paismart.infrastructure.intelligence.IsolatedParserLifecycleTest"
affected_source = (MODULE / "src/test/java/com/ruoyi/paismart/infrastructure/intelligence/IsolatedParserLifecycleTest.java").resolve()
if output.exists():
    raise SystemExit("已有归并结果不覆盖")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def sources(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return {Path(row["Path"]).resolve(): row["Hash"].lower() for row in csv.DictReader(stream)}


before, current = sources(BASE_CSV), sources(current_csv)
assert before.keys() == current.keys()
assert all(sha(path) == value for path, value in current.items())
changed = [path for path in before if before[path] != current[path]]
assert changed == [affected_source], "定向复验仅可沿用同一生产源码、其余未变化源码的通过项"
origins = []
total = 0
base_xml = sorted(BASE.glob("TEST-*.xml"))
assert len(base_xml) == 36
for path in base_xml:
    original = ET.parse(path).getroot()
    is_affected = original.get("name") == affected
    chosen = targeted / path.name if is_affected else path
    suite = ET.parse(chosen).getroot()
    assert suite.get("name") == original.get("name")
    assert all(int(suite.get(key)) == 0 for key in ("failures", "errors", "skipped"))
    count = int(suite.get("tests"))
    total += count
    if is_affected:
        assert int(original.get("failures")) == 1 and count == 3
    else:
        assert all(int(original.get(key)) == 0 for key in ("failures", "errors", "skipped"))
    origins.append({
        "class": suite.get("name"), "filename": path.name, "tests": count,
        "origin": str(chosen.relative_to(ROOT)).replace("\\", "/"), "sha256": sha(chosen),
        "scope": "本轮日志测试诊断及夹具修正后实际定向复验" if is_affected else "沿用本轮首修完整执行的通过结果；生产和该类源码未变",
        "targetedExecution": is_affected,
    })
assert len([row for row in origins if row["targetedExecution"]]) == 1 and total == 362
output.mkdir()
for row in origins:
    origin = ROOT / row["origin"]
    shutil.copy2(origin, output / row["filename"])
    text_report = origin.with_name(origin.name.removeprefix("TEST-")).with_suffix(".txt")
    if text_report.is_file():
        shutil.copy2(text_report, output / text_report.name)
manifest = {
    "time": datetime.now().astimezone().isoformat(), "totalClasses": 36, "totalTests": total,
    "retainedFullRunClasses": 35, "retainedFullRunTests": 359,
    "targetedClasses": 1, "targetedTests": 3,
    "sameProductionAndOtherTestSources": True,
    "changedSinceFirstFullRun": [str(path.relative_to(ROOT)).replace("\\", "/") for path in changed],
    "firstFullRunSourceCSV": str(BASE_CSV.relative_to(ROOT)).replace("\\", "/"),
    "firstFullRunSourceCSVSHA256": sha(BASE_CSV),
    "currentSourceCSV": str(current_csv.relative_to(ROOT)).replace("\\", "/"),
    "currentSourceCSVSHA256": sha(current_csv),
    "firstFullRunLog": str((ROUND / "解析日志-修后全模块复验.log").relative_to(ROOT)).replace("\\", "/"),
    "targetedLog": str(targeted_log.relative_to(ROOT)).replace("\\", "/"),
    "targetedLogSHA256": sha(targeted_log), "reports": origins,
    "scope": "36类362项当前通过结果由同生产源码的35类359项沿用与1类3项定向复验组成；真实业务未执行。",
}
manifest_path = output / "执行来源.json"
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({key: manifest[key] for key in ("totalClasses", "totalTests", "retainedFullRunTests", "targetedTests", "scope")}, ensure_ascii=False))
