"""核对新Worker未改变既有解析字节码/依赖，明确沿用19例而非声称新执行。"""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).parent
MODULE = ROOT / 'ruoyi-backend/ruoyi-paismart'
worker = Path(sys.argv[1]).resolve()
assert worker.is_relative_to((MODULE / 'target').resolve()) and worker.is_dir()
previous = json.loads((EVIDENCE / '2026-10-03-终版解析进程复验/062900/results.json').read_text(encoding='utf-8'))
old_worker = Path(previous['workerBatch'])
assert previous['total'] == previous['passed'] == len(previous['results']) == 19
assert all(row['exitCode'] == 0 and row['expectedBodyFound'] is True for row in previous['results'])

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

old_libs = {p.name: sha(p) for p in (old_worker / 'lib').glob('*.jar')}
new_libs = {p.name: sha(p) for p in (worker / 'lib').glob('*.jar')}
assert old_libs == new_libs and len(new_libs) == 240, '解析依赖变化，不能沿用旧进程样例'
for artifact in previous['artifacts']:
    assert sha(Path(artifact['file'])) == artifact['sha256'], '原进程证据对应产物已变更'

prefixes = ('com/ruoyi/paismart/infrastructure/intelligence/',
            'com/ruoyi/paismart/infrastructure/Jsons',
            'com/ruoyi/paismart/application/intelligence/Values')
with zipfile.ZipFile(old_worker / 'ruoyi-paismart-3.9.2.jar') as old, zipfile.ZipFile(worker / 'ruoyi-paismart-3.9.2.jar') as new:
    names = {n for n in old.namelist() if n.endswith('.class') and n.startswith(prefixes)}
    current = {n for n in new.namelist() if n.endswith('.class') and n.startswith(prefixes)}
    assert names == current and names, '解析相关类清单变化'
    changed = [n for n in sorted(names) if old.read(n) != new.read(n)]
    assert not changed, ('解析相关类已变更，不能沿用旧样例', changed)
    classes = {n: hashlib.sha256(new.read(n)).hexdigest() for n in sorted(names)}
result = {'time': datetime.now().isoformat(), 'workerBatch': str(worker.relative_to(ROOT)),
          'previousEvidence': '2026-10-03-终版解析进程复验/062900/results.json',
          'newlyExecutedParserCases': 0, 'retainedParserCases': 19,
          'businessAcceptanceExecuted': False, 'parserClasses': classes,
          'dependencyJars': len(new_libs), 'changedClasses': changed,
          'impactJudgment': '解析相关字节码、完整240依赖和原进程证据产物摘要一致；本轮只改变评测与页面，无解析影响。19例沿用原批次结果，不冒称新批次再次启动。OS沙箱及真实业务格式验收仍未完成。'}
(EVIDENCE / '2026-10-03-验收准备补齐-解析基线沿用.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: v for k, v in result.items() if k != 'parserClasses'}, ensure_ascii=False, indent=2))
