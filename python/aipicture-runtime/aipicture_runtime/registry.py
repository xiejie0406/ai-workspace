"""源 Pydantic 参数定义为唯一注册表，Java/React 读取同份导出契约。"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from pydantic import TypeAdapter, ValidationError

from aipicture_runtime import params


class UnknownTool(ValueError):
    pass


class InvalidParams(ValueError):
    pass


@dataclass(frozen=True)
class ToolSpec:
    name: str
    label: str
    description: str
    params: type
    queued: bool
    session_required: bool
    agent_hidden: tuple[str, ...]

    def label_for(self, values: dict | None = None) -> str:
        if self.name == "flip_layer":
            return {"horizontal": "水平翻转", "vertical": "垂直翻转"}.get((values or {}).get("direction"), self.label)
        return self.label


_RAW = json.loads(Path(__file__).with_name("tool_metadata.json").read_text(encoding="utf-8"))
SPECS = tuple(ToolSpec(item["name"], item["label"], item["description"], getattr(params, item["paramsModel"]),
                       item["queued"], item["sessionRequired"], tuple(item["agentHidden"])) for item in _RAW)
_BY_NAME = {spec.name: spec for spec in SPECS}


def spec_of(name: str) -> ToolSpec:
    try:
        return _BY_NAME[name]
    except KeyError as exc:
        raise UnknownTool(f"未注册的工具：{name}") from exc


def label_of(name: str, values: dict | None = None) -> str:
    return spec_of(name).label_for(values)


def validate(name: str, values: dict) -> dict:
    try:
        normalized = spec_of(name).params.model_validate(values).model_dump(mode="json")
        if name == "batch_process":
            for operation in normalized["operations"]:
                operation["params"] = validate(operation["tool"], operation["params"])
        return normalized
    except ValidationError as exc:
        error = exc.errors()[0]
        field = ".".join(str(part) for part in error["loc"]) or "参数"
        raise InvalidParams(f"{field}：{error['msg']}") from exc


def _params_schema(model: type[params.BaseModel]) -> dict:
    schema = model.model_json_schema()
    # Pydantic 不导出 default_factory；Java 仅消费契约，必须收到与原 DTO 相同的默认。
    # 这些模型的工厂只构造固定参数容器，不包含业务调用或环境状态。
    models = {name: value for name, value in vars(params).items()
              if isinstance(value, type) and issubclass(value, params.BaseModel)}
    for definition, current_model in [(schema, model), *[
        (definition, models[name]) for name, definition in schema.get("$defs", {}).items() if name in models
    ]]:
        for name, field in current_model.model_fields.items():
            if field.default_factory is not None:
                definition["properties"][name]["default"] = TypeAdapter(field.annotation).dump_python(
                    field.get_default(call_default_factory=True), mode="json")
    return schema


def export_registry() -> dict:
    from aipicture_runtime import SCHEMA_VERSION, TOOL_REGISTRY_VERSION
    tools = []
    for metadata, spec in zip(_RAW, SPECS, strict=True):
        item = {key: value for key, value in metadata.items() if key != "paramsModel"}
        if spec.name == "prepare_delivery_sizes":
            item["sourceQueued"] = item["queued"]
            item["queued"] = True
        item["executionOwner"] = "java" if spec.name in {"crop_canvas", "flip_layer", "set_layer_opacity",
            "set_layer_visible", "set_layer_text", "reorder_layer", "scale_layer", "rotate_layer", "move_layer"} else "python"
        item["paramsSchema"] = _params_schema(spec.params)
        # Pydantic model_validator 的条件也进入跨语言契约，不能仅导出字段上下限。
        groups = {"scale_layer": ["factor", "scale_x", "scale_y"], "rotate_layer": ["angle", "rotation"],
                  "move_layer": ["x", "y", "dx", "dy"]}
        if spec.name in groups:
            item["paramsSchema"]["anyOf"] = [{"required": [field], "properties": {field: {"type": "number"}}} for field in groups[spec.name]]
        if spec.name == "crop_canvas":
            item["paramsSchema"]["oneOf"] = [
                {"required": ["ratio"], "properties": {"ratio": {"type": "string"}}, "not": {"properties": {"rect": {"type": "object"}}, "required": ["rect"]}},
                {"required": ["rect"], "properties": {"rect": {"type": "object"}}, "not": {"properties": {"ratio": {"type": "string"}}, "required": ["ratio"]}}]
            item["paramsSchema"]["x-constraints"] = ["rect.x + rect.width <= 1.0001", "rect.y + rect.height <= 1.0001", "min(crop.width,crop.height) >= 32"]
        tools.append(item)
    return {"$schema": "https://json-schema.org/draft/2020-12/schema", "schemaVersion": SCHEMA_VERSION,
            "toolRegistryVersion": TOOL_REGISTRY_VERSION, "tools": tools}
