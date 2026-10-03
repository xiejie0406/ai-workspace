"""仅保存未确认事件的瞬时顺序，不持有业务数据库或发布状态。"""
from __future__ import annotations

import asyncio
import copy
import json
from datetime import datetime, timezone
from typing import Any

from .errors import WorkerError


EVENT_TYPES = frozenset({"node.started", "node.completed", "node.failed", "text.delta", "reasoning.delta",
                         "usage", "artifact", "checkpoint", "run.completed", "run.failed", "run.cancelled"})
WORKER_EVENT_TYPES = EVENT_TYPES - {"run.completed", "run.failed", "run.cancelled"}
SECRET_KEYS = frozenset({"password", "passwd", "apikey", "apisecret", "accesstoken", "refreshtoken", "authorization", "secretkey"})


def _validate_payload(value, depth=0):
    if depth > 64:
        raise WorkerError("DSL_INVALID", "事件数据嵌套超过预算")
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise WorkerError("DSL_INVALID", "事件对象键无效")
            if key.lower().replace("_", "").replace("-", "") in SECRET_KEYS and item:
                raise WorkerError("TOOL_POLICY_DENIED", "事件不能携带明文凭据")
            _validate_payload(item, depth + 1)
    elif isinstance(value, list):
        for item in value:
            _validate_payload(item, depth + 1)


class EventPublisher:
    def __init__(self, center, run_id: str, lease_id: str, attempt: int, last_seq: int):
        self.center = center
        self.run_id = run_id
        self.lease_id = lease_id
        self.attempt = attempt
        self.last_seq = last_seq
        self._lock = asyncio.Lock()
        self._unconfirmed = None

    async def emit(self, event_type: str, payload: dict[str, Any], *, node_id: str | None = None,
                   iteration_id: str | None = None) -> dict[str, Any]:
        if event_type not in EVENT_TYPES or not isinstance(payload, dict):
            raise WorkerError("DSL_INVALID", "未知事件类型或无效事件数据")
        if event_type not in WORKER_EVENT_TYPES:
            raise WorkerError("TOOL_POLICY_DENIED", "运行终态事件只能由中心 CAS 生成")
        _validate_payload(payload)
        try:
            encoded = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        except (ValueError, TypeError, UnicodeError):
            raise WorkerError("DSL_INVALID", "事件数据不是有效 JSON") from None
        if len(encoded) > 2 * 1024 * 1024:
            raise WorkerError("DSL_INVALID", "事件超过预算，应保存为授权 artifact")
        async with self._lock:
            if self._unconfirmed is not None:
                raise WorkerError("EFFECT_UNKNOWN", "前一事件尚未得到中心确认，不能改变序号继续执行")
            event = {"schemaVersion": "paiflow.events.v1", "runId": self.run_id, "leaseId": self.lease_id,
                     "attempt": self.attempt, "seq": self.last_seq + 1, "eventType": event_type,
                     "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"), "payload": copy.deepcopy(payload)}
            if node_id is not None:
                event["nodeId"] = node_id
            if iteration_id is not None:
                event["iterationId"] = iteration_id
            self._unconfirmed = event
            await self.center.append_event(self.run_id, copy.deepcopy(event))
            self.last_seq = event["seq"]
            self._unconfirmed = None
            return event

    async def reconcile(self) -> None:
        """只重发同一 seq/body；中心必须按 run/attempt/seq 去重。"""
        async with self._lock:
            if self._unconfirmed is not None:
                await self.center.append_event(self.run_id, copy.deepcopy(self._unconfirmed))
                self.last_seq = self._unconfirmed["seq"]
                self._unconfirmed = None
