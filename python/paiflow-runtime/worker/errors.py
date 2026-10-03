"""对外错误只含固定原因；不回传供应商凭据或远端响应原文。"""


class WorkerError(RuntimeError):
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(message)


class LeaseLost(WorkerError):
    def __init__(self):
        super().__init__("LEASE_LOST", "中心租约已失效，执行已停止")


class CenterUnavailable(WorkerError):
    def __init__(self):
        super().__init__("RESOURCE_UNAVAILABLE", "中心接口不可用，未确认的业务结果不能记为成功")
