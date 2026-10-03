import base64

import json

import struct

import uuid

import zlib

from collections.abc import AsyncGenerator

import httpx

import pytest

from pptx import Presentation

from pptx.enum.shapes import MSO_SHAPE_TYPE

from app.core.config import get_settings

from app.domain.content import Deck, ImageBlock, Slide, TextBlock

from app.images.bailian import BailianImageProvider, _closest_size, _resolve_base_url

from app.images.base import ImageAsset, ImageRequest

from app.images.generated import GeneratedImageProvider

from app.images.pipeline import ImagePipeline

from app.images.unsplash import UnsplashImageProvider, sanitize_unsplash_query

from app.images.validate import ImageRejected, validate_image

from app.render.pptx import render_deck_to_pptx

def _chunk(tag: bytes, data: bytes) -> bytes:
    crc = struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return struct.pack(">I", len(data)) + tag + data + crc

def minimal_png(*, width: int = 2, height: int = 2) -> bytes:
    """手工构造最小合法 PNG，避免测试依赖外部文件。"""
    signature = b"\x89PNG\r\n\x1a\n"
    ihdr = _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    raw = b"".join(b"\x00" + b"\xff\x00\x00" * width for _ in range(height))
    idat = _chunk(b"IDAT", zlib.compress(raw))
    iend = _chunk(b"IEND", b"")
    return signature + ihdr + idat + iend

MINIMAL_PNG = minimal_png()

from io import BytesIO
from PIL import Image
def _real_image(fmt):
    stream=BytesIO(); Image.new("RGB", (2,2), "red").save(stream, format=fmt); return stream.getvalue()
MINIMAL_JPEG = _real_image("JPEG")

MINIMAL_WEBP = _real_image("WEBP")

def test_validate_image_rejects_text_and_oversized(monkeypatch: pytest.MonkeyPatch) -> None:
    with pytest.raises(ImageRejected, match="不支持"):
        validate_image(b"plain text bytes")

    settings = get_settings()
    monkeypatch.setattr(settings, "max_image_mb", 0)
    with pytest.raises(ImageRejected, match="上限"):
        validate_image(MINIMAL_PNG)

class _FakeProvider:
    def __init__(
        self,
        *,
        source: str,
        available: bool = True,
        asset: ImageAsset | None = None,
    ) -> None:
        self.source = source  # type: ignore[assignment]
        self._available = available
        self._asset = asset
        self.calls = 0

    def available(self) -> bool:
        return self._available

    async def fetch(self, request: ImageRequest) -> ImageAsset | None:
        self.calls += 1
        return self._asset

@pytest.mark.asyncio
async def test_pipeline_prefers_generated_then_stock() -> None:
    generated = _FakeProvider(
        source="generated",
        asset=ImageAsset(data=MINIMAL_PNG, content_type="image/png", source="generated"),
    )
    stock = _FakeProvider(
        source="stock",
        asset=ImageAsset(data=MINIMAL_JPEG, content_type="image/jpeg", source="stock"),
    )
    pipeline = ImagePipeline([generated, stock])  # type: ignore[arg-type]
    asset = await pipeline.fetch(ImageRequest(prompt="p", query="q", aspect_ratio=1.5))
    assert asset is not None
    assert asset.source == "generated"
    assert stock.calls == 0

@pytest.mark.asyncio
async def test_pipeline_falls_back_when_generated_unavailable_or_none() -> None:
    stock_asset = ImageAsset(
        data=MINIMAL_JPEG,
        content_type="image/jpeg",
        source="stock",
        credit="Photo by Ada on Unsplash",
    )
    unavailable = _FakeProvider(source="generated", available=False)
    returns_none = _FakeProvider(source="generated", asset=None)
    stock = _FakeProvider(source="stock", asset=stock_asset)

    via_unavailable = await ImagePipeline([unavailable, stock]).fetch(  # type: ignore[arg-type]
        ImageRequest(prompt="p", query="q", aspect_ratio=1.0)
    )
    assert via_unavailable is not None and via_unavailable.source == "stock"

    stock.calls = 0
    via_none = await ImagePipeline([returns_none, stock]).fetch(  # type: ignore[arg-type]
        ImageRequest(prompt="p", query="q", aspect_ratio=1.0)
    )
    assert via_none is not None and via_none.source == "stock"

