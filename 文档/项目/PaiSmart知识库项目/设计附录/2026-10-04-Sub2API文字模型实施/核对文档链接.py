"""检查本轮维护文档与报告的本地入口；不检查业务或远端页面。"""
from pathlib import Path
from urllib.parse import unquote
from collections import Counter
import json
import re

evidence = Path(__file__).resolve().parent
root = next(p for p in evidence.parents if (p / 'ruoyi-backend/pom.xml').is_file())
paths = ['文档/项目/PaiSmart知识库项目/Sub2API文字模型接入实现文档.md', '文档/项目/PaiSmart知识库项目/完整迁移设计.md', 'ruoyi-backend/ruoyi-paismart/README.md', 'contracts/paismart/README.md']
paths.append((evidence / '实施核对报告.html').relative_to(root).as_posix())
def references(source):
    return re.findall(r'\]\(([^)]+)\)', source) + re.findall(r'href="([^"]+)"', source)
results = []
for relative in paths:
    path = root / relative
    refs = references(path.read_text(encoding='utf-8'))
    prior = evidence / '修改前原文' / relative
    baseline = Counter(references(prior.read_text(encoding='utf-8'))) if prior.is_file() else Counter()
    for ref in refs:
        ref = ref.strip('<>')
        if ref.startswith(('#', 'http:', 'https:', 'codex:', 'app:', 'data:', 'mailto:')):
            continue
        destination = unquote(ref.split('#', 1)[0])
        if not destination:
            continue
        target = path.parent / destination
        introduced = baseline[ref] == 0
        if baseline[ref] > 0:
            baseline[ref] -= 1
        results.append({'source': relative, 'target': ref, 'exists': target.exists(), 'introduced': introduced})
missing = [row for row in results if not row['exists']]
result = {'scope': '本轮本地文件/目录存在性，未全面检查标题锚点或远端网页', 'count': len(results), 'missing': missing, 'newMissing': [row for row in missing if row['introduced']]}
(evidence / '文档链接核对.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
if result['newMissing']:
    raise SystemExit('本轮有新增缺失入口')
