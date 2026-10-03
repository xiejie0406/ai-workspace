"""仅评估中心冻结的既有专项产物，不执行模型、工具或正式写入。"""
from __future__ import annotations

import hashlib
import io
import math
import re
import uuid

from PIL import Image

from aipicture_runtime.edits.render import TRANSPARENT, flatten
from aipicture_runtime.geometry import local_to_canvas
from aipicture_runtime.layers import LayerDocument, LayerKind
from aipicture_runtime.ratios import Ratio, size_of
from aipicture_runtime.eval.metrics import alpha_iou, image_size, mae, psnr

DEFAULT_THRESHOLDS = {"alpha_iou": 0.8, "outside_mae": 0.0, "outside_max": 0.0,
                      "cer": 0.0, "bbox_iou": 0.8, "count_match": 1.0, "size": 1.0,
                      "mae": 12.0, "psnr": 20.0}
_SUITES = {"sam": {"selection"}, "region_protection": {"erase_region", "replace_region", "promote_object_to_layer"},
           "ocr": {"split_layers"}, "enhancement": {"upscale_image"}, "generation": {"generate_image", "generate_marketing"}}
_LOWER = {"outside_mae", "outside_max", "cer", "mae"}


def _data(images, identifier) -> bytes:
    try:
        return images[uuid.UUID(str(identifier))]
    except (ValueError, KeyError, TypeError) as exc:
        raise ValueError("专项素材没有出现在中心冻结引用中") from exc


def _document(raw, images) -> LayerDocument:
    from aipicture_runtime.engine import validate_document
    document = LayerDocument.model_validate(raw)
    validate_document(document, images)
    return document


def _number(value, label, minimum=0, maximum=255) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not minimum <= value <= maximum:
        raise ValueError(f"{label}须为范围内有限数值")
    return float(value)


def _bbox(raw) -> tuple[float, float, float, float]:
    if not isinstance(raw, list) or len(raw) != 4:
        raise ValueError("OCR人工框须为[x,y,width,height]")
    x, y, width, height = (_number(value, "人工框", -100_000, 100_000) for value in raw)
    if width <= 0 or height <= 0:
        raise ValueError("OCR人工框宽高必须为正")
    return x, y, width, height


def _iou(left, right) -> float:
    ax, ay, aw, ah = left
    bx, by, bw, bh = right
    intersection = max(0.0, min(ax + aw, bx + bw) - max(ax, bx)) * max(0.0, min(ay + ah, by + bh) - max(ay, by))
    union = aw * ah + bw * bh - intersection
    return intersection / union if union else 0.0


def _distance(left: str, right: str) -> int:
    if left == right:
        return 0
    if not left or not right:
        return max(len(left), len(right))
    previous = list(range(len(right) + 1))
    for index, a in enumerate(left, 1):
        current = [index]
        for column, b in enumerate(right, 1):
            current.append(min(current[-1] + 1, previous[column] + 1, previous[column - 1] + (a != b)))
        previous = current
    return previous[-1]


