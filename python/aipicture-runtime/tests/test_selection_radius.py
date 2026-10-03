"""原选区API省略radius仍为3%短边；纯笔画检查不需要SAM或供应商。"""
import base64
import io

import pytest
from PIL import Image

from aipicture_runtime.config import get_settings
from aipicture_runtime.dto import ComputeRequest, SelectionRequest
from aipicture_runtime.engine import compute
from aipicture_runtime.layers import LayerDocument


def payload(radius=None):
    get_settings().offline_test = False
    get_settings().image_provider = "dashscope"
    get_settings().matting_provider = get_settings().ocr_provider = "unconfigured"
    params = {"revision": 0, "strokes": [[{"x": .25, "y": .5}, {"x": .75, "y": .5}]]}
    if radius is not None:
        params["radius"] = radius
    return ComputeRequest(run_id="pure-brush-radius", attempt=1, tool="selection", input_revision=0,
                          document=LayerDocument(width=100, height=100), params=params)


@pytest.mark.asyncio
@pytest.mark.parametrize("radius,bounds,middle_edge,end_edge", [
    (None, (23, 48, 77, 52), 255, 255),
    (.03, (23, 48, 77, 52), 255, 255),
    (.025, (24, 49, 77, 52), 0, 0),
])
async def test_original_default_and_explicit_brush_pixels(radius, bounds, middle_edge, end_edge):
    # 100×100短边，原默认.03→3px线宽/端点半径1.5；.025仍显式得到2px。
    output = await compute(payload(radius))
    alpha = Image.open(io.BytesIO(base64.b64decode(output["artifacts"][0]["content_base64"]))).getchannel("A")
    assert alpha.size == (100, 100) and alpha.getbbox() == bounds
    assert alpha.getpixel((50, 49)) == middle_edge
    assert alpha.getpixel((25, 48)) == end_edge
    assert alpha.getpixel((50, 50)) == 255 and alpha.getpixel((50, 47)) == 0
    assert output["provider"]["offline_test"] is False


def test_selection_adapter_default_matches_original_public_dto():
    assert SelectionRequest(run_id="default-radius", attempt=1).radius == .03


@pytest.mark.asyncio
@pytest.mark.parametrize("radius", [.0049, .1201])
async def test_existing_brush_radius_resource_range_is_unchanged(radius):
    with pytest.raises(ValueError, match="笔刷"):
        await compute(payload(radius))


def test_signed_private_brush_request_omitting_radius_preserves_original_pixels():
    import json
    from fastapi.testclient import TestClient
    from aipicture_runtime.main import create_app
    from aipicture_runtime.security import sign_headers
    request = payload().model_dump(mode="json")
    get_settings().enabled = True  # 独立TestClient真实模式私有签名入口的必要前提；不启动正式服务。
    body = json.dumps(request).encode()
    path = "/internal/v1/prepare-selection"
    headers = sign_headers(get_settings().service_secret, "aipicture-java", "POST", path, body)
    with TestClient(create_app()) as client:
        response = client.post(path, content=body, headers=headers)
    assert response.status_code == 200, response.text
    raw = base64.b64decode(response.json()["artifacts"][0]["content_base64"])
    alpha = Image.open(io.BytesIO(raw)).getchannel("A")
    assert alpha.getbbox() == (23, 48, 77, 52) and alpha.getpixel((50, 49)) == 255
