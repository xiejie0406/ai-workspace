"""图层中心仿射：同 Konva 的 x/y、负缩放与顺时针旋转，选区按真实 alpha 命中。"""
from __future__ import annotations

import io
import math
import uuid

from PIL import Image, ImageChops

from aipicture_runtime.edits.mask import overlay_png, to_luma
from aipicture_runtime.edits.split import EmptyCut
from aipicture_runtime.layers import Layer, LayerDocument, LayerKind


def local_to_canvas(layer: Layer) -> tuple[float, float, float, float, float, float]:
    t = layer.transform
    if not all(math.isfinite(v) for v in (t.x, t.y, t.scale_x, t.scale_y, t.rotation)) or not t.scale_x or not t.scale_y:
        raise ValueError("图层变换必须有限且缩放非零")
    angle = math.radians(t.rotation)
    cosine, sine = math.cos(angle), math.sin(angle)
    a, b, d, e = cosine * t.scale_x, -sine * t.scale_y, sine * t.scale_x, cosine * t.scale_y
    cx, cy = layer.width / 2, layer.height / 2
    return a, b, t.x + cx - a * cx - b * cy, d, e, t.y + cy - d * cx - e * cy


def inverse_affine(values: tuple) -> tuple[float, ...]:
    a, b, c, d, e, f = values
    det = a * e - b * d
    if abs(det) < 1e-12:
        raise ValueError("图层变换不可逆")
    return e / det, -b / det, (b * f - e * c) / det, -d / det, a / det, (d * c - a * f) / det


def canvas_to_local(data: bytes, layer: Layer, *, mask: bool = False,
                    output_size: tuple[int, int] | None = None) -> Image.Image:
    image = Image.open(io.BytesIO(data)).convert("L" if mask else "RGBA")
    size = output_size or (layer.width, layer.height)
    if size[0] < 1 or size[1] < 1:
        raise ValueError("局部像素尺寸必须为正")
    # Pillow需要输出像素→输入画布的映射。原素材网格先换算至逻辑框，再应用中心仿射；
    # c/f保持逻辑框的世界中心，不把原素材尺寸误当图层布局尺寸。
    a, b, c, d, e, f = local_to_canvas(layer)
    sx, sy = layer.width / size[0], layer.height / size[1]
    values = a * sx, b * sy, c, d * sx, e * sy, f
    return image.transform(size, Image.Transform.AFFINE, values,
                           resample=Image.Resampling.NEAREST if mask else Image.Resampling.BICUBIC,
                           fillcolor=0 if mask else (0, 0, 0, 0))


def mask_for_layer(mask: bytes, layer: Layer, canvas: tuple[int, int], source: bytes | None = None) -> bytes:
    luma = to_luma(mask, canvas)
    buffer = io.BytesIO()
    luma.save(buffer, "PNG")
    alpha = Image.open(io.BytesIO(source)).convert("RGBA").getchannel("A") if source is not None else None
    local = canvas_to_local(buffer.getvalue(), layer, mask=True, output_size=alpha.size if alpha is not None else None)
    if alpha is not None:
        local = ImageChops.multiply(local, alpha)
    if local.getbbox() is None:
        raise EmptyCut("选区与图层真实像素没有交集")
    return overlay_png(local)


def layer_under_mask(document: LayerDocument, mask: bytes, images: dict[uuid.UUID, bytes]) -> Layer:
    for layer in reversed(document.layers):
        if layer.kind is not LayerKind.IMAGE or not layer.visible or layer.opacity <= 0 or layer.asset_id not in images:
            continue
        try:
            mask_for_layer(mask, layer, (document.width, document.height), images[layer.asset_id])
        except EmptyCut:
            continue
        return layer
    raise EmptyCut("选区没有覆盖到任何可见图像像素")
