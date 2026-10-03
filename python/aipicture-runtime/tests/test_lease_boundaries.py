from __future__ import annotations

import asyncio
import threading

import httpx
import pytest

from aipicture_runtime.execution import ExecutionClient
from test_worker import request


def claim(tool, params=None):
    payload = request(tool, params).model_dump(mode="json")
    payload["lease_token"] = "controlled-lease-only"
    return payload


def immediate_heartbeat(monkeypatch):
    original = asyncio.sleep

    async def sleep(delay):
        await original(0 if delay == 15 else delay)

    monkeypatch.setattr("aipicture_runtime.execution.asyncio.sleep", sleep)


async def close_after(client, payload):
    try:
        await asyncio.wait_for(client.process(payload), timeout=5)
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_lost_heartbeat_stops_next_actual_batch_provider_step(monkeypatch):
    payload = claim("batch_process")
    asset = payload["assets"][0]
    payload["params"] = {"asset_ids": [asset["id"]], "operations": [
        {"tool": "replace_background", "params": {"prompt": "first"}},
        {"tool": "replace_background", "params": {"prompt": "second"}},
    ], "formats": ["png"]}
    import base64
    image = base64.b64decode(asset["content_base64"])
    lost = asyncio.Event()
    provider_steps, calls = [], []

    async def post(endpoint, body):
        calls.append(endpoint)
        # wait_for 在子任务执行 process；其心跳定时器以协程名称区分。
        if endpoint == "/heartbeat" and asyncio.current_task().get_coro().__name__ == "heartbeat":
            lost.set()
            raise httpx.ReadTimeout("controlled heartbeat response lost")
        return {}

    class Provider:
        async def edit(self, value, on_progress=None):
            provider_steps.append(value.prompt)
            if len(provider_steps) == 1:
                await lost.wait()
            return [image]

    immediate_heartbeat(monkeypatch)
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: Provider())
    client = ExecutionClient()
    monkeypatch.setattr(client, "post", post)
    await close_after(client, payload)
    assert lost.is_set()
    assert len(provider_steps) == 1, "失租后仍进入了后续供应商步骤"
    assert "/artifacts" not in calls and "/receipt" not in calls


@pytest.mark.asyncio
@pytest.mark.parametrize("cancel", [False, True], ids=["lost-lease", "cancelled"])
async def test_background_stop_during_first_upload_stops_remaining_upload_and_success_receipt(monkeypatch, cancel):
    payload = claim("adjust_image")
    first_upload = asyncio.Event()
    stop = asyncio.Event()
    calls = []
    asset = payload["assets"][0]
    artifacts = [{**{key: asset[key] for key in ("id", "width", "height", "sha256", "format", "content_base64")},
                  "kind": "generated", "source": "tool"}] * 2

    async def compute(_request, _report):
        return {"result": {}, "artifacts": artifacts, "provider": {"offline_test": True}}

    async def post(endpoint, body):
        calls.append((endpoint, body))
        if endpoint == "/heartbeat":
            # 前台交付前确认租约；本场景仅让独立后台心跳在上传在途时通知停止。
            if asyncio.current_task().get_coro().__name__ != "heartbeat":
                return {"status": "running", "cancelled": False}
            await first_upload.wait()
            stop.set()
            if cancel:
                return {"cancelled": True}
            raise httpx.ReadTimeout("controlled heartbeat response lost")
        if endpoint == "/artifacts":
            first_upload.set()
            await stop.wait()
            return {"id": asset["id"]}
        return {}

    immediate_heartbeat(monkeypatch)
    monkeypatch.setattr("aipicture_runtime.execution.compute", compute)
    client = ExecutionClient()
    monkeypatch.setattr(client, "post", post)
    await close_after(client, payload)
    assert sum(endpoint == "/artifacts" for endpoint, _ in calls) == 1, "停止信号后又上传了下一产物"
    assert not any(endpoint == "/receipt" and body["status"] == "succeeded" for endpoint, body in calls)
    if not cancel:
        assert not any(endpoint == "/receipt" for endpoint, _ in calls)


