"""实际 HTTP / HMAC / 纯计算技术检查；不登录用户、不调用模型、不写正式素材。"""
import base64
import hashlib
import hmac
import io
import json
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from PIL import Image

root = Path(__file__).resolve().parents[2]
secret = (root / '.runtime/aipicture/service.secret').read_text(encoding='utf-8-sig').strip()
results = []

def call(base, path, method='GET', payload=None, service=None):
    body = b'' if payload is None else json.dumps(payload, separators=(',', ':')).encode()
    headers = {'Content-Type': 'application/json'}
    if service:
        timestamp, nonce = str(int(time.time())), str(uuid.uuid4())
        canonical = '\n'.join([method, path.split('?')[0], timestamp, nonce, hashlib.sha256(body).hexdigest()])
        headers.update({'X-Aipicture-Service': service, 'X-Aipicture-Timestamp': timestamp, 'X-Aipicture-Nonce': nonce, 'X-Aipicture-Signature': hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()})
    try:
        response = urlopen(Request(base + path, data=body if method != 'GET' else None, method=method, headers=headers), timeout=25)
    except HTTPError as error:
        response = error
    raw = response.read()
    return response.status, json.loads(raw) if raw else {}

worker = 'http://127.0.0.1:7405'
center = 'http://127.0.0.1:8415'
status, _ = call(worker, '/internal/v1/capabilities')
assert status == 401, '私有能力接口必须拒绝未签名请求'
results.append({'name': '计算接口拒绝未签名请求', 'passed': True})
status, capability = call(worker, '/internal/v1/capabilities', 'POST', {}, 'aipicture-java')
assert status == 200 and len(capability['tools']) == 22
results.append({'name': '实际 HMAC 能力读取', 'passed': True, 'registeredTools': 22, 'capabilities': capability['capabilities']})
status, response = call(center, '/aipicture/assets')
assert status == 401 or response.get('code') == 401, '公开素材接口必须要求若依认证'
results.append({'name': '正式素材接口要求若依认证', 'passed': True})
run_id, asset_id, lease = map(str, [uuid.uuid4(), uuid.uuid4(), uuid.uuid4()])
path = f'/aipicture/internal/v1/execution/assets/{asset_id}?run_id={run_id}&attempt=1&lease_token={lease}'
status, _ = call(center, path)
assert status == 401, '内部资产读取必须要求服务签名'
status, response = call(center, path, service='aipicture-worker')
assert status in (404, 409) and response.get('errorCode'), f'签名素材边界响应不符预期：{status} {response}'
results.append({'name': '中心 HMAC 与租约/素材边界', 'passed': True, 'rejectedStatus': status, 'errorCode': response['errorCode']})
stream = io.BytesIO()
Image.new('RGBA', (32, 32), (80, 120, 160, 255)).save(stream, 'PNG')
image = stream.getvalue()
document = {'width': 32, 'height': 32, 'layers': [{'id': str(uuid.uuid4()), 'kind': 'image', 'name': 'HTTP 技术素材', 'width': 32, 'height': 32, 'asset_id': asset_id, 'transform': {'x': 0, 'y': 0, 'scale_x': 1, 'scale_y': 1, 'rotation': 0}, 'opacity': 1, 'visible': True, 'locked': False}]}
status, response = call(worker, '/internal/v1/compute', 'POST', {'schema_version': '1', 'tool_registry_version': '2026.10-r2', 'run_id': run_id, 'attempt': 1, 'tool': 'adjust_image', 'params': {'brightness': 0.1}, 'document': document, 'input_version': 0, 'input_revision': 0, 'assets': [{'id': asset_id, 'sha256': hashlib.sha256(image).hexdigest(), 'width': 32, 'height': 32, 'format': 'PNG', 'content_base64': base64.b64encode(image).decode()}]}, 'aipicture-java')
assert status == 200 and response.get('artifacts'), '实际像素计算 HTTP 未成功'
artifact = response['artifacts'][0]
output = base64.b64decode(artifact['content_base64'])
assert hashlib.sha256(output).hexdigest() == artifact['sha256']
assert Image.open(io.BytesIO(output)).size == (32, 32)
assert output != image
results.append({'name': '实际 HTTP 确定性调色与产物指纹', 'passed': True, 'format': artifact['format'], 'size': [32, 32]})
target = root / '.runtime/aipicture/http-check.json'
target.write_text(json.dumps({'scope': '实际 HTTP 技术边界；无真实模型或用户业务验收', 'checks': results}, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'实际 HTTP 技术检查 {len(results)}/{len(results)} 通过；模型可用性如实记录。')
