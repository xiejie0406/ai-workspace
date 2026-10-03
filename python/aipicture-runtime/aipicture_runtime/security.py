"""同 Java 的服务签名；时间窗口与 nonce 防重放仅是短时凭据缓存。"""
from __future__ import annotations

import hashlib
import hmac
import re
import threading
import time
import uuid


class CredentialError(ValueError):
    pass


def canonical(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> bytes:
    return "\n".join([method.upper(), path, timestamp, nonce, hashlib.sha256(body).hexdigest()]).encode("utf-8")


def sign_headers(secret: str, service: str, method: str, path: str, body: bytes, *, timestamp: str | None = None, nonce: str | None = None) -> dict[str, str]:
    if len(secret.encode()) < 32:
        raise CredentialError("服务密钥必须至少 32 字节")
    timestamp = timestamp or str(int(time.time()))
    nonce = nonce or uuid.uuid4().hex
    signature = hmac.new(secret.encode(), canonical(method, path, timestamp, nonce, body), hashlib.sha256).hexdigest()
    return {"X-Aipicture-Service": service, "X-Aipicture-Timestamp": timestamp,
            "X-Aipicture-Nonce": nonce, "X-Aipicture-Signature": signature}


class CredentialVerifier:
    def __init__(self, secret: str, service: str, skew_seconds: int = 120):
        self.secret, self.service, self.skew_seconds = secret, service, skew_seconds
        self._seen: dict[str, float] = {}
        self._lock = threading.Lock()

    def verify(self, method: str, path: str, body: bytes, headers: dict, *, now: float | None = None) -> None:
        values = {key.lower(): value for key, value in headers.items()}
        if len(self.secret.encode()) < 32 or values.get("x-aipicture-service") != self.service:
            raise CredentialError("服务凭据未配置或无效")
        timestamp = values.get("x-aipicture-timestamp", "")
        nonce = values.get("x-aipicture-nonce", "")
        if not re.fullmatch(r"\d{10,12}", timestamp) or not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", nonce):
            raise CredentialError("签名时间戳或 nonce 格式无效")
        current = time.time() if now is None else now
        if abs(current - int(timestamp)) > self.skew_seconds:
            raise CredentialError("服务签名已过期")
        expected = hmac.new(self.secret.encode(), canonical(method, path, timestamp, nonce, body), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, values.get("x-aipicture-signature", "")):
            raise CredentialError("服务签名无效")
        with self._lock:
            self._seen = {key: deadline for key, deadline in self._seen.items() if deadline > current}
            if nonce in self._seen:
                raise CredentialError("服务签名 nonce 已使用")
            if len(self._seen) >= 10_000:
                raise CredentialError("服务凭据缓存繁忙")
            self._seen[nonce] = current + self.skew_seconds * 2
