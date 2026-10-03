"""真实ExecutionClient/engine与受控中心传输；不使用真实模型或网络。"""
from __future__ import annotations

import asyncio
import hashlib
import json

import httpx
import pytest

from aipicture_runtime import execution
from aipicture_runtime.execution import ExecutionClient
from test_worker import request


class Center:
    """模拟中心既有同ID登记/终态回执，不实现另一套Worker状态。"""

    def __init__(self, endpoint, failure, *, exhausted=False, heartbeat_stop=None, committed=False):
        self.endpoint = endpoint
        self.failure = failure
        self.exhausted = exhausted
        self.heartbeat_stop = heartbeat_stop
        self.committed = committed
        self.calls = []
        self.registered = {}
        self.receipt = None
        self.receipt_commits = 0
        self.target_attempts = 0
        self.delivery_failed = False
        self.computed = False

    def accept(self, endpoint, body):
        if endpoint == "artifacts":
            existing = self.registered.setdefault(body["id"], body["content_base64"])
            assert existing == body["content_base64"], "同ID不能重交不同字节"
            return {"id": body["id"]}
        if endpoint == "receipt":
            if self.receipt is None:
                self.receipt = body
                self.receipt_commits += 1
            return {"status": self.receipt["status"]}
        return {}

    def __call__(self, req):
        endpoint = req.url.path.rsplit("/", 1)[-1]
        body = json.loads(req.content)
        self.calls.append({"endpoint": endpoint, "body": body, "raw": req.content,
                           "nonce": req.headers["X-Aipicture-Nonce"]})
        if endpoint == "heartbeat":
            if self.computed and self.heartbeat_stop == "empty-data":
                return httpx.Response(200, json={"code": 200, "data": {}})
            if self.computed and self.heartbeat_stop == "missing-data":
                return httpx.Response(200, json={"code": 200})
            if self.computed and self.heartbeat_stop == "null-status":
                return httpx.Response(200, json={"code": 200, "data": {"status": None, "cancelled": False}})
            if self.delivery_failed and self.heartbeat_stop:
                if self.heartbeat_stop == "cancel":
                    return httpx.Response(200, json={"code": 200, "data": {"status": "cancelled", "cancelled": True}})
                return httpx.Response(int(self.heartbeat_stop), json={"code": int(self.heartbeat_stop)})
            status = self.receipt["status"] if self.receipt else "running"
            if self.computed and self.heartbeat_stop == "terminal-unknown":
                status = "external_result_unknown"
            return httpx.Response(200, json={"code": 200, "data": {"status": status, "cancelled": False}})
        if endpoint == self.endpoint:
            self.target_attempts += 1
            if self.exhausted or self.target_attempts == 1:
                self.delivery_failed = True
                if self.committed:
                    self.accept(endpoint, body)
                if self.failure == "timeout":
                    raise httpx.ReadTimeout("controlled center response lost", request=req)
                if self.failure == "disconnect":
                    raise httpx.RemoteProtocolError("controlled center closed response", request=req)
                if self.failure.startswith("body-"):
                    return httpx.Response(200, json={"code": int(self.failure[5:]), "msg": "controlled center rejection"})
                return httpx.Response(int(self.failure), json={"code": int(self.failure)})
        return httpx.Response(200, json={"code": 200, "data": self.accept(endpoint, body)})

    def of(self, endpoint):
        return [call for call in self.calls if call["endpoint"] == endpoint]


async def actual_batch(monkeypatch, center):
    """一次真实batch计算，两种真实图片格式；Provider只是纯内存替身。"""
    provider_calls = []
    computation_calls = []
    payload = request("batch_process").model_dump(mode="json")
    payload["lease_token"] = "controlled-fixed-lease"
    payload["params"] = {"asset_ids": [payload["assets"][0]["id"]], "operations": [
        {"tool": "replace_background", "params": {"prompt": "controlled scene"}},
    ], "formats": ["png", "jpg"]}

    class Provider:
        async def edit(self, value, on_progress=None):
            provider_calls.append(value.prompt)
            return [value.image]

    original_compute = execution.compute
    original_sleep = asyncio.sleep

    async def counted_compute(value, report):
        computation_calls.append(value.run_id)
        result = await original_compute(value, report)
        center.computed = True
        return result

    async def controlled_sleep(delay):
        # 只压缩本测试中交付退避，15秒后台心跳仍保留独立计时。
        await original_sleep(0 if delay in (1, 2) else delay)

    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: Provider())
    monkeypatch.setattr(execution, "compute", counted_compute)
    monkeypatch.setattr(execution.asyncio, "sleep", controlled_sleep)
    client = ExecutionClient()
    await client.client.aclose()
    client.client = httpx.AsyncClient(base_url=client.settings.center_url, transport=httpx.MockTransport(center), trust_env=False)
    try:
        await asyncio.wait_for(client.process(payload), timeout=5)
    finally:
        await client.close()
    assert len(computation_calls) == 1, "交付恢复不能重跑compute"
    assert len(provider_calls) == 1, "交付恢复不能重跑收费Provider"
    return payload


def assert_replay_same(calls):
    assert len(calls) >= 2
    assert len({call["raw"] for call in calls}) == 1, "只允许重交原始同一JSON/ID/内容"
    assert len({call["nonce"] for call in calls}) == len(calls), "每次传输必须新签名nonce"


