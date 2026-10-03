"""根唯一Maven入口；首554有1断言与3错误，修正Bridge入站数组及错误断言后仅重跑受影响4类，冻结源窗口并保全每次失败/旧报告。"""
from pathlib import Path
from datetime import datetime,timezone
import hashlib,json,re,shutil,subprocess,sys,time,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[6]
MODULE=ROOT/'ruoyi-backend/ruoyi-paiflow'
EVIDENCE=ROOT/'文档/项目/PaiFlow工作流项目/证据/本地源码检查'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def sources():return sorted(set([MODULE/'pom.xml',*[p for group in ('src/main','src/test','sql') for p in (MODULE/group).rglob('*') if p.is_file()],*[p for p in (ROOT/'contracts/paiflow').rglob('*') if p.is_file()]]))
def main():
    label=sys.argv[1];assert re.fullmatch(r'[\w-]{4,100}',label)
    names={kind:EVIDENCE/(label+suffix) for kind,suffix in [('inputs','输入.json'),('result','结果.json'),('execution','执行.json'),('log','.log'),('old','检查前JUnit快照'),('new','本次JUnit快照')]}
    for p in names.values():assert not p.exists(),f'不可覆盖旧证据 {p}'
    gate=json.loads((EVIDENCE/'纯迭代调试流首轮局部修复源码冻结输入.json').read_text('utf-8-sig'))
    for item in gate['inputs']:assert sha(ROOT/item['path'])==item['sha256'],f'综合源码冻结变化 {item["path"]}'
    expected=set();classes=set()
    selected={'LegacyWorkflowStreamCodecTest','LegacyDebugBridgePersistenceTest','LegacyDebugStreamPersistenceTest','LegacyPureIterationDebugPersistenceTest'}
    for p in (MODULE/'src/test/java').rglob('*Test.java'):
        if p.stem not in selected:continue
        s=p.read_text('utf-8-sig');package=re.search(r'^package\s+([^;]+);',s,re.M)[1];methods=re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\s*\(',s)
        if methods:classes.add(package+'.'+p.stem)
        expected.update((package+'.'+p.stem,m) for m in methods)
    before={p.relative_to(ROOT).as_posix():sha(p) for p in sources()}
    save(names['inputs'],{'files':before,'sourceInputCount':len(before),'expectedTests':len(expected),'expectedCases':[list(v) for v in sorted(expected)],'realDbWrites':False,'businessVerified':False})
    reports=MODULE/'target/surefire-reports';names['old'].mkdir();prior={}
    if reports.exists():
        for p in reports.iterdir():
            if p.is_file():prior[p.name]=sha(p);shutil.copy2(p,names['old']/p.name)
    save(names['old']/'输入.json',{'files':prior})
    command=[r'D:\java-i-exe\apache-maven-3.9.15\bin\mvn.cmd','-o','-pl','ruoyi-paiflow','-am','-Dtest='+','.join(sorted(classes)),'-Dsurefire.failIfNoSpecifiedTests=false','test']
    start=datetime.now(timezone.utc).isoformat();ns=time.time_ns();save(names['execution'],{'status':'running','startedAt':start,'command':command})
    print(json.dumps({'status':'running','sourceInputCount':len(before),'expectedTests':len(expected),'classes':len(classes)},ensure_ascii=False),flush=True)
    with names['log'].open('wb') as stream:process=subprocess.run(command,cwd=ROOT/'ruoyi-backend',stdout=stream,stderr=subprocess.STDOUT)
    ended=datetime.now(timezone.utc).isoformat();after={p.relative_to(ROOT).as_posix():sha(p) for p in sources()};changes=[k for k in sorted(set(before)|set(after)) if before.get(k)!=after.get(k)]
    names['new'].mkdir();suites=[];cases=[]
    for p in sorted(reports.glob('TEST-com.ruoyi.paiflow.*.xml')):
        if p.stat().st_mtime_ns<ns:continue
        tree=ET.parse(p).getroot();suites.append({'name':tree.get('name'),**{field:int(tree.get(field,0)) for field in ('tests','failures','errors','skipped')},'sha256':sha(p)})
        for case in tree.findall('testcase'):
            outcome='failed' if case.find('failure') is not None else 'error' if case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            cases.append({'class':case.get('classname'),'name':case.get('name'),'outcome':outcome})
        shutil.copy2(p,names['new']/p.name);txt=reports/(tree.get('name')+'.txt')
        if txt.exists():shutil.copy2(txt,names['new']/txt.name)
    actual={(v['class'],v['name']) for v in cases};missing=sorted(expected-actual);unexpected=sorted(actual-expected)
    totals={f:sum(v[f] for v in suites) for f in ('tests','failures','errors','skipped')};success=process.returncode==0 and not changes and not missing and not unexpected and totals['tests']==len(expected) and not any(totals[f] for f in ('failures','errors','skipped'))
    result={'status':'technical_checks_passed' if success else 'technical_checks_not_passed','startedAt':start,'endedAt':ended,'exitCode':process.returncode,'totals':totals,'suites':suites,'cases':cases,'expectedTests':len(expected),'missingCases':missing,'unexpectedCases':unexpected,'sourceWindowChanges':changes,'sourceInputCount':len(before),'realDbWrites':False,'businessVerified':False,'migrationComplete':False}
    save(names['result'],result);save(names['execution'],{'status':'completed','startedAt':start,'endedAt':ended,'command':command,'exitCode':process.returncode,'logSha256':sha(names['log']),'sourceWindowChanges':changes});print(json.dumps({k:result[k] for k in ('status','totals','sourceWindowChanges','missingCases','unexpectedCases')},ensure_ascii=False));raise SystemExit(0 if success else 1)
if __name__=='__main__':main()
