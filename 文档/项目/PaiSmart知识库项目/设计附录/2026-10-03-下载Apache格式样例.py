"""只下载 Apache Tika 已知 Git blob 样例；校验 blob 身份和 SHA256，不执行样例。"""
import base64
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[4]
DEST = ROOT / "ruoyi-backend/ruoyi-paismart/src/test/resources/paismart/format-samples"
PREFIX = "tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/"
FIXTURES = [
    ("LICENSE.txt", "17ea384d6121cbed828375c12212c2db8a83a3ef"),
    ("NOTICE.txt", "1c8cc05bd8865ea94f48ccc902bd0255c5cf6aef"),
    ("tika-parser-apple-module/src/test/resources/test-documents/testKeynote.key", "6e0e032f969c89dc8b9c86fe2e9e60ecc83ca710"),
    ("tika-parser-apple-module/src/test/resources/test-documents/testKeynote2013.key", "d0dd416d07684008189e99dc9f32e7f2f6ba0db9"),
    ("tika-parser-apple-module/src/test/resources/test-documents/testNumbers.numbers", "51360e01f0ecc1e5685114f98c51be37d7ab8244"),
    ("tika-parser-apple-module/src/test/resources/test-documents/testNumbers2013.numbers", "3f9a013023759ea6631fed140fb2ad139af82d6d"),
    ("tika-parser-apple-module/src/test/resources/test-documents/testPages.pages", "9fe1e40129734a4c0f1142956176b48ca1c108cb"),
    ("tika-parser-apple-module/src/test/resources/test-documents/testPages2013.pages", "b82ac7a9de648146c4304fc5ff1c782686416de7"),
    ("tika-parser-microsoft-module/src/test/resources/test-documents/testEXCEL.xls", "347d8a662fa8c565a9932531e52df81cf55823e7"),
    ("tika-parser-microsoft-module/src/test/resources/test-documents/testEXCEL.xlsx", "8d5169f84108cc4af5dd66e2c0957eb52e6622e6"),
    ("tika-parser-microsoft-module/src/test/resources/test-documents/testWORD.doc", "c1f4f3d0b0c1e475bf03e9eba3ed7c7ac166d557"),
    ("tika-parser-microsoft-module/src/test/resources/test-documents/testWORD.docx", "5ef040e98817c7e216c1b736e217619348d544d4"),
    ("tika-parser-microsoft-module/src/test/resources/test-documents/testPPT.ppt", "b48cfaf2bd7045c21c5f65e1478725e8cee84ed7"),
    ("tika-parser-microsoft-module/src/test/resources/test-documents/testPPT.pptx", "38bc6a233b32cedd88e7660867582015aae2491c"),
    ("tika-parser-miscoffice-module/src/test/resources/test-documents/testEPUB.epub", "a88df805858b35cf78c0e2f10ba0a9e3163a2f80"),
    ("tika-parser-miscoffice-module/src/test/resources/test-documents/testOpenOffice2.odt", "0b1bb11e522c56e9a58eb4ccf5f704c2d207d3ff"),
    ("tika-parser-miscoffice-module/src/test/resources/test-documents/testFooter.ods", "fddac2cc625cd20839908d16124c5126803ee040"),
    ("tika-parser-miscoffice-module/src/test/resources/test-documents/testMasterFooter.odp", "4108a4ddfd09a50ade41fea6b18692966a09e24e"),
]

def git_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()

def fetch(entry):
    relative, blob_sha = entry
    source_path = relative if relative in {"LICENSE.txt", "NOTICE.txt"} else PREFIX + relative
    url = "https://api.github.com/repos/apache/tika/git/blobs/" + blob_sha
    target = DEST / Path(relative).name
    if target.exists() and git_sha(target.read_bytes()) == blob_sha:
        data = target.read_bytes()
    else:
        request = urllib.request.Request(url, headers={"User-Agent": "PaiSmart-format-parity-check", "Accept": "application/vnd.github+json"})
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                payload = json.load(response)
            if payload.get("encoding") != "base64" or payload.get("sha") != blob_sha:
                raise ValueError("Git blob 返回身份或编码不符")
            data = base64.b64decode(payload["content"])
        except (TimeoutError, OSError):
            # 原工具已核实的源码路径；即使分支移动也必须匹配固定 blob 身份才保存。
            raw = "https://raw.githubusercontent.com/apache/tika/main/" + source_path
            with urllib.request.urlopen(urllib.request.Request(raw, headers={"User-Agent": "PaiSmart-format-parity-check"}), timeout=45) as response:
                data = response.read()
        if git_sha(data) != blob_sha:
            raise ValueError("下载样例与固定 Git blob 不符: " + source_path)
        target.write_bytes(data)
    result = {"file": target.name, "sourcePath": source_path, "sourceUrl": url, "gitBlobSha1": blob_sha, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
    print(target.name + ": verified " + str(len(data)), flush=True)
    return result

if __name__ == "__main__":
    DEST.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(fetch, FIXTURES))
    manifest = {"upstream": "Apache Tika", "licensingFiles": ["LICENSE.txt", "NOTICE.txt"], "purpose": "本地解析格式等价测试；不执行文件内脚本、宏、嵌入附件或外部OCR", "fixtures": results}
    (DEST / "provenance.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("verified=" + str(len(results)), flush=True)
