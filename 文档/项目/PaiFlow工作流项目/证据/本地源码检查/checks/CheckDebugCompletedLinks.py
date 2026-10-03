"""复用既有本地链接检查，冻结Doc23校正/Doc24及新设计；不修改正文。"""
from contextlib import redirect_stdout
from datetime import datetime,timezone
import io,json,re,sys,shutil
import CheckCurrentPaiflowLinks as original

def main():
    expected={
      '文档/项目/PaiFlow工作流项目/完整迁移设计.md':'9cc2ba32f738245758df605bb454265eb989117d0a1e9e05c59c87c9547c624e',
      '文档/项目/PaiFlow工作流项目/设计资料核对记录.md':'45d6c250e9b72b5cf43b2a5b71dfaee88440a8ce8b24199af7d5f106db323ebd',
      'ruoyi-backend/ruoyi-paiflow/README.md':'085d311005c193e1b746e9814d7d3e90abc4e421ea034e53bf6233c5919fb260',
    }
    assert all(original.sha(original.ROOT/p)==h for p,h in expected.items())
    original.OUT=original.ROOT/'文档/项目/PaiFlow工作流项目/证据/本地源码检查/调试接续与变量预检完成后文档本地链接核对.json'
    resume='--resume-section-count-review' in sys.argv
    if resume:
        # 首执行已完成6文档扫描，错误把Doc+README新增18链当成Doc23独有。
        # 保全扫描原文，只完善统计解释，不再次读运行源码或重写历史检查。
        copy=original.OUT.parent/'checks/调试接续文档链接数量首执行原文.json'
        assert not copy.exists();shutil.copyfile(original.OUT,copy)
        code=0
    else:
        assert not original.OUT.exists(),'不覆盖本轮或历史链接证据'
        with redirect_stdout(io.StringIO()):code=original.main()
    result=json.loads(original.OUT.read_text(encoding='utf-8'))
    doc='文档/项目/PaiFlow工作流项目/设计资料核对记录.md';lines=(original.ROOT/doc).read_text(encoding='utf-8-sig').splitlines()
    item=next(v for v in result['files'] if v['path']==doc);sections=[]
    for n in (23,24):
        starts=[i+1 for i,line in enumerate(lines) if re.match(rf'^## {n}\.',line)];assert len(starts)==1
        start=starts[0];end=next((i+1 for i in range(start,len(lines)) if re.match(r'^## \d+\.',lines[i])),len(lines)+1)
        links=[v for v in item['links'] if start<=v['line']<end]
        sections.append({'number':n,'source':doc,'startLine':start,'endExclusiveLine':end,'localLinkCount':len(links),'bad':[v for v in links if v['status']!='valid']})
    assert sections[0]['localLinkCount']==16 and sections[1]['localLinkCount']==14
    assert not result['badLinks'] and not result['fragmentMismatches'] and not result['readWindowChanges']
    assert all(original.sha(original.ROOT/p)==h for p,h in expected.items())
    result.update({'finalizedAt':datetime.now(timezone.utc).isoformat(),'scope':'最终三主文档和backend/Python/frontend三README：Doc23计数校正、Doc24变量许可前事实及新纯迭代拟实现设计；不修改任何正文','expectedFrozenDocumentHashes':expected,'sections':sections,'priorChecksPreserved':['输入类型同步完成后文档本地链接核对.json','checks/调试接续前端阶段计数校正前原文/输入.json','checks/调试接续文档局部更新检查.json'],'sectionCountFinalizationRepair':{'firstAssertion':'误把Doc23+README共新增18链视为Doc23独有18','actualDoc23':16,'actualDoc24':14,'businessOrBrokenLinkFailure':False,'initialScanPreserved':resume},'apiIndexScope':'172route/105Java/562front/86support为先前已冻结debug批时点；新纯迭代Java候选与Worker变量preflight不计入该快照，不再重生成。','limitations':['链接存在与标题片段不是设计批准、源码实现、页面渲染或业务通过。','纯迭代设计新增段仍为拟实现；本核对不升级能力状态。']})
    original.OUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'path':original.OUT.relative_to(original.ROOT).as_posix(),'sha256':original.sha(original.OUT),'files':len(result['files']),'localLinks':result['localLinkCount'],'bad':result['badLinks'],'fragmentMismatches':result['fragmentMismatches'],'readWindowChanges':result['readWindowChanges'],'sections':sections},ensure_ascii=False,indent=2))
    return code
if __name__=='__main__':raise SystemExit(main())