@pytest.mark.asyncio
async def test_lost_heartbeat_during_background_prefill_stops_actual_split_provider_call(monkeypatch):
    payload = claim("split_layers", {"include_text": False})
    lost = threading.Event()
    provider_steps, calls = [], []

    async def post(endpoint, body):
        calls.append(endpoint)
        if endpoint == "/heartbeat" and asyncio.current_task().get_coro().__name__ == "heartbeat":
            lost.set()
            raise httpx.ReadTimeout("controlled heartbeat response lost")
        return {}

    def fill(source, mask):
        assert lost.wait(timeout=2)
        return source

    class Provider:
        async def edit(self, value, on_progress=None):
            provider_steps.append(value.prompt)
            return [value.image]

    immediate_heartbeat(monkeypatch)
    monkeypatch.setattr("aipicture_runtime.engine.remove_background", lambda data: data)
    monkeypatch.setattr("aipicture_runtime.engine.fill_background", fill)
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: Provider())
    client = ExecutionClient()
    monkeypatch.setattr(client, "post", post)
    await close_after(client, payload)
    assert lost.is_set()
    assert not provider_steps, "本地预修期间失租后仍提交了供应商重修"
    assert "/artifacts" not in calls and "/receipt" not in calls


def center_transport(failure, calls, reject):
    import json
    def handler(req):
        endpoint = req.url.path.rsplit("/", 1)[-1]
        body = json.loads(req.content)
        calls.append((endpoint, body))
        if endpoint == "heartbeat" and reject(body):
            if failure == "protocol":
                return httpx.Response(200, json={"code": 409, "msg": "controlled stale lease"})
            return httpx.Response(int(failure), json={"error_code": "CONTROLLED_LEASE_REJECTED"})
        return httpx.Response(200, json={"id": body.get("id"), "cancelled": False})
    return httpx.MockTransport(handler)


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["409", "503", "protocol"])
async def test_foreground_center_rejection_cannot_become_retryable_batch_item_failure(monkeypatch, failure):
    import uuid
    payload = claim("batch_process")
    second = {**payload["assets"][0], "id": str(uuid.uuid4())}
    payload["assets"].append(second)
    payload["params"] = {"asset_ids": [ref["id"] for ref in payload["assets"]], "operations": [
        {"tool": "replace_background", "params": {"prompt": "background"}},
    ], "formats": ["png"]}
    calls, provider_steps = [], []
    failed = False
    def reject(body):
        nonlocal failed
        if not failed and body["stage"] == "执行批量操作：replace_background":
            failed = True
            return True
        return False
    class Provider:
        async def edit(self, value, on_progress=None):
            provider_steps.append(value.prompt)
            return [value.image]
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: Provider())
    client = ExecutionClient()
    await client.client.aclose()
    client.client = httpx.AsyncClient(base_url=client.settings.center_url, transport=center_transport(failure, calls, reject))
    await close_after(client, payload)
    assert failed
    assert not provider_steps, "前台心跳无法确认后，下一批量项仍提交了供应商请求"
    assert not any(endpoint in {"artifacts", "receipt"} for endpoint, _ in calls)


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["409", "503", "protocol"])
async def test_created_provider_task_foreground_rejection_never_receipts_failed_or_reposts(monkeypatch, failure):
    from aipicture_runtime.config import get_settings
    from aipicture_runtime.providers.dashscope import DashScopeImageProvider
    get_settings().dashscope_api_key = "unused-offline-test-key"
    get_settings().provider_output_hosts = ("approved.invalid",)
    provider = DashScopeImageProvider()
    await provider._client.aclose()
    provider_calls, center_calls = [], []
    def submit(req):
        provider_calls.append((req.method, req.url.path))
        assert req.method == "POST", "失去中心确认后不能继续查询或再次提交"
        return httpx.Response(200, json={"output": {"task_id": "controlled-existing-task"}})
    provider._client = httpx.AsyncClient(base_url="https://dashscope.aliyuncs.com", transport=httpx.MockTransport(submit))
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: provider)
    client = ExecutionClient()
    await client.client.aclose()
    client.client = httpx.AsyncClient(base_url=client.settings.center_url, transport=center_transport(failure, center_calls,
        lambda body: body.get("provider_task_id") == "controlled-existing-task"))
    try:
        await close_after(client, claim("generate_image", {"prompt": "test"}))
    finally:
        await provider._client.aclose()
    assert len(provider_calls) == 1
    assert any(body.get("provider_task_id") == "controlled-existing-task" for endpoint, body in center_calls if endpoint == "heartbeat")
    assert not any(endpoint in {"artifacts", "receipt"} for endpoint, _ in center_calls), "已在途任务失租后不能报告普通失败并诱发付费重试"


