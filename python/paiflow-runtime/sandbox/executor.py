"""代码交给中心登记的沙箱操作；此进程永不执行用户代码。

受限容器/进程的真实 Runner 尚需装配、限额、禁网和只读挂载验收。
中心未提供 sandbox.execute 时明确失败，不降级到 multiprocessing/exec。
"""
from __future__ import annotations

from worker.errors import WorkerError
from worker.task_runner import current_context
from worker.node_context import CURRENT_ITERATION_ID
import hashlib
import asyncio


class CenterSandboxExecutor:
    async def execute(self, language: str, code: str, timeout: int, span=None, **kwargs) -> str:
        if language != "python" or not isinstance(code, str) or not code or len(code.encode("utf-8")) > 256 * 1024:
            raise WorkerError("DSL_INVALID", "沙箱代码语言/内容超出范围")
        if type(timeout) is not int or not 1 <= timeout <= 600:
            raise WorkerError("DSL_INVALID", "沙箱超时超出范围")
        node_id = kwargs.get("node_id")
        if not isinstance(node_id, str) or not node_id:
            raise WorkerError("TOOL_POLICY_DENIED", "沙箱请求必须绑定固定节点")
        context = current_context()
        fingerprint = hashlib.sha256(code.encode("utf-8")).hexdigest()
        try:
            result = await context.operation(node_id, "sandbox.execute", {"language": language, "code": code, "timeoutSeconds": timeout},
                key=f"{context.assignment.run_id}:{context.assignment.attempt}:{node_id}:{CURRENT_ITERATION_ID.get() or '-'}:{fingerprint}:sandbox")
        except asyncio.CancelledError:
            # 已发请求但没有执行/停止回执；单次同步草案不能证明Runner停止。
            context.boundary_failure = WorkerError("EFFECT_UNKNOWN", "沙箱执行/停止未确认，须由中心核对Runner")
            raise
        if not isinstance(result, dict) or result.get("state") != "completed" or not isinstance(result.get("output"), str):
            error = WorkerError("RESOURCE_UNAVAILABLE", "沙箱没有返回可确认的执行结果")
            context.boundary_failure = error
            raise error
        return result["output"]
