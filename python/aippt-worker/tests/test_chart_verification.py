"""真实原生图表构造后的缓存与内嵌数据独立完整性门禁。"""
from io import BytesIO
import posixpath
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

import pytest

from app.domain.content import ChartBlock, ChartSeries, Deck, Slide, TextBlock
from app.domain.flex_layout import FlexContainer, FlexLeaf
from app.domain.text_metrics import unbind_font_faces
from app.render.pptx import render_deck_to_pptx
from app.render.verify import key_texts_from_block, verify_pptx
from app.worker.catalog import initialize_catalog
from app.worker.fonts import bind_task_fonts

NS = {"c": "http://schemas.openxmlformats.org/drawingml/2006/chart",
      "x": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
      "p": "http://schemas.openxmlformats.org/presentationml/2006/main"}


@pytest.fixture(autouse=True)
def estimate_fonts():
    initialize_catalog()
    _, token = bind_task_fonts({"font_manifest_hash": "unavailable"})
    yield
    unbind_font_faces(token)


def deck_for(kind="column", *, blank=False, precise=False):
    categories = ["", "乙", "丙"] if blank else ["甲", "乙", "丙"]
    values = [1.2345678901234567, 1e-12, 1e20] if precise else [0, -0.25, 2.5]
    chart = ChartBlock(id="chart", slot_id="chart", chart_type=kind, categories=categories,
                       series=[ChartSeries(name="" if blank else "收入", values=values),
                               ChartSeries(name="隐藏支出", values=[3, 4, 5]),
                               ChartSeries(name="隐藏增长", values=[6, 7, 8])])
    return Deck(id="deck", title="合成图表", theme_id="ivory", slides=[Slide(id="slide", layout_id="chart",
                speaker_notes="完整备注", blocks=[TextBlock(id="title", slot_id="title", text="合成图表"), chart])])


def rebuild(data, replacements=None, removed=()):
    output = BytesIO()
    with ZipFile(BytesIO(data)) as source, ZipFile(output, "w", ZIP_DEFLATED) as target:
        for info in source.infolist():
            if info.filename not in removed:
                target.writestr(info.filename, (replacements or {}).get(info.filename, source.read(info.filename)))
    return output.getvalue()


def chart_part(data):
    with ZipFile(BytesIO(data)) as package:
        return next(n for n in package.namelist() if n.startswith("ppt/charts/chart") and n.endswith(".xml"))


def change_chart(data, action):
    path = chart_part(data)
    with ZipFile(BytesIO(data)) as package:
        node = ET.fromstring(package.read(path))
    action(node)
    return rebuild(data, {path: ET.tostring(node, encoding="utf-8", xml_declaration=True)})


def workbook_part(data):
    part = chart_part(data)
    with ZipFile(BytesIO(data)) as package:
        root = ET.fromstring(package.read(part))
        rid = root.find("c:externalData", NS).get("{" + NS["r"] + "}id")
        folder, name = posixpath.split(part)
        rels = ET.fromstring(package.read(folder + "/_rels/" + name + ".rels"))
        relation = next(r for r in rels if r.get("Id") == rid)
        return posixpath.normpath(posixpath.join(folder, relation.get("Target")))


def change_workbook(data, action):
    part = workbook_part(data)
    with ZipFile(BytesIO(data)) as package:
        original = package.read(part)
    with ZipFile(BytesIO(original)) as package:
        path = "xl/worksheets/sheet1.xml"
        root = ET.fromstring(package.read(path))
    action(root)
    return rebuild(data, {part: rebuild(original, {path: ET.tostring(root, encoding="utf-8", xml_declaration=True)})})


def set_cell(root, address, value):
    cell = root.find(f".//x:c[@r='{address}']", NS)
    cell.attrib.pop("t", None)
    for child in list(cell):
        cell.remove(child)
    ET.SubElement(cell, "{" + NS["x"] + "}v").text = value


def assert_native_failure(data, deck):
    report = verify_pptx(data, deck)
    assert not report.passed
    assert any(issue.check == "native_chart" and issue.slide_index == 1 for issue in report.issues), report


@pytest.mark.parametrize("kind", ["bar", "column", "line", "pie"])
def test_actual_three_series_chart_passes(kind):
    deck = deck_for(kind)
    report = verify_pptx(render_deck_to_pptx(deck).getvalue(), deck)
    assert report.passed, report
    # 公共内容提取仍保留隐藏系列，验证器不能篡改正式模型。
    assert key_texts_from_block(deck.slides[0].blocks[1])[-2:] == ["隐藏支出", "隐藏增长"]


