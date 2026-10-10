"""新历史端口与原Factory的技术夹具；不代替中心Mapper/Provider/页面验收。"""
import asyncio
import copy
import hashlib
import json
from pathlib import Path
import unittest
import jsonschema

from worker.center_client import CenterClient
from worker.engine_adapter import create_task_runner
from worker.errors import CenterUnavailable, WorkerError
from worker.events import EventPublisher
from worker.history import add_history
from worker.node_context import CURRENT_ITERATION_ID
from worker.python_history import (canonical, read_snapshot, validate_ack, check_model_messages,
                                   check_completed_inputs, require_snapshot)
from worker.task_runner import Assignment, RunContext, CURRENT_CONTEXT
from tests.test_worker import MemoryTransport, refresh_test_canonical
from tests.test_controlled_nodes import ModelCenter, model_raw, spark_frame, openai_frame

NODE = 'spark-llm::model'
CONTEXT = {'flowId': 'formal-flow', 'userId': 'ruoyi:7', 'appId': 'debug:9', 'chatId': '会话甲'}


def snapshot_hash(value):
    # 独立按正式sorted/UTF8定义生成回执，不调用被测消费者的canonical/hash。
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':'), allow_nan=False).encode('utf-8')).hexdigest()


def payload(raw, history=None):
    snapshot = {'runId': raw['runId'], 'leaseId': raw['leaseId'], 'attempt': raw['attempt'],
        'workflowVersionId': int(raw['workflowVersionId']), 'dslHash': raw['dslHash'], 'spaceId': 0,
        'ownerUserId': 7, 'workflowId': 9, **CONTEXT, 'inputHash': 'a' * 64,
        'history': copy.deepcopy(history or []), 'historyV2': []}
    return {'history': copy.deepcopy(snapshot['history']), 'historyV2': [], 'historyRef': 'python-history:fixed',
            'historyHash': snapshot_hash(snapshot), 'snapshot': snapshot}


def history(question='旧问', answer='旧答'):
    return [{'nodeID': NODE, 'chat_history': [{'role': 'user', 'content': question}, {'role': 'assistant', 'content': answer}]}]


class HistoryCenter(ModelCenter):
    def __init__(self, raw, frames, records=None):
        super().__init__(raw, frames)
        self.records = records if records is not None else []
        self.history_reads = []
        self.history_writes = []
        self.reject_history = False
        self.timeline = []

    async def verify_lease(self, *args):
        value = await super().verify_lease(*args)
        value['executionContext'] = copy.deepcopy(CONTEXT)
        return value

    async def read_python_history(self, *args):
        self.history_reads.append(args)
        self.timeline.append('history.read')
        pairs = [message for pair in self.records[-10:] for message in pair]
        return payload(self.raw, [{'nodeID': NODE, 'chat_history': pairs}] if pairs else [])

    async def append_history(self, run, lease, attempt, node, question, answer, *, iteration_id=None):
        self.history_writes.append((run, lease, attempt, node, copy.deepcopy(question), copy.deepcopy(answer), iteration_id))
        self.timeline.append('history.append')
        if self.reject_history:
            raise CenterUnavailable()
        self.records.append([copy.deepcopy(question), copy.deepcopy(answer)])
        return {'stored': True, 'revision': 1}

    async def append_event(self, run, event):
        self.timeline.append(event['nodeId'] + ':' + event['eventType'])
        return await super().append_event(run, event)


def raw(source='xinghuo'):
    result = model_raw(source=source, node_params={'enableChatHistory': True})
    result['workflowVersionId'] = '51'
    refresh_test_canonical(result)
    return result


def context(center, value):
    task = Assignment.parse(value)
    result = RunContext(center, task, EventPublisher(center, task.run_id, task.lease_id, task.attempt, task.last_event_seq))
    result.execution_context = copy.deepcopy(CONTEXT)
    return result