@pytest.mark.asyncio
@pytest.mark.parametrize("phase", ["task", "score"])
@pytest.mark.parametrize("stop", ["healthy", "lost", "cancelled"])
async def test_evaluation_cpu_work_keeps_heartbeat_and_obeys_stop(monkeypatch, phase, stop):
    """较慢本地评测期间心跳必须可运行，失租/取消仍阻止正式采用。"""
    from aipicture_runtime.eval import runner, tasks

    payload = claim("adjust_image")
    payload["tool"] = "evaluation"
    identifier = payload["assets"][0]["id"]
    payload["params"] = {"manifest": [{"id": "heartbeat-boundary", "task": "adjust_image",
        "input_asset_id": identifier, "expect_asset_id": identifier,
        "params": {}, "metrics": ["mae", "psnr"]}]}
    entered, heartbeat_seen = threading.Event(), threading.Event()
    checkpoint_seen, calls = [], []
    original_sleep = asyncio.sleep

    def blocking(actual):
        def wrapped(*args, **kwargs):
            entered.set()
            checkpoint_seen.append(heartbeat_seen.wait(0.4))
            return actual(*args, **kwargs)
        return wrapped

    if phase == "task":
        monkeypatch.setattr(tasks, "run_task", blocking(tasks.run_task))
    else:
        monkeypatch.setattr(runner, "_score_output", blocking(runner._score_output))

    async def post(endpoint, body):
        calls.append((endpoint, body))
        if endpoint == "/heartbeat" and asyncio.current_task().get_coro().__name__ == "heartbeat":
            while not entered.is_set():
                await original_sleep(0)
            heartbeat_seen.set()
            if stop == "lost":
                raise httpx.ReadTimeout("controlled evaluation lease response lost")
            if stop == "cancelled":
                return {"cancelled": True}
        if endpoint == "/artifacts":
            return {"id": body["id"]}
        if endpoint == "/heartbeat":
            return {"status": "running", "cancelled": False}
        return {}

    immediate_heartbeat(monkeypatch)
    client = ExecutionClient()
    monkeypatch.setattr(client, "post", post)
    await close_after(client, payload)
    assert checkpoint_seen == [True], "本地任务/指标计算占用事件循环，计算期间心跳没有执行"
    receipts = [body for endpoint, body in calls if endpoint == "/receipt"]
    uploads = [body for endpoint, body in calls if endpoint == "/artifacts"]
    if stop == "healthy":
        assert len(uploads) == 1
        assert len(receipts) == 1 and receipts[0]["status"] == "succeeded"
        assert receipts[0]["result"]["cases"][0]["outputs"][0]["metrics"] == {"mae": 0, "psnr": 99}
    else:
        assert uploads == [], "失租或取消后仍上传本地评测产物"
        assert not any(body["status"] == "succeeded" for body in receipts)
        if stop == "lost":
            assert receipts == [], "失租后不应回执普通失败并导致重新派发"
        else:
            assert len(receipts) == 1 and receipts[0]["status"] == "cancelled"
