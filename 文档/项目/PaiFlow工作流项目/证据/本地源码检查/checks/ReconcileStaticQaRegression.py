"""按用例身份合并已有检查证据；不把沿用基线称为重新执行。"""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[6]
EVIDENCE = ROOT / '文档/项目/PaiFlow工作流项目/证据/本地源码检查'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


RENAMED_CASE = {
    ('com.ruoyi.paiflow.service.StaticQuestionAnswerOptionsTest', 'actualOneHundredEighteenPydanticCasesHaveSameEffectiveValuesOrExplicitProfileRejection'):
    ('com.ruoyi.paiflow.service.StaticQuestionAnswerOptionsTest', 'actualOneHundredEighteenPydanticCasesKeepOriginalFactsAndOnlyApprovedMissingStringCompatibility')
}


def identities(result):
    rows = {RENAMED_CASE.get((row['class'], row['name']), (row['class'], row['name'])): row for row in result['cases']}
    assert len(rows) == len(result['cases']), '重复用例身份'
    return rows


def main():
    prefixes = ['静态问答与运行JSON-联合回归', '静态问答首验修复-定向复验', '静态问答规范字节断言-定向复验']
    results = [read(EVIDENCE / (prefix + '结果.json')) for prefix in prefixes]
    inputs = [read(EVIDENCE / (prefix + '输入.json'))['files'] for prefix in prefixes]
    sets = [identities(result) for result in results]
    assert [len(rows) for rows in sets] == [445, 242, 1]
    assert set(sets[2]) <= set(sets[1]) <= set(sets[0])
    assert all(not result['sourceWindowChanges'] for result in results)
    assert all(row['outcome'] == 'passed' for row in sets[2].values())
    carry_first = set(sets[0]) - set(sets[1])
    carry_second = set(sets[1]) - set(sets[2])
    assert len(carry_first) == 203 and len(carry_second) == 241
    assert all(sets[0][key]['outcome'] == 'passed' for key in carry_first)
    assert all(sets[1][key]['outcome'] == 'passed' for key in carry_second)
    transitions = []
    for earlier, later in zip(inputs, inputs[1:]):
        assert set(earlier) == set(later)
        transitions.append([key for key in earlier if earlier[key] != later[key]])
    assert {Path(key).name for key in transitions[0]} == {'WorkflowJsonBoundary.java', 'StaticQuestionAnswerOptionsTest.java', 'StaticQuestionAnswerTestFixture.java'}
    assert {Path(key).name for key in transitions[1]} == {'StaticQuestionAnswerPersistenceTest.java'}
    assert all(digest(ROOT / key) == sha for key, sha in inputs[2].items()), '最新检查后输入变化'
    # 首次后生产只改 WorkflowJsonBoundary 的字段标记预算；第二次已重验所有直接消费者家族。
    marker = ROOT / 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowJsonBoundary.java'
    caller_names = []
    for path in marker.parent.glob('*.java'):
        if path != marker and 'WorkflowJsonBoundary.check(' in path.read_text(encoding='utf-8-sig'):
            caller_names.append(path.name)
    assert set(caller_names) == {'WorkflowService.java', 'WorkflowModelCompiler.java', 'AgentService.java', 'ModelService.java'}
    tested = {key[0].rsplit('.', 1)[-1] for key in sets[1]}
    out = EVIDENCE / '静态问答三次检查用例沿用核对.json'
    assert not out.exists(), '不得覆盖旧证据'
    artifact = ROOT / 'ruoyi-backend/ruoyi-paiflow/target/static-question-answer-assignment.json'
    copy = EVIDENCE / '静态问答实际领取任务-c5b2718db84c.json'
    assert digest(artifact) == 'c5b2718db84ccdf1ddab8f79609476e7f78bf15066e9ee7794cf2241f5ce95fb'
    assert not copy.exists()
    shutil.copy2(artifact, copy)
    cases = []
    for key in sorted(sets[0]):
        source = 2 if key in sets[2] else 1 if key in sets[1] else 0
        cases.append({**sets[source][key], 'evidencePrefix': prefixes[source], 'carriedBaseline': source != 2})
    proof = {
        'status': 'technical_baseline_reconciled', 'uniqueCurrentTests': len(cases),
        'firstRunCarriedPassed': len(carry_first), 'secondRunCarriedPassed': len(carry_second),
        'latestRunNewlyExecutedPassed': len(sets[2]), 'full445RerunAfterRepairs': False,
        'sourceInputCount': len(inputs[2]), 'sourceWindowChanges': [],
        'sourceTransitions': transitions, 'markerDirectCallerSources': sorted(caller_names),
        'renamedRetestedCases': [{'class': old[0], 'oldName': old[1], 'currentName': new[1], 'reason': '首修明确两项missing字符串兼容与原typed拒绝的差异；旧失败保留，现名对应同一118夹具定向复验。'} for old, new in RENAMED_CASE.items()],
        'secondRunTestClasses': sorted(tested),
        'carryReason': '首修只改字段标记预算及问答夹具，定向检查覆盖全部标记直接消费者家族；末修只改一个用例的数值对象比较为既定规范字节/哈希断言，生产源码与其他测试输入未变。',
        'runs': [{'prefix': prefix, 'resultSha256': digest(EVIDENCE / (prefix + '结果.json')), 'totals': result['totals'], 'startedAt': result['startedAt'], 'endedAt': result['endedAt']} for prefix, result in zip(prefixes, results)],
        'actualJavaClaimCopy': {'path': copy.relative_to(ROOT).as_posix(), 'sha256': digest(copy)},
        'cases': cases, 'realDbWrites': False, 'businessVerified': False, 'migrationComplete': False,
    }
    out.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'path': out.relative_to(ROOT).as_posix(), 'sha256': digest(out), 'uniqueTests': len(cases), 'carriedFirst': len(carry_first), 'carriedSecond': len(carry_second), 'newlyExecuted': len(sets[2])}, ensure_ascii=False))


if __name__ == '__main__':
    main()
