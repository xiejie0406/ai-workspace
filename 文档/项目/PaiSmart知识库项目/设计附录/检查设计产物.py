"""只读核对本项目链接、功能/验收对应与图稿尺寸；结果写回本目录。"""
from pathlib import Path
from urllib.parse import unquote, urlsplit
import re, json, sys
from PIL import Image

sys.stdout.reconfigure(encoding='utf-8')

ROOT=Path(__file__).resolve().parent.parent
issues=[]; links=[]
for file in ROOT.rglob('*.md'):
    body=file.read_text(encoding='utf-8-sig')
    for match in re.finditer(r'!?\[[^\]\n]*\]\(([^)]+)\)',body):
        target=match.group(1).strip('<>')
        if urlsplit(target).scheme or target.startswith('#'): continue
        path=(file.parent/unquote(target.split('#')[0])).resolve()
        links.append({'source':str(file.relative_to(ROOT)),'target':target,'exists':path.exists()})
        if not path.exists(): issues.append('失效链接：'+str(file.relative_to(ROOT))+' → '+target)
main=(ROOT/'完整迁移设计.md').read_text(encoding='utf-8')
feature=main.split('### 1.3 原能力—目标—验收对应')[1].split('### 1.4')[0]
acceptance=main.split('## 5. 业务验收方法')[1]
feature_ids=[int(x) for x in re.findall(r'^\| (\d+) \|',feature,re.M)]
acceptance_ids=[int(x) for x in re.findall(r'^\| (\d+)\. ',acceptance,re.M)]
if feature_ids!=list(range(1,33)): issues.append('功能对应表顺序不完整')
if acceptance_ids!=list(range(1,32)): issues.append('验收业务点顺序不完整')
images=[]
for file in sorted((ROOT/'图稿').glob('*.png')):
    with Image.open(file) as image:
        image.verify()
    with Image.open(file) as image:
        images.append({'name':file.name,'size':image.size})
if len(images)!=9: issues.append('PNG图稿数量应为9')
for file in ROOT.rglob('*'):
    if file.is_file() and not re.search(r'[\u4e00-\u9fff]',file.name):
        issues.append('人读文件缺中文名：'+file.name)
html=(ROOT/'图稿/知识库界面设计.html').read_text(encoding='utf-8')
pages=re.findall(r'data-page="([^"]+)"',html)
screens=re.findall(r'class="page screen[^"\n]*" id="([^"]+)"',html)
if sorted(pages)!=sorted(screens) or len(pages)!=9: issues.append('HTML图稿选择/页面ID对应异常')
result={'日期':'2026-10-02','范围':'仅PaiSmart知识库项目设计产物','本地链接数':len(links),'功能对应项':len(feature_ids),'验收业务点':len(acceptance_ids),'PNG设计图':images,'HTML页面对应数':len(pages),'HTML浏览器验证':'未完成；本地file协议被内置浏览器策略拒绝，未绕过','问题':issues,'结论':'通过' if not issues else '未通过'}
(ROOT/'设计附录/设计产物检查结果.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
