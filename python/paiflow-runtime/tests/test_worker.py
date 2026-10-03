"""离线契约/边界测试。没有启动数据库、Provider 或真实业务服务。"""
from __future__ import annotations

import asyncio
import ast
import base64
import copy
import importlib
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from worker.boundaries import LegacyControlPlaneDenied
from worker.catalog import NODE_CLASSES
from worker.center_client import CenterClient
from worker.errors import CenterUnavailable, LeaseLost, WorkerError
from worker.events import EventPublisher
from worker.task_runner import CURRENT_CONTEXT, Assignment, TaskRunner, dsl_hash


def assignment():
    dsl = {"nodes": [{"id": "node-start::1", "data": {}}, {"id": "node-end::2", "data": {}}],
           "edges": [{"sourceNodeId": "node-start::1", "targetNodeId": "node-end::2"}]}
    canonical = json.dumps(dsl, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {"schemaVersion": "paiflow.worker.v1", "runId": "run-1", "workflowVersionId": "version-1",
            "dslHash": dsl_hash(canonical), "dslCanonicalBase64": base64.b64encode(canonical).decode("ascii"),
            "engineProfile": "python", "input": {"问题": "你好"},
            "resourceSnapshots": {"knowledge-1": {"version": "v1"}}, "attempt": 1, "leaseId": "lease-1",
            "deadline": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat(),
            "effectPolicy": {"mode": "read_only", "allowedOperations": ["knowledge.retrieve", "sandbox.execute"]},
            "lastEventSeq": 3, "dsl": dsl}


class MemoryTransport:
    def __init__(self, status=200, response=None):
        self.status = status
        self.response = response if response is not None else {"code": 200, "data": {"valid": True}}
        self.calls = []

    async def request(self, method, url, headers, body, timeout, max_response_bytes):
        self.calls.append((method, url, headers, json.loads(body) if body else None))
        return self.status, json.dumps(self.response).encode()


def refresh_test_canonical(raw):
    canonical = json.dumps(raw["dsl"], ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    raw["dslHash"] = dsl_hash(canonical)
    raw["dslCanonicalBase64"] = base64.b64encode(canonical).decode("ascii")


class CenterProtocolTest(unittest.IsolatedAsyncioTestCase):
    async def test_authorized_lease_path_and_envelope(self):
        transport = MemoryTransport()
        client = CenterClient("https://center.example/ruoyi", "test-service-token", transport=transport)
        await client.verify_lease("run/1", "lease-1", 2)
        method, url, headers, body = transport.calls[0]
        self.assertEqual(method, "POST")
        self.assertEqual(url, "https://center.example/ruoyi/internal/paiflow/v1/runs/run%2F1/lease/verify")
        self.assertEqual(body, {"leaseId": "lease-1", "attempt": 2})
        self.assertEqual(headers["Authorization"], "Bearer test-service-token")

    async def test_center_rejection_never_becomes_success(self):
        for status in (401, 403, 409, 410):
            with self.subTest(status=status), self.assertRaises(LeaseLost):
                await CenterClient("https://center.example", "token", transport=MemoryTransport(status)).verify_lease("r", "l", 1)
        for transport in (MemoryTransport(500), MemoryTransport(302), MemoryTransport(response={"code": 0, "data": {"valid": True}}),
                          MemoryTransport(response={"code": 200}), MemoryTransport(response={"code": 200, "data": None})):
            with self.assertRaises(WorkerError):
                await CenterClient("https://center.example", "token", transport=transport).verify_lease("r", "l", 1)

    async def test_terminal_and_event_require_matching_acknowledgement(self):
        client = CenterClient("https://center.example", "token", transport=MemoryTransport(response={"code": 200, "data": {"accepted": True, "state": "FAILED", "seq": 2}}))
        with self.assertRaises(CenterUnavailable):
            await client.complete("r", {"state": "COMPLETED"})
        with self.assertRaises(CenterUnavailable):
            await client.append_event("r", {"seq": 3})

    async def test_resource_ids_cannot_replace_center_origin(self):
        transport = MemoryTransport(response={"code": 200, "data": {"id": "r"}})
        client = CenterClient("https://center.example", "token", transport=transport)
        await client.resource("run-1", "lease-1", 1, "https://other.example/secret")
        self.assertIn("/resources/https%3A%2F%2Fother.example%2Fsecret", transport.calls[0][1])
        self.assertTrue(transport.calls[0][1].startswith("https://center.example/"))

    async def test_tool_policy_rejection_has_no_side_effect_success(self):
        client = CenterClient("https://center.example", "token", transport=MemoryTransport(403))
        with self.assertRaises(WorkerError) as error:
            await client.operation("r", "l", 1, "plugin::1", "http.invoke", {}, "effect-1")
        self.assertEqual(error.exception.code, "TOOL_POLICY_DENIED")

    def test_unsafe_center_configuration_rejected(self):
        for url in ("file:///tmp/a", "https://user:password@center.example", "https://center.example?q=a", "https://center.example/#a", "https://center.example/../other"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                CenterClient(url, "token")


class RecordingCenter:
    def __init__(self, raw):
        self.raw = raw
        self.events = []
        self.results = []
        self.operations = []
        self.lease_lost = False
        self.cancel_requested = False
        self.reject_event_once = False
        self.reject_terminal = False
        self.resource_calls = []
        self.begin_calls = []

    async def verify_lease(self, run_id, lease_id, attempt):
        if self.lease_lost:
            raise LeaseLost()
        return {"valid": True, "dslHash": self.raw["dslHash"], "workflowVersionId": self.raw["workflowVersionId"],
                "lastEventSeq": self.raw["lastEventSeq"], "assignment": copy.deepcopy(self.raw), "cancelRequested": self.cancel_requested}

    async def heartbeat(self, *args):
        if self.lease_lost:
            raise LeaseLost()
        return {"valid": True, "cancelRequested": self.cancel_requested}

    async def begin_execution(self, run_id, lease_id, attempt):
        self.begin_calls.append((run_id, lease_id, attempt))
        return {"begun": True, "runId": run_id, "leaseId": lease_id, "attempt": attempt,
            "workflowVersionId": self.raw["workflowVersionId"], "dslHash": self.raw["dslHash"]}

    async def append_event(self, run_id, event):
        self.events.append(copy.deepcopy(event))
        if self.reject_event_once:
            self.reject_event_once = False
            raise CenterUnavailable()
        return {"accepted": True, "seq": event["seq"]}

    async def complete(self, run_id, result):
        if self.reject_terminal:
            raise CenterUnavailable()
        self.results.append(copy.deepcopy(result))
        return {"accepted": True, "state": result["state"]}

    async def resource(self, *args):
        self.resource_calls.append(args)
        return {"version": "v1", "content": "授权片段"}

    async def operation(self, *args):
        self.operations.append(args)
        return {"state": "completed", "output": "sandbox-response"}


class ExercisingEngine:
    supported_types = frozenset(NODE_CLASSES)

    def __init__(self, execute=None):
        self._execute = execute
        self.calls = 0

    async def execute(self, task, context):
        self.calls += 1
        if self._execute:
            return await self._execute(task, context)
        await context.emit("node.started", {}, node_id="node-start::1")
        await context.emit("text.delta", {"text": task.input["问题"]}, node_id="node-end::2")
        return {"answer": task.input["问题"]}


class TaskLifecycleTest(unittest.IsolatedAsyncioTestCase):
    def test_real_center_canonical_fixture_uses_original_bytes_for_special_values(self):
        workspace = Path(__file__).resolve().parents[3]
        fixture = json.loads((workspace / "contracts/paiflow/fixtures/dsl-canonical.json").read_text(encoding="utf-8"))
        raw = assignment()
        raw.update({field: fixture[field] for field in ("dsl", "dslHash", "dslCanonicalBase64")})
        parsed = Assignment.parse(raw)
        self.assertEqual(parsed.hash, fixture["dslHash"])
        self.assertEqual(parsed.dsl, fixture["dsl"])
        recoded = json.dumps(raw["dsl"], ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        self.assertNotEqual(dsl_hash(recoded), fixture["dslHash"])

    def test_missing_invalid_or_divergent_canonical_bytes_cannot_fall_back(self):
        raw = assignment()
        raw.pop("dslCanonicalBase64")
        with self.assertRaises(WorkerError):
            Assignment.parse(raw)
        for encoded in ("not-base64!", base64.b64encode(b'{"nodes":[],"edges":[]}').decode(), base64.b64encode(b'{"nodes":[],"nodes":[],"edges":[]}').decode()):
            raw = assignment()
            raw["dslCanonicalBase64"] = encoded
            with self.assertRaises(WorkerError):
                Assignment.parse(raw)

    async def test_center_ack_is_required_for_success_and_sequences_continue(self):
        raw = assignment()
        center = RecordingCenter(raw)
        result = await TaskRunner(center, ExercisingEngine()).run(raw)
        self.assertEqual(result, {"accepted": True, "state": "COMPLETED"})
        self.assertEqual([event["seq"] for event in center.events], [4, 5])
        self.assertEqual(center.results[0]["output"], {"answer": "你好"})
        self.assertIsNone(CURRENT_CONTEXT.get())

    async def test_unknown_versions_hash_nodes_and_approvals_reject_before_engine(self):
        mutations = [lambda a: a.update(schemaVersion="future"), lambda a: a.update(dslHash="a" * 64),
                     lambda a: a.update(extra="unauthorized"), lambda a: a.update(engineProfile="java"),
                     lambda a: a.update(attempt=True), lambda a: a.update(effectPolicy={"mode": "approved_write", "allowedOperations": ["data.write"]})]
        for mutate in mutations:
            raw = assignment()
            mutate(raw)
            engine = ExercisingEngine()
            with self.assertRaises(WorkerError):
                await TaskRunner(RecordingCenter(raw), engine).run(raw)
            self.assertEqual(engine.calls, 0)
        raw = assignment()
        raw["dsl"]["nodes"][1]["id"] = "unknown::2"
        refresh_test_canonical(raw)
        with self.assertRaises(WorkerError):
            Assignment.parse(raw)

    async def test_no_engine_does_not_claim_preserved_nodes_ready(self):
        raw = assignment()
        center = RecordingCenter(raw)
        with self.assertRaisesRegex(WorkerError, "尚未注册"):
            await TaskRunner(center, None).run(raw)
        self.assertEqual(center.results, [])

    async def test_expired_lease_prevents_engine_and_terminal_write(self):
        raw = assignment()
        center = RecordingCenter(raw)
        center.lease_lost = True
        engine = ExercisingEngine()
        with self.assertRaises(LeaseLost):
            await TaskRunner(center, engine).run(raw)
        self.assertEqual(engine.calls, 0)
        self.assertEqual(center.results, [])

    async def test_heartbeat_lease_loss_stops_execution_without_fake_cancel(self):
        raw = assignment()
        center = RecordingCenter(raw)
        stopped = asyncio.Event()
        async def execute(task, context):
            center.lease_lost = True
            try:
                await asyncio.sleep(60)
            finally:
                stopped.set()
        with self.assertRaises(LeaseLost):
            await TaskRunner(center, ExercisingEngine(execute), heartbeat_seconds=.01).run(raw)
        self.assertTrue(stopped.is_set())
        self.assertEqual(center.results, [])

    async def test_center_cancel_request_stops_engine_then_confirms_terminal(self):
        raw = assignment()
        center = RecordingCenter(raw)
        stopped = asyncio.Event()
        async def execute(task, context):
            center.cancel_requested = True
            try:
                await asyncio.sleep(60)
            finally:
                stopped.set()
        result = await TaskRunner(center, ExercisingEngine(execute), heartbeat_seconds=.01).run(raw)
        self.assertTrue(stopped.is_set())
        self.assertEqual(result["state"], "CANCELLED")
        self.assertEqual(center.events, [])  # 终态事件由中心 CAS 生成。

    async def test_deadline_failure_has_confirmed_error_not_success(self):
        raw = assignment()
        raw["deadline"] = (datetime.now(timezone.utc) + timedelta(milliseconds=40)).isoformat()
        async def execute(task, context):
            await asyncio.sleep(60)
        center = RecordingCenter(raw)
        result = await TaskRunner(center, ExercisingEngine(execute)).run(raw)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(center.results[-1]["error"]["code"], "DEPENDENCY_TIMEOUT")

    async def test_provider_failure_does_not_expose_sensitive_exception(self):
        raw = assignment()
        center = RecordingCenter(raw)
        async def execute(task, context):
            raise ValueError("sensitive-provider-response-marker")
        result = await TaskRunner(center, ExercisingEngine(execute)).run(raw)
        self.assertEqual(result["state"], "FAILED")
        self.assertNotIn("sensitive-provider-response-marker", json.dumps(center.results))

    async def test_terminal_storage_failure_never_returns_success(self):
        raw = assignment()
        center = RecordingCenter(raw)
        center.reject_terminal = True
        with self.assertRaises(CenterUnavailable):
            await TaskRunner(center, ExercisingEngine()).run(raw)
        self.assertEqual(center.results, [])

    async def test_unknown_effect_stays_unknown_for_center_reconciliation(self):
        raw = assignment()
        center = RecordingCenter(raw)
        async def execute(task, context):
            raise WorkerError("EFFECT_UNKNOWN", "untrusted-provider-detail")
        result = await TaskRunner(center, ExercisingEngine(execute)).run(raw)
        self.assertEqual(result["state"], "EFFECT_UNKNOWN")
        self.assertEqual(center.events, [])
        self.assertNotIn("untrusted-provider-detail", json.dumps(center.results))

    async def test_out_of_scope_resources_and_readonly_writes_denied(self):
        raw = assignment()
        raw["effectPolicy"]["allowedOperations"].append("data.write")
        center = RecordingCenter(raw)
        async def execute(task, context):
            with self.assertRaises(WorkerError):
                await context.resource("another-space-resource")
            with self.assertRaises(WorkerError):
                await context.operation("node-end::2", "data.write", {"value": 1}, key="effect-1")
            value = await context.resource("knowledge-1")
            return {"content": value["content"]}
        await TaskRunner(center, ExercisingEngine(execute)).run(raw)
        self.assertEqual(len(center.resource_calls), 1)
        self.assertEqual(center.operations, [])

    async def test_duplicate_live_lease_is_not_executed_twice(self):
        raw = assignment()
        entered = asyncio.Event()
        release = asyncio.Event()
        async def execute(task, context):
            entered.set()
            await release.wait()
            return {"done": True}
        runner = TaskRunner(RecordingCenter(raw), ExercisingEngine(execute))
        first = asyncio.create_task(runner.run(raw))
        await entered.wait()
        with self.assertRaises(WorkerError):
            await runner.run(raw)
        self.assertEqual(len(runner.center.begin_calls), 1)
        release.set()
        await first


class EventsAndBoundariesTest(unittest.IsolatedAsyncioTestCase):
    async def test_parallel_events_get_continuous_sequences(self):
        center = RecordingCenter(assignment())
        publisher = EventPublisher(center, "r", "l", 1, 20)
        await asyncio.gather(*(publisher.emit("text.delta", {"part": value}) for value in range(40)))
        self.assertEqual([x["seq"] for x in center.events], list(range(21, 61)))

    async def test_worker_cannot_emit_center_terminal_events(self):
        center = RecordingCenter(assignment())
        publisher = EventPublisher(center, "r", "l", 1, 0)
        with self.assertRaises(WorkerError):
            await publisher.emit("run.completed", {"output": "cannot-self-certify"})
        self.assertEqual(center.events, [])

    async def test_nested_credentials_never_enter_event_payload(self):
        center = RecordingCenter(assignment())
        publisher = EventPublisher(center, "r", "l", 1, 0)
        with self.assertRaises(WorkerError):
            await publisher.emit("node.completed", {"output": [{"api_key": "example-secret-marker"}]})
        self.assertEqual(center.events, [])
        await publisher.emit("node.completed", {"secretRef": "managed-ref"})
        self.assertEqual(center.events[0]["seq"], 1)

    async def test_produced_fixtures_match_current_shared_machine_contracts(self):
        try:
            from jsonschema import Draft202012Validator, FormatChecker
        except ImportError:
            self.skipTest("未安装 jsonschema，实际联合 schema 验证不能判通过")
        workspace = Path(__file__).resolve().parents[3]
        worker_schema = json.loads((workspace / "contracts/paiflow/worker.schema.json").read_text(encoding="utf-8"))
        event_schema = json.loads((workspace / "contracts/paiflow/events.schema.json").read_text(encoding="utf-8"))
        raw = assignment()
        Draft202012Validator(worker_schema, format_checker=FormatChecker()).validate(raw)
        center = RecordingCenter(raw)
        await TaskRunner(center, ExercisingEngine()).run(raw)
        for event in center.events:
            Draft202012Validator(event_schema, format_checker=FormatChecker()).validate(event)
        Draft202012Validator(worker_schema["$defs"]["Result"]).validate(center.results[0])
        self.assertEqual(set(worker_schema["required"]), set(raw))

    async def test_unconfirmed_event_is_reconciled_with_same_payload_and_seq(self):
        center = RecordingCenter(assignment())
        center.reject_event_once = True
        publisher = EventPublisher(center, "r", "l", 1, 7)
        with self.assertRaises(CenterUnavailable):
            await publisher.emit("text.delta", {"text": "original"})
        with self.assertRaises(WorkerError):
            await publisher.emit("text.delta", {"text": "later"})
        await publisher.reconcile()
        self.assertEqual(center.events[0], center.events[1])
        self.assertEqual(publisher.last_seq, 8)

    def test_old_databases_and_repository_imports_reject_before_loading_drivers(self):
        for module in ("workflow.repository.flow_dao", "workflow.extensions.middleware.database.manager", "agent.repository.mysql_client",
                       "plugin.link.domain.models.manager", "plugin.link.infra.tool_crud.process", "common.service.db.db_service"):
            with self.subTest(module=module), self.assertRaises(LegacyControlPlaneDenied):
                importlib.import_module(module)

    async def test_original_local_executor_no_longer_executes_on_user_host(self):
        from workflow.engine.nodes.code.executor.local.local_executor import LocalExecutor
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "must-not-exist.txt"
            code = f"__import__('pathlib').Path({str(target)!r}).write_text('host-side-effect')"
            raw = assignment()
            raw["dsl"]["nodes"][1]["id"] = "ifly-code::2"
            raw["dsl"]["edges"][0]["targetNodeId"] = "ifly-code::2"
            refresh_test_canonical(raw)
            center = RecordingCenter(raw)
            async def execute(task, context):
                result = await LocalExecutor().execute("python", code, 10, node_id="ifly-code::2")
                return {"sandboxOutput": result}
            await TaskRunner(center, ExercisingEngine(execute)).run(raw)
            self.assertFalse(target.exists())
            self.assertEqual(center.operations[0][4], "sandbox.execute")
            self.assertEqual(center.operations[0][5]["code"], code)

    def test_all_original_21_node_classes_are_present(self):
        from workflow.engine.nodes.base_node import BaseNode
        from workflow.engine.nodes.cache_node import tool_classes
        self.assertEqual(len(NODE_CLASSES), 21)
        self.assertEqual(set(NODE_CLASSES), {"ifly-code", "node-start", "node-end", "plugin", "knowledge-base", "knowledge-pro-base",
            "extractor-parameter", "spark-llm", "decision-making", "if-else", "message", "iteration", "iteration-node-start",
            "iteration-node-end", "text-joiner", "node-variable", "flow", "agent", "question-answer", "database", "rpa"})
        root = Path(__file__).resolve().parents[1]
        registry = ast.parse((root / "workflow/engine/nodes/cache_node.py").read_text(encoding="utf-8"))
        imports = {alias.name: node.module for node in registry.body if isinstance(node, ast.ImportFrom) for alias in node.names}
        imports.update({node.targets[0].id: node.value.args[0].value for node in registry.body
            if isinstance(node, ast.Assign) and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name)
            and node.value.func.id == "LazyNodeClass"})
        for classname in NODE_CLASSES.values():
            module = imports[classname]
            source = root / (module.replace(".", "/") + ".py")
            self.assertTrue(source.is_file(), classname)
        for identifier, lazy_class in tool_classes.items():
            with self.subTest(identifier=identifier):
                actual = lazy_class.load()
                self.assertTrue(issubclass(actual, BaseNode))
                self.assertEqual(actual.__name__, NODE_CLASSES[identifier])
            classes = {node.name for node in ast.parse(source.read_text(encoding="utf-8-sig")).body if isinstance(node, ast.ClassDef)}
            self.assertIn(classname, classes)


if __name__ == "__main__":
    unittest.main()
