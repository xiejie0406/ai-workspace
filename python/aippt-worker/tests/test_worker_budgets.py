import json
import shutil
import pytest
import httpx
from openai import APIConnectionError, APIStatusError
from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda
from pydantic import BaseModel, ValidationError
from app.core.config import Settings, get_settings
from app.llm.client import StructuredChatClient, create_chat_model
from app.llm.errors import InvalidModelOutputError, InvalidOutlineOutputError
from app.worker.execution import begin_execution, end_execution, ModelBudgetExceeded
from app.worker.runner import Worker, public_error

class Answer(BaseModel):
    value: int

class Fixture(FakeMessagesListChatModel):
    def with_structured_output(self, schema, **kwargs):
        return self | RunnableLambda(lambda message: schema.model_validate_json(message.content))
    def bind_tools(self, tools, **kwargs): return self

@pytest.mark.asyncio
async def test_logical_budget_is_reserved_before_request_and_failed_call_consumes_budget():
    cfg = Settings(_env_file=None,llm_max_calls=1)
    stats,token = begin_execution(cfg,"generate_outline")
    model = Fixture(responses=[AIMessage(content='bad structure')])
    client = StructuredChatClient(model=model,api_key="fixture")
    try:
        with pytest.raises(InvalidModelOutputError): await client.complete(Answer,system="fixture",user="fixture",purpose="fixture")
        assert stats.logical_calls == 1
        with pytest.raises(ModelBudgetExceeded): await client.complete(Answer,system="fixture",user="fixture",purpose="fixture")
        assert stats.logical_calls == 1
        assert public_error(ModelBudgetExceeded())[2] is False
    finally: end_execution(token)

@pytest.mark.asyncio
async def test_tool_loop_and_history_respect_smaller_configured_caps(monkeypatch):
    from app.llm.slide_edit import DeepSeekSlideEditGenerator
    from app.llm.base import SlideEditInput, AiEditHistoryTurn
    from app.domain.content import TextBlock
    cfg = get_settings(); monkeypatch.setattr(cfg,"tool_rounds_max",1); monkeypatch.setattr(cfg,"edit_history_max",1)
    messages = []
    class Tools(Fixture):
        async def ainvoke(self, value, *args, **kwargs):
            messages.extend(value)
            return await super().ainvoke(value,*args,**kwargs)
    model = Tools(responses=[AIMessage(content="",tool_calls=[{"name":"replace_text","args":{"block_id":"body","text":"after"},"id":"call","type":"tool_call"}])])
    block = TextBlock(id="body",slot_id="body",text="before")
    payload = SlideEditInput(deck_title="fixture",tone="professional",page_title="fixture",layout_id="bullets",instruction="change",blocks=[],original_blocks=[block],history=[AiEditHistoryTurn(instruction="old-history"),AiEditHistoryTurn(instruction="latest-history")])
    stats,token=begin_execution(cfg,"ai_edit")
    try:
        result=await DeepSeekSlideEditGenerator(model=model,api_key="fixture").generate(payload)
        assert stats.logical_calls == 1 and stats.tool_rounds == 1 and result.blocks[0].text == "after"
        assert "latest-history" in [message.content for message in messages] and "old-history" not in [message.content for message in messages]
    finally:end_execution(token)

def test_actual_model_request_configuration_contains_output_and_retry_caps():
    cfg=Settings(_env_file=None,llm_max_output_tokens=512,llm_max_retries=1)
    model=create_chat_model(cfg)
    assert model.max_tokens == 512 and model.max_retries == 1

@pytest.mark.parametrize("key,value",[("llm_max_calls",9),("tool_rounds_max",5),("repair_rounds_max",2),("edit_history_max",6),("llm_max_output_tokens",255),("llm_max_retries",3)])
def test_config_cannot_exceed_approved_caps(key,value):
    with pytest.raises(ValidationError): Settings(_env_file=None,**{key:value})

@pytest.mark.parametrize("kind",["connection","timeout",429,500,503,400,401])
def test_provider_error_classification_follows_nested_cause_without_exposing_body(kind):
    request=httpx.Request("POST","https://provider.example/")
    if kind == "connection": cause=APIConnectionError(request=request)
    elif kind == "timeout": cause=httpx.ReadTimeout("private body",request=request)
    else: cause=APIStatusError("private body",response=httpx.Response(kind,request=request),body={"api_key":"private"})
    inner=InvalidModelOutputError("invalid");inner.__cause__=cause
    error=InvalidOutlineOutputError("invalid");error.__cause__=inner
    result=public_error(error)
    assert result[2] is (kind not in (400,401)) and "private" not in result[1]
    if result[2]: assert result[0] == "UPSTREAM_UNAVAILABLE"

