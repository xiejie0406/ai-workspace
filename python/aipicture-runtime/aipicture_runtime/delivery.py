"""已计算产物的有限传输副本；正式租约和采用决定仍完全来自 Java。"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import uuid
from functools import wraps
from threading import RLock


def _serialized(method):
    @wraps(method)
    def guarded(self, *args, **kwargs):
        with self._io:
            return method(self, *args, **kwargs)
    return guarded


class DeliveryJournal:
    MAX_ENTRIES = 4

    def __init__(self, settings):
        if settings.delivery_spool_directory is None:
            raise ValueError("领取循环须配置 AIPICTURE_DELIVERY_SPOOL_DIRECTORY 私有绝对目录")
        self.root = settings.delivery_spool_directory.absolute()
        if any(path.is_symlink() or path.is_junction() for path in [self.root, *self.root.parents]):
            raise ValueError("产物交付缓存不允许符号链接或目录联接")
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.binding = {"center": settings.center_url.rstrip("/"), "worker": settings.service_name}
        self.limit = settings.max_output_bytes * 2 + settings.max_request_bytes
        self._lock = None
        self._io = RLock()

    @_serialized
    def open(self):
        path = self.root / ".worker.lock"
        if path.is_symlink():
            raise ValueError("非法交付缓存锁路径")
        self._lock = path.open("a+b")
        try:
            if os.name == "nt":
                import msvcrt
                if path.stat().st_size == 0:
                    self._lock.write(b"0"); self._lock.flush()
                self._lock.seek(0)
                msvcrt.locking(self._lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self._lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except Exception:
            self._lock.close(); self._lock = None
            raise RuntimeError("交付缓存已由其他领取进程持有，不能并行重交") from None

    @_serialized
    def close(self):
        if self._lock is not None:
            self._lock.close(); self._lock = None

    def _name(self, claim):
        run = str(uuid.UUID(str(claim["run_id"])))
        attempt = claim["attempt"]
        if isinstance(attempt, bool) or not isinstance(attempt, int) or not 1 <= attempt <= 100:
            raise ValueError("交付缓存尝试版本无效")
        return f"{run}.{attempt}.json"

    @staticmethod
    def _encode(value):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")

    def _path(self, record):
        return self.root / self._name(record["claim"])

    def _files(self):
        files = sorted(self.root.glob("*.json"))
        entries = {path.stem for path in files} | {path.stem for path in self.root.glob("*.part")}
        if len(entries) > self.MAX_ENTRIES:
            raise ValueError("交付缓存超过4份上限，请先核对隔离副本")
        return files

    @_serialized
    def has_capacity(self):
        files = self._files()
        return len({path.stem for path in files} | {path.stem for path in self.root.glob("*.part")}) < self.MAX_ENTRIES

    @_serialized
    def save(self, record):
        if self._lock is None:
            raise RuntimeError("交付缓存进程锁未持有")
        path = self._path(record)
        if path.is_symlink() or not path.exists() and not self.has_capacity():
            raise ValueError("交付缓存已满或路径无效，不能开始新的计算")
        payload = self._encode(record)
        packed = self._encode({"sha256": hashlib.sha256(payload).hexdigest(), "record": record})
        if len(packed) > self.limit:
            raise ValueError("已计算产物超出耐久交付副本字节上限")
        # 每次仅一个原子替换；意外中断留下的 .part 既不重算也不当作已确认结果。
        temporary = path.with_suffix(".part")
        if temporary.is_symlink():
            raise ValueError("交付缓存临时路径无效")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(packed); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
        self._sync_directory()

    def _sync_directory(self):
        if os.name != "nt":
            descriptor = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)

    @_serialized
    def reserve(self, claim):
        record = {"version": 1, "binding": self.binding, "claim": claim,
                  "computed": None, "receipt": None, "quarantined": False}
        if self._path(record).exists():
            raise ValueError("此尝试已有交付副本，不能重新调用算法或供应商")
        self.save(record)
        return record

    @_serialized
    def pending(self):
        # 原子替换前退出时，完整且校验一致的临时副本也可恢复；不完整文件保留并停止领取。
        for temporary in sorted(self.root.glob("*.part")):
            record = self._read(temporary)
            if self._path(record).with_suffix(".part") != temporary:
                raise ValueError("交付临时副本任务名不匹配")
            os.replace(temporary, self._path(record))
            self._sync_directory()
        records = []
        for path in self._files():
            record = self._read(path)
            if self._path(record) != path:
                raise ValueError("交付副本任务名不匹配")
            if not record.get("quarantined"):
                records.append(record)
        return records

    def _read(self, path):
        if path.is_symlink() or path.stat().st_size > self.limit:
            raise ValueError("交付缓存文件越界或过大")
        packed = json.loads(path.read_bytes())
        record = packed["record"]
        if packed["sha256"] != hashlib.sha256(self._encode(record)).hexdigest():
            raise ValueError("交付缓存指纹不匹配；保留原文，禁止重交或重算")
        if record.get("version") != 1 or record.get("binding") != self.binding:
            raise ValueError("交付副本与中心或执行身份不匹配")
        return record

    @_serialized
    def quarantine(self, record, reason):
        record["quarantined"] = True
        record["reason"] = reason
        self.save(record)

    @_serialized
    def confirmed(self, record):
        # 仅在中心确认同一回执后删除传输副本，不接触正式素材或任务。
        path = self._path(record)
        if path.is_symlink():
            raise ValueError("交付缓存路径无效")
        path.unlink(missing_ok=True)
        path.with_suffix(".part").unlink(missing_ok=True)
        self._sync_directory()
