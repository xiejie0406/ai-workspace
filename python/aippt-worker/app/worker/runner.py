"""租约、心跳与截止时间。退出或租约失效不偷偷重试已经付费的任务。"""
import asyncio
import logging
from datetime import datetime, timezone
import httpx
from app.core.config import Settings
from app.llm.errors import LLMNotConfiguredError
from app.worker.catalog import catalog_version, initialize_catalog
from app.worker.handlers import TaskHandlers
from app.worker.protocol import CenterClient, ClaimedTask, LeaseLost, ExportVerificationFailed

logger = logging.getLogger(__name__)


def public_error(error: Exception) -> tuple[str, str, bool]:
    from app.worker.fonts import FontManifestInvalid, FontManifestMismatch
    from app.images.validate import ImageRejected
    from app.worker.execution import ModelBudgetExceeded
    if isinstance(error, ExportVerificationFailed): return "PPTX_VERIFY_FAILED", "PPTX回读验证失败，请查看导出诊断", False
    if isinstance(error, ModelBudgetExceeded): return "TASK_BUDGET_EXCEEDED", "文本模型调用次数超过任务预算", False
    if isinstance(error, FontManifestMismatch): return "FONT_MANIFEST_MISMATCH", "Worker字体资源与任务快照不一致", False
    if isinstance(error, FontManifestInvalid): return "FONT_MANIFEST_INVALID", "Worker字体manifest或实际文件校验失败", False
    if isinstance(error, ImageRejected): return "INVALID_CONTENT", "图片字节或尺寸校验失败，请替换引用图片", False
    if isinstance(error, LLMNotConfiguredError): return "PROVIDER_NOT_CONFIGURED", "未配置文本模型，任务未调用模型", False
    # LCEL/生成器会包装Provider异常；必须保留瞬时错误的可重试语义。
    from openai import APIConnectionError, APIStatusError
    cause = error; seen = set()
    while cause is not None and id(cause) not in seen:
        seen.add(id(cause))
        if isinstance(cause, ModelBudgetExceeded): return "TASK_BUDGET_EXCEEDED", "文本模型调用次数超过任务预算", False
        transient_http = isinstance(cause, httpx.HTTPError) and (not isinstance(cause, httpx.HTTPStatusError) or cause.response.status_code == 429 or cause.response.status_code >= 500)
        if isinstance(cause, APIConnectionError) or transient_http or isinstance(cause, APIStatusError) and (cause.status_code == 429 or cause.status_code >= 500):
            return "UPSTREAM_UNAVAILABLE", "外部服务或中心通道暂时不可用", True
        cause = cause.__cause__
    if isinstance(error, (TimeoutError, asyncio.TimeoutError)): return "TASK_TIMEOUT", "任务超过运行时间预算", True
    if isinstance(error, httpx.HTTPStatusError): return "UPSTREAM_REJECTED", "外部服务拒绝请求，请检查配置或访问许可", False
    if isinstance(error, httpx.HTTPError): return "UPSTREAM_UNAVAILABLE", "外部服务或中心通道暂时不可用", True
    if isinstance(error, ValueError): return "INVALID_CONTENT", "任务内容、来源、布局或质量校验失败，请查看输入及质量提示", False
    return "ALGORITHM_FAILED", "算法执行失败，可检查Worker日志中的任务号与异常类型", False


