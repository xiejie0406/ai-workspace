"""原辅助CLI的目录身份、输出范围和真实入口；不重跑八组合算法基线。"""
import ast
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest
from app.core.paths import SHARED_DIR

WORKER = Path(__file__).resolve().parents[1]


def snapshot(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()}


@pytest.fixture
def auxiliary_workspace(tmp_path):
    worker = tmp_path / "worker"
    worker.mkdir()
    shutil.copytree(WORKER / "app", worker / "app", ignore=shutil.ignore_patterns("__pycache__"))
    (worker / "scripts").mkdir()
    for name in ("dump_ambient_fixture.py", "run_regression.py"):
        shutil.copyfile(WORKER / "scripts" / name, worker / "scripts" / name)
    catalog = tmp_path / "data" / "catalog"
    shutil.copytree(SHARED_DIR, catalog)
    shutil.copyfile(SHARED_DIR.parent / "catalog-manifest.json", catalog.parent / "catalog-manifest.json")
    (worker / ".env").write_text(f'AIPPT_CATALOG_DIR="{catalog.as_posix()}"\n', encoding="utf-8")
    guard = tmp_path / "guard"
    guard.mkdir()
    (guard / "sitecustomize.py").write_text("import socket\ndef denied(*a,**k):\n raise AssertionError('auxiliary network forbidden')\nsocket.socket.connect=denied\nsocket.socket.connect_ex=denied\nsocket.create_connection=denied\n", encoding="utf-8")
    env = {k: v for k, v in os.environ.items() if not k.startswith("AIPPT_") and k not in {"PYTHONPATH", "PYTHONHOME"}}
    env.update(PYTHONPATH=str(guard), PYTHONDONTWRITEBYTECODE="1", PYTHONIOENCODING="utf-8", AIPPT_IMAGE_ENABLED="false")
    return tmp_path, worker, catalog, env


def execute(workspace, name="dump_ambient_fixture.py", *args):
    _, worker, _, env = workspace
    return subprocess.run([sys.executable, "-B", str(worker / "scripts" / name), *map(str, args)], cwd=worker, env=env, capture_output=True, encoding="utf-8", timeout=20)


def test_dump_default_generates_original_24_cases_only_in_local_var(auxiliary_workspace):
    root, worker, catalog, _ = auxiliary_workspace
    before = snapshot(root)
    result = execute(auxiliary_workspace)
    output = worker / "var/ambient-fixtures/theme-cases.json"
    assert result.returncode == 0 and not result.stderr
    assert str(output) in result.stdout and "24 个用例" in result.stdout
    expected = json.loads((catalog / "ambient-fixtures/theme-cases.json").read_text(encoding="utf-8"))
    actual = json.loads(output.read_text(encoding="utf-8"))
    assert actual == expected and len(actual["cases"]) == 24
    after = snapshot(root)
    assert set(after) - set(before) == {"worker/var/ambient-fixtures/theme-cases.json"}
    assert all(after[name] == value for name, value in before.items())
    assert not list(root.rglob("*.pptx"))


def test_dump_explicit_unicode_output_keeps_fixed_catalog_and_source(auxiliary_workspace):
    root, _, catalog, _ = auxiliary_workspace
    output = root / "候选🙂" / "氛围🙂.json"
    before = snapshot(root)
    result = execute(auxiliary_workspace, "dump_ambient_fixture.py", "--output", output)
    assert result.returncode == 0 and not result.stderr and str(output) in result.stdout
    assert json.loads(output.read_text(encoding="utf-8")) == json.loads((catalog / "ambient-fixtures/theme-cases.json").read_text(encoding="utf-8"))
    after = snapshot(root)
    assert set(after) - set(before) == {"候选🙂/氛围🙂.json"}
    assert all(after[name] == value for name, value in before.items())


@pytest.mark.parametrize("destination", ["catalog", "manifest", "normalized_alias"])
def test_dump_refuses_fixed_catalog_and_manifest_before_writing(auxiliary_workspace, destination):
    root, _, catalog, _ = auxiliary_workspace
    target = {"catalog": catalog / "ambient-fixtures/theme-cases.json", "manifest": catalog.parent / "catalog-manifest.json", "normalized_alias": catalog / ".." / "catalog" / "new.json"}[destination]
    before = snapshot(root)
    result = execute(auxiliary_workspace, "dump_ambient_fixture.py", "--output", target)
    assert result.returncode == 2 and not result.stdout
    assert "不得覆盖" in result.stderr and "network forbidden" not in result.stderr
    assert snapshot(root) == before


@pytest.mark.parametrize("script", ["dump_ambient_fixture.py", "run_regression.py"])
@pytest.mark.parametrize("failure", ["missing_manifest", "tampered_manifest", "missing_directory"])
def test_both_original_cli_reject_unverified_catalog_without_outputs(auxiliary_workspace, script, failure):
    root, worker, catalog, _ = auxiliary_workspace
    manifest = catalog.parent / "catalog-manifest.json"
    if failure == "missing_manifest":
        manifest.unlink()
    elif failure == "tampered_manifest":
        data = json.loads(manifest.read_text(encoding="utf-8"))
        data["files"][0]["sha256"] = "0" * 64
        manifest.write_text(json.dumps(data) + "\n", encoding="utf-8")
    else:
        (worker / ".env").write_text('AIPPT_CATALOG_DIR="missing-catalog"\n', encoding="utf-8")
    before = snapshot(root)
    result = execute(auxiliary_workspace, script)
    assert result.returncode != 0 and not result.stdout
    expected = {"missing_manifest": "catalog-manifest.json", "tampered_manifest": "哈希", "missing_directory": "目录不完整"}[failure]
    assert expected in result.stderr and "network forbidden" not in result.stderr
    assert snapshot(root) == before and not list(root.rglob("*.pptx"))


def test_original_geometry_and_regression_dispatch_are_preserved():
    reference = WORKER.parents[1] / "参考项目/ai-ppt-generator-main/backend/scripts"
    def functions(path):
        return {node.name: node for node in ast.parse(path.read_text(encoding="utf-8")).body if isinstance(node, ast.FunctionDef)}
    old = functions(reference / "dump_ambient_fixture.py")
    new = functions(WORKER / "scripts/dump_ambient_fixture.py")
    assert ast.dump(old["_two_column_content"]) == ast.dump(new["_two_column_content"])
    def cases(function):
        return next(node for node in function.body if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == "cases" for target in node.targets))
    assert ast.dump(cases(old["main"])) == ast.dump(cases(new["main"]))
    old_run = functions(reference / "run_regression.py")["main"]
    new_run = functions(WORKER / "scripts/run_regression.py")["main"]
    assert [ast.dump(node) for node in new_run.body[1:]] == [ast.dump(node) for node in old_run.body]
