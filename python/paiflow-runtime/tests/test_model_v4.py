"""V4真实Factory/中心HTTP消费者；golden来自独立原算法采集，无Provider调用。"""
import asyncio
import copy
import json
import os
from pathlib import Path
import unittest
from urllib.parse import unquote
from jsonschema import Draft202012Validator

from worker.center_client import CenterClient
from worker.engine_adapter import create_task_runner
from worker.errors import CenterUnavailable, WorkerError
from worker.model_contract import json_bytes
from worker.task_runner import Assignment
from tests import test_controlled_nodes as original_model
from tests import test_question_answer as original_qa
from tests.schema_support import local_validator
from tests.test_worker import refresh_test_canonical
from tests.test_original_engine import node, output, ref, edge


ROOT = Path(__file__).resolve().parents[3]
GOLDEN = {case['name']: case for case in json.loads((Path(__file__).parent / 'fixtures/model-v4-algorithm-golden.json').read_text(encoding='utf-8'))['cases']}
CAPTURE = []
VALIDATORS = {}


def validate_model(definition, value):
    if definition not in VALIDATORS:
        VALIDATORS[definition] = local_validator(ROOT / 'contracts/paiflow/model-operations.schema.json', definition)
    VALIDATORS[definition].validate(value)


def v4(raw):
    raw['executionCodec'] = 'paiflow.execution.v4'
    for snapshot in raw['resourceSnapshots'].values():
        snapshot['capabilities'] = ['chat', 'function_call'] if snapshot['provider'] == 'xinghuo' else ['chat']
    refresh_test_canonical(raw)
    # 中心RunJsonCodec.canonicalFull对整个assignment使用TreeMap；领取JSON解析后遵守同顺序。
    return json.loads(json.dumps(raw, ensure_ascii=False, sort_keys=True, separators=(',', ':')))


class V4HttpTransport:
    def __init__(self, center):
        self.center, self.calls = center, []
        self.mutate = None

    async def request(self, method, url, headers, encoded, timeout, max_response_bytes):
        body = json.loads(encoded)
        self.calls.append((url, copy.deepcopy(body)))
        center = self.center
        if url.endswith('/runs/claim'):
            return 200, json_bytes({'code': 200, 'data': copy.deepcopy(center.raw)})
        args = center.raw['runId'], body['leaseId'], body['attempt']
        if url.endswith('/lease/verify'):
            data = await original_model.ModelCenter.verify_lease(center, *args)
        elif url.endswith('/heartbeat'):
            data = await original_model.ModelCenter.heartbeat(center, *args)
        elif url.endswith('/execution/begin'):
            data = await original_model.ModelCenter.begin_execution(center, *args)
        elif url.endswith('/events'):
            data = await original_model.ModelCenter.append_event(center, args[0], body)
        elif url.endswith('/result'):
            data = await original_model.ModelCenter.complete(center, args[0], body)
        elif '/resources/' in url:
            data = await original_model.ModelCenter.resource(center, *args, unquote(url.split('/resources/')[1]))
        elif url.endswith('/operations'):
            if set(body) != {'leaseId', 'attempt', 'nodeId', 'iterationId', 'operation', 'parameters', 'idempotencyKey'} or body['iterationId'] is not None:
                raise AssertionError('完整顶层模型请求缺少绑定')
            validate_model('ModelOperationRequest', body)
            validate_model('V4ModelParameters', body['parameters'])
            op_args = (*args, body['nodeId'], body['operation'], body['parameters'], body['idempotencyKey'])
            center.operations.append(copy.deepcopy(op_args))
            operation_id = 'v4-operation-' + str(len(center.operations))
            if center.reject_model:
                raise CenterUnavailable()
            if center.operation_wait is not None:
                center.operation_wait.set()
                await asyncio.Event().wait()
            data = {**center.binding(op_args, operation_id), 'state': 'RUNNING'}
            validate_model('ModelStartAck', data)
            center.operation_frames_by_id[operation_id] = center.response_groups[len(center.operations) - 1]
        elif '/operations/' in url and url.endswith('/frames'):
            operation_id = url.split('/operations/')[1].split('/')[0]
            center.frames = center.operation_frames_by_id[operation_id]
            data = await original_model.ModelCenter.operation_frames(center, *args, operation_id, body['afterCursor'], body['limit'])
            validate_model('ModelFramesAck', data)
        elif '/operations/' in url and url.endswith('/cancel'):
            operation_id = url.split('/operations/')[1].split('/')[0]
            data = await original_model.ModelCenter.cancel_operation(center, *args, operation_id)
            validate_model('ModelCancelAck', data)
        elif url.endswith('/checkpoints'):
            data = await original_qa.CheckpointCenter.create_checkpoint(center, *args, body['nodeId'], body['ordinal'],
                body['question'], body['needReply'], body['timeoutSeconds'], body['idempotencyKey'], iteration_id=body.get('iterationId'))
        elif '/checkpoints/' in url and url.endswith('/resume/poll'):
            checkpoint_id = url.split('/checkpoints/')[1].split('/')[0]
            data = await original_qa.CheckpointCenter.poll_checkpoint(center, *args, checkpoint_id, body['afterRevision'])
        elif '/checkpoints/' in url and url.endswith('/consume'):
            checkpoint_id = url.split('/checkpoints/')[1].split('/')[0]
            data = await original_qa.CheckpointCenter.consume_checkpoint(center, *args, checkpoint_id, body['expectedRevision'], body['idempotencyKey'])
        else:
            raise AssertionError('未声明的离线HTTP路径')
        if self.mutate:
            data = self.mutate(url, data)
        return 200, json_bytes({'code': 200, 'data': data})


