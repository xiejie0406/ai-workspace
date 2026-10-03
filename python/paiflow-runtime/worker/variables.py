"""原会话变量语义委托中心；不在 Worker Redis/磁盘持有第二套状态。"""
from .task_runner import current_context
from .node_context import CURRENT_ITERATION_ID
from .errors import WorkerError
import hashlib
import json


class VariablesManage:
    def __init__(self, flow_id, uid, app_id, chat_id, *, node_id):
        # 保留原构造契约，正式归属由租约和中心会话决定。
        self.flow_id, self.uid, self.app_id, self.chat_id = flow_id, uid, app_id, chat_id
        self.node_id = node_id

    async def _request(self, operation, **values):
        context = current_context()
        if context.execution_context is None or self.node_id not in context.node_ids:
            raise WorkerError("RESOURCE_UNAVAILABLE", "中心正式变量作用域尚未绑定")
        binding = context.execution_context
        if (self.flow_id, self.uid, self.app_id, self.chat_id) != (
                binding["flowId"], binding["userId"], binding["appId"], binding["chatId"]):
            raise WorkerError("TOOL_POLICY_DENIED", "原变量作用域与中心租约绑定不一致")
        try:
            result = await context.center.variables(context.assignment.run_id, context.assignment.lease_id,
                context.assignment.attempt, operation, node_id=self.node_id, **values)
            context.assert_active()
            return result
        except WorkerError as error:
            context.boundary_failure = error
            raise

    async def _mutate(self, operation, **values):
        context = current_context()
        identity = json.dumps({"operation": operation, "nodeId": self.node_id,
            "iterationId": CURRENT_ITERATION_ID.get(), "values": values}, ensure_ascii=False,
            sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
        key = f"{context.assignment.run_id}:{context.assignment.attempt}:variables:" + hashlib.sha256(identity).hexdigest()
        async with context.variable_lock:
            # 保存同一逻辑写的原请求用于原节点重试，不猜测断线写入结果。
            request = context.variable_requests.get(key)
            if request is None:
                state = await self._request("get_all")
                request = {**values, "expectedRevision": state["revision"], "idempotencyKey": key}
                context.variable_requests[key] = request
            return await self._request(operation, **request)

    async def add_variable(self, variable_name, value):
        return await self._mutate("set", variableName=variable_name, value=value)

    async def get_variable(self, variable_name):
        data = await self._request("get", variableName=variable_name)
        return data.get("value")

    async def get_all_variables(self):
        data = await self._request("get_all")
        return data["variables"]

    async def clear(self):
        return await self._mutate("clear")
