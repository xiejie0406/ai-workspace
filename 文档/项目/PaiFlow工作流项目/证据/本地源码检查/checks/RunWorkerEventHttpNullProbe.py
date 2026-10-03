"""只读当前Controller与classpath；私有javac/MockMVC反例，不写共享target或跑Maven。"""
from datetime import datetime,timezone
import base64,hashlib,json,os,shutil,subprocess,sys,tempfile,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent
SOURCE=ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/controller/WorkerRunController.java'
PROBE=OUT/'WorkerEventHttpNullProbe.java'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def entry(p):return {'path':p.relative_to(ROOT).as_posix(),'sha256':sha(p)}
def main():
    repaired='--repaired' in sys.argv;name='Worker事件HTTP显式空值修复独立复验' if repaired else 'Worker事件HTTP显式空值首反例'
    target=OUT/(name+'.json');assert not target.exists(),'不得覆盖前一真实证据'
    report=ROOT/'ruoyi-backend/ruoyi-paiflow/target/surefire-reports/TEST-com.ruoyi.paiflow.controller.WorkerRunControllerTest.xml'
    cp=next(p.attrib['value'] for p in ET.parse(report).getroot().find('properties') if p.attrib.get('name')=='java.class.path')
    paths=[Path(p) for p in cp.split(os.pathsep)];assert all(p.exists() for p in paths)
    loaded=[SOURCE,PROBE,report,ROOT/'ruoyi-backend/ruoyi-paiflow/target/classes/com/ruoyi/paiflow/service/RunJsonBoundary.class',ROOT/'ruoyi-backend/ruoyi-paiflow/target/classes/com/ruoyi/paiflow/service/RunLeaseService.class',ROOT/'ruoyi-backend/ruoyi-paiflow/target/classes/com/ruoyi/paiflow/service/WorkerIdentityService.class']
    before={str(p.relative_to(ROOT)):sha(p) for p in loaded}
    original=OUT/'Worker事件HTTP空值入口修改前原文/WorkerRunController.java'
    if not repaired:
        assert not original.exists();original.parent.mkdir(parents=True);shutil.copyfile(SOURCE,original)
    else:assert original.exists() and sha(original)!=sha(SOURCE),'须原入口实际局部修复后复验'
    started=datetime.now(timezone.utc).isoformat()
    with tempfile.TemporaryDirectory(prefix='paiflow-worker-http-null-') as folder:
        private=Path(folder);classes=private/'classes';classes.mkdir();controller=private/'WorkerRunController.java';shutil.copyfile(SOURCE,controller)
        assert sha(controller)==sha(SOURCE)
        compile_cmd=['javac','-encoding','UTF-8','-parameters','-cp',cp,'-d',str(classes),str(controller),str(PROBE)]
        compiled=subprocess.run(compile_cmd,cwd=private,capture_output=True,text=True,encoding='utf-8')
        (OUT/(name+'-私有编译.log')).write_text(compiled.stdout+compiled.stderr,encoding='utf-8');assert compiled.returncode==0,compiled.stderr
        run_cmd=['java','--class-path',str(classes)+os.pathsep+cp,'WorkerEventHttpNullProbe']+(['repaired'] if repaired else [])
        executed=subprocess.run(run_cmd,cwd=private,capture_output=True,text=True,encoding='utf-8')
        (OUT/(name+'-实际MVC.log')).write_text(executed.stdout+executed.stderr,encoding='utf-8');assert executed.returncode==0,executed.stderr
    rows=[line.removeprefix('WORKER_HTTP_NULL_PROBE=') for line in executed.stdout.splitlines() if line.startswith('WORKER_HTTP_NULL_PROBE=')];assert len(rows)==1
    observation=json.loads(rows[0])
    if repaired:
        # Java探针和固定raw请求逐字未改；额外从capture原字节核present且值为null。
        captured=json.loads(base64.b64decode(observation['appendArgumentBase64']))
        frame=captured['payload']['legacyFrame'];node=frame['workflow_step']['node'];choice=frame['choices'][0]
        paths=[(node['outputs'],'value'),(captured['payload']['metadata']['nested'],'nullable'),(node,'finish_reason'),(choice,'finish_reason'),(choice['delta'],'content')]
        assert all(key in obj and obj[key] is None for obj,key in paths)
        observation['deepExplicitNullValuesVerified']=5
    after={str(p.relative_to(ROOT)):sha(p) for p in loaded};assert before==after
    result={'schemaVersion':'paiflow.worker-http-null-probe.v1','startedAt':started,'endedAt':datetime.now(timezone.utc).isoformat(),'status':'repair_same_probe_checked' if repaired else 'confirmed_actual_http_null_loss','source':entry(SOURCE),'preservedOriginal':entry(original),'probe':entry(PROBE),'sourcesAndClasspathReportsBefore':before,'sourcesAndClasspathReportsAfter':after,'sourceWindowChanges':[],'classpathSource':entry(report),'classpathEntries':len(paths),'classpathSha256':hashlib.sha256(cp.encode()).hexdigest(),'compile':{'commandShape':['javac','-encoding','UTF-8','-parameters','-cp','<read-only actual surefire classpath>','-d','<privateTemp/classes>','<byte-identical current WorkerRunController.java>','WorkerEventHttpNullProbe.java'],'exitCode':compiled.returncode,'log':entry(OUT/(name+'-私有编译.log'))},'execution':{'commandShape':['java','--class-path','<privateTemp/classes first>'+os.pathsep+'<read-only actual surefire classpath>','WorkerEventHttpNullProbe']+(['repaired'] if repaired else []),'exitCode':executed.returncode,'log':entry(OUT/(name+'-实际MVC.log'))},'observation':observation,'noMaven':True,'targetWritten':False,'noDatabase':True,'network':False,'servicesStarted':False,'businessVerified':False,'limits':['真实Boot4/Jackson3 converter+MockMVC请求绑定+逐字当前Controller执行；private classes优先共享target旧Controller，身份与RunLease明确mock。','仅capture append入口参数的null/0/false；不证明Worker授权、租约、run状态、持久化、SSE或页面通过。','当前RunLease编译class只用于Mockito方法类型，实际业务方法没有执行；不以正在修改的RunLease源码冒充已编译证明。','依赖classpath仅读现surefire报告与已有jar，不安装、不启动HTTP、不调用SDK/外部服务；privateTemp由工具正常清理。']}
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'proof':entry(target),'source':entry(SOURCE),'original':entry(original),'status':result['status'],'observation':observation,'sourceWindowChanges':[]},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
