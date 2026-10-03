from __future__ import annotations

import json
from pathlib import Path

import yaml

from aipicture_runtime.layers import LayerDocument
from aipicture_runtime.dto import ComputeRequest, PlanRequest
from aipicture_runtime.registry import export_registry

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT.parents[1] / "contracts" / "aipicture" / "v1"


def main(*, worker_only: bool = False) -> None:
    CONTRACTS.mkdir(parents=True, exist_ok=True)
    document = LayerDocument.model_json_schema()
    document["$schema"] = "https://json-schema.org/draft/2020-12/schema"
    document["x-schemaVersion"] = "1"
    if not worker_only:
        for name, body in [("tools.schema.json", export_registry()), ("document.schema.json", document)]:
            (CONTRACTS / name).write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    components = {"FrozenComputation": ComputeRequest.model_json_schema(), "PlanRequest": PlanRequest.model_json_schema()}
    components["FrozenComputation"]["allOf"] = [{"if": {"properties": {"tool": {"const": "export"}}, "required": ["tool"]},
        "then": {"properties": {"params": {"properties": {"asset_ids": {
            "type": "array", "maxItems": 200, "uniqueItems": True, "items": {"type": "string", "format": "uuid"}}}}}},
        "else": {"if": {"properties": {"tool": {"const": "evaluation"}}, "required": ["tool"]},
                 "then": {"properties": {"params": {"properties": {"manifest": {"type": "array", "minItems": 1, "maxItems": 100}}}}},
                 "else": {"properties": {"assets": {"maxItems": 64}}}}}]
    components["FrozenComputation"]["x-export-reference-rule"] = "ZIP所选最多200个互异素材；仅PNG、空ZIP或所选含当前素材时增加必要画布图像引用（最多64个额外依赖）；冻结引用必须恰好等于所选与必要依赖并集，禁止无关引用。PNG仅引用画布依赖。"
    components["FrozenComputation"]["x-evaluation-reference-rule"] = "评测清单1–100项；最多264个互异引用，必须恰为CLI输入/预期和专项中心冻结assessment.asset_ids、输出、两画布图像、实际mask及人工参考依赖并集。维持128MB总字节与每图5000万像素。"
    # Pydantic local $defs remains nested per component; rewrite references to their exact path.
    def rewrite(value, component):
        if isinstance(value, dict):
            return {key: (f"#/components/schemas/{component}/$defs/{item.split('/')[-1]}" if key == "$ref" and isinstance(item, str) and item.startswith("#/$defs/") else rewrite(item, component)) for key, item in value.items()}
        if isinstance(value, list):
            return [rewrite(item, component) for item in value]
        return value
    components = {name: rewrite(schema, name) for name, schema in components.items()}
    headers = [{"name": key, "in": "header", "required": True, "schema": {"type": "string"}} for key in ["X-Aipicture-Service", "X-Aipicture-Timestamp", "X-Aipicture-Nonce", "X-Aipicture-Signature"]]
    paths = {}
    for operation, model, summary in [("compute", "FrozenComputation", "冻结输入的像素、合成、分层、批量和内部控制计算"),
                                      ("prepare-selection", "FrozenComputation", "从 params 准备SAM缓存/构建选区"),
                                      ("plan", "PlanRequest", "仅生成候选计划，Java再次校验和确认")]:
        paths[f"/internal/v1/{operation}"] = {"post": {"summary": summary, "parameters": headers,
            "requestBody": {"required": True, "content": {"application/json": {"schema": {"$ref": f"#/components/schemas/{model}"}}}},
            "responses": {"200": {"description": "真实计算结果/候选计划及schema/toolRegistry版本"},
                          "401": {"description": "服务凭据无效、过期或nonce重复"}, "413": {"description": "资源边界超限"},
                          "422": {"description": "输入、模型参数或冻结数据不合法"}, "503": {"description": "运行时/模型未启用或真实能力不可用"}}}}
    paths["/internal/v1/capabilities"] = {method: {"summary": "读取实际配置、依赖、本地模型与22工具清单",
        "parameters": headers, "responses": {"200": {"description": "实际能力状态，不含密钥"}, "401": {"description": "无效凭据"}}} for method in ["get", "post"]}
    worker = {"openapi": "3.1.0", "info": {"title": "若依 AI 图片私有计算协议", "version": "1",
        "description": "Java→Worker incoming service=aipicture-java；Worker→Java service=aipicture-worker。HMAC=METHOD\\nPATH\\nTIMESTAMP(seconds)\\nNONCE\\nSHA256(raw body)。PATH不含query。TTL120s、nonce单次使用。Worker没有业务数据库/账号/正式会话任务。计算版本schema_version=1，tool_registry_version=2026.10-r2。"},
        "servers": [{"url": "http://127.0.0.1:7405"}], "paths": paths, "components": {"schemas": components},
        "x-resource-limits": {"assetBytes": 20 * 1024 * 1024, "pixels": 50_000_000, "assets": 64, "exportAssets": 264, "evaluationAssets": 264, "evaluationCases": 100,
                              "exportSelected": 200, "exportDocumentDependencies": 64, "documentLayers": 200,
                              "outputs": 200, "outputBytes": 128 * 1024 * 1024, "requestBytes": 70 * 1024 * 1024, "concurrency": 2},
        "x-internal-control-tools": ["inspect_image", "prepare_selection", "selection", "export", "evaluation"],
        "x-assess-result": {"task": "assess_result", "suite": ["sam", "region_protection", "ocr", "enhancement", "generation"],
            "userFields": ["id", "source_run_id", "output_asset_id", "expect_asset_id", "expected_ocr", "thresholds", "require_true_super_resolution", "human_review"],
            "centerOnlyAssessment": ["source_tool", "source_params", "source_document", "result_document", "source_result", "source_provider", "mask_asset_id", "output_asset_ids", "asset_ids"],
            "humanReview": "status=pending/passed/failed；passed须有非空notes；独立意见不是用户接受。",
            "result": "technical_passed、human_review、real_model_verified、overall/passed、metrics、thresholds、details、provenance与error分别报告。offline_test必须显式false且实际Provider符合专项，人工passed后才可整体通过。所有评分只读已成功正式任务，不重新调用模型。",
            "defaultThresholds": {"alpha_iou": 0.8, "outside_mae": 0, "outside_max": 0, "cer": 0, "bbox_iou": 0.8, "count_match": 1, "size": 1},
            "fixedInvariants": "size/count_match阈值与实际指标必须为1；区域outside_mae/outside_max阈值必须为0且outside_changed_pixels=0。人工expect_asset_id不得为原任务产物。",
            "ocr": "人工框1–128条、每条非空文字最多2000字、合计32000字；bbox=[x,y,width,height]各数绝对值≤100000，宽高>0。按IoU匹配，同IoU优先相同文字再稳定文字/框排序；报告matched_ocr/missing_ocr/extra_ocr。",
            "strictSuperResolutionDefault": True},
        "x-java-execution": {"prefix": "/aipicture/internal/v1/execution", "methods": ["POST claim", "POST heartbeat", "POST artifacts", "POST receipt", "GET assets/{id}"],
            "artifact": "每次一个JSON base64；中心生成object_key/hash/正式记录；ZIP不解压。", "lease": "run_id/attempt/lease_token绑定每次内部访问；claim外壳lease_expires_at不传纯computeDTO。", "versions": "input_version=CAS row_version；input_revision=选区/画布revision，禁止混用。"}}
    (CONTRACTS / "worker.openapi.yaml").write_text(yaml.safe_dump(worker, allow_unicode=True, sort_keys=False), encoding="utf-8")
    event = {"$schema": "https://json-schema.org/draft/2020-12/schema", "title": "若依图片任务持久事件",
        "type": "object", "required": ["event_seq", "payload", "created_at"], "properties": {
            "event_seq": {"type": "integer", "minimum": 1}, "created_at": {"type": "integer", "description": "中心毫秒时间戳"},
            "payload": {"type": "object", "required": ["id", "status", "progress", "event_seq"], "properties": {
                "id": {"type": "string", "format": "uuid"}, "event_seq": {"type": "integer", "minimum": 1},
                "status": {"enum": ["queued", "running", "succeeded", "partial_success", "failed", "cancelled", "external_result_unknown"]},
                "progress": {"type": "integer", "minimum": 0, "maximum": 100}, "stage": {"type": ["string", "null"]},
                "attempt": {"type": "integer", "minimum": 0}, "result": {"type": "object"}, "error_code": {"type": ["string", "null"]}}}},
        "x-delivery": "至少一次；客户端按任务id+event_seq去重，MySQL快照为唯一状态源；终态不接受迟到running。"}
    if not worker_only:
        (CONTRACTS / "run-event.schema.json").write_text(json.dumps(event, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    import sys
    main(worker_only="--worker-only" in sys.argv[1:])
