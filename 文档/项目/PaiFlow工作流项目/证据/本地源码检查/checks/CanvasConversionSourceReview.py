"""保存原纯画布转换的精确源码事实；只改证据 JSON，不执行业务或转换候选。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json

root = Path(__file__).resolve().parents[6]
evidence = Path(__file__).resolve().parent.parent
record = evidence / "原迭代画布执行转换来源核对.json"
original_sha = hashlib.sha256(record.read_bytes()).hexdigest()
data = json.loads(record.read_text(encoding="utf-8"))
prefix = "参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/"
specs = {
    "service/workflow/WorkflowService.java": [
        "private List<Edge> bizEdgesToSysEdges", "private NodeData bizNodeDataToSysNodeData",
        "handleRetryConfig(BizNodeData", "private void inputCopy", "private void outputCopy",
        "private Property bizPropertyToProperty", "private Map<String, Property> bizPropertiesToPropertyMap",
        "public static boolean isInputContentEmpty", "private static boolean isJsonObjEmpty",
        "private JSONObject handleNodeParam"],
    "entity/biz/workflow/node/BizNodeData.java": ["String label;", "JSONObject nodeMeta;", "JSONObject nodeParam;"],
    "entity/biz/workflow/node/BizInputOutput.java": ["String id;", "Boolean required;"],
    "entity/biz/workflow/node/BizSchema.java": ['@JSONField(name = "default")', 'Object dft;', "List<BizProperty> properties;"],
    "entity/biz/workflow/node/BizProperty.java": ["String dft;", "Boolean required;"],
    "entity/biz/workflow/node/BizValue.java": ["String type;", "Object content;"],
    "entity/core/workflow/node/NodeData.java": ["JSONObject nodeMeta;", "JSONObject retryConfig;"],
    "entity/core/workflow/node/InputOutput.java": ["Boolean required;"],
    "entity/core/workflow/node/Schema.java": ['@JSONField(name = "default")', "Map<String, Property> properties;", "Property items;"],
    "entity/core/workflow/node/Property.java": ["Map<String, Property> properties;", "Object required;"],
    "entity/core/workflow/Edge.java": ["String sourceHandle;", "String targetHandle;"],
}
sources = []
for relative, needles in specs.items():
    path = root / (prefix + relative)
    lines = path.read_text(encoding="utf-8").splitlines()
    markers = []
    for needle in needles:
        found = [n for n, line in enumerate(lines, 1) if needle in line]
        assert found, (relative, needle)
        markers.append({"needle": needle, "lines": found})
    sources.append({"path": path.relative_to(root).as_posix(),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "markers": markers})

facts = [
    {"symbol": "bizNodeDataToSysNodeData", "lines": [1571, 1593],
     "fact": "仅投影 nodeMeta/inputs/outputs/nodeParam/retryConfig。原 nodeMeta.aliasName 强制由 label 覆盖；原函数会复用/修改 DTO 对象，新执行副本不能因此改动 raw canvas。root parentId/position 等不决定执行归属。"},
    {"symbol": "inputCopy", "lines": [1608, 1645],
     "fact": "先按 schema.value.content 判空过滤；仅输入的 time(忽略大小写)转 string。array-* 以 Java split('-') 的第一/二段构造 type=array/items.type，第二段不存在回退 object；不把 UI properties 递归进输入 items。BizSchema.item 与 core Schema.items 名称不同，不是原数组 items 来源。value 仅复制 type/content；contentErrMsg 等 UI 字段不进入执行 DTO。"},
    {"symbol": "isInputContentEmpty/isJsonObjEmpty", "lines": [2802, 2885],
     "fact": "null、空白字符串、空集合以及元素全部判空的集合会被过滤；false/0 非空。对象先用 text/content/value/title/name/message/prompt/url/fileUrl/path 的 getString 判非空，再看 default 递归非空、任意直接字符串值非空。非候选键下只有嵌套对象、数字或布尔值的对象可能仍判空，不能以一般 truthiness/任意深层内容替代此规则。"},
    {"symbol": "outputCopy", "lines": [1647, 1696],
     "fact": "不按 value.content 判空过滤输出，不把 time 转 string。array-object 把 properties 列表递归成 items.properties，并仅把其直接 property.required=true 的名称放 items.required(可为空数组)。其他 typed array 不构建对象属性；非 typed array 构造 schema.properties。"},
    {"symbol": "outputCopy description/default", "lines": [1688, 1692],
     "fact": "只有 schema.description==null 时令 description=default==null?null:default.toString()，并清 default；空字符串 description 不触发此分支，既有 description 保留 default。BizSchema/CoreSchema dft 使用 JSONField 与 JsonProperty 映射到 default。该规则也作用于主开始节点输出，不应把执行 fixture 的 optional default 等同于原 canvas 必然保留的 default。"},
    {"symbol": "bizPropertyToProperty/bizPropertiesToPropertyMap", "lines": [2532, 2585],
     "fact": "属性按 name 建 Map；重复 name 后写覆盖前写。递归仅 type/properties/items；Property DTO 不含 BizProperty.id/name/default/description。嵌套 required 收集代码被注释，不能声称每层 required 均有原实现依据。array-* 子属性将第二段设 items.type，并把子 properties 放 items.properties；原 split[1] 无回退，畸形 array- 可能抛错。"},
    {"symbol": "bizEdgesToSysEdges", "lines": [1541, 1556],
     "fact": "source/target 映射 sourceNodeId/targetNodeId。sourceHandle 非空且包含任意 intent-one-of/branch_one_of/fail_one_of 时加 intent_chain| 前缀(containsAny，不仅前缀匹配)；targetHandle 原值复制。"},
    {"symbol": "handleRetryConfig", "lines": [1597, 1605],
     "fact": "customOutput 非空白字符串按 JSONObject.parseObject 转对象，异常不在该方法吞掉；空白字符串原值保留。新纯准入可明确拒 shouldRetry=true 来保持展开预算边界，而不能把未知重试费用或次数标已验证。"},
    {"symbol": "handleNodeParam/checkAndFixNode", "lines": [2888, 2915],
     "fact": "原通用 handleNodeParam 去 configs，并有 topN/patchId/dbId 资源专用转换；buildWorkflowData 还调用 checkAndFixNode 注入 app/key/provider 等。纯转换只投影已核纯节点参数，不能复制这些外部密钥/环境注入。IterationStartNodeId 原前端直接提供，后端不从 parentId 推断。"},
]
fixtures = [
    "真实 React typed-array canvas：主开始 array-string 输出、迭代直接 ref、内部已配置 string item，产出执行 array/items.type=string；原 raw/viewport/parentId/未知根字段及字符串保存字节不变。",
    "输入空值表：null/' '/[]/[null,' ']过滤，false/0/[false]保留，{opaque:{x:'v'}}按原判空，{opaque:'v'}保留；time/Time 仅 input 转 string。",
    "输出 object/array-object 两层属性：第一层 required 保留，嵌套 required/default/id 按原 DTO 不传；同名 property 原覆盖行为与候选更严格拒绝须明确区分。",
    "输出 default/description 表：description 缺失/null 时 default 转 description 后清除；description='' 或非空时 default 保留；包括主开始输出，避免未经原转换的执行 fixture 假证默认值兼容。",
    "branch sourceHandle 包含匹配词的前/中/后位置均加 intent_chain|；普通 sourceHandle/targetHandle 原值保持；新 compiler 不二次给已经执行形状重复加前缀。",
    "retry customOutput 有效对象字符串、空白与畸形分别转换/保留/拒绝；shouldRetry=true 新批明确未准入，不声称原重试已迁完。",
    "旧 execution_codec NULL 版本继续旧 V1 hash 重建，新 V2 不改历史 immutable execution_json/raw/hash，资源 descriptor 重建也选择记录 codec，禁止按默认 V2 重算旧行。",
]
data["pureConversionReview"] = {
    "recordedAt": datetime.now(timezone.utc).isoformat(), "beforeEvidenceSha256": original_sha,
    "sources": sources, "facts": facts, "minimalFixtures": fixtures,
    "status": "原源码只读事实与建议；未执行候选转换器或 Maven，不证明本批原页面可派发",
    "productionSourceModified": False, "network": False, "databaseCalls": 0,
    "servicesStarted": False, "targetWrites": 0,
}
record.write_text(json.dumps(data, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")

script = evidence / "checks/PureVariableImplicitReview.py"
log = evidence / "纯迭代隐式错误字段-独立检查.log"
probe = json.loads(log.read_text(encoding="utf-8").strip())
implicit = {"schemaVersion": "paiflow.pure-variable.implicit.readonly.v1",
    "recordedAt": datetime.now(timezone.utc).isoformat(),
    "scope": "实际原 VariablePool.add_variable 隐式错误字段只读反例；非 Factory/中心业务通过",
    "command": ".venv/Scripts/python.exe -B checks/PureVariableImplicitReview.py <workspace>",
    "exitCode": 0, "probe": probe,
    "files": [{"path": p.relative_to(root).as_posix(), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in [script,log]],
    "recommendation": "全图 outputs 地址去重须包含每节点自动 errorCode/errorMessage；同节点声明同名允许，跨节点复合地址撞名必须拒绝。inputs/outputs 是两映射，不能错误拒同节点合法 input/output 同名。",
    "limits": "未把原 do_validate 的 startswith 前缀污染直接当作跨读；该差异需独立反例。未执行新 Java/Python 准入候选。"}
implicit_path=evidence / "纯迭代隐式错误字段独立核对.json"
implicit_path.write_text(json.dumps(implicit, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
for p in [record,implicit_path]:
    print(json.dumps({"path": p.relative_to(root).as_posix(), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}, ensure_ascii=False))