def _ocr(document, expected):
    if not isinstance(expected, list) or not 1 <= len(expected) <= 128:
        raise ValueError("OCR须有1–128个人工独立文本/框")
    references = []
    for box in expected:
        text = box.get("text") if isinstance(box, dict) else None
        if not isinstance(text, str) or not text.strip() or len(text) > 2000:
            raise ValueError("OCR人工文本须为非空且最多2000字")
        references.append({"text": text, "bbox": _bbox(box.get("bbox"))})
    if sum(len(box["text"]) for box in references) > 32_000:
        raise ValueError("OCR人工文本总字数超过32000")
    predicted = []
    for layer in document.layers:
        if layer.kind is not LayerKind.TEXT or not layer.visible or layer.opacity <= 0:
            continue
        text = layer.text if layer.text is not None else layer.name
        if not text or not text.strip():
            continue
        if len(text) > 500:
            raise ValueError("OCR结果文字超过500字限制")
        a, b, c, d, e, f = local_to_canvas(layer)
        points = [(a * x + b * y + c, d * x + e * y + f) for x, y in
                  [(0, 0), (layer.width, 0), (0, layer.height), (layer.width, layer.height)]]
        xs, ys = [point[0] for point in points], [point[1] for point in points]
        predicted.append({"text": text, "bbox": (min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys))})
    if not predicted:
        raise ValueError("OCR没有提取非空文字层，不能以空结果通过")
    # 位置优先；同IoU先配完全相同文字，再按文字/框稳定排序，不依赖人工清单顺序。
    # 仅最终配对计算编辑距离，避免对每对最大文本运行昂贵的动态规划。
    ranked = sorted((-_iou(reference["bbox"], pred["bbox"]), reference["text"] != pred["text"],
                     reference["text"], reference["bbox"], pred["text"], pred["bbox"], i, j)
                    for i, reference in enumerate(references) for j, pred in enumerate(predicted))
    matched, used = {}, set()
    for negative, *_, i, j in ranked:
        if i not in matched and j not in used:
            matched[i] = (j, -negative)
            used.add(j)
    errors, overlaps = 0, 0.0
    for i, reference in enumerate(references):
        pair = matched.get(i)
        errors += _distance(reference["text"], predicted[pair[0]]["text"] if pair else "")
        overlaps += pair[1] if pair else 0.0
    errors += sum(len(pred["text"]) for j, pred in enumerate(predicted) if j not in used)
    return {"cer": errors / sum(len(box["text"]) for box in references),
            "bbox_iou": overlaps / max(len(references), len(predicted)),
            "count_match": float(len(references) == len(predicted))}, {
                "predicted_ocr": predicted,
                "matched_ocr": [{"expected_index": i, "actual_index": matched[i][0],
                    "expected": reference, "actual": predicted[matched[i][0]], "bbox_iou": matched[i][1]}
                    for i, reference in enumerate(references) if i in matched],
                "missing_ocr": [reference for i, reference in enumerate(references) if i not in matched],
                "extra_ocr": [pred for j, pred in enumerate(predicted) if j not in used]}


def _outside(source, result, mask):
    before, after = Image.open(io.BytesIO(source)).convert("RGBA"), Image.open(io.BytesIO(result)).convert("RGBA")
    alpha = Image.open(io.BytesIO(mask)).convert("RGBA").getchannel("A")
    if before.size != after.size or before.size != alpha.size:
        raise ValueError("区域保护须在相同冻结画布/遮罩尺寸上比较，不能自动缩放")
    outside, inside, changed, total, largest = 0, 0, 0, 0, 0
    # 按行读取，避免按5000万像素建立Python对象列表或缓存巨型差分位图。
    for y in range(before.height):
        a = before.crop((0, y, before.width, y + 1)).tobytes()
        b = after.crop((0, y, after.width, y + 1)).tobytes()
        m = alpha.crop((0, y, alpha.width, y + 1)).tobytes()
        for x, selected in enumerate(m):
            if selected:
                inside += 1
                continue
            outside += 1
            offset = x * 4
            differences = [abs(a[offset + channel] - b[offset + channel]) for channel in range(4)]
            total += sum(differences)
            peak = max(differences)
            largest = max(largest, peak)
            changed += peak > 0
    if not inside or not outside:
        raise ValueError("区域保护遮罩为空或覆盖整幅，缺少可独立核对的内外像素")
    return {"outside_mae": total / (outside * 4), "outside_max": float(largest)}, {
        "outside_pixels": outside, "inside_pixels": inside, "outside_changed_pixels": changed}


