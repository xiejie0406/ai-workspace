"""本地文本/HTML 抽取和可回查切分，不读取任意 URL/磁盘文件。

PDF/Office/图片/OCR 未经过依赖与真实文档验证，明确拒绝而非假装抽取成功。
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from html.parser import HTMLParser

from worker.errors import WorkerError


@dataclass(frozen=True)
class Chunk:
    id: str
    document_id: str
    document_hash: str
    start: int
    end: int
    text: str


class _HtmlText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.suppressed = 0
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style", "noscript", "template"}:
            self.suppressed += 1
        elif not self.suppressed and tag in {"p", "div", "br", "li", "h1", "h2", "h3", "tr"}:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in {"script", "style", "noscript", "template"}:
            self.suppressed = max(0, self.suppressed - 1)
        elif not self.suppressed and tag in {"p", "div", "li", "h1", "h2", "h3", "tr"}:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.suppressed:
            self.parts.append(data)


def extract(content: bytes, mime: str, *, max_bytes: int = 20 * 1024 * 1024) -> str:
    if not isinstance(content, bytes) or not content or len(content) > max_bytes:
        raise WorkerError("DSL_INVALID", "知识文件为空或超过抽取预算")
    if mime not in {"text/plain", "text/markdown", "text/html"}:
        raise WorkerError("ENGINE_UNSUPPORTED", "当前抽取器未验证此文件格式")
    try:
        text = content.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise WorkerError("DSL_INVALID", "知识文本不是合法 UTF-8") from None
    if "\x00" in text:
        raise WorkerError("DSL_INVALID", "知识文本包含二进制内容")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if mime == "text/html":
        parser = _HtmlText()
        parser.feed(text)
        parser.close()
        text = "".join(parser.parts)
    if not text.strip():
        raise WorkerError("DSL_INVALID", "文件未抽取出可索引文本")
    return text


def split(text: str, document_id: str, document_hash: str, *, size: int = 1000, overlap: int = 100) -> list[Chunk]:
    """offset 指抽取后的规范化文本字符；原对象 hash 可回查原文件。"""
    if not isinstance(text, str) or not text.strip() or not document_id or not document_hash:
        raise WorkerError("DSL_INVALID", "知识切分缺少文本或来源")
    if type(size) is not int or type(overlap) is not int or not 16 <= size <= 100_000 or not 0 <= overlap < size // 2:
        raise WorkerError("DSL_INVALID", "知识切分大小/重叠范围无效")
    chunks = []
    start = 0
    while start < len(text):
        end = min(start + size, len(text))
        if end < len(text):
            paragraph = text.rfind("\n", start + size // 2, end)
            if paragraph >= 0:
                end = paragraph + 1
        value = text[start:end]
        if value.strip():
            identity = hashlib.sha256(f"{document_id}:{document_hash}:{start}:{end}".encode()).hexdigest()
            chunks.append(Chunk(identity, document_id, document_hash, start, end, value))
        if end == len(text):
            break
        start = max(end - overlap, start + 1)
    return chunks
