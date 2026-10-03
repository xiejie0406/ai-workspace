"""仅生成离线验收材料；冻结产品的render/verify与独立OOXML核对，不执行业务。"""
from __future__ import annotations

import copy
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
from io import BytesIO
import json
from pathlib import Path
import posixpath
import sys
import uuid
from xml.etree import ElementTree as ET
import zipfile

OUT = Path(__file__).resolve().parent
REPO = next(parent for parent in OUT.parents if (parent / "python/aippt-worker/app").is_dir())
WORKER = REPO / "python/aippt-worker"
sys.path.insert(0, str(WORKER))
sys.stdout.reconfigure(encoding="utf-8")

from app.domain.content import Deck
from app.domain.export_check import run_export_check
from app.domain.flex_edit import build_flex_tree_from_fixed
from app.domain.layout import get_layout
from app.domain.sample import load_sample_deck
from app.domain.text_metrics import unbind_font_faces
from app.render.pptx import render_deck_to_pptx
from app.render.verify import verify_pptx
from app.worker.catalog import initialize_catalog
from app.worker.fonts import bind_task_fonts

NS = {
    "p": "http://schemas.openxmlformats.org/presentationml/2006/main",
    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
    "c": "http://schemas.openxmlformats.org/drawingml/2006/chart",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "rel": "http://schemas.openxmlformats.org/package/2006/relationships",
    "s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
}


def stable(name):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "aippt-offline-40:" + name))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def walk(node):
    yield node
    for child in node.get("children", []):
        yield from walk(child)


def frame_strings(block):
    kind = block["type"]
    if kind == "text": return [block["text"]]
    if kind == "bullets": return block["items"]
    if kind == "kpi": return [value for value in [block["value"], block["label"], block.get("note")] if value]
    if kind == "cards": return [item[field] for item in block["items"] for field in ["title", "desc", "icon"] if item.get(field)]
    if kind == "callout": return [value for value in [block["text"], block.get("icon")] if value]
    return []


