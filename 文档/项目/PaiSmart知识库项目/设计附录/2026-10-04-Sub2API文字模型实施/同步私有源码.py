"""编译前同步本功能当前源码到本轮私有目录；不覆盖共享制品。"""
from pathlib import Path
import csv
import hashlib
import json

evidence = Path(__file__).resolve().parent
root = next(p for p in evidence.parents if (p / 'ruoyi-backend/pom.xml').is_file())
location = json.loads((evidence / '私有编译定位.json').read_text(encoding='utf-8'))
stage = Path(location['stage'])
rows = list(csv.DictReader((evidence / '私有编译源码摘要.csv').open(encoding='utf-8-sig')))
prefixes = ('ruoyi-backend/ruoyi-paismart/src/main/', 'frontend/admin-web/src/views/paismart/', 'frontend/admin-web/src/api/paismart/', 'frontend/admin-web/src/types/paismart/', 'contracts/paismart/')
updated = []
known = {row['source'] for row in rows}
resources = [p for folder in ['contracts/aipicture/v1', 'contracts/aippt/catalog'] for p in (root / folder).rglob('*') if p.is_file()]
resources.append(root / 'contracts/aippt/catalog-manifest.json')
for src in resources:
    relative = src.relative_to(root).as_posix()
    if src.is_file() and relative not in known:
        dst = stage / relative
        dst.parent.mkdir(parents=True, exist_ok=True)
        data = src.read_bytes()
        dst.write_bytes(data)
        rows.append({'source': relative, 'snapshot': relative, 'sha256': hashlib.sha256(data).hexdigest()})
        updated.append(relative)
for row in rows:
    if not row['source'].startswith(prefixes):
        continue
    src, dst = root / row['source'], stage / row['snapshot']
    data = src.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if row['sha256'] != digest:
        dst.write_bytes(data)
        row['sha256'] = digest
        updated.append(row['source'])
with (evidence / '私有编译源码摘要.csv').open('w', encoding='utf-8-sig', newline='') as handle:
    writer = csv.DictWriter(handle, fieldnames=['source', 'snapshot', 'sha256'])
    writer.writeheader()
    writer.writerows(rows)
print(json.dumps({'updated': updated}, ensure_ascii=False))
