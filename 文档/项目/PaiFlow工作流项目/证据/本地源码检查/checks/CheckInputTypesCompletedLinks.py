"""复用现有链接核对，读取Root design ready及Doc22，不改文档正文或旧证据。"""
from contextlib import redirect_stdout
from datetime import datetime,timezone
import io,json,re
import CheckCurrentPaiflowLinks as original

def main():
    original.OUT=original.ROOT/'文档/项目/PaiFlow工作流项目/证据/本地源码检查/输入类型同步完成后文档本地链接核对.json'
    with redirect_stdout(io.StringIO()):code=original.main()
    output=json.loads(original.OUT.read_text(encoding='utf-8'))
    doc='文档/项目/PaiFlow工作流项目/设计资料核对记录.md'
    lines=(original.ROOT/doc).read_text(encoding='utf-8-sig').splitlines()
    start=next(i+1 for i,line in enumerate(lines) if re.match(r'^## 22\.',line))
    end=next((i+1 for i in range(start,len(lines)) if re.match(r'^## \d+\.',lines[i])),len(lines)+1)
    item=next(v for v in output['files'] if v['path']==doc)
    section=[v for v in item['links'] if start<=v['line']<end]
    output['scope']='Root design ready后的三主文档与backend/Python/frontend三README，包含Doc22；未修改任何正文'
    output['section22']={'source':doc,'startLine':start,'endExclusiveLine':end,'localLinkCount':len(section),'bad':[v for v in section if v['status']!='valid']}
    output['priorChecksPreserved']=['当前文档本地链接独立核对.json','纯迭代完成后文档本地链接核对.json','静态问答完成后文档本地链接核对.json']
    output['finalizedAt']=datetime.now(timezone.utc).isoformat()
    original.OUT.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'path':original.OUT.relative_to(original.ROOT).as_posix(),'sha256':original.sha(original.OUT),'files':len(output['files']),'localLinks':output['localLinkCount'],'badLinks':output['badLinks'],'fragmentMismatches':output['fragmentMismatches'],'sourceWindowChanges':output['readWindowChanges'],'section22':output['section22']},ensure_ascii=False,indent=2))
    return code
if __name__=='__main__':raise SystemExit(main())
