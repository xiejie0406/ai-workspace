"""离线导入准备与逐项目核账；不连接源库/目标库，也不搬账号秘密。"""
from __future__ import annotations
import hashlib
import json
import uuid
import copy
from pathlib import Path
from app.domain.content import Slide
from app.domain.outline import OutlinePage
from app.domain.theme import load_themes
from app.domain.layout import load_layouts
from app.worker.catalog import catalog_version

SECRET_FIELDS = {"password", "hashed_password", "password_hash", "token", "access_token", "refresh_token", "jwt_secret", "llm_api_key", "image_api_key", "unsplash_access_key", "image_workspace_id", "cos_secret_id", "cos_secret_key", "worker_key", "api_key", "access_key", "secret", "secret_id", "secret_key"}
_SECRET_NAMES = {name.replace("_", "") for name in SECRET_FIELDS}


def _sanitize(value):
    if isinstance(value, dict): return {k: _sanitize(v) for k, v in value.items() if k.lower().replace("_", "").replace("-", "") not in _SECRET_NAMES}
    if isinstance(value, list): return [_sanitize(v) for v in value]
    return value


def _read(path): return json.loads(path.read_text(encoding="utf-8-sig"))


def _tree_structure_valid(raw: dict) -> bool:
    """核对原树JSON字段/数值，不归一化或改写原几何。"""
    import math
    from app.domain.flex_layout import FlexContainer, FlexLeaf
    from app.worker.security import assert_payload_budget
    def number(value): return isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value)
    def walk(node):
        if not isinstance(node,dict) or not isinstance(node.get("id"),str): return False
        leaf=node.get("type")=="block"
        model=FlexLeaf if leaf else FlexContainer
        if not set(node)<=set(model.model_fields):return False
        if "grow" in node and (not number(node["grow"]) or node["grow"]<0):return False
        if leaf:return isinstance(node.get("block_id"),str)
        if node.get("type") not in {"row","column"} or not isinstance(node.get("children"),list):return False
        if "gap_pt" in node and (not number(node["gap_pt"]) or node["gap_pt"]<0):return False
        ratios=node.get("ratios")
        if ratios is not None and (not isinstance(ratios,list) or len(ratios)!=len(node["children"]) or not all(number(value) and value>0 for value in ratios)):return False
        return all(walk(child) for child in node["children"])
    try:
        assert_payload_budget({"slide":{"layout_tree":raw}})
        return walk(raw)
    except (ValueError,TypeError): return False


def _field_preservation(before: dict, after: dict, transforms: dict[str, str]) -> dict:
    """独立比较原字段；预期转换仅由现有转换步骤登记，不建立第二套映射。"""
    checks = []; expected = []; missing = object()
    def digest(value):
        if value is missing: return None
        return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(",", ":"),allow_nan=False).encode()).hexdigest()
    def compare(path, old, new):
        old_hash=digest(old);new_hash=digest(new)
        record={"path":path,"source_sha256":old_hash,"prepared_sha256":new_hash,"preserved":old_hash==new_hash}
        if path in transforms:
            expected.append({**record,"reason":transforms[path]});return
        if isinstance(old,dict) and isinstance(new,dict):
            for key,value in old.items(): compare(path+"/"+key.replace("~","~0").replace("/","~1"),value,new.get(key,missing))
        elif isinstance(old,list) and isinstance(new,list):
            checks.append({"path":path+"/length","source_sha256":digest(len(old)),"prepared_sha256":digest(len(new)),"preserved":len(old)==len(new)})
            for index,value in enumerate(old):compare(path+f"/{index}",value,new[index] if index<len(new) else missing)
        else: checks.append(record)
    compare("",before,after)
    # 旧指纹移到明确的 legacy 字段，应保留其值而非只列作允许删除。
    old_outline=before.get("outline") or {};new_outline=after.get("outline") or {}
    if "input_signature" in old_outline:
        checks.append({"path":"/outline/input_signature→legacy_input_signature","source_sha256":digest(old_outline["input_signature"]),"prepared_sha256":digest(new_outline.get("legacy_input_signature",missing)),"preserved":old_outline["input_signature"]==new_outline.get("legacy_input_signature",missing)})
    return {"passed":all(item["preserved"] for item in checks),"checked_fields":len(checks),"checks":checks,"expected_transforms":expected,"manual_review_required":True}


