"""已批准的现有文字层布局与缺字拒绝；使用本机字体，不下载或分发字体。"""
import io
from pathlib import Path

import pytest
from PIL import Image

from aipicture_runtime.capabilities import capabilities
from aipicture_runtime.config import get_settings
from aipicture_runtime.edits import render
from aipicture_runtime.layers import Layer, LayerDocument, LayerKind, Transform


@pytest.fixture(autouse=True)
def reset_font(monkeypatch):
    monkeypatch.setattr(render, "_font_file", None)
    render._probe_font.cache_clear()
    render._glyph_supported.cache_clear()


@pytest.fixture
def chinese_font():
    path = next((Path(path) for path in render._FONT_PATHS if Path(path).is_file()), None)
    if path is None:
        pytest.skip("本机无已有中文字体，不下载字体")
    get_settings().font_path = path
    return render._font(32)


def document(text="中文价格优惠今日购", width=96, height=128, size=32, **kwargs):
    return LayerDocument(width=320, height=320, layers=[Layer(id="text", kind=LayerKind.TEXT,
        name="文字", text=text, width=width, height=height, font_size=size, **kwargs)])


def pixels(doc, **kwargs):
    return Image.open(io.BytesIO(render.flatten(doc, {}, **kwargs)))


@pytest.mark.parametrize("height,expected", [(128, ["中文价", "格优惠", "今日购"]), (32, ["中文价"])])
def test_chinese_wrap_and_height_keep_first_lines(chinese_font, height, expected):
    assert render._text_lines("中文价格优惠今日购", 96, height, chinese_font, 32) == expected
    image = pixels(document(height=height), background=render.TRANSPARENT)
    # 每行有真实字形，32px框只保留第一行；不再以完整九字单行横向居中裁切。
    if height == 128:
        box = image.getchannel("A").getbbox()
        assert box[3] - box[1] >= 90
    else:
        first_line = pixels(document("中文价", height=32), background=render.TRANSPARENT)
        assert image.tobytes() == first_line.tobytes()


@pytest.mark.parametrize("content,width,expected", [
    ("  A B  \n\nC", 144, ["  A B  ", "", "C"]),
    ("中文价\n\n格优惠", 96, ["中文价", "", "格优惠"]),
    ("AB CD EF", 96, ["AB", "CD EF"]),
    ("AB-CD-EF", 96, ["AB-", "CD-EF"]),
])
def test_explicit_newline_spaces_and_word_or_dash_wrap(chinese_font, content, width, expected):
    assert render._text_lines(content, width, 320, chinese_font, 32) == expected


def test_leading_space_remains_layout_content(chinese_font):
    plain = render.flatten(document("A", width=100, height=50), {}, background=render.TRANSPARENT)
    spaced = render.flatten(document(" A", width=100, height=50), {}, background=render.TRANSPARENT)
    assert spaced != plain


@pytest.mark.parametrize("kind", ["missing", "corrupt", "latin"])
def test_explicit_bad_or_non_chinese_font_is_rejected_and_capability_isolated(tmp_path, monkeypatch, kind):
    path = tmp_path / "font.ttf"
    if kind == "corrupt":
        path.write_bytes(b"not-a-font")
    elif kind == "latin":
        path = Path("C:/Windows/Fonts/arial.ttf")
        if not path.is_file():
            pytest.skip("本机未安装Arial，不下载字体")
    get_settings().font_path = path
    with pytest.raises(render.FontRenderingUnavailable):
        pixels(document())
    result = capabilities()["capabilities"]
    assert result["text_rendering"]["ready"] is False and result["text_rendering"]["error"]
    assert result["deterministic_pixels"]["ready"] is True
    if kind == "latin":
        assert result["text_rendering"]["loaded"] is True


@pytest.mark.parametrize("visible,text", [(False, "中文"), (True, ""), (True, "  \n ")])
def test_hidden_or_blank_text_does_not_require_font(tmp_path, visible, text):
    get_settings().font_path = tmp_path / "missing.ttf"
    image = pixels(document(text, visible=visible), background=render.TRANSPARENT)
    assert image.getchannel("A").getbbox() is None
    assert pixels(LayerDocument(width=32, height=32, layers=[])).getpixel((0, 0)) == render.WHITE


def test_missing_system_font_rejects_chinese_but_keeps_ascii(monkeypatch):
    get_settings().font_path = None
    monkeypatch.setattr(render, "_FONT_PATHS", ())
    with pytest.raises(render.FontRenderingUnavailable):
        pixels(document("中文"))
    assert pixels(document("ABC"), background=render.TRANSPARENT).getchannel("A").getbbox()


def test_font_capability_uses_real_family_and_fingerprint(chinese_font):
    import hashlib
    path = get_settings().font_path
    result = capabilities()["capabilities"]["text_rendering"]
    assert result["ready"] and result["loaded"] and result["family"] == chinese_font.getname()[0]
    assert result["font"]["sha256"] == hashlib.sha256(path.read_bytes()).hexdigest()
    assert result["font"]["size_bytes"] == path.stat().st_size
    assert result["validation"] == "font_load_and_basic_glyph_probe"


def test_rgb_alpha_transforms_and_document_preserved(chinese_font):
    doc = document("中文", width=96, height=64, fill="#12abcd", opacity=0.5,
                   transform=Transform(x=10, y=20, scale_x=-1, scale_y=1, rotation=0))
    frozen = doc.model_dump(mode="json")
    transparent = pixels(doc, background=render.TRANSPARENT)
    assert max(transparent.getchannel("A").getdata()) == 127
    opaque_ink = next(pixel for pixel in transparent.getdata() if pixel[3] == 127)
    assert opaque_ink == (18, 171, 205, 127)
    assert set(pixels(doc).getchannel("A").getdata()) == {255}
    assert doc.model_dump(mode="json") == frozen


def test_support_probe_is_fixed_small_size_and_boolean_bounded_cache(chinese_font, monkeypatch):
    large_font = render._font(400)
    original = render.ImageFont.truetype
    sizes = []
    def tracked(path, size, *args, **kwargs):
        sizes.append(size)
        return original(path, size, *args, **kwargs)
    monkeypatch.setattr(render.ImageFont, "truetype", tracked)
    render._check_glyphs(large_font, "中文" * 250)
    render._check_glyphs(large_font, "中文" * 250)
    assert sizes == [32]
    info = render._glyph_supported.cache_info()
    assert info.maxsize == 2048 and info.currsize == 2 and info.hits == 2


def test_fractional_font_size_keeps_original_field(chinese_font):
    doc = document("中文", width=96, height=64, size=32.5)
    assert render._font(doc.layers[0].font_size).size == 32.5
    assert pixels(doc, background=render.TRANSPARENT).getchannel("A").getbbox()
