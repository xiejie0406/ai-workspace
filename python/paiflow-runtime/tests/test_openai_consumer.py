"""真实原Factory/OpenAI消费者的尾用量兼容；中心仅离线原帧fixture。"""
import asyncio
import copy
from dataclasses import replace
import hashlib
import json
import unittest

from worker.engine_adapter import create_task_runner, OriginalWorkflowEngine
from worker.errors import WorkerError
from worker.events import EventPublisher
from worker.task_runner import Assignment, RunContext, CURRENT_CONTEXT
from test_controlled_nodes import ModelCenter, model_raw, openai_frame
from test_original_engine import edge, node, output, raw_workflow, ref
from test_openai_producer import BodyFixture, CenterFixture, PrivateFixture, chunk, encoded, grant_fixture, usage_only
from worker.producers.openai_chat import OpenAIChatProducer


def usage_frame(tokens):
    return {"choices": [], "usage": {"prompt_tokens": 2, "completion_tokens": tokens - 2,
                                      "total_tokens": tokens, "provider_details": {"retained": True}}}


def frames(tokens=11):
    first = openai_frame("原内容", status=None, reasoning="原推理")
    first["usage"] = None
    finish = openai_frame("尾内容", status="stop")
    finish["usage"] = None
    return [first, finish, usage_frame(tokens)]


