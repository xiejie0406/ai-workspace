"""正式数据协议对原节点消费的兼容与拒绝边界；中心是隔离fixture，不是业务验收。"""
import copy
from pathlib import Path
import unittest

from tests.schema_support import local_validator
from tests.test_remaining_nodes import capability_raw, CapabilityCenter
from tests.test_worker import refresh_test_canonical
from worker.engine_adapter import create_task_runner


SCHEMA = Path(__file__).resolve().parents[3] / 'contracts/paiflow/data-operations.schema.json'
RESOURCE = 'data:7:revision:2'


def formal_raw(mode=3, environment=2):
    changes = {'mode': mode, 'limit': 3, 'assignmentList': ['name', 'count']}
    if mode == 0:
        changes.update(sql="SELECT name, count FROM items WHERE name='{{question}}'", tableName=None)
    raw = capability_raw('database', **changes)
    descriptor = raw['resourceSnapshots'].pop('capability')
    descriptor['version'] = '2'
    descriptor['tables']['items'].update(tableId=9, revision=2, schemaHash='a' * 64,
                                        allowedModes=[0, 1, 2, 3, 4])
    raw['resourceSnapshots'][RESOURCE] = descriptor
    raw['dsl']['nodes'][1]['data']['nodeParam']['dataResourceId'] = RESOURCE
    raw['dataEnvironment'] = environment
    if mode in (2, 4):
        raw['dsl']['nodes'][1]['data']['nodeParam']['cases'] = [{'logicalOperator': 'and', 'conditions': [
            {'fieldName': 'count', 'varIndex': '2', 'selectCondition': '=', 'fieldType': 'integer'}]}]
    if mode == 2:
        raw['dsl']['nodes'][1]['data']['nodeParam']['assignmentList'] = ['question', 'bundle']
    refresh_test_canonical(raw)
    return raw


class DataPublicContractTest(unittest.IsolatedAsyncioTestCase):
    async def captured(self, mode, environment=2):
        raw = formal_raw(mode, environment)
        center = CapabilityCenter(raw)
        await create_task_runner(center, heartbeat_seconds=.02).run(raw)
        self.assertEqual('COMPLETED', center.results[-1]['state'], center.results[-1])
        arguments = center.operations[0]
        request = {'leaseId': raw['leaseId'], 'attempt': raw['attempt'], 'nodeId': arguments[3],
                   'operation': arguments[4], 'parameters': arguments[5], 'idempotencyKey': arguments[6]}
        return raw, center, request

    async def test_real_original_node_requests_for_all_modes_and_both_environments(self):
        for mode in range(5):
            for environment in (1, 2):
                with self.subTest(mode=mode, environment=environment):
                    raw, center, request = await self.captured(mode, environment)
                    local_validator(SCHEMA, 'DataResource').validate(raw['resourceSnapshots'][RESOURCE])
                    local_validator(SCHEMA, 'DataOperationRequest').validate(request)
                    self.assertEqual(environment, center.body['legacyPayload']['execDev'])
                    self.assertEqual('data.query' if mode in (0, 3) else 'data.mutate', request['operation'])

    async def test_lease_environment_resource_and_unknown_fields_reject(self):
        _, _, request = await self.captured(3)
        validator = local_validator(SCHEMA, 'DataOperationRequest')
        for mutation in ('attempt', 'execDev', 'resourceId', 'resourceVersion', 'credential', 'iteration'):
            bad = copy.deepcopy(request)
            if mutation == 'attempt': bad['attempt'] = True
            elif mutation == 'execDev': bad['parameters']['legacyPayload']['execDev'] = True
            elif mutation == 'resourceId': bad['parameters']['resourceId'] = 'tool:7:version:2:operation:eA'
            elif mutation == 'resourceVersion': bad['parameters']['resourceVersion'] = 'v1'
            elif mutation == 'credential': bad['parameters']['apiKey'] = 'must-reject-fixture'
            else: bad['iterationId'] = 'unowned-iteration'
            with self.subTest(mutation=mutation): self.assertFalse(validator.is_valid(bad))

    async def test_default_personal_resource_and_logical_grant_reject_connection_and_bool(self):
        raw, _, _ = await self.captured(3)
        resource = raw['resourceSnapshots'][RESOURCE]
        validator = local_validator(SCHEMA, 'DataResource')
        self.assertIsNone(resource['legacySpaceId'])
        for field, value in (('legacyDbId', True), ('legacySpaceId', False), ('endpoint', 'https://private.invalid')):
            self.assertFalse(validator.is_valid({**resource, field: value}))
        bad = copy.deepcopy(resource)
        bad['tables']['items']['allowedModes'] = [True, 3]
        self.assertFalse(validator.is_valid(bad))

    async def test_synchronous_ack_has_no_fabricated_operation_lifecycle(self):
        _, center, _ = await self.captured(3)
        ack = {**center.binding(), 'state': 'COMPLETED', 'failureKind': None, 'legacyResponse': center.original}
        validator = local_validator(SCHEMA, 'DataOperationAck')
        validator.validate(ack)
        for field, value in (('operationId', 'fabricated'), ('state', 'RUNNING'), ('failureKind', 'provider')):
            self.assertFalse(validator.is_valid({**ack, field: value}))
        bad = copy.deepcopy(ack)
        bad['legacyResponse']['data']['exec_success'] = [True]
        self.assertFalse(validator.is_valid(bad))


if __name__ == '__main__': unittest.main()
