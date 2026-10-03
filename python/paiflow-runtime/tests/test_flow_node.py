"""真实原FlowNode算法及中心子run关联离线检查；没有正式子run Producer。"""
import asyncio
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from worker.center_client import CenterClient
from worker.engine_adapter import create_task_runner
from worker.errors import CenterUnavailable, WorkerError
from tests.test_original_engine import node, output, ref, edge, raw_workflow
from tests.test_worker import RecordingCenter, MemoryTransport, refresh_test_canonical
from tests.test_controlled_nodes import openai_frame


def flow_raw(mode=0, **params):
    identifiers = [output("answer")] if mode != 1 else [output("content"), output("reasoning_content")]
    raw = raw_workflow([node("node-start::s", outputs=[output("question")]),
        node("flow::child", inputs=[ref("question", "node-start::s", "question")], outputs=identifiers,
            flowResourceId="child-resource", flowId="legacy-flow", appId="legacy-app-label", uid="legacy-user-label", **params),
        node("node-end::e", inputs=[ref("answer", "flow::child", "content" if mode == 1 else "answer")], outputMode=0)],
        [edge("node-start::s", "flow::child"), edge("flow::child", "node-end::e")], {"question": "原子流程输入"})
    raw["resourceSnapshots"] = {"child-resource": {"kind": "workflow", "provider": "python", "version": "resource-v1",
        "workflowVersionId": "fixed-child-version", "dslHash": "a" * 64, "flowId": "legacy-flow",
        "outputMode": mode, "enabled": True, "legacyVersion": "original-v1"}}
    raw["effectPolicy"] = {"mode": "approved_write", "allowedOperations": ["workflow.invoke"], "approvalRef": "center-approved-fixture"}
    return raw


class FlowCenter(RecordingCenter):
    def __init__(self, raw, frames=None):
        super().__init__(raw)
        self.frames = frames if frames is not None else [openai_frame('{"answer":"中心子run输出"}')]
        self.changed, self.changed_frames = {}, {}
        self.frame_calls, self.cancel_calls = [], []
        self.stop_confirmed = True
        self.operation_wait = self.frame_wait = None
        self.reject_operation = False
        self.failure_kind = None

    def fixed(self):
        resource = self.raw["resourceSnapshots"]["child-resource"]
        return {"resourceId": "child-resource", "resourceVersion": resource["version"], "workflowVersionId": resource["workflowVersionId"],
            "dslHash": resource["dslHash"], "outputMode": resource["outputMode"]}

    async def resource(self, *args):
        self.resource_calls.append(args)
        return {**copy.deepcopy(self.raw["resourceSnapshots"]["child-resource"]), **self.changed}

    async def operation(self, *args):
        self.operations.append(copy.deepcopy(args))
        if self.operation_wait is not None:
            self.operation_wait.set()
            await asyncio.Event().wait()
        if self.reject_operation:
            raise CenterUnavailable()
        return {"state": "RUNNING", "kind": "workflow", "operationId": "child-operation", "childRunId": "center-child-run", **self.fixed()}

    async def workflow_frames(self, *args):
        self.frame_calls.append(args)
        if self.frame_wait is not None:
            self.frame_wait.set()
            await asyncio.Event().wait()
        cursor = args[-2]
        batch = copy.deepcopy(self.frames[cursor:cursor + 1])
        done = cursor + len(batch) == len(self.frames)
        ack = {"kind": "workflow", "childRunId": "center-child-run", "state": "FAILED" if self.failure_kind else "COMPLETED" if done else "RUNNING",
            "frames": batch, "nextCursor": cursor + len(batch), "done": done, **self.fixed(), **self.changed_frames}
        if self.failure_kind:
            ack["failureKind"] = self.failure_kind
        return ack

    async def cancel_operation(self, *args):
        self.cancel_calls.append(args)
        if not self.stop_confirmed:
            raise CenterUnavailable()
        return {"stopped": True}


