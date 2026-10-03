"""只读核对公开配置来源、9字段、014 hex种子及受限契约；不执行SQL或网络。"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import runpy

ROOT = Path(__file__).resolve().parents[2]


def main():
    generator = runpy.run_path(str(Path(__file__).with_name("generate-public-config-catalog.py")))
    source = generator["SOURCE"]
    statement = next(line for line in source.read_text(encoding="utf-8-sig").splitlines() if line.startswith("INSERT INTO `config_info`"))
    raw = generator["parse_rows"](statement)
    columns = ["id", "category", "code", "name", "value", "isValid", "remarks", "createTime", "updateTime"]
    records = [dict(zip(columns, row)) for row in raw]
    policy = generator["PUBLIC"]
    expected = [{**record, "sourceOrdinal": ordinal} for ordinal, record in enumerate(records) if record["category"] in policy and (policy[record["category"]] is None or record["code"] in policy[record["category"]])]
    catalog = json.loads(generator["TARGET"].read_text(encoding="utf-8"))
    audit = json.loads(generator["AUDIT"].read_text(encoding="utf-8"))
    assert catalog["records"] == expected and len(expected) == 147 and sum(r["isValid"] == 1 for r in expected) == 141
    assert catalog["sourceSha256"] == hashlib.sha256(source.read_bytes()).hexdigest()
    assert catalog["missingPublicCategories"] == ["WORKFLOW_CATEGORY"]
    for entry in audit["sourceFiles"]:
        assert hashlib.sha256((ROOT / entry["path"]).read_bytes()).hexdigest() == entry["sha256"]
    for key, path_key in [("catalogSha256", "catalog"), ("ddlSha256", "ddl"), ("generatorSha256", "generator")]:
        assert hashlib.sha256((ROOT / audit[path_key]).read_bytes()).hexdigest() == audit[key]
    assert not any(generator["public_content"](r) for r in expected)
    ddl = generator["DDL"].read_text(encoding="utf-8")
    tuples = ddl.split("VALUES\n", 1)[1].strip().rstrip(";").split(",\n")
    assert len(tuples) == 147
    for line, record in zip(tuples, expected):
        tokens = re.findall(r"CONVERT\(X'[0-9a-f]*' USING utf8mb4\)|NULL|[0-9]+", line)
        assert len(tokens) == 9
        decoded = [bytes.fromhex(t.split("'")[1]).decode("utf-8") if t.startswith("CONVERT") else None if t == "NULL" else int(t) for t in tokens]
        assert decoded == [record[c] for c in columns]
    assert "INSERT IGNORE INTO paiflow_public_config" in ddl and "ck_paiflow_public_config_public" in ddl and "ON UPDATE CURRENT_TIMESTAMP" in ddl
    model_secret = [r for r in records if r["category"] in {"MODEL_SECRET_KEY", "NODE_API_K_S", "AI_CODE"}]
    assert model_secret and not any(r["id"] in {e["id"] for e in expected} for r in model_secret)
    probes = [
        {"nested": {"apiSecret": "private-fixture"}},
        {"encoded": json.dumps({"credentialRef": "private-fixture"})},
    ]
    assert all(generator["secret_keys"](value) for value in probes)
    assert generator["public_content"]({"name": "", "code": "", "value": '{"url":"http:\\/\\/10.0.0.1\\/private"}', "remarks": None})
    assert generator["public_content"]({"name": "", "code": "", "value": 'https://public.invalid/path?%74oken=private-fixture', "remarks": None})
    contract = json.loads((ROOT / "contracts/paiflow/public-config.openapi.json").read_text(encoding="utf-8"))
    assert len(contract["paths"]) == 10 and all(set(item) == {"get"} for item in contract["paths"].values())
    print(json.dumps({"sourceRows": len(records), "publicRows": len(expected), "activeRows": 141, "excludedRows": len(records)-len(expected), "exactNineFieldAndHexSeedMismatches": 0, "missingCategories": ["WORKFLOW_CATEGORY"], "contracts": 10, "sourceHashesMatched": len(audit["sourceFiles"]), "sensitiveAndEncodedPrivateProbes": 4, "sqlExecuted": False, "network": False, "businessVerified": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
