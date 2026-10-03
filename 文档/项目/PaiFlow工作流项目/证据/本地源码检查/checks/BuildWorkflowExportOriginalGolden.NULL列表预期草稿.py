"""原导出DTO+纯算法段在私有临时目录执行；无Maven/target/业务源/网络。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib,json,os,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent
FINAL=OUT/'工作流原YAML导出合同金样.json'
assert not FINAL.exists(),'已交付证据不能覆写'
J='参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/'
C='参考项目/PaiFlow-main/console/backend/commons/src/main/java/com/iflytek/astron/console/commons/'
dto=[C+'entity/workflow/Workflow.java',J+'entity/biz/workflow/BizWorkflowData.java',J+'entity/biz/workflow/BizWorkflowNode.java',J+'entity/biz/workflow/BizWorkflowEdge.java',*[J+f'entity/biz/workflow/node/{name}.java' for name in ['BizNodeData','BizInputOutput','BizSchema','BizProperty','BizValue']]]
paths=[*dto,J+'service/workflow/WorkflowExportService.java',J+'controller/workflow/WorkflowController.java','参考项目/PaiFlow-main/console/backend/toolkit/pom.xml','参考项目/PaiFlow-main/console/backend/pom.xml','参考项目/PaiFlow-main/console/frontend/src/utils/http.ts','frontend/paiflow-web/src/utils/http.ts','参考项目/PaiFlow-main/console/frontend/src/components/workflow/utils/index.ts','frontend/paiflow-web/src/components/workflow/utils/index.ts','frontend/paiflow-web/src/pages/workflow/components/btn-groups/index.tsx','frontend/paiflow-web/src/pages/space-page/agent-page/index.tsx']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
before={p:sha(ROOT/p) for p in paths};started=datetime.now(timezone.utc).isoformat()
fixtures=[
 {'id':'empty-arrays-seven-metadata','workflow':{'name':'中文 😀','description':'描述','avatarIcon':'icon','avatarColor':'#f0f','edgeType':'curve','category':3,'advancedConfig':'{"x":null}','uid':'PRIVATE_FIXTURE','appId':'FIXTURE_APP','flowId':'FIXTURE_FLOW','id':1,'futureMeta':'ignored'},'canvas':{'nodes':[],'edges':[]}},
 {'id':'all-null-metadata-and-null-dto-lists','workflow':{'name':None,'description':None,'avatarIcon':None,'avatarColor':None,'edgeType':None,'category':None,'advancedConfig':None},'canvas':{'nodes':None,'edges':None}},
 {'id':'root-unknown-viewport-dropped','workflow':{'name':'root'},'canvas':{'nodes':[],'edges':[],'viewport':{'x':0,'y':None,'zoom':0.75},'futureRoot':None}},
 {'id':'closed-node-data-edge-fields','workflow':{'name':'closed'},'canvas':{'nodes':[{'id':'node-start::s','futureNode':{'nil':None},'data':{'label':'S','futureData':{'zero':0,'flag':False}}}],'edges':[{'id':'e','source':'a','target':'b','futureEdge':17}]}},
 {'id':'open-object-json-fields-null-policy','workflow':{'name':'open'},'canvas':{'nodes':[{'id':'node-start::s','position':{'x':0,'y':None,'future':False},'data':{'nodeParam':{'nil':None,'nested':{'nil':None,'zero':0,'flag':False},'array':[None,0,False,{'nil':None,'x':'x'}]},'nodeMeta':{'future':None,'zero':0},'originPosition':{'nil':None,'flag':False},'references':{'nil':None,'zero':0},'retryConfig':{'enabled':False,'customOutput':None}}}],'edges':[{'source':'a','target':'b','data':{'nil':None,'false':False}}]}},
 {'id':'schema-default-item-value-and-property','workflow':{'name':'schema'},'canvas':{'nodes':[{'id':'node-start::s','data':{'outputs':[{'name':'obj','futureOutput':'dropped','schema':{'type':'object','default':{'nil':None,'zero':0,'flag':False},'item':{'future':None,'zero':0},'value':{'type':'literal','content':{'nil':None,'flag':False},'futureValue':'drop'},'properties':[{'name':'p','type':'integer','default':'17','required':False,'futureProperty':'drop'}],'futureSchema':'drop'}}]}}],'edges':[]}},
 {'id':'typed-node-width-boolean-string-coercion','workflow':{'name':17,'category':'3'},'canvas':{'nodes':[{'id':17,'width':'13','height':'0','selected':'false','dragging':'true','data':{'label':23,'updatable':False}}],'edges':[]}},
 {'id':'typed-property-default-coercion','workflow':{'name':'property'},'canvas':{'nodes':[{'id':'node-start::s','data':{'outputs':[{'name':15,'required':'false','schema':{'type':'object','properties':[{'name':'p','default':123,'required':'true','type':'string'}]}}]}}],'edges':[]}},
 {'id':'raw-json-null-root','workflow':{'name':'null'},'canvasRaw':'null'},
 {'id':'nullable-node-edge-array-items','workflow':{'name':'nullitems'},'canvas':{'nodes':[None,{'id':'node-end::e','data':None}],'edges':[None]}},
 {'id':'invalid-typed-width','workflow':{'name':'invalid'},'canvas':{'nodes':[{'id':'node-start::s','width':'not-int'}],'edges':[]}},
 {'id':'private-fields-original-cleanup-commented','workflow':{'name':'fixture-only'},'canvas':{'nodes':[{'id':'node-start::s','data':{'nodeParam':{'uid':'PRIVATE_FIXTURE','appId':'FIXTURE_APP','apiKey':'FIXTURE_NOT_A_CREDENTIAL'}}}],'edges':[]}}
]
cache=Path('C:/Users/admin/.m2/repository')
relative=['com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar','com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar','com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar','com/alibaba/fastjson2/fastjson2/2.0.51/fastjson2-2.0.51.jar','org/yaml/snakeyaml/2.5/snakeyaml-2.5.jar','org/projectlombok/lombok/1.18.32/lombok-1.18.32.jar','com/baomidou/mybatis-plus-annotation/3.5.5/mybatis-plus-annotation-3.5.5.jar']
jars=[cache/p for p in relative];jars.append(cache/'org/mybatis/mybatis/3.5.19/mybatis-3.5.19.jar');assert all(p.is_file() for p in jars),'仅已有缓存，不安装'
cp=os.pathsep.join(map(str,jars));source=(ROOT/(J+'service/workflow/WorkflowExportService.java')).read_text(encoding='utf-8')
segment=source[source.index('            BizWorkflowData bizWorkflowData = JSON.parseObject(workflow.getData(), BizWorkflowData.class);'):source.index('            yaml.dump(yamlWrapper, new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));')+len('            yaml.dump(yamlWrapper, new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));')]
prefix='''import com.alibaba.fastjson2.JSON;import com.alibaba.fastjson2.JSONObject;import com.iflytek.astron.console.commons.entity.workflow.Workflow;import com.iflytek.astron.console.toolkit.entity.biz.workflow.*;import com.iflytek.astron.console.toolkit.entity.biz.workflow.node.*;import com.fasterxml.jackson.annotation.JsonInclude;import com.fasterxml.jackson.databind.*;import org.yaml.snakeyaml.*;import org.yaml.snakeyaml.constructor.SafeConstructor;import org.yaml.snakeyaml.representer.Representer;import java.util.*;import java.io.*;import java.nio.charset.StandardCharsets;
class OriginalExportProjection { static final ObjectMapper objectMapper=new ObjectMapper();static {objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);}static byte[] project(Workflow workflow) throws Exception {objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,false);ByteArrayOutputStream outputStream=new ByteArrayOutputStream();'''
with tempfile.TemporaryDirectory(prefix='paiflow-original-yaml-') as folder:
 private=Path(folder);classes=private/'classes';classes.mkdir();copied=[]
 for p in dto:
  target=private/Path(p).name;shutil.copyfile(ROOT/p,target);assert sha(target)==sha(ROOT/p);copied.append(target)
 wrapper=private/'OriginalExportProjection.java';wrapper.write_text(prefix+segment+'\nreturn outputStream.toByteArray();}}',encoding='utf-8')
 input_file=private/'fixtures.json';input_file.write_text(json.dumps(fixtures,ensure_ascii=False),encoding='utf-8')
 command=['javac','-J-Dfile.encoding=UTF-8','-J-Dsun.stderr.encoding=UTF-8','-J-Duser.language=en','-encoding','UTF-8','-cp',cp,'-processorpath',str(jars[5]),'-processor','lombok.launch.AnnotationProcessorHider$AnnotationProcessor','-d',str(classes),*map(str,copied),str(wrapper),str(OUT/'WorkflowExportOriginalProjectionProbe.java')]
 compile_result=subprocess.run(command,cwd=private,capture_output=True,text=True,encoding='utf-8');(OUT/'工作流原YAML导出DTO-私有编译.log').write_text(compile_result.stdout+compile_result.stderr,encoding='utf-8');assert compile_result.returncode==0,compile_result.stderr
 run_result=subprocess.run(['java','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-cp',str(classes)+os.pathsep+cp,'WorkflowExportOriginalProjectionProbe',str(input_file)],cwd=private,capture_output=True,text=True,encoding='utf-8');(OUT/'工作流原YAML导出DTO-实际纯执行.log').write_text(run_result.stdout+run_result.stderr,encoding='utf-8');assert run_result.returncode==0,run_result.stderr
 actual=json.loads(run_result.stdout)
 wrapper_sha=sha(wrapper)
node_result=subprocess.run(['node',str(OUT/'WorkflowExportDownloadConsumerReview.cjs')],cwd=ROOT,capture_output=True,text=True,encoding='utf-8');(OUT/'工作流原YAML下载消费者AST-实际执行.log').write_text(node_result.stdout+node_result.stderr,encoding='utf-8');assert node_result.returncode==0,node_result.stderr
download=json.loads(node_result.stdout)
by={r['id']:r for r in actual};meta=by['empty-arrays-seven-metadata']['parsedYaml']['flowMeta'];assert set(meta)=={'name','description','avatarIcon','avatarColor','edgeType','category','advancedConfig','dslVersion'}
assert meta['dslVersion']=='v1' and isinstance(meta['advancedConfig'],str)
assert by['all-null-metadata-and-null-dto-lists']['parsedYaml']=={'flowMeta':{'dslVersion':'v1'},'flowData':{}}
assert by['root-unknown-viewport-dropped']['parsedYaml']['flowData']=={'nodes':[],'edges':[]}
closed=by['closed-node-data-edge-fields']['parsedYaml']['flowData'];assert 'futureNode' not in closed['nodes'][0] and 'futureData' not in closed['nodes'][0]['data'] and 'futureEdge' not in closed['edges'][0]
assert by['raw-json-null-root']['parsedYaml']['flowData'] is None
assert 'errorType' in by['nullable-node-edge-array-items'] and 'errorType' in by['invalid-typed-width']
assert by['private-fields-original-cleanup-commented']['parsedYaml']['flowData']['nodes'][0]['data']['nodeParam']['apiKey']=='FIXTURE_NOT_A_CREDENTIAL'
after={p:sha(ROOT/p) for p in paths};assert before==after
report={'schemaVersion':'paiflow.original-workflow-export-golden.v1','startedAt':started,'endedAt':datetime.now(timezone.utc).isoformat(),'scope':'原9完整DTO+原导出纯算法段+原/current下载函数AST技术golden，不执行原完整Service权限/Mapper/App/Provider/导入','sourceHashesBefore':before,'sourceHashesAfter':after,'sourceWindowChanges':[],'fixtures':fixtures,'actualOriginalProjection':actual,'actualProjectionCases':len(actual),'actualDownloadConsumer':download,'algorithmSegmentSha256':hashlib.sha256(segment.encode()).hexdigest(),'privateWrapperSha256':wrapper_sha,'cacheJars':[{'name':p.name,'sha256':sha(p)} for p in jars],
 'commands':{'javacExitCode':compile_result.returncode,'javaExitCode':run_result.returncode,'nodeExitCode':node_result.returncode,'privateTempOnly':True},
 'controllerSourceDerived':{'path':'/workflow/export/{id}','idType':'Long','contentType':'application/octet-stream','characterEncoding':'UTF-8','contentDisposition':"attachment; filename*=UTF-8''workflow-{id}.yaml",'sourceLines':[450,451,461,463,465],'actualControllerExecuted':False},
 'sourceVsApprovedCenterCompatibility':['原flowData只nodes/edges，DTO闭合层裁root viewport/future、node/data/schema未知字段；Object/JSONObject开放字段表现见实际case，不由FastJSON二次默认序列化推定。','中心已批准保存原画布未知字段/布局，未来export保未知/null是明确扩展保全，不能称原typed投影全部原样；执行DSL不可替代rawcanvas。','原private nodeParam scrub是注释，fixture apiKey确实被纯原投影带出。中心不得复制这个泄露边界，也不能把原算法称已脱敏。','空JSON null canvas产生flowData null；null node导致原循环NullPointerException，非Controller完整错误码证据。','原Controller download只有二进制不包装LegacyResult，前端helper成功HTTP200才下载caller filename，错误只message。'],
 'limitations':['完整原Service类未实例化，权限检查、BusinessException包装、Controller flush与HTTP均只核源码；只执行字节相同9DTO和原内部算法段。','Jackson2.17.2/FastJSON2.0.51/Lombok1.18.32与原声明一致；MyBatis注解3.5.5仅缓存compile-time旁证，原3.5.7不执行ORM；SnakeYAML2.5为当前缓存与root确定目标版本，不冒充原完整锁依赖闭环。','全部golden是可区分的私有fixture，不是原库/真实凭据/正式App。','下载AST只使用内存XHR/DOM/Blob/URL/store/token spies，无页面挂载、账号、网络和实际文件下载。','不写Maven/target/生产/前端/SQL/contracts/index，导入依赖正式App/ProtocolAdd/资源的独立大流程未执行。'],
 'toolSources':[{'path':p.relative_to(ROOT).as_posix(),'sha256':sha(p)} for p in [Path(__file__),OUT/'WorkflowExportOriginalProjectionProbe.java',OUT/'WorkflowExportDownloadConsumerReview.cjs']], 'businessVerified':False}
FINAL.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'path':FINAL.relative_to(ROOT).as_posix(),'sha256':sha(FINAL),'sourceCount':len(paths),'sourceWindowChanges':0,'projectionCases':len(actual),'downloadCasesEach':download['caseCountEach'],'businessVerified':False},ensure_ascii=False))
