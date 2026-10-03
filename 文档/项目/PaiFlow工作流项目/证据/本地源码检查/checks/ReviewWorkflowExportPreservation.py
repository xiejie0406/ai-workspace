"""本批局部差异保全：只读当前原文；不以旧HEAD替代未提交文件。"""
from pathlib import Path
import difflib
import hashlib
import json

ROOT = Path(__file__).resolve().parents[6]
DOC = ROOT / '文档/项目/PaiFlow工作流项目'
CHECKS = Path(__file__).resolve().parent

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def read(p):
    return json.loads(p.read_text('utf-8-sig'))

def main():
    out = CHECKS / '工作流YAML导出局部范围保全核对.json'
    assert not out.exists()
    inv = ROOT / 'contracts/paiflow/source-api-inventory.json'
    snapshot = read(inv)
    maps = {k: snapshot[k] for k in ('targetSnapshotHashes', 'targetFrontendSnapshotHashes', 'targetSupportingSnapshotHashes')}
    runtime = ROOT / 'python/paiflow-runtime/verification/variable-scope-stage.json'
    rt = read(runtime)['sourceAfter']
    assert len(rt) == 478
    design = DOC / '完整迁移设计.md'
    old = CHECKS / '工作流YAML导出设计修改前原文/完整迁移设计.md'
    front = ROOT / 'frontend/paiflow-web/src/utils/http.ts'
    old_front = CHECKS / '工作流YAML下载JSON错误判别修改前原文/http.ts'
    manifest_path = DOC / '附录/原前端引入清单.json'
    manifest = read(manifest_path)
    files = {inv, runtime, design, old, front, old_front, manifest_path}
    files.update(ROOT / p for d in maps.values() for p in d)
    files.update(ROOT / 'python/paiflow-runtime' / p for p in rt)
    files.update(ROOT / manifest[k] / row['path'] for k in ('source', 'target') for row in manifest['files'])
    before = {p.relative_to(ROOT).as_posix(): sha(p) for p in files}
    changes = {k: [{'path': p, 'before': h, 'after': sha(ROOT / p)} for p, h in d.items() if sha(ROOT / p) != h] for k, d in maps.items()}
    assert not changes['targetSnapshotHashes'] and not changes['targetSupportingSnapshotHashes']
    assert [d['path'] for d in changes['targetFrontendSnapshotHashes']] == ['frontend/paiflow-web/src/utils/http.ts']
    rt_changes = [p for p, h in rt.items() if sha(ROOT / 'python/paiflow-runtime' / p) != h]
    assert not rt_changes
    previous = old.read_bytes().decode('utf-8')
    current = design.read_bytes().decode('utf-8')
    ops = [op for op in difflib.SequenceMatcher(None, previous, current, autojunk=False).get_opcodes() if op[0] != 'equal']
    assert len(ops) == 1 and ops[0][0] == 'insert'
    paragraph = current[ops[0][3]:ops[0][4]]
    assert '**原工作流YAML下载。**' in paragraph
    current_http = front.read_bytes().decode('utf-8')
    previous_http = old_front.read_bytes().decode('utf-8')
    # 删除完整新增注释和判别块，随后恢复原三处临时URL名称。
    start = current_http.index('      // 平台认证过滤器可用HTTP 200返回错误JSON；')
    end = current_http.index('      // 创建临时URL和a标签触发下载', start)
    guard = current_http[start:end]
    restored = current_http[:start] + current_http[end:]
    restored = restored.replace('const blobUrl = URL.createObjectURL(blob);', 'const url = URL.createObjectURL(blob);')
    restored = restored.replace('link.href = blobUrl;', 'link.href = url;').replace('URL.revokeObjectURL(blobUrl);', 'URL.revokeObjectURL(url);')
    assert restored == previous_http and 'application/json' in guard
    adapted, missing, reference_changed = [], [], []
    for row in manifest['files']:
        source, target = (ROOT / manifest[k] / row['path'] for k in ('source', 'target'))
        if not target.is_file():
            missing.append(row['path'])
            continue
        if sha(source) != row['sha256']:
            reference_changed.append(row['path'])
        if sha(target) != row['sha256']:
            adapted.append(row['path'])
    assert len(manifest['files']) == 1703 and len(adapted) == 12 and not missing and not reference_changed
    after = {p.relative_to(ROOT).as_posix(): sha(p) for p in files}
    window = [p for p in before if before[p] != after[p]]
    assert not window
    result = {'scope': '只补原YAML下载，沿用React外壳；本批既有前端仅HTTP工具一处局部差异',
        'sourceHashesBefore': before, 'sourceHashesAfter': after, 'sourceReadWindowChanges': window,
        'previousJavaSourcesCompared': 109, 'previousJavaChanges': changes['targetSnapshotHashes'],
        'previousSupportingCompared': 93, 'previousSupportingChanges': changes['targetSupportingSnapshotHashes'],
        'frontendCompared': 562, 'frontendChanges': changes['targetFrontendSnapshotHashes'],
        'frontGuardAndThreeTemporaryUrlRenamesExactlyInvertToPreservedOriginal': True,
        'designOnlyOneParagraphInserted': True, 'insertedDesignParagraph': paragraph,
        'originalFrontendImported': 1703, 'originalFrontendByteIdentical': 1691, 'existingAdaptedFrontendFiles': adapted,
        'runtimeCompared': 478, 'runtimeChangesFromLast271Execution': rt_changes,
        'runtime271Reference': {'path': runtime.relative_to(ROOT).as_posix(), 'sha256': sha(runtime)},
        'runtime271Rerun': False, 'frontendPrior110Rerun': False,
        'firstPrivateCheckLimitation': '首私有删除范围只包含if块，未包含新增注释，反向还原断言未产出结果；本次核对完整插入块，不修改生产源。首私有输出未单独持久化，不能追溯称其执行窗为零。',
        'businessVerified': False, 'migrationComplete': False}
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'proofSha256': sha(out), 'sourceInputs': len(files), 'frontendChanges': 1, 'runtimeChanges': len(rt_changes), 'readWindowChanges': window}, ensure_ascii=False), flush=True)

if __name__ == '__main__':
    main()
