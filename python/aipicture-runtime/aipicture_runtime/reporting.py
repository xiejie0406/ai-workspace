"""一次计算的临时进度回调，不保存任务状态。"""
from contextvars import ContextVar

progress_callback = ContextVar("aipicture_progress_callback", default=None)


async def provider_task_created(task_id: str):
    callback = progress_callback.get()
    if callback:
        await callback(10, "供应商任务已创建", {"provider_task_id": task_id, "provider_operation": "generate"})
