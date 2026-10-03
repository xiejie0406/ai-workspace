"""本地输入类型同步冻结索引；保全QA100/561/77，技术沿用与业务状态分开。"""
from datetime import datetime,timezone
import hashlib,json,py_compile,shutil,subprocess,sys,tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent.parent
INVENTORY=ROOT/'contracts/paiflow/source-api-inventory.json';AUDIT=ROOT/'文档/项目/PaiFlow工作流项目/附录/接口覆盖核对.json'
CHECK=OUT/'输入类型同步接口索引冻结核对.json'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def entry(p):return {'path':p.relative_to(ROOT).as_posix(),'sha256':sha(p)}
def delta(a,b):return [{'path':p,'before':a.get(p),'after':b.get(p)} for p in sorted(a.keys()|b.keys()) if a.get(p)!=b.get(p)]
def preserve(source,target):
    if target.exists():assert sha(source)==sha(target),'不可覆盖既有保全原文'
    else:shutil.copyfile(source,target)
    return entry(target)
def run(command,log):
    p=subprocess.run(command,cwd=ROOT,capture_output=True,text=True,encoding='utf-8');log.write_text(p.stdout+p.stderr,encoding='utf-8')
    if p.returncode:raise RuntimeError((p.returncode,str(log)))
    return {'command':command,'exitCode':p.returncode,'log':entry(log)}
