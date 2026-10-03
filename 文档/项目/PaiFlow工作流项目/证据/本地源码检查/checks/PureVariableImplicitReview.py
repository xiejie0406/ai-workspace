"""只读复现原add_variable隐式错误字段覆盖另节点显式输出；无运行或外调。"""
from pathlib import Path
import hashlib
import json
import sys

root = Path(sys.argv[1]).resolve()
runtime = root / "python/paiflow-runtime"
sys.path.insert(0, str(runtime))
from workflow.engine.entities.workflow_dsl import WorkflowDSL
from workflow.engine.entities.variable_pool import VariablePool
from workflow.engine.nodes.entities.node_run_result import NodeRunResult, WorkflowNodeExecutionStatus


def node(identifier, outputs=None):
    return {"id": identifier, "data": {"inputs": [], "outputs": outputs or [], "nodeParam": {},
        "nodeMeta": {"nodeType": "text-joiner", "aliasName": identifier}}}


source = runtime / "workflow/engine/entities/variable_pool.py"
before = hashlib.sha256(source.read_bytes()).hexdigest()
owner = "text-joiner::a"
other = "text-joiner::a-x"
schemas = WorkflowDSL.model_validate({"nodes": [node(owner, [{"name": "x-errorMessage",
    "schema": {"type": "string", "default": "BODY_FIXTURE"}, "required": False}]), node(other)], "edges": []})
pool = VariablePool(schemas.nodes)
first = pool.get_output_variable(owner, "x-errorMessage", None)
pool.add_variable(other, [], NodeRunResult(status=WorkflowNodeExecutionStatus.SUCCEEDED,
    node_id=other, alias_name=other, node_type="text-joiner", outputs={},
    error_outputs={"errorMessage": "ROOT_ERROR_FIXTURE"}), None)
second = pool.get_output_variable(owner, "x-errorMessage", None)
assert first == "BODY_FIXTURE" and second == "ROOT_ERROR_FIXTURE"
after = hashlib.sha256(source.read_bytes()).hexdigest()
assert before == after
print(json.dumps({"scope": "actual VariablePool.add_variable only; no Factory execution or formal guard",
    "explicitAddress": [owner, "x-errorMessage"], "implicitAddress": [other, "errorMessage"],
    "mappingKey": owner + "-x-errorMessage", "beforeAdd": first, "afterActualAdd": second,
    "confirmed": True, "sourceSha256Before": before, "sourceSha256After": after,
    "productionSourceModified": False, "network": False, "databaseCalls": 0,
    "servicesStarted": False, "targetWrites": 0}, ensure_ascii=False))
