"""真实中心错误信封经 HTTP 适配到质量/导出门禁；全部请求由 MockTransport 接管。"""
import copy
import json
import httpx
import pytest

from app.core.config import Settings
from app.worker.catalog import catalog_version
from app.worker.handlers import TaskHandlers
from app.worker.protocol import CenterClient, ClaimedTask, LeaseLost


ASSET_ID = "00000000-0000-0000-0000-000000000001"
TASK_ID = "00000000-0000-0000-0000-000000000002"


def claimed(kind="quality"):
    return ClaimedTask(taskId=TASK_ID, attempt=2, leaseToken="fixture-lease", fencingToken=7,
        type=kind, catalogVersion=catalog_version(), payload={"font_manifest_hash": "unavailable",
        "snapshot": {"project": {"id": "fixture-deck", "title": "缺失资源定位", "theme_id": "ivory"},
            "slides": [{"id": "fixture-slide", "title": "实际引用页", "layout_id": "image-left", "blocks": [
                {"id": "title", "slot_id": "title", "type": "text", "text": "实际引用页"},
                {"id": "body", "slot_id": "body", "type": "bullets", "items": ["原图片仍有引用", "阻断导出并定位替换"]},
                {"id": "photo", "slot_id": "image", "type": "image", "source": "upload", "alt": "正式私有图片",
                    "url": f"/aippt/assets/{ASSET_ID}", "locked": True}]}]}})


def center_error(code="ASSET_MISSING"):
    # AipptExceptionHandler.business 的实际顶层 AjaxResult 形状。
    return {"code": 503, "msg": "私有文件不可读取", "errorCode": code,
        "correlationId": "fixture-correlation", "currentRevision": None, "retryable": True}


class ErrorStream(httpx.AsyncByteStream):
    def __init__(self, body): self.body = body
    async def __aiter__(self):
        yield self.body[:20]
        yield self.body[20:]


def missing_response():
    # 模拟真实 client.stream：JSON 字节在迭代流之前尚不可读取。
    return httpx.Response(503, stream=ErrorStream(json.dumps(center_error()).encode()))


def client_for(response, records):
    def respond(request):
        records.append(request)
        assert request.method == "GET"
        assert request.url.path == f"/internal/aippt/tasks/{TASK_ID}/assets/{ASSET_ID}"
        assert dict(request.url.params) == {"attempt": "2", "leaseToken": "fixture-lease", "fencingToken": "7"}
        assert request.headers["X-Aippt-Worker-Key"] == "fixture-key"
        return response
    return CenterClient(Settings(_env_file=None, center_url="http://fixture.invalid", worker_key="fixture-key"),
        transport=httpx.MockTransport(respond))


@pytest.mark.asyncio
async def test_actual_center_missing_envelope_becomes_located_quality_error():
    records = []
    center = client_for(missing_response(), records)
    task = claimed(); original = copy.deepcopy(task.payload)
    try:
        result, assets = await TaskHandlers(center).run(task)
        issues = [issue for issue in result["issues"] if issue["severity"] == "error"]
        assert len(issues) == 1
        assert issues[0]["slide_id"] == "fixture-slide" and issues[0]["slot_id"] == "image"
        assert "引用的图片资源不存在" in issues[0]["message"]
        assert result["can_export"] is False and assets == []
        assert result["font_mode"] == "estimate" and task.payload == original
        assert len(records) == 1
    finally:
        await center.close()


@pytest.mark.asyncio
async def test_actual_center_missing_envelope_blocks_export_without_upload():
    records = []
    center = client_for(missing_response(), records)
    try:
        with pytest.raises(ValueError, match="质量错误阻止导出"):
            await TaskHandlers(center).run(claimed("export"))
        assert len(records) == 1 and records[0].method == "GET"
    finally:
        await center.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["quality", "export"])
async def test_center_confirmed_asset_hash_corruption_is_located_and_never_exported(kind):
    records = []
    center = client_for(httpx.Response(503, stream=ErrorStream(json.dumps(center_error("ASSET_CORRUPT")).encode())), records)
    try:
        if kind == "export":
            with pytest.raises(ValueError, match="质量错误阻止导出"):
                await TaskHandlers(center).run(claimed(kind))
        else:
            result, assets = await TaskHandlers(center).run(claimed(kind))
            errors = [issue for issue in result["issues"] if issue["severity"] == "error"]
            assert len(errors) == 1 and errors[0]["slide_id"] == "fixture-slide" and errors[0]["slot_id"] == "image"
            assert "图片字节或尺寸不合法" in errors[0]["message"]
            assert result["can_export"] is False and assets == []
        assert len(records) == 1 and records[0].method == "GET"
    finally:
        await center.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [
    center_error("STORAGE_UNAVAILABLE"), center_error("UPSTREAM_UNAVAILABLE"), center_error(None),
    {"code": 503, "msg": "ASSET_MISSING"}, {"code": 503, "data": {"errorCode": "ASSET_MISSING"}},
    ["ASSET_MISSING"], None,
])
async def test_other_503_envelopes_do_not_become_missing_image_issues(body):
    records = []
    center = client_for(httpx.Response(503, json=body), records)
    try:
        with pytest.raises(httpx.HTTPStatusError) as failure:
            await TaskHandlers(center).run(claimed())
        assert failure.value.response.status_code == 503 and len(records) == 1
    finally:
        await center.close()


@pytest.mark.asyncio
async def test_non_json_503_preserves_http_failure():
    center = client_for(httpx.Response(503, content=b"ASSET_MISSING"), [])
    try:
        with pytest.raises(httpx.HTTPStatusError):
            await TaskHandlers(center).run(claimed())
    finally:
        await center.close()


@pytest.mark.asyncio
async def test_oversized_error_envelope_is_not_accepted_as_missing_resource():
    body = center_error(); body["msg"] = "x" * 8192
    center = client_for(httpx.Response(503, stream=ErrorStream(json.dumps(body).encode())), [])
    try:
        with pytest.raises(httpx.HTTPStatusError):
            await TaskHandlers(center).run(claimed())
    finally:
        await center.close()


@pytest.mark.asyncio
async def test_incomplete_missing_error_stream_preserves_read_failure():
    class BrokenStream(ErrorStream):
        async def __aiter__(self):
            yield self.body[:20]
            raise httpx.ReadError("fixture interrupted response")
    center = client_for(httpx.Response(503, stream=BrokenStream(json.dumps(center_error()).encode())), [])
    try:
        with pytest.raises(httpx.ReadError):
            await TaskHandlers(center).run(claimed())
    finally:
        await center.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("status,expected", [(401, httpx.HTTPStatusError), (403, LeaseLost), (409, LeaseLost), (410, LeaseLost)])
async def test_asset_missing_label_cannot_swallow_authentication_or_lease_failure(status, expected):
    center = client_for(httpx.Response(status, json=center_error()), [])
    try:
        with pytest.raises(expected):
            await TaskHandlers(center).run(claimed())
    finally:
        await center.close()


@pytest.mark.asyncio
async def test_private_image_channel_disconnect_stays_task_failure():
    def disconnected(request):
        raise httpx.ConnectError("fixture disconnect", request=request)
    center = CenterClient(Settings(_env_file=None, worker_key="fixture-key"), transport=httpx.MockTransport(disconnected))
    try:
        with pytest.raises(httpx.ConnectError):
            await TaskHandlers(center).run(claimed())
    finally:
        await center.close()
