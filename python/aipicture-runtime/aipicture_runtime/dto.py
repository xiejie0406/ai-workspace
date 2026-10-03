from __future__ import annotations

import uuid
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from aipicture_runtime import SCHEMA_VERSION, TOOL_REGISTRY_VERSION
from aipicture_runtime.layers import LayerDocument, LayerKind


class DTO(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class AssetRef(DTO):
    id: uuid.UUID
    sha256: str = Field(pattern="^[a-f0-9]{64}$")
    width: int = Field(gt=0)
    height: int = Field(gt=0)
    format: str = "PNG"
    kind: str | None = None
    read_url: str | None = None
    content_base64: str | None = None

    @model_validator(mode="after")
    def one_source(self):
        if (self.read_url is None) == (self.content_base64 is None):
            raise ValueError("素材必须提供 read_url/content_base64 之一")
        return self


class ComputeRequest(DTO):
    schema_version: Literal[SCHEMA_VERSION] = SCHEMA_VERSION
    tool_registry_version: Literal[TOOL_REGISTRY_VERSION] = TOOL_REGISTRY_VERSION
    run_id: str = Field(min_length=1, max_length=80)
    attempt: int = Field(ge=1, le=100)
    tool: str
    params: dict = Field(default_factory=dict)
    document: LayerDocument | None = None
    input_version: int = Field(default=0, ge=0)
    input_revision: int | None = Field(default=None, ge=0)
    session_id: str | None = None
    lease_token: str | None = None
    provider_task_id: str | None = Field(default=None, max_length=128)
    assets: list[AssetRef] = Field(default_factory=list, max_length=264)
    selection_asset_id: uuid.UUID | None = None
    background_mode: Literal["white", "transparent"] = "white"

    @model_validator(mode="after")
    def asset_budget(self):
        if self.tool == "evaluation":
            manifest = self.params.get("manifest")
            if not isinstance(manifest, list) or not 1 <= len(manifest) <= 100:
                raise ValueError("评测清单须为1–100项")
            needed = set()
            for raw in manifest:
                if not isinstance(raw, dict):
                    raise ValueError("评测项须为对象")
                for key in ("input_asset_id", "expect_asset_id", "output_asset_id"):
                    if raw.get(key) is not None:
                        needed.add(uuid.UUID(str(raw[key])))
                if raw.get("task") == "assess_result":
                    frozen = raw.get("assessment")
                    if not isinstance(frozen, dict) or not isinstance(frozen.get("asset_ids"), list):
                        raise ValueError("专项评估必须提供中心冻结的assessment与引用清单")
                    for key in ("asset_ids", "output_asset_ids"):
                        values = frozen.get(key) or []
                        if not isinstance(values, list) or len(values) > 264:
                            raise ValueError("专项冻结引用清单超限")
                        needed.update(uuid.UUID(str(value)) for value in values)
                    if frozen.get("mask_asset_id") is not None:
                        needed.add(uuid.UUID(str(frozen["mask_asset_id"])))
                    for key in ("source_document", "result_document"):
                        # Java将没有画布的正式生成/选区结果编码为空对象。
                        if frozen.get(key):
                            document = LayerDocument.model_validate(frozen[key])
                            needed.update(layer.asset_id for layer in document.layers
                                if layer.kind is LayerKind.IMAGE and layer.asset_id is not None)
            identifiers = [ref.id for ref in self.assets]
            if len(set(identifiers)) != len(identifiers) or len(identifiers) > 264 or set(identifiers) != needed:
                raise ValueError("评测最多264个互异素材且必须恰好为冻结源/结果/人工参考依赖并集")
            return self
        if self.tool != "export":
            if len(self.assets) > 64:
                raise ValueError("普通计算最多引用 64 个素材")
            return self
        raw_selected = self.params.get("asset_ids") or []
        if not isinstance(raw_selected, list) or len(raw_selected) > 200:
            raise ValueError("导出最多选择 200 个素材")
        selected = [uuid.UUID(str(identifier)) for identifier in raw_selected]
        if len(set(selected)) != len(selected):
            raise ValueError("导出所选素材不能重复")
        fmt = self.params.get("format", "png")
        if not isinstance(fmt, str) or fmt.lower() not in {"png", "zip"}:
            raise ValueError("导出格式无效")
        current = self.params.get("current_asset_id")
        current = uuid.UUID(str(current)) if current is not None else None
        flatten = fmt.lower() == "png" or not selected or current in selected
        if flatten and self.document is None:
            raise ValueError("合成导出必须提供冻结画布")
        if not flatten and self.document is not None:
            raise ValueError("非当前素材 ZIP 不应冻结无用画布")
        # PNG 只合成画布；ZIP 只有所选当前素材需要画布图层原图。
        selected_refs = set(selected) if fmt.lower() == "zip" else set()
        dependencies = {layer.asset_id for layer in self.document.layers
            if layer.kind is LayerKind.IMAGE and layer.asset_id is not None} if flatten else set()
        if len(dependencies - selected_refs) > 64:
            raise ValueError("导出最多增加 64 个必要画布素材依赖")
        identifiers = [ref.id for ref in self.assets]
        if len(identifiers) > 264 or len(set(identifiers)) != len(identifiers):
            raise ValueError("导出冻结素材必须互异且最多 264 个")
        if set(identifiers) != selected_refs | dependencies:
            raise ValueError("导出只允许所选 200 个素材与必要画布依赖，且不得遗漏引用")
        return self


class SelectionRequest(ComputeRequest):
    tool: str = "selection"
    points: list[tuple[float, float]] = Field(default_factory=list, max_length=100)
    strokes: list[dict] = Field(default_factory=list, max_length=1000)
    radius: float = Field(default=0.03, ge=0.005, le=0.12)
    prepare_only: bool = False


class PlanRequest(DTO):
    schema_version: Literal[SCHEMA_VERSION] = SCHEMA_VERSION
    tool_registry_version: Literal[TOOL_REGISTRY_VERSION] = TOOL_REGISTRY_VERSION
    run_id: str = Field(min_length=1, max_length=80)
    attempt: int = Field(default=1, ge=1, le=100)
    goal: str = Field(min_length=1, max_length=1500)
    context: str = Field(default="", max_length=20000)


class Artifact(DTO):
    id: uuid.UUID
    sha256: str
    width: int
    height: int
    format: str
    size_bytes: int
    has_alpha: bool
    kind: str
    source: str = "tool"
    content_base64: str
