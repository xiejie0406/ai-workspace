import hashlib
import io
from collections import OrderedDict
from concurrent.futures import Future
from pathlib import Path
from threading import Lock

from PIL import Image, ImageDraw

from aipicture_runtime.config import get_settings
from aipicture_runtime.edits.mask import overlay_png

_CACHE_LIMIT = 4
_HASH_CHUNK_BYTES = 1024 * 1024
_PREPROCESS_VERSION = "sam-rgb-warp-684x1024-v1"
_CacheKey = tuple[object, tuple[tuple[str, int, str], ...], str, tuple[int, int], str]
_lock = Lock()
_embeddings: OrderedDict[_CacheKey, object] = OrderedDict()
_inflight: dict[_CacheKey, Future] = {}
_sam = None


def warm_embedding(image: bytes) -> None:
    """进点选时先算 embedding，点击就只跑 decoder。"""
    if get_settings().offline_test and get_settings().matting_provider == "corner":
        return
    source = Image.open(io.BytesIO(image)).convert("RGB")
    _embedding_of(_sam_session(), image, source)


def segment_points(image: bytes, points: list[tuple[float, float]]) -> bytes:
    """按归一化点选生成 L 遮罩。同一张图的 embedding 只算一次。"""
    source = Image.open(io.BytesIO(image)).convert("RGB")
    if not points:
        return overlay_png(Image.new("L", source.size, 0))

    if get_settings().offline_test and get_settings().matting_provider == "corner":
        mask = _circles(source.size, points)
    else:
        mask = _sam_mask(source, image, points)
    return overlay_png(mask)


def _circles(size: tuple[int, int], points: list[tuple[float, float]]) -> Image.Image:
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    radius = max(16, int(min(size) * 0.16))
    for nx, ny in points:
        x, y = nx * size[0], ny * size[1]
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=255)
    return mask


def _sam_mask(source: Image.Image, raw: bytes, points: list[tuple[float, float]]) -> Image.Image:
    session = _sam_session()
    embedding = _embedding_of(session, raw, source)
    prompt = [
        {"type": "point", "label": 1, "data": [int(x * source.width), int(y * source.height)]}
        for x, y in points
    ]
    return _decode(session, embedding, prompt)


def _sam_session():
    global _sam
    with _lock:
        if _sam is None:
            from types import SimpleNamespace
            import onnxruntime as ort
            settings = get_settings()
            if not settings.sam_encoder_path or not settings.sam_decoder_path or not settings.sam_encoder_path.is_file() or not settings.sam_decoder_path.is_file():
                raise RuntimeError("SAM encoder/decoder 未配置，未使用圆形选区替代")
            paths = (("encoder", settings.sam_encoder_path.resolve()),
                     ("decoder", settings.sam_decoder_path.resolve()))
            # 两次新鲜流式观察只证明加载期间文件的一致性，不冒称ORT已加载字节的精确SHA。
            fingerprints = _model_fingerprints(paths)
            encoder = ort.InferenceSession(str(paths[0][1]), providers=["CPUExecutionProvider"])
            decoder = ort.InferenceSession(str(paths[1][1]), providers=["CPUExecutionProvider"])
            if _model_fingerprints(paths) != fingerprints:
                raise RuntimeError("SAM 权重在加载期间发生变化，未发布模型实例")
            _sam = SimpleNamespace(
                encoder=encoder, decoder=decoder,
                # namespace随成功加载的encoder固定，键保留对象，不使用可能复用的id()。
                cache_namespace=object(), model_fingerprints=fingerprints,
            )
        return _sam


def _model_fingerprints(paths: tuple[tuple[str, Path], ...]) -> tuple[tuple[str, int, str], ...]:
    observations = []
    for role, path in paths:
        digest, size = hashlib.sha256(), 0
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(_HASH_CHUNK_BYTES), b""):
                digest.update(chunk)
                size += len(chunk)
        observations.append((role, size, digest.hexdigest()))
    return tuple(observations)


