"""旧配置不在算法导入时初始化；受控工厂从中心固定资源读取。"""
from worker.errors import WorkerError


class _LegacyConfigDenied:
    def __getattr__(self, name):
        raise WorkerError("RESOURCE_UNAVAILABLE", "旧 Agent 配置只供参考，能力必须通过中心固定资源")


agent_config = _LegacyConfigDenied()

__all__ = ["agent_config"]
