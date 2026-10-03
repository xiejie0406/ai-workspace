"""只读锁版本/引擎依赖分析；不解析到新环境、不安装、不导入原服务。"""
from __future__ import annotations

import ast
import json
import sys
import tomllib
from collections import deque
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    sys.path.insert(0, str(root))
    from worker.boundaries import BLOCKED_PREFIXES
    declarations = {}
    focused = {"aiohttp", "pydantic", "pydantic-settings", "openai", "opentelemetry-api", "opentelemetry-sdk", "sqlmodel"}
    locked = {}
    for path in (root / "upstream-dependencies").rglob("pyproject.toml"):
        tree = tomllib.loads(path.read_text(encoding="utf-8-sig"))
        dependencies = tree.get("project", {}).get("dependencies", [])
        if not dependencies:
            dependencies = [key + " " + str(value) for key, value in tree.get("tool", {}).get("poetry", {}).get("dependencies", {}).items()]
        for item in dependencies:
            name = item.split(";", 1)[0].split("==")[0].split(">=")[0].split("<=")[0].split("~=")[0].split("[")[0].strip().lower()
            declarations.setdefault(name, []).append({"baseline": path.relative_to(root).as_posix(), "constraint": item})
    for path in (root / "upstream-dependencies").rglob("*.lock"):
        tree = tomllib.loads(path.read_text(encoding="utf-8-sig"))
        locked[path.relative_to(root).as_posix()] = [{"name": item["name"], "version": item.get("version")}
                                                   for item in tree.get("package", []) if item["name"] in focused]
    differences = [{"dependency": key, "constraints": values, "status": "需要按原锁核对；声明不同不一定不可同时满足"}
                   for key, values in sorted(declarations.items()) if len({item["constraint"].split(";", 1)[0] for item in values}) > 1]
    modules = {}
    for path in root.rglob("*.py"):
        relative = path.relative_to(root)
        if relative.parts[0] in {"scripts", "tests", ".venv", "build", "dist"}:
            continue
        name = ".".join(relative.with_suffix("").parts)
        if name.endswith(".__init__"):
            name = name[:-9]
        modules[name] = path
    graph = {}
    for name, path in modules.items():
        edges = []
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8-sig"))):
            if isinstance(node, ast.ImportFrom) and not node.level and node.module:
                edges.append(node.module)
                edges.extend(node.module + "." + alias.name for alias in node.names)
            elif isinstance(node, ast.Import):
                edges.extend(alias.name for alias in node.names)
            elif (isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "LazyNodeClass"
                    and node.args and isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str)):
                edges.append(node.args[0].value)
        graph[name] = [value for value in dict.fromkeys(edges) if value in modules
            or any(value == prefix or value.startswith(prefix + ".") for prefix in BLOCKED_PREFIXES)]
    queue = deque([["workflow.engine.dsl_engine"]])
    visited = set()
    blockers = []
    while queue:
        chain = queue.popleft()
        current = chain[-1]
        if current in visited:
            continue
        visited.add(current)
        if any(current == prefix or current.startswith(prefix + ".") for prefix in BLOCKED_PREFIXES):
            blockers.append(chain)
            continue
        for value in graph.get(current, []):
            queue.append(chain + [value])
    report = {"declarationDifferences": differences, "originalLockedFocusedVersions": locked,
              "engineGuardDependencyChains": blockers, "reachableLocalModules": len(visited),
              "limits": ["静态图包括显式 LazyNodeClass 和方法内动态导入，展示全部21节点可达依赖，不代表当前纯能力按该顺序执行",
                         "纯引擎已按 requirements-pure.lock 独立恢复；未统一五个完整依赖环境，不能以导入约束代替完整中心适配",
                         "原 Agent 无包名前缀 import 尚须迁入后的包路径适配"]}
    (root / "dependency-port-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"focusedLockedVersions": locked, "engineGuardDependencyChains": blockers}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
