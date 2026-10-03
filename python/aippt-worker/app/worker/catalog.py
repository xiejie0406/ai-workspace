"""所有算法文件校验清单，目录不完整时禁止领取任务。"""
import hashlib
import json
from pathlib import Path
from app.core.paths import SHARED_DIR


def catalog_version(path: Path = SHARED_DIR) -> str:
    files = sorted(p for p in path.rglob("*.json") if p.name not in {"manifest.json", "catalog-manifest.json"})
    if not files or len(list((path / "themes").glob("*.json"))) != 4 or len(list((path / "layouts").glob("*.json"))) != 11:
        raise ValueError("主题或布局目录不完整，拒绝启动 Worker")
    manifest_path = path.parent / "catalog-manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        expected = {item["path"]: item for item in manifest["files"]}
        if set(expected) != {p.relative_to(path).as_posix() for p in files}:
            raise ValueError("目录文件清单与中心 manifest 不一致")
        for relative, item in expected.items():
            data = (path / relative).read_bytes()
            if len(data) != item["size"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise ValueError("目录文件哈希与中心 manifest 不一致")
        return manifest["catalogVersion"]
    raise ValueError("目录缺少中心 catalog-manifest.json，拒绝启动 Worker")


def initialize_catalog() -> str:
    """同一批已验证字节预热算法缓存，部署目录须保持不可变。"""
    from app.domain.layout import load_layouts
    from app.domain.theme import load_themes
    from app.domain.flex_presets import load_presets
    before = catalog_version()
    manifest_bytes = (SHARED_DIR.parent / "catalog-manifest.json").read_bytes()
    for loader in (load_layouts, load_themes, load_presets):
        loader.cache_clear()
        loader()
    after = catalog_version()
    if before != after or manifest_bytes != (SHARED_DIR.parent / "catalog-manifest.json").read_bytes():
        raise ValueError("预热时目录版本发生改变")
    return after


def catalog_readiness() -> dict:
    from app.domain.layout import load_layouts
    from app.domain.theme import load_themes
    from app.worker.fonts import font_readiness
    return {"catalogVersion": catalog_version(), "themes": len(load_themes()), "layouts": len(load_layouts()),
            "presets": len(list((SHARED_DIR / "flex-presets").glob("*.json"))), **font_readiness()}
