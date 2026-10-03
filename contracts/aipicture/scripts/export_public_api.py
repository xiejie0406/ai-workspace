"""从实际 Java Controller 导出公开路径，避免手写接口清单漂移。"""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[3]
controller = root / 'ruoyi-backend/ruoyi-aipicture/src/main/java/com/ruoyi/aipicture/controller/AiPictureController.java'
source = controller.read_text(encoding='utf-8')
paths = {}
for match in re.finditer(r'@(Get|Post|Patch|Delete)Mapping\(([^)]*)\)(.*?)public\s+([^{}]+)\{', source, re.S):
    method, raw_paths, annotations, signature = match.groups()
    for route in re.findall(r'"([^"\n]+)"', raw_paths):
        path = '/aipicture' + route
        permissions = sorted(set(re.findall(r'aipicture:[a-z]+:[a-z]+', annotations)))
        is_blob = 'ResponseEntity<byte[]>' in signature
        is_table = 'TableDataInfo' in signature
        output = {'type': 'string', 'format': 'binary'} if is_blob else {'$ref': '#/components/schemas/TableDataInfo' if is_table else '#/components/schemas/AjaxResult'}
        operation = {
            'operationId': method.lower() + '_' + re.sub(r'[^a-zA-Z0-9]+', '_', route).strip('_'),
            'summary': re.search(r'@Log\(title="([^"]+)"', annotations).group(1) if '@Log(title=' in annotations else route,
            'security': [{'RuoYiBearer': []}],
            'x-ruoyi-permissions-any': permissions,
            'description': '沿用若依登录与权限；读取、修改和下载均再次核对当前用户的数据归属。工具参数按 tools.schema.json 校验，文档按 document.schema.json 校验。',
            'parameters': [{'name': name, 'in': 'path', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}} for name in re.findall(r'\{([^}]+)\}', path)],
            'responses': {'200': {'description': '实际业务结果；队列任务返回正式 run，由 runs 查询终态。', 'content': {'application/octet-stream' if is_blob else 'application/json': {'schema': output}}}, **{str(code): {'description': description, 'content': {'application/json': {'schema': {'$ref': '#/components/schemas/BusinessError'}}}} for code, description in [(400, '输入无效'), (401, '若依认证失效'), (403, '权限不足'), (404, '资源不存在或不属于当前用户'), (409, '版本、忙碌、幂等键或选区冲突'), (413, '资源超过限制'), (503, '真实运行能力未就绪')]}}
        }
        if is_table:
            operation['parameters'] += [{'name': name, 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': upper, 'default': default}} for name, upper, default in [('pageNum', 100000, 1), ('pageSize', 200, 50)]]
        if route.endswith('/events'):
            operation['parameters'].append({'name': 'after', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 0, 'default': 0}})
        if '@RequestBody' in signature:
            operation['requestBody'] = {'required': True, 'content': {'application/json': {'schema': {'$ref': '#/components/schemas/MutationRequest'}}}}
        if method == 'Post' and route == '/assets':
            operation['requestBody'] = {'required': True, 'content': {'multipart/form-data': {'schema': {'type': 'object', 'required': ['file'], 'properties': {'file': {'type': 'string', 'format': 'binary', 'description': 'JPG/PNG/WebP；≤20MB、≤5000万像素、最短边≥32；服务端按真实内容判断。'}}}}}}
        paths.setdefault(path, {})[method.lower()] = operation
document = {
    'openapi': '3.1.0', 'info': {'title': '若依 AI 图片编辑公开业务接口', 'version': '1', 'description': '路径、方法与权限从实际 Controller 导出。响应保持若依 envelope；详细文档与22工具参数分别引用同目录唯一契约。内部 HMAC 执行通道见 worker.openapi.yaml。'},
    'paths': paths,
    'components': {
        'securitySchemes': {'RuoYiBearer': {'type': 'http', 'scheme': 'bearer', 'description': '若依既有认证；React 经宿主共享 request 发起，不持有令牌。'}},
        'schemas': {
            'AjaxResult': {'type': 'object', 'required': ['code', 'msg'], 'properties': {'code': {'type': 'integer'}, 'msg': {'type': 'string'}, 'data': {}}},
            'TableDataInfo': {'type': 'object', 'required': ['code', 'msg', 'rows', 'total'], 'properties': {'code': {'type': 'integer'}, 'msg': {'type': 'string'}, 'rows': {'type': 'array', 'items': {'type': 'object'}}, 'total': {'type': 'integer'}}},
            'BusinessError': {'type': 'object', 'properties': {'code': {'type': 'integer'}, 'msg': {'type': 'string'}, 'errorCode': {'type': 'string'}, 'retryable': {'type': 'boolean'}, 'currentVersion': {'type': 'integer'}}},
            'MutationRequest': {'type': 'object', 'description': '写入按接口校验；会话采用 expectedVersion CAS；幂等动作附 idempotencyKey，相同键不同内容拒绝。selection 自身 points/strokes/revision 是业务字段。', 'properties': {'schemaVersion': {'const': '1'}, 'toolRegistryVersion': {'const': '2026.10-r2'}, 'idempotencyKey': {'type': 'string', 'minLength': 1, 'maxLength': 128}, 'expectedVersion': {'type': 'integer', 'minimum': 0}, 'tool': {'type': 'string'}, 'params': {'type': 'object'}}, 'additionalProperties': True}
        }
    }
}
target = root / 'contracts/aipicture/v1/aipicture.openapi.yaml'
target.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'公开接口：{len(paths)} 路径，{sum(len(operations) for operations in paths.values())} 操作；契约已从实际 Controller 导出。')
