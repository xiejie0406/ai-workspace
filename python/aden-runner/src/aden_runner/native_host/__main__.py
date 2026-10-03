"""有界 stdio / Windows 命名管道 Native Messaging Host。"""

import base64
import ctypes
import json
import os
import re
import struct
import sys
from datetime import datetime
from typing import BinaryIO

MAX_MESSAGE = 256 * 1024
EXTENSION_ORIGIN = "chrome-extension://mnejmmlalapfhnanlnckfdhmfpbahidm/"
TYPES = frozenset(
    {
        "hello",
        "capture.lookup",
        "capture.prepare",
        "capture.commit",
        "asset.chunk",
        "capture.status",
        "library.open",
    }
)


def read_exact(stream: BinaryIO, size: int) -> bytes:
    parts = bytearray()
    while len(parts) < size:
        value = stream.read(size - len(parts))
        if not value:
            if not parts:
                raise EOFError
            raise ValueError("消息被截断")
        parts.extend(value)
    return bytes(parts)


def read_message(stream: BinaryIO) -> dict:
    length = struct.unpack("<I", read_exact(stream, 4))[0]
    if length == 0 or length > MAX_MESSAGE:
        raise ValueError("消息长度超限")
    value = json.loads(read_exact(stream, length).decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("消息必须是对象")
    return value


def write_message(stream: BinaryIO, value: dict) -> None:
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode(
        "utf-8"
    )
    if len(raw) > MAX_MESSAGE:
        raise ValueError("响应消息长度超限")
    stream.write(struct.pack("<I", len(raw)) + raw)
    stream.flush()


def validate_message(value: dict) -> None:
    if set(value) - {"protocolVersion", "messageId", "type", "payload", "nonce", "sequence", "expiresAt"}:
        raise ValueError("消息包含不支持的字段")
    if value.get("protocolVersion") != 1 or value.get("type") not in TYPES:
        raise ValueError("不支持的协议版本或消息类型")
    if not isinstance(value.get("messageId"), str) or not re.fullmatch(
        r"[a-zA-Z0-9_-]{1,80}", value["messageId"]
    ):
        raise ValueError("messageId 无效")
    payload = value.get("payload")
    if not isinstance(payload, dict):
        raise ValueError("payload 必须是对象")
    if value["type"] != "hello":
        if not isinstance(value.get("nonce"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", value["nonce"]):
            raise ValueError("缺少有效连接证明，请升级扩展并重新连接")
        if not isinstance(value.get("sequence"), str) or not re.fullmatch(r"[1-9][0-9]{0,31}", value["sequence"]):
            raise ValueError("消息序号必须为规范正整数字符串")
        expiry = value.get("expiresAt")
        if not isinstance(expiry, str) or len(expiry) > 40 or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z", expiry):
            raise ValueError("连接有效期必须为 UTC 时间")
        try:
            datetime.fromisoformat(expiry.replace("Z", "+00:00"))
        except ValueError as error:
            raise ValueError("连接有效期无效") from error
    if any(key in payload for key in ("token", "cookie", "authorization", "workspaceId")):
        raise ValueError("消息不可携带凭据或自行指定工作空间")
    if value["type"] == "asset.chunk":
        data = base64.b64decode(payload.get("dataBase64", ""), validate=True)
        if not data or len(data) > 128 * 1024:
            raise ValueError("资源分片长度无效")
        offset, total = payload.get("offset"), payload.get("totalSize")
        if (
            type(offset) is not int
            or type(total) is not int
            or offset < 0
            or total > 10 * 1024 * 1024
            or offset + len(data) > total
        ):
            raise ValueError("资源分片范围无效")
        if not re.fullmatch(r"[0-9a-f]{64}", str(payload.get("sha256", ""))):
            raise ValueError("资源摘要无效")


def current_user_sid() -> str:
    # 使用进程 Token 获取 SID。不依赖本地化用户名或外部命令。
    from ctypes import wintypes

    advapi = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    advapi.OpenProcessToken.argtypes = [
        wintypes.HANDLE,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.HANDLE),
    ]
    advapi.GetTokenInformation.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
    ]
    advapi.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.LPWSTR)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    token = wintypes.HANDLE()
    if not advapi.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(token)):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        size = wintypes.DWORD()
        advapi.GetTokenInformation(token, 1, None, 0, ctypes.byref(size))
        buffer = ctypes.create_string_buffer(size.value)
        if not advapi.GetTokenInformation(token, 1, buffer, size, ctypes.byref(size)):
            raise ctypes.WinError(ctypes.get_last_error())
        sid = ctypes.cast(buffer, ctypes.POINTER(ctypes.c_void_p))[0]
        text = wintypes.LPWSTR()
        if not advapi.ConvertSidToStringSidW(sid, ctypes.byref(text)):
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            if text.value is None:
                raise ValueError("当前用户 SID 为空")
            return text.value
        finally:
            kernel.LocalFree(text)
    finally:
        kernel.CloseHandle(token)


def pipe_name() -> str:
    configured = os.environ.get("ADEN_COLLECTOR_PIPE")
    result = configured or "\\\\.\\pipe\\aden-collector-" + current_user_sid()
    if not re.fullmatch(r"\\\\\.\\pipe\\aden-collector-[A-Za-z0-9_-]{1,120}", result):
        raise ValueError("命名管道配置无效")
    return result


def serve(source: BinaryIO, destination: BinaryIO, bridge: BinaryIO) -> None:
    while True:
        try:
            request = read_message(source)
        except EOFError:
            return
        try:
            validate_message(request)
            write_message(bridge, request)
            response = read_message(bridge)
            if (
                response.get("messageId") != request["messageId"]
                or response.get("protocolVersion") != 1
            ):
                raise ValueError("桌面响应关联不一致")
            write_message(destination, response)
        except (ValueError, OSError, EOFError) as error:
            write_message(
                destination,
                {
                    "protocolVersion": 1,
                    "messageId": request.get("messageId"),
                    "ok": False,
                    "error": {"code": "NATIVE_BRIDGE_ERROR", "message": str(error)},
                },
            )
            # 管道故障后不偷偷重连到另一个登录会话。
            if isinstance(error, (OSError, EOFError)):
                return


def main() -> int:
    if os.name != "nt" or len(sys.argv) < 2 or sys.argv[1] != EXTENSION_ORIGIN:
        print("不允许的 Native Host 调用来源", file=sys.stderr)
        return 2
    import msvcrt

    msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)
    msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)
    try:
        # Windows CRT 二进制无缓冲管道保留同一会话。支持多次捕获。
        with open(pipe_name(), "r+b", buffering=0) as bridge:
            serve(sys.stdin.buffer, sys.stdout.buffer, bridge)
    except (OSError, ValueError, EOFError) as error:
        print(f"Aden Native Host: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
