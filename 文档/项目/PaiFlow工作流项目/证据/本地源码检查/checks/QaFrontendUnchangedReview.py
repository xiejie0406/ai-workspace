"""只读复核1703引入原前端与1694原样+9既有适配及12冻结输入。"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[6]
OUT = Path(__file__).resolve().parent.parent
DOC = ROOT / "文档/项目/PaiFlow工作流项目"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    manifest = DOC / "附录/原前端引入清单.json"
    difference = DOC / "附录/原前端接入差异.json"
    latest = OUT / "默认个人原前端接入检查.json"
    history = OUT / "纯迭代画布批原前端未修改核对.json"
    metadata = [manifest, difference, latest, history]
    metadata_before = {p.relative_to(ROOT).as_posix(): sha(p) for p in metadata}
    imported = json.loads(manifest.read_text(encoding="utf-8"))
    changes = json.loads(difference.read_text(encoding="utf-8"))
    frozen = json.loads(latest.read_text(encoding="utf-8"))
    reference = ROOT / imported["source"]
    target = ROOT / imported["target"]
    adapted = {item["path"]: item for item in changes["changes"]}
    missing, original_changes, unexpected, actual_adapters = [], [], [], []
    source_hashes, target_hashes = {}, {}
    for item in imported["files"]:
        name = item["path"]
        src, dst = reference / name, target / name
        if not src.is_file() or not dst.is_file():
            missing.append(name)
            continue
        source_hashes[name], target_hashes[name] = sha(src), sha(dst)
        if source_hashes[name] != item["sha256"]:
            original_changes.append(name)
        if target_hashes[name] != item["sha256"]:
            actual_adapters.append(name)
            if name not in adapted or target_hashes[name] != adapted[name]["currentSha256"]:
                unexpected.append(name)
    frozen_mismatches = [item["path"] for item in frozen["frozenSource"] if not (ROOT / item["path"]).is_file() or sha(ROOT / item["path"]) != item["sha256"]]
    metadata_after = {p.relative_to(ROOT).as_posix(): sha(p) for p in metadata}
    source_after = {name: sha(reference / name) for name in source_hashes}
    target_after = {name: sha(target / name) for name in target_hashes}
    assert metadata_before == metadata_after and source_hashes == source_after and target_hashes == target_after
    assert len(imported["files"]) == 1703 and len(actual_adapters) == 9
    assert sorted(actual_adapters) == sorted(adapted)
    assert not missing and not original_changes and not unexpected and not frozen_mismatches
    output = {
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "本次静态问答/运行JSON候选期间的只读源核对，不执行前端构建或页面检查",
        "sourceFiles": 1703, "unchangedOriginalFiles": 1694,
        "existingAdapterChanges": actual_adapters,
        "changesBeyondExistingAdapterBaseline": unexpected,
        "missing": missing, "referenceChanged": original_changes,
        "latestFrozenInputsChecked": len(frozen["frozenSource"]),
        "latestFrozenInputMismatches": frozen_mismatches,
        "metadataBefore": metadata_before, "metadataAfter": metadata_after,
        "originalHashesDigest": hashlib.sha256(json.dumps(source_hashes, sort_keys=True).encode()).hexdigest(),
        "targetHashesDigest": hashlib.sha256(json.dumps(target_hashes, sort_keys=True).encode()).hexdigest(),
        "sourceWindowChanges": [], "frontendRewrite": False,
        "productionSourceModified": False, "targetWritten": False,
        "browserUsed": False, "buildExecuted": False, "businessVerified": False,
        "limitations": ["只核已明确的1703迁入文件及12必要适配/检查冻结输入，结果不是页面/构建新执行。"],
    }
    path = OUT / "静态问答批原前端未修改核对.json"
    path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path), "sourceFiles": 1703, "unchangedOriginalFiles": 1694, "existingAdapterChanges": 9, "latestFrozenInputMismatches": [], "sourceWindowChanges": []}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
