"""从只读参考SQL恢复公开配置白名单；不复制秘密/环境类别，不联网或执行SQL。"""
from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
from pathlib import Path
import re
from urllib.parse import urlsplit, unquote_plus

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "参考项目/PaiFlow-main/docker/PaiFlow/mysql/schema.sql"
TARGET = ROOT / "ruoyi-backend/ruoyi-paiflow/src/main/resources/paiflow/public-config-source.json"
AUDIT = ROOT / "文档/项目/PaiFlow工作流项目/证据/本地源码检查/公开配置来源核对.json"
DDL = ROOT / "ruoyi-backend/ruoyi-paiflow/sql/014-paiflow-public-config.sql"
PUBLIC = {
    "ICON": None, "COLOR": None, "TOOL_ICON": None,
    "TAG": {"TOOL_TAGS", "BOT_TAGS", "TOOL_TAGS_V2", "FLOW_TAGS"},
    "DEFAULT_SLICE_RULES": {"1"}, "CUSTOM_SLICE_RULES": {"1"},
    "DEFAULT_SLICE_RULES_CBG": {"1"}, "CUSTOM_SLICE_RULES_CBG": {"1"},
    "DEFAULT_SLICE_RULES_SPARK": {"1"}, "CUSTOM_SLICE_RULES_SPARK": {"1"},
    "DEFAULT_SLICE_RULES_AIUI": {"1"}, "CUSTOM_SLICE_RULES_AIUI": {"1"},
    "CUSTOM_SLICE_SEPERATORS_AIUI": {"1"}, "CUSTOM_SLICE_SEPERATORS_CBG": {"1"},
    "CUSTOM_SLICE_SEPERATORS_SPARK": {"1"},
    "DB_TABLE_TEMPLATE": {"TB"}, "DB_TABLE_RESERVED_KEYWORD": {"reserved_keyword"},
    "TEMPLATE": {"node"}, "SPARK_PRO_QR_CODE": {"qr", "qr_feishu"},
    "WORKFLOW": {"python-dependency"}, "WORKFLOW_CATEGORY": None,
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_rows(statement: str) -> list[list]:
    """解析本dump的VALUES字面量；按MySQL标准反斜杠模式，不执行表达式。"""
    data = statement.split(" VALUES ", 1)[1]
    rows, row = [], []
    i = 0
    while i < len(data):
        c = data[i]
        if c in " ,;\r\n\t":
            i += 1
            continue
        if c == "(":
            row = []
            i += 1
            continue
        if c == ")":
            if len(row) != 9:
                raise ValueError("config_info字段数量与9字段DDL不符")
            rows.append(row)
            i += 1
            continue
        if c == "'":
            i += 1
            value = ""
            while i < len(data):
                ch = data[i]
                if ch == "'":
                    if i + 1 < len(data) and data[i + 1] == "'":
                        value += "'"
                        i += 2
                        continue
                    i += 1
                    break
                if ch == "\\":
                    i += 1
                    escaped = data[i]
                    value += {"0": "\0", "b": "\b", "n": "\n", "r": "\r", "t": "\t", "Z": "\x1a", "%": "\\%", "_": "\\_"}.get(escaped, escaped)
                else:
                    value += ch
                i += 1
            else:
                raise ValueError("未闭合的SQL字符串")
            row.append(value)
            continue
        end = i
        while end < len(data) and data[end] not in ",)":
            end += 1
        token = data[i:end].strip()
        if token == "NULL":
            row.append(None)
        elif re.fullmatch(r"-?[0-9]+", token):
            row.append(int(token))
        else:
            raise ValueError("公开来源不执行非字面量SQL表达式")
        i = end
    return rows


def secret_keys(value, path="$", found=None, depth=0):
    found = [] if found is None else found
    if depth > 48:
        found.append(path + ":depth_exceeded")
        return found
    if isinstance(value, dict):
        for key, child in value.items():
            norm = re.sub(r"[^a-z0-9]", "", key.lower())
            if norm in {"apikey", "apisecret", "appkey", "appsecret", "password", "accesstoken", "refreshtoken", "authorization", "credential", "credentialref", "secretref"}:
                found.append(path + "." + key)
            secret_keys(child, path + "." + key, found, depth + 1)
    elif isinstance(value, list):
        for i, child in enumerate(value):
            secret_keys(child, f"{path}[{i}]", found, depth + 1)
    elif isinstance(value, str) and value.lstrip().startswith(("{", "[", '"')):
        try:
            parsed = json.loads(value)
        except ValueError:
            pass
        else:
            secret_keys(parsed, path + ":encoded", found, depth + 1)
    return found


def public_content(record: dict) -> list[str]:
    """只检查准备引入的值；问题只输出ID/原因，不打印原秘密内容。"""
    problems = []
    for key in ("name", "code", "value", "remarks"):
        text = record[key]
        if not isinstance(text, str):
            continue
        try:
            parsed = json.loads(text)
        except (ValueError, TypeError):
            parsed = None
        if parsed is not None and secret_keys(parsed):
            problems.append(key + ":secret_field")
        texts = [text]
        def nested_strings(value, depth=0):
            if depth > 48:
                problems.append(key + ":depth_exceeded")
            elif isinstance(value, dict):
                for child in value.values():
                    nested_strings(child, depth + 1)
            elif isinstance(value, list):
                for child in value:
                    nested_strings(child, depth + 1)
            elif isinstance(value, str):
                texts.append(value)
                if value.lstrip().startswith(("{", "[", '"')):
                    try:
                        child = json.loads(value)
                    except ValueError:
                        pass
                    else:
                        nested_strings(child, depth + 1)
        nested_strings(parsed)
        for url in [url for candidate in texts for url in re.findall(r"https?://[^\s\"'<>\\]+", candidate)]:
            parts = urlsplit(url)
            host = (parts.hostname or "").lower()
            if parts.username is not None or parts.password is not None:
                problems.append(key + ":url_userinfo")
            if host == "localhost" or host.endswith((".local", ".internal")) or not host:
                problems.append(key + ":nonpublic_host")
            try:
                if not ipaddress.ip_address(host).is_global:
                    problems.append(key + ":nonpublic_ip")
            except ValueError:
                pass
            for query_item in parts.query.split("&"):
                try:
                    query_key = unquote_plus(query_item.split("=", 1)[0], errors="strict")
                except (ValueError, UnicodeError):
                    problems.append(key + ":invalid_query_encoding")
                    continue
                normalized = re.sub(r"[_-]", "", query_key.lower())
                if any(word in normalized for word in ("token", "secret", "signature", "credential")) or normalized in {"apikey", "authorization", "awsaccesskeyid"}:
                    problems.append(key + ":signed_or_secret_query")
    return sorted(set(problems))


def main() -> None:
    lines = SOURCE.read_text(encoding="utf-8-sig").splitlines()
    source_line, statement = next((i, line) for i, line in enumerate(lines, 1) if line.startswith("INSERT INTO `config_info`"))
    raw = parse_rows(statement)
    if len(raw) != 381:
        raise ValueError("原config_info381条基线发生变化，需重新审核")
    columns = ["id", "category", "code", "name", "value", "isValid", "remarks", "createTime", "updateTime"]
    all_records = [dict(zip(columns, row)) for row in raw]
    records, excluded, rejected = [], [], []
    for ordinal, record in enumerate(all_records):
        allowed = record["category"] in PUBLIC and (PUBLIC[record["category"]] is None or record["code"] in PUBLIC[record["category"]])
        if not allowed:
            excluded.append({"id": record["id"], "category": record["category"], "code": record["code"]})
            continue
        problems = public_content(record)
        if problems:
            rejected.append({"id": record["id"], "category": record["category"], "code": record["code"], "reasons": problems})
            continue
        records.append({**record, "sourceOrdinal": ordinal})
    # 公开候选如含敏感/私有内容需先人工处置，不能悄悄删除变成完整成功。
    if rejected:
        raise ValueError(json.dumps({"publicCandidatesRejected": rejected}, ensure_ascii=False))
    metadata = {
        "schemaVersion": "paiflow.public-config-source.v1",
        "source": SOURCE.relative_to(ROOT).as_posix(), "sourceSha256": sha(SOURCE), "sourceLine": source_line,
        "sourceRows": len(raw), "publicWhitelist": {k: sorted(v) if v is not None else None for k, v in PUBLIC.items()},
        "profileTagIdRule": "仅spring.profiles.active完整字符串等于dev或test时tool_v2使用非空remarks的Long；组合profile按原逻辑不映射",
        "genericOrder": "原Controller前三查询没有ORDER BY；按原SQL种子顺序保全，未声称真实旧库无序返回顺序已验证",
        "tagOrder": "update_time DESC；相同时间保留来源顺序，原SQL没有第二排序条件",
        "missingPublicCategories": sorted(set(PUBLIC) - {r["category"] for r in records}),
        "excludedRows": len(excluded), "excludedCategoryCounts": dict(Counter(r["category"] for r in excluded)),
        "realEnvironmentImported": False, "network": False, "sqlExecuted": False,
        "records": records,
    }
    TARGET.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    def sql_text(value):
        if value is None:
            return "NULL"
        if isinstance(value, int):
            return str(value)
        # 明确UTF8字节，避免真实MySQL sql_mode改变反斜杠/换行或引号语义。
        return "CONVERT(X'" + value.encode("utf-8").hex() + "' USING utf8mb4)"
    predicates = []
    for category, codes in PUBLIC.items():
        predicate = "category='" + category + "'"
        if codes is not None:
            predicate += " AND code IN (" + ",".join("'" + c + "'" for c in sorted(codes)) + ")"
        predicates.append("(" + predicate + ")")
    ddl = """-- PaiFlow公开配置目录：MySQL8未执行增量草案，不创建数据库、不执行原schema。
-- 只迁经审查的公开白名单种子；秘密/环境配置无列外通道，不保存在本表。
-- 原9字段、is_valid筛选及动态更新后读取保留；本模块没有HTTP写接口。
CREATE TABLE IF NOT EXISTS paiflow_public_config (
  id BIGINT NOT NULL AUTO_INCREMENT,
  category VARCHAR(64) NOT NULL,
  code VARCHAR(128) NOT NULL,
  name VARCHAR(255) NULL,
  value TEXT NULL,
  is_valid TINYINT NULL,
  remarks VARCHAR(1000) NULL,
  create_time DATETIME NULL DEFAULT '2000-01-01 00:00:00',
  update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_paiflow_public_config_lookup (category,code,is_valid),
  CONSTRAINT ck_paiflow_public_config_valid CHECK (is_valid IS NULL OR is_valid IN (0,1)),
  CONSTRAINT ck_paiflow_public_config_public CHECK (
""" + "    " + " OR\n    ".join(predicates) + "\n  )\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;\n\n"
    ddl += "-- 147原来源行（141有效）；不覆盖已有运行期修改，重复ID仅保留原有行。\n"
    ddl += "INSERT IGNORE INTO paiflow_public_config (id,category,code,name,value,is_valid,remarks,create_time,update_time) VALUES\n"
    ddl += ",\n".join("(" + ",".join(sql_text(record[c]) for c in columns) + ")" for record in records) + ";\n"
    DDL.write_text(ddl, encoding="utf-8")
    sources = [SOURCE, ROOT / "参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/controller/common/ConfigInfoController.java", ROOT / "参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/service/common/ConfigInfoService.java", ROOT / "参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/table/ConfigInfo.java", ROOT / "参考项目/PaiFlow-main/console/backend/toolkit/src/main/resources/mybatis/mapper/mysql/ConfigInfoMapper.xml"]
    audit = {"recordedAt": datetime.now(timezone.utc).isoformat(), "generator": Path(__file__).relative_to(ROOT).as_posix(), "generatorSha256": sha(Path(__file__)), "sourceFiles": [{"path": p.relative_to(ROOT).as_posix(), "sha256": sha(p)} for p in sources], "catalog": TARGET.relative_to(ROOT).as_posix(), "catalogSha256": sha(TARGET), "ddl": DDL.relative_to(ROOT).as_posix(), "ddlSha256": sha(DDL), "sourceRows": len(raw), "publicRows": len(records), "publicActiveRows": sum(r["isValid"] == 1 for r in records), "publicCategoryCounts": dict(Counter(r["category"] for r in records)), "missingPublicCategories": metadata["missingPublicCategories"], "excludedRows": len(excluded), "excludedCategoryCounts": metadata["excludedCategoryCounts"], "publicCandidateRejections": rejected, "network": False, "sqlExecuted": False, "realDatabaseWrites": False, "businessVerified": False, "limitations": ["SQL标准反斜杠解码；真实旧库sql_mode/用户新增配置尚未核对。", "未复制排除行的值、remarks或真实环境；公共URL只是源码引用，未访问且未证明存储可用。", "WORKFLOW_CATEGORY原无行；未知/敏感目录未接通，不能用空目录伪造成功。"]}
    AUDIT.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: audit[k] for k in ["sourceRows", "publicRows", "publicActiveRows", "publicCategoryCounts", "missingPublicCategories", "excludedRows", "catalogSha256"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
