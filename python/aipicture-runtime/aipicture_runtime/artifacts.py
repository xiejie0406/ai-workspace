"""瞬时输入/输出字节校验；读地址只能是已配置中心内部资产或批准的 HTTPS 域。"""
from __future__ import annotations

import base64
import hashlib
import io
from urllib.parse import parse_qs, urlsplit

import httpx
from PIL import Image, UnidentifiedImageError

from aipicture_runtime.config import get_settings
from aipicture_runtime.dto import AssetRef
from aipicture_runtime.security import sign_headers


class ImageRejected(ValueError):
    """已知图片输入拒绝；消息只来自固定校验文案，不转发解码器或原输入。"""

    error_code = "INVALID_IMAGE"


def probe(data: bytes) -> dict:
    settings = get_settings()
    if not data or len(data) > settings.max_asset_bytes:
        raise ImageRejected("素材为空或超过 20 MB")
    try:
        with Image.open(io.BytesIO(data)) as image:
            if image.format not in {"PNG", "JPEG", "WEBP"} or image.width * image.height > settings.max_pixels:
                raise ImageRejected("只允许 JPG/PNG/WebP 且不超过 5000 万像素")
            image.load()
            return {"width": image.width, "height": image.height, "format": image.format,
                    "size_bytes": len(data), "has_alpha": "A" in image.getbands() or "transparency" in image.info,
                    "sha256": hashlib.sha256(data).hexdigest()}
    except Image.DecompressionBombError as exc:
        raise ImageRejected("图片像素总量过大，存在解压炸弹风险") from exc
    except (UnidentifiedImageError, OSError) as exc:
        raise ImageRejected("图片已损坏或不是有效 JPG/PNG/WebP") from exc


def checked_center_path(url: str, asset_id: object, request=None) -> str:
    settings = get_settings()
    target, center = urlsplit(url), urlsplit(settings.center_url)
    expected = center.path.rstrip("/") + f"/aipicture/internal/v1/execution/assets/{asset_id}"
    if target.scheme not in {"https", "http"} or target.scheme != center.scheme or target.netloc != center.netloc or target.path != expected:
        raise ValueError("素材读地址须为批准中心的指定内部资产接口")
    if target.fragment or target.username or target.password:
        raise ValueError("素材读地址不能附加用户凭据或片段")
    if target.query:
        query = parse_qs(target.query, strict_parsing=True)
        expected_query = {"run_id": [request.run_id], "attempt": [str(request.attempt)], "lease_token": [request.lease_token]} if request else {}
        if query != expected_query:
            raise ValueError("素材地址必须绑定本次 run/attempt/lease")
    return target.path


async def read_asset(asset: AssetRef, request=None) -> bytes:
    settings = get_settings()
    if asset.content_base64 is not None:
        if len(asset.content_base64) > ((settings.max_asset_bytes + 2) // 3) * 4:
            raise ValueError("内联素材超过大小限制")
        data = base64.b64decode(asset.content_base64, validate=True)
    else:
        path = checked_center_path(asset.read_url or "", asset.id, request)
        headers = sign_headers(settings.service_secret, settings.service_name, "GET", path, b"")
        async with httpx.AsyncClient(timeout=60, follow_redirects=False, trust_env=False) as client:
            async with client.stream("GET", asset.read_url, headers=headers) as response:
                response.raise_for_status()
                chunks, count = [], 0
                async for part in response.aiter_bytes():
                    count += len(part)
                    if count > settings.max_asset_bytes:
                        raise ValueError("素材下载超过大小限制")
                    chunks.append(part)
                data = b"".join(chunks)
    info = probe(data)
    if info["sha256"] != asset.sha256 or (info["width"], info["height"]) != (asset.width, asset.height):
        raise ValueError("素材 hash/尺寸与冻结引用不一致")
    return data


async def read_provider_result(url: str) -> bytes:
    settings = get_settings()
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in settings.provider_output_hosts or parsed.username or parsed.password or parsed.port not in {None, 443}:
        raise ValueError("模型输出地址不是已批准的 HTTPS 域名")
    async with httpx.AsyncClient(timeout=60, follow_redirects=False, trust_env=False) as client:
        async with client.stream("GET", url) as response:
            response.raise_for_status()
            count, chunks = 0, []
            async for part in response.aiter_bytes():
                count += len(part)
                if count > settings.max_asset_bytes:
                    raise ValueError("模型输出超过大小限制")
                chunks.append(part)
            data = b"".join(chunks)
    probe(data)
    return data
