"""付费请求未知结果不能变成可重复调用的失败；导出尊重正式画布。"""
import base64
import io
import json
import uuid
import zipfile
from datetime import UTC, datetime

import httpx
import pytest
from PIL import Image
from pydantic import ValidationError

from aipicture_runtime.config import get_settings
from aipicture_runtime.dto import AssetRef, ComputeRequest
from aipicture_runtime.engine import compute
from aipicture_runtime.execution import ExecutionClient
from aipicture_runtime.providers.base import ProviderError, ProviderOutcomeUnknown
from aipicture_runtime.providers.dashscope import DashScopeImageProvider
from test_worker import request, png


async def receipt_of(payload, monkeypatch):
    payload = payload.model_dump(mode="json")
    payload["lease_token"] = "leased-test"
    calls = []
    async def post(endpoint, body):
        calls.append((endpoint, body))
        if endpoint == "/heartbeat":
            return {"status": "running", "cancelled": False}
        return {"id": body.get("id")} if endpoint == "/artifacts" else {}
    client = ExecutionClient()
    monkeypatch.setattr(client, "post", post)
    try:
        await client.process(payload)
    finally:
        await client.close()
    return next(body for endpoint, body in calls if endpoint == "/receipt"), calls


@pytest.mark.asyncio
async def test_batch_actual_edit_timeout_propagates_unknown_without_artifacts(monkeypatch):
    class LostResponse:
        async def edit(self, *args):
            raise httpx.ReadTimeout("test response lost")
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: LostResponse())
    payload = request("batch_process")
    payload.params = {"asset_ids": [str(payload.assets[0].id)], "operations": [
        {"tool": "replace_background", "params": {"prompt": "背景"}}], "formats": ["png"]}
    result, calls = await receipt_of(payload, monkeypatch)
    assert result["status"] == "external_result_unknown"
    assert result["error_code"] == "PROVIDER_OUTCOME_UNKNOWN"
    assert not any(endpoint == "/artifacts" for endpoint, _ in calls)


def configured_provider():
    get_settings().dashscope_api_key = "test-unused-key"
    get_settings().provider_output_hosts = ("approved.invalid",)
    return DashScopeImageProvider()


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["pending", "download"])
async def test_existing_generate_task_timeout_or_download_never_reposts(monkeypatch, failure):
    provider = configured_provider()
    await provider._client.aclose()
    calls = []
    def handler(req):
        calls.append((req.method, req.url.path))
        assert req.method == "GET"
        return httpx.Response(200, json={"output": {"task_status": "RUNNING" if failure == "pending" else "SUCCEEDED",
            "choices": [{"message": {"content": [{"image": "https://approved.invalid/result.png"}]}}]}})
    provider._client = httpx.AsyncClient(base_url="https://dashscope.aliyuncs.com", transport=httpx.MockTransport(handler))
    if failure == "pending":
        monkeypatch.setattr("aipicture_runtime.providers.dashscope._POLL_TIMEOUT", -1)
    else:
        async def download(_url):
            raise httpx.ReadTimeout("test paid result cannot download")
        monkeypatch.setattr("aipicture_runtime.artifacts.read_provider_result", download)
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: provider)
    payload = request("generate_image", {"prompt": "商品图"})
    payload.provider_task_id = "existing-task-123"
    try:
        result, _ = await receipt_of(payload, monkeypatch)
    finally:
        await provider._client.aclose()
    assert result["status"] == "external_result_unknown"
    assert result["provider_task_id"] == "existing-task-123"
    assert result["error_code"] == "PROVIDER_TASK_PENDING"
    assert len(calls) == 1 and calls[0][0] == "GET"


@pytest.mark.asyncio
async def test_explicit_remote_failure_remains_normal_failed(monkeypatch):
    class Rejected:
        async def edit(self, *args):
            raise ProviderError("明确拒绝请求")
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: Rejected())
    result, _ = await receipt_of(request("replace_background", {"prompt": "背景"}), monkeypatch)
    assert result["status"] == "failed" and result["error_code"] == "COMPUTE_FAILED"


