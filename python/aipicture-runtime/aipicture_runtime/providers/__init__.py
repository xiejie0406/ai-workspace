from functools import lru_cache

from aipicture_runtime.config import get_settings
from aipicture_runtime.providers.base import EditRequest, GenerateRequest, ImageProvider, ProviderError


@lru_cache
def get_image_provider() -> ImageProvider:
    settings = get_settings()
    if settings.image_provider == "mock" and settings.offline_test:
        from aipicture_runtime.providers.mock import MockImageProvider
        return MockImageProvider()
    if settings.image_provider == "dashscope" and settings.enabled and not settings.offline_test:
        from aipicture_runtime.providers.dashscope import DashScopeImageProvider
        return DashScopeImageProvider()
    raise ProviderError("图像 Provider 未启用或未配置，未生成任何替代图片")


__all__ = ["EditRequest", "GenerateRequest", "ImageProvider", "ProviderError", "get_image_provider"]