def prepare_inputs():
    seeds = load_sample_deck().model_dump(mode="json")["slides"]
    pages, expectations = [], []
    chart_kinds = ["bar", "column", "line", "pie"]
    chart_index = 0
    for index in range(1, 41):
        seed = copy.deepcopy(seeds[(index - 1) % len(seeds)])
        page_id = stable(f"slide:{index}")
        original_ids = {block["id"]: stable(f"slide:{index}:block:{ordinal}") for ordinal, block in enumerate(seed["blocks"], 1)}
        original_title = next((block["id"] for block in seed["blocks"] if block["type"] == "text" and block["slot_id"] == "title"), None)
        if original_title is None:
            original_title = next(node["block_id"] for node in walk(seed["layout_tree"]) if node.get("type") == "block" and node.get("text_style") == "title")
        title = f"验收{index:02d} · Slide{index:02d} 🙂"
        notes = f"验收第{index:02d}页独立备注。\nSpeaker note {index:02d}: preserve editable objects.\n中文、English、emoji 🙂🚀；仅离线样本，未执行业务验收。"
        placeholder_boxes = []
        for ordinal, block in enumerate(seed["blocks"], 1):
            old_id = block["id"]
            block["id"] = original_ids[old_id]
            block["locked"] = ordinal % 2 == 0
            if old_id == original_title:
                block["text"] = title
            if block["type"] == "bullets":
                block["items"] = [f"第{index:02d}页中文要点", f"Editable bullet {index:02d}", "Unicode 🙂🚀"]
            elif block["type"] == "kpi":
                block.update(value=f"{index:02d}.{ordinal}", label=f"指标{ordinal} Metric", note=f"核对{index:02d}-{ordinal} 🙂")
            elif block["type"] == "cards":
                block["items"] = [{"title":f"卡片{index:02d}A", "desc":f"中文正文 Edit {index:02d}A", "icon":"🙂"}, {"title":f"卡片{index:02d}B", "desc":f"English body {index:02d}B", "icon":"🚀"}]
            elif block["type"] == "callout":
                block.update(text=f"核对注{index:02d}：Editable callout 🙂", icon="🙂")
            elif block["type"] == "table":
                block.update(header=["核对项", "值", "English"], rows=[[f"第{index:02d}页", "123.50", "Editable cell"], ["中文", f"ID-{index:02d}", "🙂"]])
            elif block["type"] == "chart":
                kind = chart_kinds[chart_index]; chart_index += 1
                series = [{"name":f"Series {index:02d} A", "values":[index + 11.25, index + 22.5, index + 33.75]}]
                if kind != "pie": series.append({"name":f"Series {index:02d} B", "values":[index + 8.0, index + 15.0, index + 19.0]})
                block.update(chart_type=kind, categories=["甲A", "乙B", "丙C"], series=series, unit="件" if kind != "pie" else None)
            elif block["type"] == "image":
                block.update(url=None, source="placeholder", alt=f"第{index:02d}页可编辑几何占位图", credit=None)
                rect = get_layout(seed["layout_id"]).slot_by_id(block["slot_id"]).rect.model_dump()
                placeholder_boxes.append({"block_id":block["id"], "rect":rect})
        seed.update(id=page_id, speaker_notes=notes)
        if seed["layout_mode"] == "flex":
            for node_index, node in enumerate(walk(seed["layout_tree"]), 1):
                node["id"] = stable(f"slide:{index}:node:{node_index}")
                if node["type"] == "block": node["block_id"] = original_ids[node["block_id"]]
        elif index % 2 == 0:
            seed["layout_mode"] = "flex"
            seed["layout_tree"] = build_flex_tree_from_fixed(get_layout(seed["layout_id"]), seed["blocks"]).model_dump(mode="json")
            for node_index, node in enumerate(walk(seed["layout_tree"]), 1): node["id"] = stable(f"slide:{index}:node:{node_index}")
        if seed["layout_mode"] == "flex":
            for block in seed["blocks"]: block["slot_id"] = block["id"]
        seed.update(position=index, title=title, status="ready", outline_page_id=stable(f"outline:{index}"), error=None)
        pages.append(seed)
        expectations.append({"position":index, "slide_id":page_id, "title":title, "speaker_notes":notes, "layout_id":seed["layout_id"], "layout_mode":seed["layout_mode"], "blocks":[{"id":b["id"], "type":b["type"], "slot_id":b["slot_id"], "locked":b["locked"]} for b in seed["blocks"]], "native_frame_texts":[value for block in seed["blocks"] for value in frame_strings(block)], "native_tables":[{"header":b["header"], "rows":b["rows"]} for b in seed["blocks"] if b["type"] == "table"], "native_charts":[{key:b[key] for key in ["chart_type", "categories", "series", "unit"]} for b in seed["blocks"] if b["type"] == "chart"], "native_placeholders":placeholder_boxes, "raster_picture_count":0})
    catalog = initialize_catalog()
    project = {"id":stable("project"), "title":"四十页可编辑核对样本", "audience":"离线验收准备", "tone":"professional", "page_count":40, "theme_id":"ivory", "layout_mode":"flex", "content_density":"medium", "theme_overrides":{}, "status":"ready"}
    snapshot = {"project":project, "slides":pages, "catalog_version":catalog, "font_manifest_hash":"unavailable", "synthetic_offline_material":True}
    expected = {"scope":"输入预期，先于生成保存；并非Office或真实导出链通过", "expected_page_count":40, "page_order":[page["slide_id"] for page in expectations], "theme_id":"ivory", "catalog_version":catalog, "font_manifest_hash":"unavailable", "font_mode":"estimate", "expected_block_types":sorted({b["type"] for page in pages for b in page["blocks"]}), "expected_chart_types":chart_kinds, "whole_page_bitmap_allowed":False, "pages":expectations, "limitations":["图片全部为明确占位几何形状，没有真实图源或Provider调用", "字体估算，未装字体、未核许可", "第36项Office编辑与真实导出链仍未执行"]}
    return snapshot, expected


