"""字体准备工具仅用合成字体与隔离 HTTP/文件故障，不取得真实字体。"""
from dataclasses import replace
from io import BytesIO
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
import httpx
import pytest

from app.core.config import Settings
from app.worker import fonts
from scripts import fetch_fonts as fp


def fixture_font(family):
    builder = FontBuilder(1000, isTTF=True)
    names = [".notdef", "space", "A", "quarter"]
    builder.setupGlyphOrder(names)
    builder.setupCharacterMap({32: "space", 65: "A", ord("季"): "quarter"})
    builder.setupGlyf({name: TTGlyphPen(None).glyph() for name in names})
    builder.setupHorizontalMetrics({name: (500, 0) for name in names})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({"familyName": family, "styleName": "Regular", "uniqueFontIdentifier": "Offline fixture", "fullName": family + " Regular", "psName": "Offline-Fixture", "version": "Version 1.0; Synthetic offline fixture"})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost()
    result = BytesIO()
    builder.save(result)
    return result.getvalue()


@pytest.fixture
def resources(monkeypatch):
    payloads, sources = {}, []
    for original in fp.SOURCES:
        data = fixture_font(original.family) if original.kind == "font" else b"SIL OPEN FONT LICENSE Version 1.1\nPERMISSION & CONDITIONS\nSynthetic offline fixture license text"
        source = replace(original, size=len(data), git_blob_sha1=fp.git_blob_sha1(data))
        sources.append(source)
        payloads[source.url] = data
    monkeypatch.setattr(fp, "SOURCES", tuple(sources))
    return payloads


def client_factory(payloads, handler=None):
    calls = []
    def make(**kwargs):
        assert kwargs["follow_redirects"] is False and kwargs["trust_env"] is False
        def request(req):
            calls.append(str(req.url))
            assert req.headers["accept-encoding"] == "identity"
            return handler(req) if handler else httpx.Response(200, content=payloads[str(req.url)])
        return httpx.Client(transport=httpx.MockTransport(request), **kwargs)
    return make, calls


def bytes_in(root):
    return {path.relative_to(root).as_posix(): path.read_bytes() for path in root.rglob("*") if path.is_file()}


def test_default_cli_plan_is_readonly_offline_and_honors_dotenv_and_explicit_targets(tmp_path):
    script = Path(fp.__file__).resolve()
    dotenv = tmp_path / ".env"
    dotenv.write_text('AIPPT_FONTS_DIR="configured-fonts"\nAIPPT_FONT_MANIFEST_PATH="configured-fonts/custom.json"\n', encoding="utf-8")
    # 子进程通道也禁网络；若默认 plan 意外联网，立即失败。
    (tmp_path / "sitecustomize.py").write_text("import socket\ndef denied(*args, **kwargs):\n    raise AssertionError('network forbidden in plan fixture')\nsocket.socket.connect = denied\nsocket.create_connection = denied\n", encoding="utf-8")
    env = {key: value for key, value in os.environ.items() if not key.startswith("AIPPT_")}
    env.update(PYTHONPATH=str(tmp_path), PYTHONDONTWRITEBYTECODE="1", PYTHONIOENCODING="utf-8")
    before = bytes_in(tmp_path)
    run = subprocess.run([sys.executable, str(script)], cwd=tmp_path, env=env, capture_output=True, text=True, encoding="utf-8", timeout=20)
    assert run.returncode == 0, run.stderr
    output = json.loads(run.stdout)
    assert output["mode"] == "plan" and not output["network"] and not output["writes"]
    assert output["matches_current_worker_configuration"] and output["apply_supported"]
    assert Path(output["fonts_dir"]) == tmp_path / "configured-fonts"
    assert Path(output["manifest_path"]) == tmp_path / "configured-fonts/custom.json"
    assert len(output["sources"]) == 4 and "font_manifest_hash" not in output
    assert sum(item["expected_size"] for item in output["sources"]) == 8_909_222
    assert all("/main/" not in item["url"] and len(item["commit"]) == 40 for item in output["sources"])
    explicit = subprocess.run([sys.executable, str(script), "--fonts-dir", "explicit", "--manifest-path", "explicit/selected.json"], cwd=tmp_path, env=env, capture_output=True, text=True, encoding="utf-8", timeout=20)
    assert explicit.returncode == 0, explicit.stderr
    selected = json.loads(explicit.stdout)
    assert Path(selected["fonts_dir"]) == tmp_path / "explicit"
    assert Path(selected["manifest_path"]) == tmp_path / "explicit/selected.json"
    assert not selected["matches_current_worker_configuration"]
    assert bytes_in(tmp_path) == before and not (tmp_path / "configured-fonts").exists() and not (tmp_path / "explicit").exists()


