"""只读原全局变量节点事实；不导入引擎、不访问服务、不修改源码或target。"""
import ast
import hashlib
import json
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[6]
reference = "参考项目/PaiFlow-main/"
current = "python/paiflow-runtime/"
java = "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/"
sources = [
    reference + "core/workflow/engine/nodes/global_variables/global_variables_node.py",
    reference + "core/workflow/engine/nodes/cache_node.py",
    reference + "core/workflow/engine/node.py",
    reference + "core/workflow/engine/entities/workflow_dsl.py",
    reference + "core/workflow/engine/entities/variable_pool.py",
    reference + "core/workflow/extensions/middleware/cache/manager.py",
    reference + "core/workflow/exception/errors/err_code.py",
    reference + "core/workflow/api/v1/chat/debug.py",
    reference + "core/workflow/api/v1/chat/open.py",
    reference + "core/workflow/domain/entities/chat.py",
    reference + "console/frontend/src/components/workflow/nodes/variable-memory/index.tsx",
    reference + "console/frontend/src/components/workflow/nodes/variable-memory/components/inputs.tsx",
    reference + "console/frontend/src/components/workflow/hooks/use-variable-memory-handlers.ts",
    "frontend/paiflow-web/src/components/workflow/nodes/variable-memory/index.tsx",
    "frontend/paiflow-web/src/components/workflow/nodes/variable-memory/components/inputs.tsx",
    "frontend/paiflow-web/src/components/workflow/hooks/use-variable-memory-handlers.ts",
    current + "workflow/engine/nodes/global_variables/global_variables_node.py",
    current + "worker/variables.py",
    current + "worker/task_runner.py",
    current + "worker/engine_adapter.py",
    current + "worker/center_client.py",
    current + "protocols/persistence-draft.schema.json",
    current + "tests/test_original_engine.py",
    java + "domain/NodeCatalog.java",
    java + "service/WorkflowModelCompiler.java",
    java + "service/CanvasExecutionCompiler.java",
    java + "service/RunService.java",
    java + "service/RunLeaseService.java",
    java + "service/PureIterationPolicy.java",
    java + "service/StaticQuestionAnswerPolicy.java",
    java + "adapter/legacy/LegacyWorkflowStreamCodec.java",
    "contracts/paiflow/worker.schema.json",
    "contracts/paiflow/internal.openapi.json",
]

def sha(data):
    return hashlib.sha256(data).hexdigest()

before = {path: (root / path).read_bytes() for path in sources}

def original_body(path):
    module = ast.parse(before[path].decode("utf-8-sig"))
    node = next(item for item in module.body if isinstance(item, ast.ClassDef) and item.name == "GlobalVariablesNode")
    return deepcopy(next(item for item in node.body if isinstance(item, ast.AsyncFunctionDef) and item.name == "async_execute"))

class RemoveExplicitCenterAwait(ast.NodeTransformer):
    def visit_Await(self, node):
        if isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute) and isinstance(node.value.func.value, ast.Name) and node.value.func.value.id == "var_manager" and node.value.func.attr in {"add_variable", "get_all_variables"}:
            return self.generic_visit(node.value)
        return self.generic_visit(node)

    def visit_Call(self, node):
        if isinstance(node.func, ast.Name) and node.func.id == "VariablesManage":
            node.keywords = [keyword for keyword in node.keywords if keyword.arg != "node_id"]
        return self.generic_visit(node)

reference_node = sources[0]
imported_node = current + "workflow/engine/nodes/global_variables/global_variables_node.py"
algorithm_equal = ast.dump(original_body(reference_node), include_attributes=False) == ast.dump(RemoveExplicitCenterAwait().visit(original_body(imported_node)), include_attributes=False)
frontend_pairs = []
for suffix in ["nodes/variable-memory/index.tsx", "nodes/variable-memory/components/inputs.tsx", "hooks/use-variable-memory-handlers.ts"]:
    old = reference + "console/frontend/src/components/workflow/" + suffix
    new = "frontend/paiflow-web/src/components/workflow/" + suffix
    frontend_pairs.append({"reference": old, "current": new, "byteEqual": before[old] == before[new]})

