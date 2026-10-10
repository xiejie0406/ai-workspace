"""正式工具消费者/完整scope边界；协议夹具不证明Java或真实供应商。"""
import asyncio
import base64
import copy
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.parse import unquote

from jsonschema import Draft202012Validator
from worker.engine_adapter import create_task_runner
from worker.errors import WorkerError
from worker.model_contract import json_bytes
from worker.tool_contract import check_resource, check_ack, check_parameters
from tests.test_agent_link_consumer import LinkHttpCenter, LinkTransport, link_raw
from tests.test_worker import refresh_test_canonical

SCHEMA = json.loads((Path(__file__).resolve().parents[3] / 'contracts/paiflow/tool-operations.schema.json').read_text(encoding='utf-8'))
CAPTURE = []


def validate(name, value):
    Draft202012Validator({'$ref': '#/$defs/' + name, '$defs': SCHEMA['$defs']}).validate(value)


def formal_raw(effect='read', operation='lookup'):
    raw = link_raw(effect)
    suffix = base64.urlsafe_b64encode(operation.encode()).decode().rstrip('=')
    identifier = 'tool:7:version:77:operation:' + suffix
    resource = raw['resourceSnapshots'].pop('capability')
    resource['version'], resource['operationId'] = '77', operation
    resource['openApiSchema']['paths']['/fixed']['get']['operationId'] = operation
    raw['resourceSnapshots'][identifier] = resource
    node = raw['dsl']['nodes'][1]['data']['nodeParam']
    node['toolResourceId'], node['operationId'] = identifier, operation
    refresh_test_canonical(raw)
    return raw


class FormalTransport(LinkTransport):
    async def request(self, method, url, headers, encoded, timeout, max_response_bytes):
        center = self.center
        if url.endswith('/operations'):
            body = json.loads(encoded)
            validate('ToolOperationRequest', body)
            self.calls.append((url, copy.deepcopy(body)))
            center.operations.append(copy.deepcopy(body))
            p = body['parameters']
            center.binding = {'runId': center.raw['runId'], 'leaseId': body['leaseId'], 'attempt': body['attempt'],
                'workflowVersionId': center.raw['workflowVersionId'], 'dslHash': center.raw['dslHash'], 'nodeId': body['nodeId'],
                'iterationId': body['iterationId'], 'idempotencyKey': body['idempotencyKey'],
                'operationId': 'tool-op:11111111-2222-3333-4444-555555555555', 'operation': body['operation'],
                **{k: copy.deepcopy(p[k]) for k in ('kind', 'provider', 'resourceId', 'resourceVersion', 'resourceBindings')}}
            if center.lose_start:
                return 503, b''
            data = center.ack(center.start_state)
            if center.duplicate_json:
                raw = json.dumps({'code': 200, 'data': data}).replace('"attempt": 1', '"attempt": 1, "attempt": 2')
                return 200, raw.encode()
        elif url.endswith('/status') or url.endswith('/cancel'):
            body = json.loads(encoded)
            self.calls.append((url, copy.deepcopy(body)))
            validate('ToolStatusRequest', body)
            if url.endswith('/status'):
                center.status_calls += 1
                if center.status_wait is not None:
                    center.status_wait.set()
                    await asyncio.Event().wait()
                data = center.ack(center.status_states.pop(0) if center.status_states else 'COMPLETED')
            else:
                center.cancel_calls += 1
                data = center.ack('CANCELLED' if center.stop_proof else 'EFFECT_UNKNOWN')
                data.update(stopped=center.stop_proof, proofId='not-dispatched:' + data['operationId'] if center.stop_proof else None)
        else:
            return await super().request(method, url, headers, encoded, timeout, max_response_bytes)
        if center.operation_mutate:
            data = center.operation_mutate(url, data)
        center.returned_acks.append({'url': url, 'data': copy.deepcopy(data)})
        return 200, json_bytes({'code': 200, 'data': data})


