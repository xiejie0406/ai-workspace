"""只修正实际指向本包文件的旧顶层 from 导入，保留原算法及原文。"""
import ast
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
EVIDENCE = Path(__file__).resolve().parent
RUNTIME = ROOT / 'python/paiflow-runtime'
changes = []
for package in ('agent', 'plugin/link', 'plugin/aitools'):
    base = RUNTIME / package
    prefix = package.replace('/', '.')
    for path in base.rglob('*.py'):
        original = path.read_text(encoding='utf-8-sig')
        lines = original.splitlines(keepends=True)
        imports = []
        for item in ast.walk(ast.parse(original)):
            if not isinstance(item, ast.ImportFrom) or item.level or not item.module:
                continue
            local = base.joinpath(*item.module.split('.'))
            if not (local.with_suffix('.py').exists() or local.is_dir()):
                continue
            before = lines[item.lineno - 1]
            after = re.sub(r'^(\s*from\s+)' + re.escape(item.module) + r'(\s+import\b)',
                           lambda match: match[1] + prefix + '.' + item.module + match[2], before)
            if after != before:
                lines[item.lineno - 1] = after
                imports.append({'line': item.lineno, 'before': item.module, 'after': prefix + '.' + item.module})
        if not imports:
            continue
        edited = ''.join(lines)
        ast.parse(edited, filename=str(path))
        relative = path.relative_to(ROOT)
        backup = EVIDENCE / '修改前原文/worker' / relative
        backup.parent.mkdir(parents=True, exist_ok=True)
        if not backup.exists():
            shutil.copy2(path, backup)
        path.write_text(edited, encoding='utf-8', newline='')
        changes.append({'file': str(relative), 'imports': imports})
(EVIDENCE / 'Worker包内导入修正.json').write_text(json.dumps(changes, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'files': len(changes), 'imports': sum(len(item['imports']) for item in changes)}, ensure_ascii=False))
