"""完整迁移收口的只读静态核对；准备脚本本身不构成执行证据。

只用 Python 标准库读取已有文件，不导入业务代码或报告生成器，不解包到磁盘，
不连接网络、数据库、模型、浏览器或进程，不执行测试/构建。必须显式提供最终
React 记录、四份 JS 指纹、新截图指纹和新的私有结果路径。结果文件禁止覆盖。
历史阶段记录按其自身来源保留，不要求历史 source/bundle 指纹等于当前冻结版本。

示例（参数由根线程在 r11 两份 Markdown 和 HTML 最终冻结后提供）：
  python .runtime/aipicture/check-remaining-final-artifacts-and-report.py \
    --react-record .runtime/aipicture/<final-react-record>.json \
    --bundle <copy-1.js>=<sha256> --bundle <copy-2.js>=<sha256> \
    --bundle <copy-3.js>=<sha256> --bundle <copy-4.js>=<sha256> \
    --evidence-image <new-image.png>=<sha256> \
    --output .runtime/aipicture/<new-final-static-result>.json

也可用 --image-manifest 指定 {"images":[{"path":...,"sha256":...}]}。
退出码：0 仅表示本脚本的静态检查通过；1 有不一致；2 参数/输入或输出路径无效。
这些结果不增加技术测试数量，不改变 22 项业务结果，不代表用户接受。
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import io
import json
from pathlib import Path
import re
import tarfile
from urllib.parse import unquote, urlsplit
import zipfile


HEX = re.compile(r"^[0-9a-fA-F]{64}$")
LINK = re.compile(r"!?\[([^\]\n]*)\]\(([^)\n]+)\)")
EXPECTED_COUNTS = {"通过": 0, "未通过": 0, "受阻": 18, "未执行": 4, "不适用": 0}
PROJECT = Path("文档/项目/AI图片编辑项目")
BUSINESS = PROJECT / "证据/2026-10-03-完整迁移验收/业务验收记录.md"
IMPLEMENTATION = PROJECT / "开发记录/2026-10-03-完整迁移实施.md"
BEFORE = Path(".runtime/aipicture/before-remaining-completion-20261003")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def compact(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def text_marks(value: str) -> str:
    # 独立的源文字规范化，不调用/复刻 Renderer.inline 的 HTML 生成逻辑。
    return compact(value.replace("**", "").replace("`", ""))


def source_fragments(source: str) -> list[str]:
    """从原文按顺序保留文字，链接只取标签，避免图注额外文字造成误报。

