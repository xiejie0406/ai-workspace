"""检查当前PaiFlow三主文档和三README本地行内链接，保留旧时点证据。"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[6]
OUT = Path(__file__).resolve().parent.parent / "纯迭代完成后文档本地链接核对.json"
FILES = ["文档/项目/PaiFlow工作流项目/完整迁移设计.md", "文档/项目/PaiFlow工作流项目/源码覆盖索引.md", "文档/项目/PaiFlow工作流项目/设计资料核对记录.md", "ruoyi-backend/ruoyi-paiflow/README.md", "python/paiflow-runtime/README.md", "frontend/paiflow-web/README.md"]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def slugs(path):
    counts, output = {}, set()
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        title = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if title:
            value = title[1].lower().replace("`", "")
            value = re.sub(r"[^\w\s\-]", "", value)
            value = re.sub(r"\s", "-", value)
            number = counts.get(value, 0)
            counts[value] = number + 1
            output.add(value if not number else f"{value}-{number}")
    return output


def main():
    records, bad, fragments = [], [], []
    before = {name: sha(ROOT / name) for name in FILES}
    for name in FILES:
        path = ROOT / name
        links = []
        for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            for match in re.finditer(r"!?\[[^\]\n]*\]\((<[^>]+>|[^)\n]+)\)", line):
                target = match[1]
                if target.startswith("<"):
                    target = target[1:-1]
                else:
                    target = re.sub(r'\s+"[^\"]*"\s*$', "", target).strip()
                if re.match(r"^(https?://|mailto:|app:|codex:|data:|plugin:)", target):
                    continue
                address, separator, fragment = unquote(target).partition("#")
                address = re.sub(r":\d+$", "", address)
                resolved = (path.parent / address).resolve() if address else path
                exists = resolved.exists()
                fragment_ok = (not separator or not fragment or resolved.suffix.lower() != ".md" or not exists or fragment in slugs(resolved))
                item = {"line": number, "target": target, "resolved": str(resolved.relative_to(ROOT)) if resolved.is_relative_to(ROOT) else str(resolved), "exists": exists, "fragment": fragment, "status": "missing" if not exists else "fragment_requires_render_review" if not fragment_ok else "valid"}
                links.append(item)
                if not exists:
                    bad.append({"source": name, **item})
                elif not fragment_ok:
                    fragments.append({"source": name, **item})
        records.append({"path": name, "sha256Before": before[name], "sha256After": sha(path), "localLinks": len(links), "links": links})
    changed = [record["path"] for record in records if record["sha256Before"] != record["sha256After"]]
    output = {"schemaVersion": "paiflow.documentation.local-link-review.v1", "checkedAt": datetime.now(timezone.utc).isoformat(), "scope": "当前三主文档及backend/Python/frontend README，含新增第20；未修改正文", "method": "本地Markdown行内链接存在性，另按常见GitHub标题slug提示片段；不是完整Markdown渲染验收", "files": records, "localLinkCount": sum(v["localLinks"] for v in records), "badLinks": bad, "fragmentMismatches": fragments, "readWindowChanges": changed, "sourceModified": False, "priorCheckPreserved": "当前文档本地链接独立核对.json", "network": False}
    OUT.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"path": OUT.relative_to(ROOT).as_posix(), "sha256": sha(OUT), "localLinks": output["localLinkCount"], "bad": bad, "fragmentMismatches": fragments, "windowChanges": changed}, ensure_ascii=False, indent=2))
    return 0 if not (bad or fragments or changed) else 1


if __name__ == "__main__":
    raise SystemExit(main())
