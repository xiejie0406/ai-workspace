"""只读来源 + 原DTO/Jackson纯执行 + 原消费者AST；输出一次性checks金样，不改契约/业务源码。"""
from datetime import datetime, timezone
from pathlib import Path
import hashlib, json, os, shutil, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[6]
OUT = Path(__file__).resolve().parent
FINAL = OUT / '工作流原调试对话历史合同金样.json'
assert not FINAL.exists(), '已交付SHA证据不得覆盖'
J = '参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/'
F = '参考项目/PaiFlow-main/console/frontend/src/'
N = 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/'
paths = [J+'controller/workflow/WorkflowController.java', J+'service/workflow/WorkflowService.java',
         J+'entity/table/workflow/WorkflowDialog.java', J+'mapper/workflow/WorkflowDialogMapper.java',
         J+'tool/DataPermissionCheckTool.java', J+'config/aop/ResponseResultBodyAdvice.java',
         J+'common/anno/ResponseResultBody.java',
         '参考项目/PaiFlow-main/console/backend/commons/src/main/java/com/iflytek/astron/console/commons/response/ApiResult.java',
         '参考项目/PaiFlow-main/console/backend/hub/src/main/java/com/iflytek/astron/console/hub/config/JacksonConfig.java',
         '参考项目/PaiFlow-main/console/backend/pom.xml', '参考项目/PaiFlow-main/console/backend/toolkit/pom.xml',
         '参考项目/PaiFlow-main/docker/PaiFlow/mysql/schema.sql',
         F+'services/flow.ts', F+'utils/http.ts', F+'components/workflow/store/flow-chat-function.ts',
         F+'components/workflow/drawer/chat-debugger/index.tsx',
         'frontend/paiflow-web/src/services/flow.ts',
         'frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts',
         'frontend/paiflow-web/src/components/workflow/drawer/chat-debugger/index.tsx',
         N+'domain/RunRecord.java', N+'mapper/RunMapper.java', N+'service/RunService.java',
         N+'adapter/legacy/LegacyDebugBridgeService.java', N+'service/ResourceAccessService.java',
         N+'service/IdentityMappingService.java',
         'ruoyi-backend/ruoyi-paiflow/src/main/resources/mapper/paiflow/RunMapper.xml',
         '参考项目/PaiFlow-main/core/workflow/api/v1/chat/debug.py',
         '参考项目/PaiFlow-main/core/workflow/service/chat_service.py',
         '参考项目/PaiFlow-main/core/workflow/extensions/otlp/trace/span.py',
         N+'adapter/legacy/LegacyWorkflowStreamCodec.java']
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def entry(p): return {'path': p.relative_to(ROOT).as_posix(), 'sha256': sha(p)}
def lines(path, token):
    return [i for i, line in enumerate((ROOT/path).read_text(encoding='utf-8-sig').splitlines(), 1) if token in line]
def fact(id, path, token, meaning, kind='source_derived_not_executed'):
    nums = lines(path, token); assert nums, (path, token)
    return {'id': id, 'path': path, 'lines': nums, 'symbolOrAnchor': token, 'fact': meaning, 'basis': kind}

before = {p: sha(ROOT/p) for p in paths}
started = datetime.now(timezone.utc).isoformat()
node = subprocess.run(['node', str(OUT/'WorkflowDialogueOriginalConsumerProbe.cjs')], cwd=ROOT,
                      capture_output=True, text=True, encoding='utf-8')
(OUT/'工作流原对话消费者AST-实际执行.log').write_text(node.stdout+node.stderr, encoding='utf-8')
assert node.returncode == 0, node.stderr
consumers = json.loads(node.stdout)

cache = Path('C:/Users/admin/.m2/repository')
# 原toolkit显式Jackson2.17.2、原Lombok1.18.32均按现有缓存执行。
# 原父POM声明MyBatisPlus3.5.7未缓存，本probe仅用3.5.5 TableId/IdType注解，不执行ORM。
relative_jars = [
    'com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar',
    'com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar',
    'com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar',
    'com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.17.2/jackson-datatype-jsr310-2.17.2.jar',
    'org/projectlombok/lombok/1.18.32/lombok-1.18.32.jar',
    'com/baomidou/mybatis-plus-annotation/3.5.5/mybatis-plus-annotation-3.5.5.jar',
    'org/springframework/spring-context/6.1.14/spring-context-6.1.14.jar',
    'org/springframework/spring-beans/6.1.14/spring-beans-6.1.14.jar',
    'org/springframework/spring-core/6.1.14/spring-core-6.1.14.jar',
    'org/springframework/spring-jcl/6.1.14/spring-jcl-6.1.14.jar']
