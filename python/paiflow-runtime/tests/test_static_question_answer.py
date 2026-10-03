"""顶层静态 QA 的许可前边界和真实原 Factory 回归；只有隔离中心 fixture。"""
import asyncio
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import AsyncMock, patch

from worker.engine_adapter import OriginalWorkflowEngine, create_task_runner, _CenterFrames
from worker.errors import WorkerError
from worker.events import EventPublisher
from worker.static_question_answer import validate_assignment
from worker.task_runner import Assignment, CURRENT_CONTEXT, RunContext
from tests.test_original_engine import node, output, ref, edge, raw_workflow
from tests.test_question_answer import CheckpointCenter, qa_raw
from tests.test_worker import refresh_test_canonical

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = json.loads((ROOT.parents[1] / 'contracts/paiflow/fixtures/static-question-answer.json').read_text(encoding='utf-8'))


def fixed(dsl=None, supplied=None):
    raw = raw_workflow(copy.deepcopy(dsl or FIXTURE['execution']), [], copy.deepcopy(FIXTURE['input'] if supplied is None else supplied))
    raw['dsl'] = copy.deepcopy(dsl or FIXTURE['execution'])
    raw['resourceSnapshots'] = {}
    raw['effectPolicy'] = {'mode': 'read_only', 'allowedOperations': []}
    refresh_test_canonical(raw)
    return raw


def paths(raw, count):
    dsl = raw['dsl']
    dsl['edges'] = [edge('node-start::s', 'question-answer::qa')]
    start = current = 'question-answer::qa'
    for index, bit in enumerate(bin(count)[3:]):
        left, right, merge = ['message::path' + str(index) + suffix for suffix in ('L', 'R', 'M')]
        dsl['nodes'].extend(node(identifier, template='', streamOutput=False) for identifier in (left, right, merge))
        dsl['edges'].extend([edge(current, left), edge(current, right), edge(left, merge), edge(right, merge)])
        if bit == '1':
            dsl['edges'].append(edge(start, merge))
        current = merge
    dsl['edges'].append(edge(current, 'node-end::e'))
    refresh_test_canonical(raw)


