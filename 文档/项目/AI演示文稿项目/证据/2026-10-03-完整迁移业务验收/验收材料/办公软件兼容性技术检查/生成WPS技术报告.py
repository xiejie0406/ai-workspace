"""仅从已保存的技术 JSON 生成本目录 HTML，不读取或改写 PPTX。"""
from datetime import datetime, timezone
import hashlib
from html import escape
from html.parser import HTMLParser
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
RESULT = HERE / "WPS副本离线语义核对-实际.json"
OUTPUT = HERE / "办公软件兼容性技术检查.html"
RECORD = HERE / "技术报告生成记录.json"
STATUS = {"saved_as_planned": ("ok", "已编辑，保存副本可回读新值"),
          "recorded_edit_not_saved_in_pptx": ("fail", "界面编辑有记录；保存副本仍为原值"),
          "not_executed_original_retained": ("pending", "未执行；保存副本原值保留"),
          "unexpected_saved_content": ("fail", "保存内容异常"), "unrecorded_change": ("fail", "出现无操作记录的变化")}
SHOTS = {("文字", 1): ["文字编辑前.png", "文字编辑后.png"],
         ("演讲备注", 1): ["备注编辑后.jpg"],
         ("表格", 10): ["表格编辑前.jpg", "表格编辑后.jpg"],
         ("图表数据", 5): ["横条图编辑前.jpg", "横条图数据编辑前.jpg", "横条图数据编辑后.jpg", "横条图编辑后.jpg"],
         ("图表数据", 16): ["柱状图编辑前.jpg", "柱状图数据编辑前.jpg", "柱状图数据编辑后.jpg"]}


def text(value):
    return escape(str(value), quote=True)


def pretty(value):
    return text(json.dumps(value, ensure_ascii=False, indent=2))


def link(path, label):
    return f'<a href="{text(path)}">{text(label)}</a>'


def main():
    if OUTPUT.exists() or RECORD.exists():
        raise ValueError("拒绝覆盖已有技术报告")
    raw = RESULT.read_bytes()
    result = json.loads(raw)
    pages = result["actual_native"]["pages"]
    base = result["baseline_native"]["pages"]
    rows, sections = [], []
    for step in result["steps"]:
        css, label = STATUS[step["status"]]
        actual = step["actual_saved_values"]
        if step["name"] == "图表数据":
            observed = f'缓存 {actual["cache"]}；内嵌 {step["sheet"]}!{step["cell"]} = {actual["embedded_cell"]["value"]}'
        elif step["name"] == "文字":
            observed = f'原眉题出现 {actual["before_count"]} 次，新眉题出现 {actual["after_count"]} 次'
        elif step["name"] == "表格":
            observed = f'第 {step["row"]} 行第 {step["column"]} 列 = {actual["cell"]}'
        else:
            observed = "原三段备注保留，第四段为 Office备注核对01" if step["status"] == "saved_as_planned" else json.dumps(actual, ensure_ascii=False)
        operation = "有实际界面编辑记录" if step["recorded_edit"] else "没有执行记录，保持未执行"
        rows.append(f'<tr><td>{step["number"]}</td><td>第 {step["page"]} 页{text(step["name"])}</td><td>{text(operation)}</td><td class="{css}">{text(label)}</td><td>{text(observed)}</td></tr>')
        images = []
        for name in SHOTS.get((step["name"], step["page"]), []):
            if not (HERE / name).is_file():
                raise ValueError(f"实际截图文件缺失：{name}")
            images.append(f'<figure><a href="{text(name)}"><img src="{text(name)}" alt="{text(name)}" loading="lazy"></a><figcaption>{text(name)}</figcaption></figure>')
        sections.append(f'<section><h3>{step["number"]}．第 {step["page"]} 页{text(step["name"])}</h3><p class="{css}">{text(label)}。{text(observed)}。</p><details><summary>事前预期及保存实值</summary><pre>{pretty({"before":step["before"],"after":step["after"],"actual":actual})}</pre></details>' + ('<div class="gallery">' + "".join(images) + "</div>" if images else "<p>该项未执行，没有编辑后的实际截图，不计通过。</p>") + "</section>")
    failures = "".join(f'<li>第 {item.get("page", "—")} 页：{text(item["name"])}。{link("WPS副本离线语义核对-实际.json", "查看实值与独立预期")}</li>' for item in result["failures"])
    checks = len(result["checks"])
    passed = sum(item["passed"] for item in result["checks"])
    charts = [chart for page in pages for chart in page["charts"]]
    shape_counts_equal = all(source["native_shape_count"] == actual["native_shape_count"] for source, actual in zip(base, pages))
    chart_sections = []
    for page in pages:
        for chart in page["charts"]:
            chart_sections.append(f'<details><summary>第 {page["position"]} 页 {text(chart["type"])}：类别、系列、缓存及内嵌格值</summary><pre>{pretty({"chart_part":chart["part"],"workbook_part":chart["workbook_part"],"series":chart["series"],"workbook":chart["workbook"]["nonempty_sheets"],"cache_workbook_checks":chart["cache_workbook_checks"]})}</pre></details>')
    document = f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>办公软件兼容性技术检查：部分完成</title>
