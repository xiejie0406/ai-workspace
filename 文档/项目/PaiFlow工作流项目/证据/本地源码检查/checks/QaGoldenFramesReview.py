"""生成原DTO帧并执行原页面纯分支，作为薄SSE转换的来源Golden建议。"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[6]
OUT=Path(__file__).resolve().parent.parent
RUNTIME=ROOT/"python/paiflow-runtime"
sys.path.insert(0,str(RUNTIME))
from workflow.engine.callbacks.openai_types_sse import Choice,Delta,GenerateUsage,LLMGenerate,NodeInfo,WorkflowStep

FILES=[RUNTIME/"workflow/engine/callbacks/openai_types_sse.py",ROOT/"frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts",ROOT/"frontend/paiflow-web/src/components/workflow/drawer/chat-debugger/components/chat-content.tsx"]


def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()


JAVASCRIPT=r"""
const fs=require('fs'),vm=require('vm'),ts=require(process.argv[1]),path=process.argv[2];
const input=JSON.parse(fs.readFileSync(0,'utf8')),text=fs.readFileSync(path,'utf8');
const ast=ts.createSourceFile(path,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
const names=['initInterruptChat','extractNodeInfo','handleMessage','handleInterrupt','handleFlowStop','handleMessageEnd'];
const declarations=names.map(name=>{
 const found=ast.statements.find(s=>ts.isVariableStatement(s)&&s.declarationList.declarations.some(d=>d.name.getText(ast)===name));
 if(!found)throw Error(name+' missing');return found.getText(ast);
});
const code=ts.transpileModule(declarations.join('\n')+'\nmodule.exports={handleMessage,extractNodeInfo};',{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
const observations=input.map(({name,frame})=>{
 const record={name,nodeUpdates:[],pushed:[],flowResult:null,interrupt:null,auditCalled:false};
 const state={chatInfoRef:{answer:{}},setInterruptChat:v=>{record.interrupt=v;},wsMessageStatus:'old'};
 const sandbox={module:{exports:{}},isJSON:s=>{try{JSON.parse(s);return true;}catch{return false;}},
  handleNodeStatusChange:v=>record.nodeUpdates.push({nodeId:v.nodeId,nodeStatus:v.nodeStatus,responseResult:v.responseResult}),
  pushContentToAnswer:(queue,content)=>record.pushed.push({queue,content}),
  useFlowStore:{getState:()=>({setEdges:f=>f([])})},handleRunningNodeStatus:()=>{},handleAuditFailed:()=>{record.auditCalled=true;},
  useFlowsManager:{getState:()=>({setShowNodeList:()=>{},setFlowResult:v=>{record.flowResult=v;},setCanvasesDisabled:()=>{},historyVersion:false,setEdges:f=>f([])})}};
 vm.runInNewContext(code,sandbox,{timeout:1000});
 sandbox.module.exports.handleMessage([],[],{data:JSON.stringify(frame)},()=>state,()=>{});
 record.sid=state.chatInfoRef.sid;record.messageStatus=state.wsMessageStatus;
 record.extracted=sandbox.module.exports.extractNodeInfo(frame);return record;
});console.log(JSON.stringify(observations));
"""


def main():
    before={p.relative_to(ROOT).as_posix():sha(p) for p in FILES}
    start=LLMGenerate.node_start("fixture-run","question-answer::fixture","fixture QA",0.5)
    interrupt=LLMGenerate.node_interrupt("fixture-run","fixture-checkpoint",{"type":"option","content":"fixture question","option":[{"id":"default","text":"fallback","content_type":"text"},{"id":"A","text":"fixture A","content_type":"text"},{"id":"B","text":"fixture B","content_type":"text"}]},"question-answer::fixture","fixture QA",0.1,None,0.5,"interrupt",False)
    direct=LLMGenerate.node_interrupt("fixture-run","fixture-checkpoint-direct",{"type":"direct","content":"fixture direct question"},"question-answer::fixture","fixture QA",0.1,None,0.5,"interrupt",True)
    complete=LLMGenerate(id="fixture-run",workflow_step=WorkflowStep(node=NodeInfo(id="question-answer::fixture",finish_reason="stop",inputs={"query":"fixture"},outputs={"answer":"fixture","opaqueNull":None},error_outputs={},executed_time=0.25),progress=0.75),choices=[Choice(delta=Delta())])
    failed=LLMGenerate(id="fixture-run",code=22101,message="fixture safe node failure",workflow_step=WorkflowStep(node=NodeInfo(id="question-answer::fixture",finish_reason="stop",error_outputs={"errorCode":22101,"errorMessage":"fixture safe node failure"}),progress=0.75),choices=[Choice(delta=Delta())])
    terminal=LLMGenerate.workflow_end("fixture-run",GenerateUsage(prompt_tokens=2,completion_tokens=3,total_tokens=5))
    terminal_error=LLMGenerate.workflow_end_error("fixture-run",22101,"fixture safe workflow failure")
    frames=[]
    for name,value in [("node.started",start),("checkpoint.option",interrupt),("checkpoint.direct",direct),("node.completed",complete),("node.failed",failed),("run.completed wire shape from original DTO",terminal),("run.failed wire shape from original DTO",terminal_error)]:
        value.created=1700000000
        frame=value.model_dump(mode="json")
        if name.startswith("run."):frame["executedTime"]=0.75
        frames.append({"name":name,"frame":frame})
    frames.append({"name":"malformed original error-only body", "frame":{"code":-1,"message":"fixture original error only"}})
    command=["node","-e",JAVASCRIPT,str(ROOT/"frontend/paiflow-web/node_modules/typescript"),str(FILES[1])]
    result=subprocess.run(command,input=json.dumps(frames),capture_output=True,text=True,encoding="utf-8")
    if result.returncode:
        print(result.stderr)
        raise RuntimeError("original consumer private VM probe failed")
    observations=json.loads(result.stdout)
    assert observations[0]["nodeUpdates"][0]["nodeStatus"]=="ing"
    assert [item["id"] for item in observations[1]["interrupt"]["option"]]==["A","B"]
    assert observations[1]["interrupt"]["needReply"] is False
    assert observations[2]["interrupt"]["needReply"] is True
    assert observations[3]["nodeUpdates"][0]["nodeStatus"]=="stop" and observations[3]["flowResult"] is None
    assert observations[4]["nodeUpdates"][0]["responseResult"]["status"]=="failed" and observations[4]["flowResult"] is None
    assert observations[5]["flowResult"]["status"]=="success" and observations[5]["flowResult"]["totalTokens"]=="5"
    assert observations[6]["flowResult"]["status"]=="failed"
    assert observations[7]["flowResult"] is None and not observations[7]["nodeUpdates"]
    after={p.relative_to(ROOT).as_posix():sha(p) for p in FILES}
    assert before==after
    data={"schemaVersion":"paiflow.qa.original-consumer-golden-review.v1","checkedAt":datetime.now(timezone.utc).isoformat(),"status":"original_dto_and_pure_consumer_branches_checked_backend_codec_not_verified","sourceHashesBefore":before,"sourceHashesAfter":after,"sourceWindowChanges":[],"fixtureOrigin":"原LLMGenerate真实构造器/声明字段，输入为隔离惰性fixture；不是已授权run或正式checkpoint数据","frames":frames,"actualOriginalConsumerObservations":observations,"consumerScope":"AST提取实际extractNodeInfo/handleMessage/handleInterrupt/handleFlowStop/handleMessageEnd，在Node VM执行；UI/store渲染与setNode细节用显式spy，不计页面检查","adapterRecommendations":["node.started/delta/completed/failed只投choice.finish_reason=null；node.finish_reason依node事件nullable/stop，不能将node完成变flow stop", "checkpoint必须关联已授权当前run/lease/attempt/固定node的中心真实question和revision；原event_data.value.type/content/option与need_reply不能从任意Worker字符串信任", "center run.completed/failed/cancelled持久终态才投choice.stop；失败/取消code非0且safe message，usage/executedTime从中心已确认记录读取", "恢复原DTO已声明nullable字段供旧UI严格null，不更改canonical/event/model原帧或猜填未知输出", "原error-only code/message会被旧UI忽略；无授权terminal不能包装成成功/stop，可以HTTP拒绝或由真实失败结果投安全终帧", "原option点击的是interrupt.id，即原QA配置name，不是配置UUID id；default由原UI过滤", "缺合法legacyFrame的reasoning.delta不能直接泛化完整模型SSE；同text.delta内reasoning与下一reasoning.delta可能重复，应建立可核对关联"],"productionSourceModified":False,"network":False,"providerCalls":False,"factoryCalled":False,"taskRunnerCalled":False,"centerCalls":0,"browserUsed":False,"businessVerified":False}
    path=OUT/"原问答SSE消费者黄金帧核对.json"
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"path":path.relative_to(ROOT).as_posix(),"sha256":sha(path),"frames":len(frames),"sourceWindowChanges":[]},ensure_ascii=False,indent=2))


if __name__=="__main__":main()
