"""只追加唯一记录第24节；保全准确第23节与README，不改源码、target、索引。"""
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote

root = Path(__file__).resolve().parents[6]
checks = Path(__file__).resolve().parent
record = root / "文档/项目/PaiFlow工作流项目/设计资料核对记录.md"
readme = root / "ruoyi-backend/ruoyi-paiflow/README.md"
runtime = root / "python/paiflow-runtime"
verification = runtime / "verification"
sha = lambda b: hashlib.sha256(b).hexdigest()
stage_path = verification / "variable-scope-stage.json"
stage_bytes = stage_path.read_bytes()
if sha(stage_bytes) != "5080518a3e0f0cc1a0fc6d3037256aa38aeebc7ca9b5887ee8185b268b0262e9":
    raise RuntimeError("变量预检最终stage变化，应先核对")
stage = json.loads(stage_bytes.decode("utf-8-sig"))
if (stage["full"]["count"], len(stage["reexecutedBaselineIdentities"]), len(stage["newIdentities"]), len(stage["carriedButNotRerun"])) != (271, 266, 5, 0):
    raise RuntimeError("测试身份口径变化")
if len(stage["sourceAfter"]) != 478:
    raise RuntimeError("源码末态清单范围变化")
current_mismatch = [p for p, expected in stage["sourceAfter"].items() if sha((runtime / p).read_bytes()) != expected]
implementation_delta = [p for p, expected in stage["sourceBefore"].items() if stage["sourceAfter"].get(p) != expected]
assert sorted(implementation_delta) == sorted(stage["changedPython"])
logs = []
for name, expected in stage["checkLogs"].items():
    actual = sha((verification / name).read_bytes())
    if actual != expected:
        raise RuntimeError("已有日志hash不一致：" + name)
    logs.append({"path": (verification / name).relative_to(root).as_posix(), "sha256": actual})
windows = []
for name in ["variable-scope-targeted-first-check.json", "variable-scope-targeted-fixed-check.json", "variable-scope-full-check.json"]:
    path = verification / name
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    before, after = data["sourceBefore"], data["sourceAfter"]
    delta = [p for p in set(before) | set(after) if before.get(p) != after.get(p)]
    windows.append({"path": path.relative_to(root).as_posix(), "sha256": sha(path.read_bytes()), "command": data["command"], "startedAtUtc": data["startedAtUtc"], "finishedAtUtc": data["finishedAtUtc"], "exitCode": data["exitCode"], "beforeCount": len(before), "afterCount": len(after), "changes": delta})
    if delta or len(before) != 478 or len(after) != 478:
        raise RuntimeError("已保存执行窗口变化：" + name)
if current_mismatch:
    raise RuntimeError("当前Python源与冻结末态变化：" + str(current_mismatch))

old_record, old_readme = record.read_bytes(), readme.read_bytes()
if sha(old_record) != "f03aec2783969ce67fb820f017a07d6d0061fa33997fa250c6fde454e46d5b5a" or "## 24." in old_record.decode("utf-8-sig"):
    raise RuntimeError("当前第23节不是已校正冻结原文或第24节已存在")
preserved = checks / "变量许可前预检记录追加前原文"
if preserved.exists():
    raise RuntimeError("原文保全目录已存在，禁止覆盖")
preserved.mkdir()
raw = []
for path, data in [(record, old_record), (readme, old_readme), (checks / "调试接续文档局部更新检查.json", (checks / "调试接续文档局部更新检查.json").read_bytes())]:
    saved = preserved / path.name
    saved.write_bytes(data)
    assert saved.read_bytes() == data
    raw.append({"source": path.relative_to(root).as_posix(), "backup": saved.relative_to(root).as_posix(), "bytes": len(data), "sha256": sha(data)})
