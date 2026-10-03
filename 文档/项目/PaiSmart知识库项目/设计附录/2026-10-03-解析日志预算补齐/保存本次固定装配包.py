"""保存共享构建输出的原文及本次固定副本，不启动、安装或切换运行通道。"""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[5]
MODULE = ROOT / "ruoyi-backend/ruoyi-paismart"
phase = sys.argv[1]
folder = Path(sys.argv[2]).resolve()
if phase not in {"before", "after"} or not folder.is_relative_to((MODULE / "target").resolve()):
    raise SystemExit("阶段必须为before/after，副本目录限定本模块target内")
folder.mkdir(exist_ok=True)
source = ROOT / "ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar"
target = folder / ("构建前共享包.jar" if phase == "before" else "ruoyi-admin.jar")
metadata = folder / (phase + "-artifact.json")
if target.exists() or metadata.exists():
    raise SystemExit("不覆盖已有固定包或记录")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


before = source.stat()
source_sha = sha(source)
shutil.copy2(source, target)
after = source.stat()
assert (before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns), "复制时共享输出变化，副本不能交付"
assert sha(target) == source_sha, "固定副本与核对源不一致"
with zipfile.ZipFile(target) as archive:
    nested_sha = hashlib.sha256(archive.read("BOOT-INF/lib/ruoyi-paismart-3.9.2.jar")).hexdigest()
if phase == "after":
    assert nested_sha == sha(MODULE / "target/ruoyi-paismart-3.9.2.jar"), "本次装配的PaiSmart不匹配本次模块"
result = {
    "phase": phase, "checkedAt": datetime.now().astimezone().isoformat(),
    "source": str(source.relative_to(ROOT)).replace("\\", "/"),
    "sourceModifiedAt": datetime.fromtimestamp(before.st_mtime).astimezone().isoformat(),
    "file": str(target.relative_to(ROOT)).replace("\\", "/"),
    "bytes": target.stat().st_size, "sha256": source_sha, "nestedPaiSmartSHA256": nested_sha,
    "scope": "共享包原文保留/本次构建固定副本；未启动业务，未覆盖配置，不表示其他模块验收或运行切换。",
}
metadata.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False))
