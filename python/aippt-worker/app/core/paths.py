"""中心契约目录是唯一目录源；打包可显式指定经哈希校验的只读副本。"""
from pathlib import Path
from app.core.config import get_settings

WORKER_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = WORKER_ROOT.parents[1]
SHARED_DIR = Path(get_settings().catalog_dir or str(REPO_ROOT / "contracts/aippt/catalog")).resolve()
LAYOUTS_DIR = SHARED_DIR / "layouts"
THEMES_DIR = SHARED_DIR / "themes"
