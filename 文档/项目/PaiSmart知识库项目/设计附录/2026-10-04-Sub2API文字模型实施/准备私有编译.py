"""复制本轮当前main源码用于私有编译，不启动服务，不复制Java测试或覆盖共享制品。"""
from pathlib import Path
import csv
import hashlib
import json
import os
import shutil
import uuid

root = next(p for p in Path(__file__).resolve().parents if (p / 'ruoyi-backend/pom.xml').is_file())
evidence = Path(__file__).resolve().parent
stage = root / '.runtime' / ('paismart-sub2api-20261004-' + uuid.uuid4().hex[:12])
backend = stage / 'backend'
frontend = stage / 'frontend'
backend.mkdir(parents=True)
frontend.mkdir()
source_rows = []

def copied(src, dst):
    dst.parent.mkdir(parents=True, exist_ok=True)
    data = src.read_bytes()
    dst.write_bytes(data)
    source_rows.append({'source': src.relative_to(root).as_posix(), 'snapshot': dst.relative_to(stage).as_posix(),
                        'sha256': hashlib.sha256(data).hexdigest()})

for pom in (root / 'ruoyi-backend').rglob('pom.xml'):
    relative = pom.relative_to(root / 'ruoyi-backend')
    if any(p in {'target', '_reference', 'node_modules', '.git'} for p in relative.parts):
        continue
    copied(pom, backend / relative)
    main = pom.parent / 'src/main'
    if main.is_dir():
        for src in main.rglob('*'):
            if src.is_file():
                copied(src, backend / src.relative_to(root / 'ruoyi-backend'))

excluded = {'node_modules', 'dist', 'target', '.git', '.vite', '_reference', 'coverage', 'test-results', 'playwright-report'}
for directory, folders, files in os.walk(root / 'frontend/admin-web'):
    folders[:] = [folder for folder in folders if folder not in excluded]
    for filename in files:
        src = Path(directory) / filename
        if not filename.startswith('.env.local'):
            copied(src, frontend / src.relative_to(root / 'frontend/admin-web'))

for folder in ['contracts/paismart', 'contracts/aipicture/v1', 'contracts/aippt/catalog']:
    for src in (root / folder).rglob('*'):
        if src.is_file():
            copied(src, stage / src.relative_to(root))
manifest = root / 'contracts/aippt/catalog-manifest.json'
if manifest.is_file():
    copied(manifest, stage / manifest.relative_to(root))

with (evidence / '私有编译源码摘要.csv').open('w', encoding='utf-8-sig', newline='') as handle:
    writer = csv.DictWriter(handle, fieldnames=['source', 'snapshot', 'sha256'])
    writer.writeheader()
    writer.writerows(source_rows)
location = {'stage': str(stage), 'backend': str(backend), 'frontend': str(frontend),
            'frontendOutput': str(stage / 'frontend-dist'), 'testSourcesCopied': False,
            'authorization': '离线源码实现及私有编译；Java不复制或编译测试、不安装依赖、不启动业务、不切换运行包'}
(evidence / '私有编译定位.json').write_text(json.dumps(location, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'stage': str(stage), 'mainAndFrontendFiles': len(source_rows)}, ensure_ascii=True))
