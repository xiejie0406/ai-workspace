"""固定纯迭代图的实际原Factory与 pre-begin 拒绝；没有外部业务调用。"""
import asyncio
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from worker.engine_adapter import create_task_runner
from worker.errors import WorkerError
from worker.pure_iteration import validate, _boolean
from tests.test_original_engine import node, output, ref, edge, raw_workflow
from tests.test_worker import RecordingCenter, refresh_test_canonical


FIXTURE = json.loads((Path(__file__).resolve().parents[3] / "contracts/paiflow/fixtures/pure-iteration.json").read_text(encoding="utf8"))


def baseline():
    return copy.deepcopy(FIXTURE["baseline"]["dsl"]), copy.deepcopy(FIXTURE["baseline"]["input"])


def assigned(dsl=None, supplied=None):
    if dsl is None:
        dsl, supplied = baseline()
    raw = raw_workflow(copy.deepcopy(dsl["nodes"]), copy.deepcopy(dsl["edges"]), copy.deepcopy(supplied))
    raw["resourceSnapshots"] = {}
    raw["effectPolicy"] = {"mode": "read_only", "allowedOperations": []}
    return raw


def connect_chain(dsl, source, target, count, prefix):
    dsl["edges"] = [item for item in dsl["edges"]
                    if (item["sourceNodeId"], item["targetNodeId"]) != (source, target)]
    identifiers = ["message::" + prefix + str(i) for i in range(count)]
    dsl["nodes"].extend(node(identifier, template="", streamOutput=False) for identifier in identifiers)
    chain = [source, *identifiers, target]
    dsl["edges"].extend(edge(a, b) for a, b in zip(chain, chain[1:]))
    return identifiers


def body_paths(dsl, count):
    # p -> 2p 或 2p+1 的小DAG；不枚举路径就可精确给出预算边界。
    start = current = "iteration-node-start::is"
    dsl["edges"] = [item for item in dsl["edges"] if item["sourceNodeId"] != start]
    for index, bit in enumerate(bin(count)[3:]):
        left, right, merge = ("message::path" + str(index) + suffix for suffix in ("L", "R", "M"))
        dsl["nodes"].extend(node(identifier, template="", streamOutput=False) for identifier in (left, right, merge))
        dsl["edges"].extend([edge(current, left), edge(current, right), edge(left, merge), edge(right, merge)])
        if bit == "1":
            dsl["edges"].append(edge(start, merge))
        current = merge
    dsl["edges"].append(edge(current, "text-joiner::j"))


