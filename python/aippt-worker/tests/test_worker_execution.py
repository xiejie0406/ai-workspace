import asyncio
import json
import uuid
import pytest
from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda
from langchain_core.outputs import ChatGeneration,LLMResult
from app.core.config import Settings
from app.llm.deepseek import DeepSeekOutlineGenerator
from app.worker.catalog import catalog_version
from app.worker.execution import EXECUTION_CALLBACK,begin_execution,end_execution
from app.worker.handlers import TaskHandlers
from app.worker.protocol import ClaimedTask,CenterClient
from app.worker.runner import Worker
import httpx


class ProviderFixture(FakeMessagesListChatModel):
    def with_structured_output(self,schema,**kwargs):
        return self | RunnableLambda(lambda message:schema.model_validate_json(message.content))
    def bind_tools(self,tools,**kwargs):return self


def setup_handler(input_tokens=10,output_tokens=5,with_usage=True,valid=True):
    section=str(uuid.uuid4())
    payload={"project":{"title":"不能进摘要的私人原文","tone":"professional","page_count":5},"sources":[{"id":"source","sections":[{"id":section,"text":"不能进摘要的来源正文"}]}]}
    pages=[{"title":f"页面{i}","objective":"独立业务目标","key_points":["事实一","事实二"],"source_refs":[section],"layout_id":"bullets"} for i in range(5)]
    message=AIMessage(content=json.dumps({"pages":pages}) if valid else "不能进摘要的模型正文",response_metadata={"model_name":"fixture-model"},usage_metadata={"input_tokens":input_tokens,"output_tokens":output_tokens,"total_tokens":input_tokens+output_tokens} if with_usage else None)
    model=ProviderFixture(responses=[message],callbacks=[EXECUTION_CALLBACK])
    handlers=TaskHandlers(object());handlers.generators["outline"]=DeepSeekOutlineGenerator(model=model,api_key="不能进摘要的fixture-secret")
    task=ClaimedTask(taskId=str(uuid.uuid4()),attempt=1,leaseToken="lease",fencingToken=1,type="generate_outline",payload=payload,catalogVersion=catalog_version())
    return handlers,task


@pytest.mark.asyncio
async def test_real_langchain_callback_chain_records_actual_usage_without_sensitive_content():
    handlers,task=setup_handler()
    output,_=await handlers.run(task)
    execution=output["execution"]
    assert execution["model"]=="fixture-model" and execution["model_calls"]==1
    assert (execution["input_tokens"],execution["output_tokens"],execution["total_tokens"])==(10,5,15)
    assert execution["usage_status"]=="available" and execution["elapsed_ms"]>=0
    assert len(execution["parameters_version"])==len(execution["prompt_version"])==64
    assert "不能进摘要" not in json.dumps(execution,ensure_ascii=False)
    assert "_execution" not in task.model_dump()


@pytest.mark.asyncio
async def test_concurrent_task_contexts_do_not_mix_usage_or_leave_old_totals():
    one,task_one=setup_handler(11,3);two,task_two=setup_handler(71,9)
    first,second=await asyncio.gather(one.run(task_one),two.run(task_two))
    assert first[0]["execution"]["input_tokens"]==11
    assert second[0]["execution"]["input_tokens"]==71
    again,_=await one.run(task_one)
    assert again["execution"]["input_tokens"]==11 and again["execution"]["model_calls"]==1


@pytest.mark.asyncio
async def test_missing_provider_usage_is_unknown_and_never_filled_with_zero():
    handlers,task=setup_handler(with_usage=False)
    output,_=await handlers.run(task)
    execution=output["execution"]
    assert execution["usage_status"]=="unavailable" and execution["usage_missing_reason"]=="provider_usage_omitted"
    assert all(field not in execution for field in ("input_tokens","output_tokens","total_tokens"))


def test_partial_coverage_does_not_report_incomplete_totals_as_complete():
    stats,token=begin_execution(Settings(_env_file=None),"generate_outline")
    try:
        EXECUTION_CALLBACK.on_chat_model_start({},[],run_id="first")
        EXECUTION_CALLBACK.on_llm_end(LLMResult(generations=[[ChatGeneration(message=AIMessage(content="ignored",usage_metadata={"input_tokens":13,"output_tokens":4,"total_tokens":17}))]]),run_id="first")
        EXECUTION_CALLBACK.on_chat_model_start({},[],run_id="second")
        EXECUTION_CALLBACK.on_llm_end(LLMResult(generations=[[ChatGeneration(message=AIMessage(content="ignored"))]]),run_id="second")
        execution=stats.summary()
        assert execution["usage_status"]=="partial" and execution["model_calls"]==2
        assert all(field not in execution for field in ("input_tokens","output_tokens","total_tokens"))
    finally:end_execution(token)


