"""本地 RGB ONNX 超分接入；不下载权重，不用插值或生成式编辑冒充超分。

模型契约：单输入/单输出 float32 NCHW，RGB [0,1]，batch=1，输出为精确倍率。
只接受内嵌权重图；部署者须核实权重来源、许可与训练用途。
"""
from __future__ import annotations

import asyncio
import hashlib
import io
from importlib.util import find_spec
from pathlib import Path
from threading import Lock

from PIL import Image

from aipicture_runtime.artifacts import probe
from aipicture_runtime.config import get_settings
from aipicture_runtime.providers import get_image_provider
from aipicture_runtime.providers.base import ProviderError, ProgressCallback

_lock = Lock()
_sessions: dict[int, tuple[str, str, object]] = {}


def _digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def capability() -> dict:
    settings = get_settings()
    if settings.enhancement_provider == "image_edit":
        return {"provider": "image_edit", "true_super_resolution": False,
                "validation": "provider_configuration_only"}
    dependencies = bool(find_spec("onnxruntime")) and bool(find_spec("numpy"))
    scales = {}
    for scale in (2, 3, 4):
        path, expected = settings.super_resolution_models.get(scale), settings.super_resolution_sha256.get(scale)
        present = bool(path and path.is_file())
        try:
            actual = _digest(path) if present else None
        except OSError:
            actual = None
        verified = bool(actual and expected and actual == expected.lower())
        # 实例发布为单次字典赋值；能力查询不等待耗时推理锁，避免阻塞 HTTP 事件循环。
        cached = _sessions.get(scale)
        loaded = bool(cached and path and cached[:2] == (str(path.resolve()), actual))
        scales[str(scale)] = {"ready": dependencies and verified, "loaded": loaded,
                              "model": {"present": present, "sha256": actual, "verified": verified}}
    return {"provider": "onnxruntime", "ready": any(item["ready"] for item in scales.values()),
            "loaded": any(item["loaded"] for item in scales.values()), "true_super_resolution": False,
            "implementation": "onnx_super_resolution", "scales": scales,
            "validation": "files_and_dependencies_only",
            "note": "逐倍率资源就绪不表示推理或质量通过；正式产物在模型运行后记录真实来源"}


def _infer(data: bytes, scale: int) -> tuple[bytes, dict]:
    settings = get_settings()
    path, expected = settings.super_resolution_models.get(scale), settings.super_resolution_sha256.get(scale)
    if not path or not path.is_file() or not expected:
        raise ProviderError(f"未配置 {scale} 倍本地超分权重及 SHA256，未生成替代图片")
    if not find_spec("onnxruntime") or not find_spec("numpy"):
        raise ProviderError("本地超分缺少 onnxruntime/numpy，未生成替代图片")
    info = probe(data)
    width, height = info["width"] * scale, info["height"] * scale
    if width * height > settings.max_pixels:
        raise ProviderError("超分输出像素超过既有资源限制")
    # 输入/输出 float 张量和 RGBA 编码工作区在运行前按既有字节预算估算。
    if (info["width"] * info["height"] * 28 + width * height * 24) > settings.max_output_bytes:
        raise ProviderError("超分工作区超过既有字节预算；请使用较小图片")
    import numpy as np
    import onnxruntime as ort
    with _lock:
        if path.stat().st_size > settings.max_request_bytes:
            raise ProviderError("超分权重文件超过既有输入字节预算")
        actual = _digest(path)
        if actual != expected.lower():
            raise ProviderError("超分权重 SHA256 不符合批准配置")
        key = (str(path.resolve()), actual)
        cached = _sessions.get(scale)
        if not cached or cached[:2] != key:
            model_bytes = path.read_bytes()
            if len(model_bytes) > settings.max_request_bytes or hashlib.sha256(model_bytes).hexdigest() != actual:
                raise ProviderError("超分模型读取期间文件变化或超过字节预算")
            # bytes 加载没有外部权重的路径基准；外置权重模型应转换为自包含图后配置。
            session = ort.InferenceSession(model_bytes, providers=["CPUExecutionProvider"])
            if _digest(path) != actual:
                raise ProviderError("超分模型加载期间文件变化，拒绝发布实例")
            inputs, outputs = session.get_inputs(), session.get_outputs()
            if len(inputs) != 1 or len(outputs) != 1:
                raise ProviderError("超分只接受单输入、单输出的 RGB ONNX 模型")
            for node in (inputs[0], outputs[0]):
                if node.type != "tensor(float)" or len(node.shape) != 4 or node.shape[:2] != [1, 3]:
                    raise ProviderError("超分模型必须采用 float32 NCHW RGB，batch=1")
            _sessions[scale] = (*key, session)
        else:
            session = cached[2]
        source = Image.open(io.BytesIO(data)).convert("RGBA")
        expected_input = (1, 3, source.height, source.width)
        expected_output = (1, 3, height, width)
        for node, shape in ((session.get_inputs()[0], expected_input), (session.get_outputs()[0], expected_output)):
            if any(isinstance(dimension, int) and dimension != size for dimension, size in zip(node.shape, shape)):
                raise ProviderError("图片尺寸或倍率与超分模型张量契约不符，不自动缩放")
        rgb = np.asarray(source.convert("RGB"), dtype=np.float32)
        rgb /= 255.0
        tensor = np.ascontiguousarray(rgb.transpose(2, 0, 1)[None])
        del rgb
        output = session.run([session.get_outputs()[0].name], {session.get_inputs()[0].name: tensor})[0]
        if output.dtype != np.float32 or output.shape != expected_output or not np.isfinite(output).all():
            raise ProviderError("超分输出类型、尺寸或数值非法，不采用结果")
        np.clip(output, 0, 1, out=output)
        output *= 255
        np.rint(output, out=output)
        pixels = output[0].transpose(1, 2, 0).astype(np.uint8)
        image = Image.fromarray(pixels).convert("RGBA")
        # 透明度属于覆盖信息；仅 alpha 随尺寸映射，RGB 来自模型实际输出。
        image.putalpha(source.getchannel("A").resize((width, height), Image.Resampling.LANCZOS))
        buffer = io.BytesIO()
        image.save(buffer, "PNG")
        raw = buffer.getvalue()
        probe(raw)
        return raw, {"enhancement_implementation": "onnx_super_resolution", "true_super_resolution": True,
                     "enhancement": {"provider": "onnxruntime", "scale": scale, "model_sha256": actual,
                                     "loaded": True, "inference": "rgb_nchw", "quality_verified": False}}


async def upscale(data: bytes, scale: int, progress: ProgressCallback | None = None) -> tuple[bytes, dict]:
    if type(scale) is not int or scale not in {2, 3, 4}:
        raise ProviderError("超分倍率必须为 2、3、4")
    settings = get_settings()
    if not settings.enabled and not settings.offline_test:
        raise ProviderError("图片计算尚未启用，未执行超分")
    if settings.enhancement_provider == "image_edit":
        raw = await get_image_provider().upscale(data, scale, progress)
        return raw, {"enhancement_implementation": "image_edit", "true_super_resolution": False}
    if progress:
        await progress(20, "校验本地超分权重")
    result = await asyncio.to_thread(_infer, data, scale)
    if progress:
        await progress(85, "超分完成，等待中心保存")
    return result
