"""只读调用原Pydantic/VariablePool/Chains；无Factory执行、服务、数据库或网络。"""
from pathlib import Path
import hashlib
import json
import sys

workspace = Path(sys.argv[1]).resolve()
runtime = workspace / "python/paiflow-runtime"
sys.path.insert(0, str(runtime))
from workflow.engine.entities.workflow_dsl import WorkflowDSL
from workflow.engine.entities.variable_pool import VariablePool, assemble_mapping_key
from workflow.engine.entities.chains import Chains


def node(identifier, inputs=None, outputs=None, params=None):
    return {"id": identifier, "data": {"inputs": inputs or [], "outputs": outputs or [],
        "nodeParam": params or {}, "nodeMeta": {"nodeType": identifier.split("::")[0], "aliasName": identifier}}}


def output(name, default):
    return {"name": name, "schema": {"type": "string", "default": default}, "required": False}


def edge(source, target):
    return {"sourceNodeId": source, "targetNodeId": target, "sourceHandle": ""}


sources = ["workflow/engine/entities/workflow_dsl.py", "workflow/engine/entities/variable_pool.py", "workflow/engine/entities/chains.py"]
before = {name: hashlib.sha256((runtime/name).read_bytes()).hexdigest() for name in sources}
own = "text-joiner::a"
foreign = "text-joiner::a-x"
consumer = "text-joiner::consumer"
reference = {"name": "leak", "schema": {"type": "string", "value": {
    "type": "ref", "content": {"nodeId": own, "name": "x-y"}}}}
schema = WorkflowDSL.model_validate({"nodes": [node(own, outputs=[output("x-y", "BODY_FIXTURE")]),
    node(foreign, outputs=[output("y", "ROOT_FIXTURE")]), node(consumer, inputs=[reference])], "edges": []})
pool = VariablePool(schema.nodes)
actual = pool.get_variable(consumer, "leak", None)
assert assemble_mapping_key(own, "x-y") == assemble_mapping_key(foreign, "y")
assert actual == "ROOT_FIXTURE"

start = "node-start::s"
end = "node-end::e"
layers = [[f"text-joiner::l{number}a", f"text-joiner::l{number}b"] for number in range(8)]
nodes = [node(start), *[node(item) for layer in layers for item in layer], node(end)]
edges = [edge(start, item) for item in layers[0]]
for previous, following in zip(layers, layers[1:]):
    edges.extend(edge(source, target) for source in previous for target in following)
edges.extend(edge(item, end) for item in layers[-1])
graph = WorkflowDSL.model_validate({"nodes": nodes, "edges": edges})
chains = Chains(workflow_schema=graph)
chains.gen()
assert len(chains.master_chains) == 256
assert max(len(path.node_id_list) for path in chains.master_chains) == 10

after = {name: hashlib.sha256((runtime/name).read_bytes()).hexdigest() for name in sources}
assert before == after
print(json.dumps({"scope": "actual original Pydantic + VariablePool + Chains in-memory; no Factory execution or formal guard result",
    "variableAddressCollision": {"ownAddress": [own, "x-y"], "foreignAddress": [foreign, "y"],
        "mappingKey": assemble_mapping_key(own, "x-y"), "consumerExplicitReference": [own, "x-y"],
        "expectedOwnDeclaredDefault": "BODY_FIXTURE", "actualRead": actual, "confirmed": True},
    "exponentialDag": {"layers": 8, "nodes": len(nodes), "edges": len(edges), "acyclic": True,
        "actualSimplePaths": len(chains.master_chains), "actualMaxPathLengthIncludingEndpoints": 10,
        "pathNodeEntries": chains.get_all_simple_paths_node_cnt(), "exceedsProposedPathBudget128": True},
    "sourceHashesBefore": before, "sourceHashesAfter": after, "sourceWindowChanges": [],
    "serviceStarted": False, "network": False, "databaseCalls": 0, "factoryExecuted": False,
    "formalGuardExecuted": False, "productionSourceModified": False, "targetWrites": 0}, ensure_ascii=False))
