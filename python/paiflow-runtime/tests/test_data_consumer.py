"""数据消费者真实原节点隔离检查；中心回执fixture不证明业务库已迁移。"""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator
from worker.engine_adapter import create_task_runner
from worker.errors import WorkerError
from worker.task_runner import Assignment
from tests.test_worker import assignment, refresh_test_canonical
from tests.test_remaining_nodes import capability_raw, CapabilityCenter


def custom_raw(sql):
    raw = capability_raw("database", mode=0, sql=sql, tableName=None)
    raw["resourceSnapshots"]["capability"]["tables"]["items"]["allowedModes"].append(0)
    return raw


class DataEnvironmentContractTest(unittest.TestCase):
    def test_pure_graph_optional_and_database_requires_centre_environment(self):
        self.assertIsNone(Assignment.parse(assignment()).data_environment)
        raw = capability_raw("database")
        for env in (1, 2):
            raw["dataEnvironment"] = env
            self.assertEqual(env, Assignment.parse(raw).data_environment)
        for env in (None, True, 0, 3, "2"):
            raw["dataEnvironment"] = env
            with self.assertRaises(WorkerError):
                Assignment.parse(raw)
        raw.pop("dataEnvironment")
        with self.assertRaises(WorkerError):
            Assignment.parse(raw)

    def test_shared_schema_enforces_same_conditional_field(self):
        contract = Path(__file__).resolve().parents[3] / "contracts/paiflow/worker.schema.json"
        validator = Draft202012Validator(json.loads(contract.read_text(encoding="utf-8")))
        validator.validate(assignment())
        raw = capability_raw("database")
        validator.validate(raw)
        raw.pop("dataEnvironment")
        self.assertTrue(list(validator.iter_errors(raw)))
        for env in (None, True, 0, 3, "2"):
            raw["dataEnvironment"] = env
            self.assertTrue(list(validator.iter_errors(raw)))


class DataConsumerTest(unittest.IsolatedAsyncioTestCase):
    async def execute(self, raw, center=None, state="COMPLETED"):
        center = center or CapabilityCenter(raw)
        await create_task_runner(center, heartbeat_seconds=.02).run(raw)
        self.assertEqual(state, center.results[-1]["state"], center.results[-1])
        return center

    async def test_original_pool_and_structured_request_use_only_centre_environment(self):
        from workflow.engine.entities.variable_pool import ParamKey, SystemParams
        original = SystemParams.set
        for env in (1, 2):
            observed = []
            def capture(pool, key, value, **kwargs):
                if key == ParamKey.IsRelease:
                    observed.append(value)
                return original(pool, key, value, **kwargs)
            raw = capability_raw("database", execDev=3, dataEnvironment=3)
            raw["dataEnvironment"] = env
            with patch.object(SystemParams, "set", capture):
                center = await self.execute(raw)
            self.assertEqual([env == 2], observed)
            self.assertEqual(env, center.body["legacyPayload"]["execDev"])

    async def test_custom_select_preserves_original_placeholder_substitution_and_query_policy(self):
        raw = custom_raw("SELECT name, count FROM items WHERE name = '{{question}}'")
        raw["dataEnvironment"] = 1
        raw["effectPolicy"] = {"mode": "read_only", "allowedOperations": ["data.query"]}
        center = await self.execute(raw)
        self.assertEqual("data.query", center.operations[0][4])
        self.assertEqual({"mode": 0, "sql": "SELECT name, count FROM items WHERE name = '原查询'", "execDev": 1}, center.body["legacyPayload"])
        self.assertEqual(1, len(center.operations))
        self.assertEqual([{"name": "原数据", "count": 2}], center.results[-1]["output"]["outputs"]["outputList"])

    async def test_custom_insert_update_delete_all_require_approved_mutate(self):
        for sql in ("INSERT INTO items(name) VALUES ('{{question}}')", "UPDATE items SET name='{{question}}' WHERE count=2", "DELETE FROM items WHERE count=2"):
            raw = custom_raw(sql)
            center = await self.execute(raw)
            self.assertEqual("data.mutate", center.operations[0][4])
            self.assertEqual(1, len(center.operations))
            raw["effectPolicy"] = {"mode": "read_only", "allowedOperations": ["data.query", "data.mutate"]}
            refused = await self.execute(raw, state="FAILED")
            self.assertEqual([], refused.operations)

    async def test_ungranted_mode_environment_changed_resource_and_credentials_refuse_before_effect(self):
        raw = custom_raw("SELECT name FROM items")
        for field, value in (("allowedModes", [3]), ("execEnvs", [1]), ("execEnvs", [True, 2]), ("allowedModes", [False, 3])):
            changed = copy.deepcopy(raw)
            changed["resourceSnapshots"]["capability"]["tables"]["items"][field] = value
            refused = await self.execute(changed, state="FAILED")
            self.assertEqual([], refused.operations)
        changed = copy.deepcopy(raw)
        changed["dsl"]["nodes"][1]["data"]["nodeParam"]["apiKey"] = "fixture-rejected"
        refresh_test_canonical(changed)
        self.assertEqual([], (await self.execute(changed, state="FAILED")).operations)
        center = CapabilityCenter(raw)
        center.changed = {"tables": {}}
        self.assertEqual([], (await self.execute(raw, center, "FAILED")).operations)

    async def test_custom_provider_failure_and_unconfirmed_write_keep_distinct_states(self):
        raw = custom_raw("UPDATE items SET name='x' WHERE count=2")
        center = CapabilityCenter(raw)
        center.failure_kind = "provider"
        center.original = {"code": 1003, "message": "fixture-provider-failure"}
        await self.execute(raw, center, "FAILED")
        self.assertTrue(any(event["eventType"] == "node.failed" for event in center.events))
        unknown = CapabilityCenter(raw)
        unknown.reject_operation = True
        await self.execute(raw, unknown, "EFFECT_UNKNOWN")

    async def test_custom_payload_matches_current_machine_contract(self):
        root = Path(__file__).resolve().parents[1]
        document = json.loads((root / "protocols/remaining-capabilities-draft.schema.json").read_text(encoding="utf-8"))
        validator = Draft202012Validator({"$ref": "#/$defs/Parameters", "$defs": document["$defs"]})
        center = await self.execute(custom_raw('SELECT name FROM "items"'))
        validator.validate(center.body)


if __name__ == "__main__":
    unittest.main()