class OriginalFlowNodeTest(unittest.IsolatedAsyncioTestCase):
    async def execute(self, raw, center=None, state="COMPLETED"):
        center = center or FlowCenter(raw)
        await asyncio.wait_for(create_task_runner(center, heartbeat_seconds=.02).run(raw), 8)
        self.assertEqual(center.results[-1]["state"], state, center.results[-1])
        return center

    async def test_original_three_output_modes_input_delta_reasoning_usage_and_no_old_target(self):
        for mode, content in [(0, '{"answer":"甲乙"}'), (1, "甲乙"), (2, "甲乙")]:
            raw = flow_raw(mode)
            center = FlowCenter(raw, [openai_frame(content[:2], status=None, reasoning="思"), openai_frame(content[2:], reasoning="考")])
            with patch.dict("os.environ", {"WORKFLOW_BASE_URL": "https://must-not-call.invalid"}):
                await self.execute(raw, center)
            self.assertEqual(center.results[-1]["output"]["outputs"], {"answer": "甲乙"})
            self.assertEqual(center.results[-1]["output"]["usage"]["total_tokens"], 5)
            self.assertEqual(center.operations[0][3:5], ("flow::child", "workflow.invoke"))
            body = center.operations[0][5]
            self.assertEqual(body["input"], {"question": "原子流程输入"})
            self.assertEqual(body["outputMode"], mode)
            self.assertNotIn("uid", body)
            self.assertNotIn("appId", body)
            self.assertEqual([call[-2] for call in center.frame_calls], [0, 1])
            self.assertEqual(center.cancel_calls, [])

    async def test_original_prompt_mode_reasoning_output_preserved(self):
        raw = flow_raw(1)
        raw["dsl"]["nodes"][-1]["data"]["inputs"].append(ref("reasoning", "flow::child", "reasoning_content"))
        refresh_test_canonical(raw)
        center = FlowCenter(raw, [openai_frame("答", reasoning="原推理")])
        await self.execute(raw, center)
        self.assertEqual(center.results[-1]["output"]["outputs"], {"answer": "答", "reasoning": "原推理"})

    async def test_fixed_version_and_legacy_selection_changed_or_missing_refuses_before_child(self):
        for changed in ({"version": "other"}, {"flowId": "other"}, {"dslHash": "b" * 64}, {"outputMode": 2}, {"enabled": False}):
            raw = flow_raw()
            center = FlowCenter(raw)
            center.changed = changed
            await self.execute(raw, center, "FAILED")
            self.assertEqual(center.operations, [])
        raw = flow_raw(version="other-original-version")
        center = await self.execute(raw, state="FAILED")
        self.assertEqual(center.operations, [])
        raw = flow_raw()
        raw["resourceSnapshots"] = {}
        center = await self.execute(raw, state="FAILED")
        self.assertEqual(center.operations, [])

    async def test_center_missing_child_endpoint_never_locally_recurses_or_completes(self):
        raw = flow_raw()
        center = FlowCenter(raw)
        center.reject_operation = True
        with self.assertRaises(CenterUnavailable):
            await asyncio.wait_for(create_task_runner(center).run(raw), 8)
        self.assertEqual(center.results, [])
        self.assertEqual(center.frame_calls, [])

    async def test_child_binding_cursor_and_unconfirmed_completion_refuse(self):
        for change in ({"childRunId": "other"}, {"dslHash": "b" * 64}, {"resourceVersion": "other"}, {"nextCursor": 3}, {"state": "RUNNING", "done": False}):
            raw = flow_raw()
            center = FlowCenter(raw)
            center.changed_frames = change
            await self.execute(raw, center, "FAILED")
            self.assertEqual(len(center.cancel_calls), 1)
            self.assertFalse(any(e["nodeId"] == "node-end::e" and e["eventType"] == "node.started" for e in center.events))

    async def test_original_child_error_and_invalid_variable_json_keep_codes(self):
        from workflow.exception.errors.err_code import CodeEnum
        raw = flow_raw()
        center = FlowCenter(raw, [{"code": 7002, "message": "recorded child failure"}])
        center.failure_kind = "child_run"
        await self.execute(raw, center, "FAILED")
        failed = [e for e in center.events if e["eventType"] == "node.failed"]
        self.assertEqual(failed[-1]["payload"]["legacyFrame"]["code"], CodeEnum.WORKFLOW_EXECUTION_ERROR.code)
        self.assertEqual(center.cancel_calls, [])
        center = FlowCenter(flow_raw(), [openai_frame("invalid variable JSON")])
        await self.execute(center.raw, center, "FAILED")
        failed = [e for e in center.events if e["eventType"] == "node.failed"]
        self.assertEqual(failed[-1]["payload"]["legacyFrame"]["code"], CodeEnum.WORKFLOW_EXEC_RESP_FORMAT_ERROR.code)

    async def test_authorization_or_invented_child_failure_never_becomes_original_success(self):
        for kind, frame in [("authorization", {"code": 7002}), ("child_run", {"code": 0}), ("child_run", {"code": "7002"})]:
            raw = flow_raw()
            center = FlowCenter(raw, [frame])
            center.failure_kind = kind
            await self.execute(raw, center, "FAILED")
            self.assertEqual(len(center.cancel_calls), 1)

    async def test_original_empty_choices_termination_requires_center_completed(self):
        raw = flow_raw(2)
        center = FlowCenter(raw, [openai_frame("积累", status=None), {"choices": []}])
        await self.execute(raw, center)
        self.assertEqual(center.results[-1]["output"]["outputs"], {"answer": "积累"})
        self.assertEqual(center.cancel_calls, [])

    async def test_cancellation_before_child_ack_and_unconfirmed_stop_keeps_unknown(self):
        for before_ack, stopped, expected in [(True, True, "EFFECT_UNKNOWN"), (False, False, "EFFECT_UNKNOWN"), (False, True, "CANCELLED")]:
            raw = flow_raw()
            center = FlowCenter(raw)
            started = asyncio.Event()
            if before_ack:
                center.operation_wait = started
            else:
                center.frame_wait = started
                center.stop_confirmed = stopped
            task = asyncio.create_task(create_task_runner(center, heartbeat_seconds=.02).run(raw))
            await asyncio.wait_for(started.wait(), 5)
            center.cancel_requested = True
            await asyncio.wait_for(task, 8)
            self.assertEqual(center.results[-1]["state"], expected)

    async def test_enabled_history_and_unapproved_effect_do_not_invoke_child(self):
        for raw in (flow_raw(enableChatHistoryV2={"isEnabled": True}), flow_raw()):
            if not raw["dsl"]["nodes"][1]["data"]["nodeParam"].get("enableChatHistoryV2"):
                raw["effectPolicy"] = {"mode": "read_only", "allowedOperations": ["workflow.invoke"]}
            center = await self.execute(raw, state="FAILED")
            self.assertEqual(center.operations, [])

    async def test_iteration_child_without_formal_instance_binding_refuses(self):
        from worker.events import EventPublisher
        from worker.flow_bridge import frames
        from worker.node_context import CURRENT_ITERATION_ID
        from worker.task_runner import CURRENT_CONTEXT, RunContext, Assignment
        from workflow.engine.nodes.flow.flow_node import FlowNode
        raw = flow_raw()
        center = FlowCenter(raw)
        assigned = Assignment.parse(raw)
        context = RunContext(center, assigned, EventPublisher(center, assigned.run_id, assigned.lease_id, 1, assigned.last_event_seq))
        instance = FlowNode(node_id="flow::child", node_type="flow", alias_name="flow", flowId="legacy-flow", appId="label", uid="label",
            flowResourceId="child-resource", input_identifier=["question"], output_identifier=["answer"])
        tokens = CURRENT_CONTEXT.set(context), CURRENT_ITERATION_ID.set("iteration::outer:0")
        source = frames(instance, {"question": "真实轮次绑定缺失"}, None)
        try:
            with self.assertRaises(WorkerError) as error:
                await anext(source)
            self.assertEqual(error.exception.code, "RESOURCE_UNAVAILABLE")
            self.assertEqual(center.operations, [])
            self.assertEqual(center.resource_calls, [])
        finally:
            await source.aclose()
            CURRENT_ITERATION_ID.reset(tokens[1])
            CURRENT_CONTEXT.reset(tokens[0])

    async def test_http_and_draft_binding_are_exact_and_old_database_request_blocked(self):
        from jsonschema import Draft202012Validator
        from workflow.engine.nodes.flow.flow_node import FlowNode
        raw = flow_raw(version="original-v1")
        center = await self.execute(raw)
        document = json.loads((Path(__file__).resolve().parents[1] / "protocols/flow-draft.schema.json").read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(document)
        Draft202012Validator({"$ref": "#/$defs/Parameters", "$defs": document["$defs"]}).validate(center.operations[0][5])
        ack = await center.workflow_frames("run-1", "lease-1", 1, "child-operation", 0, 64)
        Draft202012Validator({"$ref": "#/$defs/FrameAck", "$defs": document["$defs"]}).validate(ack)
        transport = MemoryTransport(response={"code": 200, "data": ack})
        client = CenterClient("https://center.example", "fixture-identity", transport=transport)
        self.assertEqual(await client.workflow_frames("r", "l", 1, "operation/1", 0), ack)
        self.assertTrue(transport.calls[0][1].endswith("/operations/operation%2F1/frames"))
        instance = FlowNode(node_id="flow::child", node_type="flow", alias_name="flow", flowId="legacy", appId="label", uid="label",
            input_identifier=[], output_identifier=["answer"])
        with self.assertRaises(WorkerError) as error:
            instance._assemble_request("https://must-not-call.invalid", {}, None, None)
        self.assertEqual(error.exception.code, "TOOL_POLICY_DENIED")


if __name__ == "__main__":
    unittest.main()