@pytest.mark.asyncio
async def test_failed_handler_and_fail_protocol_keep_actual_spent_usage_without_body():
    handlers,task=setup_handler(7,2,valid=False);records=[]
    def handler(request):
        records.append(request)
        return httpx.Response(200,json={"code":200,"data":{"accepted":True} if request.url.path.endswith('/progress') else {"leaseValid":True}})
    center=CenterClient(Settings(worker_key="fixture-key"),transport=httpx.MockTransport(handler))
    handlers.center=center
    try:
        await Worker(Settings(worker_key="fixture-key"),center=center,handlers=handlers).execute(task)
        request=next(item for item in records if item.url.path.endswith('/fail'))
        body=json.loads(request.content);execution=body["execution"]
        assert execution["model_calls"]>=1 and execution["input_tokens"]==7*execution["model_calls"]
        assert execution["output_tokens"]==2*execution["model_calls"]
        assert "不能进摘要" not in request.content.decode('utf-8')
        assert body["attempt"]==1 and not any(item.url.path.endswith('/complete') for item in records)
    finally:await center.close()


def test_production_chat_factory_registers_task_summary_callback_without_network():
    from app.llm.client import create_chat_model
    model=create_chat_model(Settings(_env_file=None,llm_api_key="fixture-key"))
    assert EXECUTION_CALLBACK in model.callbacks


@pytest.mark.parametrize("counts",[[100_000_000],[100_000_001],[60_000_000,60_000_000]])
def test_response_and_aggregate_usage_counter_budget_is_not_truncated(counts):
    stats,token=begin_execution(Settings(_env_file=None),"generate_outline")
    try:
        for index,count in enumerate(counts):
            EXECUTION_CALLBACK.on_chat_model_start({},[],run_id=str(index))
            EXECUTION_CALLBACK.on_llm_end(LLMResult(generations=[[ChatGeneration(message=AIMessage(content="ignored",usage_metadata={"input_tokens":count,"output_tokens":0,"total_tokens":count}))]]),run_id=str(index))
        execution=stats.summary()
        if sum(counts)<=100_000_000:
            assert execution["input_tokens"]==sum(counts) and execution["usage_status"]=="available"
        else:
            assert "input_tokens" not in execution and "total_tokens" not in execution
            assert execution["output_tokens"]==0  # Provider真实返回的0保留，非缺失填零。
            assert execution["usage_missing_reason"]=="provider_usage_out_of_range"
    finally:end_execution(token)


@pytest.mark.asyncio
async def test_actual_tool_loop_reports_round_count_and_both_model_responses():
    from app.llm.slide_edit import DeepSeekSlideEditGenerator
    model=ProviderFixture(responses=[AIMessage(content="",tool_calls=[{"name":"replace_text","args":{"block_id":"title","text":"改后标题"},"id":"tool-1","type":"tool_call"}],usage_metadata={"input_tokens":4,"output_tokens":2,"total_tokens":6}),AIMessage(content="完成",usage_metadata={"input_tokens":7,"output_tokens":1,"total_tokens":8})],callbacks=[EXECUTION_CALLBACK])
    handlers=TaskHandlers(object());handlers.generators["edit"]=DeepSeekSlideEditGenerator(model=model,api_key="fixture")
    task=ClaimedTask(taskId="fixture",attempt=1,leaseToken="lease",fencingToken=1,type="ai_edit",catalogVersion=catalog_version(),payload={"project":{"title":"示例"},"instruction":"改标题","history":[],"slide":{"id":"slide","revision":1,"layout_id":"bullets","layout_mode":"fixed","blocks":[{"id":"title","slot_id":"title","type":"text","text":"原标题"},{"id":"body","slot_id":"body","type":"bullets","items":["事实一","事实二"]}]}})
    output,_=await handlers.run(task)
    execution=output["execution"]
    assert execution["tool_rounds"]==1 and execution["model_calls"]==2
    assert execution["input_tokens"]==11 and execution["output_tokens"]==3
    assert output["candidate_slide"]["blocks"][0]["text"]=="改后标题"
