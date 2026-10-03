"""节点/迭代的瞬时关联信息，正式状态只保存在中心。"""
from contextlib import contextmanager
from contextvars import ContextVar


CURRENT_ITERATION_ID = ContextVar("paiflow_iteration_id", default=None)


@contextmanager
def iteration_scope(node_id, index):
    parent = CURRENT_ITERATION_ID.get()
    identifier = f"{node_id}:{index}"
    if parent:
        identifier = parent + "/" + identifier
    token = CURRENT_ITERATION_ID.set(identifier)
    try:
        yield identifier
    finally:
        CURRENT_ITERATION_ID.reset(token)
