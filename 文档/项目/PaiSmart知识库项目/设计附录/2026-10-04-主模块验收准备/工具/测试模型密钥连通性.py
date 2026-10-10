"""本轮已授权的两次合成小样本请求；不注册或激活Provider，不写业务库。"""
import concurrent.futures
import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

WORKSPACE = Path(__file__).resolve().parents[6]
PRIVATE = WORKSPACE / '.runtime/paismart/model-credentials'
EVIDENCE = Path(__file__).resolve().parent.parent / "证据"
CN = datetime.timezone(datetime.timedelta(hours=8))


def stamp():
    return datetime.datetime.now(CN).isoformat()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def redact(value, keys):
    text = str(value)
    for key in keys:
        text = text.replace(key, '[密钥已脱敏]')
    return re.sub(r'sk-[A-Za-z0-9_-]+', '[密钥已脱敏]', text)[:800]


def call(name, item, keys):
    started = time.monotonic()
    result = {'provider': name, 'startedAt': stamp(), 'baseUrl': item['baseUrl'],
              'requestedModel': item['model'], 'keyFingerprint': hashlib.sha256(item['key'].encode()).hexdigest()[:16],
              'expected': 'HTTP200、完整文字输出和用量' if name == 'sub2api' else 'HTTP200、1条有限非零向量和用量',
              'result': '未通过', 'httpStatus': None}
    if name == 'sub2api':
        path = '/responses'
        body = {'model': item['model'], 'input': [{'role': 'user', 'content': '请只输出：连接成功'}],
                'max_output_tokens': 128, 'store': False, 'include': ['reasoning.encrypted_content']}
    else:
        path = '/embeddings'
        body = {'model': item['model'], 'input': ['知识库连通性测试。']}
    result['endpoint'] = item['baseUrl'].rstrip('/') + path
    result['requestBody'] = body
    req = urllib.request.Request(result['endpoint'], data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
                                 headers={'Authorization': 'Bearer ' + item['key'], 'Content-Type': 'application/json'}, method='POST')
    try:
        # 禁止携带Authorization跨域跳转；HTTPS使用系统默认CA校验。
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=30) as response:
            result['httpStatus'] = response.status
            raw = response.read(2 * 1024 * 1024 + 1)
            if len(raw) > 2 * 1024 * 1024:
                raise ValueError('响应超过本轮2MiB上限')
            result['responseSha256'] = hashlib.sha256(raw).hexdigest()
            payload = json.loads(raw)
            result['returnedModel'] = payload.get('model')
            result['usage'] = payload.get('usage')
            result['requestId'] = payload.get('id') or response.headers.get('x-request-id')
            if name == 'sub2api':
                output = payload.get('output', [])
                texts = [part.get('text', '') for item in output if item.get('type') == 'message'
                         for part in item.get('content', []) if part.get('type') == 'output_text']
                result['responseStatus'] = payload.get('status')
                result['outputText'] = redact(''.join(texts), keys)
                result['outputTypes'] = [item.get('type') for item in output]
                result['encryptedReasoningPresent'] = all(bool(item.get('encrypted_content'))
                                                          for item in output if item.get('type') == 'reasoning')
                passed = payload.get('status') == 'completed' and '连接成功' in ''.join(texts) and isinstance(payload.get('usage'), dict)
            else:
                data = payload.get('data', [])
                vectors = [item.get('embedding', []) for item in data]
                finite = bool(vectors) and all(isinstance(v, list) and len(v) > 0 and
                          all(isinstance(n, (int, float)) and not isinstance(n, bool) and math.isfinite(n) for n in v) for v in vectors)
                nonzero = finite and all(any(n != 0 for n in v) for v in vectors)
                result.update({'vectorCount': len(vectors), 'dimensions': [len(v) for v in vectors],
                               'allValuesFinite': finite, 'nonzeroVectors': nonzero,
                               'vectorSha256': hashlib.sha256(json.dumps(vectors, separators=(',', ':')).encode()).hexdigest()})
                passed = len(vectors) == 1 and finite and nonzero and isinstance(payload.get('usage'), dict)
            result['result'] = '通过' if passed else '未通过'
            if not passed:
                result['reason'] = 'HTTP成功，但返回内容未满足本轮独立预期'
    except urllib.error.HTTPError as exc:
        result['httpStatus'] = exc.code
        raw = exc.read(8192)
        result['responseSha256'] = hashlib.sha256(raw).hexdigest()
        result['error'] = redact(raw.decode('utf-8', errors='replace'), keys)
    except Exception as exc:
        result['error'] = redact(type(exc).__name__ + ': ' + str(exc), keys)
    result['finishedAt'] = stamp()
    result['elapsedSeconds'] = round(time.monotonic() - started, 3)
    return result


def main():
    config = json.loads((PRIVATE / '本次模型密钥.json').read_text(encoding='utf-8'))
    keys = [config[n]['key'] for n in ('sub2api', 'aliyun')]
    initial = {'startedAt': stamp(), 'authorization': '2026-10-04用户提供两份密钥并明确要求连通测试',
               'scope': '两次合成小样本直连；不操作原业务库、Provider激活、文档、索引、支付或其他项目',
               'businessAcceptanceExecuted': False, 'results': []}
    record = EVIDENCE / '模型密钥连通测试结果.json'
    if record.exists():
        raise SystemExit('已有测试结果，保留原记录；不自动重复调用')
    record.write_text(json.dumps(initial, ensure_ascii=False, indent=2), encoding='utf-8')
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(call, n, config[n], keys) for n in ('sub2api', 'aliyun')]
        initial['results'] = [f.result() for f in futures]
    initial['finishedAt'] = stamp()
    record.write_text(json.dumps(initial, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(initial, ensure_ascii=False))


if __name__ == '__main__':
    main()
