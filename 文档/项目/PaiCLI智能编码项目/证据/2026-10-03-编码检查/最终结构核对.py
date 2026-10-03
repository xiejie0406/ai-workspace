"""核对本次契约引用及所属文档相对链接，不访问网络或修改真实配置。"""
import json
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit
import yaml

evidence = Path(__file__).resolve().parent
root = evidence.parents[4]
contract = root / 'contracts/paicli/runner-v1.openapi.yaml'
document = yaml.safe_load(contract.read_text(encoding='utf-8-sig'))
refs = []
failures = []
def visit(value):
    if isinstance(value, dict):
        if '$ref' in value:
            reference = value['$ref']
            refs.append(reference)
            target = document
            try:
                if not reference.startswith('#/'):
                    raise ValueError('仅期望当前契约内引用')
                for part in reference[2:].split('/'):
                    target = target[part.replace('~1', '/').replace('~0', '~')]
            except (KeyError, TypeError, ValueError):
                failures.append({'kind': 'OPENAPI_REF', 'ref': reference})
        for item in value.values():
            visit(item)
    elif isinstance(value, list):
        for item in value:
            visit(item)
visit(document)
contract_report = {'openapi': document.get('openapi'), 'pathCount': len(document['paths']),
                   'referenceCount': len(refs), 'failures': failures.copy(), 'businessPassed': 0}
(evidence / '契约结构检查.json').write_text(json.dumps(contract_report, ensure_ascii=False, indent=2), encoding='utf-8')

files = list((root / '文档/项目/PaiCLI智能编码项目').rglob('*.md'))
files += list((root / '文档/项目/PaiCLI智能编码项目').rglob('*.html'))
files += [root / '文档/README.md', root / '文档/项目/README.md', root / 'ruoyi-backend/ruoyi-paicli/README.md']
links = 0
for file in files:
    source = file.read_text(encoding='utf-8-sig')
    targets = re.findall(r'!?\[[^\]]*\]\((?:<([^>]+)>|([^\s)]+))(?:\s+[^)]*)?\)', source) if file.suffix == '.md' else []
    values = [a or b for a, b in targets]
    if file.suffix == '.html':
        values += re.findall(r'(?:href|src)=["\']([^"\']+)["\']', source)
    for value in values:
        parsed = urlsplit(value)
        if parsed.scheme or parsed.netloc or not parsed.path:
            continue
        links += 1
        target = file.parent / unquote(parsed.path)
        if not target.exists():
            failures.append({'kind': 'RELATIVE_LINK', 'file': str(file.relative_to(root)), 'target': value})
link_report = {'fileCount': len(files), 'relativeLinks': links,
               'failures': [item for item in failures if item['kind'] == 'RELATIVE_LINK']}
(evidence / '文档链接检查.json').write_text(json.dumps(link_report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'contract': contract_report, 'links': link_report}, ensure_ascii=False))
raise SystemExit(bool(failures))
