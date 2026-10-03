"""真实历史选择反例修复后的受影响检查，保留原101/生产构建时点。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[6]
FRONT=ROOT/'frontend/paiflow-web'
EVIDENCE=Path(__file__).resolve().parent.parent
NODE=r'C:\Program Files\nodejs\node.exe'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    proof=EVIDENCE/'原React调试接续历史选择修复定向检查.json'
    assert not proof.exists()
    original=EVIDENCE/'checks/原React调试接续冻结输入.json'
    gate=json.loads(original.read_text('utf-8'))
    names=[item['path'] for item in gate['files']]
    before={name:sha(ROOT/name) for name in names}
    delta=[item['path'] for item in gate['files'] if before[item['path']]!=item['sha256']]
    assert set(delta)=={'frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts','frontend/paiflow-web/tests/workflow-debug.test.cjs'}
    frozen=EVIDENCE/'checks/原React调试接续历史选择修复冻结输入.json'
    assert not frozen.exists()
    frozen.write_text(json.dumps({'createdAt':datetime.now(timezone.utc).isoformat(),'priorFreeze':{'path':original.relative_to(ROOT).as_posix(),'sha256':sha(original)},'changedPaths':delta,'files':[{'path':name,'sha256':digest} for name,digest in before.items()]},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'freeze':sha(frozen),'changedPaths':delta},ensure_ascii=False),flush=True)
    priorTypes=ROOT/'文档/项目/PaiFlow工作流项目/附录/原前端类型基线比较.json'
    oldTypes=EVIDENCE/'历史选择修复前原类型诊断.json'
    assert not oldTypes.exists();shutil.copy2(priorTypes,oldTypes)
    results=[]
    commands=[('完整条件与调试请求104定向',[NODE,'--test','tests/platform-auth.test.cjs','tests/workflow-revision.test.cjs','tests/workflow-debug.test.cjs']),('历史选择修复原类型增量',[NODE,'tests/compare-reference-types.cjs'])]
    for label,command in commands:
        log=EVIDENCE/('原React历史选择修复-'+label+'.log');assert not log.exists();start=datetime.now(timezone.utc).isoformat()
        with log.open('wb') as stream: process=subprocess.run(command,cwd=FRONT,stdout=stream,stderr=subprocess.STDOUT)
        results.append({'label':label,'command':command,'workdir':str(FRONT),'startedAt':start,'endedAt':datetime.now(timezone.utc).isoformat(),'exitCode':process.returncode,'log':log.relative_to(ROOT).as_posix(),'logSha256':sha(log)})
        print(json.dumps({'label':label,'exitCode':process.returncode},ensure_ascii=False),flush=True)
    types=json.loads(priorTypes.read_text('utf-8'));saved=EVIDENCE/'历史选择修复原类型最终诊断.json';assert not saved.exists();shutil.copy2(priorTypes,saved)
    after={name:sha(ROOT/name) for name in names};changes=[name for name in names if before[name]!=after[name]]
    tests=(EVIDENCE/'原React历史选择修复-完整条件与调试请求104定向.log').read_text('utf-8')
    result={'scope':'只修原historyVersionData.id作用域与对应未知值guard，不改store/卡片UI/import/依赖；不是业务验收','sourceHashesBefore':before,'sourceHashesAfter':after,'sourceWindowChanges':changes,
        'sourceDeltaFromOriginal101':delta,'frozenInput':{'path':frozen.relative_to(ROOT).as_posix(),'sha256':sha(frozen)},'commands':results,'testSummary':tests[tests.rfind('ℹ tests'):],
        'baselineTypeErrors':types['baselineErrors'],'currentTypeErrors':types['currentErrors'],'newTypeErrors':types['newErrors'],'typeDiagnostic':{'path':saved.relative_to(ROOT).as_posix(),'sha256':sha(saved)},
        'strictHelpersCarried':{'path':(EVIDENCE/'原React调试接续前端定向检查.json').relative_to(ROOT).as_posix(),'sha256':sha(EVIDENCE/'原React调试接续前端定向检查.json'),'reason':'两独立严格检查模块未修改'},
        'actualArtifactsConsumerCarried':{'path':(EVIDENCE/'checks/调试接续Java实际响应原消费者最终独立核对.json').relative_to(ROOT).as_posix(),'sha256':sha(EVIDENCE/'checks/调试接续Java实际响应原消费者最终独立核对.json'),'reason':'service/helper/revision和原AST来源未修改'},
        'productionBuildFromEarlierScopeOnly':True,'productionBuildRepeated':False,'browserUsed':False,'network':False,'maven':False,'businessVerified':False}
    result['status']='directed_technical_checks_passed' if all(row['exitCode']==0 for row in results) and not changes and not types['newErrors'] else 'directed_technical_checks_not_passed'
    proof.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'status':result['status'],'sha256':sha(proof),'types':[types['baselineErrors'],types['currentErrors'],len(types['newErrors'])],'windowChanges':changes},ensure_ascii=False),flush=True)
    raise SystemExit(0 if result['status']=='directed_technical_checks_passed' else 1)
if __name__=='__main__':main()
