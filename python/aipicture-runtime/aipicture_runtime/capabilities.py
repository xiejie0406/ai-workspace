"""能力清单来自实际配置/依赖/本地权重，绝不把未配置模型标为可用。"""
from importlib.util import find_spec
from functools import lru_cache
import hashlib
import sys
from pathlib import Path

from aipicture_runtime import SCHEMA_VERSION, TOOL_REGISTRY_VERSION
from aipicture_runtime.config import get_settings
from aipicture_runtime.registry import export_registry


@lru_cache(maxsize=16)
def _model_hash(path: str, size: int, modified_ns: int) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _model(path) -> dict:
    if not path or not path.is_file():
        return {"present": False, "sha256": None, "size_bytes": None}
    stat = path.stat()
    return {"present": True, "name": path.name, "size_bytes": stat.st_size,
            "sha256": _model_hash(str(path.resolve()), stat.st_size, stat.st_mtime_ns)}


def _text_rendering() -> dict:
    from aipicture_runtime.edits import render
    result = {"ready": False, "provider": "pillow", "loaded": False, "family": None,
              "validation": "font_load_and_basic_glyph_probe", "probe": "中文价格ABC123",
              "font": {"present": False, "sha256": None, "size_bytes": None}, "error": None}
    try:
        path = render._font_path()
        result["font"] = _model(Path(path)) if path else result["font"]
        font = render._font(32)
        result.update(loaded=True, family=font.getname()[0])
        render._check_glyphs(font, result["probe"])
        result["ready"] = True
    except (RuntimeError, OSError, ValueError) as exc:
        result["error"] = str(exc)[:200]
    return result


def capabilities() -> dict:
    settings = get_settings()
    has = lambda path: bool(path and path.is_file())
    test = settings.offline_test
    remote_configured = settings.enabled and bool(settings.dashscope_api_key) and not test
    # 图像 Provider 在付费提交前要求批准下载域名；文本规划没有此下载前提。
    image = remote_configured and settings.image_provider == "dashscope" and bool(settings.provider_output_hosts)
    planner = remote_configured and bool(find_spec("langgraph"))
    matting = settings.matting_provider in {"rembg", "auto"} and bool(find_spec("rembg")) and has(settings.matting_model_path)
    sam = bool(find_spec("onnxruntime")) and bool(find_spec("rembg")) and has(settings.sam_encoder_path) and has(settings.sam_decoder_path)
    ocr = settings.ocr_provider in {"rapidocr", "auto"} and bool(find_spec("rapidocr_onnxruntime")) and all(has(path) for path in [settings.ocr_det_model_path, settings.ocr_rec_model_path, settings.ocr_cls_model_path])
    def state(ready: bool, provider: str) -> dict:
        return {"ready": ready, "provider": provider, "offline_test": test}
    def local(ready: bool, provider: str, files: dict, module: str, instance: str) -> dict:
        loaded = getattr(sys.modules.get(module), instance, None) is not None
        return {**state(ready, provider), "loaded": loaded, "validation": "files_and_dependencies_only",
                "models": {name: _model(path) for name, path in files.items()}}
    from aipicture_runtime.edits.super_resolution import capability
    enhancement = capability()
    if settings.enhancement_provider == "image_edit":
        enhancement["ready"] = image
    return {"schema_version": SCHEMA_VERSION, "tool_registry_version": TOOL_REGISTRY_VERSION, "enabled": settings.enabled,
        "tools": export_registry()["tools"], "capabilities": {
            "deterministic_pixels": state(True, "pillow"), "text_rendering": {**_text_rendering(), "offline_test": test},
            "image_generation": state(image, settings.image_provider),
            "image_edit": state(image, settings.image_provider), "enhancement": {**enhancement, "offline_test": test},
            "matting": local(matting or test and settings.matting_provider == "corner", settings.matting_provider,
                {"u2net": settings.matting_model_path}, "aipicture_runtime.edits.pixels", "_session"),
            "sam": local(sam or test and settings.matting_provider == "corner", "sam" if not test else "test_circles",
                {"encoder": settings.sam_encoder_path, "decoder": settings.sam_decoder_path}, "aipicture_runtime.edits.segment", "_sam"),
            "ocr": local(ocr, settings.ocr_provider, {"det": settings.ocr_det_model_path, "rec": settings.ocr_rec_model_path,
                "cls": settings.ocr_cls_model_path}, "aipicture_runtime.edits.ocr", "_engine"),
            "planner": state(planner, settings.planner_model)}}