jars = [cache/p for p in relative_jars]
assert all(p.is_file() for p in jars), '只使用已有缓存，不安装依赖'
cp = os.pathsep.join(map(str,jars))
dto_path = ROOT/(J+'entity/table/workflow/WorkflowDialog.java')
jackson_path = ROOT/'参考项目/PaiFlow-main/console/backend/hub/src/main/java/com/iflytek/astron/console/hub/config/JacksonConfig.java'
with tempfile.TemporaryDirectory(prefix='paiflow-dialogue-source-') as folder:
    private = Path(folder); classes=private/'classes'; classes.mkdir()
    copied=[]
    for source in [dto_path,jackson_path]:
        target=private/source.name; shutil.copyfile(source,target); assert sha(source)==sha(target);copied.append(target)
    compile_cmd=['javac','-encoding','UTF-8','-cp',cp,'-processorpath',str(jars[4]),'-processor',
                 'lombok.launch.AnnotationProcessorHider$AnnotationProcessor','-d',str(classes),
                 *map(str,copied),str(OUT/'WorkflowDialogueDtoSourceProbe.java')]
    compiled=subprocess.run(compile_cmd,cwd=private,capture_output=True,text=True,encoding='utf-8')
    (OUT/'工作流原对话DTO-私有编译.log').write_text(compiled.stdout+compiled.stderr,encoding='utf-8')
    assert compiled.returncode==0,compiled.stderr
    executed=subprocess.run(['java','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-cp',
                             str(classes)+os.pathsep+cp,'WorkflowDialogueDtoSourceProbe'],cwd=private,
                            capture_output=True,text=True,encoding='utf-8')
    (OUT/'工作流原对话DTO-实际纯执行.log').write_text(executed.stdout+executed.stderr,encoding='utf-8')
    assert executed.returncode==0,executed.stderr
    dto=json.loads(executed.stdout)
base_mapper=cache/'com/baomidou/mybatis-plus-core/3.5.5/mybatis-plus-core-3.5.5.jar'
assert base_mapper.is_file()
signature=subprocess.run(['javap','-classpath',str(base_mapper),'com.baomidou.mybatisplus.core.mapper.BaseMapper'],
                         capture_output=True,text=True,encoding='utf-8')
assert signature.returncode==0
update_lines=[s.strip() for s in signature.stdout.splitlines() if ' update(' in s]
assert any('default int update(com.baomidou.mybatisplus.core.conditions.Wrapper<T>)' in s for s in update_lines)
(OUT/'工作流原对话clear返回类型-缓存API旁证.log').write_text('\n'.join(update_lines)+'\n',encoding='utf-8')

