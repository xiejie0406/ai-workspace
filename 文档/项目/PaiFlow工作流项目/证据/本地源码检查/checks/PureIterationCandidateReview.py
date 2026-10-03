"""冻结候选的独立差分检查：原 Chains 与新有界预检；无服务/Provider/数据库。"""
from pathlib import Path
import asyncio
import copy
import hashlib
import json
import random
import sys
import time
from unittest.mock import patch

root = Path(__file__).resolve().parents[6]
runtime = root / "python/paiflow-runtime"
sys.path.insert(0, str(runtime))
from worker.pure_iteration import validate
from worker.errors import WorkerError
from workflow.engine.entities.workflow_dsl import WorkflowDSL
from workflow.engine.entities.chains import Chains

sources = [runtime/"worker/pure_iteration.py", runtime/"worker/task_runner.py",
           runtime/"workflow/engine/entities/chains.py", runtime/"workflow/engine/entities/workflow_dsl.py",
           root/"contracts/paiflow/fixtures/pure-iteration.json"]
before = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
fixture = json.loads(sources[-1].read_text(encoding="utf-8"))


def baseline():
    return copy.deepcopy(fixture["baseline"]["dsl"]), copy.deepcopy(fixture["baseline"]["input"])


def edge(source, target):
    return {"sourceNodeId": source, "targetNodeId": target, "sourceHandle": ""}


def message(identifier):
    return {"id": identifier, "data": {"inputs": [], "outputs": [],
        "nodeParam": {"template": "", "streamOutput": False},
        "nodeMeta": {"nodeType": "message", "aliasName": identifier}}}


def reject(graph, supplied, code):
    try:
        validate(graph, supplied)
    except WorkerError as error:
        assert error.code == code, (code, error.code)
        return
    raise AssertionError("candidate admitted an expected rejection")


rng = random.Random(20261003)
accepted = rejected = 0
max_materialized_paths = 0
for case in range(120):
    graph, supplied = baseline()
    extra = [f"message::review{case}-{index}" for index in range(rng.randrange(0, 10))]
    graph["nodes"].extend(message(identifier) for identifier in extra)
    body = ["iteration-node-start::is", *extra, "text-joiner::j", "iteration-node-end::ie"]
    body_set = set(body)
    graph["edges"] = [item for item in graph["edges"] if item["sourceNodeId"] not in body_set]
    graph["edges"].extend(edge(a,b) for a,b in zip(body,body[1:]))
    for i, source in enumerate(body):
        for target in body[i+2:]:
            if rng.random() < .26:
                graph["edges"].append(edge(source,target))
    # 原 Chains 不按 handle 区分 source/target；重复边只生成同一邻接。
    graph["edges"].extend(dict(item, sourceHandle="review_duplicate") for item in graph["edges"][:3])
    chains = Chains(workflow_schema=WorkflowDSL.model_validate(graph)); chains.gen()
    all_paths = [*chains.master_chains, *chains.iteration_chains["iteration::i"].master_chains]
    path_count = len(all_paths)
    path_length = max(len(item.node_id_list) for item in all_paths)
    max_materialized_paths = max(max_materialized_paths,path_count)
    if path_count > 128:
        reject(graph,supplied,"QUOTA_EXCEEDED"); rejected += 1
    else:
        metrics = validate(graph,supplied)
        assert (metrics.path_count,metrics.max_path_length,metrics.expanded_nodes) == (path_count,path_length,3+3*len(body))
        accepted += 1

# 极大路径只做预检；精确原 Chains.gen 必须零调用。
graph,supplied=baseline()
body_start="iteration-node-start::is"
graph["edges"]=[item for item in graph["edges"] if item["sourceNodeId"] != body_start]
last=[body_start]
for layer in range(30):
    current=[f"message::exponential-{layer}-{side}" for side in (0,1)]
    graph["nodes"].extend(message(identifier) for identifier in current)
    graph["edges"].extend(edge(source,target) for source in last for target in current)
    last=current
graph["edges"].extend(edge(source,"text-joiner::j") for source in last)
started=time.perf_counter()
with patch.object(Chains,"gen",side_effect=AssertionError("must not enumerate paths")) as generated:
    reject(graph,supplied,"QUOTA_EXCEEDED")
assert generated.call_count == 0
huge_elapsed=time.perf_counter()-started

# 原隐式字段与显式字段的同一复合地址须在入口拒绝。
graph,supplied=baseline()
first,second="text-joiner::a","text-joiner::a-x"
first_node=message(first);first_node["data"]["nodeMeta"]["nodeType"]="text-joiner"
first_node["data"]["outputs"]=[{"name":"x-errorMessage","schema":{"type":"string"},"required":False}]
second_node=message(second);second_node["data"]["nodeMeta"]["nodeType"]="text-joiner"
graph["nodes"].extend([first_node,second_node])
graph["edges"].remove(edge("node-start::s","iteration::i"))
graph["edges"].extend([edge("node-start::s",first),edge(first,second),edge(second,"iteration::i")])
reject(graph,supplied,"DSL_INVALID")


async def pre_begin():
    from worker.engine_adapter import create_task_runner
    from tests.test_original_engine import raw_workflow
    from tests.test_worker import RecordingCenter
    from workflow.engine.dsl_engine import WorkflowEngineFactory
    raw=raw_workflow(graph["nodes"],graph["edges"],supplied)
    raw["resourceSnapshots"]={};raw["effectPolicy"]={"mode":"read_only","allowedOperations":[]}
    center=RecordingCenter(raw)
    with patch("socket.socket.connect",side_effect=AssertionError("no network")), patch.object(WorkflowEngineFactory,"create_engine",side_effect=AssertionError("no Factory")) as factory:
        try:
            await create_task_runner(center).run(raw)
        except WorkerError as error:
            assert error.code == "DSL_INVALID"
        else:
            raise AssertionError("actual TaskRunner admitted collision")
    assert factory.call_count == 0
    assert center.begin_calls == center.results == center.events == center.operations == center.resource_calls == []
    return {"factoryCalls":factory.call_count,"begin":len(center.begin_calls),"results":len(center.results),
        "events":len(center.events),"operations":len(center.operations),"resources":len(center.resource_calls)}


entry_result=asyncio.run(pre_begin())
after={p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
assert before==after
print(json.dumps({"scope":"Python frozen pure-iteration candidate independently compared to original Chains; not Java/business validation",
    "differentialDagCases":120,"accepted":accepted,"rejectedByActualPathCount":rejected,
    "maxOriginalMaterializedPaths":max_materialized_paths,"allMetricsEqual":True,
    "exponential2Power30":{"graphNodes":len(fixture["baseline"]["dsl"]["nodes"])+60,"chainsGenCalls":0,"rejected":True,"elapsedSeconds":huge_elapsed},
    "implicitAddressRejected":True,"actualTaskRunnerPreBegin":entry_result,
    "sourceHashesBefore":before,"sourceHashesAfter":after,"sourceWindowChanges":[],
    "productionSourceModified":False,"targetWrites":0,"network":False,"servicesStarted":False,"databaseCalls":0},ensure_ascii=False))
