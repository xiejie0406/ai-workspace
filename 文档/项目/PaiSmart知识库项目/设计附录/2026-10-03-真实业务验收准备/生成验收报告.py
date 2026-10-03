"""只将同目录的验收记录渲染成清单及HTML；不启动服务或执行任何业务操作。"""
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / '验收记录.json'


def esc(value):
    return html.escape(str(value), quote=True)


def bullets(values):
    return '<ul>' + ''.join('<li>' + esc(v) + '</li>' for v in values) + '</ul>'


def main():
    data = json.loads(DATA.read_text(encoding='utf-8'))
    cases = data['businessCases']
    assert [c['number'] for c in cases] == list(range(1, 32))
    counts = {label: sum(c['result'] == label for c in cases)
              for label in ['通过', '未通过', '受阻', '未执行', '不适用']}
    for c in cases:
        assert c['prerequisites'] and c['steps'] and c['expected'] and c['verification']
        assert c['requirementSource'] and c['requiredScreenshots']
        if c['result'] == '通过':
            assert c['executedAt'] and c['actual'] and c['evidence']
            assert c['screenshotStatus'] == '已截图'
    md = [
        '# PaiSmart 逐项业务验收清单', '',
        '> 类型：真实业务验收准备及记录的可读视图；日期：' + data['date'],
        '> 执行者：当前任务执行者；需求版本：完整迁移设计1.0；全部31项沿用原序号。',
        '> 本清单及HTML由[验收记录](验收记录.json)生成；项目总状态仍由[完整迁移设计](../../完整迁移设计.md)唯一维护。', '',
        data['summary'], '', '## 执行前提与授权', '',
    ]
    for p in data['readiness']:
        md += ['- **' + p['name'] + '：' + p['state'] + '**。' + p['fact']]
    md += ['', '## 共用样本和核对方法', '']
    md += ['- ' + v for v in data['commonPrerequisites']]
    md += ['', '技术结果单独沿用；业务验收：' + '，'.join(f'{k}{v}项' for k, v in counts.items()) + '。用户接受未取得；未发布。', '']
    cards = []
    for c in cases:
        md += [f"## {c['number']}. {c['name']}", '',
               '**要求出处：** ' + c['requirementSource'], '',
               '**拟菜单入口：** ' + '；'.join(c['entryPoints']), '',
               '**前提：**', '']
        md += ['- ' + v for v in c['prerequisites']]
        md += ['', '**操作步骤：**', '']
        md += [f'{i}. {v}' for i, v in enumerate(c['steps'], 1)]
        md += ['', '**独立预期：** ' + c['expected'], '', '**数据和状态核对：** ' + c['verification'], '',
               '**必需截图：** ' + '；'.join(c['requiredScreenshots']), '',
               '**实际及结果：** ' + c['actual'] + '；' + c['result'] + '；' + c['screenshotStatus'] + '。', '',
               '**不足的业务影响：** ' + c['impact'], '']
        cards.append(f'''<article id="case-{c['number']}" class="case"><header><h2>{c['number']}. {esc(c['name'])}</h2><span class="status">{esc(c['result'])}</span></header>
<p class="source">要求出处：{esc(c['requirementSource'])}</p><p><strong>拟菜单入口：</strong>{esc('；'.join(c['entryPoints']))}</p>
<h3>前提</h3>{bullets(c['prerequisites'])}<h3>操作步骤</h3><ol>{''.join('<li>'+esc(v)+'</li>' for v in c['steps'])}</ol>
<h3>独立预期</h3><p>{esc(c['expected'])}</p><h3>数据与状态核对</h3><p>{esc(c['verification'])}</p>
<h3>必需截图</h3>{bullets(c['requiredScreenshots'])}<p class="actual"><strong>实际及结果：</strong>{esc(c['actual'])}；{esc(c['result'])}；{esc(c['screenshotStatus'])}。</p>
<p><strong>不足的业务影响：</strong>{esc(c['impact'])}</p></article>''')
    md += ['## 技术基线及适用范围', '']
    technical = []
    for t in data['technicalBaseline']:
        md += ['- [' + t['name'] + '](' + t['href'] + ')：' + t['fact']]
        technical.append('<li><a href="' + esc(t['href']) + '">' + esc(t['name']) + '</a>：' + esc(t['fact']) + '</li>')
    md += ['', '报告自身的截图仅证明报告呈现，不能充当上述页面业务截图。', '']
    (ROOT / '逐项验收清单.md').write_text('\n'.join(md), encoding='utf-8')
    report = '''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PaiSmart 真实业务验收准备</title><link rel="icon" href="data:,">
<style>:root{font-family:"Microsoft YaHei",system-ui,sans-serif;color:#183239;background:#f3f7f7;font-size:16px}*{box-sizing:border-box}body{margin:0}main{max-width:1140px;margin:auto;padding:38px 28px 72px}h1{font-size:32px;line-height:1.35;margin:10px 0 14px}h2{font-size:23px;line-height:1.4;margin:0}h3{font-size:17px;margin:22px 0 6px}p,li{line-height:1.85;overflow-wrap:anywhere}a{color:#00696e;text-underline-offset:3px}.eyebrow,.source{color:#526b72;font-size:14px}.notice{border-left:5px solid #be823d;background:#fff7e9;padding:15px 20px;margin:24px 0}.stats{display:grid;grid-template-columns:repeat(5,1fr);gap:12px}.stat{background:white;border:1px solid #dce7e7;border-radius:12px;padding:18px}.stat strong{display:block;font-size:30px}.panel,.case{background:white;border:1px solid #dce7e7;border-radius:14px;padding:26px;margin-top:24px}.case header{display:flex;gap:15px;justify-content:space-between;align-items:flex-start}.status{white-space:nowrap;font-size:14px;color:#586871;background:#edf1f3;padding:5px 10px;border-radius:20px}.actual{border-top:1px solid #e2eaea;padding-top:15px;color:#4c6269}.toc{display:grid;grid-template-columns:repeat(2,1fr);gap:9px 22px}.toc a{text-decoration:none}.tools{display:flex;gap:12px;align-items:center;margin:20px 0}input{width:100%;padding:12px;border:1px solid #b6c9ca;border-radius:8px;font:inherit}button{padding:12px 20px;background:#00696e;border:0;color:white;border-radius:8px;font:inherit;white-space:nowrap}footer{margin-top:32px;color:#526b72}[hidden]{display:none!important}@media(max-width:680px){main{padding:25px 15px}.stats{grid-template-columns:repeat(2,1fr)}.toc{grid-template-columns:1fr}.panel,.case{padding:20px}h1{font-size:27px}.case header{display:block}.status{display:inline-block;margin-top:8px}}@media print{body{background:white}.tools{display:none}.case{break-inside:avoid}.panel,.case{border-radius:0}.stats{grid-template-columns:repeat(5,1fr)}main{padding:0}}</style></head><body><main>'''
    report += '<p class="eyebrow">验收准备 · 需求版本1.0 · ' + esc(data['date']) + '</p><h1>PaiSmart 真实业务验收准备</h1>'
    report += '<p>' + esc(data['summary']) + '</p><div class="notice">当前报告只完成清单准备。真实平台、业务数据库和外部服务尚未操作；没有真实业务截图。源码实施批准、技术检查、业务结果和用户接受分别记录。</div>'
    report += '<div class="stats">' + ''.join('<div class="stat"><strong>' + str(v) + '</strong>' + esc(k) + '</div>' for k, v in counts.items()) + '</div>'
    report += '<section class="panel"><h2>执行前提与授权</h2>' + bullets([p['name'] + '：' + p['state'] + '。' + p['fact'] for p in data['readiness']]) + '<p>这是准备时已知的前提，未进行真实联机尝试；逐项业务结果均保留未执行。</p></section>'
    report += '<section class="panel"><h2>共用样本与独立核对</h2>' + bullets(data['commonPrerequisites']) + '</section>'
    report += '<section class="panel"><h2>技术基线单列</h2><ul>' + ''.join(technical) + '</ul><p>以上技术结果不计入31项业务通过数。既有组件替身截图也不沿用为真实业务截图。用户接受未取得；未发布。</p></section>'
    report += '<section class="panel"><h2>31项业务清单</h2><div class="tools"><input id="search" type="search" placeholder="按业务名称或核对内容查找" aria-label="查找验收点"><button id="print" type="button">打印清单</button></div><nav class="toc" aria-label="业务点目录">' + ''.join(f'<a href="#case-{c["number"]}">{c["number"]}. {esc(c["name"])}</a>' for c in cases) + '</nav></section>'
    report += ''.join(cards) + '<footer><a href="逐项验收清单.md">查看可读清单</a> · <a href="验收记录.json">查看同源记录</a> · <a href="../../完整迁移设计.md">需求、方案及唯一实施状态</a><p>报告自身的呈现截图只能证明报告可读，不能充当业务页面证据。执行后应将实际、截图回读、数据核对和版本填入同源记录，再生成报告；原失败证据及未受影响的通过基线必须保留。</p></footer></main><script>document.getElementById("search").addEventListener("input",function(){const q=this.value.trim().toLowerCase();document.querySelectorAll(".case").forEach(c=>c.hidden=q&&!c.textContent.toLowerCase().includes(q));});document.getElementById("print").addEventListener("click",()=>window.print());</script></body></html>'
    (ROOT / 'verification-report.html').write_text(report, encoding='utf-8')
    print(json.dumps({'cases': len(cases), 'businessCounts': counts, 'reportGenerated': True, 'businessExecuted': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
