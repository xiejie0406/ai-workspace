"""原 Agent Builder/Chat/CoT/ReAct 的受控工厂。

工厂沿用原 Prompt、步骤解析、scratchpad、工具选择及消息输出算法。外部
调用由租约关联的能力回调承担；没有旧配置、凭据、bot_config 数据库或 URL。
"""
from __future__ import annotations

import copy
import json
from types import SimpleNamespace

from .errors import WorkerError


async def build_original_agent(payload, *, span, model_factory, tool_schemas,
                               tool_executor, mcp_servers, mcp_executor,
                               workflow_schemas, workflow_executor, knowledge_executor,
                               app_id='', uid=''):
    from agent.api.schemas.workflow_agent_inputs import CustomCompletionInputs
    from agent.service.builder.workflow_agent_builder import WorkflowAgentRunnerBuilder
    from agent.service.plugin.link import LinkPluginFactory
    from agent.service.plugin.mcp import McpPluginFactory
    from agent.service.plugin.workflow import WorkflowPluginFactory

    request = copy.deepcopy(payload)
    request.setdefault('model_config', {}).setdefault('api', '')
    configuration = request['model_config']
    if configuration.get('api') or configuration.get('api_key') or request.get('plugin', {}).get('mcp_server_urls'):
        raise WorkerError('TOOL_POLICY_DENIED', '受控 Agent 不接收原模型凭据/API/MCP URL')
    inputs = CustomCompletionInputs.model_validate(request)
    if not 1 <= inputs.max_loop_count <= 30:
        raise WorkerError('QUOTA_EXCEEDED', 'Agent 循环预算须在1至30轮')

    class ControlledLinkFactory(LinkPluginFactory):
        async def tool_schema_list(self, current_span):
            return copy.deepcopy(tool_schemas)

    class ControlledMcpFactory(McpPluginFactory):
        async def query_servers(self, current_span):
            return copy.deepcopy(mcp_servers)

    class ControlledWorkflowFactory(WorkflowPluginFactory):
        async def query_workflows_schema_list(self, current_span):
            return copy.deepcopy(workflow_schemas)

    class ControlledBuilder(WorkflowAgentRunnerBuilder):
        async def create_model(self, app_id, model_name, base_url, api_key=''):
            if base_url or api_key:
                raise WorkerError('TOOL_POLICY_DENIED', '模型只能由中心已绑定的能力创建')
            return await model_factory(model_name)

        async def build_plugins(self, tool_ids, mcp_server_ids, mcp_server_urls, workflow_ids):
            if mcp_server_urls:
                raise WorkerError('TOOL_POLICY_DENIED', 'MCP 只能使用固定服务标识')
            plugins = []
            if tool_ids:
                built = await ControlledLinkFactory(app_id=self.app_id, uid=self.uid, tool_ids=tool_ids).gen(self.span)
                for plugin in built:
                    # 继续使用原 runner 的 query/header/body/x-from/x-display 组装。
                    original_runner = plugin.run.__self__
                    async def run_tool(action_input, current_span, runner=original_runner):
                        return await tool_executor(runner, action_input, current_span)
                    plugin.run = run_tool
                plugins.extend(built)
            if mcp_server_ids:
                built = await ControlledMcpFactory(app_id=self.app_id, mcp_server_ids=mcp_server_ids,
                                                   mcp_server_urls=[]).gen(self.span)
                for plugin in built:
                    original_runner = plugin.run.__self__
                    async def run_mcp(action_input, current_span, runner=original_runner):
                        return await mcp_executor(runner, action_input, current_span)
                    plugin.run = run_mcp
                plugins.extend(built)
            if workflow_ids:
                built = await ControlledWorkflowFactory(app_id=self.app_id, uid=self.uid,
                                                        workflow_ids=workflow_ids).gen(self.span)
                for plugin in built:
                    original_runner = plugin.run.__self__
                    async def run_flow(action_input, span, runner=original_runner):
                        async for item in workflow_executor(runner, action_input, span):
                            yield item
                    plugin.run = run_flow
                plugins.extend(built)
            names = [plugin.name for plugin in plugins]
            if len(names) != len(set(names)):
                raise WorkerError('VERSION_CONFLICT', '固定 Agent 工具名重复，无法唯一裁决所选能力')
            return plugins

        async def exec_query_knowledge(self, params, current_span):
            return await knowledge_executor(params, self.inputs.get_last_message_content(), current_span)

    return await ControlledBuilder(app_id=app_id, uid=uid, span=span, inputs=inputs).build()