例如“前文 [截图](a.png) 后文”独立要求前文、截图、后文依次存在，能检测
链接变量覆盖导致的前文/后文丢失；不是只检查文件名或两个关键词。
    """
    source = re.sub(r"<!--.*?-->", "", source, flags=re.S)
    result: list[str] = []
    in_code = False
    for original in source.splitlines():
        if original.strip().startswith("```"):
            in_code = not in_code
            continue
        line = original if in_code else re.sub(r"^\s*(?:#{1,6}\s+|[-*]\s+|\d+\.\s+)", "", original)
        if in_code:
            if compact(line):
                result.append(compact(line))
            continue
        start = 0
        for match in LINK.finditer(line):
            result.extend(part for part in (text_marks(line[start:match.start()]), text_marks(match.group(1))) if part)
            start = match.end()
        tail = text_marks(line[start:])
        if tail:
            result.append(tail)
    return result


class VisibleReport(HTMLParser):
    """只读取真实 HTML 文本节点；不运行脚本、不采用生成器的 inline 函数。"""

    VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}
    BLOCK = {"p", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "figure", "figcaption", "details", "summary", "section", "footer", "header", "br"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.stack: list[dict] = []
        self.buffers: dict[str, list[str]] = {}
        self.cards: dict[int, dict] = {}
        self.hrefs: list[str] = []
        self.images: list[str] = []
        self.duplicates: list[str] = []

    def active(self) -> list[str]:
        return [frame["capture"] for frame in self.stack if frame.get("capture")]

    def append(self, data: str) -> None:
        if any(frame["tag"] in {"script", "style"} for frame in self.stack):
            return
        summary = any(frame["tag"] == "summary" for frame in self.stack)
        for key in self.active():
            if key == "notes" and summary:
                continue
            self.buffers[key].append(data)

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        classes = set((attributes.get("class") or "").split())
        if tag in self.BLOCK:
            self.append("\n")
        if tag == "a" and attributes.get("href"):
            self.hrefs.append(attributes["href"])
        if tag == "img" and attributes.get("src"):
            self.images.append(attributes["src"])
        card = next((frame.get("card") for frame in reversed(self.stack) if frame.get("card") is not None), None)
        if "business-card" in classes:
            found = re.fullmatch(r"business-(\d+)", attributes.get("id") or "")
            if found:
                card = int(found.group(1))
                if card in self.cards:
                    self.duplicates.append(f"business-{card}")
                self.cards[card] = {"status": attributes.get("data-status"), "attributes": attributes}
        capture = None
        for class_name, suffix in (("card-body", "body"), ("card-title", "title"), ("card-result", "result"), ("card-meta", "meta")):
            if class_name in classes and card is not None:
                capture = f"card:{card}:{suffix}"
        if "record-notes" in classes:
            capture = "notes"
        elif tag == "footer":
            capture = "footer"
        elif tag == "header":
            capture = "header"
        elif attributes.get("data-count-status"):
            capture = "stat:" + attributes["data-count-status"]
        if capture:
            if capture in self.buffers:
                self.duplicates.append(capture)
            self.buffers[capture] = []
        frame = {"tag": tag, "card": card, "capture": capture}
        if tag not in self.VOID:
            self.stack.append(frame)

    def handle_endtag(self, tag: str) -> None:
        if tag in self.BLOCK:
            self.append("\n")
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index]["tag"] == tag:
                del self.stack[index:]
                break

    def handle_data(self, data: str) -> None:
        self.append(data)

    def visible(self, key: str) -> str:
        return compact("".join(self.buffers.get(key, [])))


class Audit:
    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.checks: list[dict] = []
        self.details: dict = {}

    def path(self, value: str | Path) -> Path:
        raw = str(value)
        if raw.startswith(("\\\\", "//")):
            raise ValueError("不读取 UNC/网络路径")
        target = Path(value)
        target = (target if target.is_absolute() else self.root / target).resolve()
        if not target.is_relative_to(self.root):
            raise ValueError(f"路径越出工作区：{raw}")
        return target

    def check(self, name: str, passed: bool, **facts) -> None:
        self.checks.append({"name": name, "passed": bool(passed), **facts})

    def json(self, value: str | Path) -> dict:
        return json.loads(self.path(value).read_text(encoding="utf-8-sig"))

    def fingerprint(self, value: str | Path, expected: str, name: str, size: int | None = None) -> None:
        if not HEX.fullmatch(expected):
            raise ValueError(f"{name} 未提供合法 SHA256")
        path = self.path(value)
        if not path.is_file():
            self.check(name, False, path=str(path), reason="文件不存在")
            return
        actual = sha(path.read_bytes())
        self.check(name, actual == expected.lower() and (size is None or path.stat().st_size == size), path=str(path), expected_sha256=expected.lower(), actual_sha256=actual, bytes=path.stat().st_size, expected_bytes=size)

    def group(self, name: str, operation) -> None:
        try:
            operation()
        except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, tarfile.TarError) as error:
            self.check(name + ":输入/结构", False, error_type=type(error).__name__, reason=str(error))


def check_java(audit: Audit, record: dict) -> None:
    source_root = Path("ruoyi-backend/ruoyi-aipicture/src/main/java")
    compiled_root = Path("ruoyi-backend/ruoyi-aipicture/target/classes")
    sources, classes, contracts = record["source_sha256"], record["compiled_classes"], record["contracts"]
    audit.check("Java库存32源/43class/5契约", (len(sources), len(classes), len(contracts)) == (32, 43, 5), counts=[len(sources), len(classes), len(contracts)])
    actual_sources = {path.relative_to(audit.path(source_root)).as_posix() for path in audit.path(source_root).rglob("*.java")}
    audit.check("Java当前main源码集合", actual_sources == set(sources), unexpected=sorted(actual_sources - set(sources)), missing=sorted(set(sources) - actual_sources))
    for relative, expected in sources.items():
        audit.fingerprint(source_root / relative, expected, "Java源码:" + relative)
    audit.fingerprint(record["frozen_path"], record["total_sha256"], "Java冻结总包")
    with zipfile.ZipFile(audit.path(record["frozen_path"])) as total:
        members = [name for name in total.namelist() if name.startswith("BOOT-INF/lib/ruoyi-aipicture-") and name.endswith(".jar")]
        audit.check("总包内本项目模块唯一", len(members) == 1, members=members)
        if len(members) != 1:
            return
        module_bytes = total.read(members[0])
    audit.check("内嵌Java模块SHA", sha(module_bytes) == record["module_sha256"], actual_sha256=sha(module_bytes), expected_sha256=record["module_sha256"])
    with zipfile.ZipFile(io.BytesIO(module_bytes)) as module:
        members = set(module.namelist())
        packaged_classes = {name for name in members if name.startswith("com/ruoyi/aipicture/") and name.endswith(".class")}
        audit.check("模块43class集合", packaged_classes == set(classes), actual_count=len(packaged_classes))
        for relative, expected in classes.items():
            audit.check("内嵌class:" + relative, relative in members and sha(module.read(relative)) == expected)
            audit.fingerprint(compiled_root / relative, expected, "当前target/class:" + relative)
        for name, expected in contracts.items():
            member = "contracts/aipicture/v1/" + name
            audit.check("内嵌契约:" + name, member in members and sha(module.read(member)) == expected)
            audit.fingerprint(Path("contracts/aipicture/v1") / name, expected, "唯一源契约:" + name)


def check_python(audit: Audit, record: dict) -> None:
    sources = record["source_sha256"]
    audit.check("Python库存38源", len(sources) == 38, count=len(sources))
    frozen = audit.path(record["frozen_directory"])
    wheels, sdists = list(frozen.glob("*.whl")), list(frozen.glob("*.tar.gz"))
    audit.check("Python冻结wheel/sdist唯一", len(wheels) == len(sdists) == 1, wheel_count=len(wheels), sdist_count=len(sdists))
    if len(wheels) != 1 or len(sdists) != 1:
        return
    audit.fingerprint(wheels[0], record["wheel_sha256"], "Python冻结wheel")
    audit.fingerprint(sdists[0], record["sdist_sha256"], "Python冻结sdist")
    audit.fingerprint(Path("python/aipicture-runtime/uv.lock"), record["uv_lock_sha256"], "Python锁文件")
    source_root = Path("python/aipicture-runtime")
    actual_sources = {path.relative_to(audit.path(source_root)).as_posix() for path in audit.path(source_root / "aipicture_runtime").rglob("*.py")}
    audit.check("Python当前38源码集合", actual_sources == set(sources), unexpected=sorted(actual_sources - set(sources)), missing=sorted(set(sources) - actual_sources))
    with zipfile.ZipFile(wheels[0]) as wheel, tarfile.open(sdists[0], "r:gz") as sdist:
        wheel_members = set(wheel.namelist())
        sdist_members = [member for member in sdist.getmembers() if member.isfile()]
        wheel_sources = {name for name in wheel_members if name.startswith("aipicture_runtime/") and name.endswith(".py")}
        audit.check("wheel38源码集合", wheel_sources == set(sources), actual_count=len(wheel_sources))
        for relative, expected in sources.items():
            audit.fingerprint(source_root / relative, expected, "Python源码:" + relative)
            matching = [member for member in sdist_members if member.name.endswith("/" + relative)]
            wheel_ok = relative in wheel_members and sha(wheel.read(relative)) == expected
            stream = sdist.extractfile(matching[0]) if len(matching) == 1 else None
            sdist_ok = stream is not None and sha(stream.read()) == expected
            audit.check("源码/wheel/sdist:" + relative, wheel_ok and sdist_ok, wheel_match=wheel_ok, sdist_match=sdist_ok, sdist_member_count=len(matching))


def check_original_mapping(audit: Audit) -> None:
    appendix = audit.path(PROJECT / "源码核查附录.md")
    rows = re.findall(r"^\|\s*\[([^\]]+)\]\(([^)]+)\)\s*\|\s*`([0-9a-fA-F]{64})`\s*\|\s*$", appendix.read_text(encoding="utf-8-sig"), re.M)
    audit.check("原源码附录210条", len(rows) == 210 and len({row[1] for row in rows}) == 210, count=len(rows))
    for label, link, expected in rows:
        target = appendix.parent / unquote(urlsplit(link.strip("<>")).path)
        audit.fingerprint(target, expected, "原始参考源码:" + label)
    manifest_path = Path("frontend/aipicture-workbench/migration-coverage.json")
    manifest = audit.json(manifest_path)
    files = manifest["files"]
    audit.check("前端原始映射75条", manifest["total_source_files"] == len(files) == 75 and len({row["source"] for row in files}) == 75, count=len(files))
    for row in files:
        audit.fingerprint(Path(manifest["source"]) / row["source"], row["source_sha256"], "前端原参考:" + row["source"])
        if row.get("target") is not None:
            target = audit.path(Path(manifest["target"]) / row["target"])
            audit.check("前端迁移目标存在:" + row["source"], target.is_file(), path=str(target), mapping_status=row.get("status"))
        else:
            audit.check("前端无文件替代有明确理由:" + row["source"], bool(row.get("status") and row.get("reason")), mapping_status=row.get("status"))
    for added in manifest.get("target_added_pages", []):
        audit.check("前端新增页面存在:" + added, audit.path(Path(manifest["target"]) / added).is_file())
    audit.details["reference_mapping"] = {"appendix_sha256": sha(appendix.read_bytes()), "coverage_sha256": sha(audit.path(manifest_path).read_bytes()), "scope": "核对原引用hash及目标存在，不据此推断联机或业务完成"}


def specifications(values: list[str]) -> list[tuple[str, str]]:
    result = []
    for value in values:
        path, separator, digest = value.rpartition("=")
        if not separator or not path or not HEX.fullmatch(digest):
            raise ValueError("指纹参数必须为 文件路径=64位SHA256")
        result.append((path, digest.lower()))
    return result


def check_react(audit: Audit, record_path: str, bundles: list[tuple[str, str]]) -> None:
    record = audit.json(record_path)
    sources = record["sources"]
    audit.check("React最终源码路径无重复", len({row["path"] for row in sources}) == len(sources), source_count=len(sources))
    for row in sources:
        audit.fingerprint(row["path"], row["sha256"], "React最终源码:" + row["path"], row.get("bytes"))
    audit.check("最终JS四库为互异路径且同一内容", len(bundles) == len({str(audit.path(path)) for path, _ in bundles}) == 4 and len({digest for _, digest in bundles}) == 1)
    for path, digest in bundles:
        audit.fingerprint(path, digest, "最终JS副本:" + path)
    current_bundle = record["checks"]["library_build"]["bundle"]
    audit.fingerprint(current_bundle["path"], current_bundle["sha256"], "React最终记录的当前库", current_bundle.get("bytes"))
    audit.check("React当前库与四库指纹一致", {digest for _, digest in bundles} == {current_bundle["sha256"]})
    audit.details["react_record"] = {"path": str(audit.path(record_path)), "sha256": sha(audit.path(record_path).read_bytes()), "historical_fields_not_compared_to_current": ["preserved_previous_stage", "preserved_before_bundle", "previous_stage"], "note": "历史阶段不是当前版本，未递归要求历史指纹等于当前；此项不新增测试计数"}


def parse_cards(source: str) -> tuple[list[dict], str]:
    start = re.search(r"^## 2\. 首个验收操作前的全部业务清单\s*$", source, re.M)
    finish = re.search(r"^## 3\. 当前统计与后续填报\s*$", source, re.M)
    if not start or not finish or finish.start() <= start.end():
        raise ValueError("业务清单/统计段边界不完整")
    section = source[start.end():finish.start()]
    headings = list(re.finditer(r"^### (\d+)\. (.+)$", section, re.M))
    if [int(match.group(1)) for match in headings] != list(range(1, 23)):
        raise ValueError("业务项编号不是完整1–22")
    cards = []
    for index, heading in enumerate(headings):
        body = section[heading.end():headings[index + 1].start() if index + 1 < len(headings) else len(section)].strip()
        fields = {}
        for line in body.splitlines():
            field = re.match(r"^\s*(?:[-*]\s+)?\*\*([^*]+)\*\*[：:]\s*(.*)$", line)
            if not field:
                continue
            label, value = field.groups()
            if label == "原始结果/当前结果/截图/用户决定":
                columns = re.split(r"\s+/\s+", value, maxsplit=3)
                if len(columns) != 4:
                    raise ValueError(f"第{index + 1}项四列结果不完整")
                fields.update(result=columns[1], screenshot=columns[2], decision=columns[3])
            elif label in {"当前结果", "当前完整项结果", "复验结果"}:
                fields["result"] = value
            elif label == "截图情况":
                fields["screenshot"] = value
            elif label == "用户决定":
                fields["decision"] = value
        status = re.match(r"^(未通过|未执行|不适用|受阻|通过)(?:$|[\s：:，,。；;（(])", text_marks(fields["result"]))
        if not status:
            raise ValueError(f"第{index + 1}项当前结果不能识别")
        cards.append({"number": index + 1, "title": heading.group(2), "body": body, "status": status.group(1), **fields})
    return cards, source[finish.start():]


def check_visible_fragments(audit: Audit, name: str, source: str, actual: str) -> None:
    fragments = source_fragments(source)
    cursor = 0
    missing = []
    for fragment in fragments:
        found = actual.find(fragment, cursor)
        if found < 0:
            missing.append(fragment)
        else:
            cursor = found + len(fragment)
    audit.check(name, not missing and bool(fragments), expected_fragment_count=len(fragments), missing_fragments=missing, visible_text_sha256=sha(actual.encode("utf-8")))


def local_links(source: str) -> set[str]:
    return {match.group(2).strip().removeprefix("<").removesuffix(">") for match in LINK.finditer(source) if not urlsplit(match.group(2).strip("<>")).scheme and not match.group(2).startswith("//")}


def check_changed_links(audit: Audit, current: Path, before: Path) -> None:
    now = audit.path(current)
    old = audit.path(before)
    audit.check("相对链接的操作前原文存在:" + current.name, old.is_file(), before=str(old))
    if not old.is_file():
        return
    affected = local_links(now.read_text(encoding="utf-8-sig")) - local_links(old.read_text(encoding="utf-8-sig"))
    for value in sorted(affected):
        parts = urlsplit(value)
        if not parts.path:
            # 本轮新增本页锚点也核对实际标题，避免只看到 '#' 就略过。
            headings = re.findall(r"^#{1,6}\s+(.+)$", now.read_text(encoding="utf-8-sig"), re.M)
            anchors = {re.sub(r"[^\w\-\u4e00-\u9fff ]", "", text_marks(title).lower()).replace(" ", "-") for title in headings}
            audit.check("新增本页锚点:" + value, unquote(parts.fragment) in anchors, document=str(now))
        else:
            target = audit.path(now.parent / unquote(parts.path))
            audit.check("新增相对链接:" + value, target.is_file(), document=str(now), target=str(target), fragment=unquote(parts.fragment))
    audit.details.setdefault("affected_links", []).append({"document": str(now), "before": str(old), "new_or_changed_destinations": sorted(affected), "scope": "按本轮操作前原文作目标集合差异；跨文件锚点记录但不推断未执行页面效果"})


def check_report(audit: Audit, args, images: list[tuple[str, str]]) -> None:
    business = audit.path(args.business)
    implementation = audit.path(args.implementation)
    html_path = audit.path(args.report or business.parent / "verification-report.html")
    raw = business.read_bytes()
    source = raw.decode("utf-8-sig")
    html_source = html_path.read_text(encoding="utf-8-sig")
    parser = VisibleReport()
    parser.feed(html_source)
    parser.close()
    audit.check("HTML解析无重复业务作用域", not parser.duplicates, duplicates=parser.duplicates)
    audit.check("MD记录版本r11", f"记录版本：{args.version}" in source or f"记录版本:{args.version}" in source, expected_version=args.version)
    audit.check("HTML记录版本r11", f"记录版本：{args.version}" in parser.visible("header"), expected_version=args.version)
    footer_sha = re.findall(r"源记录 SHA256[：:]\s*([0-9a-fA-F]{64})", parser.visible("footer"))
    audit.check("HTML嵌入源MD字节SHA", footer_sha == [sha(raw)], expected_sha256=sha(raw), embedded_sha256=footer_sha)
    cards, notes = parse_cards(source)
    counts = {status: Counter(card["status"] for card in cards)[status] for status in EXPECTED_COUNTS}
    decisions = sum(text_marks(card["decision"]).startswith("待确认") for card in cards)
    audit.check("业务22项0通过4未执行18受阻", counts == EXPECTED_COUNTS, actual=counts, expected=EXPECTED_COUNTS)
    audit.check("用户决定22待确认", decisions == 22, pending=decisions)
    audit.check("HTML完整22卡片", set(parser.cards) == set(range(1, 23)), actual_card_numbers=sorted(parser.cards))
    for card in cards:
        number = card["number"]
        actual = parser.cards.get(number, {})
        audit.check(f"卡片{number}:状态/标题/摘要/用户决定", actual.get("status") == card["status"] and parser.visible(f"card:{number}:title") == card["title"] and text_marks(parser.visible(f"card:{number}:result")) == text_marks(card["result"]) and "用户决定：" + card["decision"] in parser.visible(f"card:{number}:meta") and "hidden" not in actual.get("attributes", {}))
        check_visible_fragments(audit, f"卡片{number}:全部可见正文顺序", card["body"], parser.visible(f"card:{number}:body"))
    check_visible_fragments(audit, "notes:全部可见正文顺序", notes, parser.visible("notes"))
    for status, expected in EXPECTED_COUNTS.items():
        match = re.match(r"^(\d+)\s*" + re.escape(status) + r"$", parser.visible("stat:" + status))
        audit.check("HTML统计:" + status, bool(match) and int(match.group(1)) == expected, actual=parser.visible("stat:" + status))
    # 明确保留既有事实；全文片段检查另外覆盖本轮新追加段，而非只验这些关键词。
    required_notes = ["Pydantic构造请求时失败", "失租0上传/0回执", "当前serve matting loaded=true", "SAM/OCR loaded=false", "原灰度AI→Al仍未通过", *args.note_required]
    normalized_notes = text_marks(notes)
    for required in required_notes:
        audit.check("notes关键事实:" + required, text_marks(required) in normalized_notes and text_marks(required) in parser.visible("notes"))
    check_changed_links(audit, business, Path(args.business_before))
    check_changed_links(audit, implementation, Path(args.implementation_before))
    referenced = set()
    for document in (business, implementation):
        for value in local_links(document.read_text(encoding="utf-8-sig")):
            if urlsplit(value).path:
                referenced.add(str(audit.path(document.parent / unquote(urlsplit(value).path))))
    audit.check("新证据图片有提供指纹且路径互异", bool(images) and len({str(audit.path(path)) for path, _ in images}) == len(images), image_count=len(images))
    for path, digest in images:
        audit.fingerprint(path, digest, "新证据图片:" + path)
        resolved = audit.path(path)
        audit.check("新证据图片已入两唯一MD链接:" + path, str(resolved) in referenced and resolved.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".gif"})
    audit.details["report"] = {"business_md": str(business), "business_sha256": sha(raw), "implementation_md": str(implementation), "implementation_sha256": sha(implementation.read_bytes()), "html": str(html_path), "html_sha256": sha(html_path.read_bytes()), "record_version": args.version, "business_snapshot": {"cards": 22, "counts": counts, "user_acceptance_pending": decisions}, "visible_text_scope": "独立HTMLParser读取卡片body与notes的实际文本节点，逐个原文片段有序核对；不运行生成器，不作页面/截图回读验收"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--artifacts", default=".runtime/aipicture/remaining-completion-artifacts.json")
    parser.add_argument("--react-record", required=True, help="最终当前React记录，不传早期阶段记录")
    parser.add_argument("--bundle", action="append", required=True, help="四份JS的路径=SHA256，重复四次")
    parser.add_argument("--evidence-image", action="append", default=[], help="本轮新证据图片路径=SHA256，可重复")
    parser.add_argument("--image-manifest", help="额外图片指纹JSON：images数组，每项path/sha256")
    parser.add_argument("--business", default=str(BUSINESS))
    parser.add_argument("--implementation", default=str(IMPLEMENTATION))
    parser.add_argument("--report", help="默认业务MD同目录verification-report.html")
    parser.add_argument("--version", default="2026-10-03-r11")
    parser.add_argument("--business-before", default=str(BEFORE / "业务验收记录.md"))
    parser.add_argument("--implementation-before", default=str(BEFORE / "2026-10-03-完整迁移实施.md"))
    parser.add_argument("--note-required", action="append", default=[], help="本轮追加的关键notes文字，可重复；全文核对仍照常")
    parser.add_argument("--output", required=True, help="新的.runtime/aipicture/*.json；绝不覆盖原报告")
    args = parser.parse_args()
    audit = Audit(Path(args.root))
    try:
        output = audit.path(args.output)
        if not output.is_relative_to(audit.path(".runtime/aipicture")) or output.suffix != ".json" or output.exists() or not output.parent.is_dir():
            raise ValueError("output必须为已存在私有目录中的全新JSON文件，禁止覆盖")
        bundles = specifications(args.bundle)
        if len(bundles) != 4:
            raise ValueError("必须提供四份最终JS的指纹")
        images = specifications(args.evidence_image)
        if args.image_manifest:
            image_record = audit.json(args.image_manifest)
            images.extend((row["path"], row["sha256"]) for row in image_record["images"])
        if not images:
            raise ValueError("必须提供新证据图片指纹，不能把未提供当核对通过")
        frozen = audit.json(args.artifacts)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print("参数/输入无效：" + str(error))
        return 2
    audit.group("Java制品", lambda: check_java(audit, frozen["java"]))
    audit.group("Python制品", lambda: check_python(audit, frozen["python"]))
    audit.group("原来源映射", lambda: check_original_mapping(audit))
    audit.group("React最终制品", lambda: check_react(audit, args.react_record, bundles))
    audit.group("r11报告与证据", lambda: check_report(audit, args, images))
    passed = all(check["passed"] for check in audit.checks)
    result = {"created_at": datetime.now(timezone.utc).isoformat(), "static_only": True, "scope": "冻结制品/原来源映射/两唯一MD受影响链接/r11HTML可见正文与判定/新增图片指纹", "passed": passed, "checks_total": len(audit.checks), "checks_failed": sum(not check["passed"] for check in audit.checks), "technical_tests_executed": 0, "business_operations_executed": 0, "business_acceptance": False, "network_or_database_or_model_or_process_operations": False, "frozen_record": {"path": str(audit.path(args.artifacts)), "sha256": sha(audit.path(args.artifacts).read_bytes())}, "script_sha256": sha(Path(__file__).read_bytes()), "stage_rule": "只将显式最终冻结记录对当前源码/制品；历史记录和保留阶段不要求等于当前，不重复累计测试或业务通过", "details": audit.details, "checks": audit.checks}
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"static_passed": passed, "checks_total": result["checks_total"], "checks_failed": result["checks_failed"], "technical_tests_executed": 0, "business_acceptance": False, "output": str(output)}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
