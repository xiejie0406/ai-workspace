"""中心一次性许可的真实Worker生命周期边界；不连接中心/数据库。"""
import asyncio
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from worker.center_client import CenterClient
from worker.engine_adapter import create_task_runner
from worker.errors import CenterUnavailable, LeaseLost, WorkerError
from worker.task_runner import CURRENT_CONTEXT, TaskRunner
from tests.test_original_engine import passthrough, raw_workflow, node, output, ref, edge
from tests.test_worker import assignment, RecordingCenter, MemoryTransport, ExercisingEngine


def variable_assignment():
    return raw_workflow([node("node-start::s", outputs=[output("value")]),
        node("node-variable::get", inputs=[ref("value", "node-start::s", "value")],
            outputs=[output("value")], method="get"),
        node("node-end::e", inputs=[ref("answer", "node-variable::get", "value")],
            outputs=[output("answer")], outputMode=0)],
        [edge("node-start::s", "node-variable::get"), edge("node-variable::get", "node-end::e")], {"value": "scope-fixture"})


def scope_binding(chat_id=""):
    return {"flowId": "bound-original-flow", "userId": "bound-platform-user", "appId": "bound-original-app", "chatId": chat_id}


class BeginCenter(RecordingCenter):
    def __init__(self, raw, *, error=None, mutate=None):
        super().__init__(raw)
        self.error, self.mutate = error, mutate
        self.permission_started = False

    async def begin_execution(self, *args):
        if self.permission_started:
            self.begin_calls.append(args)
            raise LeaseLost()
        ack = await super().begin_execution(*args)
        if self.error is not None:
            raise self.error
        self.permission_started = True
        if self.mutate is not None:
            self.mutate(ack)
        return ack


