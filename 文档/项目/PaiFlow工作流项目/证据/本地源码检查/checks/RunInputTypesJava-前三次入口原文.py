"""输入类型同步冻结后的唯一 Maven 定向入口；保留旧QA报告。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[6]
MODULE = ROOT / 'ruoyi-backend/ruoyi-paiflow'
EVIDENCE = ROOT / '文档/项目/PaiFlow工作流项目/证据/本地源码检查'
MAVEN = r'D:\java-i-exe\apache-maven-3.9.15\bin\mvn.cmd'
CLASSES = {'AgentPersistenceTest', 'AgentInputTypesPersistenceTest', 'LegacyAgentContractTest', 'WorkflowInputTypesPolicyTest'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    repair = '--repair' in sys.argv[1:]
    fixture = '--fixture' in sys.argv[1:]
    assert not (repair and fixture), '检查模式不得混用'
    prefix = '输入类型同步H2与固定输入修复定向检查' if fixture else ('输入类型同步编译修复定向检查' if repair else '输入类型同步定向检查')
    classes = {'AgentInputTypesPersistenceTest', 'WorkflowInputTypesPolicyTest'} if fixture else CLASSES
    inputs_path = EVIDENCE / (prefix + '输入.json')
    result_path = EVIDENCE / (prefix + '结果.json')
    execution_path = EVIDENCE / (prefix + '执行.json')
    log_path = EVIDENCE / (prefix + '.log')
    old_reports = EVIDENCE / ('输入类型同步H2与固定输入修复检查前JUnit报告快照' if fixture else ('输入类型同步编译修复检查前JUnit报告快照' if repair else '输入类型同步检查前JUnit报告快照'))
    new_reports = EVIDENCE / ('输入类型同步H2与固定输入修复定向JUnit报告快照' if fixture else ('输入类型同步编译修复定向JUnit报告快照' if repair else '输入类型同步定向JUnit报告快照'))
    for path in (inputs_path, result_path, execution_path, log_path, old_reports, new_reports):
        assert not path.exists(), f'不得覆盖旧证据：{path}'
    frozen = json.loads((EVIDENCE / ('输入类型同步H2与固定输入修复冻结输入.json' if fixture else ('输入类型同步编译修复冻结输入.json' if repair else '输入类型同步源码冻结输入.json'))).read_text('utf-8-sig'))
    for item in frozen['inputs']:
        assert sha(ROOT / item['path']) == item['sha256'], f'冻结后变化：{item["path"]}'
    expected, count = set(), 0
    for path in (MODULE / 'src/test/java').rglob('*.java'):
        if path.stem not in classes:
            continue
        source = path.read_text('utf-8-sig')
        expected.add(re.search(r'^package\s+([^;]+);', source, re.M)[1] + '.' + path.stem)
        count += len(re.findall(r'@Test\b', source))
    assert len(expected) == len(classes) and count > 0
    paths = sorted(set([MODULE / 'pom.xml', *[p for group in ('src/main', 'src/test', 'sql') for p in (MODULE / group).rglob('*') if p.is_file()],
                        *[p for p in (ROOT / 'contracts/paiflow').glob('*') if p.is_file()],
                        *[p for p in (ROOT / 'contracts/paiflow/fixtures').rglob('*') if p.is_file()]]))
    before = {p.relative_to(ROOT).as_posix(): sha(p) for p in paths}
    save(inputs_path, {'files': before, 'sourceInputCount': len(before), 'expectedTests': count, 'classes': sorted(expected), 'realDbWrites': False})
    reports = MODULE / 'target/surefire-reports'
    old_reports.mkdir()
    prior = {}
    for path in reports.iterdir():
        if path.is_file():
            prior[path.name] = sha(path)
            shutil.copy2(path, old_reports / path.name)
    save(old_reports / '输入.json', {'files': prior})
    command = [MAVEN, '-o', '-pl', 'ruoyi-paiflow', '-am', '-Dtest=' + ','.join(sorted(expected)), '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
    started = datetime.now(timezone.utc).isoformat()
    started_ns = time.time_ns()
    save(execution_path, {'status': 'running', 'startedAt': started, 'command': command})
    print(json.dumps({'status': 'running', 'sourceInputCount': len(before), 'expectedTests': count, 'classes': sorted(expected)}, ensure_ascii=False), flush=True)
    with log_path.open('wb') as stream:
        process = subprocess.run(command, cwd=ROOT / 'ruoyi-backend', stdout=stream, stderr=subprocess.STDOUT)
    ended = datetime.now(timezone.utc).isoformat()
    after = {p.relative_to(ROOT).as_posix(): sha(p) for p in paths if p.exists()}
    changes = [key for key in sorted(set(before) | set(after)) if before.get(key) != after.get(key)]
    current = {p.relative_to(ROOT).as_posix() for group in ('src/main', 'src/test', 'sql') for p in (MODULE / group).rglob('*') if p.is_file()}
    current.update(p.relative_to(ROOT).as_posix() for p in (ROOT / 'contracts/paiflow').glob('*') if p.is_file())
    current.update(p.relative_to(ROOT).as_posix() for p in (ROOT / 'contracts/paiflow/fixtures').rglob('*') if p.is_file())
    changes.extend(sorted(current - set(before)))
    new_reports.mkdir()
    suites, cases = [], []
    for path in sorted(reports.glob('TEST-com.ruoyi.paiflow.*.xml')):
        if path.stat().st_mtime_ns < started_ns:
            continue
        tree = ET.parse(path).getroot()
        suites.append({'name': tree.get('name'), **{field: int(tree.get(field, 0)) for field in ('tests', 'failures', 'errors', 'skipped')}, 'sha256': sha(path)})
        for case in tree.findall('testcase'):
            outcome = 'failed' if case.find('failure') is not None else 'error' if case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            cases.append({'class': case.get('classname'), 'name': case.get('name'), 'outcome': outcome})
        shutil.copy2(path, new_reports / path.name)
        text = reports / (tree.get('name') + '.txt')
        if text.exists():
            shutil.copy2(text, new_reports / text.name)
    totals = {field: sum(row[field] for row in suites) for field in ('tests', 'failures', 'errors', 'skipped')}
    missing = sorted(expected - {row['name'] for row in suites})
    success = process.returncode == 0 and totals['tests'] == count and not missing and not changes and totals['failures'] == totals['errors'] == totals['skipped'] == 0
    output = {'status': 'technical_checks_passed' if success else 'technical_checks_not_passed', 'startedAt': started, 'endedAt': ended, 'exitCode': process.returncode,
        'totals': totals, 'suites': suites, 'cases': cases, 'expectedTests': count, 'expectedClasses': sorted(expected), 'missingClasses': missing,
        'sourceInputCount': len(before), 'sourceWindowChanges': changes, 'realDbWrites': False, 'businessVerified': False, 'migrationComplete': False}
    save(result_path, output)
    save(execution_path, {'status': 'completed', 'startedAt': started, 'endedAt': ended, 'exitCode': process.returncode, 'command': command, 'logSha256': sha(log_path), 'sourceWindowChanges': changes})
    print(json.dumps({key: output[key] for key in ('status', 'totals', 'sourceWindowChanges', 'missingClasses')}, ensure_ascii=False))
    raise SystemExit(0 if success else 1)


if __name__ == '__main__':
    main()