class StaticQuestionAdmissionTest(unittest.IsolatedAsyncioTestCase):
    async def reject(self, raw, code):
        refresh_test_canonical(raw)
        center = CheckpointCenter(raw)
        runner = create_task_runner(center)
        runner.engine.execute = AsyncMock(side_effect=AssertionError('engine must not run'))
        with patch('workflow.engine.dsl_engine.WorkflowEngineFactory.create_engine', side_effect=AssertionError('Factory must not run')) as factory:
            with self.assertRaises(WorkerError) as error:
                await runner.run(raw)
        self.assertEqual(error.exception.code, code)
        self.assertEqual(factory.call_count, 0)
        self.assertEqual(runner.engine.execute.call_count, 0)
        self.assertEqual(center.begin_calls, [])
        self.assertEqual(center.results, [])
        self.assertEqual(center.events, [])
        self.assertEqual(center.operations, [])
        self.assertEqual(center.resource_calls, [])
        self.assertEqual(center.checkpoint_calls, [])

    def test_shared_original_typed_118_cases(self):
        from workflow.engine.nodes.question_answer.question_answer_node import QuestionAnswerNode
        for case in FIXTURE['coercionCases']:
            with self.subTest(case=case['id']):
                try:
                    instance = QuestionAnswerNode(input_identifier=['question'], output_identifier=['id','query','content'], **case['parameters'])
                except Exception:
                    self.assertFalse(case['accepted'])
                else:
                    self.assertTrue(case['accepted'])
                    field = case['field'].split('.')[0]
                    actual = instance.model_dump(mode='json', include={field})[field]
                    self.assertEqual(actual, case['effectiveValue'])
                    self.assertIs(type(actual), type(case['effectiveValue']))

    async def test_invalid_original_parameters_all_fail_before_begin(self):
        for case in FIXTURE['coercionCases']:
            if case['accepted']:
                continue
            with self.subTest(case=case['id']):
                raw = fixed()
                raw['dsl']['nodes'][1]['data']['nodeParam'] = copy.deepcopy(case['parameters'])
                await self.reject(raw, 'DSL_INVALID')

    async def test_direct_model_history_resources_and_active_retry_are_not_admitted(self):
        changes = [lambda p: p['directAnswer'].update(handleResponse=True),
            lambda p: p.update(enableChatHistory='yes'),
            lambda p: p.update(enableChatHistoryV2={'isEnabled':'true'}),
            lambda p: p.update(modelResourceId='not-authorized'),
            lambda p: p.update(url='https://unused.invalid')]
        for change in changes:
            raw = fixed(); change(raw['dsl']['nodes'][1]['data']['nodeParam'])
            await self.reject(raw, 'RESOURCE_UNAVAILABLE')
        raw = fixed(); raw['resourceSnapshots'] = {'extra': {'version':'1'}}
        await self.reject(raw, 'RESOURCE_UNAVAILABLE')
        raw = fixed(); raw['effectPolicy']['allowedOperations'] = ['sandbox.execute']
        await self.reject(raw, 'RESOURCE_UNAVAILABLE')
        raw = fixed(); raw['dsl']['nodes'][1]['data']['retryConfig'] = {'shouldRetry':'on','maxRetries':2}
        await self.reject(raw, 'RESOURCE_UNAVAILABLE')

    async def test_disabled_retry_still_checks_original_custom_output(self):
        raw = fixed(); raw['dsl']['nodes'][1]['data']['retryConfig'] = {
            'shouldRetry':False,'maxRetries':2,'customOutput':{'fixture':None}}
        await self.reject(raw, 'DSL_INVALID')
        raw['dsl']['nodes'][1]['data']['retryConfig']['customOutput'] = {'id':'','query':'','content':''}
        refresh_test_canonical(raw)
        self.assertIsNotNone(validate_assignment(Assignment.parse(raw)))

    async def test_unsupported_whole_graph_and_schema_reference_before_factory(self):
        for kind in ['spark-llm','iteration','node-variable','flow','ifly-code','database','plugin']:
            raw = fixed(); raw['dsl']['nodes'][1]['id'] = kind+'::qa'
            # 保留另一个 QA，以选择严格 wholegraph profile。
            raw['dsl']['nodes'].append(copy.deepcopy(FIXTURE['execution']['nodes'][1]))
            await self.reject(raw, 'RESOURCE_UNAVAILABLE')
        raw = fixed(); raw['dsl']['nodes'][0]['data']['outputs'][0]['schema']['$ref'] = 'https://schema.invalid'
        await self.reject(raw, 'RESOURCE_UNAVAILABLE')

    async def test_cycle_orphan_missing_end_and_wrong_metadata_before_begin(self):
        raw = fixed(); raw['dsl']['edges'].append(edge('question-answer::qa','node-start::s'))
        await self.reject(raw, 'DSL_INVALID')
        raw = fixed(); raw['dsl']['nodes'].append(node('message::orphan',template='',streamOutput=False))
        await self.reject(raw, 'DSL_INVALID')
        raw = fixed(); raw['dsl']['nodes'][1]['data']['nodeMeta']['nodeType'] = 'spark-llm'
        await self.reject(raw, 'DSL_INVALID')
        raw = fixed(); raw['dsl']['nodes'][1]['data']['nodeParam'] = None
        await self.reject(raw, 'DSL_INVALID')

    async def test_declared_upstream_references_only(self):
        for name, source in [('missing','node-start::s'), ('content','question-answer::qa'), ('content','node-end::e')]:
            raw = fixed(); raw['dsl']['nodes'][1]['data']['inputs'][0]['schema']['value']['content'] = {'nodeId':source,'name':name}
            await self.reject(raw, 'DSL_INVALID')

    async def test_pool_addresses_include_qa_unfiltered_and_error_outputs(self):
        for name, option in [('query',False),('content',False),('id',True),('errorMessage',False)]:
            raw = fixed()
            raw['dsl']['nodes'][1]['data']['outputs'].append(output('x-'+name))
            params = copy.deepcopy(raw['dsl']['nodes'][1]['data']['nodeParam'])
            if option:
                params['answerType'] = 'option'
            raw['dsl']['nodes'].append(node('question-answer::qa-x', **params))
            raw['dsl']['edges'] = [edge('node-start::s','question-answer::qa'),edge('question-answer::qa','question-answer::qa-x'),edge('question-answer::qa-x','node-end::e')]
            await self.reject(raw, 'DSL_INVALID')

    async def test_duplicate_defaults_fail_before_checkpoint_and_begin(self):
        raw = fixed(); raw['dsl']['nodes'][1]['data']['nodeParam'].update(answerType='option',optionAnswer=[
            {'id':str(i),'name':'default','type':1,'content_type':'text','content':''} for i in range(2)])
        await self.reject(raw,'DSL_INVALID')

    async def test_node_128_129_boundary_independent_of_paths(self):
        for count, valid in [(125,True),(126,False)]:
            raw = fixed(); raw['dsl']['edges'] = [edge('node-start::s','question-answer::qa')]
            for i in range(count):
                name = 'message::wide'+str(i); raw['dsl']['nodes'].append(node(name,template='',streamOutput=False))
                raw['dsl']['edges'].extend([edge('question-answer::qa',name),edge(name,'node-end::e')])
            refresh_test_canonical(raw)
            if valid:
                self.assertEqual(validate_assignment(Assignment.parse(raw)).graph_nodes,128)
            else:
                await self.reject(raw,'QUOTA_EXCEEDED')

    async def test_path_128_129_and_large_dag_without_chains(self):
        raw = fixed(); paths(raw,128)
        self.assertEqual(validate_assignment(Assignment.parse(raw)).path_count,128)
        raw['dsl']['edges'].append(edge('question-answer::qa','node-end::e'))
        await self.reject(raw,'QUOTA_EXCEEDED')
        raw = fixed(); paths(raw,2**30)
        with patch('workflow.engine.entities.chains.Chains.gen',side_effect=AssertionError('no enumeration')) as chains:
            await self.reject(raw,'QUOTA_EXCEEDED')
        self.assertEqual(chains.call_count,0)

    async def test_path_length_64_65(self):
        for count, valid in [(61,True),(62,False)]:
            raw = fixed(); raw['dsl']['edges'] = [edge('node-start::s','question-answer::qa')]
            previous = 'question-answer::qa'
            for i in range(count):
                name = 'message::long'+str(i);raw['dsl']['nodes'].append(node(name,template='',streamOutput=False));raw['dsl']['edges'].append(edge(previous,name));previous=name
            raw['dsl']['edges'].append(edge(previous,'node-end::e'));refresh_test_canonical(raw)
            if valid:
                self.assertEqual(validate_assignment(Assignment.parse(raw)).max_path_length,64)
            else:
                await self.reject(raw,'QUOTA_EXCEEDED')

    async def test_old_model_slot_fixture_is_only_offline_algorithm(self):
        from tests.test_question_answer import OriginalQuestionAnswerTest
        await self.reject(OriginalQuestionAnswerTest().slot_raw(), 'RESOURCE_UNAVAILABLE')


