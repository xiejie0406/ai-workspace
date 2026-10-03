"""只抽取算法与参数，绝不导入或执行参考工程的数据库/账号/队列模块。"""
from __future__ import annotations

import ast
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parents[1] / "参考项目" / "ai-picture-editor-main" / "backend"
PACKAGE = ROOT / "aipicture_runtime"


def write(relative: str, content: str) -> None:
    target = ROOT / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def main() -> None:
    if (PACKAGE / "params.py").exists():
        raise SystemExit("来源抽取已经完成；为保护迁移后的安全修复，禁止覆盖现有算法。来源指纹见 source-fingerprints.json。")
    files = ["ratios.py", "layers.py", "providers/base.py", "providers/mock.py",
             "providers/dashscope.py", "agent/llm.py", "agent/graph.py", "agent/plan.py"]
    files += [str(p.relative_to(SOURCE / "app")).replace("\\", "/")
              for group in ["edits", "eval"] for p in (SOURCE / "app" / group).rglob("*")
              if p.is_file() and p.suffix in {".py", ".png", ".json"}]
    fingerprints = {}
    for relative in files:
        source = SOURCE / "app" / relative
        fingerprints["app/" + relative] = hashlib.sha256(source.read_bytes()).hexdigest()
        target = PACKAGE / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.suffix == ".py":
            content = source.read_text(encoding="utf-8").replace("from app", "from aipicture_runtime")
            content = content.replace("from aipicture_runtime.models import Asset", "from typing import Any\nAsset = Any")
            content = content.replace("from aipicture_runtime.services import tools as tool_service", "from aipicture_runtime import registry as tool_service")
            content = content.replace("from aipicture_runtime.tools import", "from aipicture_runtime.registry import")
            target.write_text(content, encoding="utf-8")
        else:
            target.write_bytes(source.read_bytes())
    for relative in ["test_edits.py", "test_eval.py"]:
        content = (SOURCE / "tests" / relative).read_text(encoding="utf-8")
        content = content.replace("from app", "from aipicture_runtime")
        if relative == "test_eval.py":
            content = content.replace("from aipicture_runtime.storage import _client, signed_url\n", "")
            content = content.split("def test_signed_url_uses_public_endpoint")[0]
        write("tests/test_source_" + relative[5:], content)

    ordered = ["tools/base.py", "schemas/run.py", "tools/retouch.py", "tools/enhance.py",
               "tools/region.py", "tools/layers.py", "tools/marketing.py", "schemas/batch.py", "tools/canvas.py"]
    ignore = {"ToolSpec", "UnknownTool", "RunOut", "BatchItemOut", "BatchOut", "PackedExport"}
    classes = []
    for relative in ordered:
        path = SOURCE / "app" / relative
        fingerprints["app/" + relative] = hashlib.sha256(path.read_bytes()).hexdigest()
        tree = ast.parse(path.read_text(encoding="utf-8"))
        classes += [ast.unparse(node) for node in tree.body if isinstance(node, ast.ClassDef) and node.name not in ignore]
    header = '''"""源参数模型的纯 DTO 版本；用于三端唯一 JSON Schema，无数据库依赖。"""
import enum
import uuid
from typing import Annotated, Literal
from pydantic import BaseModel as PydanticBaseModel, ConfigDict, Field, field_validator, model_validator
from aipicture_runtime.ratios import Ratio, DELIVERY_RATIOS

class BaseModel(PydanticBaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

MAX_PROMPT = 1500
MAX_REFERENCES = 3
MAX_BATCH_ASSETS = 20
ExportFormat = Literal["png", "jpg"]
BATCH_TOOLS = ("remove_background", "replace_background", "adjust_image", "upscale_image", "expand_canvas", "prepare_delivery_sizes")
'''
    write("aipicture_runtime/params.py", header + "\n\n" + "\n\n".join(classes) + "\n")

    metadata = {}
    for path in (SOURCE / "app" / "tools").glob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in tree.body:
            if not isinstance(node, ast.Assign) or not isinstance(node.value, ast.Call):
                continue
            call = node.value
            if not isinstance(call.func, ast.Name) or call.func.id not in {"ToolSpec", "_canvas"}:
                continue
            if call.func.id == "ToolSpec":
                fields = {kw.arg: kw.value for kw in call.keywords}
                name_node = fields["name"]
                name = "batch_process" if isinstance(name_node, ast.Name) else ast.literal_eval(name_node)
                item = {"name": name, "label": ast.literal_eval(fields["label"]),
                        "description": ast.literal_eval(fields["description"]),
                        "paramsModel": fields["params"].id,
                        "queued": ast.literal_eval(fields.get("queued", ast.Constant(True))),
                        "sessionRequired": ast.literal_eval(fields.get("session_required", ast.Constant(False)))}
                hidden = fields.get("agent_hidden", ast.Tuple(elts=[], ctx=ast.Load()))
                item["agentHidden"] = ["mask_asset_id", "revision"] if isinstance(hidden, ast.Name) else list(ast.literal_eval(hidden))
            else:
                item = {"name": ast.literal_eval(call.args[0]), "label": ast.literal_eval(call.args[1]),
                        "description": ast.literal_eval(call.args[2]), "paramsModel": call.args[3].id,
                        "queued": False, "sessionRequired": True, "agentHidden": []}
                if len(call.args) > 5:
                    item["agentHidden"] = ["x", "y"]
            metadata[item["name"]] = item
    order = ["generate_image", "replace_background", "expand_canvas", "upscale_image", "remove_background",
             "adjust_image", "erase_region", "replace_region", "split_layers", "promote_object_to_layer",
             "generate_marketing", "prepare_delivery_sizes", "batch_process", "crop_canvas", "flip_layer",
             "set_layer_opacity", "set_layer_visible", "set_layer_text", "reorder_layer", "scale_layer",
             "rotate_layer", "move_layer"]
    write("aipicture_runtime/tool_metadata.json", json.dumps([metadata[name] for name in order], ensure_ascii=False, indent=2) + "\n")
    write("source-fingerprints.json", json.dumps(fingerprints, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
