"""只检查当前六份文档本地链接，保留批26原报告。"""
from contextlib import redirect_stdout
from datetime import datetime, timezone
import io
import json
import CheckCurrentPaiflowLinks as original

def main():
    evidence = original.ROOT / '文档/项目/PaiFlow工作流项目/证据/本地源码检查'
    proof = json.loads((evidence / 'checks/工作流YAML导出记录局部追加检查.json').read_text('utf-8'))
    index = json.loads((evidence / '工作流YAML导出接口索引冻结核对.json').read_text('utf-8'))
    expected = {
        '文档/项目/PaiFlow工作流项目/完整迁移设计.md': '7a53c616551b6af27a0259a0df9dfbe9a8d82826903bafcd04787f5c6bcbff53',
        '文档/项目/PaiFlow工作流项目/源码覆盖索引.md': index['metadataChange']['index']['sha256'],
        proof['record']['path']: proof['record']['sha256'],
        proof['readme']['path']: proof['readme']['sha256'],
    }
    assert all(original.sha(original.ROOT / path) == digest for path, digest in expected.items())
    original.OUT = evidence / '工作流YAML导出完成后文档本地链接核对.json'
    assert not original.OUT.exists()
    with redirect_stdout(io.StringIO()):
        code = original.main()
    result = json.loads(original.OUT.read_text('utf-8'))
    assert len(result['files']) == 6
    assert not result['badLinks'] and not result['fragmentMismatches'] and not result['readWindowChanges']
    assert all(original.sha(original.ROOT / path) == digest for path, digest in expected.items())
    result.update({'finalizedAt': datetime.now(timezone.utc).isoformat(),
        'scope': '现有三主文档及backend/Python/frontend三README，含YAML下载第27节；只查链接、标题片段及读取窗',
        'expectedFrozenDocumentHashes': expected,
        'apiIndexScope': '111Java/176routes/562front/95support；53实际执行与52不同身份、597含沿用严格分开',
        'priorCheckPreserved': '调试显示历史完成后文档本地链接核对.json', 'businessVerified': False, 'migrationComplete': False})
    original.OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'path': original.OUT.relative_to(original.ROOT).as_posix(), 'sha256': original.sha(original.OUT),
        'files': 6, 'localLinks': result['localLinkCount'], 'bad': len(result['badLinks']),
        'fragmentMismatches': len(result['fragmentMismatches']), 'readWindowChanges': result['readWindowChanges']}, ensure_ascii=False), flush=True)
    return code

if __name__ == '__main__':
    raise SystemExit(main())
