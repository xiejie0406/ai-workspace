"""任务内私有资源缓存。内容只来自中心租约许可，不从任意 URL 获取。"""
from contextvars import ContextVar

_assets: ContextVar[dict[str, bytes | Exception] | None] = ContextVar("aippt_task_assets", default=None)


def media_key_from_url(url: str) -> str | None:
    prefix = "/aippt/assets/"
    if url.startswith(prefix):
        key = url[len(prefix):].split("?")[0].split("/")[0]
        return key if key and "/" not in key and ".." not in key else None
    return None


def load_image(key: str) -> bytes:
    assets = _assets.get() or {}
    if key not in assets:
        raise FileNotFoundError("任务未获准读取此图片")
    value = assets[key]
    if isinstance(value, Exception): raise value
    return value


def bind_assets(assets: dict[str, bytes | Exception]):
    return _assets.set(dict(assets))


def unbind_assets(token) -> None:
    _assets.reset(token)
