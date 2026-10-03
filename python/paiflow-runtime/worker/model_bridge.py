"""受控模型流端口；复用原 Spark/OpenAI 解码与原 FC 响应算法。

没有模型假响应、静态凭据、任意 URL 或直接 Provider 连接。中心端口缺失则失败。
"""
from __future__ import annotations

import asyncio
import hashlib
import json

from workflow.engine.nodes.entities.llm_response import LLMResponse
from workflow.infra.providers.llm.iflytek_spark.spark_chat_llm import SparkChatAi
from workflow.infra.providers.llm.iflytek_spark.spark_fc_llm import SparkFunctionCallAi
from workflow.infra.providers.llm.openai.openai_chat_llm import OpenAIChatAI

from .errors import CenterUnavailable, WorkerError
from .events import _validate_payload
from .model_contract import check_binding, check_cancel, check_frames, json_bytes
from .node_context import CURRENT_ITERATION_ID
from .task_runner import current_context


class _BoundModel:
    def _parameters(self, messages, functions, extra_params, timeout, search_disable, *, mode):
        options = {"temperature": self.temperature, "maxTokens": self.max_tokens, "topK": self.top_k,
            "patchId": self.patch_id, "respFormat": self.response_format, "searchDisable": search_disable,
            "extraParams": extra_params}
        if mode == "function_call":
            options.update(questionType=self.question_type, functionChoice=self.function_choice)
        body = {"resourceId": self.resource_id, "provider": self.provider, "modelName": self.model_name,
                "mode": mode, "messages": messages, "options": options}
        if functions is not None:
            body["functions"] = functions
        if timeout is not None:
            if not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or not 0 < timeout <= 600:
                raise WorkerError("DSL_INVALID", "模型超时超出范围")
            body["timeoutSeconds"] = timeout
        _validate_payload(body)
        return body

    async def _frames(self, body):
        context = current_context()
        operation_id = None
        completed = False
        invoke_dispatched = False
        try:
            snapshot = context.assignment.resources.get(self.resource_id)
            if not isinstance(snapshot, dict):
                raise WorkerError("TOOL_POLICY_DENIED", "模型不属于固定资源快照")
            resource = await context.resource(self.resource_id)
            for key, expected in {"kind": "model", "provider": self.provider, "modelName": self.model_name}.items():
                if snapshot.get(key) != expected or resource.get(key) != expected:
                    raise WorkerError("VERSION_CONFLICT", "模型资源与原 SDK/模型参数绑定不一致")
            version = snapshot.get("version")
            if (not isinstance(version, str) or not version or resource.get("version") != version
                    or snapshot.get("enabled") is not True or resource.get("enabled") is not True):
                raise WorkerError("RESOURCE_UNAVAILABLE", "模型固定版本不可用")
            body["resourceVersion"] = version
            encoded = json.dumps(body, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
            if len(encoded) > 2 * 1024 * 1024:
                raise WorkerError("DSL_INVALID", "模型消息超出预算，须经中心对象资源分发")
            fingerprint = hashlib.sha256(encoded).hexdigest()
            iteration_id = CURRENT_ITERATION_ID.get()
            key = f"{context.assignment.run_id}:{context.assignment.attempt}:{self.node_id}:{iteration_id or '-'}:{fingerprint}:model"
            binding = {'runId': context.assignment.run_id, 'leaseId': context.assignment.lease_id,
                'attempt': context.assignment.attempt, 'nodeId': self.node_id, 'iterationId': iteration_id,
                'workflowVersionId': context.assignment.version_id, 'dslHash': context.assignment.hash,
                'resourceId': self.resource_id, 'resourceVersion': version, 'provider': self.provider}
            invoke_dispatched = True
            ack = await context.operation(self.node_id, "model.invoke", body, key=key)
            check_binding(ack, binding, {'state'})
            if ack['state'] != 'RUNNING':
                raise CenterUnavailable()
            operation_id = ack["operationId"]
            binding['operationId'] = operation_id
            cursor = 0
            total_bytes = 0
            final_frame = False
            openai_finish = None
            openai_finish_seq = None
            while True:
                context.assert_active()
                response = await context.center.operation_frames(context.assignment.run_id, context.assignment.lease_id,
                    context.assignment.attempt, operation_id, cursor, 64)
                context.assert_active()
                try:
                    check_frames(response, binding, cursor)
                except CenterUnavailable:
                    raise WorkerError('DSL_INVALID', '中心模型分页回执不满足完整共享契约') from None
                frames = response["frames"]
                cursor = response["nextCursor"]
                if cursor > 10000:
                    raise WorkerError("DSL_INVALID", "模型流超过帧预算")
                state = response["state"]
                actual_state = response['operationState']
                if actual_state in {'FAILED', 'CANCELLED', 'EFFECT_UNKNOWN'} and not (
                        actual_state == 'FAILED' and response['failureKind'] == 'provider' and response['finishSeq'] is not None):
                    raise WorkerError('EFFECT_UNKNOWN' if actual_state == 'EFFECT_UNKNOWN' else 'MODEL_FAILED',
                                      '中心已确认非合法Provider终帧的操作失败/停止边界')
                # 只有中心已核对来源并结束、且原 SDK 错误帧合法，才沿原节点错误规则。
                provider_failed = state == "FAILED" and response.get("failureKind") == "provider"
                if provider_failed and self.provider != "openai":
                    if (not response["done"] or not frames or response['finishSeq'] != cursor
                            or not self._is_provider_error(frames[-1], body["mode"])):
                        raise WorkerError("DSL_INVALID", "中心 Provider 失败缺少原合法 SDK 结束错误帧")
                    completed = True  # 中心操作已 FAILED；原 DEFAULT 不改变该审计状态。
                if state in {"FAILED", "CANCELLED", "EFFECT_UNKNOWN"}:
                    if not provider_failed:
                        raise WorkerError("EFFECT_UNKNOWN" if state == "EFFECT_UNKNOWN" else "MODEL_FAILED", "中心模型操作没有完成")
                for index, frame in enumerate(frames):
                    if final_frame:
                        raise WorkerError("DSL_INVALID", "原 SDK 终帧后仍返回内容帧")
                    _validate_payload(frame)
                    total_bytes += len(json_bytes(frame))
                    if total_bytes > 8 * 1024 * 1024:
                        raise WorkerError("DSL_INVALID", "模型流超过字节预算")
                    if self.provider == "openai":
                        if frame.get("choices") == []:
                            usage = frame.get("usage")
                            if (not isinstance(usage, dict) or any(type(usage.get(k)) is not int or usage[k] < 0
                                    for k in ("prompt_tokens", "completion_tokens", "total_tokens"))):
                                raise WorkerError("DSL_INVALID", "OpenAI空choices必须是合法原用量帧")
                            yield LLMResponse(frame)
                            continue
                        if openai_finish is not None:
                            raise WorkerError("DSL_INVALID", "OpenAI真实finish后仍返回内容帧")
                        code, status, *_ = self.decode_message(frame)
                        if code != 0 or status not in {None, "stop", "length", "content_filter", "function_call", "tool_calls"}:
                            raise WorkerError("DSL_INVALID", "OpenAI原帧终止类型不合法")
                        if status is not None:
                            openai_finish = frame
                            openai_finish_seq = cursor - len(frames) + index + 1
                        else:
                            yield LLMResponse(frame)
                        continue
                    if provider_failed and frame is frames[-1]:
                        # BaseLLM 使用原 CodeConvert；FC 使用原 _process_message。
                        yield LLMResponse(frame)
                        return
                    code, status, *_ = self.decode_message(frame)
                    if code != 0:
                        raise WorkerError("MODEL_FAILED", "原 SDK 返回模型错误码")
                    final_frame = status in {2, "stop"}
                    # 先等待中心完成确认再向原引擎送终帧，防止 break 跳过正式 ACK。
                    if final_frame and state != "COMPLETED":
                        raise WorkerError("RESOURCE_UNAVAILABLE", "SDK 终帧未获得中心模型完成确认")
                    if final_frame:
                        if not response["done"] or response['finishSeq'] != cursor - len(frames) + index + 1:
                            raise WorkerError("RESOURCE_UNAVAILABLE", "SDK 终帧后中心仍有未确认流")
                        completed = True
                    yield LLMResponse(frame)
                if response["done"]:
                    if self.provider == "openai":
                        if (openai_finish is None or type(response.get("finishSeq")) is not int
                                or response["finishSeq"] != openai_finish_seq
                                or type(response.get("lastSeq")) is not int or response["lastSeq"] != cursor):
                            raise WorkerError("RESOURCE_UNAVAILABLE", "OpenAI中心完成缺少真实finish及最后帧绑定")
                        expected_failed = self.decode_message(openai_finish)[1] != "stop"
                        if (state != ("FAILED" if expected_failed else "COMPLETED")
                                or response.get("failureKind") != ("provider" if expected_failed else None)):
                            raise WorkerError("RESOURCE_UNAVAILABLE", "OpenAI真实finish未获得匹配中心终态")
                        completed = True
                        yield LLMResponse(openai_finish)
                        return
                    if state != "COMPLETED" or not final_frame:
                        raise WorkerError("RESOURCE_UNAVAILABLE", "模型流没有完整原 SDK 终帧")
                    completed = True
                    return
                if not frames:
                    await asyncio.sleep(.05)
        except asyncio.CancelledError:
            if invoke_dispatched and operation_id is None:
                context.boundary_failure = WorkerError("EFFECT_UNKNOWN", "模型已发起但未回读操作标识，停止未确认")
            raise
        except WorkerError as error:
            context.boundary_failure = error
            raise
        except Exception:
            error = WorkerError("DSL_INVALID", "中心模型原 SDK 帧不满足已绑定协议")
            context.boundary_failure = error
            raise error from None
        finally:
            if operation_id is not None and not completed:
                try:
                    cancel = getattr(context.center, 'cancel_model_operation', context.center.cancel_operation)
                    stopped = await cancel(context.assignment.run_id, context.assignment.lease_id,
                        context.assignment.attempt, operation_id)
                    check_cancel(stopped, binding)
                except BaseException:
                    # 未确认停止不能被原 Decision 的 DEFAULT 回退变成成功。
                    context.boundary_failure = WorkerError("EFFECT_UNKNOWN", "中心模型停止未确认，须核对费用/副作用")

    def _is_provider_error(self, frame, mode):
        _validate_payload(frame)
        if self.provider == "xinghuo":
            if mode == "function_call":
                code = frame["header"]["code"]
            else:
                code, *_ = self.decode_message(frame)
            return type(code) is int and code != 0
        code, status, *_ = self.decode_message(frame)
        return code == 0 and status in {"length", "content_filter", "function_call", "tool_calls"}


class CenterSparkChatAI(_BoundModel, SparkChatAi):
    node_id: str
    resource_id: str
    provider: str = "xinghuo"
    response_format: int = 0
    async def achat(self, flow_id, user_message, span, extra_params=None, timeout=None, search_disable=True, event_log_node_trace=None):
        body = self._parameters(user_message, None, extra_params or {}, timeout, search_disable, mode="chat")
        async for frame in self._frames(body):
            yield frame


class CenterOpenAIChatAI(_BoundModel, OpenAIChatAI):
    node_id: str
    resource_id: str
    patch_id: list = []
    provider: str = "openai"
    response_format: int = 0
    async def achat(self, flow_id, user_message, span, extra_params=None, timeout=None, search_disable=True, event_log_node_trace=None):
        body = self._parameters(user_message, None, extra_params or {}, timeout, search_disable, mode="chat")
        async for frame in self._frames(body):
            yield frame


class CenterSparkFunctionCallAI(_BoundModel, SparkFunctionCallAi):
    node_id: str
    resource_id: str
    provider: str = "xinghuo"
    response_format: int = 0

    def decode_message(self, frame):
        return SparkChatAi.decode_message(self, frame)

    async def async_call_spark_fc(self, user_input, span, event_log_node_trace=None, history=None, function=None):
        messages = [item.model_dump(mode="json") for item in (history or [])]
        messages.append({"role": "user", "content": user_input})
        functions = [item.dict() for item in (function or [])]
        body = self._parameters(messages, functions, {}, None, True, mode="function_call")
        result = None
        async for response in self._frames(body):
            parsed = self._process_message(response.msg, span)
            if parsed[0] is not None:
                result = parsed
        if result is None:
            raise WorkerError("MODEL_FAILED", "原 Spark FC 没有返回 function_call")
        return result


def _configuration(node):
    context = current_context()
    if node.node_id not in context.node_ids or not node.modelResourceId:
        error = WorkerError("RESOURCE_UNAVAILABLE", "模型节点缺少中心固定资源绑定")
        context.boundary_failure = error
        raise error
    if node.apiKey or node.apiSecret or node.url:
        error = WorkerError("TOOL_POLICY_DENIED", "Worker 不接受原模型 URL/明文凭据或环境引用")
        context.boundary_failure = error
        raise error
    return {"node_id": node.node_id, "resource_id": node.modelResourceId, "model_url": "", "model_name": node.domain,
        "spark_version": "", "temperature": node.temperature, "app_id": "", "api_key": "", "api_secret": "",
        "max_tokens": node.maxTokens, "top_k": node.topK, "patch_id": node.patch_id, "uid": "",
        "response_format": node.respFormat, "stream_node_first_token": node.stream_node_first_token}


def create_chat_ai(node):
    options = _configuration(node)
    if node.source == "xinghuo":
        return CenterSparkChatAI(**options)
    if node.source == "openai":
        return CenterOpenAIChatAI(**options)
    error = WorkerError("ENGINE_UNSUPPORTED", "原模型 SDK 类型不受支持")
    current_context().boundary_failure = error
    raise error


def create_function_call_ai(node, *, function_choice="extractor_parameter"):
    if node.source != "xinghuo":
        error = WorkerError("ENGINE_UNSUPPORTED", "原提取/决策 FunctionCall 仅实现 Spark SDK，不做供应商别名")
        current_context().boundary_failure = error
        raise error
    return CenterSparkFunctionCallAI(**_configuration(node), question_type=node.question_type, function_choice=function_choice)
