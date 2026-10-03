"""原React调试接续定向检查，完整输出留在既有技术证据；不启动页面或服务。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[6]
FRONT = ROOT / 'frontend/paiflow-web'
EVIDENCE = Path(__file__).resolve().parent.parent
NODE = r'C:\Program Files\nodejs\node.exe'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    proof = EVIDENCE / '原React调试接续前端定向检查.json'
    assert not proof.exists(), '旧证据不能覆盖'
    names = ['src/services/flow.ts', 'src/utils/workflow-revision.ts', 'src/utils/workflow-debug.ts',
        'src/components/workflow/store/flow-chat-function.ts', 'src/components/workflow/types/drawer/chat-debugger.ts',
        'tests/workflow-revision.test.cjs', 'tests/workflow-debug.test.cjs', 'src/utils/http.ts',
        'src/components/workflow/store/use-chat-store.ts', 'tests/platform-auth.test.cjs', 'tests/compare-reference-types.cjs',
        'package.json', 'package-lock.json', 'tsconfig.json', 'vite.config.js']
    before = {name: sha(FRONT / name) for name in names}
    commands = [
        ('原认证版本与调试请求定向测试', [NODE, '--test', 'tests/platform-auth.test.cjs', 'tests/workflow-revision.test.cjs', 'tests/workflow-debug.test.cjs'], FRONT),
        ('新增请求适配器严格类型', [NODE, 'node_modules/typescript/bin/tsc', '--noEmit', '--strict', '--target', 'ES2022', '--module', 'ESNext', '--moduleResolution', 'bundler', '--skipLibCheck', '--lib', 'ES2022,DOM', 'src/utils/workflow-revision.ts', 'src/utils/workflow-debug.ts'], FRONT),
        ('原类型基线最终增量比较', [NODE, 'tests/compare-reference-types.cjs'], FRONT),
        ('Java实际响应原服务与原AST消费者', [NODE, str(Path(__file__).with_name('DebugActualResponsesReview.cjs')), '调试接续Java实际响应原消费者最终独立核对.json'], ROOT),
        ('原节点ID与比较停止协议最终复验', [NODE, str(Path(__file__).with_name('DebugContextProtocolProbe.cjs')), 'frontend/paiflow-web/src/utils/workflow-debug.ts', '原调试上下文协议最终独立复验.json'], ROOT),
    ]
    results = []
    for label, command, workdir in commands:
        log = EVIDENCE / ('原React调试接续-' + label + '.log')
        assert not log.exists()
        started = datetime.now(timezone.utc).isoformat()
        with log.open('wb') as stream:
            process = subprocess.run(command, cwd=workdir, stdout=stream, stderr=subprocess.STDOUT)
        results.append({'label': label, 'command': command, 'workdir': str(workdir), 'startedAt': started,
            'endedAt': datetime.now(timezone.utc).isoformat(), 'exitCode': process.returncode,
            'log': log.relative_to(ROOT).as_posix(), 'logSha256': sha(log)})
        print(json.dumps({'label': label, 'exitCode': process.returncode}, ensure_ascii=False), flush=True)
    prior = ROOT / '文档/项目/PaiFlow工作流项目/附录/原前端类型基线比较.json'
    types = json.loads(prior.read_text('utf-8'))
    target = EVIDENCE / '原React调试接续原类型最终诊断.json'
    assert not target.exists(); shutil.copy2(prior, target)
    after = {name: sha(FRONT / name) for name in names}
    changed = [name for name in names if before[name] != after[name]]
    tests = (EVIDENCE / '原React调试接续-原认证版本与调试请求定向测试.log').read_text('utf-8')
    output = {'sourceHashesBefore': before, 'sourceHashesAfter': after, 'sourceWindowChanges': changed,
        'commands': results, 'testSummary': tests[tests.rfind('ℹ tests'):],
        'baselineTypeErrors': types['baselineErrors'], 'currentTypeErrors': types['currentErrors'], 'newTypeErrors': types['newErrors'],
        'typeDiagnostic': {'path': target.relative_to(ROOT).as_posix(), 'sha256': sha(target)},
        'firstFailuresPreserved': ['原React调试接续类型增量首失败.json', 'checks/原调试上下文首协议反例.json', 'checks/调试实际响应消费者探针首实现失败.json'],
        'scope': '原服务+现HTTP条件适配器+原调试函数内存VM/原AST消费者，完整日志；不计业务或页面验收',
        'fullProjectTypeCheckPassed': False, 'browserUsed': False, 'liveHttp': False, 'providerCalls': 0,
        'databaseWrites': False, 'mavenExecuted': False, 'productionBuildExecuted': False, 'businessVerified': False}
    output['status'] = 'directed_technical_checks_passed' if all(row['exitCode'] == 0 for row in results) and not changed and not types['newErrors'] else 'directed_technical_checks_not_passed'
    proof.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': output['status'], 'typeBaseline': types['baselineErrors'], 'typeCurrent': types['currentErrors'], 'typeNew': len(types['newErrors']), 'windowChanges': changed, 'sha256': sha(proof)}, ensure_ascii=False), flush=True)
    raise SystemExit(0 if output['status'] == 'directed_technical_checks_passed' else 1)

if __name__ == '__main__': main()
