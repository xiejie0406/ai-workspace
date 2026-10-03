"""远端在付费请求后断开必须保留未知结果；仅使用本地 MockTransport。"""
import uuid

import httpx
import pytest

from aipicture_runtime.config import get_settings
from aipicture_runtime.providers.dashscope import DashScopeImageProvider
from test_paid_recovery_and_exports import receipt_of
from test_worker import request


def real_provider_settings():
    settings = get_settings()
    settings.offline_test = False
    settings.image_provider = "dashscope"
    settings.matting_provider = "unconfigured"
    settings.ocr_provider = "unconfigured"
    settings.dashscope_api_key = "test-unused-key"
    settings.provider_output_hosts = ("approved.invalid",)


async def intercepted_provider(monkeypatch, handler):
    real_provider_settings()
    provider = DashScopeImageProvider()
    await provider._client.aclose()
    provider._client = httpx.AsyncClient(base_url="https://dashscope.aliyuncs.com",
        transport=httpx.MockTransport(handler), trust_env=False)
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: provider)
    return provider


def tool_payload(tool):
    if tool == "batch_process":
        payload = request(tool)
        payload.params = {"asset_ids": [str(payload.assets[0].id)], "operations": [
            {"tool": "replace_background", "params": {"prompt": "背景"}}], "formats": ["png"]}
        return payload
    return request(tool, {"prompt": "商品图"})


@pytest.mark.parametrize("tool", ["replace_background", "batch_process", "generate_image"])
async def test_paid_post_remote_disconnect_is_unknown_not_retryable_failure(monkeypatch, tool):
    calls = []
    def handler(req):
        calls.append((req.method, req.url.path, req.content))
        assert req.method == "POST" and req.content
        raise httpx.RemoteProtocolError("Server disconnected without sending a response.", request=req)
    provider = await intercepted_provider(monkeypatch, handler)
    try:
        receipt, center = await receipt_of(tool_payload(tool), monkeypatch)
    finally:
        await provider._client.aclose()
    assert receipt["status"] == "external_result_unknown", receipt
    assert receipt["error_code"] == "PROVIDER_OUTCOME_UNKNOWN"
    assert "provider_task_id" not in receipt
    assert len(calls) == 1 and calls[0][0] == "POST"
    assert not any(endpoint == "/artifacts" for endpoint, _ in center)


@pytest.mark.parametrize("task_origin", ["existing", "submitted"])
async def test_known_generation_disconnect_keeps_original_task_and_never_reposts(monkeypatch, task_origin):
    task_id = "source-task-123"
    calls = []
    def handler(req):
        calls.append((req.method, req.url.path))
        if req.method == "POST":
            assert task_origin == "submitted" and len(calls) == 1
            return httpx.Response(200, json={"output": {"task_id": task_id}})
        assert req.url.path == f"/api/v1/tasks/{task_id}"
        raise httpx.RemoteProtocolError("Server disconnected without sending a response.", request=req)
    provider = await intercepted_provider(monkeypatch, handler)
    payload = tool_payload("generate_image")
    if task_origin == "existing":
        payload.provider_task_id = task_id
    try:
        receipt, center = await receipt_of(payload, monkeypatch)
    finally:
        await provider._client.aclose()
    assert receipt["status"] == "external_result_unknown", receipt
    assert receipt["error_code"] == "PROVIDER_TASK_PENDING"
    assert receipt["provider_task_id"] == task_id
    assert [method for method, _ in calls] == (["GET"] if task_origin == "existing" else ["POST", "GET"])
    assert not any(endpoint == "/artifacts" for endpoint, _ in center)


async def test_batch_adapter_remote_disconnect_propagates_before_item_failure(monkeypatch):
    real_provider_settings()
    calls = []
    class LostResponse:
        async def edit(self, *args):
            calls.append(args)
            raise httpx.RemoteProtocolError("Server disconnected without sending a response.")
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: LostResponse())
    payload = tool_payload("batch_process")
    second = payload.assets[0].model_copy(update={"id": uuid.uuid4()})
    payload.assets.append(second)
    payload.params["asset_ids"].append(str(second.id))
    payload.params["operations"] *= 2
    receipt, center = await receipt_of(payload, monkeypatch)
    assert len(calls) == 1, f"外部编辑调用次数应为1，实际为{len(calls)}"
    assert receipt["status"] == "external_result_unknown", receipt
    assert receipt["error_code"] == "PROVIDER_OUTCOME_UNKNOWN"
    assert not any(endpoint == "/artifacts" for endpoint, _ in center)


@pytest.mark.parametrize("failure", ["local_protocol", "malformed_json"])
async def test_local_protocol_and_json_errors_keep_explicit_failure_boundary(monkeypatch, failure):
    calls = []
    def handler(req):
        calls.append((req.method, req.url.path))
        if failure == "local_protocol":
            raise httpx.LocalProtocolError("Invalid local request headers.", request=req)
        return httpx.Response(200, content=b"not-json")
    provider = await intercepted_provider(monkeypatch, handler)
    try:
        receipt, center = await receipt_of(tool_payload("replace_background"), monkeypatch)
    finally:
        await provider._client.aclose()
    assert receipt["status"] == "failed", receipt
    assert receipt["error_code"] == "COMPUTE_FAILED"
    assert len(calls) == 1
    assert not any(endpoint == "/artifacts" for endpoint, _ in center)
