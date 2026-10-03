"""一次当前私有HTTP真实SAM点选；无正式登记、付费调用、重试或其他模型推理。"""
from datetime import UTC, datetime
import base64
import hashlib
import hmac
import io
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
import uuid

from PIL import Image


directory = Path(__file__).resolve().parent
root = directory.parents[2]
private = root / ".runtime/aipicture"
source_path = root / "参考项目/ai-picture-editor-main/backend/app/eval/dataset/images/product-white.png"
expect_path = root / "参考项目/ai-picture-editor-main/backend/app/eval/dataset/expect/product-alpha.png"
historical_path = private / "vision/http-check/sam-foreground-http.png"
source_sha = "bb4d28230e31246825bb45abb7b142c3b5ac20c4742a6f0e30f8bab95421de06"
expect_sha = "cd5ac47dba40d9a22905d99364e4be39f50a30fb8c614ee007458d8e321d30fd"
historical_sha = "1fef10592468a044e7c9c4d43ca78c737999fe5f784170246aacbefd13fd1a38"
historical_iou = 0.9639788889731981
points = [(0.30, 0.40), (0.65, 0.45)]
expected_size = (1080, 1080)
alpha_threshold = 128
iou_threshold = 0.8
calls = []
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def now():
    return datetime.now(UTC).isoformat()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save(name, value):
    with (directory / name).open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def request(method, path, payload=None, *, timeout=30):
    body = b"" if payload is None else json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    timestamp, nonce = str(int(time.time())), uuid.uuid4().hex
    canonical = "\n".join([method, path, timestamp, nonce, sha(body)]).encode("utf-8")
    headers = {"X-Aipicture-Service": "aipicture-java", "X-Aipicture-Timestamp": timestamp,
               "X-Aipicture-Nonce": nonce,
               "X-Aipicture-Signature": hmac.new(secret.encode(), canonical, hashlib.sha256).hexdigest()}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    entry = {"method": method, "path": path, "started_at": now(), "timeout_seconds": timeout,
             "request_bytes": len(body), "request_sha256": sha(body)}
    calls.append(entry)
    started = time.perf_counter()
    try:
        with opener.open(urllib.request.Request("http://127.0.0.1:7405" + path,
                                             data=body if payload is not None else None,
                                             headers=headers, method=method), timeout=timeout) as response:
            data = response.read()
            entry.update(status=response.status, response_sha256=sha(data), response_bytes=len(data))
            return json.loads(data)
    except urllib.error.HTTPError as exc:
        data = exc.read()
        entry.update(status=exc.code, response_sha256=sha(data), response_bytes=len(data))
        save(f"http-error-{len(calls)}.json", {"method": method, "path": path, "status": exc.code,
                                 "body": data.decode("utf-8", errors="replace")})
        raise
    finally:
        entry["seconds"] = time.perf_counter() - started


for name in ["expected.json", "capabilities-before.json", "capabilities-after.json", "sam-mask.png", "result.json"]:
    if (directory / name).exists():
        raise RuntimeError("已有单次证据，禁止覆盖或重复SAM推理")

source = source_path.read_bytes()
expect_raw = expect_path.read_bytes()
assert sha(source) == source_sha and sha(expect_raw) == expect_sha
source_image = Image.open(io.BytesIO(source))
expect_image = Image.open(io.BytesIO(expect_raw))
assert source_image.size == expect_image.size == expected_size
assert "A" in expect_image.getbands()
expect_alpha = expect_image.getchannel("A")
pixel_points = [(int(x * expected_size[0]), int(y * expected_size[1])) for x, y in points]
expect_point_alpha = [expect_alpha.getpixel(point) for point in pixel_points]
assert all(value >= alpha_threshold for value in expect_point_alpha)
assert expect_alpha.getpixel((0, 0)) < alpha_threshold
expect_pixels = expect_alpha.tobytes()
assert file_sha(historical_path) == historical_sha