@pytest.mark.asyncio
async def test_pipeline_returns_none_when_all_unavailable() -> None:
    pipeline = ImagePipeline(
        [
            _FakeProvider(source="generated", available=False),  # type: ignore[list-item]
            _FakeProvider(source="stock", available=False),  # type: ignore[list-item]
        ]
    )
    assert await pipeline.fetch(ImageRequest(prompt="p", query="q", aspect_ratio=1.0)) is None

@pytest.mark.asyncio
async def test_generated_provider_reads_b64_and_url() -> None:
    png = MINIMAL_PNG
    generation_calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal generation_calls
        if request.url.path.endswith("/images/generations"):
            generation_calls += 1
            if generation_calls == 1:
                return httpx.Response(
                    200,
                    json={"data": [{"b64_json": base64.b64encode(png).decode()}]},
                )
            return httpx.Response(
                200,
                json={"data": [{"url": "https://cdn.example.com/gen.png"}]},
            )
        if "cdn.example.com" in str(request.url):
            return httpx.Response(200, content=png)
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        provider = GeneratedImageProvider(
            client=client,
            base_url="https://api.example.com/v1",
            api_key="test-key",
            model="gpt-image-1",
        )
        first = await provider.fetch(ImageRequest(prompt="a", query="a", aspect_ratio=1.5))
        second = await provider.fetch(ImageRequest(prompt="b", query="b", aspect_ratio=1.0))

    assert first is not None and first.data == png and first.source == "generated"
    assert second is not None and second.data == png

@pytest.mark.asyncio
async def test_generated_provider_http_500_returns_none() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        provider = GeneratedImageProvider(
            client=client,
            base_url="https://api.example.com/v1",
            api_key="test-key",
            model="gpt-image-1",
        )
        result = await provider.fetch(ImageRequest(prompt="a", query="a", aspect_ratio=1.0))
    assert result is None

def test_bailian_size_and_workspace_base_url() -> None:
    assert _closest_size(1.0) == "1328*1328"
    assert _closest_size(1.5) == "1664*928"
    assert _closest_size(0.67) == "928*1664"
    assert (
        _resolve_base_url("https://dashscope.aliyuncs.com/api/v1", "ws-demo")
        == "https://ws-demo.cn-beijing.maas.aliyuncs.com/api/v1"
    )

@pytest.mark.asyncio
async def test_bailian_provider_posts_native_payload_and_downloads_image() -> None:
    png = MINIMAL_PNG
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/services/aigc/multimodal-generation/generation"):
            captured["auth"] = request.headers.get("Authorization")
            captured["json"] = json.loads(request.content.decode())
            return httpx.Response(
                200,
                json={
                    "output": {
                        "choices": [
                            {
                                "finish_reason": "stop",
                                "message": {
                                    "role": "assistant",
                                    "content": [
                                        {"image": "https://cdn.example.com/bailian.png"}
                                    ],
                                },
                            }
                        ]
                    },
                    "request_id": "req-1",
                },
            )
        if "cdn.example.com" in str(request.url):
            return httpx.Response(200, content=png)
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        provider = BailianImageProvider(
            client=client,
            api_key="sk-test",
            model="qwen-image-3.0",
            base_url="https://dashscope.aliyuncs.com/api/v1",
        )
        asset = await provider.fetch(
            ImageRequest(prompt="商务配图，无文字", query="business", aspect_ratio=1.5)
        )

    assert asset is not None and asset.data == png and asset.source == "generated"
    assert captured["auth"] == "Bearer sk-test"
    body = captured["json"]
    assert isinstance(body, dict)
    assert body["model"] == "qwen-image-3.0"
    assert body["input"]["messages"][0]["content"][0]["text"] == "商务配图，无文字"
    assert body["parameters"]["size"] == "1664*928"
    assert body["parameters"]["n"] == 1
    assert body["parameters"]["watermark"] is False
    assert body["parameters"]["prompt_extend"] is True

