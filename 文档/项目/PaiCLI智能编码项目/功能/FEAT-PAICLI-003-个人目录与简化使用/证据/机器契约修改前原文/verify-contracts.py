"""从实际操作者Controller核对路由、权限和HTTP字段，生成同目录唯一契约。

--write用于首次生成或源协议更新；默认只核对，不修改文件。
JSON对象扩展字段按实际Map接口保留；JSON Schema不能替代服务端成员、版本和许可检查。
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
API = ROOT / "ruoyi-backend/ruoyi-paicli/paicli-api/src/main/java/com/ruoyi/paicli"
IDENTIFIER = {"type": "string", "pattern": "^[1-9][0-9]{0,18}$"}
INPUT_ID = {"oneOf": [IDENTIFIER, {"type": "integer", "minimum": 1, "maximum": 9223372036854775807}]}
SHA = {"type": "string", "pattern": "^[a-f0-9]{64}$"}
CURSOR = {"oneOf": [{"type": "integer", "minimum": 0}, {"type": "string", "pattern": "^(0|[1-9][0-9]{0,18})$"}]}


def obj(properties=None, required=(), **extra):
    result = {"type": "object", "properties": properties or {}, "additionalProperties": True, **extra}
    if required:
        result["required"] = list(required)
    return result


def balanced(text, start, left="(", right=")"):
    depth, quoted, escaped = 0, False, False
    for position in range(start, len(text)):
        char = text[position]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
        elif char == left:
            depth += 1
        elif char == right:
            depth -= 1
            if depth == 0:
                return text[start + 1:position], position + 1
    raise ValueError("源码括号不完整")


def route_values(arguments):
    value = arguments.strip()
    explicit = re.search(r"(?:^|,)\s*(?:value|path)\s*=\s*", value)
    if explicit:
        value = value[explicit.end():]
    elif value and not value.startswith(('"', "{")):
        return [""]
    if value.startswith("{"):
        value, _ = balanced(value, 0, "{", "}")
        return re.findall(r'"([^"\n]*)"', value)
    first = re.match(r'"([^"\n]*)"', value)
    return [first.group(1)] if first else [""]


def annotations(text):
    for match in re.finditer(r"@(\w+)", text):
        end, arguments = match.end(), ""
        if end < len(text) and text[end] == "(":
            arguments, end = balanced(text, end)
        yield match.group(1), arguments, match.start(), end


def split_parameters(text):
    parts, start, level, quoted = [], 0, 0, False
    for i, char in enumerate(text):
        if char == '"' and (i == 0 or text[i - 1] != "\\"):
            quoted = not quoted
        if quoted:
            continue
        if char in "(<[{":
            level += 1
        elif char in ")>]}":
            level -= 1
        elif char == "," and level == 0:
            parts.append(text[start:i].strip())
            start = i + 1
    if text[start:].strip():
        parts.append(text[start:].strip())
    return parts


def schema_for(java_type, name, location):
    if java_type in {"long", "Long"}:
        if location == "path" or name.endswith("Id"):
            return copy.deepcopy(IDENTIFIER)
        return {"type": "integer", "format": "int64", "minimum": 0}
    if java_type in {"int", "Integer"}:
        return {"type": "integer", "format": "int32"}
    if java_type in {"boolean", "Boolean"}:
        return {"type": "boolean"}
    return {"type": "string"}


def routes(prefix_filter="/paicli/v1", require_permissions=True):
    result = []
    for source in sorted((API / "controller").glob("*.java")):
        text = source.read_text(encoding="utf-8-sig")
        class_position = text.find("public class ")
        prefix = None
        for name, arguments, _, _ in annotations(text[:class_position]):
            if name == "RequestMapping":
                prefix = route_values(arguments)[0]
        if prefix is None or not prefix.startswith(prefix_filter):
            continue
        body = text[class_position:]
        for name, arguments, position, end in annotations(body):
            if name not in {"GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"}:
                continue
            method = re.search(r"public\s+([\w.]+(?:<[^\n]*?>)?)\s+(\w+)\s*\(", body[end:])
            if method is None:
                raise ValueError(f"{source.name}缺少映射方法")
            signature_start = end + method.end() - 1
            signature, _ = balanced(body, signature_start)
            declarations = body[end:signature_start]
            permissions = sorted(set(re.findall(r"@ss\.hasPermi\('([^']+)'\)", declarations)))
            if require_permissions and not permissions:
                raise ValueError(f"{source.name}.{method.group(2)}没有按钮权限")
            parameters, request_body = [], None
            for parameter in split_parameters(signature):
                annotations_found = list(annotations(parameter))
                bare = parameter
                for _, _, begin, stop in reversed(annotations_found):
                    bare = bare[:begin] + bare[stop:]
                declaration = re.search(r"([\w.<>?, ]+)\s+(\w+)\s*$", bare.strip())
                if declaration is None:
                    raise ValueError(f"参数无法核对：{parameter}")
                java_type, field = declaration.group(1).strip(), declaration.group(2)
                for kind, settings, _, _ in annotations_found:
                    if kind == "RequestBody":
                        request_body = {"required": "required=false" not in settings.replace(" ", ""), "content": {"application/json": {"schema": obj()}}}
                    if kind not in {"PathVariable", "RequestParam", "RequestHeader", "RequestPart"}:
                        continue
                    named = re.search(r'(?:value|name)\s*=\s*"([^"]+)"', settings)
                    positional = re.match(r'\s*"([^"]+)"', settings)
                    field_name = named.group(1) if named else positional.group(1) if positional else field
                    default = re.search(r'defaultValue\s*=\s*"([^"]*)"', settings)
                    required = "required=false" not in settings.replace(" ", "") and default is None
                    location = {"PathVariable": "path", "RequestParam": "query", "RequestHeader": "header", "RequestPart": "form"}[kind]
                    schema = schema_for(java_type, field_name, location)
                    if default:
                        value = default.group(1)
                        schema["default"] = int(value) if schema.get("type") == "integer" else value
                        if value == "0" and schema.get("pattern") == IDENTIFIER["pattern"]:
                            schema["pattern"] = "^(0|[1-9][0-9]{0,18})$"
                    if location == "path":
                        required = True
                    if kind == "RequestPart":
                        schema = {"type": "string", "format": "binary"}
                    parameters.append({"name": field_name, "in": location, "required": required, "schema": schema})
            for suffix in route_values(arguments):
                raw_path = prefix.removeprefix(prefix_filter) + suffix
                path = re.sub(r"\{(\w+):[^}]+\}", r"{\1}", raw_path) or "/"
                params = copy.deepcopy(parameters)
                for param in params:
                    expression = re.search(r"\{" + re.escape(param["name"]) + r":([^}]+)\}", raw_path)
                    if expression:
                        param["schema"] = {"type": "string", "enum": expression.group(1).split("|")}
                result.append({"path": path, "method": name.removesuffix("Mapping").lower(), "javaMethod": method.group(2),
                               "returnType": method.group(1), "parameters": params, "body": copy.deepcopy(request_body),
                               "permissions": permissions, "source": str(source.relative_to(ROOT)).replace("\\", "/"),
                               "line": text[:class_position + position].count("\n") + 1})
    return result


def body_overrides():
    source = obj({"content": {"type": "string", "description": "直接正文最多1MiB UTF-8；更大正文使用contentArtifactId"}, "contentArtifactId": INPUT_ID, "sha256": SHA},
                 anyOf=[{"required": ["content"]}, {"required": ["contentArtifactId"]}])
    session = obj({"workspaceId": INPUT_ID, "runnerId": INPUT_ID, "modelId": INPUT_ID,
                   "title": {"type": "string", "minLength": 1, "maxLength": 128}, "mode": {"enum": ["REACT", "PLAN", "TEAM"]},
                   "approvalMode": {"enum": ["AUTO", "ASK", "PLAN"]}}, ("workspaceId", "runnerId", "modelId", "title", "mode", "approvalMode"))
    turn = obj({"text": {"type": "string", "minLength": 1, "maxLength": 65536, "x-maxUtf8Bytes": 65536},
                "attachments": {"type": "array", "maxItems": 10, "items": INPUT_ID}, "modelId": INPUT_ID,
                "mode": {"enum": ["REACT", "PLAN", "TEAM"]}, "approvalMode": {"enum": ["AUTO", "ASK", "PLAN"]}}, ("text",))
    rule = copy.deepcopy(source)
    rule["properties"].update({"id": INPUT_ID, "workspaceId": INPUT_ID, "scope": {"enum": ["PROJECT", "PERSONAL"]},
                               "layer": {"enum": ["USER_PAI", "ROOT_PAI", "PAICLI_PAI", "LOCAL_PAI", "PAICLI_LOCAL_PAI"]},
                               "sourcePath": {"type": "string"}, "enabled": {"type": "boolean"},
                               "imports": {"type": "array", "maxItems": 200, "items": {"allOf": [source, obj({"path": {"type": "string"}}, ("path",))]}}})
    rule["required"] = ["scope", "layer", "sourcePath"]
    memory = obj({"workspaceId": INPUT_ID, "scope": {"enum": ["PERSONAL", "PROJECT", "global", "project"]},
                  "content": {"type": "string", "minLength": 1, "maxLength": 8192}, "source": {"type": "string"}, "allowConflict": {"type": "boolean"},
                  "replace_id": INPUT_ID, "replaceVersion": {"type": "integer", "minimum": 1},
                  "conflictVersions": {"type": "object", "propertyNames": IDENTIFIER, "additionalProperties": {"type": "integer", "minimum": 1}}}, ("content",))
    config = {"workspaceId": INPUT_ID, "id": INPUT_ID}
    provider = obj({**config, "provider": {"enum": ["deepseek", "glm", "hunyuan", "kimi", "step", "freellmapi", "xfyun", "agnes", "sub2api"]},
                    "wireApi": {"enum": ["responses", "chat_completions"], "description": "Sub2API必填；不猜测协议或回退其他供应商；Runner需声明sub2api.responses.v1或sub2api.chat_completions.v1"},
                    "name": {"type": "string"}, "baseUrl": {"type": "string", "format": "uri"}, "secretRef": {"type": "string"},
                    "model": {"type": "string", "maxLength": 256}, "loraId": {"type": "string", "maxLength": 256},
                    "temperature": {"type": "number", "minimum": 0, "maximum": 2}, "default": {"type": "boolean"}},
                   ("workspaceId", "baseUrl"), anyOf=[{"required": ["provider"]}, {"required": ["name"]}],
                   allOf=[{"if": {"anyOf": [{"required": ["provider"], "properties": {"provider": {"const": "sub2api"}}},
                                            {"required": ["name"], "properties": {"name": {"const": "sub2api"}}}]},
                           "then": {"required": ["wireApi"]}}])
    model = obj({**config, "providerId": INPUT_ID, "modelId": {"type": "string", "minLength": 1, "maxLength": 256},
                 "contextWindow": {"type": "integer", "minimum": 8000, "maximum": 10000000, "default": 128000},
                 "maxOutputTokens": {"type": "integer", "minimum": 1, "maximum": 1000000},
                 "thinking": {"enum": ["enabled", "disabled", ""]}, "reasoningEffort": {"enum": ["low", "medium", "high", "max", ""]},
                 **{name: {"type": "boolean"} for name in ["supportsImages", "imageInput", "supportsThinking", "supportsTools", "capabilityKnown", "enabled", "reasoningHistory", "dsml"]}},
                ("workspaceId", "providerId", "modelId"))
    mcp = obj({**config, "name": {"type": "string", "pattern": "^[A-Za-z0-9._-]{1,64}$"}, "transport": {"enum": ["STDIO", "HTTP"]},
               "command": {"type": "string", "maxLength": 4096}, "args": {"type": "array", "items": {"type": "string"}}, "url": {"type": "string", "format": "uri"},
               "envSecretRefs": {"type": "object", "additionalProperties": {"type": "string"}}, "headerSecretRefs": {"type": "object", "additionalProperties": {"type": "string"}},
               "env": obj(), "headers": obj(), "enabled": {"type": "boolean"}}, ("workspaceId", "name", "transport"),
              allOf=[{"if": {"properties": {"transport": {"const": "STDIO"}}}, "then": {"required": ["command"]}},
                     {"if": {"properties": {"transport": {"const": "HTTP"}}}, "then": {"required": ["url"]}}])
    browser = obj({**config, "mode": {"enum": ["SHARED", "ISOLATED", "DISCONNECTED"]}, "endpoint": {"type": "string", "format": "uri"},
                   "mcpConfigId": INPUT_ID, "confirmShared": {"type": "boolean"}, "autoConnect": {"type": "boolean"}}, ("workspaceId", "mode"),
                  allOf=[{"if": {"properties": {"mode": {"const": "SHARED"}}}, "then": {"required": ["confirmShared"], "properties": {"confirmShared": {"const": True}}}},
                         {"if": {"properties": {"mode": {"enum": ["SHARED", "ISOLATED"]}}}, "then": {"required": ["endpoint", "mcpConfigId"]}}])
    return {
        ("post", "/sessions"): session,
        ("post", "/sessions/{id}/turns"): turn,
        ("post", "/tasks"): {"allOf": [session, turn]},
        ("post", "/sessions/{id}/commands"): obj({"name": {"type": "string", "minLength": 1}, "arguments": {"type": "string"}}, ("name",)),
        ("post", "/sessions/{id}/context"): obj({"action": {"enum": ["CLEAR", "CLEAR_INPUT_HISTORY", "COMPRESS"]}}, ("action",)),
        ("put", "/sessions/{id}/model"): obj({"modelId": INPUT_ID}, ("modelId",)),
        ("post", "/project-rules"): rule,
        ("post", "/memories"): memory,
        ("post", "/approvals/{id}/decision"): obj({"decision": {"enum": ["APPROVE", "REJECT", "SKIP", "REVISE"]}, "feedback": {"type": "string"}, "revisedArguments": obj()}, ("decision",)),
        ("post", "/tasks/{id}/reconcile"): obj({"evidence": {"type": "string", "minLength": 1, "maxLength": 8192}, "currentFingerprint": SHA,
                                                "decision": {"enum": ["CONFIRM_COMPLETED", "CONFIRM_FAILED", "CONFIRM_CANCELLED"]}}, ("evidence", "currentFingerprint", "decision")),
        ("put", "/providers"): provider,
        ("post", "/providers/{id}/verify"): obj({"modelId": INPUT_ID, "modelVersion": {"type": "integer", "minimum": 1}, "confirmCost": {"const": True}}, ("modelId", "modelVersion", "confirmCost")),
        ("post", "/models"): model,
        ("post", "/mcp"): mcp,
        ("put", "/browser"): browser,
        ("post", "/skills"): obj({**config, "name": {"type": "string", "minLength": 1}, "content": {"type": "string", "minLength": 1}, "sha256": SHA, "enabled": {"type": "boolean"}}, ("workspaceId", "name", "content", "sha256")),
        ("post", "/mcp/{id}/contents"): obj({"kind": {"enum": ["resource", "prompt"]}, "reference": {"type": "string", "minLength": 1, "maxLength": 4096}, "arguments": obj()}, ("kind", "reference")),
        ("put", "/wechat/{id}/policy"): obj({"dailyTurnLimit": {"type": "integer", "minimum": 1, "maximum": 10000, "default": 50},
                                              "singleTurnTokenBudget": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 64000},
                                              "commandAllowlist": {"type": "array", "items": {"type": "string"}}, "mcpAllowlist": {"type": "array", "items": {"type": "string"}}}, additionalProperties=False),
        ("post", "/harness"): obj({"sessionId": INPUT_ID, "mode": {"enum": ["QUICK", "NORMAL", "INLINE"]}}, ("sessionId", "mode")),
    }


def event_schema():
    runner_source = (API / "service/PcRunnerService.java").read_text(encoding="utf-8-sig")
    registered = re.search(r'if\(!Set\.of\((.*?)\)\.contains\(type\)\)throw PcException\.invalid\("未注册的事件类型"\)', runner_source, re.S)
    if not registered:
        raise ValueError("无法定位中心注册事件集合")
    types = re.findall(r'"([A-Z_]+)"', registered.group(1))
    lease = obj({"taskId": IDENTIFIER, "leaseId": {"type": "string"}, "fencingToken": {"type": "integer", "minimum": 1},
                 "version": {"type": "integer", "minimum": 1}, "policyVersion": {"type": "integer", "minimum": 1}, "rootFingerprint": SHA},
                ("taskId", "leaseId", "fencingToken", "version", "policyVersion", "rootFingerprint"))
    reported_usage = obj({**{field: {"type": "integer", "minimum": 0, "maximum": 100000000} for field in ["inputTokens", "outputTokens", "cachedInputTokens", "totalTokens"]},
                          "status": {"enum": ["FAILED", "CANCELLED"]}},
                         ("inputTokens", "outputTokens", "cachedInputTokens", "totalTokens", "status"), type=["object", "null"], additionalProperties=False,
                         description="可选：失败/取消前已收到的合法计数证据；不证明最终费用或退款。仅USAGE且usagePresent=false；total=input+output，cached<=input。")
    published = obj({"eventId": IDENTIFIER, "sequence": {"type": "integer", "minimum": 1}, "type": {"type": "string", "minLength": 1},
                     "payload": obj({"taskId": IDENTIFIER, "reportedUsage": reported_usage}), "occurredAt": {"type": "string", "format": "date-time"}, "taskId": IDENTIFIER},
                    ("eventId", "sequence", "type", "payload", "occurredAt"))
    ledger = obj({"schemaVersion": {"const": 1}, "sessionId": IDENTIFIER, "sequence": {"type": "integer", "minimum": 0},
                  "event": {"type": "string", "minLength": 1}, "message": {"type": ["object", "null"]}, "metadata": obj(),
                  "rawPromptRequired": {"type": "boolean"}, "sourceSequence": CURSOR}, ("schemaVersion", "sessionId", "sequence", "event", "message", "metadata"))
    return {"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "urn:paicli:events:v1", "title": "PaiCLI 中心事件及原始账本协议",
            "description": "中心发布事件、Runner输入及原始账本为不同对象；大正文只保存完整私有产物引用。事件内容不能授予新工具或账号权限。",
            "$ref": "#/$defs/PublishedEvent", "$defs": {"PublishedEvent": published, "LedgerEntry": ledger,
            "RunnerEventInput": {"allOf": [lease, obj({"eventId": {"type": "string", "pattern": "^[A-Za-z0-9._:-]{1,128}$"}, "type": {"enum": types},
                                                      "payload": obj({"eventArtifactId": INPUT_ID, "eventArtifactSha256": SHA, "reportedUsage": reported_usage,
                                                                      "eventArtifactSize": {"type": "integer", "minimum": 1, "maximum": 134217728}})}, ("eventId", "type", "payload"))]},
            "EventPage": obj({"events": {"type": "array", "items": {"$ref": "#/$defs/PublishedEvent"}}, "cursor": CURSOR,
                              "endSequence": {"type": "integer", "minimum": 0}, "hasMore": {"type": "boolean"}, "terminal": {"type": "boolean"}},
                             ("events", "cursor", "hasMore", "terminal"))}}


def operator_contract(entries):
    overrides, paths = body_overrides(), {}
    titles = {"PcWorkspaceController": "工作区与设备", "PcSessionController": "会话、任务与批准", "PcKnowledgeController": "知识、记忆与快照",
              "PcConnectionController": "连接与模型", "PcProjectRuleController": "项目规则", "PcPromptController": "提示词模板",
              "PcAuditController": "审计与评测", "PcImportController": "历史资料导入", "PcCommandController": "终端命令", "PcArtifactController": "私有附件与产物",
              "PcTerminalAuthorizationController": "网页终端授权", "PcTerminalController": "网页终端", "PcTerminalClientController": "终端设备授权"}
    schemas = {"AjaxResult": obj({"code": {"type": "integer"}, "msg": {"type": "string"}, "data": {}}, ("code",)),
               "TableDataInfo": obj({"code": {"type": "integer"}, "msg": {"type": "string"}, "total": {"type": "integer", "minimum": 0}, "rows": {"type": "array", "items": obj()}}, ("code", "rows", "total")),
               "Error": obj({"code": {"type": "integer"}, "msg": {"type": "string"}, "errorCode": {"type": "string"}}, ("code", "msg")),
               "ArtifactText": obj({"artifactId": IDENTIFIER, "sessionId": IDENTIFIER, "sha256": SHA, "size": {"type": "integer", "maximum": 134217728},
                                    "offset": {"type": "integer", "minimum": 0}, "nextOffset": {"type": "integer", "minimum": 0},
                                    "hasMore": {"type": "boolean"}, "text": {"type": "string"}}, ("artifactId", "sessionId", "sha256", "size", "offset", "nextOffset", "hasMore", "text"))}
    for entry in entries:
        path, method = entry["path"], entry["method"]
        operation = {"operationId": Path(entry["source"]).stem + "_" + entry["javaMethod"] + "_" + hashlib.sha256((method+path).encode()).hexdigest()[:8],
                     "summary": {"get": "查询", "post": "提交", "put": "更新", "delete": "删除", "patch": "调整"}[method] + titles[Path(entry["source"]).stem] + "：" + path,
                     "tags": [titles[Path(entry["source"]).stem]], "x-java-method": entry["javaMethod"],
                     "x-source": {"file": entry["source"], "line": entry["line"]}, "x-required-permissions": entry["permissions"],
                     "description": "同时校验当前若依用户的按钮权限及服务端成员/owner/用途边界；ID和客户端参数不能扩大授权。",
                     "parameters": entry["parameters"], "responses": {"200": {"description": "操作或查询成功；业务完成仍须核对正式状态和证据", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/" + ("TableDataInfo" if entry["returnType"] == "TableDataInfo" else "AjaxResult")}}}},
                     "default": {"description": "400输入；401凭证；403权限/硬拒绝；404对象；409版本/审批/CAS；410游标/产物过期；413容量；422能力；429预算/队列；503不可用", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}}}}
        if entry["body"]:
            operation["requestBody"] = entry["body"]
            if (method, path) in overrides:
                operation["requestBody"]["content"]["application/json"]["schema"] = overrides[method, path]
        for parameter in operation["parameters"]:
            if parameter["name"] == "Idempotency-Key":
                parameter["schema"].update(minLength=1, maxLength=128)
                parameter["description"] = "重复键及参数相同返回原结果；参数变化拒绝；重传不等于重做副作用"
            if parameter["name"] == "If-Match":
                parameter["schema"]["pattern"] = '^"?[0-9]{1,18}"?$'
            if parameter["name"] == "limit":
                maximum = 1048576 if path.endswith(("/text", "/preview-text")) else 500 if path == "/sessions/{id}/events" else 200
                parameter["schema"].update(minimum=4 if maximum == 1048576 else 1, maximum=maximum)
            if parameter["name"] == "waitSeconds":
                parameter["schema"].update(minimum=0, maximum=20)
        if path == "/artifacts" and method == "post":
            fields = {parameter["name"]: parameter["schema"] for parameter in operation["parameters"] if parameter["in"] in {"query", "form"}}
            fields["purpose"] = {"enum": ["TURN_IMAGE", "LEGACY_IMPORT", "EVALUATION_REPORT", "PAI_SOURCE"]}
            operation["parameters"] = [parameter for parameter in operation["parameters"] if parameter["in"] == "header"]
            operation["requestBody"] = {"required": True, "content": {"multipart/form-data": {"schema": obj(fields, fields)}}}
            operation["description"] += " TURN_IMAGE最多5MiB；其他用途最多50MiB；PAI_SOURCE须严格UTF-8，保留原bytes/SHA和CRLF，不借评测用途存规则。"
        if path == "/artifacts/{id}/content":
            operation["responses"]["200"] = {"description": "当前owner的私有完整产物，下载含ETag及no-store，不暴露存储路径", "content": {"application/octet-stream": {"schema": {"type": "string", "format": "binary"}}}}
        if path.endswith(("/text", "/preview-text")):
            operation["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/AjaxResult"}, obj({"data": {"$ref": "#/components/schemas/ArtifactText"}}, ("data",))]}
            operation["description"] += " 字节offset只接受完整UTF-8码点边界；单页4字节至1MiB，总SHA和size保持不变。批准预览只能读取该申请冻结的preview receipt产物，不能自选artifactId；普通下载仍ownerOnly。"
        if entry["returnType"].startswith(("Map<", "List<")):
            operation["responses"]["200"]["content"]["application/json"]["schema"] = {"type": "array", "items": obj()} if entry["returnType"].startswith("List<") else obj()
        if "TableDataInfo" == entry["returnType"]:
            operation["parameters"] += [{"name": "pageNum", "in": "query", "required": False, "schema": {"type": "integer", "minimum": 1}}, {"name": "pageSize", "in": "query", "required": False, "schema": {"type": "integer", "minimum": 1}}]
        if method in paths.setdefault(path, {}):
            raise ValueError(f"重复路由{method} {path}")
        paths[path][method] = operation
    return {"openapi": "3.1.0", "info": {"title": "PaiCLI 若依操作者接口", "version": "1.0.0", "description": "沿用若依JWT；通过现有请求组件使用，无独立账号系统。机器结构和路由来源可由verify-contracts.py核对；服务端仍执行数据库成员、owner、许可和版本规则。"},
            "servers": [{"url": "/paicli/v1"}], "security": [{"platformBearer": []}], "paths": paths,
            "components": {"securitySchemes": {"platformBearer": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}}, "schemas": schemas}}


def validate_refs(document):
    def visit(value):
        if isinstance(value, dict):
            reference = value.get("$ref")
            if reference and reference.startswith("#/"):
                current = document
                for part in reference[2:].split("/"):
                    current = current[part.replace("~1", "/").replace("~0", "~")]
            for nested in value.values():
                visit(nested)
        elif isinstance(value, list):
            for nested in value:
                visit(nested)
    visit(document)


def main():
    arguments = argparse.ArgumentParser()
    arguments.add_argument("--write", action="store_true")
    options = arguments.parse_args()
    entries = routes()
    documents = {"operator-v1.openapi.json": operator_contract(entries), "events-v1.schema.json": event_schema()}
    for name, document in documents.items():
        validate_refs(document)
        from jsonschema import Draft202012Validator
        if name == "events-v1.schema.json":
            Draft202012Validator.check_schema(document)
        else:
            for schema in document["components"]["schemas"].values():
                Draft202012Validator.check_schema(schema)
            from openapi_spec_validator import validate_spec
            validate_spec(document)
        destination = HERE / name
        if options.write:
            destination.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        elif json.loads(destination.read_text(encoding="utf-8-sig")) != document:
            raise ValueError(f"{name}与当前Controller/事件协议不一致；核对后再显式--write")
    import yaml
    from openapi_spec_validator import validate_spec
    runner = yaml.safe_load((HERE / "runner-v1.openapi.yaml").read_text(encoding="utf-8-sig"))
    validate_refs(runner)
    validate_spec(runner)
    runner_entries = routes("/paicli/runner/v1", False)
    expected = {(entry["path"], entry["method"]) for entry in runner_entries}
    actual = {(path, method) for path, methods in runner["paths"].items() for method in methods if method in {"get", "post", "put", "delete", "patch"}}
    if expected != actual:
        raise ValueError(f"设备契约路由缺失{sorted(expected-actual)}，多余{sorted(actual-expected)}")
    print(json.dumps({"operatorRoutes": len(entries), "operatorPaths": len(documents["operator-v1.openapi.json"]["paths"]),
                      "sourceControllers": len(set(entry["source"] for entry in entries)), "runnerOperations": len(actual), "referencesValid": True,
                      "businessVerified": False, "files": [{"name": name, "sha256": hashlib.sha256((HERE/name).read_bytes()).hexdigest()} for name in documents]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