def _pixel_digest(source: Image.Image) -> str:
    digest = hashlib.sha256(f"RGB:{source.width}:{source.height}\0".encode("ascii"))
    row_bytes = source.width * 3
    if row_bytes <= _HASH_CHUNK_BYTES:
        rows = max(1, _HASH_CHUNK_BYTES // row_bytes)
        for top in range(0, source.height, rows):
            digest.update(source.crop((0, top, source.width, min(source.height, top + rows))).tobytes())
    else:
        # 超宽合法图按行内块处理；保持RGB像素的行序，每块仍不超过1MiB。
        columns = _HASH_CHUNK_BYTES // 3
        for top in range(source.height):
            for left in range(0, source.width, columns):
                digest.update(source.crop((left, top, min(source.width, left + columns), top + 1)).tobytes())
    return digest.hexdigest()


def _embedding_of(session, raw: bytes, source: Image.Image):
    # raw仅保留内部调用签名；缓存按实际RGB encoder输入复用，不按容器压缩/元数据区分。
    if source.mode != "RGB":
        source = source.convert("RGB")
    key = (session.cache_namespace, session.model_fingerprints,
           _PREPROCESS_VERSION, source.size, _pixel_digest(source))
    with _lock:
        cached = _embeddings.get(key)
        if cached is not None:
            _embeddings.move_to_end(key)
            return cached
        pending = _inflight.get(key)
        producer = pending is None
        if producer:
            pending = Future()
            _inflight[key] = pending
    # 同图共享一次冷编码；不同图编码与等待都在全局缓存锁外进行。
    if not producer:
        return pending.result()
    try:
        embedding = _encode(session, source)
        with _lock:
            _embeddings[key] = embedding
            _embeddings.move_to_end(key)
            while len(_embeddings) > _CACHE_LIMIT:
                _embeddings.popitem(last=False)
            _inflight.pop(key, None)
    except BaseException as exc:
        with _lock:
            _inflight.pop(key, None)
        pending.set_exception(exc)
        raise
    pending.set_result(embedding)
    return embedding


def _encode(session, source: Image.Image):
    import numpy as np
    from rembg.sessions.sam import warp_affine

    target = (684, 1024)
    cv_image = np.array(source.convert("RGB"))
    original_size = cv_image.shape[:2]
    scale = min(target[1] / cv_image.shape[1], target[0] / cv_image.shape[0])
    transform = np.array([[scale, 0, 0], [0, scale, 0], [0, 0, 1]])
    warped = warp_affine(cv_image, transform[:2], target)
    name = session.encoder.get_inputs()[0].name
    embedding = session.encoder.run(None, {name: warped.astype(np.float32)})[0]
    return {
        "image_embedding": embedding,
        "original_size": original_size,
        "transform_matrix": transform,
        "input_size": target,
    }


def _decode(session, embedding: dict, prompt: list) -> Image.Image:
    import numpy as np
    from PIL import Image as PILImage
    from rembg.sessions.sam import apply_coords, get_input_points, transform_masks

    input_size = embedding["input_size"]
    transform = embedding["transform_matrix"]
    input_points, input_labels = get_input_points(prompt)
    onnx_coord = np.concatenate([input_points, np.array([[0.0, 0.0]])], axis=0)[None, :, :]
    onnx_label = np.concatenate([input_labels, np.array([-1])], axis=0)[None, :].astype(np.float32)
    onnx_coord = apply_coords(onnx_coord, input_size, 1024).astype(np.float32)
    onnx_coord = np.concatenate(
        [onnx_coord, np.ones((1, onnx_coord.shape[1], 1), dtype=np.float32)],
        axis=2,
    )
    onnx_coord = np.matmul(onnx_coord, transform.T)[:, :, :2].astype(np.float32)
    masks, _, _ = session.decoder.run(
        None,
        {
            "image_embeddings": embedding["image_embedding"],
            "point_coords": onnx_coord,
            "point_labels": onnx_label,
            "mask_input": np.zeros((1, 1, 256, 256), dtype=np.float32),
            "has_mask_input": np.zeros(1, dtype=np.float32),
            "orig_im_size": np.array(input_size, dtype=np.float32),
        },
    )
    masks = transform_masks(masks, embedding["original_size"], np.linalg.inv(transform))
    packed = np.zeros((masks.shape[2], masks.shape[3]), dtype=np.uint8)
    for item in masks[0]:
        packed[item > 0.0] = 255
    return PILImage.fromarray(packed, mode="L")
