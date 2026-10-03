"""素材像素与逻辑框分离；真实Compute+受控Provider桩只验证几何/传递，不验证模型质量。"""
import base64
import hashlib
import io
import uuid

import pytest
from PIL import Image, ImageDraw

from aipicture_runtime import engine
from aipicture_runtime.config import get_settings
from aipicture_runtime.dto import AssetRef, ComputeRequest
from aipicture_runtime.edits.mask import overlay_png, to_luma
from aipicture_runtime.edits.render import flatten
from aipicture_runtime.edits.split import EmptyCut
from aipicture_runtime.geometry import canvas_to_local, layer_under_mask, mask_for_layer
from aipicture_runtime.layers import Layer, LayerDocument, LayerKind, Transform


def png(image):
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return buffer.getvalue()


def pattern(size=(128, 96)):
    image = Image.new("RGBA", size)
    image.putdata([((x * 17 + y * 3) % 256, (x * 5 + y * 19) % 256,
                    255 if (x + y) % 2 else 0, 255)
                   for y in range(size[1]) for x in range(size[0])])
    return image


def ref(identifier, raw):
    image = Image.open(io.BytesIO(raw))
    return AssetRef(id=identifier, width=image.width, height=image.height,
                    sha256=hashlib.sha256(raw).hexdigest(), content_base64=base64.b64encode(raw).decode())


def region(canvas, box):
    image = Image.new("L", canvas, 0)
    ImageDraw.Draw(image).rectangle((box[0], box[1], box[2] - 1, box[3] - 1), fill=255)
    return overlay_png(image)


def request(tool, document, images, params=None, mask=None):
    # 真配置形式下只跑纯计算；Provider/抠图桩在各测试明确设置，不下载/调用模型。
    settings = get_settings()
    settings.offline_test = False
    settings.image_provider = "dashscope"
    settings.matting_provider = settings.ocr_provider = "unconfigured"
    params = dict(params or {})
    if mask is not None:
        key = uuid.UUID(int=100)
        images = {**images, key: mask}
        params.update(mask_asset_id=str(key), revision=0)
    return ComputeRequest(run_id="native-layer-pixels", attempt=1, tool=tool, document=document,
                          params=params, assets=[ref(key, data) for key, data in images.items()])


def artifact_images(output):
    return {uuid.UUID(item["id"]): Image.open(io.BytesIO(base64.b64decode(item["content_base64"]))).convert("RGBA")
            for item in output["artifacts"]}


@pytest.mark.asyncio
async def test_zero_adjust_preserves_original_native_pixels_and_logical_document():
    original = pattern()
    identifier = uuid.UUID(int=1)
    layer = Layer(id="base", kind=LayerKind.IMAGE, name="原图", width=64, height=48, asset_id=identifier,
                  transform=Transform(x=8, y=6, scale_x=-1, scale_y=.5, rotation=90))
    document = LayerDocument(width=96, height=80, layers=[layer])
    output = await engine.compute(request("adjust_image", document, {identifier: png(original)}))
    result = artifact_images(output)[uuid.UUID(output["result"]["asset_ids"][0])]
    assert result.size == (128, 96) and result.tobytes() == original.tobytes()
    next_doc = LayerDocument.model_validate(output["result"]["document"])
    expected = layer.model_dump(exclude={"asset_id"})
    assert next_doc.layers[0].model_dump(exclude={"asset_id"}) == expected
    assert flatten(document, {identifier: png(original)}) == flatten(next_doc, {next_doc.layers[0].asset_id: png(result)})
    assert output["provider"]["offline_test"] is False


