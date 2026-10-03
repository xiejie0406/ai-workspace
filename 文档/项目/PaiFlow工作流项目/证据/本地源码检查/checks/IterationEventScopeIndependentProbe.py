"""冻结后编译实际Policy到私有Temp；不执行原引擎/数据库/Maven，不修改target。"""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[6]
CHECKS=Path(__file__).resolve().parent
POLICY=ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/PureIterationPolicy.java'
LEASE=ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunLeaseService.java'
CODEC=ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowStreamCodec.java'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
parser=argparse.ArgumentParser()
parser.add_argument('--policy-sha',required=True)
parser.add_argument('--lease-sha',required=True)
parser.add_argument('--codec-sha')
args=parser.parse_args()
assert sha(POLICY)==args.policy_sha and sha(LEASE)==args.lease_sha,'未收到的候选冻点拒执行'
if args.codec_sha:assert sha(CODEC)==args.codec_sha,'Codec冻点不符'
helpers=[ROOT/('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/'+p) for p in
    ['service/ModelNodeOptions.java','service/ModelOperationJson.java','domain/NodeCatalog.java']]
source=[POLICY,LEASE,*helpers,
    ROOT/'python/paiflow-runtime/worker/task_runner.py',ROOT/'python/paiflow-runtime/worker/engine_adapter.py',
    ROOT/'contracts/paiflow/fixtures/pure-iteration.json',
    ROOT/'python/paiflow-runtime/verification/pure-iteration-java-assignment-check.json']
if args.codec_sha:source.append(CODEC)
before={str(p.relative_to(ROOT)):sha(p) for p in source}
artifact=ROOT/'python/paiflow-runtime/verification/pure-iteration-java-assignment-original.json'
assert sha(artifact)=='59507f49972d93e8d207c3e746c1caf7acd2b7981793303c7a202d346297e816'
base=json.loads(artifact.read_text(encoding='utf-8'))
cases=[]

def add(name,expected,*,dsl=None,input=None,node='text-joiner::j',tag='iteration::i:0'):
    cases.append({'name':name,'expected':expected,'dsl':copy.deepcopy(dsl if dsl is not None else base['dsl']),
        'input':copy.deepcopy(input if input is not None else base['input']),'nodeId':node,'iterationId':tag})

for count in (0,3,64):
    inputs={'items':['fixture']*count}
    add(f'batch-{count}-main-no-tag',True,input=inputs,node='node-start::s',tag=None)
    add(f'batch-{count}-container-no-tag',True,input=inputs,node='iteration::i',tag=None)
    add(f'batch-{count}-inner-0',count>0,input=inputs)
    add(f'batch-{count}-inner-at-boundary',False,input=inputs,tag=f'iteration::i:{count}')
    if count:
        add(f'batch-{count}-inner-last',True,input=inputs,tag=f'iteration::i:{count-1}')
for name,value in [('false',False),('zero',0),('null',None),('string-array','abc')]:
    add(f'present-{name}-is-not-array',False,input={'items':value})
for required in (False,0,'false','0'):
    dsl=copy.deepcopy(base['dsl']);decl=dsl['nodes'][0]['data']['outputs'][0];decl['required']=required
    add('optional-empty-required-'+repr(required),False,dsl=dsl,input={})
    add('optional-main-required-'+repr(required),True,dsl=dsl,input={},node='node-start::s',tag=None)
    decl['schema']['default']=['D','E','F']
    add('optional-default-required-'+repr(required),True,dsl=dsl,input={},tag='iteration::i:2')
    add('optional-default-boundary-required-'+repr(required),False,dsl=dsl,input={},tag='iteration::i:3')
    add('present-overrides-default-required-'+repr(required),False,dsl=dsl,input={'items':[]})
    add('present-null-not-default-required-'+repr(required),False,dsl=dsl,input={'items':None})
for default in (False,0,None):
    dsl=copy.deepcopy(base['dsl']);decl=dsl['nodes'][0]['data']['outputs'][0];decl['required']=False;decl['schema']['default']=default
    add('nonarray-default-'+repr(default),False,dsl=dsl,input={})
add('missing-required-array',False,input={})
for tag in ['', 'iteration::i:', 'iteration::i:00', 'iteration::i:01', 'iteration::i:-1', 'iteration::i:+1',
            'iteration::i:1.0','iteration::i:1/0','iteration::i:2147483648','iteration::i:99999999999999999999999999',
            'iteration::other:0','iteration::i: 0','iteration::i:0 ','iteration::i:٠','iteration::i:0:0']:
    add('invalid-tag-'+repr(tag),False,tag=tag)