class StaticQuestionOriginalEngineTest(unittest.IsolatedAsyncioTestCase):
    async def test_direct_duplicate_default_metadata_is_inert_but_option_factory_rejects(self):
        from workflow.engine.dsl_engine import WorkflowEngineFactory
        from workflow.exception.e import CustomException
        for answer_type in ['direct', 'option']:
            with self.subTest(answerType=answer_type):
                raw = qa_raw(answerType=answer_type, optionAnswer=[
                    {'id':str(i),'name':'default','type':1,'content_type':'text','content':'未消费元数据'}
                    for i in range(2)])
                refresh_test_canonical(raw)
                assignment = Assignment.parse(raw)
                if answer_type == 'direct':
                    self.assertIsNotNone(validate_assignment(assignment))
                else:
                    await StaticQuestionAdmissionTest().reject(raw, 'DSL_INVALID')
                center = CheckpointCenter(raw)
                context = RunContext(center, assignment, EventPublisher(center, assignment.run_id,
                    assignment.lease_id, assignment.attempt, assignment.last_event_seq))
                token = CURRENT_CONTEXT.set(context)
                try:
                    with patch('socket.socket.connect',side_effect=AssertionError('no Provider socket')):
                        with patch.object(WorkflowEngineFactory,'create_engine',wraps=WorkflowEngineFactory.create_engine) as factory:
                            if answer_type == 'direct':
                                result = await asyncio.wait_for(OriginalWorkflowEngine().execute(assignment,context),8)
                                self.assertEqual(result['outputs'],{'answer':'用户fixture完整回复'})
                                self.assertEqual(len(center.checkpoint_calls),1)
                            else:
                                with self.assertRaises(CustomException) as failure:
                                    await asyncio.wait_for(OriginalWorkflowEngine().execute(assignment,context),8)
                                self.assertEqual(failure.exception.code,22100)
                                self.assertEqual(center.checkpoint_calls,[])
                            self.assertEqual(factory.call_count,1)
                finally:
                    CURRENT_CONTEXT.reset(token)
                self.assertEqual(center.begin_calls,[]);self.assertEqual(center.results,[])
                self.assertEqual(center.operations,[]);self.assertEqual(center.resource_calls,[])

    async def test_twelve_shared_real_factory_reply_ignore_timeout_abort_and_option_cases(self):
        for case in FIXTURE['algorithmCases']:
            with self.subTest(case=case['name']):
                raw = fixed(case['execution'],case['input'])
                class OriginalMetadataCenter(CheckpointCenter):
                    async def consume_checkpoint(self, *args):
                        ack = await super().consume_checkpoint(*args)
                        ack['resumeData']['metadata'] = copy.deepcopy(case['metadata'])
                        self.actual_resume = copy.deepcopy(ack['resumeData'])
                        return ack
                center = OriginalMetadataCenter(raw, [case['event']] if case['event'] is not None else [])
                center.poll_state = case['pollState']
                with patch('socket.socket.connect',side_effect=AssertionError('no Provider socket')):
                    await asyncio.wait_for(create_task_runner(center,heartbeat_seconds=.02).run(raw),8)
                self.assertEqual(len(center.begin_calls),1)
                self.assertEqual(center.operations,[]);self.assertEqual(center.resource_calls,[])
                self.assertEqual(center.checkpoint_calls[0][0][4],1)
                if case['expectedErrorCode'] is None:
                    self.assertEqual(center.results[-1]['state'],'COMPLETED')
                    self.assertEqual(center.results[-1]['output']['outputs'],case['expectedOutputs'])
                else:
                    self.assertEqual(center.results[-1]['state'],'FAILED')
                    failures=[e for e in center.events if e['eventType']=='node.failed']
                    self.assertEqual(failures[-1]['payload']['legacyFrame']['code'],case['expectedErrorCode'])
                if case['event'] is not None:
                    self.assertEqual(json.loads(center.actual_resume['message']),case['event'])
                    self.assertEqual(center.actual_resume['metadata'],case['metadata'])

    async def test_effective_bool_integer_coercion_real_checkpoint_limits(self):
        raw = qa_raw(needReply='OFF',timeout='+3',directAnswer={'handleResponse':'false','maxRetryCounts':'5.0'})
        center = CheckpointCenter(raw,[{'event_type':'ignore','content':''}])
        await asyncio.wait_for(create_task_runner(center).run(raw),8)
        self.assertEqual(center.results[-1]['state'],'COMPLETED')
        self.assertEqual(center.checkpoint_calls[0][0][4],1)
        self.assertEqual(center.checkpoint_calls[0][0][6:8],(False,180))

    async def test_actual_factory_history_uses_effective_original_flags(self):
        for params, supported in [({'enableChatHistory':'false'},True),({'enableChatHistory':True},False),
            ({'enableChatHistory':'true'},False),({'enableChatHistoryV2':{'isEnabled':'false'}},True),
            ({'enableChatHistoryV2':{'isEnabled':'true'}},False),({'enableChatHistoryV2':{'is_enabled':True}},True)]:
            raw=qa_raw(**params);center=CheckpointCenter(raw);fixed_assignment=Assignment.parse(raw)
            context=RunContext(center,fixed_assignment,EventPublisher(center,fixed_assignment.run_id,fixed_assignment.lease_id,1,fixed_assignment.last_event_seq))
            token=CURRENT_CONTEXT.set(context)
            from workflow.engine.dsl_engine import WorkflowEngineFactory
            original=WorkflowEngineFactory.create_engine
            try:
                with patch.object(WorkflowEngineFactory,'create_engine',wraps=original) as factory:
                    if supported:
                        result=await asyncio.wait_for(OriginalWorkflowEngine().execute(fixed_assignment,context),8)
                        self.assertEqual(result['outputs'],{'answer':'用户fixture完整回复'})
                    else:
                        with self.assertRaises(WorkerError) as failure:
                            await OriginalWorkflowEngine().execute(fixed_assignment,context)
                        self.assertEqual(failure.exception.code,'RESOURCE_UNAVAILABLE')
                        self.assertEqual(center.checkpoint_calls,[])
                    self.assertEqual(factory.call_count,1)
            finally:
                CURRENT_CONTEXT.reset(token)
            self.assertEqual(center.begin_calls,[]);self.assertEqual(center.results,[])

    async def test_actual_pydantic_start_completed_and_tail_keep_null_without_run_terminal(self):
        from workflow.engine.callbacks.openai_types_sse import LLMGenerate, NodeInfo, GenerateUsage
        raw=fixed();center=CheckpointCenter(raw);assignment=Assignment.parse(raw)
        context=RunContext(center,assignment,EventPublisher(center,assignment.run_id,assignment.lease_id,1,assignment.last_event_seq))
        frames=_CenterFrames(context)
        started=LLMGenerate.node_start(assignment.run_id,'question-answer::qa','原问答',0)
        completed=LLMGenerate._common(sid=assignment.run_id,node_info=NodeInfo(id='question-answer::qa',finish_reason='stop',outputs={'opaqueNull':None}))
        await frames.put(started);await frames.put(completed)
        for event in center.events:
            frame=event['payload']['legacyFrame']
            self.assertIn('finish_reason',frame['choices'][0]);self.assertIsNone(frame['choices'][0]['finish_reason'])
        self.assertIsNone(center.events[0]['payload']['legacyFrame']['workflow_step']['node']['finish_reason'])
        self.assertEqual(center.events[1]['payload']['legacyFrame']['workflow_step']['node']['finish_reason'],'stop')
        self.assertIsNone(center.events[1]['payload']['legacyFrame']['workflow_step']['node']['outputs']['opaqueNull'])
        self.assertEqual([e['eventType'] for e in center.events],['node.started','node.completed'])
        # 无节点的原 workflow 尾帧不具有正式 run 终态权限。
        await frames.put(LLMGenerate.workflow_end_error(assignment.run_id,1,'fixture'))
        self.assertEqual(len(center.events),2)

    async def test_typed_projection_still_denies_private_payload(self):
        from workflow.engine.callbacks.openai_types_sse import LLMGenerate
        raw=fixed();center=CheckpointCenter(raw);assignment=Assignment.parse(raw)
        context=RunContext(center,assignment,EventPublisher(center,assignment.run_id,assignment.lease_id,1,assignment.last_event_seq))
        frame=LLMGenerate.node_start(assignment.run_id,'question-answer::qa','',0)
        frame.workflow_step.node.outputs={'apiKey':'fixture'+str(1)}
        with self.assertRaises(WorkerError) as failure:
            await _CenterFrames(context).put(frame)
        self.assertEqual(failure.exception.code,'TOOL_POLICY_DENIED');self.assertEqual(center.events,[])
