"""阻止原控制面/正式数据库进入迁移后的 Worker。

这是本进程导入约束，不冒称 OS 沙箱。代码节点另外交给已登记沙箱协议。
"""
from __future__ import annotations

import importlib.abc
import sys

from .errors import WorkerError


BLOCKED_PREFIXES = (
    "workflow.repository", "workflow.domain.models", "workflow.extensions.middleware.database",
    "workflow.extensions.middleware.masdk", "workflow.cache",
    "agent.repository", "agent.domain.models.bot_config_table", "agent.cache",
    "repository",  # 原 Agent 使用的无包名前缀入口
    "plugin.link.domain.models", "plugin.link.infra.tool_crud",
    "common.service.db", "common.metrology_auth", "common.initialize",
    "workflow.service.app_service", "workflow.service.flow_service", "workflow.service.history_service",
)


class LegacyControlPlaneDenied(ImportError, WorkerError):
    def __init__(self, module: str):
        self.code = "RESOURCE_UNAVAILABLE"
        ImportError.__init__(self, f"旧控制面 {module} 已封禁，必须通过运行范围内的中心接口")


class _Guard(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if any(fullname == prefix or fullname.startswith(prefix + ".") for prefix in BLOCKED_PREFIXES):
            raise LegacyControlPlaneDenied(fullname)
        return None


def install_legacy_guard() -> None:
    if not any(isinstance(finder, _Guard) for finder in sys.meta_path):
        sys.meta_path.insert(0, _Guard())