openapi = json.loads(before["contracts/paiflow/internal.openapi.json"])
formal_paths = list(openapi.get("paths", {}))
controller_root = root / java / "controller"
variable_controllers = [file.relative_to(root).as_posix() for file in controller_root.rglob("*.java") if '"/variables"' in file.read_text(encoding="utf-8-sig") or '"variables"' in file.read_text(encoding="utf-8-sig")]
after = {path: (root / path).read_bytes() for path in sources}
drift = [path for path in sources if before[path] != after[path]]
report = {
    "schemaVersion": "paiflow.readonly-source-facts.v1",
    "checkedAt": datetime.now(timezone.utc).isoformat(),
    "scope": "下一局部实施候选的原全局变量源码核对；没有新需求/设计/运行状态真相",
    "execution": {"pythonEngineImports": 0, "FactoryRuns": 0, "MavenRuns": 0, "databaseConnections": 0, "networkCalls": 0, "sourceWrites": 0, "targetWrites": 0},
    "sources": [{"path": path, "sha256": sha(before[path])} for path in sources],
    "sourceWindow": {"count": len(sources), "drift": drift},
    "staticChecks": {
        "importedAsyncExecuteMatchesOriginalAfterOnlyExplicitAwaitAndNodeIdentityAdaptation": algorithm_equal,
        "meaning": "AST主体相等只证明所列await与node_id差异外保留原算法文本，不证明中心端点或原业务执行",
        "frontendOriginalBytes": frontend_pairs,
        "formalVariableOpenApiPaths": [path for path in formal_paths if "variable" in path],
        "currentVariableControllerMatches": variable_controllers,
    },
    "originalFacts": [
        {"source": reference_node, "lines": [49, 116], "fact": "scope为flow_id/uid/app_id；chat_id is None时没有chat维，其他值含空串都追加chat维。add/get/get_all/clear相同scope；写入hash_set_ex的expire_time=None，7日常量当前不使用。"},
        {"source": reference_node, "lines": [128, 221], "fact": "method必须set或get。set按input_identifier逐项写；get先读所有global_vars，再按output_identifier取同名已存在键，缺项才调用本节点VariablePool.get_variable。全局存在null/false/0不因truthiness而回退。返回dict按声明输出顺序保留，get没有写库。"},
        {"source": reference_node, "lines": [222, 234], "fact": "捕获执行错误为VARIABLE_NODE_EXECUTION_ERROR(22900)，原逐项set没有跨多键回滚；不能由后续节点FAILED断言此前变量写入不存在。"},
        {"source": reference + "core/workflow/engine/entities/variable_pool.py", "lines": [372, 393], "fact": "输出池初始化使用schema.type默认值，显式schema.default覆盖；get_variable先用本节点input mapping，再用本节点output mapping。原get页inputs=[]，缺全局项时可读取已初始化output默认值。"},
        {"source": reference + "core/workflow/extensions/middleware/cache/manager.py", "lines": [120, 223], "fact": "原Redis存pickle后HSET、HSCAN还原任意可pickle值。不能未经验证照搬pickle反序列化/旧Redis真相；当前JSON Worker可表示的值范围需在正式中心端口明确。"},
        {"source": reference + "core/workflow/api/v1/chat/debug.py", "lines": [32, 68], "fact": "原debug入口app_id来自x_consumer_username并查app_service.get_info；uid/chat_id来自ChatVo。原请求允许空uid/chat_id默认，但迁移身份不能信任客户端uid或伪造App。"},
        {"source": reference + "console/frontend/src/components/workflow/nodes/variable-memory/index.tsx", "lines": [30, 109], "fact": "原get选项从set输入name/id/type收集；refId用于页面跟踪set输入，执行器依据变量name而非refId查持久变量。UI literal.content用truthiness筛选，false/0不会成为选项，不能将此UI筛选误称原后端类型规则。"},
        {"source": reference + "console/frontend/src/components/workflow/nodes/variable-memory/index.tsx", "lines": [157, 226], "fact": "迭代编辑时选项可合并主画布与内部节点；切set初始化一个ref输入并清outputs，切get清inputs并初始化一个required输出。原页面/参数不改。"},
    ],
    "currentFacts": [
        {"source": java + "domain/NodeCatalog.java", "lines": [27, 27], "fact": "真实目录名node-variable，中文全局变量；仅Python引擎登记。"},
        {"source": java + "service/WorkflowModelCompiler.java", "lines": [14, 42], "fact": "node-variable不在模型资源类型，普通V2执行投影目前保留method但没有正式变量scope编译事实；版本保存不代表Run获准执行。"},
        {"source": java + "service/RunService.java", "lines": [79, 94], "fact": "当前五纯节点、静态QA和纯迭代profile均不放行node-variable，排队前503。LegacyWorkflowStreamCodec同样不允许此类型。"},
        {"source": current + "worker/task_runner.py", "lines": [274, 305], "fact": "verify的可选executionContext严格四字段flowId/userId/appId/chatId，前三必须非空string，chatId允许null/空串。一般node-variable缺scope的拒绝现在发生于engine.execute，execution/begin之后；未来放行时需要把scope可用性预检提前而不绕过中心一次性执行许可。"},
        {"source": current + "worker/variables.py", "lines": [15, 59], "fact": "每次操作对比原tuple与中心executionContext并送run/lease/attempt/nodeId。set先get_all拿revision后CAS，进程内锁和请求memo只做当前并行/重试关联；operation+nodeId+iterationId+values生成幂等键。无Redis/本地正式持久化回退。"},
        {"source": current + "worker/center_client.py", "lines": [345, 371], "fact": "已有get_all/get/set/clear草案消费者及严格ACK，但共享OpenAPI/中心controller没有正式变量路由。原node method仍仅set/get，clear不能据消费者枚举自行成为节点方法。"},
        {"source": current + "protocols/persistence-draft.schema.json", "fact": "已有唯一变量/history草案明确draft-center-persistence-not-implemented，不再建立第二套合同；跨run变量scope没有版本/runId维度，null与空chat不能合并。"},
        {"source": current + "tests/test_original_engine.py", "lines": [186, 282], "fact": "已有历史测试源码覆盖中心shim set/get、本地fallback、null/empty chat、缺scope、拒ACK、取消/租约子任务清理。本次只读取，未重新执行这些测试，不能作为新联机或全迁移通过。"},
    ],
    "minimumBoundaryCandidates": [
        "先由中心真实应用/会话关系产生并绑定scope；当前debug纯图没有App，不能填空App、把flowId/workflowVersion/runId当App，或悄悄变成每run私有变量。若采用明确不同的调试scope需先写入现唯一设计决定。",
        "复用现有中心业务MySQL、RunLease服务身份/活租约/执行已begin/当前发起者权限；中心推导真实scope，严格核nodeId/method/声明variableName，不接受任意用户/应用/会话或空间字段。",
        "按scope持久revision、值和幂等请求回执；scope分列并显式区分chat null与空，避免原Redis字符串拼接碰撞。set/clear由真实CAS+去重确认，保null任意JSON字节、拒秘密/超预算/非法值；旧值迁移不执行不可信pickle。",
        "复用现有顶层DAG/pool地址冲突与路径预算，先只接顶层5pure+node-variable、无activeRetry/iteration/model/history；根据原Literal核method。已有get的原pool回退、变量顺序和逐项写事实保留，不写替代引擎。",
        "Worker在begin前验证所需scope/固定图/能力，已发生变量写但ACK未知时停止且由中心账本核对；不能假FAILED回滚事实，也不能重放整个attempt。正式返回事件及原页投影另受同一许可/版本绑定。",
        "实际测试应包含同scope跨run/跨version复用、不同uid/app/chat隔离、null/空chat区别、false/0/null值、缺全局fallback、声明多输入逐项写/局部失败、CAS并发/同key同请求复验与异请求409、撤权/过期/取消/不重复执行。测试实际值与原算法分别核，不能据端点存在称业务通过。",
    ],
    "notEstablished": ["正式变量表/路由/真实App或chat绑定", "真实Redis数据迁入", "新原引擎执行检查", "真实中心HTTP/前端浏览器UAT", "完整迁移业务通过"],
}
if drift:
    raise RuntimeError("只读源码窗口发生变化：" + str(drift))
out = Path(__file__).with_name("原全局变量节点中心接续只读核对.json")
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"report": str(out), "sha256": sha(out.read_bytes()), "sources": len(sources), "drift": len(drift), "algorithmAstEqual": algorithm_equal, "originalFrontendBytesEqual": all(item["byteEqual"] for item in frontend_pairs), "formalVariableRoutes": report["staticChecks"]["formalVariableOpenApiPaths"]}, ensure_ascii=False))