class PureIterationStructureTest(unittest.TestCase):
    def reject(self, dsl, supplied=None, code=None):
        with self.assertRaises(WorkerError) as error:
            validate(dsl, {"items": ["A"]} if supplied is None else supplied)
        if code is not None:
            self.assertEqual(error.exception.code, code)

    def test_shared_fifteen_original_admission_cases_and_stats(self):
        dsl, supplied = baseline()
        metrics = validate(dsl, supplied)
        self.assertEqual((metrics.graph_nodes, metrics.expanded_nodes, metrics.path_count, metrics.max_path_length), (6, 12, 2, 3))
        self.assertEqual(dict(metrics.batch_sizes), {"iteration::i": 3})
        for case in FIXTURE["cases"]:
            with self.subTest(case=case["name"]):
                if case["expectedAdmission"]:
                    self.assertIsNotNone(validate(case["dsl"], case["input"]))
                else:
                    self.reject(case["dsl"], case["input"], "DSL_INVALID")

    def test_actual_batch64_and65_and_default_is_budgeted(self):
        dsl, _ = baseline()
        self.assertEqual(dict(validate(dsl, {"items": [""] * 64}).batch_sizes)["iteration::i"], 64)
        self.reject(dsl, {"items": [""] * 65}, "QUOTA_EXCEEDED")
        declaration = dsl["nodes"][0]["data"]["outputs"][0]
        declaration["required"] = "OFF"
        declaration["schema"]["default"] = [""] * 65
        self.reject(dsl, {}, "QUOTA_EXCEEDED")

    def test_graph_nodes128_and129_do_not_replace_path_budget(self):
        for count, accepted in ((122, True), (123, False)):
            dsl, supplied = baseline()
            dsl["edges"].remove(edge("node-start::s", "iteration::i"))
            for index in range(count):
                identifier = "message::wide" + str(index)
                dsl["nodes"].append(node(identifier, template="", streamOutput=False))
                dsl["edges"].extend([edge("node-start::s", identifier), edge(identifier, "iteration::i")])
            if accepted:
                self.assertEqual(validate(dsl, supplied).graph_nodes, 128)
            else:
                self.reject(dsl, supplied, "QUOTA_EXCEEDED")

    def test_total_paths128_and129_after_endpoint_dedup(self):
        dsl, supplied = baseline()
        body_paths(dsl, 127)
        dsl["edges"].extend(copy.deepcopy(dsl["edges"][:4]))
        self.assertEqual(validate(dsl, supplied).path_count, 128)
        dsl, supplied = baseline()
        body_paths(dsl, 128)
        self.reject(dsl, supplied, "QUOTA_EXCEEDED")

    def test_exponential_dag_is_rejected_without_chains_path_enumeration(self):
        dsl, supplied = baseline()
        body_paths(dsl, 2 ** 30)
        self.assertLessEqual(len(dsl["nodes"]), 128)
        with patch("workflow.engine.entities.chains.Chains.gen", side_effect=AssertionError("must not enumerate")):
            self.reject(dsl, supplied, "QUOTA_EXCEEDED")

    def test_max_path_node_length64_and65(self):
        for length in (64, 65):
            dsl, supplied = baseline()
            connect_chain(dsl, "iteration-node-start::is", "text-joiner::j", length - 3, "long")
            if length == 64:
                self.assertEqual(validate(dsl, supplied).max_path_length, 64)
            else:
                self.reject(dsl, supplied, "QUOTA_EXCEEDED")

    def test_expanded4096_and4097_within_other_budgets(self):
        dsl, _ = baseline()
        main = connect_chain(dsl, "node-start::s", "iteration::i", 61, "main")
        connect_chain(dsl, "iteration-node-start::is", "text-joiner::j", 60, "body")
        self.assertEqual(validate(dsl, {"items": [""] * 64}).expanded_nodes, 4096)
        dsl["nodes"].append(node("message::parallel", template="", streamOutput=False))
        dsl["edges"].extend([edge("node-start::s", "message::parallel"), edge("message::parallel", main[1])])
        self.assertEqual(len(dsl["nodes"]), 128)
        self.reject(dsl, {"items": [""] * 64}, "QUOTA_EXCEEDED")

    def test_cycle_or_orphan_or_shared_start_or_cross_scope_edge_reject(self):
        for change in ("cycle", "orphan", "shared", "cross"):
            dsl, supplied = baseline()
            if change == "cycle":
                dsl["edges"].append(edge("text-joiner::j", "iteration-node-start::is"))
            elif change == "orphan":
                dsl["nodes"].append(node("message::orphan", template="", streamOutput=False))
            elif change == "shared":
                dsl["nodes"].append(node("iteration::other", inputs=copy.deepcopy(dsl["nodes"][1]["data"]["inputs"]),
                                         IterationStartNodeId="iteration-node-start::is"))
                dsl["edges"] = [e for e in dsl["edges"] if e != edge("iteration::i", "node-end::e")]
                dsl["edges"].extend([edge("iteration::i", "iteration::other"), edge("iteration::other", "node-end::e")])
            else:
                dsl["edges"].append(edge("text-joiner::j", "node-end::e"))
            with self.subTest(change=change):
                self.reject(dsl, supplied)

    def test_inner_external_and_downstream_and_undeclared_references_reject(self):
        for source, name, expected in (("node-start::s", "items", "RESOURCE_UNAVAILABLE"),
                                       ("iteration-node-end::ie", "value", "DSL_INVALID"),
                                       ("iteration-node-start::is", "missing", "DSL_INVALID")):
            dsl, supplied = baseline()
            dsl["nodes"][3]["data"]["inputs"] = [ref("item", source, name)]
            self.reject(dsl, supplied, expected)

    def test_array_binding_requires_direct_main_start_declared_array_and_item_name(self):
        for change in ("path", "type", "literal", "item", "second"):
            dsl, supplied = baseline()
            schema = dsl["nodes"][1]["data"]["inputs"][0]["schema"]
            if change == "path":
                schema["value"]["content"]["name"] = "items.child"
            elif change == "type":
                dsl["nodes"][0]["data"]["outputs"][0]["schema"]["type"] = "string"
            elif change == "literal":
                schema["value"] = {"type": "literal", "content": "[]"}
            elif change == "item":
                dsl["nodes"][2]["data"]["outputs"][0]["name"] = "different"
            else:
                dsl["nodes"][1]["data"]["inputs"].append(ref("second", "node-start::s", "items", "array"))
            with self.subTest(change=change):
                self.reject(dsl, supplied)

    def test_explicit_and_implicit_output_address_collisions_reject(self):
        for implicit in (False, True):
            dsl, supplied = baseline()
            first = "text-joiner::a"
            second = "text-joiner::a-x"
            names = ["x-errorMessage", "errorMessage"] if implicit else ["x-y", "y"]
            dsl["nodes"].extend([node(first, outputs=[output(names[0])], mode=0, prompt=""),
                                  node(second, outputs=[] if implicit else [output(names[1])], mode=0, prompt="")])
            dsl["edges"].remove(edge("node-start::s", "iteration::i"))
            dsl["edges"].extend([edge("node-start::s", first), edge(first, second), edge(second, "iteration::i")])
            self.reject(dsl, supplied, "DSL_INVALID")

    def test_input_address_collision_reject_and_same_node_input_output_legal(self):
        dsl, supplied = baseline()
        dsl["nodes"][3]["data"]["outputs"].append(output("errorCode", "integer"))
        self.assertIsNotNone(validate(dsl, supplied))
        dsl["nodes"].extend([node("text-joiner::a", inputs=[ref("x-y", "node-start::s", "items", "array")]),
                              node("text-joiner::a-x", inputs=[ref("y", "node-start::s", "items", "array")])])
        self.reject(dsl, supplied, "DSL_INVALID")

    def test_required_and_retry_effective_bools_match_original_model(self):
        from workflow.engine.entities.retry_config import RetryConfig
        from workflow.engine.entities.workflow_dsl import OutputItem
        for value in (False, True, 0, 1, 0.0, 1.0, "OFF", "False", "N", "0", "ON", "true", "Y", "1"):
            self.assertEqual(_boolean(value), RetryConfig(shouldRetry=value).should_retry)
            self.assertEqual(_boolean(value), OutputItem(name="v", schema={}, required=value).required)
        for value in (None, 2, " false ", "", "maybe"):
            with self.assertRaises(WorkerError):
                _boolean(value)
        dsl, supplied = baseline()
        dsl["nodes"][3]["data"]["retryConfig"] = {"shouldRetry": "false", "maxRetries": 100}
        self.assertIsNotNone(validate(dsl, supplied))
        dsl["nodes"][3]["data"]["retryConfig"]["shouldRetry"] = "ON"
        self.reject(dsl, supplied, "RESOURCE_UNAVAILABLE")

    def test_remote_schema_and_nested_resource_and_unsupported_profiles_reject(self):
        for change in ("schema", "nested", "model", "qa", "global", "flow"):
            dsl, supplied = baseline()
            if change == "schema":
                dsl["nodes"][0]["data"]["outputs"][0]["schema"]["items"]["$ref"] = "https://must-not-resolve.invalid/schema"
            elif change == "nested":
                dsl["nodes"][3]["data"]["nodeParam"]["nested"] = {"id": "spark-llm::hidden"}
            else:
                kind = {"model": "spark-llm", "qa": "question-answer", "global": "node-variable", "flow": "flow"}[change]
                dsl["nodes"][3]["id"] = kind + "::blocked"
            with self.subTest(change=change):
                self.reject(dsl, supplied, "RESOURCE_UNAVAILABLE")

    def test_no_iteration_preserves_existing_nonpure_admission(self):
        self.assertIsNone(validate({"nodes": [node("spark-llm::existing")], "edges": []}, {}))


