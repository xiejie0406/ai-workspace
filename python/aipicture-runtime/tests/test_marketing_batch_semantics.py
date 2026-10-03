"""原营销文案规范化与批量回滚语义；Provider桩只记录请求，不计模型质量。"""
import base64
import hashlib
import io
import json
import random
import uuid

import pytest
from PIL import Image

from aipicture_runtime import engine
from aipicture_runtime.config import get_settings
from aipicture_runtime.dto import AssetRef, ComputeRequest
from aipicture_runtime.edits.pixels import encode
from aipicture_runtime.layers import Layer, LayerDocument, LayerKind


def png(image):
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return buffer.getvalue()


def ref(identifier, data):
    image = Image.open(io.BytesIO(data))
    return AssetRef(id=identifier, width=image.width, height=image.height,
                    sha256=hashlib.sha256(data).hexdigest(), content_base64=base64.b64encode(data).decode())


def real_config():
    settings = get_settings()
    settings.offline_test = False
    settings.image_provider = "dashscope"
    settings.matting_provider = settings.ocr_provider = "unconfigured"
    return settings


@pytest.mark.asyncio
@pytest.mark.parametrize("kind,original_prompt", [
    ("product", "把商品放在纯白背景上，居中构图，柔和顶光，边缘干净，保留商品原有材质与颜色，输出电商主图。"),
    ("scene", "把商品放到真实生活场景中，侧逆光、浅景深，氛围自然，保留商品原有材质与颜色。"),
    ("model", "生成模特手持或佩戴该商品的半身展示图，简洁室内背景、自然光，商品细节清晰可辨。"),
    ("poster", "做一张促销海报，突出商品，右侧或下方留出文字区，构图干净有冲击力。"),
])
@pytest.mark.parametrize("caption,expected_extra", [(" \n\t ", ""), ("  新品 首发  ", " 文案：新品 首发")])
async def test_all_four_marketing_kinds_preserve_original_caption_prompt_and_wall_outputs(monkeypatch, kind, original_prompt, caption, expected_extra):
    real_config()
    source = png(Image.new("RGBA", (48, 40), (20, 40, 60, 255)))
    key = uuid.UUID(int=1)
    document = LayerDocument(width=48, height=40, layers=[
        Layer(id="base", kind=LayerKind.IMAGE, name="原图", width=48, height=40, asset_id=key)])
    original_document = document.model_dump(mode="json")
    captured = []
    class ProviderStub:
        async def edit(self, request, progress):
            captured.append(request)
            return [png(Image.new("RGBA", (32, 32), color)) for color in [(30, 90, 180, 255), (90, 180, 30, 255)]]
    monkeypatch.setattr(engine, "get_image_provider", lambda: ProviderStub())
    output = await engine.compute(ComputeRequest(run_id="marketing-caption-source", attempt=1,
        tool="generate_marketing", document=document, assets=[ref(key, source)],
        params={"kind": kind, "caption": caption, "count": 2, "ratio": "1:1"}))
    request = captured[0]
    assert request.prompt == original_prompt + expected_extra
    assert request.count == 2 and (request.width, request.height) == (1080, 1080)
    assert Image.open(io.BytesIO(request.image)).size == (48, 40)
    result = output["result"]
    assert len(result["asset_ids"]) == 2 and "document" not in result and "adopt_asset_id" not in result
    assert result["variants"] == [{"kind": kind, "ratio": "1:1", "width": 1080, "height": 1080, "asset_id": identifier}
                                  for identifier in result["asset_ids"]]
    assert all((item["kind"], item["source"], item["format"], item["width"], item["height"])
               == ("marketing", "tool", "PNG", 1080, 1080) for item in output["artifacts"])
    assert document.model_dump(mode="json") == original_document


@pytest.mark.asyncio
@pytest.mark.parametrize("budget", ["bytes", "count"])
async def test_partial_encoding_rollback_clears_failed_item_ids_and_keeps_other_success(budget):
    settings = real_config()
    first = png(Image.new("RGBA", (32, 32), (20, 40, 60, 255)))
    # 固定种子构造有细节的原始输入；真正执行调色与PNG/JPG编码，不用Provider或模型。
    detailed = Image.frombytes("RGB", (96, 80), random.Random(7).randbytes(96 * 80 * 3)).convert("RGBA")
    second = png(detailed)
    expected_success = [encode(first, "png"), encode(first, "jpg")]
    if budget == "bytes":
        # 成功项两格式+失败项PNG可放入，第2项JPEG才超过已有预算，形成真实部分写入回滚。
        settings.max_output_bytes = sum(map(len, expected_success)) + len(encode(second, "png"))
        assert len(first) + len(second) < settings.max_output_bytes
    else:
        settings.max_outputs = 3
    keys = [uuid.UUID(int=1), uuid.UUID(int=2)]
    output = await engine.compute(ComputeRequest(run_id="batch-rollback-ids", attempt=1, tool="batch_process",
        assets=[ref(key, data) for key, data in zip(keys, [first, second], strict=True)],
        params={"asset_ids": list(map(str, keys)), "operations": [{"tool": "adjust_image", "params": {}}], "formats": ["png", "jpg"]}))
    result = output["result"]
    assert result["summary"] == {"total": 2, "succeeded": 1, "failed": 1, "status": "partial_success"}
    succeeded, failed = result["items"]
    assert failed["source_id"] == str(keys[1]) and failed["status"] == "failed"
    assert failed["error"] == "计算输出超过资源预算" and failed["output_ids"] == []
    assert succeeded["source_id"] == str(keys[0]) and succeeded["status"] == "succeeded" and succeeded["error"] is None
    assert result["asset_ids"] == succeeded["output_ids"] == [item["id"] for item in output["artifacts"]]
    assert [base64.b64decode(item["content_base64"]) for item in output["artifacts"]] == expected_success
    assert [item["format"] for item in output["artifacts"]] == ["PNG", "JPEG"]


