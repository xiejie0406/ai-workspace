"""剩余原节点的受控操作生命周期；只转送中心确认的原帧，不实现 Producer。"""
import asyncio
import hashlib
import json
from contextlib import asynccontextmanager
from types import SimpleNamespace
from workflow.exception.e import CustomException

from .errors import CenterUnavailable, WorkerError
from .events import _validate_payload
from .node_context import CURRENT_ITERATION_ID
from .task_runner import READ_ONLY_OPERATIONS, _same_json, current_context


async def bound_resource(node, resource_id, kind, expected):
    context = current_context()
    if node.node_id not in context.node_ids or not resource_id:
        raise WorkerError("RESOURCE_UNAVAILABLE", "原节点缺少中心固定资源绑定")
    if CURRENT_ITERATION_ID.get() is not None:
        raise WorkerError("RESOURCE_UNAVAILABLE", "该操作缺少中心实际迭代实例归属")
    snapshot = context.assignment.resources.get(resource_id)
    if not isinstance(snapshot, dict):
        raise WorkerError("TOOL_POLICY_DENIED", "资源不属于固定运行快照")
    resource = await context.resource(resource_id)
    for key, value in {"kind": kind, **expected}.items():
        if not _same_json(snapshot.get(key), value) or not _same_json(resource.get(key), value):
            raise WorkerError("VERSION_CONFLICT", "原参数与中心固定资源绑定不一致")
    version, provider = snapshot.get("version"), snapshot.get("provider")
    if (not isinstance(version, str) or not version or not isinstance(provider, str) or not provider
            or resource.get("version") != version or resource.get("provider") != provider
            or snapshot.get("enabled") is not True or resource.get("enabled") is not True):
        raise WorkerError("RESOURCE_UNAVAILABLE", "中心固定能力版本不可用")
    if any(not _same_json(value, resource.get(key)) for key, value in snapshot.items()):
        raise WorkerError("VERSION_CONFLICT", "固定资源描述改变但版本未更新")
    _validate_payload(snapshot)
    _validate_payload(resource)
    return snapshot


