"""原 Span 接口的有界本地诊断上下文。业务事件仍实时等待中心 ACK。

诊断可由运行结束后的中心 trace/artifact 端口保存；不初始化 DB/OSS/OTLP，
不写日志文件，不把遥测成功作为节点业务成功。
"""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone

from common.utils.secret_reference import redact_sensitive_data


class WorkerSpan:
    def __init__(self, app_id="", uid="", chat_id="", *, sid="", records=None):
        self.app_id, self.uid, self.chat_id, self.sid = app_id, uid, chat_id, sid
        self.records = records if records is not None else []
        self.attributes = {}

    @contextmanager
    def start(self, func_name="", add_source_function_name=False, attributes=None, trace_context=None):
        child = WorkerSpan(self.app_id, self.uid, self.chat_id, sid=self.sid, records=self.records)
        if attributes:
            child.set_attributes(attributes)
        child.add_event("scope.start", {"function": func_name})
        try:
            yield child
        finally:
            child.add_event("scope.end", {"function": func_name})

    def set_attribute(self, key, value, node_log=None):
        self.attributes.update(redact_sensitive_data({key: value}))

    def set_attributes(self, attributes):
        self.attributes.update(redact_sensitive_data(attributes))

    def set_status(self, status):
        self.add_event("status", {"type": type(status).__name__})

    def set_code(self, code, node_log=None):
        self.set_attribute("code", code)

    def record_exception(self, exception, node_log=None, **kwargs):
        # 异常字符串可能含供应商完整 URL/令牌，仅记录类型；错误分类由节点结果保存。
        self.add_event("exception", {"type": type(exception).__name__})

    def add_event(self, name, attributes=None, **kwargs):
        if len(self.records) >= 256:
            self.records.pop(0)
        safe = redact_sensitive_data(attributes or {})
        self.records.append({"name": name, "timestamp": datetime.now(timezone.utc).isoformat(), "attributes": safe})

    def add_info_event(self, value=None, node_log=None, *, attributes=None):
        # 原 engine 会把 config/输入序列化成不透明字符串；避免绕过结构化脱敏。
        self.add_event("info", {"valueType": type(value).__name__})

    def add_info_events(self, value=None, node_log=None, *, attributes=None):
        data = attributes if attributes is not None else value
        self.add_event("info", data if isinstance(data, dict) else {"valueType": type(data).__name__})

    def add_error_event(self, value=None, node_log=None, *, attributes=None):
        self.add_event("error", {"valueType": type(value).__name__})

    def add_error_events(self, value=None, node_log=None, *, attributes=None):
        self.add_error_event(attributes if attributes is not None else value, node_log)
