"""原选区接口 points 优先；混合字段不能扩大实际局部作用区。"""
import base64
import io

import pytest
from PIL import Image

from aipicture_runtime.edits.mask import overlay_png
from aipicture_runtime.engine import compute
from test_worker import request


@pytest.mark.asyncio
@pytest.mark.parametrize("append", [False, True])
async def test_mixed_selection_keeps_original_points_priority(monkeypatch, append):
    # 独立固定SAM产物，矩形采用Pillow半开坐标；远处笔刷不能被施加。
    expected = Image.new("L", (64, 80), 0)
    expected.paste(255, (9, 11, 16, 20))
    calls = []

    def fixed_segmentation(_source, coords):
        calls.append(coords)
        return overlay_png(expected)

    monkeypatch.setattr("aipicture_runtime.engine.segment_points", fixed_segmentation)
    payload = request("selection", {
        "points": [{"x": .2, "y": .2}],
        "strokes": [[{"x": .7, "y": .8}, {"x": .85, "y": .8}]],
        "selection_markers": [{"index": 1, "x": .35, "y": .25}],
        "append": append, "revision": 0,
    })
    payload.input_revision = 0
    output = await compute(payload)
    alpha = Image.open(io.BytesIO(base64.b64decode(output["artifacts"][0]["content_base64"]))).getchannel("A")
    assert alpha.getpixel((50, 64)) == 0, "原points分支忽略strokes，远处笔刷不能扩大mask"
    assert alpha.tobytes() == expected.tobytes()
    expected_coords = [(.35, .25), (.2, .2)] if append else [(.2, .2)]
    assert calls == [expected_coords]
    assert output["result"]["markers"] == [
        {"index": index + 1, "x": x, "y": y} for index, (x, y) in enumerate(expected_coords)
    ]
    assert output["result"]["revision"] == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("invalid_stroke", [
    [{"x": 1.001, "y": .8}],
    [[.7]],
])
async def test_ignored_strokes_still_validate_all_input_before_segmentation(monkeypatch, invalid_stroke):
    calls = []
    monkeypatch.setattr("aipicture_runtime.engine.segment_points", lambda *_args: calls.append(True))
    payload = request("selection", {"points": [{"x": .2, "y": .2}], "strokes": [invalid_stroke]})
    with pytest.raises(ValueError, match="归一化坐标"):
        await compute(payload)
    assert not calls
