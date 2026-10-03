"""冻结输入的一次纯计算：返回文档候选/产物，持久采用、历史与任务真相全由 Java 裁决。"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import io
import json
import math
import re
import uuid
import zipfile
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Awaitable, Callable

from PIL import Image, ImageChops
from aipicture_runtime import SCHEMA_VERSION, TOOL_REGISTRY_VERSION
from aipicture_runtime.artifacts import probe, read_asset
from aipicture_runtime.config import get_settings
from aipicture_runtime.dto import Artifact, ComputeRequest
from aipicture_runtime.edits import document as doc_edits
from aipicture_runtime.edits.mask import apply_masked, overlay_png, rasterize_strokes, to_luma, union
from aipicture_runtime.edits.ocr import detect_text
from aipicture_runtime.edits.pixels import adjust, encode, letterbox, remove_background, resize_to
from aipicture_runtime.edits.render import TRANSPARENT, WHITE, flatten
from aipicture_runtime.edits.segment import segment_points, warm_embedding
from aipicture_runtime.edits.split import (
    EmptyCut, alpha_mask, already_promoted, already_split, as_background_and_object,
    cut_object, expand_mask, fill_background, mask_hash, promote_document, remove_text,
    split_document,
)
from aipicture_runtime.geometry import canvas_to_local, layer_under_mask, mask_for_layer
from aipicture_runtime.layers import BACKGROUND_LAYER_ID, Layer, LayerDocument, LayerKind, resolve_layer
from aipicture_runtime.params import BatchOpIn
from aipicture_runtime.providers import EditRequest, GenerateRequest, get_image_provider
from aipicture_runtime.ratios import Ratio, cover_size, ratio_of, size_of
from aipicture_runtime.registry import spec_of, validate

Progress = Callable[[int, str, dict | None], Awaitable[None]]
_RECONSTRUCT = "画面中已有一块被修补过的区域。只把这块修补处修得和周围景物衔接自然，不要新增人物、动物或物体，也不要改变未修补的部分。"
_MARKETING = {
    "product": "把商品放在纯白背景上，居中构图，柔和顶光，边缘干净，保留商品原有材质与颜色，输出电商主图。",
    "scene": "把商品放到真实生活场景中，侧逆光、浅景深，氛围自然，保留商品原有材质与颜色。",
    "model": "生成模特手持或佩戴该商品的半身展示图，简洁室内背景、自然光，商品细节清晰可辨。",
    "poster": "做一张促销海报，突出商品，右侧或下方留出文字区，构图干净有冲击力。",
}


def _safe_export_title(title: str) -> str:
    # 沿用源安全标题规则；补Windows设备名限制，PNG/ZIP均不能成为目录或控制字节。
    cleaned = re.sub(r"[^\w\u4e00-\u9fff\- ]+", "", title if isinstance(title, str) else "").strip()
    cleaned = (cleaned.replace(" ", "-") or "物料")[:40]
    if cleaned.upper() in {"CON", "PRN", "AUX", "NUL", *(f"COM{n}" for n in range(1, 10)), *(f"LPT{n}" for n in range(1, 10))}:
        cleaned = f"物料-{cleaned}"
    return cleaned


def validate_document(doc: LayerDocument, images: dict[uuid.UUID, bytes]) -> None:
    settings = get_settings()
    if doc.width < 1 or doc.height < 1 or doc.width * doc.height > settings.max_pixels or len(doc.layers) > 200:
        raise ValueError("画布尺寸或图层数超过资源限制")
    if len({layer.id for layer in doc.layers}) != len(doc.layers):
        raise ValueError("图层 ID 不能重复")
    for layer in doc.layers:
        if layer.width < 1 or layer.height < 1 or layer.width * layer.height > settings.max_pixels:
            raise ValueError("图层尺寸非法")
        t = layer.transform
        if not all(math.isfinite(value) for value in (t.x, t.y, t.scale_x, t.scale_y, t.rotation, layer.opacity, layer.font_size)):
            raise ValueError("图层数值必须有限")
        if not t.scale_x or not t.scale_y or not 0 <= layer.opacity <= 1 or not 8 <= layer.font_size <= 400:
            raise ValueError("图层缩放、透明度或字号非法")
        scaled_w, scaled_h = layer.width * abs(t.scale_x), layer.height * abs(t.scale_y)
        if not math.isfinite(scaled_w) or not math.isfinite(scaled_h) or scaled_w > settings.max_pixels or scaled_h > settings.max_pixels:
            raise ValueError("图层缩放尺寸超过资源限制")
        actual_w, actual_h = max(1, int(round(scaled_w))), max(1, int(round(scaled_h)))
        if actual_w * actual_h > settings.max_pixels:
            raise ValueError("图层缩放后的像素超过资源限制")
        cosine, sine = abs(math.cos(math.radians(t.rotation))), abs(math.sin(math.radians(t.rotation)))
        if math.ceil(actual_w * cosine + actual_h * sine) * math.ceil(actual_w * sine + actual_h * cosine) > settings.max_pixels:
            raise ValueError("图层旋转后的中间位图超过资源限制")
        if layer.kind is LayerKind.IMAGE and layer.asset_id not in images:
            raise ValueError("图层素材没有出现在冻结资产引用中")


@dataclass
class Computation:
    request: ComputeRequest
    images: dict[uuid.UUID, bytes]
    progress: Progress | None = None
    artifacts: list[Artifact] = field(default_factory=list)
    used_bytes: int = 0
    sam_provenance: dict | None = None
    enhancement_provenance: dict | None = None

    @property
    def document(self) -> LayerDocument:
        if self.request.document is None:
            raise ValueError("此计算需要冻结画布文档")
        return self.request.document

    @property
    def revision(self) -> int:
        return self.request.input_revision if self.request.input_revision is not None else self.request.input_version

    async def report(self, amount: int, stage: str, details: dict | None = None) -> None:
        if self.progress:
            await self.progress(amount, stage, details)

    async def provider_progress(self, amount: int, stage: str) -> None:
        await self.report(amount, stage)

    async def upscale(self, data: bytes, scale: int) -> bytes:
        from aipicture_runtime.edits.super_resolution import upscale
        output, provenance = await upscale(data, scale, self.provider_progress)
        self.enhancement_provenance = provenance
        return output

    def source(self, asset_id: str | uuid.UUID) -> bytes:
        key = uuid.UUID(str(asset_id))
        try:
            return self.images[key]
        except KeyError as exc:
            raise ValueError("素材不属于本次冻结引用") from exc

    def store(self, data: bytes, kind: str = "generated", *, archive: bool = False, source: str = "tool") -> uuid.UUID:
        settings = get_settings()
        self.used_bytes += len(data)
        if self.used_bytes > settings.max_output_bytes or len(self.artifacts) >= settings.max_outputs:
            raise ValueError("计算输出超过资源预算")
        info = {"sha256": hashlib.sha256(data).hexdigest(), "width": 0, "height": 0, "format": "ZIP",
                "size_bytes": len(data), "has_alpha": False} if archive else probe(data)
        # 同一run/attempt的相同字节得到稳定临时ID，Java决定正式存储/重复回执。
        identifier = uuid.uuid5(uuid.NAMESPACE_URL, f"aipicture:{self.request.run_id}:{self.request.attempt}:{len(self.artifacts)}:{info['sha256']}")
        self.artifacts.append(Artifact(id=identifier, kind=kind, source=source,
            content_base64=base64.b64encode(data).decode(), **info))
        return identifier

    def flattened(self, *, include_text: bool = True, transparent: bool = False) -> bytes:
        return flatten(self.document, self.images, include_text=include_text,
                       background=TRANSPARENT if transparent else WHITE)

    def layer_bytes(self, layer: Layer) -> bytes:
        if layer.kind is not LayerKind.IMAGE or layer.asset_id is None:
            raise ValueError("只能处理图像图层")
        # 图层width/height是布局框；工具沿用源项目处理原素材，避免零调整也丢失原始像素。
        return self.source(layer.asset_id)

    def selection(self) -> bytes | None:
        identifier = self.request.params.get("mask_asset_id") or self.request.selection_asset_id
        if not identifier:
            return None
        if self.request.params.get("revision") not in (None, self.revision):
            raise ValueError("选区已过期，请重新选择")
        raw = self.source(identifier)
        if Image.open(io.BytesIO(raw)).size != (self.document.width, self.document.height):
            raise ValueError("画幅已变，选区失效，请重新选择")
        if to_luma(raw).getbbox() is None:
            raise EmptyCut("选区为空，请重新选择")
        return raw

    def replace_layer(self, layer: Layer, data: bytes, kind: str) -> dict:
        next_doc = self.document.model_copy(deep=True)
        identifier = self.store(data, kind)
        resolve_layer(next_doc, layer.id).asset_id = identifier
        result = {"document": next_doc.model_dump(mode="json"), "asset_ids": [str(identifier)]}
        if sum(1 for item in next_doc.layers if item.kind is LayerKind.IMAGE) <= 1:
            result["adopt_asset_id"] = str(identifier)
        return result

    def wall(self, images: list[bytes], *, adopt: bool = False, kind: str = "generated", variants: list[dict] | None = None, source: str = "tool") -> dict:
        identifiers = [str(self.store(data, kind, source=source)) for data in images]
        result = {"asset_ids": identifiers}
        if adopt and identifiers:
            result["adopt_asset_id"] = identifiers[0]
        if variants is not None:
            result["variants"] = [{**variant, "asset_id": identifier} for variant, identifier in zip(variants, identifiers, strict=True)]
        return result

    async def fill_hole(self, source: bytes, mask: bytes) -> bytes:
        hole = expand_mask(mask, Image.open(io.BytesIO(source)).size)
        prepared = await asyncio.to_thread(fill_background, source, hole)
        # 本地预修可能耗时；提交下一收费步骤前重新通过中心心跳边界。
        await self.report(60, "重修背景")
        edited = await get_image_provider().edit(EditRequest(prompt=_RECONSTRUCT, image=prepared), self.provider_progress)
        if not edited:
            raise ValueError("修补模型未返回图片")
        return apply_masked(prepared, edited[0], hole)

    async def split(self, values: dict) -> dict:
        if already_split(self.document):
            return {"document": self.document.model_dump(mode="json")}
        source = self.flattened()
        await self.report(35, "分离主体")
        subject = await asyncio.to_thread(remove_background, source)
        mask = alpha_mask(subject)
        await self.report(55, "修复背景")
        background = await self.fill_hole(source, mask)
        texts = []
        if values.get("include_text"):
            await self.report(80, "识别文字")
            texts = await asyncio.to_thread(detect_text, source)
            if texts:
                subject, background = await asyncio.to_thread(remove_text, subject, background, texts,
                                                               (self.document.width, self.document.height))
        subject_id = self.store(subject, "subject")
        background_id = self.store(background, "background")
        document = split_document(self.document, background_id=background_id, subject_id=subject_id,
                                  texts=texts, subject_hash=mask_hash(mask))
        return {"document": document.model_dump(mode="json"), "asset_ids": [str(background_id), str(subject_id)], "selection_clear": True}

    async def promote(self, values: dict) -> dict:
        mask = self.selection()
        if mask is None:
            raise ValueError("请先点选或涂抹要拆出的物体")
        key = mask_hash(mask)
        if already_promoted(self.document, key):
            return {"document": self.document.model_dump(mode="json")}
        source = self.flattened(include_text=False)
        cut, left, top, width, height = cut_object(source, mask)
        await self.report(45, "修复原位置")
        filled = await self.fill_hole(source, mask)
        object_id = self.store(cut, "subject")
        created = [str(object_id)]
        if already_split(self.document):
            replacements = {}
            for layer in self.document.layers:
                if layer.kind is not LayerKind.IMAGE or layer.asset_id is None:
                    continue
                data = self.layer_bytes(layer)
                try:
                    local_mask = mask_for_layer(mask, layer, (self.document.width, self.document.height), data)
                except EmptyCut:
                    continue
                if layer.id == BACKGROUND_LAYER_ID:
                    buffer = io.BytesIO()
                    canvas_to_local(filled, layer, output_size=Image.open(io.BytesIO(data)).size).save(buffer, "PNG")
                    replacement = apply_masked(data, buffer.getvalue(), local_mask)
                    kind = "background"
                else:
                    image = Image.open(io.BytesIO(data)).convert("RGBA")
                    image.putalpha(ImageChops.multiply(image.getchannel("A"), ImageChops.invert(to_luma(local_mask))))
                    buffer = io.BytesIO()
                    image.save(buffer, "PNG")
                    replacement, kind = buffer.getvalue(), "subject"
                next_id = self.store(replacement, kind)
                replacements[layer.id] = next_id
                created.append(str(next_id))
            next_doc = promote_document(self.document, asset_id=object_id, x=left, y=top, width=width, height=height,
                                        source_hash=key, replacements=replacements)
        else:
            background_id = self.store(filled, "background")
            created.append(str(background_id))
            # 保留已有文字层；像素输入跳过文字，不能因提升物体丢掉文字文档。
            next_doc = as_background_and_object(self.document, background_id=background_id, object_id=object_id,
                x=left, y=top, width=width, height=height, source_hash=key)
            next_doc.layers.extend(layer.model_copy(deep=True) for layer in self.document.layers if layer.kind is LayerKind.TEXT)
        if values.get("name"):
            next_doc.layers[-1 if next_doc.layers[-1].kind is LayerKind.IMAGE else 1].name = values["name"]
        return {"document": next_doc.model_dump(mode="json"), "asset_ids": created, "selection_clear": True}

    async def batch_op(self, data: bytes, operation: BatchOpIn) -> list[bytes]:
        await self.report(20, f"执行批量操作：{operation.tool}")
        values = validate(operation.tool, operation.params)
        if operation.tool == "remove_background":
            return [await asyncio.to_thread(remove_background, data)]
        if operation.tool == "adjust_image":
            values.pop("layer_id", None)
            return [await asyncio.to_thread(adjust, data, **values)]
        if operation.tool == "upscale_image":
            return [await self.upscale(data, values["scale"])]
        if operation.tool == "replace_background":
            return await get_image_provider().edit(EditRequest(prompt=f"只替换背景，保持主体、光线和边缘不变。新背景：{values['prompt']}",
                image=data, count=1, negative_prompt=values.get("negative_prompt")), self.provider_progress)
        if operation.tool == "expand_canvas":
            image = Image.open(io.BytesIO(data))
            width, height = cover_size(image.width, image.height, Ratio(values["ratio"]))
            return await get_image_provider().edit(EditRequest(prompt=values["prompt"], image=data, width=width, height=height), self.provider_progress)
        if operation.tool == "prepare_delivery_sizes":
            return [letterbox(data, *size_of(Ratio(ratio))) for ratio in dict.fromkeys(values["ratios"])]
        raise ValueError("此操作不允许批量处理")

    async def batch(self, values: dict) -> dict:
        items = []
        for index, source_id in enumerate(values["asset_ids"]):
            item = {"source_id": source_id, "status": "running", "output_ids": [], "error": None}
            items.append(item)
            await self.report(10 + int(80 * index / len(values["asset_ids"])), f"处理第 {index + 1} / {len(values['asset_ids'])} 张", {"items": items})
            start_artifacts, start_bytes = len(self.artifacts), self.used_bytes
            try:
                frames = [self.source(source_id)]
                for raw_op in values["operations"]:
                    next_frames = []
                    for data in frames:
                        next_frames.extend(await self.batch_op(data, BatchOpIn.model_validate(raw_op)))
                        if len(next_frames) > 25:
                            raise ValueError("单图流水线分支超过 25 项")
                    frames = next_frames
                for data in frames:
                    for fmt in dict.fromkeys(values["formats"]):
                        item["output_ids"].append(str(self.store(encode(data, fmt), "export")))
                item["status"] = "succeeded"
            except Exception as exc:
                from aipicture_runtime.execution import ExecutionCancelled, ExecutionLeaseLost
                from aipicture_runtime.providers.base import ProviderOutcomeUnknown
                import httpx
                if isinstance(exc, (ExecutionCancelled, ExecutionLeaseLost, ProviderOutcomeUnknown, httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError)):
                    raise
                del self.artifacts[start_artifacts:]
                self.used_bytes = start_bytes
                item.update(status="failed", error=str(exc) or "处理失败", output_ids=[])
        succeeded = sum(item["status"] == "succeeded" for item in items)
        status = "succeeded" if succeeded == len(items) else "partial_success" if succeeded else "failed"
        return {"items": items, "summary": {"total": len(items), "succeeded": succeeded, "failed": len(items) - succeeded, "status": status},
                "asset_ids": [value for item in items if item["status"] == "succeeded" for value in item["output_ids"]]}

    async def execute(self) -> dict:
        name, raw = self.request.tool, self.request.params
        if name in {"export", "evaluation", "selection", "prepare_selection", "inspect_image"}:
            return await self.control(name, raw)
        spec = spec_of(name)
        server_fields = {key: raw[key] for key in ("revision", "mask_asset_id") if key in raw}
        values = validate(name, {key: value for key, value in raw.items() if key not in server_fields})
        # 模型默认值与Java校验一致，server-only选区上下文仍以冻结请求为准。
        self.request.params = {**values, **server_fields}
        if spec.session_required and self.request.document is None:
            raise ValueError("此工具需要冻结编辑会话文档")
        await self.report(15, "读取冻结输入")
        if name == "generate_image":
            width, height = size_of(Ratio(values["ratio"]))
            request = GenerateRequest(prompt=values["prompt"], count=values["count"], width=width, height=height,
                seed=values["seed"], negative_prompt=values["negative_prompt"], references=[self.source(key) for key in values["reference_asset_ids"]])
            provider = get_image_provider()
            if self.request.provider_task_id:
                images = await provider.resume_generate(self.request.provider_task_id, self.provider_progress)
            else:
                images = await provider.generate(request, self.provider_progress)
            return self.wall(images, source="generate")
        if name in {"remove_background", "adjust_image"}:
            layer = resolve_layer(self.document, values.get("layer_id"))
            data = self.layer_bytes(layer)
            if name == "remove_background":
                output = await asyncio.to_thread(remove_background, data)
            else:
                kwargs = {key: value for key, value in values.items() if key != "layer_id"}
                output = await asyncio.to_thread(adjust, data, **kwargs)
            return self.replace_layer(layer, output, "subject" if name == "remove_background" else "generated")
        if name in {"replace_background", "expand_canvas", "upscale_image"}:
            layered = already_split(self.document) or sum(layer.kind is LayerKind.IMAGE for layer in self.document.layers) > 1
            if name == "replace_background":
                target = next((layer for layer in self.document.layers if layer.id == BACKGROUND_LAYER_ID and layer.kind is LayerKind.IMAGE), None) or resolve_layer(self.document, None)
                source = self.flattened() if layered or target.id != BACKGROUND_LAYER_ID else self.layer_bytes(target)
                images = await get_image_provider().edit(EditRequest(prompt=f"只替换背景，保持主体、光线和边缘不变。新背景：{values['prompt']}", image=source,
                    count=values["count"], negative_prompt=values.get("negative_prompt")), self.provider_progress)
                if values["count"] > 1 or layered:
                    return self.wall(images)
                return self.replace_layer(target, images[0], "background" if target.id == BACKGROUND_LAYER_ID else "generated")
            if name == "expand_canvas":
                width, height = cover_size(self.document.width, self.document.height, Ratio(values["ratio"]))
                images = await get_image_provider().edit(EditRequest(prompt=values["prompt"], image=self.flattened(), width=width, height=height), self.provider_progress)
            else:
                images = [await self.upscale(self.flattened(transparent=get_settings().enhancement_provider == "onnx"), values["scale"])]
            return self.wall(images, adopt=not layered)
        if name in {"erase_region", "replace_region"}:
            mask = self.selection()
            layer = layer_under_mask(self.document, mask, self.images) if not values.get("layer_id") and mask else resolve_layer(self.document, values.get("layer_id"))
            source = self.layer_bytes(layer)
            local = mask_for_layer(mask, layer, (self.document.width, self.document.height), source) if mask else alpha_mask(source)
            prompt = values.get("prompt") or "移除物体，用周围背景自然填补。"
            if name == "replace_region":
                prompt = f"只改选中区域：{prompt}。选区外的主体、光线和背景必须保持原样。" if mask else f"{prompt}。保持构图、透视与画幅不变，透明区域仍然透明。"
            images = await get_image_provider().edit(EditRequest(prompt=prompt, image=source), self.provider_progress)
            return {**self.replace_layer(layer, apply_masked(source, images[0], local), "generated"), "selection_clear": True}
        if name == "split_layers":
            return await self.split(values)
        if name == "promote_object_to_layer":
            return await self.promote(values)
        if name == "generate_marketing":
            width, height = size_of(Ratio(values["ratio"]))
            caption = (values.get("caption") or "").strip()
            prompt = _MARKETING[values["kind"]] + (f" 文案：{caption}" if caption else "")
            images = await get_image_provider().edit(EditRequest(prompt=prompt, image=self.flattened(), count=values["count"], width=width, height=height), self.provider_progress)
            fitted = [resize_to(data, width, height) for data in images]
            return self.wall(fitted, kind="marketing", variants=[{"kind": values["kind"], "ratio": values["ratio"], "width": width, "height": height}] * len(fitted))
        if name == "prepare_delivery_sizes":
            source = self.flattened()
            variants, images = [], []
            for ratio in dict.fromkeys(values["ratios"]):
                width, height = size_of(Ratio(ratio))
                images.append(letterbox(source, width, height))
                variants.append({"kind": "export", "ratio": ratio, "width": width, "height": height})
            return self.wall(images, kind="export", variants=variants)
        if name == "batch_process":
            return await self.batch(values)
        # 原九种文档算法保留为共享协议校验/离线对照，正式文档命令由 Java 执行。
        layer = values.pop("layer_id", None)
        if name == "crop_canvas":
            rect = values.get("rect")
            output = doc_edits.crop(self.document, ratio=Ratio(values["ratio"]) if values.get("ratio") else None,
                rect=(rect["x"], rect["y"], rect["width"], rect["height"]) if rect else None)
        else:
            function = {"flip_layer": doc_edits.flip, "set_layer_opacity": doc_edits.set_opacity,
                        "set_layer_visible": doc_edits.set_visible, "set_layer_text": doc_edits.set_text,
                        "reorder_layer": doc_edits.reorder, "scale_layer": doc_edits.scale,
                        "rotate_layer": doc_edits.rotate, "move_layer": doc_edits.move}[name]
            if name == "set_layer_text":
                output = function(self.document, layer, values.pop("text"), **values)
            else:
                output = function(self.document, layer, **values)
        validate_document(output, self.images)
        return {"document": output.model_dump(mode="json")}

    async def control(self, name: str, values: dict) -> dict:
        if name == "inspect_image":
            content = values.get("content_base64", "")
            if len(content) > 28 * 1024 * 1024:
                raise ValueError("素材超过大小限制")
            info = probe(base64.b64decode(content, validate=True))
            minimum = values.get("minimum_side", 32)
            if isinstance(minimum, bool) or minimum not in (1, 32):
                raise ValueError("内部检查最短边只允许 1/32 像素")
            if min(info["width"], info["height"]) < minimum:
                raise ValueError(f"图片最短边不得小于 {minimum} 像素")
            return info
        if name in {"selection", "prepare_selection"}:
            if values.get("revision") not in (None, self.revision):
                raise ValueError("选区已过期，请重新选择")
            if name == "prepare_selection":
                await asyncio.to_thread(warm_embedding, self.flattened())
                return {"prepared": True, "revision": self.revision}
            points = values.get("points") or []
            strokes = values.get("strokes") or []
            # Java 从正式选区冻结既有点标记；模型/客户端不能自行指定这些服务字段。
            previous = values.get("selection_markers") or []
            if not isinstance(previous, list) or len(previous) > 100:
                raise ValueError("冻结选区标记超过限制")
            if points and values.get("append"):
                points = [*previous, *points]
            radius = values.get("radius", 0.03)
            if not 0.005 <= radius <= 0.12 or len(points) > 100 or len(strokes) > 1000:
                raise ValueError("选区笔刷或点数超过限制")
            coords = [(point["x"], point["y"]) if isinstance(point, dict) else tuple(point) for point in points]
            traces = [[(point["x"], point["y"]) if isinstance(point, dict) else tuple(point) for point in (stroke.get("points", []) if isinstance(stroke, dict) else stroke)] for stroke in strokes]
            if sum(len(trace) for trace in traces) > 20_000:
                raise ValueError("笔刷坐标超过限制")
            if any(len(point) != 2 or not all(isinstance(value, (int, float)) and math.isfinite(value) and 0 <= value <= 1 for value in point) for point in [*coords, *(point for trace in traces for point in trace)]):
                raise ValueError("归一化坐标须在 [0,1]")
            if not coords and not any(traces):
                raise EmptyCut("选区为空")
            size = (self.document.width, self.document.height)
            # 原点选用全部正点重新分割；原涂抹不带 append，始终在当前遮罩上累加。
            base = self.selection() if not coords else None
            luma = to_luma(base, size) if base else None
            if coords:
                selected = to_luma(await asyncio.to_thread(segment_points, self.flattened(), coords), size)
                luma = selected
                # 只在本次点选推理成功后记来源；缓存/文件存在、prepare与笔刷不能代替本次推理。
                from aipicture_runtime.edits import segment
                session = segment._sam
                if not get_settings().offline_test and session is not None and getattr(session, "encoder", None) is not None and getattr(session, "decoder", None) is not None:
                    self.sam_provenance = {"provider": "onnxruntime", "model": "sam", "loaded": True, "inference": "points"}
            # 原接口采用 if points / elif strokes；混合字段已校验，但不能扩大点选作用区。
            elif traces:
                luma = rasterize_strokes(size, traces, radius=radius, base=luma)
            identifier = self.store(overlay_png(luma), "mask")
            return {"mask_asset_id": str(identifier), "asset_ids": [str(identifier)], "revision": self.revision,
                    "markers": [{"index": index + 1, "x": x, "y": y} for index, (x, y) in enumerate(coords)] if coords else previous}
        if name == "evaluation":
            from aipicture_runtime.eval.cases import Case
            from aipicture_runtime.eval.runner import _score_output
            from aipicture_runtime.eval.tasks import run_task
            manifest = values.get("manifest") or []
            if not isinstance(manifest, list) or not 1 <= len(manifest) <= 100:
                raise ValueError("评测清单须为 1–100 项")
            results = []
            for index, raw in enumerate(manifest):
                await self.report(20 + int(70 * index / len(manifest)), "评估冻结素材与已有产物")
                if raw.get("task") == "assess_result":
                    from aipicture_runtime.eval.assessment import assess_result
                    results.append(await asyncio.to_thread(assess_result, raw, self.images))
                    continue
                item = {"id": raw.get("id"), "task": raw.get("task"), "outputs": [], "error": None, "passed": False}
                results.append(item)
                try:
                    source = self.source(raw["input_asset_id"])
                    expect = self.source(raw["expect_asset_id"]) if raw.get("expect_asset_id") else None
                    case = Case(id=raw["id"], task=raw["task"], input="frozen", params=raw.get("params") or {},
                        metrics=raw.get("metrics") or ["size"], thresholds=raw.get("thresholds") or {})
                    produced = await asyncio.to_thread(run_task, case.task, source, case.params)
                    for label, data in produced:
                        score = await asyncio.to_thread(_score_output, case, label, data, source, expect)
                        identifier = self.store(data, "export")
                        item["outputs"].append({"label": label, "metrics": score.values, "passed": score.ok, "asset_id": str(identifier)})
                    item["passed"] = all(result["passed"] for result in item["outputs"])
                except Exception as exc:
                    item["error"] = str(exc)
            metrics = {"passed": sum(item["passed"] for item in results), "total": len(results)}
            if any(item.get("task") == "assess_result" for item in results):
                metrics.update(technical_passed=sum(item.get("technical_passed", item["passed"]) for item in results),
                    real_model_verified=sum(item.get("real_model_verified", False) for item in results))
            return {"cases": results, "metrics": metrics,
                    "dataset_hash": hashlib.sha256(json.dumps(manifest, sort_keys=True, ensure_ascii=False).encode()).hexdigest()}
        if name == "export":
            fmt = values.get("format", "png").lower()
            background = values.get("background_mode", self.request.background_mode)
            if background not in {"white", "transparent"} or fmt not in {"png", "zip"}:
                raise ValueError("导出格式/背景模式无效")
            if fmt == "png":
                output = self.flattened(transparent=background == "transparent")
                return {"asset_ids": [str(self.store(output, "export"))], "background_mode": background,
                        "filename": f"{_safe_export_title(values.get('title', '物料'))}.png"}
            identifiers = values.get("asset_ids") or []
            if len(identifiers) > 200:
                raise ValueError("导出清单超过限制")
            files = []
            current = values.get("current_asset_id")
            refs = {str(ref.id): ref for ref in self.request.assets}
            for index, identifier in enumerate(identifiers):
                data = self.flattened(transparent=background == "transparent") if current is not None and str(identifier) == str(current) else self.source(identifier)
                ext = {"PNG": "png", "JPEG": "jpg", "WEBP": "webp"}[probe(data)["format"]]
                files.append((f"images/{index + 1:03d}-{identifier}.{ext}", data, str(identifier)))
            if not files:
                files = [("images/canvas.png", self.flattened(transparent=background == "transparent"), None)]
            entries = []
            for filename, data, identifier in files:
                meta = probe(data)
                ratio = ratio_of(meta["width"], meta["height"])
                flattened = identifier is None or current is not None and str(identifier) == str(current)
                entries.append({"name": filename, "file": filename, "asset_id": identifier,
                    "kind": "canvas" if flattened else refs[str(identifier)].kind or "generated",
                    "ratio": ratio.value if ratio else None, **meta})
            created_at = datetime.now(UTC)
            manifest = {"schema_version": SCHEMA_VERSION, "run_id": self.request.run_id,
                        "title": values.get("title", "物料"), "session_id": self.request.session_id,
                        "created_at": created_at.isoformat(),
                        "background_mode": background, "files": entries, "items": entries}
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
                for filename, data, identifier in files:
                    archive.writestr(filename, data)
                archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
            return {"asset_ids": [str(self.store(buffer.getvalue(), "export", archive=True))], "manifest": manifest,
                    "filename": f"{_safe_export_title(manifest['title'])}-{created_at:%Y%m%d}.zip"}
        raise ValueError("未知内部计算任务")


async def compute(request: ComputeRequest, progress: Progress | None = None) -> dict:
    request.asset_budget()  # 可变 DTO 的本地调用也必须遵守 HTTP 冻结输入边界。
    settings = get_settings()
    if len({asset.id for asset in request.assets}) != len(request.assets):
        raise ValueError("冻结资产引用不能重复")
    images, total = {}, 0
    for asset in request.assets:
        data = await read_asset(asset, request)
        total += len(data)
        if total > settings.max_output_bytes:
            raise ValueError("冻结输入总大小超过资源预算")
        images[asset.id] = data
    if request.document is not None:
        validate_document(request.document, images)
    context = Computation(request, images, progress)
    from aipicture_runtime.reporting import progress_callback
    token = progress_callback.set(progress)
    try:
        result = await context.execute()
    finally:
        progress_callback.reset(token)
    provider = {"image": settings.image_provider, "matting": settings.matting_provider, "ocr": settings.ocr_provider,
                "offline_test": settings.offline_test, "enhancement_implementation": settings.enhancement_provider, "true_super_resolution": False}
    if context.enhancement_provenance is not None:
        provider.update(context.enhancement_provenance)
    if context.sam_provenance is not None:
        provider["sam"] = context.sam_provenance
    return {"schema_version": SCHEMA_VERSION, "tool_registry_version": TOOL_REGISTRY_VERSION,
            "run_id": request.run_id, "attempt": request.attempt, "result": result,
            "artifacts": [artifact.model_dump(mode="json") for artifact in context.artifacts],
            "provider": provider}