def relations(archive, part):
    path = posixpath.join(posixpath.dirname(part), "_rels", posixpath.basename(part) + ".rels")
    if path not in archive.namelist(): return {}
    result = {}
    for item in ET.fromstring(archive.read(path)):
        if item.get("TargetMode") == "External": continue
        target = item.attrib["Target"]
        result[item.attrib["Id"]] = {"target":posixpath.normpath(posixpath.join(posixpath.dirname(part), target)), "type":item.attrib["Type"]}
    return result


def texts(node, namespace="a"):
    return [element.text or "" for element in node.findall(f".//{namespace}:t", NS)]


def string_cache(node, path):
    points = node.findall(path + "/c:pt", NS)
    return [(point.findtext("c:v", namespaces=NS) or "") for point in sorted(points, key=lambda value:int(value.attrib["idx"]))]


def workbook_cells(data):
    with zipfile.ZipFile(BytesIO(data)) as book:
        shared = []
        if "xl/sharedStrings.xml" in book.namelist():
            shared = ["".join(texts(item, "s")) for item in ET.fromstring(book.read("xl/sharedStrings.xml")).findall("s:si", NS)]
        sheet = ET.fromstring(book.read("xl/worksheets/sheet1.xml"))
        result = {}
        for cell in sheet.findall(".//s:c", NS):
            value = cell.findtext("s:v", namespaces=NS)
            if cell.get("t") == "s": value = shared[int(value)]
            elif cell.get("t") == "inlineStr": value = "".join(texts(cell, "s"))
            result[cell.attrib["r"]] = value
        return result


