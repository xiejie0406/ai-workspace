"""原Workflow OpenAI1.60.2的受控Chat Producer；未注册、无默认网络出口。

SDK只接中心私有许可。一次create、固定HTTP预算、无重试/redirect/env代理；
完整原SDK帧交中心持久化，finish_stream引用真实finish帧，EOF不生成finish。
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import hashlib
import json
import re
import time
from typing import Protocol
from urllib.parse import urlsplit

import httpx
from openai import APIError, APIConnectionError, APIResponseValidationError, APIStatusError, APITimeoutError, AsyncOpenAI

from workflow.exception.errors.err_code import CodeEnum
from workflow.infra.providers.llm.openai.openai_chat_llm import OpenAIChatAI
from worker.errors import WorkerError
from worker.producers.chat_lifecycle import (
    ChatBoundaryError, cleanup, commit_ack, contains_private, json_value, number,
    quiet_sdk,
)
from worker.producers.spark_chat import FailureFacts, FrameCommit, ProducerOutcome, StopConfirmation
from worker.task_runner import _same_json


@dataclass(frozen=True, slots=True)
class OpenAILimits:
    connect_seconds: float
    read_seconds: float
    write_seconds: float
    pool_seconds: float
    total_seconds: float
    close_seconds: float
    max_frames: int
    max_frame_bytes: int
    max_response_bytes: int
    max_request_bytes: int


@dataclass(frozen=True, slots=True)
class OpenAIPublicConfig:
    resource_id: str
    kind: str
    provider: str
    version: str
    model_name: str
    enabled: bool
    options_json: bytes = field(repr=False)
    limits: OpenAILimits


@dataclass(frozen=True, slots=True, repr=False)
class OpenAIPrivateMaterial:
    base_url: str
    api_key: str


@dataclass(frozen=True, slots=True, repr=False)
class OpenAIGrant:
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
    config: OpenAIPublicConfig
    private: OpenAIPrivateMaterial


@dataclass(frozen=True, slots=True, kw_only=True)
class StreamFinishCommit(FrameCommit):
    finish_sequence: int
    last_sequence: int


class CenterOpenAIChatPort(Protocol):
    async def acquire(self, operation_id: str) -> OpenAIGrant: ...
    async def assert_active(self, grant: OpenAIGrant) -> None: ...
    async def commit_frame(self, grant: OpenAIGrant, sequence: int, frame: dict, *,
                           terminal: bool, provider_error_code: int | None) -> FrameCommit: ...
    async def finish_stream(self, grant: OpenAIGrant, last_sequence: int, terminal_sequence: int,
                            finish_reason: str) -> StreamFinishCommit:
        """CAS引用已持久真实finish帧及尾usage，不添加伪帧；同绑定/序号完整ACK。"""
        ...
    async def confirm_stop(self, grant: OpenAIGrant, reason: str) -> StopConfirmation: ...
    async def record_failure(self, grant: OpenAIGrant, facts: FailureFacts) -> None: ...


class PrivateOpenAITransport(Protocol):
    async def create_transport(self, material: OpenAIPrivateMaterial, limits: OpenAILimits) -> httpx.AsyncBaseTransport:
        """只构造私有固定TLS/出口transport，不发业务请求；禁止日志，无默认网络实现。"""
        ...


class _WireBudget(httpx.AsyncByteStream):
    def __init__(self, inner, limits):
        self.inner, self.limits = inner, limits
    async def __aiter__(self):
        total = line = 0
        async for chunk in self.inner:
            if not isinstance(chunk, bytes):
                raise ChatBoundaryError("DSL_INVALID", "protocol")
            total += len(chunk)
            if total > self.limits.max_response_bytes:
                raise ChatBoundaryError("FRAME_BUDGET_EXCEEDED", "protocol")
            for part in chunk.splitlines(keepends=True):
                line += len(part)
                if line > self.limits.max_frame_bytes:
                    raise ChatBoundaryError("FRAME_BUDGET_EXCEEDED", "protocol")
                if part.endswith((b"\n", b"\r")):
                    line = 0
            yield chunk
    async def aclose(self):
        await self.inner.aclose()


class _OneRequest(httpx.AsyncBaseTransport):
    def __init__(self, inner, grant):
        self.inner, self.grant, self.sent = inner, grant, False
        self.response = self.boundary_error = None
    async def handle_async_request(self, request):
        target = httpx.URL(self.grant.private.base_url.rstrip("/") + "/chat/completions")
        if (self.sent or request.method != "POST" or request.url != target
                or request.headers.get("authorization") != "Bearer " + self.grant.private.api_key):
            self.boundary_error = ChatBoundaryError("TOOL_POLICY_DENIED", "authorization")
            raise self.boundary_error
        self.sent = True
        response = await self.inner.handle_async_request(request)
        self.response = response
        if response.headers.get("content-encoding", "identity").strip().lower() != "identity":
            self.boundary_error = ChatBoundaryError("UNSUPPORTED_CONTENT_ENCODING", "protocol")
            raise self.boundary_error
        response.stream = _WireBudget(response.stream, self.grant.config.limits)
        return response
    async def aclose(self):
        await self.inner.aclose()


def _validate(grant, operation_id):
    if (not isinstance(grant, OpenAIGrant) or grant.operation_id != operation_id
            or any(not isinstance(v, str) or not v for v in (grant.run_id, grant.lease_id,
                grant.node_id, grant.workflow_version_id))
            or type(grant.attempt) is not int or grant.attempt < 1
            or not isinstance(grant.dsl_hash, str) or not re.fullmatch(r"[a-f0-9]{64}", grant.dsl_hash)
            or grant.node_id.split("::", 1)[0] not in {"spark-llm", "extractor-parameter", "decision-making", "question-answer"}
            or not number(grant.deadline_epoch, 10**12)
            or not isinstance(grant.config, OpenAIPublicConfig) or not isinstance(grant.private, OpenAIPrivateMaterial)):
        raise ChatBoundaryError("RESOURCE_UNAVAILABLE", "protocol")
    config, limits = grant.config, grant.config.limits
    if (config.kind != "model" or config.provider != "openai" or config.enabled is not True
            or any(not isinstance(v, str) or not v for v in (config.resource_id, config.version, config.model_name))
            or not isinstance(config.options_json, bytes) or not isinstance(grant.parameters_json, bytes)
            or not isinstance(grant.parameters_sha256, str)
            or hashlib.sha256(grant.parameters_json).hexdigest() != grant.parameters_sha256
            or not isinstance(limits, OpenAILimits)):
        raise ChatBoundaryError("DSL_INVALID", "protocol")
    if any(not number(value, upper) for value, upper in ((limits.connect_seconds, 60),
            (limits.read_seconds, 600), (limits.write_seconds, 600), (limits.pool_seconds, 60),
            (limits.total_seconds, 86400), (limits.close_seconds, 10))):
        raise ChatBoundaryError("DSL_INVALID", "protocol")
    if any(type(value) is not int or not 0 < value <= upper for value, upper in (
            (limits.max_frames, 10000), (limits.max_frame_bytes, 8 * 1024 * 1024),
            (limits.max_response_bytes, 8 * 1024 * 1024), (limits.max_request_bytes, 2 * 1024 * 1024))):
        raise ChatBoundaryError("DSL_INVALID", "protocol")
    try:
        if (not isinstance(grant.private.base_url, str) or not isinstance(grant.private.api_key, str)
                or any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in grant.private.base_url)
                or not grant.private.api_key.isascii() or any(ord(char) <= 32 or ord(char) == 127 for char in grant.private.api_key)):
            raise ValueError("invalid private material")
        grant.private.base_url.encode("utf-8")
        url = urlsplit(grant.private.base_url)
        valid = (url.scheme == "https" and url.hostname and not url.username and not url.password
                 and not url.query and not url.fragment and url.port in {None, 443}
                 and isinstance(grant.private.api_key, str) and bool(grant.private.api_key))
    except (TypeError, ValueError, UnicodeError):
        valid = False
    if not valid:
        raise ChatBoundaryError("TOOL_POLICY_DENIED", "authorization")


def _request(grant):
    body, options = json_value(grant.parameters_json), json_value(grant.config.options_json)
    required = {"resourceId", "resourceVersion", "provider", "modelName", "mode", "messages", "options"}
    keys = {"temperature", "maxTokens", "topK", "patchId", "respFormat", "searchDisable", "extraParams"}
    if (not isinstance(body, dict) or not required <= set(body) or set(body) - required - {"timeoutSeconds"}
            or body["resourceId"] != grant.config.resource_id or body["resourceVersion"] != grant.config.version
            or body["provider"] != "openai" or body["modelName"] != grant.config.model_name or body["mode"] != "chat"
            or not isinstance(options, dict) or set(options) != keys or not _same_json(body["options"], options)
            or not isinstance(body["messages"], list) or not body["messages"]
            or any(not isinstance(m, dict) for m in body["messages"])
            or not isinstance(options["extraParams"], dict)
            or contains_private(body, (grant.private.base_url, urlsplit(grant.private.base_url).hostname, grant.private.api_key))):
        raise ChatBoundaryError("DSL_INVALID", "protocol")
    extra = options["extraParams"]
    allowed = {"temperature", "top_p", "max_tokens", "presence_penalty", "frequency_penalty", "seed", "stop", "n",
        "response_format", "stream_options", "logprobs", "top_logprobs", "user", "tools", "tool_choice",
        "functions", "function_call", "parallel_tool_calls", "max_completion_tokens", "reasoning_effort", "store",
        "metadata", "audio", "modalities", "prediction", "service_tier"}
    if set(extra) - allowed or ("n" in extra and (type(extra["n"]) is not int or extra["n"] != 1)):
        raise ChatBoundaryError("TOOL_POLICY_DENIED", "authorization")
    read = body.get("timeoutSeconds", grant.config.limits.read_seconds)
    if not number(read, grant.config.limits.read_seconds):
        raise ChatBoundaryError("DSL_INVALID", "protocol")
    request = {"model": grant.config.model_name, "messages": body["messages"], "stream": True, **extra}
    if len(json.dumps(request, ensure_ascii=False, allow_nan=False).encode("utf-8")) > grant.config.limits.max_request_bytes:
        raise ChatBoundaryError("DSL_INVALID", "protocol")
    return request, read


def _frame(chunk, grant):
    frame = chunk.model_dump(mode="json")
    frame = json_value(json.dumps(frame, ensure_ascii=False, allow_nan=False))
    if contains_private(frame, (grant.private.base_url, urlsplit(grant.private.base_url).hostname, grant.private.api_key)):
        raise ChatBoundaryError("DSL_INVALID", "protocol")
    size = len(json.dumps(frame, ensure_ascii=False, allow_nan=False).encode("utf-8"))
    if size > grant.config.limits.max_frame_bytes:
        raise ChatBoundaryError("FRAME_BUDGET_EXCEEDED", "protocol")
    choices = frame.get("choices")
    if not isinstance(choices, list):
        raise ChatBoundaryError("DSL_INVALID", "protocol")
    if not choices:
        if not isinstance(frame.get("usage"), dict):
            raise ChatBoundaryError("DSL_INVALID", "protocol")
        return frame, size, None
    try:
        code, status, content, reasoning, usage = OpenAIChatAI.decode_message(None, frame)
        if (code != 0 or status not in {None, "stop", "length", "content_filter", "function_call", "tool_calls"}
                or content is not None and not isinstance(content, str)
                or not isinstance(reasoning, str) or not isinstance(usage, dict)):
            raise ValueError("invalid frame")
    except (KeyError, TypeError, IndexError, ValueError, AttributeError):
        raise ChatBoundaryError("DSL_INVALID", "protocol") from None
    return frame, size, status


class OpenAIChatProducer:
    def __init__(self, center: CenterOpenAIChatPort, private_transport: PrivateOpenAITransport, *, acquire_timeout=10):
        if not number(acquire_timeout, 30):
            raise ValueError("invalid acquire timeout")
        self.center, self.private_transport, self.acquire_timeout = center, private_transport, acquire_timeout
        self.used = False

    @quiet_sdk
    async def produce(self, operation_id: str) -> ProducerOutcome:
        if self.used:
            raise ChatBoundaryError("DUPLICATE_EXECUTION", "authorization")
        self.used = True
        if not isinstance(operation_id, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", operation_id):
            raise ChatBoundaryError("DSL_INVALID", "protocol")
        grant = stream = client = http_client = transport = outcome = error = None
        grant_valid = sent = False
        try:
            grant = await asyncio.wait_for(self.center.acquire(operation_id), self.acquire_timeout)
            _validate(grant, operation_id)
            grant_valid = True
            duration = min(grant.config.limits.total_seconds, grant.deadline_epoch - time.time())
            if duration <= 0:
                raise ChatBoundaryError("DEADLINE_EXCEEDED", "deadline")
            async with asyncio.timeout(duration):
                await self.center.assert_active(grant)
                request, read = _request(grant)
                limits = grant.config.limits
                inner = await asyncio.wait_for(self.private_transport.create_transport(grant.private, limits), limits.connect_seconds)
                if not isinstance(inner, httpx.AsyncBaseTransport):
                    raise ChatBoundaryError("RESOURCE_UNAVAILABLE", "protocol")
                transport = _OneRequest(inner, grant)
                http_client = httpx.AsyncClient(transport=transport, trust_env=False, follow_redirects=False,
                    headers={"accept-encoding": "identity"},
                    timeout=httpx.Timeout(connect=limits.connect_seconds, read=read,
                                          write=limits.write_seconds, pool=limits.pool_seconds))
                client = AsyncOpenAI(api_key=grant.private.api_key, base_url=grant.private.base_url,
                                    max_retries=0, timeout=http_client.timeout, http_client=http_client)
                await self.center.assert_active(grant)
                sent = True  # create取消/超时可能已送达；不再请求或领取。
                stream = await asyncio.wait_for(client.chat.completions.create(**request), min(duration, limits.connect_seconds + read))
                sequence = total = terminal_sequence = 0
                finish = None
                while True:
                    await self.center.assert_active(grant)
                    try:
                        chunk = await asyncio.wait_for(stream.__anext__(), read)
                    except StopAsyncIteration:
                        break
                    await self.center.assert_active(grant)
                    frame, size, status = _frame(chunk, grant)
                    sequence += 1
                    total += size
                    if sequence > limits.max_frames or total > limits.max_response_bytes:
                        raise ChatBoundaryError("FRAME_BUDGET_EXCEEDED", "protocol")
                    if terminal_sequence and frame["choices"]:
                        raise ChatBoundaryError("DSL_INVALID", "protocol")
                    if status is not None:
                        terminal_sequence, finish = sequence, status
                    ack = await self.center.commit_frame(grant, sequence, frame, terminal=False, provider_error_code=None)
                    commit_ack(ack, grant, sequence, "RUNNING", False)
                if not terminal_sequence:
                    raise ChatBoundaryError("MISSING_PROVIDER_FINISH", "protocol")
                await self.center.assert_active(grant)
                ack = await self.center.finish_stream(grant, sequence, terminal_sequence, finish)
                failed = finish != "stop"
                if (not isinstance(ack, StreamFinishCommit) or type(ack.last_sequence) is not int
                        or ack.last_sequence != sequence or type(ack.finish_sequence) is not int
                        or ack.finish_sequence != terminal_sequence):
                    raise ChatBoundaryError("RESOURCE_UNAVAILABLE", "protocol")
                commit_ack(ack, grant, sequence, "FAILED" if failed else "COMPLETED", True, "provider" if failed else None)
                outcome = ProducerOutcome(ack, None, CodeEnum.OPEN_AI_REQUEST_ERROR.code if failed else None, False)
        except asyncio.CancelledError:
            error = ChatBoundaryError("PRODUCER_CANCELLED", "cancel")
        except (TimeoutError, APITimeoutError):
            error = ChatBoundaryError("EFFECT_UNKNOWN" if grant is None else "DEADLINE_EXCEEDED", "deadline")
        except ChatBoundaryError as caught:
            error = caught
        except APIStatusError as caught:
            error = ChatBoundaryError("PROVIDER_HTTP_ERROR", "protocol" if 300 <= caught.status_code < 400 else "provider")
        except APIConnectionError:
            error = (transport.boundary_error if transport is not None and transport.boundary_error is not None
                     else ChatBoundaryError("RESOURCE_UNAVAILABLE", "transport"))
        except APIResponseValidationError:
            error = ChatBoundaryError("DSL_INVALID", "protocol")
        except APIError:
            error = ChatBoundaryError("PROVIDER_API_ERROR", "provider")
        except (ValueError, TypeError, UnicodeError):
            error = ChatBoundaryError("DSL_INVALID", "protocol")
        except WorkerError as caught:
            kind = "authorization" if caught.code in {"TOOL_POLICY_DENIED", "LEASE_LOST"} else "transport"
            error = ChatBoundaryError("EFFECT_UNKNOWN" if grant is None and kind != "authorization" else caught.code, kind)
        except Exception:
            error = ChatBoundaryError("EFFECT_UNKNOWN" if grant is None else "RESOURCE_UNAVAILABLE", "transport")
        if error is not None and not grant_valid and not (grant is None and error.failure_kind == "authorization"):
            error.code = "EFFECT_UNKNOWN"
        # transport已交付但client未成功构造也须清理；正常由client关闭transport。
        response = transport.response if transport is not None else None
        handles = [stream, response, client if client is not None else http_client if http_client is not None else transport]
        closed = await cleanup(self.center, grant, handles, grant_valid=grant_valid, sent=sent,
                               terminal_known=outcome is not None, error=error)
        return ProducerOutcome(outcome.commit, outcome.provider_error_code, outcome.mapped_error_code, closed)
