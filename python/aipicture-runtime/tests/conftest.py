import pytest

from aipicture_runtime.config import get_settings
from aipicture_runtime.providers import get_image_provider


@pytest.fixture(autouse=True)
def offline_models(monkeypatch):
    # 明确测试方式；测试不会触碰真实中心/业务数据库/付费 Provider 或下载权重。
    monkeypatch.setenv("AIPICTURE_OFFLINE_TEST", "true")
    monkeypatch.setenv("AIPICTURE_IMAGE_PROVIDER", "mock")
    monkeypatch.setenv("AIPICTURE_MATTING_PROVIDER", "corner")
    monkeypatch.setenv("AIPICTURE_OCR_PROVIDER", "none")
    monkeypatch.setenv("AIPICTURE_SERVICE_SECRET", "test-only-service-secret-at-least-32-bytes")
    get_settings.cache_clear()
    get_image_provider.cache_clear()
    yield
    get_settings.cache_clear()
    get_image_provider.cache_clear()
