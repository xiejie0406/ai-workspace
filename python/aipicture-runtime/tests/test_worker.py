from __future__ import annotations

import base64
import copy
import hashlib
import io
import json
import time
import uuid
import zipfile

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

from aipicture_runtime import SCHEMA_VERSION, TOOL_REGISTRY_VERSION
from aipicture_runtime.agent.plan import PlanError, assemble, validate as validate_plan
from aipicture_runtime.artifacts import checked_center_path, probe
from aipicture_runtime.config import Settings, get_settings
from aipicture_runtime.dto import ComputeRequest
from aipicture_runtime.edits.mask import apply_masked, overlay_png, to_luma
from aipicture_runtime.edits.ocr import TextBox, detect_text
from aipicture_runtime.edits.pixels import remove_background
from aipicture_runtime.edits.segment import segment_points
from aipicture_runtime.engine import compute
from aipicture_runtime.execution import ExecutionClient, remap_ids
from aipicture_runtime.geometry import inverse_affine, layer_under_mask, local_to_canvas, mask_for_layer
from aipicture_runtime.layers import Layer, LayerDocument, LayerKind, Transform
from aipicture_runtime.main import create_app
from aipicture_runtime.registry import SPECS, export_registry, validate
from aipicture_runtime.security import CredentialError, CredentialVerifier, sign_headers


def png(color=(255, 255, 255, 255), size=(64, 80), *, subject=True):
    image = Image.new("RGBA", size, color)
    if subject:
        ImageDraw.Draw(image).rectangle((16, 20, 47, 59), fill=(30, 90, 180, 255))
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return buffer.getvalue()


def request(tool: str, params: dict | None = None, *, layered: bool = False):
    identifier = uuid.uuid4()
    raw = png()
    doc = LayerDocument(width=64, height=80, layers=[Layer(id="base", kind=LayerKind.IMAGE,
        name="底图", width=64, height=80, asset_id=identifier, locked=True)])
    if layered:
        doc.layers[0].id = "background"
        doc.layers.append(Layer(id="subject", kind=LayerKind.IMAGE, name="主体", width=64, height=80, asset_id=identifier,
                                transform=Transform(x=4, y=2, scale_x=-0.5, scale_y=0.5, rotation=20)))
    return ComputeRequest(run_id=str(uuid.uuid4()), attempt=1, tool=tool, params=params or {}, document=doc,
        input_version=9, input_revision=7, session_id=str(uuid.uuid4()), assets=[{"id": identifier, "width": 64, "height": 80,
        "sha256": hashlib.sha256(raw).hexdigest(), "content_base64": base64.b64encode(raw).decode()}])


async def with_mask(payload: ComputeRequest):
    selected = payload.model_copy(deep=True)
    selected.tool = "selection"
    selected.params = {"strokes": [[[0.25, 0.25], [0.75, 0.25], [0.75, 0.75], [0.25, 0.75]]], "radius": 0.01}
    result = await compute(selected)
    artifact = result["artifacts"][0]
    from aipicture_runtime.dto import AssetRef
    payload.assets.append(AssetRef.model_validate({key: artifact[key] for key in ["id", "sha256", "width", "height", "format", "content_base64"]}))
    payload.params.update(mask_asset_id=artifact["id"], revision=7)
    return payload


PIXEL_CASES = [
    ("generate_image", {"prompt": "蓝色杯子", "count": 1}),
    ("replace_background", {"prompt": "绿色背景"}),
    ("expand_canvas", {"ratio": "1:1"}),
    ("upscale_image", {"scale": 3}),
    ("remove_background", {}),
    ("adjust_image", {"brightness": 0.2, "vignette": 0.3}),
    ("erase_region", {}),
    ("replace_region", {"prompt": "红色杯子"}),
    ("split_layers", {"include_text": False}),
    ("promote_object_to_layer", {"name": "杯子"}),
    ("generate_marketing", {"kind": "poster", "caption": "今日优惠", "count": 1}),
    ("prepare_delivery_sizes", {"ratios": ["1:1", "4:5", "1:1"]}),
]