model_paths = {}
for line in (private / "vision/runtime.env").read_text(encoding="utf-8-sig").splitlines():
    key, separator, value = line.partition("=")
    if separator and key in {"AIPICTURE_SAM_ENCODER_PATH", "AIPICTURE_SAM_DECODER_PATH"}:
        path = Path(value.strip().strip("\"'"))
        assert path.is_file()
        model_paths[key] = {"path": str(path), "sha256": file_sha(path), "size_bytes": path.stat().st_size}
assert len(model_paths) == 2
source_hash = file_sha(root / "python/aipicture-runtime/aipicture_runtime/edits/segment.py")
assert source_hash == "13db45720cdadc9f84e04b32ea5af072fe4e00ce0aa87f915c1412fc3a9c09cf"
save("expected.json", {"saved_before_http": True, "saved_at": now(),
    "source": {"path": str(source_path), "sha256": source_sha, "size_bytes": len(source)},
    "independent_expect": {"path": str(expect_path), "sha256": expect_sha, "size_bytes": len(expect_raw)},
    "dimensions": expected_size, "points": points, "pixel_points": pixel_points,
    "groundtruth_point_alpha": expect_point_alpha, "background_pixel": [0, 0],
    "groundtruth_background_alpha": expect_alpha.getpixel((0, 0)),
    "alpha_threshold": alpha_threshold, "minimum_iou": iou_threshold,
    "historical_output_is_not_groundtruth": True, "model_paths": model_paths,
    "segment_source_sha256": source_hash,
    "root_prestart_free_physical_memory_kib": 4884624,
    "memory_measurement_origin": "根线程启动前只读实测，非本次峰值测量"})

secret = (private / "service.secret").read_text(encoding="utf-8-sig").strip()
assert len(secret.encode()) >= 32
result = {"scope": "缓存加载改动后当前7405单样本真实本地SAM点选", "started_at": now(),
          "passed": False, "real_sam_selection_posts": 0, "other_model_inference_calls": 0,
          "paid_provider_calls": 0, "formal_tasks_registered": 0, "formal_assets_registered": 0,
          "database_writes": 0, "service_operations": 0, "models_changed": False,
          "source_sha256": source_sha, "independent_expect_sha256": expect_sha,
          "segment_source_sha256": source_hash, "model_paths": model_paths,
          "business_acceptance": False, "quality_accepted": False,
          "limits": ["只证明当前通道一个固定样本的真实输出，不证明完整质量集、峰值、真实LRU次数或缓存字节上限。",
                     "历史输出仅作比较；通过条件来自独立groundtruth和操作前预期。",
                     "CPU模式来源为已冻结segment中两个InferenceSession的显式CPUExecutionProvider设置，不冒称HTTP公开了get_providers结果。"]}