class FormalCenter(LinkHttpCenter):
    def __init__(self, raw=None):
        super().__init__(raw or formal_raw())
        self.transport = FormalTransport(self)
        self.client._transport = self.transport
        self.start_state, self.status_states = 'COMPLETED', []
        self.status_wait, self.binding = None, None
        self.status_calls = self.cancel_calls = 0
        self.lose_start = self.duplicate_json = self.stop_proof = False
        self.operation_mutate = None
        self.returned_acks = []

    def ack(self, state):
        failure = 'provider' if state == 'FAILED' else 'cancelled' if state in {'CANCELLED', 'EFFECT_UNKNOWN'} else None
        return {**copy.deepcopy(self.binding), 'state': state, 'failureKind': failure,
            'legacyResponse': copy.deepcopy(self.recorded_response) if state in {'COMPLETED', 'FAILED'} else None,
            'stopped': False, 'proofId': None}

    async def tool_status(self, *args, **kwargs): return await self.client.tool_status(*args, **kwargs)
    async def cancel_tool_operation(self, *args, **kwargs): return await self.client.cancel_tool_operation(*args, **kwargs)


class ToolContractTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def tearDownClass(cls):
        path = os.environ.get('PAIFLOW_TOOL_CAPTURE_FILE')
        if path:
            Path(path).write_text(json.dumps({'scope': '实际原Factory/CenterClient；正式Tool协议夹具，未运行Java/Provider/DB',
                'cases': CAPTURE}, ensure_ascii=False, indent=2), encoding='utf-8')

    async def execute(self, center):
        claimed = await center.client.claim()
        await asyncio.wait_for(create_task_runner(center, heartbeat_seconds=.02).run(claimed), 8)
        CAPTURE.append({'test': self._testMethodName, 'assignment': copy.deepcopy(center.raw),
            'requests': copy.deepcopy(center.operations), 'acks': copy.deepcopy(center.returned_acks),
            'completion': copy.deepcopy(center.results), 'httpRequests': copy.deepcopy(center.transport.calls)})
        return center

    async def test_actual_factory_formal_sync_wire_matches_schema_and_original_output(self):
        center = await self.execute(FormalCenter())
        self.assertEqual(center.results[-1]['state'], 'COMPLETED')
        self.assertEqual(center.results[-1]['output']['outputs'], {'answer': '原插件答', 'count': 0})
        self.assertEqual(len(center.operations), 1)
        self.assertEqual(center.status_calls + center.cancel_calls, 0)
        validate('ToolOperationRequest', center.operations[0])
        validate('ToolOperationAck', center.ack('COMPLETED'))
        self.assertIsNone(center.operations[0]['iterationId'])
        message = center.operations[0]['parameters']['legacyPayload']['payload']['message']
        self.assertEqual(json.loads(base64.b64decode(message['path'])), {'business': '原透传'})

    async def test_running_only_polls_same_operation_without_replaying_provider(self):
        center = FormalCenter()
        center.start_state, center.status_states = 'RUNNING', ['RUNNING', 'COMPLETED']
        await self.execute(center)
        self.assertEqual(center.results[-1]['state'], 'COMPLETED')
        self.assertEqual(len(center.operations), 1)
        self.assertEqual(center.status_calls, 2)
        operation_urls = [unquote(url) for url, _ in center.transport.calls if url.endswith('/status')]
        self.assertTrue(all('/' + center.binding['operationId'] + '/status' in url for url in operation_urls))

    async def test_formal_provider_failure_preserves_original_error_and_stops_end(self):
        from workflow.exception.errors.err_code import CodeEnum
        center = FormalCenter()
        center.start_state = 'FAILED'
        center.recorded_response = {'header': {'code': 1003, 'message': '原失败'}}
        await self.execute(center)
        self.assertEqual(center.results[-1]['state'], 'FAILED')
        failed = [e for e in center.events if e['eventType'] == 'node.failed']
        self.assertEqual(failed[-1]['payload']['legacyFrame']['code'], CodeEnum.PLUGIN_NODE_EXECUTION_ERROR.code)
        self.assertFalse(any(e['eventType'] == 'node.started' and e['nodeId'] == 'node-end::e' for e in center.events))

    async def test_full_scope_drift_after_write_dispatch_is_unknown_without_cross_scope_cancel(self):
        for key, wrong in [('runId', 'other'), ('leaseId', 'other'), ('attempt', True), ('workflowVersionId', 'other'),
                ('dslHash', 'f' * 64), ('nodeId', 'plugin::other'), ('iterationId', 'forged'),
                ('resourceId', 'tool:9:version:77:operation:bG9va3Vw'), ('idempotencyKey', 'other'), ('operation', 'tools.read')]:
            with self.subTest(field=key):
                center = FormalCenter(formal_raw('write'))
                center.operation_mutate = lambda url, data: {**data, key: wrong}
                await self.execute(center)
                self.assertEqual(center.results[-1]['state'], 'EFFECT_UNKNOWN')
                self.assertEqual(len(center.operations), 1)
                self.assertEqual(center.cancel_calls, 0)

    async def test_unknown_field_and_duplicate_json_ack_fail_closed(self):
        for duplicate in (False, True):
            with self.subTest(duplicate=duplicate):
                center = FormalCenter(formal_raw('write'))
                center.duplicate_json = duplicate
                if not duplicate: center.operation_mutate = lambda url, data: {**data, 'untrusted': 'extra'}
                await self.execute(center)
                self.assertEqual(center.results[-1]['state'], 'EFFECT_UNKNOWN')
                self.assertEqual(len(center.operations), 1)

    async def test_lost_start_write_ack_is_unknown_and_never_retried(self):
        center = FormalCenter(formal_raw('write'))
        center.lose_start = True
        await self.execute(center)
        self.assertEqual(center.results[-1]['state'], 'EFFECT_UNKNOWN')
        self.assertEqual(len(center.operations), 1)
        self.assertEqual(center.status_calls + center.cancel_calls, 0)

    async def test_center_read_effect_unknown_remains_sticky_unknown(self):
        center = FormalCenter()
        center.start_state = 'EFFECT_UNKNOWN'
        await self.execute(center)
        self.assertEqual(center.results[-1]['state'], 'EFFECT_UNKNOWN')
        self.assertEqual(center.results[-1]['error']['code'], 'EFFECT_UNKNOWN')
        self.assertEqual(len(center.operations), 1)
        self.assertEqual(center.cancel_calls, 1)

    async def test_write_cancel_requires_center_proof_and_full_binding(self):
        for proof in (False, True):
            with self.subTest(proof=proof):
                center = FormalCenter(formal_raw('write'))
                center.start_state, center.status_wait, center.stop_proof = 'RUNNING', asyncio.Event(), proof
                task = asyncio.create_task(self.execute(center))
                await asyncio.wait_for(center.status_wait.wait(), 8)
                center.cancel_requested = True
                await asyncio.wait_for(task, 8)
                self.assertEqual(center.results[-1]['state'], 'CANCELLED' if proof else 'EFFECT_UNKNOWN')
                self.assertEqual(len(center.operations), 1)
                self.assertEqual(center.cancel_calls, 1)
                cancel = center.ack('CANCELLED' if proof else 'EFFECT_UNKNOWN')
                cancel.update(stopped=proof, proofId='not-dispatched:' + cancel['operationId'] if proof else None)
                validate('ToolCancelAck', cancel)

    async def test_status_drift_cancels_only_original_bound_operation(self):
        center = FormalCenter(formal_raw('write'))
        center.start_state = 'RUNNING'
        center.operation_mutate = lambda url, data: {**data, 'operationId': 'tool-op:aaaaaaaa-2222-3333-4444-555555555555'} if url.endswith('/status') else data
        await self.execute(center)
        self.assertEqual(center.results[-1]['state'], 'EFFECT_UNKNOWN')
        self.assertEqual(center.cancel_calls, 1)
        cancel_url = next(url for url, _ in center.transport.calls if url.endswith('/cancel'))
        self.assertIn(center.binding['operationId'], unquote(cancel_url))

    async def test_long_original_operation_id_preserves_utf8_resource_binding(self):
        center = FormalCenter(formal_raw(operation='查询' * 60))
        await self.execute(center)
        self.assertEqual(center.results[-1]['state'], 'COMPLETED')
        self.assertGreater(len(center.binding['resourceId']), 256)
        check_resource(center.binding['resourceId'], center.raw['resourceSnapshots'][center.binding['resourceId']])
        validate('ToolResourceId', center.binding['resourceId'])

    async def test_formal_tool_preflight_rejects_scope_and_policy_before_execution_permission(self):
        for change in ('old-codec', 'unreferenced', 'wrong-node-operation', 'write-no-approval'):
            with self.subTest(change=change):
                raw = formal_raw('write' if change == 'write-no-approval' else 'read')
                if change == 'old-codec': raw['executionCodec'] = 'paiflow.execution.v1'
                elif change == 'unreferenced': raw['dsl']['nodes'][1]['data']['nodeParam']['toolResourceId'] = 'not-pinned'
                elif change == 'wrong-node-operation': raw['dsl']['nodes'][1]['data']['nodeParam']['operationId'] = 'other'
                else: raw['effectPolicy'] = {'mode': 'read_only', 'allowedOperations': ['tools.read']}
                refresh_test_canonical(raw)
                center = FormalCenter(raw)
                with self.assertRaises(WorkerError): await self.execute(center)
                self.assertFalse(any(url.endswith('/execution/begin') for url, _ in center.transport.calls))
                self.assertEqual(center.operations, [])
                self.assertEqual(center.events, [])
                self.assertEqual(center.results, [])

    def test_schema_business_names_and_defaults_are_precise_not_global_credential_exemptions(self):
        raw = formal_raw()
        identifier = raw['dsl']['nodes'][1]['data']['nodeParam']['toolResourceId']
        resource = raw['resourceSnapshots'][identifier]
        schema = resource['openApiSchema']['paths']['/fixed']['get']['requestBody']['content']['application/json']['schema']
        schema['properties']['apiKey'] = {'type': 'string', 'default': {'apiKey': '业务字面值'}}
        check_resource(identifier, resource)
        validate('ToolResource', resource)
        schema['apiKey'] = '实际未知认证注解'
        with self.assertRaises(WorkerError): check_resource(identifier, resource)
        schema.pop('apiKey')
        resource['openApiSchema']['apiSecret'] = '不应公开'
        with self.assertRaises(WorkerError): check_resource(identifier, resource)

    def test_formal_descriptor_rejects_unknown_material_and_wrong_encoded_operation(self):
        raw = formal_raw()
        identifier = raw['dsl']['nodes'][1]['data']['nodeParam']['toolResourceId']
        resource = raw['resourceSnapshots'][identifier]
        with self.assertRaises(WorkerError): check_resource(identifier, {**resource, 'credentials': 'material'})
        with self.assertRaises(WorkerError): check_resource(identifier.replace('bG9va3Vw', 'b3RoZXI'), resource)
        for place in ('root', 'path', 'operation'):
            with self.subTest(private_target=place):
                descriptor = copy.deepcopy(resource)
                schema = descriptor['openApiSchema']
                target = schema if place == 'root' else schema['paths']['/fixed'] if place == 'path' else schema['paths']['/fixed']['get']
                target['servers'] = [{'url': 'https://private.invalid'}]
                with self.assertRaises(WorkerError): check_resource(identifier, descriptor)

    def test_request_decoded_fields_cannot_move_position_or_carry_credentials(self):
        raw = formal_raw()
        identifier = raw['dsl']['nodes'][1]['data']['nodeParam']['toolResourceId']
        resource = raw['resourceSnapshots'][identifier]
        base = {'legacyPayload': {'header': {'app_id': resource['legacyAppId']}, 'parameter': {
            'tool_id': resource['pluginId'], 'version': resource['legacyVersion'], 'operation_id': resource['operationId']},
            'payload': {'message': {}}}}
        for location, value in [('header', {'question': '错位'}), ('body', {'unknown': '额外'}), ('query', {'apiKey': '材料'})]:
            with self.subTest(location=location):
                data = copy.deepcopy(base)
                data['legacyPayload']['payload']['message'][location] = base64.b64encode(json.dumps(value).encode()).decode()
                with self.assertRaises(WorkerError): check_parameters(data, resource)


if __name__ == '__main__': unittest.main()
