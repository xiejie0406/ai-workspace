#!/usr/bin/env python3
"""默认只输出字体准备计划；--apply 的真实下载须另获项目授权。"""
from __future__ import annotations

import sys
# plan 不因首次导入在源码/依赖目录写 __pycache__。
sys.dont_write_bytecode = True

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import tempfile
import time
import uuid

import httpx
from fontTools.ttLib import TTFont

# 直接运行原入口也读取 Worker 的 Settings/.env，不导入中心/Provider。
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.core.config import get_settings
from app.domain import text_metrics
from app.worker.fonts import MAX_FONT_BYTES, MAX_MANIFEST_BYTES, canonical_manifest


@dataclass(frozen=True)
class Source:
    role: str
    kind: str
    filename: str
    repository: str
    commit: str
    source_path: str
    size: int
    git_blob_sha1: str
    family: str | None = None

    @property
    def url(self) -> str:
        return f"https://raw.githubusercontent.com/{self.repository}/{self.commit}/{self.source_path}"


# GitTree 元数据锚定来源字节；Git blob SHA1 不是 manifest SHA256。
SOURCES = (
    Source("latin", "font", "NotoSans-Regular.ttf", "notofonts/noto-fonts", "ffebf8c1ee449e544955a7e813c54f9b73848eac", "hinted/ttf/NotoSans/NotoSans-Regular.ttf", 569208, "d55220958aa51eeeb85048d746eabe43d2cd9f14", "Noto Sans"),
    Source("latin", "license", "NotoSans-LICENSE.txt", "notofonts/noto-fonts", "ffebf8c1ee449e544955a7e813c54f9b73848eac", "LICENSE", 4377, "c82d72e422e2d08c5ab439b6bac7c2177ea0c565"),
    Source("cjk", "font", "NotoSansSC-Regular.otf", "notofonts/noto-cjk", "f8d157532fbfaeda587e826d4cd5b21a49186f7c", "Sans/SubsetOTF/SC/NotoSansSC-Regular.otf", 8331336, "fc0fda9394367c16c1af7555a2ae4d5e2e6a6f02", "Noto Sans SC"),
    Source("cjk", "license", "NotoSansSC-LICENSE.txt", "notofonts/noto-cjk", "f8d157532fbfaeda587e826d4cd5b21a49186f7c", "Sans/LICENSE", 4301, "d952d62c065f3f35fb83a173496e90b21525aef3"),
)
METADATA_NAME = "font-sources.json"
MAX_LICENSE_BYTES = 256 * 1024


class PreparationError(ValueError):
    pass


def git_blob_sha1(data: bytes) -> str:
    return hashlib.sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()


def targets(fonts_dir: str | None, manifest_path: str | None) -> tuple[Path, Path]:
    if fonts_dir == "" or manifest_path == "":
        raise PreparationError("显式CLI目标不得为空")
    cfg = get_settings()
    root = Path(fonts_dir or cfg.fonts_dir or text_metrics.FONTS_DIR).absolute()
    # 显式目录选择新的完整包；显式 manifest 仍优先，未改写 .env。
    manifest = Path(manifest_path or (str(root / "manifest.json") if fonts_dir else cfg.font_manifest_path) or root / "manifest.json").absolute()
    if root.is_symlink() or manifest.is_symlink():
        raise PreparationError("拒绝符号链接字体目标")
    return root.resolve(), manifest.resolve()


def plan(root: Path, manifest: Path, timeout: float) -> dict:
    configured_root, configured_manifest = targets(None, None)
    return {
        "mode": "plan", "network": False, "writes": False,
        "fonts_dir": str(root), "manifest_path": str(manifest),
        "metadata_path": str(root / METADATA_NAME),
        "publication": "staged_directory_rename",
        "matches_current_worker_configuration": root == configured_root and manifest == configured_manifest,
        "apply_supported": manifest.parent == root and manifest.name not in {s.filename for s in SOURCES} | {METADATA_NAME, ".gitkeep"},
        "timeout_seconds_per_resource": timeout, "http_retries": 0, "redirects": False,
        "max_font_bytes": MAX_FONT_BYTES, "max_license_bytes": MAX_LICENSE_BYTES,
        "sources": [{"role": s.role, "kind": s.kind, "target": str(root / s.filename), "repository": s.repository, "commit": s.commit, "source_path": s.source_path, "url": s.url, "expected_size": s.size, "expected_git_blob_sha1": s.git_blob_sha1, "expected_family": s.family} for s in SOURCES],
        "authorization": "真实下载须另获完整迁移设计第3.6节授权；--apply 参数本身不代替授权。",
        "limitations": "原参考未固定字体版本；该候选不证明历史字体字节或像素一致，亦不代表 Office/真实业务通过。",
    }