def independent_xml(data, expected):
    errors, pages = [], []
    with zipfile.ZipFile(BytesIO(data)) as archive:
        presentation = ET.fromstring(archive.read("ppt/presentation.xml"))
        rels = relations(archive, "ppt/presentation.xml")
        slide_parts = [rels[item.attrib['{'+NS['r']+'}id']]["target"] for item in presentation.findall("p:sldIdLst/p:sldId", NS)]
        if len(slide_parts) != 40: errors.append("presentation slide count differs from 40")
        canvas = presentation.find("p:sldSz", NS)
        if (int(canvas.attrib["cx"]), int(canvas.attrib["cy"])) != (12192000, 6858000): errors.append("canvas differs from 960x540pt")
        for index, (part, plan) in enumerate(zip(slide_parts, expected["pages"]), 1):
            root = ET.fromstring(archive.read(part)); linked = relations(archive, part)
            frame_nodes = root.findall(".//p:sp/p:txBody", NS)
            frame_texts = ["".join(texts(node)) for node in frame_nodes]
            flat = "\n".join(frame_texts)
            missing = [value for value in plan["native_frame_texts"] if value not in flat]
            if missing: errors.append({"page":index, "missing_native_text":missing})
            tables = []
            for table in root.findall(".//a:tbl", NS):
                tables.append([["".join(texts(cell)) for cell in row.findall("a:tc", NS)] for row in table.findall("a:tr", NS)])
            expected_tables = [[item["header"], *item["rows"]] for item in plan["native_tables"]]
            if tables != expected_tables: errors.append({"page":index, "table_cells_mismatch":True})
            charts = []
            for reference, chart_plan in zip(root.findall(".//c:chart", NS), plan["native_charts"]):
                chart_part = linked[reference.attrib['{'+NS['r']+'}id']]["target"]
                chart = ET.fromstring(archive.read(chart_part))
                plot = chart.find("c:chart/c:plotArea", NS)
                kind = chart_plan["chart_type"]
                expected_tag = "barChart" if kind in {"bar", "column"} else kind+"Chart"
                actual_plot = plot.find("c:"+expected_tag, NS)
                if actual_plot is None: errors.append({"page":index, "chart_kind_mismatch":kind}); continue
                if kind in {"bar", "column"} and actual_plot.find("c:barDir", NS).get("val") != ("bar" if kind == "bar" else "col"): errors.append({"page":index, "chart_direction_mismatch":True})
                found_series = []
                for series in actual_plot.findall("c:ser", NS):
                    found_series.append({"name":series.findtext("c:tx/c:strRef/c:strCache/c:pt/c:v", namespaces=NS), "categories":string_cache(series, "c:cat/c:strRef/c:strCache"), "values":string_cache(series, "c:val/c:numRef/c:numCache")})
                wanted = chart_plan["series"]
                series_ok = len(found_series)==len(wanted) and all(actual["name"]==intent["name"] and actual["categories"]==chart_plan["categories"] and [Decimal(value) for value in actual["values"]]==[Decimal(str(value)) for value in intent["values"]] for actual,intent in zip(found_series,wanted))
                if not series_ok: errors.append({"page":index,"chart_series_categories_or_numbers_mismatch":True})
                chart_rels = relations(archive,chart_part)
                external_data = chart.find("c:externalData",NS)
                cells = {}
                if external_data is not None:
                    workbook = chart_rels[external_data.attrib['{'+NS['r']+'}id']]["target"]
                    cells = workbook_cells(archive.read(workbook))
                workbook_ok = bool(cells)
                for row, category in enumerate(chart_plan["categories"],2): workbook_ok = workbook_ok and cells.get(f"A{row}")==category
                for ordinal, series in enumerate(wanted,1):
                    column = chr(ord("A")+ordinal)
                    workbook_ok = workbook_ok and cells.get(column+"1")==series["name"]
                    for row,value in enumerate(series["values"],2): workbook_ok = workbook_ok and cells.get(f"{column}{row}") is not None and Decimal(cells[f"{column}{row}"])==Decimal(str(value))
                if not workbook_ok: errors.append({"page":index,"editable_chart_workbook_mismatch":True})
                charts.append({"part":chart_part,"kind":kind,"series":found_series,"cache_matches":series_ok,"embedded_workbook_matches":workbook_ok})
            chart_count = len(root.findall(".//c:chart", NS))
            if chart_count != len(plan["native_charts"]): errors.append({"page":index,"native_chart_count_mismatch":True})
            notes_parts = [value["target"] for value in linked.values() if value["type"].endswith("/notesSlide")]
            actual_notes = None
            if len(notes_parts)==1:
                note_root = ET.fromstring(archive.read(notes_parts[0]))
                for shape in note_root.findall("p:cSld/p:spTree/p:sp", NS):
                    placeholder = shape.find("p:nvSpPr/p:nvPr/p:ph", NS)
                    if placeholder is not None and placeholder.get("type")=="body": actual_notes = "\n".join("".join(texts(paragraph)) for paragraph in shape.findall("p:txBody/a:p", NS))
            if actual_notes != plan["speaker_notes"]: errors.append({"page":index,"notes_mismatch":True})
            pictures = len(root.findall(".//p:pic", NS))
            if pictures: errors.append({"page":index,"unexpected_bitmap_count":pictures})
            placeholder_checks = []
            for placeholder in plan["native_placeholders"]:
                rect = placeholder["rect"]; wanted_bounds = [rect["x"]*12192000,rect["y"]*6858000,rect["w"]*12192000,rect["h"]*6858000]
                ellipses=custom=0
                for shape in root.findall(".//p:sp", NS):
                    transform = shape.find("p:spPr/a:xfrm", NS)
                    if transform is None: continue
                    off,ext = transform.find("a:off",NS),transform.find("a:ext",NS)
                    if off is None or ext is None: continue
                    x,y,w,h = [int(off.get("x")),int(off.get("y")),int(ext.get("cx")),int(ext.get("cy"))]
                    bx,by,bw,bh = wanted_bounds
                    if x>=bx-6350 and y>=by-6350 and x+w<=bx+bw+6350 and y+h<=by+bh+6350:
                        geometry=shape.find("p:spPr/a:prstGeom",NS)
                        if geometry is not None and geometry.get("prst")=="ellipse": ellipses+=1
                        if shape.find("p:spPr/a:custGeom",NS) is not None: custom+=1
                passed=ellipses>=2 and custom>=1
                if not passed: errors.append({"page":index,"native_placeholder_mismatch":placeholder["block_id"]})
                placeholder_checks.append({"block_id":placeholder["block_id"],"ellipses":ellipses,"custom_shapes":custom,"passed":passed})
            pages.append({"position":index,"slide_part":part,"slide_id_from_expected_order":plan["slide_id"],"title_present":plan["title"] in flat,"native_text_frame_count":len(frame_nodes),"native_texts_match":not missing,"native_table_count":len(tables),"table_cells_match":tables==expected_tables,"native_chart_count":chart_count,"charts":charts,"speaker_notes_match":actual_notes==plan["speaker_notes"],"picture_count":pictures,"placeholders":placeholder_checks})
    return {"passed":not errors,"page_count":len(pages),"errors":errors,"pages":pages,"method":"独立解析presentation关系页序、slide/notes/chart XML和嵌入XLSX，不调用产品verify内部助手","whole_page_bitmap_count":sum(page["picture_count"] for page in pages)}


