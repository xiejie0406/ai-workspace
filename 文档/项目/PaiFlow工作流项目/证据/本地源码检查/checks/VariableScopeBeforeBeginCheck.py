"""本批变量作用域窄门的源码保全、离线检查和身份对账；不执行外部服务。"""
import argparse
import asyncio
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import unittest
from unittest.mock import patch

WORKSPACE = Path(__file__).resolve().parents[6]
ROOT = WORKSPACE / 'python/paiflow-runtime'
OUT = ROOT / 'verification'
sys.path.insert(0, str(ROOT))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(name, data):
    path = OUT / name
    if path.exists():
        raise RuntimeError('已有证据拒覆盖: ' + name)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return path


def sources():
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(ROOT.rglob('*.py'))
            if p.relative_to(ROOT).parts[0] not in {'.venv', 'build', 'dist'}}


def prepare():
    files = ['worker/task_runner.py', 'tests/test_execution_begin.py', 'tests/test_original_engine.py', 'README.md',
             'verification/unittest.log', 'verification/checks.json', 'verification/source-check.log',
             'verification/dependency-analysis.log', 'verification/dependency-check.log', 'source-verification.json',
             'verification/static-question-answer-test-identities.json']
    saved = {}
    for name in files:
        path = ROOT / name
        target = OUT / ('variable-scope-before-' + name.replace('/', '-') + '.txt')
        if target.exists():
            raise RuntimeError('原文备份已存在: ' + str(target))
        target.write_bytes(path.read_bytes())
        saved[name] = {'sha256': sha(path), 'saved': target.relative_to(ROOT).as_posix()}
    write('variable-scope-before.json', {'at': datetime.now(timezone.utc).isoformat(), 'saved': saved,
        'pythonSourceBefore': sources(), 'baselineIdentityUnion': 266})
    from tests.test_original_engine import OriginalEngineTest
    from tests.test_worker import RecordingCenter
    from worker.engine_adapter import create_task_runner
    from workflow.engine.dsl_engine import WorkflowEngineFactory
    async def probe():
        raw = OriginalEngineTest().variable_raw()
        center = RecordingCenter(raw)
        runner = create_task_runner(center)
        before = sources()
        with patch.object(runner.engine, 'execute', wraps=runner.engine.execute) as engine, \
             patch.object(WorkflowEngineFactory, 'create_engine', wraps=WorkflowEngineFactory.create_engine) as factory:
            await asyncio.wait_for(runner.run(raw), 8)
            data = {'scope': 'original target before narrow fix; exact TaskRunner with memory center, 0 real HTTP/DB/Provider',
                'expectedNextBoundary': {'begin': 0, 'engine': 0, 'factory': 0, 'result': 0},
                'actual': {'begin': len(center.begin_calls), 'engine': engine.call_count, 'factory': factory.call_count,
                    'result': len(center.results), 'events': len(center.events), 'operation': len(center.operations),
                    'resource': len(center.resource_calls), 'state': center.results[-1]['state'],
                    'errorCode': center.results[-1]['error']['code']}, 'sourceBefore': before, 'sourceAfter': sources()}
            assert data['actual']['begin'] == data['actual']['engine'] == data['actual']['result'] == 1
            assert data['actual']['factory'] == data['actual']['events'] == 0
            assert data['sourceBefore'] == data['sourceAfter']
            data['meetsNextBoundary'] = False
            path = write('variable-scope-before-first-probe.json', data)
            print(json.dumps({'firstProbe': sha(path), 'actual': data['actual']}))
    asyncio.run(probe())


def run(mode):
    before = sources()
    env = dict(os.environ, PYTHONUTF8='1', PYTHONDONTWRITEBYTECODE='1')
    args = ['-B', '-m', 'unittest', '-v', 'tests.test_execution_begin', 'tests.test_worker', 'tests.test_original_engine']
    if mode == 'full':
        args = ['-B', 'scripts/run_offline_checks.py']
    elif mode == 'targeted-fixed':
        args = ['-B', '-m', 'unittest', '-v', 'tests.test_execution_begin.ExecutionBeginTest.test_variable_lease_hash_sequence_assignment_validation_precedes_scope_gate']
    started = datetime.now(timezone.utc).isoformat()
    result = subprocess.run([str(ROOT / '.venv/Scripts/python.exe'), *args], cwd=ROOT, env=env,
                            capture_output=True, encoding='utf-8', errors='strict', timeout=180, check=False)
    log = OUT / ('variable-scope-' + mode + '.log')
    if log.exists():
        raise RuntimeError('已有日志拒覆盖')
    log.write_text(result.stdout + result.stderr, encoding='utf-8')
    report = {'command': ['.venv/Scripts/python.exe', *args], 'startedAtUtc': started,
        'finishedAtUtc': datetime.now(timezone.utc).isoformat(), 'exitCode': result.returncode,
        'log': log.relative_to(ROOT).as_posix(), 'logSha256': sha(log), 'sourceBefore': before,
        'sourceAfter': sources(), 'scope': 'actual offline tests only; no real center/Provider/DB/Redis/Maven/service'}
    assert report['sourceBefore'] == report['sourceAfter']
    if mode == 'full':
        for name in ['unittest.log', 'checks.json', 'source-check.log', 'dependency-analysis.log', 'dependency-check.log']:
            (OUT / ('variable-scope-full-' + name)).write_bytes((OUT / name).read_bytes())
    path = write('variable-scope-' + mode + '-check.json', report)
    print(json.dumps({'exitCode': result.returncode, 'reportSha256': sha(path), 'output': result.stdout + result.stderr}))
    if result.returncode:
        raise SystemExit(result.returncode)


