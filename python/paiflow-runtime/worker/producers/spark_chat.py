"""Spark Chat 原算法的受控 Producer；中心私有端口尚未接通。

只接收 operationId。中心须一次性领取许可并提供不可变参数/私有材料，
持有正式帧、费用、审批和终态；本模块没有本地业务状态或默认网络连接。
复用原组装/解码/HMAC，不调用原 achat/assemble_url 的日志和环境入口。
本地 close 仅清理流；取消必须另获中心核实的 Provider 停止证据。
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import hashlib
import json
import math
import re
import time
from typing import Protocol
from urllib.parse import urlsplit

from workflow.exception.errors.code_convert import CodeConvert
from workflow.infra.providers.llm.iflytek_spark.spark_chat_auth import SparkChatHmacAuth
from workflow.infra.providers.llm.iflytek_spark.spark_chat_llm import SparkChatAi, recv_with_retry

from worker.errors import WorkerError
from worker.events import _validate_payload
from worker.task_runner import _same_json


@dataclass(frozen=True, slots=True)
class SparkLimits:
    connect_seconds: float
    read_seconds: float
    total_seconds: float
    close_seconds: float
    max_frames: int
    max_frame_bytes: int
    max_response_bytes: int
    max_request_bytes: int


@dataclass(frozen=True, slots=True)
class SparkPublicConfig:
    resource_id: str
    kind: str
    provider: str
    version: str
    model_name: str
    enabled: bool
    options_json: bytes = field(repr=False)
    quickly_think: bool
    limits: SparkLimits


@dataclass(frozen=True, slots=True, repr=False)
class SparkPrivateMaterial:
    """仅受控中心私有通道可创建；不得序列化至 assignment/events/log。"""
    endpoint: str
    app_id: str
    uid: str
    api_key: str
    api_secret: str


@dataclass(frozen=True, slots=True, repr=False)
class SparkGrant:
    operation_id: str
    run_id: str
    lease_id: str
    attempt: int
    node_id: str
    workflow_version_id: str
    dsl_hash: str
    parameters_json: bytes
    parameters_sha256: str
    deadline_epoch: float
    config: SparkPublicConfig
    private: SparkPrivateMaterial


@dataclass(frozen=True, slots=True)
class FrameCommit:
    operation_id: str
    resource_id: str
    resource_version: str
    provider: str
    sequence: int
    state: str
    done: bool
    failure_kind: str | None = None


@dataclass(frozen=True, slots=True)
class StopConfirmation:
    operation_id: str
    resource_id: str
    resource_version: str
    provider: str
    state: str
    provider_stopped: bool
    proof_id: str | None


@dataclass(frozen=True, slots=True)
class FailureFacts:
    failure_kind: str
    code: str
    send_attempted: bool
    local_closed: bool
    provider_stop_confirmed: bool


@dataclass(frozen=True, slots=True)
class ProducerOutcome:
    """已确认的中心回执投影；不生成正式运行终态。"""
    commit: FrameCommit
    provider_error_code: int | None
    mapped_error_code: int | None
    local_closed: bool


class SparkProducerError(WorkerError):
    def __init__(self, code: str, failure_kind: str):
        super().__init__(code, "Spark Producer 边界未完成，须由中心核对")
        self.failure_kind = failure_kind
        self.send_attempted = False
        self.local_closed = False
        self.provider_stop_confirmed = False


class CenterSparkPort(Protocol):
    async def acquire(self, operation_id: str) -> SparkGrant:
        """中心 CAS 一次性许可；校验身份/活租约/begin/节点/版本/逐次审批。

        同操作二次领取必须拒绝，不重发许可。grant 是私有执行许可，不能
        用 assignment/前端字段构造；参数和公开 profile 来自固定中心版本。
        """
        ...

    async def assert_active(self, grant: SparkGrant) -> None:
        """校验当前许可/租约/撤权/取消；失败必须抛异常。"""
        ...

    async def commit_frame(self, grant: SparkGrant, sequence: int, frame: dict, *,
                           terminal: bool, provider_error_code: int | None) -> FrameCommit:
        """中心持久原帧并 CAS 操作状态，ACK 同绑定/序号；不可创建 Provider 请求。

        terminal 非错误为 COMPLETED/done；合法原错误为 FAILED/provider/done。
        同 sequence/同帧幂等，异帧拒绝；ACK 丢失由中心核对，本模块不重发。
        """
        ...

    async def confirm_stop(self, grant: SparkGrant, reason: str) -> StopConfirmation:
        """中心核对真实 Provider 停止/已终止证据，不能以本地 close 自授。"""
        ...

    async def record_failure(self, grant: SparkGrant, facts: FailureFacts) -> None:
        """记录固定、无秘密的事实；中心裁决终态/费用，不覆盖既有 Provider 终态。"""
        ...


class PrivateSparkStream(Protocol):
    async def send(self, payload: str) -> None: ...
    async def recv(self) -> str | bytes: ...
    async def close(self) -> None: ...


class PrivateSparkTransport(Protocol):
    async def open(self, authenticated_url: str, *, connect_timeout: float,
                   close_timeout: float) -> PrivateSparkStream:
        """私有受控连接，须支持取消且超时/异常后清理尚未交付的句柄。

        禁止日志/redirect/任意代理覆盖；实际实现须校验 TLS、固定出口并
        有界关闭。没有默认 websockets.connect；离线夹具不是正式 transport。
        """
        ...


def _json(raw: bytes | str):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def constant(_value):
        raise ValueError("nonfinite JSON")

    try:
        value = json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)
        _validate_payload(value)
        json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
        return value
    except (ValueError, TypeError, UnicodeError, RecursionError, WorkerError):
        raise SparkProducerError("DSL_INVALID", "protocol") from None


def _number(value, upper):
    return _finite(value) and 0 < value <= upper


def _finite(value):
    try:
        return type(value) in {int, float} and math.isfinite(value)
    except OverflowError:
        return False


def _validate(grant, operation_id):
    if not isinstance(grant, SparkGrant) or grant.operation_id != operation_id:
        raise SparkProducerError("RESOURCE_UNAVAILABLE", "protocol")
    strings = (grant.run_id, grant.lease_id, grant.node_id, grant.workflow_version_id)
    if (any(not isinstance(v, str) or not v for v in strings)
            or type(grant.attempt) is not int or grant.attempt < 1
            or not isinstance(grant.dsl_hash, str) or not re.fullmatch(r"[a-f0-9]{64}", grant.dsl_hash)
            or grant.node_id.split("::", 1)[0] not in {
                "spark-llm", "extractor-parameter", "decision-making", "question-answer"}
            or not _number(grant.deadline_epoch, 10**12)
            or not isinstance(grant.config, SparkPublicConfig)
            or not isinstance(grant.private, SparkPrivateMaterial)):
        raise SparkProducerError("RESOURCE_UNAVAILABLE", "protocol")
    config, private = grant.config, grant.private
    if (any(not isinstance(v, str) or not v for v in (config.resource_id, config.version, config.model_name))
            or config.kind != "model" or config.provider != "xinghuo" or config.enabled is not True
            or type(config.quickly_think) is not bool or not isinstance(config.options_json, bytes)
            or not isinstance(grant.parameters_json, bytes)
            or not isinstance(grant.parameters_sha256, str)
            or hashlib.sha256(grant.parameters_json).hexdigest() != grant.parameters_sha256):
        raise SparkProducerError("DSL_INVALID", "protocol")
    limits = config.limits
    if not isinstance(limits, SparkLimits):
        raise SparkProducerError("DSL_INVALID", "protocol")
    timings = ((limits.connect_seconds, 60), (limits.read_seconds, 600),
               (limits.total_seconds, 86400), (limits.close_seconds, 10))
    sizes = ((limits.max_frames, 10000), (limits.max_frame_bytes, 8 * 1024 * 1024),
             (limits.max_response_bytes, 8 * 1024 * 1024), (limits.max_request_bytes, 2 * 1024 * 1024))
    if (any(not _number(v, upper) for v, upper in timings)
            or any(type(v) is not int or not 1 <= v <= upper for v, upper in sizes)
            or limits.max_frame_bytes > limits.max_response_bytes
            or len(grant.parameters_json) > limits.max_request_bytes
            or len(config.options_json) > limits.max_request_bytes):
        raise SparkProducerError("DSL_INVALID", "protocol")
    if any(not isinstance(v, str) or not v for v in (
            private.endpoint, private.app_id, private.uid, private.api_key, private.api_secret)):
        raise SparkProducerError("RESOURCE_UNAVAILABLE", "protocol")
    try:
        endpoint = urlsplit(private.endpoint)
        valid_endpoint = (endpoint.scheme == "wss" and endpoint.hostname and endpoint.path
                          and not endpoint.username and not endpoint.password
                          and not endpoint.query and not endpoint.fragment and endpoint.port in {None, 443})
    except ValueError:
        valid_endpoint = False
    if not valid_endpoint:
        raise SparkProducerError("TOOL_POLICY_DENIED", "authorization")


def _contains_private(value, material):
    if isinstance(value, str):
        return any(secret in value for secret in (material.endpoint, material.api_key, material.api_secret))
    if isinstance(value, dict):
        return any(_contains_private(k, material) or _contains_private(v, material) for k, v in value.items())
    if isinstance(value, list):
        return any(_contains_private(v, material) for v in value)
    return False


def _request(grant):
    body, options = _json(grant.parameters_json), _json(grant.config.options_json)
    required = {"resourceId", "resourceVersion", "provider", "modelName", "mode", "messages", "options"}
    option_keys = {"temperature", "maxTokens", "topK", "patchId", "respFormat", "searchDisable", "extraParams"}
    if (not isinstance(body, dict) or not required <= set(body) or set(body) - required - {"timeoutSeconds"}
            or body["resourceId"] != grant.config.resource_id or body["resourceVersion"] != grant.config.version
            or body["provider"] != "xinghuo" or body["modelName"] != grant.config.model_name or body["mode"] != "chat"
            or not isinstance(options, dict) or set(options) != option_keys
            or not _same_json(body["options"], options)
            or not isinstance(body["messages"], list) or not body["messages"]
            or any(not isinstance(message, dict) for message in body["messages"])
            or not _finite(options["temperature"])
            or type(options["maxTokens"]) is not int or options["maxTokens"] < 1
            or type(options["topK"]) is not int or options["topK"] < 1
            or not isinstance(options["patchId"], list) or type(options["respFormat"]) is not int
            or options["respFormat"] not in {0, 1, 2} or type(options["searchDisable"]) is not bool
            or not isinstance(options["extraParams"], dict)
            or "timeoutSeconds" in body and (not _number(body["timeoutSeconds"], 600)
                or body["timeoutSeconds"] > grant.config.limits.read_seconds)
            or _contains_private(body, grant.private)):
        raise SparkProducerError("DSL_INVALID", "protocol")
    sdk = SparkChatAi(model_url="", model_name=grant.config.model_name,
        temperature=options["temperature"], app_id=grant.private.app_id, api_key="", api_secret="",
        max_tokens=options["maxTokens"], top_k=options["topK"], uid=grant.private.uid,
        spark_version=grant.config.version, patch_id=options["patchId"])
    payload = sdk.assemble_payload(body["messages"], search_disable=options["searchDisable"])
    if grant.config.quickly_think:
        value = _json(payload)
        value["parameter"]["chat"]["enable_thinking"] = False
        payload = json.dumps(value, ensure_ascii=False, allow_nan=False)
    if len(payload.encode("utf-8")) > grant.config.limits.max_request_bytes:
        raise SparkProducerError("DSL_INVALID", "protocol")
    return sdk, payload, body.get("timeoutSeconds", grant.config.limits.read_seconds)


def _frame(sdk, wire, grant):
    if not isinstance(wire, (str, bytes)):
        raise SparkProducerError("DSL_INVALID", "protocol")
    size = len(wire.encode("utf-8") if isinstance(wire, str) else wire)
    if size > grant.config.limits.max_frame_bytes:
        raise SparkProducerError("FRAME_BUDGET_EXCEEDED", "protocol")
    value = _json(wire)
    try:
        code, status, content, reasoning, usage = sdk.decode_message(value)
        if (type(code) is not int or type(status) is not int or status not in {0, 1, 2}
                or not isinstance(content, str) or not isinstance(reasoning, str)
                or not isinstance(usage, dict) or _contains_private(value, grant.private)):
            raise ValueError("invalid Spark frame")
    except (KeyError, TypeError, IndexError, ValueError, AttributeError):
        raise SparkProducerError("DSL_INVALID", "protocol") from None
    return value, size, code, status


def _binding(receipt, grant):
    return (receipt.operation_id == grant.operation_id and receipt.resource_id == grant.config.resource_id
            and receipt.resource_version == grant.config.version and receipt.provider == "xinghuo")


class SparkChatProducer:
    """一实例一次领取/发送；跨进程防重复必须由 CenterSparkPort.acquire CAS 保证。"""
    def __init__(self, center: CenterSparkPort, transport: PrivateSparkTransport, *, acquire_timeout=10):
        if not _number(acquire_timeout, 30):
            raise ValueError("invalid acquire timeout")
        self._center, self._transport = center, transport
        self._acquire_timeout = acquire_timeout
        self._used = False

    async def produce(self, operation_id: str) -> ProducerOutcome:
        if self._used:
            raise SparkProducerError("DUPLICATE_EXECUTION", "authorization")
        self._used = True
        if not isinstance(operation_id, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", operation_id):
            raise SparkProducerError("DSL_INVALID", "protocol")
        grant = stream = outcome = error = None
        grant_valid = sent = False
        try:
            # 许可ACK未知不能重新领取，更不能打开连接。
            grant = await asyncio.wait_for(self._center.acquire(operation_id), self._acquire_timeout)
            _validate(grant, operation_id)
            grant_valid = True
            limits = grant.config.limits
            duration = min(limits.total_seconds, grant.deadline_epoch - time.time())
            if duration <= 0:
                raise SparkProducerError("DEADLINE_EXCEEDED", "deadline")
            async with asyncio.timeout(duration):
                await self._center.assert_active(grant)
                sdk, payload, read_seconds = _request(grant)
                signed_url = SparkChatHmacAuth(grant.private.endpoint,
                    grant.private.api_key, grant.private.api_secret).create_url()
                stream = await asyncio.wait_for(self._transport.open(signed_url,
                    connect_timeout=limits.connect_seconds, close_timeout=limits.close_seconds), limits.connect_seconds)
                await self._center.assert_active(grant)
                sent = True  # send异常/取消也可能已被Provider接收，不能按未发送重试。
                await stream.send(payload)
                sequence = total = 0
                while True:
                    await self._center.assert_active(grant)
                    wire = await asyncio.wait_for(recv_with_retry(stream), read_seconds)
                    await self._center.assert_active(grant)
                    frame, size, code, status = _frame(sdk, wire, grant)
                    sequence += 1
                    total += size
                    if sequence > limits.max_frames or total > limits.max_response_bytes:
                        raise SparkProducerError("FRAME_BUDGET_EXCEEDED", "protocol")
                    terminal = code != 0 or status == 2
                    ack = await self._center.commit_frame(grant, sequence, frame,
                        terminal=terminal, provider_error_code=code if code != 0 else None)
                    expected = "FAILED" if code != 0 else "COMPLETED" if terminal else "RUNNING"
                    if (not isinstance(ack, FrameCommit) or not _binding(ack, grant)
                            or type(ack.sequence) is not int or ack.sequence != sequence
                            or type(ack.done) is not bool or ack.done != terminal or ack.state != expected
                            or ack.failure_kind != ("provider" if code != 0 else None)):
                        raise SparkProducerError("RESOURCE_UNAVAILABLE", "protocol")
                    if terminal:
                        outcome = ProducerOutcome(ack, code if code != 0 else None,
                            CodeConvert.sparkCode(code).code if code != 0 else None, False)
                        break
        except asyncio.CancelledError:
            error = SparkProducerError("PRODUCER_CANCELLED", "cancel")
        except TimeoutError:
            error = SparkProducerError("EFFECT_UNKNOWN" if grant is None else "DEADLINE_EXCEEDED", "deadline")
        except SparkProducerError as caught:
            error = caught
        except WorkerError as caught:
            kind = "authorization" if caught.code in {"TOOL_POLICY_DENIED", "LEASE_LOST"} else "transport"
            error = SparkProducerError("EFFECT_UNKNOWN" if grant is None and kind != "authorization" else caught.code, kind)
        except Exception:
            error = SparkProducerError("EFFECT_UNKNOWN" if grant is None else "RESOURCE_UNAVAILABLE", "transport")
        if error is not None and not grant_valid and not (
                grant is None and error.failure_kind == "authorization"):
            # 领取回执未知/不完整，不能推定许可未发，也不能用未经核对grant写终态。
            error.code = "EFFECT_UNKNOWN"
        local_closed = stream is None
        if stream is not None:
            try:
                await asyncio.wait_for(stream.close(), grant.config.limits.close_seconds)
                local_closed = True
            except BaseException:
                local_closed = False
        if error is None and not local_closed:
            error = SparkProducerError("PRODUCER_CLEANUP_UNKNOWN", "transport")
        if error is not None:
            error.send_attempted, error.local_closed = sent, local_closed
            if sent and outcome is None:
                try:
                    receipt = await asyncio.wait_for(self._center.confirm_stop(grant, error.failure_kind),
                        grant.config.limits.close_seconds)
                    error.provider_stop_confirmed = (isinstance(receipt, StopConfirmation) and _binding(receipt, grant)
                        and receipt.state in {"CANCELLED", "COMPLETED", "FAILED"}
                        and receipt.provider_stopped is True and isinstance(receipt.proof_id, str) and bool(receipt.proof_id))
                except BaseException:
                    error.provider_stop_confirmed = False
                if not error.provider_stop_confirmed or not local_closed:
                    error.code = "EFFECT_UNKNOWN"
            if grant_valid:
                facts = FailureFacts(error.failure_kind, error.code, sent, local_closed, error.provider_stop_confirmed)
                try:
                    await asyncio.wait_for(self._center.record_failure(grant, facts), grant.config.limits.close_seconds)
                except BaseException:
                    if sent and outcome is None:
                        error.code = "EFFECT_UNKNOWN"
            raise error from None
        return ProducerOutcome(outcome.commit, outcome.provider_error_code, outcome.mapped_error_code, local_closed)
