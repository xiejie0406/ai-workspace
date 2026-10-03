"""仅冻结生产源审查+实际纯方法/MIME有限probe；不写target，不执行Service/Auth/DB。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib,json,os,subprocess,tempfile,xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[6];CHECKS=Path(__file__).resolve().parent
PROD=ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
service=PROD/'WorkflowExportService.java'
assert sha(service)=='9706d260c26966ce9622032873a389f7e1bc900ebc4397fbeaa7929d5cc6b3e7'
source_names=['WorkflowExportService','WorkflowJsonBoundary','RunJsonBoundary','DslCodec','CanvasExecutionCompiler','WorkflowService','DefaultPersonalSpaceService','ResourceAccessService','IdentityMappingService']
sources=[PROD/(p+'.java') for p in source_names]
sources += [ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowExportController.java',ROOT/'frontend/paiflow-web/src/utils/http.ts',ROOT/'frontend/paiflow-web/src/services/flow.ts',ROOT/'ruoyi-backend/ruoyi-common/src/main/java/com/ruoyi/common/exception/ServiceException.java',ROOT/'文档/项目/PaiFlow工作流项目/完整迁移设计.md',CHECKS/'WorkflowYamlExportPureProbe.java',CHECKS/'WorkflowYamlDownloadMimeProbe.cjs',Path(__file__)]
before={p.relative_to(ROOT).as_posix():sha(p) for p in sources}
snapshot=ROOT/'文档/项目/PaiFlow工作流项目/证据/本地源码检查/调试显示历史八类定向检查本次JUnit快照'
files=sorted(snapshot.glob('TEST-*.xml'));assert files,'只使用已保存classpath，禁止Maven/安装'
classpath=ET.parse(files[0]).find("./properties/property[@name='java.class.path']").get('value')
parts=classpath.split(os.pathsep)
assert all(Path(p).exists() for p in parts),[p for p in parts if not Path(p).exists()]
compile_sources=[PROD/(name+'.java') for name in ['WorkflowExportService','WorkflowJsonBoundary','RunJsonBoundary','DslCodec','CanvasExecutionCompiler']]+[ROOT/'ruoyi-backend/ruoyi-common/src/main/java/com/ruoyi/common/exception/ServiceException.java',CHECKS/'WorkflowYamlExportPureProbe.java']
started=datetime.now(timezone.utc).isoformat()
with tempfile.TemporaryDirectory(prefix='paiflow-yaml-export-pure-') as temp:
    private=Path(temp)
    arguments=['-proc:none','-encoding','UTF-8','-cp',classpath,'-d',str(private),*[str(p) for p in compile_sources]]
    argfile=private/'javac.args';argfile.write_text('\n'.join('"'+p.replace('\\','/').replace('"','\\"')+'"' for p in arguments),encoding='utf-8')
    compile_command=['javac','@'+str(argfile)]
    compiled=subprocess.run(compile_command,cwd=ROOT,capture_output=True)
    (CHECKS/'工作流YAML导出纯方法独立编译.log').write_bytes(compiled.stdout+compiled.stderr)
    assert compiled.returncode==0,(compiled.stdout+compiled.stderr).decode('utf-8',errors='replace')
    command=['java','-Xmx512m','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-cp',os.pathsep.join([str(private),classpath]),'com.ruoyi.paiflow.service.WorkflowYamlExportPureProbe']
    javaargs=private/'java.args';javaargs.write_text('\n'.join('"'+p.replace('\\','/').replace('"','\\"')+'"' for p in command[1:]),encoding='utf-8')
    ran=subprocess.run(['java','@'+str(javaargs)],cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
    (CHECKS/'工作流YAML导出纯方法独立执行.log').write_text(ran.stdout+ran.stderr,encoding='utf-8')
    assert ran.returncode==0,ran.stderr
    observations=json.loads(ran.stdout)
node_command=['node',str(CHECKS/'WorkflowYamlDownloadMimeProbe.cjs')]
node=subprocess.run(node_command,cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
(CHECKS/'工作流YAML下载MIME独立执行.log').write_text(node.stdout+node.stderr,encoding='utf-8')
assert node.returncode==0,node.stderr
mime=json.loads(node.stdout)
after={p.relative_to(ROOT).as_posix():sha(p) for p in sources};changes=[p for p in before if before[p]!=after[p]]
assert not changes,changes
assert all(p['passed'] for p in observations) and all(p['passed'] for p in mime['cases'])
facts=[
 {'id':'read-only-scope','source':'WorkflowExportService.export/sameActor；DefaultPersonalSpaceService.existing','fact':'readOnly READ_COMMITTED入口仅existing空间映射，不materialize；permission query+role3+同actor，再require当前draft，不读latestVersion/Run/App/资源服务。'},
 {'id':'final-auth','source':'WorkflowExportService:87-95；ResourceAccessService.requireSpace；IdentityMappingService.current','fact':'dump完成后space/menu/account/member/actor与WF删除/域复读；requireSpace/Identity.current清SqlSession缓存并查现用户/menu/member。有限源码证明，不等于真实认证/Redis/session/H2执行。'},
 {'id':'snapshot','source':'WorkflowExportService:52-69','fact':'rowcanvas/metadata/advanced为局部不可变String，object解析后清理/typed投影仅副本；最终读允许返回该次读取的旧revision快照，不声称与并发改稿实时一致。'},
 {'id':'meta-whitelist','source':'WorkflowExportService:70-81；SourceMeta','fact':'七meta由rowname/description和metadata四字段及rawAdvanced明确构造，不导出legacyView/WorkflowRecord平台字段；NULLmeta按原规则去掉再dslVersion=v1。'},
 {'id':'typed-extension-null','source':'WorkflowExportService.project/SourceGraph..SourceValue；实际pure cases','fact':'已知字段按原类型转换，project以original字段副本为底，只覆盖实际已出现的已知字段，保unknown/显式null；Object/JSONObject开放值保业务键和引用，未调用CanvasExecutionCompiler来重写画布。'},
 {'id':'precise-uid','source':'WorkflowExportService.clearInitiator两次；实际pure cases','fact':'仅nodes[index].data.nodeParam.uid非空String（包括空格）清空；null/empty保留，非String非null400；nodeParamJSON字符串经原typed转换后第二次检查，comment只含数字index固定路径与中文说明。'},
 {'id':'secret-single-boundary','source':'WorkflowExportService:58-60,82；WorkflowJsonBoundary；实际pure case','fact':'当前canvas、metadata、advancedobject及最终wrapper均唯一WorkflowJsonBoundary检查；不新增宽secret pattern。不声称普通文本或所有name/value凭据都可被识别，原22case限制保留。'},
 {'id':'utf8-budget','source':'WorkflowExportService.dump/BoundedOutput；实际pure amplification case','fact':'canvas/metadata/advanced各16MiB JSON，wrapper32MiB；YAML OutputStream每次真实UTF8write前reserve32MiB，缩进放大已实际抛413。writer/yaml内部仍有库正常缓冲，不声称进程总内存严格32MiB。'},
 {'id':'delivery-before-commit','source':'LegacyWorkflowExportController.download','fact':'完整export成功后才设置附件content type/headers/body；filename只long id，无客户端文件名注入；no-store/nosniff、length、expose disposition已设置。IOException发生实际send后无法追回网络字节，未在本probe执行。'},
 {'id':'local-error-http','source':'LegacyWorkflowExportController.rejected/invalidId/denied/unavailable','fact':'本Controller局部RuoYi AjaxResult真实4xx/5xx，未知异常500泛化消息，GlobalExceptionHandler未改；ServiceException路径消息无凭据值。此为源码事实，MockMVC由Root后续执行。'},
 {'id':'filter-json-mime','source':'http.ts downloadFileWithHeaders；实际TS AST5 cases','fact':'原workflow/export URL的HTTP200 application/json（大小写/charset/query）不会造blob/点a；正常octet200一次、403无附件，其他下载原JSON行为保持。使用内存XHR VM，不是浏览器或真实过滤器执行。'},
]
review={'startedAt':started,'finishedAt':datetime.now(timezone.utc).isoformat(),'sourcesBefore':before,'sourcesAfter':after,'sourceWindowChanges':changes,'sourceCount':len(sources),'compileSources':[str(p.relative_to(ROOT)) for p in compile_sources],'compileExitCode':compiled.returncode,'compileCommand':compile_command,'classpathFromRecordedJUnit':str(files[0].relative_to(ROOT)),'classpathArtifactSha256':sha(files[0]),'classpathSha256':hashlib.sha256(classpath.encode()).hexdigest(),'classpathUse':'readonly既有target类型与依赖cache；新Export/Boundary/Dsl/Canvas均由exact当前源码privateTemp编译优先加载，未执行任何非纯依赖方法','javaExitCode':ran.returncode,'javaCases':observations,'javaCaseCount':len(observations),'nodeCommand':node_command,'nodeExitCode':node.returncode,'nodeMimeProbe':mime,'facts':facts,'factCount':len(facts),'confirmedBlockers':[],'sourceOnly':['export完整事务/Auth/Mapper/final权限recheck','Controller actualHTTP/exception路由','响应发送/断连行为'],'fullExportExecuted':False,'ServiceMapperHttpExecuted':False,'MavenExecuted':False,'targetWrites':False,'RuntimeOrFrontSuiteRerun':False,'ProviderCalls':0,'businessVerified':False,'limitations':['只调用实际新Export纯static方法；不把该probe说成完整Service/事务/授权成功。','未知扩展内与账号/运行字段同名的业务值保留；无法仅据名称证明或泛拒任意人工嵌入的秘密/身份。','真实原DTO source事实和首22边界独立事实沿用，不再执行原ExportService。']}
output=CHECKS/'工作流YAML导出安全边界独立核对.json'
previous=output.read_bytes();previous_sha=hashlib.sha256(previous).hexdigest()
assert previous_sha=='00fce073ed04169fa3957f3e4b94d58e9bb129f4b085a44eb7f48e32e1ddbe0d'
preserved=CHECKS/'工作流YAML导出候选审查前证据原文';preserved.mkdir(exist_ok=False)
(preserved/output.name).write_bytes(previous)
report=json.loads(previous);report['finalSourceReview']=review;report['approvedUidHandling']['productionImplementationOrProbeCompleted']=True;report['approvedUidHandling']['completedScope']='冻结Service源码审＋真实purestatic UID处理；非Service/Auth/DB完整执行';report['previousDecisionProof']={'path':str((preserved/output.name).relative_to(ROOT)),'sha256':previous_sha}
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
execution=CHECKS/'工作流YAML导出候选独立执行.json';assert not execution.exists()
execution.write_text(json.dumps({'report':str(output.relative_to(ROOT)),'reportSha256':sha(output),'scriptSha256':sha(Path(__file__)),'javaProbeSha256':sha(CHECKS/'WorkflowYamlExportPureProbe.java'),'mimeProbeSha256':sha(CHECKS/'WorkflowYamlDownloadMimeProbe.cjs'),'sourceCount':len(sources),'sourceWindowChanges':changes,'pureJavaCases':len(observations),'mimeVmCases':mime['count'],'compileExit':compiled.returncode,'javaExit':ran.returncode,'nodeExit':node.returncode,'fullServiceExecuted':False,'sourceOnlyFactCount':len(facts)},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'reportSha256':sha(output),'executionSha256':sha(execution),'javaCount':len(observations),'mimeCount':mime['count'],'sources':len(sources),'sourceWindowChanges':changes,'confirmedBlockers':[]},ensure_ascii=False))