class V4HttpCenter(original_qa.CheckpointCenter):
    def __init__(self, raw, response_groups, responses=None):
        super().__init__(raw, responses=responses)
        self.response_groups = response_groups
        self.operation_frames_by_id = {}
        self.transport = V4HttpTransport(self)
        self.client = CenterClient('https://center.invalid', 'offline-service-identity', transport=self.transport)

    async def resource(self, *args): return await self.client.resource(*args)
    async def verify_lease(self, *args): return await self.client.verify_lease(*args)
    async def heartbeat(self, *args): return await self.client.heartbeat(*args)
    async def begin_execution(self, *args): return await self.client.begin_execution(*args)
    async def append_event(self, *args): return await self.client.append_event(*args)
    async def complete(self, *args): return await self.client.complete(*args)
    async def operation(self, *args): return await self.client.operation(*args)
    async def operation_frames(self, *args): return await self.client.operation_frames(*args)
    async def cancel_model_operation(self, *args): return await self.client.cancel_model_operation(*args)
    async def create_checkpoint(self, *args, **kwargs): return await self.client.create_checkpoint(*args, **kwargs)
    async def poll_checkpoint(self, *args): return await self.client.poll_checkpoint(*args)
    async def consume_checkpoint(self, *args): return await self.client.consume_checkpoint(*args)


