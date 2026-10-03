"""独立小样本验证专项评分；构造冻结快照，不是实际模型质量或业务验收。"""
import base64
import copy
import hashlib
import io
import uuid

import pytest
from PIL import Image, ImageDraw
from pydantic import ValidationError

from aipicture_runtime.dto import AssetRef, ComputeRequest
from aipicture_runtime.engine import compute


def image_bytes(size=(64, 64), color=(20, 50, 90, 255), rectangle=None):
    image = Image.new("RGBA", size, color)
    if rectangle:
        ImageDraw.Draw(image).rectangle(rectangle[0], fill=rectangle[1])
    stream = io.BytesIO()
    image.save(stream, "PNG")
    return stream.getvalue()


def asset(data):
    image = Image.open(io.BytesIO(data))
    return AssetRef(id=uuid.uuid4(), width=image.width, height=image.height, format="PNG",
        sha256=hashlib.sha256(data).hexdigest(), content_base64=base64.b64encode(data).decode())


def doc(ref, *, width=64, height=64):
    return {"width": width, "height": height, "layers": [{"id": "base", "kind": "image", "name": "底图",
        "width": width, "height": height, "asset_id": str(ref.id)}]}


def payload(suite, source, output=None, expected=None):
    tools = {"sam": "selection", "region_protection": "replace_region", "ocr": "split_layers",
             "enhancement": "upscale_image", "generation": "generate_image"}
    frozen = {"source_tool": tools[suite], "source_params": {}, "source_document": doc(source),
        "result_document": {}, "source_result": {}, "source_provider": {"image": "dashscope", "ocr": "rapidocr", "offline_test": False},
        "mask_asset_id": None, "output_asset_ids": [str(output.id)] if output else [],
        "asset_ids": [str(ref.id) for ref in [source, output] if ref]}
    item = {"id": "专项独立预期", "task": "assess_result", "suite": suite, "source_run_id": str(uuid.uuid4()),
        "assessment": frozen, "human_review": {"status": "passed", "notes": "人工独立标注样本：仅检查评分代码，不证明真实模型。"}}
    if output:
        item["output_asset_id"] = str(output.id)
    if expected:
        item["expect_asset_id"] = str(expected.id)
    request = ComputeRequest(run_id=str(uuid.uuid4()), attempt=1, tool="evaluation",
        params={"manifest": [item]}, assets=[ref for ref in [source, output, expected] if ref])
    return request, item, frozen