@pytest.mark.asyncio
async def test_bailian_provider_business_error_returns_none() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"code": "InvalidApiKey", "message": "Invalid API-key provided."},
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        provider = BailianImageProvider(
            client=client,
            api_key="bad",
            model="qwen-image-3.0",
        )
        result = await provider.fetch(ImageRequest(prompt="a", query="a", aspect_ratio=1.0))
    assert result is None

@pytest.mark.asyncio
async def test_bailian_provider_http_500_returns_none() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        provider = BailianImageProvider(
            client=client,
            api_key="sk-test",
            model="qwen-image-3.0",
        )
        result = await provider.fetch(ImageRequest(prompt="a", query="a", aspect_ratio=1.0))
    assert result is None

@pytest.mark.asyncio
async def test_unsplash_parses_image_credit_and_tracks_download() -> None:
    tracked: list[str] = []
    png = MINIMAL_PNG

    def handler(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        if "/search/photos" in url:
            return httpx.Response(
                200,
                json={
                    "results": [
                        {
                            "urls": {"regular": "https://images.unsplash.com/photo.jpg"},
                            "links": {
                                "download_location": "https://api.unsplash.com/photos/x/download"
                            },
                            "user": {"name": "Ada Lovelace"},
                        }
                    ]
                },
            )
        if "images.unsplash.com" in url:
            return httpx.Response(200, content=png, headers={"content-type": "image/png"})
        if "/download" in url:
            tracked.append(url)
            return httpx.Response(200, json={"url": "https://images.unsplash.com/photo.jpg"})
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        provider = UnsplashImageProvider(client=client, access_key="unsplash-key")
        asset = await provider.fetch(ImageRequest(prompt="p", query="workflow", aspect_ratio=1.6))

    assert asset is not None
    assert asset.data == png
    assert asset.source == "stock"
    assert asset.credit == "Photo by Ada Lovelace on Unsplash"
    assert tracked and "download" in tracked[0]

@pytest.mark.asyncio
async def test_unsplash_empty_results_returns_none() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"results": []})

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        provider = UnsplashImageProvider(client=client, access_key="unsplash-key")
        assert await provider.fetch(ImageRequest(prompt="p", query="q", aspect_ratio=1.0)) is None

def test_sanitize_unsplash_query_strips_fullwidth_colon() -> None:
    cleaned = sanitize_unsplash_query(
        "抽象几何图形：三条交错的路径分别代表认知、行为与环境，寓意三者协同驱动成长"
    )
    assert "：" not in cleaned
    assert ":" not in cleaned
    assert "抽象几何图形" in cleaned
    assert " " in cleaned

@pytest.mark.asyncio
async def test_unsplash_retries_after_content_removed() -> None:
    """首个 query 若仍被 410，应继续尝试短候选。"""
    seen: list[str] = []
    png = MINIMAL_PNG

    def handler(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        if "/search/photos" in url:
            query = request.url.params.get("query", "")
            seen.append(query)
            if len(seen) == 1:
                return httpx.Response(410, json={"errors": ["Content removed"]})
            return httpx.Response(
                200,
                json={
                    "results": [
                        {
                            "urls": {"regular": "https://images.unsplash.com/photo.jpg"},
                            "links": {},
                            "user": {"name": "Ada"},
                        }
                    ]
                },
            )
        if "images.unsplash.com" in url:
            return httpx.Response(200, content=png, headers={"content-type": "image/png"})
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        provider = UnsplashImageProvider(client=client, access_key="unsplash-key")
        asset = await provider.fetch(
            ImageRequest(
                prompt="p",
                query="抽象几何图形：三条交错的路径分别代表认知、行为与环境",
                aspect_ratio=1.6,
            )
        )

    assert asset is not None
    assert asset.source == "stock"
    assert len(seen) >= 2
    assert all("：" not in q for q in seen)
