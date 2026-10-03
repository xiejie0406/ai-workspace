#!/usr/bin/env python3
"""只准备合成原资料及独立预期；不访问中心，不加载原项目，不修改产品源码。

运行：python 本文件 --workspace <工作区绝对路径>
需要本机 JDK 和已构建 CLI jar。已有 legacy-import 或本轮基线一律拒绝覆盖。
SQLite 表定义直接抽取只读原源码的 CREATE TABLE/INDEX，不自行猜测。
"""
from __future__ import annotations
import argparse, base64, collections, datetime as dt, hashlib, json, os
from pathlib import Path
import re, shutil, sqlite3, struct, subprocess, sys, zlib

STAMP = "2026-10-01T00:00:00Z"
EPOCH = int(dt.datetime.fromisoformat(STAMP.replace("Z", "+00:00")).timestamp())
SESSION = "synthetic_legacy_session_001"
THREAD = "synthetic_runtime_thread_001"
TURN = "synthetic_runtime_turn_001"
SOURCE_OWNER = "local"  # Exporter固定来源标签；不是若依账号，严禁当作真实用户。

BRIDGE = r'''
import com.paicli.runner.RunnerPrivateDirectory;
import com.paicli.runner.ResourceFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
public class LegacyImportPrivateFiles {
 public static void main(String[] a) throws Exception {
  Path p=Path.of(a[1]).toAbsolutePath().normalize();ObjectMapper j=new ObjectMapper();
  if(a[0].equals("mkdir")){System.out.println(RunnerPrivateDirectory.createDirectories(p).toRealPath());return;}
  if(a[0].equals("put")){RunnerPrivateDirectory.writeAtomic(p.getParent(),p.getFileName().toString(),System.in.readAllBytes());return;}
  if(a[0].equals("require-file")){RunnerPrivateDirectory.requireRegularFile(p);return;}
  if(a[0].equals("fingerprint")){RunnerPrivateDirectory.requireDirectory(p);var r=new ResourceFingerprint(p);System.out.println(j.writeValueAsString(Map.of("root",r.root().toString(),"rootFingerprint",r.rootFingerprint(),"source","actual frozen native ResourceFingerprint constructor")));return;}
  RunnerPrivateDirectory.requireDirectory(p);List<Object> nodes=new ArrayList<>();
  for(Path n=p;n!=null;n=n.getParent())nodes.add(view(n));
  if(a[0].equals("check-tree"))try(var s=Files.walk(p)){
   for(Path n:s.sorted().toList()){
    if(Files.isSymbolicLink(n)||!n.equals(n.toRealPath()))throw new Exception("样本含链接或目录跳转");
    if(Files.isDirectory(n,LinkOption.NOFOLLOW_LINKS))RunnerPrivateDirectory.requireDirectory(n);
    else if(Files.isRegularFile(n,LinkOption.NOFOLLOW_LINKS)){
     var owner=Files.getOwner(n);var acl=Files.getFileAttributeView(n,AclFileAttributeView.class);
     if(!owner.equals(Files.getOwner(p))||acl==null)throw new Exception("样本所有者不一致或无ACL");
     for(var e:acl.getAcl())if(e.type()==AclEntryType.ALLOW&&!e.principal().equals(owner)&&!e.permissions().isEmpty())throw new Exception("样本文件非私有");
    }else throw new Exception("样本非普通文件");nodes.add(view(n));
   }
  }
  System.out.println(j.writeValueAsString(Map.of("canonical",p.toRealPath().toString(),"nodes",nodes,"checked",true)));
 }
 static Object view(Path p)throws Exception{
  List<Object> entries=new ArrayList<>();var v=Files.getFileAttributeView(p,AclFileAttributeView.class);
  if(v!=null)for(var e:v.getAcl())entries.add(Map.of("principal",e.principal().getName(),"type",e.type().name(),"flags",e.flags().stream().map(Enum::name).sorted().toList(),"permissions",e.permissions().stream().map(Enum::name).sorted().toList()));
  return Map.of("path",p.toString(),"owner",Files.getOwner(p).getName(),"acl",entries);
 }
}
'''

