"""运行历史通过中心端口确认保存，绝不创建本地历史库。"""
from .task_runner import current_context
from .errors import WorkerError
from .node_context import CURRENT_ITERATION_ID


async def add_history(flow_id, node_id, uid, raw_question, raw_answer, chat_id=None, **kwargs):
    context = current_context()
    if context.execution_context is None or node_id not in context.node_ids:
        raise WorkerError("RESOURCE_UNAVAILABLE", "中心正式历史作用域尚未绑定")
    binding = context.execution_context
    if flow_id != binding["flowId"] or uid != binding["userId"] or chat_id is not None and chat_id != binding["chatId"]:
        raise WorkerError("TOOL_POLICY_DENIED", "原历史作用域与中心租约绑定不一致")
    # flow/uid/chat 仅为原算法上下文；中心从租约关联正式身份和会话。
    try:
        result = await context.center.append_history(context.assignment.run_id, context.assignment.lease_id,
            context.assignment.attempt, node_id, raw_question, raw_answer, iteration_id=CURRENT_ITERATION_ID.get())
        context.assert_active()
        return result
    except WorkerError as error:
        context.boundary_failure = error
        raise