def main():
    assert '--freeze-ready' in sys.argv,'仅Root全部源码冻结后执行一次'
    assert sha(INVENTORY)=='388b7112b0fd424731c5bee372db8f6984a5c3d30d390f983f1c37d059b8ac46'
    assert sha(AUDIT)=='274619e3f50b55a98c208c20ca5846e46284f7ea049e1c14f365aba736d1f7e5'
    previous=read(INVENTORY);assert (len(previous['targetSnapshotHashes']),len(previous['targetFrontendSnapshotHashes']),len(previous['targetSupportingSnapshotHashes']))==(100,561,77)
    protected=[preserve(INVENTORY,OUT/'输入类型刷新前100Java接口源码快照.json'),preserve(AUDIT,OUT/'输入类型刷新前100Java接口覆盖快照.json')]
    prior_check=OUT/'静态问答接口索引冻结核对.json';prior_check_hash=sha(prior_check)
    manifest_path=OUT/'输入类型同步循环夹具清理修复定向检查输入.json';manifest=read(manifest_path)
    manifest_before={p:sha(ROOT/p) for p in manifest['files']};before_diff=delta(manifest['files'],manifest_before)
    assert {d['path'] for d in before_diff}=={'contracts/paiflow/generate-source-api-inventory.py'},before_diff
    assert before_diff[0]['before']=='7f3cb32cff6d1ba4a416d9dbe518d83a5b3c80c89fe81d7068e45f42811dd796'
    proof_path=OUT/'输入类型同步四次检查用例沿用核对.json';proof=read(proof_path)
    assert sha(proof_path)=='a2c8199c5eb5fff4df42e8a7e094c262bf2bb74bd98e87d7da4fdcc768d45cc9'
    assert (proof['inputTypesUniqueTests'],proof['secondRunCarriedPassed'],proof['thirdRunCarriedPassed'],proof['latestRunNewlyExecutedPassed'],proof['currentAllModuleBaselineTests'],proof['priorQaUnaffectedCarriedPassed'])==(68,46,20,2,471,403)
    assert not proof['all68Or471FullyRerunAfterRepairs'] and not proof['sourceWindowChanges'] and len(manifest['files'])==201
    actual_path=ROOT/proof['actualMvcResponseCopy']['path'];assert sha(actual_path)==proof['actualMvcResponseCopy']['sha256']
    artifact=read(actual_path);assert (artifact['response']['status'],artifact['response']['ETag'],artifact['response']['X-PaiFlow-Agent-Id'])==(200,'"2"','1')
    isolated_path=OUT/'checks/输入类型Java实际响应原消费者独立核对.json';isolated=read(isolated_path)
    assert sha(isolated_path)=='48899d1308c6578e3568445d4683884295c73a3402a864769ef685320f9972bb'
    assert isolated['checkCount']==13 and not isolated['failed'] and not isolated['sourceWindowChanges']
    assert all(sha(ROOT/p)==h for p,h in isolated['sourcesAfter'].items())
    generator=run([sys.executable,'-X','utf8','-B','contracts/paiflow/generate-source-api-inventory.py'],OUT/'输入类型同步接口索引-冻结生成.log')
    checker=run([sys.executable,'-X','utf8','-B','contracts/paiflow/check-source-api-inventory.py','--output',str(CHECK)],OUT/'输入类型同步接口索引-冻结核对.log')
    current,check=read(INVENTORY),read(CHECK);assert check['status']=='source_snapshot_consistent_business_unverified'
    originals=lambda d:{e['id']:{k:v for k,v in e.items() if k not in {'migrationStatus','targetMatches'}} for e in d['originalEndpoints']}
    assert originals(previous)==originals(current) and len(current['originalEndpoints'])==389
    assert current['summary']['targetPaiflowEndpointVariants']==previous['summary']['targetPaiflowEndpointVariants']==167
    endpoint=next(e for e in current['targetPaiflowEndpoints'] if e['method']=='POST' and e['path']=='/paiflow/legacy/workflow/bot/get-inputs-type')
    assert endpoint['migrationStatus']=='local_bound_workflow_input_metadata_mapper_mvc_checked_live_runtime_unverified'
    assert current['summary']['businessRuntimeVerifiedOriginalRoutes']==0
    assert sha(prior_check)==prior_check_hash
    final_manifest={p:sha(ROOT/p) for p in manifest['files']};window_diff=delta(manifest_before,final_manifest)
    assert {d['path'] for d in window_diff}=={'contracts/paiflow/source-api-inventory.json'},window_diff
    copies=[preserve(INVENTORY,OUT/'输入类型同步接口源码快照.json'),preserve(AUDIT,OUT/'输入类型同步接口覆盖快照.json')]
    syntax=[ROOT/'contracts/paiflow'/p for p in ['generate-source-api-inventory.py','check-source-api-inventory.py']]
    with tempfile.TemporaryDirectory(prefix='paiflow-input-type-index-syntax-') as folder:
        for i,p in enumerate(syntax):py_compile.compile(str(p),cfile=str(Path(folder)/f'{i}.pyc'),doraise=True)
    check.update({'finalizedAt':datetime.now(timezone.utc).isoformat(),'execution':{'generator':generator,'checker':checker,'syntaxInputs':[entry(p) for p in syntax],'syntaxExitCode':0,'syntaxOutput':'private TemporaryDirectory; no target/manifest pyc writes','workingDirectory':str(ROOT)},
      'retainedPreviousQaSnapshot':{'beforeCopies':protected,'priorFreeze':entry(prior_check),'priorFreezeModified':False,'javaInputs':100,'frontendInputs':561,'supportingInputs':77},'frozenSnapshotCopies':copies,
      'deltaFromPreviousQa':{'java':delta(previous['targetSnapshotHashes'],current['targetSnapshotHashes']),'frontend':delta(previous['targetFrontendSnapshotHashes'],current['targetFrontendSnapshotHashes']),'supporting':delta(previous['targetSupportingSnapshotHashes'],current['targetSupportingSnapshotHashes']),'originalIdsChanged':0,'originalFactsChanged':0,'targetRoutesChanged':0},
      'latestRegressionInput':{**entry(manifest_path),'count':201,'authorisedGeneratorStatusMappingChangeFromFreeze':before_diff,'generationWindowChangesOnlyInventory':window_diff,'unexpectedSourceChanges':[],'originalManifestModified':False},
      'technicalEvidence':{'identityReconciliation':entry(proof_path),'uniqueInputTypesCases':68,'secondRunCarriedPassed':46,'thirdRunCarriedPassed':20,'latestRunNewlyExecutedPassed':2,'priorQaUnaffectedCarriedPassed':403,'currentAllModuleCombination':471,'full68Or471RerunAfterRepairs':False,'runs':proof['runs'],'actualIsolatedMvcResponse':entry(actual_path),'actualRawBodySha256':artifact['response']['rawBodySha256'],'originalConsumerAndTransportReview':entry(isolated_path),'independentActualChecks':13,'purePolicyCounterexample':entry(OUT/'checks/输入类型同步纯策略独立反例核对.json'),'purePolicyRepair':entry(OUT/'checks/输入类型同步纯策略修复独立复验.json'),'businessVerified':0},
      'inputTypesEndpoint':{'method':endpoint['method'],'path':endpoint['path'],'migrationStatus':endpoint['migrationStatus'],'request':'原JSON body.botId，scope仅原space-id头/默认owner域；没有伪造If-Match','response':'原12 nullable DTO数组，保AGENT_USER_INPUT与次序；实际Agent ETag和X-PaiFlow-Agent-Id','sourceAndMetadata':'原Start.outputs typed投影；metadata先过滤固定AGENT，同锁当前WF来源+AgentCAS，幂等不推进版本；复制清来源','fullOriginalMaasExecuted':False,'liveCentre':False,'AppOrFileUploadVerified':False},
      'generatorHashes':read(AUDIT)['generatorHashes'],'scopeLimits':['原389/68来源、365exports和23direct基线保全；AST/static route不代表原全部字段契约闭环。','68身份技术组合=46沿用+20沿用+2新复验，全模块组合471=403沿用+68，不是全量471新跑。','实际MVC来源为隔离H2+Mapper/Service/MockMVC，原AST导航仅spy；无live MySQL/HTTP/页面/完整Maas/Provider/App/上传验收。','公开build/chat/resume/SSE尚未迁，设计拟实现不增加route或业务通过；业务verified保持0。']})
    CHECK.write_text(json.dumps(check,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'check':entry(CHECK),'inventory':entry(INVENTORY),'audit':entry(AUDIT),'original':check['originalBaseline'],'routes':current['summary']['targetPaiflowEndpointVariants'],'counts':check['currentInputCounts'],'summary':current['summary'],'javaDelta':len(check['deltaFromPreviousQa']['java']),'supportingDelta':len(check['deltaFromPreviousQa']['supporting']),'frontendDelta':len(check['deltaFromPreviousQa']['frontend']),'sourceFreezeReleased':True},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
