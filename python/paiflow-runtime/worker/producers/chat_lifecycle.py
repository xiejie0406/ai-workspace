"""Chat Producer 的有界清理与中心回执核对；没有本地业务状态。"""
import asyncio
import contextvars
from functools import wraps
import json
import logging
import math

from worker.errors import WorkerError
from worker.events import _validate_payload
from worker.producers.spark_chat import FailureFacts, FrameCommit, StopConfirmation

_private_sdk = contextvars.ContextVar("paiflow_private_chat_sdk", default=False)


class _PrivateSDKLogFilter(logging.Filter):
    def filter(self, record):
        return not _private_sdk.get()


# SDK自身会记URL/options/exception；只抑制本次任务的已加载SDK日志，
# 不改变其他任务日志级别。真实私有transport还须禁止其自身出口日志。
_sdk_filter = _PrivateSDKLogFilter()


def _install_sdk_filters():
    # OpenAI的部分模块懒加载；在真正使用SDK前也核对logger，不能只在import时挂filter。
    for name in ("openai._base_client", "openai._streaming", "httpx"):
        logging.getLogger(name)
    for name, logger in list(logging.Logger.manager.loggerDict.items()):
        if isinstance(logger, logging.Logger) and name.split(".", 1)[0] in {"openai", "httpx", "httpcore"}:
            if _sdk_filter not in logger.filters:
                logger.addFilter(_sdk_filter)


def quiet_sdk(function):
    @wraps(function)
    async def scoped(*args, **kwargs):
        _install_sdk_filters()
        token = _private_sdk.set(True)
        try:
            return await function(*args, **kwargs)
        finally:
            _private_sdk.reset(token)
    return scoped


class ChatBoundaryError(WorkerError):
    def __init__(self, code, failure_kind):
        super().__init__(code, "Chat Producer 边界未完成，须由中心核对")
        self.failure_kind = failure_kind
        self.send_attempted = False
        self.local_closed = False
        self.provider_stop_confirmed = False


def number(value, upper):
    try:
        return type(value) in {int, float} and math.isfinite(value) and 0 < value <= upper
    except OverflowError:
        return False


def json_value(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result
    def constant(_value):
        raise ValueError("nonfinite JSON")
    try:
        value = json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)
        _validate_payload(value)
        json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
        return value
    except (ValueError, TypeError, UnicodeError, RecursionError, WorkerError):
        raise ChatBoundaryError("DSL_INVALID", "protocol") from None


def contains_private(value, materials):
    if isinstance(value, str):
        return any(material and material in value for material in materials)
    if isinstance(value, dict):
        return any(contains_private(k, materials) or contains_private(v, materials) for k, v in value.items())
    if isinstance(value, list):
        return any(contains_private(v, materials) for v in value)
    return False


def binding(receipt, grant):
    return (receipt.operation_id == grant.operation_id and receipt.resource_id == grant.config.resource_id
            and receipt.resource_version == grant.config.version and receipt.provider == grant.config.provider)


def commit_ack(receipt, grant, sequence, state, done, failure_kind=None):
    if (not isinstance(receipt, FrameCommit) or not binding(receipt, grant)
            or type(receipt.sequence) is not int or receipt.sequence != sequence
            or type(receipt.done) is not bool or receipt.done != done
            or receipt.state != state or receipt.failure_kind != failure_kind):
        raise ChatBoundaryError("RESOURCE_UNAVAILABLE", "protocol")
    return receipt


async def cleanup(center, grant, handles, *, grant_valid, sent, terminal_known, error):
    """先清理本地句柄，再独立核对Provider停止；record不能覆盖已知中心终态。"""
    local_closed = True
    for handle in handles:
        if handle is not None:
            try:
                close = getattr(handle, "aclose", None) or getattr(handle, "close", None)
                if close is None:
                    raise TypeError("missing async close")
                await asyncio.wait_for(close(), grant.config.limits.close_seconds)
            except BaseException:
                local_closed = False
    if error is None and not local_closed:
        error = ChatBoundaryError("PRODUCER_CLEANUP_UNKNOWN", "transport")
    if error is None:
        return local_closed
    error.send_attempted, error.local_closed = sent, local_closed
    if sent and not terminal_known:
        try:
            receipt = await asyncio.wait_for(center.confirm_stop(grant, error.failure_kind),
                                             grant.config.limits.close_seconds)
            error.provider_stop_confirmed = (isinstance(receipt, StopConfirmation) and binding(receipt, grant)
                and receipt.state in {"CANCELLED", "COMPLETED", "FAILED"}
                and receipt.provider_stopped is True and isinstance(receipt.proof_id, str) and bool(receipt.proof_id))
        except BaseException:
            error.provider_stop_confirmed = False
        if not error.provider_stop_confirmed or not local_closed:
            error.code = "EFFECT_UNKNOWN"
    if grant_valid:
        try:
            await asyncio.wait_for(center.record_failure(grant, FailureFacts(error.failure_kind, error.code,
                sent, local_closed, error.provider_stop_confirmed)), grant.config.limits.close_seconds)
        except BaseException:
            if sent and not terminal_known:
                error.code = "EFFECT_UNKNOWN"
    raise error from None