@pytest.mark.parametrize("kind", ["bar", "column", "line", "pie"])
@pytest.mark.parametrize("mode", ["blank", "precise"])
def test_original_writer_blank_and_finite_numeric_semantics(kind, mode):
    deck = deck_for(kind, blank=mode == "blank", precise=mode == "precise")
    report = verify_pptx(render_deck_to_pptx(deck).getvalue(), deck)
    assert report.passed, report


@pytest.mark.parametrize("kind", ["bar", "column", "line", "pie"])
def test_duplicate_names_and_office_string_escapes_keep_positional_identity(kind):
    deck = deck_for(kind)
    chart = deck.slides[0].blocks[1]
    chart.categories = ["name", "name", "_x0041_"]
    for series, name in zip(chart.series, ["name", "name", "_x0041_"]):
        series.name = name
    report = verify_pptx(render_deck_to_pptx(deck).getvalue(), deck)
    assert report.passed, report


@pytest.mark.parametrize("kind", ["bar", "column", "line", "pie"])
@pytest.mark.parametrize("field", ["name", "category", "value", "type"])
def test_rejects_visible_cache_tampering_even_if_other_text_echoes_it(kind, field):
    deck = deck_for(kind)
    # 回读必须直接核原图表，其他文本出现名字不能遮蔽缓存改写。
    deck.slides[0].blocks[0].text = "甲 乙 丙 收入 隐藏支出 隐藏增长"
    data = render_deck_to_pptx(deck).getvalue()
    def alter(root):
        if field == "type":
            plot = root.find("c:chart/c:plotArea", NS)
            node = next(n for n in plot if n.tag.endswith("Chart"))
            if kind in {"bar", "column"}:
                node.find("c:barDir", NS).set("val", "col" if kind == "bar" else "bar")
            else:
                node.tag = "{" + NS["c"] + "}areaChart"
        else:
            path = {"name": ".//c:ser/c:tx/c:strRef/c:strCache/c:pt/c:v",
                    "category": ".//c:ser/c:cat/c:strRef/c:strCache/c:pt/c:v",
                    "value": ".//c:ser/c:val/c:numRef/c:numCache/c:pt/c:v"}[field]
            root.find(path, NS).text = "999" if field == "value" else "被篡改"
    assert_native_failure(change_chart(data, alter), deck)


@pytest.mark.parametrize("address,value", [("A2", "99"), ("B1", "99"), ("B2", "999"), ("C1", "99"), ("C2", "999"), ("D2", "999")])
def test_rejects_workbook_visible_or_hidden_series_tampering(address, value):
    deck = deck_for("pie")
    data = render_deck_to_pptx(deck).getvalue()
    assert_native_failure(change_workbook(data, lambda root: set_cell(root, address, value)), deck)


@pytest.mark.parametrize("fault", ["missing", "external", "bad_zip", "missing_cell", "duplicate_cell", "wrong_range"])
def test_rejects_missing_broken_or_misbound_workbook(fault):
    deck = deck_for("column")
    data = render_deck_to_pptx(deck).getvalue()
    if fault == "missing":
        damaged = change_chart(data, lambda root: root.remove(root.find("c:externalData", NS)))
    elif fault == "bad_zip":
        damaged = rebuild(data, {workbook_part(data): b"not-a-workbook"})
    elif fault == "external":
        part = chart_part(data)
        folder, name = posixpath.split(part)
        path = folder + "/_rels/" + name + ".rels"
        with ZipFile(BytesIO(data)) as package:
            root = ET.fromstring(package.read(path))
        relation = next(iter(root))
        relation.set("TargetMode", "External")
        relation.set("Target", "https://example.invalid/no-network.xlsx")
        damaged = rebuild(data, {path: ET.tostring(root, encoding="utf-8", xml_declaration=True)})
    elif fault == "wrong_range":
        damaged = change_chart(data, lambda root: setattr(root.find(".//c:ser/c:val/c:numRef/c:f", NS), "text", "Sheet1!$C$2:$C$4"))
    else:
        def alter(root):
            row = root.find(".//x:row[@r='2']", NS)
            cell = row.find("x:c[@r='B2']", NS)
            row.remove(cell) if fault == "missing_cell" else row.append(ET.fromstring(ET.tostring(cell)))
        damaged = change_workbook(data, alter)
    assert_native_failure(damaged, deck)