class OpenAIConsumerTest(unittest.IsolatedAsyncioTestCase):
    async def run_graph(self, supplied=None, *, center=None, raw=None, completed=True):
        raw = raw or model_raw(source="openai")
        center = center or ModelCenter(raw, supplied if supplied is not None else frames())
        await asyncio.wait_for(create_task_runner(center).run(raw), 8)
        self.assertEqual(center.results[-1]["state"], "COMPLETED" if completed else "FAILED", center.results[-1])
        return center.results[-1], center

    async def test_real_factory_tail_usage_preserved_without_changing_finish_or_duplicate_text(self):
        supplied = frames()
        before = copy.deepcopy(supplied)
        fingerprint = hashlib.sha256(json.dumps(supplied[1], sort_keys=True).encode()).hexdigest()
        result, center = await self.run_graph(supplied)
        self.assertEqual(result["output"]["outputs"], {"answer": "原内容尾内容"})
        self.assertEqual(result["output"]["usage"]["total_tokens"], 11)
        self.assertEqual(center.frames, before)
        self.assertEqual(hashlib.sha256(json.dumps(center.frames[1], sort_keys=True).encode()).hexdigest(), fingerprint)
        self.assertEqual([call[-2] for call in center.frame_calls], [0, 1, 2])

    async def test_real_sdk_producer_frames_through_original_factory(self):
        class JoinedCenter(ModelCenter):
            async def operation(self, *args):
                ack = await super().operation(*args)
                params = args[5]
                grant = grant_fixture()
                config = replace(grant.config, resource_id="model-1", version="model-version-1",
                                 options_json=encoded(params["options"]))
                raw = encoded(params)
                grant = replace(grant, operation_id=ack['operationId'], run_id=ack['runId'], lease_id=ack['leaseId'],
                                attempt=ack['attempt'], node_id=ack['nodeId'], workflow_version_id=ack['workflowVersionId'],
                                dsl_hash=ack['dslHash'], config=config, parameters_json=raw,
                                parameters_sha256=hashlib.sha256(raw).hexdigest())
                self.producer_center = CenterFixture(grant)
                self.private = PrivateFixture(BodyFixture([chunk("SDK原内容", reasoning="SDK原推理"),
                    chunk(finish="stop"), usage_only(13)]))
                self.producer_outcome = await OpenAIChatProducer(self.producer_center, self.private).produce(ack['operationId'])
                self.frames = [copy.deepcopy(item[1]) for item in self.producer_center.frames]
                return ack
        raw = model_raw(source="openai"); center = JoinedCenter(raw, [])
        result, _ = await self.run_graph(raw=raw, center=center)
        self.assertEqual(result["output"]["outputs"], {"answer": "SDK原内容"})
        self.assertEqual(result["output"]["usage"]["total_tokens"], 13)
        self.assertEqual(center.producer_center.finishes, [(3, 2, "stop")])
        self.assertEqual(len(center.private.http.calls), 1)
        self.assertEqual(center.frames[-1]["choices"], [])

    async def test_latest_usage_snapshot_not_sum_and_nullable_role_delta(self):
        role = openai_frame(None, status=None); role["usage"] = None
        supplied = [role, usage_frame(17), *frames(9)]
        result, _ = await self.run_graph(supplied)
        self.assertEqual(result["output"]["usage"]["total_tokens"], 9)
        self.assertEqual(result["output"]["outputs"]["answer"], "原内容尾内容")

    async def test_illegal_empty_choices_does_not_reach_original_decoder_success(self):
        for usage in (None, {}, {"total_tokens": 3}, {"prompt_tokens": True, "completion_tokens": 1, "total_tokens": 2}):
            await self.run_graph([*frames()[:2], {"choices": [], "usage": usage}], completed=False)

    async def test_eof_usage_only_without_real_finish_rejected(self):
        for supplied in ([openai_frame("片段", status=None)], [usage_frame(7)]):
            await self.run_graph(supplied, completed=False)

    async def test_nonstop_with_tail_usage_keeps_original_openai_error(self):
        for reason in ("length", "content_filter", "tool_calls", "function_call"):
            raw = model_raw(source="openai")
            center = ModelCenter(raw, [openai_frame("部分", status=reason), usage_frame(7)])
            center.failure_kind = "provider"
            result, _ = await self.run_graph(raw=raw, center=center, completed=False)
            self.assertEqual(result["error"]["code"], "ENGINE_FAILED")
            failed = [e for e in center.events if e["eventType"] == "node.failed"]
            self.assertEqual(failed[-1]["payload"]["legacyFrame"]["code"], 20380)

    async def test_missing_wrong_or_bool_finish_last_sequence_ack_rejected(self):
        for field, value in (("finishSeq", None), ("finishSeq", True), ("finishSeq", 1), ("lastSeq", None), ("lastSeq", 2)):
            class BadAck(ModelCenter):
                async def operation_frames(self, *args):
                    response = await super().operation_frames(*args)
                    if response["done"]:
                        if value is None:
                            response.pop(field, None)
                        else:
                            response[field] = value
                    return response
            raw = model_raw(source="openai")
            await self.run_graph(raw=raw, center=BadAck(raw, frames()), completed=False)

    async def test_finish_not_delivered_before_bound_center_completion(self):
        class DeferredCenter(ModelCenter):
            def __init__(self, raw, supplied):
                super().__init__(raw, supplied)
                self.waiting, self.release = asyncio.Event(), asyncio.Event()
            async def operation_frames(self, *args):
                if args[-2] == len(self.frames):
                    self.waiting.set()
                    await self.release.wait()
                    return {**copy.deepcopy(self.bindings[args[-3]]), "state": "COMPLETED", 'operationState': 'COMPLETED',
                        "frames": [], "nextCursor": len(self.frames), "done": True, "finishSeq": 2, "lastSeq": 3, 'failureKind': None}
                response = await super().operation_frames(*args)
                response["state"], response['operationState'], response["done"] = "RUNNING", 'RUNNING', False
                return response
        raw = model_raw(source="openai"); center = DeferredCenter(raw, frames())
        task = asyncio.create_task(create_task_runner(center).run(raw))
        await asyncio.wait_for(center.waiting.wait(), 3)
        self.assertFalse(task.done())
        self.assertFalse(any(e["nodeId"] == "spark-llm::model" and e["eventType"] == "node.completed" for e in center.events))
        center.release.set(); await asyncio.wait_for(task, 4)
        self.assertEqual(center.results[-1]["state"], "COMPLETED")
        self.assertEqual(center.results[-1]["output"]["usage"]["total_tokens"], 11)

    async def test_content_after_real_finish_rejected(self):
        await self.run_graph([*frames()[:2], openai_frame("迟到", status=None), usage_frame(5)], completed=False)

    async def test_concurrent_and_repeated_real_factories_usage_is_local(self):
        async def one(index):
            raw = model_raw(source="openai"); raw["runId"] = "independent-fixture-" + str(index)
            result, _ = await self.run_graph(frames(index + 3), raw=raw)
            return result["output"]["usage"]["total_tokens"]
        self.assertEqual(await asyncio.gather(one(1), one(8), one(15)), [4, 11, 18])
        self.assertEqual(await one(2), 5)

    async def test_real_iteration_node_usage_does_not_cross_batches(self):
        raw = raw_workflow([
            node("node-start::s", outputs=[output("items", "array", items={"type": "string"})]),
            node("iteration::i", inputs=[ref("items", "node-start::s", "items", "array")],
                 outputs=[output("value", "array", items={"type": "string"})], IterationStartNodeId="iteration-node-start::is"),
            node("iteration-node-start::is", outputs=[output("items")]),
            node("spark-llm::model", inputs=[ref("question", "iteration-node-start::is", "items")], outputs=[output("answer")],
                 appId="legacy-label", domain="model-fixture", source="openai", modelResourceId="model-1", template="{{question}}"),
            node("iteration-node-end::ie", inputs=[ref("value", "spark-llm::model", "answer")], outputs=[output("value")], outputMode=0),
            node("node-end::e", inputs=[ref("answer", "iteration::i", "value", "array")],
                 outputs=[output("answer", "array", items={"type": "string"})], outputMode=0)],
            [edge("node-start::s", "iteration::i"), edge("iteration::i", "node-end::e"),
             edge("iteration-node-start::is", "spark-llm::model"), edge("spark-llm::model", "iteration-node-end::ie")],
            {"items": ["甲", "乙", "丙"]})
        sample = model_raw(source="openai")
        raw["resourceSnapshots"], raw["effectPolicy"] = sample["resourceSnapshots"], sample["effectPolicy"]
        class IterationCenter(ModelCenter):
            def __init__(self, raw):
                super().__init__(raw, [])
                self.by_operation = {}
            async def operation(self, *args):
                params = args[5]; text = params["messages"][-1]["content"]
                tokens = {"甲": 5, "乙": 9, "丙": 13}[text]
                operation = "iteration-operation-" + str(len(self.by_operation))
                self.by_operation[operation] = [openai_frame(text, status=None), openai_frame(status="stop"), usage_frame(tokens)]
                self.operations.append(copy.deepcopy(args))
                return {**self.binding(args, operation), 'state': 'RUNNING'}
            async def operation_frames(self, *args):
                supplied = self.by_operation[args[-3]]; after = args[-2]; batch = copy.deepcopy(supplied[after:after+1])
                done = after + len(batch) == len(supplied)
                return {**copy.deepcopy(self.bindings[args[-3]]), "state": "COMPLETED" if done else "RUNNING",
                    'operationState': 'COMPLETED', 'failureKind': None,
                    "frames": batch, "nextCursor": after + len(batch), "done": done, "finishSeq": 2, "lastSeq": 3}
        # 原226三批用量算法证据继续离线执行；此混合图不具当前纯迭代准入。
        center = IterationCenter(raw)
        assigned = Assignment.parse(raw)
        context = RunContext(center, assigned, EventPublisher(center, assigned.run_id, assigned.lease_id, 1, assigned.last_event_seq))
        token = CURRENT_CONTEXT.set(context)
        try:
            result = await asyncio.wait_for(OriginalWorkflowEngine().execute(assigned, context), 8)
        finally:
            context.active = False
            CURRENT_CONTEXT.reset(token)
        self.assertEqual(result["outputs"]["answer"], ["甲", "乙", "丙"])
        actual = {e["iterationId"]: e["payload"]["usage"]["total_tokens"] for e in center.events
                  if e["nodeId"] == "spark-llm::model" and e["eventType"] == "usage"}
        self.assertEqual(actual, {"iteration::i:0": 5, "iteration::i:1": 9, "iteration::i:2": 13})
        self.assertEqual(center.begin_calls, [])
        self.assertEqual(center.results, [])
        refused = IterationCenter(raw)
        with self.assertRaises(WorkerError) as error:
            await create_task_runner(refused).run(raw)
        self.assertEqual(error.exception.code, "RESOURCE_UNAVAILABLE")
        self.assertEqual(refused.begin_calls, [])
        self.assertEqual(refused.results, [])
        self.assertEqual(refused.operations, [])
