"""Java及Python同步前后只读HTTP；模型文件就绪与认证保持，重启后SAM未加载，不重新推理。"""
from datetime import UTC, datetime
import argparse
import hashlib
import hmac
import json
from pathlib import Path
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser()
parser.add_argument('phase', choices=['before','after'])
phase = parser.parse_args().phase
root = Path(__file__).resolve().parents[2]
private = root / '.runtime/aipicture'
output = private / f'selection-progress-live-{phase}.json'
assert not output.exists(), '禁止覆盖原HTTP记录'
secret = (private / 'service.secret').read_text(encoding='utf-8-sig').strip()
checks = []
def signed(path, service):
    stamp, nonce = str(int(time.time())), str(uuid.uuid4())
    canonical = '\n'.join(['GET',path,stamp,nonce,hashlib.sha256(b'').hexdigest()])
    return {'X-Aipicture-Service': service, 'X-Aipicture-Timestamp': stamp,
            'X-Aipicture-Nonce': nonce,'X-Aipicture-Signature':hmac.new(secret.encode(),canonical.encode(),hashlib.sha256).hexdigest()}
def fetch(name,url,status_expected,predicate,headers=None):
    start=time.monotonic()
    try:
        with urlopen(Request(url,headers=headers or {},method='GET'),timeout=25) as response:
            status,body=response.status,json.load(response)
    except HTTPError as error:
        status,body=error.code,json.load(error)
    passed=status==status_expected and predicate(body)
    checks.append({'name':name,'status':status,'passed':passed,'response':body,'elapsed_seconds':round(time.monotonic()-start,3)})
    return body
path='/internal/v1/capabilities'
capabilities=fetch('原Python签名能力','http://127.0.0.1:7405'+path,200,
    lambda body:len(body['tools'])==22 and body['capabilities']['sam']['ready'] is True and body['capabilities']['sam']['loaded'] is (phase=='before'),
    signed(path,'aipicture-java'))
if phase=='after':
    before=json.loads((private/'selection-progress-live-before.json').read_text(encoding='utf-8'))
    import copy
    before_normalized=copy.deepcopy(before['capabilities'])
    after_normalized=copy.deepcopy(capabilities)
    expected_loaded_delta=before_normalized['capabilities']['sam'].pop('loaded') is True and after_normalized['capabilities']['sam'].pop('loaded') is False
    checks.append({'name':'模型资源与工具保持、SAM重启后未加载','passed':expected_loaded_delta and before_normalized==after_normalized,'scope':'仅SAM.loaded true到false的预期变化，其余完整能力JSON保持；0新推理'})
    fetch('若依业务仍要求既有登录','http://127.0.0.1:8415/aipicture/sessions',200,lambda body:body.get('code')==401)
    fetch('Python私有入口拒绝无签名','http://127.0.0.1:7405'+path,401,lambda body:bool(body))
    path='/aipicture/internal/v1/execution/assets/'+str(uuid.uuid4())
    query='?run_id='+str(uuid.uuid4())+'&attempt=1&lease_token='+str(uuid.uuid4())
    fetch('原Java接受原签名且未知资产404','http://127.0.0.1:8415'+path+query,404,
          lambda body:body.get('errorCode')=='NOT_FOUND',signed(path,'aipicture-worker'))
passed=all(row['passed'] for row in checks)
record={'checked_at':datetime.now(UTC).isoformat(),'scope':__doc__,'phase':phase,
        'checks':checks,'checks_total':len(checks),'checks_passed':sum(row['passed'] for row in checks),
        'passed':passed,'capabilities':capabilities,'http_requests':1 if phase=='before' else 4,
        'formal_tasks_created':0,'formal_assets_written':0,'database_writes':0,'model_or_provider_calls':0,
        'business_acceptance':False}
with output.open('x',encoding='utf-8') as stream:
    json.dump(record,stream,ensure_ascii=False,indent=2);stream.write('\n')
print(json.dumps({'phase':phase,'passed':passed,'checks':len(checks),'http_requests':record['http_requests'],'model_or_provider_calls':0},ensure_ascii=False))
raise SystemExit(0 if passed else 1)
