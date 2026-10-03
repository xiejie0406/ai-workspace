"""原请求条件的定向技术检查；不启动页面，不升级依赖。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[6]
FRONT = ROOT / 'frontend/paiflow-web'
EVIDENCE = ROOT / '文档/项目/PaiFlow工作流项目/证据/本地源码检查'
NODE = r'C:\Program Files\nodejs\node.exe'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    proof = EVIDENCE / '输入类型同步原请求适配技术检查.json'
    assert not proof.exists(), '旧检查证据不得覆盖'
    names = ['src/utils/workflow-revision.ts', 'tests/workflow-revision.test.cjs', 'src/utils/http.ts', 'src/services/flow.ts', 'src/components/workflow/store/flow-chat-function.ts', 'tests/compare-reference-types.cjs', 'package.json', 'package-lock.json', 'tsconfig.json']
    paths = [FRONT / name for name in names]
    before = {name: sha(path) for name, path in zip(names, paths)}
    prior_types = ROOT / '文档/项目/PaiFlow工作流项目/附录/原前端类型基线比较.json'
    saved_types = EVIDENCE / '输入类型同步检查前原前端类型基线.json'
    assert not saved_types.exists()
    shutil.copy2(prior_types, saved_types)
    commands = [
        ('条件定向回归', [NODE, '--test', 'tests/workflow-revision.test.cjs']),
        ('适配器独立严格类型检查', [NODE, 'node_modules/typescript/bin/tsc', '--noEmit', '--strict', '--target', 'ES2022', '--module', 'ESNext', '--moduleResolution', 'bundler', '--skipLibCheck', '--lib', 'ES2022,DOM', 'src/utils/workflow-revision.ts']),
        ('原类型基线比较', [NODE, 'tests/compare-reference-types.cjs']),
    ]
    results = []
    for label, command in commands:
        log = EVIDENCE / ('输入类型同步-' + label + '.log')
        assert not log.exists()
        started = datetime.now(timezone.utc).isoformat()
        with log.open('wb') as stream:
            process = subprocess.run(command, cwd=FRONT, stdout=stream, stderr=subprocess.STDOUT)
        ended = datetime.now(timezone.utc).isoformat()
        results.append({'label': label, 'command': command, 'startedAt': started, 'endedAt': ended, 'exitCode': process.returncode, 'log': log.relative_to(ROOT).as_posix(), 'logSha256': sha(log)})
        print(json.dumps({'label': label, 'exitCode': process.returncode}, ensure_ascii=False), flush=True)
    current_types = json.loads(prior_types.read_text('utf-8'))
    copied_types = EVIDENCE / '输入类型同步原前端类型诊断.json'
    assert not copied_types.exists()
    shutil.copy2(prior_types, copied_types)
    after = {name: sha(path) for name, path in zip(names, paths)}
    changed = [name for name in before if before[name] != after[name]]
    output = {'sourceHashesBefore': before, 'sourceHashesAfter': after, 'sourceWindowChanges': changed, 'commands': results,
        'fullProjectTypeCheckPassed': False, 'baselineTypeErrors': current_types['baselineErrors'], 'currentTypeErrors': current_types['currentErrors'], 'newTypeErrors': current_types['newErrors'],
        'priorTypeReportPreserved': {'path': saved_types.relative_to(ROOT).as_posix(), 'sha256': sha(saved_types)},
        'currentTypeReport': {'path': copied_types.relative_to(ROOT).as_posix(), 'sha256': sha(copied_types)},
        'pageLayoutStoreRouterChanged': False, 'browserExecuted': False, 'realDatabaseWrites': False, 'businessVerified': False}
    output['status'] = 'directed_technical_checks_passed' if all(item['exitCode'] == 0 for item in results) and not changed and not current_types['newErrors'] else 'directed_technical_checks_not_passed'
    proof.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': output['status'], 'baselineErrors': output['baselineTypeErrors'], 'currentErrors': output['currentTypeErrors'], 'newErrors': len(output['newTypeErrors']), 'windowChanges': changed}, ensure_ascii=False))
    raise SystemExit(0 if output['status'] == 'directed_technical_checks_passed' else 1)


if __name__ == '__main__':
    main()
