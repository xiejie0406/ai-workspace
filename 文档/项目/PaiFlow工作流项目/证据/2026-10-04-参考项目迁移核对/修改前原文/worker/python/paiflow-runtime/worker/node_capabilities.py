"""原 Plugin/专业RAG/Agent/数据库/RPA 节点的固定能力绑定。

这些是尚无生产端点的草案消费者；原算法保留，正式状态只接受中心回执。
"""
import copy
import json
import re
from functools import wraps

from .errors import WorkerError
from .events import _validate_payload
from .legacy_operations import bound_resource, completed_response, parameters, response_stream
from .task_runner import current_context


def guarded(function):
    @wraps(function)
    async def call(*args, **kwargs):
        context = current_context()
        try:
            return await function(*args, **kwargs)
        except WorkerError as error:
            context.boundary_failure = error
            raise
    return call


def no_credentials(node, *fields):
    if any(getattr(node, name, None) for name in fields):
        raise WorkerError("TOOL_POLICY_DENIED", "原节点不接受明文凭据或旧URL目标")


def stream_parameters(node, resource_id, resource, payload, bindings=None):
    body, _ = parameters(resource_id, resource, payload, bindings)
    body["timeoutSeconds"] = resource.get("limits", {}).get("timeoutSeconds", 1800)
    if node.retry_config.should_retry:
        body["readTimeoutSeconds"] = node.retry_config.timeout
    if (type(body["timeoutSeconds"]) is not int or not 0 < body["timeoutSeconds"] <= 86400
            or "readTimeoutSeconds" in body and (not isinstance(body["readTimeoutSeconds"], (int, float))
                or isinstance(body["readTimeoutSeconds"], bool) or not 0 < body["readTimeoutSeconds"] <= 600)):
        raise WorkerError("DSL_INVALID", "原能力超时范围无效")
    import hashlib
    fingerprint = hashlib.sha256(json.dumps(body, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return body, fingerprint


@guarded
async def plugin_link(node):
    from workflow.engine.nodes.plugin_tool.link_client import Link
    resource = await bound_resource(node, node.toolResourceId, "tool", {
        "provider": "spark-link", "pluginId": node.pluginId, "operationId": node.operationId,
        "legacyVersion": node.version, "legacyAppId": node.appId})
    if resource.get("effectClass") not in {"read", "write"} or not isinstance(resource.get("openApiSchema"), dict):
        raise WorkerError("RESOURCE_UNAVAILABLE", "中心插件描述缺少固定Schema/读写分级")
    link = Link(node.appId, [node.pluginId], "", "", node.version,
        open_api_schemas=[{"id": node.pluginId, "schema": json.dumps(resource["openApiSchema"], ensure_ascii=False)}])
    for tool in link.tools:
        if tool.operation_id == node.operationId:
            tool._worker_node, tool._worker_resource = node, resource
    if not any(tool.operation_id == node.operationId for tool in link.tools):
        raise WorkerError("VERSION_CONFLICT", "中心固定Schema没有所选operationId")
    return link


def _link_response(value, failed):
    if (not isinstance(value, dict) or not isinstance(value.get("header"), dict)
            or type(value["header"].get("code")) is not int or not isinstance(value["header"].get("message"), str)
            or (value["header"]["code"] != 0) != failed):
        raise WorkerError("DSL_INVALID", "原Link回执缺少header/code/message")
    if not failed:
        try:
            parsed = json.loads(value["payload"]["text"]["text"])
        except (KeyError, TypeError, ValueError):
            raise WorkerError("DSL_INVALID", "原Link回执缺少JSON text") from None
        if not isinstance(parsed, dict):
            raise WorkerError("DSL_INVALID", "原插件输出不是对象")
        _validate_payload(parsed)


@guarded
async def plugin_run(tool, payload):
    node, resource = getattr(tool, "_worker_node", None), getattr(tool, "_worker_resource", None)
    if node is None or not isinstance(resource, dict) or tool.get_url or tool.run_url:
        raise WorkerError("RESOURCE_UNAVAILABLE", "旧Link客户端未绑定中心节点能力")
    body, fingerprint = parameters(node.toolResourceId, resource, payload)
    operation = "tools.read" if resource["effectClass"] == "read" else "tools.invoke"
    return await completed_response(node, operation, body, fingerprint, _link_response)


@guarded
async def rpa_response(node, payload, consume):
    if node.header:
        raise WorkerError("TOOL_POLICY_DENIED", "RPA不接受旧认证header")
    resource = await bound_resource(node, node.rpaResourceId, "rpa", {"provider": "original-rpa",
        "projectId": node.projectId, "execPosition": node.rpaParams.get("execPosition", "EXECUTOR")})
    if (resource.get("stopSupported") is not True or not isinstance(resource.get("limits"), dict)
            or type(resource["limits"].get("timeoutSeconds")) is not int or not 0 < resource["limits"]["timeoutSeconds"] <= 86400):
        raise WorkerError("RESOURCE_UNAVAILABLE", "RPA缺少明确执行限制和停止能力")
    body, fingerprint = stream_parameters(node, node.rpaResourceId, resource, payload)
    async with response_stream(node, "rpa.execute", body, fingerprint, "rpa") as response:
        return await consume(response)


@guarded
async def pro_response(node, payload, consume):
    no_credentials(node, "url", "apiKey", "apiSecret")
    from workflow.engine.nodes.knowledge_pro.consts import RepoTypeEnum, RagTypeEnum
    resource = await bound_resource(node, node.knowledgeResourceId, "knowledge-pro", {
        "provider": RepoTypeEnum.getitem(node.repoType), "ragMode": RagTypeEnum.getitem(node.ragType),
        "repoIds": node.repoIds, "docIds": node.docIds, "modelResourceId": node.modelResourceId})
    model = await bound_resource(node, node.modelResourceId, "model", {"modelName": node.domain})
    bindings = {node.modelResourceId: {"kind": "model", "provider": model["provider"], "version": model["version"]}}
    body, fingerprint = stream_parameters(node, node.knowledgeResourceId, resource, payload, bindings)
    async with response_stream(node, "knowledge.answer", body, fingerprint, "knowledge-pro") as response:
        return await consume(response)


@guarded
async def agent_response(node, payload, consume):
    no_credentials(node, "apiKey", "apiSecret")
    if node.modelConfig.api or node.plugin.mcpServerUrls:
        raise WorkerError("TOOL_POLICY_DENIED", "Agent不接受旧模型API或MCP URL")
    if node.enableChatHistoryV2.is_enabled:
        raise WorkerError("RESOURCE_UNAVAILABLE", "Agent正式历史scope尚未绑定")
    plugin = copy.deepcopy(payload["plugin"])
    resource = await bound_resource(node, node.agentResourceId, "agent", {"provider": "original-agent",
        "strategy": node.modelConfig.agentStrategy, "modelResourceId": node.modelResourceId,
        "legacyPlugin": plugin})
    model = await bound_resource(node, node.modelResourceId, "model", {"provider": node.source, "modelName": node.modelConfig.domain})
    bindings = {node.modelResourceId: {"kind": "model", "provider": model["provider"], "version": model["version"]}}
    dependencies = resource.get("dependencyBindings")
    selections = resource.get("dependencySelections")
    selected = bool(plugin["tools"] or plugin["mcp_server_ids"] or plugin["workflow_ids"] or plugin["knowledge"])
    if not isinstance(dependencies, dict) or not isinstance(selections, dict) or selected and not dependencies:
        raise WorkerError("RESOURCE_UNAVAILABLE", "Agent子能力未取得中心固定绑定")
    expected_selections = {}
    for item in plugin["tools"]:
        if not isinstance(item, dict) or not isinstance(item.get("tool_id"), str) or not isinstance(item.get("version"), str):
            raise WorkerError("DSL_INVALID", "Agent原tool选择形状无效")
        expected_selections[f"tool:{item['tool_id']}:{item['version']}"] = ("tool", {"pluginId": item["tool_id"], "legacyVersion": item["version"]})
    for identifier in plugin["mcp_server_ids"]:
        expected_selections[f"mcp:{identifier}"] = ("mcp", {"legacyServerId": identifier})
    for identifier in plugin["workflow_ids"]:
        expected_selections[f"workflow:{identifier}"] = ("workflow", {"flowId": identifier})
    for index, item in enumerate(plugin["knowledge"]):
        # 原独立Agent builder明确映射1/2/3，不把Ragflow或CBG别名成AIUI。
        provider = {1: "AIUI-RAG2", 2: "CBG-RAG", 3: "Ragflow-RAG"}[item["repo_type"]]
        expected_selections[f"knowledge:{index}"] = ("knowledge", {"provider": provider,
            "repoIds": item["match"]["repo_ids"], "docIds": item["match"].get("doc_ids")})
    if set(selections) != set(expected_selections) or any(not isinstance(value, str) or value not in dependencies for value in selections.values()):
        raise WorkerError("RESOURCE_UNAVAILABLE", "Agent原工具/知识/工作流选择没有逐项中心映射")
    if set(dependencies) != set(selections.values()):
        raise WorkerError("TOOL_POLICY_DENIED", "Agent依赖绑定含未选择能力")
    for resource_id, descriptor in dependencies.items():
        if (not isinstance(descriptor, dict) or set(descriptor) != {"kind", "provider", "version"}
                or descriptor["kind"] not in {"tool", "mcp", "workflow", "knowledge", "knowledge-pro"}
                or not isinstance(descriptor["version"], str) or not descriptor["version"]):
            raise WorkerError("RESOURCE_UNAVAILABLE", "Agent子能力绑定形状无效")
        fixed = await bound_resource(node, resource_id, descriptor["kind"], {"provider": descriptor["provider"], "version": descriptor["version"]})
        bindings[resource_id] = {"kind": fixed["kind"], "provider": fixed["provider"], "version": fixed["version"]}
    for selection, (kind, expected) in expected_selections.items():
        identifier = selections[selection]
        await bound_resource(node, identifier, kind, expected)
    body, fingerprint = stream_parameters(node, node.agentResourceId, resource, payload, bindings)
    async with response_stream(node, "agent.invoke", body, fingerprint, "agent") as response:
        return await consume(response)


def structured_database_request(node, inputs):
    """保留第一case、NULL/ZERO/LIKE及写后返回规则，改为中心参数化事务。"""
    from workflow.engine.nodes.pgsql.pgsql_node import ZERO
    if node.mode == 0:
        raise WorkerError("RESOURCE_UNAVAILABLE", "CUSTOM SQL尚无中心解析/翻译和授权端口")
    if node.sql:
        raise WorkerError("TOOL_POLICY_DENIED", "结构化数据库操作不能携带任意SQL")
    identifiers = [node.tableName, *node.assignmentList, *(item.fieldName for item in node.orderData)]
    first = node.cases[0] if node.cases else None
    if first:
        identifiers.extend(item.fieldName for item in first.conditions)
    if node.mode == 1:
        identifiers.extend(inputs)
    if any(not isinstance(item, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", item) for item in identifiers):
        raise WorkerError("TOOL_POLICY_DENIED", "表/列须为中心逻辑标识符")
    conditions = []
    if first:
        for item in first.conditions:
            condition = {"fieldName": item.fieldName, "operator": item.selectCondition}
            if item.selectCondition in {"null", "not null"}:
                if item.fieldType.lower() in ZERO:
                    condition["zeroEquivalent"] = copy.deepcopy(ZERO[item.fieldType.lower()])
            else:
                value = inputs.get(item.varIndex, item.varIndex)
                if isinstance(value, str) and item.selectCondition in {"like", "not like"}:
                    value = f"%{value}%"
                condition["value"] = value
            conditions.append(condition)
    return {"mode": node.mode, "table": node.tableName,
        "values": copy.deepcopy(inputs) if node.mode == 1 else {key: inputs[key] for key in node.assignmentList} if node.mode == 2 else {},
        "where": {"logicalOperator": first.logicalOperator if first else "and", "conditions": conditions},
        "columns": node.assignmentList if node.mode == 3 else [],
        "order": [item.model_dump() for item in node.orderData] if node.mode == 3 else [],
        "limit": node.limit if node.mode == 3 else None,
        "returning": "last_inserted_row" if node.mode == 1 else "matched_rows" if node.mode == 2 else "exec_success"}


def _database_response(value, failed):
    if (not isinstance(value, dict) or type(value.get("code")) is not int or (value["code"] != 0) != failed
            or not isinstance(value.get("message", ""), str)):
        raise WorkerError("DSL_INVALID", "原数据返回缺少code/message")
    if not failed and (not isinstance(value.get("data"), dict) or not isinstance(value["data"].get("exec_success"), list)
                       or any(not isinstance(row, dict) for row in value["data"]["exec_success"])):
        raise WorkerError("DSL_INVALID", "原数据返回缺少exec_success行对象")


@guarded
async def database_response(node, inputs):
    no_credentials(node, "apiKey")
    payload = structured_database_request(node, inputs)
    resource = await bound_resource(node, node.dataResourceId, "database", {"provider": "center-data",
        "legacyDbId": node.dbId, "legacySpaceId": node.spaceId})
    tables = resource.get("tables")
    if (not isinstance(tables, dict) or node.tableName not in tables or not isinstance(tables[node.tableName], dict)
            or node.mode not in tables[node.tableName].get("allowedModes", [])):
        raise WorkerError("TOOL_POLICY_DENIED", "中心未授权该逻辑表和数据操作")
    columns = tables[node.tableName].get("columns")
    used = {*payload["values"], *payload["columns"], *(item["fieldName"] for item in payload["where"]["conditions"]),
            *(item["fieldName"] for item in payload["order"])}
    if not isinstance(columns, list) or not used <= set(columns):
        raise WorkerError("TOOL_POLICY_DENIED", "中心未授权对应逻辑列")
    body, fingerprint = parameters(node.dataResourceId, resource, payload)
    original = await completed_response(node, "data.query" if node.mode == 3 else "data.mutate", body, fingerprint, _database_response)
    if original["code"] != 0:
        from workflow.exception.e import CustomException
        from workflow.exception.errors.err_code import CodeEnum
        raise CustomException(CodeEnum.PG_SQL_REQUEST_ERROR, err_msg="原数据Provider返回失败")
    return original