@pytest.mark.asyncio
async def test_batch_png_keeps_transparent_corners_and_jpeg_flattens_white(monkeypatch, tmp_path):
    real_config()
    forbidden_calls = []

    def forbid_model(*args, **kwargs):
        forbidden_calls.append(True)
        raise AssertionError("此确定性批量检查不允许Provider或模型调用")

    for name in ("get_image_provider", "remove_background", "detect_text", "segment_points"):
        monkeypatch.setattr(engine, name, forbid_model)

    # 独立输入：透明背景的隐藏RGB不是白色，中央32×32主体为实心蓝色。
    image = Image.new("RGBA", (64, 80), (235, 12, 18, 0))
    image.paste((20, 80, 160, 255), (16, 24, 48, 56))
    source = png(image)
    key = uuid.UUID(int=16)
    output = await engine.compute(ComputeRequest(run_id="r16-batch-alpha", attempt=1, tool="batch_process",
        assets=[ref(key, source)], params={"asset_ids": [str(key)],
            "operations": [{"tool": "adjust_image", "params": {"brightness": 0.25}}],
            "formats": ["png", "jpg"]}))
    assert not forbidden_calls
    assert output["result"]["summary"] == {"total": 1, "succeeded": 1, "failed": 0, "status": "succeeded"}
    artifacts = output["artifacts"]
    assert [item["format"] for item in artifacts] == ["PNG", "JPEG"]
    assert output["result"]["asset_ids"] == output["result"]["items"][0]["output_ids"] == [item["id"] for item in artifacts]
    decoded = []
    (tmp_path / "input.png").write_bytes(source)
    for item, suffix in zip(artifacts, ("png", "jpg"), strict=True):
        data = base64.b64decode(item["content_base64"], validate=True)
        (tmp_path / f"output.{suffix}").write_bytes(data)
        assert hashlib.sha256(data).hexdigest() == item["sha256"]
        picture = Image.open(io.BytesIO(data))
        picture.load()
        assert picture.size == (64, 80) and picture.format == item["format"]
        assert (item["width"], item["height"]) == (64, 80)
        decoded.append(picture)
    png_image, jpeg_image = decoded
    assert png_image.mode == "RGBA" and artifacts[0]["has_alpha"] is True
    assert jpeg_image.mode == "RGB" and artifacts[1]["has_alpha"] is False
    # PNG逐像素alpha由独立矩形定义；不以encode()/adjust()产物作预期。
    assert png_image.getchannel("A").tobytes() == bytes(
        255 if 16 <= x < 48 and 24 <= y < 56 else 0 for y in range(80) for x in range(64))
    assert png_image.getpixel((32, 40)) == (25, 100, 200, 255)
    corners = [(0, 0), (63, 0), (0, 79), (63, 79)]
    # JPEG是有损编码：远离主体边界的全白8×8角块允许最多2/255舍入误差。
    # 若透明RGB直接丢弃，此处将成为红色，不能通过此容差。
    corner_blocks = [(x, y) for y in (*range(8), *range(72, 80))
                     for x in (*range(8), *range(56, 64))]
    assert all(png_image.getpixel(point)[3] == 0 for point in corners)
    assert all(all(abs(channel - 255) <= 2 for channel in jpeg_image.getpixel(point)) for point in corner_blocks)
    assert all(abs(channel - expected) <= 3 for channel, expected in zip(jpeg_image.getpixel((32, 40)), (25, 100, 200), strict=True))
    report = {"input_sha256": hashlib.sha256(source).hexdigest(), "size": [64, 80],
        "transparent_pixels": 64 * 80 - 32 * 32, "opaque_subject_box": [16, 24, 48, 56],
        "png_corners": [png_image.getpixel(point) for point in corners],
        "jpeg_corners": [jpeg_image.getpixel(point) for point in corners],
        "jpeg_white_block_max_error": max(abs(channel - 255) for point in corner_blocks for channel in jpeg_image.getpixel(point)),
        "png_subject_center": png_image.getpixel((32, 40)), "jpeg_subject_center": jpeg_image.getpixel((32, 40)),
        "artifacts": [{key: item[key] for key in ("id", "format", "width", "height", "sha256", "has_alpha")} for item in artifacts],
        "provider_calls": 0, "model_inference": 0, "formal_asset_writes": 0, "database_writes": 0,
        "business_acceptance": False}
    (tmp_path / "pixel-review.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"R16_ALPHA_EVIDENCE={tmp_path}")
