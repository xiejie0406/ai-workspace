"""从唯一业务验收 Markdown 生成 HTML；不保存或修改另一份判定状态。

默认：python scripts/aipicture/生成迁移验收报告.py
指定记录：python scripts/aipicture/生成迁移验收报告.py --input 业务验收记录.md
报告始终写到输入记录同目录的 verification-report.html，不读取账号或访问网络。
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
import hashlib
import html
import os
from pathlib import Path
import re
from urllib.parse import quote, unquote, urlsplit


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "文档/项目/AI图片编辑项目/证据/2026-10-03-完整迁移验收/业务验收记录.md"
STATUSES = ("通过", "未通过", "受阻", "未执行", "不适用")
STATUS_STYLE = {"通过": "passed", "未通过": "failed", "受阻": "blocked", "未执行": "pending", "不适用": "na"}
REQUIRED_FIELDS = ("依据与入口", "前提", "步骤", "独立预期", "截图与数据核对")
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
COMBINED_FIELD = "原始结果/当前结果/截图/用户决定"
FIELD = re.compile(r"^\s*(?:[-*]\s+)?\*\*([^*]+)\*\*[：:]\s*(.*)$")
ITEM_HEADING = re.compile(r"^### (\d+)\. (.+)$", re.M)
INLINE = re.compile(r"(`[^`\n]+`|!?\[[^\]\n]*\]\([^\)\n]+\)|\*\*[^*\n]+\*\*)")
LINK = re.compile(r"(!?)\[([^\]]*)\]\((.+)\)")


def plain(value: str) -> str:
    return re.sub(r"[*`]", "", value).strip()


def status_of(value: str) -> str:
    match = re.match(r"^(未通过|未执行|不适用|受阻|通过)(?:$|[\s：:，,。；;（(])", plain(value))
    if not match:
        raise ValueError(f"无法识别完整项当前结果：{value!r}")
    return match.group(1)


@dataclass
class Item:
    number: int
    title: str
    body: str
    status: str
    result: str
    screenshot: str
    decision: str


def parse_record(source: str) -> tuple[str, list[Item], str]:
    """只解析业务清单区；技术通过/统计段中的文字不会改变业务卡片结果。"""
    begin = re.search(r"^## 2\. 首个验收操作前的全部业务清单\s*$", source, re.M)
    end = re.search(r"^## 3\. 当前统计与后续填报\s*$", source, re.M)
    if not begin or not end or end.start() <= begin.end():
        raise ValueError("记录缺少约定的业务清单区和当前统计区")
    content = source[begin.end():end.start()]
    headings = list(ITEM_HEADING.finditer(content))
    if [int(match.group(1)) for match in headings] != list(range(1, 23)):
        raise ValueError("业务项必须与主设计一一对应，编号连续为 1–22")
    items = []
    for index, match in enumerate(headings):
        body = content[match.end():headings[index + 1].start() if index + 1 < len(headings) else len(content)].strip()
        fields = [(field.group(1).strip(), field.group(2).strip()) for line in body.splitlines() if (field := FIELD.match(line))]
        for required in REQUIRED_FIELDS:
            if not any(name == required for name, _ in fields):
                raise ValueError(f"第 {match.group(1)} 项缺少字段：{required}")
        result = screenshot = decision = None
        for name, value in fields:
            if name == COMBINED_FIELD:
                # 只按两侧有空格的斜线拆列，正文中的模型/Provider 和 URL 不误拆。
                values = re.split(r"\s+/\s+", value, maxsplit=3)
                if len(values) != 4:
                    raise ValueError(f"第 {match.group(1)} 项结果字段必须有四列")
                _, result, screenshot, decision = values
            elif name in {"当前结果", "当前完整项结果", "复验结果"}:
                result = value
            elif name == "截图情况":
                screenshot = value
            elif name == "用户决定":
                decision = value
        if result is None or screenshot is None or decision is None:
            raise ValueError(f"第 {match.group(1)} 项缺少当前结果、截图或用户决定")
        # 顺序读取时最后一次追加覆盖卡片摘要，原始记录仍完整渲染在卡片正文中。
        decision_value = plain(decision)
        decision_match = re.match(r"^(待确认|有条件接受|不接受|接受)", decision_value)
        if not decision_match:
            raise ValueError(f"第 {match.group(1)} 项用户决定无法识别：{decision_value!r}")
        items.append(Item(int(match.group(1)), match.group(2), body, status_of(result), plain(result), plain(screenshot), decision_match.group(1)))
    return source[:begin.start()], items, source[end.start():]


class Renderer:
    """本记录所需的有限 Markdown：文本、标题、列表、引用、代码、链接与实际图片。"""

    def __init__(self, record: Path, report: Path):
        self.record = record
        self.report = report
        self.missing_links: set[str] = set()
        self.images: set[str] = set()

    def target(self, value: str) -> tuple[str | None, Path | None]:
        value = value.strip().removeprefix("<").removesuffix(">")
        parts = urlsplit(value)
        if parts.scheme:
            return (value, None) if parts.scheme in {"http", "https", "mailto"} else (None, None)
        if value.startswith("//"):
            return None, None
        if not parts.path:
            return value, None
        path = (self.record.parent / unquote(parts.path)).resolve()
        relative = Path(os.path.relpath(path, self.report.parent)).as_posix()
        href = quote(relative, safe="/.-_~")
        if parts.fragment:
            href += "#" + quote(unquote(parts.fragment), safe="-._~")
        if not path.is_file():
            self.missing_links.add(value)
        return href, path

    def inline(self, value: str, *, show_images: bool = False) -> str:
        output = []
        position = 0
        for match in INLINE.finditer(value):
            output.append(html.escape(value[position:match.start()]))
            token = match.group(0)
            if token.startswith("`"):
                output.append("<code>" + html.escape(token[1:-1]) + "</code>")
            elif token.startswith("**"):
                output.append("<strong>" + self.inline(token[2:-2]) + "</strong>")
            else:
                link = LINK.fullmatch(token)
                assert link is not None
                image_mark, label, link_value = link.groups()
                href, path = self.target(link_value)
                label_html = self.inline(label)
                if href is None:
                    output.append(label_html + '<span class="evidence-note">（不支持的链接）</span>')
                else:
                    anchor = '<a href="' + html.escape(href, quote=True) + '">' + label_html + "</a>"
                    if path is not None and not path.is_file():
                        output.append(anchor + '<span class="missing">（证据文件尚未找到）</span>')
                    elif show_images and path is not None and path.suffix.lower() in IMAGE_EXTENSIONS:
                        # 普通截图链接也展示真实图像，缺文件/远程图片不造占位像素。
                        self.images.add(href)
                        output.append('<figure class="evidence"><a href="' + html.escape(href, quote=True)
                                      + '"><img src="' + html.escape(href, quote=True) + '" alt="'
                                      + html.escape(label, quote=True) + '" loading="lazy"></a><figcaption>'
                                      + label_html + " · 点击查看原图</figcaption></figure>")
                    elif image_mark:
                        output.append(anchor + '<span class="evidence-note">（图像仅作链接，未核实本地文件）</span>')
                    else:
                        output.append(anchor)
            position = match.end()
        output.append(html.escape(value[position:]))
        return "".join(output)

    def markdown(self, source: str, *, show_images: bool = False) -> str:
        source = re.sub(r"<!--.*?-->", "", source, flags=re.S)
        output: list[str] = []
        paragraph: list[str] = []
        list_kind: str | None = None
        code: list[str] | None = None

        def flush() -> None:
            if paragraph:
                content = self.inline(" ".join(paragraph), show_images=show_images)
                tag = "div" if "<figure" in content else "p"
                output.append(f"<{tag}>" + content + f"</{tag}>")
                paragraph.clear()

        def close_list() -> None:
            nonlocal list_kind
            if list_kind:
                output.append(f"</{list_kind}>")
                list_kind = None

        for line in source.splitlines():
            if line.strip().startswith("```"):
                flush(); close_list()
                if code is None:
                    code = []
                else:
                    output.append("<pre><code>" + html.escape("\n".join(code)) + "</code></pre>")
                    code = None
                continue
            if code is not None:
                code.append(line)
                continue
            if not line.strip():
                flush(); close_list()
                continue
            heading = re.match(r"^(#{1,6})\s+(.+)$", line)
            if heading:
                flush(); close_list()
                level = min(6, max(3, len(heading.group(1)) + 1))
                output.append(f"<h{level}>" + self.inline(heading.group(2)) + f"</h{level}>")
                continue
            entry = re.match(r"^\s*(?:([-*])\s+|(\d+)\.\s+)(.*)$", line)
            if entry:
                flush()
                kind = "ul" if entry.group(1) else "ol"
                if list_kind != kind:
                    close_list(); output.append(f"<{kind}>"); list_kind = kind
                output.append("<li>" + self.inline(entry.group(3), show_images=show_images) + "</li>")
                continue
            if line.startswith(">"):
                flush(); close_list()
                output.append('<p class="metadata">' + self.inline(line.lstrip("> "), show_images=show_images) + "</p>")
                continue
            close_list()
            paragraph.append(line.strip())
        flush(); close_list()
        if code is not None:
            raise ValueError("Markdown 代码块未闭合")
        return "\n".join(output)


CSS = r"""
:root{color-scheme:light;font-family:'Microsoft YaHei','PingFang SC',system-ui,sans-serif;color:#183047;background:#eef3f7;line-height:1.75}
*{box-sizing:border-box}body{margin:0}main{max-width:1180px;margin:auto;padding:32px 24px 70px}a{color:#176298;text-underline-offset:3px}button,input,select{font:inherit}button{cursor:pointer}
header{padding:26px 30px;background:#183b56;color:#fff;border-radius:18px}header p{margin:6px 0 0;color:#dce9f3}h1{margin:0;font-size:clamp(23px,3vw,34px);line-height:1.4}h2{font-size:21px}h3{font-size:18px}h4,h5,h6{font-size:16px;margin:18px 0 8px}
.stats{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:12px;margin:20px 0}.stat{padding:15px;background:#fff;border:1px solid #d6e0e8;border-radius:12px}.stat strong{display:block;font-size:28px;line-height:1.35}.stat span{font-size:13px;color:#536779}
.notice{border-left:4px solid #cb8a24;background:#fff9eb;padding:14px 18px;border-radius:8px;margin:16px 0}.overview,.record-notes,.technical{background:#fff;border:1px solid #d6e0e8;border-radius:12px;padding:16px 22px;margin:18px 0}.overview>summary,.record-notes>summary{font-weight:700;cursor:pointer}.metadata{font-size:13px;color:#5a6b7b;margin:5px 0}
.toolbar{position:sticky;top:0;z-index:2;background:#eef3f7f5;backdrop-filter:blur(8px);padding:12px 0;border-bottom:1px solid #cdd9e3;display:flex;gap:10px;align-items:center;flex-wrap:wrap}.toolbar input{flex:1;min-width:200px}.toolbar input,.toolbar select,.toolbar button{border:1px solid #b9cbd8;border-radius:8px;background:#fff;padding:8px 12px;color:#183047}.toolbar label{display:flex;align-items:center;gap:8px;font-size:14px}.toolbar button:hover{background:#e0edf6}.visibility{font-size:13px;color:#526878;margin:10px 0}.empty{padding:28px;text-align:center;background:#fff;border-radius:12px}
.business-card{background:#fff;border:1px solid #d6e0e8;border-radius:13px;margin:14px 0;overflow:hidden;scroll-margin-top:100px}.business-card[hidden]{display:none}.business-card>summary{cursor:pointer;padding:17px 20px;list-style:none}.business-card>summary::-webkit-details-marker{display:none}.card-head{display:flex;gap:12px;align-items:flex-start}.number{background:#e6eff5;flex:none;width:32px;height:32px;display:flex;justify-content:center;align-items:center;border-radius:8px;font-weight:700}.card-title{flex:1;font-weight:700;font-size:17px}.chevron{font-size:13px;color:#677e91}.business-card[open] .chevron{transform:rotate(90deg)}.badge{font-size:12px;padding:3px 10px;white-space:nowrap;border-radius:999px;font-weight:700}.blocked{background:#fff1d6;color:#895700}.pending{background:#edf0f4;color:#526476}.passed{background:#e0f1e5;color:#176033}.failed{background:#fbe4e4;color:#9f2323}.na{background:#e6eaf1;color:#536079}.card-result{font-size:13px;color:#526779;margin:8px 0 3px 44px}.card-meta{display:flex;flex-wrap:wrap;gap:4px 20px;margin-left:44px;font-size:12px;color:#65778b}.card-body{padding:0 22px 20px;border-top:1px solid #e4ebf1}.card-body ul,.card-body ol,.overview ul,.overview ol,.record-notes ul{padding-left:22px}.card-body li,.overview li,.record-notes li{margin:11px 0}.card-body strong{color:#203e54}.card-body p{margin:12px 0}code{font:13px ui-monospace,Consolas,monospace;background:#eef3f7;border-radius:4px;padding:2px 4px;overflow-wrap:anywhere}pre{overflow:auto;background:#eef3f7;padding:12px;border-radius:8px}pre code{padding:0}
.evidence{display:block;margin:14px 0;background:#f0f4f7;border:1px solid #d0dce6;border-radius:9px;overflow:hidden}.evidence img{display:block;width:100%;height:auto;max-height:800px;object-fit:contain;background:#e5ecf2}.evidence figcaption{font-size:13px;padding:8px 12px;color:#536779}.missing{color:#a23826;font-size:12px}.evidence-note{color:#65778b;font-size:12px}.technical{border-left:4px solid #58758f}.technical h2{margin:0 0 8px}.technical p{margin:8px 0}footer{color:#617386;font-size:12px;overflow-wrap:anywhere;padding-top:14px}
@media(max-width:760px){main{padding:18px 12px 40px}header{padding:22px}.stats{grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.stat{padding:11px}.stat strong{font-size:24px}.card-head{gap:8px}.card-title{font-size:15px}.card-result,.card-meta{margin-left:0}.business-card>summary{padding:14px}.card-body{padding:0 15px 14px}.toolbar{position:static}.toolbar input{width:100%}.badge{padding:2px 8px}.overview,.record-notes,.technical{padding:14px}}
@media print{body{background:#fff}main{max-width:none;padding:0}.toolbar,.visibility{display:none}header{background:#fff;color:#183047;border:1px solid #ddd}header p{color:#526779}.business-card,.overview,.record-notes,.technical{break-inside:avoid}.business-card[hidden]{display:block}.evidence img{max-height:600px}.stat{border-color:#aaa}.chevron{display:none}}
"""

JAVASCRIPT = r"""
const cards=Array.from(document.querySelectorAll('.business-card'));
const state=document.getElementById('state-filter'),search=document.getElementById('search');
function filterCards(){const query=search.value.trim().toLocaleLowerCase();let visible=0;for(const card of cards){const show=(!state.value||card.dataset.status===state.value)&&(!query||card.textContent.toLocaleLowerCase().includes(query));card.hidden=!show;if(show)visible++;}document.getElementById('visibility').textContent=`显示 ${visible} / ${cards.length} 项；统计始终按全部业务项当前结果。`;document.getElementById('empty').hidden=visible!==0;}
state.addEventListener('change',filterCards);search.addEventListener('input',filterCards);
document.getElementById('expand-all').addEventListener('click',()=>cards.filter(card=>!card.hidden).forEach(card=>card.open=true));
document.getElementById('collapse-all').addEventListener('click',()=>cards.forEach(card=>card.open=false));
let printState=[];window.addEventListener('beforeprint',()=>{printState=Array.from(document.querySelectorAll('details')).map(card=>[card,card.open]);printState.forEach(([card])=>card.open=true);});window.addEventListener('afterprint',()=>printState.forEach(([card,open])=>card.open=open));
filterCards();
"""


def generate(record: Path) -> tuple[Path, Counter, set[str], set[str]]:
    record = record.resolve(strict=True)
    report = record.with_name("verification-report.html")
    raw = record.read_bytes()
    source = raw.decode("utf-8-sig")
    intro, items, notes = parse_record(source)
    counts = Counter(item.status for item in items)
    renderer = Renderer(record, report)
    stats = '<div class="stat"><strong>22</strong><span>全部业务项</span></div>' + "".join(
        f'<div class="stat" data-count-status="{status}"><strong>{counts[status]}</strong><span>{status}</span></div>' for status in STATUSES)
    cards = []
    for item in items:
        opened = " open" if item.number in {1, 9, 21} else ""
        cards.append(f'<details class="business-card" id="business-{item.number}" data-status="{item.status}"{opened}>'
                     f'<summary><div class="card-head"><span class="number">{item.number}</span><span class="card-title">{html.escape(item.title)}</span>'
                     f'<span class="badge {STATUS_STYLE[item.status]}">{item.status}</span><span class="chevron" aria-hidden="true">▶</span></div>'
                     '<p class="card-result">' + html.escape(item.result) + '</p><div class="card-meta"><span>截图：'
                     + html.escape(item.screenshot) + '</span><span>用户决定：' + html.escape(item.decision)
                     + '</span></div></summary><div class="card-body">' + renderer.markdown(item.body, show_images=True) + '</div></details>')
    # 共同说明、历史与技术关联保持记录原文；技术检查不参与上面的状态提取。
    intro = re.sub(r"^# .+\n", "", intro, count=1)
    overview = renderer.markdown(intro)
    rendered_notes = renderer.markdown(notes)
    source_href = renderer.target(record.name)[0]
    metadata = re.search(r"记录版本[：:]([^；;\n]+)", source)
    version = metadata.group(1).strip() if metadata else "以源记录为准"
    generated = datetime.now().astimezone().isoformat(timespec="seconds")
    technical_href = renderer.target("../2026-10-03-迁移技术检查/HTTP技术检查.json")[0]
    document = '''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>AI 图片编辑完整迁移业务验收报告</title><style>__CSS__</style></head>
<body><main>
<header><h1>AI 图片编辑完整迁移业务验收报告</h1><p>验收准备与实际受阻记录 · 按完整业务项判断，保留子场景的实际证据</p><p>记录版本：__VERSION__ · 用户决定由记录逐项提供</p></header>
<section class="stats" aria-label="按唯一记录自动统计的当前结果">__STATS__</section>
<p class="notice">统计来自每项最后一次明确填写的当前结果。登录入口可访问、部分 CLI 样本通过、技术构建或接口成功，均不能自动算作完整业务项通过。历史与恢复条件在各卡片中保留。</p>
<details class="overview"><summary>共同前提、当前环境、要求依据与恢复条件</summary>__OVERVIEW__</details>
<section aria-label="全部二十二项业务验收清单">
<div class="toolbar"><label for="state-filter">结果筛选<select id="state-filter"><option value="">全部结果</option>__OPTIONS__</select></label><input id="search" type="search" aria-label="搜索业务名称与记录内容" placeholder="搜索业务名称、前提或实际记录"><button type="button" id="expand-all">展开所选项</button><button type="button" id="collapse-all">折叠全部</button></div>
<p class="visibility" id="visibility" aria-live="polite"></p><p class="empty" id="empty" hidden>没有符合筛选条件的业务项。</p>__CARDS__
</section>
<section class="technical" aria-label="独立技术证据"><h2>技术证据单独记录</h2><p>构建、单元检查、协议检查和只读技术宿主截图的原结论保留，不参与本页业务结果统计，不充当真实模型或登录后完整流程通过。</p><p><a href="__TECHNICAL_HREF__">查看已有 HTTP 技术检查记录</a>；其余技术材料入口见唯一记录中的实施记录链接。</p></section>
<details class="record-notes"><summary>记录中的当前统计、后续安排与历史说明</summary>__NOTES__</details>
<footer><p>本页由 <code>scripts/aipicture/生成迁移验收报告.py</code>从<a href="__SOURCE_HREF__">业务验收记录.md</a>生成。所有判定、实际输入和证据维护在源记录，本页不接受编辑结果或代签用户接受。</p><p>生成时间：__GENERATED__；源记录 SHA256：<code>__SHA__</code>。重新运行生成器即可同步源记录的最新追加；页面视觉检查由实际打开后确认。</p></footer>
</main><script>__JS__</script></body></html>'''
    replacements = {
        "CSS": CSS, "JS": JAVASCRIPT, "VERSION": html.escape(version), "STATS": stats,
        "OVERVIEW": overview, "CARDS": "\n".join(cards), "NOTES": rendered_notes,
        "OPTIONS": "".join(f'<option value="{status}">{status}</option>' for status in STATUSES),
        "SOURCE_HREF": html.escape(source_href or record.name, quote=True),
        "TECHNICAL_HREF": html.escape(technical_href or "", quote=True),
        "GENERATED": html.escape(generated), "SHA": hashlib.sha256(raw).hexdigest(),
    }
    for name, value in replacements.items():
        document = document.replace("__" + name + "__", value)
    # 若记录在生成过程中被另一执行者修改，保留上一次报告并要求重新读取，防止错版。
    if record.read_bytes() != raw:
        raise RuntimeError("源记录在生成过程中变化，未覆盖报告；请重新运行")
    report.write_text(document, encoding="utf-8")
    return report, counts, renderer.missing_links, renderer.images


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="唯一 Markdown 验收记录路径")
    args = parser.parse_args()
    try:
        report, counts, missing, images = generate(args.input)
    except (OSError, UnicodeError, ValueError, RuntimeError) as error:
        parser.exit(1, f"生成失败：{error}\n")
    print(f"报告：{report}")
    print("完整业务项当前结果：" + "；".join(f"{status} {counts[status]}" for status in STATUSES))
    print(f"真实本地图像引用：{len(images)}；缺失相对链接：{len(missing)}")
    for link in sorted(missing):
        print(f"证据文件尚未找到：{link}")
    return 2 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
