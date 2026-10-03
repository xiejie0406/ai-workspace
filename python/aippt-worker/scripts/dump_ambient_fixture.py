"""重新生成前后端氛围层对齐夹具。

用法：在 Worker 源码或解包 sdist 根运行 python scripts/dump_ambient_fixture.py
改了 domain/ambient.py 或任一主题的 ambient 后跑一次，
输出本地待核对夹具，不能直接覆写已固定版本的共享目录。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.paths import SHARED_DIR, WORKER_ROOT  # noqa: E402
from app.domain.ambient import iter_ambient_shapes  # noqa: E402
from app.domain.geometry import SAFE_AREA, Rect  # noqa: E402
from app.domain.theme import load_themes  # noqa: E402
from app.worker.catalog import initialize_catalog  # noqa: E402

# 覆盖三种作用域：封面、章节页、正文页
LAYOUT_IDS = ("cover", "section", "bullets")
SLIDE_INDEX = 2

FIXTURE = WORKER_ROOT / "var" / "ambient-fixtures" / "theme-cases.json"

_TITLE_H = 0.14
_GUTTER = 0.02


def _two_column_content() -> list[Rect]:
    """安全区里典型的「标题 + 左右两栏」占位，用来验 avoid_content。

    栏间留了空隙：打开避让的网格线只该保留在这道缝里。
    """
    area = SAFE_AREA
    body_y = area.y + _TITLE_H
    body_h = area.bottom - body_y
    column_w = (area.w - _GUTTER) / 2
    return [
        Rect(x=area.x, y=area.y, w=area.w, h=_TITLE_H),
        Rect(x=area.x, y=body_y, w=column_w, h=body_h),
        Rect(x=area.right - column_w, y=body_y, w=column_w, h=body_h),
    ]


# 每个主题/布局都跑一遍空占位与两栏占位：前者验展开、后者验避让
OCCUPIED_CASES: dict[str, list[Rect]] = {
    "empty": [],
    "two_column": _two_column_content(),
}


def main() -> None:
    parser = argparse.ArgumentParser(description="从已核对目录生成本地氛围层对齐夹具")
    parser.add_argument("--output", type=Path, default=FIXTURE, help="本地输出JSON；不得位于共享catalog或覆盖其manifest")
    output = parser.parse_args().output.resolve()
    catalog = SHARED_DIR.resolve()
    if output.is_relative_to(catalog) or output == catalog.parent / "catalog-manifest.json":
        parser.error("输出不得覆盖已固定版本的共享catalog或catalog-manifest.json")
    initialize_catalog()
    cases = [
        {
            "theme_id": theme_id,
            "layout_id": layout_id,
            "slide_index": SLIDE_INDEX,
            "occupied_id": occupied_id,
            "occupied": [rect.model_dump() for rect in occupied],
            "expected_shapes": [
                shape.model_dump()
                for shape in iter_ambient_shapes(theme, layout_id, SLIDE_INDEX, occupied)
            ],
        }
        for theme_id, theme in load_themes().items()
        for layout_id in LAYOUT_IDS
        for occupied_id, occupied in OCCUPIED_CASES.items()
    ]

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps({"cases": cases}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"写入 {output}（{len(cases)} 个用例）")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    main()