def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def compact(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

def pretty(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

def inventory(root: Path, exclude: str | None = None):
    result = []
    for p in sorted(root.rglob("*"), key=lambda x: x.relative_to(root).as_posix()):
        rel = p.relative_to(root).as_posix()
        if exclude and (rel == exclude or rel.startswith(exclude + "/")):
            continue
        if p.is_symlink():
            raise RuntimeError("目录包含链接，不读取：" + str(p))
        if p.is_file():
            st = p.stat()
            result.append({"name": rel, "size": st.st_size, "sha256": sha(p.read_bytes()), "modifiedNs": st.st_mtime_ns})
    return result

def original_inventory(samples: Path):
    # 其他代理本轮新建rules-skills/protocol-fixtures不属于原15文件，不扫描或误记为修改。
    result = []
    for group in ["A", "B"]:
        for item in inventory(samples / group):
            result.append({**item, "name": group + "/" + item["name"]})
    p = samples / "sample-manifest-before-20261003T140647857767.json"
    st = p.stat()
    result.append({"name": p.name, "size": st.st_size, "sha256": sha(p.read_bytes()), "modifiedNs": st.st_mtime_ns})
    return sorted(result, key=lambda x: x["name"])

def safe_run(args, *, data=None, timeout=90):
    # 不继承中心、供应商、微信、数据库等凭证；只保留启动JDK所需基础系统变量。
    allowed = {"SystemRoot", "WINDIR", "PATH", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "HOMEDRIVE", "HOMEPATH", "JAVA_HOME"}
    env = {k: v for k, v in os.environ.items() if k.upper() in {x.upper() for x in allowed}}
    env["PYTHONIOENCODING"] = "utf-8"
    return subprocess.run([str(x) for x in args], input=data, capture_output=True, timeout=timeout, env=env)

def extract_ddl(workspace: Path):
    paths = ["参考项目/paicli-main/src/main/java/com/paicli/runtime/api/RuntimeThreadStore.java",
             "参考项目/paicli-main/src/main/java/com/paicli/runtime/task/DurableTaskManager.java"]
    statements, origins = [], []
    for name in paths:
        raw = (workspace / name).read_bytes()
        text = raw.decode("utf-8-sig")
        tables = [s.strip() for s in re.findall(r'stmt\.execute\("""(.*?)"""\);', text, re.S) if s.strip().startswith("CREATE TABLE")]
        indexes = re.findall(r'stmt\.execute\("(CREATE INDEX[^\"]+)"\);', text)
        statements.extend(tables + indexes)
        origins.append({"path": str(workspace / name), "sha256": sha(raw), "extractedStatements": tables + indexes})
    assert len(statements) == 6 and len([s for s in statements if s.startswith("CREATE TABLE")]) == 3
    return ";\n".join(statements) + ";\n", origins

def make_png():
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\0\xff\0\0\0\xff\0\0\0\0\xff\xff\xff\xff")) + chunk(b"IEND", b"")

def message(role, text, **extra):
    return {"role": role, "content": text, "reasoningContent": None, "toolCalls": None,
            "toolCallId": None, "contentParts": None, **extra}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", required=True, type=Path)
    args = parser.parse_args()
    workspace = args.workspace.resolve(strict=True)
    evidence = Path(__file__).resolve().parent
    baseline_path = evidence / "原资料导入样本基线.json"
    if baseline_path.exists():
        raise RuntimeError("已有基线，拒绝覆盖；本脚本仅CREATE_NEW")
    pointer = workspace / ".runtime/paicli/current-runtime.txt"
    pointer_bytes = pointer.read_bytes()
    runtime = Path(pointer_bytes.decode("utf-8-sig").strip()).resolve(strict=True)
    samples = runtime / "acceptance-samples"
    target = samples / "legacy-import"
    if not samples.is_dir() or target.exists():
        raise RuntimeError("验收样本父目录不存在或legacy-import已存在；不覆盖")
    before = original_inventory(samples)
    if len(before) != 16:
        raise RuntimeError("已有样本数量变化，停止：预期15个原资料文件和1个既有清单")
    cli = workspace / "ruoyi-backend/ruoyi-paicli/paicli-cli/target/paicli-cli-3.9.2.jar"
    java_home = Path(os.environ.get("JAVA_HOME", r"C:\Users\admin\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot"))
    java, javac = java_home / "bin/java.exe", java_home / "bin/javac.exe"
    helper = evidence / "LegacyImportPrivateFiles.java"
    classes = evidence / "legacy-import-preparer-classes"
    if helper.exists() or classes.exists():
        if not helper.exists() or helper.read_bytes() != BRIDGE.encode("utf-8") or not (classes / "LegacyImportPrivateFiles.class").is_file():
            raise RuntimeError("已有准备器支持文件不匹配，拒绝覆盖")
    else:
        helper.write_text(BRIDGE, encoding="utf-8", newline="\n")
        classes.mkdir()
        compiled = safe_run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", cli, "-d", classes, helper])
        if compiled.returncode:
            raise RuntimeError("私有目录准备器编译失败：" + compiled.stderr.decode("utf-8", errors="replace"))
    helper_cmd = [java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", str(classes) + os.pathsep + str(cli), "LegacyImportPrivateFiles"]
    def bridge(operation, path, data=None):
        result = safe_run(helper_cmd + [operation, path], data=data)
        if result.returncode:
            raise RuntimeError("私有边界拒绝：" + result.stderr.decode("utf-8", errors="replace"))
        return result.stdout.decode("utf-8").strip()
    acl_before = json.loads(bridge("check-tree", samples))
    bridge("mkdir", target)
    def put(relative, data, fixed=True):
        p = target / relative
        if not p.resolve().is_relative_to(target.resolve()):
            raise RuntimeError("样本写入越界")
        bridge("mkdir", p.parent)
        bridge("put", p, data if isinstance(data, bytes) else data.encode("utf-8"))
        if fixed:
            os.utime(p, (EPOCH, EPOCH))
        return p
    def dump(relative, value):
        return put(relative, pretty(value))
    fingerprints = {group: json.loads(bridge("fingerprint", samples / group)) for group in ["A", "B"]}
    dump("technical/workspace-root-fingerprints.json", {"sourceJarSha256": sha(cli.read_bytes()), "businessBinding": "NOT_ENROLLED", "roots": fingerprints})
    ddl, origins = extract_ddl(workspace)
    put("schema/original-runtime.sql", ddl)
    source = target / "source-consistent"
    bridge("mkdir", source)
    staging = target / "construction"
    bridge("mkdir", staging)
    db = staging / "synthetic-runtime.sqlite"
    bridge("put", db, b"")
    writer = sqlite3.connect(db)
    writer.execute("PRAGMA journal_mode=WAL")
    writer.execute("PRAGMA wal_autocheckpoint=0")
    writer.execute("PRAGMA cache_spill=OFF")
    writer.executescript(ddl)
    task_rows = []
    for i, status in enumerate(["running", "enqueued", "completed", "failed", "cancelled"], 1):
        terminal = status in {"completed", "failed", "cancelled"}
        row = ("synthetic_task_" + status, status, "合成任务：" + status + "，不调用模型", compact({"summary": "合成已完成结果", "synthetic": True}).decode() if status == "completed" else None,
               "合成失败说明" if status == "failed" else None, STAMP, STAMP if status != "enqueued" else None,
               "2026-10-01T00:00:02Z" if terminal else None, STAMP, 2000 if terminal else 0)
        writer.execute("INSERT INTO runtime_tasks VALUES (?,?,?,?,?,?,?,?,?,?)", row)
        task_rows.append(row)
    writer.execute("INSERT INTO runtime_threads VALUES (?,?)", (THREAD, STAMP))
    runtime_events = [("turn.started", {"turn_id": TURN, "input": "合成Runtime输入"}),
                      ("message.delta", {"turn_id": TURN, "content": "合成增量；不是完整账本消息"}),
                      ("turn.completed", {"turn_id": TURN, "status": "completed"}),
                      ("approval.requested", {"synthetic": True, "note": "历史授权事件必须被排除"})]
    for kind, value in runtime_events:
        writer.execute("INSERT INTO runtime_events(thread_id,type,data,created_at) VALUES (?,?,?,?)", (THREAD, kind, compact(value).decode(), STAMP))
    writer.commit()
    writer.execute("BEGIN IMMEDIATE")
    writer.execute("INSERT INTO runtime_tasks(id,status,prompt,created_at) VALUES (?,?,?,?)", ("synthetic_uncommitted_excluded", "running", "未提交任务必须排除", STAMP))
    source_pair = [db, Path(str(db) + "-wal")]
    wal_before = [{"name": p.name, "size": p.stat().st_size, "sha256": sha(p.read_bytes())} for p in source_pair]
    for p in source_pair:
        put("source-consistent/" + p.name, p.read_bytes())
    wal_after = [{"name": p.name, "size": p.stat().st_size, "sha256": sha(p.read_bytes())} for p in source_pair]
    assert wal_before == wal_after
    writer.rollback()
    writer.close()  # 只关闭合成构造库，冻结来源DB+WAL不被打开或恢复。
    assert not (source / "synthetic-runtime.sqlite-shm").exists()
    verification = target / "verification-copy"
    bridge("mkdir", verification)
    for name in ["synthetic-runtime.sqlite", "synthetic-runtime.sqlite-wal"]:
        put("verification-copy/" + name, (source / name).read_bytes())
    check = sqlite3.connect(verification / "synthetic-runtime.sqlite")
    actual_integrity = check.execute("PRAGMA integrity_check").fetchone()[0]
    actual_tasks = check.execute("SELECT id,status FROM runtime_tasks ORDER BY id").fetchall()
    actual_events = check.execute("SELECT type,data FROM runtime_events ORDER BY id").fetchall()
    assert actual_integrity == "ok" and len(actual_tasks) == 5 and not any("uncommitted" in r[0] for r in actual_tasks)
    check.close()
    image = make_png()
    image_hash = sha(image)
    put("attachments/synthetic-image-2x2.png", image)
    user = message("user", "合成账本输入与附件", contentParts=[{"type": "text", "text": "合成文本", "imageBase64": None, "imageUrl": None, "mimeType": None},
                    {"type": "image_base64", "text": None, "imageBase64": base64.b64encode(image).decode(), "imageUrl": None, "mimeType": "image/png"}])
    assistant = message("assistant", "合成工具提案", reasoningContent="合成推理，不是真实供应商输出", toolCalls=[{"id": "synthetic_toolcall_01", "function": {"name": "read_file", "arguments": '{"path":"README.md"}'}}])
    tool = message("tool", "合成工具结果", toolCallId="synthetic_toolcall_01")
    final = message("assistant", "合成终结回复")
    ledger = []
    for sequence, (event, actor, msg) in enumerate([("turn.started", "synthetic_user", None), ("message", "synthetic_user", user), ("message", "synthetic_agent", assistant), ("message", "synthetic_tool", tool), ("message", "synthetic_agent", final), ("turn.completed", "synthetic_agent", None)], 1):
        ledger.append({"schemaVersion": 1, "sessionId": SESSION, "sequence": sequence, "timestamp": EPOCH * 1000 + sequence,
                       "event": event, "mode": "react", "actor": actor, "source": "synthetic_acceptance", "message": msg,
                       "metadata": {"synthetic": True, "turnId": "synthetic_ledger_turn_001", "ordinal": sequence}})
    put("source-consistent/history/synthetic-ledger.jsonl", b"".join(compact(row) + b"\n" for row in ledger))
    memories = [{"id": "synthetic_memory_project_001", "content": "合成项目事实，仅供导入验收", "type": "FACT", "timestamp": STAMP, "lastVerifiedAt": "2026-10-01T00:00:01Z", "metadata": {"scope": "project", "synthetic": "true"}, "tokenCount": 13},
                {"id": "synthetic_memory_personal_001", "content": "合成待核实个人事实，不代表真实用户偏好", "type": "FACT", "timestamp": STAMP, "lastVerifiedAt": STAMP, "metadata": {"scope": "global", "verification_pending": "true", "synthetic": "true"}, "tokenCount": 22}]
    dump("source-consistent/.paicli/long_term_memory.json", memories)
    pai = "# 合成项目规则\n\n仅对导入样本：使用中文注释。不得自动启用。\n"
    local = "# 合成本地覆盖\n\n仅对导入样本：保留 PAI.local 层，按中心明确登记后启用。\n"
    prompt = "合成 React 提示词覆盖；只供导入验收，导入后默认禁用。\n"
    put("source-consistent/PAI.md", pai)
    put("source-consistent/PAI.local.md", local)
    put("source-consistent/prompts/agent/react_system.md", prompt)
    rejection = target / "source-rejected-json"
    bridge("mkdir", rejection)
    rejected_db = rejection / "synthetic-invalid-event.sqlite"
    bridge("put", rejected_db, b"")
    invalid = sqlite3.connect(rejected_db)
    invalid.executescript(ddl)
    invalid.execute("INSERT INTO runtime_threads VALUES (?,?)", ("synthetic_rejected_thread", STAMP))
    invalid.execute("INSERT INTO runtime_events(thread_id,type,data,created_at) VALUES (?,?,?,?)", ("synthetic_rejected_thread", "message.delta", '{"content":', STAMP))
    invalid.commit()
    invalid.close()
    os.utime(rejected_db, (EPOCH, EPOCH))

    # 独立golden先依据上面的合成输入和已读转换规则建成，不读取Exporter输出。
    def legacy(name, ident):
        return sha(name.encode())[:16] + ":" + ident
    def record(kind, ident, payload, occurred=STAMP):
        return {"kind": kind, "legacyId": ident, "legacyOwnerId": SOURCE_OWNER, "occurredAt": occurred, "payload": payload}
    expected = []
    db_name = "synthetic-runtime.sqlite"
    for row in task_rows:
        payload = dict(zip(["id", "status", "prompt", "result", "error", "created_at", "started_at", "finished_at", "updated_at", "duration_ms"], row))
        old = payload["status"].upper()
        payload.update(legacyStatus=old, status=old if old in {"COMPLETED", "FAILED", "CANCELLED"} else "PAUSED_IMPORTED" if old == "ENQUEUED" else "RECONCILE_REQUIRED", input=row[2], startedAt=row[6], finishedAt=row[7], durationMs=row[9], source="LEGACY_TASK")
        expected.append(record("TASK", legacy(db_name, row[0]), payload))
    runtime_session = legacy(db_name, "thread:" + THREAD)
    expected.append(record("SESSION", runtime_session, {"id": THREAD, "created_at": STAMP, "title": THREAD, "status": "HISTORICAL", "mode": "REACT", "approvalMode": "ASK"}))
    for i, (kind, value) in enumerate(runtime_events[:3], 1):
        expected.append(record("EVENT", legacy(db_name, "event:" + str(i)), {"data": value, "type": kind, "sequence": i, "legacySessionId": runtime_session, "evidenceComplete": False}))
    ledger_session = "ledger:" + SESSION
    expected.append(record("SESSION", ledger_session, {"title": SESSION, "mode": "REACT", "status": "HISTORICAL", "approvalMode": "ASK"}, "2026-10-01T00:00:00.001Z"))
    for row in ledger:
        occurred = "2026-10-01T00:00:00.%03dZ" % row["sequence"]
        meta = {k: v for k, v in row.items() if k != "message"}
        if row["message"] is None:
            payload = {**row, "legacySessionId": ledger_session, "evidenceComplete": True, "_ledger": meta}
            expected.append(record("LEDGER_EVENT", legacy("history/synthetic-ledger.jsonl", "event:" + str(row["sequence"])), payload, occurred))
        else:
            payload = json.loads(compact(row["message"]))
            if row["sequence"] == 2:
                # sanitize对所有对象都删除imageBase64键，包括text part里的null占位。
                payload["contentParts"][0].pop("imageBase64", None)
                payload["contentParts"][1] = {"text": None, "type": "image_omitted", "legacyAttachmentSha256": image_hash, "sha256": image_hash, "size": len(image), "mimeType": "image/png"}
            payload.update(legacySessionId=ledger_session, sequence=row["sequence"], _ledger=meta)
            expected.append(record("MESSAGE", legacy("history/synthetic-ledger.jsonl", "message:" + str(row["sequence"])), payload, occurred))
    for i, entry in enumerate(memories):
        payload = {**entry, "scope": "PROJECT" if i == 0 else "PERSONAL", "status": "VERIFIED" if i == 0 else "PENDING", "source": "LEGACY_MEMORY", "verifiedAt": entry["lastVerifiedAt"]}
        expected.append(record("MEMORY", legacy(".paicli/long_term_memory.json", entry["id"]), payload))
    for name, content, variant, display in [("PAI.md", pai, "PROJECT", "PAI"), ("PAI.local.md", local, "LOCAL_OVERRIDE", "PAI.local")]:
        expected.append(record("POLICY", legacy(name, "pai"), {"name": display, "relativePath": name, "variant": variant, "scope": "PROJECT", "sourceSha256": sha(content.encode()), "content": content, "status": "IMPORTED_UNVERIFIED"}))
    expected.append(record("PROMPT", legacy("prompts/agent/react_system.md", "agent/react_system.md"), {"relativePath": "agent/react_system.md", "scope": "PROJECT", "content": prompt, "sha256": sha(prompt.encode()), "sourceSha256": sha(prompt.encode()), "enabled": False, "status": "IMPORTED_UNVERIFIED"}))
    expected_counts = {"TASK": 5, "SESSION": 2, "EVENT": 3, "MESSAGE": 4, "LEDGER_EVENT": 2, "MEMORY": 2, "POLICY": 2, "PROMPT": 1}
    assert dict(collections.Counter(r["kind"] for r in expected)) == expected_counts and len(expected) == 21
    golden = {"schemaVersion": 1, "synthetic": True, "status": "PREPARED_NOT_CENTRAL_IMPORTED", "expectedRecordCount": 21,
              "expectedCounts": expected_counts, "expectedRecords": sorted(expected, key=lambda r: (r["kind"], r["legacyId"])),
              "sourceRules": {"ledgerSchemaVersion": 1, "uncommittedTaskExcluded": "synthetic_uncommitted_excluded", "runtimeDeltaIsEVENT": True, "historicalApprovalExcluded": True, "noOriginalRuntimeStartup": True, "noSyntheticIdentityAsRealUser": True},
              "centralExpectations": {"firstCommit": {"status": "NOT_EXECUTED", "newRecords": 21, "sessions": "HISTORICAL", "running": "RECONCILE_REQUIRED", "queued": "PAUSED_IMPORTED", "noTaskDispatch": True, "POLICY": "PENDING, enabled=false, ROOT_PAI / LOCAL_PAI", "PROMPT": "disabled PROJECT", "EVENT": "IMPORTED_UNVERIFIED"},
                                      "duplicateNewBatch": {"status": "NOT_EXECUTED", "newRecords": 0, "importedRecords": [], "sameFingerprintScopeMappedOwnerKindLegacyId": True},
                                      "changedPayloadSameIdentity": {"status": "NOT_EXECUTED", "expectedHttpStatus": 409},
                                      "missingAttachmentMapping": {"status": "NOT_EXECUTED", "canImport": False},
                                      "revokeModifiedImportedRow": {"status": "NOT_EXECUTED", "expectedHttpStatus": 409}},
              "attachment": {"file": "attachments/synthetic-image-2x2.png", "sha256": image_hash, "size": len(image), "mimeType": "image/png", "dimensions": [2, 2], "mapKey": image_hash, "requiresUploadedTURN_IMAGE": True},
              "walMethod": "原DDL合成写入；WAL autockpt=0；commit后BEGIN IMMEDIATE未提交行；在同一写线程静止时按DB+WAL稳定摘要复制；SHM不复制；只在另一个私有验证副本查询，冻结来源不开启原Store/恢复", 
              "rejection": {"case": "source-rejected-json，真实原DDL但runtime_events.data非法JSON", "expectedExit": 2, "expectedOutputExists": False, "expectedErrorContains": "来源JSON格式无效"}}
    golden_file = dump("golden.json", golden)
    mapping = {"synthetic": True, "executable": False, "legacyOwnerId": "local", "bodyTemplate": {"workspaceId": "REQUIRED_AUTHORIZED_WORKSPACE_ID", "artifactId": "REQUIRED_LEGACY_IMPORT_ARTIFACT_ID", "identityMappings": {"local": "REQUIRED_ACTIVE_RUOYI_MEMBER_ID"}, "attachmentMappings": {image_hash: "REQUIRED_SAME_OWNER_TURN_IMAGE_ARTIFACT_ID"}}, "note": "占位符故意不能提交。只供获授权的工作区管理员替换，不把synthetic身份作为真实账号。"}
    dump("import-mapping.template.json", mapping)
    put("说明.md", "# 原资料导入合成样本\n\n所有身份均为 synthetic，不是真实用户或业务资料；无凭证、无外部调用。\n\nsource-consistent：原Runtime真实DDL的DB/WAL稳定副本、完整JSONL账本、记忆、PAI.local和明确PROJECT提示词。construction和verification-copy仅为本次构造/技术核对衍生副本，不能作为导入来源。\n\nexports/bundle-first.json和bundle-repeat.json是本机已构建CLI离线导出，两次应有相同fingerprint与records。golden.json在读取导出前独立生成。attachments单独上传TURN_IMAGE后按真实字节SHA映射。source-rejected-json为非法事件JSON拒绝样本，不上传作为成功数据。\n\n需要真实若依管理员身份、所属工作区、active成员与LEGACY_IMPORT/TURN_IMAGE附件；本轮未向中心预检/提交，重复导入/撤销仍待实际验收。合成数据不含真实授权，历史approval事件不导入。只读原来源，不启动原Runtime恢复running任务。\n")
    exports = target / "exports"
    bridge("mkdir", exports)
    logs, checks = [], []
    source_before = inventory(source)
    for label, src, output, scope in [("valid-first", source, exports / "bundle-first.json", "PROJECT"), ("valid-repeat", source, exports / "bundle-repeat.json", "PROJECT"), ("reject-invalid-json", rejection, exports / "must-not-exist.json", "PROJECT"), ("reject-unspecified-prompt-scope", source, exports / "must-not-exist-prompt.json", None)]:
        cmd = [java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", cli, "export-legacy", "--source", src, "--output", output]
        if scope:
            cmd.extend(["--prompt-scope", scope])
        run = safe_run(cmd)
        body = b"COMMAND " + compact([str(x) for x in cmd]) + b"\nEXIT " + str(run.returncode).encode() + b"\nSTDOUT\n" + run.stdout + b"\nSTDERR\n" + run.stderr
        log_path = evidence / ("原资料导入样本-" + label + ".log")
        with log_path.open("xb") as f:
            f.write(body)
        logs.append({"case": label, "path": str(log_path), "sha256": sha(body), "exit": run.returncode})
        expected_exit = 0 if label.startswith("valid") else 2
        checks.append({"case": label, "expectedExit": expected_exit, "actualExit": run.returncode, "outputExists": output.exists(), "passed": run.returncode == expected_exit and output.exists() == (expected_exit == 0)})
        if not checks[-1]["passed"]:
            raise RuntimeError("离线样本检查失败，保留日志和现场：" + label)
    first = json.loads((exports / "bundle-first.json").read_bytes())
    repeat = json.loads((exports / "bundle-repeat.json").read_bytes())
    actual_records = sorted(first["records"], key=lambda r: (r["kind"], r["legacyId"]))
    if actual_records != golden["expectedRecords"]:
        mismatch = {"expected": golden["expectedRecords"], "actual": actual_records}
        dump("golden-mismatch.json", mismatch)
        raise RuntimeError("导出内容与独立golden不一致，未改golden；保留mismatch")
    assert first["sourceFingerprint"] == repeat["sourceFingerprint"] and first["records"] == repeat["records"]
    assert not any("imageBase64" in compact(record).decode() for record in first["records"])
    assert source_before == inventory(source)
    after = original_inventory(samples)
    assert before == after and pointer.read_bytes() == pointer_bytes
    acl_after = json.loads(bridge("check-tree", samples))
    # 检查新文件的严格0600等价ACL。SQLite衍生文件继承private父的file-only权限。
    new_inventory = inventory(target)
    for item in new_inventory:
        bridge("require-file", target / item["name"])
    product_origins = []
    for relative in ["参考项目/paicli-main/src/main/java/com/paicli/history/ConversationLedger.java", "参考项目/paicli-main/src/main/java/com/paicli/llm/LlmClient.java", "参考项目/paicli-main/src/main/java/com/paicli/memory/LongTermMemory.java", "ruoyi-backend/ruoyi-paicli/paicli-cli/src/main/java/com/paicli/cli/LegacyBundleExporter.java", "ruoyi-backend/ruoyi-paicli/paicli-api/src/main/java/com/ruoyi/paicli/service/PcImportService.java"]:
        p = workspace / relative
        if not p.exists():
            raise RuntimeError("实际事实源位置变化：" + relative)
        product_origins.append({"path": str(p), "sha256": sha(p.read_bytes())})
    baseline = {"schemaVersion": 1, "preparedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "task": "原SQLite/WAL脱敏合成导入样本准备", "status": "PREPARED_NOT_CENTRAL_IMPORTED", "runtimePointer": str(pointer), "runtimePointerSha256": sha(pointer_bytes), "runtimeRoot": str(runtime), "sampleRoot": str(target), "sourceIdentity": "synthetic_*；Exporter legacyOwnerId=local不是若依UserId", "credentialsPresent": False, "externalRequests": 0, "realBusinessDatabaseTouched": False, "productSourceChanged": False,
                "existingProtection": {"count": len(before), "originalSampleFiles": 15, "preexistingManifestFiles": 1, "before": before, "after": after, "allUnchanged": before == after, "otherAgentsNewDirectoriesExcluded": ["rules-skills", "protocol-fixtures"], "privateAclBefore": acl_before, "privateAclAfter": acl_after},
                "schemaOrigins": origins, "sourceOrigins": product_origins, "exporterJar": {"path": str(cli), "sha256": sha(cli.read_bytes()), "size": cli.stat().st_size}, "javaRuntime": {"path": str(java), "note": "使用已安装JDK21执行；准备器编译--release17，不代表Java17实跑"},
                "sqliteVerification": {"integrityCheck": actual_integrity, "committedTasks": actual_tasks, "runtimeEventCountIncludingFilteredApproval": len(actual_events), "walBefore": wal_before, "walAfter": wal_after, "stablePair": wal_before == wal_after, "uncommittedExcluded": True, "frozenSourceUnchangedByExporter": True, "shmCopied": False},
                "golden": {"path": str(golden_file), "sha256": sha(golden_file.read_bytes()), "createdBeforeExporterOutputs": True, "expectedRecordCount": 21, "expectedCounts": expected_counts, "exactPayloadComparisonPassed": True}, "offlineChecks": checks, "logs": logs,
                "repeatOfflineExport": {"sourceFingerprint": first["sourceFingerprint"], "sameFingerprint": True, "sameRecords": True, "centralDuplicateImport": "NOT_EXECUTED"}, "newFiles": new_inventory, "newFileCount": len(new_inventory), "newFilesManifestSha256": sha(compact(new_inventory)),
                "actualNativeRootFingerprints": fingerprints,
                "remainingPrerequisites": ["平台已授权管理员认证与workspace ADMIN/import:manage/artifact:write", "真实active若依成员identityMappings；禁止填合成ID", "上传LEGACY_IMPORT bundle与同owner TURN_IMAGE后填实际artifactId/attachmentMappings", "中心preflight→commit和重复新批次/修改冲突/撤销仍未执行", "实际原资料必须停止或静止写入；本样本只证明同一合成写线程quiescent DB+WAL稳定复制，不宣称任意活跃writer快照正确"]}
    with baseline_path.open("xb") as f:
        f.write(pretty(baseline))
    print(json.dumps({"status": baseline["status"], "sampleRoot": str(target), "newFileCount": len(new_inventory), "goldenRecords": 21, "goldenSha256": baseline["golden"]["sha256"], "baseline": str(baseline_path), "baselineSha256": sha(baseline_path.read_bytes()), "newFilesManifestSha256": baseline["newFilesManifestSha256"], "existingFilesUnchanged": len(before), "offlineChecks": checks}, ensure_ascii=False))

if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print("样本准备未完成：" + str(failure), file=sys.stderr)
        raise SystemExit(2)
