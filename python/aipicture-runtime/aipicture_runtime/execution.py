"""Java 领取/心跳/产物上传/回执客户端；只有中心保存任务、租约和重试状态。"""
from __future__ import annotations

import asyncio
import json
from urllib.parse import urlsplit

import httpx

from aipicture_runtime.config import get_settings
from aipicture_runtime.delivery import DeliveryJournal
from aipicture_runtime.dto import ComputeRequest
from aipicture_runtime.engine import compute
from aipicture_runtime.providers.base import ProviderOutcomeUnknown
from aipicture_runtime.main import _safe_error
from aipicture_runtime.security import sign_headers

BASE = "/aipicture/internal/v1/execution"
_DELIVERY_BACKOFF = (1, 2)


class ExecutionCancelled(RuntimeError):
    pass


class ExecutionLeaseLost(RuntimeError):
    pass


class CenterRequestRejected(RuntimeError):
    """只保留安全的中心业务码，不携带响应正文、配置或产物内容。"""

    def __init__(self, code):
        self.code = code if type(code) is int else None
        super().__init__("中心拒绝执行服务请求")


def _temporary_delivery_error(exc: Exception) -> bool:
    if isinstance(exc, httpx.HTTPStatusError):
        return 500 <= exc.response.status_code <= 599
    if isinstance(exc, CenterRequestRejected):
        return exc.code is not None and 500 <= exc.code <= 599
    return isinstance(exc, (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError))


def remap_ids(value, mapping: dict[str, str]):
    if isinstance(value, dict):
        return {key: remap_ids(item, mapping) for key, item in value.items()}
    if isinstance(value, list):
        return [remap_ids(item, mapping) for item in value]
    return mapping.get(value, value) if isinstance(value, str) else value