def assess_result(raw: dict, images: dict[uuid.UUID, bytes]) -> dict:
    """Java鉴权/冻结后评估：技术、人审、正式模式证据与整体判定分别返回。"""
    result = {"id": raw.get("id"), "task": "assess_result", "suite": raw.get("suite"),
              "source_run_id": raw.get("source_run_id"), "outputs": [], "metrics": {}, "error": None,
              "technical_passed": False, "human_review": {"status": "pending", "notes": ""},
              "real_model_verified": False, "overall": False, "passed": False, "reasons": [],
              "provenance": {}, "user_acceptance": False}
    try:
        uuid.UUID(str(raw["source_run_id"]))
        suite = raw["suite"]
        frozen = raw.get("assessment")
        if suite not in _SUITES or not isinstance(frozen, dict) or frozen.get("source_tool") not in _SUITES[suite]:
            raise ValueError("专项类型与中心冻结的原工具不匹配")
        params = frozen.get("source_params") or {}
        provider = frozen.get("source_provider") or {}
        review = raw.get("human_review") or {"status": "pending", "notes": ""}
        if not isinstance(review, dict) or review.get("status") not in {"pending", "passed", "failed"}:
            raise ValueError("人工判定状态无效")
        notes = review.get("notes", "")
        if not isinstance(notes, str) or len(notes) > 2000 or (review["status"] == "passed" and not notes.strip()):
            raise ValueError("人工通过必须给出独立说明，最多2000字")
        result["human_review"] = {"status": review["status"], "notes": notes}
        source_refs = frozen.get("asset_ids") or []
        result["provenance"] = {"source_tool": frozen["source_tool"], "source_provider": provider,
            "asset_sha256": {str(identifier): hashlib.sha256(_data(images, identifier)).hexdigest() for identifier in source_refs}}
        if raw.get("expect_asset_id") and uuid.UUID(str(raw["expect_asset_id"])) in {
                uuid.UUID(str(identifier)) for identifier in frozen.get("output_asset_ids", [])}:
            raise ValueError("人工参考必须独立于原任务实际输出，不能用产物自比较")
        expect = _data(images, raw["expect_asset_id"]) if raw.get("expect_asset_id") else None
        metrics, details = {}, {}
        if suite == "sam":
            if not params.get("points"):
                raise ValueError("SAM评估必须来自正式点选，不能把纯笔刷结果当SAM")
            identifier = raw.get("output_asset_id") or frozen.get("mask_asset_id")
            if str(identifier) not in [str(value) for value in frozen.get("output_asset_ids", [])]:
                raise ValueError("SAM输出不是原任务实际产物")
            output = _data(images, identifier)
            if expect is None or image_size(output) != image_size(expect):
                raise ValueError("SAM须有相同尺寸的人工alpha遮罩，不能自动缩放")
            for data in (output, expect):
                alpha = Image.open(io.BytesIO(data)).convert("RGBA").getchannel("A")
                selected = sum(alpha.histogram()[129:])
                if selected in {0, alpha.width * alpha.height}:
                    raise ValueError("SAM实际/人工alpha遮罩为空或覆盖整幅，不能独立判定分割")
            metrics["alpha_iou"] = alpha_iou(output, expect)
        elif suite == "region_protection":
            source_doc = _document(frozen.get("source_document"), images)
            result_doc = _document(frozen.get("result_document"), images)
            if (source_doc.width, source_doc.height) != (result_doc.width, result_doc.height):
                raise ValueError("区域保护原/结果画幅不同，无法按同坐标比较")
            rendered = flatten(result_doc, images, background=TRANSPARENT)
            metrics, details = _outside(flatten(source_doc, images, background=TRANSPARENT),
                rendered, _data(images, frozen.get("mask_asset_id")))
            if expect is not None:
                if image_size(expect) != (result_doc.width, result_doc.height):
                    raise ValueError("区域像素参考图必须与冻结画布同尺寸")
                metrics.update(mae=mae(rendered, expect), psnr=psnr(rendered, expect))
        elif suite == "ocr":
            if expect is not None:
                raise ValueError("OCR只接受人工文本/框，不以图像参考冒充文字标注")
            if params.get("include_text") is not True:
                raise ValueError("OCR源任务没有启用文字提取")
            metrics, details = _ocr(_document(frozen.get("result_document"), images), raw.get("expected_ocr"))
        else:
            identifier = raw.get("output_asset_id")
            if str(identifier) not in [str(value) for value in frozen.get("output_asset_ids", [])]:
                raise ValueError("须选择原任务实际输出素材")
            output = _data(images, identifier)
            if suite == "enhancement":
                document = _document(frozen.get("source_document"), images)
                scale = params.get("scale")
                if type(scale) is not int or not 2 <= scale <= 4:
                    raise ValueError("增强缺少原任务合法倍率")
                expected_size = (document.width * scale, document.height * scale)
                strict = raw.get("require_true_super_resolution", True)
                if type(strict) is not bool:
                    raise ValueError("严格超分标志必须为布尔值")
                details["strict_super_resolution"] = strict
                details["true_super_resolution"] = provider.get("true_super_resolution") is True
            else:
                expected_size = size_of(Ratio(params.get("ratio", "1:1")))
            actual = image_size(output)
            metrics["size"] = float(actual == expected_size)
            details.update(expected_size=list(expected_size), actual_size=list(actual))
            if expect is not None:
                if image_size(expect) != actual:
                    raise ValueError("像素参考图尺寸与实际产物不同，不能自动缩放")
                metrics.update(mae=mae(output, expect), psnr=psnr(output, expect))
        thresholds = raw.get("thresholds") if raw.get("thresholds") is not None else {}
        if not isinstance(thresholds, dict) or any(key not in metrics for key in thresholds):
            raise ValueError("阈值必须对应此专项实际指标")
        checked = {name: _number(thresholds.get(name, DEFAULT_THRESHOLDS[name]), "指标阈值", 0,
            1 if name in {"alpha_iou", "bbox_iou", "count_match", "size"} else 10 if name == "cer" else 255) for name in metrics}
        for name in {"size", "count_match"} & metrics.keys():
            if checked[name] != 1:
                raise ValueError(f"{name}固定阈值为1，不能放宽完全一致条件")
        if suite == "region_protection" and any(checked[name] != 0 for name in ("outside_mae", "outside_max")):
            raise ValueError("区域保护outside_mae/outside_max固定阈值为0，不允许遮罩外变化")
        passed = all(value <= checked[name] if name in _LOWER else value >= checked[name] for name, value in metrics.items())
        passed = passed and all(metrics[name] == 1 for name in {"size", "count_match"} & metrics.keys())
        if suite == "region_protection":
            passed = passed and details["outside_changed_pixels"] == 0
        if suite == "enhancement" and details["strict_super_resolution"] and not details["true_super_resolution"]:
            passed = False
            result["reasons"].append("源供应商未声明真实超分，不能以image_edit/插值输出通过严格超分")
        if suite == "sam":
            source = provider.get("sam")
            model_verified = isinstance(source, dict) and source.get("provider") == "onnxruntime" and source.get("model") == "sam" and source.get("loaded") is True and source.get("inference") == "points"
        elif suite == "enhancement":
            source = provider.get("enhancement")
            model_verified = isinstance(source, dict) and source.get("provider") == "onnxruntime" and source.get("loaded") is True and source.get("inference") == "rgb_nchw" and source.get("scale") == params.get("scale") and bool(re.fullmatch(r"[0-9a-f]{64}", str(source.get("model_sha256", ""))))
            if not details["strict_super_resolution"] and provider.get("enhancement_implementation") == "image_edit":
                model_verified = provider.get("image") not in {None, "", "none", "mock", "unconfigured"}
        else:
            model = provider.get("ocr") if suite == "ocr" else provider.get("image")
            model_verified = isinstance(model, str) and model not in {"", "none", "mock", "unconfigured", "corner"}
        result["real_model_verified"] = provider.get("offline_test") is False and model_verified
        result.update(metrics=metrics, thresholds=checked, details=details, technical_passed=passed)
        if not passed:
            result["reasons"].append("专项技术指标或能力条件未满足")
        if not result["real_model_verified"]:
            result["reasons"].append("原产物缺少正式非测试模型配置证据")
        if review["status"] != "passed":
            result["reasons"].append("人工内容/质量尚未通过")
        result["overall"] = result["passed"] = passed and result["real_model_verified"] and review["status"] == "passed"
    except Exception as exc:
        result["error"] = str(exc)
        result["reasons"].append(str(exc))
    return result