def prepare_import(snapshot_path: Path, owners_path: Path, objects_path: Path, output_dir: Path) -> dict:
    snapshot_bytes = snapshot_path.read_bytes(); snapshot = json.loads(snapshot_bytes)
    owner_map = _read(owners_path); objects = _read(objects_path)
    records = objects if isinstance(objects, list) else objects.get("objects", [])
    object_root = objects_path.resolve().parent
    asset_map = {}; asset_failures = {}
    for item in records:
        key = str(item["key"])
        path = (object_root / item["path"]).resolve()
        if not path.is_relative_to(object_root): asset_failures[key] = "对象路径越出清单目录"; continue
        if not path.is_file(): asset_failures[key] = "源对象缺失"; continue
        data = path.read_bytes(); digest = hashlib.sha256(data).hexdigest()
        if digest != item.get("sha256") or len(data) != item.get("size"):
            asset_failures[key] = "对象体积或哈希不符"; continue
        asset_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "aippt-asset:" + key + ":" + digest))
        asset_map[key] = {"asset_id": asset_id, "source_key": key, "path": str(path), "sha256": digest,
                          "size": len(data), "mime": item.get("mime", "application/octet-stream"), "url": f"/aippt/assets/{asset_id}"}
    source_hash = hashlib.sha256(snapshot_bytes).hexdigest()
    batch = str(uuid.uuid5(uuid.NAMESPACE_URL, "aippt-import:" + source_hash))
    prepared = []; rejected = []; totals = {"projects": 0, "sources": 0, "sections": 0, "slides": 0, "blocks": 0, "assets": 0}
    for raw in snapshot.get("projects", []):
        project = _sanitize(raw); original=copy.deepcopy(project);transforms={}; reasons = []; refs = {}; referenced = set(); structure_issues = []
        owner_uuid = str(project.get("user_id", project.get("owner_id", "")))
        owner = owner_map.get(owner_uuid)
        if not isinstance(owner, int) or isinstance(owner, bool) or not 0 < owner <= 2**63 - 1: reasons.append("旧owner未映射到明确若依user_id")
        if project.get("theme_id", "ivory") not in load_themes(): reasons.append("未知主题")
        for source_index, source in enumerate(project.get("sources", []), 1):
            for section_index, section in enumerate(source.get("sections", []), 1):
                for field in ("id","ref"):
                    transforms[f"/sources/{source_index-1}/sections/{section_index-1}/{field}"]="旧小节标识改为稳定UUID，映射已在legacy_ref_map登记"
                old_ref = f"S{source_index}:{section_index}"
                section_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"aippt:{source['id']}:{section_index}"))
                refs[old_ref] = section_id; refs[str(section.get("id", section_id))] = section_id
                section["id"] = section_id; section["ref"] = section_id
            source_key = source.get("storage_key", source.get("asset_key"))
            if source_key:
                if source_key not in asset_map: reasons.append(f"来源对象缺失或不符：{source_key}")
                else:
                    transforms[f"/sources/{source_index-1}/asset_id"]="原文件由已核对哈希的中心资产引用"
                    source["asset_id"] = asset_map[source_key]["asset_id"]; referenced.add(source_key)
        outline = project.get("outline") or {}
        for page_index,page in enumerate(outline.get("pages", [])):
            try: OutlinePage.model_validate(page)
            except Exception: reasons.append("提纲字段不符合约束")
            if page.get("layout_id") not in load_layouts(): reasons.append("提纲未知版式")
            old_refs = page.get("source_refs", [])
            if any(ref.startswith("S") and ":" in ref for ref in old_refs) and snapshot.get("source_order_verified") is not True:
                reasons.append("未证明源材料顺序，不能安全改写位置引用")
            if any(ref not in refs for ref in old_refs): reasons.append("提纲引用丢失")
            transforms[f"/outline/pages/{page_index}/source_refs"]="按已有legacy_ref_map转换稳定小节引用"
            page["source_refs"] = [refs.get(ref, ref) for ref in old_refs]
        for slide_index,slide in enumerate(project.get("slides", [])):
            if slide.get("status") == "generating":
                transforms[f"/slides/{slide_index}/status"]="旧在途页转可续跑状态，原状态保留legacy_status"
                slide["legacy_status"] = "generating"; slide["status"] = "pending"
            transforms[f"/slides/{slide_index}/job_id"]="原队列job不恢复活跃"
            slide.pop("job_id", None)
            for block_index,block in enumerate(slide.get("blocks", [])):
                if block.get("type") == "image" and block.get("url"):
                    old_url = block["url"]; prefix = "/api/v1/media/"
                    key = old_url[len(prefix):] if old_url.startswith(prefix) else None
                    if not key or key not in asset_map: reasons.append("图片未登记或对象缺失")
                    else:
                        transforms[f"/slides/{slide_index}/blocks/{block_index}/url"]="根据已核对的对象hash改为中心私有资产引用"
                        block["url"] = asset_map[key]["url"]; referenced.add(key)
            parsed_slide = None
            try: parsed_slide = Slide.model_validate(slide)
            except Exception: reasons.append("页面块、布局树或字段不合法")
            if slide.get("layout_id") not in load_layouts(): reasons.append("页面未知版式")
            unfinished_stub = slide.get("status") in {"pending", "failed"} and slide.get("layout_tree") is None and slide.get("blocks") == []
            if slide.get("layout_mode") == "flex" and not slide.get("layout_tree") and not unfinished_stub:
                reasons.append("有内容或就绪灵活页缺少树")
            if parsed_slide is not None and not unfinished_stub:
                from app.domain.validation import validate_slide
                issues = validate_slide(parsed_slide, theme_id=project.get("theme_id", "ivory"))
                structure_issues.extend(issue.model_dump(mode="json") for issue in issues)
                if any(issue.severity == "error" for issue in issues): reasons.append("页面结构校验失败")
                block_ids = [block.id for block in parsed_slide.blocks]
                if len(block_ids) != len(set(block_ids)): reasons.append("内容块ID重复")
                if parsed_slide.layout_tree is not None:
                    if not _tree_structure_valid(slide["layout_tree"]): reasons.append("布局树JSON字段或权重不合法")
                    from app.domain.flex_layout import iter_leaf_block_ids
                    leaf_ids = iter_leaf_block_ids(parsed_slide.layout_tree)
                    if len(leaf_ids) != len(set(leaf_ids)): reasons.append("布局树重复引用内容块")
                    node_ids = []
                    def collect_nodes(node):
                        node_ids.append(node.id)
                        for child in getattr(node,"children",[]): collect_nodes(child)
                    collect_nodes(parsed_slide.layout_tree)
                    if len(node_ids) != len(set(node_ids)): reasons.append("布局树节点ID重复")
        if reasons:
            rejected.append({"project_id": project.get("id"), "reasons": sorted(set(reasons))}); continue
        transforms["/owner_id"]="使用显式owner映射，旧标识保留legacy_owner_id"
        transforms["/import_batch"]="绑定本次源快照hash派生的批次"
        project["owner_id"] = owner; project["legacy_owner_id"] = owner_uuid
        transforms["/source_system"]="绑定源快照明确声明的来源系统"
        project["source_system"] = snapshot.get("source_system", "ai-ppt-generator")
        project["import_batch"] = batch
        if outline.get("status") == "confirmed":
            transforms["/outline/status"]="旧确认提纲须按新规则人工重新确认"
            outline["legacy_status"] = "confirmed"; outline["status"] = "draft"; outline["requires_reconfirmation"] = True
        elif outline.get("status") == "generating":
            transforms["/outline/status"]="旧在途提纲转非活跃可重试草稿，原状态保留legacy_status"
            outline["legacy_status"] = "generating"; outline["status"] = "draft"
        transforms["/outline/input_signature"]="旧签名移至legacy_input_signature，中心计算新指纹"
        transforms["/outline/job_id"]="原提纲队列job不恢复活跃"
        if "input_signature" in outline:
            outline["legacy_input_signature"] = outline.pop("input_signature")
        else: outline.setdefault("legacy_input_signature",None)
        outline.pop("job_id", None)
        if "outline" in original and original["outline"] is None:
            transforms["/outline"]="无旧提纲按空记录准备，不生成页面内容"
        project["outline"] = outline
        if project.get("status") == "generating":
            transforms["/status"]="旧生成批次不恢复活跃，保留已完成页与legacy_status"
            project["legacy_status"] = "generating"; project["status"] = "outline_ready"
        preservation=_field_preservation(original,project,transforms)
        asset_checks=[]
        for key in sorted(referenced):
            spec=next(item for item in records if str(item["key"])==key)
            for field in ("sha256","size","mime"):
                expected_value=spec.get(field,"application/octet-stream" if field=="mime" else None)
                asset_checks.append({"source_key":key,"field":field,"preserved":asset_map[key][field]==expected_value})
        preservation["asset_checks"]=asset_checks
        preservation["passed"]=preservation["passed"] and all(item["preserved"] for item in asset_checks)
        if not preservation["passed"]:
            rejected.append({"project_id":project.get("id"),"reasons":["逐字段保留核账失败"],"field_preservation":preservation});continue
        audit = {"sources": len(project.get("sources", [])), "sections": sum(len(s.get("sections", [])) for s in project.get("sources", [])),
                 "slides": len(project.get("slides", [])), "blocks": sum(len(s.get("blocks", [])) for s in project.get("slides", [])),
                 "slide_order": [s["id"] for s in sorted(project.get("slides", []), key=lambda x: x.get("position", 0))],
                 "locked_blocks": [b["id"] for s in project.get("slides", []) for b in s.get("blocks", []) if b.get("locked")],
                 "referenced_assets": len(referenced), "legacy_ref_map": refs,"field_preservation":preservation,"structure_issues":structure_issues}
        prepared.append({"project": project, "assets": [asset_map[k] for k in sorted(referenced)], "audit": audit})
        totals["projects"] += 1
        for key in ["sources", "sections", "slides", "blocks"]: totals[key] += audit[key]
        totals["assets"] += len(referenced)
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest = {"schema_version": "aippt-import-1", "source_sha256": source_hash, "import_batch": batch,
                "catalog_version": catalog_version(), "projects": prepared, "rejected": rejected, "object_failures": asset_failures,
                "totals": totals, "complete": not rejected and not asset_failures and bool(prepared),"manual_review_required":True}
    (output_dir / "import-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"import_batch": batch, "complete": manifest["complete"], "totals": totals, "rejected_projects": len(rejected), "object_failures": len(asset_failures)}
