"""原Pydantic帧→实际_CenterFrames→原React提取函数独立协议反例。"""
import asyncio
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[6]
RUNTIME = ROOT / "python/paiflow-runtime"
OUT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RUNTIME))

from workflow.engine.callbacks.openai_types_sse import Choice, Delta, LLMGenerate, NodeInfo, WorkflowStep
from worker.engine_adapter import _CenterFrames

FILES = [RUNTIME / "worker/engine_adapter.py", RUNTIME / "workflow/engine/callbacks/openai_types_sse.py", ROOT / "frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts", ROOT / "参考项目/PaiFlow-main/core/workflow/service/chat_service.py"]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


class Context:
    def __init__(self):
        self.events = []
        self.checkpoint_acks = {}

    async def emit(self, kind, payload, **identifiers):
        self.events.append({"eventType": kind, "payload": payload, **identifiers})


NODE_REVIEW = r"""
const fs=require('fs'),vm=require('vm'),ts=require(process.argv[1]);
const file=process.argv[2],raw=fs.readFileSync(file,'utf8');
const ast=ts.createSourceFile(file,raw,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
const statement=ast.statements.find(s=>ts.isVariableStatement(s)&&s.declarationList.declarations.some(d=>d.name.getText(ast)==='extractNodeInfo'));
if(!statement)throw Error('original extractNodeInfo not found');
const source=ts.transpileModule(statement.getText(ast)+'\nmodule.exports=extractNodeInfo;', {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
const sandbox={module:{exports:{}}};vm.runInNewContext(source,sandbox,{timeout:1000});
const frames=JSON.parse(fs.readFileSync(0,'utf8'));
const cases=frames.map(({name,frame})=>{const r=sandbox.module.exports(frame);return{name,
 choiceFinishKeyPresent:Object.prototype.hasOwnProperty.call(frame.choices[0],'finish_reason'),
 nodeFinishKeyPresent:Object.prototype.hasOwnProperty.call(frame.workflow_step.node,'finish_reason'),
 choiceIsNull:r.flowResult===null,choiceIsUndefined:r.flowResult===undefined,
 nodeIsNull:r.nodeStatus===null,nodeIsUndefined:r.nodeStatus===undefined,
 originalNodeBranch:r.flowResult===null&&r.nodeId!=='flow_obj',
 originalNodeStatus:r.nodeStatus===null?'ing':(r.nodeStatus===undefined?'undefined':r.nodeStatus),
 originalInterruptBranch:r.flowResult==='interrupt',originalStopBranch:r.flowResult==='stop',
 nodeId:r.nodeId,outputs:r.responseResult.outputs};});
console.log(JSON.stringify({source:file,cases}));
"""


async def main():
    before = {p.relative_to(ROOT).as_posix(): sha(p) for p in FILES}
    start = LLMGenerate.node_start(sid="fixture-run", node_id="question-answer::fixture", alias_name="fixture QA", progress=0.5)
    complete = LLMGenerate(id="fixture-run", workflow_step=WorkflowStep(node=NodeInfo(id="question-answer::fixture", finish_reason="stop", inputs={"query":"fixture"}, outputs={"answer":"fixture", "opaqueNull":None}), progress=0.75), choices=[Choice(delta=Delta())])
    context = Context()
    projection = _CenterFrames(context)
    await projection.put(start)
    await projection.put(complete)
    original_start = start.model_dump(mode="json")
    original_complete = complete.model_dump(mode="json")
    frames = [{"name":"original Pydantic node-start with default null", "frame":original_start}, {"name":"actual Worker node.started legacyFrame", "frame":context.events[0]["payload"]["legacyFrame"]}, {"name":"original Pydantic node-completed with null choice", "frame":original_complete}, {"name":"actual Worker node.completed legacyFrame", "frame":context.events[1]["payload"]["legacyFrame"]}]
    response = subprocess.run(["node", "-e", NODE_REVIEW, str(ROOT / "frontend/paiflow-web/node_modules/typescript"), str(FILES[2])], input=json.dumps(frames), capture_output=True, text=True, encoding="utf-8", check=True)
    consumer = json.loads(response.stdout)
    assert consumer["cases"][0]["originalNodeBranch"] is True
    assert consumer["cases"][1]["choiceIsUndefined"] is True and consumer["cases"][1]["nodeIsUndefined"] is True and consumer["cases"][1]["originalNodeBranch"] is False
    assert consumer["cases"][2]["originalNodeBranch"] is True and consumer["cases"][2]["originalNodeStatus"] == "stop"
    assert consumer["cases"][3]["originalNodeBranch"] is False
    assert "opaqueNull" in original_complete["workflow_step"]["node"]["outputs"]
    # 实际Pydantic保全任意dict内的None；本问题限定为typed字段空值。
    assert "opaqueNull" in context.events[1]["payload"]["legacyFrame"]["workflow_step"]["node"]["outputs"]
    after = {p.relative_to(ROOT).as_posix(): sha(p) for p in FILES}
    assert before == after
    output = {"checkedAt":datetime.now(timezone.utc).isoformat(), "status":"confirmed_worker_original_ui_protocol_gap", "actualMethods":["LLMGenerate.node_start", "LLMGenerate model construction", "_CenterFrames.put", "TypeScript AST extraction/transpile of unchanged extractNodeInfo, executed in Node VM"], "sourceHashesBefore":before, "sourceHashesAfter":after, "sourceChanged":False, "frames":frames, "workerEvents":context.events, "originalConsumer":consumer, "confirmed":["node.started and node.completed omit choices[0].finish_reason null, so unchanged page ignores both node branches", "node.started additionally omits node.finish_reason null instead of ing", "arbitrary output dict opaqueNull is already preserved; no nested-dict null-loss claim"], "originalPythonSerialization":"chat_service.py actual event_stream uses response.model_dump(exclude_none=True); original Java reparses typed ChatResponse before SseEmitter.send. JacksonUtil ALWAYS writer exists, but actual Servlet wire serialization was not executed in this probe.", "retainedProbeAssumptionFailure":"原问答帧空值与Worker投影-探针假设首次失败.log", "productionSourceModified":False, "network":False, "providerCalls":False, "factoryCalled":False, "taskRunnerCalled":False, "centerCalls":0, "browserUsed":False, "businessVerified":False, "limitations":["Synthetic inert frame inputs exercise actual original DTO and adapter; not an actual QA run/lease/checkpoint.", "React only original pure extractNodeInfo plus exact branch predicates executed; component/store rendering not exercised.", "Exit 0 means the protocol mismatch was observed as expected, not that business/UI passed."]}
    path = OUT / "原问答帧空值与Worker投影独立反例.json"
    path.write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"path":path.relative_to(ROOT).as_posix(),"sha256":sha(path),"status":output["status"],"sourceChanged":False,"consumer":consumer["cases"]},ensure_ascii=False,indent=2))


if __name__ == "__main__":
    asyncio.run(main())