(preserved / "输入.json").write_text(json.dumps({"savedAt": datetime.now(timezone.utc).isoformat(), "scope": "仅追加第24节前的准确第23节全部工作区字节、README及其原局部proof", "files": raw}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

addition = """

## 24. 2026-10-03全局变量的许可前作用域预检

本节只记录Worker对原`node-variable`的许可前窄修，承接第23节当时仍在接续的状态，不回改其历史。准确第23节全字节、backend README及原局部检查已保存到[追加前原文](证据/本地源码检查/checks/变量许可前预检记录追加前原文/输入.json)，backend README此次没有重复追加Runtime结果；当前Python说明仍在[Runtime README](../../../python/paiflow-runtime/README.md#全局变量的许可前作用域边界)。原React页面、Java、SQL、契约、变量算法、Factory、Pool、QA和Producer均未因本段修改。

[TaskRunner](../../../python/paiflow-runtime/worker/task_runner.py)仅增加三行判断：在原租约、hash、版本、事件序号和完整Assignment一致性核对后，复用已有executionContext四字段校验；固定图含全局变量而正式作用域缺失或为null时，在一次性`execution/begin`前拒绝。非法绑定和空App同样不取执行许可，不调用Factory/Engine、事件、结果、操作或资源；错误不关闭另一执行者已经取得许可的运行。纯图不需要变量作用域，仍按原Factory路径运行。绑定中的flowId/userId/appId/chatId保留原形，chat为null与空字符串不互换，不以空App、每run私有变量或页面随机chatId替代正式作用域。

[修改前反例](../../../python/paiflow-runtime/verification/variable-scope-before-first-probe.json)真实经过TaskRunner及内存中心：缺作用域时begin一次、Engine一次、Factory零次，并提交一次FAILED/RESOURCE_UNAVAILABLE，尚未到变量算法；[当时源码与检查原文](../../../python/paiflow-runtime/verification/variable-scope-before.json)保留。修后的新增五项覆盖缺失/null、非法或空App、此前租约/hash/固定快照/序号优先拒绝、重复领取不关闭真正持许可方，以及null/空chat的独立形状。有效作用域但中心变量ACK不可用是另一个边界：原语义抛CenterUnavailable，不提交正式result；测试明确已经begin且已经尝试get_all，不能混称许可前零调用或写成FAILED。该内存绑定只证明字段与失败路径，不代表正式App关系或变量存储已接通。

[共用生命周期首验](../../../python/paiflow-runtime/verification/variable-scope-targeted-first.log)实际66项，65通过、一项新增测试失败：误把原Assignment哈希冲突期望写成DSL_INVALID，真实原行为为VERSION_CONFLICT。仅修该测试期望，保留[首失败测试原文](../../../python/paiflow-runtime/verification/variable-scope-first-test_execution_begin.py.txt)，[单案复验](../../../python/paiflow-runtime/verification/variable-scope-targeted-fixed.log)一次通过（0.025秒）；没有改变生产错误语义。随后[完整离线日志](../../../python/paiflow-runtime/verification/variable-scope-full-unittest.log)实际271项全部通过（22.239秒），身份对账为此前266项全部本次重验加五项新增，沿用未执行项和移除项均零；此前265全套、34定向及266身份联合证据保留为历史，没有混算本次新执行。

[单一阶段证据](../../../python/paiflow-runtime/verification/variable-scope-stage.json)的sourceBefore→sourceAfter三项差异是此次实施变更：TaskRunner与两个既有测试文件，README另记hash；它不是执行窗口漂移。另有实际[首验窗口](../../../python/paiflow-runtime/verification/variable-scope-targeted-first-check.json)、[单案窗口](../../../python/paiflow-runtime/verification/variable-scope-targeted-fixed-check.json)和[完整检查窗口](../../../python/paiflow-runtime/verification/variable-scope-full-check.json)，各自执行前后478份源码hash差异零；完整命令是原离线脚本，退出0。根执行者末态复读及本节写入前当前478份也与sourceAfter一致，不能据此倒推其他未采集窗口。原导入427份无缺失，语法、依赖分析及原25包pip check技术检查通过，没有安装或升级依赖。

Java的全局变量准入仍503，正式App/WF/session绑定、中心variables/history端口和旧变量值迁移仍未完成。[原App与变量归属核对](证据/本地源码检查/checks/原应用身份与全局变量作用域来源只读核对.json)保留CommonApp与用户发布App的不同来源，不能用字段格式正确代替授权与旧数据关系。此段没有真实中心HTTP、业务库、Redis、Provider、服务启动、Maven、DDL、发布或Git操作；271项是隔离技术检查，不是全局变量功能已恢复或业务验收通过。
"""
record.write_bytes(old_record + addition.encode("utf-8"))
assert record.read_bytes()[:len(old_record)] == old_record
assert readme.read_bytes() == old_readme
assert stage_path.read_bytes() == stage_bytes
links, bad = [], []
def anchor(s):
    s = re.sub(r"[`*_]", "", s.strip().lower())
    return re.sub(r"[^\w\- ]", "", s, flags=re.UNICODE).replace(" ", "-")
for target in re.findall(r"\]\(([^)]+)\)", addition):
    file_part, _, fragment = target.partition("#")
    path = (record.parent / unquote(file_part)).resolve() if file_part else record
    item = {"target": target, "exists": path.exists()}
    if fragment and path.exists() and path.suffix == ".md":
        headings = re.findall(r"^#{1,6}\s+(.+)$", path.read_text(encoding="utf-8-sig"), re.MULTILINE)
        item["fragmentFound"] = unquote(fragment) in {anchor(s) for s in headings}
    links.append(item)
    if not item["exists"] or item.get("fragmentFound") is False:
        bad.append(item)
report = {
    "checkedAt": datetime.now(timezone.utc).isoformat(), "scope": "仅追加唯一记录第24节，保全准确第23节与README；未运行历史技术检查",
    "rawPreservation": raw, "recordBeforeSha256": sha(old_record), "recordAfterSha256": sha(record.read_bytes()),
    "recordBeforeBytes": len(old_record), "recordAfterBytes": record.stat().st_size,
    "oldFullPrefixPreserved": True, "readmeUnchanged": True, "readmeSha256": sha(old_readme), "section24Count": record.read_text(encoding="utf-8-sig").count("## 24."),
    "stageSha256": sha(stage_bytes), "stageUnchanged": True, "implementationDelta": implementation_delta,
    "stageSourceAfterCount": 478, "currentSourceAfterMismatches": current_mismatch, "existingExecutionWindows": windows,
    "readExistingLogHashes": logs, "localLinks": links, "badLocalLinks": bad,
    "sourceSqlTestContractTargetIndexEdits": 0, "MavenOrEngineRuns": 0, "businessVerified": False,
}
out = checks / "变量许可前预检记录局部追加检查.json"
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
if bad:
    raise RuntimeError("新增链接失败：" + str(bad))
print(json.dumps({"recordSha256": sha(record.read_bytes()), "oldFullPrefixBytes": len(old_record), "readmeSha256": sha(old_readme), "proofSha256": sha(out.read_bytes()), "localLinks": len(links), "bad": len(bad), "currentSourceAfterMismatch": len(current_mismatch)}, ensure_ascii=True))
