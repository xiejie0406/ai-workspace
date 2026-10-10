"""剩余五类真实原节点隔离回归；保存的原帧不是生产能力响应。"""
import asyncio
import base64
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from worker.center_client import CenterClient
from worker.engine_adapter import create_task_runner
from worker.errors import CenterUnavailable, WorkerError
from worker.node_context import CURRENT_ITERATION_ID
from tests.test_controlled_nodes import openai_frame
from tests.test_original_engine import node, output, ref, edge, raw_workflow
from tests.test_worker import RecordingCenter, MemoryTransport, refresh_test_canonical


def capability_raw(kind, **changes):
    params = {
        "plugin": {"toolResourceId": "capability", "pluginId": "legacy-tool", "operationId": "lookup", "appId": "legacy-label", "businessInput": ["business"]},
        "knowledge-pro-base": {"knowledgeResourceId": "capability", "modelResourceId": "model", "model": "legacy-model-label",
            "domain": "fixed-model", "temperature": .5, "maxTokens": 100, "topK": 2, "ragType": 1, "repoType": 2,
            "repoIds": ["repo"], "docIds": ["doc"], "repoTopK": 3, "score": .2, "answerRole": "原专业提示"},
        "agent": {"agentResourceId": "capability", "modelResourceId": "model", "modelConfig": {"domain": "fixed-model", "agentStrategy": 1},
            "instruction": {"reasoning": "思考{{question}}", "answer": "回答{{question}}", "query": "查询{{question}}"},
            "plugin": {"tools": [], "mcpServerIds": [], "mcpServerUrls": [], "workflowIds": [], "knowledge": []}, "maxLoopCount": 4},
        "database": {"dataResourceId": "capability", "dbId": 7, "spaceId": None, "mode": 3, "tableName": "items",
            "assignmentList": ["name"], "limit": 0},
        "rpa": {"rpaResourceId": "capability", "projectId": "legacy-project", "rpaParams": {"execPosition": "EXECUTOR"}},
    }[kind]
    params.update(changes)
    identifier = kind + "::cap"
    selected_outputs = {
        "plugin": [output("answer"), output("count", "integer")],
        "knowledge-pro-base": [output("output"), output("result", "array")],
        "agent": [output("answer"), output("REASONING_CONTENT")],
        "database": [output("isSuccess", "boolean"), output("message"), output("outputList", "array", items={"type": "object", "required": ["name", "count"], "properties": {"name": {"type": "string"}, "count": {"type": "integer"}}})],
        "rpa": [output("answer")],
    }[kind]
    if kind == "database" and params["mode"] == 4:
        selected_outputs = selected_outputs[:2]
    raw = raw_workflow([node("node-start::s", outputs=[output("question"), output("bundle", "object")]),
        node(identifier, inputs=[ref("question", "node-start::s", "question"), ref("bundle", "node-start::s", "bundle", "object")], outputs=selected_outputs, **params),
        node("node-end::e", inputs=[ref(item["name"], identifier, item["name"], item["schema"]["type"]) for item in selected_outputs], outputMode=0)],
        [edge("node-start::s", identifier), edge(identifier, "node-end::e")], {"question": "原查询", "bundle": {"nested": [{"business": "原透传"}]}})
    resources = {
        "plugin": {"kind": "tool", "provider": "spark-link", "pluginId": "legacy-tool", "operationId": "lookup", "legacyAppId": "legacy-label",
            "legacyVersion": "V1.0", "effectClass": "read", "openApiSchema": {"paths": {"/fixed": {"get": {"operationId": "lookup",
                "parameters": [{"in": "query", "name": "question", "schema": {"type": "string", "x-from": 0}},
                    {"in": "path", "name": "business", "schema": {"type": "string", "x-from": 1}}],
                "requestBody": {"content": {"application/json": {"schema": {"type": "object", "properties": {"defaulted": {"type": "string", "default": "保留默认"}}}}}}}}}}},
        "knowledge-pro-base": {"kind": "knowledge-pro", "provider": "CBG-RAG", "ragMode": "DeepSearch", "repoIds": ["repo"], "docIds": ["doc"], "modelResourceId": "model"},
        "agent": {"kind": "agent", "provider": "original-agent", "strategy": 1, "modelResourceId": "model", "dependencyBindings": {}, "dependencySelections": {},
            "legacyPlugin": {"tools": [], "mcp_server_ids": [], "mcp_server_urls": [], "workflow_ids": [], "knowledge": []}},
        "database": {"kind": "database", "provider": "center-data", "legacyDbId": 7, "legacySpaceId": None,
            "tables": {"items": {"allowedModes": [1, 2, 3, 4], "execEnvs": [1, 2], "columns": ["name", "count", "question", "bundle"]}}},
        "rpa": {"kind": "rpa", "provider": "original-rpa", "projectId": "legacy-project", "execPosition": "EXECUTOR",
            "stopSupported": True, "limits": {"timeoutSeconds": 300}},
    }[kind]
    resources.update(version="v1", enabled=True)
    raw["resourceSnapshots"] = {"capability": resources, "model": {"kind": "model", "provider": "xinghuo", "modelName": "fixed-model", "version": "model-v1", "enabled": True}}
    raw["effectPolicy"] = {"mode": "approved_write", "allowedOperations": ["tools.read", "tools.invoke", "knowledge.answer", "agent.invoke", "data.query", "data.mutate", "rpa.execute"], "approvalRef": "center-approved-fixture"}
    if kind == "database":
        raw["dataEnvironment"] = 2
    return raw