<style>body{{font-family:system-ui,sans-serif;margin:0;background:#f5f6f8;color:#172033;line-height:1.65}}main{{max-width:1160px;margin:auto;padding:28px}}h1{{font-size:28px;line-height:1.35}}h2{{margin-top:32px}}h3{{font-size:20px}}section,.notice{{background:white;padding:18px 22px;border:1px solid #dde2e8;margin:18px 0;border-radius:6px}}.notice{{border-left:5px solid #bb6200}}table{{border-collapse:collapse;width:100%;background:white;font-size:14px}}th,td{{border:1px solid #dbe0e7;padding:10px;text-align:left;vertical-align:top}}th{{background:#edf0f5}}.ok{{color:#1b6b3f}}.fail{{color:#a51e1e;font-weight:600}}.pending{{color:#805300}}pre{{white-space:pre-wrap;word-break:break-word;background:#f2f4f7;padding:12px;font-size:13px}}a{{color:#175bae}}.gallery{{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}}figure{{margin:0}}img{{width:100%;height:auto;display:block;border:1px solid #ddd}}figcaption{{font-size:13px;color:#526075;margin:5px 0}}details{{margin:12px 0}}summary{{cursor:pointer}}@media(max-width:760px){{main{{padding:14px}}.gallery{{grid-template-columns:1fr}}table{{font-size:12px}}}}body{{overflow-wrap:anywhere}}code{{word-break:break-all}}</style></head>
<body><main><h1>办公软件兼容性技术检查：部分完成</h1>
<div class="notice"><p><strong>七项计划中，五项有界面编辑记录，四项新值已进入保存副本。</strong>第16页柱状图的主PPTX缓存和内嵌工作簿仍为 27.25，未达到 127.25 的保存后预期；第27、38页未执行，原值保留。</p><p>WPS Office 12.1.0.28505；合成四十页材料；字体保持 estimate。本报告不是 PowerPoint 兼容性证明，也不是41项真实业务验收通过。没有恢复 ComputerUse、补点保存、改写PPTX或连接中心/Provider。</p></div>
<p>读取时间（UTC）：{text(result["checked_at_utc"])}。副本 SHA256：<code>{text(result["copy"]["sha256"])}</code>；本次读取期间字节稳定：{text(result["copy"]["stable_during_check"])}。</p>
<p>{link("操作前独立预期.json", "操作前独立预期")} · {link("实际操作记录.json", "实际界面操作记录")} · {link("WPS副本离线语义核对-实际.json", "完整技术回读JSON")} · {link("WPS副本离线语义核对.py", "独立只读核对脚本")} · {link("WPS原生编辑检查副本.pptx", "WPS实际保存副本")} · {link("../四十页可编辑核对样本.pptx", "未修改原样本")} · {link("../四十页输入预期.json", "独立输入预期")}</p>
<h2>七项计划的实际执行与保存</h2><table><thead><tr><th>顺序</th><th>操作</th><th>实际执行</th><th>保存回读判定</th><th>保存文件中的内容</th></tr></thead><tbody>{''.join(rows)}</tbody></table>
<h2>四十页内容保留核账</h2><section><p>共 {checks} 个技术断言，{passed} 个符合，{len(result["failures"])} 个不符合；两个不符合均属于第16页新值尚未进入保存副本，不能把它们改记为通过，也不能据此判断最终保存后的WPS编辑能力。</p>
<ul><li>40页数量、关系页序和40个独立标题保留；121个非空原生文本框按逐页完整段落文本 multiset 比较，仅第1页眉题作计划中的单一替换。</li><li>全部3张原生表格的每一格核对；第10页第2行第2列为 456.75，其他格值保留。</li><li>四张原生图表仍在，类别与全部系列核对；全部缓存与内嵌XLSX范围实值一致。P5 为116.25；P16仍27.25；P27/P38仍38.25/49.25。完整工作表非空格值未见其他变化。</li><li>40页演讲备注原段落精确保留，只有第1页按计划追加第四段。每页原生shape数量与原样本一致：{text(shape_counts_equal)}；共458个原生shape，3表、4图，0个图片shape及0个图像引用。</li><li>原样本、独立输入、原始快照、事前预期和操作记录哈希保持。本脚本只对一次内存快照解析，再核副本读取期间的字节稳定。</li></ul>
<p>以上证明包内原生内容与保存实值。没有核定字体/样式/精确像素、图表视觉轴线、裁剪或打印表现；WPS可能正规化run、shape名称和part名称，脚本按关系与内容核对，不要求格式字节保持。</p></section>
<h2>保留的不符合项</h2><ul>{failures}</ul>
<h2>实际截图与逐项记录</h2><p>这些链接只指向已存在的实际截图。截图回读由根执行者完成；本脚本没有重新操作或渲染界面。图表工作簿截图焦点/鼠标可能遮挡目标格，最终判定采用已保存PPTX中的缓存和内嵌工作簿实值。</p>{''.join(sections)}
<h2>四图数据的独立回读明细</h2>{''.join(chart_sections)}
<section><p>业务执行计数：0；业务通过计数：0；PowerPoint验证：未执行。P16需要用户明确恢复操作后再完成主文稿最终保存与定向回读；P27、P38需要实际编辑及保存。未执行步骤保持原状。</p></section>
</main></body></html>'''
    OUTPUT.write_text(document, encoding="utf-8")
    class Links(HTMLParser):
        def __init__(self):
            super().__init__()
            self.targets = []
        def handle_starttag(self, tag, attrs):
            self.targets += [value for key, value in attrs if key in ("href", "src")]
    parser = Links()
    parser.feed(document)
    for target in parser.targets:
        if not (HERE / target).is_file():
            raise ValueError(f"本地链接缺失：{target}")
    metadata = {"generated_at_utc": datetime.now(timezone.utc).isoformat(), "source_json_sha256": hashlib.sha256(raw).hexdigest(),
                "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "html": str(OUTPUT),
                "html_sha256": hashlib.sha256(OUTPUT.read_bytes()).hexdigest(), "relative_local_link_checks": len(parser.targets),
                "planned_step_rows": len(result["steps"]), "failed_checks": len(result["failures"]),
                "shape_count_per_page_matches": shape_counts_equal, "screenshots_existing_only": True,
                "html_gui_or_browser_rendered": False, "pptx_reads": 0, "pptx_writes": 0, "business_passed": 0}
    RECORD.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
