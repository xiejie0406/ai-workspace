"""只读当前同步纯策略；私有javac，不用Maven/target或正式DB。"""
from pathlib import Path
import argparse,hashlib,json,os,subprocess
from datetime import datetime,timezone

ROOT=Path(__file__).resolve().parents[6]
CHECKS=Path(__file__).resolve().parent
parser=argparse.ArgumentParser();parser.add_argument('--recheck',action='store_true');parser.add_argument('--gate');args=parser.parse_args()
BUILD=CHECKS/('输入类型策略修复独立编译' if args.recheck else '输入类型策略独立编译')
PROD=ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
source=[PROD/(name+'.java') for name in ['WorkflowInputTypesPolicy','WorkflowJsonBoundary','RunJsonBoundary','DslCodec','CanvasExecutionCompiler']]
source += [ROOT/'ruoyi-backend/ruoyi-common/src/main/java/com/ruoyi/common/exception/ServiceException.java',CHECKS/'InputTypesPolicyIndependentProbe.java']
gate=ROOT/(args.gate or '文档/项目/PaiFlow工作流项目/证据/本地源码检查/输入类型同步源码冻结输入.json')
before={p.relative_to(ROOT).as_posix():sha(p) for p in source}
cases=[
 {'id':'reserved-null-schema','rawStartOutputs':[{'name':'AGENT_USER_INPUT','schema':None,'required':True}]},
 {'id':'reserved-empty-file-list','rawStartOutputs':[{'name':'AGENT_USER_INPUT','schema':{'type':'string'},'allowedFileType':[]}]},
 {'id':'reserved-null-first-file-type','rawStartOutputs':[{'name':'AGENT_USER_INPUT','schema':{'type':'string'},'allowedFileType':[None,'pdf']}]},
 {'id':'empty-name-original-nonfile','rawStartOutputs':[{'name':'','schema':{'type':'string'}}]},
 {'id':'open-typed-null','rawStartOutputs':[{'name':'text','schema':{'type':'string','default':{'unknown':7,'null':None},'item':{'unknown':7,'null':None},'value':{'type':'literal','content':{'unknown':7,'null':None}}},'refId':{'unknown':7,'null':None}}]}
]
BUILD.mkdir(exist_ok=True)
inputs=BUILD/'cases.json';inputs.write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
jar=Path('C:/Users/admin/.m2/repository/com/alibaba/fastjson2/fastjson2/2.0.64/fastjson2-2.0.64.jar')
assert jar.is_file(),'只用本地缓存'
compilecmd=['javac','-encoding','UTF-8','-cp',str(jar),'-d',str(BUILD),*[str(p) for p in source]]
c=subprocess.run(compilecmd,cwd=ROOT,capture_output=True)
(BUILD/'javac.log').write_bytes(c.stdout+c.stderr)
assert c.returncode==0,(c.stdout+c.stderr).decode('utf-8',errors='replace')
command=['java','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-cp',os.pathsep.join([str(BUILD),str(jar)]),'InputTypesPolicyIndependentProbe',str(inputs)]
run=subprocess.run(command,cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
(BUILD/'current.log').write_text(run.stdout+run.stderr,encoding='utf-8')
assert run.returncode==0,run.stderr
current=json.loads(run.stdout)
oldBuild=CHECKS/'原输入类型纯转换独立编译'
oldjars=[Path('C:/Users/admin/.m2/repository')/p for p in ['com/alibaba/fastjson2/fastjson2/2.0.51/fastjson2-2.0.51.jar','org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar','org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar','com/fasterxml/jackson/core/jackson-annotations/2.19.2/jackson-annotations-2.19.2.jar']]
assert (oldBuild/'InputTypesSourceReview.class').is_file() and all(p.is_file() for p in oldjars)
oldcmd=['java','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-cp',os.pathsep.join([str(oldBuild),*[str(p) for p in oldjars]]),'InputTypesSourceReview',str(inputs)]
old=subprocess.run(oldcmd,cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
(BUILD/'original-pure.log').write_text(old.stdout+old.stderr,encoding='utf-8')
assert old.returncode==0,old.stderr
original=json.loads(old.stdout)
after={p.relative_to(ROOT).as_posix():sha(p) for p in source}
comparisons=[]
if args.recheck:
    originalById={item['id']:item for item in original['cases']}
    for item in current:
        comparisons.append({'id':item['id'],'accepted':item.get('status')=='accepted','originalTypedFieldsEqual':item.get('fields')==originalById[item['id']]['actualGetInputsInfoData'],'rawUnchanged':item['rawUnchanged']})
        if item['id'].startswith('reserved-'):comparisons[-1]['filteredMetadataEmpty']=item.get('extraInputsConfig')==[] and item.get('extraInputs')=={} and item.get('supportUpload')==0
    assert all(all(v for k,v in row.items() if k!='id') for row in comparisons),comparisons
report={'checkedAt':datetime.now(timezone.utc).isoformat(),'phase':'repair_recheck' if args.recheck else 'first_confirmed_source_compatibility_counterexample','method':'实际当前7个纯source私有javac + 既有原Biz/Hub DTO/JsonConverter私有classes按原2.0.51执行','compileCommand':compilecmd,'compileExitCode':c.returncode,'currentCommand':command,'currentExitCode':run.returncode,'originalCommand':oldcmd,'originalExitCode':old.returncode,'gatePath':str(gate.relative_to(ROOT)),'gateSha256':sha(gate),'sourcesBefore':before,'sourcesAfter':after,'sourceWindowChanges':[p for p in before if before[p]!=after[p]],'cases':cases,'current':current,'originalPure':original,'repairComparisons':comparisons,'fullOriginalMaasExecuted':False,'sourceDerivedMaasFinding':'原MaasUtil548-556先过滤AGENT_USER_INPUT，故三种reserved不进入allowed/schema处理；返回原res的DTO投影保持nullable/empty allowed字段。','currentFinding':'三种reserved修后实际成功；同原typed返回值且metadata空。' if args.recheck else '当前Policy先校验schema/allowed再过滤，对三种reserved返回400。','firstFailureEvidence':'输入类型策略首反例保全/独立反例核对.json','ServiceMapperHttpExecuted':False,'productionModified':False,'MavenExecuted':False,'targetWrites':False,'network':False,'businessVerified':False}
out=CHECKS/('输入类型同步纯策略修复独立复验.json' if args.recheck else '输入类型同步纯策略独立反例核对.json');out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'report':str(out.relative_to(ROOT)),'sha256':sha(out),'observations':current,'sourceWindowChanges':report['sourceWindowChanges']},ensure_ascii=False,indent=2))
