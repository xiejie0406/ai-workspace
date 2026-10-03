"""默认列出快照。显式选择文件并加 --restore 才会恢复，先备份当前内容。"""
from pathlib import Path
from datetime import datetime
import argparse,base64,hashlib,json

feature=Path(__file__).resolve().parents[2]
snapshot=Path(__file__).with_name('原文快照.json')
parser=argparse.ArgumentParser(description='恢复明确选中的设计重构前文件')
parser.add_argument('--file',action='append',default=[],help='快照中的完整相对路径，可重复指定')
parser.add_argument('--restore',action='store_true',help='恢复选中文件；先保留当前原始字节')
args=parser.parse_args()
records=json.loads(snapshot.read_text(encoding='utf-8'))['files']
chosen=[]
for name in args.file:
    match=next((x for x in records if x['path']==name),None)
    if match is None:parser.error('文件不在快照中：'+name)
    chosen.append(match)
if not chosen:
    for x in records:print(x['path'])
    raise SystemExit(0)
if args.restore:
    before=[]
    for x in chosen:
        target=(feature/x['path']).resolve()
        if not target.is_relative_to(feature):raise ValueError('目标越出功能目录')
        if target.exists():
            raw=target.read_bytes();before.append({'path':x['path'],'sha256':hashlib.sha256(raw).hexdigest(),'base64':base64.b64encode(raw).decode('ascii')})
    stamp=datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    Path(__file__).with_name('恢复前当前内容-'+stamp+'.json').write_text(json.dumps({'files':before},ensure_ascii=False,indent=2),encoding='utf-8')
for x in chosen:
    raw=base64.b64decode(x['base64'])
    if hashlib.sha256(raw).hexdigest()!=x['sha256']:raise ValueError('快照内容摘要不一致')
    target=(feature/x['path']).resolve()
    if not target.is_relative_to(feature):raise ValueError('目标越出功能目录')
    if args.restore:
        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
    print(('已恢复：' if args.restore else '待恢复（未写入）：')+x['path'])
