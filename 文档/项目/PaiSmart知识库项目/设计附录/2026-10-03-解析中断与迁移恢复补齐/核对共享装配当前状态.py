"""保留本轮原装配记录，核对稍后变化的共享包；不构建、不写源码或业务数据。"""
import hashlib
import io
import json
from datetime import datetime
from pathlib import Path
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[5]
APPENDIX = ROOT / "文档/项目/PaiSmart知识库项目/设计附录"
ROUND = Path(__file__).resolve().parent
CURRENT = APPENDIX / "2026-10-03-终版交付产物核对.json"
BACKUP = ROUND / "终版交付产物核对-共享包后来变化前原文.json"


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def local(path):
    return ROOT / Path(path.replace("\\", "/"))


original_bytes = CURRENT.read_bytes()
original = json.loads(original_bytes)
if BACKUP.exists():
    raise SystemExit("原文备份已存在，不覆盖、不重复执行")
admin = local(original["AdminJar"])
before = admin.stat()
current_admin_sha = sha(admin)
module = local(original["ModuleJar"])
worker = local(original["WorkerJar"])
assert sha(module) == sha(worker) == original["ModuleSHA256"]
with zipfile.ZipFile(admin) as package:
    nested_bytes = package.read(original["NestedEntry"])
    nested_sha = hashlib.sha256(nested_bytes).hexdigest()
    assert nested_sha == original["ModuleSHA256"]
    application = package.read("BOOT-INF/classes/application.yml").decode("utf-8")
    assert "optional:classpath:application-paismart.yml" in application
    bc = sorted(name for name in package.namelist()
                if name.startswith("BOOT-INF/lib/bc") and name.endswith(".jar"))
    assert bc == original["BouncyCastleArtifacts"]
with zipfile.ZipFile(io.BytesIO(nested_bytes)) as package:
    config = yaml.safe_load(package.read("application-paismart.yml"))["paismart"]
    expected_resources = ["META-INF/LICENSE-PaiSmart", "META-INF/NOTICE-PaiSmart.txt",
                          "eval/sample-eval.jsonl", "eval/NOTICE.txt",
                          "db/paismart/V005__evaluation_datasets.sql"]
    assert all(name in package.namelist() for name in expected_resources)
flags = {
    "module": config["enabled"],
    "object-store": config["object-store"]["enabled"],
    "object-write": config["object-store"]["writes-enabled"],
    "object-probe": config["object-store"]["probe-enabled"],
    "kafka": config["jobs"]["kafka-enabled"],
    "external-calls": config["intelligence"]["external-calls-enabled"],
    "parse-process": config["intelligence"]["parse"]["process-enabled"],
    "parse-ocr": config["intelligence"]["parse"]["ocr-enabled"],
    "aliyun-ocr": config["intelligence"]["ocr"]["aliyun-enabled"],
    "payment": config["payment"]["enabled"],
    "operations-execute": config["operations"]["execute-enabled"],
    "legacy-source": config["operations"]["legacy-source"]["enabled"],
}
assert flags == original["AllDefaultFlagsOff"] and all(value is False for value in flags.values())
after = admin.stat()
assert (before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns)
assert current_admin_sha != original["AdminSHA256"]
cos_report = APPENDIX / "2026-10-03-COS共享装配兼容通过报告/TEST-com.qcloud.cos.internal.crypto.CosRuntimeCompatibilityTest.xml"
assert cos_report.is_file()
build_log = APPENDIX / "2026-10-03-解析迁移恢复补齐后Admin构建.log"
assert build_log.is_file()
checked_at = datetime.now().astimezone().isoformat()
BACKUP.write_bytes(original_bytes)
current = dict(original)
current["time"] = checked_at
current["AdminSHA256"] = current_admin_sha
current["OriginalAdminBuildEvidence"] = {
    "time": original["time"],
    "AdminSHA256": original["AdminSHA256"],
    "evidence": str(BACKUP.relative_to(ROOT)).replace("\\", "/"),
    "evidenceSHA256": sha(BACKUP),
    "buildLog": str(build_log.relative_to(ROOT)).replace("\\", "/"),
    "buildLogSHA256": sha(build_log),
    "scope": "本轮原构建成功记录；共享 target 后来发生变化，原外层哈希不能用于当前文件。",
}
current["CurrentSharedAdminArtifact"] = {
    "checkedAt": checked_at,
    "fileModifiedAt": datetime.fromtimestamp(after.st_mtime).astimezone().isoformat(),
    "bytes": after.st_size,
    "AdminSHA256": current_admin_sha,
    "outerJarChangedSinceOriginalBuild": True,
    "NestedModuleSHA256": nested_sha,
    "PaiSmartModuleMatchesOriginalBuild": True,
    "verified": ["当前嵌套模块、普通模块与固定 Worker 一致", "默认12开关全部关闭",
                 "同版4个BC依赖", "许可、V005和示例资源入包", "可选配置导入保留"],
    "unverified": ["共享包稍后变化的执行归属及原因", "其他模块业务结果", "当前共享包真实启动与联机结果"],
    "rebuiltOrOverwrittenDuringThisCheck": False,
}
current["SharedCosCompatibility"] = dict(original["SharedCosCompatibility"])
current["SharedCosCompatibility"]["report"] = str(cos_report.relative_to(ROOT)).replace("\\", "/")
current["Scope"] = "本轮原构建记录已原文保留；当前共享包仅静态核对，不冒称稍后外层变化为本轮构建。未启动业务、未写真实库、未发布。"
CURRENT.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"passed": True, "currentAdminSHA256": current_admin_sha,
                  "originalAdminSHA256": original["AdminSHA256"],
                  "moduleSHA256": nested_sha, "defaultFlagsChecked": len(flags),
                  "originalEvidenceSHA256": sha(BACKUP), "currentEvidenceSHA256": sha(CURRENT)},
                 ensure_ascii=False))
