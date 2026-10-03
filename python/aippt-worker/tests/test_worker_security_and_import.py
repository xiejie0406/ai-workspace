import hashlib
import json
from io import BytesIO
from pathlib import Path
import zipfile
import pytest
from PIL import Image
from app.core.config import get_settings
from app.ingest.upload import validate_upload, UploadRejected
from app.images.validate import validate_image, ImageRejected
from app.worker.security import assert_payload_budget, assert_public_url, BudgetExceeded, SafeAsyncClient
from app.worker.migration import prepare_import


def _image(fmt):
    buffer=BytesIO(); Image.new("RGB", (20,10), "blue").save(buffer, format=fmt); return buffer.getvalue()


@pytest.mark.parametrize("fmt,mime", [("PNG","image/png"),("JPEG","image/jpeg"),("WEBP","image/webp")])
def test_full_image_decoder_accepts_supported_bytes(fmt,mime):
    assert validate_image(_image(fmt))[1]==mime


def test_fake_magic_truncated_image_and_pixel_budget_rejected(monkeypatch):
    with pytest.raises(ImageRejected): validate_image(b"\x89PNG\r\n\x1a\n"+b"\0"*64)
    monkeypatch.setattr(get_settings(),"max_image_pixels",100)
    with pytest.raises(ImageRejected,match="预算"): validate_image(_image("PNG"))


def test_zip_bomb_traversal_and_fake_docx_rejected():
    for members in [[("word/document.xml", b"x"*2_000_000)],[("word/document.xml",b"x"),("../escape",b"bad")],[("nothing",b"text")]]:
        stream=BytesIO()
        with zipfile.ZipFile(stream,"w",zipfile.ZIP_DEFLATED) as archive:
            for filename,data in members: archive.writestr(filename,data)
        with pytest.raises(UploadRejected): validate_upload("file.docx",stream.getvalue())


def test_payload_tree_budget_rejects_without_truncation(monkeypatch):
    tree={"type":"column","id":"root","children":[]}
    cursor=tree
    for index in range(20):
        child={"type":"column","id":str(index),"children":[]}; cursor["children"].append(child); cursor=child
    with pytest.raises(BudgetExceeded): assert_payload_budget({"slide":{"layout_tree":tree}})
    monkeypatch.setattr(get_settings(),"max_blocks_per_slide",2)
    with pytest.raises(BudgetExceeded): assert_payload_budget({"slide":{"blocks":[{},{},{}]}})


def test_chart_nan_rejected_before_json_protocol():
    with pytest.raises(ValueError): assert_payload_budget({"slide":{"blocks":[{"type":"chart","series":[{"values":[float("nan")]}]}]}})


@pytest.mark.asyncio
async def test_dns_connection_is_pinned_to_checked_public_ip(monkeypatch):
    import asyncio
    import socket
    from app.worker.transport import PublicNetworkBackend
    loop=asyncio.get_running_loop(); resolves=[]; connections=[]
    async def resolve(host,port,**kwargs):
        resolves.append(host)
        return [(socket.AF_INET,socket.SOCK_STREAM,6,"",("8.8.8.8",port))]
    class Delegate:
        async def connect_tcp(self,host,port,**kwargs): connections.append(host); return "stream"
    monkeypatch.setattr(loop,"getaddrinfo",resolve)
    assert await PublicNetworkBackend(Delegate()).connect_tcp("image.example",443)=="stream"
    assert resolves==["image.example"] and connections==["8.8.8.8"]


@pytest.mark.asyncio
async def test_mixed_public_private_dns_records_do_not_connect(monkeypatch):
    import asyncio
    import socket
    import httpcore
    from app.worker.transport import PublicNetworkBackend
    loop=asyncio.get_running_loop()
    async def resolve(host,port,**kwargs):
        return [(socket.AF_INET,socket.SOCK_STREAM,6,"",(ip,port)) for ip in ["8.8.8.8","127.0.0.1"]]
    monkeypatch.setattr(loop,"getaddrinfo",resolve)
    with pytest.raises(httpcore.ConnectError): await PublicNetworkBackend().connect_tcp("image.example",443)


@pytest.mark.asyncio
@pytest.mark.parametrize("url",["http://public.example/image","https://user:secret@public.example/image","https://127.0.0.1/image","https://[::1]/image","https://169.254.169.254/meta","https://public.example:8443/image"])
async def test_ssrf_private_or_credentialled_sources_rejected(url):
    with pytest.raises(ValueError): await assert_public_url(url)


@pytest.mark.asyncio
async def test_unapproved_host_rejected_before_dns():
    with pytest.raises(ValueError,match="清单"): await assert_public_url("https://unapproved.example/image",["approved.example"])


