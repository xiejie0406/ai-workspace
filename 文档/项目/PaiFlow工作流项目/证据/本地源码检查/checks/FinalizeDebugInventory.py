"""本批调试接续唯一接口快照刷新；保全旧167/102/561/79，不触碰业务源/target。"""
from datetime import datetime,timezone
import hashlib,json,py_compile,shutil,subprocess,sys,tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent.parent
INV=ROOT/'contracts/paiflow/source-api-inventory.json';AUD=ROOT/'文档/项目/PaiFlow工作流项目/附录/接口覆盖核对.json'
CHECK=OUT/'调试接续接口索引冻结核对.json';PREP=OUT/'checks/调试接续索引更新前原文/输入.json'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def entry(p):return {'path':p.relative_to(ROOT).as_posix(),'sha256':sha(p)}
def delta(a,b):return [{'path':p,'before':a.get(p),'after':b.get(p)} for p in sorted(a.keys()|b.keys()) if a.get(p)!=b.get(p)]
def preserve(p,q):
    q.parent.mkdir(parents=True,exist_ok=True)
    if q.exists():assert sha(p)==sha(q),'不得覆盖既有原文'
    else:shutil.copyfile(p,q)
    return entry(q)
def run(cmd,log):
    p=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,encoding='utf-8');log.write_text(p.stdout+p.stderr,encoding='utf-8')
    assert p.returncode==0,(p.returncode,str(log))
    return {'command':cmd,'exitCode':p.returncode,'log':entry(log)}
def prepare():
    assert not PREP.exists(),'prepare仅一次'
    assert sha(INV)=='ba8e4784561d24f929897dce444d75a5d674bc51c6a08693592803e1a439a0d0'
    assert sha(AUD)=='c4ab8875a9e9c6b5053e185b8015f9353a307c34e847934dc815aaf04a36bf93'
    old=read(INV);assert (len(old['targetSnapshotHashes']),len(old['targetFrontendSnapshotHashes']),len(old['targetSupportingSnapshotHashes']))==(102,561,79)
    paths=[INV,AUD,ROOT/'contracts/paiflow/generate-source-api-inventory.py',ROOT/'文档/项目/PaiFlow工作流项目/源码覆盖索引.md',ROOT/'文档/项目/PaiFlow工作流项目/附录/原前端接入差异.json',OUT/'输入类型同步接口索引冻结核对.json']
    copies=[preserve(p,PREP.parent/p.name) for p in paths]
    full=OUT/'调试聊天接续完整模块首次检查输入.json';files=read(full)['files'];current={p:sha(ROOT/p) for p in files}
    output={'preparedAt':datetime.now(timezone.utc).isoformat(),'scope':'原167routes/102Java/561frontend/79support原文保全；此阶段仅source索引刷新','copies':copies,'originalSnapshotCounts':{'routes':167,'java':102,'frontend':561,'supporting':79},'fullFirstInput':entry(full),'filesAtPrepare':current,'first519InputChangesBeforeIndex':delta(files,current),'businessVerified':False}
    PREP.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(entry(PREP),ensure_ascii=False))