class PythonHistoryConsumerTest(unittest.IsolatedAsyncioTestCase):
    async def test_formal_read_posts_only_lease_and_attempt_and_retains_unicode_snapshot(self):
        value = raw()
        ack = payload(value, history('换行\n😀', '原答'))
        schema = json.loads((Path(__file__).resolve().parents[3] / 'contracts/paiflow/python-history.schema.json').read_text(encoding='utf-8-sig'))
        schema['$ref'] = '#/$defs/ReadAck'
        jsonschema.Draft202012Validator(schema).validate(ack)
        transport = MemoryTransport(response={'code': 200, 'data': ack})
        client = CenterClient('https://centre.invalid', 'test-token', transport=transport)
        ctx = context(client, value)
        self.assertEqual(await read_snapshot(ctx), ack)
        call = transport.calls[0]
        self.assertEqual(call[0:2], ('POST', 'https://centre.invalid/internal/paiflow/v1/runs/run-1/history/python/read'))
        self.assertEqual(call[3], {'leaseId': value['leaseId'], 'attempt': value['attempt']})
        self.assertEqual(call[2]['Authorization'], 'Bearer test-token')
        self.assertEqual(len(transport.calls), 1)

    async def test_once_read_and_independent_private_snapshot_under_concurrent_factory_reads(self):
        value = raw()
        center = HistoryCenter(value, [spark_frame('答')], [[{'role': 'user', 'content': '问'}, {'role': 'assistant', 'content': '答'}]])
        ctx = context(center, value)
        first, second = await asyncio.gather(read_snapshot(ctx), read_snapshot(ctx))
        first['history'][0]['chat_history'][0]['content'] = '不能污染许可'
        self.assertEqual(second['history'][0]['chat_history'][0]['content'], '问')
        self.assertEqual((await read_snapshot(ctx))['history'], second['history'])
        self.assertEqual(len(center.history_reads), 1)

    def test_wrong_scope_hash_or_top_projection_rejects_complete_response(self):
        value = raw(); ctx = context(HistoryCenter(value, []), value)
        for name in ('runId', 'leaseId', 'attempt', 'workflowVersionId', 'dslHash', 'flowId', 'userId', 'appId', 'chatId', 'ownerUserId'):
            with self.subTest(name=name):
                ack = payload(value, history()); ack['snapshot'][name] = 2 if name in ('attempt', 'ownerUserId') else 'different'
                ack['historyHash'] = snapshot_hash(ack['snapshot'])
                self.assertRaises(WorkerError, validate_ack, ctx, ack)
        for mutation in ('hash', 'projection', 'extra', 'boolean-attempt'):
            with self.subTest(mutation=mutation):
                ack = payload(value, history())
                if mutation == 'hash': ack['historyHash'] = '0' * 64
                elif mutation == 'projection': ack['history'] = []
                elif mutation == 'extra': ack['snapshot']['extra'] = None
                else: ack['snapshot']['attempt'] = True
                if mutation != 'hash': ack['historyHash'] = snapshot_hash(ack['snapshot'])
                self.assertRaises(WorkerError, validate_ack, ctx, ack)
        for version in ('51', True, 51.0, 0, 9223372036854775808):
            with self.subTest(version=version):
                ack = payload(value, history()); ack['snapshot']['workflowVersionId'] = version
                ack['historyHash'] = snapshot_hash(ack['snapshot'])
                self.assertRaises(WorkerError, validate_ack, ctx, ack)
        for ref in ('x' * 129, 'x\n'):
            ack = payload(value); ack['historyRef'] = ref
            self.assertRaises(WorkerError, validate_ack, ctx, ack)
        for chat in (None, 'x' * 129):
            ack = payload(value); ack['snapshot']['chatId'] = chat
            ack['historyHash'] = snapshot_hash(ack['snapshot'])
            ctx.execution_context['chatId'] = chat
            self.assertRaises(WorkerError, validate_ack, ctx, ack)
        ctx.execution_context['chatId'] = ''
        ack = payload(value); ack['snapshot']['chatId'] = ''
        ack['historyHash'] = snapshot_hash(ack['snapshot'])
        self.assertEqual(validate_ack(ctx, ack)['snapshot']['chatId'], '')

    def test_deleted_disabled_duplicate_nodes_wrong_role_and_twenty_message_bound(self):
        value = raw(); ctx = context(HistoryCenter(value, []), value)
        long = history()[0]; long['chat_history'] *= 10
        self.assertEqual(len(validate_ack(ctx, payload(value, [long]))['history'][0]['chat_history']), 20)
        for entries in ([*history(), *history()], [{'nodeID': 'spark-llm::deleted', 'chat_history': []}],
                        [{'nodeID': 'node-start::s', 'chat_history': []}],
                        [{'nodeID': NODE, 'chat_history': [{'role': 'assistant', 'content': '错序'}, {'role': 'user', 'content': '错序'}]}],
                        [{'nodeID': NODE, 'chat_history': [{'role': 'user', 'content': '半轮'}]}],
                        [{'nodeID': NODE, 'chat_history': long['chat_history'] + history()[0]['chat_history']}]):
            self.assertRaises(WorkerError, validate_ack, ctx, payload(value, entries))
        value['dsl']['nodes'][1]['data']['nodeParam']['enableChatHistory'] = False; refresh_test_canonical(value)
        self.assertRaises(WorkerError, validate_ack, context(HistoryCenter(value, []), value), payload(value, history()))

    def test_129_distinct_fixed_history_nodes_are_legal_but_1001_exceeds_centre_row_budget(self):
        for count in (129, 1001):
            with self.subTest(count=count):
                value=raw(); original=copy.deepcopy(value['dsl']['nodes'][1]); entries=[]
                value['dsl']['nodes']=[value['dsl']['nodes'][0],value['dsl']['nodes'][-1]]
                for index in range(count):
                    node=copy.deepcopy(original);node['id']=NODE if index==0 else f'spark-llm::history-{index}'
                    value['dsl']['nodes'].insert(-1,node)
                    item=history()[0];item['nodeID']=node['id'];entries.append(item)
                refresh_test_canonical(value)
                ctx=context(HistoryCenter(value,[]),value);ack=payload(value,entries)
                if count==129:
                    self.assertEqual(len(validate_ack(ctx,ack)['history']),129)
                    schema=json.loads((Path(__file__).resolve().parents[3]/'contracts/paiflow/python-history.schema.json').read_text(encoding='utf-8-sig'))
                    schema['$ref']='#/$defs/ReadAck'
                    jsonschema.Draft202012Validator(schema).validate(ack)
                else:
                    self.assertRaises(WorkerError,validate_ack,ctx,ack)

    async def test_duplicate_json_bytes_and_ack_budget_are_rejected_by_actual_transport_client(self):
        value = raw()
        class DuplicateTransport(MemoryTransport):
            async def request(self, *args):
                return 200, b'{"code":200,"data":{"history":[],"history":[],"historyV2":[],"historyRef":"r","historyHash":"x","snapshot":{}}}'
        client = CenterClient('https://centre.invalid', 'test-token', transport=DuplicateTransport())
        with self.assertRaises(WorkerError): await read_snapshot(context(client, value))
        ack = payload(value, history('x' * (1800 * 1024), 'a'))
        client = CenterClient('https://centre.invalid', 'test-token', transport=MemoryTransport(response={'code': 200, 'data': ack}), max_response_bytes=4*1024*1024)
        with self.assertRaises(WorkerError): await read_snapshot(context(client, value))

    async def test_nonempty_v2_iteration_cancel_and_unfrozen_append_cannot_read_or_write(self):
        value=raw(); center=HistoryCenter(value, []); ctx=context(center,value)
        ack=payload(value);ack['historyV2']=[{'role':'user','content':'V2'}];ack['snapshot']['historyV2']=copy.deepcopy(ack['historyV2'])
        ack['historyHash']=snapshot_hash(ack['snapshot'])
        self.assertRaises(WorkerError,validate_ack,ctx,ack)
        token=CURRENT_CONTEXT.set(ctx)
        try:
            with self.assertRaises(WorkerError): await add_history(CONTEXT['flowId'],NODE,CONTEXT['userId'],{'role':'user','content':'a'},{'role':'assistant','content':'b'})
            self.assertEqual(center.history_writes,[])
            ctx.boundary_failure=None
            iteration=CURRENT_ITERATION_ID.set('iteration::i:1')
            try:
                with self.assertRaises(WorkerError): await read_snapshot(ctx)
            finally: CURRENT_ITERATION_ID.reset(iteration)
            ctx.active=False
            with self.assertRaises(WorkerError): await read_snapshot(ctx)
            self.assertEqual(center.history_reads,[])
        finally: CURRENT_CONTEXT.reset(token)

    async def test_cached_display_keeps_original_spark_text_field_and_cannot_enter_model_messages(self):
        value=raw();center=HistoryCenter(value,[]);ctx=context(center,value)
        center.records.extend([[{'role':'user','content':'旧问'},{'role':'assistant','content':'旧答'}]])
        await read_snapshot(ctx)
        display=[{'role':'user','content':'旧问','content_type':'text'},{'role':'assistant','content':'旧答','content_type':'text'}]
        check_completed_inputs(ctx,NODE,{'chatHistory':display})
        self.assertRaises(WorkerError,check_completed_inputs,ctx,NODE,{'chatHistory':history()[0]['chat_history']})
        self.assertRaises(WorkerError,check_completed_inputs,ctx,NODE,{'chatHistory':[]})
        check_model_messages(ctx,NODE,[{'role':'system','content':'固定'},{'role':'user','content':'当前'}])
        self.assertRaises(WorkerError,check_model_messages,ctx,NODE,[{'role':'user','content':'旧问'},{'role':'assistant','content':'旧答'},{'role':'user','content':'当前'}])

    async def test_original_factory_two_rounds_both_providers_keep_current_wire_append_before_completion(self):
        for provider in ('xinghuo','openai'):
            records=[]
            for ordinal in range(2):
                value=raw(provider);value['runId']=f'run-{provider}-{ordinal}'
                frames=[spark_frame('原答案')] if provider=='xinghuo' else [openai_frame('原答案')]
                center=HistoryCenter(value,frames,records)
                await asyncio.wait_for(create_task_runner(center).run(value),8)
                self.assertEqual(center.results[-1]['state'],'COMPLETED',center.results[-1])
                self.assertEqual(center.operations[0][5]['messages'],[{'role':'system','content':'original system'},{'role':'user','content':'ask=真实算法输入'}])
                self.assertEqual(center.history_writes[0][4:6],({'role':'user','content':'ask=真实算法输入'},{'role':'assistant','content':'原答案'}))
                self.assertLess(center.timeline.index('history.append'),center.timeline.index(NODE+':node.completed'))
                completed=next(e for e in center.events if e['nodeId']==NODE and e['eventType']=='node.completed')
                inputs=completed['payload']['legacyFrame']['workflow_step']['node']['inputs']
                if ordinal: self.assertEqual(inputs['chatHistory'],[{'role':'user','content':'ask=真实算法输入','content_type':'text'},{'role':'assistant','content':'原答案','content_type':'text'}])
                else: self.assertNotIn('chatHistory',inputs)
                self.assertEqual(len(center.history_reads),1)

    async def test_original_provider_failure_does_not_create_python_half_round(self):
        value=raw();center=HistoryCenter(value,[spark_frame(code=10000)]);center.failure_kind='provider'
        await asyncio.wait_for(create_task_runner(center).run(value),8)
        self.assertEqual(center.results[-1]['state'],'FAILED')
        self.assertEqual(center.history_writes,[]);self.assertEqual(center.records,[])
        self.assertFalse(any(e['nodeId']==NODE and e['eventType']=='node.completed' for e in center.events))

    async def test_original_raw_false_strings_freeze_empty_window_but_append_on_success(self):
        for flag in ('false', '0'):
            with self.subTest(flag=flag):
                value=raw();value['dsl']['nodes'][1]['data']['nodeParam']['enableChatHistory']=flag
                refresh_test_canonical(value)
                center=HistoryCenter(value,[spark_frame('原答案')])
                await asyncio.wait_for(create_task_runner(center).run(value),8)
                self.assertEqual(center.results[-1]['state'],'COMPLETED')
                self.assertEqual(len(center.history_reads),1)
                self.assertEqual(len(center.history_writes),1)
                self.assertLess(center.timeline.index('history.read'),center.timeline.index('history.append'))
                self.assertEqual(center.operations[0][5]['messages'],[{'role':'system','content':'original system'},
                    {'role':'user','content':'ask=真实算法输入'}])
                event=next(e for e in center.events if e['nodeId']==NODE and e['eventType']=='node.completed')
                self.assertNotIn('chatHistory',event['payload']['legacyFrame']['workflow_step']['node']['inputs'])
                # raw追加不能授予typed已关闭的旧窗口读取。
                self.assertRaises(WorkerError,validate_ack,context(center,value),payload(value,history()))

    async def test_original_append_rejection_prevents_completed_callback_and_success_result(self):
        value=raw();center=HistoryCenter(value,[spark_frame('原答案')]);center.reject_history=True
        with self.assertRaises(WorkerError): await asyncio.wait_for(create_task_runner(center).run(value),8)
        self.assertEqual(center.records,[]);self.assertEqual(len(center.history_writes),1)
        self.assertFalse(any(e['nodeId']==NODE and e['eventType']=='node.completed' for e in center.events))
        self.assertEqual(center.results,[])

    async def test_original_cancel_and_unconfirmed_stop_never_write_python_half_round(self):
        for confirmed in (True, False):
            value=raw();center=HistoryCenter(value,[spark_frame('不得保存')]);center.frame_wait=asyncio.Event()
            center.stop_confirmed=confirmed
            task=asyncio.create_task(create_task_runner(center,heartbeat_seconds=.02).run(value))
            await asyncio.wait_for(center.frame_wait.wait(),5)
            center.cancel_requested=True
            await asyncio.wait_for(task,8)
            self.assertEqual(center.results[-1]['state'],'CANCELLED' if confirmed else 'EFFECT_UNKNOWN')
            self.assertEqual(center.history_writes,[]);self.assertEqual(center.records,[])
            self.assertFalse(any(e['nodeId']==NODE and e['eventType']=='node.completed' for e in center.events))

    async def test_missing_context_and_changed_append_scope_reject_before_history_port(self):
        value=raw();center=HistoryCenter(value,[]);ctx=context(center,value);ctx.execution_context=None
        with self.assertRaises(WorkerError):await read_snapshot(ctx)
        self.assertEqual(center.history_reads,[])
        ctx.execution_context=copy.deepcopy(CONTEXT);await read_snapshot(ctx);token=CURRENT_CONTEXT.set(ctx)
        try:
            for flow,uid,chat,node in [('wrong',CONTEXT['userId'],None,NODE),(CONTEXT['flowId'],'wrong',None,NODE),
                (CONTEXT['flowId'],CONTEXT['userId'],'wrong',NODE),(CONTEXT['flowId'],CONTEXT['userId'],None,'node-start::s')]:
                with self.assertRaises(WorkerError):
                    await add_history(flow,node,uid,{'role':'user','content':'a'},{'role':'assistant','content':'b'},chat_id=chat)
                ctx.boundary_failure=None
            self.assertEqual(center.history_writes,[])
        finally:CURRENT_CONTEXT.reset(token)