class Worker:
    def __init__(self, settings: Settings, *, center: CenterClient | None = None, handlers=None):
        self.settings = settings
        self.center = center or CenterClient(settings)
        self.handlers = handlers or TaskHandlers(self.center)
        self.version = initialize_catalog()
        self.stop = asyncio.Event()

    def _timeout(self, task: ClaimedTask) -> float:
        timeout = self.settings.task_timeout_seconds
        if task.deadline:
            deadline = datetime.fromisoformat(task.deadline.replace("Z", "+00:00"))
            if deadline.tzinfo is None: deadline = deadline.replace(tzinfo=timezone.utc)
            timeout = min(timeout, (deadline - datetime.now(timezone.utc)).total_seconds())
        return max(0, timeout)

    async def execute(self, task: ClaimedTask):
        if self._timeout(task) <= 0:
            try:
                await self.center.fail(task, *public_error(TimeoutError()))
            except (LeaseLost, httpx.HTTPError):
                logger.warning("已过截止时间，等待中心恢复终态 task=%s", task.taskId)
            return
        try:
            actual_version = catalog_version()
        except ValueError:
            await self.center.fail(task, "CATALOG_MISMATCH", "Worker目录实际字节与manifest不一致", False)
            return
        if task.catalogVersion != self.version or actual_version != self.version:
            await self.center.fail(task, "CATALOG_MISMATCH", "Worker目录版本与任务不一致", False)
            return
        lost = asyncio.Event()
        async def heartbeat():
            while True:
                await asyncio.sleep(self.settings.heartbeat_seconds)
                try:
                    result = await self.center.heartbeat(task)
                    if isinstance(result, dict) and result.get("leaseValid") is False: raise LeaseLost("租约已失效")
                    # cancelRequested 为软取消，已领取页面可完成；中心不再派未开始页。
                except Exception:
                    lost.set(); return
        beat = asyncio.create_task(heartbeat())
        operation = None
        lease_wait = asyncio.create_task(lost.wait())
        try:
            receipt = await self.center.progress(task, 10, "Worker已领取，正在处理")
            if not isinstance(receipt, dict) or receipt.get("accepted") is not True or lost.is_set():
                raise LeaseLost("中心未确认有效任务租约")
            timeout = self._timeout(task)
            if timeout <= 0:
                raise TimeoutError("任务截止时间已到")
            operation = asyncio.create_task(self.handlers.run(task))
            done, _ = await asyncio.wait([operation, lease_wait], timeout=timeout, return_when=asyncio.FIRST_COMPLETED)
            if lease_wait in done: raise LeaseLost("心跳失败，禁止迟到结果写回")
            if operation not in done: raise TimeoutError("任务截止时间已到")
            output, assets = await operation
            if lost.is_set(): raise LeaseLost("租约失效")
            await self.center.heartbeat(task)  # 最后一次确认中心仍接受此 fencingToken
            await self.center.complete(task, output, assets)
            logger.info("任务完成 task=%s type=%s attempt=%d", task.taskId, task.type, task.attempt)
        except LeaseLost:
            logger.warning("租约失效，候选丢弃 task=%s", task.taskId)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            logger.error("任务失败 task=%s type=%s error=%s", task.taskId, task.type, type(error).__name__)
            if not lost.is_set():
                # 超时也先取消并等待 finally，以保留已发生调用的真实摘要。
                if operation is not None:
                    if not operation.done():operation.cancel()
                    await asyncio.gather(operation,return_exceptions=True)
                try:
                    if isinstance(error, ExportVerificationFailed):
                        await self.center.fail(task, *public_error(error), task._execution,
                            verification_failure=error.verification_failure)
                    else:
                        await self.center.fail(task, *public_error(error),task._execution)
                except (LeaseLost, httpx.HTTPError): logger.warning("中心未接受失败终态 task=%s", task.taskId)
        finally:
            for future in [beat, lease_wait, operation]:
                if future is not None and not future.done(): future.cancel()
            await asyncio.gather(*(future for future in [beat, lease_wait, operation] if future is not None), return_exceptions=True)

    async def slot(self):
        while not self.stop.is_set():
            try:
                task = await self.center.claim(self.version)
                if task:
                    await self.execute(task)
                    continue
            except (httpx.HTTPError, LeaseLost):
                logger.warning("中心暂不可用，保留领取循环")
            try: await asyncio.wait_for(self.stop.wait(), timeout=self.settings.claim_interval_seconds)
            except TimeoutError: pass

    async def run(self, *, once=False):
        try:
            if once:
                task = await self.center.claim(self.version, wait_millis=0)
                if task: await self.execute(task)
            else:
                await asyncio.gather(*(self.slot() for _ in range(self.settings.concurrency)))
        finally: await self.center.close()
