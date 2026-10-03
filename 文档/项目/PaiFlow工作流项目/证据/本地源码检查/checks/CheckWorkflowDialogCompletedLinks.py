"""显示历史记录完成后检查现有六份文档链接，保留旧报告。"""
from contextlib import redirect_stdout
from datetime import datetime, timezone
import io
import json
import CheckCurrentPaiflowLinks as original


def main():
    evidence = original.ROOT / '文档/项目/PaiFlow工作流项目/证据/本地源码检查'
    proof = json.loads((evidence / 'checks/调试显示历史记录局部追加检查.json').read_text(encoding='utf-8'))
    expected = {
        '文档/项目/PaiFlow工作流项目/完整迁移设计.md': 'e7f99dd271210b7f20afe9149d5e276977f25e8f053af6390ef46140ad25bb97',
        '文档/项目/PaiFlow工作流项目/源码覆盖索引.md': '3b68175f6855a2baf9bf46579e58521e08f52c345bfb933f86fdef74fb3c7d18',
        proof['currentDocument']['path']: proof['currentDocument']['sha256'],
        proof['currentReadme']['path']: proof['currentReadme']['sha256'],
    }
    assert all(original.sha(original.ROOT / p) == h for p, h in expected.items())
    original.OUT = evidence / '调试显示历史完成后文档本地链接核对.json'
    assert not original.OUT.exists()
    with redirect_stdout(io.StringIO()):
        code = original.main()
    result = json.loads(original.OUT.read_text(encoding='utf-8'))
    assert len(result['files']) == 6
    assert not result['badLinks'] and not result['fragmentMismatches'] and not result['readWindowChanges']
    assert all(original.sha(original.ROOT / p) == h for p, h in expected.items())
    result.update({
        'finalizedAt': datetime.now(timezone.utc).isoformat(),
        'scope': '原三主文档及backend/Python/frontend三README，含显示历史新增第26节；只查本地链接、片段及读取窗',
        'expectedFrozenDocumentHashes': expected,
        'apiIndexScope': '当前109Java/175routes/562front/93support；Maven执行期间227输入窗0与之后两份授权索引变化分开',
        'priorCheckPreserved': '纯迭代调试流完成后文档本地链接核对.json',
        'businessVerified': False, 'migrationComplete': False,
    })
    original.OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'path': original.OUT.relative_to(original.ROOT).as_posix(),
                      'sha256': original.sha(original.OUT), 'files': len(result['files']),
                      'localLinks': result['localLinkCount'], 'bad': len(result['badLinks']),
                      'fragmentMismatches': len(result['fragmentMismatches']),
                      'readWindowChanges': result['readWindowChanges']}, ensure_ascii=False))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