def main():
    names = ["四十页原始快照.json", "四十页输入预期.json", "四十页可编辑核对样本.pptx", "四十页技术回读.json"]
    if any((OUT/name).exists() for name in names): raise SystemExit("拒绝覆盖已有验收材料")
    snapshot, expected = prepare_inputs()
    write_json(OUT/names[0],snapshot)
    write_json(OUT/names[1],expected)
    identity,token = bind_task_fonts({"font_manifest_hash":"unavailable"})
    report = {"checked_at_utc":datetime.now(timezone.utc).isoformat(),"business_item_36":"blocked_office_editing_and_real_export_chain_not_executed","font_identity":identity,"provider_requests":False,"center_connections":False,"input_snapshot_sha256":hashlib.sha256((OUT/names[0]).read_bytes()).hexdigest(),"input_expectations_sha256":hashlib.sha256((OUT/names[1]).read_bytes()).hexdigest()}
    try:
        deck = Deck.model_validate({**snapshot["project"],"slides":snapshot["slides"]})
        quality = run_export_check(deck,slide_titles={page["slide_id"]:page["title"] for page in expected["pages"]})
        report["input_quality"] = quality.model_dump(mode="json")
        if not quality.export_allowed:
            report["technical_status"]="failed_input_quality";write_json(OUT/names[3],report);raise SystemExit("输入质量阻断；原输入与失败已保存，不裁减内容")
        data = render_deck_to_pptx(deck).getvalue()
        (OUT/names[2]).write_bytes(data)
        saved = (OUT/names[2]).read_bytes()
        report["artifact"]={"filename":names[2],"size":len(saved),"sha256":hashlib.sha256(saved).hexdigest()}
        report["original_verify"] = verify_pptx(saved,deck).model_dump(mode="json")
        report["independent_ooxml"] = independent_xml(saved,expected)
        report["technical_status"]="passed" if report["original_verify"]["passed"] and report["independent_ooxml"]["passed"] else "failed_readback"
        report["coverage"]={"pages":40,"fixed_pages":sum(page["layout_mode"]=="fixed" for page in expected["pages"]),"flex_pages":sum(page["layout_mode"]=="flex" for page in expected["pages"]),"block_types":expected["expected_block_types"],"chart_types":expected["expected_chart_types"],"unique_titles":len({page["title"] for page in expected["pages"]}),"unique_notes":len({page["speaker_notes"] for page in expected["pages"]})}
        write_json(OUT/names[3],report)
        print(json.dumps({"status":report["technical_status"],"artifact":report["artifact"],"coverage":report["coverage"],"original_verify":report["original_verify"],"independent_errors":report["independent_ooxml"]["errors"],"fonts":identity},ensure_ascii=False))
        if report["technical_status"]!="passed": raise SystemExit("回读失败证据已保留；不能记业务通过")
    finally:
        unbind_font_faces(token)


if __name__=="__main__": main()
