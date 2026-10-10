"""V4模型节点许可前检查；不执行原节点、不持有中心许可或替换原算法。"""
from .errors import WorkerError


def validate_assignment(assignment):
    if assignment.execution_codec != 'paiflow.execution.v4':
        return
    from workflow.engine.entities.workflow_dsl import WorkflowDSL
    from workflow.engine.node import NodeFactory
    from workflow.extensions.otlp.trace.span import Span
    try:
        dsl = WorkflowDSL.model_validate(assignment.dsl)
        span = Span(app_id='', uid='', chat_id=None, sid=assignment.run_id)
        for node in dsl.nodes:
            kind = node.id.split('::', 1)[0]
            if kind not in {'spark-llm', 'extractor-parameter', 'decision-making', 'question-answer'}:
                continue
            if node.data.nodeMeta.nodeType != kind:
                raise WorkerError('DSL_INVALID', 'V4原模型节点类型与固定标识不一致')
            instance = NodeFactory.create(node, span).node_instance
            if instance.enableChatHistory or instance.enableChatHistoryV2.is_enabled:
                raise WorkerError('RESOURCE_UNAVAILABLE', 'V4模型历史尚未获得中心固定来源绑定')
            needs_model = kind != 'question-answer' or (instance.answerType == 'direct' and instance.directAnswer.handleResponse)
            if not needs_model:
                continue
            mode = 'chat'
            if kind == 'extractor-parameter' and instance.reasonMode != 1:
                mode = 'function_call'
            elif kind == 'decision-making' and instance.reasonMode != 1 and instance.useFunctionCall:
                mode = 'function_call'
            fixed = assignment.resources.get(instance.modelResourceId)
            if (instance.url or instance.apiKey or instance.apiSecret or not isinstance(fixed, dict)
                    or set(fixed) != {'kind', 'provider', 'version', 'modelName', 'enabled', 'capabilities'}
                    or fixed.get('kind') != 'model' or fixed.get('enabled') is not True
                    or instance.source not in {'openai', 'xinghuo'}
                    or fixed.get('provider') != instance.source or fixed.get('modelName') != instance.domain
                    or not isinstance(fixed.get('version'), str) or not 1 <= len(fixed['version']) <= 256
                    or not 1 <= len(instance.domain) <= 256
                    or not (fixed.get('capabilities') == ['chat'] or instance.source == 'xinghuo'
                            and fixed.get('capabilities') == ['chat', 'function_call'])
                    or mode not in fixed.get('capabilities', [])
                    or mode == 'function_call' and instance.source != 'xinghuo'
                    or instance.domain in {'image', 'imagev3'}
                    or assignment.effect_policy.get('mode') != 'approved_write'
                    or 'model.invoke' not in assignment.effect_policy.get('allowedOperations', [])):
                raise WorkerError('RESOURCE_UNAVAILABLE', 'V4模型节点缺少已准入固定资源和原调用能力')
    except WorkerError:
        raise
    except Exception:
        raise WorkerError('DSL_INVALID', 'V4原模型节点参数无效') from None