class PureIterationRuntimeTest(unittest.IsolatedAsyncioTestCase):
    async def assert_pre_begin_refused(self, raw):
        from workflow.engine.dsl_engine import WorkflowEngineFactory
        center = RecordingCenter(raw)
        with patch.object(WorkflowEngineFactory, "create_engine", side_effect=AssertionError("Factory must not run")) as factory:
            with self.assertRaises(WorkerError):
                await create_task_runner(center).run(raw)
        self.assertEqual(factory.call_count, 0)
        self.assertEqual(center.begin_calls, [])
        self.assertEqual(center.results, [])
        self.assertEqual(center.events, [])
        self.assertEqual(center.operations, [])
        self.assertEqual(center.resource_calls, [])

    async def test_bad_iteration_has_no_begin_result_factory_or_effect(self):
        raw = assigned(); raw["input"]["items"] = "abc"
        await self.assert_pre_begin_refused(raw)
        raw = assigned(); raw["dsl"]["edges"].append(edge("text-joiner::j", "node-end::e")); refresh_test_canonical(raw)
        await self.assert_pre_begin_refused(raw)

    async def test_pure_iteration_resources_and_effect_policy_cannot_self_authorize(self):
        for field, value in (("resourceSnapshots", {"unexpected": {"version": "v1"}}),
                             ("effectPolicy", {"mode": "read_only", "allowedOperations": ["sandbox.execute"]}),
                             ("effectPolicy", {"mode": "approved_write", "allowedOperations": [], "approvalRef": "fixture"})):
            raw = assigned(); raw[field] = value
            await self.assert_pre_begin_refused(raw)

    async def test_shared_admitted_cases_run_actual_factory_without_remote_schema_or_socket(self):
        with patch("socket.socket.connect", side_effect=AssertionError("no external connection")):
            for case in FIXTURE["cases"]:
                if not case["expectedAdmission"]:
                    continue
                with self.subTest(case=case["name"]):
                    raw = assigned(case["dsl"], case["input"]); center = RecordingCenter(raw)
                    await asyncio.wait_for(create_task_runner(center, heartbeat_seconds=.03).run(raw), 8)
                    self.assertEqual(len(center.begin_calls), 1)
                    self.assertEqual(center.results[-1]["state"], case["originalFactoryState"])
                    self.assertEqual(center.results[-1].get("output", {}).get("outputs"), case["originalFactoryOutputs"])
                    self.assertEqual(center.operations, [])

    async def test_actual_inner_failure_never_container_or_end_success(self):
        raw = assigned()
        raw["dsl"]["nodes"][3]["data"]["nodeParam"] = {"mode": 1, "separator": ""}
        refresh_test_canonical(raw); center = RecordingCenter(raw)
        await asyncio.wait_for(create_task_runner(center).run(raw), 8)
        self.assertEqual(center.results[-1]["state"], "FAILED")
        self.assertTrue(any(e["nodeId"] == "text-joiner::j" and e["eventType"] == "node.failed" for e in center.events))
        self.assertFalse(any(e["nodeId"] == "iteration::i" and e["eventType"] == "node.completed" for e in center.events))
        # 原预处理可先发主End started；只有completed才是该节点的成功事实。
        self.assertFalse(any(e["nodeId"] == "node-end::e" and e["eventType"] == "node.completed" for e in center.events))

    async def test_cancel_in_real_inner_engine_stops_later_batches_and_child_tasks(self):
        class WaitingCenter(RecordingCenter):
            def __init__(self, raw):
                super().__init__(raw); self.waiting = asyncio.Event(); self.event_wait_cancelled = False
            async def append_event(self, run_id, event):
                result = await super().append_event(run_id, event)
                if event["nodeId"] == "text-joiner::j" and event["eventType"] == "node.started":
                    self.waiting.set()
                    try:
                        await asyncio.Event().wait()
                    except asyncio.CancelledError:
                        self.event_wait_cancelled = True
                        raise
                return result
        raw = assigned(); center = WaitingCenter(raw)
        task = asyncio.create_task(create_task_runner(center, heartbeat_seconds=.02).run(raw))
        await asyncio.wait_for(center.waiting.wait(), 4)
        center.cancel_requested = True
        await asyncio.wait_for(task, 5)
        self.assertEqual(center.results[-1]["state"], "CANCELLED")
        self.assertTrue(center.event_wait_cancelled)
        self.assertFalse(any(e.get("iterationId") in {"iteration::i:1", "iteration::i:2"} for e in center.events))
        self.assertFalse(any(not item.done() and item is not asyncio.current_task() for item in asyncio.all_tasks()))

    async def test_fixed_assignment_mismatch_is_checked_before_iteration_preflight(self):
        raw = assigned(); center = RecordingCenter(copy.deepcopy(raw))
        raw["input"]["items"] = "bad"
        with self.assertRaises(WorkerError) as error:
            await create_task_runner(center).run(raw)
        self.assertEqual(error.exception.code, "VERSION_CONFLICT")
        self.assertEqual(center.begin_calls, [])
        self.assertEqual(center.results, [])


if __name__ == "__main__":
    unittest.main()
