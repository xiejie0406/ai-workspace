"""同步既有源码索引和前端接入差异；不改设计、记录或生产源。"""
from datetime import datetime,timezone
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent.parent
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def entry(p):return {'path':p.relative_to(ROOT).as_posix(),'sha256':sha(p)}
def main():
    index=ROOT/'文档/项目/PaiFlow工作流项目/源码覆盖索引.md'
    diff=ROOT/'文档/项目/PaiFlow工作流项目/附录/原前端接入差异.json'
    prior=OUT/'checks/调试接续索引更新前原文'
    assert sha(index)==sha(prior/index.name) and sha(diff)==sha(prior/diff.name)
    inv=ROOT/'contracts/paiflow/source-api-inventory.json';audit=ROOT/'文档/项目/PaiFlow工作流项目/附录/接口覆盖核对.json'
    assert sha(inv)=='6fd861415232ff0db7f0a71d76fdd5daa196cb5aca609d9240da59fc20f3103a'
    assert sha(audit)=='b8035711a26d5b4c2ac480357d7012c94ee6825a26eca79adb9577f98cbcff4b'
    check_path=OUT/'调试接续接口索引冻结核对.json';check=read(check_path);snapshot=read(inv)
    intro_path=ROOT/'文档/项目/PaiFlow工作流项目/附录/原前端引入清单.json';intro=read(intro_path)
    current=ROOT/intro['target'];reference=ROOT/intro['source']
    before={e['path']:sha(current/e['path']) for e in intro['files'] if (current/e['path']).is_file()}
    missing=[e['path'] for e in intro['files'] if not (current/e['path']).is_file()]
    reference_changed=[e['path'] for e in intro['files'] if not (reference/e['path']).is_file() or sha(reference/e['path'])!=e['sha256']]
    assert len(intro['files'])==1703 and not missing and not reference_changed
    old=read(diff);reasons={e['path']:e['reason'] for e in old['changes']}
    reasons.update({'src/services/flow.ts':'原build/comparison调用仅新增可选同次buildKey请求头，保留body与原页面调用链','src/components/workflow/store/flow-chat-function.ts':'原消费者保留，局部绑定同次固定receipt/Run/SSE/检查点，断开释放本地视图且保历史只读；不伪造中心terminal或副作用','src/components/workflow/types/drawer/chat-debugger.ts':'仅补原问答中断真实checkpoint revision/state/waiting字段，未修原ChatStoreType全局欠账'})
    changes=[{'path':e['path'],'originalSha256':e['sha256'],'currentSha256':before[e['path']],'reason':reasons[e['path']]} for e in intro['files'] if before[e['path']]!=e['sha256']]
    assert len(changes)==12 and {e['path'] for e in changes}-{e['path'] for e in old['changes']}=={'src/services/flow.ts','src/components/workflow/store/flow-chat-function.ts','src/components/workflow/types/drawer/chat-debugger.ts'}
    gate_path=OUT/'checks/原React调试接续断开视图与只读最终冻结输入.json';gate=read(gate_path)
    assert len(gate['files'])==7 and all(sha(ROOT/e['path'])==e['sha256'] for e in gate['files'])
    revision=[]
    for e in old['revisionTransportFiles']:
        revision.append({**e,'sha256':sha(current/e['path'])})
    added=[*old['added']]
    for p in ('src/utils/workflow-revision.ts','tests/workflow-revision.test.cjs','src/utils/workflow-debug.ts','tests/workflow-debug.test.cjs'):
        assert (current/p).is_file()
        if p not in added:added.append(p)
    old.update({'checkedAt':datetime.now(timezone.utc).isoformat(),'sourceFilesIntroduced':1703,'unchangedOriginalFiles':1691,'changedOriginalFiles':12,'missingOriginalFiles':missing,'referenceChangedSinceIntroduction':reference_changed,'changes':changes,'added':added,'revisionTransportFiles':revision,'debugTransportFiles':[{**e,'kind':'local_minimal_debug_transport_or_test'} for e in gate['files']],'previousSourceImportBaseline':{'path':(prior/diff.name).relative_to(ROOT).as_posix(),'sha256':sha(prior/diff.name),'unchangedOriginalFiles':1694,'changedOriginalFiles':9},'scope':'保留原React/ReactFlow/Ant Design/Recoil/Zustand/Router和页面布局；已授权认证及必要revision/debug传输局部接入，不重写界面，业务迁移未完成','latestTechnicalEvidence':{'path':(OUT/'调试聊天接续本地源码检查.json').relative_to(ROOT).as_posix(),'sha256':sha(OUT/'调试聊天接续本地源码检查.json'),'nodePassed':110,'referenceTypeErrors':3712,'currentTypeErrors':3698,'newTypeErrors':0,'fullTypeCheckPassed':False,'latestSourceProductionBundleBuilt':False,'priorProductionOutputs':242,'businessVerified':False}})
    diff.write_text(json.dumps(old,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    text=index.read_text(encoding='utf-8-sig');marker='## 1. 页面与路由';assert text.count(marker)==1
    paragraph='当前调试接续源码快照：机器清单保全原389条接口、68个接口来源文件、365个服务导出及23处直接请求基线；目标为172条路由、105个Java源、562个前端源/配置与86个Java支持文件。新增build、comparison、chat、resume、cancel只标受限源码及隔离技术证据，业务验证仍为0，模型/资源/迭代原SSE及正式App未接通。原167条路由快照原文保留，原React框架和页面布局继续沿用；当前引入1703个原文件中1691个逐字保留、12个为已授权必要适配。完整字段契约与真实联机不能由本索引推定。详见[机器接口覆盖核对](附录/接口覆盖核对.json)、[本批冻结核对](证据/本地源码检查/调试接续接口索引冻结核对.json)和[原前端实际差异](附录/原前端接入差异.json)。\n\n'
    index.write_text(text.replace(marker,paragraph+marker),encoding='utf-8')
    after={e['path']:sha(current/e['path']) for e in intro['files']};assert before==after
    assert all(sha(ROOT/e['path'])==e['sha256'] for e in gate['files'])
    output={'checkedAt':datetime.now(timezone.utc).isoformat(),'scope':'仅更新既有索引说明和原前端差异；前端原1703文件/7gate未改，原9项基线保全','inventory':entry(inv),'audit':entry(audit),'freezeCheck':entry(check_path),'index':entry(index),'frontendDifference':entry(diff),'introducedManifest':entry(intro_path),'originalFilesIntroduced':1703,'originalByteIdentical':1691,'originalNecessaryAdaptations':12,'missing':missing,'referenceChanged':reference_changed,'sourceWindowChanges':[],'retainedPriorDifference':entry(prior/diff.name),'businessVerified':False,'latestSourceBundleBuilt':False}
    target=OUT/'调试接续源码索引与原前端差异核对.json';assert not target.exists();target.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'proof':entry(target),'index':entry(index),'frontendDifference':entry(diff),'counts':[1703,1691,12],'sourceWindowChanges':[]},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
