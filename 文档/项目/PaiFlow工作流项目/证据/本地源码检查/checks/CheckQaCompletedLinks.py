"""复用既有链接检查器，只读当前主文档/README及第21节；保全旧检查。"""
from contextlib import redirect_stdout
from datetime import datetime, timezone
import io
import json
import re
import CheckCurrentPaiflowLinks as original

def main():
    original.OUT=original.ROOT/'文档/项目/PaiFlow工作流项目/证据/本地源码检查/静态问答完成后文档本地链接核对.json'
    with redirect_stdout(io.StringIO()):code=original.main()
    output=json.loads(original.OUT.read_text(encoding='utf-8'))
    doc='文档/项目/PaiFlow工作流项目/设计资料核对记录.md'
    lines=(original.ROOT/doc).read_text(encoding='utf-8-sig').splitlines()
    start=next(i+1 for i,line in enumerate(lines) if re.match(r'^## 21\.',line))
    end=next((i+1 for i in range(start,len(lines)) if re.match(r'^## \d+\.',lines[i])),len(lines)+1)
    doc_record=next(item for item in output['files'] if item['path']==doc)
    section=[item for item in doc_record['links'] if start<=item['line']<end]
    output['scope']='当前完整迁移设计/源码覆盖索引/设计资料核对记录三主文档与backend/Python/frontend三README，包含第21节；未修改正文'
    output['section21']={'source':doc,'startLine':start,'endExclusiveLine':end,'localLinkCount':len(section),'bad':[item for item in section if item['status']!='valid']}
    output['priorChecksPreserved']=['当前文档本地链接独立核对.json','纯迭代完成后文档本地链接核对.json']
    output['finalizedAt']=datetime.now(timezone.utc).isoformat()
    original.OUT.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'path':original.OUT.relative_to(original.ROOT).as_posix(),'sha256':original.sha(original.OUT),'files':len(output['files']),'localLinks':output['localLinkCount'],'badLinks':output['badLinks'],'fragmentMismatches':output['fragmentMismatches'],'sourceWindowChanges':output['readWindowChanges'],'section21':output['section21']},ensure_ascii=False,indent=2))
    return code
if __name__=='__main__':raise SystemExit(main())