def finalize():
    assert '--freeze-ready' in sys.argv
    prep=read(PREP);previous=read(PREP.parent/'source-api-inventory.json')
    resume='--resume-after-line-identity-review' in sys.argv
    if not resume:assert sha(INV)==sha(PREP.parent/INV.name) and sha(AUD)==sha(PREP.parent/AUD.name),'生成前原机器事实未变'
    proof_path=OUT/'调试聊天接续本地源码检查.json';proof=read(proof_path)
    assert sha(proof_path)=='62e1e8bc6e9cbcde21083205a96415f11c31eeaaa8dc93aa9d909318a8aab1c1'
    assert (proof['java']['fullFirstSnapshotTests'],proof['java']['currentUniqueTestBaseline'])==(519,520)
    assert proof['java']['notClaimedFull520Rerun'] and proof['java']['sourceWindowChanges']==[]
    assert (proof['frontend']['nodePassed'],proof['frontend']['referenceTypeErrors'],proof['frontend']['currentTypeErrors'],proof['frontend']['newTypeErrors'])==(110,3712,3698,0)
    assert not proof['frontend']['fullProjectTypeCheckPassed'] and not proof['productionBuild']['currentSourceBundleBuilt']
    gate_path=OUT/'checks/原React调试接续断开视图与只读最终冻结输入.json';gate=read(gate_path)
    assert sha(gate_path)=='f6805018f50578eb25c47d4a8a689217b7328f9fe1e4819a4308d37d18dce397'
    assert len(gate['files'])==7 and all(sha(ROOT/e['path'])==e['sha256'] for e in gate['files'])
    window_before={p:sha(ROOT/p) for p in prep['filesAtPrepare']}
    expected_changed={'contracts/paiflow/generate-source-api-inventory.py'}|({'contracts/paiflow/source-api-inventory.json'} if resume else set())
    assert {v['path'] for v in delta(prep['filesAtPrepare'],window_before)}==expected_changed
    gen_cmd=[sys.executable,'-X','utf8','-B','contracts/paiflow/generate-source-api-inventory.py'];check_cmd=[sys.executable,'-X','utf8','-B','contracts/paiflow/check-source-api-inventory.py','--output',str(CHECK)]
    if resume:
        # 源码ID含行号，首收尾断言误把六条行移算新增。生成/核对日志已成功，
        # 此处只恢复统计收尾，不重复生成或覆写原日志。
        checker_result=json.loads((OUT/'调试接续接口索引-冻结核对.log').read_text(encoding='utf-8'))
        assert checker_result['status']=='source_snapshot_consistent_business_unverified'
        assert sha(INV)==checker_result['inventorySha256'] and sha(AUD)==checker_result['auditSha256']
        generator={'command':gen_cmd,'exitCode':0,'log':entry(OUT/'调试接续接口索引-冻结生成.log'),'carriedExecutedLog':True}
        checker={'command':check_cmd,'exitCode':0,'log':entry(OUT/'调试接续接口索引-冻结核对.log'),'carriedExecutedLog':True}
    else:
        generator=run(gen_cmd,OUT/'调试接续接口索引-冻结生成.log')
        checker=run(check_cmd,OUT/'调试接续接口索引-冻结核对.log')
    current,check=read(INV),read(CHECK)
    assert check['status']=='source_snapshot_consistent_business_unverified'
    originals=lambda d:{e['id']:{k:v for k,v in e.items() if k not in {'migrationStatus','targetMatches'}} for e in d['originalEndpoints']}
    assert originals(previous)==originals(current) and len(current['originalEndpoints'])==389
    assert current['summary']['businessRuntimeVerifiedOriginalRoutes']==0
    assert current['summary']['frontendServiceExports']==365 and current['summary']['baseline23DirectCalls']==23
    old_pairs={(e['method'],e['path']) for e in previous['targetPaiflowEndpoints']}
    added=[e for e in current['targetPaiflowEndpoints'] if (e['method'],e['path']) not in old_pairs]
    removed=[e for e in previous['targetPaiflowEndpoints'] if (e['method'],e['path']) not in {(v['method'],v['path']) for v in current['targetPaiflowEndpoints']}]
    moved=[{'method':e['method'],'path':e['path'],'beforeId':next(v['id'] for v in previous['targetPaiflowEndpoints'] if (v['method'],v['path'])==(e['method'],e['path'])),'afterId':e['id']} for e in current['targetPaiflowEndpoints'] if (e['method'],e['path']) in old_pairs and e['id']!=next(v['id'] for v in previous['targetPaiflowEndpoints'] if (v['method'],v['path'])==(e['method'],e['path']))]
    paths={'/paiflow/legacy/workflow/build','/paiflow/legacy/workflow/add-comparisons','/paiflow/legacy/workflow/chat','/paiflow/legacy/workflow/resume','/paiflow/legacy/workflow/cancel'}
    assert {(e['method'],e['path']) for e in added}=={('POST',p) for p in paths}
    assert removed==[] and len(moved)==6
    assert all('checked' in e['migrationStatus'] and 'unverified' in e['migrationStatus'] for e in added)
    window_after={p:sha(ROOT/p) for p in window_before};window_changes=delta(window_before,window_after)
    assert {v['path'] for v in window_changes}==(set() if resume else {'contracts/paiflow/source-api-inventory.json'})
    assert all(sha(ROOT/e['path'])==e['sha256'] for e in gate['files'])
    copies=[preserve(INV,OUT/'调试接续接口源码快照.json'),preserve(AUD,OUT/'调试接续接口覆盖快照.json')]
    syntax=[ROOT/'contracts/paiflow'/p for p in ('generate-source-api-inventory.py','check-source-api-inventory.py')]
    with tempfile.TemporaryDirectory(prefix='paiflow-debug-index-syntax-') as folder:
        for i,p in enumerate(syntax):py_compile.compile(str(p),cfile=str(Path(folder)/f'{i}.pyc'),doraise=True)
    check.update({'finalizedAt':datetime.now(timezone.utc).isoformat(),'execution':{'generator':generator,'checker':checker,'syntaxInputs':[entry(p) for p in syntax],'syntaxExitCode':0,'syntaxOutput':'private TemporaryDirectory; no target or manifest pyc writes','workingDirectory':str(ROOT)},'retainedPreviousInputTypesSnapshot':{'preparation':entry(PREP),'priorFreeze':entry(PREP.parent/'输入类型同步接口索引冻结核对.json'),'javaInputs':102,'frontendInputs':561,'supportingInputs':79,'routes':167},'frozenSnapshotCopies':copies,'deltaFromPreviousInputTypes':{'java':delta(previous['targetSnapshotHashes'],current['targetSnapshotHashes']),'frontend':delta(previous['targetFrontendSnapshotHashes'],current['targetFrontendSnapshotHashes']),'supporting':delta(previous['targetSupportingSnapshotHashes'],current['targetSupportingSnapshotHashes']),'newRoutes':added,'removedRoutes':removed,'movedSourceIds':moved,'originalIdsChanged':0,'originalFactsChanged':0},'indexGenerationWindow':{'count':len(window_before),'finalizationWindowChanges':window_changes,'generationWindowChanges':current['summary']['targetFilesChangedDuringParse'],'unexpectedSourceChanges':[],'singleGeneratorExecution':True,'lineIdentityAssertionRepair':resume},'technicalEvidence':{'singleTechnicalSummary':entry(proof_path),'java':proof['java'],'frontend':proof['frontend'],'productionBuild':proof['productionBuild'],'finalFrontendGate':entry(gate_path),'businessVerified':0},'scopeLimits':['原389/68来源、365exports和23direct基线保全；AST/static method/path不是全字段闭环。','5条新增debug路由仅受限源码+随机H2/Mapper/MVC技术证据；无真实MySQL/Redis/JWT/HTTP/Worker/页面业务验收。','当前Java组合520=509沿用+10调度修复+1夹具断言复验；本轮未全量重跑520。','原React当前110Node/两helper严格类型沿用；原类型3712→3698新增0，整个前端类型未通过。','旧242bundle对应47e649修复前源码，最新scope/view源未重新打包或部署。','SSE仅纯顶层与静态QA；模型/资源/纯迭代原SSE、正式App及发布仍未接通，未知不能标完成。','本索引目标快照不覆盖python/paiflow-runtime本轮TaskRunner变量许可前窄修；该新runtime preflight只由自身真实冻结证据说明。']})
    CHECK.write_text(json.dumps(check,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'check':entry(CHECK),'inventory':entry(INV),'audit':entry(AUD),'counts':check['currentInputCounts'],'summary':current['summary'],'newRoutes':[{k:e[k] for k in ('method','path','migrationStatus')} for e in added],'deltas':{k:len(check['deltaFromPreviousInputTypes'][k]) for k in ('java','frontend','supporting')},'sourceFreezeReleased':True},ensure_ascii=False,indent=2))
if __name__=='__main__':
    if '--prepare' in sys.argv:prepare()
    else:finalize()