def _snapshot():
    return {"source_system":"python","source_order_verified":True,"projects":[{"id":"p","user_id":"owner", "title":"原文保留", "theme_id":"ivory",
        "sources":[{"id":"source","kind":"text","sections":[{"text":"原始来源","level":0,"locator":"段1"}]}],
        "outline":{"status":"confirmed","input_signature":"legacy","pages":[{"id":"c1f33fb2-cb9f-41c4-bdf0-7b42d8e546d7","title":"原标题","objective":"原目的","key_points":["要点一","要点二"],"source_refs":["S1:1"],"layout_id":"bullets"}]},
        "slides":[{"id":"slide","layout_id":"bullets","status":"ready","position":1,"speaker_notes":"备注原文","revision":8,"blocks":[
            {"id":"title","type":"text","slot_id":"title","text":"原标题","locked":True},
            {"id":"body","type":"bullets","slot_id":"body","items":["要点一","要点二"]}]}],"password_hash":"never-import"}]}


def test_offline_import_is_idempotent_preserves_fields_and_never_imports_secrets(tmp_path):
    snapshot=tmp_path/"snapshot.json"; owners=tmp_path/"owners.json"; objects=tmp_path/"objects.json"
    snapshot.write_text(json.dumps(_snapshot(),ensure_ascii=False),encoding="utf-8"); owners.write_text('{"owner":100}',encoding="utf-8"); objects.write_text('{"objects":[]}',encoding="utf-8")
    first=prepare_import(snapshot,owners,objects,tmp_path/"out")
    raw=(tmp_path/"out/import-manifest.json").read_text(encoding="utf-8")
    second=prepare_import(snapshot,owners,objects,tmp_path/"out")
    assert first==second and first["complete"]
    assert "never-import" not in raw and "password_hash" not in raw
    project=json.loads(raw)["projects"][0]["project"]
    assert project["slides"][0]["revision"]==8 and project["slides"][0]["speaker_notes"]=="备注原文"
    assert project["slides"][0]["blocks"][0]["locked"]
    assert project["outline"]["requires_reconfirmation"] and project["outline"]["legacy_input_signature"]=="legacy"
    assert len(project["outline"]["pages"][0]["source_refs"][0])==36
    manifest=json.loads(raw);audit=manifest["projects"][0]["audit"]["field_preservation"]
    assert manifest["manual_review_required"] and audit["manual_review_required"] and audit["passed"]
    paths={check["path"] for check in audit["checks"] if check["preserved"]}
    assert {"/slides/0/speaker_notes","/slides/0/revision","/slides/0/position","/slides/0/blocks/0/locked","/slides/0/blocks/0/text"}<=paths
    assert any(item["path"]=="/outline/pages/0/source_refs" for item in audit["expected_transforms"])


def test_offline_import_blocks_unmapped_owner_unverified_order_and_bad_object(tmp_path):
    snapshot=_snapshot(); snapshot["source_order_verified"]=False
    source=tmp_path/"snapshot.json"; source.write_text(json.dumps(snapshot),encoding="utf-8")
    owners=tmp_path/"owners.json";owners.write_text("{}",encoding="utf-8")
    data=tmp_path/"bad.png";data.write_bytes(b"bad")
    objects=tmp_path/"objects.json";objects.write_text(json.dumps({"objects":[{"key":"media/key","path":"bad.png","size":3,"sha256":"wrong"}]}),encoding="utf-8")
    result=prepare_import(source,owners,objects,tmp_path/"out")
    assert not result["complete"] and result["rejected_projects"]==1 and result["object_failures"]==1


def test_field_preservation_detects_unexpected_content_notes_lock_style_or_order_changes():
    import copy
    from app.worker.migration import _field_preservation
    before=_snapshot()["projects"][0]
    before["slides"][0]["blocks"][0]["style"]={"size_pt":28}
    before["slides"].append({**copy.deepcopy(before["slides"][0]),"id":"other-slide","position":2})
    after=copy.deepcopy(before)
    after["slides"][0]["speaker_notes"]="被改的备注"
    after["slides"][0]["blocks"][0].update(text="被改的文字",locked=False,style={"size_pt":32})
    after["slides"][0]["position"]=2
    result=_field_preservation(before,after,{})
    failed={check["path"] for check in result["checks"] if not check["preserved"]}
    assert not result["passed"] and {"/slides/0/speaker_notes","/slides/0/blocks/0/text","/slides/0/blocks/0/locked","/slides/0/blocks/0/style/size_pt","/slides/0/position"}<=failed
    changed_order=_field_preservation(before,{**before,"slides":list(reversed(before["slides"]))},{})
    assert not changed_order["passed"]


