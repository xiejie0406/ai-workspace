"""仅冻结生产源码并准备 PaiCLI 私有构建目录；不执行测试、业务、DDL 或运行包同步。"""
from pathlib import Path
import hashlib
import json
import shutil
import re
from datetime import datetime

ROOT = Path(__file__).resolve().parents[5]
EVIDENCE = Path(__file__).resolve().parent
BUILD = ROOT / '.runtime/paicli/delivery-20261003-logic'
if BUILD.exists():
    raise SystemExit('私有目录已存在，避免覆盖已冻结内容')
BUILD.mkdir(parents=True)
entries = []

def copy(source, destination):
    source = ROOT / source
    destination = BUILD / destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    content = source.read_bytes()
    destination.write_bytes(content)
    entries.append({'source': str(source.relative_to(ROOT)).replace('\\', '/'),
                    'snapshot': str(destination.relative_to(BUILD)).replace('\\', '/'),
                    'sha256': hashlib.sha256(content).hexdigest(), 'size': len(content)})

def module(source, destination, benchmarks=False):
    copy(f'{source}/pom.xml', f'{destination}/pom.xml')
    for subdir in ('src/main', 'benchmarks') if benchmarks else ('src/main',):
        for file in sorted((ROOT / source / subdir).rglob('*')):
            if file.is_file():
                relative = file.relative_to(ROOT / source).as_posix()
                copy(f'{source}/{relative}', f'{destination}/{relative}')

copy('ruoyi-backend/ruoyi-paicli/pom.xml', 'native/pom.xml')
native = BUILD / 'native/pom.xml'
native.write_text(native.read_text(encoding='utf-8').replace('    <module>paicli-api</module>', ''), encoding='utf-8')
for name in ('core', 'runner', 'cli', 'eval'):
    module(f'ruoyi-backend/ruoyi-paicli/paicli-{name}', f'native/paicli-{name}', name == 'eval')

copy('ruoyi-backend/pom.xml', 'center/pom.xml')
center = BUILD / 'center/pom.xml'
text = center.read_text(encoding='utf-8')
text = re.sub(r'<modules>.*?</modules>', '<modules><module>ruoyi-common</module><module>ruoyi-system</module><module>ruoyi-framework</module><module>ruoyi-paicli/paicli-api</module></modules>', text, count=1, flags=re.S)
center.write_text(text, encoding='utf-8')
for name in ('common', 'system', 'framework'):
    module(f'ruoyi-backend/ruoyi-{name}', f'center/ruoyi-{name}')
module('ruoyi-backend/ruoyi-paicli/paicli-api', 'center/ruoyi-paicli/paicli-api')

record = {'recordedAt': datetime.now().astimezone().isoformat(), 'root': str(ROOT), 'build': str(BUILD),
          'scope': '仅生产源码/资源/POM及Eval既有配方；未复制测试、账号配置或共享运行产物',
          'snapshotOnlyPomChanges': ['native父聚合移除API（API单独按Boot父POM构建）', 'center父聚合仅保留三个共享依赖和PaiCLI API'],
          'files': entries}
(EVIDENCE / '逻辑交付源码冻结.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'build': str(BUILD), 'fileCount': len(entries)}, ensure_ascii=False))
