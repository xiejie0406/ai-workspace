"""原普通知识检索协议的中心绑定；不把本地索引别名成AIUI/专业RAG。"""
import hashlib
import json

from workflow.exception.e import CustomException
from workflow.exception.errors.err_code import CodeEnum

from .errors import CenterUnavailable, WorkerError
from .events import _validate_payload
from .node_context import CURRENT_ITERATION_ID
from .task_runner import current_context


async def retrieve(config, payload_factory, span):
    context = current_context()
    try:
        if config.node_id not in context.node_ids or not config.resource_id:
            raise WorkerError("RESOURCE_UNAVAILABLE", "知识节点缺少中心固定资源绑定")
        if config.url:
            raise WorkerError("TOOL_POLICY_DENIED", "知识客户端不接受旧URL/env任意目标")
        if config.history:
            raise WorkerError("RESOURCE_UNAVAILABLE", "知识检索正式历史scope尚未接通")
        snapshot = context.assignment.resources.get(config.resource_id)
        if not isinstance(snapshot, dict):
            raise WorkerError("TOOL_POLICY_DENIED", "知识资源不属于运行固定快照")
        resource = await context.resource(config.resource_id)
        fields = {"kind": "knowledge", "provider": config.rag_type, "repoIds": config.repo_id, "docIds": config.doc_ids}
        for key, expected in fields.items():
            if snapshot.get(key) != expected or resource.get(key) != expected:
                raise WorkerError("VERSION_CONFLICT", "旧repo/doc参数与中心已授权知识版本不一致")
        version, flow_id = snapshot.get("version"), snapshot.get("flowId")
        if (not isinstance(version, str) or not version or resource.get("version") != version
                or snapshot.get("enabled") is not True or resource.get("enabled") is not True
                or not isinstance(flow_id, str) or not flow_id or resource.get("flowId") != flow_id
                or config.flow_id and config.flow_id != flow_id):
            raise WorkerError("RESOURCE_UNAVAILABLE", "知识固定版本/flow归属未获得中心确认")
        # 只有真实中心版本绑定可填原flowId；不借runId/versionId造会话或权限。
        config.flow_id = flow_id
        payload = json.loads(payload_factory())
        parameters = {"resourceId": config.resource_id, "resourceVersion": version, "legacyPayload": payload}
        _validate_payload(parameters)
        encoded = json.dumps(parameters, ensure_ascii=False, sort_keys=True, allow_nan=False, separators=(",", ":")).encode("utf-8")
        if len(encoded) > 2 * 1024 * 1024:
            raise WorkerError("DSL_INVALID", "知识输入超过中心预算")
        key = f"{context.assignment.run_id}:{context.assignment.attempt}:{config.node_id}:{CURRENT_ITERATION_ID.get() or '-'}:{hashlib.sha256(encoded).hexdigest()}:knowledge"
        ack = await context.operation(config.node_id, "knowledge.retrieve", parameters, key=key)
        if (not isinstance(ack, dict) or ack.get("resourceId") != config.resource_id
                or ack.get("resourceVersion") != version or ack.get("provider") != config.rag_type):
            raise CenterUnavailable()
        if ack.get("state") == "FAILED" and ack.get("failureKind") == "provider":
            original = ack.get("legacyResponse")
            if not isinstance(original, dict) or type(original.get("code")) is not int or original["code"] == 0:
                raise WorkerError("DSL_INVALID", "知识Provider失败缺少合法原错误码")
            _validate_payload(original)
            raise CustomException(err_code=CodeEnum.KNOWLEDGE_REQUEST_ERROR,
                err_msg="原知识Provider返回失败", cause_error=f"原知识Provider错误码 {original['code']}")
        if ack.get("state") != "COMPLETED" or ack.get("failureKind") is not None:
            raise WorkerError("TOOL_POLICY_DENIED" if ack.get("failureKind") == "authorization" else "RESOURCE_UNAVAILABLE",
                "中心知识操作未确认完成")
        data = ack.get("data")
        if not isinstance(data, dict) or "results" not in data:
            raise WorkerError("DSL_INVALID", "知识回执缺少原results结构")
        _validate_payload(data)
        return json.dumps(data, ensure_ascii=False, allow_nan=False)
    except CustomException:
        raise  # 原节点保留KNOWLEDGE_REQUEST_ERROR及其失败/重试规则。
    except WorkerError as error:
        context.boundary_failure = error
        raise
    except Exception:
        context.boundary_failure = WorkerError("DSL_INVALID", "知识回执不满足原JSON协议")
        raise context.boundary_failure from None