def test_asset_hash_audit_and_expected_url_mapping_keep_alt_notes_and_lock(tmp_path):
    snapshot=_snapshot();slide=snapshot["projects"][0]["slides"][0];slide["layout_id"]="image-left"
    slide["blocks"].append({"id":"image","slot_id":"image","type":"image","source":"upload","url":"/api/v1/media/legacy/image.png","alt":"原配图说明","locked":True})
    data=_image("PNG");(tmp_path/"image.png").write_bytes(data)
    for name,value in [("snapshot.json",snapshot),("owners.json",{"owner":100}),("objects.json",{"objects":[{"key":"legacy/image.png","path":"image.png","size":len(data),"sha256":hashlib.sha256(data).hexdigest(),"mime":"image/png"}]})]:
        (tmp_path/name).write_text(json.dumps(value,ensure_ascii=False),encoding="utf-8")
    result=prepare_import(tmp_path/"snapshot.json",tmp_path/"owners.json",tmp_path/"objects.json",tmp_path/"out")
    assert result["complete"]
    manifest=json.loads((tmp_path/"out/import-manifest.json").read_text('utf-8'));entry=manifest["projects"][0]
    audit=entry["audit"]["field_preservation"]
    assert audit["passed"] and len(audit["asset_checks"])==3 and all(item["preserved"] for item in audit["asset_checks"])
    assert any(item["path"]=="/slides/0/blocks/2/url" for item in audit["expected_transforms"])
    assert entry["project"]["slides"][0]["blocks"][2]["alt"]=="原配图说明"
    assert entry["assets"][0]["sha256"]==hashlib.sha256(data).hexdigest()


def test_import_recursively_removes_actual_source_configuration_credentials_but_keeps_body(tmp_path):
    import ast
    source_config = Path(__file__).resolve().parents[3] / "参考项目/ai-ppt-generator-main/backend/app/core/config.py"
    tree = ast.parse(source_config.read_text(encoding="utf-8"))
    fields = {node.target.id for node in ast.walk(tree) if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name)}
    credentials = {name for name in fields if "secret" in name or name.endswith("api_key") or name.endswith("access_key")}
    assert {"llm_api_key","image_api_key","unsplash_access_key","cos_secret_id","cos_secret_key","jwt_secret"} <= credentials
    snapshot = _snapshot(); project = snapshot["projects"][0]
    project["nested"] = [{name:"credential-must-not-persist" for name in credentials}, {"workerKey":"credential-must-not-persist","password":"credential-must-not-persist"}]
    body = "正文包含 api_key/token/password 字样必须逐字保留"
    project["sources"][0]["sections"][0]["text"] = body
    paths = [tmp_path/name for name in ["snapshot.json","owners.json","objects.json"]]
    for path, value in zip(paths, [snapshot,{"owner":100},{"objects":[]}]): path.write_text(json.dumps(value,ensure_ascii=False),encoding="utf-8")
    original = paths[0].read_bytes()
    result = prepare_import(*paths,tmp_path/"out")
    manifest = (tmp_path/"out/import-manifest.json").read_text(encoding="utf-8")
    assert result["complete"] and "credential-must-not-persist" not in manifest and body in manifest
    assert paths[0].read_bytes() == original


@pytest.mark.parametrize("owner", [True,False,1.0,1.5,"100","01",0,-1,2**63])
def test_import_owner_matches_centers_strict_positive_long_integer_boundary(tmp_path, owner):
    paths = [tmp_path/name for name in ["snapshot.json","owners.json","objects.json"]]
    for path, value in zip(paths, [_snapshot(),{"owner":owner},{"objects":[]}]): path.write_text(json.dumps(value),encoding="utf-8")
    result = prepare_import(*paths,tmp_path/"out")
    assert not result["complete"] and result["rejected_projects"] == 1


@pytest.mark.parametrize("status", ["pending", "generating", "failed"])
def test_import_preserves_original_unfinished_flex_stub_without_constructing_tree(tmp_path, status):
    snapshot = _snapshot(); slide = snapshot["projects"][0]["slides"][0]
    slide.update(status=status, layout_mode="flex", layout_tree=None, blocks=[], error_message="原始失败原因", job_id="old-job")
    paths = [tmp_path/name for name in ["snapshot.json", "owners.json", "objects.json"]]
    for path, value in zip(paths, [snapshot, {"owner":100}, {"objects":[]} ]): path.write_text(json.dumps(value,ensure_ascii=False),encoding="utf-8")
    result = prepare_import(*paths,tmp_path/"out")
    assert result["complete"]
    imported = json.loads((tmp_path/"out/import-manifest.json").read_text("utf-8"))["projects"][0]["project"]["slides"][0]
    assert imported["blocks"] == [] and imported["layout_tree"] is None and imported["error_message"] == "原始失败原因" and "job_id" not in imported
    assert imported["status"] == ("pending" if status == "generating" else status)
    if status == "generating": assert imported["legacy_status"] == "generating"