class ExecutionClient:
    def __init__(self):
        self.settings = get_settings()
        self.journal = None
        target = urlsplit(self.settings.center_url)
        if target.scheme != "https" and not (target.scheme == "http" and target.hostname in {"127.0.0.1", "localhost", "::1"}):
            raise ValueError("中心须为 HTTPS 或明确的本机 HTTP 地址")
        if target.username or target.password or target.query or target.fragment:
            raise ValueError("中心地址不能包含凭据或查询")
        self.client = httpx.AsyncClient(base_url=self.settings.center_url.rstrip("/"), timeout=60, follow_redirects=False, trust_env=False)

    async def close(self):
        await self.client.aclose()
        if self.journal is not None:
            self.journal.close()

    async def post(self, endpoint: str, payload: dict):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        path = urlsplit(self.settings.center_url).path.rstrip("/") + BASE + endpoint
        headers = {**sign_headers(self.settings.service_secret, self.settings.service_name, "POST", path, body), "Content-Type": "application/json"}
        response = await self.client.post(path, content=body, headers=headers)
        response.raise_for_status()
        data = response.json() if response.content else None
        if isinstance(data, dict) and "code" in data:
            if data["code"] != 200:
                raise CenterRequestRejected(data["code"])
            return data.get("data")
        return data

    async def process(self, claim: dict, delivery: dict | None = None):
        base = {key: claim[key] for key in ["run_id", "attempt", "lease_token"]}
        running = True
        lost = False
        cancelled = False
        rejected_by_center = False
        computed = delivery.get("computed") if delivery is not None else None
        computed_available = computed is not None
        last_progress, last_stage = 0, "准备计算"
        provider_task_id = claim.get("provider_task_id")

        def ensure_active():
            if lost:
                raise ExecutionLeaseLost("执行租约无法确认，已停止后续步骤和上传；在途供应商结果由中心核对")
            if cancelled:
                raise ExecutionCancelled("中心已取消后续步骤，未采用正在完成的第三方结果")

        async def report(progress: int, stage: str, details: dict | None = None):
            nonlocal last_progress, last_stage, cancelled, lost, provider_task_id
            if details and details.get("provider_task_id"):
                provider_task_id = details["provider_task_id"]
            ensure_active()
            last_progress, last_stage = max(last_progress, min(99, progress)), stage
            try:
                response = await self.post("/heartbeat", {**base, "progress": last_progress, "stage": stage,
                    **({"provider_task_id": details["provider_task_id"]} if details and "provider_task_id" in details else {}),
                    **({"result": details} if details else {})})
                if response and response.get("cancelled"):
                    cancelled = True
            except ExecutionCancelled:
                raise
            except Exception as exc:
                # 前台与后台心跳都不能把中心拒绝/通信异常当作可恢复单项失败。
                lost = True
                raise ExecutionLeaseLost("中心心跳未确认执行租约，已停止后续步骤和上传") from exc
            # 前台请求等待期间，后台心跳也可能已确认停止。
            ensure_active()
            return response

        async def deliver(endpoint: str, payload: dict):
            nonlocal lost, rejected_by_center
            # 仅重交本进程已计算的同一份产物/回执，不重跑compute或Provider。
            for sending in range(len(_DELIVERY_BACKOFF) + 1):
                if sending:
                    await asyncio.sleep(_DELIVERY_BACKOFF[sending - 1])
                ensure_active()
                confirmation = await report(last_progress, last_stage)
                state = confirmation.get("status") if isinstance(confirmation, dict) else None
                # 成功回执可能已提交但响应丢失；中心允许同token终态回放。
                # 其他终态不再上传，也不能用迟到结果覆盖中心既有结论。
                if state != "running" and not (
                    endpoint == "/receipt" and state == payload["status"]
                ):
                    lost = True
                    rejected_by_center = state in {"failed", "succeeded", "cancelled", "external_result_unknown", "partial_success"}
                    raise ExecutionLeaseLost("中心已结束本次执行，已停止产物交付")
                try:
                    result = await self.post(endpoint, payload)
                    ensure_active()
                    return result
                except (ExecutionCancelled, ExecutionLeaseLost):
                    raise
                except Exception as exc:
                    if _temporary_delivery_error(exc) and sending < len(_DELIVERY_BACKOFF):
                        continue
                    lost = True
                    raise ExecutionLeaseLost("已计算产物未获中心确认，已停止交付并保留中心租约裁决") from exc

        async def heartbeat():
            nonlocal lost
            while running:
                await asyncio.sleep(15)
                if running:
                    try:
                        await report(last_progress, last_stage)
                    except ExecutionCancelled:
                        return
                    except Exception:
                        lost = True
                        return

        timer = asyncio.create_task(heartbeat())
        request = None
        try:
            allowed = set(ComputeRequest.model_fields)
            request = ComputeRequest.model_validate({key: value for key, value in claim.items() if key in allowed})
            if delivery is not None and delivery.get("receipt") is not None:
                receipt = delivery["receipt"]
                confirmed = await deliver("/receipt", receipt)
                if not isinstance(confirmed, dict) or confirmed.get("status") != receipt["status"]:
                    rejected_by_center = isinstance(confirmed, dict) and confirmed.get("status") in {"failed", "succeeded", "cancelled", "external_result_unknown", "partial_success"}
                    raise ExecutionLeaseLost("中心未确认原回执终态，已停止重交")
                await asyncio.to_thread(self.journal.confirmed, delivery)
                return
            if computed is None:
                computed = await compute(request, report)
            computed_available = True
            if delivery is not None:
                delivery["computed"] = computed
                await asyncio.to_thread(self.journal.save, delivery)
            ensure_active()
            identifiers = {}
            uploaded = []
            for artifact in computed["artifacts"]:
                ensure_active()
                payload = {**base, "id": artifact["id"], "content_base64": artifact["content_base64"], "kind": artifact["kind"],
                    "source": artifact["source"], "format": artifact["format"].lower(), "manifest": computed["result"].get("manifest")}
                saved = await deliver("/artifacts", payload)
                ensure_active()
                identifier = str(saved.get("id", artifact["id"]))
                identifiers[artifact["id"]] = identifier
                uploaded.append(identifier)
            result = remap_ids(computed["result"], identifiers)
            status = "failed" if result.get("summary", {}).get("status") == "failed" else "succeeded"
            message = "全部批量项失败" if status == "failed" else None
            items = result.get("items")
            if status == "failed" and isinstance(items, list) and len(items) == 1:
                cause = items[0].get("error") if isinstance(items[0], dict) else None
                if isinstance(cause, str) and cause:
                    # Java单图子run把顶层message入正式batch item，保留源逐图错误与已有脱敏边界。
                    message = _safe_error(RuntimeError(cause))
            ensure_active()
            receipt = {**base, "schema_version": request.schema_version, "tool_registry_version": request.tool_registry_version,
                "status": status, "result": result, "artifacts": uploaded, "provider": computed["provider"],
                "error_code": "BATCH_ALL_FAILED" if status == "failed" else None, "message": message}
            if delivery is not None:
                delivery["receipt"] = receipt
                await asyncio.to_thread(self.journal.save, delivery)
            confirmed = await deliver("/receipt", receipt)
            if not isinstance(confirmed, dict) or confirmed.get("status") != status:
                rejected_by_center = isinstance(confirmed, dict) and confirmed.get("status") in {"failed", "succeeded", "cancelled", "external_result_unknown", "partial_success"}
                raise ExecutionLeaseLost("中心未确认回执终态，已停止采用")
            if delivery is not None:
                await asyncio.to_thread(self.journal.confirmed, delivery)
        except Exception as exc:
            # 包括交付响应格式异常：已有计算结果不能被交付故障改写为可付费重试的failed。
            if computed_available:
                lost = True
            if not lost:
                unknown = isinstance(exc, (ProviderOutcomeUnknown, httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError)) and request is not None and request.tool in {
                    "generate_image", "replace_background", "expand_canvas", "upscale_image", "erase_region",
                    "replace_region", "split_layers", "promote_object_to_layer", "generate_marketing", "batch_process"}
                known_task = getattr(exc, "provider_task_id", None) or provider_task_id
                query_existing = unknown and request.tool == "generate_image" and known_task
                confirmation = await self.post("/receipt", {**base, "status": "cancelled" if cancelled else "external_result_unknown" if unknown else "failed", "result": {}, "artifacts": [],
                    **({"provider_task_id": known_task} if query_existing else {}),
                    "error_code": "CANCELLED" if cancelled else "PROVIDER_TASK_PENDING" if query_existing else "PROVIDER_OUTCOME_UNKNOWN" if unknown else "COMPUTE_FAILED", "message": _safe_error(exc)})
                if delivery is not None and isinstance(confirmation, dict) and confirmation.get("status") in {"failed", "cancelled", "external_result_unknown", "queued"}:
                    await asyncio.to_thread(self.journal.confirmed, delivery)
            elif delivery is not None:
                # 暂时网络故障保留同一副本；明确失租/权限拒绝或取消则隔离，永不复算。
                cause = exc
                rejected = cancelled or rejected_by_center
                while cause is not None:
                    rejected |= isinstance(cause, CenterRequestRejected) and cause.code in {401, 403, 404, 409}
                    rejected |= isinstance(cause, httpx.HTTPStatusError) and cause.response.status_code in {401, 403, 404, 409}
                    cause = cause.__cause__
                if rejected:
                    await asyncio.to_thread(self.journal.quarantine, delivery, "中心拒绝旧租约或取消；保留产物，不重算")
        finally:
            running = False
            timer.cancel()
            await asyncio.gather(timer, return_exceptions=True)

    async def serve(self):
        if not self.settings.enabled or self.settings.offline_test:
            raise RuntimeError("真实领取循环未启用；离线测试禁止读取中心业务任务")
        self.journal = DeliveryJournal(self.settings)
        self.journal.open()
        delay = 1
        while True:
            try:
                pending = await asyncio.to_thread(self.journal.pending)
                if pending:
                    for delivery in pending:
                        if delivery.get("computed") is None:
                            await asyncio.to_thread(self.journal.quarantine, delivery, "计算未耐久完成；外部结果交由中心核对，禁止复算")
                        else:
                            await self.process(delivery["claim"], delivery)
                    await asyncio.sleep(min(delay, 30))
                    delay = min(delay * 2, 30)
                    continue
                if not await asyncio.to_thread(self.journal.has_capacity):
                    raise RuntimeError("交付缓存4份已满，请核对隔离副本后释放空间；未领取新任务")
                claim = await self.post("/claim", {"worker_id": self.settings.service_name})
                if claim:
                    delivery = await asyncio.to_thread(self.journal.reserve, claim)
                    await self.process(claim, delivery)
                delay = 1
            except (httpx.HTTPError, CenterRequestRejected):
                delay = min(delay * 2, 30)
            await asyncio.sleep(delay)
