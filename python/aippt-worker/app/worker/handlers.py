"""七类任务的算法适配。仅返回候选/报告，绝不写正式业务状态。"""
from __future__ import annotations
import hashlib
import uuid
from typing import Any
from app.core.config import get_settings
from app.domain.content import Deck, Slide, ImageBlock
from app.domain.content_density import normalize_density, normalize_page_role
from app.domain.export_check import run_export_check
from app.domain.page_rhythm import allows_callout, skeleton_hint
from app.domain.slide_geometry import resolve_slide_geometry, placed_by_block_id
from app.domain.theme import resolve_theme
from app.domain.validation import has_blocking_issue
from app.services.media import bind_assets, unbind_assets, media_key_from_url, load_image
from app.worker.parse import parse_document, stable_sections
from app.worker.protocol import ClaimedTask, CenterClient, ExportVerificationFailed
from app.worker.security import SafeAsyncClient, assert_payload_budget


def _sections(sources: list[dict]):
    from app.llm.base import OutlineSourceSection
    result = []
    for source_index, source in enumerate(sources, 1):
        for index, raw in enumerate(source.get("sections", []), 1):
            result.append(OutlineSourceSection(ref=str(raw.get("ref") or raw.get("id") or f"S{source_index}:{index}"),
                         heading=raw.get("heading"), level=raw.get("level", 0), text=raw.get("text", ""), locator=raw.get("locator", "")))
    return result


def _theme(project: dict):
    return resolve_theme(project.get("theme_id", "ivory"), project.get("theme_overrides") or {})


def _manifest(upload: dict, data: bytes, mime: str, role: str) -> dict:
    return {"uploadId": str(upload.get("uploadId") or upload.get("asset_id") or upload.get("id")),
            "mime": mime, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data), "role": role}


def _geometry(slide: Slide) -> list[dict]:
    blocks = {b.id: b for b in slide.blocks}
    return [{"block_id": p.block_id, "slot_id": blocks[p.block_id].slot_id, "rect": p.rect.model_dump()} for p in resolve_slide_geometry(slide)]


