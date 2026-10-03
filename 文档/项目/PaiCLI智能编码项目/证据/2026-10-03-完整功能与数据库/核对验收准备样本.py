"""只读核对准备样本，不登记工作区、不运行产品工具、不访问网络或业务库。"""
import collections
import datetime
import hashlib
import json
from pathlib import Path

EVIDENCE = Path(__file__).resolve().parent
WORKSPACE = EVIDENCE.parents[4]


def digest(value):
    return hashlib.sha256(value).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def inventory(root, records, fields, utf8=False):
    root = Path(root).resolve(strict=True)
    seen = set()
    for record in records:
        name = record[fields[0]]
        assert name not in seen, ("重复路径", name)
        seen.add(name)
        path = (root / name).resolve(strict=True)
        assert path.is_relative_to(root), ("越界路径", name)
        raw = path.read_bytes()
        assert len(raw) == record[fields[1]], ("字节数变化", name)
        assert digest(raw) == record[fields[2]].lower(), ("摘要变化", name)
        if utf8:
            raw.decode("utf-8", errors="strict")
    return len(seen)


def main():
    pointer = WORKSPACE / ".runtime/paicli/current-runtime.txt"
    runtime = Path(pointer.read_text(encoding="utf-8-sig").strip()).resolve(strict=True)
    assert runtime.parent == Path.home().resolve()
    assert runtime.name.startswith("paicli-local-")
    samples = runtime / "acceptance-samples"

    original = load(EVIDENCE / "本机业务样本基线.json")
    original_records = original.get("Files", original.get("files"))
    assert isinstance(original_records, list), "原样本清单结构变化"
    protected = []
    for row in original_records:
        protected.append({"name": row["Workspace"] + "/" + row["Path"], "size": row["Size"], "sha256": row["SHA256"]})
    assert inventory(samples, protected, ("name", "size", "sha256")) == 15

    rule_path = EVIDENCE / "规则与Skill样本基线.json"
    rule = load(rule_path)
    assert Path(rule["privateRoot"]).resolve() == (samples / "rules-skills").resolve()
    assert inventory(rule["privateRoot"], rule["files"], ("path", "size", "sha256"), True) == 39
    assert rule["databaseWrites"] == rule["networkRequests"] == rule["businessExecuted"] == rule["businessPassed"] == 0

    protocol_path = EVIDENCE / "协议验收样本基线.json"
    protocol = load(protocol_path)
    assert Path(protocol["PrivateRoot"]).resolve() == (samples / "protocol-fixtures").resolve()
    assert inventory(protocol["PrivateRoot"], protocol["Files"], ("Path", "Size", "SHA256"), True) == 117
    assert len(protocol["Providers"]) == 8 and len(protocol["SearchProviders"]) == 4 and len(protocol["McpTransports"]) == 2
    for key in ("DatabaseWrites", "NetworkCalls", "PortsStarted", "BusinessExecuted", "BusinessPassed"):
        assert protocol[key] == 0, key

    legacy_path = EVIDENCE / "原资料导入样本基线.json"
    legacy = load(legacy_path)
    legacy_root = Path(legacy["sampleRoot"])
    assert legacy_root.resolve() == (samples / "legacy-import").resolve()
    assert inventory(legacy_root, legacy["newFiles"], ("name", "size", "sha256")) == 18
    assert inventory(samples, legacy["existingProtection"]["before"], ("name", "size", "sha256")) == 16
    golden = load(legacy["golden"]["path"])
    assert digest(Path(legacy["golden"]["path"]).read_bytes()) == legacy["golden"]["sha256"]
    bundles = [load(legacy_root / "exports" / name) for name in ("bundle-first.json", "bundle-repeat.json")]
    counts = {"TASK": 5, "SESSION": 2, "EVENT": 3, "MESSAGE": 4, "LEDGER_EVENT": 2, "MEMORY": 2, "POLICY": 2, "PROMPT": 1}
    for bundle in bundles:
        assert len(bundle["records"]) == 21
        assert dict(collections.Counter(row["kind"] for row in bundle["records"])) == counts
        identities = [(row["kind"], row["legacyId"]) for row in bundle["records"]]
        assert len(set(identities)) == 21, "导出记录身份重复"
        assert sorted(bundle["records"], key=lambda row: (row["kind"], row["legacyId"])) == golden["expectedRecords"], "原记录与预先独立答案不一致"
        assert not any("synthetic_uncommitted_excluded" in row["legacyId"] for row in bundle["records"])
    assert bundles[0]["sourceFingerprint"] == bundles[1]["sourceFingerprint"]
    assert bundles[0]["records"] == bundles[1]["records"], "重复导出记录及顺序发生变化"
    assert not (legacy_root / "source-consistent/synthetic-runtime.sqlite-shm").exists()
    assert not (legacy_root / "exports/bundle-rejected.json").exists()
    for check in legacy["offlineChecks"]:
        assert check["actualExit"] == check["expectedExit"] and check["passed"]
    actual_jar_sha = digest(Path(legacy["exporterJar"]["path"]).read_bytes())
    assert actual_jar_sha == legacy["exporterJar"]["sha256"]
    fingerprints = load(legacy_root / "technical/workspace-root-fingerprints.json")
    assert fingerprints["sourceJarSha256"] == actual_jar_sha
    assert fingerprints["businessBinding"] == "NOT_ENROLLED"
    assert fingerprints["roots"]["A"]["rootFingerprint"] != fingerprints["roots"]["B"]["rootFingerprint"]

    changed = []
    sources = [(row["path"], row["sha256"]) for row in rule["sources"]]
    sources += [(row["Path"], row["SHA256"]) for row in protocol["SourceEvidence"]]
    for name, expected in sources:
        current = digest((WORKSPACE / name).read_bytes())
        if current != expected.lower():
            changed.append({"path": name, "preparedSha256": expected, "currentSha256": current})
    print(json.dumps({"checkedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      "status": "PREPARATION_READBACK_PASSED_NOT_BUSINESS_ACCEPTANCE", "originalFiles": 15,
                      "newFiles": {"rulesSkills": 39, "protocol": 117, "legacy": 18}, "legacyRecords": counts,
                      "sourceChangesAfterPreparation": changed, "businessExecuted": 0, "businessPassed": 0,
                      "databaseWrites": 0, "networkRequests": 0, "portsStarted": 0,
                      "limitations": ["ACL沿用各准备脚本实际检查证据，本脚本仅核路径/字节/摘要/预期记录", "源摘要变化单列，修复后须按影响核对，不覆盖准备时原基线"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
