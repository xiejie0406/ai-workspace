"""运行范围中心客户端。只通过配置的中心 origin 请求，不接受任意目标 URL。

接口必须返回若依 AjaxResult；网络/授权/协议失败均明确拒绝。重试由中心的
租约、幂等键和人工核对策略决定，这里不自动重放有费用或写入的操作。
"""
from __future__ import annotations

import asyncio
import json
import re
from typing import Any, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from .errors import CenterUnavailable, LeaseLost, WorkerError
from .model_contract import check_binding, check_cancel, check_frames, json_bytes


class Transport(Protocol):
    async def request(self, method: str, url: str, headers: dict[str, str], body: bytes | None,
                      timeout: float, max_response_bytes: int) -> tuple[int, bytes]: ...


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class JsonHttpTransport:
    """标准库 HTTP 传输，阻断携带服务令牌的重定向。"""
    async def request(self, method, url, headers, body, timeout, max_response_bytes):
        def send():
            try:
                with build_opener(_NoRedirect()).open(Request(url, body, headers, method=method), timeout=timeout) as response:
                    data = response.read(max_response_bytes + 1)
                    return response.status, data
            except HTTPError as error:
                # 不读/回传错误正文，防止响应包含远端凭据或栈。
                return error.code, b""
            except (URLError, TimeoutError, OSError):
                raise CenterUnavailable() from None
        return await asyncio.to_thread(send)