def identities(path):
    data = path.read_text(encoding='utf-8-sig')
    match = re.search(r'Ran (\d+) tests? in ([0-9.]+)s', data)
    entries = re.findall(r'^test_\S+ \(([^)\r\n]+)\) \.\.\.', data, re.M)
    assert match and int(match[1]) == len(entries) and data.rstrip().endswith('OK')
    return [i.removeprefix('tests.') for i in entries], float(match[2])


def finalize():
    old = json.loads((OUT / 'static-question-answer-test-identities.json').read_text(encoding='utf-8'))
    old_ids = set(old['currentDiscovery']['identities'])
    target_fixed, target_fixed_secs = identities(OUT / 'variable-scope-targeted-fixed.log')
    first_target = (OUT / 'variable-scope-targeted-first.log').read_text(encoding='utf-8')
    target = [i.removeprefix('tests.') for i in re.findall(r'^test_\S+ \(([^)\r\n]+)\) \.\.\.', first_target, re.M)]
    target_secs = float(re.search(r'Ran 66 tests in ([0-9.]+)s', first_target)[1])
    assert len(target) == 66 and first_target.rstrip().endswith('FAILED (failures=1)')
    assert target_fixed == ['test_execution_begin.ExecutionBeginTest.test_variable_lease_hash_sequence_assignment_validation_precedes_scope_gate']
    full, full_secs = identities(OUT / 'variable-scope-full-unittest.log')
    def flatten(suite):
        for item in suite:
            if isinstance(item, unittest.TestSuite):
                yield from flatten(item)
            else:
                yield item.id().removeprefix('tests.')
    with patch('socket.socket.connect', side_effect=AssertionError('discovery cannot connect')):
        current = list(flatten(unittest.defaultTestLoader.discover(str(ROOT / 'tests'))))
    assert len(full) == len(set(full)) and set(full) == set(current)
    assert not old_ids - set(full)
    baseline = json.loads((OUT / 'variable-scope-before.json').read_text(encoding='utf-8'))
    after = sources()
    changed = [p for p, value in baseline['pythonSourceBefore'].items() if after.get(p) != value]
    assert set(changed) == {'worker/task_runner.py', 'tests/test_execution_begin.py', 'tests/test_original_engine.py'}
    report = {'scope': 'actual current full rerun after shared lifecycle fix; prior265/34/266 identity union remains historical',
        'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
        'baselineIdentityUnion': len(old_ids), 'baselineIdentityProofSha256': sha(OUT / 'static-question-answer-test-identities.json'),
        'targeted': {'firstCount': len(target), 'firstPass': 65, 'firstFailure': 1, 'firstSeconds': target_secs,
            'identities': target, 'failureReason': '新测试误期望DSL_INVALID；原Assignment.parse哈希冲突实际VERSION_CONFLICT，生产语义未改',
            'fixedActualCount': len(target_fixed), 'fixedSeconds': target_fixed_secs, 'fixedIdentities': target_fixed,
            'uniquePassAfterFix': 66},
        'full': {'count': len(full), 'seconds': full_secs, 'identities': full, 'discoveryMatches': True},
        'newIdentities': sorted(set(full) - old_ids), 'removedIdentities': sorted(old_ids - set(full)),
        'reexecutedBaselineIdentities': sorted(old_ids & set(full)), 'carriedButNotRerun': [],
        'changedPython': changed, 'sourceBefore': baseline['pythonSourceBefore'], 'sourceAfter': after,
        'changedAndReadmeHashes': {p: sha(ROOT / p) for p in [*changed, 'README.md']},
        'sharedLifecycleCoverage': ['test_execution_begin entire family (permit binding/duplicates/cancellation/factory)',
            'test_worker entire family', 'original Factory variable/pure/branch/iteration',
            'all current QA/iteration/model controlled adapters and Producer offline test identities'],
        'unavailable': ['Java node-variable admission remains503', 'no formal App/scope binding',
            'center variables/history endpoints not implemented', 'memory shape is not production binding or persisted data migration'],
        'checkLogs': {p: sha(OUT / p) for p in ['variable-scope-before-first-probe.json', 'variable-scope-targeted-first.log', 'variable-scope-targeted-fixed.log',
            'variable-scope-full-unittest.log', 'variable-scope-full-checks.json', 'variable-scope-full-source-check.log',
            'variable-scope-full-dependency-analysis.log', 'variable-scope-full-dependency-check.log']}}
    path = write('variable-scope-stage.json', report)
    print(json.dumps({'old': len(old_ids), 'targeted': len(target), 'full': len(full), 'seconds': full_secs,
        'new': report['newIdentities'], 'changedPython': changed, 'sha256': sha(path)}, ensure_ascii=False))


mode = argparse.ArgumentParser()
mode.add_argument('mode', choices=['prepare', 'targeted', 'targeted-fixed', 'full', 'finalize'])
choice = mode.parse_args().mode
if choice == 'prepare':
    prepare()
elif choice == 'finalize':
    finalize()
else:
    run(choice)