@pytest.mark.asyncio
async def test_zip_current_canvas_matches_png_and_other_asset_keeps_bytes():
    payload = request("export")
    current = str(payload.assets[0].id)
    other = payload.assets[0].model_copy(update={"id": uuid.uuid4()})
    payload.document.layers[0].opacity = 0
    payload.params = {"format": "png"}
    rendered = await compute(payload)
    expected = base64.b64decode(rendered["artifacts"][0]["content_base64"])
    payload.assets.append(other)
    payload.params = {"format": "zip", "asset_ids": [current, str(other.id)], "current_asset_id": current}
    output = await compute(payload)
    with zipfile.ZipFile(io.BytesIO(base64.b64decode(output["artifacts"][0]["content_base64"]))) as archive:
        names = [name for name in archive.namelist() if name.startswith("images/")]
        assert archive.read(names[0]) == expected
        assert Image.open(io.BytesIO(expected)).getpixel((20, 25)) == (255, 255, 255, 255)
        assert archive.read(names[1]) == base64.b64decode(other.content_base64)
        manifest = json.loads(archive.read("manifest.json"))
        assert manifest["files"][0]["asset_id"] == current


@pytest.mark.asyncio
@pytest.mark.parametrize("amount", [65, 200])
async def test_export_200_reference_budget_is_specific_to_export(amount):
    payload = request("export")
    prototype = payload.assets[0]
    payload.assets = [prototype.model_copy(update={"id": uuid.uuid4()}) for _ in range(amount)]
    payload.document = None
    payload.params = {"format": "zip", "asset_ids": [str(ref.id) for ref in payload.assets]}
    payload = ComputeRequest.model_validate(payload.model_dump(mode="json"))
    output = await compute(payload)
    with zipfile.ZipFile(io.BytesIO(base64.b64decode(output["artifacts"][0]["content_base64"]))) as archive:
        assert len(json.loads(archive.read("manifest.json"))["files"]) == amount
    as_normal = payload.model_dump(mode="json")
    as_normal["tool"] = "adjust_image"
    with pytest.raises(ValidationError, match="64"):
        ComputeRequest.model_validate(as_normal)
    as_normal["tool"] = "export"
    as_normal["assets"].append({**as_normal["assets"][0], "id": str(uuid.uuid4())})
    if amount == 200:
        with pytest.raises(ValidationError, match="200"):
            ComputeRequest.model_validate(as_normal)


@pytest.mark.asyncio
@pytest.mark.parametrize("size,expected", [((64, 64), "1:1"), ((64, 80), "4:5"),
    ((60, 80), "3:4"), ((45, 80), "9:16"), ((80, 45), "16:9"), ((65, 80), None)])
async def test_export_manifest_keeps_all_source_metadata(size, expected):
    from aipicture_runtime.artifacts import probe
    raw = png(size=size)
    meta = probe(raw)
    identifier = uuid.uuid4()
    payload = ComputeRequest(run_id="test-manifest", attempt=1, tool="export", session_id="test-session",
        params={"format": "zip", "title": "商品套图", "asset_ids": [str(identifier)]},
        assets=[AssetRef(id=identifier, kind="marketing", content_base64=base64.b64encode(raw).decode(),
            **{key: meta[key] for key in ("sha256", "width", "height", "format")})])
    output = await compute(payload)
    with zipfile.ZipFile(io.BytesIO(base64.b64decode(output["artifacts"][0]["content_base64"]))) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        assert manifest["title"] == "商品套图" and manifest["session_id"] == "test-session"
        assert manifest["files"] == manifest["items"]
        item = manifest["items"][0]
        assert item["ratio"] == expected and item["kind"] == "marketing"
        assert (item["width"], item["height"]) == size
        assert item["asset_id"] == str(identifier) and item["file"] in archive.namelist()


