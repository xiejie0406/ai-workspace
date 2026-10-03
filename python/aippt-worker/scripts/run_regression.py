#!/usr/bin/env python3
"""运行固定回归集：语料 × 主题 → 可编辑性 / 溢出 / 主题一致性报告。

用法（在 Worker 源码或解包 sdist 根目录，依赖已准备）：
  .venv/Scripts/python.exe -B scripts/run_regression.py
仅内存生成/回读八组合PPTX，打印原中文报告，不保存PPTX文件。
"""

from __future__ import annotations

import sys
from pathlib import Path

# 允许直接 `python scripts/run_regression.py` 时找到 app 包
BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.regression.runner import format_report, run_regression  # noqa: E402
from app.worker.catalog import initialize_catalog  # noqa: E402


def main() -> int:
    initialize_catalog()
    report = run_regression()
    sys.stdout.write(format_report(report))
    return 0 if report.passed else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
