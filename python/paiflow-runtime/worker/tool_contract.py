"""正式 Link 工具固定资源与操作回执；不授予能力或保存操作真相。"""
import base64
import copy
import json
import re

from .errors import CenterUnavailable, WorkerError
from .events import _validate_payload

RESOURCE_FIELDS = frozenset({'kind', 'provider', 'version', 'enabled', 'pluginId', 'operationId',
    'legacyVersion', 'legacyAppId', 'effectClass', 'openApiSchema'})
BINDING_FIELDS = frozenset({'runId', 'leaseId', 'attempt', 'workflowVersionId', 'dslHash', 'nodeId',
    'iterationId', 'idempotencyKey', 'operationId', 'operation', 'kind', 'provider', 'resourceId',
    'resourceVersion', 'resourceBindings'})
STATES = frozenset({'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EFFECT_UNKNOWN'})
FAILURES = frozenset({'provider', 'authorization', 'protocol', 'transport', 'deadline', 'lease', 'cancelled', 'budget'})
RESOURCE_PATTERN = re.compile(r'tool:([1-9][0-9]*):version:([1-9][0-9]*):operation:([A-Za-z0-9_-]+)')
OPERATION_PATTERN = re.compile(r'tool-op:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}')
METHODS = frozenset({'get', 'post', 'put', 'delete', 'patch', 'head', 'options', 'trace'})


def formal_tool(resource_id):
    return isinstance(resource_id, str) and resource_id.startswith('tool:')


def validate_schema(schema):
    """仅明确 OpenAPI Schema 域识别业务字段/default；其它材料保持凭据守卫。"""
    if not isinstance(schema, dict) or not isinstance(schema.get('paths'), dict) or 'servers' in schema:
        raise WorkerError('DSL_INVALID', '工具公开Schema缺少paths或包含私有服务目标')
    paths = set()
    guarded = copy.deepcopy(schema)
    components = guarded.get('components', {})
    if not isinstance(components, dict):
        raise WorkerError('DSL_INVALID', '工具Schema components无效')
    for name in ('schemas', 'securitySchemes'):
        definitions = components.pop(name, {})
        if not isinstance(definitions, dict):
            raise WorkerError('DSL_INVALID', '工具Schema定义不是对象')
        for label, definition in definitions.items():
            if not isinstance(label, str) or not label or not isinstance(definition, dict):
                raise WorkerError('DSL_INVALID', '工具Schema定义无效')
            if name == 'schemas':
                _validate_payload(definition, schema_paths=frozenset({()}))
            else:
                _validate_payload(definition)
                if definition.get('type') not in {'apiKey', 'none'}:
                    raise WorkerError('TOOL_POLICY_DENIED', '工具公开认证仅保留登记的声明')
                if set(definition) - {'type', 'name', 'in', 'description'}:
                    raise WorkerError('TOOL_POLICY_DENIED', '工具公开认证声明包含私有材料')
    for path, item in schema['paths'].items():
        if not isinstance(path, str) or not isinstance(item, dict):
            raise WorkerError('DSL_INVALID', '工具路径定义无效')
        if 'servers' in item:
            raise WorkerError('TOOL_POLICY_DENIED', '工具公开路径不能携带私有目标')
        for method, operation in item.items():
            if method not in METHODS:
                continue
            if not isinstance(operation, dict):
                raise WorkerError('DSL_INVALID', '工具操作定义无效')
            if 'servers' in operation:
                raise WorkerError('TOOL_POLICY_DENIED', '工具公开操作不能携带私有目标')
            parameters = operation.get('parameters', [])
            if not isinstance(parameters, list):
                raise WorkerError('DSL_INVALID', '工具参数列表无效')
            for index, parameter in enumerate(parameters):
                if isinstance(parameter, dict) and 'schema' in parameter:
                    paths.add(('paths', path, method, 'parameters', index, 'schema'))
            request = operation.get('requestBody', {})
            if isinstance(request, dict):
                content = request.get('content', {})
                if not isinstance(content, dict):
                    raise WorkerError('DSL_INVALID', '工具请求媒体类型无效')
                for media, entry in content.items():
                    if isinstance(entry, dict) and 'schema' in entry:
                        paths.add(('paths', path, method, 'requestBody', 'content', media, 'schema'))
            responses = operation.get('responses', {})
            if not isinstance(responses, dict):
                raise WorkerError('DSL_INVALID', '工具响应定义无效')
            for code, response in responses.items():
                if isinstance(response, dict):
                    content = response.get('content', {})
                    if not isinstance(content, dict):
                        raise WorkerError('DSL_INVALID', '工具响应媒体类型无效')
                    for media, entry in content.items():
                        if isinstance(entry, dict) and 'schema' in entry:
                            paths.add(('paths', path, method, 'responses', code, 'content', media, 'schema'))
    _validate_payload(guarded, schema_paths=frozenset(paths))
    return schema