def test_wrapped_budget_failure_is_not_retried():
    inner=ModelBudgetExceeded(); error=InvalidModelOutputError();error.__cause__=inner
    assert public_error(error)[0] == "TASK_BUDGET_EXCEEDED" and not public_error(error)[2]


def test_direct_permanent_http_status_is_not_retried():
    request=httpx.Request("GET","https://provider.example/")
    error=httpx.HTTPStatusError("fixture",request=request,response=httpx.Response(400,request=request))
    assert not public_error(error)[2]

@pytest.mark.asyncio
async def test_first_task_revalidates_catalog_bytes_before_algorithm(tmp_path,monkeypatch):
    from app.worker import catalog,runner
    from app.worker.protocol import ClaimedTask
    from app.core.paths import SHARED_DIR
    from app.domain import layout,theme,flex_presets
    root=tmp_path/"catalog";shutil.copytree(SHARED_DIR,root);shutil.copyfile(SHARED_DIR.parent/"catalog-manifest.json",tmp_path/"catalog-manifest.json")
    monkeypatch.setattr(catalog,"SHARED_DIR",root);monkeypatch.setattr(layout,"LAYOUTS_DIR",root/"layouts");monkeypatch.setattr(theme,"THEMES_DIR",root/"themes");monkeypatch.setattr(flex_presets,"PRESETS_DIR",root/"flex-presets")
    monkeypatch.setattr(runner,"catalog_version",lambda:catalog.catalog_version(root));monkeypatch.setattr(catalog,"catalog_version",lambda path=root:original_version(path))
    class Center:
        failed=[]
        async def fail(self,*args): self.failed.append(args)
    class Handlers:
        calls=0
        async def run(self,task): self.calls+=1;return {},[]
    center=Center();handlers=Handlers()
    try:
        worker=Worker(Settings(_env_file=None),center=center,handlers=handlers)
        changed=root/"layouts/bullets.json";changed.write_bytes(changed.read_bytes()+b" ")
        task=ClaimedTask(taskId="fixture",attempt=1,leaseToken="lease",fencingToken=1,type="parse_source",payload={},catalogVersion=worker.version)
        await worker.execute(task)
        assert handlers.calls == 0 and center.failed[0][1] == "CATALOG_MISMATCH"
    finally:
        for loader in (layout.load_layouts,theme.load_themes,flex_presets.load_presets): loader.cache_clear()

from app.worker.catalog import catalog_version as original_version


def test_real_cli_health_loads_catalog_dir_from_dotenv(tmp_path):
    import os
    import subprocess
    import sys
    from pathlib import Path
    from app.core.paths import SHARED_DIR
    root=tmp_path/"catalog";shutil.copytree(SHARED_DIR,root);shutil.copyfile(SHARED_DIR.parent/"catalog-manifest.json",tmp_path/"catalog-manifest.json")
    # 存在但缺少中心manifest的指定目录应失败，才能证明没有悄悄回退工作区目录。
    (tmp_path/".env").write_text("AIPPT_CATALOG_DIR="+root.as_posix()+"\n",encoding="utf-8")
    env={key:value for key,value in os.environ.items() if key != "AIPPT_CATALOG_DIR"}
    env["PYTHONPATH"]=str(Path(__file__).resolve().parents[1])
    # Windows 子进程输出与父管道都明确 UTF-8，严格解码，不丢弃失败诊断。
    env.update(PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    command=[sys.executable,"-m","app","health"]
    valid=subprocess.run(command,cwd=tmp_path,env=env,capture_output=True,text=True,encoding="utf-8",timeout=30)
    assert valid.returncode == 0 and json.loads(valid.stdout)["catalogVersion"] == original_version()
    (tmp_path/"catalog-manifest.json").unlink()
    invalid=subprocess.run(command,cwd=tmp_path,env=env,capture_output=True,text=True,encoding="utf-8",timeout=30)
    assert invalid.returncode != 0 and "catalog-manifest.json" in invalid.stderr
