"""顶层静态问答许可前准入；沿用原 typed 节点和中心检查点。"""
from __future__ import annotations

from .errors import WorkerError
from .pure_iteration import validate_top_level


STATIC_TYPES = frozenset({"node-start", "node-end", "if-else", "text-joiner", "message", "question-answer"})


def _invalid():
    raise WorkerError("DSL_INVALID", "原静态问答图或节点参数无效")


def _unavailable():
    raise WorkerError("RESOURCE_UNAVAILABLE", "本批仅支持顶层静态问答，不支持模型、历史、资源或主动重试")


def validate_assignment(assignment):
    """只读已核中心快照；拒绝不得取得 begin 或提交另一执行者的终态。"""
    nodes = assignment.dsl.get("nodes", [])
    question_nodes = [node for node in nodes if node.get("id", "").startswith("question-answer::")]
    if not question_nodes:
        return None
    if (assignment.resources or assignment.effect_policy.get("mode") != "read_only"
            or assignment.effect_policy.get("allowedOperations") != []):
        _unavailable()
    # 原 ignore 返回这些未经过 output_identifier 过滤的字段；原 Pool 在地址
    # 已存在时会写入，因此也须检查与其它节点声明地址的碰撞。
    implicit = {}
    for node in question_nodes:
        parameters = node["data"].get("nodeParam", {})
        if not isinstance(parameters, dict):
            _invalid()
        implicit[node["id"]] = {"query", "content"} | (
            {"id"} if parameters.get("answerType") == "option" else set())
    plan = validate_top_level(assignment.dsl, assignment.input, STATIC_TYPES, extra_outputs=implicit)
    # 原类在此只执行 Pydantic/原 customOutput 构造校验；不创建工作流 Factory、
    # 枚举 Chains、执行节点、读取中心资源或构造 Provider SDK。
    from workflow.engine.entities.workflow_dsl import WorkflowDSL
    from workflow.engine.node import NodeFactory
    from workflow.extensions.otlp.trace.span import Span

    try:
        dsl = WorkflowDSL.model_validate(assignment.dsl)
        span = Span(app_id="", uid="", chat_id=None, sid=assignment.run_id)
        for node in dsl.nodes:
            kind = node.id.split("::", 1)[0]
            if node.data.nodeMeta.nodeType != kind:
                _invalid()
            instance = NodeFactory.create(node, span).node_instance
            if instance.retry_config.should_retry:
                _unavailable()
            if kind == "question-answer":
                if instance.answerType == "direct" and instance.directAnswer.handleResponse:
                    _unavailable()
                if instance.enableChatHistory or instance.enableChatHistoryV2.is_enabled:
                    _unavailable()
                if instance.url or instance.apiKey or instance.apiSecret or instance.modelResourceId:
                    _unavailable()
                if instance.answerType == "option" and sum(option.type == 1 for option in instance.optionAnswer) > 1:
                    _invalid()
    except WorkerError:
        raise
    except Exception:
        # 原 Pydantic/协议异常可能含输入原文，不向事件或日志回送私有材料。
        _invalid()
    return plan
