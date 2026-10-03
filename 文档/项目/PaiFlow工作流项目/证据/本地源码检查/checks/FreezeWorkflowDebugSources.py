from pathlib import Path
import hashlib, json, datetime
root=Path('ruoyi-backend/ruoyi-paiflow')
names=['src/main/java/com/ruoyi/paiflow/domain/WorkflowVersionRecord.java','src/main/java/com/ruoyi/paiflow/mapper/WorkflowMapper.java','src/main/resources/mapper/paiflow/WorkflowMapper.xml','src/main/java/com/ruoyi/paiflow/service/WorkflowService.java','src/main/java/com/ruoyi/paiflow/service/AgentService.java','src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowScopeFacade.java','src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowController.java','sql/018-paiflow-workflow-debug-snapshot.sql']
names+=['src/test/java/com/ruoyi/paiflow/service/'+n+'.java' for n in ['WorkflowDebugTestFixture','WorkflowDebugPersistenceTest','LegacyDebugBuildContractTest','AgentPersistenceTest','WorkflowDefaultScopePersistenceTest','WorkflowPersistenceTest','RuntimeModelCompilationTest','PureIterationRunPersistenceTest','StaticQuestionAnswerTestFixture']]
inputs=[{'path':(root/n).as_posix(),'sha256':hashlib.sha256((root/n).read_bytes()).hexdigest()} for n in names]
record={'schemaVersion':'paiflow.local.freeze.v1','generatedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'local source candidate; no Maven/target/real DB/Provider/Git; SQL018 unexecuted','inputs':inputs,'expectedNewTests':{'WorkflowDebugPersistenceTest':16,'LegacyDebugBuildContractTest':6,'WorkflowDebugTestFixture':0},'priorBytes':'checks/调试构建实施前快照/输入.json','checks':{'mapperXML':'well formed only; not a business check','javaExecuted':False},'ownership':'root owns Run/Checkpoint/stream/front, tests run exclusively by root'}
out=Path('文档/项目/PaiFlow工作流项目/证据/本地源码检查/调试构建元数据同步断言修正冻结输入.json')
record['priorCandidateGate']='调试构建末权限复查冻结输入.json'
record['priorCandidateSourceSnapshot']='checks/调试构建同步断言修正前快照'
record['localChange']='仅普通构建新test预期区分真实inputExample同步与unknown/null保全；无生产变化/Java检查执行'
out.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('inputs',len(inputs),'gate',hashlib.sha256(out.read_bytes()).hexdigest())
for item in inputs[:8]:print(item['path'],item['sha256'])