class ModelV4ConsumerTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def tearDownClass(cls):
        capture_file = os.environ.get('PAIFLOW_V4_CAPTURE_FILE')
        if capture_file:
            Path(capture_file).write_text(json.dumps({'scope': 'actual original Factory and CenterClient requests; isolated HTTP fixture, no real centre/provider', 'cases': CAPTURE}, ensure_ascii=False, indent=2), encoding='utf-8')

    async def execute(self, raw, frames, responses=None):
        center = V4HttpCenter(raw, frames, responses)
        claimed = await center.client.claim()
        await asyncio.wait_for(create_task_runner(center, heartbeat_seconds=.02).run(claimed), 8)
        self.assertEqual(center.results[-1]['state'], 'COMPLETED', center.results[-1])
        self.assertEqual(len(center.begin_calls), 1)
        return center

    def compare_original(self, name, center):
        expected = GOLDEN[name]
        parameters = [call[5] for call in center.operations]
        without_checkpoint = [{key: value for key, value in item.items() if key != 'checkpointId'} for item in parameters]
        self.assertEqual(without_checkpoint, expected['parameters'] if isinstance(expected['parameters'], list) else [expected['parameters']])
        actual = center.results[-1]['output']
        self.assertEqual(actual['outputs'], expected['output']['outputs'])
        self.assertEqual(actual['usage'], expected['output']['usage'])
        CAPTURE.append({'name': name, 'assignment': copy.deepcopy(center.raw), 'parameters': parameters,
            'checkpoints': copy.deepcopy(center.checkpoint_calls), 'output': actual})

    async def test_pe_prompt_both_providers_keep_exact_prompt_schema_and_output(self):
        for source in ('xinghuo', 'openai'):
            with self.subTest(source=source):
                raw = v4(original_model.model_raw('extractor-parameter', source=source, schema_type='integer', node_params={'reasonMode': 1}))
                frame = original_model.spark_frame if source == 'xinghuo' else original_model.openai_frame
                center = await self.execute(raw, [[frame('```json\n{"answer":"9"}\n```')]])
                self.compare_original('pe-prompt-' + source, center)

    async def test_pe_fc_default_and_custom_schema_preserve_original_functions(self):
        for custom in (False, True):
            with self.subTest(custom=custom):
                params = {'fc_schema_params': {'type': 'object', 'properties': {'tag': {'type': 'string'}}, 'required': ['tag']}} if custom else {}
                raw = v4(original_model.model_raw('extractor-parameter', schema_type='integer', node_params=params))
                center = await self.execute(raw, [[original_model.spark_frame(function_call={'name': 'extractor_parameter', 'arguments': '{"answer":"11","tag":"fixed"}'})]])
                self.compare_original('pe-fc-' + ('custom' if custom else 'default'), center)

    async def test_canonical_custom_pe_prompt_and_object_decision_inputs_match_independent_source(self):
        for source in ('xinghuo', 'openai'):
            for name, kind, response in [('pe-prompt-custom-' + source, 'extractor-parameter', '```json\n{"answer":"9"}\n```'),
                    ('decision-prompt-object-' + source, 'decision-making', '{"category_name":"accept"}'),
                    ('decision-normal-object-' + source, 'decision-making', '{"destination":"accept","next_inputs":"value"}')]:
                with self.subTest(name=name):
                    expected = GOLDEN[name]
                    raw = original_model.model_raw(kind, source=source)
                    raw['dsl'] = copy.deepcopy(expected['dsl'])
                    raw['input'] = copy.deepcopy(expected['input'])
                    frame = original_model.spark_frame if source == 'xinghuo' else original_model.openai_frame
                    center = await self.execute(v4(raw), [[frame(response)]])
                    self.compare_original(name, center)

    async def test_decision_prompt_and_normal_both_providers_preserve_branch(self):
        for source in ('xinghuo', 'openai'):
            for mode, response, name in [({'reasonMode': 1}, '{"category_name":"accept"}', 'prompt'),
                    ({'useFunctionCall': False}, '{"destination":"accept","next_inputs":"value"}', 'normal')]:
                with self.subTest(source=source, mode=name):
                    raw = original_model.OriginalModelNodeTest().decision_raw(**mode)
                    raw['dsl']['nodes'][1]['data']['nodeParam']['source'] = source
                    raw['resourceSnapshots']['model-1']['provider'] = source
                    frame = original_model.spark_frame if source == 'xinghuo' else original_model.openai_frame
                    center = await self.execute(v4(raw), [[frame(response)]])
                    self.compare_original('decision-' + name + '-' + source, center)
                    self.assertFalse(any(event['nodeId'] == 'text-joiner::fallback' and event['eventType'] == 'node.started' for event in center.events))

    async def test_decision_fc_default_and_custom_schema_keep_empty_choice(self):
        for custom in (False, True):
            params = {'fs_params': {'type': 'object', 'properties': {'scope': {'type': 'string'}}, 'required': ['scope']}} if custom else {}
            raw = v4(original_model.OriginalModelNodeTest().decision_raw(**params))
            center = await self.execute(raw, [[original_model.spark_frame(function_call={'name': 'accept', 'arguments': '{"scope":"fixed","next_inputs":"value"}'})]])
            self.compare_original('decision-fc-' + ('custom' if custom else 'default'), center)
            self.assertEqual(center.operations[0][5]['options']['functionChoice'], '')

    async def test_dynamic_qa_two_rounds_bind_actual_consumed_checkpoints_and_history(self):
        raw = v4(original_qa.OriginalQuestionAnswerTest().slot_raw())
        frames = [[original_model.spark_frame('{"completed":{},"incomplete":{"age":null},"content":"请补充年龄"}')],
            [original_model.spark_frame('```json\n{"completed":{"age":"8"},"incomplete":{}}\n```')]]
        center = await self.execute(raw, frames, [{'event_type': 'resume', 'content': '还没年龄'}, {'event_type': 'resume', 'content': '年龄8'}])
        self.compare_original('dynamic-qa-two-rounds-offline-algorithm', center)
        self.assertEqual([call[5]['checkpointId'] for call in center.operations], ['checkpoint-1', 'checkpoint-2'])
        self.assertNotEqual(center.operations[0][-1], center.operations[1][-1])
        self.assertEqual([call[0][4] for call in center.checkpoint_calls], [1, 2])

    async def test_v3_controlled_qa_and_missing_fc_grant_refuse_before_begin(self):
        for codec in (None, 'paiflow.execution.v3'):
            raw = v4(original_qa.OriginalQuestionAnswerTest().slot_raw())
            if codec is None: raw.pop('executionCodec')
            else: raw['executionCodec'] = codec
            center = V4HttpCenter(raw, [])
            with self.assertRaises(WorkerError): await create_task_runner(center).run(raw)
            self.assertEqual(center.begin_calls, [])
            self.assertTrue(all(url.endswith('/lease/verify') for url, _ in center.transport.calls))
        raw = v4(original_model.model_raw('extractor-parameter'))
        raw['resourceSnapshots']['model-1']['capabilities'] = ['chat']
        center = V4HttpCenter(raw, [])
        with self.assertRaises(WorkerError): await create_task_runner(center).run(raw)
        self.assertEqual(center.begin_calls, [])

    async def test_codec_schema_and_complete_assignment_binding(self):
        raw = v4(original_model.model_raw('extractor-parameter', node_params={'reasonMode': 1}))
        Draft202012Validator(json.loads((ROOT / 'contracts/paiflow/worker.schema.json').read_text(encoding='utf-8'))).validate(raw)
        for invalid in (None, 4, 'paiflow.execution.v5'):
            bad = copy.deepcopy(raw); bad['executionCodec'] = invalid
            with self.assertRaises(WorkerError): Assignment.parse(bad)
        center = V4HttpCenter(raw, [])
        copied = copy.deepcopy(raw); copied['executionCodec'] = 'paiflow.execution.v3'
        with self.assertRaises(WorkerError) as failure: await create_task_runner(center).run(copied)
        self.assertEqual(failure.exception.code, 'VERSION_CONFLICT')
        self.assertEqual(center.begin_calls, [])

    async def test_dynamic_cancel_before_operation_ack_keeps_unknown(self):
        raw = v4(original_model.model_raw('extractor-parameter', node_params={'reasonMode': 1}))
        center = V4HttpCenter(raw, [])
        center.operation_wait = asyncio.Event()
        task = asyncio.create_task(create_task_runner(center, heartbeat_seconds=.02).run(raw))
        await asyncio.wait_for(center.operation_wait.wait(), 5)
        center.cancel_requested = True
        await asyncio.wait_for(task, 8)
        self.assertEqual(center.results[-1]['state'], 'EFFECT_UNKNOWN')
        self.assertEqual(len(center.operations), 1)
        self.assertEqual(center.cancel_calls, [])

    async def test_acknowledged_provider_error_preserves_all_decision_default_variants(self):
        for mode in ({}, {'reasonMode': 1}, {'useFunctionCall': False}):
            raw = v4(original_model.OriginalModelNodeTest().decision_raw(**mode))
            center = V4HttpCenter(raw, [[original_model.spark_frame(code=10003)]])
            center.failure_kind = 'provider'
            await asyncio.wait_for(create_task_runner(center).run(raw), 8)
            self.assertEqual(center.results[-1]['state'], 'COMPLETED')
            self.assertEqual(center.results[-1]['output']['outputs'], {'chosen': '', 'fallback': 'fallback'})
            self.assertEqual(len(center.operations), 1)
            self.assertEqual(center.cancel_calls, [])

    async def test_dynamic_qa_original_retry_limit_and_ignore_abort_never_invent_model_reply(self):
        raw = v4(original_qa.OriginalQuestionAnswerTest().slot_raw(max_rounds=1))
        center = V4HttpCenter(raw, [[original_model.spark_frame('{"completed":{},"incomplete":{"age":null}}')]])
        await asyncio.wait_for(create_task_runner(center).run(raw), 8)
        self.assertEqual(center.results[-1]['state'], 'FAILED')
        self.assertEqual(len(center.checkpoint_calls), 1)
        self.assertEqual(len(center.operations), 1)
        failures = [event for event in center.events if event['eventType'] == 'node.failed']
        self.assertEqual(failures[-1]['payload']['legacyFrame']['code'], 23803)
        for event_type in ('ignore', 'abort'):
            raw = v4(original_qa.OriginalQuestionAnswerTest().slot_raw())
            center = V4HttpCenter(raw, [], [{'event_type': event_type, 'content': '原回复内容'}])
            await asyncio.wait_for(create_task_runner(center).run(raw), 8)
            self.assertEqual(center.results[-1]['state'], 'FAILED')
            self.assertEqual(center.operations, [])
            self.assertEqual(center.resource_calls, [])

    async def test_unbound_history_and_multimodal_remain_denied_before_permission(self):
        for params in ({'reasonMode': 1, 'enableChatHistoryV2': {'isEnabled': True}},
                {'reasonMode': 1, 'enableChatHistory': True}, {'reasonMode': 1, 'domain': 'image'}):
            raw = v4(original_model.model_raw('extractor-parameter', node_params=params))
            if params.get('domain'): raw['resourceSnapshots']['model-1']['modelName'] = params['domain']
            center = V4HttpCenter(raw, [])
            with self.assertRaises(WorkerError): await create_task_runner(center).run(raw)
            self.assertEqual(center.begin_calls, [])
            self.assertTrue(all(url.endswith('/lease/verify') for url, _ in center.transport.calls))

    async def test_original_function_schema_field_names_are_business_data_not_provider_credentials(self):
        schema = {'type': 'object', 'properties': {'password': {'type': 'string', 'default': '业务默认值'},
            'apiKey': {'type': 'string'}, 'token': {'type': 'string'}}, 'required': []}
        raw = v4(original_model.model_raw('extractor-parameter', schema_type='integer', node_params={'fc_schema_params': schema}))
        center = await self.execute(raw, [[original_model.spark_frame(function_call={'name': 'extractor_parameter', 'arguments': '{"answer":11}'})]])
        self.assertEqual(center.operations[0][5]['functions'][0]['parameters']['properties']['password'], schema['properties']['password'])
        from worker.events import _validate_payload
        for payload in ({'password': '真实凭据必须拒绝'}, {'functions': [{'password': '真实认证字段'}]},
                {'functions': [{'parameters': {'properties': {'password': {'type': 'string'}}}}]}):
            with self.assertRaises(WorkerError): _validate_payload(payload)

    def test_schema_exemption_is_exact_and_does_not_disable_credentials_or_budgets(self):
        from worker.events import _validate_payload
        allowed_path = frozenset({('functions', 0, 'parameters')})
        allowed = {'functions': [{'parameters': {'type': 'object', 'properties': {'apiKey': {'type': 'object',
            'properties': {'password': {'type': 'string'}}, 'default': {'password': '业务默认值'}}},
            'default': {'authorization': '业务默认值'}, '$defs': {'secretKey': {'type': 'string'}}}}]}
        _validate_payload(allowed, schema_paths=allowed_path)
        denied = [
            {'functions': [{'parameters': {}, 'apiKey': 'Provider认证字段'}]},
            {'functions': [{'parameters': {}, 'provider': {'apiSecret': 'Provider认证字段'}}]},
            {'functions': [{'parameters': {'apiKey': '非法schema根认证字段'}}]},
            {'functions': [{'parameters': {'allOf': [{'authorization': '非法schema认证扩展'}]}}]},
            {'functions': [{'parameters': {}}, {'parameters': {'default': {'password': '未登记schema域'}}}]},
            {'options': {'extraParams': {'functions': [{'parameters': {'default': {'password': '错误嵌套域'}}}]}}},
            {'legacyFrame': {'payload': allowed}},
            {'functions': [{'parameters': {42: '无效键'}}]},
        ]
        for payload in denied:
            with self.subTest(payload=payload), self.assertRaises(WorkerError):
                _validate_payload(payload, schema_paths=allowed_path)
        nested = {'password': '业务默认值'}
        for _ in range(66): nested = {'nested': nested}
        with self.assertRaises(WorkerError) as failure:
            _validate_payload({'functions': [{'parameters': {'default': nested}}]}, schema_paths=allowed_path)
        self.assertEqual(failure.exception.code, 'DSL_INVALID')

    async def test_v4_decision_multiple_end_branches_keep_original_selected_output(self):
        for mode, frame in [({}, original_model.spark_frame(function_call={'name': 'accept', 'arguments': '{"next_inputs":"value"}'})),
                ({'reasonMode': 1}, original_model.spark_frame('{"category_name":"accept"}')),
                ({'useFunctionCall': False}, original_model.spark_frame('{"destination":"accept","next_inputs":"value"}'))]:
            raw = original_model.OriginalModelNodeTest().decision_raw(**mode)
            raw['dsl']['nodes'][2] = node('node-end::accept', inputs=[ref('chosen', 'text-joiner::accept', 'value')], outputMode=0)
            raw['dsl']['nodes'].append(node('node-end::fallback', inputs=[ref('fallback', 'text-joiner::fallback', 'value')], outputMode=0))
            raw['dsl']['edges'][-2:] = [edge('text-joiner::accept', 'node-end::accept'), edge('text-joiner::fallback', 'node-end::fallback')]
            center = await self.execute(v4(raw), [[frame]])
            self.assertEqual(center.results[-1]['output']['outputs'], {'chosen': 'selected'})
            self.assertFalse(any(event['nodeId'] == 'node-end::fallback' for event in center.events))


if __name__ == '__main__':
    unittest.main()
