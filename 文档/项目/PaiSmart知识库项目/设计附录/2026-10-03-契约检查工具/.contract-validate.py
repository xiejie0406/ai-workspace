from pathlib import Path
import json,re,yaml,importlib.util
from jsonschema import Draft202012Validator,Draft7Validator,FormatChecker
DIR=Path(__file__).parent
ROOT=DIR.parents[1]
docs={f.name:(json.loads(f.read_text(encoding='utf-8')) if f.suffix=='.json' else yaml.safe_load(f.read_text(encoding='utf-8'))) for f in DIR.iterdir() if f.name in ['knowledge-v1.openapi.yaml','jobs-v1.asyncapi.yaml','stream-v1.schema.json']}
count=0
def walk(v):
    global count
    if isinstance(v,dict):
        if '$ref' in v:
            r=v['$ref'];fname,pointer=r.split('#',1) if '#' in r else (r,'')
            target=docs[fname] if fname else current
            for part in pointer.lstrip('/').split('/') if pointer else []:
                target=target[part.replace('~1','/').replace('~0','~')]
            count+=1
        for x in v.values():walk(x)
    elif isinstance(v,list):
        for x in v:walk(x)
for n,current in docs.items():walk(current)
oas=docs['knowledge-v1.openapi.yaml']; stream=docs['stream-v1.schema.json']; event=docs['jobs-v1.asyncapi.yaml']
inventory=json.loads((DIR/'.route-inventory.json').read_text(encoding='utf-8'))
source_routes={(r['path'],r['method']) for r in inventory}
spec_routes={(p,m) for p,v in oas['paths'].items() for m in v}
assert source_routes==spec_routes,(source_routes-spec_routes,spec_routes-source_routes)
operation_ids=[]
for path,methods in oas['paths'].items():
    for m,op in methods.items():
        operation_ids.append(op['operationId'])
        assert op['responses'] and op.get('summary')
        declared={p['name'] for p in op.get('parameters',[]) if p['in']=='path'}
        assert declared==set(re.findall(r'\{([^}]+)\}',path)),(path,declared)
        for source in op['x-source'] if isinstance(op['x-source'],list) else [op['x-source']]:
            f,line=source.rsplit(':',1);assert (ROOT/f).exists(),f
assert len(set(operation_ids))==len(operation_ids)
assert oas['paths']['/paismart/ws/chat']['get']['security']==[]
assert oas['paths']['/paismart/internal/ocr/liteparse']['post']['security']==[]
assert oas['paths']['/paismart/recharge/callbacks/wechat']['post']['security']==[]
assert set(oas['paths']['/paismart/uploads/{id}/chunks/{number}']['put']['requestBody']['content'])=={'multipart/form-data','application/octet-stream'}
Draft202012Validator.check_schema(stream)
for v in event['components']['schemas'].values():Draft7Validator.check_schema(v)
uid='123e4567-e89b-12d3-a456-426614174000'
def validator(n):return Draft202012Validator({'$ref':'#/$defs/'+n,'$defs':stream['$defs']},format_checker=FormatChecker())
samples=[('ClientFrame',{'kind':'submit','text':'问题','clientRequestId':'same-request'}),('ClientFrame',{'type':'chat','message':'问题','clientRequestId':'same-request'}),('ClientFrame',{'kind':'resume','generationId':uid,'afterSequence':3}),('ClientFrame',{'type':'stop','generationId':uid}),('ClientFrame',{'kind':'ping'}),('ClientFrame','__chat_ping__'),('ServerFrame',{'kind':'connected','protocolVersion':1}),('ServerFrame',{'kind':'heartbeat'}),('ServerFrame',{'generationId':uid,'conversationId':uid,'messageId':uid,'state':'QUEUED','errorMessage':None,'text':'','references':[],'lastSequence':0})]
for kind,extra in [('tool',{'tool':'search','state':'RUNNING'}),('delta',{'text':'分段'}),('summary',{'text':'摘要'}),('references',{'references':[]}),('done',{'state':'COMPLETE'}),('stopped',{'state':'STOPPED'}),('error',{'state':'INTERRUPTED','errorMessage':'请求已中断'})]:samples.append(('ServerFrame',{'kind':kind,'sequence':1,'generationId':uid,'conversationId':uid,'messageId':uid,**extra}))
for n,s in samples:validator(n).validate(s)
invalid=[('ClientFrame',{'kind':'submit','text':'问题'}),('ClientFrame',{'kind':'resume','generationId':uid,'afterSequence':-1}),('PersistentEvent',{'kind':'done','generationId':uid,'conversationId':uid,'messageId':uid,'state':'COMPLETE'}),('PersistentEvent',{'kind':'state','sequence':1,'generationId':uid,'conversationId':uid,'messageId':uid,'state':'COMPLETE'})]
for n,s in invalid:assert list(validator(n).iter_errors(s)),(n,s)
for n,s in [('JobNotification',{'jobId':uid}),('JobDeadLetter',{'jobId':uid,'reason':'业务失败'}),('MalformedNotificationDeadLetter',{'notificationId':uid,'sourceTopic':'paismart.jobs','partition':0,'offset':42,'payloadHash':'a'*64,'reason':'通知格式无效'})]:Draft7Validator({'$ref':'#/components/schemas/'+n,'components':event['components']},format_checker=FormatChecker()).validate(s)

