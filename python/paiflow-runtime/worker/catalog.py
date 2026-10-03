"""不导入供应商库即可核对的 21 节点来源；不是就绪宣告。"""
from __future__ import annotations

import ast
from pathlib import Path
from types import MappingProxyType


def _source_registry() -> dict[str, str]:
    path = Path(__file__).resolve().parents[1] / "workflow/engine/nodes/cache_node.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == "tool_classes" for target in node.targets):
            if not isinstance(node.value, ast.Dict):
                break
            return {key.value: value.id for key, value in zip(node.value.keys, node.value.values)}
    raise RuntimeError("原节点注册表缺失，不能缩减节点清单")


NODE_CLASSES = MappingProxyType(_source_registry())