@pytest.mark.parametrize("transform,world_box,native_box", [
    (Transform(x=24, y=28), (32, 32, 40, 40), (16, 8, 32, 24)),
    (Transform(x=24, y=28, scale_x=-1), (32, 32, 40, 40), (32, 8, 48, 24)),
    (Transform(x=24, y=28, scale_y=-1), (32, 32, 40, 40), (16, 24, 32, 40)),
    (Transform(x=32, y=24, scale_x=2, scale_y=.5), (32, 32, 48, 36), (16, 8, 32, 24)),
    (Transform(x=24, y=28, rotation=90), (40, 32, 44, 40), (16, 16, 32, 24)),
    (Transform(x=24, y=28, scale_x=-2, scale_y=.5, rotation=90), (40, 48, 44, 56), (16, 8, 24, 24)),
])
def test_native_mask_bbox_uses_independent_center_affine_cases(transform, world_box, native_box):
    # 手工推导半开像素框；native64×48→logical32×24的系数为.5，非调用待测映射倒推预期。
    layer = Layer(id="subject", kind=LayerKind.IMAGE, name="主体", width=32, height=24,
                  asset_id=uuid.UUID(int=1), transform=transform)
    mask = mask_for_layer(region((96, 96), world_box), layer, (96, 96), png(pattern((64, 48))))
    local = to_luma(mask)
    assert local.size == (64, 48) and local.getbbox() == native_box
    assert all(local.getpixel((x, y)) == (255 if native_box[0] <= x < native_box[2] and native_box[1] <= y < native_box[3] else 0)
               for y in range(48) for x in range(64))


def test_native_mask_intersects_original_alpha_and_rejects_transparent_only_selection():
    layer = Layer(id="base", kind=LayerKind.IMAGE, name="原图", width=64, height=48, asset_id=uuid.UUID(int=1))
    original = pattern()
    ImageDraw.Draw(original).rectangle((32, 24, 47, 47), fill=(20, 30, 40, 0))
    local = to_luma(mask_for_layer(region((64, 48), (16, 12, 32, 24)), layer, (64, 48), png(original)))
    assert local.size == (128, 96) and local.getbbox() == (48, 24, 64, 48)
    with pytest.raises(EmptyCut):
        mask_for_layer(region((64, 48), (16, 12, 24, 24)), layer, (64, 48), png(original))


def test_native_alpha_target_hit_skips_transparent_top_layer():
    top_id, bottom_id = uuid.UUID(int=1), uuid.UUID(int=2)
    top = pattern()
    ImageDraw.Draw(top).rectangle((32, 24, 63, 47), fill=(20, 30, 40, 0))
    layers = [Layer(id=name, kind=LayerKind.IMAGE, name=name, width=64, height=48, asset_id=key)
              for name, key in [("bottom", bottom_id), ("top", top_id)]]
    found = layer_under_mask(LayerDocument(width=64, height=48, layers=layers),
                            region((64, 48), (16, 12, 32, 24)), {top_id: png(top), bottom_id: png(pattern())})
    assert found.id == "bottom"


def test_canvas_inverse_can_explicitly_produce_native_grid():
    layer = Layer(id="background", kind=LayerKind.IMAGE, name="背景", width=64, height=48,
                  transform=Transform(x=8, y=6), asset_id=uuid.UUID(int=1))
    world = Image.new("RGBA", (80, 60), (10, 20, 30, 255))
    ImageDraw.Draw(world).rectangle((24, 18, 39, 29), fill=(190, 50, 60, 255))
    native = canvas_to_local(png(world), layer, output_size=(128, 96))
    logical = canvas_to_local(png(world), layer)
    assert logical.size == (64, 48) and native.size == (128, 96)
    assert native.getpixel((48, 36)) == (190, 50, 60, 255) and native.getpixel((8, 8)) == (10, 20, 30, 255)


@pytest.mark.asyncio
@pytest.mark.parametrize("tool,params", [("erase_region", {}), ("replace_region", {"prompt": "换成蓝色"})])
async def test_region_passes_native_source_and_protects_native_pixels_outside_mask(monkeypatch, tool, params):
    original = pattern()
    key, other_key = uuid.UUID(int=1), uuid.UUID(int=2)
    layers = [Layer(id="other", kind=LayerKind.IMAGE, name="其他", width=64, height=48, asset_id=other_key),
              Layer(id="subject", kind=LayerKind.IMAGE, name="目标", width=64, height=48, asset_id=key)]
    document = LayerDocument(width=64, height=48, layers=layers)
    calls = []
    class ProviderStub:
        async def edit(self, edit_request, progress):
            calls.append(Image.open(io.BytesIO(edit_request.image)).size)
            return [png(Image.new("RGBA", (64, 48), (255, 255, 255, 255)))]
    monkeypatch.setattr(engine, "get_image_provider", lambda: ProviderStub())
    output = await engine.compute(request(tool, document, {key: png(original), other_key: png(pattern())},
                                          {"layer_id": "subject", **params}, region((64, 48), (16, 12, 32, 24))))
    next_doc = LayerDocument.model_validate(output["result"]["document"])
    result = artifact_images(output)[next_doc.layers[1].asset_id]
    assert calls == [(128, 96)] and result.size == (128, 96)
    assert next_doc.layers[0] == layers[0] and next_doc.layers[1].width == 64 and next_doc.layers[1].height == 48
    assert result.getpixel((48, 36)) != original.getpixel((48, 36))
    assert all(result.getpixel((x, y)) == original.getpixel((x, y))
               for y in range(96) for x in range(128) if not (32 <= x < 64 and 24 <= y < 48))
    assert output["result"]["selection_clear"] is True


