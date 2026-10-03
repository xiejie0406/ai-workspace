"""真实小图的输入拒绝通过既有签名 ASGI 入口，不能变成计算故障。

不调用中心/Provider、不登记产物、不运行模型。解压炸弹用临时低阈值和小PNG
触发 Pillow 的真实异常，既不生成大图，也不改生产限制。
"""
import base64
import hashlib
import io
import json
import struct
import uuid
import zlib

import pytest
from fastapi.testclient import TestClient
from PIL import Image, UnidentifiedImageError

from aipicture_runtime.artifacts import probe
from aipicture_runtime.config import get_settings
from aipicture_runtime.engine import Computation
from aipicture_runtime.main import create_app
from aipicture_runtime.security import sign_headers


def small_image(fmt="PNG", size=(64, 80)):
    image = Image.new("RGBA", size)
    image.putdata([(x % 256, (x * 3) % 256, (x * 7) % 256, 255) for x in range(size[0] * size[1])])
    stream = io.BytesIO()
    image.save(stream, fmt, **({"lossless": True} if fmt == "WEBP" else {}))
    return stream.getvalue()


def truncated_png():
    raw = small_image()
    position = 8
    while position < len(raw):
        length = struct.unpack(">I", raw[position:position + 4])[0]
        if raw[position + 4:position + 8] == b"IDAT":
            return raw[:position + 8 + max(1, length // 3)]
        position += length + 12
    raise AssertionError("真实PNG没有IDAT")


def inspection(raw):
    return {"schema_version": "1", "tool_registry_version": "2026.10-r2",
            "run_id": str(uuid.uuid4()), "attempt": 1, "input_version": 0,
            "tool": "inspect_image", "params": {"content_base64": base64.b64encode(raw).decode(), "minimum_side": 32}}


def signed_post(client, payload, path="/internal/v1/compute"):
    body = json.dumps(payload).encode()
    headers = sign_headers(get_settings().service_secret, "aipicture-java", "POST", path, body)
    return client.post(path, content=body, headers={**headers, "Content-Type": "application/json"})


@pytest.fixture
def no_side_effects(monkeypatch):
    calls = {"provider": 0, "external_http": 0, "artifact": 0}

    def provider(*args, **kwargs):
        calls["provider"] += 1
        raise AssertionError("图像检查不能调用Provider")

    def external_http(*args, **kwargs):
        calls["external_http"] += 1
        raise AssertionError("内联图像检查不能发起外部HTTP")

    def artifact(*args, **kwargs):
        calls["artifact"] += 1
        raise AssertionError("图像检查不能登记计算产物")

    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", provider)
    monkeypatch.setattr("aipicture_runtime.artifacts.httpx.AsyncClient", external_http)
    monkeypatch.setattr(Computation, "store", artifact)
    yield calls
    assert calls == {"provider": 0, "external_http": 0, "artifact": 0}


@pytest.mark.parametrize("kind", ["unidentified", "truncated_png", "bomb"])
def test_actual_invalid_images_return_safe_structured_422(kind, monkeypatch, no_side_effects):
    if kind == "unidentified":
        raw = b"not-an-image:original-input-must-not-be-returned"
        with pytest.raises(UnidentifiedImageError):
            Image.open(io.BytesIO(raw))
    elif kind == "truncated_png":
        raw = truncated_png()
        with Image.open(io.BytesIO(raw)) as image:
            assert image.size == (64, 80)  # header合法，错误必须发生在真实decode。
            with pytest.raises(OSError):
                image.load()
    else:
        raw = small_image()
        monkeypatch.setattr(Image, "MAX_IMAGE_PIXELS", 1000)
        with pytest.raises(Image.DecompressionBombError):
            Image.open(io.BytesIO(raw))
    payload = inspection(raw)
    with TestClient(create_app()) as client:
        response = signed_post(client, payload)
    assert response.status_code == 422, response.text
    result = response.json()
    assert result["error_code"] == "INVALID_IMAGE"
    assert isinstance(result["message"], str) and result["message"]
    assert "detail" not in result and "artifacts" not in result and "asset_ids" not in result
    assert payload["params"]["content_base64"] not in response.text
    assert "original-input-must-not-be-returned" not in response.text
    assert get_settings().service_secret not in response.text
    assert all(name not in response.text for name in ("UnidentifiedImageError", "DecompressionBombError", "Traceback", "input_value"))


@pytest.mark.parametrize("fmt", ["PNG", "WEBP"])
def test_valid_png_webp_keeps_real_metadata_and_zero_artifacts(fmt, no_side_effects):
    raw = small_image(fmt)
    with TestClient(create_app()) as client:
        response = signed_post(client, inspection(raw))
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["result"]["format"] == fmt
    assert (result["result"]["width"], result["result"]["height"]) == (64, 80)
    assert result["result"]["sha256"] == hashlib.sha256(raw).hexdigest()
    assert result["artifacts"] == []


def test_prepare_selection_uses_same_safe_rejection_for_corrupted_inline_asset(no_side_effects):
    raw = b"not-a-real-mask-or-image"
    identifier = str(uuid.uuid4())
    payload = inspection(raw)
    payload.update(tool="prepare_selection", params={}, document={"width": 64, "height": 80, "layers": [
        {"id": "base", "name": "底图", "kind": "image", "width": 64, "height": 80, "asset_id": identifier}]},
        assets=[{"id": identifier, "sha256": hashlib.sha256(raw).hexdigest(), "width": 64, "height": 80,
                 "content_base64": base64.b64encode(raw).decode()}])
    with TestClient(create_app()) as client:
        response = signed_post(client, payload, "/internal/v1/prepare-selection")
    assert response.status_code == 422, response.text
    assert response.json()["error_code"] == "INVALID_IMAGE"
    assert response.json()["message"]
    assert payload["assets"][0]["content_base64"] not in response.text


def test_real_compute_fault_still_503_without_image_rejection_code(monkeypatch, no_side_effects):
    async def failed_compute(payload):
        raise RuntimeError("受控计算故障")
    monkeypatch.setattr("aipicture_runtime.main.compute", failed_compute)
    payload = inspection(small_image())
    with TestClient(create_app()) as client:
        response = signed_post(client, payload)
    assert response.status_code == 503, response.text
    assert response.json() == {"code": "COMPUTE_UNAVAILABLE", "message": "受控计算故障",
                               "run_id": payload["run_id"], "attempt": 1}
    assert "error_code" not in response.json()


def test_pydantic_validation_is_not_globally_forwarded_as_image_message(no_side_effects):
    payload = inspection(small_image())
    payload["attempt"] = 0
    with TestClient(create_app()) as client:
        response = signed_post(client, payload)
    assert response.status_code == 422
    assert "detail" in response.json()
    assert "error_code" not in response.json() and "message" not in response.json()


def test_image_rejection_still_requires_service_signature(no_side_effects):
    with TestClient(create_app()) as client:
        response = client.post("/internal/v1/compute", json=inspection(b"bad-image"))
    assert response.status_code == 401
    assert "error_code" not in response.json()


def test_fifty_million_pixel_header_is_rejected_before_decode(monkeypatch):
    # 真实PNG容器中的超限IHDR；压缩数据只有一个像素，不能被完整解码/分配大图。
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    raw = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 10000, 5001, 8, 2, 0, 0, 0))
    raw += chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00")) + chunk(b"IEND", b"")
    with Image.open(io.BytesIO(raw)) as image:
        assert image.size == (10000, 5001)
    calls = []
    from PIL.PngImagePlugin import PngImageFile
    def forbidden_decode(self, *args, **kwargs):
        calls.append("decode")
        raise AssertionError("超限header不能进入完整decode")
    monkeypatch.setattr(PngImageFile, "load", forbidden_decode)
    with pytest.raises(ValueError, match="5000"):
        probe(raw)
    assert calls == []


def test_twenty_mb_limit_rejects_before_pillow_parser(monkeypatch):
    calls = []
    def forbidden_open(*args, **kwargs):
        calls.append("open")
        raise AssertionError("超限文件不能进入图片解析")
    monkeypatch.setattr(Image, "open", forbidden_open)
    with pytest.raises(ValueError, match="20 MB"):
        probe(b"x" * (20 * 1024 * 1024 + 1))
    assert calls == []