def _validate_bytes(source: Source, data: bytes) -> dict:
    limit = MAX_FONT_BYTES if source.kind == "font" else MAX_LICENSE_BYTES
    if not 0 < len(data) <= limit or len(data) != source.size or git_blob_sha1(data) != source.git_blob_sha1:
        raise PreparationError(f"{source.filename} 的体积或固定来源字节不符")
    result = {"role": source.role, "kind": source.kind, "path": source.filename,
              "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
              "repository": source.repository, "commit": source.commit,
              "source_path": source.source_path, "url": source.url,
              "git_blob_sha1": source.git_blob_sha1}
    if source.kind == "font":
        try:
            if text_metrics._load_face_bytes(data, source.filename) is None:
                raise ValueError("缺少 Worker 度量数据")
            with TTFont(BytesIO(data), lazy=False) as font:
                if not font.getBestCmap() or not font["hmtx"].metrics or int(font["head"].unitsPerEm) <= 0:
                    raise ValueError("缺少度量数据")
                family, style, version = (font["name"].getDebugName(i) for i in (1, 2, 5))
                if family != source.family or style != "Regular" or not version:
                    raise ValueError("字体名称或版本不符")
                result.update(family=family, style=style, font_version=version)
        except Exception as error:
            raise PreparationError(f"{source.filename} 不是合法指定的 Regular 度量字体") from error
    else:
        try:
            license_text = data.decode("utf-8")
            if "SIL OPEN FONT LICENSE Version 1.1" not in license_text or "PERMISSION & CONDITIONS" not in license_text:
                raise ValueError("许可内容无效")
        except (UnicodeError, ValueError) as error:
            raise PreparationError(f"{source.filename} 不是指定的 OFL 1.1 许可") from error
        result["license"] = "SIL-OFL-1.1"
    return result


def _read(path: Path, limit: int) -> bytes:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
        raise PreparationError(f"{path.name} 不是预算内的普通文件")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise PreparationError(f"{path.name} 超过读取预算")
    return data


def _manifest_and_metadata(resources: list[dict]) -> tuple[dict, dict]:
    manifest = {"version": "aippt-fonts-1", "files": [{key: item[key] for key in ("role", "path", "size", "sha256")} for item in resources if item["kind"] == "font"]}
    manifest["files"].sort(key=lambda item: item["role"])
    digest = hashlib.sha256(canonical_manifest(manifest)).hexdigest()
    return manifest, {"version": "aippt-font-sources-1", "font_manifest_sha256": digest, "resources": resources}


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise PreparationError("资源JSON含重复字段")
        result[key] = value
    return result


def validate_directory(root: Path, manifest_name: str) -> dict:
    expected = {s.filename for s in SOURCES} | {manifest_name, METADATA_NAME}
    present = {p.name for p in root.iterdir()}
    if present - expected - {".gitkeep"} or expected - present:
        raise PreparationError("已存在字体目录不完整或含其他文件，拒绝覆盖")
    resources = [_validate_bytes(s, _read(root / s.filename, MAX_FONT_BYTES if s.kind == "font" else MAX_LICENSE_BYTES)) for s in SOURCES]
    manifest, metadata = _manifest_and_metadata(resources)
    actual_manifest = json.loads(_read(root / manifest_name, MAX_MANIFEST_BYTES), object_pairs_hook=_unique_object)
    actual_metadata = json.loads(_read(root / METADATA_NAME, MAX_MANIFEST_BYTES), object_pairs_hook=_unique_object)
    if canonical_manifest(actual_manifest) != canonical_manifest(manifest) or actual_metadata != metadata:
        raise PreparationError("已存在manifest或来源metadata不符，拒绝覆盖")
    return {"mode": "reused", "font_manifest_hash": metadata["font_manifest_sha256"], "fonts_dir": str(root), "manifest_path": str(root / manifest_name)}


def download(source: Source, target: Path, timeout: float, client: httpx.Client) -> dict:
    deadline = time.monotonic() + timeout
    limit = MAX_FONT_BYTES if source.kind == "font" else MAX_LICENSE_BYTES
    if not 0 < source.size <= limit:
        raise PreparationError("来源体积超过资源预算")
    try:
        with client.stream("GET", source.url, timeout=timeout, headers={"Accept-Encoding": "identity"}) as response:
            if time.monotonic() > deadline:
                raise PreparationError(f"{source.filename} 下载超过总时限")
            if response.status_code != 200 or str(response.url) != source.url:
                raise PreparationError(f"{source.filename} HTTP状态或响应地址无效（不允许重定向）")
            if response.headers.get("content-encoding", "identity").lower() != "identity":
                raise PreparationError(f"{source.filename} HTTP压缩编码不符")
            declared = response.headers.get("content-length")
            if declared is not None and (not declared.isascii() or not declared.isdecimal() or int(declared) != source.size):
                raise PreparationError(f"{source.filename} HTTP长度与固定来源不符")
            data = bytearray()
            for part in response.iter_bytes(64 * 1024):
                if time.monotonic() > deadline:
                    raise PreparationError(f"{source.filename} 下载超过总时限")
                if len(data) + len(part) > min(limit, source.size):
                    raise PreparationError(f"{source.filename} 下载超过资源预算")
                data.extend(part)
            if time.monotonic() > deadline:
                raise PreparationError(f"{source.filename} 下载超过总时限")
    except httpx.HTTPError as error:
        raise PreparationError(f"{source.filename} HTTP通道失败（{type(error).__name__}）") from error
    if time.monotonic() > deadline:
        raise PreparationError(f"{source.filename} 下载超过总时限")
    resource = _validate_bytes(source, bytes(data))
    if time.monotonic() > deadline:
        raise PreparationError(f"{source.filename} 下载超过总时限")
    _write(target, bytes(data))
    return resource


