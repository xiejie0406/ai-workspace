"""原普通KnowledgeNode/Client算法通过隔离中心ACK运行；没有真实检索Producer。"""
import asyncio
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from worker.engine_adapter import create_task_runner
from worker.errors import CenterUnavailable
from tests.test_original_engine import node, output, ref, edge, raw_workflow
from tests.test_worker import RecordingCenter, refresh_test_canonical


def knowledge_raw(**params):
    raw = raw_workflow([node("node-start::s", outputs=[output("query")]),
        node("knowledge-base::k", inputs=[ref("query", "node-start::s", "query")], outputs=[output("result", "array", items={"type": "object"})],
            knowledgeResourceId="knowledge-1", repoId=["original-repo-1"], docIds=["original-doc-1"], topN="3", score=.25, **params),
        node("node-end::e", inputs=[ref("result", "knowledge-base::k", "result", "array")], outputMode=0)],
        [edge("node-start::s", "knowledge-base::k"), edge("knowledge-base::k", "node-end::e")], {"query": "原知识查询"})
    raw["resourceSnapshots"] = {"knowledge-1": {"kind": "knowledge", "provider": "AIUI-RAG2", "version": "knowledge-v1", "enabled": True,
        "repoIds": ["original-repo-1"], "docIds": ["original-doc-1"], "flowId": "formal-flow-1"}}
    return raw


class KnowledgeCenter(RecordingCenter):
    def __init__(self, raw):
        super().__init__(raw)
        self.changed = {}
        self.failure_kind = None
        self.reject_knowledge = False
        self.raw_error = {"code": 1003}
        self.results_fixture = [{"content": "原片段正文", "docId": "original-doc-1", "score": .8,
            "metadata": {"pages": [1, 2], "chunk": {"start": 2, "end": 8}}}]

    async def resource(self, *args):
        self.resource_calls.append(args)
        return {**copy.deepcopy(self.raw["resourceSnapshots"]["knowledge-1"]), **self.changed}

    async def operation(self, *args):
        self.operations.append(copy.deepcopy(args))
        if self.reject_knowledge:
            raise CenterUnavailable()
        params = args[5]
        ack = {"state": "FAILED" if self.failure_kind else "COMPLETED", "resourceId": params["resourceId"],
            "resourceVersion": params["resourceVersion"], "provider": "AIUI-RAG2"}
        if self.failure_kind:
            ack.update(failureKind=self.failure_kind, legacyResponse=self.raw_error)
        else:
            ack["data"] = {"results": copy.deepcopy(self.results_fixture), "otherMetadata": {"preserved": True}}
        return ack


class OriginalKnowledgeNodeTest(unittest.IsolatedAsyncioTestCase):
    async def execute(self, raw, center=None, state="COMPLETED"):
        center = center or KnowledgeCenter(raw)
        await asyncio.wait_for(create_task_runner(center).run(raw), 8)
        self.assertEqual(center.results[-1]["state"], state)
        return center

    async def test_original_payload_and_results_keep_nested_metadata_without_old_env_target(self):
        raw = knowledge_raw()
        with patch.dict("os.environ", {"KNOWLEDGE_BASE_URL": "https://must-not-call.invalid"}):
            center = await self.execute(raw)
        self.assertEqual(center.results[-1]["output"]["outputs"], {"result": center.results_fixture})
        params = center.operations[0][5]
        self.assertEqual(params["legacyPayload"], {"query": "原知识查询", "topN": "3", "ragType": "AIUI-RAG2",
            "match": {"repoId": ["original-repo-1"], "docIds": ["original-doc-1"], "flowId": "formal-flow-1", "threshold": .25}, "history": []})
        self.assertEqual(center.operations[0][4], "knowledge.retrieve")
        self.assertNotIn("url", params)

    async def test_missing_binding_changed_repository_document_flow_or_version_never_reads(self):
        for changed in [{"repoIds": ["other"]}, {"docIds": ["other"]}, {"flowId": "other"}, {"version": "other"}, {"enabled": False}]:
            raw = knowledge_raw()
            center = KnowledgeCenter(raw)
            center.changed = changed
            await self.execute(raw, center, state="FAILED")
            self.assertEqual(center.operations, [])
        raw = knowledge_raw()
        raw["dsl"]["nodes"][1]["data"]["nodeParam"]["knowledgeResourceId"] = ""
        refresh_test_canonical(raw)
        center = await self.execute(raw, state="FAILED")
        self.assertEqual(center.operations, [])

    async def test_declared_history_without_scope_refuses_empty_memory_fallback(self):
        raw = knowledge_raw(enableChatHistoryV2={"isEnabled": True})
        center = await self.execute(raw, state="FAILED")
        self.assertEqual(center.operations, [])

    async def test_center_unavailable_is_not_success_or_local_index_fallback(self):
        raw = knowledge_raw()
        center = KnowledgeCenter(raw)
        center.reject_knowledge = True
        with self.assertRaises(CenterUnavailable):
            await asyncio.wait_for(create_task_runner(center).run(raw), 8)
        self.assertEqual(center.results, [])

    async def test_legal_provider_failure_uses_original_knowledge_code(self):
        from workflow.exception.errors.err_code import CodeEnum
        raw = knowledge_raw()
        center = KnowledgeCenter(raw)
        center.failure_kind = "provider"
        await self.execute(raw, center, state="FAILED")
        failed = [event for event in center.events if event["eventType"] == "node.failed"]
        self.assertEqual(failed[-1]["payload"]["legacyFrame"]["code"], CodeEnum.KNOWLEDGE_REQUEST_ERROR.code)

    async def test_authorization_or_fake_provider_error_cannot_continue_original_retry_defaults(self):
        for kind, response in [("authorization", {"code": 1003}), ("provider", {"code": 0})]:
            raw = knowledge_raw()
            center = KnowledgeCenter(raw)
            center.failure_kind, center.raw_error = kind, response
            await self.execute(raw, center, state="FAILED")
            self.assertFalse(any(e["nodeId"] == "node-end::e" and e["eventType"] == "node.started" for e in center.events))

    async def test_knowledge_machine_draft_matches_actual_parameters(self):
        from jsonschema import Draft202012Validator
        root = Path(__file__).resolve().parents[1]
        document = json.loads((root / "protocols/knowledge-draft.schema.json").read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(document)
        center = await self.execute(knowledge_raw())
        Draft202012Validator({"$ref": "#/$defs/Parameters", "$defs": document["$defs"]}).validate(center.operations[0][5])


if __name__ == "__main__":
    unittest.main()
