"""真实图片解码＋受控 ONNX 构造/编码，不加载权重或执行真实模型。"""

import hashlib
import io
import os
import sys
import threading
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest
from PIL import Image, PngImagePlugin

from aipicture_runtime.edits import segment


def encoded(image, *, compression=6, note=None):
    buffer = io.BytesIO()
    metadata = PngImagePlugin.PngInfo()
    if note is not None:
        metadata.add_text("technical_note", note)
    image.save(buffer, "PNG", compress_level=compression, pnginfo=metadata)
    return buffer.getvalue()


def rgb_of(raw):
    return Image.open(io.BytesIO(raw)).convert("RGB")


@pytest.fixture
def configured_sam(monkeypatch, tmp_path):
    encoder, decoder = tmp_path / "encoder.onnx", tmp_path / "decoder.onnx"
    encoder.write_bytes(b"encoder-weight-A")
    decoder.write_bytes(b"decoder-weight-A")
    state = SimpleNamespace(encoder_path=encoder, decoder_path=decoder, on_load=None, calls=[])

    class ControlledInferenceSession:
        def __init__(self, path, *, providers):
            assert isinstance(path, str), "保持路径加载，不让 ORT 长期持有模型 bytes"
            assert providers == ["CPUExecutionProvider"]
            self.path = Path(path)
            self.loaded_bytes = self.path.read_bytes()
            state.calls.append(self)
            if state.on_load:
                state.on_load(self)

    ort = ModuleType("onnxruntime")
    ort.InferenceSession = ControlledInferenceSession
    monkeypatch.setitem(sys.modules, "onnxruntime", ort)
    monkeypatch.setattr(segment, "get_settings", lambda: SimpleNamespace(
        sam_encoder_path=encoder, sam_decoder_path=decoder))
    monkeypatch.setattr(segment, "_sam", None)
    monkeypatch.setattr(segment, "_embeddings", OrderedDict())
    monkeypatch.setattr(segment, "_inflight", {})
    return state


@pytest.fixture
def controlled_encoding(monkeypatch):
    calls = []

    def encode(session, source):
        value = {"loaded_weight": session.encoder.loaded_bytes, "size": source.size,
                 "first_pixel": source.getpixel((0, 0)), "call": len(calls) + 1}
        calls.append(value)
        return value

    monkeypatch.setattr(segment, "_encode", encode)
    return calls


def test_same_rgb_pixels_reuse_across_png_compression_and_metadata(configured_sam, controlled_encoding):
    source = Image.new("RGB", (32, 24), (19, 87, 151))
    first = encoded(source, compression=0)
    second = encoded(source, compression=9, note="same pixels, different encoding")
    assert first != second and rgb_of(first).tobytes() == rgb_of(second).tobytes()
    session = segment._sam_session()
    value = segment._embedding_of(session, first, rgb_of(first))
    assert segment._embedding_of(session, second, rgb_of(second)) is value
    assert len(controlled_encoding) == 1


def test_alpha_only_difference_reuses_actual_rgb_encoder_input(configured_sam, controlled_encoding):
    opaque = encoded(Image.new("RGBA", (8, 8), (31, 72, 119, 255)))
    transparent = encoded(Image.new("RGBA", (8, 8), (31, 72, 119, 0)))
    assert opaque != transparent and rgb_of(opaque).tobytes() == rgb_of(transparent).tobytes()
    session = segment._sam_session()
    value = segment._embedding_of(session, opaque, rgb_of(opaque))
    assert segment._embedding_of(session, transparent, rgb_of(transparent)) is value
    assert len(controlled_encoding) == 1


def test_equal_pixel_byte_stream_with_different_dimensions_does_not_reuse(configured_sam, controlled_encoding):
    tall = Image.new("RGB", (2, 4), (11, 12, 13))
    wide = Image.new("RGB", (4, 2), (11, 12, 13))
    assert tall.tobytes() == wide.tobytes() and tall.size != wide.size
    session = segment._sam_session()
    first = segment._embedding_of(session, encoded(tall), tall)
    second = segment._embedding_of(session, encoded(wide), wide)
    assert first is not second and second["size"] == (4, 2)
    assert len(controlled_encoding) == 2


def test_changed_pixel_does_not_reuse_same_size_input(configured_sam, controlled_encoding):
    first = Image.new("RGB", (8, 8), (11, 12, 13))
    second = first.copy()
    second.putpixel((7, 7), (11, 12, 14))
    session = segment._sam_session()
    a = segment._embedding_of(session, encoded(first), first)
    assert segment._embedding_of(session, encoded(second), second) is not a
    assert len(controlled_encoding) == 2


def test_different_loaded_encoder_instances_never_share_cache(configured_sam, controlled_encoding):
    source = Image.new("RGB", (8, 8), (11, 12, 13))
    raw = encoded(source)
    first_session = segment._sam_session()
    first = segment._embedding_of(first_session, raw, source)
    # 仅受控测试模拟另一个实例，不增加公共模型热重载入口。
    segment._sam = None
    second_session = segment._sam_session()
    assert first_session.encoder is not second_session.encoder
    assert segment._embedding_of(second_session, raw, source) is not first
    assert len(controlled_encoding) == 2


def test_changed_weights_in_new_instance_do_not_return_old_embedding(configured_sam, controlled_encoding):
    source = Image.new("RGB", (8, 8), (11, 12, 13))
    raw = encoded(source)
    first = segment._embedding_of(segment._sam_session(), raw, source)
    configured_sam.encoder_path.write_bytes(b"encoder-weight-B")
    segment._sam = None
    second = segment._embedding_of(segment._sam_session(), raw, source)
    assert first["loaded_weight"] == b"encoder-weight-A"
    assert second["loaded_weight"] == b"encoder-weight-B"
    assert len(controlled_encoding) == 2


