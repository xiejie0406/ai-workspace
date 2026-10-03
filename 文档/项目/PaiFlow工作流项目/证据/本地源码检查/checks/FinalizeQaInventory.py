"""固定QA本批接口索引；保全旧167快照，不扩大原业务完成声明。"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import py_compile
import shutil
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[6]
OUT=Path(__file__).resolve().parent.parent
INVENTORY=ROOT/'contracts/paiflow/source-api-inventory.json'
AUDIT=ROOT/'文档/项目/PaiFlow工作流项目/附录/接口覆盖核对.json'
CHECK=OUT/'静态问答接口索引冻结核对.json'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def entry(p):return {'path':p.relative_to(ROOT).as_posix(),'sha256':sha(p)}
def delta(before,after):return [{'path':k,'before':before.get(k),'after':after.get(k)} for k in sorted(before.keys()|after.keys()) if before.get(k)!=after.get(k)]
def preserve(source,target):
    if target.exists():assert sha(source)==sha(target),'不得覆盖已保全原文'
    else:shutil.copyfile(source,target)
    return entry(target)
def run(command,path):
    process=subprocess.run(command,cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
    path.write_text(process.stdout+process.stderr,encoding='utf-8')
    if process.returncode:raise RuntimeError((process.returncode,path))
    return {'command':command,'exitCode':process.returncode,'log':entry(path)}

def main():
    if '--freeze-ready' not in sys.argv:raise RuntimeError('必须收到root唯一QA source freeze才允许生成')
    previous=read(INVENTORY)
    assert sha(INVENTORY)=='e58c8eb905ba64dbe2e46d821be7ea457cea3c72ff114fad33297f4e0ad53e88'
    assert sha(AUDIT)=='b706c052d311a6255095a9cc4133fe40980f1ecfef9e172d00a4f548229d048b'
    assert previous['summary']['targetPaiflowEndpointVariants']==167
    protected=[preserve(INVENTORY,OUT/'静态问答刷新前167接口源码快照.json'),preserve(AUDIT,OUT/'静态问答刷新前167接口覆盖快照.json')]
    historical_check=OUT/'纯迭代重试夹具接口索引冻结核对.json'
    historical_hash=sha(historical_check)
    final_input=OUT/'静态问答规范字节断言-定向复验输入.json'
    manifest=read(final_input)
    before_manifest={p:sha(ROOT/p) for p in manifest['files']}
    assert before_manifest==manifest['files'],'生成前正式196冻结输入须一致'
    proof_path=OUT/'静态问答三次检查用例沿用核对.json'
    proof=read(proof_path)
    assert sha(proof_path)=='7f2f9e2b9286eb2c452987d881c7202dccd2e1db9145f185df14905922628431'
    assert proof['uniqueCurrentTests']==445 and not proof['full445RerunAfterRepairs']
    assert (proof['firstRunCarriedPassed'],proof['secondRunCarriedPassed'],proof['latestRunNewlyExecutedPassed'])==(203,241,1)
    cross_path=ROOT/'python/paiflow-runtime/verification/static-question-answer-java-c5b2718db84c-check.json'
    cross=read(cross_path)
    assert sha(cross_path)=='6fa3ddee8175afb7dc183dd8c9160be05ba59a05fb664f8059e027f900ef8afc'
    assert cross['result']=='PASS' and not cross['sourceChanged'] and cross['taskRunnerCalls']==0 and cross['realCenterVerifyBeginResultEventOperationResourceCalls']==0
    assert cross['artifactSha256']==proof['actualJavaClaimCopy']['sha256']
    cross_inputs={p:sha(Path(p) if Path(p).is_absolute() else ROOT/'python/paiflow-runtime'/p) for p in cross['sourceHashesAfter']}
    assert cross_inputs==cross['sourceHashesAfter']
    generator=run([sys.executable,'-X','utf8','-B','contracts/paiflow/generate-source-api-inventory.py'],OUT/'静态问答接口索引-冻结生成.log')
    checker=run([sys.executable,'-X','utf8','-B','contracts/paiflow/check-source-api-inventory.py','--output',str(CHECK)],OUT/'静态问答接口索引-冻结核对.log')
    current,check=read(INVENTORY),read(CHECK)
    assert check['status']=='source_snapshot_consistent_business_unverified'
    assert current['summary']['targetPaiflowEndpointVariants']==167
    originals=lambda d:{e['id']:{k:v for k,v in e.items() if k not in {'migrationStatus','targetMatches'}} for e in d['originalEndpoints']}
    assert originals(previous)==originals(current)
    assert len(current['originalEndpoints'])==389
    assert current['targetFrontendSnapshotHashes']==previous['targetFrontendSnapshotHashes']
    after_manifest={p:sha(ROOT/p) for p in manifest['files']}
    source_changes=delta(before_manifest,after_manifest)
    assert all(v['path']=='contracts/paiflow/source-api-inventory.json' for v in source_changes)
    assert sha(historical_check)==historical_hash
    syntax=[ROOT/'contracts/paiflow'/name for name in ['generate-source-api-inventory.py','check-source-api-inventory.py']]
    with tempfile.TemporaryDirectory(prefix='paiflow-qa-index-syntax-') as folder:
        for i,path in enumerate(syntax):py_compile.compile(str(path),cfile=str(Path(folder)/f'{i}.pyc'),doraise=True)
    frontend_path=OUT/'静态问答批原前端未修改核对.json'
    projection_path=OUT/'原问答Java实际投影原消费者独立核对.json'
    assert sha(frontend_path)=='e05b79f0145ea308ce2b4920e5ac7a4215b822f561d1bb2e6ecde7a0869dcb2c'
    projection=read(projection_path)
    assert sha(projection_path)=='cc43d6129362acfddc9880dd205c4af0cafcbeabe9999d4e95555f287cd2ac6e'
    assert projection['framesChecked']==8 and not projection['sourceWindowChanges']
    copies=[preserve(INVENTORY,OUT/'静态问答接口源码快照.json'),preserve(AUDIT,OUT/'静态问答接口覆盖快照.json')]
    unconnected=[]
    for endpoint in current['targetPaiflowEndpoints']:
        if any(token in endpoint['path'] for token in ['/workflow/chat','/workflow/resume','/workflow/bot/get-inputs-type']):
            unconnected.append({k:endpoint.get(k) for k in ['method','path','migrationStatus','file','line']})
    check.update({'finalizedAt':datetime.now(timezone.utc).isoformat(),
        'execution':{'generator':generator,'checker':checker,'syntaxInputs':[entry(p) for p in syntax],'syntaxExitCode':0,'syntaxOutput':'TemporaryDirectory only; old manifest pyc preserved','workingDirectory':str(ROOT)},
        'retainedPrevious167':{'beforeCopies':protected,'priorFreeze':entry(historical_check),'priorFreezeModified':False,'javaInputs':len(previous['targetSnapshotHashes']),'frontendInputs':len(previous['targetFrontendSnapshotHashes']),'supportingInputs':len(previous['targetSupportingSnapshotHashes'])},
        'frozenSnapshotCopies':copies,'deltaFromPrevious167':{'java':delta(previous['targetSnapshotHashes'],current['targetSnapshotHashes']),'frontend':[],'supporting':delta(previous['targetSupportingSnapshotHashes'],current['targetSupportingSnapshotHashes']),'originalIdsChanged':0,'originalFactsChanged':0,'targetRoutesChanged':0},
        'latestRegressionInput':{**entry(final_input),'count':manifest['count'],'changesOnlyAuthorizedInventoryGeneration':source_changes,'unexpectedSourceChanges':[],'originalManifestModified':False},
        'technicalEvidence':{'identityReconciliation':entry(proof_path),'uniqueCurrentCases':445,'firstRunCarriedPassed':203,'secondRunCarriedPassed':241,'latestRunNewlyExecutedPassed':1,'full445RerunAfterRepairs':False,'runs':proof['runs'],'rootProjectionAndRunJsonTests':20,'actualJavaFrameConsumerProbe':entry(projection_path),'actualJavaProjectedFrames':projection['preservedArtifact'],'originalFrontend1703Proof':entry(frontend_path),'businessVerified':0},
        'crossLanguageStaticQuestionAnswer':{'report':entry(cross_path),'actualJavaArtifact':proof['actualJavaClaimCopy'],'canonicalSha256':cross['canonicalSha256'],'schemaAccepted':cross['schemaAccepted'],'deadlineUnmodified':cross['deadlineUnmodified'],'budget':cross['budget'],'result':cross['result'],'sourceCount':len(cross_inputs),'sourceWindowChanges':[],'taskRunnerCalls':0,'realCenterCalls':0,'full265Rerun':False,'scope':cross['scope']},
        'inputTypesSourceOnlyMaterial':{**entry(OUT/'checks/工作流原输入类型合同材料.json'),'formalContractsFixtureCreated':False,'fullMaasExecuted':False,'formalInputTypesEndpointImplemented':False},
        'unconnectedLegacyEndpointsRetained':unconnected,'generatorHashes':read(AUDIT)['generatorHashes'],
        'scopeLimits':['公开chat/resume/SSE、原App与inputTypes没有因QA guard/纯投影/来源材料而变为已实现；其503/未迁状态保留。','当前组合445身份通过为203沿用+241沿用+1新复验，不是修后445全模块重跑。','跨语言只原Engine/Factory和内存Checkpoint夹具；未TaskRunner/实际中心HTTP/Provider/正式业务验收。']})
    CHECK.write_text(json.dumps(check,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'check':entry(CHECK),'inventory':entry(INVENTORY),'audit':entry(AUDIT),'original':check['originalBaseline'],'routes':current['summary']['targetPaiflowEndpointVariants'],'counts':check['currentInputCounts'],'summary':current['summary'],'javaDelta':len(check['deltaFromPrevious167']['java']),'supportingDelta':len(check['deltaFromPrevious167']['supporting']),'frontendDelta':0,'sourceFreezeReleased':True},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
