"""增补设计入链和唯一实际记录；保护其他对话的未提交原文。"""
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib,json,re
from urllib.parse import unquote
from PIL import Image
E=Path(__file__).resolve().parent
PROJECT=E.parents[3]
FEATURE=E.parents[1]
ROOT=E.parents[6]
design=FEATURE/'README.md'
main=PROJECT/'完整迁移设计.md'
record=PROJECT/'开发记录/2026-10-03-完整迁移实施记录.md'
backup=E/'修改前原文';backup.mkdir(exist_ok=True)
insert='2026-10-04 新增需求方案：[网页命令行终端设计方案](功能/FEAT-PAICLI-002-网页命令行终端/README.md)，拟在若依页内通过Runner伪终端运行现有CLI。该功能的需求、方案与后续验收方法唯一维护在所属功能控制页；当前为待评审设计，不代表已实现，原整体迁移与Sub2API扩展记录保持。\n\n'
old=main.read_bytes()
if '网页命令行终端设计方案' not in old.decode('utf-8-sig'):
    (backup/'完整迁移设计-增补入口前.md').write_bytes(old)
    text=old.decode('utf-8')
    anchor='### 1.1 要交付的产品'
    assert text.count(anchor)==1
    newline='\r\n' if b'\r\n' in old else '\n'
    new=text.replace(anchor,insert.replace('\n',newline)+anchor,1).encode('utf-8')
    assert main.read_bytes()==old,'共享设计原文刚发生变化，须重新读取后补链'
    main.write_bytes(new)
text=design.read_text(encoding='utf-8')
links=[];missing=[]
for target in re.findall(r'\]\(([^)]+)\)',text):
    if target.startswith(('http:','https:')):continue
    file=(design.parent/unquote(target.split('#')[0])).resolve()
    links.append({'target':target,'exists':file.exists()})
    if not file.exists():missing.append(target)
sections=re.findall(r'^## ([1-5])[.] ',text,re.M)
assert sections==list('12345'),sections
assert not missing,missing
images=[]
for name in ('网页命令行终端-主界面.png','网页命令行终端-关键状态.png'):
    file=PROJECT/'图稿'/name
    with Image.open(file) as image:
        image.load();images.append({'path':str(file),'size':list(image.size),'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'visualReadBack':True})
now=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds')
addition=f'''

## 2026-10-04 网页命令行终端设计交付

> 实际记录时间：{now}；只记录文档和设计资产，不推进原实现或业务验收状态。

- 用户要求若依页内启动命令行窗口进行对话。本次在所属功能唯一控制页交付[网页命令行终端设计方案](../功能/FEAT-PAICLI-002-网页命令行终端/README.md)，标为Draft/待评审；按需求与业务逻辑、前端界面、后台处理、实施步骤、业务验收方法组织。没有复制原完整迁移任务或另建长期进度来源。
- 推荐xterm网页终端、若依授权及双向转发、Runner真实PTY与现有CenterCli；明确托管通道不占runOnce唯一任务槽，短期能力不传完整JWT，单实例单输入者、刷新/分离不重交，结束CLI与取消任务分开。拟新增实例元数据表及有限Gateway/帧契约只写入方案，未执行DDL、授权或代码实现。
- 已在原整体设计增补待评审链接，保护随后出现的Sub2API入口和所有原正文；当前修改前原文保存在该功能证据目录，不使用Git HEAD恢复。
- 交付[主界面](../图稿/网页命令行终端-主界面.png)、[关键状态图](../图稿/网页命令行终端-关键状态.png)及[HTML图稿](../图稿/网页命令行终端设计图.html)。两张PNG由静态绘图生成并通过view_image实际回读，覆盖正常、审批、断线/控制权、未启动和结束；修正下拉符号的缺字显示，正常示例不显示虚假待处理审批。它们是设计资产，不是产品截图。
- CUA内置浏览器自动打开本地file图稿被URL安全策略拒绝，未用其他地址、浏览器、服务或原始协议绕过。采用不执行HTML/JavaScript的静态图作为更安全的可查看产物，HTML交互预览未实际检查。
- 设计正文12个本地链接/图片引用与五个主章节结构核对有效；技术选型查阅xterm API/安全/流控、Microsoft ConPTY和pty4j官方资料，链接就在相关方案段落，版本与原生依赖留到获准实施时锁定。本轮没有安装依赖、修改产品代码、构建业务包、运行测试/业务验收、访问数据库、操纵服务、真实模型或微信调用、Git写入或发布。
'''
before=record.read_bytes()
title='## 2026-10-04 网页命令行终端设计交付'
if title not in before.decode('utf-8-sig'):
    (backup/'完整迁移实施记录-补记设计前.md').write_bytes(before)
    with record.open('ab') as f:f.write(addition.encode('utf-8'))
result={'recordedAt':now,'design':str(design),'status':'Draft','sections':sections,'localLinks':links,'missingLinks':missing,'images':images,'htmlBrowserPreview':'blocked_file_protocol; no_workaround','productCodeChanged':False,'databaseAccessed':False,'servicesChanged':False,'businessTestsRun':False,'sub2apiEntryPreserved':'Sub2API' in main.read_text(encoding='utf-8-sig'),'recordPreviousBytesPreserved':record.read_bytes().startswith(before)}
(E/'文档与图稿核对.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'sections':sections,'localLinks':len(links),'missing':missing,'images':len(images),'previousRecordPreserved':result['recordPreviousBytesPreserved'],'sub2apiPreserved':result['sub2apiEntryPreserved']},ensure_ascii=False))
