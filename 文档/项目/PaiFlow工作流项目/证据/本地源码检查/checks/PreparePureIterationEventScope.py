"""保存本批三个既有owned文件原文，第四文件明确新建；不改目标源或target。"""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[6]
checks = Path(__file__).resolve().parent
saved = checks / "纯迭代事件作用域修改前原文"
if saved.exists():
    raise RuntimeError("旧原文保全目录已存在，禁止覆盖")
paths = [
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/PureIterationPolicy.java",
    "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunLeaseService.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/RunLeasePolicyTest.java",
    "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/PureIterationEventScopeTest.java",
]
files = []
saved.mkdir()
for p in paths:
    path = root / p
    if not path.exists():
        files.append({"path": p, "existedBefore": False, "reason": "本批新增纯策略测试，非遗漏原文"})
        continue
    raw = path.read_bytes()
    backup = saved / path.name
    backup.write_bytes(raw)
    assert backup.read_bytes() == raw
    files.append({"path": p, "existedBefore": True, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(), "backup": backup.relative_to(root).as_posix(), "testMethodsBefore": raw.decode("utf-8-sig").count("@Test")})
record = {"savedAt": datetime.now(timezone.utc).isoformat(), "scope": "只拥有四文件；三个已有原文保全，一个新test此前不存在", "files": files,
    "changedBehavior": "RunLeasePolicyTest:109非迭代batch-0原仅符合非空schema校验；新seq明确拒400，不能据旧错误接受授权scope。原完全相同历史replay仍按原lease/seq/canonical确认、不重写存量。", "MavenRuns": 0, "targetWrites": 0}
(saved / "输入.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(record, ensure_ascii=True))