def test_loaded_session_key_does_not_reread_changed_disk_model(configured_sam, controlled_encoding, monkeypatch):
    source = Image.new("RGB", (8, 8), (11, 12, 13))
    raw = encoded(source)
    session = segment._sam_session()
    first = segment._embedding_of(session, raw, source)
    configured_sam.encoder_path.write_bytes(b"encoder-weight-B")

    def forbidden_read(*_args, **_kwargs):
        raise AssertionError("缓存查找不应读取当前模型文件决定旧实例身份")

    monkeypatch.setattr(Path, "open", forbidden_read)
    assert segment._embedding_of(session, raw, source) is first
    assert first["loaded_weight"] == b"encoder-weight-A" and len(controlled_encoding) == 1


def test_preprocessing_version_is_part_of_embedding_identity(configured_sam, controlled_encoding, monkeypatch):
    source = Image.new("RGB", (8, 8), (11, 12, 13))
    raw = encoded(source)
    session = segment._sam_session()
    first = segment._embedding_of(session, raw, source)
    monkeypatch.setattr(segment, "_PREPROCESS_VERSION", "controlled-different-preprocess", raising=False)
    assert segment._embedding_of(session, raw, source) is not first
    assert len(controlled_encoding) == 2


@pytest.mark.parametrize("role", ["encoder", "decoder"])
def test_changed_file_during_loading_is_rejected_even_if_stat_is_restored(configured_sam, role):
    path = getattr(configured_sam, f"{role}_path")
    original = path.stat()

    def changed(session):
        if session.path == path:
            # 同大小和mtime不能蒙混为相同权重；需要加载期新鲜内容hash。
            path.write_bytes(path.read_bytes().replace(b"-A", b"-B"))
            os.utime(path, ns=(original.st_atime_ns, original.st_mtime_ns))

    configured_sam.on_load = changed
    with pytest.raises(RuntimeError, match="加载期间.*变化"):
        segment._sam_session()
    assert segment._sam is None
    configured_sam.on_load = None
    assert segment._sam_session().encoder.loaded_bytes == configured_sam.encoder_path.read_bytes()


@pytest.mark.parametrize("role", ["encoder", "decoder"])
def test_constructor_failure_does_not_publish_session_and_can_retry(configured_sam, role):
    path = getattr(configured_sam, f"{role}_path")

    def failed(session):
        if session.path == path:
            raise RuntimeError("controlled-constructor-failure")

    configured_sam.on_load = failed
    with pytest.raises(RuntimeError, match="controlled-constructor-failure"):
        segment._sam_session()
    assert segment._sam is None
    configured_sam.on_load = None
    assert segment._sam_session().decoder.loaded_bytes == b"decoder-weight-A"


def test_loaded_identity_has_fixed_namespace_and_observation_fingerprints(configured_sam):
    session = segment._sam_session()
    expected = tuple((role, len(raw), hashlib.sha256(raw).hexdigest()) for role, raw in [
        ("encoder", b"encoder-weight-A"), ("decoder", b"decoder-weight-A")])
    assert session.model_fingerprints == expected
    assert session.cache_namespace is segment._sam_session().cache_namespace
    configured_sam.encoder_path.write_bytes(b"encoder-weight-B")
    assert session.model_fingerprints == expected


@pytest.mark.parametrize("size", [(100, 6000), (350000, 2)], ids=["vertical-strips", "horizontal-blocks"])
def test_pixel_digest_uses_bounded_strips_not_a_second_full_image(configured_sam, controlled_encoding, monkeypatch, size):
    source = Image.new("RGB", size, (11, 12, 13))
    raw = encoded(source)
    original, observed = Image.Image.tobytes, []

    def bounded(image, *args, **kwargs):
        assert image.size != source.size, "不能为hash另建整张图片的RGB字节副本"
        data = original(image, *args, **kwargs)
        assert len(data) <= 1024 * 1024, "像素hash应分块读取"
        observed.append((image.size, len(data)))
        return data

    session = segment._sam_session()
    monkeypatch.setattr(Image.Image, "tobytes", bounded)
    segment._embedding_of(session, raw, source)
    assert len(observed) >= 2 and sum(length for _, length in observed) == size[0] * size[1] * 3
    assert len(controlled_encoding) == 1


def test_same_pixels_different_encoding_share_one_inflight_encoder(configured_sam, monkeypatch):
    source = Image.new("RGB", (16, 16), (11, 12, 13))
    first_raw, second_raw = encoded(source, compression=0), encoded(source, compression=9, note="waiter")
    entered, duplicate, release, second_started = (threading.Event() for _ in range(4))
    calls, count_lock = [], threading.Lock()
    expected = object()

    def controlled(_session, _source):
        with count_lock:
            calls.append(1)
            (entered if len(calls) == 1 else duplicate).set()
        assert release.wait(3)
        return expected

    monkeypatch.setattr(segment, "_encode", controlled)
    session = segment._sam_session()

    def invoke(raw, second=False):
        if second:
            second_started.set()
        return segment._embedding_of(session, raw, rgb_of(raw))

    with ThreadPoolExecutor(max_workers=2) as pool:
        try:
            first = pool.submit(invoke, first_raw)
            assert entered.wait(2)
            second = pool.submit(invoke, second_raw, True)
            assert second_started.wait(2)
            duplicate.wait(0.2)
        finally:
            release.set()
        assert first.result(timeout=2) is expected and second.result(timeout=2) is expected
    assert len(calls) == 1 and segment._inflight == {}
