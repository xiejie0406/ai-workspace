"""服务端私有 HTTP；监听 localhost，正式状态仍全部在 Java。"""
from __future__ import annotations

import asyncio
import json

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from aipicture_runtime.artifacts import ImageRejected
from aipicture_runtime.capabilities import capabilities
from aipicture_runtime.config import get_settings
from aipicture_runtime.dto import ComputeRequest, PlanRequest
from aipicture_runtime.engine import compute
from aipicture_runtime.security import CredentialError, CredentialVerifier


def create_app() -> FastAPI:
    application = FastAPI(title="AI 图片私有计算 Worker", docs_url=None, redoc_url=None, openapi_url=None)
    settings = get_settings()
    verifier = CredentialVerifier(settings.service_secret, settings.allowed_caller_service, settings.timestamp_skew_seconds)
    semaphore = asyncio.Semaphore(2)

    async def read(request: Request, *, enabled: bool = True) -> dict:
        if request.url.query:
            raise HTTPException(400, "私有计算接口不接受 query 参数")
        chunks, size = [], 0
        async for chunk in request.stream():
            size += len(chunk)
            if size > settings.max_request_bytes:
                raise HTTPException(413, "请求超过资源限制")
            chunks.append(chunk)
        body = b"".join(chunks)
        try:
            verifier.verify(request.method, request.url.path, body, dict(request.headers))
        except CredentialError as exc:
            raise HTTPException(401, str(exc)) from exc
        if enabled and not settings.enabled and not settings.offline_test:
            raise HTTPException(503, "图片计算运行时未启用")
        try:
            return json.loads(body) if body else {}
        except ValueError as exc:
            raise HTTPException(400, "请求不是有效 JSON") from exc

    @application.api_route("/internal/v1/capabilities", methods=["GET", "POST"])
    async def get_capabilities(request: Request):
        await read(request, enabled=False)
        return capabilities()

    @application.post("/internal/v1/compute")
    async def run_compute(request: Request):
        raw = await read(request)
        try:
            payload = ComputeRequest.model_validate(raw)
            async with semaphore:
                return await compute(payload)
        except ImageRejected as exc:
            return JSONResponse(status_code=422, content={"error_code": exc.error_code, "message": str(exc)})
        except (ValueError, ValidationError) as exc:
            raise HTTPException(422, str(exc)) from exc
        except Exception as exc:
            # 不输出密钥、Provider原始响应、签名链接或堆栈到业务端。
            return JSONResponse(status_code=503, content={"code": "COMPUTE_UNAVAILABLE", "message": _safe_error(exc),
                "run_id": raw.get("run_id"), "attempt": raw.get("attempt")})

    @application.post("/internal/v1/prepare-selection")
    async def prepare_selection(request: Request):
        raw = await read(request)
        # source 点选预热与构建遮罩共用真实渲染/分割，仅名字由服务端决定。
        raw["tool"] = "selection" if raw.get("tool") == "selection" or not raw.get("prepare_only", True) else "prepare_selection"
        raw.pop("prepare_only", None)
        for key in ["points", "strokes", "radius", "append", "revision"]:
            if key in raw:
                raw.setdefault("params", {})[key] = raw.pop(key)
        try:
            async with semaphore:
                return await compute(ComputeRequest.model_validate(raw))
        except ImageRejected as exc:
            return JSONResponse(status_code=422, content={"error_code": exc.error_code, "message": str(exc)})
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        except Exception as exc:
            raise HTTPException(503, _safe_error(exc)) from exc

    @application.post("/internal/v1/plan")
    async def plan(request: Request):
        raw = await read(request)
        try:
            payload = PlanRequest.model_validate(raw)
            from aipicture_runtime.agent.graph import run
            async with semaphore:
                reply, steps = await run(payload.goal, payload.context)
            return {"schema_version": payload.schema_version, "tool_registry_version": payload.tool_registry_version,
                    "run_id": payload.run_id, "attempt": payload.attempt, "reply": reply, "steps": steps,
                    "needs_confirm": len(steps) > 1}
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        except Exception as exc:
            raise HTTPException(503, _safe_error(exc)) from exc

    return application


def _safe_error(exc: Exception) -> str:
    # 受控本地算法异常可原样说明；第三方 HTTP/模型异常只返回类别。
    from aipicture_runtime.providers import ProviderError
    if isinstance(exc, (ProviderError, RuntimeError)) and not any(token in str(exc).lower() for token in ["https:", "http:", "bearer", "api_key="]):
        return str(exc)[:300]
    return f"计算不可用（{type(exc).__name__}）"


app = create_app()