@pytest.fixture(autouse=True)
def never_invoke_tools_or_models(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("专项评估不得重新执行工具或模型")
    for name in ["get_image_provider", "segment_points", "detect_text", "remove_background"]:
        monkeypatch.setattr("aipicture_runtime.engine." + name, forbidden)
    monkeypatch.setattr("aipicture_runtime.eval.tasks.run_task", forbidden)


async def scored(request):
    response = await compute(request)
    assert response["artifacts"] == []
    assert len(response["result"]["dataset_hash"]) == 64
    case = response["result"]["cases"][0]
    assert case["user_acceptance"] is False
    return case


@pytest.mark.parametrize("kind", ["same", "wrong", "size", "empty", "full", "brush"])
async def test_sam_requires_real_point_snapshot_and_same_nontrivial_manual_alpha(kind):
    source = asset(image_bytes())
    mask = image_bytes(color=(0, 0, 0, 0), rectangle=((16, 16, 31, 31), (95, 152, 173, 255)))
    expected = mask if kind != "wrong" else image_bytes(color=(0, 0, 0, 0), rectangle=((32, 16, 47, 31), (0, 0, 0, 255)))
    if kind == "size":
        expected = image_bytes((32, 32), (0, 0, 0, 0), ((8, 8, 15, 15), (0, 0, 0, 255)))
    if kind in {"empty", "full"}:
        expected = image_bytes(color=(0, 0, 0, 0 if kind == "empty" else 255))
    request, item, frozen = payload("sam", source, asset(mask), asset(expected))
    frozen["source_params"] = {"points": [] if kind == "brush" else [{"x": 0.5, "y": 0.5}]}
    frozen["source_provider"]["sam"] = {"provider": "onnxruntime", "model": "sam", "loaded": True, "inference": "points"}
    result = await scored(request)
    assert result["passed"] is (kind == "same")
    if kind in {"same", "wrong"}:
        assert result["metrics"]["alpha_iou"] == (1.0 if kind == "same" else 0.0)
    else:
        assert result["error"]


@pytest.mark.parametrize("change", ["inside", "outside", "empty", "full", "wrong_size"])
async def test_region_compares_frozen_canvases_and_exact_outside_rgba(change):
    source = asset(image_bytes())
    target = asset(image_bytes(rectangle=((16, 16, 31, 31) if change != "outside" else (0, 0, 0, 0), (240, 20, 30, 255))))
    mask = asset(image_bytes(color=(0, 0, 0, 0), rectangle=((16, 16, 31, 31), (0, 0, 0, 255))))
    if change in {"empty", "full", "wrong_size"}:
        mask = asset(image_bytes((32, 32) if change == "wrong_size" else (64, 64), (0, 0, 0, 0 if change == "empty" else 255)))
    request, item, frozen = payload("region_protection", source, target)
    request.assets.append(mask)
    frozen["asset_ids"].append(str(mask.id))
    frozen["mask_asset_id"] = str(mask.id)
    frozen["result_document"] = doc(target)
    result = await scored(request)
    assert result["technical_passed"] is (change == "inside")
    if change == "inside":
        assert result["metrics"] == {"outside_mae": 0, "outside_max": 0}
        assert result["details"] == {"outside_pixels": 3840, "inside_pixels": 256, "outside_changed_pixels": 0}
    if change == "outside":
        assert result["details"]["outside_changed_pixels"] == 1
        assert result["metrics"]["outside_max"] == 220
        assert result["metrics"]["outside_mae"] == (220 + 30 + 60) / (3840 * 4)
    if change in {"empty", "full", "wrong_size"}:
        assert result["error"]


async def test_region_uses_native_image_in_full_flipped_nonuniform_rotated_document():
    source = asset(image_bytes((128, 64)))
    target = asset(image_bytes((128, 64)))
    mask = asset(image_bytes(color=(0, 0, 0, 0), rectangle=((16, 16, 31, 31), (0, 0, 0, 255))))
    request, item, frozen = payload("region_protection", source, target)
    for key, ref in [("source_document", source), ("result_document", target)]:
        document = doc(ref)
        layer = document["layers"][0]
        layer.update(width=64, height=32, transform={"x": 0, "y": 16, "scale_x": -1, "scale_y": 0.5, "rotation": 90})
        frozen[key] = document
    request.assets.append(mask)
    frozen["asset_ids"].append(str(mask.id))
    frozen["mask_asset_id"] = str(mask.id)
    result = await scored(request)
    assert result["metrics"] == {"outside_mae": 0, "outside_max": 0}
    assert result["details"]["outside_pixels"] == 3840
    assert result["passed"] is True


@pytest.mark.parametrize("kind", ["same", "wrong_text", "wrong_box", "empty"])
async def test_ocr_scores_frozen_text_and_affine_canvas_box_without_ocr_call(kind):
    source = asset(image_bytes())
    request, item, frozen = payload("ocr", source)
    frozen["source_params"] = {"include_text": True}
    frozen["result_document"] = doc(source)
    if kind != "empty":
        frozen["result_document"]["layers"].append({"id": "text", "kind": "text", "name": "识别文字", "text": "中文价格",
            "width": 20, "height": 10, "font_size": 12, "transform": {"x": 4, "y": 8, "scale_x": -1, "scale_y": 2, "rotation": 90}})
    item["expected_ocr"] = [{"text": "中文价值" if kind == "wrong_text" else "中文价格",
        "bbox": [40, 40, 20, 20] if kind == "wrong_box" else [4, 3, 20, 20]}]
    result = await scored(request)
    assert result["passed"] is (kind == "same")
    if kind != "empty":
        assert result["metrics"]["cer"] == (0.25 if kind == "wrong_text" else 0)
        assert result["metrics"]["bbox_iou"] == pytest.approx(0 if kind == "wrong_box" else 1)
        assert result["metrics"]["count_match"] == 1
    else:
        assert "没有提取" in result["error"]


@pytest.mark.parametrize("strict", [True, False])
async def test_enhancement_cannot_upgrade_image_edit_to_true_super_resolution(strict):
    source, target = asset(image_bytes()), asset(image_bytes((128, 128)))
    request, item, frozen = payload("enhancement", source, target)
    frozen["source_params"] = {"scale": 2}
    frozen["source_provider"].update(enhancement_implementation="image_edit", true_super_resolution=False)
    item["require_true_super_resolution"] = strict
    result = await scored(request)
    assert result["metrics"]["size"] == 1
    assert result["details"]["true_super_resolution"] is False
    assert result["technical_passed"] is not strict
    assert result["passed"] is not strict


@pytest.mark.parametrize("mode", ["real", "offline", "missing", "mock", "pending", "failed", "empty_notes", "bad_size"])
async def test_generation_separates_pixel_size_formal_mode_and_human_decision(mode):
    source, target = asset(image_bytes()), asset(image_bytes((1080, 1080) if mode != "bad_size" else (64, 64)))
    request, item, frozen = payload("generation", source, target)
    frozen["source_document"] = {}  # Java没有会话的正式生成没有doc，不能因空对象误拒。
    frozen["source_params"] = {"ratio": "1:1"}
    if mode == "offline":
        frozen["source_provider"]["offline_test"] = True
    if mode == "missing":
        frozen["source_provider"].pop("offline_test")
    if mode == "mock":
        frozen["source_provider"]["image"] = "mock"
    if mode in {"pending", "failed"}:
        item["human_review"]["status"] = mode
    if mode == "empty_notes":
        item["human_review"]["notes"] = "   "
    result = await scored(request)
    assert result["passed"] is (mode == "real")
    assert result["technical_passed"] is (mode not in {"empty_notes", "bad_size"})
    if mode in {"offline", "missing", "mock"}:
        assert result["real_model_verified"] is False


@pytest.mark.parametrize("amount", [65, 264])
def test_only_evaluation_can_reference_264_exact_center_frozen_dependencies(amount):
    source = asset(image_bytes())
    request, item, frozen = payload("generation", source, source.model_copy(update={"id": uuid.uuid4()}))
    request.assets = [source.model_copy(update={"id": uuid.uuid4()}) for _ in range(amount)]
    frozen["asset_ids"] = [str(ref.id) for ref in request.assets]
    frozen["output_asset_ids"] = [str(request.assets[0].id)]
    frozen["source_document"] = {}
    item["output_asset_id"] = str(request.assets[0].id)
    assert len(ComputeRequest.model_validate(request.model_dump(mode="json")).assets) == amount
    altered = request.model_dump(mode="json")
    altered["tool"] = "adjust_image"
    with pytest.raises(ValidationError, match="64"):
        ComputeRequest.model_validate(altered)
    extra = copy.deepcopy(request.model_dump(mode="json"))
    extra["params"]["manifest"][0]["assessment"]["asset_ids"] = frozen["asset_ids"][1:]
    extra["params"]["manifest"][0]["assessment"]["output_asset_ids"] = []
    extra["params"]["manifest"][0].pop("output_asset_id")
    with pytest.raises(ValidationError, match="并集"):
        ComputeRequest.model_validate(extra)


@pytest.mark.parametrize("failure", ["missing_ref", "265_refs", "101_cases"])
def test_evaluation_rejects_missing_or_excess_frozen_dependencies(failure):
    source, target = asset(image_bytes()), asset(image_bytes())
    request, item, frozen = payload("generation", source, target)
    raw = request.model_dump(mode="json")
    if failure == "missing_ref":
        raw["assets"].pop()
    if failure == "265_refs":
        raw["assets"] = [source.model_copy(update={"id": uuid.uuid4()}).model_dump(mode="json") for _ in range(265)]
    if failure == "101_cases":
        raw["params"]["manifest"] *= 101
    with pytest.raises(ValidationError):
        ComputeRequest.model_validate(raw)


@pytest.mark.parametrize("thresholds", [{"imaginary": 1}, {"size": float("nan")}, []])
async def test_metric_presence_or_invalid_threshold_cannot_make_assessment_pass(thresholds):
    source, target = asset(image_bytes()), asset(image_bytes((1080, 1080)))
    request, item, frozen = payload("generation", source, target)
    item["thresholds"] = thresholds
    result = await scored(request)
    assert result["technical_passed"] is False and result["passed"] is False and result["error"]


@pytest.mark.parametrize("boundary", ["size", "count_match", "outside_mae", "outside_max"])
async def test_fixed_invariants_cannot_be_relaxed_by_user_thresholds(boundary):
    source = asset(image_bytes())
    if boundary == "size":
        request, item, frozen = payload("generation", source, asset(image_bytes()))
        item["thresholds"] = {"size": 0}
    elif boundary == "count_match":
        request, item, frozen = payload("ocr", source)
        frozen["source_params"] = {"include_text": True}
        frozen["result_document"] = doc(source)
        frozen["result_document"]["layers"].append({"id": "text", "kind": "text", "text": "A", "name": "A", "width": 10, "height": 10})
        item["expected_ocr"] = [{"text": "A", "bbox": [0, 0, 10, 10]}, {"text": "B", "bbox": [20, 0, 10, 10]}]
        item["thresholds"] = {"count_match": 0, "cer": 10, "bbox_iou": 0}
    else:
        target = asset(image_bytes(rectangle=((0, 0, 0, 0), (21, 50, 90, 255))))
        request, item, frozen = payload("region_protection", source, target)
        mask = asset(image_bytes(color=(0, 0, 0, 0), rectangle=((16, 16, 31, 31), (0, 0, 0, 255))))
        request.assets.append(mask)
        frozen["asset_ids"].append(str(mask.id))
        frozen.update(mask_asset_id=str(mask.id), result_document=doc(target))
        item["thresholds"] = {"outside_mae": 1, "outside_max": 1} if boundary == "outside_mae" else {"outside_max": 1}
    result = await scored(request)
    assert result["error"] and "固定" in result["error"]
    assert result["technical_passed"] is False and result["overall"] is False


async def test_ocr_equal_position_text_ties_are_stable_and_report_missing_and_extra():
    source = asset(image_bytes())
    request, item, frozen = payload("ocr", source)
    frozen["source_params"] = {"include_text": True}
    frozen["result_document"] = doc(source)
    for index, text in enumerate(["A", "B"]):
        frozen["result_document"]["layers"].append({"id": f"text-{index}", "kind": "text", "name": text, "text": text, "width": 10, "height": 10})
    item["expected_ocr"] = [{"text": text, "bbox": [0, 0, 10, 10]} for text in ["A", "B"]]
    original = await scored(request)
    item["expected_ocr"].reverse()
    reordered = await scored(request)
    assert original["metrics"] == reordered["metrics"] == {"cer": 0, "bbox_iou": 1, "count_match": 1}
    assert [pair["expected"]["text"] for pair in reordered["details"]["matched_ocr"]] == ["B", "A"]
    assert all(pair["expected"]["text"] == pair["actual"]["text"] for pair in reordered["details"]["matched_ocr"])
    assert reordered["details"]["missing_ocr"] == reordered["details"]["extra_ocr"] == []
    item["expected_ocr"] = item["expected_ocr"][:1]
    extra = await scored(request)
    assert extra["metrics"]["count_match"] == 0 and extra["passed"] is False
    assert [box["text"] for box in extra["details"]["extra_ocr"]] == ["A"]
    frozen["result_document"]["layers"] = frozen["result_document"]["layers"][:2]
    item["expected_ocr"] = [{"text": text, "bbox": [0, 0, 10, 10]} for text in ["A", "B"]]
    missing = await scored(request)
    assert missing["metrics"]["count_match"] == 0 and missing["passed"] is False
    assert [box["text"] for box in missing["details"]["missing_ocr"]] == ["B"]


@pytest.mark.parametrize("x, valid", [(-100_000, True), (100_001, False)])
async def test_ocr_manual_bbox_uses_authoritative_100000_boundary(x, valid):
    source = asset(image_bytes())
    request, item, frozen = payload("ocr", source)
    frozen["source_params"] = {"include_text": True}
    frozen["result_document"] = doc(source)
    frozen["result_document"]["layers"].append({"id": "text", "kind": "text", "text": "A", "name": "A", "width": 10, "height": 10})
    item["expected_ocr"] = [{"text": "A", "bbox": [x, -10, 10, 10]}]
    result = await scored(request)
    assert (result["error"] is None) is valid


async def test_manual_expected_mask_cannot_be_source_output_itself():
    source = asset(image_bytes())
    mask = asset(image_bytes(color=(0, 0, 0, 0), rectangle=((16, 16, 31, 31), (0, 0, 0, 255))))
    request, item, frozen = payload("sam", source, mask)
    frozen["source_params"] = {"points": [{"x": 0.5, "y": 0.5}]}
    item["expect_asset_id"] = str(mask.id)
    result = await scored(request)
    assert result["error"] and "独立" in result["error"]
    assert result["passed"] is False


@pytest.mark.parametrize("evidence, offline, real", [
    (None, False, False),
    ("sam", False, False),
    ({"provider": "onnxruntime", "model": "sam", "loaded": False, "inference": "points"}, False, False),
    ({"provider": "mock", "model": "sam", "loaded": True, "inference": "points"}, False, False),
    ({"provider": "onnxruntime", "model": "sam", "loaded": True, "inference": "brush"}, False, False),
    ({"provider": "onnxruntime", "model": "sam", "loaded": True, "inference": "points"}, True, False),
    ({"provider": "onnxruntime", "model": "sam", "loaded": True, "inference": "points"}, False, True),
])
async def test_sam_formal_evidence_requires_explicit_loaded_point_model(evidence, offline, real):
    source = asset(image_bytes())
    mask = image_bytes(color=(0, 0, 0, 0), rectangle=((16, 16, 31, 31), (0, 0, 0, 255)))
    request, item, frozen = payload("sam", source, asset(mask), asset(mask))
    frozen["source_params"] = {"points": [{"x": 0.5, "y": 0.5}]}
    frozen["source_provider"]["offline_test"] = offline
    if evidence is not None:
        frozen["source_provider"]["sam"] = evidence
    result = await scored(request)
    assert result["technical_passed"] is True
    assert result["real_model_verified"] is real and result["overall"] is real


@pytest.mark.parametrize("points, loaded, offline, expected", [(True, True, False, True), (True, False, False, False),
                                                             (False, True, False, False), (True, True, True, False)])
async def test_only_successful_loaded_real_point_run_emits_sam_provenance(monkeypatch, points, loaded, offline, expected):
    from types import SimpleNamespace
    from aipicture_runtime.config import Settings
    from aipicture_runtime.edits import segment
    settings = Settings(offline_test=offline, matting_provider="corner" if offline else "unconfigured",
                        image_provider="dashscope", ocr_provider="unconfigured")
    monkeypatch.setattr("aipicture_runtime.engine.get_settings", lambda: settings)
    # 形状替身只验证回执来源门槛，未加载ONNX或实际执行SAM模型。
    monkeypatch.setattr(segment, "_sam", SimpleNamespace(encoder=object(), decoder=object()) if loaded else None)
    monkeypatch.setattr("aipicture_runtime.engine.segment_points", lambda *args: image_bytes(color=(0, 0, 0, 0),
        rectangle=((16, 16, 31, 31), (0, 0, 0, 255))))
    source = asset(image_bytes())
    request = ComputeRequest(run_id=str(uuid.uuid4()), attempt=1, tool="selection", document=doc(source), assets=[source],
        params={"points": [{"x": 0.5, "y": 0.5}] if points else [], "strokes": [] if points else [[{"x": 0.5, "y": 0.5}]]})
    response = await compute(request)
    assert ("sam" in response["provider"]) is expected
    if expected:
        assert response["provider"]["sam"] == {"provider": "onnxruntime", "model": "sam", "loaded": True, "inference": "points"}