@pytest.mark.parametrize("status,nonempty", [("ready",False), ("pending",True), ("failed",True)])
def test_import_rejects_ready_or_nonempty_flex_slide_missing_tree(tmp_path, status, nonempty):
    snapshot = _snapshot(); slide = snapshot["projects"][0]["slides"][0]
    slide.update(status=status, layout_mode="flex", layout_tree=None)
    if not nonempty: slide["blocks"] = []
    paths = [tmp_path/name for name in ["snapshot.json", "owners.json", "objects.json"]]
    for path, value in zip(paths, [snapshot, {"owner":100}, {"objects":[]} ]): path.write_text(json.dumps(value),encoding="utf-8")
    result = prepare_import(*paths,tmp_path/"out")
    assert not result["complete"] and result["rejected_projects"] == 1


@pytest.mark.parametrize("status,has_pages", [("generating",False), ("generating",True), ("failed",False), ("draft",True)])
def test_import_outline_cannot_restore_old_active_job_and_retains_pages_and_error(tmp_path, status, has_pages):
    snapshot = _snapshot(); outline = snapshot["projects"][0]["outline"]
    outline.update(status=status, job_id="original-job", error_message="原提纲错误")
    if not has_pages: outline["pages"] = []
    old_pages = json.loads(json.dumps(outline["pages"]))
    paths = [tmp_path/name for name in ["snapshot.json", "owners.json", "objects.json"]]
    for path, value in zip(paths, [snapshot, {"owner":100}, {"objects":[]} ]): path.write_text(json.dumps(value,ensure_ascii=False),encoding="utf-8")
    result = prepare_import(*paths,tmp_path/"out")
    assert result["complete"]
    imported = json.loads((tmp_path/"out/import-manifest.json").read_text("utf-8"))["projects"][0]["project"]["outline"]
    assert imported["status"] == ("draft" if status == "generating" else status)
    assert imported["error_message"] == "原提纲错误" and "job_id" not in imported
    assert len(imported["pages"]) == len(old_pages)
    if old_pages: assert imported["pages"][0]["title"] == old_pages[0]["title"]
    if status == "generating": assert imported["legacy_status"] == "generating"


@pytest.mark.parametrize("defect", [None,"duplicate_block","duplicate_slot","missing_leaf","duplicate_leaf","duplicate_node","invalid_ratios","unknown_field"])
def test_import_ready_structure_is_checked_without_constructing_missing_content(tmp_path, defect):
    snapshot=_snapshot(); slide=snapshot["projects"][0]["slides"][0]
    if defect in {"missing_leaf","duplicate_leaf","duplicate_node","invalid_ratios","unknown_field"}:
        slide.update(layout_mode="flex",layout_tree={"type":"column","id":"root","children":[{"type":"block","id":"leaf-title","block_id":"title"},{"type":"block","id":"leaf-body","block_id":"body"}]})
    if defect == "duplicate_block": slide["blocks"][1]["id"]="title"
    elif defect == "duplicate_slot": slide["blocks"][1]["slot_id"]="title"
    elif defect == "missing_leaf": slide["layout_tree"]["children"][1]["block_id"]="not-present"
    elif defect == "duplicate_leaf": slide["layout_tree"]["children"].append({"type":"block","id":"extra-leaf","block_id":"body"})
    elif defect == "duplicate_node": slide["layout_tree"]["children"][1]["id"]="leaf-title"
    elif defect == "invalid_ratios": slide["layout_tree"]["ratios"]=[1]
    elif defect == "unknown_field": slide["layout_tree"]["mystery"]="must-not-pass"
    paths=[tmp_path/name for name in ["snapshot.json","owners.json","objects.json"]]
    for path,value in zip(paths,[snapshot,{"owner":100},{"objects":[]}]):path.write_text(json.dumps(value),encoding="utf-8")
    result=prepare_import(*paths,tmp_path/"out")
    assert result["complete"] is (defect is None)


def test_import_retains_overflow_and_capacity_warnings_and_original_text(tmp_path):
    snapshot=_snapshot(); body=snapshot["projects"][0]["slides"][0]["blocks"][1]
    original="原始内容"*200;body["items"][0]=original
    paths=[tmp_path/name for name in ["snapshot.json","owners.json","objects.json"]]
    for path,value in zip(paths,[snapshot,{"owner":100},{"objects":[]}]):path.write_text(json.dumps(value,ensure_ascii=False),encoding="utf-8")
    result=prepare_import(*paths,tmp_path/"out")
    assert result["complete"]
    entry=json.loads((tmp_path/"out/import-manifest.json").read_text("utf-8"))["projects"][0]
    assert entry["project"]["slides"][0]["blocks"][1]["items"][0] == original
    assert any(issue["code"] in {"overflow","capacity"} for issue in entry["audit"]["structure_issues"])
