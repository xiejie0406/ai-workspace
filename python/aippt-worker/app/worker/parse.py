"""每份不可信文档在独立子进程解析；超时终止，避免占住 Worker 槽位。"""
import asyncio
import multiprocessing
import uuid
import time


def _parse_child(connection, filename: str, data: bytes):
    try:
        from app.ingest.upload import validate_upload
        from app.ingest.registry import parser_for
        validate_upload(filename, data)
        result = parser_for(filename).parse(data)
        connection.send({"ok": True, "document": result.model_dump(mode="json")})
    except Exception as error:
        # 用户错误类型可定位，但不回传解析器原异常（可能含材料正文）。
        connection.send({"ok": False, "error": type(error).__name__})
    finally:
        connection.close()


async def parse_document(filename: str, data: bytes, source_id: str) -> dict:
    from app.core.config import get_settings
    timeout = get_settings().parse_timeout_seconds
    ctx = multiprocessing.get_context("spawn")
    reader, writer = ctx.Pipe(duplex=False)
    process = ctx.Process(target=_parse_child, args=(writer, filename, data), daemon=True)
    process.start(); writer.close()
    try:
        deadline = time.monotonic() + timeout
        while not reader.poll():
            if time.monotonic() >= deadline: raise TimeoutError("文档解析超过时间预算")
            if not process.is_alive(): raise ValueError("文档解析子进程异常退出")
            await asyncio.sleep(.05)
        result = await asyncio.to_thread(reader.recv)
        if not result["ok"]: raise ValueError("文档解析失败：" + result["error"])
        document = result["document"]
    finally:
        reader.close()
        if process.is_alive(): process.terminate()
        await asyncio.to_thread(process.join, 5)
    return stable_sections(document, source_id, filename)


def stable_sections(document: dict, source_id: str, filename: str | None = None) -> dict:
    sections = []
    for index, section in enumerate(document.get("sections", []), 1):
        section_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"aippt:{source_id}:{index}"))
        sections.append({**section, "id": section_id, "ref": section_id})
    return {"sections": sections, "warnings": document.get("warnings", []), "filename": filename,
            "char_count": sum(len(s.get("text", "")) for s in sections), "parser_version": "aippt-parse-1"}
