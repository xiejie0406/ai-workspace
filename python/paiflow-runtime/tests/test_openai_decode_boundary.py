"""原OpenAI SDK缺省字段与中心原帧的解码边界；无Provider调用。"""
import asyncio
import copy
import json
import unittest

from openai.types.chat import ChatCompletionChunk
from pydantic import ValidationError
from worker.engine_adapter import create_task_runner
from worker.model_bridge import CenterOpenAIChatAI
from workflow.infra.providers.llm.openai.openai_chat_llm import OpenAIChatAI
from tests.test_controlled_nodes import model_raw
from tests.test_model_contract import HttpModelCenter


def raw_chunk(delta, finish='absent', usage='absent'):
    choice = {'index': 0, 'delta': copy.deepcopy(delta)}
    if finish != 'absent': choice['finish_reason'] = finish
    value = {'id': 'chatcmpl-isolated', 'object': 'chat.completion.chunk', 'created': 1700000000,
             'model': 'model-fixture', 'choices': [choice]}
    if usage != 'absent': value['usage'] = copy.deepcopy(usage)
    return value


class OpenAIDecodeBoundaryTest(unittest.IsolatedAsyncioTestCase):
    def test_original_sdk_defaults_match_consumer_private_decode_and_keep_raw_untouched(self):
        decoder = CenterOpenAIChatAI.model_construct()
        usage = {'prompt_tokens': 2, 'completion_tokens': 3, 'total_tokens': 5}
        frames = [raw_chunk({'content': ' 原内容'}), raw_chunk({}, 'stop'),
                  raw_chunk({'content': '', 'reasoning_content': '原思考'}, None, usage)]
        for raw in frames:
            with self.subTest(raw=raw):
                before = json.dumps(raw, ensure_ascii=False, sort_keys=True)
                sdk = ChatCompletionChunk.model_validate(raw).model_dump()
                self.assertIn('usage', sdk)
                self.assertIn('finish_reason', sdk['choices'][0])
                self.assertIn('content', sdk['choices'][0]['delta'])
                expected = OpenAIChatAI.decode_message(None, sdk)
                actual = decoder.decode_message(raw)
                self.assertEqual(actual[:4], expected[:4])
                # 原SDK还补token_details:null；本次解码桥不向原用量里创造这些字段。
                self.assertEqual(actual[4], raw.get('usage') or {})
                for key, value in actual[4].items(): self.assertEqual(value, expected[4][key])
                self.assertEqual(json.dumps(raw, ensure_ascii=False, sort_keys=True), before)

    async def test_actual_factory_decodes_omitted_usage_empty_final_delta_and_real_tail_usage(self):
        value = model_raw(source='openai')
        frames = [raw_chunk({'content': ' 原内容'}), raw_chunk({}, 'stop'),
                  {**raw_chunk({}, 'stop'), 'choices': [],
                   'usage': {'prompt_tokens': 2, 'completion_tokens': 11, 'total_tokens': 13}}]
        original = copy.deepcopy(frames)
        centre = HttpModelCenter(value, frames)
        await asyncio.wait_for(create_task_runner(centre).run(value), 8)
        self.assertEqual(centre.results[-1]['state'], 'COMPLETED')
        self.assertEqual(centre.results[-1]['output']['outputs'], {'answer': ' 原内容'})
        self.assertEqual(centre.results[-1]['output']['usage']['total_tokens'], 13)
        self.assertEqual(centre.frames, original)
        self.assertNotIn('usage', frames[0]); self.assertNotIn('finish_reason', frames[0]['choices'][0])
        self.assertEqual(frames[1]['choices'][0]['delta'], {})
        self.assertEqual(centre.cancel_calls, [])
        acknowledged = [frame for call in centre.frame_calls for frame in original[call[-2]:call[-2]+1]]
        self.assertEqual(acknowledged, original)

    async def test_unknown_nonstop_frame_cannot_be_normalized_to_success(self):
        value = model_raw(source='openai')
        supplied = raw_chunk({}, 'unknown_finish')
        with self.assertRaises(ValidationError): ChatCompletionChunk.model_validate(supplied)
        centre = HttpModelCenter(value, [supplied]); centre.failure_kind = 'provider'
        await asyncio.wait_for(create_task_runner(centre).run(value), 8)
        self.assertEqual(centre.results[-1]['state'], 'FAILED')
        self.assertFalse(any(event['nodeId'] == 'spark-llm::model' and event['eventType'] == 'node.completed'
                             for event in centre.events))
        self.assertEqual(centre.frames, [supplied])
        self.assertEqual(len(centre.cancel_calls), 1)
