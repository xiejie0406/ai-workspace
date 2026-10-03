"""解析、布局和出站资源的确定性预算，不静默裁掉用户内容。"""
import asyncio
import ipaddress
import socket
from urllib.parse import urlsplit
import httpx
from app.core.config import get_settings


class BudgetExceeded(ValueError):
    pass


async def assert_public_url(url: str, allowed_hosts: list[str] | None = None) -> None:
    parts = urlsplit(url)
    host = (parts.hostname or "").lower()
    if parts.scheme != "https" or not host or parts.username or parts.password or parts.fragment:
        raise ValueError("图源必须为不含凭据的 HTTPS 地址")
    if parts.port not in (None, 443):
        raise ValueError("图源端口不允许")
    if allowed_hosts and host not in {h.lower() for h in allowed_hosts}:
        raise ValueError("图源域名不在批准出站清单")
    try:
        records = await asyncio.get_running_loop().getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except OSError as error:
        raise ValueError("图源域名无法解析") from error
    if not records or any(not ipaddress.ip_address(record[4][0]).is_global for record in records):
        raise ValueError("禁止访问内网、环回及保留地址图源")


class SafeAsyncClient(httpx.AsyncClient):
    """拒绝重定向并在流式读取时限制字节，Provider 凭据不跟随其他主机。"""
    def __init__(self, *, max_bytes: int | None = None, allowed_hosts: list[str] | None = None, **kwargs):
        self.max_bytes = max_bytes or get_settings().max_image_bytes * 2
        self.allowed_hosts = allowed_hosts if allowed_hosts is not None else get_settings().outbound_allowed_hosts
        from app.worker.transport import PublicTransport
        kwargs.setdefault("transport", PublicTransport())
        super().__init__(follow_redirects=False, trust_env=False, **kwargs)

    async def send(self, request, *, stream=False, **kwargs):
        await assert_public_url(str(request.url), self.allowed_hosts)
        response = await super().send(request, stream=True, **kwargs)
        try:
            if 300 <= response.status_code < 400:
                raise ValueError("图源重定向被拒绝，请批准最终域名后配置")
            if int(response.headers.get("content-length", "0")) > self.max_bytes:
                raise BudgetExceeded("图源响应超过体积上限")
            chunks = []; size = 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > self.max_bytes:
                    raise BudgetExceeded("图源响应超过体积上限")
                chunks.append(chunk)
            return httpx.Response(response.status_code, headers=response.headers, content=b"".join(chunks), request=request)
        finally:
            await response.aclose()


def assert_payload_budget(payload: dict) -> None:
    import json
    cfg = get_settings()
    if len(json.dumps(payload, ensure_ascii=False, allow_nan=False).encode()) > cfg.max_payload_bytes:
        raise BudgetExceeded("任务输入超过体积上限")
    def check_tree(tree):
        nodes = 0
        def walk(value, depth):
            nonlocal nodes
            if not isinstance(value, dict): raise ValueError("布局树节点不是对象")
            nodes += 1
            if depth > cfg.max_tree_depth or nodes > cfg.max_tree_nodes:
                raise BudgetExceeded("布局树超过深度或节点上限")
            for child in value.get("children", []): walk(child, depth + 1)
        walk(tree, 0)
    def visit(value):
        if isinstance(value, dict):
            if value.get("layout_tree") is not None: check_tree(value["layout_tree"])
            if isinstance(value.get("blocks"), list) and len(value["blocks"]) > cfg.max_blocks_per_slide:
                raise BudgetExceeded("单页内容块超过上限")
            for child in value.values(): visit(child)
        elif isinstance(value, list):
            for child in value: visit(child)
    visit(payload)