def parameters(resource_id, resource, payload, bindings=None):
    body = {"resourceId": resource_id, "resourceVersion": resource["version"],
            "kind": resource["kind"], "provider": resource["provider"], "legacyPayload": payload,
            "resourceBindings": bindings or {}}
    _validate_payload(body)
    encoded = json.dumps(body, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    if len(encoded) > 2 * 1024 * 1024:
        raise WorkerError("DSL_INVALID", "原能力输入超过中心预算")
    return body, hashlib.sha256(encoded).hexdigest()


def _authorized(node, operation):
    context = current_context()
    policy = context.assignment.effect_policy
    if (operation not in policy["allowedOperations"]
            or policy["mode"] == "read_only" and operation not in READ_ONLY_OPERATIONS):
        raise WorkerError("TOOL_POLICY_DENIED", "费用或写入操作未获中心审批")
    return context


def _binding(ack, body):
    return isinstance(ack, dict) and all(_same_json(ack.get(k), body[k]) for k in
        ("kind", "provider", "resourceId", "resourceVersion", "resourceBindings"))


def _key(context, node, operation, fingerprint):
    parts = [context.assignment.run_id, context.assignment.lease_id, context.assignment.attempt,
             node.node_id, CURRENT_ITERATION_ID.get(), operation, fingerprint]
    return "operation_" + hashlib.sha256(json.dumps(parts, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


async def completed_response(node, operation, body, fingerprint, validate):
    """同步原 Link/数据响应仍须中心确认结束；不自行重放写后查询。"""
    context = _authorized(node, operation)
    writing = operation not in READ_ONLY_OPERATIONS
    try:
        ack = await context.operation(node.node_id, operation, body, key=_key(context, node, operation, fingerprint))
        if not _binding(ack, body):
            raise CenterUnavailable()
        original = ack.get("legacyResponse")
        state, failure = ack.get("state"), ack.get("failureKind")
        if state == "COMPLETED" and failure is None:
            validate(original, False)
        elif state == "FAILED" and failure == "provider":
            validate(original, True)
        else:
            raise WorkerError("TOOL_POLICY_DENIED" if failure == "authorization" else "RESOURCE_UNAVAILABLE",
                              "中心正式能力未确认完成")
        _validate_payload(original)
        return original
    except asyncio.CancelledError:
        if writing:
            context.boundary_failure = WorkerError("EFFECT_UNKNOWN", "写入已请求但终态回执/停止未确认")
        raise
    except WorkerError as error:
        if writing and isinstance(error, CenterUnavailable):
            error = WorkerError("EFFECT_UNKNOWN", "已请求写入能力但回执或停止未知")
        context.boundary_failure = error
        raise error


@asynccontextmanager
async def response_stream(node, operation, body, fingerprint, profile):
    """把受控原 JSON 帧包装为原节点的 response.content，停止事实仍来自中心。"""
    context = _authorized(node, operation)
    operation_id, ended = None, False
    timeout_seconds = body.get("timeoutSeconds", 1800)
    read_timeout = body.get("readTimeoutSeconds")
    started = asyncio.get_running_loop().time()
    last_frame_at = started
    writing = operation not in READ_ONLY_OPERATIONS
    async def content():
        nonlocal ended, last_frame_at
        cursor, byte_count = 0, 0
        while True:
            context.assert_active()
            now = asyncio.get_running_loop().time()
            remaining = timeout_seconds - (now - started)
            if read_timeout is not None:
                remaining = min(remaining, read_timeout - (now - last_frame_at))
            if remaining <= 0:
                raise WorkerError("DEPENDENCY_TIMEOUT", "原能力等待超过固定执行/逐帧间隔限制")
            try:
                ack = await asyncio.wait_for(context.center.legacy_frames(context.assignment.run_id, context.assignment.lease_id,
                    context.assignment.attempt, operation_id, cursor, 64), timeout=remaining)
            except asyncio.TimeoutError:
                raise WorkerError("DEPENDENCY_TIMEOUT", "原能力等待超过固定执行/逐帧间隔限制") from None
            context.assert_active()
            if (not _binding(ack, body) or type(ack.get("nextCursor")) is not int
                    or not isinstance(ack.get("frames"), list) or len(ack["frames"]) > 64
                    or ack["nextCursor"] != cursor + len(ack["frames"]) or type(ack.get("done")) is not bool
                    or ack.get("state") not in {"RUNNING", "COMPLETED", "FAILED", "CANCELLED", "EFFECT_UNKNOWN"}):
                raise WorkerError("VERSION_CONFLICT", "原能力资源/版本/流游标不一致")
            cursor = ack["nextCursor"]
            if cursor > 10000:
                raise WorkerError("DSL_INVALID", "原能力帧数超过预算")
            provider_failed = ack["state"] == "FAILED" and ack.get("failureKind") == "provider" and ack["done"] is True
            if provider_failed:
                if len(ack["frames"]) != 1:
                    raise WorkerError("DSL_INVALID", "Provider失败须有一个合法原错误帧")
                _frame(profile, ack["frames"][0], True)
                ended = True
            elif ack["state"] not in {"RUNNING", "COMPLETED"} or ack.get("failureKind") is not None:
                raise WorkerError("EFFECT_UNKNOWN" if ack["state"] == "EFFECT_UNKNOWN" else "RESOURCE_UNAVAILABLE",
                                  "正式能力未完成，不能沿原默认分支继续")
            if (ack["done"] != (ack["state"] in {"COMPLETED", "FAILED"})
                    or ack["done"] and not ack["frames"] and profile != "rpa"):
                raise WorkerError("DSL_INVALID", "原能力结束状态不完整")
            for index, frame in enumerate(ack["frames"]):
                last_frame_at = asyncio.get_running_loop().time()
                _validate_payload(frame)
                terminal = _frame(profile, frame, provider_failed)
                encoded = json.dumps(frame, ensure_ascii=False, allow_nan=False) if isinstance(frame, dict) else frame
                byte_count += len(encoded.encode("utf-8"))
                if byte_count > 8 * 1024 * 1024:
                    raise WorkerError("DSL_INVALID", "原能力帧字节超过预算")
                last = index == len(ack["frames"]) - 1
                if terminal and not (ack["done"] and last):
                    raise WorkerError("RESOURCE_UNAVAILABLE", "原终帧尚未获中心结束确认")
                if ack["done"] and last:
                    if not terminal and profile != "rpa":
                        raise WorkerError("RESOURCE_UNAVAILABLE", "原能力缺少终帧")
                    ended = True
                prefix = "data: " if profile == "knowledge-pro" else "data:"
                yield (prefix + encoded + "\n").encode("utf-8")
            if ack["done"]:
                ended = True
                return
            if not ack["frames"]:
                await asyncio.sleep(.05)
    try:
        ack = await context.operation(node.node_id, operation, body, key=_key(context, node, operation, fingerprint))
        if not _binding(ack, body) or ack.get("state") != "RUNNING" or not isinstance(ack.get("operationId"), str) or not ack["operationId"]:
            raise CenterUnavailable()
        operation_id = ack["operationId"]
        stream = content()
        try:
            yield SimpleNamespace(content=stream)
            if not ended:
                raise WorkerError("RESOURCE_UNAVAILABLE", "原节点提前结束，中心能力尚未确认停止")
        finally:
            await stream.aclose()
    except asyncio.CancelledError:
        if writing and operation_id is None:
            context.boundary_failure = WorkerError("EFFECT_UNKNOWN", "费用操作已请求但标识及停止未知")
        raise
    except CustomException:
        if not ended:
            context.boundary_failure = WorkerError("RESOURCE_UNAVAILABLE", "原错误未获中心Provider结束确认")
            raise context.boundary_failure
        raise
    except WorkerError as error:
        if writing and operation_id is None and isinstance(error, CenterUnavailable):
            error = WorkerError("EFFECT_UNKNOWN", "费用操作回执及停止未知")
        context.boundary_failure = error
        raise error
    except Exception:
        context.boundary_failure = WorkerError("DSL_INVALID", "原能力回执不是合法协议")
        raise context.boundary_failure from None
    finally:
        if operation_id is not None and not ended:
            try:
                stopped = await context.center.cancel_operation(context.assignment.run_id, context.assignment.lease_id,
                    context.assignment.attempt, operation_id)
                if stopped.get("stopped") is not True:
                    raise CenterUnavailable()
            except BaseException:
                context.boundary_failure = WorkerError("EFFECT_UNKNOWN", "原能力未确认停止，不能伪报取消")


def _frame(profile, frame, failed):
    if profile == "knowledge-pro" and frame == "[DONE]" and not failed:
        return True
    if not isinstance(frame, dict) or type(frame.get("code", 0)) is not int:
        raise WorkerError("DSL_INVALID", "原能力帧形状无效")
    code = frame.get("code", 0)
    if failed:
        if code == 0 or not isinstance(frame.get("message", ""), str):
            raise WorkerError("DSL_INVALID", "Provider失败缺少原非零错误码")
        return True
    if code != 0:
        raise WorkerError("RESOURCE_UNAVAILABLE", "原错误帧缺少Provider失败裁决")
    if profile == "rpa":
        if "code" not in frame or not isinstance(frame.get("message"), str) or frame.get("data") is not None and not isinstance(frame.get("data", {}), dict):
            raise WorkerError("DSL_INVALID", "原RPA返回形状无效")
        return False
    if profile == "knowledge-pro":
        data = frame.get("data")
        if (not isinstance(data, dict) or data.get("content_type", "answer") not in {"answer", "knowledge_metadata"}
                or not isinstance(data.get("content", ""), str if data.get("content_type", "answer") == "answer" else list)):
            raise WorkerError("DSL_INVALID", "专业RAG原answer/metadata帧无效")
        return False
    choices = frame.get("choices")
    if (not isinstance(choices, list) or len(choices) > 1 or not isinstance(frame.get("usage", {}), dict)
            or choices and (not isinstance(choices[0], dict) or not isinstance(choices[0].get("delta"), dict)
                or any(not isinstance(choices[0]["delta"].get(k, ""), str) for k in ("content", "reasoning_content"))
                or not isinstance(choices[0]["delta"].get("tool_calls", []), list)
                or choices[0].get("finish_reason") not in {None, "", "stop"})):
        raise WorkerError("DSL_INVALID", "Agent原choices/tool_calls/usage帧无效")
    return not choices or choices[0].get("finish_reason") == "stop"
