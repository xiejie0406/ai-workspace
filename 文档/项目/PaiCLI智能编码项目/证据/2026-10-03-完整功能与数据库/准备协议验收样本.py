"""准备离线协议数据。只新增私有 protocol-fixtures；不启动服务、不调用 API、不写业务。"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
from datetime import datetime
from pathlib import Path


EVIDENCE = Path(__file__).resolve().parent
WORKSPACE = EVIDENCE.parents[4]
CORE = "ruoyi-backend/ruoyi-paicli/paicli-core/src/main/java/com/paicli/"
RUNNER = "ruoyi-backend/ruoyi-paicli/paicli-runner/src/main/java/com/paicli/runner/"
API = "ruoyi-backend/ruoyi-paicli/paicli-api/src/main/java/com/ruoyi/paicli/service/"
FILES: dict[str, bytes] = {}
CASES: list[dict] = []


def sha(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def encode(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def compact(value) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def add(path: str, value, expected: dict, source: str, mode="json") -> str:
    if path in FILES:
        raise RuntimeError("协议文件重复")
    FILES[path] = encode(value) if mode == "json" else value.encode("utf-8")
    CASES.append({"Path": path, "Format": mode, "Expected": expected,
                  "Source": source, "Executed": False, "BusinessPassed": False})
    return path


def stream(events: list[dict], done=True) -> str:
    return "".join("data: " + compact(event) + "\n\n" for event in events) + ("data: [DONE]\n\n" if done else "")


def llm_text(text: str, model: str, reasoning="协议分析", usage=True, done=True, finish=True) -> str:
    events = [{"model": model, "choices": [{"index": 0, "delta": {"role": "assistant", "reasoning_content": reasoning}}]},
              {"model": model, "choices": [{"index": 0, "delta": {"content": text}}]}]
    if finish:
        events.append({"model": model, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
    if usage:
        events.append({"model": model, "choices": [], "usage": {"prompt_tokens": 120, "completion_tokens": 12, "prompt_tokens_details": {"cached_tokens": 20}}})
    return stream(events, done)


def make_models():
    specs = [
        ("glm", "GLMClient", "glm-5.1", "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions", {}),
        ("deepseek", "DeepSeekClient", "deepseek-flash", "https://api.deepseek.com/chat/completions", {"temperature": 1.0, "top_p": 0.95, "reasoning_effort": "max", "thinking": {"type": "enabled"}}),
        ("hunyuan", "HunyuanClient", "hy4-preview", "https://tokenhub.tencentmaas.com/v1/chat/completions", {"temperature": 0.9, "reasoning_effort": "high", "thinking": {"type": "enabled"}, "stream_options": {"include_usage": True}}),
        ("step", "StepClient", "step-3.5-flash", "https://api.stepfun.com/v1/chat/completions", {"reasoning_format": "deepseek-style"}),
        ("kimi", "KimiClient", "kimi-k2.6", "https://api.moonshot.ai/v1/chat/completions", {}),
        ("freellmapi", "FreeLlmApiClient", "auto", "http://localhost:5173/v1/chat/completions", {}),
        ("xfyun", "XfyunMaaSClient", "Qwen3.6-35B-A3B", "https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions", {"stream_options": {"include_usage": True}}),
        ("agnes", "AgnesClient", "agnes-2.0-flash", "https://apihub.agnes-ai.com/v1/chat/completions", {}),
    ]
    mappings = []
    for provider, cls, model, endpoint, additions in specs:
        src = CORE + "llm/" + cls + ".java"
        request = {"model": model, "stream": True, "messages": [{"role": "user", "content": "返回固定协议验收文字"}], **additions}
        request_path = add(f"models/{provider}/request-text.json", request,
                           {"Method": "POST", "HeaderNames": ["Content-Type", "Authorization"],
                            "CredentialValueIncluded": False, "ProfileAndCentralBudgetMayOverride": True}, src)
        response_path = add(f"models/{provider}/text-reasoning-usage.sse", llm_text("协议响应：" + provider, model),
                            {"Content": "协议响应：" + provider, "Reasoning": "协议分析", "ResolvedModel": model,
                             "InputTokens": 120, "OutputTokens": 12, "CachedInputTokens": 20, "UsagePresent": True}, CORE + "llm/AbstractOpenAiCompatibleClient.java:215", "sse")
        tool_events = [
            {"model": model, "choices": [{"delta": {"tool_calls": [{"index": 0, "id": "fixture_call_1", "type": "function", "function": {"name": "read_file", "arguments": '{"path":'}}]}}]},
            {"model": model, "choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": '"settings.txt"}'}}]}, "finish_reason": "tool_calls"}]},
            {"choices": [], "usage": {"prompt_tokens": 130, "completion_tokens": 8}},
        ]
        tools_path = add(f"models/{provider}/tool-fragments.sse", stream(tool_events),
                         {"ParsedToolCalls": [{"id": "fixture_call_1", "name": "read_file", "arguments": {"path": "settings.txt"}}],
                          "ParseOnly": True, "AutomaticToolAuthorization": False,
                          "NativeToolDispatchSupportedByDefault": provider != "xfyun",
                          "XfyunNote": "原实现supportsTools=false；仅共享wire解析样本，不声称支持工具派发" if provider == "xfyun" else None}, CORE + "llm/AbstractOpenAiCompatibleClient.java:296", "sse")
        mappings.append({"Provider": provider, "Class": cls, "DefaultModel": model, "DefaultEndpoint": endpoint,
                         "Request": request_path, "Response": response_path, "ToolsResponse": tools_path,
                         "CustomBaseUrlConsumed": True, "FormalLoopbackRegistrationAllowed": False,
                         "DefaultEndpointRegistrationNote": "源默认localhost受中心网络规则拒绝，须明确批准的合法端点" if provider == "freellmapi" else "真实端点须批准且具该供应商协议/凭证"})

    source = CORE + "llm/AbstractOpenAiCompatibleClient.java:299"
    add("models/shared/partial-without-completion.sse", llm_text("已显示的半截正文", "fixture-model", usage=False, done=False, finish=False),
        {"AcceptedAsCompleted": False, "Exception": "LlmStreamInterruptedException", "DeliveredPrefixMayRemainVisible": True,
         "RetryAfterDisplayedContent": False}, source, "sse")
    add("models/shared/finish-without-done.sse", llm_text("finish即可完成", "fixture-model", done=False),
        {"AcceptedAsCompleted": True, "CompletionProof": "非空finish_reason；[DONE]并非唯一完成标记"}, source, "sse")
    add("models/shared/done-empty.sse", "data: [DONE]\n\n", {"AcceptedAsCompleted": False, "Reason": "无正文、reasoning及toolCalls"}, source, "sse")
    add("models/shared/malformed-json.sse", 'data: {"choices":\n\n', {"AcceptedAsCompleted": False, "RetryableParseError": False}, CORE + "llm/LlmRetryPolicy.java", "sse")
    add("models/shared/usage-missing.sse", llm_text("usage未知", "fixture-model", usage=False), {"AcceptedAsCompleted": True, "UsagePresent": False, "UIUsage": "未知，不能把0当已计费事实"}, CORE + "llm/AbstractOpenAiCompatibleClient.java:327", "sse")
    add("models/shared/usage-invalid.sse", stream([{"choices": [{"delta": {"content": "usage非法仍有正文"}, "finish_reason": "stop"}], "usage": {"prompt_tokens": -1, "completion_tokens": "12"}}]),
        {"AcceptedAsCompleted": True, "UsagePresent": False}, CORE + "llm/AbstractOpenAiCompatibleClient.java:327", "sse")
    add("models/shared/reasoning-aliases.sse", stream([{"choices": [{"delta": {"reasoning": "第一段"}}]}, {"choices": [{"delta": {"reasoning_details": [{"text": "第二段"}, {"content": "第三段"}], "content": "别名正文"}, "finish_reason": "stop"}]}]),
        {"Reasoning": "第一段第二段第三段", "Content": "别名正文", "UsagePresent": False}, CORE + "llm/AbstractOpenAiCompatibleClient.java", "sse")
    for status in [400, 401, 403, 408, 429, 500, 502, 503, 504]:
        add(f"models/shared/http-{status}.json", {"status": status, "headers": {"Content-Type": "application/json", **({"Retry-After": "1"} if status == 429 else {})}, "body": {"error": {"message": "脱敏固定协议错误", "code": "fixture_error"}}},
            {"RetryableStatus": status in [408, 429, 500, 502, 503, 504], "DefaultMaxAttempts": 3,
             "OnlyBeforeConsumableOutput": True, "NoUnboundedRetry": True}, CORE + "llm/LlmRetryPolicy.java:130")
    dsml = '<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name="read_file"><｜｜DSML｜｜parameter name="path" string="true">settings.txt</｜｜DSML｜｜parameter></｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>'
    add("models/deepseek/dsml-known.sse", llm_text(dsml, "deepseek-flash"), {"Tool": "read_file", "Arguments": {"path": "settings.txt"}, "DynamicToolId": True, "RegisteredToolRequired": True, "AutomaticToolAuthorization": False}, CORE + "llm/DeepSeekClient.java", "sse")
    add("models/deepseek/dsml-unknown.sse", llm_text(dsml.replace('name="read_file"', 'name="unknown_fixture_tool"'), "deepseek-flash"), {"ToolCalls": None, "KeepOriginalText": True, "LooksLikeUnexecutedToolCall": True, "MustNotExecute": True}, CORE + "llm/DeepSeekClient.java", "sse")
    return mappings


def make_search():
    url = "https://example.com/paicli-fixture/source-a"
    src_url = "https://example.org/paicli-fixture/source-b"
    common = {"SourceUrls": [url, src_url], "Titles": ["协议资料A", "协议资料B"], "Snippets": ["固定摘要A", "固定摘要B"], "ActualFetchExecuted": False,
              "ParserSuccessDoesNotAuthorizeNetwork": True}
    specs = [
        ("zhipu", "ZhipuSearchProvider", "POST", "https://open.bigmodel.cn/api/paas/v4/web_search",
         {"search_engine": "search_std", "search_query": "固定协议查询", "count": 2, "content_size": "medium"},
         {"search_result": [{"title": t, "link": u, "content": s} for t, u, s in zip(common["Titles"], common["SourceUrls"], common["Snippets"])]}),
        ("serpapi", "SerpApiSearchProvider", "GET", "https://serpapi.com/search.json",
         {"q": "固定协议查询", "num": 2, "hl": "zh-cn"},
         {"organic_results": [{"title": t, "link": u, "snippet": s} for t, u, s in zip(common["Titles"], common["SourceUrls"], common["Snippets"])]}),
        ("searxng", "SearxngSearchProvider", "GET", "<approved-baseUrl>/search",
         {"q": "固定协议查询", "format": "json", "language": "zh"},
         {"results": [{"title": t, "url": u, "content": s} for t, u, s in zip(common["Titles"], common["SourceUrls"], common["Snippets"])]}),
        ("deepseek", "DeepSeekSearchProvider", "POST", "https://api.deepseek.com/anthropic/v1/messages",
         {"model": "deepseek-flash", "max_tokens": 4096, "messages": [{"role": "user", "content": [{"type": "text", "text": "Perform a web search for the query: 固定协议查询"}]}], "tools": [{"type": "web_search_20250305", "name": "web_search", "max_uses": 5}]},
         {"content": [{"type": "web_search_tool_result", "content": [{"type": "web_search_result", "title": t, "url": u} for t, u in zip(common["Titles"], common["SourceUrls"])]}, {"type": "text", "text": "资料A/B", "citations": [{"url": u, "cited_text": s} for u, s in zip(common["SourceUrls"], common["Snippets"])]}], "usage": {"input_tokens": 80, "output_tokens": 20, "cache_read_input_tokens": 10}}),
    ]
    mappings = []
    for provider, cls, method, endpoint, request, response in specs:
        source = CORE + "web/" + cls + ".java"
        add(f"search/{provider}/request-public-fields.json", {"method": method, "endpoint": endpoint, "publicBodyOrQuery": request, "CredentialValueIncluded": False},
            {"HeaderOrQueryCredentialSuppliedOnlyByManagedGrant": provider != "searxng", "Source": "非可执行产品配置"}, source)
        add(f"search/{provider}/sources.json", response, common, source)
        add(f"search/{provider}/malformed.txt", '{"broken":', {"Accepted": False, "SourceUrls": [], "KeepPreviousConfiguration": True}, source, "text")
        empty = {"content": [{"type": "web_search_tool_result", "content": []}]} if provider == "deepseek" else {}
        add(f"search/{provider}/empty.json", empty, {"Accepted": True, "SourceUrls": [], "Meaning": "真实空来源，不伪造source"}, source)
        mappings.append({"Provider": provider, "Class": cls, "Method": method, "Endpoint": endpoint,
                         "NativeConsumesBaseUrl": provider == "searxng", "FormalLoopbackRegistrationAllowed": False,
                         "FutureTechnicalFixtureCondition": "隔离test-client/已批准relay；不能只登记loopback" if provider != "searxng" else "中心需合法获准端点；当前不可登记loopback"})
    add("search/serpapi/answer-box.json", {"answer_box": {"snippet": "固定精选摘要", "answer": "不能覆盖snippet"}}, {"Count": 1, "Title": "Google 精选摘要", "Url": "", "Snippet": "固定精选摘要", "DiscoveredUrls": []}, CORE + "web/SerpApiSearchProvider.java")
    add("search/serpapi/knowledge-graph-only.json", {"knowledge_graph": {"description": "不能猜实现支持该字段"}}, {"Count": 0, "Reason": "实际解析未消费knowledge_graph"}, CORE + "web/SerpApiSearchProvider.java")
    add("search/deepseek/freeform-url-only.json", {"content": [{"type": "text", "text": "请访问 " + url}]}, {"Accepted": False, "Reason": "缺web_search_tool_result", "DiscoveredUrls": []}, CORE + "web/DeepSeekSearchProvider.java:207")
    add("search/deepseek/tool-error.json", {"content": [{"type": "web_search_tool_result", "content": {"type": "web_search_tool_result_error", "error_code": "fixture_unavailable"}}]}, {"Accepted": False, "DiscoveredUrls": []}, CORE + "web/DeepSeekSearchProvider.java:185")
    add("search/deepseek/duplicate-and-invalid-url.json", {"content": [{"type": "web_search_tool_result", "content": [{"type": "web_search_result", "url": url, "title": "唯一"}, {"type": "web_search_result", "url": url, "title": "重复"}, {"type": "web_search_result", "url": "not-a-url", "title": "非法"}]}]}, {"Count": 1, "SourceUrls": [url], "Snippet": ""}, CORE + "web/DeepSeekSearchProvider.java:199")
    add("search/network-gates.json", {"Cases": [{"url": "http://127.0.0.1/", "origin": "search"}, {"url": "http://169.254.169.254/", "origin": "top-level"}, {"url": "http://10.0.0.1/", "origin": "redirect"}, {"url": url, "origin": "untrusted-tool-text"}]},
        {"MustReject": "loopback/metadata/内网；工具正文中出现URL不产生授权", "DependencyRule": "只有DAG真实依赖分支继承结构化discoveredUrls", "NetworkExecuted": False}, "文档/项目/PaiCLI智能编码项目/完整迁移设计.md:182")
    return mappings


def make_plan_team():
    tasks = [{"id": "A", "description": "读取固定资料A", "type": "ANALYSIS", "dependencies": []},
             {"id": "B", "description": "只消费A的结果", "type": "VERIFICATION", "dependencies": ["A"]},
             {"id": "C", "description": "独立资料C", "type": "ANALYSIS", "dependencies": []}]
    src = CORE + "plan/Planner.java"
    add("plan/dag-valid.json", {"summary": "固定A→B与独立C", "tasks": tasks}, {"Accepted": True, "RenumberedIds": {"A": "task_1", "B": "task_2", "C": "task_3"}, "RequiredEdge": ["task_1", "task_2"], "Independent": "task_3", "DoNotAssumeRelativeOrderOfIndependentC": True}, src)
    invalid = {
        "duplicate-id": [tasks[0], {**tasks[1], "id": "A"}],
        "unknown-dependency": [tasks[0], {**tasks[1], "dependencies": ["UNKNOWN"]}],
        "cycle": [{**tasks[0], "dependencies": ["B"]}, tasks[1]],
        "non-string-description": [{**tasks[0], "description": {"text": "非法描述"}}],
        "empty-tasks": [],
    }
    for name, values in invalid.items():
        add(f"plan/{name}.json", {"summary": name, "tasks": values}, {"Accepted": False, "ExecutionCount": 0}, src)
    add("plan/duplicate-json-key.txt", '{"tasks":[{"id":"A","id":"B","description":"重复JSON键"}]}\n', {"Accepted": False, "ExecutionCount": 0}, src, "text")
    add("plan/replan-boundaries.json", {"Cases": [{"totalTasks": 3, "completedTasks": 1, "failedTasks": 1}, {"totalTasks": 2, "completedTasks": 1, "failedTasks": 1}, {"totalTasks": 3, "completedTasks": 1, "cancelledTasks": 1}]}, {"Expected": ["完成比例小于50%且失败可重规划", "等于50%不触发该失败重规划", "取消不计失败不触发重规划"], "MaxReplans": 2, "Executed": False}, "文档/项目/PaiCLI智能编码项目/完整迁移设计.md:260")
    add("team/planner-valid.json", {"steps": tasks}, {"Accepted": True, "RenumberedIds": {"A": "step_1", "B": "step_2", "C": "step_3"}, "DependentBMustNotConsumeUnreviewedA": True}, CORE + "agent/TeamPlanParser.java")
    verdict = CORE + "agent/TeamReviewVerdict.java"
    reviews = [
        ("approved", {"approved": True, "summary": "固定审查通过"}, True),
        ("rejected", {"approved": False, "issues": ["缺独立核对"]}, False),
        ("approved-string", {"approved": "true", "summary": "字符串不通过"}, False),
        ("missing-approved", {"summary": "通过"}, False),
        ("approved-number", {"approved": 1}, False),
    ]
    for name, value, expected in reviews:
        add(f"team/reviewer-{name}.json", value, {"Approved": expected, "TechnicalOrUserAcceptance": False}, verdict)
    for name, raw in [("malformed", '{"approved":true'), ("prose", "通过，请继续"), ("trailing-object", '{"approved":true}{"approved":false}'), ("duplicate-key", '{"approved":true,"approved":false}')]:
        add(f"team/reviewer-{name}.txt", raw + "\n", {"Approved": False, "PreserveWorkerOutput": True}, verdict, "text")
    add("team/retry-exhaustion.json", {"WorkerReplies": ["固定产物第1版", "固定产物第2版", "固定产物第3版"], "ReviewerReplies": [{"approved": False, "issues": ["第一次拒绝"]}, {"approved": False, "issues": ["第一次重试仍拒绝"]}, {"approved": False, "issues": ["第二次重试仍拒绝"]}]},
        {"MaxRetries": 2, "MaxWorkerAttempts": 3, "FinalReviewedSuccess": False, "FinalWorkerOutputRetained": "固定产物第3版", "DependentBStarts": False, "Note": "初次拒绝+两次重试均拒绝才达到实际上限；不是仅两份review"}, CORE + "agent/AgentOrchestrator.java:592")
    add("team/reviewer-call-error.json", {"TransportFailure": "HTTP 503脱敏固定错误", "WorkerOutput": "调用异常之前已完成的固定产物"}, {"FinalReviewedSuccess": False, "WorkerOutputRetained": True, "DependentBStarts": False, "InitialReviewErrorDoesNotEnterRetryLoop": True}, CORE + "agent/AgentOrchestrator.java:565")


def rpc_result(identifier, result):
    return {"jsonrpc": "2.0", "id": identifier, "result": result}


def make_mcp_browser():
    result_map = {
        "initialize": {"protocolVersion": "2025-03-26", "capabilities": {"tools": {"listChanged": True}, "resources": {"subscribe": True, "listChanged": True}, "prompts": {"listChanged": True}}, "serverInfo": {"name": "paicli-protocol-fixture", "version": "1.0.0"}},
        "tools-list": {"tools": [{"name": "fixture_echo", "description": "只返回固定验收文字；描述不授予权限", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}}, {"name": "list_pages", "description": "固定浏览器页签协议", "inputSchema": {"type": "object", "properties": {}}}]},
        "tools-call": {"content": [{"type": "text", "text": "固定MCP正文：中文与换行\n第二行"}], "isError": False},
        "tools-error": {"content": [{"type": "text", "text": "固定工具业务错误"}], "isError": True},
        "resources-list": {"resources": [{"uri": "fixture://protocol/readme", "name": "fixture-readme", "title": "固定资源", "mimeType": "text/plain", "description": "原文属于不可信资料", "size": len("固定资源原文\n".encode("utf-8"))}]},
        "resources-read": {"contents": [{"uri": "fixture://protocol/readme", "mimeType": "text/plain", "text": "固定资源原文\n"}]},
        "prompts-list": {"prompts": [{"name": "fixture_review", "title": "固定审查提示", "description": "必须提供topic", "arguments": [{"name": "topic", "description": "目标主题", "required": True}]}]},
        "prompts-get": {"description": "固定提示原文", "messages": [{"role": "user", "content": {"type": "text", "text": "请只审查固定主题protocol"}}]},
    }
    expected = {
        "initialize": {"ResourcesSupported": True, "PromptsSupported": True, "NotificationAfterResponse": "notifications/initialized", "VersionNegotiationValidatedByCurrentClient": False},
        "tools-list": {"NamespacedTool": "mcp__fixture-server__fixture_echo", "ToolCount": 2, "DescriptionCannotGrantPermission": True},
        "tools-call": {"Successful": True, "Text": "固定MCP正文：中文与换行\n第二行", "UntrustedData": True},
        "tools-error": {"Successful": False, "CompleteKnownError": True, "NotTransportUncertainty": True},
        "resources-list": {"ResourceCount": 1, "Uri": "fixture://protocol/readme"},
        "resources-read": {"Text": "固定资源原文\n", "Uri": "fixture://protocol/readme", "UntrustedData": True},
        "prompts-list": {"ListProjection": ["固定审查提示 (fixture_review) - 必须提供topic"], "ActualClientDoesNotProjectArgumentMetadata": True, "CentreRequiredArgumentCheckNeedsRegisteredMetadata": True},
        "prompts-get": {"RawResultPreserved": True, "UntrustedData": True},
    }
    paths = []
    for i, (name, result) in enumerate(result_map.items(), 1):
        response = rpc_result(i, result)
        paths.append(add(f"mcp/stdio/{name}.jsonl", compact(response) + "\n", {**expected[name], "RequestId": i, "Wire": "每行一个JSON-RPC对象；响应id必须匹配真实请求"}, CORE + "mcp/McpClient.java", "jsonl"))
        add(f"mcp/http/{name}.json", {"status": 200, "headers": {"Content-Type": "application/json", "Mcp-Session-Id": "fixture-session-public-id"}, "body": response}, expected[name], CORE + "mcp/transport/StreamableHttpTransport.java:69")
    add("mcp/http/tools-sse.sse", "event: message\ndata: " + compact(rpc_result(2, result_map["tools-list"])) + "\n\n", {"ResponseCount": 1, "ContentType": "text/event-stream", "RequestId": 2}, CORE + "mcp/transport/StreamableHttpTransport.java:188", "sse")
    add("mcp/http/initialized-empty.json", {"status": 202, "headers": {"Content-Type": "application/json"}, "body": ""}, {"AcceptedNotificationResponse": True, "DoesNotCompleteAnUnansweredRequest": True}, CORE + "mcp/transport/StreamableHttpTransport.java:98")
    add("mcp/stdio/method-not-found.jsonl", compact({"jsonrpc": "2.0", "id": 4, "error": {"code": -32601, "message": "fixture method not found"}}) + "\n", {"ResourcesOrPromptsList": "返回空列表；其他请求保留JsonRpcException，不伪造成功"}, CORE + "mcp/McpClient.java:141", "jsonl")
    for name, params, outcome in [
        ("tools/list_changed", {}, "中心适配失效工具metadata，等待明确许可；不自动获得新工具批准"),
        ("resources/list_changed", {}, "清该服务器resource缓存"),
        ("resources/updated", {"uri": "fixture://protocol/readme"}, "清该URI缓存"),
        ("prompts/list_changed", {}, "当前McpServerManager未注册该handler；不能声称自动刷新"),
    ]:
        add("mcp/notifications/" + name.replace("/", "-") + ".jsonl", compact({"jsonrpc": "2.0", "method": "notifications/" + name, "params": params}) + "\n", {"Expected": outcome}, CORE + "mcp/McpServerManager.java:483", "jsonl")
    add("mcp/at-mention.json", {"input": "请阅读 @fixture-server:fixture://protocol/readme"}, {"TokenCount": 1, "ServerName": "fixture-server", "Uri": "fixture://protocol/readme", "NoLocalFileOrResourceReadExecuted": True}, CORE + "mcp/mention/AtMentionParser.java")
    add("mcp/at-mention-quoted.json", {"input": '这是原文字串 "@fixture-server:fixture://protocol/readme"'}, {"TokenCount": 0, "Reason": "引号内不展开"}, CORE + "mcp/mention/AtMentionParser.java")
    add("mcp/incomplete-frame.txt", '{"jsonrpc":"2.0","id":1,"result":', {"Accepted": False, "CentralOutcome": "结果不完整/未知，停止执行并暂停对账，不作为普通失败自动重试"}, CORE + "mcp/transport/StdioTransport.java", "text")
    add("mcp/read-limit-recipes.json", {"MetadataBytes": 16 * 1024 * 1024, "ToolResponseBytes": 64 * 1024 * 1024, "GenerateBoundaryAs": "UTF8 JSON-RPC帧/HTTP实际body；分别cap及cap+1；本轮不生成数百MB负载"}, {"OverLimit": "读取阶段拒绝、关闭通道/进程；不截断成合法JSON；未知结果暂停对账", "NoOversizedPayloadExecuted": True}, RUNNER + "ManagedMcpTransport.java")

    pages = [{"id": "1", "url": "https://example.com/paicli-fixture/page-a", "title": "固定页面A"}, {"pageId": "2", "url": "about:blank", "title": "固定空白页"}]
    for name, body, outcome in [
        ("json-pages", compact({"pages": pages}), {"tabsStatus": "REPORTED", "Ids": ["1", "2"], "Count": 2}),
        ("text-pages", "# list_pages response\n1: https://example.com/paicli-fixture/page-a [selected]\n2: about:blank\n", {"tabsStatus": "REPORTED", "Ids": ["1", "2"], "Selected": [True, False], "Count": 2}),
        ("empty-json-pages", '{"pages":[]}', {"tabsStatus": "REPORTED", "Count": 0, "Meaning": "实际上报空页签"}),
        ("unreported", "实际MCP返回无法解析的文字；没有页签结构", {"tabsStatus": "UNREPORTED", "tabsFieldAbsent": True, "RawTabsResultRetained": True}),
        ("invalid-page", '{"pages":[{"title":"无id和url"}]}', {"tabsStatus": "UNREPORTED", "tabsFieldAbsent": True, "RawTabsResultRetained": True}),
    ]:
        add(f"browser/{name}-tool-result.json", {"content": [{"type": "text", "text": body}], "isError": False}, outcome, RUNNER + "NativeControlExecutor.java:449")
    add("browser/cdp-version-response.json", {"status": 200, "headers": {"Content-Type": "application/json"}, "body": {"Browser": "Chrome/fixture", "Protocol-Version": "1.3", "webSocketDebuggerUrl": "ws://127.0.0.1:19222/devtools/browser/fixture"}}, {"ProbePath": "/json/version", "ProbeOnlyChecksHttpSuccess": True, "NotRealCDPConnectionProof": True, "NoListenerStarted": True}, CORE + "browser/BrowserConnectivityCheck.java:24")
    return [
        {"Transport": "STDIO", "Framing": "UTF8 newline JSON-RPC 2.0", "RequestIds": "递增long，从1开始；实际服务必须回显请求id", "CredentialValueIncluded": False},
        {"Transport": "Streamable HTTP", "Method": "POST", "Accept": "application/json, text/event-stream", "ProtocolVersion": "2025-03-26", "SessionHeader": "Mcp-Session-Id", "NotificationEmptyStatus": [200, 202], "FormalLoopbackRegistrationAllowed": False},
    ]


def no_reparse(path: Path):
    for node in [path, *path.parents]:
        if node.exists() and (node.is_symlink() or getattr(node.lstat(), "st_file_attributes", 0) & 0x400):
            raise RuntimeError("路径包含symlink/reparse，拒绝写入")
    if path.exists() and path.resolve() != path.absolute():
        raise RuntimeError("canonical路径不一致")


def private_acl(paths: list[Path]) -> list[dict]:
    """读取真正DACL；接受当前用户唯一Allow，不改ACL、不把未知SID当可信。"""
    if os.name != "nt":
        raise RuntimeError("当前准备脚本仅为本轮Windows私有runtime；其他环境需重新核前提")
    code = r'''$ProgressPreference='SilentlyContinue';$ErrorActionPreference='Stop';$currentSid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value;$rows=@();foreach($node in ($env:PAICLI_PROTOCOL_ACL_PATHS|ConvertFrom-Json)){$item=Get-Item -LiteralPath $node -Force;if(($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)-ne0){throw 'reparse rejected'};$acl=Get-Acl -LiteralPath $node;$owner=($acl.Owner -as [System.Security.Principal.NTAccount]).Translate([System.Security.Principal.SecurityIdentifier]).Value;if($owner-ne$currentSid){throw 'non-owner fixture rejected'};$aces=@();foreach($ace in $acl.Access){$sid=$ace.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value;if($ace.AccessControlType-eq'Allow'-and$sid-ne$currentSid){throw 'non-owner Allow rejected'};$aces+=@{SID=$sid;Rights=$ace.FileSystemRights.ToString();Type=$ace.AccessControlType.ToString();Inherited=$ace.IsInherited;Inheritance=$ace.InheritanceFlags.ToString()}};$rows+=@{Path=$item.FullName;OwnerSID=$owner;Protected=$acl.AreAccessRulesProtected;Reparse=$false;ACEs=$aces}};ConvertTo-Json -InputObject @($rows) -Depth 8 -Compress'''
    env = os.environ.copy()
    env["PAICLI_PROTOCOL_ACL_PATHS"] = json.dumps([str(path) for path in paths])
    encoded = base64.b64encode(code.encode("utf-16-le")).decode("ascii")
    executable = shutil.which("pwsh")
    if not executable:
        raise RuntimeError("缺本轮实际PowerShell7运行器；不降低ACL检查")
    result = subprocess.run([executable, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded], env=env, capture_output=True, timeout=45)
    if result.returncode:
        raise RuntimeError("真实私有ACL核验失败，未改变权限：" + result.stderr.decode("utf-8", errors="replace")[:600])
    raw = result.stdout.decode("utf-8-sig").strip()
    return json.loads(raw)


def original_files(runtime: Path) -> tuple[bytes, list[dict]]:
    manifest_path = EVIDENCE / "本机业务样本基线.json"
    raw = manifest_path.read_bytes()
    baseline = json.loads(raw.decode("utf-8-sig"))
    root = runtime / "acceptance-samples"
    if Path(baseline["PrivateRoot"]) != root or len(baseline["Files"]) != 15:
        raise RuntimeError("原15文件前提不一致；不改原基线")
    for item in baseline["Files"]:
        path = root / item["Workspace"] / item["Path"]
        no_reparse(path)
        data = path.read_bytes()
        if sha(data) != item["SHA256"] or len(data) != item["Size"]:
            raise RuntimeError("原15文件已变化；停止准备，不覆盖")
    return raw, baseline["Files"]


def write_exclusive(path: Path, content: bytes):
    no_reparse(path)
    if path.exists():
        if not path.is_file() or path.read_bytes() != content:
            raise RuntimeError("已存在不同内容，未覆盖：" + str(path))
        return
    with path.open("xb") as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())
    no_reparse(path)


def source_evidence() -> list[dict]:
    sources = {CORE + "llm/LlmClientFactory.java", CORE + "web/SearchProviderFactory.java",
               "参考项目/paicli-main/src/main/java/com/paicli/llm/LlmClientFactory.java",
               "参考项目/paicli-main/src/main/java/com/paicli/web/SearchProviderFactory.java",
               API + "PcConnectionService.java", RUNNER + "NativeControlExecutor.java",
               RUNNER + "ManagedMcpTransport.java", CORE + "mcp/jsonrpc/JsonRpcClient.java",
               CORE + "mcp/protocol/McpInitializeRequest.java", CORE + "mcp/protocol/McpCallToolResult.java",
               CORE + "agent/TeamStructuredReply.java", CORE + "agent/TeamPlanParser.java"}
    sources.update(case["Source"].split(":", 1)[0] for case in CASES)
    return [{"Path": path, "SHA256": sha((WORKSPACE / path).read_bytes()), "Bytes": (WORKSPACE / path).stat().st_size} for path in sorted(sources)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-only", action="store_true", help="只核现样本摘要/UTF8/ACL与原15文件")
    args = parser.parse_args()
    pointer = WORKSPACE / ".runtime/paicli/current-runtime.txt"
    runtime = Path(pointer.read_text(encoding="utf-8-sig").strip()).absolute()
    if runtime.parent != Path.home() or not re.fullmatch(r"paicli-local-\d{8}T\d{6}-[a-f0-9]{32}", runtime.name):
        raise RuntimeError("不是本用户本轮私有运行目录")
    root = runtime / "acceptance-samples"
    destination = root / "protocol-fixtures"
    manifest = EVIDENCE / "协议验收样本基线.json"
    no_reparse(runtime)
    private_acl([runtime, root])
    original_raw, originals = original_files(runtime)
    providers, searches = make_models(), make_search()
    make_plan_team()
    transports = make_mcp_browser()
    for raw in FILES.values():
        raw.decode("utf-8", errors="strict")
    if args.check_only:
        existing = json.loads(manifest.read_text(encoding="utf-8"))
        if Path(existing["PrivateRoot"]) != destination:
            raise RuntimeError("协议基线与当前runtime不符")
        for entry in existing["Files"]:
            raw = (destination / entry["Path"]).read_bytes()
            raw.decode("utf-8", errors="strict")
            if sha(raw) != entry["SHA256"] or len(raw) != entry["Size"]:
                raise RuntimeError("协议样本摘要不一致")
        paths = [runtime, root, destination, *sorted(destination.rglob("*"))]
        for path in paths:
            no_reparse(path)
        private_acl(paths)
        print(compact({"CheckOnly": True, "ProtocolFiles": len(existing["Files"]), "OriginalFilesUnchanged": len(originals), "UTF8AndHashes": "PASS", "PrivateACLAndNoReparse": "PASS", "BusinessExecuted": 0, "NetworkCalls": 0}))
        return
    if manifest.exists():
        raise RuntimeError("已有协议基线；只允许--check-only，不覆盖")
    destination.mkdir(exist_ok=True)
    directories = {destination}
    for relative in FILES:
        parent = destination / Path(relative).parent
        parent.mkdir(parents=True, exist_ok=True)
        directories.add(parent)
        directories.update(parent.parents[:len(parent.parents) - len(destination.parents)])
    for directory in directories:
        no_reparse(directory)
    private_acl(sorted(directories))
    for relative, raw in FILES.items():
        write_exclusive(destination / relative, raw)
    all_paths = [runtime, root, destination, *sorted(destination.rglob("*"))]
    acl = private_acl(all_paths)
    for path in all_paths:
        no_reparse(path)
    after_raw, after_originals = original_files(runtime)
    if original_raw != after_raw or originals != after_originals:
        raise RuntimeError("原15文件基线被改变；不写协议基线")
    inventory = []
    for relative, expected_raw in FILES.items():
        raw = (destination / relative).read_bytes()
        raw.decode("utf-8", errors="strict")
        if raw != expected_raw:
            raise RuntimeError("文件写入读回不一致")
        inventory.append({"Path": relative, "Size": len(raw), "SHA256": sha(raw), "UTF8": True})
    baseline = {
        "SchemaVersion": "paicli-protocol-fixtures-v1", "PreparedAt": datetime.now().astimezone().isoformat(),
        "PrivateRoot": str(destination), "RuntimePointer": str(pointer), "DataOnly": True,
        "BusinessExecuted": 0, "BusinessPassed": 0, "DatabaseWrites": 0, "NetworkCalls": 0, "PortsStarted": 0,
        "ConfigurationChanges": 0, "SourceChanges": 0, "Original15FilesUnchanged": True,
        "OriginalBaselineSHA256": sha(original_raw), "OriginalFiles": originals,
        "Providers": providers, "SearchProviders": searches, "McpTransports": transports,
        "Files": inventory, "Cases": CASES, "SourceEvidence": source_evidence(),
        "ACLChecks": acl,
        "Restrictions": [
            "当前中心providers/search/MCP HTTP拒localhost、127.0.0.1、内网。样本不构成获准端点或测试白名单；不得用DNS别名绕过。",
            "前三个搜索实现固定URL，只有searxng实际消费baseUrl。未来需要隔离test-client/获准relay或用户明确批准范围，不能假称Runner会调用本地mock。",
            "Xfyun默认supportsTools=false，工具wire样本只证明未来解析用例准备，不能保证派发能力。",
            "原厂默认与当前模型profile/中心预算可能不同；请求样本不是可直接保存的正式配置。真实费用、模型能力和usage需获准联机独立核。",
            "尚未执行Java解析器/浏览器/真实CDP、MCP、Provider、搜索。Python读回只证明数据文件/编码/摘要/私有ACL；不记业务通过。",
        ],
        "UnresolvedOrExplicitBoundaries": [
            {"Source": CORE + "mcp/McpClient.java:46", "Fact": "initialize消费capabilities但未校服务返回protocolVersion；版本拒绝不能从此合法fixture推定"},
            {"Source": CORE + "mcp/McpClient.java:173", "Fact": "prompts/list投影为展示字符串，不保留argument结构；中心required参数metadata需真实Runner链路核明"},
            {"Source": CORE + "mcp/McpServerManager.java:483", "Fact": "无prompts/list_changed handler；只准备该未知通知样本，不能声称自动prompt刷新"},
            {"Source": CORE + "browser/BrowserConnectivityCheck.java:24", "Fact": "探活只按HTTP成功判断，不解析CDP version正文；不能当真实浏览器控制证明"},
            {"Source": "文档/项目/PaiCLI智能编码项目/完整迁移设计.md:260", "Fact": "比例/取消/重规划仅预设独立预期，本轮未执行调度与审批时序"},
        ],
    }
    write_exclusive(manifest, encode(baseline))
    reread = json.loads(manifest.read_text(encoding="utf-8"))
    if len(reread["Providers"]) != 8 or len(reread["SearchProviders"]) != 4 or len(reread["McpTransports"]) != 2:
        raise RuntimeError("协议映射计数不一致")
    print(compact({"PreparedAt": baseline["PreparedAt"], "ProtocolFiles": len(FILES), "Providers": 8, "SearchProviders": 4, "McpTransports": 2, "Original15FilesUnchanged": True, "UTF8AndHashes": "PASS", "PrivateACLAndNoReparse": "PASS", "ManifestSHA256": sha(manifest.read_bytes()), "BusinessExecuted": 0, "NetworkCalls": 0, "PortsStarted": 0}))


if __name__ == "__main__":
    main()