@pytest.mark.parametrize("field", ["index", "count", "omit_point"])
def test_rejects_cache_index_or_count_corruption(field):
    deck = deck_for("column")
    data = render_deck_to_pptx(deck).getvalue()
    def alter(root):
        cache = root.find(".//c:ser/c:val/c:numRef/c:numCache", NS)
        if field == "index":
            cache.findall("c:pt", NS)[1].set("idx", "0")
        elif field == "count":
            cache.find("c:ptCount", NS).set("val", "99")
        else:
            cache.remove(cache.find("c:pt", NS))
    assert_native_failure(change_chart(data, alter), deck)


@pytest.mark.parametrize("fault", ["members", "expansion", "xml_entity", "worksheet_external"])
def test_rejects_workbook_read_budget_or_unsafe_xml(fault):
    deck = deck_for("column")
    data = render_deck_to_pptx(deck).getvalue()
    part = workbook_part(data)
    with ZipFile(BytesIO(data)) as package:
        original = package.read(part)
    if fault in {"members", "expansion"}:
        output = BytesIO()
        with ZipFile(BytesIO(original)) as source, ZipFile(output, "w", ZIP_DEFLATED) as target:
            for info in source.infolist():
                target.writestr(info.filename, source.read(info.filename))
            if fault == "members":
                for index in range(65):
                    target.writestr(f"extra/{index}", "")
            else:
                target.writestr("extra/expanded", b"x" * (3 * 1024 * 1024))
        damaged = rebuild(data, {part: output.getvalue()})
    elif fault == "xml_entity":
        with ZipFile(BytesIO(original)) as source:
            sheet = source.read("xl/worksheets/sheet1.xml")
        sheet = sheet.replace(b"?>", b"?><!DOCTYPE worksheet [<!ENTITY blocked 'blocked'>]>", 1)
        damaged = rebuild(data, {part: rebuild(original, {"xl/worksheets/sheet1.xml": sheet})})
    else:
        with ZipFile(BytesIO(original)) as source:
            path = "xl/_rels/workbook.xml.rels"
            rels = ET.fromstring(source.read(path))
        next(r for r in rels if r.get("Type").endswith("/worksheet")).set("TargetMode", "External")
        damaged = rebuild(data, {part: rebuild(original, {path: ET.tostring(rels, encoding="utf-8")})})
    assert_native_failure(damaged, deck)


def two_chart_deck(*, same=False):
    deck = deck_for("column")
    first = deck.slides[0].blocks[1]
    second = first.model_copy(deep=True, update={"id": "second", "slot_id": "second"})
    if not same:
        second.series[0].values = [5, 6, 7]
    slide = deck.slides[0]
    slide.blocks = [first, second]
    slide.layout_mode = "flex"
    slide.layout_tree = FlexContainer(type="row", id="row", children=[FlexLeaf(id="a", block_id="chart"), FlexLeaf(id="b", block_id="second")])
    return deck


@pytest.mark.parametrize("same", [False, True])
def test_multiple_native_charts_are_consumed_once_in_original_order(same):
    deck = two_chart_deck(same=same)
    data = render_deck_to_pptx(deck).getvalue()
    assert verify_pptx(data, deck).passed
    with ZipFile(BytesIO(data)) as package:
        path = "ppt/slides/slide1.xml"
        root = ET.fromstring(package.read(path))
    charts = root.findall(".//c:chart", NS)
    charts[1].set("{" + NS["r"] + "}id", charts[0].get("{" + NS["r"] + "}id"))
    assert_native_failure(rebuild(data, {path: ET.tostring(root, encoding="utf-8", xml_declaration=True)}), deck)


def test_legal_maximum_dimensions_and_long_categories_are_not_fixed_size_rejected():
    deck = deck_for("column")
    chart = deck.slides[0].blocks[1]
    chart.categories = [str(i) + "category" * 300 for i in range(200)]
    chart.series = [ChartSeries(name=f"系列{i}", values=[i + c / 4 for c in range(200)]) for i in range(20)]
    data = render_deck_to_pptx(deck).getvalue()
    with ZipFile(BytesIO(data)) as package:
        assert package.getinfo(chart_part(data)).file_size > 8 * 1024 * 1024
    report = verify_pptx(data, deck)
    assert report.passed, report
