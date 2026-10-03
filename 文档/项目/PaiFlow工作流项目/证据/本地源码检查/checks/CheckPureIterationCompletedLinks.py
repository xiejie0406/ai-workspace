"""当前纯迭代接续完成后仅核本地文档链接；既有报告不覆盖。"""
from contextlib import redirect_stdout
from datetime import datetime,timezone
import io,json
import CheckCurrentPaiflowLinks as original

def main():
    expected={
        '文档/项目/PaiFlow工作流项目/完整迁移设计.md':'9cc2ba32f738245758df605bb454265eb989117d0a1e9e05c59c87c9547c624e',
        '文档/项目/PaiFlow工作流项目/设计资料核对记录.md':'be5f149707ce9d2abedec190ddded5a5738ef3bea192fb9db1502de815c783e1',
        '文档/项目/PaiFlow工作流项目/源码覆盖索引.md':'9aedd2ccf4a57d7785e4690870b55397b6e8958b8e777b99cd0ec77d63400deb',
        'ruoyi-backend/ruoyi-paiflow/README.md':'c8eae1f640fee5cadc623730906784478de52f0c7baa4a1c0261e7a58ced9da7',
    }
    assert all(original.sha(original.ROOT/p)==h for p,h in expected.items())
    original.OUT=original.ROOT/'文档/项目/PaiFlow工作流项目/证据/本地源码检查/纯迭代调试流完成后文档本地链接核对.json'
    assert not original.OUT.exists()
    with redirect_stdout(io.StringIO()):code=original.main()
    result=json.loads(original.OUT.read_text(encoding='utf-8'))
    assert not result['badLinks'] and not result['fragmentMismatches'] and not result['readWindowChanges']
    assert all(original.sha(original.ROOT/p)==h for p,h in expected.items())
    result.update({'finalizedAt':datetime.now(timezone.utc).isoformat(),'scope':'现三主文档+backend/Python/frontend三README本地链接/片段/读取窗检查；不改正文，不替代技术或业务验收','expectedFrozenDocumentHashes':expected,'apiIndexScope':'当前105Java/172routes/562front/89support；仅3条调试描述与source hash同步，业务验证仍0，contract fixture独立列证','businessVerified':False,'migrationComplete':False})
    original.OUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'path':original.OUT.relative_to(original.ROOT).as_posix(),'sha256':original.sha(original.OUT),'files':len(result['files']),'localLinks':result['localLinkCount'],'bad':len(result['badLinks']),'fragmentMismatches':len(result['fragmentMismatches']),'readWindowChanges':result['readWindowChanges']},ensure_ascii=False))
    return code
if __name__=='__main__':raise SystemExit(main())