class CapabilityCenter(RecordingCenter):
    def __init__(self, raw):
        super().__init__(raw)
        self.body, self.changed, self.changed_ack = None, {}, {}
        self.frame_calls, self.cancel_calls = [], []
        self.failure_kind = None
        self.stop_confirmed = True
        self.operation_wait = self.frame_wait = None
        self.reject_operation = False
        kind = raw["dsl"]["nodes"][1]["id"].split("::")[0]
        self.profile = {"agent": "agent", "knowledge-pro-base": "knowledge-pro", "rpa": "rpa"}.get(kind)
        self.frames = {"agent": [openai_frame("原Agent答", reasoning="原Agent思")],
            "knowledge-pro": [{"code": 0, "data": {"content_type": "answer", "content": "原专业答"}},
                {"code": 0, "data": {"content_type": "knowledge_metadata", "content": [{"doc": "doc", "score": .7, "nested": {"page": 3}}]}}, "[DONE]"],
            "rpa": [{"code": 0, "message": "", "data": {"answer": "中间"}}, {"code": 0, "message": "", "data": {"answer": "最后"}}]}.get(self.profile, [])
        self.original = {"plugin": {"header": {"code": 0, "message": ""}, "payload": {"text": {"text": json.dumps({"answer": "原插件答"})}}},
        "database": {"code": 0, "message": "原完成", "data": {"exec_success": [{"name": "原数据", "count": 2}]}}}.get(kind)

    async def resource(self, *args):
        self.resource_calls.append(args)
        return {**copy.deepcopy(self.raw["resourceSnapshots"][args[-1]]), **self.changed}

    def binding(self):
        return {key: copy.deepcopy(self.body[key]) for key in ("kind", "provider", "resourceId", "resourceVersion", "resourceBindings")}

    async def operation(self, *args):
        self.operations.append(copy.deepcopy(args))
        self.body = copy.deepcopy(args[5])
        if self.operation_wait is not None:
            self.operation_wait.set()
            await asyncio.Event().wait()
        if self.reject_operation:
            raise CenterUnavailable()
        if self.profile:
            return {**self.binding(), "state": "RUNNING", "operationId": "original-operation", **self.changed_ack}
        ack = {**self.binding(), "state": "FAILED" if self.failure_kind else "COMPLETED", "legacyResponse": copy.deepcopy(self.original), **self.changed_ack}
        if self.failure_kind:
            ack["failureKind"] = self.failure_kind
        return ack

    async def legacy_frames(self, *args):
        self.frame_calls.append(args)
        if self.frame_wait is not None:
            self.frame_wait.set()
            await asyncio.Event().wait()
        cursor = args[-2]
        frames = copy.deepcopy(self.frames[cursor:cursor + 1])
        done = cursor + len(frames) == len(self.frames)
        ack = {**self.binding(), "frames": frames, "nextCursor": cursor + len(frames), "done": done,
            "state": "FAILED" if self.failure_kind else "COMPLETED" if done else "RUNNING", **self.changed_ack}
        if self.failure_kind:
            ack["failureKind"] = self.failure_kind
        return ack

    async def cancel_operation(self, *args):
        self.cancel_calls.append(args)
        if not self.stop_confirmed:
            raise CenterUnavailable()
        return {"stopped": True}