class TaskHandlers:
    def __init__(self, center: CenterClient):
        self.center = center
        self.generators: dict[str, Any] = {}

    def generator(self, kind: str):
        if kind not in self.generators:
            from app.llm.client import create_chat_model
            from app.llm.deepseek import DeepSeekOutlineGenerator
            from app.llm.slide import DeepSeekSlideGenerator
            from app.llm.slide_edit import DeepSeekSlideEditGenerator
            from app.llm.relayout import DeepSeekRelayoutGenerator
            cfg = get_settings()
            classes = {"outline": DeepSeekOutlineGenerator, "slide": DeepSeekSlideGenerator,
                       "edit": DeepSeekSlideEditGenerator, "relayout": DeepSeekRelayoutGenerator}
            self.generators[kind] = classes[kind](model=create_chat_model(cfg), api_key=cfg.llm_api_key)
        return self.generators[kind]

    async def run(self, task: ClaimedTask) -> tuple[dict, list[dict]]:
        from app.worker.execution import begin_execution,end_execution
        stats,token=begin_execution(get_settings(),task.type)
        font_token = None
        font_identity = {}
        try:
            assert_payload_budget(task.payload)
            from app.worker.fonts import FONT_TASKS, bind_task_fonts
            if task.type in FONT_TASKS:
                font_identity, font_token = bind_task_fonts(task.payload)
            output,assets=await getattr(self, task.type)(task)
        finally:
            if font_token is not None:
                from app.domain.text_metrics import unbind_font_faces
                unbind_font_faces(font_token)
            task._execution=stats.summary()
            end_execution(token)
        return {**output,**font_identity,"execution":task._execution},assets

    async def parse_source(self, task):
        source = task.payload["source"]
        source_id = str(source["id"])
        if source["kind"] in {"topic", "text"}:
            from app.ingest.plain import PlainTextParser
            text = source.get("text", source.get("content", ""))
            if not text or len(text) > 200_000: raise ValueError("输入文字为空或超过200000字")
            output = stable_sections(PlainTextParser().parse(text.encode()).model_dump(mode="json"), source_id)
        else:
            data = await self.center.read_asset(task, str(source["asset_id"]), get_settings().max_upload_bytes)
            output = await parse_document(source["filename"], data, source_id)
        return output, []

    async def generate_outline(self, task):
        from app.llm.base import OutlineGenerationInput
        from app.domain.outline import OutlinePage
        from app.workflows.outline import build_outline_workflow, run_outline_workflow, prepare_outline_input
        project = task.payload["project"]
        payload = OutlineGenerationInput(title=project["title"], audience=project.get("audience"), tone=project.get("tone", "professional"),
                                         page_count=project.get("page_count", 10), content_density=normalize_density(project.get("content_density")),
                                         sections=_sections(task.payload.get("sources", [])))
        prepared = prepare_outline_input(payload)
        if not any(section.text.strip() for section in prepared.sections):
            raise ValueError("来源材料没有可用文字，请补充文本")
        draft = await run_outline_workflow(build_outline_workflow(self.generator("outline")), payload)
        allowed = {s.ref for s in prepared.sections}
        if len(draft.pages) != payload.page_count or any(set(p.source_refs) - allowed for p in draft.pages):
            raise ValueError("模型提纲页数或来源引用不符合输入")
        pages = [OutlinePage(**page.model_dump()).model_dump(mode="json") for page in draft.pages]
        used = sum(len(s.text) for s in prepared.sections); original = sum(len(s.text) for s in payload.sections)
        return {"pages": pages, "warnings": [f"来源模型输入按预算裁剪：{original}→{used}字"] if used < original else [],
                "source_budget": {"original_chars": original, "used_chars": used}}, []

    async def generate_slide(self, task):
        from app.llm.base import SlideGenerationInput
        from app.workflows.slide import build_slide_workflow, run_slide_workflow
        project = task.payload["project"]; page = task.payload["outline_page"]
        sections = {s.ref: s for s in _sections(task.payload.get("sources", []))}
        if set(page.get("source_refs", [])) - set(sections):
            raise ValueError("单页提纲引用了不存在的来源小节")
        role = normalize_page_role(page.get("page_role")); visual = page.get("visual")
        position = task.payload.get("slide", {}).get("position", page.get("position", 1))
        payload = SlideGenerationInput(deck_title=project["title"], audience=project.get("audience"), tone=project.get("tone", "professional"),
                  position=position, total_pages=project.get("page_count", len(task.payload.get("outline", {}).get("pages", [])) or 1),
                  page_title=page["title"], objective=page["objective"], key_points=page["key_points"], layout_id=page["layout_id"],
                  layout_mode=project.get("layout_mode", "flex"), content_density=normalize_density(project.get("content_density")),
                  page_role=role, sections=[sections[ref] for ref in page.get("source_refs", []) if ref in sections],
                  neighbor_titles=task.payload.get("neighbors") or [], visual_hint=visual,
                  skeleton_hint=skeleton_hint(position, page_role=role, has_visual=bool(visual)), allow_callout=allows_callout(position))
        slide_id = str(task.payload.get("slide", {}).get("id") or uuid.uuid4())
        slide, issues = await run_slide_workflow(build_slide_workflow(self.generator("slide")), payload, slide_id,
                                                theme_id=project.get("theme_id", "ivory"), theme_overrides=project.get("theme_overrides") or {})
        slide, assets = await self._resolve_images(task, slide, project["title"], page["title"])
        return {"slide": {**slide.model_dump(mode="json"), "title": page["title"]},
                "issues": [i.model_dump(mode="json") for i in issues], "geometry": _geometry(slide)}, assets

    async def _resolve_images(self, task, slide, deck_title, page_title):
        cfg = get_settings()
        # 本机与中心均须显式启用；凭据存在不代表已获外部图源调用许可。
        if not cfg.image_enabled or task.payload.get("image_enabled") is not True:
            return slide, []
        from app.images.pipeline import create_image_pipeline
        from app.images.base import ImageRequest
        from app.images.validate import validate_image
        assets = []; blocks = []; placements = placed_by_block_id(slide)
        async with SafeAsyncClient() as client:
            pipeline = create_image_pipeline(client)
            for block in slide.blocks:
                if not isinstance(block, ImageBlock) or block.url or block.locked:
                    blocks.append(block); continue
                try:
                    placed = placements.get(block.id)
                    if placed is None: blocks.append(block); continue
                    rect = placed.rect; aspect = (rect.w * 960) / max(1, rect.h * 540)
                    image = await pipeline.fetch(ImageRequest(prompt=f"{block.alt}。用作主题为「{deck_title}」的商务演示页面「{page_title}」配图，留白充足且不要文字。", query=block.alt, aspect_ratio=aspect))
                    if image is None: blocks.append(block); continue
                    extension, mime = validate_image(image.data)
                    upload = await self.center.upload_asset(task, image.data, f"{block.id}{extension}", mime, "image")
                    assets.append(_manifest(upload, image.data, mime, "image"))
                    asset_id = str(upload.get("asset_id") or upload.get("id") or upload.get("uploadId"))
                    blocks.append(block.model_copy(update={"url": upload.get("url") or f"/aippt/assets/{asset_id}", "source": image.source, "credit": image.credit}))
                except Exception:
                    # 外部图源/坏图仅降级；租约失效必须终止，不能继续有费用的工作。
                    from app.worker.protocol import LeaseLost
                    import sys
                    if isinstance(sys.exception(), LeaseLost): raise
                    blocks.append(block)
        return slide.model_copy(update={"blocks": blocks}), assets

    async def ai_edit(self, task):
        from app.llm.base import SlideEditInput, AiEditHistoryTurn
        from app.llm.slide_edit import block_to_edit_input
        from app.domain.slide_patch import unlocked_editable_blocks
        from app.workflows.slide_edit import build_slide_edit_workflow
        project = task.payload["project"]; raw = task.payload["slide"]; slide = Slide.model_validate(raw)
        payload = SlideEditInput(deck_title=project["title"], audience=project.get("audience"), tone=project.get("tone", "professional"),
                   page_title=raw.get("title", project["title"]), layout_id=slide.layout_id, layout_mode=slide.layout_mode,
                   instruction=task.payload["instruction"], history=[AiEditHistoryTurn.model_validate(h) for h in (task.payload.get("history") or [])],
                   blocks=[block_to_edit_input(b) for b in unlocked_editable_blocks(slide.blocks)], original_blocks=slide.blocks, layout_tree=slide.layout_tree)
        result = await build_slide_edit_workflow(self.generator("edit")).ainvoke({"input": payload, "slide_id": slide.id,
                     "layout_id": slide.layout_id, "layout_mode": slide.layout_mode, "layout_tree": slide.layout_tree,
                     "theme_id": project.get("theme_id", "ivory"), "theme_overrides": project.get("theme_overrides") or {},
                     "original_blocks": slide.blocks, "repairs": 0})
        issues = list(result.get("issues") or [])
        if has_blocking_issue(issues): raise ValueError("AI候选一轮修复后仍存在结构错误")
        patched = list(result.get("patched_blocks") or slide.blocks)
        by_id = {b.id: b for b in patched}
        if any(b.locked and (b.id not in by_id or by_id[b.id].model_dump() != b.model_dump()) for b in slide.blocks):
            raise ValueError("AI候选触及已锁定内容块")
        candidate = slide.model_copy(update={"blocks": patched, "layout_tree": result.get("layout_tree", slide.layout_tree)})
        return {"revision": slide.revision, "operations": [o.model_dump(mode="json") for o in result.get("operations", [])],
                "discarded": [d.model_dump(mode="json") for d in result.get("discarded", [])],
                "warnings": [i.model_dump(mode="json") for i in issues if i.severity == "warning"],
                "candidate_slide": candidate.model_dump(mode="json")}, []

    async def relayout(self, task):
        from app.llm.relayout import build_relayout_candidates
        from app.llm.errors import InvalidSlideOutputError
        raw = task.payload["slide"]; slide = Slide.model_validate(raw)
        if slide.layout_mode != "flex" or slide.layout_tree is None: raise ValueError("换排布需要合法灵活布局")
        from app.domain.flex_layout import clear_rect_overrides
        current_tree = clear_rect_overrides(slide.layout_tree)
        try:
            trees = await self.generator("relayout").propose(blocks=raw["blocks"], current_tree=current_tree, page_title=raw.get("title", ""), count=2)
        except InvalidSlideOutputError:
            trees = []
        trees = [clear_rect_overrides(tree) for tree in trees]
        candidates = build_relayout_candidates(llm_trees=trees, blocks=raw["blocks"], current_tree=current_tree, limit=3)
        return {"revision": slide.revision, "candidates": [{"id": id_, "layout_tree": tree.model_dump(mode="json")} for id_, tree in candidates]}, []

    async def _load_deck(self, task):
        from app.images.validate import validate_image, ImageRejected
        snapshot = task.payload["snapshot"]; project = snapshot["project"]
        deck = Deck(id=str(project["id"]), title=project["title"], theme_id=project.get("theme_id", "ivory"), slides=snapshot["slides"])
        assets = {}
        for slide in deck.slides:
            for block in slide.blocks:
                if not isinstance(block, ImageBlock) or not block.url: continue
                asset_id = media_key_from_url(block.url)
                if asset_id is None: raise ValueError("页面包含未登记中心私有图片")
                if asset_id not in assets:
                    try:
                        assets[asset_id] = await self.center.read_asset(task, asset_id, get_settings().max_image_bytes)
                    except FileNotFoundError:
                        continue  # 质量算法产出缺失资源 error，导出仍被门禁阻断
                    except ImageRejected as error:
                        assets[asset_id] = error
                        continue  # 中心已判定哈希损坏，按引用页/槽位形成阻断报告
                    try: validate_image(assets[asset_id])
                    except ImageRejected as error:
                        # 已读取的坏字节保留为阻断资源事实，质量报告在每个引用页/块定位。
                        assets[asset_id] = error
        return deck, project, snapshot, assets

    def _quality(self, deck, project, snapshot):
        return run_export_check(deck, theme=_theme(project), content_density=project.get("content_density"),
                  slide_titles={str(s["id"]): s.get("title", "") for s in snapshot["slides"]},
                  slide_sources=snapshot.get("slide_sources"), slide_roles=snapshot.get("slide_roles"),
                  load_image=load_image, media_key_from_url=media_key_from_url)

    async def quality(self, task):
        deck, project, snapshot, assets = await self._load_deck(task)
        token = bind_assets(assets)
        try:
            report = await __import__("asyncio").to_thread(self._quality, deck, project, snapshot)
            return {"issues": [i.model_dump(mode="json") for i in report.issues], "can_export": report.export_allowed,
                    "fonts_available": report.fonts_precise}, []
        finally: unbind_assets(token)

    async def export(self, task):
        from app.render.pptx import render_deck_to_pptx
        from app.render.verify import verify_pptx
        deck, project, snapshot, assets = await self._load_deck(task)
        token = bind_assets(assets)
        try:
            import asyncio
            report = await asyncio.to_thread(self._quality, deck, project, snapshot)
            if not report.export_allowed: raise ValueError("质量错误阻止导出，请先修复")
            data = (await asyncio.to_thread(render_deck_to_pptx, deck, theme=_theme(project))).getvalue()
            verify = await asyncio.to_thread(verify_pptx, data, deck)
            if not verify.passed: raise ExportVerificationFailed(verify)
            filename = "".join(c for c in deck.title if c not in '/\\\x00:*?"<>|')[:100] + ".pptx"
            mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            upload = await self.center.upload_asset(task, data, filename, mime, "export")
            return {"verify": verify.model_dump(mode="json"), "quality": report.model_dump(mode="json"),
                    "filename": filename, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}, [_manifest(upload, data, mime, "export")]
        finally: unbind_assets(token)
