"""原Factory+CenterClient的Link草案消费及Agent真实准入缺口；不证明Java Producer。"""
import asyncio
import base64
import copy
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator

from worker.engine_adapter import create_task_runner
from worker.model_contract import json_bytes
from tests.test_model_v4 import V4HttpCenter, V4HttpTransport
from tests.test_remaining_nodes import capability_raw
from tests.test_worker import refresh_test_canonical


CAPTURE = []
DRAFT = json.loads((Path(__file__).resolve().parents[1] / 'protocols/remaining-capabilities-draft.schema.json').read_text(encoding='utf-8'))
REQUEST = Draft202012Validator({'$ref': '#/$defs/OperationRequest', '$defs': DRAFT['$defs']})
COMPLETED = Draft202012Validator({'$ref': '#/$defs/CompletedAck', '$defs': DRAFT['$defs']})


class LinkTransport(V4HttpTransport):
    """仅回放既有原Link响应格式；其余HTTP控制路径复用已检查设施。"""
    async def request(self, method, url, headers, encoded, timeout, max_response_bytes):
        if not url.endswith('/operations'):
            return await super().request(method, url, headers, encoded, timeout, max_response_bytes)
        body = json.loads(encoded)
        self.calls.append((url, copy.deepcopy(body)))
        REQUEST.validate(body)
        center = self.center
        center.operations.append(copy.deepcopy(body))
        if center.operation_wait is not None:
            center.operation_wait.set()
            await asyncio.Event().wait()
        parameters = body['parameters']
        data = {key: copy.deepcopy(parameters[key]) for key in
                ('kind', 'provider', 'resourceId', 'resourceVersion', 'resourceBindings')}
        data.update(state='FAILED' if center.provider_failed else 'COMPLETED', legacyResponse=copy.deepcopy(center.recorded_response))
        if center.provider_failed:
            data['failureKind'] = 'provider'
        COMPLETED.validate(data)
        return 200, json_bytes({'code': 200, 'data': data})


class LinkHttpCenter(V4HttpCenter):
    def __init__(self, raw):
        super().__init__(raw, [])
        self.provider_failed = False
        # 记录的原协议响应只核消费者行为；没有运行Java生产器或供应商。
        self.recorded_response = {'header': {'code': 0, 'message': ''},
                                  'payload': {'text': {'text': '{"answer":"原插件答"}'}}}
        self.transport = LinkTransport(self)
        self.client._transport = self.transport


def link_raw(effect='read'):
    raw = capability_raw('plugin')
    raw['executionCodec'] = 'paiflow.execution.v4'
    raw['resourceSnapshots']['capability']['effectClass'] = effect
    return raw


class AgentLinkConsumerTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def tearDownClass(cls):
        destination = os.environ.get('PAIFLOW_AGENT_LINK_CAPTURE_FILE')
        if destination:
            Path(destination).write_text(json.dumps({'scope': 'Original Factory + actual CenterClient HTTP consumer; recorded Link envelopes; no Java Producer, supplier or business DB; Agent missing admission preserved',
                'cases': CAPTURE}, ensure_ascii=False, indent=2), encoding='utf-8')

    def capture(self, name, center):
        CAPTURE.append({'name': name, 'assignment': copy.deepcopy(center.raw),
            'requests': copy.deepcopy(center.operations), 'completion': copy.deepcopy(center.results),
            'httpRequests': copy.deepcopy(center.transport.calls)})

    async def execute(self, raw, center=None):
        center = center or LinkHttpCenter(raw)
        claimed = await center.client.claim()
        await asyncio.wait_for(create_task_runner(center, heartbeat_seconds=.02).run(claimed), 8)
        return center

    async def test_link_original_factory_full_http_read_preserves_base64_and_defaults(self):
        raw = link_raw()
        raw['effectPolicy'] = {'mode': 'read_only', 'allowedOperations': ['tools.read']}
        center = await self.execute(raw)
        self.assertEqual(center.results[-1]['state'], 'COMPLETED')
        self.assertEqual(center.results[-1]['output']['outputs'], {'answer': '原插件答', 'count': 0})
        request = center.operations[0]
        self.assertEqual(request['operation'], 'tools.read')
        self.assertEqual(request['nodeId'], 'plugin::cap')
        self.assertEqual(request['parameters']['legacyPayload']['parameter'],
            {'tool_id': 'legacy-tool', 'version': 'V1.0', 'operation_id': 'lookup'})
        message = request['parameters']['legacyPayload']['payload']['message']
        for key, expected in [('query', {'question': '原查询'}), ('path', {'business': '原透传'}), ('body', {'defaulted': '保留默认'})]:
            self.assertEqual(json.loads(base64.b64decode(message[key])), expected)
        self.assertEqual(len(center.operations), 1)
        self.capture('link-read-original-wire', center)

    async def test_link_confirmed_provider_error_keeps_original_node_code_and_stops_graph(self):
        from workflow.exception.errors.err_code import CodeEnum
        center = LinkHttpCenter(link_raw())
        center.provider_failed = True
        center.recorded_response = {'header': {'code': 1003, 'message': '原Provider失败'}}
        await self.execute(center.raw, center)
        self.assertEqual(center.results[-1]['state'], 'FAILED')
        failed = [event for event in center.events if event['eventType'] == 'node.failed']
        self.assertEqual(failed[-1]['payload']['legacyFrame']['code'], CodeEnum.PLUGIN_NODE_EXECUTION_ERROR.code)
        self.assertFalse(any(event['eventType'] == 'node.started' and event['nodeId'] == 'node-end::e' for event in center.events))
        self.capture('link-confirmed-provider-error', center)

    async def test_link_write_cancel_before_ack_preserves_unknown_and_never_replays(self):
        center = LinkHttpCenter(link_raw('write'))
        center.operation_wait = asyncio.Event()
        execution = asyncio.create_task(self.execute(center.raw, center))
        await asyncio.wait_for(center.operation_wait.wait(), 8)
        center.cancel_requested = True
        await asyncio.wait_for(execution, 8)
        self.assertEqual(center.results[-1]['state'], 'EFFECT_UNKNOWN')
        self.assertEqual(center.operations[0]['operation'], 'tools.invoke')
        self.assertEqual(len(center.operations), 1)
        self.capture('link-write-unconfirmed-cancel', center)

    async def test_link_write_cannot_be_dispatched_under_read_only_policy(self):
        raw = link_raw('write')
        raw['effectPolicy'] = {'mode': 'read_only', 'allowedOperations': ['tools.read']}
        center = await self.execute(raw)
        self.assertEqual(center.results[-1]['state'], 'FAILED')
        self.assertEqual(center.results[-1]['error']['code'], 'TOOL_POLICY_DENIED')
        self.assertEqual(center.operations, [])
        self.capture('link-write-no-approval', center)

    async def test_link_resource_drift_is_rejected_before_any_operation(self):
        center = LinkHttpCenter(link_raw())
        def mutate(url, data):
            return {**data, 'legacyVersion': 'V2.0'} if '/resources/' in url else data
        center.transport.mutate = mutate
        await self.execute(center.raw, center)
        self.assertEqual(center.results[-1]['state'], 'FAILED')
        self.assertEqual(center.results[-1]['error']['code'], 'VERSION_CONFLICT')
        self.assertEqual(center.operations, [])
        self.capture('link-resource-version-drift', center)

    async def test_agent_worker_original_builder_keeps_current_model_admission_gap(self):
        raw = capability_raw('agent')
        raw['executionCodec'] = 'paiflow.execution.v4'
        raw['effectPolicy']['allowedOperations'].append('model.invoke')
        raw['resourceSnapshots']['model']['capabilities'] = ['chat']
        raw['resourceSnapshots']['capability'].update(executionMode='worker-original', agentModelOptions={
            'temperature': .5, 'maxTokens': 100, 'topK': 2, 'patchId': [], 'respFormat': 0,
            'searchDisable': True, 'extraParams': {}})
        refresh_test_canonical(raw)
        from worker.agent_factory import consume_bound_agent
        original_failures = []
        attempted_model_requests = []
        center = LinkHttpCenter(raw)
        actual_operation = center.client.operation
        async def observe_operation(run_id, lease_id, attempt, node_id, operation, parameters, idempotency_key):
            attempted_model_requests.append({'runId': run_id, 'leaseId': lease_id, 'attempt': attempt,
                'nodeId': node_id, 'operation': operation, 'parameters': copy.deepcopy(parameters),
                'idempotencyKey': idempotency_key})
            return await actual_operation(run_id, lease_id, attempt, node_id, operation, parameters, idempotency_key)
        async def observe_original(*args, **kwargs):
            try:
                return await consume_bound_agent(*args, **kwargs)
            except Exception as error:
                original_failures.append({'type': type(error).__name__, 'message': str(error)})
                raise
        with patch('worker.agent_factory.consume_bound_agent', observe_original), \
                patch.object(center.client, 'operation', observe_operation):
            await self.execute(raw, center)
        self.capture('agent-original-builder-model-not-admitted', center)
        CAPTURE[-1]['originalFailures'] = original_failures
        CAPTURE[-1]['attemptedModelRequests'] = attempted_model_requests
        self.assertEqual(center.results[-1]['state'], 'FAILED')
        self.assertEqual(center.results[-1]['error']['code'], 'ENGINE_UNSUPPORTED', original_failures)
        self.assertEqual(center.operations, [])
        self.assertEqual(center.results[-1]['error']['message'], '当前执行器不支持该节点')
        self.assertEqual(original_failures, [{'type': 'PluginExc', 'message': '40303: Model request failed'}])
        self.assertEqual(len(attempted_model_requests), 1)
        attempted = attempted_model_requests[0]
        self.assertEqual(attempted['nodeId'], 'agent::cap')
        self.assertEqual(attempted['operation'], 'model.invoke')
        self.assertEqual(attempted['parameters']['mode'], 'chat')
        self.assertEqual(attempted['parameters']['resourceId'], raw['dsl']['nodes'][1]['data']['nodeParam']['modelResourceId'])
        self.assertEqual(attempted['parameters']['options'], raw['resourceSnapshots']['capability']['agentModelOptions'])
        self.assertTrue(attempted['parameters']['messages'])


if __name__ == '__main__':
    unittest.main()
