"""按真实用例身份沿用通过证据，保留四次失败/复验事实。"""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[6]
EVIDENCE = ROOT / '文档/项目/PaiFlow工作流项目/证据/本地源码检查'
PREFIXES = ['输入类型同步定向检查', '输入类型同步编译修复定向检查',
            '输入类型同步H2与固定输入修复定向检查', '输入类型同步循环夹具清理修复定向检查']


def read(path):
    return json.loads(path.read_text('utf-8-sig'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def identities(result):
    cases = {(case['class'], case['name']): case for case in result['cases']}
    assert len(cases) == len(result['cases']), '重复用例身份'
    return cases


def main():
    results = [read(EVIDENCE / (prefix + '结果.json')) for prefix in PREFIXES]
    inputs = [read(EVIDENCE / (prefix + '输入.json'))['files'] for prefix in PREFIXES]
    cases = [identities(result) for result in results]
    assert [len(rows) for rows in cases] == [0, 66, 22, 2]
    assert all(not result['sourceWindowChanges'] for result in results)
    assert results[0]['exitCode'] != 0 and results[0]['totals']['tests'] == 0
    assert results[1]['totals'] == {'tests': 66, 'failures': 0, 'errors': 15, 'skipped': 0}
    assert results[2]['totals'] == {'tests': 22, 'failures': 0, 'errors': 2, 'skipped': 0}
    assert results[3]['exitCode'] == 0 and all(row['outcome'] == 'passed' for row in cases[3].values())
    retained_second = set(cases[1]) - set(cases[2])
    retained_third = set(cases[2]) - set(cases[3])
    assert len(retained_second) == 46 and len(retained_third) == 20
    assert set(cases[3]) <= set(cases[2])
    assert len(set(cases[1]) & set(cases[2])) == 20
    assert all(cases[1][key]['outcome'] == 'passed' for key in retained_second)
    assert all(cases[2][key]['outcome'] == 'passed' for key in retained_third)
    transitions = []
    for before, after in zip(inputs, inputs[1:]):
        assert set(before) == set(after)
        transitions.append([key for key in sorted(before) if before[key] != after[key]])
    assert {Path(key).name for key in transitions[0]} == {'WorkflowInputTypesPolicyTest.java'}
    assert {Path(key).name for key in transitions[1]} == {'WorkflowInputTypesPolicy.java', 'WorkflowInputTypesPolicyTest.java', 'AgentInputTypesPersistenceTest.java'}
    assert {Path(key).name for key in transitions[2]} == {'AgentInputTypesPersistenceTest.java'}
    assert len(inputs[3]) == 201
    assert all(digest(ROOT / key) == sha for key, sha in inputs[3].items()), '末次检查后来源变化'
    chosen = {}
    for index in (1, 2, 3):
        for key, case in cases[index].items():
            chosen[key] = {**case, 'evidencePrefix': PREFIXES[index], 'carriedBaseline': index != 3}
    assert len(chosen) == 68 and all(case['outcome'] == 'passed' for case in chosen.values())
    old = read(EVIDENCE / '静态问答三次检查用例沿用核对.json')
    old_cases = identities(old)
    assert len(old_cases) == 445 and len(set(old_cases) & set(chosen)) == 42
    old_inputs = read(EVIDENCE / '静态问答规范字节断言-定向复验输入.json')['files']
    added = sorted(set(inputs[3]) - set(old_inputs))
    changed = [key for key in sorted(set(old_inputs) & set(inputs[3])) if old_inputs[key] != inputs[3][key]]
    assert not set(old_inputs) - set(inputs[3]) and len(added) == 5
    assert {Path(key).name for key in changed} == {'source-api-inventory.json', 'AgentService.java', 'LegacyAgentWorkflowController.java', 'LegacyAgentContractTest.java'}
    final = {key: {**value, 'carriedBaseline': True, 'carrySource': '静态问答三次检查用例沿用核对.json'} for key, value in old_cases.items() if key not in chosen}
    assert len(final) == 403
    final.update(chosen)
    assert len(final) == 471 and all(case['outcome'] == 'passed' for case in final.values())
    output = EVIDENCE / '输入类型同步四次检查用例沿用核对.json'
    assert not output.exists(), '不得覆盖旧证据'
    response = EVIDENCE / 'checks/输入类型同步实际MVC响应.json'
    proof = {
        'status': 'technical_baseline_reconciled', 'inputTypesUniqueTests': 68,
        'secondRunCarriedPassed': 46, 'thirdRunCarriedPassed': 20, 'latestRunNewlyExecutedPassed': 2,
        'currentAllModuleBaselineTests': 471, 'priorQaUnaffectedCarriedPassed': 403,
        'all68Or471FullyRerunAfterRepairs': False, 'sourceInputCount': 201, 'sourceWindowChanges': [],
        'sourceTransitions': transitions, 'priorQaInputFilesAdded': added, 'priorQaInputFilesChanged': changed,
        'carryReason': '两次新增测试修复只调整隔离H2的MySQL生成字段适配和循环清理，未改变业务权限；固定输入过滤顺序的生产修复已覆盖6项策略及16项真实持久化族。原Agent31与Legacy15来自第二次真实通过，其他403身份的生产源和测试未受影响。source-api-inventory为此前QA完成后的已保全索引刷新，不是业务源码。',
        'runs': [{'prefix': prefix, 'resultSha256': digest(EVIDENCE / (prefix + '结果.json')), 'totals': result['totals'], 'startedAt': result['startedAt'], 'endedAt': result['endedAt']} for prefix, result in zip(PREFIXES, results)],
        'actualMvcResponseCopy': {'path': response.relative_to(ROOT).as_posix(), 'sha256': digest(response)},
        'cases': [final[key] for key in sorted(final)],
        'realDbWrites': False, 'liveHttp': False, 'businessVerified': False, 'migrationComplete': False,
    }
    output.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'path': output.relative_to(ROOT).as_posix(), 'sha256': digest(output), 'inputTypesTests': 68, 'currentBaseline': 471, 'newlyExecutedLatest': 2}, ensure_ascii=False))


if __name__ == '__main__':
    main()