add('missing-inner-tag',False,tag=None)
add('missing-node',False,node='text-joiner::missing')
add('missing-node-with-tag',False,node=None)
add('main-tagged-as-child',False,node='node-start::s')
add('container-tagged-as-child',False,node='iteration::i')
add('inner-start-owner',True,node='iteration-node-start::is')
add('inner-end-owner',True,node='iteration-node-end::ie',tag='iteration::i:2')
# ID含冒号仍按完整owner前缀匹配；不按首冒号split。
dsl=copy.deepcopy(base['dsl']);old='iteration::i';new='iteration::owner:part::tail'
def rename(value):
    if isinstance(value,dict):
        for key,item in value.items():value[key]=new if item==old else rename(item)
    elif isinstance(value,list):
        for i,item in enumerate(value):value[i]=rename(item)
    return value
rename(dsl)
add('colon-owner-exact-prefix',True,dsl=dsl,tag=new+':2')
add('colon-owner-short-prefix',False,dsl=dsl,tag='iteration::owner:2')
add('colon-owner-wrong-prefix',False,dsl=dsl,tag=old+':2')
shared=json.loads((ROOT/'contracts/paiflow/fixtures/pure-iteration.json').read_text(encoding='utf-8'))
for case in shared['cases']:
    add('shared-'+case['name'],case['expectedAdmission'],dsl=case['dsl'],input=case['input'],node='node-start::s',tag=None)

temp=Path(tempfile.mkdtemp(prefix='paiflow-iteration-scope-independent-'))
payload=temp/'cases.json';payload.write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
args_lines=(CHECKS/'ModelOperationSourceAnalysis.args').read_text(encoding='utf-8').splitlines()
cp=args_lines[1].strip('"')
files=[]
for path in [POLICY,*helpers,CHECKS/'IterationEventScopeIndependentProbe.java']:
    target=temp/path.name;target.write_bytes(path.read_bytes());files.append(target)
jdk=Path('C:/Users/admin/AppData/Local/Programs/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin')
started=datetime.now(timezone.utc).isoformat()
compile_result=subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-proc:none','-classpath',cp,'-d',str(temp),
    *map(str,files)],capture_output=True,text=True,encoding='utf-8',timeout=60)
run_result=None
if compile_result.returncode==0:
    run_result=subprocess.run([str(jdk/'java.exe'),'-classpath',str(temp)+';'+cp,'IterationEventScopeIndependentProbe',str(payload)],
        capture_output=True,text=True,encoding='utf-8',timeout=30)
after={str(p.relative_to(ROOT)):sha(p) for p in source}
record={'startedAtUtc':started,'finishedAtUtc':datetime.now(timezone.utc).isoformat(),'privateTemp':str(temp),
    'scope':'实际冻结Policy及原选项helper在私有Temp编译并直接调用；0Engine/DB/HTTP/Provider/Maven/target写，不能证明真实事务授权',
    'sourceBefore':before,'sourceAfter':after,'sourceWindowChanged':[p for p in before if before[p]!=after[p]],
    'savedArtifact':{'path':str(artifact),'sha256':sha(artifact),'originalDeadline':base['deadline'],'notExecutionAuthority':True},
    'probeSourceSha256':sha(CHECKS/'IterationEventScopeIndependentProbe.java'),'cases':cases,
    'compile':{'exitCode':compile_result.returncode,'stdout':compile_result.stdout,'stderr':compile_result.stderr},
    'run':None if run_result is None else {'exitCode':run_result.returncode,'stdout':run_result.stdout,'stderr':run_result.stderr},
    'observed':None if run_result is None or not run_result.stdout.startswith('[') else json.loads(run_result.stdout)}
out=CHECKS/'纯迭代事件归属实际策略独立有限检查.json'
if out.exists():raise RuntimeError('原证据拒覆盖')
out.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'path':str(out),'sha256':sha(out),'cases':len(cases),'compileExit':compile_result.returncode,
    'runExit':None if run_result is None else run_result.returncode,'sourceChanges':record['sourceWindowChanged']},ensure_ascii=False))
if record['sourceWindowChanged'] or compile_result.returncode or run_result is None or run_result.returncode:raise SystemExit(1)
