"""复用062900的19份不可变输入及原判定；仅由已授权执行者调用本批Worker。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[5]
MODULE = ROOT / 'ruoyi-backend/ruoyi-paismart'
BASELINE = Path(__file__).resolve().parent.parent / '2026-10-03-终版解析进程复验/062900/results.json'
BASELINE_HASH = '8135e67395430143933a8c40adfff54b588db94a69fe78fbd135f8c6cc4e84b6'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if len(sys.argv) != 2:
        raise SystemExit('用法：python 实际解析CLI复验.py <本模块target下已审核的本批Worker目录>')
    worker = Path(sys.argv[1]).resolve()
    if not worker.is_relative_to((MODULE / 'target').resolve()) or not worker.is_dir():
        raise SystemExit('必须使用本模块target下已审核的本批Worker目录')
    jars = list(worker.glob('ruoyi-paismart-*.jar'))
    if len(jars) != 1 or not (worker / 'lib').is_dir():
        raise SystemExit('主JAR未唯一确认或依赖目录缺失')
    if sha256(BASELINE) != BASELINE_HASH:
        raise SystemExit('062900原基线已变化，停止复验并核对原证据')
    baseline = json.loads(BASELINE.read_text(encoding='utf-8'))
    if baseline.get('total') != 19 or len(baseline.get('results', [])) != 19:
        raise SystemExit('原基线不是固定19项')
    fixtures = []
    for row in baseline['results']:
        name = row['file']
        if Path(name).name != name or not row['expected']:
            raise SystemExit('原基线样例名称或预期不合法')
        input_path = BASELINE.parent / name / 'input'
        content = input_path.read_bytes()
        if hashlib.sha256(content).hexdigest() != row['sha256']:
            raise SystemExit('原输入摘要已变化：' + name)
        if name.startswith('test') and sha256(MODULE / 'src/test/resources/paismart/format-samples' / name) != row['sha256']:
            raise SystemExit('当前源码样例与原输入不同：' + name)
        fixtures.append((name, content, row['expected']))
    classpath = os.pathsep.join([str(jars[0]), str(worker / 'lib/*')])
    java = shutil.which('java')
    if java is None:
        raise SystemExit('未找到已安装Java')
    run = Path(__file__).resolve().parent / '实际解析CLI复验' / datetime.now().strftime('%H%M%S-%f')
    run.mkdir(parents=True, exist_ok=False)

    def parse(fixture):
        name, content, expected = fixture
        case = run / name
        case.mkdir()
        input_path = case / 'input'
        input_path.write_bytes(content)
        output = case / 'body.json'
        temporary = case / 'temp'
        temporary.mkdir()
        command = [java, '-Xmx256m', '-Djava.awt.headless=true', '-Djava.io.tmpdir=' + str(temporary),
                   '-cp', classpath, 'com.ruoyi.paismart.infrastructure.intelligence.ParserWorker',
                   str(input_path), str(output), '100', '200000']
        environment = {key: value for key, value in os.environ.items() if key.upper() in {'PATH', 'SYSTEMROOT', 'TMP', 'TEMP'}}
        result = subprocess.run(command, cwd=case, env=environment, capture_output=True, timeout=50)
        (case / 'stderr.log').write_bytes(result.stderr)
        (case / 'stdout.log').write_bytes(result.stdout)
        row = {'file': name, 'sha256': hashlib.sha256(content).hexdigest(), 'expected': expected,
               'exitCode': result.returncode, 'command': command}
        if output.is_file():
            document = json.loads(output.read_text(encoding='utf-8'))
            body = '\n'.join(page['text'] for page in document.get('pages', []))
            row.update({'pageCount': len(document.get('pages', [])), 'textChars': len(body),
                        'expectedBodyFound': expected in body, 'excerpt': body[:250]})
        row['passed'] = result.returncode == 0 and row.get('expectedBodyFound', False)
        print(name + ' passed=' + str(row['passed']) + ' chars=' + str(row.get('textChars')), flush=True)
        return row

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(parse, fixtures))
    artifacts = [jars[0]] + sorted((worker / 'lib').glob('*.jar'))
    report = {'time': datetime.now().isoformat(),
              'scope': '原062900的19份固定/自制脱敏样例在本批独立Worker的技术复验；没有OS沙箱或真实业务验收',
              'baseline': str(BASELINE), 'baselineSha256': BASELINE_HASH,
              'workerBatch': str(worker),
              'artifacts': [{'file': str(path), 'sha256': sha256(path)} for path in artifacts],
              'results': results, 'passed': sum(row['passed'] for row in results), 'total': len(results)}
    (run / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('evidence=' + str(run), flush=True)
    raise SystemExit(any(not row['passed'] for row in results))


if __name__ == '__main__':
    main()
