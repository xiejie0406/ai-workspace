"""只读当前已保存 PPTX；按关系与内容核对，不操作 WPS 或改写材料。"""
from collections import Counter
from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
from io import BytesIO
import json
from pathlib import Path
import posixpath
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
OUTPUT = HERE / "WPS副本离线语义核对-实际.json"
RUN_CONTEXT = {}
SNAPSHOT_SHA = "1600933b16b38e1680028f692fbb78a84976f55512012ba2a0d6f0815e2ace7b"
ACTION_NAMES = {"文字": "edit_native_eyebrow", "表格": "edit_native_table_cell",
                "图表数据": "edit_embedded_chart_workbook", "演讲备注": "append_native_notes"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def local(name):
    return name.rsplit("}", 1)[-1]


def children(node, name):
    return [child for child in node if local(child.tag) == name]


def descend(node, *names):
    nodes = [node]
    for name in names:
        nodes = [child for parent in nodes for child in children(parent, name)]
    return nodes


def one(nodes, label):
    if len(nodes) != 1:
        raise ValueError(f"{label}: expected one, found {len(nodes)}")
    return nodes[0]


def attr_id(node):
    values = [value for key, value in node.attrib.items() if key.endswith("}id")]
    return one(values, "relationship id")


def numeric(value):
    number = Decimal(str(value))
    if not number.is_finite():
        raise ValueError("non-finite number")
    return format(number.normalize(), "f")


def paragraphs(body):
    result = []
    for paragraph in children(body, "p"):
        pieces = []
        for item in paragraph:
            kind = local(item.tag)
            if kind in ("r", "fld"):
                pieces.extend(text.text or "" for text in item.iter() if local(text.tag) == "t")
            elif kind == "br":
                pieces.append("\v")
        result.append("".join(pieces))
    return result


def frame(body):
    return "\n".join(paragraphs(body))


class Package:
    def __init__(self, data):
        self.archive = zipfile.ZipFile(BytesIO(data))
        names = self.archive.namelist()
        if len(names) != len(set(names)) or len(names) > 5000:
            raise ValueError("duplicate or too many ZIP members")
        if sum(item.file_size for item in self.archive.infolist()) > 128 * 1024 * 1024:
            raise ValueError("ZIP expansion budget exceeded")

    def xml(self, name):
        return ET.fromstring(self.archive.read(name))

    def relationships(self, part):
        relation_part = posixpath.join(posixpath.dirname(part), "_rels", posixpath.basename(part) + ".rels")
        if relation_part not in self.archive.namelist():
            return {}
        result = {}
        for relation in self.xml(relation_part):
            target = relation.get("Target", "")
            external = relation.get("TargetMode") == "External"
            resolved = None if external else posixpath.normpath(posixpath.join(posixpath.dirname(part), target)).lstrip("/")
            if not external and (resolved.startswith("../") or ":" in resolved):
                raise ValueError("invalid internal relationship target")
            result[relation.get("Id")] = {"target": resolved, "type": relation.get("Type", ""), "external": external}
        return result


def workbook(data):
    package = Package(data)
    strings = []
    if "xl/sharedStrings.xml" in package.archive.namelist():
        strings = ["".join(item.text or "" for item in entry.iter() if local(item.tag) == "t")
                   for entry in children(package.xml("xl/sharedStrings.xml"), "si")]
    root = package.xml("xl/workbook.xml")
    relations = package.relationships("xl/workbook.xml")
    sheets = {}
    for sheet in descend(root, "sheets", "sheet"):
        relation = relations[attr_id(sheet)]
        if relation["external"]:
            raise ValueError("external workbook sheet")
        cells = {}
        for cell in package.xml(relation["target"]).iter():
            if local(cell.tag) != "c":
                continue
            address = cell.get("r")
            kind = cell.get("t", "n")
            values = children(cell, "v")
            value = values[0].text if values else None
            if kind == "inlineStr":
                value = "".join(item.text or "" for item in cell.iter() if local(item.tag) == "t")
            elif kind == "s" and value is not None:
                value = strings[int(value)]
            if value is None or value == "":
                continue  # 忽略格式化空格，不忽略实际数值 0。
            if kind in ("n", ""):
                semantic = {"kind": "number", "value": numeric(value)}
            elif kind == "b":
                semantic = {"kind": "boolean", "value": value == "1"}
            elif kind == "e":
                semantic = {"kind": "error", "value": value}
            else:
                semantic = {"kind": "string", "value": value}
            formula = children(cell, "f")
            if formula:
                semantic["formula"] = formula[0].text or ""
            cells[address] = semantic
        sheets[sheet.get("name")] = cells
    return {"sheets": sheets, "nonempty_sheets": {name: cells for name, cells in sheets.items() if cells}}


def cache(node, number=False):
    count_nodes = children(node, "ptCount")
    points = children(node, "pt")
    indexes = [int(item.get("idx")) for item in points]
    count = int(one(count_nodes, "cache point count").get("val"))
    if sorted(indexes) != list(range(count)) or len(indexes) != len(set(indexes)):
        raise ValueError("incomplete or duplicate chart cache indexes")
    values = []
    for point in sorted(points, key=lambda item: int(item.get("idx"))):
        value = one(children(point, "v"), "cache value").text or ""
        values.append(numeric(value) if number else value)
    return values


def reference(node, number=False):
    reference_nodes = children(node, "numRef" if number else "strRef")
    if reference_nodes:
        source = one(reference_nodes, "chart reference")
        values = cache(one(children(source, "numCache" if number else "strCache"), "chart cache"), number)
        formulas = children(source, "f")
        return values, formulas[0].text if formulas else None
    literal = children(node, "numLit" if number else "strLit")
    if literal:
        return cache(one(literal, "chart literal"), number), None
    direct = children(node, "v")
    if not number and direct:
        return [one(direct, "series name").text or ""], None
    raise ValueError("unsupported chart cache source")


def formula_values(formula, book):
    match = re.fullmatch(r"(?:'((?:[^']|'')+)'|([^!]+))!\$?([A-Za-z]+)\$?(\d+)(?::\$?([A-Za-z]+)\$?(\d+))?", formula or "")
    if not match:
        raise ValueError(f"unsupported local chart formula: {formula!r}")
    sheet = (match.group(1) or match.group(2)).replace("''", "'")
    sheet = re.sub(r"^\[[^]]+\]", "", sheet)
    column, first = match.group(3).upper(), int(match.group(4))
    last_column, last = (match.group(5) or column).upper(), int(match.group(6) or first)
    if column != last_column or last < first or last - first > 1000:
        raise ValueError("unsupported chart range")
    return [book["sheets"][sheet][f"{column}{row}"]["value"] for row in range(first, last + 1)]


def chart(package, part):
    root = package.xml(part)
    plot = one([item for item in root.iter() if local(item.tag) == "plotArea"], "plot area")
    native = one([item for item in plot if local(item.tag) in ("barChart", "lineChart", "pieChart")], "native supported chart")
    native_type = local(native.tag)
    direction = children(native, "barDir")
    kind = ("bar" if one(direction, "bar direction").get("val") == "bar" else "column") if native_type == "barChart" else {"lineChart": "line", "pieChart": "pie"}[native_type]
    external_data = one([item for item in root.iter() if local(item.tag) == "externalData"], "embedded data")
    relation = package.relationships(part)[attr_id(external_data)]
    if relation["external"]:
        raise ValueError("external chart workbook")
    book = workbook(package.archive.read(relation["target"]))
    series = []
    consistency = []
    native_series = sorted(children(native, "ser"), key=lambda item: int(one(children(item, "order"), "series order").get("val")))
    for index, item in enumerate(native_series):
        names, name_formula = reference(one(children(item, "tx"), "series name"))
        categories, category_formula = reference(one(children(item, "cat"), "series categories"))
        values, value_formula = reference(one(children(item, "val"), "series values"), True)
        name = one(names, "single series name")
        series.append({"name": name, "categories": categories, "values": values})
        for label, formula, cached in (("name", name_formula, names), ("categories", category_formula, categories), ("values", value_formula, values)):
            if label == "name" and not formula:
                continue  # 原生直接系列名仍由完整表头/系列语义另核，不伪造公式。
            linked = formula_values(formula, book)
            consistency.append({"series": index, "field": label, "formula": formula, "cache": cached,
                                "workbook": linked, "matches": linked == cached})
    return {"part": part, "workbook_part": relation["target"], "type": kind,
            "series": series, "workbook": book, "cache_workbook_checks": consistency}


def deck(data):
    package = Package(data)
    part = "ppt/presentation.xml"
    root = package.xml(part)
    relations = package.relationships(part)
    pages = []
    for number, slide_id in enumerate(descend(root, "sldIdLst", "sldId"), 1):
        slide_relation = relations[attr_id(slide_id)]
        if slide_relation["external"] or not slide_relation["type"].endswith("/slide"):
            raise ValueError("invalid slide relationship")
        slide_part = slide_relation["target"]
        slide = package.xml(slide_part)
        linked = package.relationships(slide_part)
        text_frames = []
        for shape in slide.iter():
            if local(shape.tag) == "sp":
                bodies = children(shape, "txBody")
                if bodies:
                    text = frame(one(bodies, "shape text body"))
                    if any(paragraphs(bodies[0])):
                        text_frames.append(text)
        tables = []
        for table in slide.iter():
            if local(table.tag) == "tbl":
                tables.append([[frame(one(children(cell, "txBody"), "table cell body")) for cell in children(row, "tc")]
                               for row in children(table, "tr")])
        charts = []
        for node in slide.iter():
            if local(node.tag) == "chart" and any(key.endswith("}id") for key in node.attrib):
                relation = linked[attr_id(node)]
                if relation["external"]:
                    raise ValueError("external slide chart")
                charts.append(chart(package, relation["target"]))
        notes_parts = [relation["target"] for relation in linked.values() if relation["type"].endswith("/notesSlide") and not relation["external"]]
        note_part = one(notes_parts, "notes slide relationship")
        note_root = package.xml(note_part)
        bodies = []
        for shape in note_root.iter():
            if local(shape.tag) != "sp":
                continue
            placeholders = descend(shape, "nvSpPr", "nvPr", "ph")
            if any(item.get("type") == "body" for item in placeholders):
                bodies.extend(children(shape, "txBody"))
        notes = paragraphs(one(bodies, "notes body"))
        pages.append({"position": number, "slide_part": slide_part, "notes_part": note_part,
                      "text_frames": text_frames, "tables": tables, "charts": charts, "notes_paragraphs": notes,
                      "native_shape_count": sum(local(item.tag) == "sp" for item in slide.iter()),
                      "picture_count": sum(local(item.tag) == "pic" for item in slide.iter()),
                      "image_reference_count": sum(local(item.tag) == "blip" for item in slide.iter())})
    return {"pages": pages, "page_count": len(pages)}


def structured_equal(left, right):
    return json.dumps(left, ensure_ascii=False, sort_keys=True) == json.dumps(right, ensure_ascii=False, sort_keys=True)


def main():
    if OUTPUT.exists():
        raise ValueError("拒绝覆盖已有核对证据")
    plan_bytes = (HERE / "操作前独立预期.json").read_bytes()
    record_bytes = (HERE / "实际操作记录.json").read_bytes()
    plan = json.loads(plan_bytes)
    record = json.loads(record_bytes)
    original_path = Path(plan["original"]["path"])
    input_path = Path(plan["independent_input"]["path"])
    snapshot_path = original_path.parent / "四十页原始快照.json"
    copy_path = Path(plan["copy"])
    original_bytes, input_bytes = original_path.read_bytes(), input_path.read_bytes()
    copy_bytes = copy_path.read_bytes()  # 一次快照读取；其后的 XML/XLSX 仅从该内存解析。
    RUN_CONTEXT.update(copy={"path": str(copy_path), "bytes": len(copy_bytes), "sha256": sha(copy_bytes)})
    material_checks = {"original_pptx": sha(original_bytes) == plan["original"]["sha256"],
                       "independent_input": sha(input_bytes) == plan["independent_input"]["sha256"],
                       "original_snapshot": sha(snapshot_path.read_bytes()) == SNAPSHOT_SHA}
    if not all(material_checks.values()):
        raise ValueError("原材料哈希不符，禁止将变化当成新预期")
    expected = json.loads(input_bytes)
    baseline, actual = deck(original_bytes), deck(copy_bytes)
    steps = []
    for index, step in enumerate(plan["steps"], 1):
        matched = [event for event in record if event.get("action") == ACTION_NAMES[step["name"]] and event.get("page") == step["page"]]
        steps.append({"number": index, **step, "recorded_edit": bool(matched),
                      "execution": "recorded_edit" if matched else "not_executed", "recorded_events": matched})
    checks, failures = [], []

    def check(name, passed, page=None, detail=None):
        result = {"name": name, "passed": bool(passed)}
        if page is not None:
            result["page"] = page
        if detail is not None:
            result["detail"] = detail
        checks.append(result)
        if not passed:
            failures.append(result)

    check("40页页数", baseline["page_count"] == actual["page_count"] == expected["expected_page_count"] == 40)
    for source, observed, page_plan in zip(baseline["pages"], actual["pages"], expected["pages"]):
        number = source["position"]
        page_steps = [step for step in steps if step["page"] == number and step["recorded_edit"]]
        check("页序与独立标题", source["text_frames"].count(page_plan["title"]) == 1 and observed["text_frames"].count(page_plan["title"]) == 1, number)
        expected_frames = list(source["text_frames"])
        expected_tables = deepcopy(source["tables"])
        expected_notes = list(source["notes_paragraphs"])
        for step in page_steps:
            if step["name"] == "文字":
                if expected_frames.count(step["before"]) != 1:
                    raise ValueError("眉题原值不是唯一原生文本框")
                expected_frames[expected_frames.index(step["before"])] = step["after"]
            elif step["name"] == "表格":
                expected_tables[0][step["row"] - 1][step["column"] - 1] = step["after"]
            elif step["name"] == "演讲备注":
                if "\n".join(source["notes_paragraphs"]) != step["before"]:
                    raise ValueError("原备注与事前预期不一致")
                expected_notes = step["after"].split("\n")
        check("非空原生文本框multiset", Counter(observed["text_frames"]) == Counter(expected_frames), number,
              {"missing": list((Counter(expected_frames) - Counter(observed["text_frames"])).elements()),
               "extra": list((Counter(observed["text_frames"]) - Counter(expected_frames)).elements())})
        check("全部原生表格格值", structured_equal(observed["tables"], expected_tables), number,
              {"expected": expected_tables, "actual": observed["tables"]} if observed["tables"] != expected_tables else None)
        check("全部备注原段落与批准追加", observed["notes_paragraphs"] == expected_notes, number,
              {"expected": expected_notes, "actual": observed["notes_paragraphs"]} if observed["notes_paragraphs"] != expected_notes else None)
        check("无原生图片或整页位图引用", observed["picture_count"] == observed["image_reference_count"] == 0, number)
        check("原生图表数量", len(source["charts"]) == len(observed["charts"]), number)
        for base_chart, actual_chart in zip(source["charts"], observed["charts"]):
            changed = [step for step in page_steps if step["name"] == "图表数据"]
            expected_series = deepcopy(base_chart["series"])
            expected_book = deepcopy(base_chart["workbook"]["nonempty_sheets"])
            for step in changed:
                expected_series[0]["values"][0] = numeric(step["after"])
                expected_book[step["sheet"]][step["cell"]]["value"] = numeric(step["after"])
            check("图表类型类别系列及全部缓存数值", actual_chart["type"] == base_chart["type"] and actual_chart["series"] == expected_series, number,
                  {"type": actual_chart["type"], "expected_series": expected_series, "actual_series": actual_chart["series"]})
            check("内嵌工作簿全部非空格值", actual_chart["workbook"]["nonempty_sheets"] == expected_book, number,
                  {"expected": expected_book, "actual": actual_chart["workbook"]["nonempty_sheets"]})
            check("图表缓存与内嵌工作簿对应", all(item["matches"] for item in actual_chart["cache_workbook_checks"]), number,
                  actual_chart["cache_workbook_checks"])
    for step in steps:
        page = actual["pages"][step["page"] - 1]
        if step["name"] == "文字":
            values = {"before_count": page["text_frames"].count(step["before"]), "after_count": page["text_frames"].count(step["after"])}
            saved = values["after_count"] == 1 and values["before_count"] == 0
            original = values["before_count"] == 1 and values["after_count"] == 0
        elif step["name"] == "表格":
            value = page["tables"][0][step["row"] - 1][step["column"] - 1]
            values, saved, original = {"cell": value}, value == step["after"], value == step["before"]
        elif step["name"] == "演讲备注":
            value = "\n".join(page["notes_paragraphs"])
            values, saved, original = {"paragraphs": page["notes_paragraphs"]}, value == step["after"], value == step["before"]
        else:
            native = page["charts"][0]
            cached = native["series"][0]["values"][0]
            embedded = native["workbook"]["sheets"][step["sheet"]][step["cell"]]
            values = {"cache": cached, "embedded_cell": embedded, "chart_part": native["part"], "workbook_part": native["workbook_part"]}
            saved = cached == embedded["value"] == numeric(step["after"])
            original = cached == embedded["value"] == numeric(step["before"])
        step["actual_saved_values"] = values
        step["stored_result"] = "after" if saved else "before" if original else "mixed_or_unexpected"
        step["status"] = ("saved_as_planned" if saved else "recorded_edit_not_saved_in_pptx" if original else "unexpected_saved_content") if step["recorded_edit"] else ("not_executed_original_retained" if original else "unrecorded_change")
    stable = sha(copy_path.read_bytes()) == sha(copy_bytes)
    check("副本核对期间字节稳定", stable)
    final_material_checks = {"original_pptx": sha(original_path.read_bytes()) == plan["original"]["sha256"],
                             "independent_input": sha(input_path.read_bytes()) == plan["independent_input"]["sha256"],
                             "original_snapshot": sha(snapshot_path.read_bytes()) == SNAPSHOT_SHA,
                             "pre_operation_plan": (HERE / "操作前独立预期.json").read_bytes() == plan_bytes,
                             "actual_operation_record": (HERE / "实际操作记录.json").read_bytes() == record_bytes}
    check("原始材料及记录保持", all(final_material_checks.values()), detail=final_material_checks)
    recorded = [step for step in steps if step["recorded_edit"]]
    report = {"checked_at_utc": datetime.now(timezone.utc).isoformat(),
              "reader": {"script": str(Path(__file__).resolve()), "script_sha256": sha(Path(__file__).read_bytes()),
                         "argv": sys.orig_argv, "python_version": sys.version, "stdlib_only": True, "ui_operations": 0, "pptx_writes": 0},
              "scope": "WPS已保存合成副本的独立离线语义回读，不恢复UI、不改任何PPTX；不是PowerPoint或真实业务验收",
              "copy": {"path": str(copy_path), "bytes": len(copy_bytes), "sha256": sha(copy_bytes), "stable_during_check": stable},
              "inputs": {"pre_operation_plan_sha256": sha(plan_bytes), "actual_operation_record_sha256": sha(record_bytes),
                         "original_pptx_sha256": sha(original_bytes), "independent_input_sha256": sha(input_bytes), "snapshot_sha256": SNAPSHOT_SHA},
              "original_material_hash_checks": final_material_checks, "planned_steps": len(steps), "recorded_edit_steps": len(recorded),
              "recorded_edits_saved_as_planned": sum(step["status"] == "saved_as_planned" for step in recorded),
              "not_executed_steps": [step["number"] for step in steps if not step["recorded_edit"]], "steps": steps,
              "checks": checks, "failures": failures, "technical_status": "partial_with_failures" if failures else "partial_recorded_scope_preserved",
              "baseline_native": baseline, "actual_native": actual, "business_executed": 0, "business_passed": 0,
              "powerpoint_verified": False, "font_mode": "estimate", "visual_style_or_pixel_equivalence_verified": False,
              "limitations": ["只比较原生内容、关系、图表缓存与内嵌XLSX；不比较WPS正规化的shape名称、part名称、字体runs或格式字节",
                              "非空文本框按完整段落文本multiset核对；表格/备注内容不进行裁剪或空白宽松化",
                              "两项无实际编辑记录的计划仍是未执行；已记录的工作簿编辑是否进入主PPTX仅依保存字节判定",
                              "没有恢复ComputerUse、点击保存或关闭WPS；WPS仍可能打开副本，文件稳定检查只覆盖本次读取期间"]}
    OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(OUTPUT), "technical_status": report["technical_status"],
                      "planned_steps": len(steps), "recorded_edits": len(recorded), "saved_recorded_edits": report["recorded_edits_saved_as_planned"],
                      "not_executed_steps": report["not_executed_steps"], "failed_checks": len(failures),
                      "step_results": [{"page": step["page"], "name": step["name"], "status": step["status"], "actual": step["actual_saved_values"]} for step in steps]}, ensure_ascii=False))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except Exception as error:
        failure = {"checked_at_utc": datetime.now(timezone.utc).isoformat(), "technical_status": "reader_or_input_failure",
                   "scope": "只读脚本未完成核对，不能据此判断全部内容保留", "error_type": type(error).__name__,
                   "error": str(error), "context": RUN_CONTEXT, "business_passed": 0, "pptx_modified": False}
        if not OUTPUT.exists():
            OUTPUT.write_text(json.dumps(failure, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(failure, ensure_ascii=False))
        raise SystemExit(1)
