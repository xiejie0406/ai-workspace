"""机器通道仅领取不可变输入，所有正式状态由 Java 中心提交。"""
from typing import Any, Literal, get_args
from urllib.parse import urlsplit
import httpx
from pydantic import BaseModel, ConfigDict, Field, PrivateAttr
from app.core.config import Settings


class LeaseLost(RuntimeError):
    pass


class ExportVerificationFailed(ValueError):
    """仅原生 PPTX 回读产生的受控诊断；异常文字不包含正文或报告。"""
    CHECKS = frozenset({"open", "page_count", "canvas_size", "full_page_picture", "bounds",
        "native_table", "native_chart", "native_image", "text_frame", "content_integrity", "speaker_notes"})

    def __init__(self, report):
        from app.render.verify import VerifyReport
        if not isinstance(report, VerifyReport) or report.passed or not report.issues or any(issue.check not in self.CHECKS for issue in report.issues):
            raise ValueError("PPTX回读报告不符合内部诊断契约")
        truncated = len(report.issues) > 2000
        issues = []
        for issue in report.issues[:2000]:
            item = issue.model_dump(mode="json")
            for name, limit in (("message", 1000), ("shape", 200)):
                value = item[name]
                if value is not None and len(value) > limit:
                    item[name] = value[:limit - 1] + "…"
                    truncated = True
            issues.append(item)
        self.verification_failure = {"passed": False, "slide_count": report.slide_count,
            "expected_slide_count": report.expected_slide_count, "issues": issues,
            "diagnostics_truncated": truncated, "total_issues": len(report.issues)}
        super().__init__("PPTX回读验证失败，未提供下载")


class ClaimedTask(BaseModel):
    model_config = ConfigDict(extra="forbid")
    taskId: str
    attempt: int = Field(ge=1)
    leaseToken: str
    fencingToken: int = Field(ge=1)
    type: Literal["parse_source", "generate_outline", "generate_slide", "ai_edit", "relayout", "quality", "export"]
    payload: dict[str, Any]
    deadline: str | None = None
    catalogVersion: str
    _execution: dict | None = PrivateAttr(default=None)

    def lease(self) -> dict:
        return {"attempt": self.attempt, "leaseToken": self.leaseToken, "fencingToken": self.fencingToken}


class CenterClient:
    def __init__(self, settings: Settings, *, transport=None):
        parts = urlsplit(settings.center_url)
        if parts.scheme not in {"http", "https"} or not parts.hostname or parts.username or parts.password:
            raise ValueError("中心服务地址无效")
        if not settings.worker_key.get_secret_value():
            raise ValueError("未配置中心 Worker 凭据")
        # Java 的 String.length() 按 UTF-16 单元计数，与中心128上限一致。
        if not 1 <= len(settings.worker_id.encode("utf-16-le")) // 2 <= 128:
            raise ValueError("Worker标识长度须为1至128")
        self.worker_id = settings.worker_id
        self.client = httpx.AsyncClient(base_url=settings.center_url.rstrip("/"), headers={"X-Aippt-Worker-Key": settings.worker_key.get_secret_value()},
                                       timeout=30, trust_env=False, follow_redirects=False, transport=transport)

    async def close(self):
        await self.client.aclose()

    async def post(self, path: str, payload: dict):
        response = await self.client.post("/internal/aippt/tasks" + path, json=payload)
        if response.status_code in {403, 404, 409, 410}: raise LeaseLost("中心拒绝已失效租约")
        response.raise_for_status()
        body = response.json()
        if body.get("code", 200) != 200:
            raise LeaseLost("中心拒绝任务写回")
        return body.get("data", body)

    async def claim(self, version: str, *, wait_millis: int = 10000, types: list[str] | None = None):
        if type(wait_millis) is not int or not 0 <= wait_millis <= 10000:
            raise ValueError("领取等待时间须为0至10000毫秒整数")
        request = {"workerId": self.worker_id, "catalogVersion": version, "waitMillis": wait_millis}
        if types is not None:
            allowed = get_args(ClaimedTask.model_fields["type"].annotation)
            if not isinstance(types, list) or any(not isinstance(value, str) or value not in allowed for value in types):
                raise ValueError("领取任务类型须为已定义类型数组")
            request["types"] = list(types)
        body = await self.post("/claim", request)
        return ClaimedTask.model_validate(body) if body else None

    async def heartbeat(self, task: ClaimedTask):
        return await self.post(f"/{task.taskId}/heartbeat", {**task.lease(), "workerId": self.worker_id})

    async def progress(self, task: ClaimedTask, percent: int, message: str):
        return await self.post(f"/{task.taskId}/progress", {**task.lease(), "progress": {"percent": percent, "message": message}})

    async def complete(self, task: ClaimedTask, output: dict, assets: list[dict]):
        return await self.post(f"/{task.taskId}/complete", {**task.lease(), "output": output, "assets": assets})

    async def fail(self, task: ClaimedTask, code: str, message: str, retryable: bool, execution: dict | None = None,
                   *, verification_failure: dict | None = None):
        body={**task.lease(), "code": code, "message": message, "retryable": retryable}
        if execution is not None:body["execution"]=execution
        if verification_failure is not None:body["verification_failure"]=verification_failure
        return await self.post(f"/{task.taskId}/fail",body)

    async def read_asset(self, task: ClaimedTask, asset_id: str, limit: int) -> bytes:
        from urllib.parse import quote
        path = f"/internal/aippt/tasks/{quote(task.taskId, safe='')}/assets/{quote(asset_id, safe='')}"
        chunks = []; size = 0
        async with self.client.stream("GET", path, params=task.lease()) as response:
            if response.status_code in {403, 409, 410}: raise LeaseLost("中心拒绝任务资源读取")
            if response.status_code == 404: raise FileNotFoundError("任务引用图片缺失")
            if response.status_code == 503:
                # 中心对象缺失使用顶层 AjaxResult.errorCode；仅此明确错误进入逐块质量报告。
                # 流响应须先读取，且错误信封独立限额，不能把任意503/鉴权/通道故障当成缺图。
                import json
                envelope = bytearray()
                async for chunk in response.aiter_bytes():
                    if len(envelope) + len(chunk) > 8192:
                        envelope.clear()
                        break
                    envelope.extend(chunk)
                try:
                    body = json.loads(envelope)
                except ValueError:
                    body = None
                if isinstance(body, dict) and body.get("errorCode") == "ASSET_MISSING":
                    raise FileNotFoundError("任务引用图片缺失")
                if isinstance(body, dict) and body.get("errorCode") == "ASSET_CORRUPT":
                    from app.images.validate import ImageRejected
                    raise ImageRejected("中心私有图片哈希不符")
            response.raise_for_status()
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > limit: raise ValueError("中心资源超过任务预算")
                chunks.append(chunk)
        return b"".join(chunks)

    async def upload_asset(self, task: ClaimedTask, data: bytes, filename: str, mime: str, role: str) -> dict:
        import hashlib
        response = await self.client.post(f"/internal/aippt/tasks/{task.taskId}/assets", data={**task.lease(), "role": role, "mime": mime,
                                                "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)},
                                          files={"file": (filename, data, mime)})
        if response.status_code in {403, 409, 410}: raise LeaseLost("中心拒绝资源登记")
        response.raise_for_status()
        result = response.json()
        if result.get("code", 200) != 200: raise LeaseLost("中心拒绝资源登记")
        return result.get("data", result)
