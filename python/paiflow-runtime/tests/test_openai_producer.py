"""实际原OpenAI SDK+内存HTTPX的离线Producer检查；没有真实Provider。"""
import asyncio
from dataclasses import FrozenInstanceError, replace
import hashlib
import gzip
import io
import json
import logging
import time
import unittest
from unittest.mock import patch

import httpx
from openai import AsyncOpenAI

from worker.errors import WorkerError
from worker.producers.chat_lifecycle import ChatBoundaryError
from worker.producers.openai_chat import (
    OpenAIChatProducer, OpenAIGrant, OpenAILimits, OpenAIPrivateMaterial, OpenAIPublicConfig, StreamFinishCommit,
)
from worker.producers.spark_chat import FrameCommit, StopConfirmation


def encoded(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()


def grant_fixture(*, extra=None, limits=None):
    options = {"temperature": .5, "maxTokens": 20, "topK": 4, "patchId": [], "respFormat": 0,
               "searchDisable": True, "extraParams": extra or {}}
    config = OpenAIPublicConfig("model-r", "model", "openai", "model-v", "model-fixture", True, encoded(options),
        limits or OpenAILimits(.5, .5, .5, .5, 3, .1, 10, 8192, 32768, 32768))
    body = {"resourceId": "model-r", "resourceVersion": "model-v", "provider": "openai", "modelName": "model-fixture",
            "mode": "chat", "messages": [{"role": "user", "content": "原问题😀"}], "options": options}
    raw = encoded(body)
    return OpenAIGrant("operation-1", "run-1", "lease-1", 1, "spark-llm::m", "workflow-v", "a" * 64,
        raw, hashlib.sha256(raw).hexdigest(), time.time() + 60, config,
        OpenAIPrivateMaterial("https://provider.invalid/v1/", "offline-material"))


def chunk(content="", *, finish=None, usage=None, reasoning=""):
    return {"id": "fixture", "created": 0, "model": "model-fixture", "object": "chat.completion.chunk",
        "choices": [{"index": 0, "delta": {"content": content, "reasoning_content": reasoning}, "finish_reason": finish}],
        "usage": usage}


def usage_only(tokens=7):
    value = chunk(usage={"prompt_tokens": 2, "completion_tokens": tokens - 2, "total_tokens": tokens})
    value["choices"] = []
    return value


class BodyFixture(httpx.AsyncByteStream):
    def __init__(self, frames=None, *, raw=None):
        self.wires = ([b"data: " + encoded(f) + b"\n\n" for f in frames] if raw is None else raw)
        self.closed = 0
        self.wait = False
        self.started = asyncio.Event()
        self.fail_close = False
    async def __aiter__(self):
        self.started.set()
        if self.wait:
            await asyncio.Event().wait()
        for wire in self.wires:
            yield wire
    async def aclose(self):
        self.closed += 1
        if self.fail_close:
            raise OSError("private cleanup detail")


class HTTPFixture(httpx.AsyncBaseTransport):
    def __init__(self, body):
        self.body, self.calls, self.closed = body, [], 0
        self.status = 200
        self.timeout = False
        self.wait = False
        self.started = asyncio.Event()
        self.fail_close = False
        self.encoding = "identity"
    async def handle_async_request(self, request):
        self.calls.append(request)
        self.started.set()
        if self.wait:
            await asyncio.Event().wait()
        if self.timeout:
            raise httpx.ConnectTimeout("private network detail", request=request)
        if self.status != 200:
            return httpx.Response(self.status, request=request, json={"error": {"message": "private rejection detail"}},
                headers={"location": "https://other.invalid/"} if 300 <= self.status < 400 else None)
        return httpx.Response(200, request=request, headers={"content-type": "text/event-stream",
            "content-encoding": self.encoding}, stream=self.body)
    async def aclose(self):
        self.closed += 1
        if self.fail_close:
            raise OSError("private pool cleanup detail")


class PrivateFixture:
    def __init__(self, body=None):
        self.http = HTTPFixture(body or BodyFixture([chunk("原内容", finish="stop")]))
        self.calls = 0
        self.wait = False
        self.cancelled = False
    async def create_transport(self, material, limits):
        self.calls += 1
        if self.wait:
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                self.cancelled = True
                raise
        return self.http


class CenterFixture:
    def __init__(self, grant=None):
        self.grant = grant or grant_fixture()
        self.acquisitions = self.checks = 0
        self.frames, self.finishes, self.failures, self.stops = [], [], [], []
        self.used = False
        self.deny = self.wait = self.lose_ack = False
        self.stop_confirmed = False
        self.revoke_at = None
        self.frame_ack_change = self.finish_ack_change = self.stop_change = None
    async def acquire(self, operation_id):
        self.acquisitions += 1
        if self.used or self.deny:
            raise WorkerError("TOOL_POLICY_DENIED", "fixture denial")
        self.used = True
        if self.wait:
            await asyncio.Event().wait()
        if self.lose_ack:
            raise OSError("private permit lost")
        return self.grant
    async def assert_active(self, grant):
        self.checks += 1
        if self.checks == self.revoke_at:
            raise WorkerError("LEASE_LOST", "fixture revoked")
    async def commit_frame(self, grant, sequence, frame, **kwargs):
        self.frames.append((sequence, frame, kwargs))
        ack = FrameCommit(grant.operation_id, grant.config.resource_id, grant.config.version, "openai", sequence, "RUNNING", False)
        return self.frame_ack_change(ack) if self.frame_ack_change else ack
    async def finish_stream(self, grant, last_sequence, terminal_sequence, finish_reason):
        self.finishes.append((last_sequence, terminal_sequence, finish_reason))
        failed = finish_reason != "stop"
        ack = StreamFinishCommit(grant.operation_id, grant.config.resource_id, grant.config.version, "openai", last_sequence,
            "FAILED" if failed else "COMPLETED", True, "provider" if failed else None,
            finish_sequence=terminal_sequence, last_sequence=last_sequence)
        return self.finish_ack_change(ack) if self.finish_ack_change else ack
    async def confirm_stop(self, grant, reason):
        self.stops.append(reason)
        ack = StopConfirmation(grant.operation_id, grant.config.resource_id, grant.config.version, "openai",
            "CANCELLED" if self.stop_confirmed else "EFFECT_UNKNOWN", self.stop_confirmed,
            "fixture-proof" if self.stop_confirmed else None)
        return self.stop_change(ack) if self.stop_change else ack
    async def record_failure(self, grant, facts):
        self.failures.append(facts)


class OpenAIProducerTest(unittest.IsolatedAsyncioTestCase):
    async def execute(self, center=None, private=None):
        center, private = center or CenterFixture(), private or PrivateFixture()
        outcome = await OpenAIChatProducer(center, private, acquire_timeout=.02).produce("operation-1")
        return outcome, center, private
    async def failure(self, center=None, private=None, code=None):
        center, private = center or CenterFixture(), private or PrivateFixture()
        with self.assertRaises(ChatBoundaryError) as caught:
            await self.execute(center, private)
        if code:
            self.assertEqual(caught.exception.code, code)
        return caught.exception, center, private

    async def test_actual_sdk_once_request_delta_reasoning_tail_usage_and_finish_reference(self):
        frames = [chunk(None), chunk("原内容😀", reasoning="原推理"), chunk(finish="stop"), usage_only(9)]
        private = PrivateFixture(BodyFixture(frames, raw=None))
        outcome, center, private = await self.execute(CenterFixture(grant_fixture(extra={"stream_options": {"include_usage": True}})), private)
        self.assertEqual(outcome.commit.state, "COMPLETED")
        self.assertEqual(center.finishes, [(4, 3, "stop")])
        self.assertEqual(len(center.frames), 4)
        self.assertEqual(center.frames[1][1]["choices"][0]["delta"]["reasoning_content"], "原推理")
        self.assertEqual(center.frames[-1][1]["choices"], [])
        self.assertEqual(center.frames[-1][1]["usage"]["total_tokens"], 9)
        self.assertTrue(all(not x[2]["terminal"] for x in center.frames))
        self.assertEqual(len(private.http.calls), 1)
        self.assertEqual(private.http.closed, 1)
        self.assertEqual(private.http.body.closed, 1)
        request = private.http.calls[0]
        self.assertEqual(request.extensions["timeout"], {"connect": .5, "read": .5, "write": .5, "pool": .5})
        body = json.loads(request.content)
        self.assertTrue(body["stream"])
        self.assertNotIn("temperature", body)  # 原通用参数不自动传入。

    async def test_sdk_done_keeps_actual_nonstop_error_not_fake_stop(self):
        for reason in ("length", "content_filter", "tool_calls", "function_call"):
            body = BodyFixture([], raw=[b"data: " + encoded(chunk("部分", finish=reason)) + b"\n\n", b"data: [DONE]\n\n"])
            outcome, center, _ = await self.execute(private=PrivateFixture(body))
            self.assertEqual(outcome.commit.state, "FAILED")
            self.assertEqual(outcome.mapped_error_code, 20380)
            self.assertEqual(center.frames[0][1]["choices"][0]["finish_reason"], reason)

    async def test_bare_eof_empty_or_usage_only_does_not_finish(self):
        for frames in ([], [chunk("片段")], [usage_only()]):
            error, center, private = await self.failure(private=PrivateFixture(BodyFixture(frames)), code="EFFECT_UNKNOWN")
            self.assertEqual(error.failure_kind, "protocol")
            self.assertEqual(center.finishes, [])
            self.assertTrue(error.local_closed)
            self.assertFalse(error.provider_stop_confirmed)

    async def test_finish_ack_every_binding_and_sequence_must_match(self):
        for key, value in (("operation_id", "other"), ("provider", "xinghuo"), ("resource_version", "other"),
                           ("sequence", True), ("last_sequence", 2), ("finish_sequence", 2), ("state", "RUNNING"), ("done", False)):
            center = CenterFixture()
            center.finish_ack_change = lambda ack, k=key, v=value: replace(ack, **{k: v})
            await self.failure(center, code="EFFECT_UNKNOWN")

    async def test_frame_ack_unknown_never_finish(self):
        center = CenterFixture()
        center.frame_ack_change = lambda ack: replace(ack, sequence=99)
        await self.failure(center, code="EFFECT_UNKNOWN")
        self.assertEqual(center.finishes, [])

    async def test_acquire_unknown_denied_and_repeat_do_not_open(self):
        for attr, expected in (("wait", "EFFECT_UNKNOWN"), ("lose_ack", "EFFECT_UNKNOWN"), ("deny", "TOOL_POLICY_DENIED")):
            center, private = CenterFixture(), PrivateFixture()
            setattr(center, attr, True)
            await self.failure(center, private, expected)
            self.assertEqual(private.calls, 0)
            self.assertEqual(center.failures, [])
        center, private = CenterFixture(), PrivateFixture()
        producer = OpenAIChatProducer(center, private)
        await producer.produce("operation-1")
        with self.assertRaises(ChatBoundaryError):
            await producer.produce("operation-1")
        self.assertEqual(center.acquisitions, 1)

    async def test_malformed_grant_options_hash_or_provider_does_not_open(self):
        grant = grant_fixture()
        for bad in (replace(grant, attempt=0), replace(grant, parameters_sha256="b"*64),
                    replace(grant, config=replace(grant.config, enabled=False)),
                    replace(grant, config=replace(grant.config, provider="xinghuo"))):
            _, _, private = await self.failure(CenterFixture(bad))
            self.assertEqual(private.calls, 0)
        with self.assertRaises(FrozenInstanceError):
            grant.attempt = 2

    async def test_sdk_overrides_n_and_private_echo_rejected(self):
        for extra in ({"extra_body": {"model": "other"}}, {"extra_headers": {}}, {"extra_query": {}},
                      {"timeout": None}, {"model": "other"}, {"stream": False}, {"n": 2}):
            _, _, private = await self.failure(CenterFixture(grant_fixture(extra=extra)), code="TOOL_POLICY_DENIED")
            self.assertEqual(private.calls, 0)
        await self.failure(private=PrivateFixture(BodyFixture([chunk("offline-material", finish="stop")])), code="EFFECT_UNKNOWN")

    async def test_initial_500_sdk_has_no_retry_and_no_fake_provider_frame(self):
        private = PrivateFixture(); private.http.status = 500
        error, center, private = await self.failure(private=private, code="EFFECT_UNKNOWN")
        self.assertEqual(len(private.http.calls), 1)
        self.assertEqual(error.failure_kind, "provider")
        self.assertEqual(center.frames, [])

    async def test_redirect_not_followed_and_not_provider_failure(self):
        private = PrivateFixture(); private.http.status = 302
        error, center, private = await self.failure(private=private, code="EFFECT_UNKNOWN")
        self.assertEqual(len(private.http.calls), 1)
        self.assertEqual(error.failure_kind, "protocol")
        self.assertEqual(center.frames, [])

    async def test_sse_apierror_closes_both_and_no_fake_raw_error(self):
        body = BodyFixture([], raw=[b'event: error\ndata: {"error":{"message":"private error","type":"fixture"}}\n\n'])
        error, center, private = await self.failure(private=PrivateFixture(body), code="EFFECT_UNKNOWN")
        self.assertEqual(error.failure_kind, "provider")
        self.assertEqual(center.frames, [])
        self.assertEqual(body.closed, 1)
        self.assertEqual(private.http.closed, 1)
        self.assertNotIn("private error", str(error))

    async def test_connect_and_read_timeout_and_total_budget(self):
        private = PrivateFixture(); private.http.timeout = True
        error, _, _ = await self.failure(private=private, code="EFFECT_UNKNOWN")
        self.assertEqual(error.failure_kind, "deadline")
        for mode in ("read", "total"):
            private = PrivateFixture(); private.http.body.wait = True
            grant = grant_fixture()
            grant = replace(grant, config=replace(grant.config, limits=replace(grant.config.limits, read_seconds=.02)))
            if mode == "total":
                grant = replace(grant, config=replace(grant.config, limits=replace(grant.config.limits, total_seconds=.02)))
            await self.failure(CenterFixture(grant), private, "EFFECT_UNKNOWN")
            self.assertEqual(private.http.closed, 1)

    async def test_cancel_local_close_not_provider_proof(self):
        private = PrivateFixture(); private.http.body.wait = True
        center = CenterFixture()
        task = asyncio.create_task(OpenAIChatProducer(center, private).produce("operation-1"))
        await private.http.body.started.wait(); task.cancel()
        with self.assertRaises(ChatBoundaryError) as caught:
            await task
        self.assertEqual(caught.exception.code, "EFFECT_UNKNOWN")
        self.assertTrue(caught.exception.local_closed)
        self.assertFalse(caught.exception.provider_stop_confirmed)

    async def test_confirmed_stop_requires_bound_proof(self):
        for mismatch in (False, True):
            private = PrivateFixture(); private.http.timeout = True
            center = CenterFixture(); center.stop_confirmed = True
            if mismatch:
                center.stop_change = lambda ack: replace(ack, resource_id="other")
            error, _, _ = await self.failure(center, private, "EFFECT_UNKNOWN" if mismatch else "DEADLINE_EXCEEDED")
            self.assertEqual(error.provider_stop_confirmed, not mismatch)

    async def test_frame_wire_total_and_frame_count_budgets(self):
        for field, limit, frames in (("max_frame_bytes", 50, [chunk("大"*200, finish="stop")]),
                ("max_response_bytes", 300, [chunk("大"*200, finish="stop")]),
                ("max_frames", 1, [chunk("甲"), chunk("乙", finish="stop")])):
            grant = grant_fixture()
            grant = replace(grant, config=replace(grant.config, limits=replace(grant.config.limits, **{field:limit})))
            error, center, _ = await self.failure(CenterFixture(grant), PrivateFixture(BodyFixture(frames)), "EFFECT_UNKNOWN")
            self.assertEqual(error.failure_kind, "protocol")
            self.assertEqual(center.finishes, [])

    async def test_real_finish_followed_by_content_is_protocol_failure(self):
        await self.failure(private=PrivateFixture(BodyFixture([chunk(finish="stop"), chunk("迟到内容")])), code="EFFECT_UNKNOWN")

    async def test_cleanup_failure_does_not_overwrite_known_terminal(self):
        private = PrivateFixture(); private.http.fail_close = True
        error, center, _ = await self.failure(private=private, code="PRODUCER_CLEANUP_UNKNOWN")
        self.assertEqual(center.finishes, [(1, 1, "stop")])
        self.assertEqual(center.stops, [])
        self.assertFalse(error.local_closed)

    async def test_private_sdk_debug_and_httpx_info_logs_suppressed(self):
        stream = io.StringIO(); handler = logging.StreamHandler(stream)
        loggers = [logging.getLogger("openai._base_client"), logging.getLogger("httpx")]
        old = [logger.level for logger in loggers]
        try:
            for logger in loggers:
                logger.addHandler(handler); logger.setLevel(logging.DEBUG)
            await self.execute()
            self.assertEqual(stream.getvalue(), "")
        finally:
            for logger, level in zip(loggers, old):
                logger.removeHandler(handler); logger.setLevel(level)

    async def test_import_and_real_sdk_transport_need_no_socket(self):
        with patch("socket.socket.connect", side_effect=AssertionError("network forbidden")):
            await self.execute()

    async def test_actual_sdk_configuration_is_fixed_and_client_constructor_failure_closes_httpx(self):
        observations = []
        def inspect_client(**kwargs):
            client = AsyncOpenAI(**kwargs)
            observations.append((client.max_retries, client.timeout.as_dict(), kwargs["http_client"].trust_env,
                                 kwargs["http_client"].follow_redirects, kwargs["http_client"].headers["accept-encoding"]))
            return client
        with patch("worker.producers.openai_chat.AsyncOpenAI", side_effect=inspect_client):
            await self.execute()
        self.assertEqual(observations, [(0, {"connect": .5, "read": .5, "write": .5, "pool": .5}, False, False, "identity")])
        constructed = []
        def fail_client(**kwargs):
            constructed.append(AsyncOpenAI(**kwargs))
            raise ValueError("private construction detail")
        private = PrivateFixture()
        with patch("worker.producers.openai_chat.AsyncOpenAI", side_effect=fail_client):
            error, center, _ = await self.failure(private=private, code="DSL_INVALID")
        self.assertFalse(error.send_attempted)
        self.assertTrue(error.local_closed)
        self.assertTrue(constructed[0].is_closed())
        self.assertEqual(private.http.closed, 1)
        self.assertEqual(private.http.calls, [])
        self.assertEqual(center.stops, [])

    async def test_private_url_control_and_credential_header_injection_rejected_before_factory(self):
        grant = grant_fixture()
        for material in (replace(grant.private, base_url=grant.private.base_url + "\x00"),
                         replace(grant.private, base_url=grant.private.base_url + "\ud800"),
                         replace(grant.private, api_key="offline\nmaterial")):
            error, _, private = await self.failure(CenterFixture(replace(grant, private=material)), code="EFFECT_UNKNOWN")
            self.assertEqual(private.calls, 0)
            self.assertFalse(error.send_attempted)

    async def test_compressed_stream_rejected_before_sdk_decompression(self):
        compressed = gzip.compress(b"data: " + encoded(chunk("large" * 400000, finish="stop")) + b"\n\n")
        self.assertLess(len(compressed), 32768)
        body = BodyFixture([], raw=[compressed]); private = PrivateFixture(body); private.http.encoding = "gzip"
        error, center, _ = await self.failure(private=private, code="EFFECT_UNKNOWN")
        self.assertEqual(error.failure_kind, "protocol")
        self.assertEqual(center.frames, [])
        self.assertEqual(center.finishes, [])
        self.assertEqual(body.closed, 1)

    async def test_cancel_during_initial_sdk_request_closes_client_without_stop_assumption(self):
        private = PrivateFixture(); private.http.wait = True
        center = CenterFixture()
        task = asyncio.create_task(OpenAIChatProducer(center, private).produce("operation-1"))
        await private.http.started.wait(); task.cancel()
        with self.assertRaises(ChatBoundaryError) as caught:
            await task
        self.assertEqual(caught.exception.code, "EFFECT_UNKNOWN")
        self.assertTrue(caught.exception.send_attempted)
        self.assertEqual(private.http.closed, 1)
        self.assertEqual(len(private.http.calls), 1)
