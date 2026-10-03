"""原代码节点接口；所有原 executor 别名统一受中心沙箱授权。"""
from abc import ABC, abstractmethod

from worker.errors import WorkerError


class BaseExecutor(ABC):
    @abstractmethod
    async def execute(self, language: str, code: str, timeout: int, span=None, **kwargs) -> str:
        raise NotImplementedError


class CodeExecutorFactory:
    @staticmethod
    def create_executor(executor: str):
        if executor not in {"local", "langchain", "ifly", "center"}:
            raise WorkerError("ENGINE_UNSUPPORTED", "未知沙箱执行方式")
        from sandbox.executor import CenterSandboxExecutor
        return CenterSandboxExecutor()