class CenterClient:
    def __init__(self, base_url: str, service_token: str, *, transport: Transport | None = None,
                 timeout: float = 10.0, max_response_bytes: int = 2 * 1024 * 1024):
        parsed = urlsplit(base_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password
                or parsed.query or parsed.fragment or ".." in parsed.path.split("/")):
            raise ValueError("中心地址必须是无凭据、无查询参数的 http/https 固定地址")
        if not service_token or any(character.isspace() for character in service_token):
            raise ValueError("Worker 服务身份不能为空或包含空白")
        if not 0 < timeout <= 60 or not 1 <= max_response_bytes <= 16 * 1024 * 1024:
            raise ValueError("中心请求超时/响应上限超出允许范围")
        self._base_url = base_url.rstrip("/") + "/internal/paiflow/v1"
        self._headers = {"Authorization": "Bearer " + service_token, "Content-Type": "application/json", "Accept": "application/json"}
        self._transport = transport or JsonHttpTransport()
        self._timeout = timeout
        self._max_response_bytes = max_response_bytes

    @staticmethod
    def _id(value: str) -> str:
        if not isinstance(value, str) or not value or value in {".", ".."} or len(value) > 256:
            raise WorkerError("DSL_INVALID", "运行或资源标识无效")
        return quote(value, safe="")

    async def _request(self, method: str, path: str, body: dict[str, Any] | None = None, *, strict_json=False) -> Any:
        payload = json.dumps(body, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8") if body is not None else None
        status, raw = await self._transport.request(method, self._base_url + path, dict(self._headers), payload,
                                                    self._timeout, self._max_response_bytes)
        if status in {403, 409} and ("/resources/" in path or path.endswith("/operations")):
            raise WorkerError("TOOL_POLICY_DENIED" if status == 403 else "VERSION_CONFLICT", "中心资源/操作策略或版本拒绝本次请求")
        if status in {401, 403, 409, 410}:
            raise LeaseLost()
        if status < 200 or status >= 300 or len(raw) > self._max_response_bytes:
            raise CenterUnavailable()
        try:
            if strict_json:
                from .task_runner import _unique_object, _deny_constant
                response = json.loads(raw, object_pairs_hook=_unique_object, parse_constant=_deny_constant)
            else:
                response = json.loads(raw)
        except (ValueError, UnicodeError):
            raise CenterUnavailable() from None
        if not isinstance(response, dict) or response.get("code") != 200 or "data" not in response:
            raise CenterUnavailable()
        return response["data"]

    def _run(self, run_id: str) -> str:
        return "/runs/" + self._id(run_id)

    async def claim(self) -> dict[str, Any] | None:
        """领取中心正式 Python 任务；空队列返回 None，不生成本地任务。"""
        data = await self._request("POST", "/runs/claim", {"engineProfile": "python"}, strict_json=True)
        if data is None:
            return None
        if not isinstance(data, dict) or data.get("engineProfile") != "python":
            raise CenterUnavailable()
        return data

    async def verify_lease(self, run_id: str, lease_id: str, attempt: int) -> dict[str, Any]:
        data = await self._request("POST", self._run(run_id) + "/lease/verify", {"leaseId": lease_id, "attempt": attempt})
        if not isinstance(data, dict) or data.get("valid") is not True:
            raise LeaseLost()
        return data

    async def heartbeat(self, run_id: str, lease_id: str, attempt: int) -> dict[str, Any]:
        data = await self._request("POST", self._run(run_id) + "/heartbeat", {"leaseId": lease_id, "attempt": attempt})
        if not isinstance(data, dict) or data.get("valid") is not True:
            raise LeaseLost()
        return data

    async def begin_execution(self, run_id, lease_id, attempt):
        if not isinstance(lease_id, str) or not lease_id or type(attempt) is not int or attempt < 1:
            raise WorkerError("DSL_INVALID", "执行许可的租约/attempt无效")
        data = await self._request("POST", self._run(run_id) + "/execution/begin", {"leaseId": lease_id, "attempt": attempt})
        if (not isinstance(data, dict) or set(data) != {"begun", "runId", "leaseId", "attempt", "workflowVersionId", "dslHash"}
                or data["begun"] is not True or data["runId"] != run_id or data["leaseId"] != lease_id
                or type(data["attempt"]) is not int or data["attempt"] != attempt
                or not isinstance(data["workflowVersionId"], str) or not data["workflowVersionId"]
                or not isinstance(data["dslHash"], str) or not re.fullmatch(r"[a-f0-9]{64}", data["dslHash"])):
            raise CenterUnavailable()
        return data

    async def append_event(self, run_id: str, event: dict[str, Any]) -> dict[str, Any]:
        data = await self._request("POST", self._run(run_id) + "/events", event)
        if not isinstance(data, dict) or type(data.get("seq")) is not int or data.get("seq") != event["seq"] or data.get("accepted") is not True:
            raise CenterUnavailable()
        return data

    async def complete(self, run_id: str, result: dict[str, Any]) -> dict[str, Any]:
        data = await self._request("POST", self._run(run_id) + "/result", result)
        if not isinstance(data, dict) or data.get("accepted") is not True or data.get("state") != result["state"]:
            raise CenterUnavailable()
        return data

    async def resource(self, run_id: str, lease_id: str, attempt: int, resource_id: str) -> dict[str, Any]:
        from .tool_contract import formal_tool, check_resource
        resource_path = quote(resource_id, safe='') if formal_tool(resource_id) and len(resource_id) <= 1024 else self._id(resource_id)
        data = await self._request("POST", self._run(run_id) + "/resources/" + resource_path,
                                   {"leaseId": lease_id, "attempt": attempt})
        if not isinstance(data, dict):
            raise CenterUnavailable()
        if data.get('kind') == 'model':
            if (set(data) != {'kind', 'provider', 'version', 'modelName', 'enabled', 'capabilities'}
                    or data.get('provider') not in {'openai', 'xinghuo'} or data.get('enabled') is not True
                    or not (data.get('capabilities') == ['chat'] or data.get('provider') == 'xinghuo'
                            and data.get('capabilities') == ['chat', 'function_call'])
                    or any(not isinstance(data.get(k), str) or not data[k] or len(data[k]) > 256 for k in ('version', 'modelName'))):
                raise CenterUnavailable()
        if formal_tool(resource_id):
            check_resource(resource_id, data)
        return data

    @staticmethod
    def _tool_binding(run_id, lease_id, attempt, node_id, operation, parameters, key, *, stopping=False, operation_id=None):
        from .task_runner import CURRENT_CONTEXT, current_context
        from .tool_contract import check_resource, check_parameters
        context = CURRENT_CONTEXT.get() if stopping else current_context()
        if context is None:
            raise WorkerError('RESOURCE_UNAVAILABLE', '工具HTTP缺少已验证中心运行')
        task = context.assignment
        from .node_context import CURRENT_ITERATION_ID
        if (task.run_id != run_id or task.lease_id != lease_id or type(attempt) is not int or task.attempt != attempt
                or task.execution_codec != 'paiflow.execution.v4'
                or CURRENT_ITERATION_ID.get() is not None or not isinstance(node_id, str) or node_id not in context.node_ids
                or not node_id.startswith('plugin::') or operation not in {'tools.read', 'tools.invoke'}
                or operation not in task.effect_policy['allowedOperations']
                or task.effect_policy['mode'] == 'read_only' and operation != 'tools.read'
                or not isinstance(key, str) or not 1 <= len(key) <= 4096
                or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in key)
                or not isinstance(parameters, dict)
                or set(parameters) != {'kind', 'provider', 'resourceId', 'resourceVersion', 'legacyPayload', 'resourceBindings'}
                or not isinstance(parameters.get('resourceId'), str)):
            raise WorkerError('TOOL_POLICY_DENIED', '工具请求不属于本次节点/租约/操作策略')
        descriptor = task.resources.get(parameters['resourceId'])
        check_resource(parameters['resourceId'], descriptor)
        node = next(n for n in task.dsl['nodes'] if n['id'] == node_id)
        fixed = node['data'].get('nodeParam', {})
        if (node['data'].get('nodeMeta', {}).get('nodeType') != 'plugin'
                or fixed.get('toolResourceId') != parameters['resourceId']
                or fixed.get('pluginId') != descriptor['pluginId'] or fixed.get('operationId') != descriptor['operationId']
                or fixed.get('version', 'V1.0') != descriptor['legacyVersion'] or fixed.get('appId') != descriptor['legacyAppId']
                or parameters['kind'] != 'tool' or parameters['provider'] != 'spark-link'
                or parameters['resourceVersion'] != descriptor['version'] or parameters['resourceBindings'] != {}
                or (operation == 'tools.read') != (descriptor['effectClass'] == 'read')):
            raise WorkerError('VERSION_CONFLICT', '工具调用与节点固定资源/版本不一致')
        check_parameters(parameters, descriptor)
        expected = {'runId': run_id, 'leaseId': lease_id, 'attempt': attempt, 'workflowVersionId': task.version_id,
            'dslHash': task.hash, 'nodeId': node_id, 'iterationId': None, 'idempotencyKey': key, 'operation': operation,
            'kind': 'tool', 'provider': 'spark-link', 'resourceId': parameters['resourceId'],
            'resourceVersion': parameters['resourceVersion'], 'resourceBindings': {}}
        if operation_id is not None:
            from .tool_contract import OPERATION_PATTERN
            if not isinstance(operation_id, str) or not OPERATION_PATTERN.fullmatch(operation_id):
                raise WorkerError('DSL_INVALID', '工具操作标识无效')
            expected['operationId'] = operation_id
        return expected

    async def tool_status(self, run_id, lease_id, attempt, operation_id, *, node_id, operation, parameters, key):
        from .tool_contract import check_ack
        expected = self._tool_binding(run_id, lease_id, attempt, node_id, operation, parameters, key, operation_id=operation_id)
        data = await self._request('POST', self._run(run_id) + '/operations/' + self._id(operation_id) + '/status',
            {'leaseId': lease_id, 'attempt': attempt}, strict_json=True)
        return check_ack(data, expected)

    async def cancel_tool_operation(self, run_id, lease_id, attempt, operation_id, *, node_id, operation, parameters, key):
        from .tool_contract import check_ack
        expected = self._tool_binding(run_id, lease_id, attempt, node_id, operation, parameters, key,
            stopping=True, operation_id=operation_id)
        data = await self._request('POST', self._run(run_id) + '/operations/' + self._id(operation_id) + '/cancel',
            {'leaseId': lease_id, 'attempt': attempt}, strict_json=True)
        return check_ack(data, expected, cancel=True)

    @staticmethod
    def _model_binding(run_id, lease_id, attempt, *, stopping=False, **fields):
        # 绑定取自本次已验证运行，客户端不建立第二套操作缓存或许可。
        from .task_runner import CURRENT_CONTEXT, current_context
        context = CURRENT_CONTEXT.get() if stopping else current_context()
        if context is None:
            raise WorkerError('RESOURCE_UNAVAILABLE', '模型HTTP须在已验证中心运行内调用')
        task = context.assignment
        if task.run_id != run_id or task.lease_id != lease_id or type(attempt) is not int or task.attempt != attempt:
            raise WorkerError('VERSION_CONFLICT', '模型请求与当前运行租约不一致')
        from .node_context import CURRENT_ITERATION_ID
        return {'runId': run_id, 'leaseId': lease_id, 'attempt': attempt,
                'workflowVersionId': task.version_id, 'dslHash': task.hash,
                'iterationId': CURRENT_ITERATION_ID.get(), **fields}

    @staticmethod
    def _check_model_resource(data, *, stopping=False):
        from .task_runner import CURRENT_CONTEXT, current_context
        # 停止回读不能被此前粘性失败阻断；仍完整核对本租约/节点/资源版本，不授权新操作。
        context = CURRENT_CONTEXT.get() if stopping else current_context()
        if context is None:
            raise WorkerError('RESOURCE_UNAVAILABLE', '模型回执缺少当前运行上下文')
        fixed = context.assignment.resources.get(data.get('resourceId'))
        if (data.get('nodeId') not in context.node_ids or not isinstance(fixed, dict)
                or fixed.get('kind') != 'model' or fixed.get('enabled') is not True
                or fixed.get('provider') != data.get('provider') or fixed.get('version') != data.get('resourceVersion')):
            raise WorkerError('VERSION_CONFLICT', '模型回执不属于当前节点/固定资源版本')
        node = next(item for item in context.assignment.dsl['nodes'] if item['id'] == data['nodeId'])
        node_parameters = node['data'].get('nodeParam', {})
        if (node_parameters.get('modelResourceId') != data.get('resourceId')
                or node_parameters.get('source', 'xinghuo') != data.get('provider')
                or node_parameters.get('domain') != fixed.get('modelName')):
            raise WorkerError('VERSION_CONFLICT', '模型回执资源不属于该节点的固定引用')

    async def operation(self, run_id: str, lease_id: str, attempt: int, node_id: str,
                        operation: str, parameters: dict[str, Any], idempotency_key: str) -> Any:
        # operation 只作为 body 数据；中心按快照、节点和审批逐次授权。
        body = {
            "leaseId": lease_id, "attempt": attempt, "nodeId": node_id,
            "operation": operation, "parameters": parameters, "idempotencyKey": idempotency_key}
        from .tool_contract import formal_tool, check_ack
        if operation in {'tools.read', 'tools.invoke'} and isinstance(parameters, dict) and formal_tool(parameters.get('resourceId')):
            expected = self._tool_binding(run_id, lease_id, attempt, node_id, operation, parameters, idempotency_key)
            body['iterationId'] = None
            from .events import _validate_payload
            _validate_payload(body)
            if len(json_bytes(body)) > 2 * 1024 * 1024:
                raise WorkerError('DSL_INVALID', '工具操作请求超过正式契约预算')
            data = await self._request('POST', self._run(run_id) + '/operations', body, strict_json=True)
            return check_ack(data, expected)
        if operation != 'model.invoke':
            return await self._request('POST', self._run(run_id) + '/operations', body)
        from .node_context import CURRENT_ITERATION_ID
        from .task_runner import current_context
        context = current_context()
        kind = node_id.split('::', 1)[0] if isinstance(node_id, str) else None
        dynamic = kind in {'extractor-parameter', 'decision-making', 'question-answer'}
        if (kind != 'spark-llm' and not (dynamic and context.assignment.execution_codec == 'paiflow.execution.v4')
                or CURRENT_ITERATION_ID.get() is not None
                or not isinstance(parameters, dict) or parameters.get('provider') not in {'openai', 'xinghuo'}
                or parameters.get('mode') not in ({'chat', 'function_call'} if dynamic else {'chat'})):
            raise WorkerError('ENGINE_UNSUPPORTED', '模型类型或原调用模式不受支持')
        required = {'resourceId', 'resourceVersion', 'provider', 'modelName', 'mode', 'messages', 'options'}
        if parameters['mode'] == 'function_call':
            required |= {'functions'}
        if kind == 'question-answer':
            required |= {'checkpointId'}
            if not isinstance(parameters.get('checkpointId'), str) or not 1 <= len(parameters['checkpointId']) <= 256:
                raise WorkerError('DSL_INVALID', '动态问答必须绑定中心已消费检查点')
        if (set(parameters) - required - {'timeoutSeconds'} or not required <= set(parameters)
                or any(not isinstance(parameters[k], str) or not parameters[k] or len(parameters[k]) > 256
                       for k in ('resourceId', 'resourceVersion', 'modelName'))
                or not isinstance(idempotency_key, str) or not 1 <= len(idempotency_key) <= 4096
                or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in idempotency_key)):
            raise WorkerError('DSL_INVALID', '模型请求字段/幂等键无效')
        messages = parameters['messages']
        from .python_history import check_model_messages
        check_model_messages(context, node_id, messages)
        if (not isinstance(messages, list) or not messages or any(not isinstance(m, dict)
                or set(m) != {'role', 'content'} or m['role'] not in {'user', 'system'}
                or not isinstance(m['content'], str) for m in messages)):
            raise WorkerError('DSL_INVALID', '原模型消息结构无效')
        if parameters['mode'] == 'function_call' and (parameters['provider'] != 'xinghuo'
                or not isinstance(parameters.get('functions'), list) or not parameters['functions']
                or any(not isinstance(function, dict) or set(function) != {'name', 'description', 'parameters'}
                       or not isinstance(function['name'], str) or not isinstance(function['description'], str)
                       or not isinstance(function['parameters'], dict) for function in parameters['functions'])):
            raise WorkerError('ENGINE_UNSUPPORTED', '原 FunctionCall 必须使用 Spark 函数声明')
        options = parameters['options']
        option_keys = {'temperature', 'maxTokens', 'topK', 'patchId', 'respFormat', 'searchDisable', 'extraParams'}
        if parameters['mode'] == 'function_call':
            option_keys |= {'questionType', 'functionChoice'}
        if (not isinstance(options, dict) or set(options) != option_keys
                or type(options['temperature']) not in (int, float) or not 0 < options['temperature'] <= 1
                or type(options['maxTokens']) is not int or options['maxTokens'] < 1
                or type(options['topK']) is not int or type(options['respFormat']) is not int or options['respFormat'] not in (0, 1, 2)
                or not isinstance(options['patchId'], list) or type(options['searchDisable']) is not bool or not isinstance(options['extraParams'], dict)
                or 'timeoutSeconds' in parameters and (type(parameters['timeoutSeconds']) not in (int, float) or not 0 < parameters['timeoutSeconds'] <= 600)):
            raise WorkerError('DSL_INVALID', '模型原参数/超时类型无效')
        extra = options['extraParams']
        allowed = {'temperature', 'top_p', 'max_tokens', 'presence_penalty', 'frequency_penalty', 'seed', 'stop', 'n',
            'response_format', 'stream_options', 'logprobs', 'top_logprobs', 'user', 'tools', 'tool_choice', 'functions',
            'function_call', 'parallel_tool_calls', 'max_completion_tokens', 'reasoning_effort', 'store', 'metadata',
            'audio', 'modalities', 'prediction', 'service_tier'}
        if set(extra) - allowed or 'n' in extra and (type(extra['n']) is not int or extra['n'] != 1):
            raise WorkerError('TOOL_POLICY_DENIED', '模型额外参数超出原SDK受控字段范围')
        body['iterationId'] = CURRENT_ITERATION_ID.get()
        if len(json_bytes(body)) > 2 * 1024 * 1024:
            raise WorkerError('DSL_INVALID', '模型完整请求超出2MiB UTF8预算')
        expected = self._model_binding(run_id, lease_id, attempt, nodeId=node_id,
            resourceId=parameters['resourceId'], resourceVersion=parameters['resourceVersion'], provider=parameters['provider'])
        self._check_model_resource(expected)
        node = next(item for item in context.assignment.dsl['nodes'] if item['id'] == node_id)
        node_parameters = node['data'].get('nodeParam', {})
        fixed = context.assignment.resources[parameters['resourceId']]
        if (node_parameters.get('modelResourceId') != parameters['resourceId']
                or node_parameters.get('source', 'xinghuo') != parameters['provider']
                or node_parameters.get('domain') != parameters['modelName']
                or fixed.get('modelName') != parameters['modelName']):
            raise WorkerError('VERSION_CONFLICT', '模型请求不属于固定节点和资源')
        if dynamic:
            expected_mode = 'chat'
            if kind == 'extractor-parameter' and node_parameters.get('reasonMode', 0) != 1:
                expected_mode = 'function_call'
            elif kind == 'decision-making' and node_parameters.get('reasonMode', 0) != 1 and node_parameters.get('useFunctionCall', True):
                expected_mode = 'function_call'
            elif kind == 'question-answer' and not (node_parameters.get('answerType') == 'direct'
                    and node_parameters.get('directAnswer', {}).get('handleResponse') is True):
                raise WorkerError('TOOL_POLICY_DENIED', '静态问答不能申请模型操作')
            if parameters['mode'] != expected_mode or expected_mode not in fixed.get('capabilities', []):
                raise WorkerError('TOOL_POLICY_DENIED', '原节点调用模式不属于固定模型能力')
            if expected_mode == 'function_call' and (options.get('questionType') != node_parameters.get('question_type', 'not_knowledge')
                    or options.get('functionChoice') != ('extractor_parameter' if kind == 'extractor-parameter' else '')
                    or options['extraParams'] != {} or options['searchDisable'] is not True or 'timeoutSeconds' in parameters):
                raise WorkerError('VERSION_CONFLICT', '原函数调用选项与固定节点不一致')
        data = await self._request('POST', self._run(run_id) + '/operations', body, strict_json=True)
        check_binding(data, expected, {'state'})
        self._check_model_resource(data)
        if data['state'] != 'RUNNING':
            raise CenterUnavailable()
        return data

    async def operation_frames(self, run_id, lease_id, attempt, operation_id, after_cursor, limit=64):
        if type(after_cursor) is not int or not 0 <= after_cursor <= 10000 or type(limit) is not int or not 1 <= limit <= 64:
            raise WorkerError("DSL_INVALID", "操作流游标/批次预算无效")
        expected = self._model_binding(run_id, lease_id, attempt, operationId=operation_id)
        data = await self._request("POST", self._run(run_id) + "/operations/" + self._id(operation_id) + "/frames",
            {"leaseId": lease_id, "attempt": attempt, "afterCursor": after_cursor, "limit": limit}, strict_json=True)
        check_frames(data, expected, after_cursor, limit)
        self._check_model_resource(data)
        return data

    async def cancel_model_operation(self, run_id, lease_id, attempt, operation_id):
        expected = self._model_binding(run_id, lease_id, attempt, stopping=True, operationId=operation_id)
        data = await self._request('POST', self._run(run_id) + '/operations/' + self._id(operation_id) + '/cancel',
            {'leaseId': lease_id, 'attempt': attempt}, strict_json=True)
        check_cancel(data, expected)
        self._check_model_resource(data, stopping=True)
        return data

    async def legacy_frames(self, run_id, lease_id, attempt, operation_id, after_cursor, limit=64):
        """原Agent/专业RAG/RPA协议草案；尚无正式Producer端点。"""
        if type(after_cursor) is not int or not 0 <= after_cursor <= 10000 or type(limit) is not int or not 1 <= limit <= 64:
            raise WorkerError("DSL_INVALID", "原能力游标/批次无效")
        data = await self._request("POST", self._run(run_id) + "/operations/" + self._id(operation_id) + "/frames",
            {"leaseId": lease_id, "attempt": attempt, "afterCursor": after_cursor, "limit": limit})
        if (not isinstance(data, dict) or data.get("kind") not in {"agent", "knowledge-pro", "rpa"}
                or data.get("provider") not in {"original-agent", "AIUI-RAG2", "CBG-RAG", "original-rpa"}
                or any(not isinstance(data.get(k), str) or not data[k] for k in ("resourceId", "resourceVersion"))
                or not isinstance(data.get("resourceBindings"), dict) or type(data.get("nextCursor")) is not int
                or data["nextCursor"] < after_cursor or type(data.get("done")) is not bool
                or not isinstance(data.get("frames"), list) or len(data["frames"]) > limit
                or any(not isinstance(frame, dict) and frame != "[DONE]" for frame in data["frames"])
                or data.get("state") not in {"RUNNING", "COMPLETED", "FAILED", "CANCELLED", "EFFECT_UNKNOWN"}
                or data.get("failureKind") not in {None, "provider", "authorization", "protocol", "transport", "deadline"}
                or data["state"] == "FAILED" and data.get("failureKind") is None):
            raise CenterUnavailable()
        return data

    async def cancel_operation(self, run_id, lease_id, attempt, operation_id):
        data = await self._request("POST", self._run(run_id) + "/operations/" + self._id(operation_id) + "/cancel",
            {"leaseId": lease_id, "attempt": attempt})
        if not isinstance(data, dict) or data.get("stopped") is not True:
            raise CenterUnavailable()
        return data

    async def workflow_frames(self, run_id, lease_id, attempt, operation_id, after_cursor, limit=64):
        if type(after_cursor) is not int or not 0 <= after_cursor <= 10000 or type(limit) is not int or not 1 <= limit <= 64:
            raise WorkerError("DSL_INVALID", "子流程事件游标/批次预算无效")
        data = await self._request("POST", self._run(run_id) + "/operations/" + self._id(operation_id) + "/frames",
            {"leaseId": lease_id, "attempt": attempt, "afterCursor": after_cursor, "limit": limit})
        if (not isinstance(data, dict) or data.get("kind") != "workflow"
                or data.get("state") not in {"RUNNING", "COMPLETED", "FAILED", "CANCELLED", "EFFECT_UNKNOWN"}
                or any(not isinstance(data.get(key), str) or not data[key] for key in ("resourceId", "childRunId", "workflowVersionId", "dslHash", "resourceVersion"))
                or type(data.get("outputMode")) is not int or data["outputMode"] not in {0, 1, 2}
                or type(data.get("nextCursor")) is not int or data["nextCursor"] < after_cursor
                or type(data.get("done")) is not bool or not isinstance(data.get("frames"), list)
                or len(data["frames"]) > limit or any(not isinstance(frame, dict) for frame in data["frames"])
                or data.get("failureKind") not in {None, "child_run", "authorization", "protocol", "transport", "deadline"}
                or data["state"] == "FAILED" and data.get("failureKind") is None):
            raise CenterUnavailable()
        return data

    async def create_checkpoint(self, run_id, lease_id, attempt, node_id, ordinal, question, need_reply,
                                timeout_seconds, key, *, iteration_id=None):
        if (not isinstance(node_id, str) or not node_id.startswith("question-answer::")
                or type(ordinal) is not int or not 1 <= ordinal <= 5 or not isinstance(question, dict)
                or type(need_reply) is not bool or type(timeout_seconds) is not int
                or not 60 <= timeout_seconds <= 300 or timeout_seconds % 60 or not isinstance(key, str)
                or not re.fullmatch(r"[A-Za-z0-9_-]{8,128}", key)):
            raise WorkerError("DSL_INVALID", "检查点题面/等待范围无效")
        body = {"leaseId": lease_id, "attempt": attempt, "nodeId": node_id, "ordinal": ordinal,
                "question": question, "needReply": need_reply, "timeoutSeconds": timeout_seconds, "idempotencyKey": key}
        if iteration_id is not None:
            body["iterationId"] = iteration_id
        data = await self._request("POST", self._run(run_id) + "/checkpoints", body)
        if (not isinstance(data, dict) or set(data) != {"checkpointId", "revision", "state", "deadline", "stopped"}
                or not isinstance(data["checkpointId"], str) or not data["checkpointId"]
                or type(data["revision"]) is not int or data["revision"] < 0
                or data["state"] not in {"WAITING", "RESUMED", "CONSUMED", "EXPIRED", "CANCELLED"}
                or not isinstance(data["deadline"], str) or type(data["stopped"]) is not bool
                or data["stopped"] != (data["state"] in {"CONSUMED", "EXPIRED", "CANCELLED"})):
            raise CenterUnavailable()
        return data

    async def poll_checkpoint(self, run_id, lease_id, attempt, checkpoint_id, after_revision):
        if type(after_revision) is not int or after_revision < 0:
            raise WorkerError("DSL_INVALID", "检查点 revision 无效")
        data = await self._request("POST", self._run(run_id) + "/checkpoints/" + self._id(checkpoint_id) + "/resume/poll",
            {"leaseId": lease_id, "attempt": attempt, "afterRevision": after_revision})
        if (not isinstance(data, dict) or set(data) != {"checkpointId", "revision", "state", "deadline", "stopped"}
                or data["checkpointId"] != checkpoint_id or type(data["revision"]) is not int
                or data["revision"] < after_revision or data["state"] not in {"WAITING", "RESUMED", "EXPIRED", "CANCELLED", "CONSUMED"}
                or not isinstance(data["deadline"], str) or type(data["stopped"]) is not bool
                or data["stopped"] != (data["state"] in {"EXPIRED", "CANCELLED", "CONSUMED"})):
            raise CenterUnavailable()
        return data

    async def consume_checkpoint(self, run_id, lease_id, attempt, checkpoint_id, expected_revision, key):
        if (type(expected_revision) is not int or expected_revision < 0 or not isinstance(key, str)
                or not re.fullmatch(r"[A-Za-z0-9_-]{8,128}", key)):
            raise WorkerError("DSL_INVALID", "检查点消费 CAS/幂等键无效")
        data = await self._request("POST", self._run(run_id) + "/checkpoints/" + self._id(checkpoint_id) + "/consume",
            {"leaseId": lease_id, "attempt": attempt, "expectedRevision": expected_revision, "idempotencyKey": key})
        if (not isinstance(data, dict) or data.get("checkpointId") != checkpoint_id
                or type(data.get("consumed")) is not bool or data.get("stopped") is not True
                or not isinstance(data.get("deadline"), str) or type(data.get("revision")) is not int
                or data["revision"] <= expected_revision):
            raise CenterUnavailable()
        fields = {"checkpointId", "consumed", "revision", "state", "deadline", "stopped"}
        if not data["consumed"]:
            if set(data) != fields or data.get("state") not in {"EXPIRED", "CANCELLED"}:
                raise CenterUnavailable()
            return data
        if set(data) != fields | {"resumeData"} or data.get("state") != "CONSUMED":
            raise CenterUnavailable()
        resume = data["resumeData"]
        if (not isinstance(resume, dict) or set(resume) != {"message", "metadata"} or not isinstance(resume["message"], str)
                or not isinstance(resume["metadata"], dict) or set(resume["metadata"]) != {"retries", "timestamp"}
                or type(resume["metadata"]["retries"]) is not int or not 0 <= resume["metadata"]["retries"] <= 4
                or type(resume["metadata"]["timestamp"]) is not int or resume["metadata"]["timestamp"] < 1):
            raise CenterUnavailable()
        return data

    async def append_history(self, run_id, lease_id, attempt, node_id, question, answer, *, iteration_id=None):
        if not isinstance(question, dict) or not isinstance(answer, dict):
            raise WorkerError("DSL_INVALID", "历史消息必须保留原 JSON 对象结构")
        body = {"leaseId": lease_id, "attempt": attempt, "nodeId": node_id,
                "question": question, "answer": answer,
                "idempotencyKey": f"{run_id}:{attempt}:{node_id}:{iteration_id or '-'}:history"}
        if iteration_id is not None:
            body["iterationId"] = iteration_id
        data = await self._request("POST", self._run(run_id) + "/history", body)
        if (not isinstance(data, dict) or data.get("stored") is not True
                or type(data.get("revision")) is not int or data["revision"] < 1):
            raise CenterUnavailable()
        return data

    async def read_history(self, run_id, lease_id, attempt):
        """中心给出当前运行的正式会话历史，Worker 不接收前端自报归属。"""
        data = await self._request("POST", self._run(run_id) + "/history/read",
                                   {"leaseId": lease_id, "attempt": attempt}, strict_json=True)
        if (not isinstance(data, dict) or set(data) != {"history", "historyV2"}
                or not isinstance(data['history'], list) or not isinstance(data['historyV2'], list)):
            raise CenterUnavailable()
        return data

    async def read_python_history(self, run_id, lease_id, attempt):
        """读取固定运行的Python旧历史快照；完整作用域/hash由RunContext消费者核对。"""
        data = await self._request('POST', self._run(run_id) + '/history/python/read',
                                   {'leaseId': lease_id, 'attempt': attempt}, strict_json=True)
        from .python_history import ACK_KEYS, MAX_HISTORY_BYTES, canonical
        if not isinstance(data, dict) or set(data) != ACK_KEYS or len(canonical(data)) > MAX_HISTORY_BYTES:
            raise WorkerError('HISTORY_PROTOCOL_INVALID', 'Python旧历史回执字段或字节预算无效')
        return data

    async def variables(self, run_id, lease_id, attempt, operation, *, node_id, **values):
        if operation not in {"set", "get", "get_all", "clear"}:
            raise WorkerError("DSL_INVALID", "未知会话变量操作")
        allowed = {"variableName", "value", "expectedRevision", "idempotencyKey"}
        if set(values) - allowed:
            raise WorkerError("DSL_INVALID", "未知会话变量字段")
        required = {"set": {"variableName", "value", "expectedRevision", "idempotencyKey"},
            "get": {"variableName"}, "get_all": set(), "clear": {"expectedRevision", "idempotencyKey"}}[operation]
        if set(values) != required or "variableName" in values and (
                not isinstance(values["variableName"], str) or not values["variableName"]):
            raise WorkerError("DSL_INVALID", "会话变量操作字段不完整或多余")
        if operation in {"set", "clear"}:
            if (type(values.get("expectedRevision")) is not int or values["expectedRevision"] < 0
                    or not isinstance(values.get("idempotencyKey"), str) or not values["idempotencyKey"]):
                raise WorkerError("DSL_INVALID", "变量写入缺少 revision/CAS/幂等键")
        data = await self._request("POST", self._run(run_id) + "/variables", {
            "leaseId": lease_id, "attempt": attempt, "nodeId": node_id, "operation": operation, **values})
        if (not isinstance(data, dict) or data.get("accepted") is not True
                or type(data.get("revision")) is not int or data["revision"] < 0):
            raise CenterUnavailable()
        if operation == "get_all" and not isinstance(data.get("variables"), dict):
            raise CenterUnavailable()
        if operation == "get" and (type(data.get("found")) is not bool or data["found"] and "value" not in data):
            raise CenterUnavailable()
        if operation in {"set", "clear"} and data["revision"] <= values["expectedRevision"]:
            raise CenterUnavailable()
        return data
