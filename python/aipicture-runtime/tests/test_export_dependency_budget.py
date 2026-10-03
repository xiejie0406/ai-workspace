from __future__ import annotations

import base64
import io
import uuid
import zipfile

from PIL import Image
from pydantic import ValidationError
import pytest

from aipicture_runtime.dto import ComputeRequest
from aipicture_runtime.engine import compute
from test_worker import request


def frozen_export(selected=200, dependencies=64):
    payload = request("export").model_dump(mode="json")
    original = payload["assets"][0]
    ids = [str(uuid.uuid5(uuid.NAMESPACE_URL, f"export-budget-{index}")) for index in range(selected + dependencies)]
    payload["assets"] = [{**original, "id": identifier} for identifier in ids]
    current = ids[0]
    base = payload["document"]["layers"][0]
    payload["document"]["layers"] = [{**base, "id": f"layer-{index}", "asset_id": identifier, "opacity": 0}
        for index, identifier in enumerate([current, *ids[selected:]])]
    payload["params"] = {"format": "zip", "current_asset_id": current, "asset_ids": ids[:selected]}
    return payload


@pytest.mark.asyncio
async def test_200_selected_outputs_can_use_64_necessary_document_dependencies():
    payload = ComputeRequest.model_validate(frozen_export())
    result = await compute(payload)
    with zipfile.ZipFile(io.BytesIO(base64.b64decode(result["artifacts"][0]["content_base64"]))) as archive:
        files = [name for name in archive.namelist() if name.startswith("images/")]
        assert len(files) == 200
        rendered = Image.open(io.BytesIO(archive.read(files[0]))).convert("RGBA")
        assert rendered.getpixel((32, 40)) == (255, 255, 255, 255)
        assert archive.read(files[1]) == base64.b64decode(payload.assets[1].content_base64)


@pytest.mark.parametrize("invalid", ["unneeded-ref", "missing-ref", "too-many-selected", "duplicate-selected", "too-many-document-dependencies"])
def test_export_extra_budget_cannot_expand_arbitrary_inputs(invalid):
    payload = frozen_export(selected=1, dependencies=0)
    if invalid == "unneeded-ref":
        payload["document"] = None
        payload["params"].pop("current_asset_id")
        payload["assets"].append({**payload["assets"][0], "id": str(uuid.uuid4())})
    elif invalid == "missing-ref":
        payload["assets"] = []
    elif invalid == "too-many-selected":
        payload["params"]["asset_ids"] *= 201
    elif invalid == "duplicate-selected":
        payload["params"]["asset_ids"] *= 2
    else:
        payload = frozen_export(selected=1, dependencies=65)
    with pytest.raises(ValidationError):
        ComputeRequest.model_validate(payload)


@pytest.mark.asyncio
@pytest.mark.parametrize("format,selected", [("png", False), ("zip", False), ("zip", True)])
async def test_export_loads_only_assets_needed_by_its_output(format, selected):
    payload = frozen_export(selected=1, dependencies=1)
    current, dependency = payload["assets"]
    payload["document"]["layers"] = [payload["document"]["layers"][1]]
    payload["params"]["format"] = format
    if not selected:
        payload["params"]["asset_ids"] = []
        payload["assets"] = [dependency]
    # 即使 current 不在正式文档图层中，所选 current 仍合成该文档。
    result = await compute(ComputeRequest.model_validate(payload))
    raw = base64.b64decode(result["artifacts"][0]["content_base64"])
    if format == "zip":
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            raw = archive.read(next(name for name in archive.namelist() if name.startswith("images/")))
    assert Image.open(io.BytesIO(raw)).getpixel((32, 40)) == (255, 255, 255, 255)


@pytest.mark.asyncio
async def test_compute_rechecks_mutated_frozen_export_before_reading(monkeypatch):
    payload = ComputeRequest.model_validate(frozen_export(selected=1, dependencies=0))
    payload.assets.append(payload.assets[0].model_copy(update={"id": uuid.uuid4()}))
    async def forbidden_read(*args):
        pytest.fail("拒绝无关引用之前不能读取素材")
    monkeypatch.setattr("aipicture_runtime.engine.read_asset", forbidden_read)
    with pytest.raises(ValueError, match="200"):
        await compute(payload)


def test_unique_worker_contract_exports_conditional_reference_limits():
    from pathlib import Path
    import yaml
    worker = yaml.safe_load((Path(__file__).resolve().parents[3] / "contracts/aipicture/v1/worker.openapi.yaml").read_text("utf-8"))
    schema = worker["components"]["schemas"]["FrozenComputation"]
    assert schema["properties"]["assets"]["maxItems"] == 264
    assert schema["allOf"][0]["else"]["properties"]["assets"]["maxItems"] == 64
    selected = schema["allOf"][0]["then"]["properties"]["params"]["properties"]["asset_ids"]
    assert selected["maxItems"] == 200 and selected["uniqueItems"] is True
    assert worker["x-resource-limits"]["exportAssets"] == 264
    assert worker["x-resource-limits"]["exportSelected"] == 200
    assert worker["x-resource-limits"]["exportDocumentDependencies"] == 64


def test_ordinary_tool_still_rejects_65_references():
    payload = frozen_export(selected=65, dependencies=0)
    payload["tool"] = "adjust_image"
    payload["params"] = {}
    with pytest.raises(ValidationError):
        ComputeRequest.model_validate(payload)
