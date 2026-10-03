"""字体资源身份校验与任务内字节快照；verified 不代表每个字形都能精确度量。"""
import hashlib
import json
import re
from pathlib import Path
from app.core.config import get_settings
from app.domain import text_metrics

FONT_TASKS = {"generate_slide", "ai_edit", "relayout", "quality", "export"}
_PATHS = {"latin": text_metrics.LATIN_FONT_NAME, "cjk": text_metrics.CJK_FONT_NAME}
MAX_MANIFEST_BYTES = 256 * 1024
MAX_FONT_BYTES = 64 * 1024 * 1024


class FontManifestInvalid(ValueError):
    pass


class FontManifestMismatch(ValueError):
    pass


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise FontManifestInvalid("字体manifest存在重复字段")
        result[key] = value
    return result


def canonical_manifest(manifest: dict) -> bytes:
    if not isinstance(manifest, dict) or set(manifest) != {"version", "files"} or manifest["version"] != "aippt-fonts-1":
        raise FontManifestInvalid("字体manifest版本或字段无效")
    files = manifest["files"]
    if not isinstance(files, list) or len(files) != 2:
        raise FontManifestInvalid("字体manifest必须声明两份度量字体")
    roles = set()
    for item in files:
        if not isinstance(item, dict) or set(item) != {"role", "path", "size", "sha256"}:
            raise FontManifestInvalid("字体manifest文件字段无效")
        role = item["role"]
        if not isinstance(role, str) or role not in _PATHS or role in roles or item["path"] != _PATHS[role]:
            raise FontManifestInvalid("字体manifest角色或路径无效")
        roles.add(role)
        if not isinstance(item["size"], int) or isinstance(item["size"], bool) or not 0 < item["size"] <= MAX_FONT_BYTES:
            raise FontManifestInvalid("字体manifest体积无效")
        if not isinstance(item["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
            raise FontManifestInvalid("字体manifest哈希无效")
    normalized = {"version": manifest["version"], "files": sorted(files, key=lambda item: item["role"])}
    return json.dumps(normalized, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def _read_bounded(path: Path, limit: int) -> bytes:
    if path.stat().st_size > limit:
        raise FontManifestInvalid("字体资源超过读取预算")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise FontManifestInvalid("字体资源超过读取预算")
    return data


def bind_task_fonts(payload: dict):
    snapshot = payload.get("snapshot") or {}
    expected = payload.get("font_manifest_hash", snapshot.get("font_manifest_hash", "unavailable"))
    if "font_manifest_hash" in snapshot and snapshot["font_manifest_hash"] != expected:
        raise FontManifestMismatch("任务与快照字体资源身份不一致")
    if expected == "unavailable":
        return {"font_manifest_hash": "unavailable", "font_mode": "estimate"}, text_metrics.bind_font_faces(None, None)
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise FontManifestInvalid("任务字体资源身份无效")
    cfg = get_settings()
    root = Path(cfg.fonts_dir) if cfg.fonts_dir else text_metrics.FONTS_DIR
    manifest_path = Path(cfg.font_manifest_path) if cfg.font_manifest_path else root / "manifest.json"
    try:
        manifest = json.loads(_read_bounded(manifest_path, MAX_MANIFEST_BYTES).decode("utf-8"), object_pairs_hook=_unique_object)
        actual = hashlib.sha256(canonical_manifest(manifest)).hexdigest()
        if actual != expected:
            raise FontManifestMismatch("Worker字体manifest与任务不一致")
        faces = {}
        for item in manifest["files"]:
            path = root / item["path"]
            data = _read_bounded(path, MAX_FONT_BYTES)
            if len(data) != item["size"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise FontManifestInvalid("度量字体实际字节与manifest不符")
            face = text_metrics._load_face_bytes(data, item["path"])
            if face is None:
                raise FontManifestInvalid("度量字体无法解析")
            faces[item["role"]] = face
    except FontManifestMismatch:
        raise
    except Exception as error:
        if isinstance(error, FontManifestInvalid):
            raise
        raise FontManifestInvalid("字体manifest或文件不可用") from error
    return {"font_manifest_hash": actual, "font_mode": "verified"}, text_metrics.bind_font_faces(faces["latin"], faces["cjk"])


def font_readiness() -> dict:
    cfg = get_settings()
    root = Path(cfg.fonts_dir) if cfg.fonts_dir else text_metrics.FONTS_DIR
    path = Path(cfg.font_manifest_path) if cfg.font_manifest_path else root / "manifest.json"
    unavailable = {"font_manifest_hash": "unavailable", "font_mode": "estimate", "fonts_precise": False}
    if not path.is_file():
        return {**unavailable, "font_status": "unavailable"}
    try:
        manifest = json.loads(_read_bounded(path, MAX_MANIFEST_BYTES).decode("utf-8"), object_pairs_hook=_unique_object)
        digest = hashlib.sha256(canonical_manifest(manifest)).hexdigest()
        identity, token = bind_task_fonts({"font_manifest_hash": digest})
        try:
            return {**identity, "fonts_precise": True, "font_status": "verified"}
        finally:
            text_metrics.unbind_font_faces(token)
    except (OSError, ValueError):
        return {**unavailable, "font_status": "invalid"}
