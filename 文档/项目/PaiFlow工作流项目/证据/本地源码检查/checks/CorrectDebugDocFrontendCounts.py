"""仅校正第23节前端阶段计数；保全当前正文和旧局部proof，不改源或索引。"""
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote

root = Path(__file__).resolve().parents[6]
checks = Path(__file__).resolve().parent
record = root / "文档/项目/PaiFlow工作流项目/设计资料核对记录.md"
readme = root / "ruoyi-backend/ruoyi-paiflow/README.md"
proof = checks / "调试接续文档局部更新检查.json"
preserved = checks / "调试接续前端阶段计数校正前原文"
audit = checks.parent / "调试聊天接续本地源码检查.json"
sha = lambda b: hashlib.sha256(b).hexdigest()
old = record.read_bytes()
old_readme = readme.read_bytes()
old_proof = proof.read_bytes()
old_audit = audit.read_bytes()
needle = "首104项、历史选择109项、断开首反例/AST夹具失败与最终七输入冻结".encode("utf-8")
replacement = "首次101项、历史选择104项、视图断开109项与最终110项、断开首反例/AST夹具失败及最终七输入冻结".encode("utf-8")
if old.count(needle) != 1 or preserved.exists():
    raise RuntimeError("目标句不唯一或保全目录已存在；拒绝重复覆盖")
preserved.mkdir()
files = []
for path, data in [(record, old), (readme, old_readme), (proof, old_proof)]:
    destination = preserved / path.name
    destination.write_bytes(data)
    assert destination.read_bytes() == data
    files.append({"source": path.relative_to(root).as_posix(), "backup": destination.relative_to(root).as_posix(), "bytes": len(data), "sha256": sha(data)})
(preserved / "输入.json").write_text(json.dumps({"savedAt": datetime.now(timezone.utc).isoformat(), "scope": "第23节计数句校正前实际工作区原文与局部proof", "files": files}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
record.write_bytes(old.replace(needle, replacement))
current = record.read_bytes()
assert current == old.replace(needle, replacement)
report = json.loads(old_proof.decode("utf-8-sig"))
for item in report["changedDocuments"]:
    path = root / item["path"]
    actual = path.read_bytes()
    initial = checks / "调试接续文档更新前原文" / path.name
    raw = initial.read_bytes()
    assert actual[:len(raw)] == raw
    item.update(currentBytes=len(actual), currentSha256=sha(actual), oldPrefixPreserved=True)
bad = []
for item in report["links"]:
    source = root / item["from"]
    target, _, fragment = item["target"].partition("#")
    path = (source.parent / unquote(target)).resolve() if target else source
    item["exists"] = path.exists()
    if fragment and path.suffix == ".md" and path.exists():
        def anchor(s):
            s = re.sub(r"[`*_]", "", s.strip().lower())
            return re.sub(r"[^\w\- ]", "", s, flags=re.UNICODE).replace(" ", "-")
        headings = re.findall(r"^#{1,6}\s+(.+)$", path.read_text(encoding="utf-8-sig"), re.MULTILINE)
        item["fragmentFound"] = unquote(fragment) in {anchor(s) for s in headings}
    if not item["exists"] or item.get("fragmentFound") is False:
        bad.append(item)
assert readme.read_bytes() == old_readme and audit.read_bytes() == old_audit and not bad
report.update(checkedAt=datetime.now(timezone.utc).isoformat(), badLocalLinks=bad)
report["countSentenceCorrection"] = {
    "preserved": (preserved / "输入.json").relative_to(root).as_posix(),
    "beforeDocumentSha256": sha(old), "afterDocumentSha256": sha(current),
    "beforeProofSha256": sha(old_proof), "onlyOneSentenceChanged": True,
    "exactStages": [101, 104, 109, 110], "readmeUnchanged": True,
    "originalFullPrefixesPreserved": True,
}
proof.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"recordSha256": sha(current), "readmeSha256": sha(old_readme), "proofSha256": sha(proof.read_bytes()), "preserved": str(preserved), "localLinks": len(report["links"]), "bad": len(bad), "onlyOneSentenceChanged": True}, ensure_ascii=True))
