"""原点选追加、连续涂抹和撤销重放在冻结 DTO 中必须等价。"""
import base64
import copy
import io

import pytest
from PIL import Image, ImageDraw

from aipicture_runtime.dto import AssetRef
from aipicture_runtime.edits.mask import overlay_png, to_luma
from aipicture_runtime.engine import compute
from test_worker import request


def mask_of(output):
    return to_luma(base64.b64decode(output["artifacts"][0]["content_base64"]))


def follow(previous, output, params):
    next_request = previous.model_copy(deep=True)
    artifact = output["artifacts"][0]
    next_request.assets.append(AssetRef.model_validate({key: artifact[key] for key in
        ("id", "width", "height", "format", "sha256", "content_base64")}))
    next_request.params = {**params, "mask_asset_id": artifact["id"],
        "selection_markers": output["result"]["markers"], "revision": next_request.input_revision}
    return next_request


@pytest.mark.asyncio
async def test_joint_points_then_two_strokes_and_replay(monkeypatch):
    calls = []

    def segment(_source, coords):
        calls.append(copy.deepcopy(coords))
        mask = Image.new("L", (64, 80), 0)
        draw = ImageDraw.Draw(mask)
        for x, y in coords:
            cx, cy = round(x * 64), round(y * 80)
            draw.rectangle((cx - 2, cy - 2, cx + 2, cy + 2), fill=255)
        return overlay_png(mask)

    monkeypatch.setattr("aipicture_runtime.engine.segment_points", segment)
    point1, point2 = {"x": 0.2, "y": 0.2}, {"x": 0.4, "y": 0.2}
    stroke1 = [{"x": 0.2, "y": 0.5}, {"x": 0.4, "y": 0.5}]
    stroke2 = [{"x": 0.6, "y": 0.7}, {"x": 0.8, "y": 0.7}]
    initial = request("selection", {"points": [point1], "append": False})
    first = await compute(initial)
    second_request = follow(initial, first, {"points": [point2], "append": True})
    second = await compute(second_request)
    assert calls[-1] == [(0.2, 0.2), (0.4, 0.2)]
    assert [m["index"] for m in second["result"]["markers"]] == [1, 2]
    third_request = follow(second_request, second, {"strokes": [stroke1], "radius": 0.025})
    third = await compute(third_request)
    fourth_request = follow(third_request, third, {"strokes": [stroke2], "radius": 0.025})
    fourth = await compute(fourth_request)
    final = mask_of(fourth)
    assert final.getpixel((13, 16)) == 255  # 点选保留
    assert final.getpixel((20, 40)) == 255  # 第一笔保留
    assert final.getpixel((45, 56)) == 255  # 第二笔加入
    assert fourth["result"]["markers"] == second["result"]["markers"]

    # 对应 React undo/redo：清空后整体重放正点，再一次提交全部笔画。
    replay_request = request("selection", {"points": [point1, point2], "append": False})
    replay_points = await compute(replay_request)
    undone = await compute(follow(replay_request, replay_points, {"strokes": [stroke1], "radius": 0.025}))
    assert mask_of(undone).getpixel((45, 56)) == 0
    redone = await compute(follow(replay_request, replay_points,
        {"strokes": [stroke1, stroke2], "radius": 0.025}))
    assert mask_of(redone).tobytes() == final.tobytes()


@pytest.mark.asyncio
async def test_point_replace_ignores_old_markers_and_mask(monkeypatch):
    calls = []
    monkeypatch.setattr("aipicture_runtime.engine.segment_points",
        lambda source, coords: calls.append(coords) or overlay_png(Image.new("L", (64, 80), 255)))
    payload = request("selection", {"points": [{"x": 0.8, "y": 0.8}], "append": False,
        "selection_markers": [{"index": 1, "x": 0.2, "y": 0.2}]})
    output = await compute(payload)
    assert calls == [[(0.8, 0.8)]]
    assert output["result"]["markers"] == [{"index": 1, "x": 0.8, "y": 0.8}]


@pytest.mark.asyncio
async def test_private_inspection_accepts_actual_small_webp_layer_only_explicitly():
    data = io.BytesIO()
    Image.new("RGBA", (8, 10), (220, 50, 20, 255)).save(data, "WEBP", lossless=True)
    content = base64.b64encode(data.getvalue()).decode()
    default = request("inspect_image", {"content_base64": content})
    with pytest.raises(ValueError, match="32"):
        await compute(default)
    default.params["minimum_side"] = 1
    output = await compute(default)
    assert output["result"]["width"] == 8 and output["result"]["height"] == 10
    assert output["result"]["format"] == "WEBP"
    for invalid in (True, 0, 2, 64, "1"):
        default.params["minimum_side"] = invalid
        with pytest.raises(ValueError, match="1/32"):
            await compute(default)