failure = None
try:
    before = request("GET", "/internal/v1/capabilities")
    save("capabilities-before.json", before)
    sam_before = before["capabilities"]["sam"]
    result["sam_before"] = sam_before
    assert before["enabled"] is True
    assert sam_before["ready"] is True and sam_before["loaded"] is False and sam_before["offline_test"] is False
    for role, key in [("encoder", "AIPICTURE_SAM_ENCODER_PATH"), ("decoder", "AIPICTURE_SAM_DECODER_PATH")]:
        assert sam_before["models"][role]["sha256"] == model_paths[key]["sha256"]
    asset_id = "00000000-0000-0000-0000-000000000001"
    payload = {"schema_version": "1", "tool_registry_version": "2026.10-r2",
        "run_id": str(uuid.uuid4()), "attempt": 1, "tool": "selection", "input_version": 0, "input_revision": 0,
        "params": {"points": [{"x": x, "y": y} for x, y in points]},
        "document": {"width": 1080, "height": 1080, "layers": [{"id": "base", "kind": "image", "name": "底图",
            "width": 1080, "height": 1080, "asset_id": asset_id, "locked": True,
            "transform": {"x": 0, "y": 0, "scale_x": 1, "scale_y": 1, "rotation": 0}, "opacity": 1, "visible": True}]},
        "assets": [{"id": asset_id, "sha256": source_sha, "width": 1080, "height": 1080,
                    "format": "PNG", "content_base64": base64.b64encode(source).decode()}]}
    result["real_sam_selection_posts"] = 1
    response = request("POST", "/internal/v1/compute", payload, timeout=180)
    assert len(response["artifacts"]) == 1
    artifact = response["artifacts"][0]
    output = base64.b64decode(artifact["content_base64"], validate=True)
    assert sha(output) == artifact["sha256"]
    assert artifact["id"] == response["result"]["mask_asset_id"] and artifact["kind"] == "mask"
    with (directory / "sam-mask.png").open("xb") as stream:
        stream.write(output)
    artifact.pop("content_base64")
    artifact["content_base64_omitted"] = True
    save("selection-response.json", response)
    output_image = Image.open(io.BytesIO(output))
    assert output_image.size == expected_size and "A" in output_image.getbands()
    alpha = output_image.getchannel("A")
    output_pixels = alpha.tobytes()
    intersection = sum(a >= alpha_threshold and b >= alpha_threshold for a, b in zip(output_pixels, expect_pixels, strict=True))
    union = sum(a >= alpha_threshold or b >= alpha_threshold for a, b in zip(output_pixels, expect_pixels, strict=True))
    assert union > 0
    iou = intersection / union
    selected = [alpha.getpixel(point) >= alpha_threshold for point in pixel_points]
    background_selected = alpha.getpixel((0, 0)) >= alpha_threshold
    sam_provider = response["provider"]["sam"]
    assert response["provider"]["offline_test"] is False
    assert sam_provider == {"provider": "onnxruntime", "model": "sam", "loaded": True, "inference": "points"}
    result["output"] = {"path": str(directory / "sam-mask.png"), "sha256": sha(output), "size_bytes": len(output),
        "dimensions": output_image.size, "mode": output_image.mode, "alpha_bbox": alpha.getbbox(),
        "alpha_iou": iou, "intersection": intersection, "union": union,
        "foreground_pixels": sum(value >= alpha_threshold for value in output_pixels),
        "points_selected": selected, "point_alpha": [alpha.getpixel(point) for point in pixel_points],
        "background_corner_selected": background_selected, "provider": response["provider"]}
    result["historical_comparison"] = {"path": str(historical_path), "sha256": historical_sha,
        "alpha_iou": historical_iou, "same_output_bytes": sha(output) == historical_sha,
        "same_groundtruth_iou": iou == historical_iou, "used_as_groundtruth": False}
    assert iou >= iou_threshold and all(selected) and not background_selected
except Exception as exc:
    failure = exc
    result["failure"] = {"type": type(exc).__name__, "message": str(exc)[:500]}
finally:
    try:
        after = request("GET", "/internal/v1/capabilities")
        save("capabilities-after.json", after)
        sam_after = after["capabilities"]["sam"]
        result["sam_after"] = sam_after
        if result["real_sam_selection_posts"] == 1 and failure is None:
            assert sam_after["loaded"] is True
            assert sam_after["models"] == result["sam_before"]["models"]
            result["passed"] = True
    except Exception as exc:
        result["after_failure"] = {"type": type(exc).__name__, "message": str(exc)[:500]}
    result.update(finished_at=now(), http_calls=calls,
                  loaded_attribution="调用前后签名capabilities来自当前7405服务进程；探针不加载SAM模型。")
    save("result.json", result)
    print(json.dumps({"passed": result["passed"], "selection_posts": result["real_sam_selection_posts"],
                      "http_calls": len(calls), "output": result.get("output"),
                      "failure": result.get("failure"), "after_failure": result.get("after_failure"),
                      "result": str(directory / "result.json")}, ensure_ascii=False))
sys.exit(0 if result["passed"] else 1)
