"""真实原 WorkflowEngineFactory/DFS/分支/变量/迭代的中心适配。

原21类消费者均懒加载注册；缺正式资源/Producer/未恢复变体时明确拒绝。
没有第二引擎算法或本地正式缓存，取消会清理原引擎子任务。
"""
from __future__ import annotations

import asyncio

from .errors import WorkerError
from .task_runner import Assignment, RunContext
from .node_context import CURRENT_ITERATION_ID


PURE_NODE_TYPES = frozenset({"node-start", "node-end", "if-else", "text-joiner", "node-variable",
                           "iteration", "iteration-node-start", "iteration-node-end", "message"})
CONTROLLED_NODE_TYPES = frozenset({"ifly-code", "spark-llm", "extractor-parameter", "decision-making", "question-answer", "knowledge-base", "flow",
    "plugin", "knowledge-pro-base", "agent", "database", "rpa"})


class _CenterFrames:
    def __init__(self, context):
        self.context = context
        self.started = set()
        self.failure = None
        self.stream_content = {}
        self.stream_reasoning = {}
        self.stream_bytes = 0

    async def put(self, frame):
        if self.failure is not None:
            raise self.failure
        try:
            # 原 UI 以严格 null 区分节点过程帧；保留原 typed 字段的空值。
            body = frame.model_dump(mode="json", exclude_none=False)
            step = frame.workflow_step
            node = step.node if step else None
            if node is None or not node.id:
                # 原 workflow start/end 是输送格式；正式 run.* 只由中心 CAS 生成。
                return
            node_id = node.id
            iteration_id = getattr(frame, "_worker_iteration_id", None)
            key = (node_id, iteration_id)
            identifiers = {"node_id": node_id, "iteration_id": iteration_id}
            if frame.code != 0:
                # 原错误 message/cause 可能含凭据，只交数字错误码和明确来源。
                body["message"] = "原节点执行失败"
            payload = {"legacyFrame": body, "progress": step.progress}
            if node.finish_reason == "interrupt":
                event_data = body.get("event_data", {})
                ack = self.context.checkpoint_acks.get(event_data.get("event_id"))
                if ack is None or ack["nodeId"] != node_id or ack["iterationId"] != iteration_id:
                    raise WorkerError("RESOURCE_UNAVAILABLE", "原中断帧缺少中心检查点确认")
                await self.context.emit("checkpoint", {**ack, **payload}, **identifiers)
                return
            if node_id.startswith("node-end::"):
                # 原 chat_service 非流输出缓存也按 callback 顺序拼接，保留原尾帧。
                for choice in frame.choices:
                    self.stream_content.setdefault(key, []).append(choice.delta.content or "")
                    self.stream_reasoning.setdefault(key, []).append(choice.delta.reasoning_content or "")
                    self.stream_bytes += len(((choice.delta.content or "") + (choice.delta.reasoning_content or "")).encode("utf-8"))
                if self.stream_bytes > 2 * 1024 * 1024:
                    raise WorkerError("DSL_INVALID", "原输出流超过中心结果预算，须改为 artifact")
            if node.finish_reason == "stop":
                await self.context.emit("node.completed" if frame.code == 0 else "node.failed", payload, **identifiers)
                self.started.discard(key)
            elif key not in self.started:
                self.started.add(key)
                await self.context.emit("node.started", payload, **identifiers)
            else:
                for choice in frame.choices:
                    if choice.delta.content:
                        await self.context.emit("text.delta", {"text": choice.delta.content, "legacyFrame": body}, **identifiers)
                    if choice.delta.reasoning_content:
                        await self.context.emit("reasoning.delta", {"text": choice.delta.reasoning_content}, **identifiers)
            if node.usage:
                await self.context.emit("usage", {"usage": node.usage.model_dump()}, **identifiers)
        except Exception as error:
            self.failure = error
            raise


