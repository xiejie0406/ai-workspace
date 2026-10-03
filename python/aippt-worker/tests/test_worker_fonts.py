import asyncio
import copy
import hashlib
from io import BytesIO
import json
import pytest
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from app.core.config import Settings
from app.domain import text_metrics
from app.domain.theme import get_theme
from app.worker import fonts
from app.worker.catalog import catalog_version
from app.worker.handlers import TaskHandlers
from app.worker.protocol import ClaimedTask
from app.worker.runner import public_error


def fixture_font(advance=500):
    builder = FontBuilder(1000, isTTF=True)
    names = [".notdef", "space", "A"]
    builder.setupGlyphOrder(names)
    builder.setupCharacterMap({32: "space", 65: "A"})
    builder.setupGlyf({name: TTGlyphPen(None).glyph() for name in names})
    builder.setupHorizontalMetrics({name: (advance, 0) for name in names})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({"familyName": "Fixture", "styleName": "Regular", "uniqueFontIdentifier": "Fixture", "fullName": "Fixture Regular", "psName": "Fixture-Regular"})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost()
    output = BytesIO()
    builder.save(output)
    return output.getvalue()


@pytest.fixture
def font_fixture(tmp_path, monkeypatch):
    data = fixture_font()
    specs = []
    for role, filename in fonts._PATHS.items():
        (tmp_path / filename).write_bytes(data)
        specs.append({"role": role, "path": filename, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    manifest = {"version": "aippt-fonts-1", "files": specs}
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    digest = hashlib.sha256(fonts.canonical_manifest(manifest)).hexdigest()
    monkeypatch.setattr(text_metrics, "FONTS_DIR", tmp_path)
    monkeypatch.setattr(fonts, "get_settings", lambda: Settings(_env_file=None, font_manifest_path=str(path)))
    text_metrics.clear_font_cache()
    yield tmp_path, path, manifest, digest
    text_metrics.clear_font_cache()


def test_verified_font_bytes_snapshot_survives_path_replacement(font_fixture):
    root, path, manifest, digest = font_fixture
    identity, token = fonts.bind_task_fonts({"font_manifest_hash": digest})
    try:
        style = get_theme("ivory").text_styles["body"].model_copy(update={"size_pt": 10, "letter_spacing_pt": 0})
        original = text_metrics.measure_text("AAAA", style=style, width_pt=12, height_pt=100)
        for filename in fonts._PATHS.values():
            (root / filename).write_bytes(fixture_font(1000))
        after = text_metrics.measure_text("AAAA", style=style, width_pt=12, height_pt=100)
        assert identity == {"font_manifest_hash": digest, "font_mode": "verified"}
        assert original.line_count == after.line_count == 2 and not after.used_estimate
        assert text_metrics.fonts_available()
    finally:
        text_metrics.unbind_font_faces(token)
    with pytest.raises(fonts.FontManifestInvalid):
        fonts.bind_task_fonts({"font_manifest_hash": digest})


def test_unavailable_forces_estimate_despite_valid_local_fonts(font_fixture):
    identity, token = fonts.bind_task_fonts({"font_manifest_hash": "unavailable"})
    try:
        assert identity["font_mode"] == "estimate" and not text_metrics.fonts_available()
        assert text_metrics.measure_text("A", style=get_theme("ivory").text_styles["body"], width_pt=100, height_pt=100).used_estimate
    finally:
        text_metrics.unbind_font_faces(token)
    assert text_metrics.fonts_available()


@pytest.mark.asyncio
async def test_font_contexts_are_isolated_between_concurrent_tasks(font_fixture):
    digest = font_fixture[3]
    async def one(expected):
        identity, token = fonts.bind_task_fonts({"font_manifest_hash": expected})
        try:
            await asyncio.sleep(0)
            await asyncio.sleep(0)
            return identity["font_mode"], text_metrics.fonts_available()
        finally:
            text_metrics.unbind_font_faces(token)
    assert await asyncio.gather(one(digest), one("unavailable")) == [("verified", True), ("estimate", False)]
    assert text_metrics._task_faces.get() is None


@pytest.mark.parametrize("mutation", ["size", "hash", "path", "duplicate", "extra"])
def test_invalid_manifest_is_rejected_with_specific_nonretryable_failure(font_fixture, mutation):
    root, path, original, digest = font_fixture
    manifest = copy.deepcopy(original)
    if mutation == "size": manifest["files"][0]["size"] = True
    if mutation == "hash": manifest["files"][0]["sha256"] = "bad"
    if mutation == "path": manifest["files"][0]["path"] = "../font.ttf"
    if mutation == "duplicate": manifest["files"][1] = manifest["files"][0]
    if mutation == "extra": manifest["credential"] = "fixture-secret"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(fonts.FontManifestInvalid) as caught:
        fonts.bind_task_fonts({"font_manifest_hash": digest})
    assert public_error(caught.value)[0] == "FONT_MANIFEST_INVALID" and not public_error(caught.value)[2]
    assert text_metrics._task_faces.get() is None


def test_hash_mismatch_snapshot_conflict_and_corrupt_font_are_rejected(font_fixture):
    root, path, manifest, digest = font_fixture
    with pytest.raises(fonts.FontManifestMismatch) as caught:
        fonts.bind_task_fonts({"font_manifest_hash": "0" * 64})
    assert public_error(caught.value)[0] == "FONT_MANIFEST_MISMATCH"
    with pytest.raises(fonts.FontManifestMismatch):
        fonts.bind_task_fonts({"font_manifest_hash": digest, "snapshot": {"font_manifest_hash": "unavailable"}})
    data = b"not-a-font"
    for item in manifest["files"]:
        (root / item["path"]).write_bytes(data)
        item.update(size=len(data), sha256=hashlib.sha256(data).hexdigest())
    path.write_text(json.dumps(manifest), encoding="utf-8")
    bad_digest = hashlib.sha256(fonts.canonical_manifest(manifest)).hexdigest()
    with pytest.raises(fonts.FontManifestInvalid):
        fonts.bind_task_fonts({"font_manifest_hash": bad_digest})


def test_manifest_and_font_read_budgets(font_fixture):
    root, path, manifest, digest = font_fixture
    manifest["files"][0]["size"] = fonts.MAX_FONT_BYTES + 1
    with pytest.raises(fonts.FontManifestInvalid):
        fonts.canonical_manifest(manifest)
    path.write_bytes(b" " * (fonts.MAX_MANIFEST_BYTES + 1))
    with pytest.raises(fonts.FontManifestInvalid):
        fonts.bind_task_fonts({"font_manifest_hash": digest})


def test_dotenv_font_directory_and_manifest_configuration_are_honored(font_fixture, tmp_path, monkeypatch):
    root, path, manifest, digest = font_fixture
    dotenv = tmp_path / "fonts.env"
    dotenv.write_text(f'AIPPT_FONTS_DIR="{root.as_posix()}"\nAIPPT_FONT_MANIFEST_PATH="{path.as_posix()}"\n', encoding="utf-8")
    cfg = Settings(_env_file=dotenv)
    monkeypatch.setattr(fonts, "get_settings", lambda: cfg)
    monkeypatch.setattr(text_metrics, "FONTS_DIR", root / "unused")
    identity, token = fonts.bind_task_fonts({"font_manifest_hash": digest})
    try:
        assert identity["font_mode"] == "verified" and text_metrics.fonts_available()
    finally:
        text_metrics.unbind_font_faces(token)
    assert fonts.font_readiness()["font_manifest_hash"] == digest
    path.write_text("not-json", encoding="utf-8")
    assert fonts.font_readiness() == {"font_manifest_hash":"unavailable","font_mode":"estimate","fonts_precise":False,"font_status":"invalid"}


@pytest.mark.asyncio
async def test_task_output_and_finally_reset_actual_font_identity(font_fixture):
    class Handler(TaskHandlers):
        async def quality(self, task):
            assert text_metrics.fonts_available()
            return {"can_export": True}, []
    digest = font_fixture[3]
    task = ClaimedTask(taskId="fixture", attempt=1, leaseToken="lease", fencingToken=1, type="quality", catalogVersion=catalog_version(), payload={"font_manifest_hash": digest})
    output, _ = await Handler(object()).run(task)
    assert output["font_manifest_hash"] == digest and output["font_mode"] == "verified"
    assert output["execution"]["usage_status"] == "not_applicable" and text_metrics._task_faces.get() is None