facts=[
 fact('controller-body',J+'controller/workflow/WorkflowController.java','saveDialog(@RequestBody','POST /workflow/dialog 接收原WorkflowDialog实体，body本身@NotNull，无字段级必填注解。'),
 fact('controller-list',J+'controller/workflow/WorkflowController.java','listDialog(@RequestParam','GET /workflow/dialog/list 的workflowId是@NotNull Long；type是可选Integer。无pageNum/pageSize/offset。'),
 fact('controller-clear',J+'controller/workflow/WorkflowController.java','clearDialog(@RequestParam','GET /workflow/dialog/clear 参数同list；原请求改变数据但必须保留既有原请求兼容。'),
 fact('save-source',J+'service/workflow/WorkflowService.java','public ApiResult<String> saveDialog','按内部workflowId查Workflow、检查visible，仅覆盖uid/createTime后insert；返回chatId String。没有Run/终态/段序号/幂等校验。'),
 fact('answeritem-outer-quote',J+'service/workflow/WorkflowService.java','answerItem.substring','只删除answerItem最外一对双引号，保反斜杠转义字面内容，不等同JSON.parse/反转义。'),
 fact('list-source',J+'service/workflow/WorkflowService.java','public List<WorkflowDialog> listDialog','where uid=当前用户 AND workflowId AND type AND deleted=false；createTime DESC、limit10。无ID次排序、无工作流权限再次检查。nullable type不代表查询所有type。'),
 fact('clear-source-bug',J+'service/workflow/WorkflowService.java','public Object clearDialog','调用BaseMapper.update(Wrapper)并返回受影响行数；条件只有workflowId/type，漏uid与工作流权限，会影响别人的历史。迁移应收紧为本人clear，不能照搬。'),
 fact('old-visible',J+'tool/DataPermissionCheckTool.java','checkWorkflowVisible','原visible个人分支含public/owner/原admin fallback，团队分支仅原space匹配。不能把原UID/public标志当现平台权限或Runowner证明。'),
 fact('response-wrapper',J+'config/aop/ResponseResultBodyAdvice.java','return ApiResult.success(body)','原已是ApiResult保持原样；其余List/Integer统一包装ApiResult，nullbody包装success。不是直接裸数组。'),
 fact('frontend-unwrapping',F+'utils/http.ts','return result.data','code===0后返回data；原消费者收到chatId字符串、列表数组、clear受影响计数。'),
 fact('dto-workflow-id',J+'entity/table/workflow/WorkflowDialog.java','Long workflowId;','实体字段是Long内部工作流ID，不是UUID flowId；13字段均wrapper/reference可null，无字段validation/default。','actual_dto_jackson_checked'),
 fact('schema-table','参考项目/PaiFlow-main/docker/PaiFlow/mysql/schema.sql','CREATE TABLE `workflow_dialog`','原MySQL table仅PK(id)/workflow_id索引；type tinyint NOT NULL DEFAULT1，sid varchar255可null，chat_id varchar100可null。DTO接受null不证明DB会接受。'),
 fact('save-consumer',F+'components/workflow/store/flow-chat-function.ts','const handleSaveDialogue','实际save8字段，type=1、workflowId=currentFlow.id、question/questionItem同JSON串；同步清chatInfoRef，ACK后刷新列表，不使用返回chatId值。','actual_original_and_current_ast_checked'),
 fact('sid-from-frame',F+'components/workflow/store/flow-chat-function.ts','get().chatInfoRef.sid =','handleMessage将公开frame.id写sid；旧id不代表Run。新受信桥frame.id为真实Run，才可server核owner/WF/version/space。'),
 fact('list-consumer',F+'components/workflow/store/flow-chat-function.ts','const getDialogues','每条question/answer JSON.parse；倒序响应通过unshift变正序ask/answer，按chatId隔divider，同sid不会去重。','actual_original_and_current_ast_checked'),
 fact('clear-consumer',F+'components/workflow/store/flow-chat-function.ts','const deleteAllChat','then完全忽略clear data数值；count0/2都清本地列表/interrupt并换chatId；history=true不解锁canvas。','actual_original_and_current_ast_checked'),
 fact('qa-interrupt',F+'components/workflow/store/flow-chat-function.ts',"get().wsMessageStatus = 'end'",'handleInterrupt可把QA等待标queue end；isChatEnd只看end+三个文本queue空，并不证明Run终态。','actual_queue_functions_checked_effect_chain_source_only'),
 fact('drawer-save-effect',F+'components/workflow/drawer/chat-debugger/index.tsx','!debuggering && buildPassRef && handleSaveDialogue','已mounted时debuggering下降且buildPassRef才保存，无Run terminal条件。React effect本身没有在本probe运行。'),
 fact('drawer-queue',F+'components/workflow/drawer/chat-debugger/index.tsx','setDebuggering(false)','timer排空文字且isChatEnd后设置false；来源链可触发QA中途保存，不宜terminal-only新增规则。'),
 fact('current-run-owner',N+'domain/RunRecord.java','private Long ownerUserId;','Run正式行含workflowId/workflowVersionId/spaceId/ownerUserId，允许做严格服务器关联，不信client uid/id。'),
 fact('current-detail-insufficient',N+'service/RunService.java','private RunRecord permitted','现detail查询检查平台run query+同space权限，但不强制本人owner；不能直接视为本人历史授权。'),
 fact('current-boundrun',N+'adapter/legacy/LegacyDebugBridgeService.java','private RunRecord boundRun','既有桥同域、Run↔固定WF/version、owner==捕获actor、固定assignment检查及返回前主体复查可复用；本方法private，不建议复制第二套授权真相。'),
 fact('current-live-identity',N+'service/IdentityMappingService.java','public LoginUser current','读取当前实际用户和当前菜单权限；client uid只作不可受信输入。'),
 fact('original-resume-new-span','参考项目/PaiFlow-main/core/workflow/api/v1/chat/debug.py','span = Span(app_id="", uid="", chat_id="")','原resume入口新建Span，不使用原Run或请求sid做正式归属。'),
 fact('original-span-sid','参考项目/PaiFlow-main/core/workflow/extensions/otlp/trace/span.py','self.sid = sid_gen.sid_generator2.gen()','无active span时生成新的Spark sid；有active span则复用其sid。不能假定原sid稳定跨所有resume。'),
 fact('original-resume-frame-id','参考项目/PaiFlow-main/core/workflow/service/chat_service.py','response.id = span_context.sid','resume输出公开response.id被改为本次Span.sid，所以原显示sid不是固定跨段RunID。'),
 fact('current-public-run-id',N+'adapter/legacy/LegacyWorkflowStreamCodec.java','!Objects.equals(run.getId(),frame.getString("id"))','现在公开原节点frame必须真实run.id；中心终帧也写run.id。新同Run多个QA段sid相同，需明确原不同sid段标识差异。')]

