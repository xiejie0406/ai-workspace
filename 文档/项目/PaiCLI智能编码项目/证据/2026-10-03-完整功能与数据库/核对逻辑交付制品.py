"""仅核对源码、编译制品、资源与SPI，不加载/运行应用或测试。"""
from pathlib import Path
from datetime import datetime
import hashlib, json, re, zipfile, fnmatch, xml.etree.ElementTree as ET
E=Path(__file__).resolve().parent
ROOT=E.parents[4]
record=json.loads((E/'逻辑交付最终源码冻结.json').read_text(encoding='utf-8'))
B=Path(record['build'])
def digest(data): return hashlib.sha256(data).hexdigest()
drift=[]; snapshot_drift=[]
for f in record['files']:
    if digest((ROOT/f['source']).read_bytes())!=f['sha256']: drift.append(f['source'])
    content=(ROOT/f['source']).read_bytes()
    if f['snapshot']=='native/pom.xml': content=content.decode('utf-8').replace('    <module>paicli-api</module>','').encode('utf-8')
    elif f['snapshot']=='center/pom.xml': content=re.sub(r'<modules>.*?</modules>','<modules><module>ruoyi-common</module><module>ruoyi-system</module><module>ruoyi-framework</module><module>ruoyi-paicli/paicli-api</module></modules>',content.decode('utf-8'),count=1,flags=re.S).encode('utf-8')
    if (B/f['snapshot']).read_bytes()!=content: snapshot_drift.append(f['snapshot'])
outputs=[]
targets=[('核心','native/paicli-core','paicli-core-3.9.2.jar'),('Runner','native/paicli-runner','paicli-runner-3.9.2.jar'),('CLI','native/paicli-cli','paicli-cli-3.9.2.jar'),('Eval','native/paicli-eval','paicli-eval-3.9.2.jar'),('Eval thin','native/paicli-eval','paicli-eval-3.9.2-agentbench-runner.jar'),('中心API','center/ruoyi-paicli/paicli-api','paicli-api-3.9.2.jar')]
for label, module, name in targets:
    folder=B/module; jar=folder/'target'/name; classes=folder/'target/classes'; failures=[]; major=set(); total=0; resource_count=0
    includes=[]
    if label=='Eval thin':
        pom=ET.parse(folder/'pom.xml'); ns={'m':'http://maven.apache.org/POM/4.0.0'}
        execution=next(e for e in pom.findall('.//m:execution',ns) if e.findtext('m:id',namespaces=ns)=='agentbench-trusted-runner')
        includes=[e.text for e in execution.findall('m:configuration/m:includes/m:include',ns)]
    def selected(relative):return not includes or any(fnmatch.fnmatchcase(relative,p) for p in includes)
    with zipfile.ZipFile(jar) as z:
        own='com/ruoyi/paicli' if label=='中心API' else 'com/paicli'
        for file in (classes/own).rglob('*.class'):
            data=file.read_bytes(); relative=file.relative_to(classes).as_posix()
            if not selected(relative):continue
            total+=1; major.add(int.from_bytes(data[6:8],'big'))
            if relative not in z.namelist() or z.read(relative)!=data: failures.append(relative)
        # 已迁移SQL、schema、内置原生资产都必须与本次冻结的资源一致。
        for file in (folder/'src/main/resources').rglob('*'):
            if not file.is_file():continue
            relative=file.relative_to(folder/'src/main/resources').as_posix()
            if not selected(relative):continue
            if relative.startswith('META-INF/services/'):continue # shade合并策略另行读取，不逐字要求相等。
            resource_count+=1
            if relative not in z.namelist() or z.read(relative)!=file.read_bytes():failures.append('resource:'+relative)
        spi={n:z.read(n).decode('utf-8') for n in z.namelist() if n.startswith('META-INF/services/com.paicli')}
        manifest=z.read('META-INF/MANIFEST.MF').decode('utf-8')
        if includes:
            failures.extend('unexpected:'+n for n in z.namelist() if n.endswith('.class') and not selected(n))
    outputs.append({'label':label,'path':str(jar),'sha256':digest(jar.read_bytes()),'bytes':jar.stat().st_size,'compiledOwnClasses':total,'ownClassMajorVersions':sorted(major),'checkedResources':resource_count,'pomIncludes':includes,'differences':failures,'spi':spi,'manifest':manifest})
front=B/'frontend-dist'; frontend=[]
for file in sorted(front.rglob('*')):
    if file.is_file():frontend.append({'path':file.relative_to(front).as_posix(),'bytes':file.stat().st_size,'sha256':digest(file.read_bytes())})
logs={name:{'sha256':digest((E/name).read_bytes()),'success':b'BUILD SUCCESS' in (E/name).read_bytes(),'testCompileSkipped':b'Not compiling test sources' in (E/name).read_bytes(),'testsSkipped':b'Tests are skipped' in (E/name).read_bytes()} for name in ('全链路逻辑交付-Native最终打包.log','全链路逻辑交付-中心最终打包.log')}
tools=json.loads((ROOT/'ruoyi-backend/ruoyi-paicli/paicli-api/src/main/resources/paicli/tools-v1.json').read_text(encoding='utf-8'))
result={'recordedAt':datetime.now().astimezone().isoformat(),'purpose':'编译与制品一致性；不是运行/业务验收','build':str(B),'frozenProductionFiles':len(record['files']),'workspaceDifferences':drift,'snapshotDifferences':snapshot_drift,'artifacts':outputs,'frontendFiles':len(frontend),'frontendOutput':str(front),'frontendManifest':frontend,'buildLogs':logs,'toolNames':[t['name'] for t in tools],'noTestsExecuted':True,'noApplicationStarted':True,'noRuntimeSynchronized':True,'businessPassedThisRun':0}
(E/'逻辑交付制品核对.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
ok=not drift and not snapshot_drift and all(not a['differences'] and a['compiledOwnClasses']>0 and a['ownClassMajorVersions']==[61] for a in outputs) and all(v['success'] and v['testCompileSkipped'] and v['testsSkipped'] for v in logs.values()) and (front/'index.html').is_file() and len(tools)==17
print(json.dumps({'consistent':ok,'sourceFiles':len(record['files']),'sourceDrift':drift,'snapshotDrift':snapshot_drift,'artifacts':[{'label':a['label'],'classCount':a['compiledOwnClasses'],'differences':a['differences']} for a in outputs],'frontendFiles':len(frontend),'tools':len(tools)},ensure_ascii=False))
if not ok:raise SystemExit(1)