def test_explicit_empty_environment_font_directory_matches_worker_fallback(tmp_path):
    script = Path(fp.__file__).resolve()
    env = {key: value for key, value in os.environ.items() if not key.startswith("AIPPT_")}
    env.update(AIPPT_FONTS_DIR="", PYTHONPATH=str(script.parents[1]), PYTHONDONTWRITEBYTECODE="1", PYTHONIOENCODING="utf-8")
    before = bytes_in(tmp_path)
    run = subprocess.run([sys.executable, str(script)], cwd=tmp_path, env=env, capture_output=True, text=True, encoding="utf-8", timeout=20)
    assert run.returncode == 0, run.stderr
    output = json.loads(run.stdout)
    consumer = subprocess.run([sys.executable, "-c", "from pathlib import Path; from app.core.config import get_settings; from app.domain import text_metrics; cfg=get_settings(); root=Path(cfg.fonts_dir) if cfg.fonts_dir else text_metrics.FONTS_DIR; print(root.resolve())"], cwd=tmp_path, env=env, capture_output=True, text=True, encoding="utf-8", timeout=20)
    assert consumer.returncode == 0, consumer.stderr
    assert Path(output["fonts_dir"]) == Path(consumer.stdout.strip()) == tmp_path
    assert output["matches_current_worker_configuration"] and Path(output["manifest_path"]) == tmp_path / "manifest.json"
    assert bytes_in(tmp_path) == before


def test_manifest_generated_from_actual_fixture_bytes_matches_worker_identity(resources, tmp_path, monkeypatch):
    root = tmp_path / "fonts"
    make, calls = client_factory(resources)
    result = fp.prepare(root, root / "identity.json", 5, make)
    assert result["mode"] == "prepared" and calls == [s.url for s in fp.SOURCES]
    manifest = json.loads((root / "identity.json").read_text())
    assert set(manifest) == {"version", "files"}
    assert all(set(item) == {"role", "path", "size", "sha256"} for item in manifest["files"])
    assert result["font_manifest_hash"] == hashlib.sha256(fonts.canonical_manifest(manifest)).hexdigest()
    metadata = json.loads((root / fp.METADATA_NAME).read_text())
    assert metadata["font_manifest_sha256"] == result["font_manifest_hash"]
    for source, item in zip(fp.SOURCES, metadata["resources"], strict=True):
        data = resources[source.url]
        assert item["sha256"] == hashlib.sha256(data).hexdigest()
        assert item["git_blob_sha1"] == hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
        assert item["commit"] == source.commit and item["source_path"] == source.source_path
        if source.kind == "font":
            assert "Synthetic offline fixture" in item["font_version"]
    cfg = Settings(_env_file=None, fonts_dir=str(root), font_manifest_path=str(root / "identity.json"))
    monkeypatch.setattr(fonts, "get_settings", lambda: cfg)
    assert fonts.font_readiness()["font_manifest_hash"] == result["font_manifest_hash"]
    before = bytes_in(tmp_path)
    def forbidden(**kwargs):
        raise AssertionError("existing valid resources must not download")
    repeated = fp.prepare(root, root / "identity.json", 5, forbidden)
    assert repeated["mode"] == "reused" and repeated["font_manifest_hash"] == result["font_manifest_hash"]
    assert bytes_in(tmp_path) == before


