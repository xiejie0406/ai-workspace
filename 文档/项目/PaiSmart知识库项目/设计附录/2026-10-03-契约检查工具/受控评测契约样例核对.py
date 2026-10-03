"""核对受控评测的正式OAS30输入/响应样例；只读契约，不调用接口。"""
from pathlib import Path
import yaml
from openapi_schema_validator import OAS30Validator

ROOT = Path(__file__).resolve().parents[5]
doc = yaml.safe_load((ROOT / 'contracts/paismart/knowledge-v1.openapi.yaml').read_text(encoding='utf-8'))
schemas = doc['components']['schemas']

def validator(name):
    return OAS30Validator({'$ref': '#/components/schemas/' + name, 'components': doc['components']})

base = {'label': '独立样本', 'seed': 42, 'topK': 5}
valid = [{**base, 'inlineJsonl': '{"question":"问题"}'},
         {**base, 'datasetId': 'builtin:paismart-sample'},
         {**base, 'datasetId': '123e4567-e89b-12d3-a456-426614174000', 'seed': -9007199254740991}]
invalid = [base, {**valid[0], 'datasetId': 'builtin:paismart-sample'},
           {**base, 'datasetId': 'https://host.invalid/file.jsonl'},
           {**valid[0], 'objectKey': 'paismart/private'},
           {**valid[0], 'seed': 9007199254740992}, {**valid[0], 'seed': 1.5}]
for value in valid:
    validator('EvaluationInput').validate(value)
for value in invalid:
    assert list(validator('EvaluationInput').iter_errors(value)), value

metadata = {'id': 'builtin:paismart-sample', 'label': '原示例', 'source': 'RESOURCE',
            'ownerId': None, 'sha256': 'a' * 64, 'byteSize': 120, 'caseCount': 5}
validator('EvaluationDataset').validate(metadata)
validator('EvaluationDataset').validate({**metadata, 'id': '123e4567-e89b-12d3-a456-426614174000',
                                       'source': 'UPLOAD', 'ownerId': 2, 'createdAt': '2026-10-03T00:00:00Z'})
validator('EvaluationSummary').validate({'samples': 1, 'totalCases': 3, 'processedCases': 1,
                                        'pendingCases': 2, 'labeledCases': 0, 'judgedCases': 0,
                                        'failedCases': 0, 'recallAtK': None, 'mrr': None, 'accuracy': None})
upload = doc['paths']['/paismart/evaluation-datasets']['post']
assert set(upload['requestBody']['content']) == {'multipart/form-data'}
body = upload['requestBody']['content']['multipart/form-data']['schema']
assert body['required'] == ['file'] and set(body['properties']) == {'file', 'label'}
assert any(p['name'] == 'Idempotency-Key' and p['required'] for p in upload['parameters'])
assert not any(p['name'] == 'label' and p['in'] == 'query' for p in upload['parameters'])
print('PASS: OAS30评测输入3有效/6拒绝；受控和本人上传元数据2有效；部分摘要3总/1完成/2待及null指标有效；multipart字段及幂等头正确。')
