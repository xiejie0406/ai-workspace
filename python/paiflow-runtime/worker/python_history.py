"""仅缓存当前租约的中心旧历史快照；正式历史与输出真相由中心持有。"""
from __future__ import annotations

import copy
import hashlib
import json
import re
from pydantic import TypeAdapter, ValidationError

from .errors import WorkerError
from .node_context import CURRENT_ITERATION_ID

ACK_KEYS = frozenset({'history', 'historyV2', 'historyRef', 'historyHash', 'snapshot'})
SNAPSHOT_KEYS = frozenset({'runId', 'leaseId', 'attempt', 'workflowVersionId', 'dslHash',
    'spaceId', 'ownerUserId', 'workflowId', 'flowId', 'userId', 'appId', 'chatId',
    'inputHash', 'history', 'historyV2'})
MAX_HISTORY_BYTES = 1800 * 1024
_READ_BOOL = TypeAdapter(bool)  # 原BaseLLMNode.enableChatHistory的Pydantic bool字段。


def _invalid(message):
    return WorkerError('HISTORY_PROTOCOL_INVALID', message)


def _bounded(value, depth=0):
    if depth > 32:
        raise _invalid('旧历史结构超出深度预算')
    if isinstance(value, dict):
        if any(not isinstance(key, str) for key in value):
            raise _invalid('旧历史对象键无效')
        for item in value.values():
            _bounded(item, depth + 1)
    elif isinstance(value, list):
        for item in value:
            _bounded(item, depth + 1)
    elif value is not None and type(value) not in (str, int):
        raise _invalid('旧历史快照包含非正式标量')


def canonical(value):
    _bounded(value)
    try:
        return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True,
                          separators=(',', ':')).encode('utf-8')
    except (ValueError, UnicodeError, TypeError):
        raise _invalid('旧历史不是合法UTF8快照') from None


def legacy_nodes(context):
    return {node['id'] for node in context.assignment.dsl['nodes']
            if node['id'].startswith('spark-llm::')
            and bool(node['data'].get('nodeParam', {}).get('enableChatHistory', False))}


def read_nodes(context):
    # 原读取由typed节点字段决定，原追加由NodeExecutionTemplate的raw truthiness决定。
    # "false"/"0"读取关闭但仍会追加，不能统一改成boolean语义。
    try:
        return {node['id'] for node in context.assignment.dsl['nodes']
                if node['id'].startswith('spark-llm::')
                and _READ_BOOL.validate_python(node['data'].get('nodeParam', {}).get('enableChatHistory', False))}
    except ValidationError:
        raise WorkerError('DSL_INVALID', '旧历史配置未通过原Pydantic bool字段校验') from None


