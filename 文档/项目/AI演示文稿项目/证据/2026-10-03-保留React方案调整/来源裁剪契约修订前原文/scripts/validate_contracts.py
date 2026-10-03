"""离线核对版本目录、模型结构、OpenAPI引用和安全声明；无数据库或网络。"""
from pathlib import Path
import hashlib, json, re
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
ROOT=Path(__file__).resolve().parents[1]
def load(path): return json.loads(path.read_text(encoding="utf-8-sig"))

def check_references(value, origin):
    if isinstance(value, dict):
        ref = value.get("$ref")
        if ref:
            filename, _, pointer = ref.partition("#")
            target = (origin.parent / filename).resolve() if filename else origin
            assert target.is_relative_to(ROOT.resolve()), ref
            document = load(target)
            for part in pointer.lstrip("/").split("/") if pointer else []:
                document = document[part.replace("~1", "/").replace("~0", "~")]
        for item in value.values(): check_references(item, origin)
    elif isinstance(value, list):
        for item in value: check_references(item, origin)

def controller_routes():
    """核对端点存在与路径，不将静态扫描当作权限/HTTP行为测试。"""
    routes = set()
    directory = ROOT.parents[1]/"ruoyi-backend/ruoyi-aippt/src/main/java/com/ruoyi/aippt/controller"
    for file in directory.glob("*.java"):
        source = file.read_text(encoding="utf-8-sig")
        prefix = re.search(r'@RequestMapping\("([^"]+)"\)', source)
        if not prefix: continue
        for match in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping\((?:value\s*=\s*)?("[^"]+"|\{[^}]+\})', source):
            for path in re.findall(r'"([^"]+)"', match.group(2)):
                paths = [prefix.group(1)+path]
                while any(re.search(r'\{\w+:([^}]+)\}', item) for item in paths):
                    expanded = []
                    for item in paths:
                        alternatives = re.search(r'\{\w+:([^}]+)\}', item)
                        if alternatives:
                            expanded.extend(item[:alternatives.start()]+choice+item[alternatives.end():]
                                            for choice in alternatives.group(1).split("|"))
                        else: expanded.append(item)
                    paths = expanded
                routes.update((match.group(1).lower(), re.sub(r'\{[^}]+\}', '{}', item)) for item in paths)
    return routes
def check():
    manifest=load(ROOT/"catalog-manifest.json")
    assert len(manifest["files"]) == 36
    for item in manifest["files"]:
        file=(ROOT/"catalog"/item["path"]).resolve()
        assert file.is_relative_to((ROOT/"catalog").resolve())
        data=file.read_bytes()
        assert len(data)==item["size"] and hashlib.sha256(data).hexdigest()==item["sha256"], item["path"]
    for name,count in [("themes",4),("layouts",11),("flex-presets",18)]:
        assert len(list((ROOT/"catalog"/name).glob("*.json")))==count
    schema=load(ROOT/"schemas/content.schema.json")
    Draft202012Validator.check_schema(schema)
    valid=load(ROOT/"catalog/sample-deck.json")
    Draft202012Validator(schema).validate(valid)
    invalid=json.loads(json.dumps(valid));invalid["slides"][0]["blocks"][0]["type"]="raw_html"
    assert list(Draft202012Validator(schema).iter_errors(invalid)), "非法块类型不能通过"
    schema_files = list((ROOT/"schemas").glob("*.json")) + [ROOT/"events-v1.schema.json"]
    for file in schema_files:
        Draft202012Validator.check_schema(load(file))
        check_references(load(file), file)
    import_schema = load(ROOT/"schemas/import-manifest.schema.json")
    import_manifest = load(ROOT/"fixtures/import-manifest.json")
    import_validator = Draft202012Validator(import_schema, format_checker=FormatChecker())
    import_validator.validate(import_manifest)
    incomplete = json.loads(json.dumps(import_manifest))
    incomplete["rejected"].append({"project_id": None, "reasons": ["owner 未映射"]})
    assert list(import_validator.iter_errors(incomplete)), "存在拒绝项时不能标记完整导入"
    invalid_owner = json.loads(json.dumps(import_manifest))
    for value in [True, 0, "100", 9223372036854775808]:
        invalid_owner["projects"][0]["project"]["owner_id"] = value
        assert list(import_validator.iter_errors(invalid_owner)), "导入owner必须在正式用户ID范围内"
    font_schema = load(ROOT/"schemas/font-manifest.schema.json")
    font_manifest = load(ROOT/"fixtures/font-manifest-metadata.json")
    font_validator = Draft202012Validator(font_schema)
    font_validator.validate(font_manifest)
    wrong_font = json.loads(json.dumps(font_manifest))
    wrong_font["files"][1]["role"] = "latin"
    wrong_font["files"][1]["path"] = "NotoSans-Regular.ttf"
    assert list(font_validator.iter_errors(wrong_font)), "字体清单不能重复角色"
    wrong_font = json.loads(json.dumps(font_manifest)); wrong_font["files"][0]["path"] = "../secret.ttf"
    assert list(font_validator.iter_errors(wrong_font)), "字体清单不得读任意路径"
    registry = Registry().with_resources((load(file)["$id"], Resource.from_contents(load(file))) for file in schema_files if "$id" in load(file))
    worker_validator = Draft202012Validator(load(ROOT/"schemas/worker.schema.json"), registry=registry, format_checker=FormatChecker())
    font_tasks = ["generate_slide", "ai_edit", "relayout", "quality", "export"]
    for task_type in font_tasks:
        task = {"taskId":"10000000-0000-4000-8000-000000000001", "type":task_type, "attempt":1,
                "leaseToken":"fixture-lease-key", "fencingToken":1, "catalogVersion":manifest["catalogVersion"],
                "payload":{"font_manifest_hash":"unavailable"}}
        worker_validator.validate(task)
        task["payload"]["font_manifest_hash"] = "a" * 64
        worker_validator.validate(task)
        for invalid_hash in [None, "", "A" * 64, "invalid"]:
            task["payload"]["font_manifest_hash"] = invalid_hash
            assert list(worker_validator.iter_errors(task)), "需要字体的任务不能携带非法资源身份"
        task["payload"] = {}
        assert list(worker_validator.iter_errors(task)), "需要字体的任务必须明确声明资源身份或估算"
    event_schema = load(ROOT/"events-v1.schema.json")
    Draft202012Validator(event_schema).validate({"eventSeq":1,"type":"running","taskId":"test-task","state":"running","data":{"progress":{"percent":20}}})
    Draft202012Validator(event_schema).validate({"eventSeq":2,"type":"snapshot","projectId":"test-project","state":"ready","completed":1,"total":1,"data":{}})
    event_validator = Draft202012Validator(event_schema)
    delivery_pairs = [("connected","REDIS_CONNECTED"),("degraded","REDIS_UNAVAILABLE"),("disabled","REDIS_DISABLED")]
    for delivery_status, delivery_reason in delivery_pairs:
        frame = {"eventSeq":2,"type":"delivery","projectId":"test-project","data":{},
                 "delivery":{"status":delivery_status,"reason":delivery_reason}}
        event_validator.validate(frame)
        wrong = json.loads(json.dumps(frame)); wrong["delivery"]["reason"] = next(reason for status, reason in delivery_pairs if status != delivery_status)
        assert list(event_validator.iter_errors(wrong)), "通知状态和原因必须匹配"
        wrong = json.loads(json.dumps(frame)); wrong["delivery"]["address"] = "private-address"
        assert list(event_validator.iter_errors(wrong)), "通知状态不得携带地址或其他未声明字段"
        wrong = json.loads(json.dumps(frame)); wrong["state"] = "running"
        assert list(event_validator.iter_errors(wrong)), "纯通知状态帧不能携带任务状态"
        wrong = json.loads(json.dumps(frame)); wrong["data"] = {"progress":20}
        assert list(event_validator.iter_errors(wrong)), "纯通知状态帧不能覆盖业务快照"
    api=load(ROOT/"aippt-v1.openapi.yaml")
    assert api["openapi"]=="3.1.0"
    check_references(api, ROOT/"aippt-v1.openapi.yaml")
    for path in ["/aippt/design/sample-deck", "/aippt/design/sample-deck/issues"]:
        params = [item for item in api["paths"][path]["get"]["parameters"] if item["in"] == "query"]
        assert [item["name"] for item in params] == ["theme_id"], "样例参数须匹配Controller显式theme_id"
        assert params[0]["schema"]["default"] == "ivory"
    sample_controller = (ROOT.parents[1]/"ruoyi-backend/ruoyi-aippt/src/main/java/com/ruoyi/aippt/controller/AipptController.java").read_text(encoding="utf-8-sig")
    for endpoint in ["/design/sample-deck", "/design/sample-deck/issues"]:
        signature = re.search(r'@GetMapping\("'+re.escape(endpoint)+r'"\)[^\n]+', sample_controller)
        assert signature and '@RequestParam(name="theme_id",defaultValue="ivory")' in signature.group(), "样例契约与Controller参数须一致"
    for name in ["SampleSlideIds", "SampleQualityResponse"]:
        Draft202012Validator.check_schema(api["components"]["schemas"][name])
    sample_response_validator = Draft202012Validator({"$ref":"#/components/schemas/SampleQualityResponse", "components":api["components"]})
    sample_ids = {f"10000000-0000-4000-8000-{i:012x}":slide["id"] for i, slide in enumerate(valid["slides"], 1)}
    sample_cached = {"catalog_version":manifest["catalogVersion"], "sample_slide_ids":sample_ids, "can_export":True, "issues":[], "fonts_available":False}
    sample_queued = {"catalog_version":manifest["catalogVersion"], "sample_slide_ids":sample_ids,
                     "jobId":"fixture-task", "job_id":"fixture-task", "state":"queued", "status":"queued"}
    sample_response_validator.validate(sample_cached)
    sample_response_validator.validate(sample_queued)
    for value in [sample_cached, sample_queued]:
        for field in ["catalog_version", "sample_slide_ids"]:
            wrong = json.loads(json.dumps(value)); del wrong[field]
            assert list(sample_response_validator.iter_errors(wrong)), "缓存与接收均须带样例目录身份和明确映射"
    for mapping in [{}, {"not-a-uuid":"s01"}, {next(iter(sample_ids)):""}, {next(iter(sample_ids)):"x"*129},
                    {next(iter(sample_ids)):True}, {f"10000000-0000-4000-8000-{i:012x}":"s01" for i in range(41)}]:
        wrong = json.loads(json.dumps(sample_cached)); wrong["sample_slide_ids"] = mapping
        assert list(sample_response_validator.iter_errors(wrong)), "样例映射须1–40项、正式UUID及有界目录ID"
    for field in ["jobId", "job_id", "state", "status"]:
        wrong = json.loads(json.dumps(sample_queued)); del wrong[field]
        assert list(sample_response_validator.iter_errors(wrong)), "异步样例响应须保留既有任务接收字段"
    wrong = json.loads(json.dumps(sample_cached)); wrong["can_export"] = "true"
    assert list(sample_response_validator.iter_errors(wrong)), "缓存报告可导出结论须为明确布尔值"
    # 原目录对应关系、唯一位置、权限与普通报告未污染由中心H2/平台代理测试验证。
    claim_validator = Draft202012Validator(api["components"]["schemas"]["WorkerClaim"])
    claim = {"workerId":"fixture-worker", "catalogVersion":manifest["catalogVersion"]}
    claim_validator.validate(claim)
    for wait in [0, 1, 10000]:
        claim_validator.validate({**claim, "waitMillis":wait})
    for wait in [-1, 10001, True, "1000", 1.5, None]:
        assert list(claim_validator.iter_errors({**claim, "waitMillis":wait})), "领取等待须为0–10000整数"
    claim_validator.validate({**claim, "types":[]})
    claim_validator.validate({**claim, "types":["parse_source","export"]})
    assert list(claim_validator.iter_errors({**claim, "types":["unknown"]})), "领取类型须在已声明七类中"
    verification_validator = Draft202012Validator(api["components"]["schemas"]["ExportVerificationFailure"])
    verification = {"passed":False, "issues":[{"check":"speaker_notes","slide_index":2,"shape":None,"message":"第2页演讲备注与快照不一致"}],
                    "slide_count":5,"expected_slide_count":5,"diagnostics_truncated":False,"total_issues":1}
    verification_validator.validate(verification)
    for field, value in [("passed",True),("slide_count",True),("expected_slide_count",0),("diagnostics_truncated","false"),("total_issues",0),("extra","private-value")]:
        wrong = json.loads(json.dumps(verification)); wrong[field] = value
        assert list(verification_validator.iter_errors(wrong)), "回读失败报告必须使用明确类型和已声明字段"
    for field, value in [("check","arbitrary-tool"),("slide_index",0),("slide_index",True),("message","x"*1001),("shape","x"*201),("url","private-url")]:
        wrong = json.loads(json.dumps(verification)); wrong["issues"][0][field] = value
        assert list(verification_validator.iter_errors(wrong)), "回读问题须可定位、有界且没有未声明字段"
    wrong = json.loads(json.dumps(verification)); wrong["issues"] = []
    assert list(verification_validator.iter_errors(wrong)), "回读失败必须携带至少一个真实问题"
    # 页号须在本快照内、计数一致及仅export可携带，另由中心的真实租约测试验证。
    ids=set();count=0
    for path,operations in api["paths"].items():
        assert path.startswith(("/aippt/","/internal/aippt/"))
        for method,operation in operations.items():
            count+=1
            assert operation["operationId"] not in ids
            ids.add(operation["operationId"])
            expected="WorkerKey" if path.startswith("/internal/") else "RuoYiBearer"
            assert expected in operation["security"][0]
            declared={x["name"] for x in operation.get("parameters",[]) if x["in"]=="path"}
            assert declared==set(re.findall(r"\{([^}]+)\}",path)), path
    expected = {(method,re.sub(r'\{[^}]+\}', '{}', path)) for path, operations in api["paths"].items() for method in operations}
    actual = controller_routes()
    assert expected == actual, {"missing_from_code": sorted(expected-actual), "missing_from_contract": sorted(actual-expected)}
    print(json.dumps({"catalogVersion":manifest["catalogVersion"],"files":len(manifest["files"]),"schemas":len(schema_files),"componentSchemas":len(api["components"]["schemas"]),"operations":count,"controllerOperations":len(actual),"sampleResponseVariants":2,"sampleNegativeCases":15,"status":"passed"},ensure_ascii=False))
if __name__=="__main__": check()
