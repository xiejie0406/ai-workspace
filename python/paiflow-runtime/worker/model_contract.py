"""共享模型契约的运行时边界；不持有操作/许可/资源状态。"""
import json
import re

from .errors import CenterUnavailable, WorkerError

BINDING_FIELDS = frozenset({'runId', 'leaseId', 'attempt', 'nodeId', 'iterationId',
    'workflowVersionId', 'dslHash', 'operationId', 'resourceId', 'resourceVersion', 'provider'})
STATES = frozenset({'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EFFECT_UNKNOWN'})
FAILURES = frozenset({'provider', 'authorization', 'protocol', 'transport', 'deadline', 'lease', 'cancelled', 'budget'})
FRAME_FIELDS = frozenset({'state', 'operationState', 'frames', 'nextCursor', 'done', 'lastSeq', 'finishSeq', 'failureKind'})


def json_bytes(value):
    try:
        return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')
    except (ValueError, TypeError, UnicodeError):
        raise WorkerError('DSL_INVALID', '模型数据不是合法有界JSON') from None


def check_binding(data, expected, fields):
    if not isinstance(data, dict) or set(data) != BINDING_FIELDS | set(fields):
        raise CenterUnavailable()
    for name in BINDING_FIELDS - {'attempt', 'iterationId'}:
        if (not isinstance(data[name], str) or not data[name]
                or len(data[name]) > (128 if name == 'operationId' else 256)):
            raise CenterUnavailable()
    if data['provider'] not in {'openai', 'xinghuo'} or not re.fullmatch('[a-f0-9]{64}', data['dslHash']):
        raise CenterUnavailable()
    if type(data['attempt']) is not int or data['attempt'] < 1:
        raise CenterUnavailable()
    if data['iterationId'] is not None and (not isinstance(data['iterationId'], str)
            or not data['iterationId'] or len(data['iterationId']) > 256):
        raise CenterUnavailable()
    for name, value in expected.items():
        if type(data.get(name)) is not type(value) or data[name] != value:
            raise WorkerError('VERSION_CONFLICT', '模型操作回执完整绑定不一致')
    return data


def check_frames(data, expected, cursor, limit=64):
    check_binding(data, expected, FRAME_FIELDS)
    if (not isinstance(data['state'], str) or not isinstance(data['operationState'], str)
            or data['state'] not in STATES or data['operationState'] not in STATES
            or type(data['done']) is not bool or type(data['nextCursor']) is not int
            or type(data['lastSeq']) is not int or not 0 <= data['nextCursor'] <= data['lastSeq'] <= 10000
            or not isinstance(data['frames'], list) or len(data['frames']) > limit
            or data['nextCursor'] != cursor + len(data['frames'])
            or data['failureKind'] is not None and (not isinstance(data['failureKind'], str) or data['failureKind'] not in FAILURES)
            or data['operationState'] == 'FAILED' and data['failureKind'] is None):
        raise CenterUnavailable()
    finish = data['finishSeq']
    if finish is not None and (type(finish) is not int or not 1 <= finish <= data['lastSeq']):
        raise CenterUnavailable()
    at_end = data['nextCursor'] == data['lastSeq']
    terminal = data['operationState'] != 'RUNNING'
    if (data['done'] != (at_end and terminal)
            or data['state'] != (data['operationState'] if at_end and terminal else 'RUNNING')):
        raise CenterUnavailable()
    if data['operationState'] == 'COMPLETED' and (finish is None or data['failureKind'] is not None):
        raise CenterUnavailable()
    for frame in data['frames']:
        if not isinstance(frame, dict) or len(json_bytes(frame)) > 64 * 1024:
            raise WorkerError('FRAME_BUDGET_EXCEEDED', '模型原帧超过共享预算')
    return data


def check_cancel(data, expected):
    check_binding(data, expected, {'state', 'stopped', 'proofId'})
    if (not isinstance(data['state'], str) or data['state'] not in STATES or type(data['stopped']) is not bool
            or data['proofId'] is not None and (not isinstance(data['proofId'], str) or not data['proofId'])):
        raise CenterUnavailable()
    if data['stopped'] and (data['state'] not in {'COMPLETED', 'FAILED', 'CANCELLED'} or not data['proofId']):
        raise CenterUnavailable()
    if not data['stopped']:
        raise WorkerError('EFFECT_UNKNOWN', '中心模型停止未确认')
    return data
