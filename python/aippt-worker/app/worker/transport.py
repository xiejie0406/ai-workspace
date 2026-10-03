"""图源DNS解析与实际连接绑定同一公网IP，TLS仍校验原域名。"""
import asyncio
import ipaddress
import socket
import ssl
import certifi
import httpcore
import httpx
from httpcore._backends.auto import AutoBackend


class PublicNetworkBackend(httpcore.AsyncNetworkBackend):
    def __init__(self, delegate=None): self.delegate = delegate or AutoBackend()

    async def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        if port != 443: raise httpcore.ConnectError("图源连接端口不允许")
        try:
            records = await asyncio.wait_for(asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM), timeout=timeout or 30)
        except (OSError, TimeoutError) as error: raise httpcore.ConnectError("图源DNS解析失败") from error
        addresses = list(dict.fromkeys(record[4][0] for record in records))
        if not addresses or any(not ipaddress.ip_address(address).is_global for address in addresses):
            raise httpcore.ConnectError("图源连接地址不是公网")
        error = None
        for address in addresses:
            try:
                # 传递数字地址，后端没有第二次域名解析；httpcore仍用原host握手SNI。
                return await self.delegate.connect_tcp(address, port, timeout=timeout, local_address=local_address, socket_options=socket_options)
            except (httpcore.ConnectError, httpcore.ConnectTimeout) as current: error = current
        raise error or httpcore.ConnectError("图源连接失败")

    async def connect_unix_socket(self, *args, **kwargs): raise httpcore.ConnectError("禁止图源Unix套接字")
    async def sleep(self, seconds): await asyncio.sleep(seconds)


class ResponseStream(httpx.AsyncByteStream):
    def __init__(self, stream): self.stream = stream
    async def __aiter__(self):
        try:
            async for part in self.stream: yield part
        except Exception as error: raise httpx.TransportError("图源读取失败") from error
    async def aclose(self): await self.stream.aclose()


class PublicTransport(httpx.AsyncBaseTransport):
    def __init__(self):
        self.pool = httpcore.AsyncConnectionPool(ssl_context=ssl.create_default_context(cafile=certifi.where()),
                     max_connections=10, max_keepalive_connections=5, network_backend=PublicNetworkBackend(), retries=0)

    async def handle_async_request(self, request):
        core_request = httpcore.Request(method=request.method, url=httpcore.URL(scheme=request.url.raw_scheme,
                         host=request.url.raw_host, port=request.url.port, target=request.url.raw_path),
                         headers=request.headers.raw, content=request.stream, extensions=request.extensions)
        try: response = await self.pool.handle_async_request(core_request)
        except Exception as error: raise httpx.TransportError("图源连接失败") from error
        return httpx.Response(response.status, headers=response.headers, stream=ResponseStream(response.stream), extensions=response.extensions)

    async def aclose(self): await self.pool.aclose()