@pytest.mark.asyncio
@pytest.mark.parametrize("selection", ["empty", "current", "other"])
async def test_zip_manifest_preserves_original_actual_utc_creation_time(selection):
    payload = request("export")
    identifier = str(payload.assets[0].id)
    payload.params = {"format": "zip", "asset_ids": [] if selection == "empty" else [identifier]}
    if selection == "current":
        payload.params["current_asset_id"] = identifier
    elif selection == "other":
        payload.document = None
    before = datetime.now(UTC)
    output = await compute(payload)
    after = datetime.now(UTC)
    with zipfile.ZipFile(io.BytesIO(base64.b64decode(output["artifacts"][0]["content_base64"]))) as archive:
        manifest = json.loads(archive.read("manifest.json"))
    assert manifest == output["result"]["manifest"]
    created = datetime.fromisoformat(manifest["created_at"].replace("Z", "+00:00"))
    assert created.tzinfo is not None and created.utcoffset().total_seconds() == 0
    assert before <= created <= after


@pytest.mark.asyncio
@pytest.mark.parametrize("title,expected", [("中文 物料!", "中文-物料"),
    ("../a/b\\c:*?\"<>|\x00\n", "abc"), ("CON", "物料-CON"), ("   ", "物料")])
async def test_zip_filename_uses_safe_title_and_manifest_utc_date(title, expected):
    payload = request("export", {"format": "zip", "title": title})
    result = (await compute(payload))["result"]
    created = datetime.fromisoformat(result["manifest"]["created_at"])
    assert result["filename"] == f"{expected}-{created:%Y%m%d}.zip"


@pytest.mark.asyncio
async def test_png_filename_is_safe_and_does_not_change_canvas_pixels():
    payload = request("export", {"format": "png", "title": "CON"})
    named = await compute(payload)
    payload.params.pop("title")
    ordinary = await compute(payload)
    assert named["result"]["filename"] == "物料-CON.png"
    assert named["artifacts"][0]["content_base64"] == ordinary["artifacts"][0]["content_base64"]


def test_capabilities_reports_hash_and_does_not_claim_model_loaded(tmp_path):
    from aipicture_runtime.capabilities import capabilities
    from aipicture_runtime.edits import segment
    model = tmp_path / "configured-encoder.onnx"
    model.write_bytes(b"test-model-metadata-only")
    get_settings().sam_encoder_path = model
    original = segment._sam
    segment._sam = None
    try:
        state = capabilities()["capabilities"]["sam"]
        assert state["loaded"] is False and state["validation"] == "files_and_dependencies_only"
        entry = state["models"]["encoder"]
        import hashlib
        assert entry["present"] and entry["sha256"] == hashlib.sha256(model.read_bytes()).hexdigest()
        assert entry["size_bytes"] == len(model.read_bytes())
        assert state["models"]["decoder"]["present"] is False
    finally:
        segment._sam = original


@pytest.mark.parametrize("enabled,key,hosts,offline,image_ready,planner_ready", [
    (True, "test-unused-key", (), False, False, True),
    (True, "test-unused-key", ("approved.invalid",), False, True, True),
    (True, "", ("approved.invalid",), False, False, False),
    (False, "test-unused-key", ("approved.invalid",), False, False, False),
    (True, "test-unused-key", ("approved.invalid",), True, False, False),
])
def test_image_download_hosts_do_not_gate_text_planner(monkeypatch, enabled, key, hosts, offline, image_ready, planner_ready):
    from aipicture_runtime.capabilities import capabilities
    settings = get_settings()
    settings.enabled, settings.dashscope_api_key = enabled, key
    settings.image_provider, settings.provider_output_hosts = "dashscope", hosts
    settings.offline_test = offline
    monkeypatch.setattr("aipicture_runtime.capabilities.find_spec", lambda _name: object())
    result = capabilities()["capabilities"]
    for capability in ("image_generation", "image_edit", "enhancement"):
        assert result[capability]["ready"] is image_ready
    assert result["planner"]["ready"] is planner_ready
