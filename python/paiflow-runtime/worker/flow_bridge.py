"""中心正式子run流代理；原FlowNode继续处理原SSE帧/输出算法。

不在本地递归私造assignment/子事件/运行状态；中心缺workflow.invoke则失败。
"""
import asyncio
import hashlib
import json
import re

from .errors import CenterUnavailable, WorkerError
from .events import _validate_payload
from .node_context import CURRENT_ITERATION_ID
from .task_runner import current_context


async def frames(node, inputs, timeout):
    context = current_context()
    operation_id = None
    dispatched = False
    completed = False
    try:
        if node.node_id not in context.node_ids or not node.flowResourceId:
            raise WorkerError("RESOURCE_UNAVAILABLE", "子流程缺少中心固定资源绑定")
        if CURRENT_ITERATION_ID.get() is not None:
            raise WorkerError("RESOURCE_UNAVAILABLE", "迭代子run缺少中心实际轮次归属，不以不透明幂等键替代")
        if node.enableChatHistoryV2.is_enabled and context.execution_context is None:
            raise WorkerError("RESOURCE_UNAVAILABLE", "子流程历史必须绑定中心正式会话")
        snapshot = context.assignment.resources.get(node.flowResourceId)
        if not isinstance(snapshot, dict):
            raise WorkerError("TOOL_POLICY_DENIED", "子流程资源不属于固定任务快照")
        resource = await context.resource(node.flowResourceId)
        for key, expected in {"kind": "workflow", "provider": "python", "flowId": node.flowId}.items():
            if snapshot.get(key) != expected or resource.get(key) != expected:
                raise WorkerError("VERSION_CONFLICT", "原子流程选择与中心固定版本不一致")
        for key in ("version", "workflowVersionId", "dslHash", "outputMode"):
            if snapshot.get(key) != resource.get(key):
                raise WorkerError("VERSION_CONFLICT", "子流程版本/hash/输出模式发生变化")
        if (not isinstance(snapshot.get("version"), str) or not snapshot["version"]
                or not isinstance(snapshot.get("workflowVersionId"), str) or not snapshot["workflowVersionId"]
                or not isinstance(snapshot.get("dslHash"), str) or not re.fullmatch("[a-f0-9]{64}", snapshot["dslHash"])
                or type(snapshot.get("outputMode")) is not int or snapshot["outputMode"] not in {0, 1, 2}
                or snapshot.get("enabled") is not True or resource.get("enabled") is not True
                or node.version is not None and (snapshot.get("legacyVersion") != node.version or resource.get("legacyVersion") != node.version)):
            raise WorkerError("RESOURCE_UNAVAILABLE", "中心固定子版本/原版本选择不可用")
        body = {"resourceId": node.flowResourceId, "resourceVersion": snapshot["version"],
            "workflowVersionId": snapshot["workflowVersionId"], "dslHash": snapshot["dslHash"],
            "outputMode": snapshot["outputMode"], "input": inputs}
        if timeout is not None:
            if not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or not 0 < timeout <= 600:
                raise WorkerError("DSL_INVALID", "子流程重试间隔等待超出范围")
            body["timeoutSeconds"] = timeout
        _validate_payload(body)
        encoded = json.dumps(body, ensure_ascii=False, sort_keys=True, allow_nan=False, separators=(",", ":")).encode("utf-8")
        if len(encoded) > 2 * 1024 * 1024:
            raise WorkerError("DSL_INVALID", "子流程输入超过中心预算")
        key = f"{context.assignment.run_id}:{context.assignment.attempt}:{node.node_id}:{CURRENT_ITERATION_ID.get() or '-'}:{hashlib.sha256(encoded).hexdigest()}:flow"
        dispatched = True
        ack = await context.operation(node.node_id, "workflow.invoke", body, key=key)
        if (not isinstance(ack, dict) or ack.get("state") != "RUNNING" or ack.get("kind") != "workflow"
                or not isinstance(ack.get("operationId"), str) or not ack["operationId"]
                or not isinstance(ack.get("childRunId"), str) or not ack["childRunId"]
                or any(ack.get(k) != body[k] for k in ("resourceId", "resourceVersion", "workflowVersionId", "dslHash", "outputMode"))):
            raise CenterUnavailable()
        operation_id, child_id = ack["operationId"], ack["childRunId"]
        if child_id == context.assignment.run_id:
            raise WorkerError("VERSION_CONFLICT", "中心子run不能等于父run")
        cursor, byte_count = 0, 0
        while True:
            context.assert_active()
            response = await context.center.workflow_frames(context.assignment.run_id, context.assignment.lease_id,
                context.assignment.attempt, operation_id, cursor, 64)
            context.assert_active()
            if (response.get("kind") != "workflow" or response.get("childRunId") != child_id
                    or any(response.get(k) != body[k] for k in ("resourceId", "resourceVersion", "workflowVersionId", "dslHash", "outputMode"))
                    or type(response.get("nextCursor")) is not int or not isinstance(response.get("frames"), list)
                    or len(response["frames"]) > 64 or type(response.get("done")) is not bool
                    or response.get("state") not in {"RUNNING", "COMPLETED", "FAILED", "CANCELLED", "EFFECT_UNKNOWN"}
                    or response["nextCursor"] != cursor + len(response["frames"])):
                raise WorkerError("VERSION_CONFLICT", "子run关联/固定版本/事件游标不一致")
            cursor = response["nextCursor"]
            if cursor > 10000:
                raise WorkerError("DSL_INVALID", "子流程帧超出预算")
            if response.get("state") == "FAILED" and response.get("failureKind") == "child_run" and response.get("done") is True:
                raw = response["frames"]
                if (len(raw) != 1 or not isinstance(raw[0], dict) or type(raw[0].get("code")) is not int
                        or raw[0]["code"] == 0 or not isinstance(raw[0].get("message", ""), str)):
                    raise WorkerError("RESOURCE_UNAVAILABLE", "中心子run失败缺少合法原错误帧")
                _validate_payload(raw[0])
                # 中心持有FAILED；保留原FlowNode错误映射，并且不撤销已结束子run。
                completed = True
                yield raw[0]
                return
            if response.get("state") in {"FAILED", "CANCELLED", "EFFECT_UNKNOWN"}:
                raise WorkerError("EFFECT_UNKNOWN" if response["state"] == "EFFECT_UNKNOWN" else "ENGINE_FAILED", "中心正式子run未完成")
            for frame in response["frames"]:
                _validate_payload(frame)
                byte_count += len(json.dumps(frame, ensure_ascii=False, allow_nan=False).encode("utf-8"))
                if byte_count > 8 * 1024 * 1024:
                    raise WorkerError("DSL_INVALID", "子流程帧超出字节预算")
                choices = frame.get("choices")
                if (type(frame.get("code", 0)) is not int or frame.get("code", 0) != 0
                        or not isinstance(choices, list) or len(choices) > 1
                        or choices and (not isinstance(choices[0], dict) or not isinstance(choices[0].get("delta"), dict)
                            or any(not isinstance(choices[0]["delta"].get(k, ""), str) for k in ("content", "reasoning_content"))
                            or choices[0].get("finish_reason") not in {None, "", "stop"})
                        or not isinstance(frame.get("usage", {}), dict)):
                    raise WorkerError("DSL_INVALID", "子流程原SSE帧形状无效")
                # 原无choices帧也终止循环，仅在中心已完成时允许同样终止语义。
                final = not choices or choices[0].get("finish_reason") == "stop"
                if final:
                    if response.get("state") != "COMPLETED" or response.get("done") is not True or frame is not response["frames"][-1]:
                        raise WorkerError("RESOURCE_UNAVAILABLE", "子流程终帧未获中心正式完成确认")
                    completed = True
                yield frame
            if response.get("done"):
                if not completed:
                    raise WorkerError("RESOURCE_UNAVAILABLE", "子流程缺少完整原SSE终帧")
                return
            if not response["frames"]:
                await asyncio.sleep(.05)
    except asyncio.CancelledError:
        if dispatched and operation_id is None:
            context.boundary_failure = WorkerError("EFFECT_UNKNOWN", "子run已请求但标识/停止未确认")
        raise
    except WorkerError as error:
        context.boundary_failure = error
        raise
    except Exception:
        context.boundary_failure = WorkerError("DSL_INVALID", "子流程回执不满足原SSE协议")
        raise context.boundary_failure from None
    finally:
        if operation_id is not None and not completed:
            try:
                ack = await context.center.cancel_operation(context.assignment.run_id, context.assignment.lease_id,
                    context.assignment.attempt, operation_id)
                if ack.get("stopped") is not True:
                    raise CenterUnavailable()
            except BaseException:
                context.boundary_failure = WorkerError("EFFECT_UNKNOWN", "中心子run停止未确认，不能当成已取消")
