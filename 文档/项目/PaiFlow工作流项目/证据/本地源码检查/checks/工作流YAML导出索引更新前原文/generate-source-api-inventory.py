"""源码接口核对：javac/TypeScript/Python AST，仅只读解析，无服务或数据库调用。"""
from __future__ import annotations
import ast
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

WORKSPACE = Path(__file__).resolve().parents[2]
REFERENCE = WORKSPACE / "参考项目/PaiFlow-main"
DOC = WORKSPACE / "文档/项目/PaiFlow工作流项目/附录"
HERE = Path(__file__).resolve().parent


def rel(path: Path) -> str:
    return path.relative_to(WORKSPACE).as_posix()


def dump(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def expr(node: ast.AST | None) -> str | None:
    return ast.unparse(node) if node else None


def literal(node: ast.AST | None, default=None):
    try:
        return ast.literal_eval(node)
    except (ValueError, TypeError, SyntaxError):
        return default


def source_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def planned_consumer_groups() -> dict[tuple[str, str], dict]:
    """继承既有365函数业务映射；只作为拟接入归属，不提升实现状态。"""
    document = WORKSPACE / "文档/项目/PaiFlow工作流项目/源码覆盖索引.md"
    groups, service = {}, None
    in_section = False
    for line_no, line in enumerate(document.read_text(encoding="utf-8-sig").splitlines(), 1):
        if line.startswith("## 9."):
            in_section = True
        elif in_section and line.startswith("## 10."):
            break
        if not in_section:
            continue
        header = re.match(r"### (.+) 服务消费者对应", line)
        if header:
            service = header.group(1)
        row = re.match(r"\| `([^`]+)` \| `([^`]+)` \| (.+) \| ([^|]+) \|", line)
        if row and service:
            groups[(service, row.group(1))] = {"plannedTargetGroup": row.group(3).strip(), "chineseAcceptanceNumbers": row.group(4).strip(), "source": rel(document), "line": line_no, "status": "design_mapping_not_implementation_evidence"}
    return groups


def java_binding(param: dict) -> dict:
    anns = param["annotations"]
    kind = "implicit_query_or_model_attribute"
    for annotation, binding in (("RequestBody", "body"), ("RequestParam", "query"), ("PathVariable", "path"), ("RequestHeader", "header"), ("RequestPart", "multipart"), ("ModelAttribute", "form_or_query"), ("CookieValue", "cookie")):
        if any(a.startswith("@" + annotation) for a in anns):
            kind = binding
            break
    if any(x in param["type"] for x in ("HttpServletRequest", "HttpServletResponse", "Principal")):
        kind = "framework_injected"
    elif "MultipartFile" in param["type"] and kind == "implicit_query_or_model_attribute":
        kind = "multipart_implicit"
    return {**param, "binding": kind, "required": "declared_in_annotation_or_type_requires_runtime_validation", "wireName": "annotation value when declared; otherwise Java parameter name subject to compiler -parameters"}


def java_enrich(index: dict, target=False) -> list[dict]:
    types = {t["name"]: t for t in index["types"]}
    simple_types = defaultdict(list)
    for t in index["types"]:
        simple_types[t["simpleName"]].append(t)

    def refs(expression: str, owner: dict) -> list[dict]:
        result = []
        for name in dict.fromkeys(re.findall(r"\b[A-Za-z_$][\w$]*\b", expression or "")):
            if name in {"String", "Object", "Long", "Integer", "Boolean", "Double", "Float", "Byte", "Short", "Character", "Void", "List", "Map", "Set", "Collection", "ResponseEntity", "CompletableFuture", "void", "byte", "int", "long", "boolean", "double", "float", "char", "short", "extends", "super", "var"}:
                continue
            candidates = simple_types.get(name, [])
            imports = owner.get("imports", [])
            exact = [t for t in candidates if t["name"] in imports]
            local = [t for t in candidates if t["name"].rsplit(".", 1)[0] == owner["controller"].rsplit(".", 1)[0] or t["name"].startswith(owner["controller"] + ".")]
            wild = [t for t in candidates if any(i.endswith(".*") and t["name"].startswith(i[:-1]) for i in imports)]
            selected = exact or local or wild or candidates
            if len(selected) == 1:
                t = selected[0]
                result.append({"type": name, "resolvedType": t["name"], "file": t["file"], "line": t["line"], "status": "source_declaration_resolved"})
            elif selected:
                result.append({"type": name, "candidates": [{"name": t["name"], "file": t["file"], "line": t["line"]} for t in selected], "status": "ambiguous"})
            else:
                result.append({"type": name, "status": "external_or_generic_type_not_expanded", "imports": [i for i in imports if i.endswith("." + name)]})
        return result

    enriched = []
    for e in index["endpoints"]:
        e = dict(e)
        e["sourceService"] = "ruoyi-paiflow" if target else ("console-hub-with-toolkit" if "/console/backend/" in e["file"] else "core-workflow-java")
        e["parameters"] = [{**java_binding(p), "schemaRefs": refs(p["type"], e)} for p in e["parameters"]]
        e["returnSchemaRefs"] = refs(e["returnType"], e)
        e["transportKind"] = "websocket" if e["method"] == "WEBSOCKET" else ("sse_stream" if "SseEmitter" in (e["returnType"] or "") else "binary_or_response_side_effect" if any("HttpServletResponse" in p["type"] for p in e["parameters"]) else "declared_response_type_requires_envelope_review")
        e["fieldContractStatus"] = "source_parameter_and_type_declarations_only_not_runtime_contract_verified"
        e["migrationStatus"] = "source_route_declared_business_and_runtime_unverified" if target else "not_implemented"
        e["verificationEvidence"] = [{"file": e["file"], "line": e["line"], "kind": "controller_source"}]
        enriched.append(e)
    return enriched


def python_inventory() -> dict:
    root = REFERENCE / "core"
    modules, diagnostics = {}, []
    for path in sorted(root.rglob("*.py")):
        if any(x in path.parts for x in ("tests", "scripts", "__pycache__")) or path.name.startswith("test_"):
            continue
        name = path.relative_to(root).with_suffix("").as_posix().replace("/", ".")
        if name.endswith(".__init__"):
            name = name[:-9]
        try:
            tree = ast.parse(path.read_text(encoding="utf-8-sig"), filename=str(path))
        except SyntaxError as error:
            diagnostics.append({"file": rel(path), "line": error.lineno, "message": error.msg})
            continue
        imports = {}
        for node in tree.body:
            if isinstance(node, ast.ImportFrom):
                prefix = node.module or ""
                if node.level:
                    parts = name.split(".")[:-node.level]
                    prefix = ".".join(parts + ([prefix] if prefix else []))
                elif name.startswith("agent.") and prefix.startswith(("api.", "service.", "domain.", "infra.", "repository.", "exceptions.", "consts.")):
                    prefix = "agent." + prefix
                for alias in node.names:
                    imports[alias.asname or alias.name] = (prefix, alias.name)
        modules[name] = {"path": path, "tree": tree, "imports": imports}

    routers, route_defs, includes, schema_defs = {}, [], [], {}
    for name, info in modules.items():
        path, tree = info["path"], info["tree"]
        for node in ast.walk(tree):
            if isinstance(node, (ast.Assign, ast.AnnAssign)):
                value = node.value
                if not isinstance(value, ast.Call) or expr(value.func).split(".")[-1] not in {"APIRouter", "FastAPI"}:
                    continue
                targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                for t in targets:
                    if isinstance(t, ast.Name):
                        key = (name, t.id)
                        prefix = next((literal(k.value) for k in value.keywords if k.arg == "prefix"), "")
                        routers[key] = {"prefix": prefix, "factory": expr(value.func), "file": rel(path), "line": node.lineno, "scope": "lexical_ast_declaration"}
            if isinstance(node, ast.ClassDef):
                fields = []
                for field in node.body:
                    if isinstance(field, ast.AnnAssign) and isinstance(field.target, ast.Name):
                        alias = None
                        if isinstance(field.value, ast.Call):
                            alias = next((literal(k.value) for k in field.value.keywords if k.arg == "alias"), None)
                        fields.append({"name": field.target.id, "type": expr(field.annotation), "line": field.lineno, "hasDefaultDeclaration": field.value is not None, "alias": alias})
                schema_defs[name + "." + node.name] = {"name": name + "." + node.name, "file": rel(path), "line": node.lineno, "bases": [expr(x) for x in node.bases], "fields": fields, "imports": info["imports"]}
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "include_router":
                if isinstance(node.func.value, ast.Name) and node.args:
                    includes.append({"parent": (name, node.func.value.id), "child": (name, expr(node.args[0])), "prefix": next((literal(k.value) for k in node.keywords if k.arg == "prefix"), ""), "file": rel(path), "line": node.lineno})
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                for decorator in node.decorator_list:
                    if not isinstance(decorator, ast.Call) or not isinstance(decorator.func, ast.Attribute) or decorator.func.attr not in {"get", "post", "put", "delete", "patch", "head", "options", "websocket", "api_route"} or not isinstance(decorator.func.value, ast.Name):
                        continue
                    arg_nodes = list(node.args.posonlyargs) + list(node.args.args)
                    defaults = [None] * (len(arg_nodes) - len(node.args.defaults)) + list(node.args.defaults)
                    params = []
                    for p, default in zip(arg_nodes, defaults):
                        typ = expr(p.annotation)
                        binding = "implicit_body_or_query_by_fastapi_type"
                        default_call = expr(default.func).split(".")[-1] if isinstance(default, ast.Call) else None
                        if default_call in {"Body", "Query", "Path", "Header", "Cookie", "File", "Form", "Depends"}:
                            binding = default_call.lower()
                        elif typ in {"Request", "Response", "BackgroundTasks"}:
                            binding = "framework_injected"
                        elif typ in {"str", "int", "float", "bool"}:
                            binding = "path" if "{" + p.arg + "}" in (literal(decorator.args[0], "") if decorator.args else "") else "query"
                        alias = next((literal(k.value) for k in default.keywords if k.arg == "alias"), None) if isinstance(default, ast.Call) else None
                        params.append({"name": p.arg, "type": typ, "binding": binding, "alias": alias, "line": p.lineno, "hasDefaultDeclaration": default is not None, "defaultFactory": default_call})
                    verbs = literal(next((k.value for k in decorator.keywords if k.arg == "methods"), None), ["ANY"]) if decorator.func.attr == "api_route" else [decorator.func.attr.upper()]
                    calls = sorted(set(expr(x.func) for x in ast.walk(node) if isinstance(x, ast.Call)))
                    body_text = ast.get_source_segment(path.read_text(encoding="utf-8-sig"), node) or ""
                    for verb in verbs:
                        route_defs.append({"router": (name, decorator.func.value.id), "method": verb, "path": literal(decorator.args[0]) if decorator.args else next((literal(k.value) for k in decorator.keywords if k.arg == "path"), None), "handler": node.name, "file": rel(path), "line": decorator.lineno, "handlerLine": node.lineno, "module": name, "parameters": params, "returnType": expr(node.returns), "responseModelExpression": next((expr(k.value) for k in decorator.keywords if k.arg == "response_model"), None), "bodyCalls": calls, "sourceSignals": ["todo_marker_in_handler"] if "TODO" in body_text else []})

    def resolve(key, seen=None):
        seen = seen or set()
        if key in seen:
            return None
        seen.add(key)
        if key in routers:
            return key
        module, name = key
        if module not in modules:
            return None
        imp = modules[module]["imports"].get(name)
        return resolve(imp, seen) if imp else None

    adjacency = defaultdict(list)
    unresolved = []
    for edge in includes:
        parent, child = resolve(edge["parent"]), resolve(edge["child"])
        if parent and child:
            adjacency[parent].append({**edge, "childResolved": child})
        else:
            unresolved.append({**edge, "parentResolved": parent, "childResolved": child})
    roots = [key for key, value in routers.items() if value["factory"].split(".")[-1] == "FastAPI"]
    mounted = []

    def walk(key, prefix, chain, origin, stack=None):
        stack = stack or set()
        if key in stack:
            unresolved.append({"cycle": list(stack), "node": key})
            return
        own = routers[key]
        combined = prefix + (own["prefix"] or "")
        for definition in route_defs:
            if resolve(definition["router"]) == key:
                d = dict(definition)
                d["path"] = combined + (d["path"] or "<UNRESOLVED>")
                d["id"] = "python:" + origin[0] + ":" + d["method"] + ":" + d["path"] + ":" + str(d["line"])
                d["sourceService"] = origin[0]
                d["mountChain"] = chain + [{"file": own["file"], "line": own["line"], "prefix": own["prefix"]}]
                d["migrationStatus"] = "not_implemented"
                d["fieldContractStatus"] = "source_parameter_and_pydantic_declarations_only_not_runtime_contract_verified"
                mounted.append(d)
        for edge in adjacency[key]:
            walk(edge["childResolved"], combined + (edge["prefix"] or ""), chain + [{"file": edge["file"], "line": edge["line"], "includePrefix": edge["prefix"]}], origin, stack | {key})
    for root_key in roots:
        walk(root_key, "", [], root_key)
    # 仅从真实应用根可达的路由计入已挂载；定义未挂载另列。
    mounted_origins = {(e["file"], e["line"], e["method"]) for e in mounted}
    unmounted = [d for d in route_defs if (d["file"], d["line"], d["method"]) not in mounted_origins]
    for endpoint in mounted:
        owner_imports = modules[endpoint["module"]]["imports"]
        for parameter in endpoint["parameters"]:
            parameter["schemaRefs"] = []
            for token in dict.fromkeys(re.findall(r"\b[A-Z]\w*\b", parameter["type"] or "")):
                imported = owner_imports.get(token)
                name = ".".join(imported) if imported else endpoint["module"] + "." + token
                if name in schema_defs:
                    schema = schema_defs[name]
                    parameter["schemaRefs"].append({"resolvedType": name, "file": schema["file"], "line": schema["line"], "status": "source_declaration_resolved"})
        endpoint["returnSchemaRefs"] = []
        for token in dict.fromkeys(re.findall(r"\b[A-Z]\w*\b", endpoint["returnType"] or "")):
            imported = owner_imports.get(token)
            name = ".".join(imported) if imported else endpoint["module"] + "." + token
            if name in schema_defs:
                schema = schema_defs[name]
                endpoint["returnSchemaRefs"].append({"resolvedType": name, "file": schema["file"], "line": schema["line"], "status": "source_declaration_resolved"})
    return {"filesParsed": len(modules), "endpoints": mounted, "unmountedRouteDefinitions": unmounted, "unresolvedMounts": unresolved, "routeDefinitions": len(route_defs), "routerDeclarations": list(routers.values()), "types": list(schema_defs.values()), "parseDiagnostics": diagnostics}


def route_key(path: str | None) -> str | None:
    if not path:
        return None
    path = re.sub(r"\$\{[^}]*\}", "{dynamic}", path.split("?", 1)[0])
    if re.match(r"^https?://", path):
        return None
    if not path.startswith("/"):
        return None
    return path.rstrip("/") or "/"


def route_match(front: dict, endpoint: dict) -> bool:
    fp, ep = route_key(front.get("url", {}).get("value")), endpoint["path"].rstrip("/") or "/"
    if fp is None or "<UNRESOLVED" in ep or front["method"] == "DYNAMIC":
        return False
    if front["method"] != endpoint["method"] and endpoint["method"] != "ANY":
        return False
    # 不把动态路径段推断成任意深度；只在同一段匹配占位符。
    fparts, eparts = fp.split("/"), ep.split("/")
    if len(fparts) != len(eparts):
        return False
    return all(f == e or re.fullmatch(r"\{[^{}]+\}", e) or re.fullmatch(r"\{[^{}]+\}", f) for f, e in zip(fparts, eparts))


def manual_gaps() -> list[dict]:
    cases = [
        ("LOCAL_LOGIN_MOCK", "原本地登录仅生成固定开发令牌；迁移改委托若依，旧假令牌不是需保留业务行为。", "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/controller/auth/LocalLoginController.java", "mock-local-dev-token"),
        ("PUBLISH_API_EMPTY", "API 发布策略含空成功占位，必须实现受限调用、凭据、回读与撤销。", "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/strategy/publish/impl/ApiPublishStrategy.java", "return ApiResult.success(null)"),
        ("PUBLISH_FEISHU_EMPTY", "飞书发布策略含空成功占位，原存在路由不能证明渠道闭环。", "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/strategy/publish/impl/FeishuPublishStrategy.java", "return ApiResult.success(null)"),
        ("PUBLISH_MCP_STUB", "MCP 发布策略未实现，与消费 MCP/目录查询是不同能力。", "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/strategy/publish/impl/McpPublishStrategy.java", "MCP publishing not implemented"),
        ("JAVA_RESUME_ABSENT", "javac AST 对 core-workflow-java 的全部 Controller 路由解析未发现 resume 入口；现有 Java 回调的 interrupt/resume 事件标志不等于实现恢复 API。目标需实现或按引擎明确拒绝，不能沿用设计中曾写的空端点表述。", "core-workflow-java/src/main/java/com/iflytek/astron/workflow/controller/WorkflowFrontendController.java", "class WorkflowFrontendController"),
        ("RPA_EXTERNAL", "原 RPA 节点依赖外部执行服务，core/plugin/rpa 未随源码提供，需真实 Runner 协议。", "core/workflow/engine/nodes/rpa/rpa_node.py", "RPA"),
        ("SPARKDB_EXTERNAL", "数据库 DDL/DML 转发依赖外部 SparkDB 服务，需落实逻辑数据表与 PG 语义转换。", "console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/service/database/DatabaseService.java", "execDDL"),
        ("KNOWLEDGE_EXTERNAL", "知识 HTTP 客户端引用抽取/检索服务；源码客户端不证明服务本体可用。", "core/workflow/engine/nodes/knowledge/knowledge_node.py", "url"),
        ("IFLYTEK_CHANNEL", "原讯飞 AIUI/开放平台发布与同步调用必须保留；不可由五主渠道统计掩盖。", "console/frontend/src/services/flow.ts", "getAiuiAgents"),
        ("EVALUATION_EXTERNAL", "评测/训练集旧调用可在前端存在但本地 Controller 未完整提供，需固定用例评测闭环。", "console/frontend/src/services/flow.ts", "addChatToSet"),
        ("CASDOOR_SDK", "原 OAuth SDK 的换令牌、刷新与重定向是静态 HTTP 名称扫描外的外部协议；迁移以平台身份替换并另核对 SSO。", "console/frontend/src/config/casdoor.ts", "new Sdk"),
        ("SIGNED_OBJECT_UPLOAD", "签名对象 URL 的 PUT 绕过中心 HTTP 拦截器，需要对象存储授权、mime/hash与回执校验。", "console/frontend/src/services/chat.ts", "axios.create().put"),
        ("TEAM_ENTITLEMENT_STUB", "原订单工具硬返回有效套餐，并伪造ENTERPRISE与一年到期；目标缺真实套餐/容量策略时必须明确失败，不能继承为正式付费事实。", "console/backend/commons/src/main/java/com/iflytek/astron/console/commons/util/space/OrderInfoUtil.java", "return true"),
        ("TEAM_CERTIFICATION_UNAVAILABLE", "原商业认证直接抛UnsupportedOperationException；认证与团队基本成员管理是不同能力。", "console/backend/commons/src/main/java/com/iflytek/astron/console/commons/service/space/impl/EnterpriseServiceImpl.java", "throw new UnsupportedOperationException"),
        ("INVITATION_LEGACY_AES_LINK", "原邀请链接依赖源码固定AES密钥；中心没有迁入该密钥，受控链接、授权和真实通知仍需接通，不能声称旧链接可用。", "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/service/space/impl/InviteRecordBizServiceImpl.java", "AESUtil.decrypt(param, AES_KEY)"),
        ("INVITATION_BATCH_RESULT_UPLOAD", "批量邀请查人包含真实容量校验、Excel用户结果文件和S3上传resultUrl；缺容量/受控成果存储不能返回空链接冒充完整导入。", "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/service/space/impl/InviteRecordBizServiceImpl.java", 's3ClientUtil.uploadObject("space/"'),
        ("AGENT_CREATE_RETURN_CONFLICT", "原BotCreateController声明Integer，原config-base创建发布消费者却取res.botId；目标明确返回必要兼容对象，不能据原声明声称消费者已闭环。", "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/controller/bot/BotCreateController.java", "public ApiResult<Integer> createBot"),
        ("AGENT_TEMPLATE_BINDING_DATA_ABSENT", "原工作流模板创建需要实体与可信UserLangChainInfo/Workflow绑定；schema只有maas_template和workflow_template_group建表，无模板种子，不能公共化用户私有画布。", "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/service/workflow/impl/BotMaasServiceImpl.java", "createFromTemplate"),
        ("APP_REQUIRES_REAL_TENANT", "正式App必须获得真实Tenant app_id/凭据并核对授权；旧App明文key/secret不能迁入或用随机字符串生成发布成功。", "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/service/publish/impl/PublishApiServiceImpl.java", "tenantService.createApp"),
    ]
    result = []
    for cid, description, local, needle in cases:
        path = REFERENCE / local
        # 历史路径可能与实际目录不同，只从同名源码定位，缺失明确列出。
        if not path.exists():
            candidates = list(REFERENCE.rglob(path.name))
            if len(candidates) == 1:
                path = candidates[0]
        found = path.exists()
        line = None
        if found:
            lines = path.read_text(encoding="utf-8-sig").splitlines()
            line = next((i + 1 for i, text in enumerate(lines) if needle in text), None)
        result.append({"id": cid, "description": description, "file": rel(path), "line": line, "sourceFound": found, "needleFound": line is not None, "migrationStatus": "not_complete", "evidenceKind": "source_manual_exception_candidate_requires_behavior_validation"})
    return result


def target_boundaries() -> list[dict]:
    """明确源码拒绝/依赖边界的人工证据；不提升编译、HTTP联机或完整业务验证状态。"""
    base = "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/"
    items = [
        ("POST", "/paiflow/legacy/enterprise/create", "requires_verified_capacity_policy_no_default_implementation", "真实套餐与容量端口没有默认实现，缺资料503、零正式创建；隔离测试套餐不代表付费事实。", "service/SpaceService.java", "public long createTeam"),
        ("GET", "/paiflow/legacy/enterprise/check-need-create-team", "requires_verified_entitlement_for_users_without_owned_team", "已有正式创建团队可返回0，其他用户缺真实套餐策略时503，不伪造服务类型。", "service/SpaceService.java", "public int checkNeedCreateTeam"),
        ("GET", "/paiflow/legacy/enterprise/check-certification", "explicit_unavailable_503", "原商业认证未接通，明确503。", "adapter/legacy/LegacyEnterpriseController.java", "public LegacyResult<Boolean> certification"),
        ("GET", "/paiflow/legacy/space-user/get-user-limit", "requires_verified_capacity_policy_no_default_implementation", "原FREE/PRO/团队套餐容量依据待映射；不能采用前端48/1000回退值。", "service/SpaceService.java", "public int maximumMembers"),
        ("GET", "/paiflow/legacy/enterprise-user/get-user-limit", "requires_verified_capacity_policy_no_default_implementation", "真实团队容量策略缺失时503。", "service/SpaceService.java", "public int maximumMembers"),
        ("POST", "/paiflow/legacy/invite-record/space-invite", "requires_verified_capacity_policy_notification_not_connected", "邀请保存/CAS/成员事务有源码和隔离检查，缺容量503；通知状态not_connected，不宣称送达。", "service/MembershipService.java", "public List<Long> invite"),
        ("POST", "/paiflow/legacy/invite-record/enterprise-invite", "requires_verified_capacity_policy_notification_not_connected", "邀请保存/CAS/成员事务有源码和隔离检查，缺容量503；通知状态not_connected。", "service/MembershipService.java", "public List<Long> invite"),
        ("POST", "/paiflow/legacy/invite-record/accept-invite", "accept_requires_verified_capacity_policy", "已授权邀请人与状态CAS仍检查真实容量，缺策略不能加入；拒绝/撤销不伪造成员。", "service/MembershipService.java", "public void respondInvitation"),
        ("GET", "/paiflow/legacy/invite-record/get-invite-by-param", "explicit_unavailable_503", "原固定AES链接未接通，要求新的受控链接与通知，不复制固定密钥。", "adapter/legacy/LegacyInvitationController.java", "public LegacyResult<JSONObject> oldLink"),
        ("GET", "/paiflow/legacy/workflow/clone", "local_agent_workflow_copy_checked_unbound_dependency_unavailable_503", "真实Agent绑定存在时完整同域复制已有H2事务/契约证据；未绑定仍503，正式App/资源/发布未接通，不能当作生产可用。", "service/AgentService.java", "public JSONObject cloneBoundWorkflow"),
        ("POST", "/paiflow/legacy/bot/create", "local_agent_metadata_persistence_checked_resources_unconfigured", "原config-base完整字段与res.botId形状经本地技术检查；仅未发布草稿，自定义模型/知识资源/正式App仍无绑定。", "service/AgentService.java", "public JSONObject create"),
        ("POST", "/paiflow/legacy/bot/update", "local_agent_workflow_revision_persistence_checked_runtime_unverified", "原read revision和绑定workflowRevision条件已由H2事务/MVC验证；缺版本409，资源与渠道未验证。", "service/AgentService.java", "public JSONObject update"),
        ("POST", "/paiflow/legacy/workflow/base-save", "local_agent_workflow_single_binding_checked_runtime_unverified", "真实Agent/Workflow同事务创建更新，不从ext.botId建立引用；当前草稿，原页面与Provider未联机验证。", "adapter/legacy/LegacyAgentWorkflowController.java", "public LegacyResult<JSONObject> save"),
        ("POST", "/paiflow/legacy/workflow/copy-bot", "local_agent_workflow_copy_checked_published_application_unverified", "同域完整复制已执行真实Mapper回滚/名称约束/原画布保全检查，返回原Void；不复制正式App、凭据与发布状态。", "service/AgentService.java", "public JSONObject copy"),
        ("POST", "/paiflow/legacy/publish/bots/{botId}", "explicit_publication_ports_unavailable_503", "MARKET/API/MCP/微信/飞书与真实回执未接通，明确拒绝；不能迁原TODO空成功。", "adapter/legacy/LegacyAgentWorkflowController.java", "public LegacyResult<Void> publish("),
        ("POST", "/paiflow/legacy/workflow/publish", "explicit_tenant_application_publication_unavailable_503", "Tenant App/API发布未接通，不生成正式App/secret/发布成功。", "adapter/legacy/LegacyAgentWorkflowController.java", "public LegacyResult<Void> publishWorkflow"),
        ("GET", "/paiflow/legacy/workflow/bot/templateGroup", "explicit_workflow_template_source_data_unavailable_503", "原工作流分组/模板没有种子与可信绑定资料，不能用用户工作流伪装公共模板。", "adapter/legacy/LegacyAgentWorkflowController.java", "public LegacyResult<Void> templateGroup"),
        ("POST", "/paiflow/legacy/workflow/bot/templateList", "explicit_workflow_template_source_data_unavailable_503", "仅原prompt/type实际种子迁入，工作流模板实体未配置。", "adapter/legacy/LegacyAgentWorkflowController.java", "public LegacyResult<Void> template("),
        ("POST", "/paiflow/legacy/workflow/bot/createFromTemplate", "explicit_workflow_template_source_data_unavailable_503", "缺实际模板与可信绑定资料，创建明确503、零Agent/画布写入。", "adapter/legacy/LegacyAgentWorkflowController.java", "public LegacyResult<Void> createTemplate"),
        ("POST", "/paiflow/legacy/workflow/bot/get-inputs-type", "local_bound_workflow_input_metadata_mapper_mvc_checked_live_runtime_unverified", "原Start.outputs typed读取投影与12 nullable字段数组、AGENT_USER_INPUT响应保留、元数据过滤和同域Agent CAS已有隔离Mapper/MVC检查；ETag为实际Agent revision、X-PaiFlow-Agent-Id为实际ID。仅本地元数据，不调用原远端Maas、不创建正式App、不证明文件上传/Provider或页面联机。", "service/AgentService.java", "public InputTypesResult legacyInputTypes"),
        ("POST", "/paiflow/legacy/workflow/build", "fixed_debug_snapshot_mapper_mvc_checked_live_runtime_unverified", "同次build key与已读revision固定不可变版本/receipt，普通构建同步现单一草稿；随机H2/Mapper/MVC已检查，但原页面、Worker和真实数据库未联机，正式App/资源不因此可用。", "service/WorkflowService.java", "public JSONObject debugBuild"),
        ("POST", "/paiflow/legacy/workflow/add-comparisons", "fixed_comparison_snapshot_mapper_mvc_checked_live_runtime_unverified", "比较UUID、body/hash和同次build key绑定独立固定快照，不更改已选正式版本/草稿；已有隔离技术证据，未证明原比较页面或真实运行成功。", "service/WorkflowService.java", "public JSONObject debugBuild"),
        ("POST", "/paiflow/legacy/workflow/chat", "bounded_legacy_debug_stream_mapper_mvc_checked_live_runtime_unverified", "回执与固定版本/输入/requestKey创建或重读同一Run，SSE runId:seq及两个真实绑定头；支持纯顶层、静态QA或固定输入的单层纯迭代。迭代以同一固定DSL/input核节点及owner:批次索引，24个原节点帧不制造全局结束，中心终态才完成；模型/资源混合、嵌套和迭代问答仍未准入。已有隔离H2/MVC与原消费者AST证据，未验证真实JWT/Redis/HTTP/Worker/页面。", "adapter/legacy/LegacyDebugBridgeService.java", "public Attachment chat"),
        ("POST", "/paiflow/legacy/workflow/resume", "bound_checkpoint_reply_mapper_mvc_checked_live_runtime_unverified", "仅静态QA核同run、真实CP、最初revision和固定replyKey；reply-only返回真实已受理状态，后续SSE仍同Run。单层纯迭代chat准入不包含迭代问答或resume；WAITING须同时期限/租约有效才暂停EOF，ACK不是中心terminal，缺正式联机验证。", "adapter/legacy/LegacyDebugBridgeService.java", "public Reply resume"),
        ("POST", "/paiflow/legacy/workflow/cancel", "bound_run_cancel_mapper_mvc_checked_real_stop_unverified", "普通或comparison同次回执、固定快照与输入严格绑定真实Run，包含已准入的单层纯迭代，返回现有RunService状态；CANCEL_REQUESTED只表示请求受理，已terminal保真实状态，不伪造Provider/Worker已停止或本地detach为取消。", "adapter/legacy/LegacyDebugBridgeService.java", "public RunService.View cancel"),
        ('POST', '/paiflow/legacy/workflow/dialog', 'source_route_declared_business_and_runtime_unverified', '原8字段请求返回chatId字符串或null；type=1仅同域当前用户真实owned Run及固定版本关联，每POST追加一段显示记录，静态QA非终态亦可保存。客户端管理字段不授权，显示正文不成为Run结果；整体20MiB及单字段MEDIUMTEXT UTF8边界、H2事务和MVC已有技术检查。type=2正式App仍503，旧UID/Spark SID历史未导入，未验证真实登录/数据库/页面。', 'service/WorkflowDialogService.java', 'public String save('),
        ('GET', '/paiflow/legacy/workflow/dialog/list', 'source_route_declared_business_and_runtime_unverified', '同域当前平台与工作流查询权限后仅本人type=1最近10条显示记录，按createTime DESC/id DESC稳定排序，返回原13 nullable字段且不泄内部Run/版本绑定。省略type仍核权限后返回空；type=2/App仍503，隔离Mapper/MVC不等于真实历史数据迁移或页面通过。', 'service/WorkflowDialogService.java', 'public List<View> list('),
        ('GET', '/paiflow/legacy/workflow/dialog/clear', 'source_route_declared_business_and_runtime_unverified', '同域当前工作流编辑权限后只软删本人type=1显示记录，返回Integer实际删除计数，重复清除为0；Run/事件/检查点/正式结果不变。省略type仍核权限后返回0，type=2/App仍503，不能用该请求取消Run或清除其他成员历史；旧数据未导入。', 'service/WorkflowDialogService.java', 'public int clear('),
        ("GET", "/paiflow/legacy/textNode/config/list", "local_personal_preference_source_declared_runtime_unverified", "当前平台用户加只读系统目录；系统uid响应为数字-1，个人偏好跨空间使用，真实页面与MySQL排序规则仍未验证。", "service/TextConfigService.java", "public List<View> list"),
        ("POST", "/paiflow/legacy/textNode/config/save", "local_personal_preference_source_declared_runtime_unverified", "个人Mapper事务、去重与原布尔结果；固定种子LF和用户两个字符反斜杠n明确区分，旧库实际HEX/sql_mode仍须核对。", "service/TextConfigService.java", "public View create"),
        ("POST", "/paiflow/legacy/textNode/config/update", "local_personal_preference_source_declared_runtime_unverified", "仅本人非系统行可写，锁后重核平台权限，显式revision发生冲突即拒绝；保留旧不带revision的目录更新协议。", "service/TextConfigService.java", "public View update"),
        ("GET", "/paiflow/legacy/textNode/config/delete", "local_personal_preference_source_declared_runtime_unverified", "物理删除本人目录项，原整数结果；不改已保存画布或版本中的分隔符字面值。", "service/TextConfigService.java", "public void delete"),
        ("POST", "/paiflow/legacy/api/model", "requires_real_credential_reference_and_provider_validation_ports", "无默认CredentialReference/Provider Hello端口时503；候选hash/空间/用户/revision/凭据版本绑定不能由草稿存在自动解锁。", "service/ModelService.java", "public JSONObject validateLegacy"),
        ("POST", "/paiflow/legacy/api/model/local-model", "requires_real_credential_reference_and_local_deployment_ports", "无受控本地部署端口503；有效真实回执仅deployment_pending，不把部署申请冒充运行成功或Agent模型绑定。", "service/ModelService.java", "public JSONObject validateLegacy"),
        ("POST", "/paiflow/legacy/api/model/list", "conditional_custom_model_directory_official_catalog_unavailable", "只对type=2提供同域模型目录；官方目录与微调资源未接通503，不返回空目录伪成功。", "service/ModelService.java", "public Page legacyList"),
        ("GET", "/paiflow/legacy/api/model/detail", "conditional_custom_model_detail_official_finetune_unavailable", "llmSource=0读取同域现有模型与真实revision；官方/微调详情另缺端口，明确拒绝。", "service/ModelService.java", "public JSONObject legacyDetail"),
        ("GET", "/paiflow/legacy/api/model/on", "requires_validated_model_and_current_credential_reference", "只有真实校验、同候选绑定和仍有效的中心凭据批准可启用；没有端口或过期不能以旧布尔回执启用。", "service/ModelService.java", "public JSONObject switchEnabled"),
        ("GET", "/paiflow/legacy/api/model/off", "local_owned_model_disable_source_declared_runtime_unverified", "本人同域模型加原读版本条件停用，legacy沿原更新成功Boolean true，实际enabled=false由详情读取；旧打开表单不由列表/启停提高版本条件。", "service/ModelService.java", "public JSONObject switchEnabled"),
        ("GET", "/paiflow/legacy/api/model/delete", "local_owned_model_soft_delete_source_declared_runtime_unverified", "本人同域加原读版本条件软删除，保全模型版本；不代表解除Agent/工作流正式资源绑定。", "service/ModelService.java", "public void delete"),
        ("GET", "/paiflow/legacy/api/model/category-tree", "source_category_metadata_only_not_formal_resource_binding", "只读取原实际类别资料，缺父节点/自定义正式类别不伪造绑定，草稿不代表Provider资源可用。", "service/ModelService.java", "public List<JSONObject> categoryTree"),
        ("GET", "/paiflow/legacy/api/model/rsa/public-key", "explicit_legacy_rsa_unavailable_503", "不迁原RSA私钥或解码旧密文；中心只接受受控env引用，原重新上架RSA流程明确未接通。", "adapter/legacy/LegacyModelController.java", "public LegacyResult<Void> unavailable"),
        ("GET", "/paiflow/legacy/api/model/local-model/list", "explicit_local_deployment_catalog_unavailable_503", "原部署目录/真实集群未接通503；不列本机任意文件或以空列表冒充可用资源。", "adapter/legacy/LegacyModelController.java", "public LegacyResult<Void> unavailable"),
        ("GET", "/paiflow/legacy/api/model/off-model", "explicit_workflow_model_unbinding_unavailable_503", "原下架包含工作流替换与跨资源业务，当前没有该正式绑定端口，不因模型草稿CRUD存在解除。", "adapter/legacy/LegacyModelController.java", "public LegacyResult<Void> unavailable"),
        ("GET", "/paiflow/legacy/api/model/check-model-base", "explicit_workflow_model_binding_check_unavailable_503", "原模型卡片下架前的工作流绑定核对接口未接通；另有受限OpenAI chat固定版本编译，不等于此旧资源管理协议已完成，不能返回空结果或false。", "adapter/legacy/LegacyModelController.java", "public LegacyResult<Void> unavailable"),
        ("POST", "/internal/paiflow/v1/runs/{runId}/resources/{resourceId}", "fixed_model_descriptor_checked_real_profile_port_unconfigured", "读取中心固定版本公开六字段，核完整run/lease/attempt与当前主体权限；批准模型profile端口无默认实现，不能把描述符或隔离fixture当Provider资源已可用。", "service/ModelOperationService.java", "public JSONObject resource"),
        ("POST", "/internal/paiflow/v1/runs/{runId}/operations", "model_operation_persistence_checked_real_policy_credential_bridge_unconfigured", "固定版本与中心输入派生受控调用候选，真实许可/凭据/Producer bridge没有默认实现；缺端口503，SDK费用调用未联机验证。", "service/ModelOperationService.java", "public JSONObject start"),
        ("POST", "/internal/paiflow/v1/runs/{runId}/operations/{operationId}/frames", "model_frame_cursor_persistence_checked_provider_stream_unverified", "中心持久原帧与单调游标，真实finish引用和尾usage单列，任意HTTP自报不制造完成；当前同域/预算/坏usage回滚检查不证明真实Provider流闭环。", "service/ModelOperationService.java", "public JSONObject frames"),
        ("POST", "/internal/paiflow/v1/runs/{runId}/operations/{operationId}/cancel", "model_cancel_receipt_checked_real_provider_stop_unverified", "停止请求核完整操作绑定；已派发且缺真实停止回执保持EFFECT_UNKNOWN，未派发可依据中心事实取消。本地close不证明Provider停止，真实停止端口未接通。", "service/ModelOperationService.java", "public JSONObject cancel"),
    ]
    # 原ConfigInfo五个读协议只覆盖已审查公开pair。正式表动态读已单独有隔离
    # Mapper/HTTP证据，仍不将147个参考seed、路由存在或技术通过升级为业务验收。
    config_reads = [
        ("/get-list-by-category", "public_configuration_live_mapper_subset_checked_runtime_unverified", "只查询公开category/pair正式表，保留九字段和原无ORDER语义；秘密/环境类别不查询，已知公开类别无有效行503。", "public List<View> list(String requested)"),
        ("/get-by-category-and-code", "public_configuration_live_mapper_subset_checked_runtime_unverified", "只读公开category+code首行，值来自当前正式Mapper；未配置503，原公开URL保全但未验证引用可达。", "public View first"),
        ("/list-by-category-and-code", "public_configuration_live_mapper_subset_checked_runtime_unverified", "正式Mapper读取全部有效公开pair行，超出预算明确拒绝；不以catalog硬编码或截断结果充当运行期配置。", "public List<View> list(String requested,String requestedCode)"),
        ("/tags", "public_tag_directory_source_flag_checked_runtime_unverified", "沿原tool/bot/tool_v2与update_time倒序；完整dev/test profile才按remarks映射ID，未知flag原真实空，不解锁私有资源能力。", "public List<View> tags"),
        ("/workflow/categories", "public_workflow_category_source_seed_absent_503", "原WORKFLOW_CATEGORY没有种子；只读真实唯一有效正式行，否则503，不造分类或假成功。", "public List<String> workflowCategories"),
    ]
    for prefix in ("/paiflow/legacy/config-info", "/paiflow/config-info"):
        for suffix, status, reason, marker in config_reads:
            items.append(("GET", prefix + suffix, status, reason, "service/ConfigInfoService.java", marker))
    result = []
    for method, path, status, reason, local, marker in items:
        file = WORKSPACE / (base + local)
        lines = file.read_text(encoding="utf-8-sig").splitlines() if file.exists() else []
        line = next((i + 1 for i, text in enumerate(lines) if marker in text), None)
        result.append({"method": method, "path": path, "status": status, "description": reason, "file": rel(file), "line": line, "sourceFound": file.exists(), "markerFound": line is not None, "evidenceKind": "manual_source_boundary_not_business_runtime_verification"})
    return result


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="paiflow-api-audit-") as temp:
        temp = Path(temp)
        java_source, java_target, frontend_file = temp / "source-java.json", temp / "target-java.json", temp / "frontend.json"
        target_files = sorted((WORKSPACE / "ruoyi-backend/ruoyi-paiflow/src/main/java").rglob("*.java"))
        target_before = {rel(p): source_hash(p) for p in target_files}
        target_root = WORKSPACE / "ruoyi-backend/ruoyi-paiflow"
        def target_supporting_hashes():
            files = [p for folder in ("src/main/resources", "src/test", "sql") for p in (target_root / folder).rglob("*") if p.is_file()]
            if (target_root / "pom.xml").is_file():
                files.append(target_root / "pom.xml")
            return {rel(p): source_hash(p) for p in sorted(files)}
        target_supporting_before = target_supporting_hashes()
        frontend_target_root = WORKSPACE / "frontend/paiflow-web"
        def target_frontend_hashes():
            files = [p for p in (frontend_target_root / "src").rglob("*") if p.is_file() and p.suffix in {".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"}]
            files += [frontend_target_root / name for name in ("package.json", "package-lock.json", "index.html", "vite.config.ts", "tsconfig.json") if (frontend_target_root / name).is_file()]
            return {rel(p): source_hash(p) for p in sorted(files)}
        target_frontend_before = target_frontend_hashes()
        for folder, output in (("参考项目/PaiFlow-main", java_source), ("ruoyi-backend/ruoyi-paiflow", java_target)):
            subprocess.run(["java", str(HERE / "GenerateJavaApiInventory.java"), str(WORKSPACE), folder, str(output)], check=True)
        target_after = {rel(p): source_hash(p) for p in sorted((WORKSPACE / "ruoyi-backend/ruoyi-paiflow/src/main/java").rglob("*.java"))}
        subprocess.run(["node", str(HERE / "generate-frontend-api-inventory.cjs"), str(WORKSPACE), str(WORKSPACE / "frontend/paiflow-web/node_modules/typescript"), str(frontend_file)], check=True)
        original_java = json.loads(java_source.read_text(encoding="utf-8"))
        target_java = json.loads(java_target.read_text(encoding="utf-8"))
        frontend = json.loads(frontend_file.read_text(encoding="utf-8"))
        target_frontend_after = target_frontend_hashes()
    python = python_inventory()
    target_supporting_after = target_supporting_hashes()
    source_endpoints = java_enrich(original_java) + python["endpoints"]
    target_endpoints = java_enrich(target_java, True)
    boundaries = target_boundaries()
    for endpoint in target_endpoints:
        matched = [b for b in boundaries if b["method"] == endpoint["method"] and b["path"] == endpoint["path"]]
        if matched:
            endpoint["declaredImplementationBoundaries"] = matched
            endpoint["migrationStatus"] = matched[0]["status"] if matched[0]["markerFound"] else "manual_boundary_source_missing_requires_review"
    changed_target_files = sorted({*target_before, *target_after} - {p for p in target_before if target_before[p] == target_after.get(p)})
    changed_frontend_files = sorted({*target_frontend_before, *target_frontend_after} - {p for p in target_frontend_before if target_frontend_before[p] == target_frontend_after.get(p)})
    changed_supporting_files = sorted({*target_supporting_before, *target_supporting_after} - {p for p in target_supporting_before if target_supporting_before[p] == target_supporting_after.get(p)})
    snapshot_invalid = bool(target_java["parseDiagnostics"] or changed_target_files or changed_frontend_files or changed_supporting_files)
    if snapshot_invalid:
        for target in target_endpoints:
            target["migrationStatus"] = "source_snapshot_changed_or_parse_error_requires_regeneration"
    for endpoint in source_endpoints:
        matches = []
        for target in target_endpoints:
            legacy_path = target["path"].removeprefix("/paiflow/legacy")
            exact = legacy_path == endpoint["path"]
            # 原option允许任意非on字符串作停用；目标只明确接受on/off，不推定泛化路由闭环。
            model_option_subset = endpoint["method"] == "GET" and endpoint["path"] == "/api/model/{option}" and legacy_path in {"/api/model/on", "/api/model/off"}
            if target["path"].startswith("/paiflow/legacy/") and (exact or model_option_subset) and target["method"] == endpoint["method"]:
                matches.append({"id": target["id"], "file": target["file"], "line": target["line"], "method": target["method"], "path": target["path"], "status": target["migrationStatus"], "matchKind": "exact_method_and_path_source_adapter" if exact else "controlled_on_off_subset_of_original_option_route_not_full_coverage"})
        if matches:
            subset = all(m["matchKind"] == "controlled_on_off_subset_of_original_option_route_not_full_coverage" for m in matches)
            endpoint["migrationStatus"] = "source_snapshot_changed_or_parse_error_requires_regeneration" if snapshot_invalid else "source_adapter_subset_present_business_runtime_unverified" if subset else "source_adapter_present_business_runtime_unverified"
        endpoint["targetMatches"] = matches
    for call in frontend["calls"]:
        call["sourceEndpointMatches"] = [{"id": e["id"], "sourceService": e["sourceService"], "method": e["method"], "path": e["path"], "file": e["file"], "line": e["line"], "matchKind": "static_path_shape_only_not_field_contract"} for e in source_endpoints if route_match(call, e)]
        call["sourceCoverageStatus"] = "static_local_route_candidate" if call["sourceEndpointMatches"] else ("dynamic_or_external_destination" if route_key(call["url"].get("value")) is None or call["method"] == "DYNAMIC" else "no_local_route_for_this_method_and_path")
        call["targetEndpointMatches"] = [{"id": t["id"], "method": t["method"], "path": t["path"], "status": t["migrationStatus"]} for t in target_endpoints if route_match(call, {**t, "path": t["path"].removeprefix("/paiflow/legacy")})]
        call["migrationStatus"] = ("source_snapshot_changed_or_parse_error_requires_regeneration" if snapshot_invalid else "source_adapter_present_business_runtime_unverified") if call["targetEndpointMatches"] else "not_implemented_or_not_proven"
    plans = planned_consumer_groups()
    for f in frontend["serviceExports"]:
        f["plannedMapping"] = plans.get((Path(f["file"]).stem, f["name"]), {"status": "existing_design_mapping_not_found"})
        f["calls"] = [{"id": c["id"], "sourceCoverageStatus": c["sourceCoverageStatus"], "migrationStatus": c["migrationStatus"]} for c in frontend["calls"] if c["id"] in f["callIds"]]
        f["migrationStatus"] = "not_implemented_or_not_proven" if not f["calls"] or any(c["migrationStatus"] == "not_implemented_or_not_proven" for c in f["calls"]) else ("source_snapshot_changed_or_parse_error_requires_regeneration" if snapshot_invalid else "source_adapter_present_business_runtime_unverified")
    baseline = json.loads((DOC / "前端源码完整性核对.json").read_text(encoding="utf-8-sig"))
    old_functions = {(f["file"], f["name"]) for f in baseline["serviceExportsDetails"]}
    new_functions = {(f["file"], f["name"]) for f in frontend["serviceExports"]}
    old_direct = baseline["directCallsOutsideServices"] if "directCallsOutsideServices" in baseline else baseline.get("outsideServiceCalls", [])
    # 字段名以旧核对文件实际结构读取，不因命名变化忽略基线 23 项。
    if not old_direct:
        for key, value in baseline.items():
            if isinstance(value, list) and len(value) == 23 and value and isinstance(value[0], dict) and "kind" in value[0]:
                old_direct = value
                break
    def old_equivalent(old):
        return [c["id"] for c in frontend["calls"] if c["file"] == old["file"] and (c["line"] == old["line"] or (old["kind"] == "XMLHttpRequest" and c["kind"] == "XMLHttpRequest.open" and abs(c["line"] - old["line"]) < 100))]
    old_direct_comparison = [{**old, "newCallIds": old_equivalent(old), "status": "included_or_transport_operation_expanded" if old_equivalent(old) else "manual_review_required"} for old in old_direct]
    # Python挂载和人工边界核对也属于生成窗口，收尾再次读取所有目标文件。
    target_after = {rel(p): source_hash(p) for p in sorted((target_root / "src/main/java").rglob("*.java"))}
    target_frontend_after = target_frontend_hashes()
    target_supporting_after = target_supporting_hashes()
    changed_target_files = sorted({*target_before, *target_after} - {p for p in target_before if target_before[p] == target_after.get(p)})
    changed_frontend_files = sorted({*target_frontend_before, *target_frontend_after} - {p for p in target_frontend_before if target_frontend_before[p] == target_frontend_after.get(p)})
    changed_supporting_files = sorted({*target_supporting_before, *target_supporting_after} - {p for p in target_supporting_before if target_supporting_before[p] == target_supporting_after.get(p)})
    snapshot_invalid = bool(target_java["parseDiagnostics"] or changed_target_files or changed_frontend_files or changed_supporting_files)
    if snapshot_invalid:
        for target in target_endpoints:
            target["migrationStatus"] = "source_snapshot_changed_or_parse_error_requires_regeneration"
        for endpoint in source_endpoints:
            if endpoint["targetMatches"]:
                endpoint["migrationStatus"] = "source_snapshot_changed_or_parse_error_requires_regeneration"
                for match in endpoint["targetMatches"]:
                    match["status"] = "source_snapshot_changed_or_parse_error_requires_regeneration"
        for call in frontend["calls"]:
            if call["targetEndpointMatches"]:
                call["migrationStatus"] = "source_snapshot_changed_or_parse_error_requires_regeneration"
                for match in call["targetEndpointMatches"]:
                    match["status"] = "source_snapshot_changed_or_parse_error_requires_regeneration"
        for function in frontend["serviceExports"]:
            for call in function["calls"]:
                if call["migrationStatus"] != "not_implemented_or_not_proven":
                    call["migrationStatus"] = "source_snapshot_changed_or_parse_error_requires_regeneration"
            if function["migrationStatus"] != "not_implemented_or_not_proven":
                function["migrationStatus"] = "source_snapshot_changed_or_parse_error_requires_regeneration"
    summary = {
        "originalJavaFilesParsed": original_java["javaFiles"], "originalJavaControllerFiles": len({e["file"] for e in original_java["endpoints"] if e.get("registrationKind") != "WebSocketHandlerRegistry.addHandler"}), "originalJavaWebSocketRegistrations": len([e for e in original_java["endpoints"] if e.get("registrationKind") == "WebSocketHandlerRegistry.addHandler"]), "originalJavaEndpointVariants": len(original_java["endpoints"]),
        "originalPythonFilesParsed": python["filesParsed"], "originalPythonRouteDefinitions": python["routeDefinitions"], "originalPythonMountedEndpointVariants": len(python["endpoints"]), "originalPythonUnmountedDefinitions": len(python["unmountedRouteDefinitions"]), "unresolvedPythonMounts": len(python["unresolvedMounts"]),
        "originalEndpointVariants": len(source_endpoints), "originalEndpointIdSha256": hashlib.sha256(json.dumps(sorted(e["id"] for e in source_endpoints), ensure_ascii=False).encode()).hexdigest(), "sourceEndpointCountsByService": dict(Counter(e["sourceService"] for e in source_endpoints)),
        "frontendTypescriptFiles": frontend["typescriptFiles"], "frontendServiceExports": len(frontend["serviceExports"]), "frontendCalls": len(frontend["calls"]), "frontendAdditionalBrowserTransports": len(frontend["additionalBrowserTransports"]),
        "frontendCallsBySourceCoverageStatus": dict(Counter(c["sourceCoverageStatus"] for c in frontend["calls"])), "frontendCallsByMigrationStatus": dict(Counter(c["migrationStatus"] for c in frontend["calls"])),
        "sourceRoutesWithoutStaticFrontendCall": len([e for e in source_endpoints if not any(route_match(c, e) for c in frontend["calls"])]),
        "targetPaiflowEndpointVariants": len(target_endpoints), "originalRoutesWithTargetSourceAdapter": len([e for e in source_endpoints if e["targetMatches"]]), "businessRuntimeVerifiedOriginalRoutes": 0,
        "targetSourceEndpointCountsByStatus": dict(Counter(e["migrationStatus"] for e in target_endpoints)),
        "manualTargetBoundariesWithoutDeclaredRoute": [b for b in boundaries if not any(e["method"] == b["method"] and e["path"] == b["path"] for e in target_endpoints)],
        "targetFilesChangedDuringParse": changed_target_files, "targetFrontendFilesChangedDuringParse": changed_frontend_files, "targetSupportingFilesChangedDuringParse": changed_supporting_files, "targetFrontendSourceFilesHashed": len(target_frontend_after), "targetSupportingFilesHashed": len(target_supporting_after), "existingDesignMappingsMissing": len([f for f in frontend["serviceExports"] if f["plannedMapping"]["status"] == "existing_design_mapping_not_found"]),
        "baseline365Missing": sorted(old_functions - new_functions), "baseline365New": sorted(new_functions - old_functions), "baseline23DirectCalls": len(old_direct), "baseline23ManualReviewRequired": len([x for x in old_direct_comparison if not x["newCallIds"]]),
        "parseDiagnosticCounts": {"javaSource": len(original_java["parseDiagnostics"]), "javaTarget": len(target_java["parseDiagnostics"]), "python": len(python["parseDiagnostics"]), "typescript": len(frontend["parseDiagnostics"])},
    }
    limitations = [
        "接口数量是服务身份、方法、路径的源码变体统计；同一个 Python handler 的旧前缀别名分别保留，不当作不同业务功能。",
        "Java 使用 javac parse AST，不做依赖类型归因或运行时 Spring 装配；条件开关、继承/元注解、代理/异常处理与安全过滤器需独立验证。RequestMapping 无方法约束保留 ANY。",
        "参数、DTO/Pydantic 字段和返回类型只反映源码声明；泛型绑定、别名、继承、JSON序列化、错误信封、状态码、SSE逐事件和文件行为未按消费者 fixture 验证，不标字段契约闭环。",
        "Java 返回类型含 ResponseResultBody/ApiResult/Result 时原统一包装规则需进一步结合 advice 核对，不能用返回类型断言最终 JSON。",
        "React route_match 只匹配方法和同段路径形状，动态段产生的是候选，不能证明目标 endpoint 或语义相等。变量存在多个同名定义、参数URL、签名URL和 SDK协议明确列动态。",
        "浏览器媒体/下载、SDK与 XHR.open 补充原388名称扫描；无法声称穷尽任意运行时别名、第三方包内部请求、Worker/service worker、反射或服务器返回URL。需实际网络与消费者契约核对。",
        "sourceSignals 和人工例外是复核入口；TODO/null 分支不是全部失败结论，代码存在也不是功能通过。",
        "当前目标 Controller 源码存在只记本地源实现；未运行平台登录、DB、Worker、渠道或原页面全业务验收，业务验证完成数保持0。",
        "生成器不包含原部署配置或body默认值，避免输出账号密钥；真实数据库/Provider/对象存储和渠道配置未读取验证。",
        "新增调试构建、比较、chat/resume/cancel只标受限源码和隔离技术检查；原React当前源与旧生产bundle版本不同，源覆盖不能证明页面或发布完成。",
        "目标快照仅当前Java/前端和Java支持文件，不涵盖python/paiflow-runtime新增TaskRunner变量许可前检查；该Runtime应按其独立冻结证据说明。",
    ]
    inventory = {"schemaVersion": "1.0", "generatedAt": datetime.now(timezone.utc).isoformat(), "scope": "full_original_source_api_plus_original_react_consumers_and_current_paiflow_controller_snapshot", "generator": rel(Path(__file__)), "summary": summary, "targetSnapshotHashes": target_after, "originalEndpoints": source_endpoints, "targetPaiflowEndpoints": target_endpoints, "frontend": frontend, "sourceTypeDeclarations": {"java": original_java["types"], "python": python["types"], "targetJava": target_java["types"]}, "pythonMountExceptions": {"unresolved": python["unresolvedMounts"], "unmountedDefinitions": python["unmountedRouteDefinitions"]}, "parseDiagnostics": {"javaSource": original_java["parseDiagnostics"], "javaTarget": target_java["parseDiagnostics"], "python": python["parseDiagnostics"], "typescript": frontend["parseDiagnostics"]}, "manualGapCandidates": manual_gaps(), "limitations": limitations}
    inventory["targetFrontendSnapshotHashes"] = target_frontend_after
    inventory["targetFrontendSnapshotScope"] = "当前原React工程src中的TS/JS与现有package/HTML/Vite/TypeScript配置；不包含dist/node_modules或CSS/图像，原资源差异另见原前端接入差异.json。哈希一致不代表类型/构建/页面通过。"
    inventory["targetSupportingSnapshotHashes"] = target_supporting_after
    inventory["targetSupportingSnapshotScope"] = "当前ruoyi-paiflow的src/main/resources、src/test、sql及模块pom.xml；包括实际Mapper、JSON种子、测试源码和未执行SQL草案，不包含target构建产物。哈希或文件存在不代表SQL执行、测试通过或真实业务可用。"
    dump(HERE / "source-api-inventory.json", inventory)
    audit = {"date": datetime.now().date().isoformat(), "status": "完整接口源码核对已生成；迁移与字段/业务契约尚未完成", "inventory": "../../../../contracts/paiflow/source-api-inventory.json", "generatorCommand": "python contracts/paiflow/generate-source-api-inventory.py", "generatorHashes": {rel(p): source_hash(p) for p in (Path(__file__), HERE / "GenerateJavaApiInventory.java", HERE / "generate-frontend-api-inventory.cjs")}, "inventorySha256": source_hash(HERE / "source-api-inventory.json"), "summary": summary, "baseline365": {"missing": summary["baseline365Missing"], "new": summary["baseline365New"]}, "baseline23DirectCalls": old_direct_comparison, "frontendCallsWithoutLocalRoute": [{"file": c["file"], "line": c["line"], "function": c["function"], "method": c["method"], "url": c["url"], "status": c["sourceCoverageStatus"]} for c in frontend["calls"] if c["sourceCoverageStatus"] != "static_local_route_candidate"], "originalRoutesWithoutStaticFrontendCall": [{"id": e["id"], "sourceService": e["sourceService"], "method": e["method"], "path": e["path"], "file": e["file"], "line": e["line"], "migrationStatus": e["migrationStatus"]} for e in source_endpoints if not any(route_match(c, e) for c in frontend["calls"])], "targetSnapshot": target_endpoints, "manualGapCandidates": inventory["manualGapCandidates"], "limitations": limitations}
    dump(DOC / "接口覆盖核对.json", audit)
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
