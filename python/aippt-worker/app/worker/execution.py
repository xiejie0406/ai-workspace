"""任务内真实调用摘要；不记录提示词、模型正文、凭据或费用估算。"""
from contextvars import ContextVar
import hashlib
import json
from pathlib import Path
import re
import time
from langchain_core.callbacks import BaseCallbackHandler

_current: ContextVar["ExecutionStats | None"] = ContextVar("aippt_execution",default=None)
_TOKEN_FIELDS=("input_tokens","output_tokens","total_tokens")
_MAX_COUNTER=100_000_000


class ExecutionStats:
    def __init__(self,settings,task_type):
        self.started=time.perf_counter();self.calls={};self.tool_rounds=None;self.invalid_usage=False
        self.logical_calls=0;self.max_calls=settings.llm_max_calls;self.center_routes=set()
        safe={key:getattr(settings,key) for key in ("image_enabled","image_provider","image_model","image_timeout_seconds","max_payload_bytes","max_tree_depth","max_tree_nodes","max_blocks_per_slide","max_upload_mb","max_image_mb","max_image_pixels","max_zip_entries","max_zip_uncompressed_bytes","max_pdf_pages","parse_timeout_seconds")}
        safe.update({key:getattr(settings,key) for key in ("llm_max_calls","tool_rounds_max","repair_rounds_max","edit_history_max")})
        safe.update(task_type=task_type, text_transport="center", text_request_timeout_seconds=settings.text_request_timeout_seconds)
        self.parameters_version=hashlib.sha256(json.dumps(safe,sort_keys=True,separators=(",", ":")).encode()).hexdigest()
        module={"generate_outline":"deepseek","generate_slide":"slide","ai_edit":"slide_edit","relayout":"relayout"}.get(task_type)
        self.prompt_version=None
        if module:
            # 版本来自实际打包的原提示词模块，不计算任何用户材料或提示词实例。
            path=Path(__file__).resolve().parents[1]/"llm"/(module+".py")
            self.prompt_version=hashlib.sha256(path.read_bytes()).hexdigest()

    def start(self,run_id,model=None):
        call=self.calls.setdefault(str(run_id),{})
        if isinstance(model,str) and re.fullmatch(r"[A-Za-z0-9_.:/@-]{1,200}",model):call["model"]=model

    def end(self,run_id,response):
        self.start(run_id)
        call=self.calls[str(run_id)]
        usages=[]
        for group in response.generations:
            for generation in group:
                message=getattr(generation,"message",None)
                if message is None:continue
                model=(getattr(message,"response_metadata",None) or {}).get("model_name")
                self.start(run_id,model)
                usage=getattr(message,"usage_metadata",None)
                if isinstance(usage,dict):usages.append(usage)
        if not usages:
            output=response.llm_output or {};usage=output.get("token_usage")
            self.start(run_id,output.get("model_name"))
            if isinstance(usage,dict):usages=[{"input_tokens":usage.get("prompt_tokens"),"output_tokens":usage.get("completion_tokens"),"total_tokens":usage.get("total_tokens")}]
        # 只有实际返回且完整覆盖该次调用的字段才写入，避免把缺项当零。
        for field in _TOKEN_FIELDS:
            values=[usage.get(field) for usage in usages]
            if values and all(isinstance(value,int) and not isinstance(value,bool) and 0<=value<=_MAX_COUNTER for value in values) and sum(values)<=_MAX_COUNTER:call[field]=sum(values)
            elif any(value is not None and (not isinstance(value,int) or isinstance(value,bool) or value<0 or value>_MAX_COUNTER) for value in values) or values and all(isinstance(value,int) and not isinstance(value,bool) for value in values) and sum(values)>_MAX_COUNTER:self.invalid_usage=True

    def center_end(self, call_id, response):
        self.start(call_id, response.get("model"))
        self.center_routes.add((response["routeVersion"], response["wireApi"], response["model"]))
        call = self.calls[str(call_id)]
        usage = response.get("usage")
        if not isinstance(usage, dict):
            self.invalid_usage = True
            return
        for field in _TOKEN_FIELDS:
            value = usage.get(field)
            if value is None:
                continue
            if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= _MAX_COUNTER:
                call[field] = value
            else:
                self.invalid_usage = True
        if all(field in call for field in _TOKEN_FIELDS) and call["total_tokens"] != call["input_tokens"] + call["output_tokens"]:
            for field in _TOKEN_FIELDS:
                call.pop(field, None)
            self.invalid_usage = True

    def summary(self):
        summary={"parameters_version":self.parameters_version,"elapsed_ms":max(0,int((time.perf_counter()-self.started)*1000)),"model_calls":len(self.calls)}
        if self.center_routes:
            summary["parameters_version"] = hashlib.sha256(json.dumps({"worker": self.parameters_version,
                "center_routes": sorted(self.center_routes)}, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        if self.prompt_version:summary["prompt_version"]=self.prompt_version
        if self.tool_rounds is not None:summary["tool_rounds"]=self.tool_rounds
        if not self.calls:
            summary.update(usage_status="not_applicable",usage_missing_reason="no_text_model_call");return summary
        models={call["model"] for call in self.calls.values() if "model" in call}
        if len(models)==1:summary["model"]=next(iter(models))
        for field in _TOKEN_FIELDS:
            if all(field in call for call in self.calls.values()):
                total=sum(call[field] for call in self.calls.values())
                if total<=_MAX_COUNTER:summary[field]=total
                else:self.invalid_usage=True
        available=sum(field in summary for field in _TOKEN_FIELDS)
        any_usage=any(field in call for call in self.calls.values() for field in _TOKEN_FIELDS)
        summary["usage_status"]="available" if available==3 else "partial" if any_usage else "unavailable"
        if available<3:summary["usage_missing_reason"]="provider_usage_out_of_range" if self.invalid_usage else "provider_usage_incomplete" if any_usage else "provider_usage_omitted"
        return summary


class ExecutionCallback(BaseCallbackHandler):
    run_inline=True
    def on_chat_model_start(self,serialized,messages,*,run_id,**kwargs):
        stats=_current.get()
        if stats is not None:
            params=kwargs.get("invocation_params") or {}
            stats.start(run_id,params.get("model_name") or params.get("model"))
    def on_llm_end(self,response,*,run_id,**kwargs):
        stats=_current.get()
        if stats is not None:stats.end(run_id,response)


EXECUTION_CALLBACK=ExecutionCallback()


class ModelBudgetExceeded(ValueError):
    """请求前的逻辑模型调用预算耗尽；不能借重试扩大预算。"""


def reserve_model_call():
    stats=_current.get()
    if stats is not None:
        if stats.logical_calls >= stats.max_calls:
            raise ModelBudgetExceeded("文本模型调用次数超过任务预算")
        stats.logical_calls += 1


def begin_execution(settings,task_type):
    stats=ExecutionStats(settings,task_type)
    return stats,_current.set(stats)


def end_execution(token):_current.reset(token)


def start_center_call(call_id):
    stats = _current.get()
    if stats is not None:
        stats.start(call_id)


def record_center_call(call_id, response):
    stats = _current.get()
    if stats is not None:
        stats.center_end(call_id, response)


def begin_tool_rounds():
    stats=_current.get()
    if stats is not None and stats.tool_rounds is None:stats.tool_rounds=0


def record_tool_round():
    stats=_current.get()
    if stats is not None:stats.tool_rounds=(stats.tool_rounds or 0)+1
