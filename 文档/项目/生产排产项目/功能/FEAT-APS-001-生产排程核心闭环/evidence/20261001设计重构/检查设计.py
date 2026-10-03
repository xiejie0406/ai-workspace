from pathlib import Path
import json,re,base64,hashlib,subprocess
from datetime import datetime,timezone,timedelta
from urllib.parse import unquote
import yaml
from PIL import Image

ROOT=Path(r'D:\2025Ai\26-05-23\java-workspace')
F=ROOT/'文档/项目/生产排产项目/功能/FEAT-APS-001-生产排程核心闭环'
WF=F/'线框图'
txt=(WF/'页面与字段确认线框.html').read_text(encoding='utf-8')
pages=json.loads(re.search(r'<script type="application/json" id="catalog">(.*?)</script>',txt,re.S).group(1))
snapshot=json.loads((F/'归档/页面设计重构前原文-20261001/原文快照.json').read_text(encoding='utf-8'))
prior=next(x for x in snapshot['files'] if x['path']=='线框图/页面与字段确认线框.html')
oldtext=base64.b64decode(prior['base64']).decode('utf-8')
old=json.loads(re.search(r'<script[^>]*id="catalog"[^>]*>(.*?)</script>',oldtext,re.S).group(1))
md=(F/'页面与字段设计方案.md').read_text(encoding='utf-8')
api=yaml.safe_load((ROOT/'contracts/aps/openapi/aps-api-v1.yaml').read_text(encoding='utf-8'))
checks=[]
def check(name,ok,detail=''):
    checks.append(dict(name=name,result='通过' if ok else '未通过',detail=detail))
check('列表及内部对应保留',len(pages)==34 and [p['id'] for p in pages]==[p['id'] for p in old])
check('列表字段逐项保留',all(p['columns']==o['columns'] or [(x['label'],x['example'],x['rule']) for x in p['columns']]==[(x['label'],x['example'],x['rule']) for x in o['columns']] for p,o in zip(pages,old)),str(sum(len(p['columns']) for p in pages)))
check('九模块六段内容完整',all(all(re.search(r'^### '+str(n)+r'\.'+str(k)+r' ',md,re.M) for k in range(1,7)) for n in range(1,10)))
forms=[f for p in pages for f in p['forms']]
check('专用表单和输入字段完整',len(forms)==39 and all(f['entry'] and f['save'] and f['result'] and f['rules'] and f['fields'] for f in forms),str(sum(len(f['fields']) for f in forms)))
check('表单字段键不重复',all(len(f['fields'])==len(set(x['key'] for x in f['fields'])) for f in forms))
bad_labels=[(p['title'],f['title'],x['label']) for p in pages for f in p['forms'] for x in f['fields'] if not re.search('[\u4e00-\u9fff]',x['label'])]
check('输入字段中文名称',not bad_labels,str(bad_labels))
rawopts=[(p['title'],x['label'],x['renderOptions']) for p in pages for f in p['forms'] for x in f['fields'] if any(re.fullmatch('[A-Z_]+',s) for s in x['renderOptions'])]
check('选项使用业务中文',not rawopts,str(rawopts))
unmatched=[a for p in pages for a in p['api'] if (a['state']=='已有契约') != (a['path'] in api['paths'] and a['method'].lower() in api['paths'][a['path']])]
check('已有与拟补接口分类准确',not unmatched,str(sum(len(p['api']) for p in pages)))
paths=[]
for p in pages:
    paths.append(WF/p['listImage'])
    paths.extend(WF/f['image'] for f in p['forms'])
check('逐页列表与专用表单均有图像',all(x.exists() for x in paths),str(len(paths)))
images=list((WF/'界面图').glob('*.png'))
invalid=[]
for image in images:
    with Image.open(image) as im:
        if im.width!=1800 or im.height<980:invalid.append(image.name)
check('所有图像可解码及尺寸有效',len(images)==77 and not invalid,str(len(images)))
local=[];missing=[]
for owner in [F/'页面与字段设计方案.md',F/'归档/页面设计重构前原文-20261001/README.md']:
    source=owner.read_text(encoding='utf-8')
    for label,target in re.findall(r'!?\[([^\]]*)\]\(([^)]+)\)',source):
        target=target.strip('<>');path,_,anchor=target.partition('#')
        if re.match(r'^[a-zA-Z]+:',path) or not path:continue
        resolved=(owner.parent/unquote(path)).resolve();local.append(str(resolved))
        if not resolved.exists():missing.append((label,target))
check('设计与恢复文档的本地链接有效',not missing,str(missing) if missing else str(len(local)))
check('设计正文无检查流水账',not any(t in md for t in ['本轮设计检查','本次设计检查','node --check','SHA-256']))
backup=json.loads((F/'归档/页面设计重构前原文-20261001/原文快照.json').read_text(encoding='utf-8'))
check('六个重构前文件完整可恢复',len(backup['files'])==6 and all(hashlib.sha256(base64.b64decode(x['base64'])).hexdigest()==x['sha256'] and (F/x['path']).resolve().is_relative_to(F.resolve()) for x in backup['files']))
source=re.search(r'<script>\s*(.*?)</script>',txt,re.S).group(1)
script=ROOT/'.runtime/aps-page-design/图稿交互检查.js';script.write_text(source,encoding='utf-8')
r=subprocess.run(['node','--check',str(script)],capture_output=True,text=True)
check('HTML交互脚本语法检查',r.returncode==0,r.stdout+r.stderr)
check('线框不调用业务接口',not re.search(r'\b(fetch|XMLHttpRequest|WebSocket)\s*\(',source))
counts=dict(modules=9,pages=len(pages),columns=sum(len(p['columns']) for p in pages),forms=len(forms),inputFields=sum(len(f['fields']) for f in forms),images=len(images),apiBindings=sum(len(p['api']) for p in pages),definedBindings=sum(a['state']=='已有契约' for p in pages for a in p['api']),proposedBindings=sum(a['state']=='拟补接口' for p in pages for a in p['api']))
out={'checkedAt':datetime.now(timezone(timedelta(hours=8))).isoformat(),'counts':counts,'checks':checks,'visual':{'staticImages':'已打开抽查关键图，详见验证记录第11节；全部图像另做解码及尺寸检查','htmlInteraction':'受阻：内置浏览器禁止本地文件协议，未绕过限制'},'businessAcceptance':'本轮未执行，原结果保留'}
E=F/'evidence/20261001设计重构';E.mkdir(exist_ok=True)
(E/'设计检查结果.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(out,ensure_ascii=True))
raise SystemExit(0 if all(x['result']=='通过' for x in checks) else 1)