@pytest.mark.parametrize("tool,params", PIXEL_CASES)
async def test_all_twelve_individual_computational_tools(tool, params):
    payload = request(tool, params)
    if tool in {"erase_region", "replace_region", "promote_object_to_layer"}:
        payload = await with_mask(payload)
    frozen = copy.deepcopy(payload.document.model_dump(mode="json"))
    result = await compute(payload)
    assert result["schema_version"] == SCHEMA_VERSION
    assert result["tool_registry_version"] == TOOL_REGISTRY_VERSION
    assert result["run_id"] == payload.run_id and result["attempt"] == 1
    assert result["provider"]["offline_test"] is True
    assert result["artifacts"], tool
    assert payload.document.model_dump(mode="json") == frozen  # Worker不能采用/修改冻结中心文档
    for artifact in result["artifacts"]:
        raw = base64.b64decode(artifact["content_base64"])
        assert hashlib.sha256(raw).hexdigest() == artifact["sha256"]
        assert Image.open(io.BytesIO(raw)).size == (artifact["width"], artifact["height"])
    if tool in {"generate_marketing", "prepare_delivery_sizes"}:
        assert "adopt_asset_id" not in result["result"]


@pytest.mark.parametrize("tool,params", [
    ("crop_canvas", {"ratio": "1:1"}), ("flip_layer", {"direction": "horizontal"}),
    ("set_layer_opacity", {"opacity": 0.5}), ("set_layer_visible", {"visible": False}),
    ("reorder_layer", {"place": "top"}), ("scale_layer", {"factor": 1.5}),
    ("rotate_layer", {"angle": 20}), ("move_layer", {"dx": 8}),
])
async def test_eight_document_commands_match_pure_source_algorithm(tool, params):
    output = await compute(request(tool, {**params, "revision": 7, "mask_asset_id": None}))
    assert output["result"]["document"]
    assert not output["artifacts"]


async def test_text_document_command_keeps_editable_vector():
    payload = request("set_layer_text", {"layer_id": "title", "text": "新文案", "font_size": 32, "fill": "#123abc"})
    payload.document.layers.append(Layer(id="title", kind=LayerKind.TEXT, name="标题", width=64, height=40, text="旧文案"))
    output = await compute(payload)
    layer = output["result"]["document"]["layers"][-1]
    assert layer["text"] == "新文案" and layer["font_size"] == 32


@pytest.mark.parametrize("tool,params", [
    ("remove_background", {}), ("replace_background", {"prompt": "蓝色背景"}),
    ("adjust_image", {"saturation": 0.2}), ("upscale_image", {"scale": 2}),
    ("expand_canvas", {"ratio": "16:9"}), ("prepare_delivery_sizes", {"ratios": ["1:1", "9:16"]}),
])
async def test_batch_all_six_whitelisted_branches_and_formats(tool, params):
    payload = request("batch_process")
    payload.params = {"asset_ids": [str(payload.assets[0].id)], "operations": [{"tool": tool, "params": params}], "formats": ["png", "jpg"]}
    output = await compute(payload)
    assert output["result"]["summary"] == {"total": 1, "succeeded": 1, "failed": 0, "status": "succeeded"}
    assert {artifact["format"] for artifact in output["artifacts"]} == {"PNG", "JPEG"}
    assert all(not artifact["has_alpha"] for artifact in output["artifacts"] if artifact["format"] == "JPEG")


async def test_batch_partial_success_keeps_per_item_failure():
    payload = request("batch_process")
    payload.params = {"asset_ids": [str(payload.assets[0].id), str(uuid.uuid4())],
                      "operations": [{"tool": "adjust_image", "params": {}}], "formats": ["png"]}
    result = (await compute(payload))["result"]
    assert result["summary"]["status"] == "partial_success"
    assert result["items"][1]["status"] == "failed"


@pytest.mark.parametrize("tool,params", [("replace_background", {"prompt": "新背景"}), ("expand_canvas", {"ratio": "1:1"}), ("upscale_image", {"scale": 2})])
async def test_layered_generation_only_goes_to_wall(tool, params):
    result = (await compute(request(tool, params, layered=True)))["result"]
    assert result["asset_ids"] and "adopt_asset_id" not in result and "document" not in result


