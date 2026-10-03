"""可重建派生向量索引；没有任务/授权/发布状态或第二业务数据库。

embedding 必须来自已授权模型调用的实际结果，不提供随机/固定向量假索引。
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import tempfile
from dataclasses import asdict
from pathlib import Path
from typing import Iterable

from worker.errors import WorkerError
from worker.task_runner import RunContext

from .processor import Chunk


def _vector(value):
    if not isinstance(value, (list, tuple)) or not value or len(value) > 65536:
        raise WorkerError("DSL_INVALID", "embedding 维度无效")
    if any(type(number) not in {int, float} or not math.isfinite(number) for number in value):
        raise WorkerError("DSL_INVALID", "embedding 包含无效数值")
    norm = math.sqrt(sum(number * number for number in value))
    if not norm or not math.isfinite(norm):
        raise WorkerError("DSL_INVALID", "embedding 不能是零向量或溢出")
    return [number / norm for number in value]


class DerivedIndexStore:
    def __init__(self, directory: Path):
        self.directory = Path(directory).resolve()

    def _path(self, index_id: str, version: str) -> Path:
        if not all(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value) for value in (index_id, version)):
            raise WorkerError("DSL_INVALID", "派生索引标识包含无效路径")
        return self.directory / (index_id + "--" + version + ".json")

    def write(self, index_id: str, version: str, chunks: Iterable[Chunk], embeddings: Iterable[list[float]], model_version: str) -> dict:
        chunks = list(chunks)
        embeddings = list(embeddings)
        if not chunks or len(chunks) > 10000 or len(chunks) != len(embeddings) or not model_version:
            raise WorkerError("DSL_INVALID", "索引片段/真实 embedding/模型版本不匹配")
        vectors = [_vector(value) for value in embeddings]
        if len({len(vector) for vector in vectors}) != 1:
            raise WorkerError("DSL_INVALID", "索引 embedding 维度不一致")
        if len({chunk.id for chunk in chunks}) != len(chunks):
            raise WorkerError("DSL_INVALID", "索引片段标识重复")
        document_versions = {}
        for chunk in chunks:
            previous = document_versions.setdefault(chunk.document_id, chunk.document_hash)
            if previous != chunk.document_hash:
                raise WorkerError("VERSION_CONFLICT", "同一索引包含不同版本的同一文档")
        body = {"schemaVersion": "paiflow.derived-index.v1", "indexId": index_id, "version": version,
                "modelVersion": model_version, "dimensions": len(vectors[0]), "documentVersions": document_versions,
                "chunks": [asdict(chunk) for chunk in chunks], "vectors": vectors}
        encoded = json.dumps(body, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        if len(encoded) > 128 * 1024 * 1024:
            raise WorkerError("DSL_INVALID", "派生索引超过预算")
        path = self._path(index_id, version)
        self.directory.mkdir(parents=True, exist_ok=True)
        if path.is_symlink():
            raise WorkerError("TOOL_POLICY_DENIED", "派生索引路径不能是符号链接")
        if path.exists():
            if path.read_bytes() != encoded:
                raise WorkerError("VERSION_CONFLICT", "派生索引版本不可覆盖")
        else:
            with tempfile.NamedTemporaryFile(dir=self.directory, delete=False) as handle:
                temporary = Path(handle.name)
                handle.write(encoded)
            try:
                # version 的正式激活仍由中心 CAS 决定；这里只保存不可变派生文件。
                os.link(temporary, path)
            except FileExistsError:
                if path.read_bytes() != encoded:
                    raise WorkerError("VERSION_CONFLICT", "派生索引并发版本冲突") from None
            finally:
                temporary.unlink(missing_ok=True)
        return {"indexId": index_id, "version": version, "hash": hashlib.sha256(encoded).hexdigest(),
                "chunkCount": len(chunks), "documentVersions": document_versions}

    def search(self, index_id: str, version: str, query_vector: list[float], *, allowed_document_versions: dict[str, str],
               top_k: int = 5, threshold: float = 0, expected_hash: str | None = None) -> list[dict]:
        if type(top_k) is not int or not 1 <= top_k <= 100 or type(threshold) not in {int, float} or not -1 <= threshold <= 1:
            raise WorkerError("DSL_INVALID", "检索 TopK/阈值无效")
        path = self._path(index_id, version)
        if path.is_symlink() or not path.is_file():
            raise WorkerError("RESOURCE_UNAVAILABLE", "指定版本的派生索引不存在")
        if path.stat().st_size > 128 * 1024 * 1024:
            raise WorkerError("DSL_INVALID", "派生索引超过读取预算")
        content = path.read_bytes()
        if expected_hash is not None and hashlib.sha256(content).hexdigest() != expected_hash:
            raise WorkerError("VERSION_CONFLICT", "派生索引内容与中心 hash 不一致")
        try:
            body = json.loads(content)
        except (ValueError, UnicodeError):
            raise WorkerError("VERSION_CONFLICT", "派生索引损坏") from None
        query = _vector(query_vector)
        if body.get("schemaVersion") != "paiflow.derived-index.v1" or body.get("indexId") != index_id or body.get("version") != version or len(query) != body.get("dimensions"):
            raise WorkerError("VERSION_CONFLICT", "派生索引协议/版本/维度不一致")
        if not isinstance(body.get("chunks"), list) or not isinstance(body.get("vectors"), list) or len(body["chunks"]) != len(body["vectors"]):
            raise WorkerError("VERSION_CONFLICT", "派生索引片段与向量数量不一致")
        results = []
        for chunk, vector in zip(body["chunks"], body["vectors"]):
            if not isinstance(chunk, dict) or not isinstance(vector, list) or len(vector) != len(query):
                raise WorkerError("VERSION_CONFLICT", "派生索引片段/向量损坏")
            vector = _vector(vector)
            # 撤权/删除/版本变动由中心当前授权文档清单决定，旧文件仍在也不能返回。
            if allowed_document_versions.get(chunk["document_id"]) != chunk["document_hash"]:
                continue
            similarity = sum(left * right for left, right in zip(query, vector))
            if similarity >= threshold:
                results.append({**chunk, "score": similarity})
        results.sort(key=lambda value: (-value["score"], value["id"]))
        return results[:top_k]


async def retrieve(context: RunContext, store: DerivedIndexStore, resource_id: str, query_vector: list[float], *, top_k=5, threshold=0):
    resource = await context.resource(resource_id)
    versions = resource.get("allowedDocumentVersions")
    if not isinstance(versions, dict) or any(not isinstance(key, str) or not isinstance(value, str) for key, value in versions.items()):
        raise WorkerError("RESOURCE_UNAVAILABLE", "中心未提供当前授权文档版本清单")
    if resource.get("version") != context.assignment.resources[resource_id].get("version"):
        raise WorkerError("VERSION_CONFLICT", "知识资源已不属于本次运行快照版本")
    if not isinstance(resource.get("indexHash"), str) or not re.fullmatch(r"[a-f0-9]{64}", resource["indexHash"]):
        raise WorkerError("RESOURCE_UNAVAILABLE", "中心未提供当前派生索引内容 hash")
    context.assert_active()
    return store.search(resource.get("indexId"), resource.get("indexVersion"), query_vector,
                        allowed_document_versions=versions, top_k=top_k, threshold=threshold, expected_hash=resource["indexHash"])