class OriginalWorkflowEngine:
    supported_types = PURE_NODE_TYPES | CONTROLLED_NODE_TYPES

    async def execute(self, assignment: Assignment, context: RunContext) -> dict:
        from workflow.engine.callbacks.callback_handler import ChatCallBacks, ChatCallBackConsumer, StructuredConsumer
        from workflow.engine.dsl_engine import WorkflowEngineFactory
        from workflow.engine.entities.variable_pool import ParamKey
        from workflow.engine.entities.workflow_dsl import WorkflowDSL
        from workflow.engine.nodes.entities.node_run_result import WorkflowNodeExecutionStatus
        from workflow.extensions.otlp.log_trace.workflow_log import WorkflowLog
        from workflow.extensions.otlp.trace.span import Span

        types = {node["id"].split("::", 1)[0] for node in assignment.dsl["nodes"]}
        if not types <= self.supported_types:
            raise WorkerError("ENGINE_UNSUPPORTED", "该原节点的中心资源客户端尚未就绪")
        if "node-variable" in types and context.execution_context is None:
            raise WorkerError("RESOURCE_UNAVAILABLE", "中心正式变量作用域尚未绑定")
        binding = context.execution_context or {}
        span = Span(app_id=binding.get("appId", ""), uid=binding.get("userId", ""),
                    chat_id=binding.get("chatId"), sid=assignment.run_id)
        try:
            dsl = WorkflowDSL.model_validate(assignment.dsl)
            engine = WorkflowEngineFactory.create_engine(dsl, span)
        except Exception:
            raise WorkerError("DSL_INVALID", "原引擎未通过 DSL/节点参数/链依赖校验") from None
        pool = engine.engine_ctx.variable_pool
        pool.system_params.set(ParamKey.FlowId, binding.get("flowId", ""))
        pool.system_params.set(ParamKey.IsRelease, True)
        for output_id, dependencies in engine.engine_ctx.msg_or_end_node_deps.items():
            for source_id in dependencies.data_dep:
                if source_id.split("::")[0] in {"spark-llm", "agent", "knowledge-pro-base", "flow"}:
                    pool.stream_data.setdefault(output_id, {})[source_id] = asyncio.Queue(maxsize=128)
        for entry in assignment.dsl["nodes"]:
            params = entry["data"].get("nodeParam", {})
            if entry["id"].startswith("flow::"):
                fixed = assignment.resources.get(params.get("flowResourceId"))
                if not isinstance(fixed, dict) or type(fixed.get("outputMode")) is not int or fixed["outputMode"] not in {0, 1, 2}:
                    raise WorkerError("RESOURCE_UNAVAILABLE", "子流程缺少中心固定输出模式")
                pool.system_params.set(ParamKey.FlowOutputMode, fixed["outputMode"], node_id=entry["id"])
            instance = engine.engine_ctx.built_nodes[entry["id"]].node_instance
            history_v2 = getattr(instance, "enableChatHistoryV2", None)
            if (getattr(instance, "enableChatHistory", False)
                    or (history_v2 is not None and history_v2.is_enabled)):
                raise WorkerError("RESOURCE_UNAVAILABLE", "模型正式历史快照尚未接通，不降级为空记忆")
        frames = _CenterFrames(context)
        ordered = asyncio.Queue(maxsize=128)
        node_order = asyncio.Queue(maxsize=128)
        structured = {}
        callback_consumer = ChatCallBackConsumer(ordered, node_order, structured)
        structured_consumer = StructuredConsumer(node_order, structured, frames, callback_consumer.support_stream_node_id_set)
        class CenterCallbacks(ChatCallBacks):
            async def _put_frame_into_queue(self, node_id, resp, finish_reason=""):
                # metadata 跟随原 frame 经过排序队列，避免消费者 ContextVar 丢失关联。
                object.__setattr__(resp, "_worker_iteration_id", CURRENT_ITERATION_ID.get())
                await super()._put_frame_into_queue(node_id, resp, finish_reason)

        callbacks = CenterCallbacks(sid=assignment.run_id, stream_queue=frames,
            end_node_output_mode=engine.end_node_output_mode, support_stream_node_ids=engine.support_stream_node_ids,
            need_order_stream_result_q=ordered, chains=engine.engine_ctx.chains,
            event_id=assignment.lease_id, flow_id=assignment.version_id)
        trace = WorkflowLog(sid=assignment.run_id, service_id=assignment.version_id,
                            flow_id=binding.get("flowId", ""), uid=binding.get("userId", ""),
                            app_id=binding.get("appId", ""))
        consumers = [asyncio.create_task(callback_consumer.consume()), asyncio.create_task(structured_consumer.consume())]
        execution = asyncio.create_task(engine.async_run(assignment.input, span, callbacks, [], [], trace))
        try:
            remaining = {execution, *consumers}
            while execution in remaining:
                done, remaining = await asyncio.wait(remaining, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    if not task.cancelled() and task.exception() is not None:
                        raise task.exception()
            result = await execution
            await asyncio.gather(*consumers)
            if context.boundary_failure is not None:
                raise context.boundary_failure
            if frames.failure is not None:
                raise frames.failure
            if result.error is not None or result.status != WorkflowNodeExecutionStatus.SUCCEEDED:
                raise WorkerError("ENGINE_FAILED", "原流程返回失败节点结果")
            final_node = engine.engine_ctx.built_nodes[result.node_id].node_instance
            is_stream_prompt = engine.end_node_output_mode.value == 1 and getattr(final_node, "streamOutput", False)
            key = (result.node_id, None)
            return {"outputs": result.outputs,
                    "content": "".join(frames.stream_content.get(key, [])) if is_stream_prompt else result.node_answer_content,
                    "reasoningContent": "".join(frames.stream_reasoning.get(key, [])) if is_stream_prompt else result.node_answer_reasoning_content,
                    "usage": callbacks.generate_usage.model_dump()}
        except BaseException:
            if context.boundary_failure is not None:
                raise context.boundary_failure
            if frames.failure is not None:
                raise frames.failure
            raise
        finally:
            tasks = [execution, *consumers]
            for item in [engine, *engine.engine_ctx.iteration_engine.values()]:
                tasks.extend(item.engine_ctx.dfs_tasks)
            unique = {task for task in tasks if task is not asyncio.current_task()}
            for task in unique:
                if not task.done():
                    task.cancel()
            if unique:
                stopped, pending = await asyncio.wait(unique, timeout=2)
                for task in stopped:
                    if not task.cancelled():
                        task.exception()
                if pending:
                    raise WorkerError("EFFECT_UNKNOWN", "原引擎子任务未响应停止，须由中心核对")


def create_task_runner(center, **settings):
    """正式注册真实原引擎；不可用类型由能力预检拒绝，不注册测试引擎。"""
    from .task_runner import TaskRunner
    return TaskRunner(center, OriginalWorkflowEngine(), **settings)
