"""引入经筛选的原算法，排除旧控制面入口、部署文件和可执行示例。

只用于已经授权的源码引入；不会安装、启动、读取 env 或连接服务。
"""
from __future__ import annotations

import ast
import hashlib
import json
from pathlib import Path


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    target = Path(__file__).resolve().parents[1]
    workspace = target.parents[1]
    source = workspace / "参考项目" / "PaiFlow-main"
    entries = []
    excluded = []
    for path in sorted((source / "core").rglob("*.py")):
        relative = path.relative_to(source / "core")
        parts = relative.parts
        reason = None
        if "tests" in parts or "scripts" in parts:
            reason = "原外部集成/配置测试与执行脚本留在只读参考源，未自动运行"
        elif path.name in {"main.py", "conftest.py"}:
            reason = "旧服务/管理 API 启动入口不进入 Worker"
        elif "api" in parts and "schemas" not in parts and path.name != "__init__.py":
            reason = "旧控制面路由不进入 Worker；协议 schemas 单独保留"
        if reason:
            excluded.append({"source": relative.as_posix(), "reason": reason})
            continue
        original = path.read_bytes()
        text = original.decode("utf-8-sig")
        tree = ast.parse(text, filename=relative.as_posix())
        lines = text.splitlines(keepends=True)
        removed = []
        for node in tree.body:
            if isinstance(node, ast.If) and isinstance(node.test, ast.Compare):
                names = {x.id for x in ast.walk(node.test) if isinstance(x, ast.Name)}
                literals = {x.value for x in ast.walk(node.test) if isinstance(x, ast.Constant) and isinstance(x.value, str)}
                if "__name__" in names and "__main__" in literals:
                    removed.append((node.lineno, node.end_lineno))
        for start, end in reversed(removed):
            lines[start - 1:end] = ["# 原可执行服务/供应商示例已剥离；来源和范围见 import-manifest.json。\n"]
        output = "".join(lines).encode("utf-8") if removed else original
        destination = target / relative
        if destination.exists():
            raise RuntimeError(f"拒绝覆盖已有迁移修改：{relative}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(output)
        entries.append({"source": relative.as_posix(), "sha256Original": digest(original),
                        "sha256Introduced": digest(output), "removedExecutableExampleLines": removed})
    for group in ("workflow", "agent", "plugin/link", "plugin/aitools", "common"):
        for name in ("pyproject.toml", "uv.lock", "poetry.lock"):
            path = source / "core" / group / name
            if path.exists():
                destination = target / "upstream-dependencies" / group / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(path.read_bytes())
    for name in ("LICENSE", "NOTICE"):
        (target / name).write_bytes((source / name).read_bytes())
    (target / "import-manifest.json").write_text(json.dumps({"source": "参考项目/PaiFlow-main/core",
        "files": entries, "excluded": excluded, "note": "sha256Introduced 是引入时基线，适配后的当前内容另按 diff/检查核对；无敏感原文"},
        ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"introducedPythonFiles": len(entries), "excludedPythonFiles": len(excluded),
                      "strippedExecutableExamples": sum(bool(x["removedExecutableExampleLines"]) for x in entries)}))


if __name__ == "__main__":
    main()
