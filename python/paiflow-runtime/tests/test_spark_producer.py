"""Spark原算法与Producer边界的离线夹具；不连接Provider或拥有正式中心状态。"""
import asyncio
import base64
from dataclasses import FrozenInstanceError, replace
import hashlib
import hmac
import json
import os
import time
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

from workflow.exception.errors.code_convert import CodeConvert
from workflow.infra.providers.llm.iflytek_spark.spark_chat_llm import SparkChatAi
from worker.errors import WorkerError
from worker.producers.spark_chat import (
    FailureFacts, FrameCommit, SparkChatProducer, SparkGrant, SparkLimits,
    SparkPrivateMaterial, SparkProducerError, SparkPublicConfig, StopConfirmation,
)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")


def grant_fixture(*, options=None, messages=None, limits=None, quickly=False, timeout=None):
    options = options or {"temperature": 0.5, "maxTokens": 100, "topK": 4,
        "patchId": ["patch-label"], "respFormat": 0, "searchDisable": True, "extraParams": {}}
    config = SparkPublicConfig("model-r", "model", "xinghuo", "model-v", "general-model", True, encoded(options), quickly,
        limits or SparkLimits(.1, .1, 1, .1, 10, 8192, 32768, 32768))
    body = {"resourceId": config.resource_id, "resourceVersion": config.version,
        "provider": "xinghuo", "modelName": config.model_name, "mode": "chat",
        "messages": messages or [{"role": "user", "content": "原问题😀"}], "options": options}
    if timeout is not None:
        body["timeoutSeconds"] = timeout
    parameters = encoded(body)
    # 虚构离线材料；从未进入真正连接器。测试输出不打印它们。
    private = SparkPrivateMaterial("wss://provider.invalid/chat", "fixture-app", "fixture-user",
        "offline-key", "offline-secret")
    return SparkGrant("operation-1", "run-1", "lease-1", 1, "spark-llm::m", "version-1", "a" * 64,
        parameters, hashlib.sha256(parameters).hexdigest(), time.time() + 60, config, private)


