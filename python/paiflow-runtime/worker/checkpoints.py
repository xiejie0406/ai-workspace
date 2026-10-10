"""中心持久检查点适配；原 QA 算法只取得中心一次性消费的原回复。

不加载原 Redis EventRegistry、不生成用户输入，局部字段只是当前请求/ACK关联。
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import hashlib
import json

from .errors import CenterUnavailable, WorkerError
from .events import _validate_payload
from .node_context import CURRENT_ITERATION_ID
from .task_runner import current_context


class CenterCheckpoints:
    def __init__(self, node_id, need_reply, timeout_seconds, max_rounds):
        self.context = current_context()
        if node_id not in self.context.node_ids:
            raise WorkerError("TOOL_POLICY_DENIED", "检查点节点不属于固定 DSL")
        self.node_id, self.need_reply = node_id, need_reply
        self.timeout_seconds, self.max_rounds = timeout_seconds, max_rounds
        self.iteration_id = CURRENT_ITERATION_ID.get()
        if self.iteration_id is not None:
            raise WorkerError("RESOURCE_UNAVAILABLE", "中心尚未绑定迭代内问答检查点，不转为顶层归属")
        self.ordinal = 0
        self.ack = None
        self.consumed_checkpoint_id = None
        self.deadline = None

    async def announce(self, question):
        context = self.context
        context.assert_active()
        try:
            _validate_payload(question)
            ordinal = self.ordinal + 1
            if ordinal > self.max_rounds:
                raise WorkerError("DSL_INVALID", "原问答重问超过固定次数")
            fingerprint = hashlib.sha256(json.dumps(question, ensure_ascii=False, sort_keys=True, allow_nan=False,
                separators=(",", ":")).encode("utf-8")).hexdigest()
            key = self._key("checkpoint", context.assignment.run_id, context.assignment.attempt,
                self.node_id, self.iteration_id, ordinal, fingerprint)
            ack = await context.center.create_checkpoint(context.assignment.run_id, context.assignment.lease_id,
                context.assignment.attempt, self.node_id, ordinal, question, self.need_reply, self.timeout_seconds,
                key, iteration_id=self.iteration_id)
            context.assert_active()
            deadline = datetime.fromisoformat(ack["deadline"].replace("Z", "+00:00"))
            if (not isinstance(ack.get("checkpointId"), str) or not ack["checkpointId"]
                    or type(ack.get("revision")) is not int or ack["revision"] < 0
                    or ack.get("state") not in {"WAITING", "RESUMED", "CONSUMED", "EXPIRED", "CANCELLED"}
                    or type(ack.get("stopped")) is not bool
                    or ack["stopped"] != (ack["state"] in {"CONSUMED", "EXPIRED", "CANCELLED"}) or deadline.tzinfo is None
                    or deadline > context.assignment.deadline
                    or (deadline - datetime.now(timezone.utc)).total_seconds() > self.timeout_seconds + 1):
                raise CenterUnavailable()
            if ack["state"] == "EXPIRED":
                self._expired()
            if ack["state"] == "CANCELLED":
                raise WorkerError("CANCEL_REQUESTED", "中心检查点已取消，不重新进入等待")
            if ack["state"] == "CONSUMED":
                raise WorkerError("VERSION_CONFLICT", "中心检查点已消费，不重开或伪造新的回复")
            self.ack, self.deadline, self.ordinal = ack, deadline, ordinal
            self.consumed_checkpoint_id = None
            context.checkpoint_acks[ack["checkpointId"]] = {"checkpointId": ack["checkpointId"],
                "revision": ack["revision"], "deadline": ack["deadline"], "ordinal": ordinal,
                "nodeId": self.node_id, "iterationId": self.iteration_id}
            return ack["checkpointId"]
        except WorkerError as error:
            context.boundary_failure = error
            raise
        except Exception as error:
            from workflow.exception.e import CustomException
            if isinstance(error, CustomException):
                raise
            context.boundary_failure = CenterUnavailable()
            raise context.boundary_failure from None

    async def fetch(self):
        context = self.context
        try:
            if self.ack is None:
                raise WorkerError("RESOURCE_UNAVAILABLE", "问答检查点未获得中心创建确认")
            revision = self.ack["revision"]
            while True:
                context.assert_active()
                if datetime.now(timezone.utc) >= self.deadline:
                    self._expired()
                response = await context.center.poll_checkpoint(context.assignment.run_id, context.assignment.lease_id,
                    context.assignment.attempt, self.ack["checkpointId"], revision)
                context.assert_active()
                if (response.get("checkpointId") != self.ack["checkpointId"]
                        or type(response.get("revision")) is not int or response["revision"] < revision
                        or response.get("deadline") != self.ack["deadline"]
                        or type(response.get("stopped")) is not bool
                        or response["stopped"] != (response.get("state") in {"EXPIRED", "CANCELLED", "CONSUMED"})):
                    raise CenterUnavailable()
                revision = response["revision"]
                state = response.get("state")
                if state == "RESUMED":
                    key = self._key("consume", context.assignment.run_id, context.assignment.attempt,
                        self.ack["checkpointId"], revision)
                    consumed = await context.center.consume_checkpoint(context.assignment.run_id, context.assignment.lease_id,
                        context.assignment.attempt, self.ack["checkpointId"], revision, key)
                    context.assert_active()
                    if (consumed.get("stopped") is not True or consumed.get("deadline") != self.ack["deadline"]
                            or consumed.get("checkpointId") != self.ack["checkpointId"]
                            or type(consumed.get("revision")) is not int or consumed["revision"] <= revision):
                        raise CenterUnavailable()
                    if consumed.get("consumed") is False:
                        if consumed.get("state") == "EXPIRED":
                            self._expired()
                        if consumed.get("state") == "CANCELLED":
                            raise WorkerError("CANCEL_REQUESTED", "中心已取消检查点，消费没有获得确认")
                        raise CenterUnavailable()
                    resume = consumed.get("resumeData")
                    if (consumed.get("checkpointId") != self.ack["checkpointId"] or consumed.get("consumed") is not True
                            or consumed.get("state") != "CONSUMED"
                            or type(consumed.get("revision")) is not int or consumed["revision"] <= revision
                            or not isinstance(resume, dict) or set(resume) != {"message", "metadata"}
                            or not isinstance(resume["message"], str) or not isinstance(resume["metadata"], dict)
                            or set(resume["metadata"]) != {"retries", "timestamp"}
                            or type(resume["metadata"]["retries"]) is not int or resume["metadata"]["retries"] != self.ordinal - 1
                            or type(resume["metadata"]["timestamp"]) is not int or resume["metadata"]["timestamp"] < 1):
                        raise CenterUnavailable()
                    _validate_payload(resume)
                    message = json.loads(resume["message"])
                    if (not isinstance(message, dict) or set(message) != {"event_type", "content"}
                            or message["event_type"] not in {"resume", "ignore", "abort"} or not isinstance(message["content"], str)):
                        raise CenterUnavailable()
                    self.consumed_checkpoint_id = self.ack['checkpointId'] if message['event_type'] == 'resume' else None
                    return resume
                if state == "EXPIRED":
                    self._expired()
                if state == "CANCELLED":
                    raise WorkerError("CANCEL_REQUESTED", "中心已取消问答检查点")
                if state != "WAITING":
                    raise WorkerError("VERSION_CONFLICT", "问答检查点已消费或状态不允许恢复")
                await asyncio.sleep(.05)
        except WorkerError as error:
            context.boundary_failure = error
            raise

    @staticmethod
    def _key(operation, *parts):
        # 固定长度安全键；JSON tuple 保留 null/空串及字段边界，不能拼接碰撞。
        raw = json.dumps(parts, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
        return operation + "_" + hashlib.sha256(raw).hexdigest()

    @staticmethod
    def _expired():
        from workflow.exception.e import CustomException
        from workflow.exception.errors.err_code import CodeEnum
        raise CustomException(err_code=CodeEnum.EVENT_REGISTRY_NOT_FOUND_ERROR,
            err_msg="Timeout while waiting for user response")
