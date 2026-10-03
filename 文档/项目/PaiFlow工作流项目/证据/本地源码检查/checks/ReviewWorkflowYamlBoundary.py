"""源码外独立核对：真实现有边界private Temp javac，无Service/DB/HTTP/Maven/target。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib, json, os, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[6]
CHECKS = Path(__file__).resolve().parent
PROD = ROOT / 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service'
REF = ROOT / '参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

cases = [
    ('credential-apikey', {'nodeParam': {'apiKey': 'SYNTHETIC_PLACEHOLDER'}}, False),
    ('credential-normalized', {'nodeParam': {'API_SECRET': 'SYNTHETIC_PLACEHOLDER'}}, False),
    ('credential-null', {'nodeParam': {'apiKey': None}}, True),
    ('credential-empty', {'nodeParam': {'apiKey': ''}}, True),
    ('credential-whitespace', {'nodeParam': {'apiKey': ' '}}, False),
    ('logical-secret-reference', {'nodeParam': {'secretRef': 'fixture:resource'}}, True),
    ('input-name-token', {'inputs': [{'name': 'token', 'schema': {'type': 'string', 'default': 'ordinary word'}}]}, True),
    ('business-default-token-key', {'inputs': [{'name': 'request', 'schema': {'default': {'token': 'ordinary word'}}}]}, False),
    ('business-default-identity-keys', {'schema': {'default': {'uid': 'business-value', 'userId': 'business-value', 'runId': 'business-value'}}}, True),
    ('business-literal-prompt', {'nodeParam': {'template': 'Explain token and Authorization in an API example.'}}, True),
    ('business-json-prompt-token', {'nodeParam': {'template': '{"token":"ordinary word"}'}}, False),
    ('business-nonjson-prompt', {'nodeParam': {'template': '{token: ordinary word'}}, True),
    ('advanced-encoded-credential', {'advancedConfig': '{"settings":{"password":"SYNTHETIC_PLACEHOLDER"}}'}, False),
    ('advanced-ordinary-text', {'advancedConfig': {'openingRemarks': 'The word password is a field name.'}}, True),
    ('resource-references', {'nodeParam': {'appId': 'fixture:app', 'modelId': 17, 'llmId': 'fixture:llm', 'repoId': ['fixture:repo'], 'serviceId': 'fixture:service'}}, True),
    ('nodeparam-uid-not-secret-rule', {'nodeParam': {'uid': 'fixture:actor'}}, True),
    ('unknown-layout-business-identities', {'layoutExtension': {'uid': 'business-value', 'userId': 'business-value', 'runId': 'business-value'}}, True),
    ('http-header-object-authorization', {'nodeParam': {'headers': {'Authorization': 'SYNTHETIC_PLACEHOLDER'}}}, False),
    ('http-header-name-value', {'nodeParam': {'headers': [{'name': 'Authorization', 'value': 'SYNTHETIC_PLACEHOLDER'}]}}, True),
    ('nonlisted-sensitive-name', {'nodeParam': {'apiToken': 'SYNTHETIC_PLACEHOLDER'}}, True),
    ('managed-owner-not-secret-rule', {'ownerUserId': 17, 'createBy': 'fixture:actor'}, True),
    ('internal-runtime-not-secret-rule', {'runId': 'fixture:run', 'leaseId': 'fixture:lease', 'attempt': 1, 'debugReceipt': {'runId': 'fixture:run'}}, True),
]
inputs = [{'id': identifier, 'input': value, 'expectedAccepted': accepted} for identifier, value, accepted in cases]
compile_sources = [PROD/(name+'.java') for name in ['WorkflowJsonBoundary','RunJsonBoundary','DslCodec','CanvasExecutionCompiler']]
compile_sources += [ROOT/'ruoyi-backend/ruoyi-common/src/main/java/com/ruoyi/common/exception/ServiceException.java', CHECKS/'WorkflowYamlBoundaryIndependentProbe.java']
sources = compile_sources + [PROD/'WorkflowService.java',ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/domain/WorkflowRecord.java',REF/'controller/workflow/WorkflowController.java',REF/'service/workflow/WorkflowExportService.java']
sources += [REF/'entity/biz/workflow'/name for name in ['BizWorkflowData.java','BizWorkflowNode.java','BizWorkflowEdge.java']]
sources += [REF/'entity/biz/workflow/node'/name for name in ['BizNodeData.java','BizInputOutput.java','BizSchema.java','BizProperty.java','BizValue.java']]
sources += [ROOT/'参考项目/PaiFlow-main/console/frontend/src/components/workflow/hooks/use-flow-common.ts',ROOT/'参考项目/PaiFlow-main/console/frontend/src/components/workflow/nodes/components/model-select/index.tsx',ROOT/'参考项目/PaiFlow-main/core/workflow/engine/nodes/base_node.py',ROOT/'参考项目/PaiFlow-main/core/workflow/api/v1/chat/node_debug.py',ROOT/'文档/项目/PaiFlow工作流项目/完整迁移设计.md',Path(__file__)]
before = {p.relative_to(ROOT).as_posix(): sha(p) for p in sources}
jar = Path('C:/Users/admin/.m2/repository/com/alibaba/fastjson2/fastjson2/2.0.64/fastjson2-2.0.64.jar')
assert jar.is_file(), '只使用现有本地依赖缓存'
started = datetime.now(timezone.utc).isoformat()
with tempfile.TemporaryDirectory(prefix='paiflow-yaml-boundary-') as temp:
    private = Path(temp)
    case_path = private/'cases.json'
    case_path.write_text(json.dumps(inputs,ensure_ascii=False),encoding='utf-8')
    compile_command = ['javac','-encoding','UTF-8','-cp',str(jar),'-d',str(private),*[str(p) for p in compile_sources]]
    compiled = subprocess.run(compile_command,cwd=ROOT,capture_output=True)
    compile_log = compiled.stdout+compiled.stderr
    assert compiled.returncode == 0,compile_log.decode('utf-8',errors='replace')
    command = ['java','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-cp',os.pathsep.join([str(private),str(jar)]),'com.ruoyi.paiflow.service.WorkflowYamlBoundaryIndependentProbe',str(case_path)]
    result = subprocess.run(command,cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
    assert result.returncode == 0,result.stderr
    observed = json.loads(result.stdout)
finished = datetime.now(timezone.utc).isoformat()
assert len(observed)==len(inputs)
assert all(a['id']==b['id'] and a['accepted']==b['expectedAccepted'] and a['inputUnchanged'] for a,b in zip(observed,inputs))
after = {p.relative_to(ROOT).as_posix(): sha(p) for p in sources}
changes = [path for path in before if before[path]!=after[path]]
assert not changes,changes
facts = [
    {'id':'original-meta','source':'WorkflowExportService.java:125-138','fact':'原flowMeta仅name、description、avatarIcon、avatarColor、edgeType、category、advancedConfig，剔除null后加dslVersion=v1；不能直接导出WorkflowRecord或legacyView。'},
    {'id':'original-param-cleaning-inert','source':'WorkflowExportService.java:108-120','fact':'uid/appId/repoList/repoId/modelId/llmId/serviceId删除块为注释，原typed导出实际保留nodeParam内容；不能声称原包已去凭据。'},
    {'id':'typed-closed-surface','source':'BizWorkflowData/BizWorkflowNode/BizWorkflowEdge/BizNodeData','fact':'原typed flowData只有nodes/edges以及声明节点、连线、data字段；未声明根/节点/data扩展会被DTO裁剪。该旧行为不证明未知布局应删除，迁移设计366要求布局/引用/unknown保全。'},
    {'id':'typed-open-values','source':'BizWorkflowNode.position/positionAbsolute/zIndex/draggable；BizNodeData.nodeMeta/nodeParam/retryConfig/errorOutputs；BizWorkflowEdge.data/markerEnd；BizSchema.default/item；BizValue.content','fact':'这些Object/JSONObject声明允许合法业务嵌套内容；任意uid/userId/runId名称不足以判定平台账号或运行状态。'},
    {'id':'uid-exact-source','source':'use-flow-common.ts:77-78,248-249,308-310；nodes/components/model-select/index.tsx:276；BaseLLMNode:1048,1089；node_debug.py:88','fact':'真实node.data.nodeParam.uid由原user.uid写入并供模型请求/Span发起者使用；此精确path非空可按导出账号边界拒绝，不扩大到schema/default/HTTP业务uid。appId及model/llm/repo/service等保留为引用，不能由导出动作授予授权。'},
    {'id':'boundary-exact-keys','source':'RunJsonBoundary.java:17-31；WorkflowJsonBoundary.java:25-41','fact':'唯一现有规则对password/passwd/secret/apikey/apisecret/token/accesstoken/refreshtoken/authorization/privatekey/clientsecret忽略大小写并去下划线/横线；非null且不等于空字符串则400。JSON字符串仅可解析为object/array时递归。不得新增宽泛敏感名称模式。'},
    {'id':'boundary-preserves-or-rejects','source':'WorkflowJsonBoundary.java:13-43；实际22case','fact':'边界只检查不改输入；null/空凭据字段可保留，空格不等于空串；business input.name=token可保留，但business default对象token键或JSON示例prompt同名键会被现有规则拒绝。该误杀是已有保存边界，不应声称YAML新清理造成或再扩大。'},
    {'id':'boundary-not-universal-detector','source':'实际22case header-name-value/nonlisted-sensitive-name/managed-owner/internal-runtime','fact':'现有规则不识别{name:Authorization,value:...}、apiToken以及owner/run/lease字段；有限key规则不能证明任意文本不存在秘密。根元数据7白名单和精确nodeParam.uid边界应独立于secret规则，禁止泛递归删除身份同名业务字段。'},
    {'id':'current-save-fresh-export','source':'WorkflowService.java:88,108,201-204,327-332,372-386','fact':'当前保存/版本已用WorkflowJsonBoundary，rawcanvas与扩展metadata独立存储；导出仍须对实际row候选包重核以防历史/直接导入脏row，而不是依旧保存成功推断安全。'},
    {'id':'no-runtime-or-identity-columns','source':'WorkflowRecord.java；WorkflowService.java:59,346','fact':'ownerUserId/spaceId/createBy/updateBy/latestVersion/revision及debugReceipt等是中心字段；七meta白名单不能夹带其legacyView。flowData真实业务同名字段需按已识别path审，不以名称递归剥除。'},
    {'id':'authorization-original-current','source':'WorkflowController.java:450-473；WorkflowExportService.java:100-101','fact':'原controller直接getById，导出service按SpaceInfoUtil/dataPermission可见性核查；新service应复用当前账号、空间成员、Workflow和真实Agent绑定规则，不移植无授权getById或调用原import/Core/Provider链。'},
    {'id':'yaml-not-execution','source':'WorkflowExportService.java:140-152；完整迁移设计.md:366','fact':'YAML业务包不等于assignment/execution资源快照，不纳入run/lease/checkpoint/privategrant。typed flowData投影不能借用CanvasExecutionCompiler而改变原画布array类型、handles、labels或节点参数。'},
]
report = {'checkedAt':finished,'executionStartedAt':started,'scope':'批27现有边界与原YAML导出独立核对；新导出候选尚未冻结，不审其未完成源码','method':'当前4个纯source+ServiceException privateTemp javac；真实WorkflowJsonBoundary22人工case；原导出/DTO/前端uid链为源码事实','cases':inputs,'observations':observed,'caseCount':len(observed),'passed':len(observed),'facts':facts,'factCount':len(facts),'sourceBefore':before,'sourceAfter':after,'sourceWindowChanges':changes,'sourceCount':len(sources),'compileCommand':compile_command,'compileExitCode':compiled.returncode,'compileLogSha256':hashlib.sha256(compile_log).hexdigest(),'command':command,'exitCode':result.returncode,'stdoutSha256':hashlib.sha256(result.stdout.encode()).hexdigest(),'compiledOriginalExport':False,'dumpedYaml':False,'actualStoredRowsRead':False,'ServiceMapperHttpExecuted':False,'authorizationExecuted':False,'providerCalls':0,'MavenExecuted':False,'targetWrites':False,'productionModified':False,'businessVerified':False,'limitations':['仅22人工纯JSON边界，不是YAML下载/导入往返或真实授权联机。','原typed投影与uid来源为实际源码事实，未执行完整原ExportService/Jackson/SnakeYaml。','任意prompt字符串中的真实秘密无法从字段名规则证明不存在；不读取或输出实际凭据。','本报告不覆盖下一版新导出Service/Controller源码；待独立freeze后按授权复核。']}
output = CHECKS/'工作流YAML导出安全边界独立核对.json'
assert not output.exists(), '唯一新证据不覆盖旧文件'
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
execution = CHECKS/'工作流YAML导出安全边界独立执行.json'
assert not execution.exists()
execution.write_text(json.dumps({'report':output.relative_to(ROOT).as_posix(),'reportSha256':sha(output),'scriptSha256':sha(Path(__file__)),'javaProbeSha256':sha(CHECKS/'WorkflowYamlBoundaryIndependentProbe.java'),'sourceCount':len(sources),'sourceWindowChanges':changes,'cases':len(observed),'exitCode':result.returncode,'startedAt':started,'finishedAt':finished},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'report':str(output.relative_to(ROOT)),'sha256':sha(output),'executionSha256':sha(execution),'caseCount':len(observed),'factCount':len(facts),'sourceCount':len(sources),'sourceWindowChanges':changes},ensure_ascii=False))