# 路径静态匹配现有前端API字符串；动态action/kind映射Controller允许值。
checked=[];fail=[]
for f in (ROOT/'frontend/admin-web/src/api/paismart').glob('*.ts'):
    if f.name=='client.ts':continue
    text=f.read_text(encoding='utf-8')
    for match in re.finditer(r'\b(get|list|post|put|remove|call)\s*(?:<[^\n]*?>)?\(\s*([\'"`])',text):
        method={'get':'get','list':'get','post':'post','put':'put','remove':'delete'}.get(match.group(1))
        q=match.group(2);start=match.end();end=text.find(q,start);raw=text[start:end]
        if not raw.startswith('/') and not raw.startswith('${administrative'):continue
        raw=re.sub(r'\$\{pathId\([^)]+\)\}',r'{id}',raw)
        variants=[raw]
        if raw.startswith('${administrative?'):
            tail=raw[raw.index('}')+1:];variants=[tail,'/operations'+tail]
        out=[]
        for v in variants:
            if '${action}' in v:
                values=['cancel','retry'] if '/operations/jobs/' in v else ['archive','unarchive','switch'] if '/conversations/' in v else ['parse','reindex','restore','retry']
                out.extend(v.replace('${action}',a) for a in values)
            else:out.append(v)
        for v in out:
            v='/paismart'+v
            possible=[p for p in oas['paths'] if re.fullmatch(re.sub(r'\{[^}]+\}',r'[^/]+',p),v)]
            if method is None:
                # call(...) GET文件或显式配置method；只核对真实路径，方法由config声明。
                ok=bool(possible)
            else:ok=any(method in oas['paths'][p] for p in possible)
            (checked if ok else fail).append((f.name,method,v))
assert not fail,fail
for f in docs:
    for source in docs[f].get('x-source',[]):assert (ROOT/source).exists(),source
for link in re.findall(r'\]\(([^)]+)\)',(DIR/'README.md').read_text(encoding='utf-8')):
    assert (DIR/link.split('#')[0]).exists(),link
try:
    from openapi_spec_validator import validate
    validate(oas); formal='OpenAPI spec validator passed'
except ImportError:formal='OpenAPI结构/引用检查，环境未安装完整OpenAPI解析器'
print(f'PASS: {len(spec_routes)} HTTP operations = current Controller routes; {count} references resolved; {len(checked)} frontend path variants matched')
print(f'PASS: JSON Schema 2020-12 + {len(event["components"]["schemas"])} AsyncAPI payload schemas; {len(samples)} valid + {len(invalid)} invalid WS samples; 3 Kafka samples')
print('PASS: source locations and README links; '+formal)
