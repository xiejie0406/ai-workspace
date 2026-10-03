"""只读现有编译classes，核对旧null字段小预算异常包装与最终物理预算。"""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[6]
CHECKS=Path(__file__).resolve().parent
OUT=CHECKS.parent
OLD=CHECKS/'静态问答实施前快照'
REL='ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/'
FILES=[ROOT/(REL+'WorkflowJsonBoundary.java'), ROOT/(REL+'RunJsonBoundary.java'),
       ROOT/'ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/WorkflowPersistenceTest.java',
       ROOT/'ruoyi-backend/ruoyi-paiflow/target/classes/com/ruoyi/paiflow/service/WorkflowJsonBoundary.class',
       ROOT/'ruoyi-backend/ruoyi-paiflow/target/classes/com/ruoyi/paiflow/service/RunJsonBoundary.class',
       OLD/(REL+'WorkflowJsonBoundary.java'),OUT/'静态问答与运行JSON-联合回归输入.json']
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    before={p.relative_to(ROOT).as_posix():sha(p) for p in FILES}
    old=(OLD/(REL+'WorkflowJsonBoundary.java')).read_text(encoding='utf-8')
    assert 'RunJsonBoundary.check(Collections.singletonMap(key,marker),4096)' in old
    frozen=json.loads((OUT/'静态问答与运行JSON-联合回归输入.json').read_text(encoding='utf-8'))
    assert frozen['files'][REL+'WorkflowJsonBoundary.java']==sha(OLD/(REL+'WorkflowJsonBoundary.java'))
    cp=os.pathsep.join(str(p) for p in [ROOT/'ruoyi-backend/ruoyi-paiflow/target/classes',ROOT/'ruoyi-backend/ruoyi-common/target/classes',Path('C:/Users/admin/.m2/repository/com/alibaba/fastjson2/fastjson2/2.0.64/fastjson2-2.0.64.jar')])
    command=['java','--class-path',cp,str(CHECKS/'WorkflowNullBudgetReview.java')]
    done=subprocess.run(command,cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
    if done.returncode: raise RuntimeError(done.stderr)
    result=json.loads(done.stdout)
    assert result['actualTinyBudgetCode']==413 and result['oldSourceCatchWouldWrapAsCode']==400
    assert result['currentBoundaryFinalMergedCode']==413
    after={p.relative_to(ROOT).as_posix():sha(p) for p in FILES}
    assert before==after
    evidence={'checkedAt':datetime.now(timezone.utc).isoformat(),'method':'actual current compiled RunJsonBoundary.check and WorkflowJsonBoundary.check; old source catch mapping is explicitly simulated, not an old full Service rerun',
        'command':command,'workingDirectory':str(ROOT),'exitCode':done.returncode,'observations':result,
        'sourceHashesBefore':before,'sourceHashesAfter':after,'sourceWindowChanges':[],
        'cause':'445首败的WorkflowJsonBoundary在逐key秘密字段检查复用4096预算；RunJsonBoundary本批保null后，大null字段名先413，被旧catch(ServiceException)包装为400，未走到merged16MiB预算。',
        'currentFixObserved':'当前WorkflowJsonBoundary改为Integer.MAX_VALUE仅复用秘密字段规则；同null大包最终物理预算真实413。',
        'noMaven':True,'targetWritten':False,'productionSourceWritten':False,'realDatabaseWrites':False,'network':False,'businessVerified':False,
        'limitations':['未重跑旧WorkflowPersistenceTest或完整445；旧异常包装由已保全源码和实际tiny check输出共同定位。','当前磁盘class的编译轮次不由本探针认定；Maven归属必须用root正式执行startedAt/end与编译输入SHA核对，不能视为首445类或集中JUnit通过。','Java source launcher只为证据探针临时编译，不写target；当前实际Mapper回滚仍交集中JUnit复验。']}
    path=OUT/'工作流null字段物理预算异常来源独立核对.json'
    path.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'path':path.relative_to(ROOT).as_posix(),'sha256':sha(path),'observations':result,'sourceWindowChanges':0},ensure_ascii=False,indent=2))
if __name__=='__main__': main()
