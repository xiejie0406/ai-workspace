"""根唯一Maven入口：只定向复验非法ID的Spring安全inline响应合同；其他51项沿用同批真实执行；每次保全旧报告与实际源窗口。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib, json, re, shutil, subprocess, sys, time, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[6]
MODULE = ROOT / 'ruoyi-backend/ruoyi-paiflow'
EVIDENCE = ROOT / '文档/项目/PaiFlow工作流项目/证据/本地源码检查'
SELECTED = {'LegacyWorkflowExportContractTest'}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def sources():
    return sorted(set([MODULE / 'pom.xml',
        *[p for group in ('src/main', 'src/test', 'sql') for p in (MODULE / group).rglob('*') if p.is_file()],
        *[p for p in (ROOT / 'contracts/paiflow').rglob('*') if p.is_file()]]))

def main():
    label, gate_name = sys.argv[1:3]
    assert re.fullmatch(r'[\w-]{4,100}', label)
    gate = json.loads((EVIDENCE / 'checks' / gate_name).read_text('utf-8-sig'))
    for item in gate['inputs']:
        assert sha(ROOT / item['path']) == item['sha256'], f'源码冻结变化: {item["path"]}'
    names = {key: EVIDENCE / (label + suffix) for key, suffix in [
        ('inputs', '输入.json'), ('result', '结果.json'), ('execution', '执行.json'),
        ('log', '.log'), ('old', '检查前JUnit快照'), ('new', '本次JUnit快照')]}
    for path in names.values():
        assert not path.exists(), f'不可覆盖旧证据: {path}'
    classes, expected = set(), set()
    for path in (MODULE / 'src/test/java').rglob('*Test.java'):
        if path.stem not in SELECTED:
            continue
        text = path.read_text('utf-8-sig')
        package = re.search(r'^package\s+([^;]+);', text, re.M)[1]
        methods = re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\s*\(', text)
        methods = [method for method in methods if method == 'malformedAndOverflowPathRejectBeforeServiceEvenWithGlobalAdvice']
        assert methods, f'候选类缺实际测试方法: {path}'
        classes.add(package + '.' + path.stem)
        expected.update((package + '.' + path.stem, method) for method in methods)
    assert len(classes) == len(SELECTED)
    before = {p.relative_to(ROOT).as_posix(): sha(p) for p in sources()}
    save(names['inputs'], {'files': before, 'sourceInputCount': len(before),
         'expectedTests': len(expected), 'expectedCases': [list(case) for case in sorted(expected)],
         'realDbWrites': False, 'businessVerified': False})
    reports = MODULE / 'target/surefire-reports'
    names['old'].mkdir()
    prior = {}
    if reports.exists():
        for path in reports.iterdir():
            if path.is_file():
                prior[path.name] = sha(path)
                shutil.copy2(path, names['old'] / path.name)
    for filename in ('pure-iteration-debug-actual-response.json', 'workflow-dialog-actual-response.json', 'workflow-dialog-budget-actual-evidence.json', 'workflow-export-actual-response.json'):
        path = MODULE / 'target' / filename
        if path.exists():
            prior['target/' + filename] = sha(path)
            shutil.copy2(path, names['old'] / filename)
    save(names['old'] / '输入.json', {'files': prior})
    command = [r'D:\java-i-exe\apache-maven-3.9.15\bin\mvn.cmd', '-o', '-pl', 'ruoyi-paiflow', '-am',
               '-Dtest=' + ','.join(c + '#malformedAndOverflowPathRejectBeforeServiceEvenWithGlobalAdvice' for c in sorted(classes)), '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
    start = datetime.now(timezone.utc).isoformat()
    since = time.time_ns()
    save(names['execution'], {'status': 'running', 'startedAt': start, 'command': command})
    print(json.dumps({'status': 'running', 'sourceInputCount': len(before), 'expectedTests': len(expected),
                      'classes': len(classes)}, ensure_ascii=False), flush=True)
    with names['log'].open('wb') as stream:
        process = subprocess.run(command, cwd=ROOT / 'ruoyi-backend', stdout=stream, stderr=subprocess.STDOUT)
    ended = datetime.now(timezone.utc).isoformat()
    after = {p.relative_to(ROOT).as_posix(): sha(p) for p in sources()}
    changes = [name for name in sorted(set(before) | set(after)) if before.get(name) != after.get(name)]
    names['new'].mkdir()
    suites, cases = [], []
    for path in sorted(reports.glob('TEST-com.ruoyi.paiflow.*.xml')):
        if path.stat().st_mtime_ns < since:
            continue
        suite = ET.parse(path).getroot()
        suites.append({'name': suite.get('name'), **{key: int(suite.get(key, 0)) for key in
                        ('tests', 'failures', 'errors', 'skipped')}, 'sha256': sha(path)})
        for case in suite.findall('testcase'):
            outcome = ('failed' if case.find('failure') is not None else 'error' if case.find('error') is not None
                       else 'skipped' if case.find('skipped') is not None else 'passed')
            cases.append({'class': case.get('classname'), 'name': case.get('name'), 'outcome': outcome})
        shutil.copy2(path, names['new'] / path.name)
        text_report = reports / (suite.get('name') + '.txt')
        if text_report.exists():
            shutil.copy2(text_report, names['new'] / text_report.name)
    actual = {(case['class'], case['name']) for case in cases}
    missing, unexpected = sorted(expected - actual), sorted(actual - expected)
    totals = {key: sum(suite[key] for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    success = (process.returncode == 0 and not changes and not missing and not unexpected
               and totals['tests'] == len(expected) and not any(totals[key] for key in ('failures', 'errors', 'skipped')))
    result = {'status': 'technical_checks_passed' if success else 'technical_checks_not_passed',
              'startedAt': start, 'endedAt': ended, 'exitCode': process.returncode, 'totals': totals,
              'suites': suites, 'cases': cases, 'expectedTests': len(expected), 'missingCases': missing,
              'unexpectedCases': unexpected, 'sourceWindowChanges': changes, 'sourceInputCount': len(before),
              'realDbWrites': False, 'businessVerified': False, 'migrationComplete': False}
    save(names['result'], result)
    save(names['execution'], {'status': 'completed', 'startedAt': start, 'endedAt': ended, 'command': command,
                             'exitCode': process.returncode, 'logSha256': sha(names['log']), 'sourceWindowChanges': changes})
    print(json.dumps({key: result[key] for key in ('status', 'totals', 'sourceWindowChanges', 'missingCases', 'unexpectedCases')}, ensure_ascii=False))
    raise SystemExit(0 if success else 1)

if __name__ == '__main__':
    main()
