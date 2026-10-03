"""privateTemp编译逐字当前Service，仅原8DTO字段/默认值及private投影；不执行业务Service。"""
from pathlib import Path
from datetime import datetime,timezone
import hashlib,json,os,subprocess,tempfile,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent
FINAL=OUT/'工作流YAML导出当前投影独立原合同核对.json';assert not FINAL.exists(),'冻结证据不得覆写'
SOURCE=ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowExportService.java'
PROBE=OUT/'WorkflowExportCurrentProjectionProbe.java';GOLDEN=OUT/'工作流原YAML导出合同金样.json'
XML=ROOT/'ruoyi-backend/ruoyi-paiflow/target/surefire-reports/TEST-com.ruoyi.paiflow.service.WorkflowDialogPersistenceTest.xml'
original=ROOT/'参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/biz/workflow'
dtos=[original/'BizWorkflowData.java',original/'BizWorkflowNode.java',original/'BizWorkflowEdge.java',*[original/'node'/f'{name}.java' for name in ['BizNodeData','BizInputOutput','BizSchema','BizProperty','BizValue']]]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def entry(p):return {'path':p.relative_to(ROOT).as_posix(),'sha256':sha(p)}
paths=[SOURCE,PROBE,GOLDEN,XML,*dtos];before={p.relative_to(ROOT).as_posix():sha(p) for p in paths}
assert sha(SOURCE)=='9706d260c26966ce9622032873a389f7e1bc900ebc4397fbeaa7929d5cc6b3e7','已声明当前冻结'
cp=next(p.attrib['value'] for p in ET.parse(XML).getroot().find('properties') if p.attrib.get('name')=='java.class.path')
entries=[Path(p) for p in cp.split(os.pathsep)];assert all(p.exists() for p in entries)
lombok=Path('C:/Users/admin/.m2/repository/org/projectlombok/lombok/1.18.32/lombok-1.18.32.jar');assert lombok.is_file()
cp=os.pathsep.join(p.as_posix() for p in [*entries,lombok]);started=datetime.now(timezone.utc).isoformat()
with tempfile.TemporaryDirectory(prefix='paiflow-export-current-project-') as folder:
 private=Path(folder);classes=private/'classes';classes.mkdir()
 compile_args=['-encoding','UTF-8','-cp',cp,'-processorpath',lombok.as_posix(),'-processor','lombok.launch.AnnotationProcessorHider$AnnotationProcessor','-d',classes.as_posix(),SOURCE.as_posix(),PROBE.as_posix(),*[p.as_posix() for p in dtos]]
 af=private/'compile.args';af.write_text('\n'.join(json.dumps(a,ensure_ascii=False) for a in compile_args),encoding='utf-8')
 compiled=subprocess.run(['javac','-J-Dfile.encoding=UTF-8','-J-Dsun.stderr.encoding=UTF-8','-J-Duser.language=en','@'+af.as_posix()],capture_output=True,cwd=private);(OUT/'工作流YAML导出当前投影-私有编译.log').write_bytes(compiled.stdout+compiled.stderr);assert compiled.returncode==0,compiled.stderr.decode('utf-8',errors='replace')
 run_args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-classpath',classes.as_posix()+os.pathsep+cp,'WorkflowExportCurrentProjectionProbe',GOLDEN.as_posix()]
 rf=private/'execute.args';rf.write_text('\n'.join(json.dumps(a,ensure_ascii=False) for a in run_args),encoding='utf-8')
 executed=subprocess.run(['java','@'+rf.as_posix()],capture_output=True,cwd=private);(OUT/'工作流YAML导出当前投影-实际纯执行.log').write_bytes(executed.stdout+executed.stderr);assert executed.returncode==0,executed.stderr.decode('utf-8',errors='replace')
 lines=[line.removeprefix('EXPORT_CURRENT_PROJECTION=') for line in executed.stdout.decode('utf-8').splitlines() if line.startswith('EXPORT_CURRENT_PROJECTION=')];assert len(lines)==1
 actual=json.loads(lines[0])
golden=json.loads(GOLDEN.read_text(encoding='utf-8'));old={r['id']:r for r in golden['actualOriginalProjection']};now={r['id']:r for r in actual['currentCases']}
def known(actual,expected):
 if isinstance(expected,dict):
  assert isinstance(actual,dict);assert set(expected)<=set(actual)
  return {k:known(actual[k],v) for k,v in expected.items()}
 if isinstance(expected,list):
  assert isinstance(actual,list) and len(actual)==len(expected)
  return [known(a,b) for a,b in zip(actual,expected)]
 assert actual==expected and type(actual)==type(expected),(actual,expected)
 return actual
ids=['empty-arrays-seven-metadata','typed-node-width-boolean-string-coercion','typed-property-default-coercion','schema-default-item-value-and-property']
for i in ids:assert known(now[i]['currentProject'],old[i]['parsedYaml']['flowData'])==old[i]['parsedYaml']['flowData']
assert len(actual['dtoPairs'])==8 and all(d['exactFieldsAndInitialValuesMatch'] for d in actual['dtoPairs'])
after={p.relative_to(ROOT).as_posix():sha(p) for p in paths};assert after==before
report={'schemaVersion':'paiflow.export-current-original-projection-review.v1','startedAt':started,'endedAt':datetime.now(timezone.utc).isoformat(),'sourceHashesBefore':before,'sourceHashesAfter':after,'sourceWindowChanges':[],
 'originalGolden':entry(GOLDEN),'privateCurrentCompiledSource':entry(SOURCE),'exactDtoPairs':8,'allOriginalAndCurrentDtoInitialValuesNull':True,'knownProjectionCasesMatchedOriginal':ids,'actual':actual,
 'comparisonRule':'known视图只取原实际投影的keys；当前批准保未知字段/显式null不假称原完全相等。叶子value与Python type同核，数组长/顺序相同。',
 'compileExitCode':compiled.returncode,'executeExitCode':executed.returncode,'classpathOrigin':entry(XML),'classpathEntries':len(entries),'lombokSha256':sha(lombok),'privateHarnessFirstFailure':'工作流YAML导出当前投影-参数文件首失败.log：javac的-J选项不能置@argfile，移至命令参数后复验，未改变生产源/原golden。',
 'limits':['编译实际当前Service整类与字节未改原8DTO，但不构造WorkflowExportService、不调用export/授权/Mapper/数据库/Run/App/Provider。','只反射执行private project和原/current DTO字段初值；不能据此证明公共export允许rawJSONnull/nullnodes或秘密包，这些入口有独立真实校验。','4个known投影对原8e7b实际golden，允许的unknown/null扩展只在比较中显式选原keys，不把扩展说成原NON_NULL同形。','不执行Yaml dump、Controller或下载；原Yaml字节golden及实际H2/MVC将另列。','原Service/Jackson/FastJSON2.0.51执行已有冻结golden；本次用实际当前Maven类路径FastJSON，原DTO仅反射字段/初值。','只私有Temp与checks写入，无Maven/target/source/contracts/前端/SQL写。'],
 'businessVerified':False}
FINAL.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'path':FINAL.relative_to(ROOT).as_posix(),'sha256':sha(FINAL),'sourceCount':len(paths),'sourceWindowChanges':0,'exactDtoPairs':8,'knownProjectionCases':len(ids)},ensure_ascii=False))
