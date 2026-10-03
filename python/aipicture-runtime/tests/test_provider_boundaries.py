from __future__ import annotations

import base64
import hashlib
import io
import json
import uuid
from types import SimpleNamespace

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from aipicture_runtime.agent.graph import spoken
from aipicture_runtime.agent import graph as agent_graph
from aipicture_runtime.artifacts import read_asset, read_provider_result
from aipicture_runtime.config import get_settings
from aipicture_runtime.dto import AssetRef
from aipicture_runtime.edits import segment
from aipicture_runtime.engine import compute
from aipicture_runtime.eval.__main__ import main as eval_main
from aipicture_runtime.execution import ExecutionClient
from aipicture_runtime.main import create_app
from aipicture_runtime.providers.base import EditRequest, GenerateRequest
from aipicture_runtime.providers.dashscope import DashScopeImageProvider
from aipicture_runtime.reporting import progress_callback
from aipicture_runtime.security import sign_headers

from test_worker import png, request


def test_embedding_cache_reuses_hash_and_evicts_lru_four(monkeypatch):
    calls = []
    monkeypatch.setattr(segment, "_encode", lambda session, source: calls.append(source.getpixel((0, 0))) or {"value": len(calls)})
    monkeypatch.setattr(segment, "_embeddings", type(segment._embeddings)())
    monkeypatch.setattr(segment, "_inflight", {})
    session = SimpleNamespace(encoder=object(), cache_namespace=object(),
                              model_fingerprints=(("encoder", 0, "controlled-weight"),))
    source = Image.new("RGB", (4, 4), (1, 2, 3))
    def raw_of(image):
        buffer = io.BytesIO()
        image.save(buffer, "PNG")
        return buffer.getvalue()

    a = segment._embedding_of(session, raw_of(source), source)
    assert segment._embedding_of(session, raw_of(source), source) is a
    assert len(calls) == 1
    for index in range(1, 5):
        different = Image.new("RGB", (4, 4), (index + 1, 2, 3))
        segment._embedding_of(session, raw_of(different), different)
    assert len(segment._embeddings) == 4
    assert all(value is not a for value in segment._embeddings.values())


async def test_dashscope_native_payloads_progress_task_id_and_resume_never_submit(monkeypatch):
    # 只截获 HTTP 层，不设置真实密钥，不产生模型调用费用。
    settings = get_settings()
    settings.dashscope_api_key = "test-unused-key"
    settings.provider_output_hosts = ("approved.invalid",)
    provider = DashScopeImageProvider()
    calls = []
    raw = png()
    def handler(request):
        calls.append((request.method, request.url.path, json.loads(request.content) if request.content else None))
        if request.method == "POST" and "image-generation" in request.url.path:
            return httpx.Response(200, json={"output": {"task_id": "source-task-123"}})
        return httpx.Response(200, json={"output": {"task_status": "SUCCEEDED", "choices": [{"message": {"content": [{"image": "https://approved.invalid/result.png"}]}}]}})
    await provider._client.aclose()
    provider._client = httpx.AsyncClient(base_url="https://dashscope.aliyuncs.com", transport=httpx.MockTransport(handler))
    async def download(url):
        return raw
    monkeypatch.setattr(provider, "_download", download)
    reported = []
    async def report(progress, stage, details):
        reported.append(details)
    token = progress_callback.set(report)
    try:
        generated = await provider.generate(GenerateRequest(prompt="商品图", width=1080, height=1080, seed=12,
            negative_prompt="模糊", references=[raw]))
    finally:
        progress_callback.reset(token)
    assert generated == [raw] and reported == [{"provider_task_id": "source-task-123", "provider_operation": "generate"}]
    payload = calls[0][2]
    assert payload["model"] == "qwen-image-3.0-pro" and payload["parameters"]["seed"] == 12
    assert payload["parameters"]["negative_prompt"] == "模糊"
    assert payload["input"]["messages"][0]["content"][0]["image"].startswith("data:image/png;base64,")
    before = len([call for call in calls if call[0] == "POST"])
    assert await provider.resume_generate("source-task-123") == [raw]
    assert len([call for call in calls if call[0] == "POST"]) == before
    await provider.edit(EditRequest(prompt="换背景", image=raw, width=4000, height=1000, negative_prompt="模糊"))
    edited = calls[-1][2]
    assert edited["model"] == "qwen-image-edit-max" and edited["parameters"]["size"] == "2048*512"
    assert "negative_prompt" in edited["parameters"]
    await provider._client.aclose()


async def test_provider_result_url_rejects_ssrf_before_network():
    for target in ["file:///C:/Windows/win.ini", "http://127.0.0.1/internal", "https://metadata.internal/x", "https://dashscope.aliyuncs.com:8443/x"]:
        with pytest.raises(ValueError, match="HTTPS"):
            await read_provider_result(target)


async def test_frozen_read_url_matches_run_attempt_lease_and_expected_asset(monkeypatch):
    payload = request("adjust_image")
    payload.lease_token = "lease-current"
    raw = png()
    asset = payload.assets[0].model_copy(update={"content_base64": None,
        "read_url": f"http://127.0.0.1:8080/aipicture/internal/v1/execution/assets/{payload.assets[0].id}?run_id={payload.run_id}&attempt=1&lease_token=lease-current"})
    original_client = httpx.AsyncClient
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(200, content=raw)
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original_client(transport=httpx.MockTransport(handler), **kwargs))
    assert await read_asset(asset, payload) == raw
    assert calls[0].headers["X-Aipicture-Service"] == "aipicture-worker"
    invalid = asset.model_copy(update={"read_url": asset.read_url.replace("lease-current", "lease-other")})
    with pytest.raises(ValueError, match="run/attempt/lease"):
        await read_asset(invalid, payload)
    assert len(calls) == 1


