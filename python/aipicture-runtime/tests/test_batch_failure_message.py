"""Java单图子run实际消费顶层message；不丢失原逐图失败原因。"""
import pytest

from aipicture_runtime.config import get_settings
from aipicture_runtime.providers.base import ProviderError
from test_paid_recovery_and_exports import receipt_of
from test_worker import request


def batch_payload(amount=1, operation="remove_background"):
    payload = request("batch_process")
    if amount > 1:
        import uuid
        payload.assets.extend(payload.assets[0].model_copy(update={"id": uuid.uuid4()}) for _ in range(amount - 1))
    payload.params = {"asset_ids": [str(asset.id) for asset in payload.assets],
        "operations": [{"tool": operation, "params": {"prompt": "背景"} if operation == "replace_background" else {}}],
        "formats": ["png"]}
    return payload


@pytest.mark.asyncio
async def test_actual_unconfigured_matting_single_child_preserves_failure_reason(monkeypatch):
    settings = get_settings()
    settings.offline_test, settings.matting_provider = False, "unconfigured"
    settings.image_provider, settings.ocr_provider = "dashscope", "unconfigured"
    result, calls = await receipt_of(batch_payload(), monkeypatch)
    expected = "去背景模型未配置，未使用四角算法替代真实能力"
    assert result["result"]["items"][0]["error"] == expected
    assert result["status"] == "failed" and result["error_code"] == "BATCH_ALL_FAILED"
    assert result["message"] == expected
    assert not any(endpoint == "/artifacts" for endpoint, _ in calls)


@pytest.mark.asyncio
async def test_multiple_failed_items_keep_summary_and_per_item_reasons(monkeypatch):
    get_settings().matting_provider = "unconfigured"
    result, _ = await receipt_of(batch_payload(2), monkeypatch)
    assert result["message"] == "全部批量项失败"
    assert len(result["result"]["items"]) == 2
    assert all(item["error"] == "去背景模型未配置，未使用四角算法替代真实能力" for item in result["result"]["items"])


@pytest.mark.asyncio
@pytest.mark.parametrize("cause,expected", [("明确拒绝请求", "明确拒绝请求"),
    ("第三方 https://example.invalid/private?api_key=secret", "计算不可用（RuntimeError）"),
    ("错" * 350, "错" * 300)])
async def test_single_child_cause_uses_existing_safe_error_boundary(monkeypatch, cause, expected):
    class Rejected:
        async def edit(self, *args):
            raise ProviderError(cause)
    monkeypatch.setattr("aipicture_runtime.engine.get_image_provider", lambda: Rejected())
    result, _ = await receipt_of(batch_payload(operation="replace_background"), monkeypatch)
    assert result["status"] == "failed" and result["message"] == expected