def check_resource(resource_id, descriptor):
    match = RESOURCE_PATTERN.fullmatch(resource_id) if isinstance(resource_id, str) and len(resource_id) <= 1024 else None
    if not match or not isinstance(descriptor, dict) or set(descriptor) != RESOURCE_FIELDS:
        raise WorkerError('DSL_INVALID', '正式工具资源标识或字段无效')
    if (descriptor['kind'] != 'tool' or descriptor['provider'] != 'spark-link'
            or descriptor['enabled'] is not True or descriptor['version'] != match[2]
            or not isinstance(descriptor['effectClass'], str) or descriptor['effectClass'] not in {'read', 'write'}
            or any(not isinstance(descriptor[k], str) or not descriptor[k] or len(descriptor[k]) > 256
                   for k in ('pluginId', 'legacyVersion', 'legacyAppId'))
            or not isinstance(descriptor['operationId'], str) or not 1 <= len(descriptor['operationId']) <= 128):
        raise WorkerError('DSL_INVALID', '正式工具固定描述无效')
    encoded = base64.urlsafe_b64encode(descriptor['operationId'].encode('utf-8')).decode('ascii').rstrip('=')
    if match[3] != encoded:
        raise WorkerError('VERSION_CONFLICT', '工具资源标识与固定操作不一致')
    validate_schema(descriptor['openApiSchema'])
    selected = [operation for methods in descriptor['openApiSchema']['paths'].values()
        for method, operation in methods.items() if method in METHODS and isinstance(operation, dict)
        and operation.get('operationId') == descriptor['operationId']]
    if len(selected) != 1:
        raise WorkerError('VERSION_CONFLICT', '工具固定Schema必须唯一对应所选operationId')
    ordinary = {k: v for k, v in descriptor.items() if k != 'openApiSchema'}
    _validate_payload(ordinary)
    return descriptor


def validate_assignment(assignment):
    """一次性执行许可前核对本地固定工具快照；最终授权仍由中心决定。"""
    identifiers = {resource_id for resource_id in assignment.resources if formal_tool(resource_id)}
    if not identifiers:
        return
    if assignment.execution_codec != 'paiflow.execution.v4':
        raise WorkerError('ENGINE_UNSUPPORTED', '正式工具只属于已迁入的固定V4节点')
    used = set()
    for node in assignment.dsl['nodes']:
        parameter = node['data'].get('nodeParam', {})
        reference = parameter.get('toolResourceId')
        if not formal_tool(reference):
            continue
        if not node['id'].startswith('plugin::') or node['data'].get('nodeMeta', {}).get('nodeType') != 'plugin':
            raise WorkerError('TOOL_POLICY_DENIED', '正式工具尚未支持该节点/子流程作用域')
        descriptor = check_resource(reference, assignment.resources.get(reference))
        if (parameter.get('pluginId') != descriptor['pluginId'] or parameter.get('operationId') != descriptor['operationId']
                or parameter.get('appId') != descriptor['legacyAppId']
                or parameter.get('version', 'V1.0') != descriptor['legacyVersion']):
            raise WorkerError('VERSION_CONFLICT', '固定工具节点与公开描述不一致')
        operation = 'tools.read' if descriptor['effectClass'] == 'read' else 'tools.invoke'
        if (operation not in assignment.effect_policy['allowedOperations']
                or operation == 'tools.invoke' and assignment.effect_policy['mode'] != 'approved_write'):
            raise WorkerError('TOOL_POLICY_DENIED', '固定工具费用/写入未获运行批准')
        used.add(reference)
    if used != identifiers:
        raise WorkerError('VERSION_CONFLICT', '正式工具固定快照与节点引用不一致')


