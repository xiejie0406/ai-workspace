"""刷新已有私有构建快照；只复制生产内容，不运行测试或业务。"""
from pathlib import Path
import json, hashlib, re
from datetime import datetime
E=Path(__file__).resolve().parent
ROOT=E.parents[4]
record=json.loads((E/'逻辑交付源码冻结.json').read_text(encoding='utf-8'))
B=Path(record['build'])
back=E/'逻辑验收修复前'
# 两处短补丁前原文从仍保留的首次生产快照取回，避免以Git替换未提交原文。
for name in ('WechatTaskSession.java','WechatMessageLoop.java'):
    if not (back/name).exists():
        (back/name).write_bytes((B/'native/paicli-cli/src/main/java/com/paicli/wechat'/name).read_bytes())
changes=[]
for entry in record['files']:
    source=ROOT/entry['source']; dest=B/entry['snapshot']; content=source.read_bytes()
    digest=hashlib.sha256(content).hexdigest()
    if entry['sha256']!=digest: changes.append(entry['source'])
    entry.update(sha256=digest,size=len(content))
    if entry['snapshot']=='native/pom.xml':
        content=content.decode('utf-8').replace('    <module>paicli-api</module>', '').encode('utf-8')
    elif entry['snapshot']=='center/pom.xml':
        content=re.sub(r'<modules>.*?</modules>','<modules><module>ruoyi-common</module><module>ruoyi-system</module><module>ruoyi-framework</module><module>ruoyi-paicli/paicli-api</module></modules>',content.decode('utf-8'),count=1,flags=re.S).encode('utf-8')
    if dest.read_bytes()!=content: dest.write_bytes(content)
record['recordedAt']=datetime.now().astimezone().isoformat()
record['updatedSinceFirstFreeze']=changes
(E/'逻辑交付最终源码冻结.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'productionFiles':len(record['files']),'changedFiles':changes},ensure_ascii=False))