@pytest.mark.parametrize("failure", ["second_resource", "license", "short", "oversized", "length_header", "blob", "redirect", "timeout", "compressed"])
def test_http_failure_never_publishes_or_changes_existing_empty_shell(resources, tmp_path, failure):
    root = tmp_path / "fonts"
    root.mkdir()
    (root / ".gitkeep").write_bytes(b"")
    before = bytes_in(tmp_path)
    first = fp.SOURCES[0]
    def handler(request):
        source = next(s for s in fp.SOURCES if s.url == str(request.url))
        data = resources[source.url]
        if failure == "second_resource" and source == fp.SOURCES[1]:
            return httpx.Response(503)
        if failure == "license" and source.kind == "license":
            return httpx.Response(403)
        if source == first:
            if failure == "short": data = data[:-1]
            if failure == "oversized": return httpx.Response(200, headers={"content-length": str(source.size)}, stream=httpx.ByteStream(data + b"!"))
            if failure == "length_header": return httpx.Response(200, headers={"content-length": "bad"}, content=data)
            if failure == "blob": data = b"!" + data[1:]
            if failure == "redirect": return httpx.Response(302, headers={"location": "https://unapproved.example/font"})
            if failure == "timeout": raise httpx.ReadTimeout("fixture private transport detail", request=request)
            if failure == "compressed": return httpx.Response(200, headers={"content-encoding": "gzip", "content-length": str(source.size)}, stream=httpx.ByteStream(data))
        return httpx.Response(200, content=data)
    make, calls = client_factory(resources, handler)
    with pytest.raises(fp.PreparationError):
        fp.prepare(root, root / "manifest.json", 5, make)
    assert bytes_in(tmp_path) == before and list(tmp_path.iterdir()) == [root]
    assert len(calls) <= 2 and not (root / "manifest.json").exists()


@pytest.mark.parametrize("kind", ["font", "license"])
def test_parse_failure_even_with_matching_source_blob_is_rejected(resources, tmp_path, monkeypatch, kind):
    source = next(s for s in fp.SOURCES if s.kind == kind)
    broken = b"invalid font or license bytes"
    replacement = replace(source, size=len(broken), git_blob_sha1=fp.git_blob_sha1(broken))
    monkeypatch.setattr(fp, "SOURCES", tuple(replacement if s == source else s for s in fp.SOURCES))
    resources[source.url] = broken
    root = tmp_path / "fonts"
    make, _ = client_factory(resources)
    with pytest.raises(fp.PreparationError):
        fp.prepare(root, root / "manifest.json", 5, make)
    assert not root.exists() and not list(tmp_path.iterdir())


def test_overall_download_timeout_and_resource_budget(resources, tmp_path, monkeypatch):
    make, _ = client_factory(resources)
    clock = iter([0, 6])
    monkeypatch.setattr(fp.time, "monotonic", lambda: next(clock))
    with make(follow_redirects=False, trust_env=False, timeout=5) as client:
        with pytest.raises(fp.PreparationError, match="总时限"):
            fp.download(fp.SOURCES[0], tmp_path / "font", 5, client)
        oversized = replace(fp.SOURCES[0], size=fp.MAX_FONT_BYTES + 1)
        monkeypatch.setattr(fp.time, "monotonic", lambda: 0)
        with pytest.raises(fp.PreparationError, match="预算"):
            fp.download(oversized, tmp_path / "font", 5, client)
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize("late_boundary", ["eof", "close", "parse"])
def test_completed_body_cannot_publish_after_final_deadline(resources, tmp_path, monkeypatch, late_boundary):
    source = fp.SOURCES[0]
    clock = {"now": 0}
    monkeypatch.setattr(fp.time, "monotonic", lambda: clock["now"])
    class Stream(httpx.SyncByteStream):
        def __iter__(self):
            yield resources[source.url]
            if late_boundary == "eof": clock["now"] = 6
        def close(self):
            if late_boundary == "close": clock["now"] = 6
    validate = fp._validate_bytes
    def delayed(source, data):
        result = validate(source, data)
        if late_boundary == "parse": clock["now"] = 6
        return result
    monkeypatch.setattr(fp, "_validate_bytes", delayed)
    def forbidden(path, data):
        raise AssertionError("expired resource must not be written")
    monkeypatch.setattr(fp, "_write", forbidden)
    with httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, stream=Stream()))) as client:
        with pytest.raises(fp.PreparationError, match="总时限"):
            fp.download(source, tmp_path / "font", 5, client)
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize("corruption", ["partial", "font", "manifest", "metadata", "other_file", "different_baseline"])
def test_existing_nonempty_directory_is_never_overwritten(resources, tmp_path, monkeypatch, corruption):
    root = tmp_path / "fonts"
    make, _ = client_factory(resources)
    fp.prepare(root, root / "manifest.json", 5, make)
    if corruption == "partial": (root / fp.SOURCES[1].filename).unlink()
    if corruption == "font": (root / fp.SOURCES[0].filename).write_bytes(b"broken")
    if corruption == "manifest": (root / "manifest.json").write_text('{}')
    if corruption == "metadata": (root / fp.METADATA_NAME).write_text('{}')
    if corruption == "other_file": (root / "owned-by-user.txt").write_text("keep me")
    if corruption == "different_baseline": monkeypatch.setattr(fp, "SOURCES", tuple(replace(s, git_blob_sha1="0" * 40) for s in fp.SOURCES))
    before = bytes_in(tmp_path)
    def forbidden(**kwargs): raise AssertionError("must reject existing resources before HTTP")
    with pytest.raises((fp.PreparationError, ValueError)):
        fp.prepare(root, root / "manifest.json", 5, forbidden)
    assert bytes_in(tmp_path) == before and list(tmp_path.iterdir()) == [root]


