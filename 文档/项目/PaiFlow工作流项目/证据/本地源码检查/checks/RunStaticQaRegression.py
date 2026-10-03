"""冻结后唯一 Maven 检查入口，保留原报告并核对源码读取窗口；不访问业务库。"""
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
MAVEN = Path(r'D:\java-i-exe\apache-maven-3.9.15\bin\mvn.cmd')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    assert (ROOT / 'AGENTS.md').is_file(), ROOT
    assert MAVEN.is_file(), MAVEN
    # 第一次检查使用固定名字；失败复验须另写入口/结果，不能覆盖首失败。
    golden = '--golden' in sys.argv[1:]
    repair = '--repair' in sys.argv[1:] or golden
    prefix = '静态问答规范字节断言-定向复验' if golden else '静态问答首验修复-定向复验' if repair else '静态问答与运行JSON-联合回归'
    inputs_path = EVIDENCE / (prefix + '输入.json')
    result_path = EVIDENCE / (prefix + '结果.json')
    execution_path = EVIDENCE / (prefix + '执行.json')
    log_path = EVIDENCE / (prefix + '.log')
    report_snapshot = EVIDENCE / ('静态问答规范字节断言定向复验报告快照' if golden else '静态问答首验修复定向复验报告快照' if repair else '静态问答与运行JSON联合回归报告快照')
    old_snapshot = EVIDENCE / ('静态问答规范字节断言检查前报告快照' if golden else '静态问答首验修复检查前报告快照' if repair else '静态问答与运行JSON检查前报告快照')
    for path in (inputs_path, result_path, execution_path, log_path, report_snapshot, old_snapshot):
        assert not path.exists(), f'保留既有证据，禁止覆盖：{path}'
    java_freeze = '静态问答数值断言修复冻结输入.json' if golden else '静态问答首验修复冻结输入.json' if repair else '静态问答与运行JSON编码冻结输入.json'
    for name, key in ((java_freeze, 'inputs'), ('问答运行JSON与原页帧候选输入.json', 'files')):
        frozen = json.loads((EVIDENCE / name).read_text(encoding='utf-8-sig'))
        for item in frozen[key]:
            assert digest(ROOT / item['path']) == item['sha256'], f'冻结后输入变化：{item["path"]}'
    expected_classes = set()
    expected_tests = 0
    repair_classes = {'LegacyAgentContractTest', 'LegacyModelContractTest', 'LegacyWorkflowContractTest',
        'LegacyWorkflowDefaultScopeContractTest', 'AgentPersistenceTest', 'CanvasExecutionCompilerTest',
        'ModelNodeOptionsTest', 'ModelPersistenceTest', 'PureIterationRunPersistenceTest',
        'RunJsonBoundaryCompatibilityTest', 'RunJsonCodecPersistenceTest', 'RuntimeModelCompilationTest',
        'StaticQuestionAnswerOptionsTest', 'StaticQuestionAnswerPersistenceTest', 'StaticQuestionAnswerPolicyTest',
        'WorkflowDefaultScopePersistenceTest', 'WorkflowPersistenceTest'}
    for path in (MODULE / 'src/test/java').rglob('*.java'):
        if golden and path.stem != 'StaticQuestionAnswerPersistenceTest':
            continue
        if repair and path.stem not in repair_classes:
            continue
        source = path.read_text(encoding='utf-8-sig')
        count = len(re.findall(r'@Test\b', source))
        if count:
            package = re.search(r'^package\s+([^;]+);', source, re.M).group(1)
            expected_classes.add(package + '.' + path.stem)
            expected_tests += 1 if golden else count
    assert expected_tests == (1 if golden else 242 if repair else 445) and len(expected_classes) == (1 if golden else 17 if repair else 40), '候选集合变化，重新核对本批检查范围'
    paths = sorted(set([MODULE / 'pom.xml', *[p for group in ('src/main', 'src/test', 'sql') for p in (MODULE / group).rglob('*') if p.is_file()],
                       *[p for p in (ROOT / 'contracts/paiflow').glob('*') if p.is_file()],
                       *[p for p in (ROOT / 'contracts/paiflow/fixtures').rglob('*') if p.is_file()]]))
    before = {p.relative_to(ROOT).as_posix(): digest(p) for p in paths}
    # 调用者完成全部所有者冻结后启动；当前实际源码与共享夹具作为唯一执行输入。
    save(inputs_path, {'schemaVersion': 'paiflow.local-check-inputs.v1', 'files': before, 'count': len(before),
         'realDbWrites': False, 'businessVerified': False})
    old_snapshot.mkdir()
    reports = MODULE / 'target/surefire-reports'
    prior = {}
    if reports.exists():
        for path in reports.iterdir():
            if path.is_file():
                prior[path.name] = {'sha256': digest(path), 'mtimeNs': path.stat().st_mtime_ns}
                shutil.copy2(path, old_snapshot / path.name)
    save(old_snapshot / '输入.json', {'count': len(prior), 'files': prior})
    test_filter = ','.join(sorted(expected_classes)) if repair else 'com.ruoyi.paiflow.**'
    if golden:
        test_filter += '#originalCanvasCompilesV3WithoutChangingRawThenQueuesClaimsBeginsAndPreservesNullBytes'
    command = [str(MAVEN), '-o', '-pl', 'ruoyi-paiflow', '-am', '-Dtest=' + test_filter,
               '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
    start = datetime.now(timezone.utc).isoformat()
    start_ns = time.time_ns()
    save(execution_path, {'status': 'running', 'startedAt': start, 'command': command,
         'cwd': str(ROOT / 'ruoyi-backend'), 'sourceInputCount': len(before)})
    print(json.dumps({'status': 'running', 'sourceInputs': len(before), 'command': command}, ensure_ascii=False), flush=True)
    with log_path.open('wb') as log:
        process = subprocess.run(command, cwd=ROOT / 'ruoyi-backend', stdout=log, stderr=subprocess.STDOUT)
    end = datetime.now(timezone.utc).isoformat()
    after = {p.relative_to(ROOT).as_posix(): digest(p) for p in paths if p.exists()}
    changes = [name for name in sorted(set(before) | set(after)) if before.get(name) != after.get(name)]
    # 新文件也进入窗口变化；不能只核对启动时已枚举文件。
    current = set(p.relative_to(ROOT).as_posix() for group in ('src/main', 'src/test', 'sql') for p in (MODULE / group).rglob('*') if p.is_file())
    current.update(p.relative_to(ROOT).as_posix() for p in (ROOT / 'contracts/paiflow').glob('*') if p.is_file())
    current.update(p.relative_to(ROOT).as_posix() for p in (ROOT / 'contracts/paiflow/fixtures').rglob('*') if p.is_file())
    changes.extend(sorted(current - set(before)))
    suites, cases = [], []
    report_snapshot.mkdir()
    for path in sorted(reports.glob('TEST-com.ruoyi.paiflow.*.xml')):
        if path.stat().st_mtime_ns < start_ns:
            continue
        tree = ET.parse(path).getroot()
        suites.append({'name': tree.get('name'), 'tests': int(tree.get('tests', 0)),
                       'failures': int(tree.get('failures', 0)), 'errors': int(tree.get('errors', 0)),
                       'skipped': int(tree.get('skipped', 0)), 'sha256': digest(path)})
        for case in tree.findall('testcase'):
            outcome = 'failed' if case.find('failure') is not None else 'error' if case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            cases.append({'class': case.get('classname'), 'name': case.get('name'), 'outcome': outcome})
        shutil.copy2(path, report_snapshot / path.name)
        text_report = reports / (tree.get('name') + '.txt')
        if text_report.is_file():
            shutil.copy2(text_report, report_snapshot / text_report.name)
    totals = {field: sum(s[field] for s in suites) for field in ('tests', 'failures', 'errors', 'skipped')}
    missing_classes = sorted(expected_classes - {s['name'] for s in suites})
    text = log_path.read_text(encoding='utf-8', errors='replace')
    success = process.returncode == 0 and 'BUILD SUCCESS' in text and not missing_classes and totals['tests'] == expected_tests and not changes and totals['failures'] == totals['errors'] == totals['skipped'] == 0
    save(execution_path, {'status': 'completed', 'startedAt': start, 'endedAt': end, 'exitCode': process.returncode,
         'command': command, 'cwd': str(ROOT / 'ruoyi-backend'), 'logSha256': digest(log_path), 'sourceWindowChanges': changes})
    save(result_path, {'status': 'technical_checks_passed' if success else 'technical_checks_not_passed',
         'startedAt': start, 'endedAt': end, 'exitCode': process.returncode, 'totals': totals, 'suites': suites,
         'cases': cases, 'sourceInputCount': len(before), 'sourceWindowChanges': changes,
         'expectedTests': expected_tests, 'expectedClasses': sorted(expected_classes), 'missingClasses': missing_classes,
         'realDbWrites': False, 'businessVerified': False, 'migrationComplete': False})
    print(json.dumps({'status': 'passed' if success else 'not_passed', 'totals': totals,
         'suites': len(suites), 'exitCode': process.returncode, 'sourceWindowChanges': changes}, ensure_ascii=False), flush=True)
    raise SystemExit(0 if success else 1)


if __name__ == '__main__':
    main()
