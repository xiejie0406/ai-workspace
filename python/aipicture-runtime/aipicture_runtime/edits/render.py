import io
import os
import unicodedata
import uuid
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

from aipicture_runtime.layers import Layer, LayerDocument, LayerKind

WHITE = (255, 255, 255, 255)
TRANSPARENT = (0, 0, 0, 0)

_FONT_PATHS = (
    "C:/Windows/Fonts/msyh.ttc",
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "/System/Library/Fonts/STHeiti Light.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
)
_font_file: str | None = None


class FontRenderingUnavailable(RuntimeError):
    """字体配置或字形不足，不能以缺字方框冒充文字导出。"""


def flatten(
    document: LayerDocument,
    images: dict[uuid.UUID, bytes],
    *,
    background: tuple[int, int, int, int] = WHITE,
    include_text: bool = True,
) -> bytes:
    """按文档合成一张 PNG。旋转绕图层中心，与画布渲染一致。

    include_text 为 false 时跳过文字层，供成层等仍保留矢量字的像素操作使用。
    """
    canvas = Image.new("RGBA", (document.width, document.height), background)
    for layer in document.layers:
        if not layer.visible:
            continue
        if layer.kind is LayerKind.TEXT:
            if include_text:
                _composite_text(canvas, layer)
            continue
        if layer.kind is not LayerKind.IMAGE or layer.asset_id is None:
            continue
        raw = images.get(layer.asset_id)
        if raw is None:
            continue
        source = Image.open(io.BytesIO(raw)).convert("RGBA")
        source = source.resize((layer.width, layer.height), Image.Resampling.LANCZOS)
        _place(canvas, layer, source)

    buffer = io.BytesIO()
    canvas.save(buffer, format="PNG")
    return buffer.getvalue()


def _composite_text(canvas: Image.Image, layer: Layer) -> None:
    raw = layer.text if layer.text is not None else layer.name
    content = raw or ""
    if not content.strip():
        return
    width = max(1, layer.width)
    height = max(1, layer.height)
    source = Image.new("RGBA", (width, height), TRANSPARENT)
    draw = ImageDraw.Draw(source)
    font = _font(layer.font_size)
    _check_glyphs(font, content)
    color = _rgba(layer.fill)
    lines = _text_lines(content, width, height, font, layer.font_size)
    ascent, descent = font.getmetrics()
    # Konva 10.3.1非legacy模式：固定lineHeight=1，按字体ascender/descender定位alphabetic基线。
    baseline = (height - len(lines) * layer.font_size) / 2 + (ascent - descent + layer.font_size) / 2
    for line in lines:
        x = (width - font.getlength(line)) / 2
        draw.text((x, baseline), line, font=font, fill=color, anchor="ls")
        baseline += layer.font_size
    _place(canvas, layer, source)


def _characters(text: str) -> list[str]:
    """保留组合附加符，避免换行把同一字的重音拆开。"""
    result = []
    for char in text:
        if result and unicodedata.category(char) in {"Mn", "Me", "Mc"}:
            result[-1] += char
        else:
            result.append(char)
    return result


def _text_lines(content: str, width: float, height: float, font, size: float) -> list[str]:
    """移植锁定Konva Text._setTextData已使用的word-wrap、lineHeight=1和高度截断。"""
    lines = []
    for paragraph in content.split("\n"):
        remaining = paragraph
        if font.getlength(remaining) <= width:
            lines.append(remaining)
        else:
            while remaining:
                chars = _characters(remaining)
                low, high = 0, len(chars)
                while low < high:
                    middle = (low + high) // 2
                    if font.getlength("".join(chars[:middle + 1])) <= width:
                        low = middle + 1
                    else:
                        high = middle
                if not low:
                    break
                match = "".join(chars[:low])
                next_char = chars[low] if low < len(chars) else None
                if next_char not in {" ", "-"}:
                    boundary = max(match.rfind(" "), match.rfind("-")) + 1
                    if boundary:
                        low = len(_characters(match[:boundary]))
                        match = "".join(chars[:low])
                lines.append(match.rstrip())
                if (len(lines) + 1) * size > height:
                    return lines
                remaining = "".join(chars[low:]).lstrip()
                if remaining and font.getlength(remaining) <= width:
                    lines.append(remaining)
                    break
        if (len(lines) + 1) * size > height:
            break
    return lines