async def test_cancellation_stops_future_batch_steps_and_never_uploads(monkeypatch):
    payload = request("batch_process").model_dump(mode="json")
    payload["params"] = {"asset_ids": [payload["assets"][0]["id"]], "operations": [{"tool": "replace_background", "params": {"prompt": "新背景"}}], "formats": ["png"]}
    payload["lease_token"] = "lease-current"
    calls = []
    async def post(endpoint, body):
        calls.append((endpoint, body))
        return {"cancelled": True} if endpoint == "/heartbeat" else {}
    client = ExecutionClient()
    monkeypatch.setattr(client, "post", post)
    await client.process(payload)
    await client.close()
    assert not any(endpoint == "/artifacts" for endpoint, body in calls)
    receipt = next(body for endpoint, body in calls if endpoint == "/receipt")
    assert receipt["status"] == "cancelled" and receipt["error_code"] == "CANCELLED"


async def test_unknown_paid_outcome_is_not_reported_as_retryable_failure(monkeypatch):
    payload = request("replace_background", {"prompt": "新背景"}).model_dump(mode="json")
    payload["lease_token"] = "lease-current"
    async def failed_compute(*args):
        raise httpx.ReadTimeout("remote outcome unknown")
    monkeypatch.setattr("aipicture_runtime.execution.compute", failed_compute)
    calls = []
    async def post(endpoint, body):
        calls.append((endpoint, body))
        return {}
    client = ExecutionClient()
    monkeypatch.setattr(client, "post", post)
    await client.process(payload)
    await client.close()
    receipt = next(body for endpoint, body in calls if endpoint == "/receipt")
    assert receipt["status"] == "external_result_unknown" and receipt["error_code"] == "PROVIDER_OUTCOME_UNKNOWN"


def test_private_selection_http_accepts_java_params_and_true_revision():
    with TestClient(create_app()) as client:
        payload = request("selection", {"points": [{"x": 0.5, "y": 0.5}], "radius": 0.025, "revision": 7}).model_dump(mode="json")
        path = "/internal/v1/prepare-selection"
        body = json.dumps(payload).encode()
        headers = sign_headers(get_settings().service_secret, "aipicture-java", "POST", path, body)
        response = client.post(path, content=body, headers=headers)
        assert response.status_code == 200, response.text
        assert response.json()["result"]["revision"] == 7  # CAS row_version=9, canvas_revision=7
        assert response.json()["result"]["mask_asset_id"]


def test_eval_cli_failure_exit_and_dataset_path_escape(tmp_path):
    (tmp_path / "a.png").write_bytes(png())
    (tmp_path / "b.png").write_bytes(png((30, 30, 30, 255), subject=False))
    (tmp_path / "cases.json").write_text(json.dumps([{"id": "fails", "task": "adjust_image", "input": "a.png", "expect": "b.png", "metrics": ["mae"], "thresholds": {"mae": 0}}]), encoding="utf-8")
    assert eval_main([str(tmp_path)]) == 1
    (tmp_path / "cases.json").write_text(json.dumps([{"id": "escape", "task": "adjust_image", "input": "../a.png", "expect": "b.png", "metrics": ["mae"]}]), encoding="utf-8")
    assert eval_main([str(tmp_path)]) == 1


def test_agent_spoken_no_plan_and_single_multi_step_cases():
    assert "确认后开始" in spoken("", [{"tool": "adjust_image"}, {"tool": "flip_layer", "params": {"direction": "horizontal"}}])
    assert "正在调色" in spoken("", [{"tool": "adjust_image"}])
    assert spoken("不能做到。详细描述", []) == "不能做到。"


async def test_actual_langgraph_compiles_and_validates_tool_calls_without_database(monkeypatch):
    from langchain_core.messages import AIMessage
    calls = [{"name": "adjust_image", "args": {"brightness": 0.2}, "id": "call-1", "type": "tool_call"},
             {"name": "flip_layer", "args": {"direction": "horizontal"}, "id": "call-2", "type": "tool_call"}]
    class LocalLLM:
        async def ainvoke(self, messages):
            assert len(messages) == 2 and "当前画布" in messages[0].content
            return AIMessage(content="", tool_calls=calls)
    monkeypatch.setattr(agent_graph, "planner", lambda: LocalLLM())
    agent_graph._graph.cache_clear()
    reply, plan = await agent_graph.run("调亮并水平翻转", "64×80，底图")
    assert len(plan) == 2 and plan[1]["depends_on"] == ["s1"]
    assert plan[0]["params"]["brightness"] == 0.2 and "确认后开始" in reply
    agent_graph._graph.cache_clear()


def test_eval_resize_budget_rejects_before_allocating_pixels():
    from aipicture_runtime.eval.tasks import run_task
    with pytest.raises(ValueError, match="资源限制"):
        run_task("resize", png(), {"width": 1000000, "height": 1000000})