def _write(path: Path, data: bytes) -> None:
    with path.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


@contextmanager
def _publication_lock(root: Path):
    root.parent.mkdir(parents=True, exist_ok=True)
    path = root.with_name(f".{root.name}.prepare.lock")
    token = uuid.uuid4().hex.encode("ascii")
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError as error:
        raise PreparationError("字体准备锁已存在；请核对另一个执行或遗留锁，不自动移除") from error
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(token)
            stream.flush()
            os.fsync(stream.fileno())
        yield
    finally:
        if path.is_file() and not path.is_symlink() and _read(path, 128) == token:
            path.unlink()


def prepare(root: Path, manifest: Path, timeout: float, client_factory=httpx.Client) -> dict:
    if manifest.parent != root or manifest.name in {s.filename for s in SOURCES} | {METADATA_NAME, ".gitkeep"}:
        raise PreparationError("原子发布要求manifest位于字体目录根内且名称不冲突；请显式选共同目标目录")
    with _publication_lock(root):
        empty_shell = False
        if root.exists():
            if not root.is_dir() or root.is_symlink():
                raise PreparationError("字体目标不是普通目录")
            members = list(root.iterdir())
            empty_shell = not members or (len(members) == 1 and members[0].name == ".gitkeep" and not members[0].is_symlink() and members[0].is_file() and members[0].stat().st_size == 0)
            if not empty_shell:
                return validate_directory(root, manifest.name)
        with tempfile.TemporaryDirectory(prefix=f".{root.name}.stage-", dir=root.parent) as temporary:
            stage = Path(temporary) / "resources"
            stage.mkdir()
            with client_factory(follow_redirects=False, trust_env=False, timeout=timeout) as client:
                resources = [download(source, stage / source.filename, timeout, client) for source in SOURCES]
            manifest_data, metadata = _manifest_and_metadata(resources)
            _write(stage / manifest.name, canonical_manifest(manifest_data))
            _write(stage / METADATA_NAME, json.dumps(metadata, ensure_ascii=False, indent=2).encode("utf-8"))
            validate_directory(stage, manifest.name)
            backup = None
            # 已可用目标从不替换。仅空壳可移动；失败恢复空壳，不逐文件合并。
            if root.exists():
                members = list(root.iterdir())
                if not empty_shell or any(p.name != ".gitkeep" or p.is_symlink() or not p.is_file() or p.stat().st_size != 0 for p in members):
                    raise PreparationError("准备期间目标目录发生变化，拒绝覆盖")
                backup = root.with_name(f".{root.name}.empty-backup-{uuid.uuid4().hex}")
                root.rename(backup)
            try:
                stage.rename(root)
            except OSError as error:
                if backup is not None and not root.exists():
                    backup.rename(root)
                raise PreparationError("字体目录发布失败；已有目录未覆盖") from error
        result = {"mode": "prepared", "font_manifest_hash": metadata["font_manifest_sha256"], "fonts_dir": str(root), "manifest_path": str(manifest)}
        if backup is not None:
            result["preserved_empty_directory"] = str(backup)
        return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="执行已另获授权的4项固定资源准备；默认只输出计划")
    parser.add_argument("--fonts-dir", help="显式目录优先于 AIPPT_FONTS_DIR/.env")
    parser.add_argument("--manifest-path", help="显式manifest优先于配置；apply要求它位于字体目录根内")
    parser.add_argument("--timeout", type=float, default=30, help="每资源HTTP与总下载时限，1至120秒，默认30")
    args = parser.parse_args(argv)
    try:
        if not 1 <= args.timeout <= 120:
            raise PreparationError("timeout须在1至120秒之间")
        root, manifest = targets(args.fonts_dir, args.manifest_path)
        result = prepare(root, manifest, args.timeout) if args.apply else plan(root, manifest, args.timeout)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (PreparationError, OSError, ValueError) as error:
        print(f"字体准备失败：{type(error).__name__}: {error}" if isinstance(error, PreparationError) else f"字体准备失败：{type(error).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