def _check_glyphs(font, content: str) -> None:
    path = getattr(font, "path", None)
    if isinstance(path, (str, bytes, os.PathLike)) and os.path.isfile(path):
        resolved = Path(os.fsdecode(path)).resolve()
        stat = resolved.stat()
        key = (str(resolved), stat.st_size, stat.st_mtime_ns)
    else:
        key = ("", 0, 0)
    for char in set(content):
        if char.isspace() or unicodedata.category(char) in {"Cc", "Cf"}:
            continue
        if not _glyph_supported(key, char):
            raise FontRenderingUnavailable(f"当前字体不包含字形 U+{ord(char):04X}，请配置覆盖该文字的字体")


@lru_cache(maxsize=8)
def _probe_font(key: tuple[str, int, int]):
    # 探针始终使用32px；缓存仅保留小字号字体实例，不持有400px等实际文字位图。
    return ImageFont.truetype(key[0], 32) if key[0] else ImageFont.load_default(size=32)


@lru_cache(maxsize=2048)
def _glyph_supported(key: tuple[str, int, int], char: str) -> bool:
    font = _probe_font(key)
    missing, glyph = font.getmask("\U0010ffff"), font.getmask(char)
    return (glyph.size, bytes(glyph)) != (missing.size, bytes(missing))


def _place(canvas: Image.Image, layer: Layer, source: Image.Image) -> None:
    transform = layer.transform
    if transform.scale_x < 0:
        source = ImageOps.mirror(source)
    if transform.scale_y < 0:
        source = ImageOps.flip(source)

    width = max(1, int(round(layer.width * abs(transform.scale_x))))
    height = max(1, int(round(layer.height * abs(transform.scale_y))))
    if source.size != (width, height):
        source = source.resize((width, height), Image.Resampling.LANCZOS)

    if layer.opacity < 1:
        alpha = source.getchannel("A").point(lambda value: int(value * layer.opacity))
        source.putalpha(alpha)

    if transform.rotation:
        # Pillow 逆时针为正，画布旋转顺时针为正
        source = source.rotate(-transform.rotation, expand=True, resample=Image.Resampling.BICUBIC)

    center_x = transform.x + layer.width / 2
    center_y = transform.y + layer.height / 2
    left = int(round(center_x - source.width / 2))
    top = int(round(center_y - source.height / 2))
    _paste(canvas, source, left, top)


def _paste(canvas: Image.Image, source: Image.Image, left: int, top: int) -> None:
    src_x, src_y = max(0, -left), max(0, -top)
    dst_x, dst_y = max(0, left), max(0, top)
    width = min(source.width - src_x, canvas.width - dst_x)
    height = min(source.height - src_y, canvas.height - dst_y)
    if width <= 0 or height <= 0:
        return
    piece = source.crop((src_x, src_y, src_x + width, src_y + height))
    canvas.alpha_composite(piece, (dst_x, dst_y))


def _font(size: float) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    path = _font_path()
    px = max(8, float(size))
    if path:
        try:
            return ImageFont.truetype(path, px)
        except OSError as exc:
            raise FontRenderingUnavailable("配置字体无法加载，请配置有效的中文字体文件") from exc
    try:
        return ImageFont.load_default(size=px)
    except TypeError:
        return ImageFont.load_default()


def _font_path() -> str:
    global _font_file
    from aipicture_runtime.config import get_settings
    configured = get_settings().font_path
    if configured:
        if not configured.is_file():
            raise FontRenderingUnavailable("显式配置的字体文件不存在，请核对 AIPICTURE_FONT_PATH")
        return str(configured)
    if _font_file is not None:
        return _font_file
    for path in _FONT_PATHS:
        if os.path.exists(path):
            _font_file = path
            return path
    _font_file = ""
    return ""


def _rgba(fill: str) -> tuple[int, int, int, int]:
    color = fill.lstrip("#")
    if len(color) == 3:
        color = "".join(ch * 2 for ch in color)
    if len(color) != 6:
        return (20, 20, 20, 255)
    return (int(color[0:2], 16), int(color[2:4], 16), int(color[4:6], 16), 255)
