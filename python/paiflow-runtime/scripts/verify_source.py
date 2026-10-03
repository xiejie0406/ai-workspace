"""只读源码核对，不导入上游算法，不输出凭据原文，不生成 pyc。"""
from __future__ import annotations

import ast
import hashlib
import json
from pathlib import Path


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / "import-manifest.json").read_text(encoding="utf-8"))
    originals = {item["source"]: item for item in manifest["files"]}
    errors, credential_bindings, executable_examples, host_exec = [], [], [], []
    files = []
    sensitive = {"password", "passwd", "api_key", "api_secret", "access_token", "refresh_token", "secret_key", "secret", "token", "authorization"}
    for path in sorted(root.rglob("*.py")):
        relative = path.relative_to(root).as_posix()
        if relative.split("/", 1)[0] in {".venv", "build", "dist"}:
            continue
        content = path.read_bytes()
        try:
            tree = ast.parse(content.decode("utf-8-sig"), filename=relative)
            compile(tree, relative, "exec")  # 只编译；不执行/写入 .pyc。
        except (SyntaxError, UnicodeError) as error:
            errors.append({"file": relative, "line": getattr(error, "lineno", None), "type": type(error).__name__})
            continue
        origin = originals.get(relative)
        sha = hashlib.sha256(content).hexdigest()
        files.append({"file": relative, "sha256": sha, "introduced": origin is not None,
                      "changedSinceIntroduction": origin is not None and sha != origin["sha256Introduced"]})
        if relative.startswith(("tests/", "scripts/")):
            continue
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in {"exec", "eval"}:
                host_exec.append({"file": relative, "line": node.lineno, "call": node.func.id})
            pairs = []
            if isinstance(node, (ast.Assign, ast.AnnAssign)):
                targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                pairs = [(target.id, node.value) for target in targets if isinstance(target, ast.Name)]
            elif isinstance(node, ast.Dict):
                pairs = [(key.value, value) for key, value in zip(node.keys, node.values) if isinstance(key, ast.Constant) and isinstance(key.value, str)]
            elif isinstance(node, ast.keyword):
                pairs = [(node.arg or "", node.value)]
            for name, value in pairs:
                if name.lower() in sensitive and isinstance(value, ast.Constant) and isinstance(value.value, str) and len(value.value.strip()) >= 8:
                    credential_bindings.append({"file": relative, "line": value.lineno, "binding": name})
        for node in tree.body:
            if isinstance(node, ast.If) and any(isinstance(value, ast.Name) and value.id == "__name__" for value in ast.walk(node.test)):
                executable_examples.append({"file": relative, "line": node.lineno})
    report = {"pythonFiles": len(files), "introducedFiles": len(originals), "missingIntroducedFiles": sorted(set(originals) - {x["file"] for x in files}),
              "syntaxErrors": errors, "suspiciousLiteralCredentialBindings": credential_bindings, "executableExamples": executable_examples,
              "hostExecCalls": host_exec, "files": files,
              "limits": ["静态检查不是秘密扫描器，不能证明没有别名/拼接/动态外部调用",
                         "凭据/exec/示例扫描包含全部生产Python（含worker），排除测试fixture和检查脚本；所有Python仍执行语法检查",
                         "21 节点源码存在不证明各节点已接通中心或联机运行", "引入基线的完整源 hash 不含原文"]}
    (root / "source-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("pythonFiles", "introducedFiles", "missingIntroducedFiles", "syntaxErrors", "suspiciousLiteralCredentialBindings", "executableExamples", "hostExecCalls")}, ensure_ascii=False))
    if errors or report["missingIntroducedFiles"] or credential_bindings or executable_examples or host_exec:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
