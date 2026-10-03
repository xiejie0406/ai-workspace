"""旧名字兼容入口。实际执行委托中心沙箱，拒绝宿主机 exec。"""
from sandbox.executor import CenterSandboxExecutor


class LocalExecutor(CenterSandboxExecutor):
    """只保留调用名称，未配置受限 Runner 时明确失败。"""