def assert_no_failed_receipt(center):
    assert not any(call["body"].get("error_code") == "COMPUTE_FAILED" for call in center.of("receipt")), \
        "成功外部结果的交付错误不能降为可重新付费的COMPUTE_FAILED"


@pytest.mark.parametrize("endpoint", ["artifacts", "receipt"])
@pytest.mark.parametrize("failure", ["503", "timeout", "body-503", "body-500", "disconnect"])
async def test_temporary_center_delivery_replays_same_payload_without_recomputing(monkeypatch, endpoint, failure):
    center = Center(endpoint, failure)
    payload = await actual_batch(monkeypatch, center)
    assert_no_failed_receipt(center)
    assert center.receipt is not None and center.receipt["status"] == "succeeded"
    assert center.receipt_commits == 1
    assert len(center.registered) == 2
    assert center.receipt["result"]["items"][0]["status"] == "succeeded"
    assert set(center.receipt["result"]["items"][0]["output_ids"]) == set(center.registered)
    attempts = center.of(endpoint)
    if endpoint == "artifacts":
        replay = [call for call in attempts if call["body"]["id"] == attempts[0]["body"]["id"]]
        assert len(attempts) == 3  # 第一幅两次，第二幅一次。
    else:
        replay = attempts
        assert len(attempts) == 2
    assert_replay_same(replay)
    assert replay[0]["body"]["run_id"] == payload["run_id"]
    assert replay[0]["body"]["attempt"] == payload["attempt"]
    assert replay[0]["body"]["lease_token"] == payload["lease_token"]


@pytest.mark.parametrize("endpoint", ["artifacts", "receipt"])
@pytest.mark.parametrize("failure", ["403", "body-409"])
async def test_explicit_center_refusal_stops_without_failed_receipt_or_replay(monkeypatch, endpoint, failure):
    center = Center(endpoint, failure)
    await actual_batch(monkeypatch, center)
    assert_no_failed_receipt(center)
    assert len(center.of(endpoint)) == 1
    if endpoint == "artifacts":
        assert not center.of("receipt") and not center.registered
    else:
        assert center.receipt is None


@pytest.mark.parametrize("endpoint", ["artifacts", "receipt"])
async def test_temporary_delivery_exhaustion_is_bounded_and_never_overwrites_success(monkeypatch, endpoint):
    center = Center(endpoint, "503", exhausted=True)
    await actual_batch(monkeypatch, center)
    assert_no_failed_receipt(center)
    calls = center.of(endpoint)
    assert len(calls) == 3, "交付仅原发送加两次重交"
    assert_replay_same(calls)
    assert center.receipt is None
    if endpoint == "artifacts":
        assert not center.of("receipt")


@pytest.mark.parametrize("stop", ["409", "503", "cancel"])
async def test_replay_requires_fresh_lease_confirmation_and_preserves_cancellation(monkeypatch, stop):
    center = Center("artifacts", "503", heartbeat_stop=stop)
    await actual_batch(monkeypatch, center)
    assert_no_failed_receipt(center)
    assert len(center.of("artifacts")) == 1, "租约未确认或取消后不能继续上传"
    receipts = center.of("receipt")
    if stop == "cancel":
        assert len(receipts) == 1 and receipts[0]["body"]["status"] == "cancelled"
    else:
        assert not receipts


async def test_committed_artifact_response_loss_uses_same_id_and_bytes(monkeypatch):
    center = Center("artifacts", "timeout", committed=True)
    await actual_batch(monkeypatch, center)
    assert_no_failed_receipt(center)
    assert len(center.registered) == 2 and center.receipt_commits == 1
    calls = center.of("artifacts")
    first_id = calls[0]["body"]["id"]
    assert_replay_same([call for call in calls if call["body"]["id"] == first_id])
    import base64
    from io import BytesIO
    from PIL import Image
    images = [Image.open(BytesIO(base64.b64decode(value))) for value in center.registered.values()]
    assert {image.format for image in images} == {"PNG", "JPEG"}
    assert {image.size for image in images} == {(64, 80)}
    assert len({hashlib.sha256(base64.b64decode(value)).hexdigest() for value in center.registered.values()}) == 2


async def test_committed_receipt_response_loss_replays_only_same_terminal_receipt(monkeypatch):
    center = Center("receipt", "timeout", committed=True)
    await actual_batch(monkeypatch, center)
    assert_no_failed_receipt(center)
    assert center.receipt_commits == 1 and center.receipt["status"] == "succeeded"
    assert len(center.of("artifacts")) == 2, "终态心跳后不能重复上传或重新计算"
    assert_replay_same(center.of("receipt"))
    assert any(call["endpoint"] == "heartbeat" and index > next(
        i for i, value in enumerate(center.calls) if value["endpoint"] == "receipt") for index, call in enumerate(center.calls))


async def test_center_terminal_unknown_before_delivery_prevents_upload_and_receipt(monkeypatch):
    center = Center("artifacts", "503", heartbeat_stop="terminal-unknown")
    await actual_batch(monkeypatch, center)
    assert not center.of("artifacts") and not center.of("receipt"), "中心终态不能被迟到计算采用覆盖"


@pytest.mark.parametrize("shape", ["empty-data", "missing-data", "null-status"])
async def test_successful_http_without_explicit_lease_state_cannot_authorize_delivery(monkeypatch, shape):
    center = Center("artifacts", "503", heartbeat_stop=shape)
    await actual_batch(monkeypatch, center)
    assert not center.of("artifacts") and not center.of("receipt"), "HTTP成功不能代替明确执行租约确认"
