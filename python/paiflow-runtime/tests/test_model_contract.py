"""共享模型协议与真实Factory/中心HTTP边界的离线检查；无Provider/正式许可。"""
import asyncio
import copy
import json
from pathlib import Path
import unittest

from worker.center_client import CenterClient
from worker.engine_adapter import create_task_runner
from worker.errors import CenterUnavailable, WorkerError
from worker.model_contract import BINDING_FIELDS, check_binding, check_cancel, check_frames, json_bytes
from worker.node_context import CURRENT_ITERATION_ID
from worker.task_runner import Assignment, CURRENT_CONTEXT, RunContext
from tests.schema_support import local_validator
from tests.test_controlled_nodes import ModelCenter, model_raw, openai_frame, spark_frame
from tests.test_worker import MemoryTransport, refresh_test_canonical


CONTRACTS = Path(__file__).resolve().parents[3] / 'contracts/paiflow'
SCHEMA = CONTRACTS / 'model-operations.schema.json'


class JavaModelSchemaTest(unittest.TestCase):
    """原 Java SDK 实际请求的共享形状；运行引擎及费用仍由中心固定事实批准。"""

    @staticmethod
    def originals():
        source = CONTRACTS.parents[1] / 'ruoyi-backend/ruoyi-paiflow/src/test/resources/fixtures/java-model-wire/original-sdk.json'
        return json.loads(source.read_text(encoding='utf-8'))

    def test_original_java_nullable_zero_multiple_choice_and_length_requests(self):
        for capture in self.originals():
            with self.subTest(variant=capture['variant']):
                body = capture['actualRequest']
                for definition in ('JavaOpenAIChatOperationRequest', 'ModelOperationRequest'):
                    local_validator(SCHEMA, definition).validate(body)
                local_validator(SCHEMA, 'JavaOpenAIChatParameters').validate(body['parameters'])
                self.assertFalse(local_validator(SCHEMA, 'OpenAIChatOperationRequest').is_valid(body))

    def test_java_shape_cannot_add_public_engine_or_python_options(self):
        original = self.originals()[3]['actualRequest']
        mutations = []
        for location in ('root', 'parameters'):
            changed = copy.deepcopy(original)
            (changed if location == 'root' else changed['parameters'])['engineProfile'] = 'java'
            mutations.append(changed)
        for key, value in (('topK', 3), ('extraParams', {}), ('streamUsage', False), ('n', '2')):
            changed = copy.deepcopy(original)
            changed['parameters']['options'][key] = value
            mutations.append(changed)
        changed = copy.deepcopy(original)
        del changed['parameters']['options']['temperature']
        mutations.append(changed)
        changed = copy.deepcopy(original)
        changed['iterationId'] = 'unapproved-iteration'
        mutations.append(changed)
        for changed in mutations:
            with self.subTest(changed=changed):
                self.assertFalse(local_validator(SCHEMA, 'JavaOpenAIChatOperationRequest').is_valid(changed))
                self.assertFalse(local_validator(SCHEMA, 'ModelOperationRequest').is_valid(changed))

    def test_python_contract_keeps_its_original_options(self):
        values = parameters()
        local_validator(SCHEMA, 'OpenAIChatParameters').validate(values)
        self.assertFalse(local_validator(SCHEMA, 'JavaOpenAIChatParameters').is_valid(values))
        changed = copy.deepcopy(values)
        changed['options']['maxTokens'] = None
        self.assertFalse(local_validator(SCHEMA, 'OpenAIChatParameters').is_valid(changed))


def binding(raw, operation_id='operation-fixture'):
    return {'runId': raw['runId'], 'leaseId': raw['leaseId'], 'attempt': raw['attempt'],
        'nodeId': 'spark-llm::model', 'iterationId': None, 'workflowVersionId': raw['workflowVersionId'],
        'dslHash': raw['dslHash'], 'operationId': operation_id, 'resourceId': 'model-1',
        'resourceVersion': 'model-version-1', 'provider': 'openai'}