def test_atomic_publish_failure_restores_initial_empty_directory(resources, tmp_path, monkeypatch):
    root = tmp_path / "fonts"
    root.mkdir()
    (root / ".gitkeep").write_bytes(b"")
    before = bytes_in(tmp_path)
    rename = Path.rename
    def failed(self, target):
        if self.name == "resources": raise OSError("fixture rename failure")
        return rename(self, target)
    monkeypatch.setattr(Path, "rename", failed)
    make, _ = client_factory(resources)
    with pytest.raises(fp.PreparationError, match="发布失败"):
        fp.prepare(root, root / "manifest.json", 5, make)
    assert bytes_in(tmp_path) == before and list(tmp_path.iterdir()) == [root]


def test_successful_empty_shell_publish_preserves_backup(resources, tmp_path):
    root = tmp_path / "fonts"
    root.mkdir()
    (root / ".gitkeep").write_bytes(b"")
    make, _ = client_factory(resources)
    result = fp.prepare(root, root / "manifest.json", 5, make)
    backup = Path(result["preserved_empty_directory"])
    assert backup.parent == tmp_path and (backup / ".gitkeep").read_bytes() == b""
    assert (root / "manifest.json").is_file() and not (root / ".gitkeep").exists()
    assert not list(tmp_path.glob("*.prepare.lock")) and not list(tmp_path.glob("*.stage-*"))


def test_lock_conflict_and_external_manifest_fail_without_network_or_target_mutation(tmp_path):
    root = tmp_path / "fonts"
    lock = tmp_path / ".fonts.prepare.lock"
    lock.write_bytes(b"other-execution")
    def forbidden(**kwargs): raise AssertionError("no HTTP under lock conflict")
    with pytest.raises(fp.PreparationError, match="锁"):
        fp.prepare(root, root / "manifest.json", 5, forbidden)
    assert lock.read_bytes() == b"other-execution" and not root.exists()
    before = bytes_in(tmp_path)
    with pytest.raises(fp.PreparationError, match="原子发布"):
        fp.prepare(root, tmp_path / "external.json", 5, forbidden)
    assert bytes_in(tmp_path) == before


def test_file_write_failure_and_target_change_preserve_user_bytes(resources, tmp_path, monkeypatch):
    root = tmp_path / "fonts"
    make, _ = client_factory(resources)
    write = fp._write
    def failed(path, data):
        if path.name == fp.METADATA_NAME: raise OSError("fixture disk full")
        write(path, data)
    monkeypatch.setattr(fp, "_write", failed)
    with pytest.raises(OSError): fp.prepare(root, root / "manifest.json", 5, make)
    assert not list(tmp_path.iterdir())
    monkeypatch.setattr(fp, "_write", write)
    validate = fp.validate_directory
    def changed(directory, manifest_name):
        result = validate(directory, manifest_name)
        if directory != root:
            root.mkdir()
            (root / "concurrent-user.txt").write_bytes(b"preserved")
        return result
    monkeypatch.setattr(fp, "validate_directory", changed)
    with pytest.raises(fp.PreparationError, match="变化"):
        fp.prepare(root, root / "manifest.json", 5, make)
    assert bytes_in(tmp_path) == {"fonts/concurrent-user.txt": b"preserved"} and list(tmp_path.iterdir()) == [root]


@pytest.mark.parametrize("timeout", ["0", "121", "nan", "inf"])
def test_cli_rejects_invalid_timeout_without_writing(timeout, tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(fp, "get_settings", lambda: Settings(_env_file=None))
    assert fp.main(["--timeout", timeout, "--fonts-dir", str(tmp_path / "fonts")]) == 1
    assert "timeout" in capsys.readouterr().err and not list(tmp_path.iterdir())
