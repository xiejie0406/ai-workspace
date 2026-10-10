"""Agent 原算法的公共 DTO 与受控诊断；不初始化原配置/数据库/服务。"""
from common.exceptions.base import BaseExc
from common.otlp.log_trace.base import Usage
from common.otlp.log_trace.node_log import Data, NodeLog
from common.otlp.log_trace.node_trace_log import NodeTraceLog, Status as TraceStatus
from worker.trace import WorkerSpan as Span
from worker.errors import WorkerError
from loguru import logger

NodeTrace = NodeTraceLog
Node = NodeLog
NodeData = Data
NodeDataUsage = Usage
DEVELOPMENT_ENV = DevelopmentEnv = 'development'
PRODUCTION_ENV = ProductionEnv = 'production'


def initialize_services(*args, **kwargs):
    raise WorkerError('RESOURCE_UNAVAILABLE', 'Worker 不初始化旧 Agent 数据库/配置/计量服务')


def __getattr__(name):
    if name in {'Meter', 'Polaris', 'ConfigFilter', 'sid_generator2', 'local_ip', 'ip'}:
        raise WorkerError('RESOURCE_UNAVAILABLE', '旧 Agent 基础设施必须改为中心受控端口')
    raise AttributeError(name)