def parameters():
    return {'resourceId': 'model-1', 'resourceVersion': 'model-version-1', 'provider': 'openai',
        'modelName': 'model-fixture', 'mode': 'chat', 'messages': [{'role': 'user', 'content': '原消息😀'}],
        'options': {'temperature': 1.0, 'maxTokens': 2048, 'topK': 3, 'patchId': [], 'respFormat': 0,
                    'searchDisable': True, 'extraParams': {}}}


def page(raw, *, state='RUNNING', operation_state='COMPLETED', after=0, frames=None, last=3, finish=2, failure=None):
    frames = [] if frames is None else frames
    return {**binding(raw), 'state': state, 'operationState': operation_state, 'frames': copy.deepcopy(frames),
        'nextCursor': after + len(frames), 'done': state != 'RUNNING', 'lastSeq': last,
        'finishSeq': finish, 'failureKind': failure}


class ModelHttpTransport:
    """仅内存HTTP；委托原算法fixture，没有真实中心状态或Provider出口。"""
    def __init__(self, center):
        self.center, self.calls, self.mutate = center, [], None

    async def request(self, method, url, headers, raw, timeout, max_response_bytes):
        body = json.loads(raw)
        self.calls.append((method, url, body))
        run, lease, attempt = self.center.raw['runId'], body['leaseId'], body['attempt']
        if '/resources/' in url:
            data = copy.deepcopy(self.center.raw['resourceSnapshots']['model-1'])
        elif url.endswith('/operations'):
            if set(body) != {'leaseId', 'attempt', 'nodeId', 'iterationId', 'operation', 'parameters', 'idempotencyKey'} or body['iterationId'] is not None:
                raise AssertionError('model request missing exact explicit top-level binding')
            data = await ModelCenter.operation(self.center, run, lease, attempt, body['nodeId'], body['operation'], body['parameters'], body['idempotencyKey'])
        elif url.endswith('/frames'):
            data = await ModelCenter.operation_frames(self.center, run, lease, attempt, 'operation-fixture', body['afterCursor'], body['limit'])
        elif url.endswith('/cancel'):
            data = await ModelCenter.cancel_operation(self.center, run, lease, attempt, 'operation-fixture')
        else:
            raise AssertionError('unrecognized offline model route')
        if self.mutate:
            data = self.mutate(url, data)
        return 200, json_bytes({'code': 200, 'data': data})


class HttpModelCenter(ModelCenter):
    def __init__(self, raw, frames):
        super().__init__(raw, frames)
        self.transport = ModelHttpTransport(self)
        self.client = CenterClient('https://center.invalid', 'offline-service-identity', transport=self.transport)
    async def resource(self, *args):
        return await self.client.resource(*args)
    async def operation(self, *args):
        return await self.client.operation(*args)
    async def operation_frames(self, *args):
        return await self.client.operation_frames(*args)
    async def cancel_model_operation(self, *args):
        return await self.client.cancel_model_operation(*args)


class ModelContractTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.raw = model_raw(source='openai')

    def context(self, client):
        return CURRENT_CONTEXT.set(RunContext(client, Assignment.parse(self.raw), None))

    async def invoke(self, client, values=None, key='key', node='spark-llm::model'):
        return await client.operation(self.raw['runId'], self.raw['leaseId'], self.raw['attempt'], node,
                                      'model.invoke', values or parameters(), key)

    async def test_real_factory_formal_http_preserves_finish_tail_usage_and_full_binding(self):
        self.raw['dsl']['nodes'][1]['data']['nodeParam']['appId'] = ''
        refresh_test_canonical(self.raw)
        supplied = [openai_frame('原内容', status=None), openai_frame(status='stop'),
            {'choices': [], 'usage': {'prompt_tokens': 2, 'completion_tokens': 11, 'total_tokens': 13}}]
        center = HttpModelCenter(self.raw, supplied)
        await asyncio.wait_for(create_task_runner(center).run(self.raw), 8)
        self.assertEqual(center.results[-1]['state'], 'COMPLETED')
        self.assertEqual(center.results[-1]['output']['usage']['total_tokens'], 13)
        self.assertEqual(center.frames, supplied)
        calls = center.transport.calls
        invoke = next(call for call in calls if call[1].endswith('/operations'))
        self.assertIsNone(invoke[2]['iterationId'])
        self.assertIs(type(invoke[2]['parameters']['options']['temperature']), float)
        local_validator(SCHEMA, 'OpenAIChatOperationRequest').validate(invoke[2])
        local_validator(SCHEMA, 'OpenAIChatStartAck').validate({**center.bindings['operation-fixture'], 'state': 'RUNNING'})
        self.assertEqual([c[2]['afterCursor'] for c in calls if c[1].endswith('/frames')], [0, 1, 2])
        self.assertFalse(any(c[1].endswith('/cancel') for c in calls))

    async def test_spark_actual_catalog_chat_fc_capabilities_keep_chat_consumer_and_all_ack_contracts(self):
        for capabilities in (['chat'], ['chat', 'function_call']):
            raw = model_raw(source='xinghuo')
            raw['resourceSnapshots']['model-1']['capabilities'] = capabilities
            center = HttpModelCenter(raw, [spark_frame('原Spark内容')])
            await create_task_runner(center).run(raw)
            self.assertEqual('COMPLETED', center.results[-1]['state'])
            invoke = next(call for call in center.transport.calls if call[1].endswith('/operations'))
            self.assertEqual('chat', invoke[2]['parameters']['mode'])
            local_validator(SCHEMA, 'SparkChatSnapshot').validate(raw['resourceSnapshots']['model-1'])
            local_validator(SCHEMA, 'SparkChatOperationRequest').validate(invoke[2])
            self.assertFalse(local_validator(SCHEMA, 'OpenAIChatOperationRequest').is_valid(invoke[2]))
            bound = center.bindings['operation-fixture']
            local_validator(SCHEMA, 'SparkChatStartAck').validate({**bound, 'state': 'RUNNING'})
            final_page = await ModelCenter.operation_frames(center, raw['runId'], raw['leaseId'], raw['attempt'], 'operation-fixture', 0, 64)
            local_validator(SCHEMA, 'SparkChatFramesAck').validate(final_page)
            stop = await ModelCenter.cancel_operation(center, raw['runId'], raw['leaseId'], raw['attempt'], 'operation-fixture')
            local_validator(SCHEMA, 'SparkChatCancelAck').validate(stop)

    async def test_same_graph_stop_ack_cannot_substitute_another_nodes_fixed_model(self):
        from tests.test_original_engine import node
        self.raw['resourceSnapshots']['model-other'] = {**self.raw['resourceSnapshots']['model-1'], 'version': 'other-version'}
        self.raw['dsl']['nodes'].append(node('spark-llm::other', modelResourceId='model-other', source='openai', domain='model-fixture'))
        refresh_test_canonical(self.raw)
        good = {**binding(self.raw), 'state': 'CANCELLED', 'stopped': True, 'proofId': 'offline-proof'}
        transport = MemoryTransport(response={'code': 200, 'data': good})
        client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        token = self.context(client)
        try:
            CURRENT_CONTEXT.get().boundary_failure = WorkerError('DSL_INVALID', 'fixture-sticky')
            for changed in ({'resourceId': 'model-other', 'resourceVersion': 'other-version'}, {'nodeId': 'spark-llm::other'}):
                transport.response['data'] = {**good, **changed}
                with self.assertRaises(WorkerError) as caught:
                    await client.cancel_model_operation(self.raw['runId'], self.raw['leaseId'], 1, 'operation-fixture')
                self.assertEqual('VERSION_CONFLICT', caught.exception.code)
            transport.response['data'] = good
            self.assertEqual(good, await client.cancel_model_operation(self.raw['runId'], self.raw['leaseId'], 1, 'operation-fixture'))
        finally:
            CURRENT_CONTEXT.reset(token)

    async def test_model_request_exact_null_key4096_and_opaque_resource_id(self):
        values = parameters(); values['resourceId'] = 'model:17:version:29'
        self.raw['resourceSnapshots'][values['resourceId']] = copy.deepcopy(self.raw['resourceSnapshots']['model-1'])
        self.raw['dsl']['nodes'][1]['data']['nodeParam']['modelResourceId'] = values['resourceId']
        refresh_test_canonical(self.raw)
        ack = {**binding(self.raw), 'resourceId': values['resourceId'], 'state': 'RUNNING'}
        transport = MemoryTransport(response={'code': 200, 'data': ack})
        client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        token = self.context(client)
        try:
            await self.invoke(client, values, key='字' * 4096)
        finally:
            CURRENT_CONTEXT.reset(token)
        self.assertEqual(transport.calls[0][3]['iterationId'], None)
        local_validator(SCHEMA, 'OpenAIChatOperationRequest').validate(transport.calls[0][3])
        transport.response = {'code': 200, 'data': self.raw['resourceSnapshots']['model-1']}
        await client.resource('r', 'l', 1, values['resourceId'])
        self.assertTrue(transport.calls[-1][1].endswith('/resources/model%3A17%3Aversion%3A29'))

    async def test_invalid_key_body_utf8_and_json_cannot_send(self):
        client = CenterClient('https://center.invalid', 'offline-identity', transport=MemoryTransport())
        token = self.context(client)
        try:
            for key in ('', 'x' * 4097, 'embedded\ncontrol', 'embedded\u0085control'):
                with self.assertRaises(WorkerError):
                    await self.invoke(client, key=key)
            values = parameters(); values['messages'][0]['content'] = '😀' * (512 * 1024)
            with self.assertRaises(WorkerError):
                await self.invoke(client, values)
            values = parameters(); values['options']['extraParams']['temperature'] = float('nan')
            with self.assertRaises(WorkerError):
                await self.invoke(client, values)
            self.assertEqual(client._transport.calls, [])
        finally:
            CURRENT_CONTEXT.reset(token)

    async def test_spark_fc_qa_nested_history_multimodal_reject_before_http(self):
        transport = MemoryTransport(); client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        token = self.context(client)
        try:
            variants = []
            for field, value in (('provider', 'xinghuo'), ('mode', 'function_call'), ('functions', [])):
                values = parameters(); values[field] = value; variants.append((values, 'spark-llm::model'))
            variants.append((parameters(), 'question-answer::qa'))
            for content in ([{'type': 'image', 'content': 'offline'}], None):
                values = parameters(); values['messages'][0]['content'] = content; variants.append((values, 'spark-llm::model'))
            values = parameters(); values['messages'][0]['role'] = 'assistant'; variants.append((values, 'spark-llm::model'))
            for values, node in variants:
                with self.assertRaises(WorkerError):
                    await self.invoke(client, values, node=node)
            iteration = CURRENT_ITERATION_ID.set('iteration::i:0')
            try:
                with self.assertRaises(WorkerError):
                    await self.invoke(client)
            finally:
                CURRENT_ITERATION_ID.reset(iteration)
            self.assertEqual(transport.calls, [])
        finally:
            CURRENT_CONTEXT.reset(token)

    async def test_original_sdk_option_names_no_private_overrides_or_multiple_choices(self):
        transport = MemoryTransport(); client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        token = self.context(client)
        try:
            for extra in ({'base_url': 'https://untrusted.invalid'}, {'extra_headers': {}}, {'n': 2}, {'n': True}):
                values = parameters(); values['options']['extraParams'] = extra
                with self.assertRaises(WorkerError):
                    await self.invoke(client, values)
                with self.assertRaises(Exception):
                    local_validator(SCHEMA, 'OpenAIChatParameters').validate(values)
            self.assertEqual(transport.calls, [])
        finally:
            CURRENT_CONTEXT.reset(token)

    async def test_start_ack_all_common_fields_absence_type_change_or_extra_rejected(self):
        good = {**binding(self.raw), 'state': 'RUNNING'}
        transport = MemoryTransport(); client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        token = self.context(client)
        try:
            for field in BINDING_FIELDS:
                for action in ('missing', 'changed'):
                    bad = copy.deepcopy(good)
                    if action == 'missing':
                        del bad[field]
                    else:
                        bad[field] = True if field == 'attempt' else 'other'
                    if field == 'operationId' and action == 'changed':
                        bad[field] = ''  # Start才获取operationId，不能要求预先知道新ID。
                    transport.response = {'code': 200, 'data': bad}
                    with self.assertRaises(WorkerError):
                        await self.invoke(client)
            for bad in ({**good, 'extra': True}, {**good, 'state': 'COMPLETED'}):
                transport.response = {'code': 200, 'data': bad}
                with self.assertRaises(WorkerError):
                    await self.invoke(client)
        finally:
            CURRENT_CONTEXT.reset(token)

    async def test_frames_http_bound_actual_state_pagination_and_empty_final_ack(self):
        responses = [page(self.raw, frames=[openai_frame(status=None)]),
            page(self.raw, after=1, frames=[openai_frame()], finish=2),
            page(self.raw, state='COMPLETED', after=2, frames=[{'choices': [], 'usage': {'total_tokens': 5}}]),
            page(self.raw, state='COMPLETED', after=3)]
        transport = MemoryTransport(); client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        token = self.context(client)
        try:
            for cursor, expected in enumerate(responses):
                transport.response = {'code': 200, 'data': expected}
                actual = await client.operation_frames(self.raw['runId'], self.raw['leaseId'], 1, 'operation-fixture', cursor)
                self.assertEqual(actual, expected)
                local_validator(SCHEMA, 'OpenAIChatFramesAck').validate(actual)
                self.assertEqual(actual['operationState'], 'COMPLETED')
            self.assertEqual(responses[-1]['frames'], [])
        finally:
            CURRENT_CONTEXT.reset(token)

    async def test_complete_done_or_provider_failure_before_last_page_cannot_terminate(self):
        for operation_state, failure in (('COMPLETED', None), ('FAILED', 'provider')):
            good = page(self.raw, operation_state=operation_state, failure=failure,
                        frames=[openai_frame(status=None)])
            check_frames(good, binding(self.raw), 0)
            for field, value in (('state', operation_state), ('done', True), ('operationState', 'RUNNING')):
                bad = copy.deepcopy(good); bad[field] = value
                if field == 'operationState' and operation_state == 'COMPLETED':
                    continue  # 实际RUNNING+未到末页也是合法中间流。
                if field == 'operationState':
                    bad['state'], bad['done'] = 'FAILED', True
                with self.assertRaises(WorkerError):
                    check_frames(bad, binding(self.raw), 0)

    async def test_frames_all_bindings_and_nullable_fields_checked(self):
        good = page(self.raw, state='COMPLETED', after=2, frames=[openai_frame()], finish=2)
        for field in BINDING_FIELDS:
            bad = copy.deepcopy(good); bad[field] = True if field == 'attempt' else 'other'
            with self.assertRaises(WorkerError):
                check_frames(bad, binding(self.raw), 2)
        for field in ('operationState', 'finishSeq', 'lastSeq', 'failureKind'):
            bad = copy.deepcopy(good); del bad[field]
            with self.assertRaises(WorkerError):
                check_frames(bad, binding(self.raw), 2)

    async def test_frame_cursor_bool_single_frame_and_total_sequence_budgets(self):
        good = page(self.raw, frames=[openai_frame(status=None)])
        for field, value in (('nextCursor', True), ('lastSeq', True), ('finishSeq', True), ('nextCursor', 2),
                             ('lastSeq', 10001), ('finishSeq', 4), ('done', 1), ('failureKind', {})):
            bad = copy.deepcopy(good); bad[field] = value
            with self.assertRaises(WorkerError):
                check_frames(bad, binding(self.raw), 0)
        bad = page(self.raw, frames=[{'data': '😀' * 16384}])
        with self.assertRaises(WorkerError) as caught:
            check_frames(bad, binding(self.raw), 0)
        self.assertEqual(caught.exception.code, 'FRAME_BUDGET_EXCEEDED')
        with self.assertRaises(WorkerError):
            check_frames(page(self.raw, frames=[{}] * 65, last=100, finish=90), binding(self.raw), 0)

    async def test_actual_running_final_cursor_is_not_final_ack(self):
        value = page(self.raw, operation_state='RUNNING', after=3, finish=2)
        check_frames(value, binding(self.raw), 3)
        self.assertFalse(value['done'])
        value['done'] = True
        with self.assertRaises(WorkerError):
            check_frames(value, binding(self.raw), 3)

    async def test_center_lease_cancelled_budget_failure_facts_are_sticky_and_preserved(self):
        for kind in ('lease', 'cancelled', 'budget'):
            value = page(self.raw, state='FAILED', operation_state='FAILED', last=0, finish=None, failure=kind)
            check_frames(value, binding(self.raw), 0)
            local_validator(SCHEMA, 'OpenAIChatFramesAck').validate(value)
            center = HttpModelCenter(self.raw, [openai_frame('must not complete')])
            center.failure_kind = kind
            await asyncio.wait_for(create_task_runner(center).run(self.raw), 8)
            self.assertEqual(center.results[-1]['state'], 'FAILED')
            self.assertFalse(any(e['eventType'] == 'node.completed' and e.get('nodeId') == 'spark-llm::model' for e in center.events))

    async def test_invalid_usage_tail_cannot_complete_formal_factory(self):
        supplied = [openai_frame('actual', status=None), openai_frame(status='stop'), {'choices': [], 'usage': {}}]
        center = HttpModelCenter(self.raw, supplied)
        await asyncio.wait_for(create_task_runner(center).run(self.raw), 8)
        self.assertEqual(center.results[-1]['state'], 'FAILED')
        self.assertEqual(center.frames, supplied)  # 原非法帧仍原样保全，不能造token或finish。
        self.assertEqual(len(center.cancel_calls), 1)

    async def test_stop_requires_full_binding_terminal_actual_proof_not_local_close(self):
        good = {**binding(self.raw), 'state': 'CANCELLED', 'stopped': True, 'proofId': 'center-not-dispatched-proof'}
        local_validator(SCHEMA, 'OpenAIChatCancelAck').validate(good)
        check_cancel(good, binding(self.raw))
        for field in BINDING_FIELDS | {'proofId'}:
            bad = copy.deepcopy(good); del bad[field]
            with self.assertRaises(WorkerError):
                check_cancel(bad, binding(self.raw))
        for bad in ({**good, 'proofId': None}, {**good, 'state': 'RUNNING'},
                    {**good, 'state': 'EFFECT_UNKNOWN', 'stopped': False, 'proofId': None}):
            with self.assertRaises(WorkerError):
                check_cancel(bad, binding(self.raw))

    async def test_cancel_model_http_works_after_sticky_boundary_without_changing_fact(self):
        ack = {**binding(self.raw), 'state': 'CANCELLED', 'stopped': True, 'proofId': 'offline-proof'}
        transport = MemoryTransport(response={'code': 200, 'data': ack})
        client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        token = self.context(client)
        try:
            original_failure = WorkerError('DSL_INVALID', 'offline protocol failure')
            CURRENT_CONTEXT.get().boundary_failure = original_failure
            CURRENT_CONTEXT.get().active = False
            for operation in (lambda: self.invoke(client), lambda: client.operation_frames(self.raw['runId'], self.raw['leaseId'], 1, 'operation-fixture', 0)):
                with self.assertRaises(WorkerError) as blocked:
                    await operation()
                self.assertIs(blocked.exception, original_failure)
            self.assertEqual(transport.calls, [])
            result = await client.cancel_model_operation(self.raw['runId'], self.raw['leaseId'], 1, 'operation-fixture')
            self.assertEqual(result, ack)
            self.assertIs(CURRENT_CONTEXT.get().boundary_failure, original_failure)
            calls = len(transport.calls)
            with self.assertRaises(WorkerError):
                await client.cancel_model_operation('other-run', self.raw['leaseId'], 1, 'operation-fixture')
            self.assertEqual(calls, len(transport.calls))
            transport.response['data'] = {**ack, 'resourceVersion': 'wrong-version'}
            with self.assertRaises(WorkerError):
                await client.cancel_model_operation(self.raw['runId'], self.raw['leaseId'], 1, 'operation-fixture')
            transport.response['data'] = {**ack, 'state': 'EFFECT_UNKNOWN', 'stopped': False, 'proofId': None}
            with self.assertRaises(WorkerError) as caught:
                await client.cancel_model_operation(self.raw['runId'], self.raw['leaseId'], 1, 'operation-fixture')
            self.assertEqual(caught.exception.code, 'EFFECT_UNKNOWN')
            self.assertFalse(transport.response['data']['stopped'])
        finally:
            CURRENT_CONTEXT.reset(token)

    async def test_generic_nonmodel_operation_and_stop_keep_original_shape(self):
        transport = MemoryTransport(response={'code': 200, 'data': {'state': 'completed', 'output': 'original'}})
        client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        self.assertEqual(await client.operation('r', 'l', 1, 'plugin::x', 'tool.invoke', {}, 'key'), transport.response['data'])
        self.assertNotIn('iterationId', transport.calls[-1][3])
        transport.response['data'] = {'stopped': True}
        self.assertEqual(await client.cancel_operation('r', 'l', 1, 'original-operation'), {'stopped': True})

    async def test_six_public_fields_private_extra_or_bad_profile_rejected(self):
        snapshot = self.raw['resourceSnapshots']['model-1']
        transport = MemoryTransport(); client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        for bad in ({**snapshot, 'endpoint': 'https://private.invalid'}, {**snapshot, 'credentialRef': 'private-reference'},
                    {**snapshot, 'provider': 'unknown-provider'}, {**snapshot, 'capabilities': ['function_call']}, {k:v for k,v in snapshot.items() if k != 'capabilities'}):
            transport.response = {'code': 200, 'data': bad}
            with self.assertRaises(WorkerError):
                await client.resource('r', 'l', 1, 'opaque-resource')
            self.assertFalse(local_validator(SCHEMA, 'OpenAIChatSnapshot').is_valid(bad))
        # 当前Java同时保全Spark chat的六字段公开目录；不能把合法provider当私有字段。
        transport.response = {'code': 200, 'data': {**snapshot, 'provider': 'xinghuo'}}
        self.assertEqual(await client.resource('r', 'l', 1, 'opaque-resource'), transport.response['data'])
        local_validator(SCHEMA, 'ModelSnapshot').validate(transport.response['data'])

    async def test_unassembled_operation_returns503_without_retry_or_fake_response(self):
        transport = MemoryTransport(status=503)
        client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        token = self.context(client)
        try:
            with self.assertRaises(CenterUnavailable):
                await self.invoke(client)
            self.assertEqual(len(transport.calls), 1)
        finally:
            CURRENT_CONTEXT.reset(token)

    async def test_duplicate_or_nonfinite_model_response_json_rejected(self):
        class RawTransport(MemoryTransport):
            async def request(self, *args):
                self.calls.append(args)
                return 200, self.raw
        transport = RawTransport(); client = CenterClient('https://center.invalid', 'offline-identity', transport=transport)
        token = self.context(client)
        try:
            for raw in (b'{"code":200,"code":200,"data":{}}', b'{"code":200,"data":{"value":NaN}}'):
                transport.raw = raw
                with self.assertRaises(CenterUnavailable):
                    await self.invoke(client)
        finally:
            CURRENT_CONTEXT.reset(token)

    async def test_schema_draft_current_vs_complete_target_and_private_dto(self):
        document = json.loads(SCHEMA.read_text(encoding='utf-8'))
        self.assertTrue({'python V2 conversation history and Decision history', 'java nested history', 'multimodal', 'nested iteration'} <= set(document['x-not-enabled']))
        self.assertTrue(any('dynamic question-answer' in entry for entry in document['x-current-scope']))
        self.assertTrue(any('centre-bound context' in entry for entry in document['x-current-scope']))
        java = parameters()
        java['options'] = {'maxTokens': 32, 'temperature': None, 'topP': None,
            'presencePenalty': None, 'frequencyPenalty': None, 'n': None,
            'streamUsage': True, 'enableThinking': True}
        java['messages'].append({'role': 'assistant', 'content': 'original first answer'})
        local_validator(SCHEMA, 'JavaOpenAIChatParameters').validate(java)
        python_history = parameters()
        python_history['messages'].append({'role': 'assistant', 'content': 'unbound history'})
        self.assertFalse(local_validator(SCHEMA, 'OpenAIChatParameters').is_valid(python_history))
        java['messages'][-1]['content'] = [{'type': 'image_url', 'image_url': 'untrusted'}]
        self.assertFalse(local_validator(SCHEMA, 'JavaOpenAIChatParameters').is_valid(java))
        for name in ('StartAck', 'FramesAck', 'ModelParameters'):
            self.assertEqual(document['$defs'][name]['x-status'], 'target_contract_not_enabled')
        spark = parameters(); spark.update(provider='xinghuo', mode='function_call', functions=[{'name': 'original'}])
        spark['options'].update(questionType='not_knowledge', functionChoice='extractor_parameter')
        local_validator(SCHEMA, 'ModelParameters').validate(spark)
        self.assertFalse(local_validator(SCHEMA, 'OpenAIChatParameters').is_valid(spark))
        draft = Path(__file__).resolve().parents[1] / 'protocols/model-operations-draft.schema.json'
        local_validator(draft, 'ModelParameters').validate(spark)
        self.assertTrue((draft.parent / json.loads(draft.read_text(encoding='utf-8'))['x-original-draft']).resolve().exists())
        private = {'binding': binding(self.raw), 'parametersCanonicalBase64': 'e30=', 'parametersSha256': 'a' * 64,
            'optionsCanonicalBase64': 'e30=', 'deadline': self.raw['deadline']}
        local_validator(SCHEMA, 'PrivateParameters').validate(private)
        self.assertFalse(local_validator(SCHEMA, 'PrivateParameters').is_valid({**private, 'endpoint': 'https://private.invalid'}))

    async def test_openapi_shared_refs_and_no_private_grant_http(self):
        api = json.loads((CONTRACTS / 'internal.openapi.json').read_text(encoding='utf-8'))
        def model(name): return {'$ref': 'model-operations.schema.json#/$defs/' + name}
        def tool(name): return {'$ref': 'tool-operations.schema.json#/$defs/' + name}
        def data(name): return {'$ref': 'data-operations.schema.json#/$defs/' + name}
        suffixes = {'/resources/{resourceId}': (model('LeaseRequest'), {'oneOf': [model('ChatSnapshot'), tool('ToolResource'), data('DataResource')]}),
            '/operations': ({'oneOf': [model('ModelOperationRequest'), tool('ToolOperationRequest'), data('DataOperationRequest')]},
                {'oneOf': [model('ModelStartAck'), tool('ToolOperationAck'), data('DataOperationAck')]}),
            '/operations/{operationId}/frames': (model('FramesRequest'), model('ModelFramesAck')),
            '/operations/{operationId}/cancel': ({'anyOf': [model('LeaseRequest'), tool('ToolCancelRequest')]},
                {'oneOf': [model('ModelCancelAck'), tool('ToolCancelAck')]}),
            '/operations/{operationId}/status': (tool('ToolStatusRequest'), tool('ToolOperationAck'))}
        for suffix, definitions in suffixes.items():
            post = api['paths']['/internal/paiflow/v1/runs/{runId}' + suffix]['post']
            request = post['requestBody']['content']['application/json']['schema']
            ack = post['responses']['200']['content']['application/json']['schema']['properties']['data']
            self.assertEqual((request, ack), definitions)
        self.assertFalse(any('grant' in p.lower() for p in api['paths']))
        for document in (SCHEMA, CONTRACTS / 'worker.schema.json', CONTRACTS / 'events.schema.json', CONTRACTS / 'checkpoints.schema.json',
                CONTRACTS / 'tool-operations.schema.json', CONTRACTS / 'data-operations.schema.json'):
            from jsonschema import Draft202012Validator
            Draft202012Validator.check_schema(json.loads(document.read_text(encoding='utf-8')))
