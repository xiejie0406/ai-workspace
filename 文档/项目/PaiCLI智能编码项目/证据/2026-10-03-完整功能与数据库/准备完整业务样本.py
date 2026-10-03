"""只在本轮用户私有运行目录补验收文件；不建账号、不写数据库、不调用产品接口。"""
import argparse
import hashlib
import io
import json
import os
import re
import struct
import zlib
from datetime import datetime
from pathlib import Path

from PIL import Image, features


def sha(content):
    return hashlib.sha256(content).hexdigest()


def check_path(path):
    for node in [path, *path.parents]:
        if node.exists() and (node.is_symlink() or getattr(node.stat(), "st_file_attributes", 0) & 0x400):
            raise RuntimeError("样本路径存在跳转，未写入")


def write_new(path, content):
    check_path(path)
    if path.exists():
        if not path.is_file() or path.read_bytes() != content:
            raise RuntimeError("样本已存在不同原文，未覆盖")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    check_path(path)
    with path.open("xb") as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--manifest", required=True)
    args = parser.parse_args()
    runtime = Path(args.runtime).absolute()
    if runtime.parent != Path.home() or not re.fullmatch(r"paicli-local-\d{8}T\d{6}-[a-f0-9]{32}", runtime.name):
        raise RuntimeError("仅接受当前用户目录下的本轮私有运行目录")
    check_path(runtime)
    manifest = Path(args.manifest)
    baseline = json.loads(manifest.read_text(encoding="utf-8-sig"))
    root = runtime / "acceptance-samples"
    if Path(baseline["PrivateRoot"]) != root:
        raise RuntimeError("原样本基线不属于本次运行目录")
    for item in baseline["Files"]:
        original = root / item["Workspace"] / item["Path"]
        check_path(original)
        if sha(original.read_bytes()) != item["SHA256"]:
            raise RuntimeError("原七文件样本已变化，未修改基线")

    source = ("# 大原文规则\r\n@imports/code-style.md\r\n" + "保留中文原文与CRLF；用于检验正式来源SHA、上传和完整下载。\r\n" * 16000 + "原文结束标记：PAI_SOURCE_END\r\n").encode("utf-8")
    imported = "# 导入规则\r\nJava类名使用大写字母开头。\r\n".encode("utf-8")
    files = {"规则/PAI-large-source.md": source, "规则/imports/code-style.md": imported, "规则/invalid-utf8.bin": bytes([0xC3, 0x28])}
    image = Image.new("RGBA", (32, 24), (255, 255, 255, 0))
    for x in range(4, 28):
        for y in range(4, 20):
            image.putpixel((x, y), (20, 90, 180, 255))
    for extension, format_name in [("png", "PNG"), ("jpg", "JPEG"), ("webp", "WEBP")]:
        if format_name == "WEBP" and not features.check("webp"):
            raise RuntimeError("当前样本工具缺WebP编码能力；未伪造WebP")
        output = io.BytesIO()
        target = image.convert("RGB") if format_name == "JPEG" else image
        target.save(output, format=format_name, **({"lossless": True} if format_name == "WEBP" else {}))
        files["图片/valid-" + extension + "." + extension] = output.getvalue()
    files["图片/invalid-image.png"] = b"not an image\n"
    ihdr = struct.pack(">IIBBBBB", 40000001, 1, 8, 6, 0, 0, 0)
    chunk = lambda kind, data: struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    files["图片/oversized-pixel-header.png"] = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IEND", b"")
    existing = {(item["Workspace"], item["Path"]) for item in baseline["Files"]}
    for relative, content in files.items():
        write_new(root / "A" / relative, content)
        if ("A", relative) not in existing:
            baseline["Files"].append({"Workspace": "A", "Path": relative, "Size": len(content), "SHA256": sha(content)})
    baseline["Expected"].update({"PAISource": {"SourcePath": "PAI.md", "UTF8Bytes": len(source), "SHA256": sha(source),
                                               "CRLFCount": source.count(b"\r\n"), "FinalMarker": "PAI_SOURCE_END", "ImportPath": "imports/code-style.md", "ImportSHA256": sha(imported)},
                                 "Images": {"ValidFormats": ["PNG", "JPEG", "WEBP"], "Width": 32, "Height": 24,
                                            "PNGAndWebPTransparentCorner": True, "InvalidImage": "拒绝且不创建产物", "OversizedPixels": 40000001,
                                            "OversizedExpected": "在像素预算检查阶段拒绝，不解码分配40000001像素"},
                                 "InvalidPAISource": "严格UTF-8校验拒绝且不创建产物"})
    stamp = datetime.now().astimezone().isoformat()
    baseline["SupplementedAt"] = stamp
    baseline["BusinessExecuted"] = 0
    backup = root / ("sample-manifest-before-" + datetime.now().strftime("%Y%m%dT%H%M%S%f") + ".json")
    write_new(backup, manifest.read_bytes())
    content = (json.dumps(baseline, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    staged = manifest.with_name(manifest.name + ".pending")
    with staged.open("xb") as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())
    os.replace(staged, manifest)
    print(json.dumps({"preparedAt": stamp, "files": len(baseline["Files"]), "newFiles": len(files), "sourceBytes": len(source),
                      "sourceSHA256": sha(source), "previousManifestBackup": str(backup), "databaseWrites": 0, "businessExecuted": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
