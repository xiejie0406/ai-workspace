import io
from dataclasses import dataclass
from threading import Lock

from PIL import Image

from aipicture_runtime.config import get_settings


@dataclass(frozen=True)
class TextBox:
    text: str
    x: int
    y: int
    width: int
    height: int


_engine = None
_engine_lock = Lock()


def detect_text(data: bytes) -> list[TextBox]:
    """识别真实文字框，缺模型/依赖明确失败；none 仅用于显式离线测试。"""
    if get_settings().offline_test and get_settings().ocr_provider == "none":
        return []
    settings = get_settings()
    paths = [settings.ocr_det_model_path, settings.ocr_rec_model_path, settings.ocr_cls_model_path]
    if settings.ocr_provider not in {"rapidocr", "auto"} or any(not path or not path.is_file() for path in paths):
        raise RuntimeError("OCR 本地模型未配置，未以空列表冒充识别成功")
    boxes = _rapidocr(data)
    return [box for box in boxes if box.text.strip() and box.width > 2 and box.height > 2]


def _rapidocr(data: bytes) -> list[TextBox]:
    import numpy as np
    from rapidocr_onnxruntime import RapidOCR

    global _engine
    if _engine is None:
        # 只保护首次构造；真实识别在锁外，不把不同图片的推理串行化。
        with _engine_lock:
            if _engine is None:
                settings = get_settings()
                _engine = RapidOCR(det_model_path=str(settings.ocr_det_model_path),
                                   rec_model_path=str(settings.ocr_rec_model_path),
                                   cls_model_path=str(settings.ocr_cls_model_path))

    # RapidOCR 的 ndarray 入口使用 BGR；保留原 RGB 解码/丢弃 alpha，再统一通道约定。
    image = np.array(Image.open(io.BytesIO(data)).convert("RGB"))[:, :, ::-1].copy()
    result, _ = _engine(image)
    if not result:
        return []

    boxes: list[TextBox] = []
    for item in result:
        quad, text, _score = item[0], item[1], item[2]
        xs = [point[0] for point in quad]
        ys = [point[1] for point in quad]
        left, top = int(min(xs)), int(min(ys))
        boxes.append(
            TextBox(
                text=str(text).strip(),
                x=left,
                y=top,
                width=max(1, int(max(xs) - left)),
                height=max(1, int(max(ys) - top)),
            )
        )
    return boxes