async def test_split_ocr_branch_and_duplicate_skip(monkeypatch):
    monkeypatch.setattr("aipicture_runtime.engine.detect_text", lambda data: [TextBox("促销", 5, 8, 24, 18)])
    output = await compute(request("split_layers", {"include_text": True}))
    assert any(layer["kind"] == "text" and layer["text"] == "促销" for layer in output["result"]["document"]["layers"])
    payload = request("split_layers", {"include_text": False}, layered=True)
    skipped = await compute(payload)
    assert not skipped["artifacts"]


async def test_promote_on_transformed_split_reconstructs_without_duplicate_pixels():
    payload = await with_mask(request("promote_object_to_layer", {"name": "物体新层"}, layered=True))
    output = await compute(payload)
    assert len(output["result"]["document"]["layers"]) == 3
    assert output["result"]["document"]["layers"][-1]["source_hash"]
    assert output["result"]["selection_clear"]


def test_inverse_affine_rotation_scale_flip_and_translation():
    layer = Layer(id="x", kind=LayerKind.IMAGE, name="图层", width=20, height=10,
        transform=Transform(x=30, y=40, scale_x=-2, scale_y=3, rotation=90))
    forward = local_to_canvas(layer)
    inverse = inverse_affine(forward)
    def apply(coefficients, x, y):
        a, b, c, d, e, f = coefficients
        return a * x + b * y + c, d * x + e * y + f
    for local in [(0, 0), (5, 4), (10, 5), (20, 10)]:
        canvas = apply(forward, *local)
        recovered = apply(inverse, *canvas)
        assert recovered == pytest.approx(local)
    assert apply(forward, 10, 5) == pytest.approx((40, 45))


@pytest.mark.parametrize("transform", [Transform(x=20, y=20), Transform(x=20, y=20, scale_x=-1),
    Transform(x=20, y=20, scale_x=2, scale_y=0.5), Transform(x=20, y=20, rotation=90),
    Transform(x=20, y=20, scale_x=-2, scale_y=1.5, rotation=37)])
def test_mask_mapping_matches_layer_center_for_all_affine_variants(transform):
    layer = Layer(id="x", kind=LayerKind.IMAGE, name="图层", width=20, height=20, transform=transform)
    image = Image.new("L", (80, 80), 0)
    ImageDraw.Draw(image).rectangle((27, 27, 33, 33), fill=255)
    local = to_luma(mask_for_layer(overlay_png(image), layer, (80, 80), png(size=(20, 20), subject=False)))
    assert local.getpixel((10, 10)) > 0


def test_hit_selection_uses_alpha_and_visibility_not_rectangles():
    bottom_id, top_id = uuid.uuid4(), uuid.uuid4()
    doc = LayerDocument(width=64, height=80, layers=[
        Layer(id="bottom", kind=LayerKind.IMAGE, name="底层", width=64, height=80, asset_id=bottom_id),
        Layer(id="top", kind=LayerKind.IMAGE, name="顶层", width=64, height=80, asset_id=top_id)])
    mask = Image.new("L", (64, 80), 0)
    ImageDraw.Draw(mask).rectangle((0, 0, 6, 6), fill=255)
    images = {bottom_id: png(subject=False), top_id: png(color=(0, 0, 0, 0))}
    assert layer_under_mask(doc, overlay_png(mask), images).id == "bottom"


def test_region_soft_edge_does_not_change_any_zero_mask_pixel():
    source, edited = png(subject=False), png((255, 0, 0, 0), subject=False)
    mask = Image.new("L", (64, 80), 0)
    ImageDraw.Draw(mask).rectangle((20, 25, 43, 54), fill=255)
    output = Image.open(io.BytesIO(apply_masked(source, edited, overlay_png(mask)))).convert("RGBA")
    original = Image.open(io.BytesIO(source)).convert("RGBA")
    for y in range(80):
        for x in range(64):
            if mask.getpixel((x, y)) == 0:
                assert output.getpixel((x, y)) == original.getpixel((x, y))


async def test_expired_or_resized_selection_rejected():
    payload = await with_mask(request("replace_region", {"prompt": "变蓝"}))
    payload.params["revision"] = 6
    with pytest.raises(ValueError, match="过期"):
        await compute(payload)
    payload.params["revision"] = 7
    payload.document.width = 65
    with pytest.raises(ValueError, match="画幅"):
        await compute(payload)