class RemainingOriginalNodeTest(unittest.IsolatedAsyncioTestCase):
    async def execute(self, raw, center=None, state="COMPLETED"):
        center = center or CapabilityCenter(raw)
        await asyncio.wait_for(create_task_runner(center, heartbeat_seconds=.02).run(raw), 8)
        self.assertEqual(center.results[-1]["state"], state, center.results[-1])
        return center

    async def test_plugin_original_schema_base64_business_search_and_default_output(self):
        center = await self.execute(capability_raw("plugin"))
        self.assertEqual(center.results[-1]["output"]["outputs"], {"answer": "原插件答", "count": 0})
        payload = center.body["legacyPayload"]
        self.assertEqual(json.loads(base64.b64decode(payload["payload"]["message"]["path"])), {"business": "原透传"})
        self.assertEqual(json.loads(base64.b64decode(payload["payload"]["message"]["query"])), {"question": "原查询"})
        self.assertEqual(json.loads(base64.b64decode(payload["payload"]["message"]["body"])), {"defaulted": "保留默认"})
        self.assertEqual(center.operations[0][4], "tools.read")

    async def test_plugin_original_wrong_output_type_defaults(self):
        raw = capability_raw("plugin")
        center = CapabilityCenter(raw)
        center.original["payload"]["text"]["text"] = '{"answer": 42, "count": true}'
        await self.execute(raw, center)
        self.assertEqual(center.results[-1]["output"]["outputs"], {"answer": "", "count": 0})

    async def test_link_constructor_and_raw_pgsql_old_calls_are_blocked(self):
        from workflow.engine.nodes.plugin_tool.link_client import Link
        from workflow.engine.nodes.pgsql.pgsql_client import PGSqlClient, PGSqlConfig
        from workflow.extensions.otlp.trace.span import Span
        with self.assertRaises(WorkerError):
            Link("label", ["tool"], "https://must-not-call.invalid", "https://must-not-call.invalid")
        with self.assertRaises(WorkerError):
            await PGSqlClient(config=PGSqlConfig("", "", 7, "", "", "SELECT 1")).exec_dml(Span())

    async def test_pro_original_request_answer_metadata_and_special_modes(self):
        center = await self.execute(capability_raw("knowledge-pro-base"))
        outputs = center.results[-1]["output"]["outputs"]
        self.assertEqual(outputs["output"], "原专业答")
        self.assertEqual(outputs["result"], [{"doc": "doc", "score": .7, "nested": {"page": 3}}])
        payload = center.body["legacyPayload"]
        self.assertEqual(payload["rag"]["type"], "CBG-RAG")
        self.assertEqual(payload["rag"]["mode"], "DeepSearch")
        self.assertEqual(payload["rag"]["llm"], {"model": "fixed-model", "max_token": 100, "top_k": 2, "temperature": .5})
        raw = capability_raw("knowledge-pro-base", repoType=1, ragType=2, docIds=[])
        raw["resourceSnapshots"]["capability"].update(provider="AIUI-RAG2", ragMode="LongRAG", docIds=[])
        center = await self.execute(raw)
        self.assertEqual(center.body["legacyPayload"]["rag"]["mode"], "LongRAG")
        self.assertEqual(center.operations[0][4], "knowledge.answer")

    async def test_pro_original_metadata_followed_by_answer_clears_metadata(self):
        raw = capability_raw("knowledge-pro-base")
        center = CapabilityCenter(raw)
        center.frames = [center.frames[1], center.frames[0], "[DONE]"]
        await self.execute(raw, center)
        self.assertEqual(center.results[-1]["output"]["outputs"]["result"], [])

    async def test_pro_cbg_requires_documents_preserves_original_error(self):
        raw = capability_raw("knowledge-pro-base", docIds=[])
        center = await self.execute(raw, state="FAILED")
        self.assertEqual(center.operations, [])
        from workflow.exception.errors.err_code import CodeEnum
        failed = [event for event in center.events if event["eventType"] == "node.failed"]
        self.assertEqual(failed[-1]["payload"]["legacyFrame"]["code"], CodeEnum.KNOWLEDGE_PARAM_ERROR.code)

    async def test_agent_original_templates_reasoning_tools_and_usage(self):
        center = await self.execute(capability_raw("agent"))
        self.assertEqual(center.results[-1]["output"]["outputs"], {"answer": "原Agent答", "REASONING_CONTENT": "原Agent思"})
        self.assertEqual(center.results[-1]["output"]["usage"]["total_tokens"], 5)
        self.assertEqual(center.body["legacyPayload"]["instruction"], {"reasoning": "思考原查询", "answer": "回答原查询"})
        self.assertEqual(center.body["legacyPayload"]["messages"], [{"role": "user", "content": "查询原查询"}])
        self.assertEqual(center.body["legacyPayload"]["model_config"], {"domain": "fixed-model"})
        self.assertNotIn("uid", center.body["legacyPayload"])

    async def test_agent_normalizes_tools_and_formats_original_tool_reasoning(self):
        raw = capability_raw("agent")
        plugin = raw["dsl"]["nodes"][1]["data"]["nodeParam"]["plugin"]
        plugin["tools"] = ["tool-1"]
        refresh_test_canonical(raw)
        raw["resourceSnapshots"]["capability"]["legacyPlugin"]["tools"] = [{"tool_id": "tool-1", "version": "V1.0"}]
        raw["resourceSnapshots"]["capability"]["dependencyBindings"] = {"tool": {"kind": "tool", "provider": "spark-link", "version": "tool-v1"}}
        raw["resourceSnapshots"]["capability"]["dependencySelections"] = {"tool:tool-1:V1.0": "tool"}
        raw["resourceSnapshots"]["tool"] = {"kind": "tool", "provider": "spark-link", "version": "tool-v1", "enabled": True, "pluginId": "tool-1", "legacyVersion": "V1.0"}
        center = CapabilityCenter(raw)
        center.frames[0]["choices"][0]["delta"]["tool_calls"] = [{"type": "tool", "reason": "原工具推理", "function": {"name": "lookup", "arguments": "{}", "response": json.dumps({"header": {"code": 0}, "payload": {"text": {"text": '{"answer":"原工具"}'}}})}}]
        await self.execute(raw, center)
        self.assertIn("原工具推理", center.results[-1]["output"]["outputs"]["REASONING_CONTENT"])
        self.assertIn("原工具", center.results[-1]["output"]["outputs"]["REASONING_CONTENT"])
        self.assertEqual(center.body["resourceBindings"]["tool"]["version"], "tool-v1")

    async def test_database_original_search_and_write_projection_defaults(self):
        center = await self.execute(capability_raw("database"))
        self.assertEqual(center.results[-1]["output"]["outputs"]["outputList"], [{"name": "原数据", "count": 2}])
        self.assertEqual(center.operations[0][4], "data.query")
        self.assertEqual(center.body["legacyPayload"]["limit"], 0)
        raw = capability_raw("database", mode=1)
        center = CapabilityCenter(raw)
        center.original["data"]["exec_success"][0].pop("count")
        await self.execute(raw, center)
        self.assertEqual(center.results[-1]["output"]["outputs"]["outputList"], [{"name": "原数据", "count": 0}])
        self.assertEqual(center.body["legacyPayload"]["returning"], "last_inserted_row")
        self.assertEqual(len(center.operations), 1)
        self.assertEqual(center.operations[0][4], "data.mutate")

    async def test_database_original_first_case_like_zero_and_order(self):
        case = {"logicalOperator": "or", "conditions": [{"fieldName": "name", "varIndex": "question", "selectCondition": "like"},
            {"fieldName": "count", "varIndex": "ignored", "selectCondition": "null", "fieldType": "integer"}]}
        raw = capability_raw("database", cases=[case, {"logicalOperator": "and", "conditions": []}], orderData=[{"fieldName": "name", "order": "desc"}], limit=3)
        center = await self.execute(raw)
        self.assertEqual(center.body["legacyPayload"]["where"], {"logicalOperator": "or", "conditions": [
            {"fieldName": "name", "operator": "like", "value": "%原查询%"}, {"fieldName": "count", "operator": "null", "zeroEquivalent": 0}]})
        self.assertEqual(center.body["legacyPayload"]["order"], [{"fieldName": "name", "order": "desc"}])

    async def test_database_update_and_delete_are_single_center_transactions(self):
        case = {"logicalOperator": "and", "conditions": [{"fieldName": "name", "varIndex": "question", "selectCondition": "="}]}
        for mode in (2, 4):
            raw = capability_raw("database", mode=mode, cases=[case], assignmentList=["question"])
            center = await self.execute(raw)
            self.assertEqual(len(center.operations), 1)
            self.assertEqual(center.operations[0][4], "data.mutate")
            self.assertNotIn("dml", center.body["legacyPayload"])
            self.assertEqual(center.body["legacyPayload"]["returning"], "matched_rows" if mode == 2 else "exec_success")
            if mode == 4:
                self.assertNotIn("outputList", center.results[-1]["output"]["outputs"])
            else:
                self.assertEqual(center.body["legacyPayload"]["values"], {"question": "原查询"})

    async def test_database_search_missing_original_required_column_is_failed(self):
        raw = capability_raw("database")
        center = CapabilityCenter(raw)
        center.original["data"]["exec_success"][0].pop("count")
        await self.execute(raw, center, "FAILED")

    async def test_synchronous_write_cancel_before_final_ack_keeps_unknown(self):
        for kind in ("plugin", "database"):
            raw = capability_raw(kind, **({"mode": 1} if kind == "database" else {}))
            if kind == "plugin":
                raw["resourceSnapshots"]["capability"]["effectClass"] = "write"
            center = CapabilityCenter(raw)
            center.operation_wait = asyncio.Event()
            execution = asyncio.create_task(create_task_runner(center, heartbeat_seconds=.02).run(raw))
            await asyncio.wait_for(center.operation_wait.wait(), 8)
            center.cancel_requested = True
            await asyncio.wait_for(execution, 8)
            self.assertEqual(center.results[-1]["state"], "EFFECT_UNKNOWN")

    async def test_database_ungranted_custom_sql_and_identifier_injection_are_explicitly_refused(self):
        for params in ({"mode": 0, "sql": "SELECT name FROM items"}, {"tableName": "items;DROP_TABLE"}, {"assignmentList": ["name --"]}):
            center = await self.execute(capability_raw("database", **params), state="FAILED")
            self.assertEqual(center.operations, [])

    async def test_rpa_original_last_frame_and_parameters_no_old_header(self):
        with patch.dict("os.environ", {"RPA_BASE_URL": "https://must-not-call.invalid"}):
            center = await self.execute(capability_raw("rpa"))
        self.assertEqual(center.results[-1]["output"]["outputs"], {"answer": "最后"})
        self.assertEqual(center.body["legacyPayload"]["project_id"], "legacy-project")
        self.assertEqual(center.body["legacyPayload"]["params"]["question"], "原查询")
        self.assertEqual(center.operations[0][4], "rpa.execute")
        self.assertEqual(center.cancel_calls, [])

    async def test_changed_resource_and_nested_iteration_never_dispatch_all_five(self):
        for kind in ("plugin", "knowledge-pro-base", "agent", "database", "rpa"):
            raw = capability_raw(kind)
            center = CapabilityCenter(raw)
            center.changed = {"version": "other"}
            await self.execute(raw, center, "FAILED")
            self.assertEqual(center.operations, [])
            raw = capability_raw(kind)
            token = CURRENT_ITERATION_ID.set("unregistered-round")
            try:
                center = await self.execute(raw, state="FAILED")
                self.assertEqual(center.operations, [])
            finally:
                CURRENT_ITERATION_ID.reset(token)
        for kind, changed in (("plugin", {"effectClass": "write"}), ("rpa", {"limits": {"timeoutSeconds": 1}}),
                ("agent", {"dependencySelections": {"unexpected": "model"}})):
            raw = capability_raw(kind)
            center = CapabilityCenter(raw)
            center.changed = changed
            await self.execute(raw, center, "FAILED")
            self.assertEqual(center.operations, [])

    async def test_old_credentials_and_targets_history_and_read_only_fees_refused(self):
        variants = [("rpa", {"header": {"apiKey": "rejected-test-value"}}), ("agent", {"apiKey": "rejected-test-value"}),
            ("agent", {"modelConfig": {"domain": "fixed-model", "api": "https://must-not-call.invalid", "agentStrategy": 1}}),
            ("knowledge-pro-base", {"url": "https://must-not-call.invalid"}), ("database", {"apiKey": "rejected-test-value"}),
            ("agent", {"enableChatHistoryV2": {"isEnabled": True}})]
        for kind, params in variants:
            center = await self.execute(capability_raw(kind, **params), state="FAILED")
            self.assertEqual(center.operations, [])
        for kind in ("knowledge-pro-base", "agent", "rpa"):
            raw = capability_raw(kind)
            raw["effectPolicy"].update(mode="read_only")
            center = await self.execute(raw, state="FAILED")
            self.assertEqual(center.operations, [])

    async def test_legal_provider_errors_keep_original_nodes_error_codes(self):
        from workflow.exception.errors.err_code import CodeEnum
        expected = {"plugin": CodeEnum.PLUGIN_NODE_EXECUTION_ERROR, "database": CodeEnum.PG_SQL_REQUEST_ERROR,
            "knowledge-pro-base": CodeEnum.KNOWLEDGE_REQUEST_ERROR, "agent": CodeEnum.AGENT_NODE_EXECUTION_ERROR, "rpa": CodeEnum.RPA_REQUEST_ERROR}
        for kind, error in expected.items():
            raw = capability_raw(kind)
            center = CapabilityCenter(raw)
            center.failure_kind = "provider"
            if center.profile:
                center.frames = [{"code": 1003, "message": "原Provider失败"}]
            elif kind == "plugin":
                center.original = {"header": {"code": 1003, "message": "原Provider失败"}}
            else:
                center.original = {"code": 1003, "message": "原Provider失败"}
            await self.execute(raw, center, "FAILED")
            failed = [event for event in center.events if event["eventType"] == "node.failed"]
            self.assertEqual(failed[-1]["payload"]["legacyFrame"]["code"], error.code)

    async def test_bad_binding_and_authorization_frames_do_not_become_provider_failures(self):
        for kind in ("agent", "knowledge-pro-base", "rpa"):
            raw = capability_raw(kind)
            center = CapabilityCenter(raw)
            center.failure_kind = "authorization"
            center.frames = [{"code": 1003, "message": "cannot-default"}]
            await self.execute(raw, center, "FAILED")
            self.assertFalse(any(e["nodeId"] == "node-end::e" and e["eventType"] == "node.started" for e in center.events))
        raw = capability_raw("agent")
        center = CapabilityCenter(raw)
        center.changed_ack = {"resourceVersion": "other"}
        await self.execute(raw, center, "EFFECT_UNKNOWN")

    async def test_missing_agent_dependency_mapping_and_rpa_stop_limits_refuse(self):
        raw = capability_raw("agent")
        raw["dsl"]["nodes"][1]["data"]["nodeParam"]["plugin"]["tools"] = ["not-bound"]
        raw["resourceSnapshots"]["capability"]["legacyPlugin"]["tools"] = [{"tool_id": "not-bound", "version": "V1.0"}]
        refresh_test_canonical(raw)
        center = await self.execute(raw, state="FAILED")
        self.assertEqual(center.operations, [])
        for changes in ({"stopSupported": False}, {"limits": {}}, {"execPosition": "other"}):
            raw = capability_raw("rpa")
            raw["resourceSnapshots"]["capability"].update(changes)
            center = await self.execute(raw, state="FAILED")
            self.assertEqual(center.operations, [])

    async def test_agent_original_done_parse_gap_and_unconfirmed_pro_terminal_refuse(self):
        raw = capability_raw("agent")
        center = CapabilityCenter(raw)
        center.frames = ["[DONE]"]
        await self.execute(raw, center, "FAILED")
        self.assertEqual(len(center.cancel_calls), 1)
        raw = capability_raw("knowledge-pro-base")
        center = CapabilityCenter(raw)
        center.changed_ack = {"done": False, "state": "RUNNING"}
        center.frames = ["[DONE]"]
        await self.execute(raw, center, "FAILED")
        self.assertEqual(len(center.cancel_calls), 1)

    async def test_stream_cancel_confirmed_or_unknown(self):
        for kind in ("agent", "knowledge-pro-base", "rpa"):
            for stopped in (True, False):
                raw = capability_raw(kind)
                center = CapabilityCenter(raw)
                center.frame_wait = asyncio.Event()
                center.stop_confirmed = stopped
                execution = asyncio.create_task(create_task_runner(center, heartbeat_seconds=.02).run(raw))
                await asyncio.wait_for(center.frame_wait.wait(), 8)
                center.cancel_requested = True
                await asyncio.wait_for(execution, 8)
                self.assertEqual(center.results[-1]["state"], "CANCELLED" if stopped else "EFFECT_UNKNOWN")
                self.assertEqual(len(center.cancel_calls), 1)

    async def test_fee_dispatch_ack_loss_or_cancel_remains_unknown(self):
        for kind in ("agent", "knowledge-pro-base", "rpa"):
            raw = capability_raw(kind)
            center = CapabilityCenter(raw)
            center.operation_wait = asyncio.Event()
            execution = asyncio.create_task(create_task_runner(center, heartbeat_seconds=.02).run(raw))
            await asyncio.wait_for(center.operation_wait.wait(), 8)
            center.cancel_requested = True
            await asyncio.wait_for(execution, 8)
            self.assertEqual(center.results[-1]["state"], "EFFECT_UNKNOWN")

    async def test_legacy_http_frames_exact_path_and_bad_shapes(self):
        raw = capability_raw("agent")
        center = await self.execute(raw)
        ack = {**center.binding(), "frames": [openai_frame("答")], "nextCursor": 1, "done": True, "state": "COMPLETED"}
        transport = MemoryTransport(response={"code": 200, "data": ack})
        client = CenterClient("https://center.example", "fixture", transport=transport)
        self.assertEqual(await client.legacy_frames("run", "lease", 1, "operation", 0), ack)
        self.assertEqual(transport.calls[0][3], {"leaseId": "lease", "attempt": 1, "afterCursor": 0, "limit": 64})
        for changes in ({"provider": "arbitrary"}, {"frames": [42]}, {"nextCursor": True}, {"resourceBindings": []}):
            transport.response["data"] = {**ack, **changes}
            with self.assertRaises(CenterUnavailable):
                await client.legacy_frames("run", "lease", 1, "operation", 0)

    async def test_machine_draft_matches_five_actual_requests_and_shared_refs(self):
        from jsonschema import Draft202012Validator
        root = Path(__file__).resolve().parents[1]
        path = root / "protocols/remaining-capabilities-draft.schema.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(document)
        validator = Draft202012Validator({"$ref": "#/$defs/Parameters", "$defs": document["$defs"]})
        for kind in ("plugin", "knowledge-pro-base", "agent", "database", "rpa"):
            center = await self.execute(capability_raw(kind))
            validator.validate(center.body)
            args = center.operations[0]
            Draft202012Validator({"$ref": "#/$defs/OperationRequest", "$defs": document["$defs"]}).validate({
                "leaseId": args[1], "attempt": args[2], "nodeId": args[3], "operation": args[4], "parameters": args[5], "idempotencyKey": args[6]})
        for name in ("Assignment", "ExecutionBeginAck", "CheckpointCreateAck"):
            source, _, pointer = document["$defs"][name]["$ref"].partition("#")
            value = json.loads((path.parent / source).resolve().read_text(encoding="utf-8"))
            if pointer:
                for part in pointer.lstrip("/").split("/"):
                    value = value[part]
            Draft202012Validator.check_schema(value)


if __name__ == "__main__":
    unittest.main()
