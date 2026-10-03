"""仅用受控初始化器/编码器验证线程边界，不执行真实模型或外部请求。"""

import threading
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest
from PIL import Image

from aipicture_runtime.config import Settings
from aipicture_runtime.edits import ocr, segment


@pytest.fixture
def empty_embedding_cache(monkeypatch):
    monkeypatch.setattr(segment, "_embeddings", OrderedDict())
    monkeypatch.setattr(segment, "_inflight", {}, raising=False)


@pytest.fixture
def configured_ocr(monkeypatch, tmp_path):
    module = pytest.importorskip("rapidocr_onnxruntime")
    paths = []
    for name in ["det.onnx", "rec.onnx", "cls.onnx"]:
        path = tmp_path / name
        path.write_bytes(b"controlled-initializer-no-model")
        paths.append(path)
    settings = Settings(offline_test=False, image_provider="dashscope", matting_provider="unconfigured",
                        ocr_provider="rapidocr", ocr_det_model_path=paths[0],
                        ocr_rec_model_path=paths[1], ocr_cls_model_path=paths[2])
    monkeypatch.setattr(ocr, "get_settings", lambda: settings)
    monkeypatch.setattr(ocr, "_engine", None)
    return module


def png(image=None):
    import io
    buffer = io.BytesIO()
    (image if image is not None else Image.new("RGB", (16, 16), (210, 210, 210))).save(buffer, "PNG")
    return buffer.getvalue()


def controlled_sam():
    return SimpleNamespace(encoder=object(), cache_namespace=object(),
                           model_fingerprints=(("encoder", 0, "controlled-weight"),))


@pytest.mark.parametrize("fails", [False, True], ids=["success", "failure-cleanup"])
def test_same_image_cold_cache_has_one_encoder_owner(monkeypatch, empty_embedding_cache, fails):
    entered, second_encoder, release, second_started = (threading.Event() for _ in range(4))
    calls, count_lock = [], threading.Lock()
    embedding = {"controlled_embedding": object()}

    def controlled_encode(_session, _source):
        with count_lock:
            calls.append(1)
            (entered if len(calls) == 1 else second_encoder).set()
        assert release.wait(3), "受控编码器释放信号未到达"
        if fails:
            raise RuntimeError("controlled-encoder-failure")
        return embedding

    monkeypatch.setattr(segment, "_encode", controlled_encode)
    session = controlled_sam()
    source = Image.new("RGB", (4, 4))
    raw = png(source)

    def invoke(second=False):
        if second:
            second_started.set()
        return segment._embedding_of(session, raw, source)

    with ThreadPoolExecutor(max_workers=2) as pool:
        try:
            first = pool.submit(invoke)
            assert entered.wait(2)
            second = pool.submit(invoke, True)
            assert second_started.wait(2)
            # 原实现第二个线程会进入受控编码器；修后应等待同一在途结果。
            second_encoder.wait(0.2)
        finally:
            release.set()
        if fails:
            for future in [first, second]:
                with pytest.raises(RuntimeError, match="controlled-encoder-failure"):
                    future.result(timeout=2)
        else:
            assert first.result(timeout=2) is embedding
            assert second.result(timeout=2) is embedding
    assert len(calls) == 1, "同图冷缓存重复编码"
    assert segment._inflight == {}
    if fails:
        assert not segment._embeddings
        recovered = object()
        monkeypatch.setattr(segment, "_encode", lambda *_: recovered)
        assert invoke() is recovered
        assert segment._inflight == {}


def test_different_images_keep_parallel_encoding(monkeypatch, empty_embedding_cache):
    rendezvous = threading.Barrier(2)

    def controlled_encode(_session, source):
        rendezvous.wait(timeout=2)
        return {"pixel": source.getpixel((0, 0))}

    monkeypatch.setattr(segment, "_encode", controlled_encode)
    session = controlled_sam()
    red_source, blue_source = Image.new("RGB", (4, 4), "red"), Image.new("RGB", (4, 4), "blue")
    with ThreadPoolExecutor(max_workers=2) as pool:
        red = pool.submit(segment._embedding_of, session, png(red_source), red_source)
        blue = pool.submit(segment._embedding_of, session, png(blue_source), blue_source)
        assert red.result(timeout=3)["pixel"] == (255, 0, 0)
        assert blue.result(timeout=3)["pixel"] == (0, 0, 255)
    assert len(segment._embeddings) == 2
    assert not segment._inflight


def test_embedding_lru_still_keeps_four_and_recomputes_evicted_image(monkeypatch, empty_embedding_cache):
    calls = []

    def controlled_encode(*_):
        value = object()
        calls.append(value)
        return value

    monkeypatch.setattr(segment, "_encode", controlled_encode)
    session = controlled_sam()
    source = Image.new("RGB", (4, 4), (0, 2, 3))
    first = segment._embedding_of(session, png(source), source)
    assert segment._embedding_of(session, png(source), source) is first
    for index in range(1, 5):
        different = Image.new("RGB", (4, 4), (index, 2, 3))
        segment._embedding_of(session, png(different), different)
    assert len(segment._embeddings) == 4
    assert len(calls) == 5
    assert segment._embedding_of(session, png(source), source) is not first
    assert len(calls) == 6
    assert len(segment._embeddings) == 4


def test_ocr_first_use_constructs_one_engine(monkeypatch, configured_ocr):
    entered, second_initializer, release, second_started = (threading.Event() for _ in range(4))
    calls, count_lock = [], threading.Lock()

    class ControlledEngine:
        def __init__(self, **kwargs):
            with count_lock:
                calls.append(kwargs)
                (entered if len(calls) == 1 else second_initializer).set()
            assert release.wait(3)

        def __call__(self, _image):
            return None, None

    monkeypatch.setattr(configured_ocr, "RapidOCR", ControlledEngine)

    def invoke(second=False):
        if second:
            second_started.set()
        return ocr.detect_text(png())

    with ThreadPoolExecutor(max_workers=2) as pool:
        try:
            first = pool.submit(invoke)
            assert entered.wait(2)
            second = pool.submit(invoke, True)
            assert second_started.wait(2)
            second_initializer.wait(0.2)
        finally:
            release.set()
        assert first.result(timeout=2) == []
        assert second.result(timeout=2) == []
    assert len(calls) == 1, "OCR 首次使用重复构造模型"


def test_ocr_initialization_failure_can_retry(monkeypatch, configured_ocr):
    calls = []

    class ControlledEngine:
        def __init__(self, **kwargs):
            calls.append(kwargs)
            if len(calls) == 1:
                raise RuntimeError("controlled-initializer-failure")

        def __call__(self, _image):
            return None, None

    monkeypatch.setattr(configured_ocr, "RapidOCR", ControlledEngine)
    with pytest.raises(RuntimeError, match="controlled-initializer-failure"):
        ocr.detect_text(png())
    assert ocr._engine is None
    assert ocr.detect_text(png()) == []
    assert len(calls) == 2


def test_loaded_ocr_calls_are_not_serialized_by_initialization_guard(monkeypatch, configured_ocr):
    rendezvous = threading.Barrier(2)

    class ControlledEngine:
        def __call__(self, _image):
            rendezvous.wait(timeout=2)
            return None, None

    monkeypatch.setattr(ocr, "_engine", ControlledEngine())
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(ocr.detect_text, png())
        second = pool.submit(ocr.detect_text, png())
        assert first.result(timeout=3) == []
        assert second.result(timeout=3) == []