@pytest.mark.parametrize("fn", [remove_background, detect_text, lambda data: segment_points(data, [(0.5, 0.5)])])
def test_real_models_missing_are_explicit_failures_not_test_fallback(monkeypatch, fn):
    monkeypatch.setenv("AIPICTURE_OFFLINE_TEST", "false")
    monkeypatch.setenv("AIPICTURE_IMAGE_PROVIDER", "unconfigured")
    monkeypatch.setenv("AIPICTURE_MATTING_PROVIDER", "unconfigured")
    monkeypatch.setenv("AIPICTURE_OCR_PROVIDER", "unconfigured")
    get_settings.cache_clear()
    with pytest.raises((RuntimeError, ImportError)):
        fn(png())


def test_test_providers_cannot_be_silently_enabled_in_real_mode():
    with pytest.raises(ValueError, match="离线测试"):
        Settings(offline_test=False, image_provider="mock", matting_provider="unconfigured", ocr_provider="unconfigured")


def test_signature_body_path_expiry_and_replay_boundaries():
    secret, service = "s" * 32, "aipicture-worker"
    body, path = b'{"run_id":"x"}', "/internal/v1/compute"
    verifier = CredentialVerifier(secret, service)
    headers = sign_headers(secret, service, "POST", path, body)
    verifier.verify("POST", path, body, headers)
    with pytest.raises(CredentialError, match="已使用"):
        verifier.verify("POST", path, body, headers)
    with pytest.raises(CredentialError, match="无效"):
        CredentialVerifier(secret, service).verify("POST", path + "x", body, headers)
    with pytest.raises(CredentialError, match="无效"):
        CredentialVerifier(secret, service).verify("POST", path, body + b"x", headers)
    old = sign_headers(secret, service, "POST", path, body, timestamp=str(int(time.time()) - 1000))
    with pytest.raises(CredentialError, match="过期"):
        CredentialVerifier(secret, service).verify("POST", path, body, old)


def test_resource_urls_no_arbitrary_paths_or_redirect_hosts():
    identifier = uuid.uuid4()
    expected = f"http://127.0.0.1:8080/aipicture/internal/v1/execution/assets/{identifier}"
    assert checked_center_path(expected, identifier).endswith(str(identifier))
    for url in ["file:///etc/passwd", f"https://evil.test/{identifier}", expected + "/../x", expected + "?run_id=x"]:
        with pytest.raises(ValueError):
            checked_center_path(url, identifier)


async def test_hash_or_oversized_document_rejected_before_computation():
    payload = request("adjust_image")
    payload.assets[0].sha256 = "0" * 64
    with pytest.raises(ValueError, match="hash"):
        await compute(payload)
    payload = request("adjust_image")
    payload.document.width = 50_000_001
    with pytest.raises(ValueError, match="资源限制"):
        await compute(payload)
    payload = request("adjust_image")
    payload.document.layers[0].transform.scale_x = 1e300
    payload.document.layers[0].transform.scale_y = 1e-300
    with pytest.raises(ValueError, match="资源限制"):
        await compute(payload)


async def test_export_default_white_and_explicit_transparency_and_zip_manifest():
    payload = request("export", {"format": "png"})
    payload.document.layers[0].opacity = 0.25
    white = await compute(payload)
    payload.params["background_mode"] = "transparent"
    transparent = await compute(payload)
    wa = Image.open(io.BytesIO(base64.b64decode(white["artifacts"][0]["content_base64"])))
    ta = Image.open(io.BytesIO(base64.b64decode(transparent["artifacts"][0]["content_base64"])))
    assert wa.getpixel((0, 0))[3] == 255 and ta.getpixel((0, 0))[3] < 255
    payload.params["format"] = "zip"
    output = await compute(payload)
    archive = zipfile.ZipFile(io.BytesIO(base64.b64decode(output["artifacts"][0]["content_base64"])))
    manifest = json.loads(archive.read("manifest.json"))
    assert manifest["background_mode"] == "transparent" and manifest["files"][0]["sha256"]
    assert "images/canvas.png" in archive.namelist()


