"""保存本工程离线技术检查，运行固定自身测试，不启动业务服务。"""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    verification = root / "verification"
    verification.mkdir(exist_ok=True)
    environment = dict(os.environ, PYTHONUTF8="1", PYTHONDONTWRITEBYTECODE="1")
    checks = [
        ("unittest.log", ["-B", "-m", "unittest", "discover", "-s", "tests", "-v"]),
        ("source-check.log", ["-B", "scripts/verify_source.py"]),
        ("dependency-analysis.log", ["-B", "scripts/analyze_port_dependencies.py"]),
        ("dependency-check.log", ["-B", "-m", "pip", "check"]),
    ]
    results = []
    for name, args in checks:
        result = subprocess.run([sys.executable, *args], cwd=root, env=environment,
            capture_output=True, encoding="utf-8", errors="replace", timeout=60, check=False)
        (verification / name).write_text(result.stdout + result.stderr, encoding="utf-8")
        results.append({"command": [str(Path(sys.executable).relative_to(root)), *args],
            "exitCode": result.returncode, "log": "verification/" + name})
        print(name + ": exit=" + str(result.returncode))
    (verification / "checks.json").write_text(json.dumps({"timestamp": datetime.now(timezone.utc).isoformat(),
        "scope": "实际原21类引擎消费者/受控模型及代码/QA共享检查点/普通知识/子run/Plugin/专业RAG/Agent/结构化数据库/RPA/一次性执行许可及取消CAS/Worker/知识派生离线定向检查；没有联机/业务验收", "checks": results},
        ensure_ascii=False, indent=2), encoding="utf-8")
    if any(item["exitCode"] for item in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