class CenterAgentModelFactory:
    """把原中心模型帧交给原 Agent BaseLLMModel；消息预算/终态仍由中心裁决。"""
    def __init__(self, node, options):
        self.node, self.options = node, options

    async def __call__(self, model_name):
        from agent.domain.models.base import BaseLLMModel
        from openai.types.chat.chat_completion_chunk import ChatCompletionChunk
        from .model_bridge import CenterOpenAIChatAI, CenterSparkChatAI

        node = self.node
        if node.modelConfig.domain != model_name:
            raise WorkerError('VERSION_CONFLICT', '原 Agent 所选模型与固定节点不同')
        bridge_type = {'openai': CenterOpenAIChatAI, 'xinghuo': CenterSparkChatAI}.get(node.source)
        if bridge_type is None:
            raise WorkerError('ENGINE_UNSUPPORTED', '原 Agent 所选模型供应商未迁入')
        options = self.options
        bridge = bridge_type(node_id=node.node_id, resource_id=node.modelResourceId,
            model_url='', model_name=model_name, app_id='', api_key='', api_secret='', uid='',
            temperature=options['temperature'], max_tokens=options['maxTokens'], top_k=options['topK'],
            spark_version='', patch_id=options['patchId'], response_format=options['respFormat'])

        class ControlledModel(BaseLLMModel):
            llm: object = None

            async def create_completion(self, messages, stream):
                if stream is not True:
                    raise WorkerError('ENGINE_UNSUPPORTED', '原 Agent 只使用模型流')
                async def chunks():
                    body = bridge._parameters(messages, None, options['extraParams'], None,
                                              options['searchDisable'], mode='chat')
                    async for response in bridge._frames(body):
                        frame = response.msg
                        if node.source == 'xinghuo':
                            code, status, content, reasoning, usage = bridge.decode_message(frame)
                            if code:
                                raise WorkerError('MODEL_FAILED', '中心 Spark 模型返回原失败帧')
                            frame = {'id': '', 'object': 'chat.completion.chunk', 'created': 0, 'model': model_name,
                                'choices': [{'index': 0, 'delta': {'content': content, 'reasoning_content': reasoning},
                                             'finish_reason': 'stop' if status == 2 else None}], 'usage': usage or None}
                        yield ChatCompletionChunk.model_validate(frame)
                return chunks()
        return ControlledModel(name=model_name)


