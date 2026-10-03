"""OCR 图像入口契约；真实库只加载像素，模型返回替身不作为识别质量证据。"""

import io

import pytest
from PIL import Image

from aipicture_runtime.config import Settings
from aipicture_runtime.edits import ocr


def encoded(image):
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return buffer.getvalue()


@pytest.fixture
def real_ocr_config(monkeypatch, tmp_path):
    paths = []
    for name in ["det.onnx", "rec.onnx", "cls.onnx"]:
        path = tmp_path / name
        path.write_bytes(b"input-contract-test-does-not-initialize-model")
        paths.append(path)
    settings = Settings(offline_test=False, image_provider="dashscope", matting_provider="unconfigured", ocr_provider="rapidocr",
                        ocr_det_model_path=paths[0], ocr_rec_model_path=paths[1], ocr_cls_model_path=paths[2])
    monkeypatch.setattr(ocr, "get_settings", lambda: settings)
    return settings


@pytest.mark.parametrize("mode,pixels", [
    ("RGB", [(240, 20, 10), (10, 20, 240)]),
    ("L", [32, 224]),
    ("RGBA", [(240, 20, 10, 0), (10, 20, 240, 128)]),
])
def test_pixels_match_library_bytes_contract_after_existing_alpha_removal(monkeypatch, real_ocr_config, mode, pixels):
    np = pytest.importorskip("numpy")
    loader = pytest.importorskip("rapidocr_onnxruntime.utils.load_image").LoadImage()
    source = Image.new(mode, (2, 1))
    source.putdata(pixels)
    # 库支持的 PNG bytes 入口为独立参照；保留原入口 convertRGB 丢弃 alpha 的语义。
    expected = loader(encoded(source.convert("RGB")))
    received = []

    class CaptureEngine:
        def __call__(self, value):
            received.append(value)
            return None, None

    monkeypatch.setattr(ocr, "_engine", CaptureEngine())
    assert ocr.detect_text(encoded(source)) == []
    assert len(received) == 1
    np.testing.assert_array_equal(loader(received[0]), expected)
    assert received[0].shape == (1, 2, 3)
    assert received[0].dtype == np.uint8
    assert received[0].flags.c_contiguous


def test_text_box_metadata_stays_on_original_canvas_grid(monkeypatch, real_ocr_config):
    pytest.importorskip("rapidocr_onnxruntime")

    class CaptureEngine:
        def __call__(self, image):
            assert image.shape == (32, 64, 3)
            return [
                [[[3.8, 5.9], [23.7, 6.1], [23.2, 16.8], [3.1, 16.2]], "  中文 AI  ", 0.93],
                [[[10, 10], [20, 10], [20, 20], [10, 20]], "   ", 0.99],
                [[[1, 1], [3, 1], [3, 8], [1, 8]], "小", 0.99],
            ], [0.01, 0.01, 0.01]

    monkeypatch.setattr(ocr, "_engine", CaptureEngine())
    result = ocr.detect_text(encoded(Image.new("RGB", (64, 32), (210, 210, 210))))
    assert result == [ocr.TextBox("中文 AI", 3, 5, 20, 11)]


def test_explicit_model_paths_are_kept_when_initializing(monkeypatch, real_ocr_config):
    module = pytest.importorskip("rapidocr_onnxruntime")
    calls = []

    class CaptureEngine:
        def __init__(self, **kwargs):
            calls.append(kwargs)

        def __call__(self, image):
            return None, None

    monkeypatch.setattr(module, "RapidOCR", CaptureEngine)
    monkeypatch.setattr(ocr, "_engine", None)
    assert ocr.detect_text(encoded(Image.new("RGB", (64, 32), "white"))) == []
    assert calls == [{"det_model_path": str(real_ocr_config.ocr_det_model_path),
                     "rec_model_path": str(real_ocr_config.ocr_rec_model_path),
                     "cls_model_path": str(real_ocr_config.ocr_cls_model_path)}]