async def test_eval_control_calculates_actual_metrics_and_dataset_hash():
    payload = request("adjust_image")
    payload.tool = "evaluation"
    source = str(payload.assets[0].id)
    payload.params = {"manifest": [{"id": "identity", "task": "adjust_image", "input_asset_id": source,
        "expect_asset_id": source, "params": {}, "metrics": ["mae", "psnr"]}]}
    output = (await compute(payload))["result"]
    assert output["metrics"] == {"passed": 1, "total": 1}
    assert output["cases"][0]["outputs"][0]["metrics"] == {"mae": 0, "psnr": 99}
    assert len(output["dataset_hash"]) == 64


def test_registry_is_complete_and_hidden_parameters_not_given_to_planner():
    from aipicture_runtime.agent.llm import _schema_of
    registry = export_registry()
    assert len(SPECS) == len(registry["tools"]) == 22
    assert sum(item["executionOwner"] == "java" for item in registry["tools"]) == 9
    assert sum(item["executionOwner"] == "python" for item in registry["tools"]) == 13
    for spec in SPECS:
        schema = _schema_of(spec)["function"]["parameters"]
        assert all(hidden not in schema["properties"] for hidden in spec.agent_hidden)
    with pytest.raises(PlanError, match="重复"):
        assemble([{"id": "x", "tool": "adjust_image"}, {"id": "x", "tool": "adjust_image"}])
    with pytest.raises(PlanError):
        assemble([{"id": str(index), "tool": "adjust_image"} for index in range(9)])
    with pytest.raises(PlanError):
        assemble([{"id": "x", "tool": "adjust_image", "depends_on": ["y"]}, {"id": "y", "tool": "adjust_image", "depends_on": ["x"]}])
    assert len(validate_plan([{"tool": "adjust_image", "params": {}}])) == 1


def test_private_api_signature_and_actual_java_shape():
    with TestClient(create_app()) as client:
        payload = request("adjust_image", {"brightness": 0.1, "revision": 7, "mask_asset_id": None}).model_dump(mode="json")
        path = "/internal/v1/compute"
        body = json.dumps(payload).encode()
        headers = sign_headers(get_settings().service_secret, "aipicture-java", "POST", path, body)
        response = client.post(path, content=body, headers={**headers, "Content-Type": "application/json"})
        assert response.status_code == 200, response.text
        assert response.json()["run_id"] == payload["run_id"]
        replay = client.post(path, content=body, headers=headers)
        assert replay.status_code == 401
        assert client.get("/internal/v1/capabilities").status_code == 401
        caps_path, caps_body = "/internal/v1/capabilities", b"{}"
        caps_headers = sign_headers(get_settings().service_secret, "aipicture-java", "POST", caps_path, caps_body)
        caps = client.post(caps_path, content=caps_body, headers=caps_headers)
        assert caps.status_code == 200 and len(caps.json()["tools"]) == 22
        wrong_sender = sign_headers(get_settings().service_secret, "aipicture-worker", "POST", caps_path, caps_body)
        assert client.post(caps_path, content=caps_body, headers=wrong_sender).status_code == 401


async def test_claim_outer_fields_filter_and_artifact_receipt_remapping(monkeypatch):
    payload = request("adjust_image", {"brightness": 0.1}).model_dump(mode="json")
    payload.update(lease_token="lease-test-1234567890", lease_expires_at="2026-10-03T23:00:00Z", worker_id="aipicture-worker")
    calls = []
    async def post(endpoint, body):
        calls.append((endpoint, body))
        if endpoint == "/artifacts":
            return {"id": "java-issued-formal-id"}
        if endpoint == "/heartbeat":
            return {"status": "running", "cancelled": False}
        return {}
    client = ExecutionClient()
    monkeypatch.setattr(client, "post", post)
    await client.process(payload)
    await client.close()
    receipt = next(body for endpoint, body in calls if endpoint == "/receipt")
    assert receipt["status"] == "succeeded"
    assert receipt["lease_token"] == payload["lease_token"]
    assert receipt["result"]["document"]["layers"][0]["asset_id"] == "java-issued-formal-id"
    assert receipt["artifacts"] == ["java-issued-formal-id"]
    assert not any("content_base64" in json.dumps(body) for endpoint, body in calls if endpoint == "/receipt")