@pytest.mark.asyncio
async def test_matting_adapter_receives_original_native_grid_without_resizing_document(monkeypatch):
    key = uuid.UUID(int=1)
    layer = Layer(id="base", kind=LayerKind.IMAGE, name="原图", width=64, height=48, asset_id=key)
    calls = []
    def matting_stub(data):
        image = Image.open(io.BytesIO(data)).convert("RGBA")
        calls.append(image.size)
        image.putalpha(123)
        return png(image)
    monkeypatch.setattr(engine, "remove_background", matting_stub)
    output = await engine.compute(request("remove_background", LayerDocument(width=64, height=48, layers=[layer]), {key: png(pattern())}))
    result = next(iter(artifact_images(output).values()))
    assert calls == [(128, 96)] and result.size == (128, 96) and result.getchannel("A").getextrema() == (123, 123)
    next_layer = LayerDocument.model_validate(output["result"]["document"]).layers[0]
    assert next_layer.model_dump(exclude={"asset_id"}) == layer.model_dump(exclude={"asset_id"})


@pytest.mark.asyncio
async def test_promote_split_layers_preserves_native_background_subject_and_unaffected_layer(monkeypatch):
    bg_id, subject_id, other_id = uuid.UUID(int=1), uuid.UUID(int=2), uuid.UUID(int=3)
    background, subject = pattern(), pattern((96, 72))
    layers = [Layer(id="background", kind=LayerKind.IMAGE, name="背景", width=64, height=48, asset_id=bg_id),
              Layer(id="subject", kind=LayerKind.IMAGE, name="主体", width=48, height=36, asset_id=subject_id, transform=Transform(x=8, y=6)),
              Layer(id="outside", kind=LayerKind.IMAGE, name="其他", width=8, height=8, asset_id=other_id)]
    document = LayerDocument(width=64, height=48, layers=layers)
    calls = []
    class ProviderStub:
        async def edit(self, edit_request, progress):
            calls.append(Image.open(io.BytesIO(edit_request.image)).size)
            return [png(Image.new("RGBA", (64, 48), (110, 30, 200, 255)))]
    monkeypatch.setattr(engine, "get_image_provider", lambda: ProviderStub())
    output = await engine.compute(request("promote_object_to_layer", document,
                                          {bg_id: png(background), subject_id: png(subject), other_id: png(pattern((16, 16)))},
                                          {"name": "独立物体"}, region((64, 48), (20, 16, 28, 24))))
    next_doc = LayerDocument.model_validate(output["result"]["document"])
    images = artifact_images(output)
    bg, punched = images[next_doc.layers[0].asset_id], images[next_doc.layers[1].asset_id]
    assert calls == [(64, 48)] and bg.size == (128, 96) and punched.size == (96, 72)
    assert next_doc.layers[2] == layers[2]
    for old, new in zip(layers[:2], next_doc.layers[:2]):
        assert old.model_dump(exclude={"asset_id"}) == new.model_dump(exclude={"asset_id"})
    assert all(bg.getpixel((x, y)) == background.getpixel((x, y))
               for y in range(96) for x in range(128) if not (40 <= x < 56 and 32 <= y < 48))
    assert all(punched.getpixel((x, y)) == subject.getpixel((x, y))
               for y in range(72) for x in range(96) if not (24 <= x < 40 and 20 <= y < 36))
    assert punched.getpixel((30, 26))[3] == 0
    assert next_doc.layers[-1].name == "独立物体" and images[next_doc.layers[-1].asset_id].size == (8, 8)
    assert next_doc.layers[-1].transform.x == 20 and next_doc.layers[-1].transform.y == 16
