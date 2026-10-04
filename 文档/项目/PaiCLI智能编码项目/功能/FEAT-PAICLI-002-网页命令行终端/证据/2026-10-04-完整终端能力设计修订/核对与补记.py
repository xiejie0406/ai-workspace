"""仅核对设计链接/图稿与静态来源并追加已有记录，不执行产品测试。"""
from pathlib import Path
from urllib.parse import unquote
import hashlib, json, re
from PIL import Image
E = Path(__file__).resolve().parent
P = E.parents[3]
R = E.parents[6]
D = P / '功能/FEAT-PAICLI-002-网页命令行终端/README.md'
text = D.read_text(encoding='utf-8')
links = re.findall(r'\]\(([^)]+)\)', text)
local = [x for x in links if not x.startswith(('http:', 'https:'))]
missing = [x for x in local if not (D.parent / unquote(x.split('#')[0])).resolve().exists()]
assert not missing, missing
assert re.findall(r'^## (\d)\.', text, re.M) == ['1','2','3','4','5']
assert '首版必需覆盖' not in text and '明确拒绝`@clipboard`' not in text
images = []
for n in ['主界面','关键状态','打开与图片']:
    f = P / f'图稿/网页命令行终端-{n}.png'
    with Image.open(f) as im:
        im.verify()
    with Image.open(f) as im:
        size = list(im.size)
    images.append({'path': str(f.relative_to(P)), 'size':size, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'visualReadBack': True})
sources = {}
for name, p in {
    '原命令解析': R / '参考项目/paicli-main/src/main/java/com/paicli/cli/CliCommandParser.java',
    '迁移命令解析': R / 'ruoyi-backend/ruoyi-paicli/paicli-cli/src/main/java/com/paicli/cli/CliCommandParser.java',
    '原终端入口': R / '参考项目/paicli-main/src/main/java/com/paicli/cli/Main.java',
    '现中心入口': R / 'ruoyi-backend/ruoyi-paicli/paicli-cli/src/main/java/com/paicli/cli/CenterCli.java',
    '原Lanterna': R / '参考项目/paicli-main/src/main/java/com/paicli/tui/RootPane.java',
}.items():
    body = p.read_text(encoding='utf-8')
    item = {'path':str(p.relative_to(R)), 'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
    if '命令解析' in name:
        enum = re.search(r'enum CommandType\s*\{(.*?)\}', body, re.S).group(1)
        item['enum'] = re.findall(r'\b[A-Z][A-Z_]+\b', enum)
    if name == '原终端入口':
        item['slashHints'] = sorted(set(re.findall(r'new SlashCommandHint\("([^"\n]+)"', body)))
    sources[name] = item
evidence = {'date':'2026-10-04','designVersion':'1.1','scope':'设计局部修订、静态图及来源核对；未实施终端功能', 'localLinks':len(local),'missing':missing,'mainSections':['1','2','3','4','5'],'images':images,'sources':sources,'limitation':'静态enum、提示项及源码hash不是功能全集或业务通过证据；命令真实分派、全部参数/别名、键位、TUI及外部通道需实施时逐项对应并验证。HTML仅编辑，未浏览器执行；未运行产品测试/业务/服务/数据库或Git写操作。'}
(E / '文档与图稿核对.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
record = P / '开发记录/2026-10-03-完整迁移实施记录.md'
title = '## 2026-10-04 原终端完整保留要求修订'
before = record.read_bytes()
if title not in before.decode('utf-8-sig'):
    note = '''

## 2026-10-04 原终端完整保留要求修订

用户明确网页应增加“打开终端”，打开后仍使用原PaiCLI，全部终端能力不能遗漏。本次在[网页命令行终端设计](../功能/FEAT-PAICLI-002-网页命令行终端/README.md)局部修订为v1.1，保留原五章、路径与现有入口。完整保留要求为用户已确认的产品范围，技术方案仍待评审，未将设计确认记成实现或业务通过。

- 新增原终端能力对照，引用已有命令/工具唯一全集；包括全部参数/别名、JLine、三renderer、原键位、计划审批、图片剪贴板、产物、外部工具和独立通道。本机CLI继续分发；当前CenterCli不能直接作为原功能全集的证明。
- 修正v1.0中剪贴板默认拒绝并留作后续的范围，设计浏览器主动授权的附件桥，保持原@clipboard/Ctrl+V目的及同一CLI轮次；不读取错误设备剪贴板。补浏览器冲突键的显式发送入口、固定独立启动配置及完整性验收门槛。
- 更新主界面、状态图及新增打开/图片/键位设计图，三张PNG均实际生成并逐张回读；HTML仅编辑，沿用上轮本地file协议预览受限的事实，没有绕过或声明预览成功。
- 核对五章结构、全部本地链接和PNG文件；保留原命令解析/提示及当前适配源码静态摘要。这只能证明本次文档检查范围，不能证明命令运行、完整迁移或业务通过。

修订前保存当前工作区文档、图稿源/PNG和已有实施记录，未用Git HEAD替代未提交内容。恢复时先保护后续修改，再按备份逐处恢复，不覆盖其他会话记录。范围、原文与核对结果见[本次证据目录](../功能/FEAT-PAICLI-002-网页命令行终端/证据/2026-10-04-完整终端能力设计修订/)。未安装依赖、改产品代码、启动服务、执行测试或真实业务、迁移数据库、同步运行包，未做Git暂存/提交/推送/分支操作。
'''
    with record.open('ab') as f:
        f.write(note.encode('utf-8'))
assert record.read_bytes().startswith(before), '本轮补记未保留原记录'
print(json.dumps({'designVersion':'1.1','localLinks':len(local),'missing':missing,'images':len(images),'recordPrefixPreserved':True},ensure_ascii=False))