async def consume_bound_agent(node, payload, resource, consume):
    """实际调用原 Agent 算法；依赖缺描述或中心操作未实现时保留明确失败。"""
    import time
    from agent.common_imports import NodeTrace, Span
    from agent.service.plugin.base import PluginResponse
    from .legacy_operations import parameters, completed_response
    from .node_capabilities import _link_response
    from .task_runner import current_context

    context = current_context()
    # AgentNode 仅含历史预算 maxTokens，不继承 BaseLLMNode 采样参数。
    # 不能把历史预算误作生成预算，实际模型配置必须由中心固定描述提供。
    options = resource.get('agentModelOptions')
    if (not isinstance(options, dict) or set(options) != {'temperature', 'maxTokens', 'topK',
            'patchId', 'respFormat', 'searchDisable', 'extraParams'}
            or isinstance(options['temperature'], bool) or not isinstance(options['temperature'], (int, float))
            or not 0 < options['temperature'] <= 1 or type(options['maxTokens']) is not int or options['maxTokens'] <= 0
            or type(options['topK']) is not int or options['topK'] <= 0 or not isinstance(options['patchId'], list)
            or any(not isinstance(item, str) for item in options['patchId']) or type(options['respFormat']) is not int
            or options['respFormat'] not in {0, 1, 2}
            or type(options['searchDisable']) is not bool or not isinstance(options['extraParams'], dict)):
        raise WorkerError('RESOURCE_UNAVAILABLE', 'Agent 缺少中心固定模型参数，不能借用历史预算或本地默认值')
    selected = resource['dependencySelections']
    fixed = context.assignment.resources
    tools, servers, workflows = [], [], []
    for selection, identifier in selected.items():
        descriptor = fixed[identifier]
        if selection.startswith('tool:'):
            if (not isinstance(descriptor.get('openApiSchema'), dict)
                    or descriptor.get('effectClass') not in {'read', 'write'}):
                raise WorkerError('RESOURCE_UNAVAILABLE', 'Agent 工具缺少固定原 OpenAPI schema')
            schema = copy.deepcopy(descriptor['openApiSchema'])
            schema['paths'] = {path: allowed for path, methods in schema.get('paths', {}).items()
                if isinstance(methods, dict) and (allowed := {method: value for method, value in methods.items()
                    if isinstance(value, dict) and value.get('operationId') == descriptor.get('operationId')})}
            if sum(len(methods) for methods in schema['paths'].values()) != 1:
                raise WorkerError('VERSION_CONFLICT', 'Agent 固定工具必须唯一对应所选 operationId')
            tools.append({'id': descriptor['pluginId'], 'version': descriptor['legacyVersion'],
                          'schema': json.dumps(schema, ensure_ascii=False)})
        elif selection.startswith('mcp:'):
            if not isinstance(descriptor.get('tools'), list):
                raise WorkerError('RESOURCE_UNAVAILABLE', 'Agent MCP 缺少固定工具列表')
            servers.append({'server_id': descriptor['legacyServerId'], 'server_url': '',
                            'server_status': 0, 'tools': descriptor['tools']})
        elif selection.startswith('workflow:'):
            if (not isinstance(descriptor.get('workflowSchema'), dict)
                    or descriptor['workflowSchema'].get('data', {}).get('data', {}).get('id') != descriptor.get('flowId')):
                raise WorkerError('RESOURCE_UNAVAILABLE', 'Agent 子流程缺少固定原 schema')
            workflows.append(descriptor['workflowSchema'])

    async def tool_executor(runner, inputs, current_span):
        identifier = selected.get(f'tool:{runner.tool_id}:{runner.version}')
        descriptor = fixed.get(identifier)
        if not isinstance(descriptor, dict) or descriptor.get('operationId') != runner.operation_id:
            raise WorkerError('TOOL_POLICY_DENIED', 'Agent 所选工具不属于固定 operationId')
        body_schema = runner.method_schema.get('requestBody', {}).get('content', {}).get('application/json', {}).get('schema', {})
        header, query = runner.assemble_parameters(inputs, {})
        body = runner.assemble_body(body_schema, inputs, {})
        message = {key: runner.dumps(value) for key, value in {'header': header, 'query': query, 'body': body}.items() if value}
        original = {'header': {'app_id': runner.app_id, 'uid': runner.uid},
                    'parameter': {'tool_id': runner.tool_id, 'version': runner.version, 'operation_id': runner.operation_id},
                    'payload': {'message': message}}
        body, fingerprint = parameters(identifier, descriptor, original)
        started = int(time.time() * 1000)
        operation = 'tools.read' if descriptor.get('effectClass') == 'read' else 'tools.invoke'
        result = await completed_response(node, operation, body, fingerprint, _link_response)
        return PluginResponse(code=result['header']['code'], sid=current_span.sid, start_time=started,
                              end_time=int(time.time() * 1000), result=result)

    async def mcp_executor(runner, inputs, current_span):
        identifier = selected.get(f'mcp:{runner.server_id}')
        descriptor = fixed.get(identifier)
        if (not isinstance(descriptor, dict) or runner.server_url
                or runner.name not in {tool.get('name') for tool in descriptor.get('tools', [])}):
            raise WorkerError('TOOL_POLICY_DENIED', 'Agent MCP 工具不属于固定服务/工具选择')
        body, fingerprint = parameters(identifier, descriptor,
            {'mcp_server_id': runner.server_id, 'tool_name': runner.name, 'tool_args': inputs, 'sid': current_span.sid})
        def validate(value, failed):
            if not isinstance(value, dict) or type(value.get('code')) is not int or (value['code'] != 0) != failed:
                raise WorkerError('DSL_INVALID', '原 MCP 回执缺少合法错误码')
        started = int(time.time() * 1000)
        operation = 'tools.read' if descriptor.get('effectClass') == 'read' else 'mcp.invoke'
        result = await completed_response(node, operation, body, fingerprint, validate)
        return PluginResponse(code=result['code'], sid=current_span.sid, start_time=started,
                              end_time=int(time.time() * 1000), result=result)

    async def workflow_executor(runner, inputs, current_span):
        from .flow_bridge import frames
        from agent.service.plugin.workflow import ResponseContext
        identifier = selected.get(f'workflow:{runner.flow_id}')
        descriptor = fixed.get(identifier)
        if not isinstance(descriptor, dict):
            raise WorkerError('TOOL_POLICY_DENIED', 'Agent 子流程选择未绑定中心版本')
        proxy = SimpleNamespace(node_id=node.node_id, flowResourceId=identifier, flowId=runner.flow_id,
                                version=descriptor.get('legacyVersion'), enableChatHistoryV2=SimpleNamespace(is_enabled=False))
        started = int(time.time() * 1000)
        async for frame in frames(proxy, inputs, descriptor.get('limits', {}).get('timeoutSeconds', 40)):
            ctx = ResponseContext(code=frame.get('code', 0), sid=current_span.sid, start_time=started,
                                  end_time=int(time.time() * 1000), action_input=inputs)
            if ctx.code:
                yield runner._create_error_response(ctx, frame)
            elif frame.get('choices'):
                delta = frame['choices'][0]['delta']
                yield runner._create_success_response(ctx, delta.get('content', ''), delta.get('reasoning_content', ''))

    async def knowledge_executor(params, query, current_span):
        provider = params.rag_type
        matches = [identifier for selection, identifier in selected.items() if selection.startswith('knowledge:')
                   and fixed[identifier].get('provider') == provider and fixed[identifier].get('repoIds') == params.repo_ids
                   and (fixed[identifier].get('docIds') or []) == params.doc_ids]
        if len(matches) != 1:
            raise WorkerError('VERSION_CONFLICT', 'Agent 知识选择无法唯一绑定固定资源')
        identifier = matches[0]
        descriptor = fixed[identifier]
        request = {'resourceId': identifier, 'resourceVersion': descriptor['version'], 'legacyPayload': {
            'query': query, 'topN': params.top_k, 'scoreThreshold': params.score_threshold, 'ragType': provider,
            'match': {'repo_ids': params.repo_ids, 'doc_ids': params.doc_ids}}}
        import hashlib
        identity = [context.assignment.run_id, context.assignment.lease_id, context.assignment.attempt, node.node_id, request]
        key = 'agent_knowledge_' + hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        result = await context.operation(node.node_id, 'knowledge.retrieve', request, key=key)
        if (not isinstance(result, dict) or result.get('state') != 'COMPLETED' or result.get('failureKind') is not None
                or result.get('resourceId') != identifier or result.get('resourceVersion') != descriptor['version']
                or result.get('provider') != provider or not isinstance(result.get('data'), dict)
                or not isinstance(result['data'].get('results'), list)):
            raise WorkerError('RESOURCE_UNAVAILABLE', '中心未确认 Agent 原知识检索完成')
        return {'code': 0, 'data': result['data']}

    binding = context.execution_context or {}
    span = Span(app_id=binding.get('appId', ''), uid=binding.get('userId', ''),
                chat_id=binding.get('chatId', ''), sid=context.assignment.run_id)
    runner = await build_original_agent(payload, span=span, model_factory=CenterAgentModelFactory(node, options),
        tool_schemas=tools, tool_executor=tool_executor, mcp_servers=servers, mcp_executor=mcp_executor,
        workflow_schemas=workflows, workflow_executor=workflow_executor, knowledge_executor=knowledge_executor,
        app_id=binding.get('appId', ''), uid=binding.get('userId', ''))
    trace = NodeTrace(service_id=context.assignment.version_id, sid=context.assignment.run_id, sub='agent')

    async def stream():
        count, total = 0, 0
        async for chunk in runner.run(span, trace):
            context.assert_active()
            frame = chunk.model_dump(mode='json')
            encoded = json.dumps(frame, ensure_ascii=False, allow_nan=False)
            count += 1
            total += len(encoded.encode('utf-8'))
            if count > 10000 or total > 8 * 1024 * 1024:
                raise WorkerError('QUOTA_EXCEEDED', 'Agent 原算法输出超过固定帧/字节预算')
            yield ('data:' + encoded + '\n').encode('utf-8')
        trace.set_end()
        frame = {'code': 0, 'message': 'success', 'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}],
                 'usage': trace.usage.model_dump()}
        yield ('data:' + json.dumps(frame, ensure_ascii=False) + '\n').encode('utf-8')
    output = stream()
    try:
        return await consume(SimpleNamespace(content=output))
    finally:
        await output.aclose()
