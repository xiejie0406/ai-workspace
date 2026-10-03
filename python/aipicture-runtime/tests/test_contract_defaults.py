"""确认 Java 仅按 JSON Schema 补默认时，与源 Pydantic DTO 保持一致。"""
from __future__ import annotations

import copy
import json
import uuid

import pytest

from aipicture_runtime.registry import export_registry, validate


def schema_defaults(value, schema, root):
    """模拟 Java SchemaValidator 的 $ref/对象/数组默认值消费。"""
    if "$ref" in schema:
        schema = root["$defs"][schema["$ref"].split("/")[-1]]
    if isinstance(value, dict):
        for name, definition in schema.get("properties", {}).items():
            if name not in value and "default" in definition:
                value[name] = copy.deepcopy(definition["default"])
            if name in value:
                value[name] = schema_defaults(value[name], definition, root)
    elif isinstance(value, list):
        value = [schema_defaults(item, schema.get("items", {}), root) for item in value]
    return value


@pytest.mark.parametrize("tool,input_values", [
    ("prepare_delivery_sizes", {}),
    ("batch_process", {"asset_ids": [str(uuid.UUID(int=1))], "operations": [{"tool": "remove_background"}]}),
    ("batch_process", {"asset_ids": [str(uuid.UUID(int=1))], "operations": [{"tool": "adjust_image"}]}),
])
def test_json_schema_factory_defaults_match_original_dto(tool, input_values):
    # JSON 往返保证枚举等 Python 值未泄漏给跨语言消费者。
    tools = json.loads(json.dumps(export_registry()))["tools"]
    schema = next(item["paramsSchema"] for item in tools if item["name"] == tool)
    normalized = schema_defaults(copy.deepcopy(input_values), schema, schema)
    assert validate(tool, normalized) == validate(tool, input_values)
    if tool == "prepare_delivery_sizes":
        assert normalized["ratios"] == ["1:1", "4:5", "9:16"]
    else:
        assert normalized["formats"] == ["png"]
        assert normalized["operations"][0]["params"] == {}


def test_factory_defaults_exported_in_committed_unique_contract():
    from pathlib import Path
    contract_path = Path(__file__).resolve().parents[3] / "contracts/aipicture/v1/tools.schema.json"
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    assert contract == export_registry()
    by_name = {item["name"]: item["paramsSchema"] for item in contract["tools"]}
    assert by_name["batch_process"]["properties"]["formats"]["default"] == ["png"]
    assert by_name["batch_process"]["$defs"]["BatchOpIn"]["properties"]["params"]["default"] == {}
    assert by_name["prepare_delivery_sizes"]["properties"]["ratios"]["default"] == ["1:1", "4:5", "9:16"]