class ExecutionBeginTest(unittest.IsolatedAsyncioTestCase):
    async def test_variable_absent_or_null_binding_rejects_before_permit_and_engine(self):
        for explicit_null in (False, True):
            raw = variable_assignment()
            center = RecordingCenter(raw)
            if explicit_null:
                async def verify(*args):
                    ack = await RecordingCenter.verify_lease(center, *args)
                    ack["executionContext"] = None
                    return ack
                center.verify_lease = verify
            engine = ExercisingEngine()
            with self.subTest(explicit_null=explicit_null), self.assertRaises(WorkerError) as error:
                await TaskRunner(center, engine).run(raw)
            self.assertEqual(error.exception.code, "RESOURCE_UNAVAILABLE")
            self.assertEqual(engine.calls, 0)
            self.assertEqual((center.begin_calls, center.results, center.events, center.operations, center.resource_calls), ([], [], [], [], []))

    async def test_variable_malformed_or_empty_app_binding_has_zero_runtime_calls(self):
        bindings = [[], "untrusted", {}, {**scope_binding(), "extra": "untrusted"},
            {key: val for key, val in scope_binding().items() if key != "appId"},
            *[{**scope_binding(), key: value} for key in ("flowId", "userId", "appId") for value in (None, "", 1)],
            {**scope_binding(), "chatId": 1}, {**scope_binding(), "chatId": []}]
        from workflow.engine.dsl_engine import WorkflowEngineFactory
        for binding in bindings:
            raw = variable_assignment()
            center = RecordingCenter(raw)
            async def verify(*args):
                ack = await RecordingCenter.verify_lease(center, *args)
                ack["executionContext"] = binding
                return ack
            center.verify_lease = verify
            runner = create_task_runner(center)
            with self.subTest(binding=binding), patch.object(runner.engine, "execute", wraps=runner.engine.execute) as engine, \
                 patch.object(WorkflowEngineFactory, "create_engine", wraps=WorkflowEngineFactory.create_engine) as factory:
                with self.assertRaises(WorkerError) as error:
                    await runner.run(raw)
                self.assertEqual(error.exception.code, "RESOURCE_UNAVAILABLE")
                engine.assert_not_called()
                factory.assert_not_called()
            self.assertEqual((center.begin_calls, center.results, center.events, center.operations, center.resource_calls), ([], [], [], [], []))

    async def test_variable_trusted_shape_preserves_null_empty_chat_in_isolated_engine(self):
        seen = []
        for chat_id in (None, ""):
            raw = variable_assignment()
            center = RecordingCenter(raw)
            binding = scope_binding(chat_id)
            async def verify(*args):
                ack = await RecordingCenter.verify_lease(center, *args)
                ack["executionContext"] = binding
                return ack
            center.verify_lease = verify
            async def execute(task, context):
                self.assertEqual(len(center.begin_calls), 1)
                self.assertEqual(context.execution_context, binding)
                self.assertIsNot(context.execution_context, binding)
                seen.append(copy.deepcopy(context.execution_context))
                return {"scopeReachedIsolatedPort": True}
            result = await TaskRunner(center, ExercisingEngine(execute)).run(raw)
            self.assertEqual(result["state"], "COMPLETED")
            self.assertEqual(center.operations, [])
        self.assertIsNone(seen[0]["chatId"])
        self.assertEqual(seen[1]["chatId"], "")
        self.assertNotEqual(seen[0], seen[1])

    async def test_variable_lease_hash_sequence_assignment_validation_precedes_scope_gate(self):
        for failure in ("lease", "hash", "version", "sequence", "assignment", "invalid_assignment"):
            raw = variable_assignment()
            center = RecordingCenter(raw)
            engine = ExercisingEngine()
            if failure == "lease":
                center.lease_lost = True
            elif failure == "invalid_assignment":
                raw["dslHash"] = "0" * 64
            else:
                async def verify(*args):
                    ack = await RecordingCenter.verify_lease(center, *args)
                    if failure == "hash":
                        ack["dslHash"] = "0" * 64
                    elif failure == "version":
                        ack["workflowVersionId"] = "other-version"
                    elif failure == "sequence":
                        ack["lastEventSeq"] += 1
                    else:
                        ack["assignment"]["input"] = {"changed": "not-fixed"}
                    return ack
                center.verify_lease = verify
            expected = "LEASE_LOST" if failure in {"lease", "hash", "version"} else "VERSION_CONFLICT"
            with self.subTest(failure=failure), self.assertRaises(WorkerError) as error:
                await TaskRunner(center, engine).run(raw)
            self.assertEqual(error.exception.code, expected)
            self.assertEqual(engine.calls, 0)
            self.assertEqual((center.begin_calls, center.results, center.events, center.operations, center.resource_calls), ([], [], [], [], []))

    async def test_variable_missing_scope_repeat_cannot_close_permitted_holder(self):
        raw = variable_assignment()
        center = BeginCenter(raw)
        verifies = 0
        async def verify(*args):
            nonlocal verifies
            verifies += 1
            ack = await RecordingCenter.verify_lease(center, *args)
            if verifies != 2:
                ack["executionContext"] = scope_binding()
            return ack
        center.verify_lease = verify
        entered, release = asyncio.Event(), asyncio.Event()
        async def execute(task, context):
            entered.set()
            await release.wait()
            return {"actualHolder": "continues"}
        first = asyncio.create_task(TaskRunner(center, ExercisingEngine(execute)).run(raw))
        await asyncio.wait_for(entered.wait(), 5)
        rejected_engine = ExercisingEngine()
        try:
            with self.assertRaises(WorkerError) as error:
                await TaskRunner(center, rejected_engine).run(raw)
            self.assertEqual(error.exception.code, "RESOURCE_UNAVAILABLE")
            self.assertEqual(rejected_engine.calls, 0)
            self.assertEqual(len(center.begin_calls), 1)
            self.assertFalse(first.done())
            self.assertEqual(center.results, [])
        finally:
            release.set()
        self.assertEqual((await asyncio.wait_for(first, 5))["state"], "COMPLETED")
        self.assertEqual(len(center.results), 1)

    async def test_http_begin_exact_path_body_and_ack(self):
        raw = assignment()
        ack = await RecordingCenter(raw).begin_execution("run/1", "lease-1", 1)
        transport = MemoryTransport(response={"code": 200, "data": ack})
        client = CenterClient("https://center.example", "fixture-identity", transport=transport)
        self.assertEqual(await client.begin_execution("run/1", "lease-1", 1), ack)
        call = transport.calls[0]
        self.assertEqual(call[0], "POST")
        self.assertTrue(call[1].endswith("/runs/run%2F1/execution/begin"))
        self.assertEqual(call[3], {"leaseId": "lease-1", "attempt": 1})
        self.assertEqual(len(transport.calls), 1)
        from jsonschema import Draft202012Validator
        document = json.loads((Path(__file__).resolve().parents[3] / "contracts/paiflow/worker.schema.json").read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(document)
        for definition, value in (("LeaseRequest", call[3]), ("ExecutionBeginAck", ack)):
            Draft202012Validator({"$ref": "#/$defs/" + definition, "$defs": document["$defs"],
                "properties": document["properties"]}).validate(value)

    async def test_begin_ack_every_binding_and_json_type_required_before_engine(self):
        for mutate in (lambda ack: ack.pop("begun"), lambda ack: ack.update(begun=False),
                lambda ack: ack.update(runId="other"), lambda ack: ack.update(leaseId="other"),
                lambda ack: ack.update(attempt=True), lambda ack: ack.update(attempt=2),
                lambda ack: ack.update(workflowVersionId="other"), lambda ack: ack.update(dslHash="b" * 64),
                lambda ack: ack.update(extra="unknown")):
            raw = assignment()
            center = BeginCenter(raw, mutate=mutate)
            engine = ExercisingEngine()
            with self.assertRaises(WorkerError) as error:
                await TaskRunner(center, engine).run(raw)
            self.assertEqual(error.exception.code, "RESOURCE_UNAVAILABLE")
            self.assertEqual(engine.calls, 0)
            self.assertEqual(len(center.begin_calls), 1)
            self.assertEqual(center.events, [])
            self.assertEqual(center.results, [])
            self.assertIsNone(CURRENT_CONTEXT.get())

    async def test_http_duplicate_denied_and_unknown_never_retry_or_write_terminal(self):
        for status in (409, 401, 403, 410, 500, 503):
            raw = assignment()
            transport = MemoryTransport(status=status)
            client = CenterClient("https://center.example", "fixture-identity", transport=transport)
            center = RecordingCenter(raw)
            center.begin_execution = client.begin_execution
            engine = ExercisingEngine()
            with self.assertRaises(WorkerError):
                await TaskRunner(center, engine).run(raw)
            self.assertEqual(engine.calls, 0)
            self.assertEqual(len(transport.calls), 1)
            self.assertEqual(center.events, [])
            self.assertEqual(center.results, [])

    async def test_timeout_or_unavailable_write_capability_reports_unknown_without_result(self):
        for error in (CenterUnavailable(), TimeoutError(), OSError("recorded transport detail")):
            raw = assignment()
            raw["effectPolicy"] = {"mode": "approved_write", "allowedOperations": ["model.invoke"], "approvalRef": "approved-fixture"}
            center = BeginCenter(raw, error=error)
            engine = ExercisingEngine()
            with self.assertRaises(WorkerError) as rejected:
                await TaskRunner(center, engine).run(raw)
            self.assertEqual(rejected.exception.code, "EFFECT_UNKNOWN")
            self.assertNotIn("recorded transport detail", str(rejected.exception))
            self.assertEqual(engine.calls, 0)
            self.assertEqual(len(center.begin_calls), 1)
            self.assertEqual(center.events, [])
            self.assertEqual(center.results, [])

    async def test_cross_runner_duplicate_cannot_stop_first_permitted_execution(self):
        raw = assignment()
        center = BeginCenter(raw)
        entered, release = asyncio.Event(), asyncio.Event()
        async def execute(task, context):
            self.assertEqual(len(center.begin_calls), 1)
            entered.set()
            await release.wait()
            return {"original": "continues"}
        first_engine, second_engine = ExercisingEngine(execute), ExercisingEngine()
        first = asyncio.create_task(TaskRunner(center, first_engine).run(raw))
        await asyncio.wait_for(entered.wait(), 5)
        with self.assertRaises(WorkerError):
            await TaskRunner(center, second_engine).run(raw)
        self.assertEqual(second_engine.calls, 0)
        self.assertFalse(first.done())
        self.assertEqual(center.results, [])
        release.set()
        self.assertEqual((await asyncio.wait_for(first, 5))["state"], "COMPLETED")
        self.assertEqual(len(center.results), 1)
        self.assertEqual(len(center.begin_calls), 2)

    async def test_refused_begin_never_enters_original_factory(self):
        from workflow.engine.dsl_engine import WorkflowEngineFactory
        raw = passthrough()
        center = BeginCenter(raw, error=LeaseLost())
        with patch.object(WorkflowEngineFactory, "create_engine", wraps=WorkflowEngineFactory.create_engine) as factory:
            with self.assertRaises(WorkerError):
                await create_task_runner(center).run(raw)
            factory.assert_not_called()
        self.assertEqual(center.results, [])
        self.assertEqual(center.events, [])

    async def test_assignment_local_capability_and_lease_preflight_before_begin(self):
        for failure in ("assignment", "engine", "lease", "snapshot"):
            raw = assignment()
            engine = ExercisingEngine()
            center = RecordingCenter(raw)
            if failure == "assignment":
                raw["schemaVersion"] = "future"
            elif failure == "engine":
                engine = None
            elif failure == "lease":
                center.lease_lost = True
            else:
                async def changed(*args):
                    ack = await RecordingCenter.verify_lease(center, *args)
                    ack["assignment"]["input"] = {"changed": True}
                    return ack
                center.verify_lease = changed
            with self.assertRaises(WorkerError):
                await TaskRunner(center, engine).run(raw)
            self.assertEqual(center.begin_calls, [])
            self.assertEqual(center.results, [])

    async def test_begin_inflight_cancellation_does_not_execute_or_write_result(self):
        raw = assignment()
        center = RecordingCenter(raw)
        entered = asyncio.Event()
        async def begin(*args):
            center.begin_calls.append(args)
            entered.set()
            await asyncio.Event().wait()
        center.begin_execution = begin
        engine = ExercisingEngine()
        task = asyncio.create_task(TaskRunner(center, engine).run(raw))
        await asyncio.wait_for(entered.wait(), 5)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(engine.calls, 0)
        self.assertEqual(center.events, [])
        self.assertEqual(center.results, [])
        self.assertEqual(len(center.begin_calls), 1)
        self.assertIsNone(CURRENT_CONTEXT.get())

    async def test_final_verify_cancel_takes_stop_path_preserving_existing_events(self):
        raw = assignment()
        center = RecordingCenter(raw)
        stopped = asyncio.Event()
        async def execute(task, context):
            await context.emit("node.completed", {"alreadyFinished": True}, node_id="node-end::2")
            center.cancel_requested = True
            stopped.set()
            return {"alreadyComputed": True}
        await TaskRunner(center, ExercisingEngine(execute), heartbeat_seconds=1).run(raw)
        self.assertTrue(stopped.is_set())
        self.assertEqual(center.results[-1]["state"], "CANCELLED")
        self.assertEqual(center.events[-1]["eventType"], "node.completed")
        self.assertEqual(center.events[-1]["payload"], {"alreadyFinished": True})
        self.assertFalse(any(result["state"] == "COMPLETED" for result in center.results))

    async def test_cancel_with_unconfirmed_effect_does_not_claim_cancelled(self):
        raw = assignment()
        center = RecordingCenter(raw)
        async def execute(task, context):
            context.boundary_failure = WorkerError("EFFECT_UNKNOWN", "recorded pending effect")
            center.cancel_requested = True
            return {"computed": True}
        await TaskRunner(center, ExercisingEngine(execute), heartbeat_seconds=1).run(raw)
        self.assertEqual(center.results[-1]["state"], "EFFECT_UNKNOWN")
        self.assertFalse(any(result["state"] in {"COMPLETED", "CANCELLED"} for result in center.results))

    async def test_result_cas_cancel_race_is_rechecked_then_stopped_without_success_retry(self):
        raw = assignment()
        center = RecordingCenter(raw)
        attempted = []
        async def result(run_id, body):
            attempted.append(copy.deepcopy(body))
            if body["state"] == "COMPLETED":
                center.cancel_requested = True
                raise LeaseLost()
            return await RecordingCenter.complete(center, run_id, body)
        center.complete = result
        await TaskRunner(center, ExercisingEngine(), heartbeat_seconds=1).run(raw)
        self.assertEqual([body["state"] for body in attempted], ["COMPLETED", "CANCELLED"])
        self.assertEqual([body["state"] for body in center.results], ["CANCELLED"])
        self.assertEqual(len(center.begin_calls), 1)

    async def test_non_cancel_result_conflict_does_not_invent_cancelled_terminal(self):
        raw = assignment()
        center = RecordingCenter(raw)
        attempted = []
        async def result(run_id, body):
            attempted.append(body)
            raise LeaseLost()
        center.complete = result
        with self.assertRaises(LeaseLost):
            await TaskRunner(center, ExercisingEngine(), heartbeat_seconds=1).run(raw)
        self.assertEqual([body["state"] for body in attempted], ["COMPLETED"])
        self.assertEqual(center.results, [])

    async def test_final_verify_missing_or_wrong_cancel_flag_cannot_commit_success(self):
        for marker in (None, 1, "false"):
            raw = assignment()
            center = RecordingCenter(raw)
            count = 0
            async def verify(*args):
                nonlocal count
                count += 1
                ack = await RecordingCenter.verify_lease(center, *args)
                if count > 1:
                    ack["cancelRequested"] = marker
                return ack
            center.verify_lease = verify
            with self.assertRaises(CenterUnavailable):
                await TaskRunner(center, ExercisingEngine(), heartbeat_seconds=1).run(raw)
            self.assertEqual(center.results, [])


if __name__ == "__main__":
    unittest.main()