def spark_frame(status=2, *, content="原内容", reasoning="原推理", code=0):
    return {"header": {"code": code, "status": status, "sid": "offline-frame-2"},
        "payload": {"choices": {"text": [{"content": content, "reasoning_content": reasoning}]},
                    "usage": {"text": {"prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 5}}}}


class WireFixture:
    def __init__(self, frames=None):
        self.frames = list(frames if frames is not None else [spark_frame()])
        self.sent, self.received, self.closed = [], 0, 0
        self.read_started = asyncio.Event()
        self.wait_read = False
        self.wait_send = False
        self.wait_close = False
        self.fail_send = False
        self.fail_close = False

    async def send(self, value):
        self.sent.append(value)
        if self.fail_send:
            raise OSError("private remote transport detail")
        if self.wait_send:
            await asyncio.Event().wait()

    async def recv(self):
        self.received += 1
        self.read_started.set()
        if self.wait_read:
            await asyncio.Event().wait()
        if not self.frames:
            raise OSError("stream ended without original terminal")
        value = self.frames.pop(0)
        return value if isinstance(value, (str, bytes)) else encoded(value)

    async def close(self):
        self.closed += 1
        if self.wait_close:
            await asyncio.Event().wait()
        if self.fail_close:
            raise OSError("private remote close detail")


class TransportFixture:
    def __init__(self, stream=None):
        self.stream = stream or WireFixture()
        self.calls = []
        self.wait_open = False
        self.fail_open = False
        self.open_cancelled = False

    async def open(self, url, **kwargs):
        self.calls.append((url, kwargs))
        if self.fail_open:
            raise OSError("private endpoint cannot be printed")
        if self.wait_open:
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                self.open_cancelled = True
                raise
        return self.stream


class CenterFixture:
    def __init__(self, grant=None):
        self.grant = grant or grant_fixture()
        self.acquisitions, self.checks, self.frames, self.failures, self.stops = 0, 0, [], [], []
        self.used = False
        self.deny = False
        self.wait_acquire = False
        self.fail_acquire = False
        self.revoke_at = None
        self.ack_change = None
        self.stop_confirmed = False
        self.stop_change = None
        self.fail_stop = False
        self.fail_record = False

    async def acquire(self, operation_id):
        self.acquisitions += 1
        if self.deny or self.used:
            raise WorkerError("TOOL_POLICY_DENIED", "fixture denial")
        self.used = True
        if self.fail_acquire:
            raise OSError("lost private permit ACK")
        if self.wait_acquire:
            await asyncio.Event().wait()
        return self.grant

    async def assert_active(self, grant):
        self.checks += 1
        if self.revoke_at == self.checks:
            raise WorkerError("LEASE_LOST", "fixture revoked")

    async def commit_frame(self, grant, sequence, frame, *, terminal, provider_error_code):
        self.frames.append((sequence, frame, terminal, provider_error_code))
        ack = FrameCommit(grant.operation_id, grant.config.resource_id, grant.config.version, "xinghuo", sequence,
            "FAILED" if provider_error_code is not None else "COMPLETED" if terminal else "RUNNING", terminal,
            "provider" if provider_error_code is not None else None)
        return self.ack_change(ack) if self.ack_change else ack

    async def confirm_stop(self, grant, reason):
        self.stops.append(reason)
        if self.fail_stop:
            raise OSError("stop confirmation unavailable")
        ack = StopConfirmation(grant.operation_id, grant.config.resource_id, grant.config.version, "xinghuo",
            "CANCELLED" if self.stop_confirmed else "EFFECT_UNKNOWN", self.stop_confirmed,
            "fixture-provider-proof" if self.stop_confirmed else None)
        return self.stop_change(ack) if self.stop_change else ack

    async def record_failure(self, grant, facts):
        self.failures.append(facts)
        if self.fail_record:
            raise OSError("failure ACK lost")


class SparkProducerTest(unittest.IsolatedAsyncioTestCase):
    async def run_fixture(self, grant=None, stream=None, center=None, transport=None):
        center = center or CenterFixture(grant)
        transport = transport or TransportFixture(stream)
        outcome = await SparkChatProducer(center, transport).produce("operation-1")
        return outcome, center, transport

    async def expect_error(self, center=None, transport=None, code=None):
        center, transport = center or CenterFixture(), transport or TransportFixture()
        with self.assertRaises(SparkProducerError) as caught:
            await SparkChatProducer(center, transport, acquire_timeout=.02).produce("operation-1")
        if code:
            self.assertEqual(caught.exception.code, code)
        return caught.exception, center, transport

    async def test_single_send_preserves_status_0_1_2_original_frames_and_usage(self):
        frames = [spark_frame(0), spark_frame(1), spark_frame(2), spark_frame(1, content="终帧后不读取")]
        outcome, center, transport = await self.run_fixture(stream=WireFixture(frames))
        self.assertEqual(outcome.commit.state, "COMPLETED")
        self.assertTrue(outcome.local_closed)
        self.assertEqual([x[1] for x in center.frames], frames[:3])
        self.assertEqual([x[0] for x in center.frames], [1, 2, 3])
        self.assertEqual([x[2] for x in center.frames], [False, False, True])
        self.assertEqual(len(transport.stream.sent), 1)
        self.assertEqual(transport.stream.received, 3)
        self.assertEqual(transport.stream.closed, 1)
        self.assertEqual(center.stops, [])
        self.assertEqual(center.frames[-1][1]["payload"]["usage"]["text"]["total_tokens"], 5)

    async def test_original_payload_patch_search_and_multimodal_objects(self):
        messages = [{"role": "user", "content": [{"type": "text", "text": "原结构"},
                     {"type": "image", "content": "离线对象字节"}]}]
        grant = grant_fixture(messages=messages)
        _, _, transport = await self.run_fixture(grant)
        payload = json.loads(transport.stream.sent[0])
        self.assertEqual(payload["payload"]["message"]["text"], messages)
        self.assertEqual(payload["header"], {"app_id": "fixture-app", "uid": "fixture-user", "patch_id": ["patch-label"]})
        self.assertEqual(payload["parameter"]["chat"], {"domain": "general-model", "temperature": .5,
            "max_tokens": 100, "top_k": 4, "auditing": "default", "question_type": "not_knowledge"})
        options = json.loads(grant.config.options_json)
        options["searchDisable"] = False
        _, _, transport = await self.run_fixture(grant_fixture(options=options))
        chat = json.loads(transport.stream.sent[0])["parameter"]["chat"]
        self.assertNotIn("question_type", chat)
        self.assertEqual(chat["tools"][0]["web_search"], {"enable": True, "show_ref_label": False, "search_mode": "normal"})

    async def test_hmac_original_signature_is_private_transport_only(self):
        _, center, transport = await self.run_fixture()
        parsed = urlsplit(transport.calls[0][0])
        query = parse_qs(parsed.query)
        authorization = base64.b64decode(query["authorization"][0]).decode()
        origin = "host: " + parsed.netloc + "\ndate: " + query["date"][0] + "\nGET " + parsed.path + " HTTP/1.1"
        expected = base64.b64encode(hmac.new(center.grant.private.api_secret.encode(), origin.encode(), hashlib.sha256).digest()).decode()
        self.assertIn('signature="' + expected + '"', authorization)
        self.assertIn('api_key="' + center.grant.private.api_key + '"', authorization)
        self.assertEqual(transport.calls[0][1], {"connect_timeout": .1, "close_timeout": .1})
        exposed = repr(center.grant) + repr(center.grant.private) + repr(center.frames) + repr(center.failures)
        for value in (center.grant.private.endpoint, center.grant.private.api_key, center.grant.private.api_secret):
            self.assertNotIn(value, exposed)

    async def test_quick_thinking_uses_fixed_profile_and_ignores_environment(self):
        with patch.dict(os.environ, {"QUICKLY_THINK_FLOW_IDS": "run-1", "QUICKLY_THINK_MODELS": "general-model",
                "QUICKLY_THINK_APPS": "fixture-app"}):
            _, _, transport = await self.run_fixture()
        self.assertNotIn("enable_thinking", json.loads(transport.stream.sent[0])["parameter"]["chat"])
        with patch.dict(os.environ, {}, clear=True):
            _, _, transport = await self.run_fixture(grant_fixture(quickly=True))
        self.assertIs(json.loads(transport.stream.sent[0])["parameter"]["chat"]["enable_thinking"], False)

    async def test_original_algorithms_are_called_without_trace_or_achat(self):
        with patch.object(SparkChatAi, "assemble_payload", autospec=True, side_effect=SparkChatAi.assemble_payload) as assembled, \
             patch.object(SparkChatAi, "decode_message", autospec=True, side_effect=SparkChatAi.decode_message) as decoded, \
             patch.object(SparkChatAi, "achat", side_effect=AssertionError("old network entry forbidden")), \
             patch.object(SparkChatAi, "assemble_url", side_effect=AssertionError("old URL trace forbidden")), \
             patch.object(SparkChatAi, "_handle_quickly_think_req_body", side_effect=AssertionError("old env entry forbidden")):
            await self.run_fixture()
        self.assertEqual(assembled.call_count, 1)
        self.assertEqual(decoded.call_count, 1)

    async def test_original_provider_error_commits_failed_and_keeps_code_mapping(self):
        for code in (10000, 987654):
            with self.subTest(code=code):
                outcome, center, transport = await self.run_fixture(stream=WireFixture([spark_frame(code=code)]))
                self.assertEqual(outcome.commit.state, "FAILED")
                self.assertEqual(outcome.commit.failure_kind, "provider")
                self.assertEqual(outcome.provider_error_code, code)
                self.assertEqual(outcome.mapped_error_code, CodeConvert.sparkCode(code).code)
                self.assertEqual(center.stops, [])
                self.assertEqual(transport.stream.closed, 1)

    async def test_duplicate_instance_and_center_cross_instance_permission_do_not_resend(self):
        center, transport = CenterFixture(), TransportFixture()
        producer = SparkChatProducer(center, transport)
        await producer.produce("operation-1")
        with self.assertRaises(SparkProducerError) as caught:
            await producer.produce("operation-1")
        self.assertEqual(caught.exception.code, "DUPLICATE_EXECUTION")
        error, _, second = await self.expect_error(center, code="TOOL_POLICY_DENIED")
        self.assertFalse(error.send_attempted)
        self.assertEqual(center.acquisitions, 2)
        self.assertEqual(len(transport.calls), 1)
        self.assertEqual(second.calls, [])

    async def test_acquire_lost_or_timed_out_ack_never_opens_or_retries(self):
        for flag in ("fail_acquire", "wait_acquire"):
            with self.subTest(flag=flag):
                center = CenterFixture()
                setattr(center, flag, True)
                error, _, transport = await self.expect_error(center, code="EFFECT_UNKNOWN")
                self.assertEqual(center.acquisitions, 1)
                self.assertEqual(transport.calls, [])
                self.assertFalse(error.send_attempted)
                self.assertEqual(center.failures, [])

    async def test_authorization_before_send_and_revoke_after_send(self):
        for revoke_at, sent in ((1, False), (2, False), (3, True)):
            with self.subTest(revoke_at=revoke_at):
                center = CenterFixture()
                center.revoke_at = revoke_at
                error, _, transport = await self.expect_error(center, code="EFFECT_UNKNOWN" if sent else "LEASE_LOST")
                self.assertEqual(error.send_attempted, sent)
                self.assertEqual(len(transport.stream.sent), int(sent))
                self.assertEqual(center.failures[-1].failure_kind, "authorization")

    async def test_immutable_config_parameter_hash_provider_and_target_mismatch_prevent_open(self):
        original = grant_fixture()
        variants = [replace(original, parameters_sha256="0" * 64),
            replace(original, config=replace(original.config, version="changed")),
            replace(original, private=replace(original.private, endpoint="http://provider.invalid/chat"))]
        for key, value in (("mode", "function_call"), ("provider", "openai"), ("resourceId", "foreign"),
                           ("modelName", "foreign"), ("url", "https://unauthorized.invalid")):
            body = json.loads(original.parameters_json)
            body[key] = value
            raw = encoded(body)
            variants.append(replace(original, parameters_json=raw, parameters_sha256=hashlib.sha256(raw).hexdigest()))
        for grant in variants:
            with self.subTest(target=grant.config.version):
                _, _, transport = await self.expect_error(CenterFixture(grant))
                self.assertEqual(transport.calls, [])
        with self.assertRaises(FrozenInstanceError):
            original.config.version = "mutable"

    async def test_option_override_and_private_material_in_messages_are_rejected(self):
        original = grant_fixture()
        for options, messages in (({"temperature": .8}, None), ({"apiKey": "unexpected"}, None),
                ({}, [{"role": "user", "content": original.private.api_key}])):
            body = json.loads(original.parameters_json)
            body["options"].update(options)
            if messages:
                body["messages"] = messages
            raw = encoded(body)
            grant = replace(original, parameters_json=raw, parameters_sha256=hashlib.sha256(raw).hexdigest())
            _, _, transport = await self.expect_error(CenterFixture(grant), code="DSL_INVALID")
            self.assertEqual(transport.calls, [])

    async def test_connect_timeout_cancels_unreturned_transport_without_send(self):
        original = grant_fixture()
        grant = replace(original, config=replace(original.config, limits=replace(original.config.limits, connect_seconds=.02)))
        transport = TransportFixture()
        transport.wait_open = True
        error, center, _ = await self.expect_error(CenterFixture(grant), transport, "DEADLINE_EXCEEDED")
        self.assertTrue(transport.open_cancelled)
        self.assertFalse(error.send_attempted)
        self.assertEqual(transport.stream.sent, [])
        self.assertEqual(center.stops, [])

    async def test_read_timeout_closes_and_cannot_claim_provider_stopped(self):
        stream = WireFixture()
        stream.wait_read = True
        error, center, _ = await self.expect_error(transport=TransportFixture(stream), code="EFFECT_UNKNOWN")
        self.assertTrue(error.local_closed)
        self.assertFalse(error.provider_stop_confirmed)
        self.assertEqual(stream.closed, 1)
        self.assertEqual(center.stops, ["deadline"])

    async def test_request_read_timeout_smaller_than_fixed_limit_is_honored(self):
        limits = replace(grant_fixture().config.limits, read_seconds=.5)
        grant = grant_fixture(timeout=.02, limits=limits)
        stream = WireFixture()
        stream.wait_read = True
        start = time.monotonic()
        await self.expect_error(CenterFixture(grant), TransportFixture(stream), "EFFECT_UNKNOWN")
        self.assertLess(time.monotonic() - start, .3)

    async def test_total_and_absolute_deadline_bound_unfinished_send_or_wait(self):
        for absolute in (False, True):
            with self.subTest(absolute=absolute):
                grant = grant_fixture()
                grant = replace(grant, deadline_epoch=time.time() + .025) if absolute else replace(grant,
                    config=replace(grant.config, limits=replace(grant.config.limits, total_seconds=.025)))
                stream = WireFixture()
                stream.wait_send = True
                error, _, _ = await self.expect_error(CenterFixture(grant), TransportFixture(stream), "EFFECT_UNKNOWN")
                self.assertTrue(error.send_attempted)
                self.assertEqual(len(stream.sent), 1)
                self.assertEqual(stream.closed, 1)
        grant = replace(grant_fixture(), deadline_epoch=time.time() - 1)
        _, _, transport = await self.expect_error(CenterFixture(grant), code="DEADLINE_EXCEEDED")
        self.assertEqual(transport.calls, [])

    async def test_frame_count_individual_and_aggregate_wire_budgets(self):
        single = len(encoded(spark_frame(0)))
        variants = [(replace(grant_fixture().config.limits, max_frames=1), [spark_frame(0), spark_frame(2)]),
            (replace(grant_fixture().config.limits, max_frame_bytes=single - 1), [spark_frame(0)]),
            (replace(grant_fixture().config.limits, max_frame_bytes=single, max_response_bytes=single),
             [spark_frame(0), spark_frame(2)])]
        for limits, frames in variants:
            with self.subTest(limits=limits):
                grant = grant_fixture(limits=limits)
                error, center, transport = await self.expect_error(CenterFixture(grant), TransportFixture(WireFixture(frames)), "EFFECT_UNKNOWN")
                self.assertEqual(error.failure_kind, "protocol")
                self.assertEqual(len(transport.stream.sent), 1)
                self.assertEqual(center.failures[-1].failure_kind, "protocol")

    async def test_invalid_frames_duplicate_keys_nonfinite_and_private_echo_are_not_provider_errors(self):
        original = grant_fixture()
        variants = [b'{"header":{"code":0,"code":1,"status":2},"payload":{}}',
            b'{"header":{"code":0,"status":2},"payload":{"usage":{"text":{"tokens":NaN}}}}',
            spark_frame(status=3), spark_frame(code=True), {"header": {"code": 0, "status": 2}},
            spark_frame(content=original.private.api_secret), {"choices": []}, "not-json"]
        for frame in variants:
            with self.subTest(kind=type(frame).__name__):
                error, center, _ = await self.expect_error(CenterFixture(original), TransportFixture(WireFixture([frame])), "EFFECT_UNKNOWN")
                self.assertEqual(error.failure_kind, "protocol")
                self.assertEqual(center.frames, [])

    async def test_center_commit_binding_cursor_or_terminal_mismatch_stops_without_resend(self):
        changes = [lambda ack: replace(ack, resource_version="foreign"), lambda ack: replace(ack, sequence=2),
            lambda ack: replace(ack, provider="openai"), lambda ack: replace(ack, done=False),
            lambda ack: replace(ack, state="RUNNING"), lambda ack: replace(ack, failure_kind="authorization")]
        for change in changes:
            center = CenterFixture()
            center.ack_change = change
            error, _, transport = await self.expect_error(center, code="EFFECT_UNKNOWN")
            self.assertEqual(error.failure_kind, "protocol")
            self.assertEqual(len(center.frames), 1)
            self.assertEqual(len(transport.stream.sent), 1)

    async def test_cancel_local_close_without_provider_proof_keeps_effect_unknown(self):
        center, stream = CenterFixture(), WireFixture()
        stream.wait_read = True
        producer = SparkChatProducer(center, TransportFixture(stream))
        task = asyncio.create_task(producer.produce("operation-1"))
        await asyncio.wait_for(stream.read_started.wait(), .5)
        task.cancel()
        with self.assertRaises(SparkProducerError) as caught:
            await task
        self.assertEqual(caught.exception.code, "EFFECT_UNKNOWN")
        self.assertEqual(caught.exception.failure_kind, "cancel")
        self.assertTrue(caught.exception.local_closed)
        self.assertFalse(caught.exception.provider_stop_confirmed)
        self.assertEqual(stream.closed, 1)
        self.assertEqual(center.failures[-1], FailureFacts("cancel", "EFFECT_UNKNOWN", True, True, False))

    async def test_cancel_provider_proof_requires_bound_ack_and_proof_reference(self):
        for invalid in (False, "binding", "proof", "boolean"):
            with self.subTest(invalid=invalid):
                center, stream = CenterFixture(), WireFixture()
                center.stop_confirmed = True
                if invalid == "binding": center.stop_change = lambda ack: replace(ack, operation_id="foreign")
                if invalid == "proof": center.stop_change = lambda ack: replace(ack, proof_id=None)
                if invalid == "boolean": center.stop_change = lambda ack: replace(ack, provider_stopped=1)
                stream.wait_read = True
                task = asyncio.create_task(SparkChatProducer(center, TransportFixture(stream)).produce("operation-1"))
                await asyncio.wait_for(stream.read_started.wait(), .5)
                task.cancel()
                with self.assertRaises(SparkProducerError) as caught:
                    await task
                self.assertEqual(caught.exception.code, "EFFECT_UNKNOWN" if invalid else "PRODUCER_CANCELLED")
                self.assertEqual(caught.exception.provider_stop_confirmed, not bool(invalid))

    async def test_send_failure_and_missing_stop_or_failure_ack_never_retry(self):
        for fail_stop, fail_record in ((False, False), (True, False), (False, True)):
            center, stream = CenterFixture(), WireFixture()
            stream.fail_send = True
            center.fail_stop, center.fail_record = fail_stop, fail_record
            error, _, _ = await self.expect_error(center, TransportFixture(stream), "EFFECT_UNKNOWN")
            self.assertTrue(error.send_attempted)
            self.assertEqual(len(stream.sent), 1)
            self.assertEqual(stream.closed, 1)
            self.assertNotIn("private", str(error))
            self.assertIsNone(error.__cause__)

    async def test_close_error_and_close_timeout_cannot_be_reported_as_clean(self):
        for flag in ("fail_close", "wait_close"):
            with self.subTest(flag=flag):
                center, stream = CenterFixture(), WireFixture()
                setattr(stream, flag, True)
                grant = center.grant
                center.grant = replace(grant, config=replace(grant.config, limits=replace(grant.config.limits, close_seconds=.02)))
                error, _, _ = await self.expect_error(center, TransportFixture(stream), "PRODUCER_CLEANUP_UNKNOWN")
                self.assertFalse(error.local_closed)
                self.assertEqual(center.frames[-1][2], True)
                self.assertEqual(center.stops, [])  # Provider已确认终态，不覆盖中心COMPLETED。

    async def test_empty_stream_is_transport_failure_not_synthetic_success(self):
        error, center, transport = await self.expect_error(transport=TransportFixture(WireFixture([])), code="EFFECT_UNKNOWN")
        self.assertEqual(error.failure_kind, "transport")
        self.assertEqual(center.frames, [])
        self.assertEqual(transport.stream.closed, 1)

    async def test_invalid_operation_identifier_and_missing_real_center_prevent_network(self):
        transport = TransportFixture()
        with self.assertRaises(SparkProducerError):
            await SparkChatProducer(CenterFixture(), transport).produce({"assignment": "not-a-grant"})
        self.assertEqual(transport.calls, [])
        class MissingCenter:
            async def acquire(self, _identifier):
                raise WorkerError("RESOURCE_UNAVAILABLE", "no center producer port")
        error, _, transport = await self.expect_error(MissingCenter(), code="EFFECT_UNKNOWN")
        self.assertFalse(error.send_attempted)
        self.assertEqual(transport.calls, [])

    async def test_cancel_while_acquiring_permit_is_unknown_and_never_opens(self):
        center, transport = CenterFixture(), TransportFixture()
        center.wait_acquire = True
        task = asyncio.create_task(SparkChatProducer(center, transport).produce("operation-1"))
        while not center.used:
            await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(SparkProducerError) as caught:
            await task
        self.assertEqual(caught.exception.code, "EFFECT_UNKNOWN")
        self.assertEqual(caught.exception.failure_kind, "cancel")
        self.assertEqual(center.acquisitions, 1)
        self.assertEqual(center.failures, [])
        self.assertEqual(transport.calls, [])

    async def test_incomplete_or_foreign_permit_and_disabled_resource_do_not_execute(self):
        original = grant_fixture()
        for grant in (None, {}, replace(original, operation_id="foreign"),
                replace(original, config=replace(original.config, kind="tool")),
                replace(original, config=replace(original.config, provider="openai")),
                replace(original, config=replace(original.config, enabled=False))):
            with self.subTest(kind=type(grant).__name__):
                center = CenterFixture()
                center.grant = grant
                error, _, transport = await self.expect_error(center, code="EFFECT_UNKNOWN")
                self.assertFalse(error.send_attempted)
                self.assertEqual(center.failures, [])
                self.assertEqual(transport.calls, [])

    async def test_request_payload_budget_and_boolean_limits_rejected_before_open(self):
        original = grant_fixture()
        for limits in (replace(original.config.limits, max_request_bytes=100),
                replace(original.config.limits, max_frames=True),
                replace(original.config.limits, connect_seconds=True)):
            grant = replace(original, config=replace(original.config, limits=limits))
            error, _, transport = await self.expect_error(CenterFixture(grant), code="EFFECT_UNKNOWN")
            self.assertFalse(error.send_attempted)
            self.assertEqual(transport.calls, [])
