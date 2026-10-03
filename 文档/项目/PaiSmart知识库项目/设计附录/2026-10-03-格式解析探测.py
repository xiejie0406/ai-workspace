"""使用实际Worker和已装配依赖解析固定样例，保留独立进程退出及正文证据。"""
import concurrent.futures
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[4]
MODULE = ROOT / "ruoyi-backend/ruoyi-paismart"
SAMPLES = MODULE / "src/test/resources/paismart/format-samples"
RUN = Path(__file__).resolve().parent / "2026-10-03-格式解析探测" / datetime.now().strftime("%H%M%S")
CLASSPATH = os.pathsep.join([str(MODULE / "target/classes"), str(ROOT / "ruoyi-backend/ruoyi-common/target/classes"), str(MODULE / "target/parser-worker/lib/*")])

def parse(sample):
    target = RUN / (sample.name + ".json")
    command = [shutil.which("java"), "-Xmx256m", "-Djava.awt.headless=true", "-cp", CLASSPATH, "com.ruoyi.paismart.infrastructure.intelligence.ParserWorker", str(sample), str(target), "100", "200000"]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=50)
    (RUN / (sample.name + ".stderr.log")).write_bytes(result.stderr)
    row = {"file": sample.name, "sha256": hashlib.sha256(sample.read_bytes()).hexdigest(), "exitCode": result.returncode, "command": command}
    if target.exists():
        document = json.loads(target.read_text(encoding="utf-8"))
        text = "\n".join(page["text"] for page in document.get("pages", []))
        row.update({"pageCount": len(document.get("pages", [])), "textChars": len(text), "excerpt": text[:250]})
    print(sample.name + " exit=" + str(result.returncode) + " chars=" + str(row.get("textChars")), flush=True)
    return row

if __name__ == "__main__":
    RUN.mkdir(parents=True, exist_ok=False)
    fixtures = sorted(path for path in SAMPLES.iterdir() if path.suffix.lower() not in {".txt", ".json"})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(parse, fixtures))
    (RUN / "results.json").write_text(json.dumps({"note": "技术解析探测；正文是否符合独立预期另在格式测试判定，进程exit0不自动判业务通过", "workerSourceSha256": hashlib.sha256((MODULE / "src/main/java/com/ruoyi/paismart/infrastructure/intelligence/ParserWorker.java").read_bytes()).hexdigest(), "results": results}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("evidence=" + str(RUN), flush=True)