def validate_ack(context, response):
    task = context.assignment
    binding = context.execution_context
    if (not isinstance(binding, dict) or set(binding) != {'flowId', 'userId', 'appId', 'chatId'}
            or any(not isinstance(binding[key], str) or not 1 <= len(binding[key]) <= 128
                   for key in ('flowId', 'userId', 'appId'))
            or not isinstance(binding['chatId'], str) or len(binding['chatId']) > 128):
        raise _invalid('旧历史缺少正式执行上下文')
    if not isinstance(response, dict) or set(response) != ACK_KEYS or len(canonical(response)) > MAX_HISTORY_BYTES:
        raise _invalid('旧历史回执字段或字节预算无效')
    ref, digest, snapshot = response['historyRef'], response['historyHash'], response['snapshot']
    if (not isinstance(ref, str) or not 1 <= len(ref) <= 128
            or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in ref)
            or not isinstance(digest, str) or not re.fullmatch(r'[0-9a-f]{64}', digest)
            or not isinstance(snapshot, dict) or set(snapshot) != SNAPSHOT_KEYS
            or hashlib.sha256(canonical(snapshot)).hexdigest() != digest):
        raise _invalid('旧历史完整快照或hash无效')
    expected = {'runId': task.run_id, 'leaseId': task.lease_id, 'attempt': task.attempt,
                'dslHash': task.hash, **binding}
    if any(snapshot[key] != value or type(snapshot[key]) is not type(value) for key, value in expected.items()):
        raise _invalid('旧历史运行/租约/会话绑定不一致')
    if (any(not isinstance(snapshot[key], str) or not 1 <= len(snapshot[key]) <= 64
            for key in ('runId', 'leaseId')) or type(snapshot['attempt']) is not int
            or snapshot['attempt'] < 1):
        raise _invalid('旧历史运行/租约或attempt预算无效')
    if (type(snapshot['workflowVersionId']) is not int
            or not 1 <= snapshot['workflowVersionId'] <= 9223372036854775807
            or str(snapshot['workflowVersionId']) != task.version_id):
        raise _invalid('旧历史固定工作流版本不一致')
    for key, lower in [('spaceId', 0), ('ownerUserId', 1), ('workflowId', 1)]:
        if type(snapshot[key]) is not int or not lower <= snapshot[key] <= 9223372036854775807:
            raise _invalid('旧历史正式主体或空间整数无效')
    if binding['userId'] != 'ruoyi:' + str(snapshot['ownerUserId']):
        raise _invalid('旧历史主体与执行上下文不一致')
    if not isinstance(snapshot['inputHash'], str) or not re.fullmatch(r'[0-9a-f]{64}', snapshot['inputHash']):
        raise _invalid('旧历史固定输入hash无效')
    if response['historyV2'] != [] or snapshot['historyV2'] != []:
        raise WorkerError('ENGINE_UNSUPPORTED', 'Python V2历史权威来源尚未接通')
    if (response['history'] != snapshot['history'] or not isinstance(snapshot['history'], list)
            or len(snapshot['history']) > 1000):
        raise _invalid('旧历史顶层数组偏离冻结快照')
    allowed = read_nodes(context)
    seen = set()
    for item in snapshot['history']:
        if (not isinstance(item, dict) or set(item) != {'nodeID', 'chat_history'}
                or not isinstance(item['nodeID'], str) or not 1 <= len(item['nodeID']) <= 255
                or item['nodeID'] not in allowed or item['nodeID'] in seen
                or not isinstance(item['chat_history'], list) or len(item['chat_history']) > 20
                or len(item['chat_history']) % 2):
            raise _invalid('旧历史节点归属/重复/窗口无效')
        seen.add(item['nodeID'])
        for index, message in enumerate(item['chat_history']):
            if (not isinstance(message, dict) or set(message) != {'role', 'content'}
                    or message['role'] != ('user' if index % 2 == 0 else 'assistant')
                    or not isinstance(message['content'], str)):
                raise _invalid('旧历史文本角色或原序无效')
    return copy.deepcopy(response)


async def read_snapshot(context):
    context.assert_active()
    if context.execution_context is None:
        raise WorkerError('RESOURCE_UNAVAILABLE', '旧历史缺少中心正式执行上下文')
    if CURRENT_ITERATION_ID.get() is not None:
        raise WorkerError('ENGINE_UNSUPPORTED', '嵌套Python历史尚未接通')
    async with context.python_history_lock:
        context.assert_active()
        if context.python_history_cache is None:
            try:
                task = context.assignment
                response = await context.center.read_python_history(task.run_id, task.lease_id, task.attempt)
                context.assert_active()
                context.python_history_cache = validate_ack(context, response)
            except WorkerError as error:
                context.boundary_failure = error
                raise
        # 原VariablePool可改返回数组，不能与冻结许可证明共享引用。
        return copy.deepcopy(context.python_history_cache)


def require_snapshot(context, node_id):
    context.assert_active()
    if CURRENT_ITERATION_ID.get() is not None or node_id not in legacy_nodes(context):
        raise WorkerError('ENGINE_UNSUPPORTED', '节点未启用顶层普通LLM旧历史')
    if context.python_history_cache is None:
        raise _invalid('旧历史操作缺少中心冻结读取')
    return validate_ack(context, context.python_history_cache)


def check_model_messages(context, node_id, messages):
    if node_id not in legacy_nodes(context):
        return
    require_snapshot(context, node_id)
    # 原旧历史仅供chatHistory显示，不进入Provider；当前提示精确来源仍由中心许可重建。
    if (not isinstance(messages, list) or [m.get('role') for m in messages if isinstance(m, dict)]
            not in (['user'], ['system', 'user']) or any(not isinstance(m, dict)
                or set(m) != {'role', 'content'} or not isinstance(m['content'], str) for m in messages)):
        raise _invalid('旧历史不能改写原当前SYSTEM/USER模型消息')


def check_completed_inputs(context, node_id, inputs):
    if node_id not in legacy_nodes(context):
        return
    fixed = require_snapshot(context, node_id)
    old = next((item['chat_history'] for item in fixed['history'] if item['nodeID'] == node_id), [])
    from workflow.infra.providers.llm.iflytek_spark.schemas import SparkAiMessage
    display = [SparkAiMessage(**message).model_dump() for message in old]
    if not isinstance(inputs, dict) or (old and inputs.get('chatHistory') != display) or (not old and 'chatHistory' in inputs):
        raise _invalid('原节点chatHistory展示偏离中心冻结旧历史')
