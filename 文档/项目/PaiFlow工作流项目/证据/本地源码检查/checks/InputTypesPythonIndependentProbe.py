"""原输入 DTO 开放形状与 null 的只读独立探针；不加载业务服务。"""
from pathlib import Path
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[6]
CHECKS = Path(__file__).resolve().parent
material = CHECKS / '工作流原输入类型合同材料.json'
facts = json.loads(material.read_text(encoding='utf-8'))
extra_paths = [
    'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/AgentService.java',
    'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowService.java',
    'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/ResourceAccessService.java',
    'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowJsonBoundary.java',
    'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyBotController.java',
    'ruoyi-backend/ruoyi-paiflow/src/main/resources/mapper/paiflow/AgentMapper.xml',
    'frontend/paiflow-web/src/pages/space-page/agent-page/index.tsx',
    'frontend/paiflow-web/src/utils/http.ts',
    '参考项目/PaiFlow-main/console/backend/hub/src/main/java/com/iflytek/astron/console/hub/util/BotPermissionUtil.java',
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


sources = list(facts['sourceHashesBefore']) + extra_paths
before = {path: sha(ROOT / path) for path in sources}
assert all(before[path] == digest for path, digest in facts['sourceHashesBefore'].items())
for path, digest in facts['execution']['cachedJarHashes'].items():
    assert sha(Path(path)) == digest

cases = [
    {'id': 'hidden-null-open-shapes', 'rawStartOutputs': [{
        'id': 'open-shape', 'name': 'profile', 'required': None, 'futureRoot': True,
        'refId': {'futureRef': True, 'opaqueNull': None},
        'schema': {'type': 'object', 'futureSchema': True, 'items': {'type': 'string'},
            'default': {'futureDefault': True, 'opaqueNull': None},
            'item': {'futureItem': True, 'opaqueNull': None},
            'value': {'type': 'literal', 'content': {'futureContent': True, 'opaqueNull': None},
                'contentErrMsg': None, 'futureValue': True},
            'properties': [{'name': 'leaf', 'type': 'string', 'default': None,
                'allowedFileType': ['pdf'], 'futureProperty': True}]}}]},
    {'id': 'allowed-file-null-first', 'rawStartOutputs': [{
        'id': 'file', 'name': 'picture', 'schema': {'type': 'string'},
        'allowedFileType': [None, 'image'], 'required': False}]},
    {'id': 'file-null-schema', 'rawStartOutputs': [{
        'id': 'file', 'name': 'picture', 'schema': None,
        'allowedFileType': ['image'], 'required': False}]},
    {'id': 'name-as-data-not-authority', 'rawStartOutputs': [
        {'id': str(index), 'name': name, 'schema': {'type': 'string'},
            'allowedFileType': ['pdf'], 'required': True}
        for index, name in enumerate(['type', 'schema', 'name', '__proto__', 'supportUpload'])]},
]
input_path = CHECKS / '工作流原输入类型Python独立探针-input.json'
input_path.write_text(json.dumps(cases, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
command = facts['execution']['javaCommand'][:-1] + [str(input_path)]
result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=30)
log_path = CHECKS / '工作流原输入类型Python独立探针.log'
log_path.write_bytes(result.stdout + result.stderr)
assert result.returncode == 0
observed = json.loads(result.stdout.decode('utf-8'))
assert len(observed['cases']) == len(cases)
assert observed['fullMaasUtilExecuted'] is False and observed['httpSerializerExecuted'] is False
after = {path: sha(ROOT / path) for path in sources}
assert before == after
report = {'scope': 'four additional actual original cached pure DTO/JsonConverter cases only; no business services/HTTP/database',
    'materialSha256': sha(material), 'originalMaterialAssertionsNotRerun': 90,
    'originalClassSourcesUnchangedSinceMaterial': True,
    'command': command, 'exitCode': result.returncode, 'cases': cases, 'observed': observed,
    'sourceHashesBefore': before, 'sourceHashesAfter': after, 'sourceChanges': [],
    'productionWritten': False, 'contractsWritten': False, 'targetWritten': False,
    'network': False, 'services': False, 'realDatabase': False, 'businessVerified': False,
    'httpSerializerExecuted': False, 'logSha256': sha(log_path)}
output = CHECKS / '工作流原输入类型Python独立探针.json'
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'result': 'PASS', 'actualPureCases': len(cases), 'sourceCount': len(before),
    'sourceChanges': 0, 'reportSha256': sha(output), 'observations': observed['cases']}, ensure_ascii=False))
