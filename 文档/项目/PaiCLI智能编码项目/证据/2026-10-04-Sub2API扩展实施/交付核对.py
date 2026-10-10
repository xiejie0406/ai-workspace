"""只读核对当前源码、隔离构建和本次日志；生成本项目技术证据，不访问真实业务。"""
from pathlib import Path
from zipfile import ZipFile
import hashlib,json,re,shutil,html
here=Path(__file__).resolve().parent
root=here.parents[4]
runtime=root/'.runtime/paicli/sub2api-20261004'
def digest(data):return hashlib.sha256(data).hexdigest()
checks=[]
modules=[('paicli-core','native'),('paicli-runner','native'),('paicli-cli','native'),('paicli-api','center/ruoyi-paicli')]
artifacts=[]
for name,area in modules:
    live=root/'ruoyi-backend/ruoyi-paicli'/name/'src'
    frozen=runtime/area/name/'src'
    differences=[];manifest=[]
    for file in sorted(live.rglob('*')):
        if not file.is_file():continue
        relative=file.relative_to(live);other=frozen/relative;data=file.read_bytes()
        manifest.append({'path':str(relative),'sha256':digest(data)})
        if not other.exists() or other.read_bytes()!=data:differences.append(str(relative))
    target=runtime/area/name/'target';jar=target/f'{name}-3.9.2.jar'
    with ZipFile(jar) as archive:
        classes=[];missing=[]
        for file in (target/'classes').rglob('*.class'):
            relative=file.relative_to(target/'classes').as_posix();classes.append(relative)
            if relative not in archive.namelist() or archive.read(relative)!=file.read_bytes():missing.append(relative)
    checks.append({'module':name,'sourceFiles':len(manifest),'sourceDifferences':differences,'compiledClasses':len(classes),'jarClassDifferences':missing})
    (here/f'{name}-源码摘要.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    artifacts.append({'path':str(jar.relative_to(root)),'sha256':digest(jar.read_bytes()),'bytes':jar.stat().st_size})
logs={
 'core-tests-initial.log':'Core测试-初次.log','center-tests-initial.log':'中心测试-首次失败.log','center-tests-second.log':'中心测试-轮换校验修复前.log',
 'frontend-tests.log':'前端测试-首次夹具失败.log','core-tests-final.log':'Core测试-最终.log','center-tests-final.log':'中心测试-最终.log',
 'runner-cli-tests-delivery.log':'Runner与CLI测试-最终.log','frontend-tests-delivery.log':'前端测试-最终.log','frontend-type-delivery.log':'前端类型检查-最终.log',
 'native-package-delivery.log':'Core与Runner及CLI构建.log','center-package.log':'中心模块构建.log','frontend-build-package.log':'前端隔离构建.log','contracts-final.log':'契约检查.log'
}
for source,destination in logs.items():shutil.copy2(runtime/source,here/destination)
counts={}
for name,area in modules:
    report=runtime/area/name/'target/surefire-reports'
    selected={'paicli-core':['Sub2ApiClientTest','LlmClientFactoryTest','ModelDiscoveryTest','ModelProfileTest','AbstractOpenAiCompatibleClientRetryTest'],
              'paicli-runner':['ManagedSub2ApiProviderTest','RunnerBoundaryTest'],'paicli-cli':['ModelCommandsTest'],
              'paicli-api':['PcCenterPersistenceTest','PcSub2ApiPolicyTest']}[name]
    import xml.etree.ElementTree as ET
    totals={key:0 for key in ['tests','failures','errors','skipped']}
    for suite in selected:
        file=next(report.glob(f'TEST-*.{suite}.xml'));attrs=ET.parse(file).getroot().attrib
        for key in totals:totals[key]+=int(attrs[key])
    counts[name]=totals
front=(runtime/'frontend-tests-delivery.log').read_text(encoding='utf-8-sig',errors='replace')
assert re.search(r'Tests\s+11 passed',re.sub(r'\x1b\[[0-9;]*m','',front))
for check in checks:assert not check['sourceDifferences'] and not check['jarClassDifferences'],check
for name,area in modules:assert not any(counts[name][key] for key in ['failures','errors','skipped']),counts[name]
dist=runtime/'frontend-dist-delivery';assert (dist/'index.html').exists()
frontend_manifest=[{'path':f.relative_to(dist).as_posix(),'sha256':digest(f.read_bytes())} for f in sorted(dist.rglob('*')) if f.is_file()]
(here/'前端制品摘要.json').write_text(json.dumps(frontend_manifest,ensure_ascii=False,indent=2),encoding='utf-8')
result={'date':'2026-10-04','scope':'PaiCLI本地Sub2API文本扩展；本地合成测试，不代表真实实例或业务验收',
        'checks':checks,'tests':counts,'frontendTests':11,'totalTests':sum(v['tests'] for v in counts.values())+11,
        'artifacts':artifacts,'frontendArtifact':{'path':str(dist.relative_to(root)),'files':len(frontend_manifest)},
        'realModelCalls':0,'businessVerified':False,'runningPackageUpdated':False,'independentReviewCompleted':False}
(here/'交付核对.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'sourceDifferences':sum(len(c['sourceDifferences']) for c in checks),'jarClassDifferences':sum(len(c['jarClassDifferences']) for c in checks),'tests':result['totalTests'],'frontendFiles':len(frontend_manifest)},ensure_ascii=False))
