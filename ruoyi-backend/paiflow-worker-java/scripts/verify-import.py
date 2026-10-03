"""只读核对原引入清单、当前改编文件和本工程包引用，写入本地审计结果。"""
from pathlib import Path
import hashlib
import json
import re

root = Path(__file__).resolve().parents[1]
workspace = root.parents[1]
manifest = json.loads((root / "upstream-manifest.json").read_text(encoding="utf-8"))
rows = []
errors = []
for entry in manifest["files"]:
    source = workspace / entry["source"]
    target = root / entry["target"]
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest() if source.is_file() else None
    target_hash = hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else None
    if source_hash != entry["sha256"]:
        errors.append("原来源与引入时清单不一致：" + entry["source"])
    if target_hash is None:
        errors.append("引入文件缺失：" + entry["target"])
    rows.append({"source": entry["source"], "target": entry["target"], "originalSha256": entry["sha256"],
                 "currentSha256": target_hash, "adapted": target_hash != entry["sha256"]})

source_roots = [root / "src/main/java", root / "src/test/java"]
local_imports = []
for path in [path for directory in source_roots for path in directory.rglob("*.java")]:
    source = path.read_text(encoding="utf-8")
    for name in re.findall(r"^import\s+(?:static\s+)?((?:com\.iflytek\.|com\.ruoyi\.paiflow\.)[\w.*]+);", source, re.M):
        chunks = name.split(".")
        found = False
        while len(chunks) >= 3:
            relative = Path(*chunks)
            if any((directory / relative).is_dir() or (directory / relative).with_suffix(".java").is_file()
                   for directory in source_roots):
                found = True
                break
            chunks.pop()
        local_imports.append({"file": path.relative_to(root).as_posix(), "import": name, "resolved": found})
        if not found:
            errors.append("本地引用无法解析：" + name)
    if re.search(r"WorkflowMapper|ToolMapper|com\.baomidou|DataSource|JdbcTemplate|RedisTemplate", source):
        errors.append("执行工程残留正式数据库设施：" + path.relative_to(root).as_posix())

for name in ["LICENSE", "NOTICE"]:
    source = workspace / "参考项目/PaiFlow-main" / name
    target = root / name
    if source.read_bytes() != target.read_bytes():
        errors.append("许可文件与原来源不一致：" + name)

artifact = {"source": manifest["source"], "introducedFileCount": len(rows),
            "adaptedFileCount": sum(row["adapted"] for row in rows), "localImportCount": len(local_imports),
            "unresolvedLocalImports": sum(not row["resolved"] for row in local_imports),
            "files": rows, "imports": local_imports, "errors": errors,
            "scope": "仅证明引入文件保全、来源未变和本地源码引用；不是业务或外部请求验收"}
output = root / "checks/source-import-audit.json"
output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({key: artifact[key] for key in ["introducedFileCount", "adaptedFileCount", "localImportCount", "unresolvedLocalImports", "errors"]}, ensure_ascii=False))
if errors:
    raise SystemExit(1)
