"""原指标工具源码/sdist入口的隔离CLI边界，沿用原统计算法。"""
import ast
from collections import Counter
from dataclasses import dataclass, field
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest
from app.core.paths import SHARED_DIR

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/compare_deck_metrics.py"


@dataclass
class CliWorkspace:
    root: Path
    catalog: Path
    env: dict = field(repr=False)

    def __getitem__(self, index):
        return (self.root, self.catalog, self.env)[index]

    def __iter__(self):
        return iter((self.root, self.catalog, self.env))


def explicit_deck():
    return {"id": "metrics-fixture", "title": "离线指标核对🙂", "theme_id": "ivory", "slides": [
        {"id": "fixed-slide", "layout_id": "bullets", "blocks": [
            {"id": "title", "slot_id": "title", "type": "text", "text": "固定页中文🙂"},
            {"id": "body", "slot_id": "body", "type": "bullets", "items": ["第一点", "Editable two"]},
        ]},
        {"id": "flex-slide", "layout_id": "bullets", "layout_mode": "flex", "layout_tree": {"id": "root", "type": "row", "preset": "outline_boxes", "ratios": [1, 1], "children": [
            {"id": "left", "type": "block", "block_id": "flex-text", "text_style": "body"},
            {"id": "right", "type": "block", "block_id": "image"},
        ]}, "blocks": [
            {"id": "flex-text", "slot_id": "flex-text", "type": "text", "text": "Flexible 中文"},
            {"id": "image", "slot_id": "image", "type": "image", "source": "placeholder", "url": "https://invalid.example/never-fetched.png", "alt": "只统计，不取得字节"},
        ]},
    ]}


def snapshot(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()}


@pytest.fixture
def cli_workspace(tmp_path):
    catalog = tmp_path / "catalog"
    shutil.copytree(SHARED_DIR, catalog)
    shutil.copyfile(SHARED_DIR.parent / "catalog-manifest.json", tmp_path / "catalog-manifest.json")
    (tmp_path / ".env").write_text(f'AIPPT_CATALOG_DIR="{catalog.as_posix()}"\n', encoding="utf-8")
    (tmp_path / "sitecustomize.py").write_text("import socket\ndef denied(*args, **kwargs):\n    raise AssertionError('metrics network forbidden')\nsocket.socket.connect=denied\nsocket.create_connection=denied\n", encoding="utf-8")
    env = {k: v for k, v in os.environ.items() if not k.startswith("AIPPT_") and k != "PYTHONPATH"}
    env.update(PYTHONPATH=str(tmp_path), PYTHONDONTWRITEBYTECODE="1", PYTHONIOENCODING="utf-8")
    return CliWorkspace(tmp_path, catalog, env)


def run_cli(workspace, *args):
    return subprocess.run([sys.executable, "-B", str(SCRIPT), *map(str, args)], cwd=workspace[0], env=workspace[2], capture_output=True, text=True, encoding="utf-8", timeout=20)


def parse_output(run, label):
    assert run.returncode == 0, run.stderr
    header, body = run.stdout.split("\n", 1)
    assert header == f"== {label} ==" and not run.stderr
    return json.loads(body)


def test_default_sample_cli_loads_verified_dotenv_catalog_and_does_not_write(cli_workspace):
    before = snapshot(cli_workspace[0])
    report = parse_output(run_cli(cli_workspace), "sample-deck")
    sample = json.loads((cli_workspace[1] / "sample-deck.json").read_text(encoding="utf-8"))
    blocks = [len(page["blocks"]) for page in sample["slides"]]
    types = dict(Counter(b["type"] for page in sample["slides"] for b in page["blocks"]))
    assert report["slides"] == len(sample["slides"]) == 11
    assert report["theme"] == sample["theme_id"] and report["blocks_per_slide"] == blocks
    assert report["block_types"] == types and report["avg_blocks"] == sum(blocks) / 11
    assert len(report["ambient_shapes_per_slide"]) == len(report["images_per_slide"]) == 11
    assert snapshot(cli_workspace[0]) == before


def test_explicit_deck_cli_keeps_original_metrics_and_unicode_label(cli_workspace):
    path = cli_workspace[0] / "指标输入🙂.json"
    path.write_text(json.dumps(explicit_deck(), ensure_ascii=False), encoding="utf-8")
    before = snapshot(cli_workspace[0])
    report = parse_output(run_cli(cli_workspace, path), path.name)
    assert report["slides"] == 2 and report["blocks_per_slide"] == [2, 2] and report["avg_blocks"] == 2
    assert report["block_types"] == {"text": 2, "bullets": 1, "image": 1}
    assert report["skins"] == {"outline_boxes": 1}
    assert report["images_per_slide"] == [0, 1] and report["slides_with_image"] == 1
    assert len(report["narrowest_column_pt"]) == len(report["narrowest_chars_per_line"]) == 2
    assert all(value > 0 for value in report["narrowest_chars_per_line"])
    assert report["worst_chars_per_line"] == min(report["narrowest_chars_per_line"])
    assert snapshot(cli_workspace[0]) == before


@pytest.mark.parametrize("failure", ["manifest_missing", "catalog_modified", "directory_missing", "invalid_deck", "invalid_json"])
def test_bad_catalog_or_deck_fails_in_actual_cli_without_fallback_or_writes(cli_workspace, failure):
    root, catalog, env = cli_workspace
    args = []
    if failure == "manifest_missing": (root / "catalog-manifest.json").unlink()
    if failure == "catalog_modified":
        target = catalog / "layouts/bullets.json"
        target.write_bytes(target.read_bytes() + b" ")
    if failure == "directory_missing":
        (root / ".env").write_text('AIPPT_CATALOG_DIR="missing-fixture-catalog"\n', encoding="utf-8")
    if failure in {"invalid_deck", "invalid_json"}:
        path = root / "invalid.json"
        path.write_text('{"slides":"invalid"}' if failure == "invalid_deck" else 'not-json', encoding="utf-8")
        args = [path]
    before = snapshot(root)
    result = run_cli(cli_workspace, *args)
    assert result.returncode != 0 and not result.stdout and "ModuleNotFoundError" not in result.stderr
    assert "network forbidden" not in result.stderr
    expected = {"manifest_missing": "catalog-manifest.json", "catalog_modified": "哈希", "directory_missing": "目录不完整", "invalid_deck": "ValidationError", "invalid_json": "JSONDecodeError"}[failure]
    assert expected in result.stderr
    assert snapshot(root) == before


def test_all_original_statistic_functions_have_identical_ast():
    reference = SCRIPT.parents[3] / "参考项目/ai-ppt-generator-main/backend/scripts/compare_deck_metrics.py"
    old = {node.name: ast.dump(node, include_attributes=False) for node in ast.parse(reference.read_text(encoding="utf-8")).body if isinstance(node, ast.FunctionDef)}
    current = {node.name: ast.dump(node, include_attributes=False) for node in ast.parse(SCRIPT.read_text(encoding="utf-8")).body if isinstance(node, ast.FunctionDef)}
    for name in ("_collect_presets", "_chars_per_line", "_text_columns", "summarize"):
        assert current[name] == old[name]