normalization_examples=[{'input':None,'sourceExpected':None},{'input':'','sourceExpected':''},
 {'input':'""','sourceExpected':''},{'input':'"a\\nb"','sourceExpected':'a\\nb'},
 {'input':'["a"]','sourceExpected':'["a"]'},{'input':'"a"tail','sourceExpected':'"a"tail'}]
after={p:sha(ROOT/p) for p in paths}
changes=[p for p in paths if before[p]!=after[p]]
assert not changes, '若业务源在读取窗口变化，应保为时点草稿而不是假零窗口'
report={
 'schemaVersion':'paiflow.original-dialogue-contract-golden.v1','startedAt':started,'endedAt':datetime.now(timezone.utc).isoformat(),
 'scope':'原调试显示历史3接口来源和golden材料；不是正式App会话/runtime history/变量真相，不是实施或长期状态文件',
 'sourceHashesBefore':before,'sourceHashesAfter':after,'sourceWindowChanges':changes,'sourceCount':len(paths),
 'methods':[
  {'method':'POST','path':'/workflow/dialog','body':'WorkflowDialog','actualOriginalFrontendFields':['chatId','type','workflowId','sid','questionItem','answerItem','question','answer'],'returns':{'code':0,'message':'成功文案由原国际化决定','data':'原入参chatId（String，可null）','timestamp':'Long'},'bodyPolicySource':'仅@NotNull body；字段nullable；uid/createTime server覆盖','originalServiceExecuted':False},
  {'method':'GET','path':'/workflow/dialog/list','query':{'workflowId':'必填Long内部ID','type':'可选Integer；原消费者固定1'},'returns':{'code':0,'data':'WorkflowDialog[0..10]，每条13原字段','timestamp':'Long'},'ordering':'createTime DESC，无稳定ID tie-break','pagination':'没有分页或总数；固定limit10','filter':'本人uid、workflowId、type、deleted=false','originalServiceExecuted':False},
  {'method':'GET','path':'/workflow/dialog/clear','query':{'workflowId':'必填Long内部ID','type':'可选Integer；原消费者固定1'},'returns':{'code':0,'data':'Integer受影响行数，0合法技术返回','timestamp':'Long'},'sourceBug':'原更新缺本人uid及visible校验；本地迁移必须本人和实际域/权限限制，不复制跨用户clear','originalServiceExecuted':False}],
 'dtoFields':{'Long':['id','workflowId'],'String':['uid','question','answer','data','sid','questionItem','answerItem','chatId'],'Date':['createTime'],'Boolean':['deleted'],'Integer':['type'],'allNullableInDTO':True,'fieldValidationAnnotations':[],'databaseNullableDifferent':['type NOT NULL DEFAULT1']},
 'actualPureDto':dto,'actualOriginalAndCurrentAst':consumers,
 'actualCommands':{'node':{'exitCode':node.returncode,'log':entry(OUT/'工作流原对话消费者AST-实际执行.log')},'javac':{'exitCode':compiled.returncode,'privateTemp':True,'log':entry(OUT/'工作流原对话DTO-私有编译.log')},'java':{'exitCode':executed.returncode,'log':entry(OUT/'工作流原对话DTO-实际纯执行.log')}},
 'cacheInputs':[{'name':p.name,'sha256':sha(p)} for p in jars],
 'ormReturnTypeCorroboration':{'originalDeclaredVersion':'3.5.7','actualCachedInspectionVersion':'3.5.5','methodSignatures':update_lines,'jarSha256':sha(base_mapper),'log':entry(OUT/'工作流原对话clear返回类型-缓存API旁证.log'),'actualOriginal3_5_7RuntimeExecuted':False,'scope':'缓存API只辅助确认BaseMapper受影响行数签名，不执行SQL，不冒充原完整运行环境'},
 'sourceFacts':facts,'answerItemOuterQuoteSourceDerivedOnly':normalization_examples,
 'mappingAndRejectionRequirements':[
  'workflowId=当前Workflow行Long，flowId=原UUID，两者必须查正式记录映射；拒将UUID当Long或由sid猜工作流。',
  '新save原sid必须对应真实Run；服务器严格核当前actor=Run.owner、当前域=Run.space、请求workflowId=Run.workflowId、固定version真实存在且归该WF。禁止client uid/id/createTime/deleted成为正式管理事实。',
  '现RunService.detail非本人严格门；需复用/提取现中心权威授权路径并在等待行锁后实时重查权限/成员和actor，而不是仅凭space membership能保存或清他人历史。',
  '原空sid、原Spark非Run sid、旧UID历史没有真实映射材料；不能自动借用最近Run、chatId、任意相同WF或现用户建立伪关联。本批不导入原dump。',
  'type=1是现页面调试显示历史；type=2正式App会话未接，不把新debug日志冒充App/runtime历史。未知type/缺type的正式兼容策略应在唯一设计明确，原optional查询不等于全类型查询。',
  '允许同Run多次QA显示段；不能unique(runId)或unique(runId,chatId)，也不能用纯bodyhash永久去重相同文本的合法重复段。原8字段没有CP/seq/稳定saveKey，需要明确同次段标识的最小协议或记录非幂等原语义。',
  '原resume每次以本次Span.sid覆盖frame.id，新桥固定公开id=runId，因此不能把sid视为天然显示段ID或保存重试幂等键。SSE eventid仍承担runId:seq位置，不能拿它虚构原8字段已经携带段回执。',
  'QA保存不是terminal-only；仅页面展示记录，保存/clear绝不能改变Run state/event/result/Checkpoint/cancel或释放费用许可。',
  'list/clear以本人行和实际可访问WF域为边界；清空软删除本人行，保留执行真相和别人的历史。无记录count0也能正常清本地展示，不能据count0称业务写失败。',
  'historyVersionData不进入原save body，不能按当前selected version猜固定Run版本。历史版本回放的归属来自sid实际Run/version。',
  'question/answer为内部JSON字符串，原页面直接JSON.parse；新行需严格可解析且保全原字段/null/顺序，不能用吞错空串伪装成功。原DTO本身没有该保证。'],
 'corrections':{'previousFrozenEvidence':{'path':'文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/下一本地功能纵向来源只读核对.json','sha256':'9bcaea648bef8819a8addd4b5062b188faa803f6938cf0d62d39ebb2da33f734'},'clearReturnType':'此前草稿误写boolean，精确代码返回BaseMapper.update的Integer受影响数；旧证据冻结不覆盖，本资料更正。','initialAstRunnerIssue':'首次AST runner在跨VM数组deepStrictEqual出现同形prototype断言错误；改为structuredClone/JSON序列值比较。不是原功能或DTO业务失败，不伪称首次执行通过。','preservedAstFixtureFailureLog':entry(OUT/'工作流原对话消费者AST-跨VM夹具首失败.log')},
 'toolSources':[entry(Path(__file__)),entry(OUT/'WorkflowDialogueOriginalConsumerProbe.cjs'),entry(OUT/'WorkflowDialogueDtoSourceProbe.java')],
 'limitations':['只执行原DTO+原JacksonConfig和原/当前所选前端函数AST；原WorkflowService/Mapper/ResponseBodyAdvice/Auth完整业务仅源码推导。',
 'Jackson2.17.2与Lombok1.18.32按原POM和现缓存；ORM注解3.5.5/compile-time Spring6.1.14是只读缓存，非原全部依赖闭环。',
 '当前Run/Bridge仅核源、未调用；当前源窗口只涵盖本报告before/after，不代表root此前219输入或Maven执行窗口。',
 '手造golden不能证明真实数据库、HTTP、React生命周期、用户界面、旧数据迁移或原功能已通过。'],
 'MavenExecuted':False,'targetWritten':False,'productionModified':False,'databaseAccessed':False,'network':False,'servicesStarted':False,'businessVerified':False}
FINAL.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'proof':entry(FINAL),'sourceCount':len(paths),'facts':len(facts),'dtoCases':dto['caseCount'],'originalAstCases':consumers['caseCount'],'currentAstCases':consumers['caseCount'],'sourceWindowChanges':changes,'clearDataType':'Integer affected rows','businessVerified':False},ensure_ascii=False))