def check_parameters(parameters, descriptor):
    original = parameters['legacyPayload']
    if (not isinstance(original, dict) or set(original) != {'header', 'parameter', 'payload'}
            or original['header'] != {'app_id': descriptor['legacyAppId']}
            or original['parameter'] != {'tool_id': descriptor['pluginId'], 'version': descriptor['legacyVersion'],
                'operation_id': descriptor['operationId']}
            or not isinstance(original['payload'], dict) or set(original['payload']) != {'message'}
            or not isinstance(original['payload']['message'], dict)
            or set(original['payload']['message']) - {'header', 'query', 'path', 'body'}):
        raise WorkerError('VERSION_CONFLICT', '工具原载荷与固定应用/插件/操作不一致')
    from .task_runner import _unique_object, _deny_constant
    selected = next(operation for methods in descriptor['openApiSchema']['paths'].values()
        for method, operation in methods.items() if method in METHODS and isinstance(operation, dict)
        and operation.get('operationId') == descriptor['operationId'])
    allowed = {location: {p['name'] for p in selected.get('parameters', [])
        if isinstance(p, dict) and p.get('in') == location and isinstance(p.get('name'), str)}
        for location in ('header', 'query', 'path')}
    body_schema = selected.get('requestBody', {}).get('content', {}).get('application/json', {}).get('schema', {})
    def registered_body(value, schema):
        properties = schema.get('properties', {}) if isinstance(schema, dict) else {}
        if not isinstance(properties, dict) or set(value) - set(properties):
            raise WorkerError('VERSION_CONFLICT', '工具body包含未登记字段')
        for name, item in value.items():
            definition = properties[name]
            if isinstance(definition, dict) and definition.get('type') == 'object' and isinstance(item, dict):
                registered_body(item, definition)
    for location, encoded in original['payload']['message'].items():
        if not isinstance(encoded, str) or not encoded:
            raise WorkerError('DSL_INVALID', '工具位置载荷不是原base64字符串')
        try:
            decoded = base64.b64decode(encoded, validate=True)
            value = json.loads(decoded, object_pairs_hook=_unique_object, parse_constant=_deny_constant)
        except (ValueError, UnicodeError):
            raise WorkerError('DSL_INVALID', '工具位置载荷不是原JSON对象') from None
        if not isinstance(value, dict) or not value:
            raise WorkerError('DSL_INVALID', '工具位置载荷须为非空对象')
        if location == 'body':
            registered_body(value, body_schema)
        elif set(value) - allowed[location]:
            raise WorkerError('VERSION_CONFLICT', '工具参数不属于所登记位置/名称')
        _validate_payload(value)
    _validate_payload(parameters)
    return parameters


def check_ack(data, expected, *, cancel=False):
    fields = BINDING_FIELDS | {'state', 'failureKind', 'legacyResponse', 'stopped', 'proofId'}
    if not isinstance(data, dict) or set(data) != fields:
        raise CenterUnavailable()
    for name in BINDING_FIELDS - {'attempt', 'iterationId', 'resourceBindings'}:
        if not isinstance(data[name], str) or not data[name] or len(data[name]) > (
                4096 if name == 'idempotencyKey' else 1024 if name == 'resourceId' else 256):
            raise CenterUnavailable()
    if (type(data['attempt']) is not int or data['attempt'] < 1
            or data['iterationId'] is not None
            or not re.fullmatch(r'[a-f0-9]{64}', data['dslHash'])
            or not OPERATION_PATTERN.fullmatch(data['operationId'])
            or not RESOURCE_PATTERN.fullmatch(data['resourceId'])
            or data['kind'] != 'tool' or data['provider'] != 'spark-link'
            or data['operation'] not in {'tools.read', 'tools.invoke'}
            or not isinstance(data['resourceBindings'], dict)
            or not isinstance(data['state'], str) or data['state'] not in STATES
            or data['failureKind'] is not None and (not isinstance(data['failureKind'], str) or data['failureKind'] not in FAILURES)):
        raise CenterUnavailable()
    from .task_runner import _same_json
    for key, value in expected.items():
        if not _same_json(data.get(key), value):
            raise WorkerError('VERSION_CONFLICT', '工具完整操作归属与固定绑定不一致')
    state, failure, original = data['state'], data['failureKind'], data['legacyResponse']
    if ((state in {'RUNNING', 'COMPLETED'} and failure is not None)
            or state in {'FAILED', 'CANCELLED', 'EFFECT_UNKNOWN'} and failure is None
            or state == 'COMPLETED' and not isinstance(original, dict)
            or state == 'FAILED' and failure == 'provider' and not isinstance(original, dict)
            or state == 'RUNNING' and original is not None
            or state in {'FAILED', 'CANCELLED', 'EFFECT_UNKNOWN'} and failure != 'provider' and original is not None):
        raise CenterUnavailable()
    if (type(data['stopped']) is not bool or data['proofId'] is not None
            and (not isinstance(data['proofId'], str) or not data['proofId'] or len(data['proofId']) > 256)
            or data['stopped'] and (state not in {'COMPLETED', 'FAILED', 'CANCELLED'} or not data['proofId'])
            or not data['stopped'] and data['proofId'] is not None):
        raise CenterUnavailable()
    _validate_payload(data)
    if len(json.dumps(data, ensure_ascii=False, allow_nan=False).encode('utf-8')) > 2 * 1024 * 1024:
        raise CenterUnavailable()
    return data
