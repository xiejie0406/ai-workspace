"""追加唯一第23节和后台README；保全当前原文及前缀，不改源码/索引/target。"""
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote

root = Path(__file__).resolve().parents[6]
project = root / "文档/项目/PaiFlow工作流项目"
record = project / "设计资料核对记录.md"
readme = root / "ruoyi-backend/ruoyi-paiflow/README.md"
evidence = project / "证据/本地源码检查"
checks = evidence / "checks"
preserved = checks / "调试接续文档更新前原文"

def sha(value):
    return hashlib.sha256(value).hexdigest()

original = {record: record.read_bytes(), readme: readme.read_bytes()}
if "## 23." in original[record].decode("utf-8-sig"):
    raise RuntimeError("第23节已经存在，禁止重复追加")
if preserved.exists():
    raise RuntimeError("文档原文保全目录已经存在，禁止覆盖")
audit_path = evidence / "调试聊天接续本地源码检查.json"
audit_bytes = audit_path.read_bytes()
if sha(audit_bytes) != "62e1e8bc6e9cbcde21083205a96415f11c31eeaaa8dc93aa9d909318a8aab1c1":
    raise RuntimeError("父任务最终单一汇总已变化，应先核对")
ledger_path = evidence / "调试聊天接续三次检查用例沿用核对.json"
ledger = json.loads(ledger_path.read_text(encoding="utf-8-sig"))
if (ledger["currentUniqueTests"], ledger["fullFirstExecution"], ledger["unaffectedFirstCasesCarried"], ledger["streamFixCasesExecutedPassed"], ledger["onlyProxyAssertionRepairCaseExecutedPassed"]) != (520, 519, 509, 10, 1):
    raise RuntimeError("用例沿用口径不匹配")
preserved.mkdir()
backup_files = []
for path, data in original.items():
    saved = preserved / path.name
    saved.write_bytes(data)
    if saved.read_bytes() != data:
        raise RuntimeError("原文保全字节不一致")
    backup_files.append({"source": path.relative_to(root).as_posix(), "backup": saved.relative_to(root).as_posix(), "bytes": len(data), "sha256": sha(data)})
(preserved / "输入.json").write_text(json.dumps({"savedAt": datetime.now(timezone.utc).isoformat(), "scope": "追加第23节与README前的当前工作区原文；不以GitHEAD恢复", "files": backup_files}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

record_append = """

## 23. 2026-10-03原调试构建、同次运行与问答流接续

本节按完整设计3.4接续原`/workflow/build`、`/workflow/add-comparisons`、`/workflow/chat`、`/workflow/resume`，复用同一Workflow版本、Run、事件和Checkpoint真相。原React框架、画布、页面及状态库继续保留，只局部传递本次构建回执、运行标识和原流格式，不迁Vue外壳。开始修改前的Java/SQL原文在[源码保全](证据/本地源码检查/checks/调试构建实施前快照/输入.json)；本节追加前第22节及README当前完整字节另在[文档原文保全](证据/本地源码检查/checks/调试接续文档更新前原文/输入.json)。完整原功能迁移目标不因本节局部结果缩小。

[WorkflowService](../../../ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowService.java)提取现有保存与快照编译规则：普通build在当前权限、真实空间、草稿revision保护下只保存一次并固定本次快照；比较build仅编译本次候选，不改草稿、Agent或当前选中版本。快照独立保存mode、debug发起者、comparisonId、build请求键/hash和真实Agent绑定；同发起者同键同请求重取原固定回执，不回读新草稿，异请求409。实际Space→Workflow→Agent关系、当前菜单/成员和主体在锁后及返回前再核对，失败整笔回滚；未装配真实关系端口时503。调试版本不进入原业务版本列表、计数、最新版本、详情或恢复入口，也不调用selectLatest改变当前版本。

构建请求键来自专用`X-PaiFlow-Build-Key`头，不取客户端metadata。最小响应给本次工作流id/flowId、原空间表示、固定revision及十字段debugReceipt，ID和revision为字符串，普通comparisonId为明确null；ETag同固定快照，并局部暴露跨域ETag头。服务只接受实际版本、来源、发起者、hash、引擎和编译标记一致的回执，不能根据flowId找最近构建。默认个人区仍原空头/null回执语义；客户端未知metadata保全但不能制造正式回执或App关系。[018增量草案](../../../ruoyi-backend/ruoyi-paiflow/sql/018-paiflow-workflow-debug-snapshot.sql)只增加六个nullable调试字段及完整性/发起者请求键唯一约束，普通历史版本保持null，不回填。H2隔离夹具实际加载，不代表MySQL语法或原业务库已经验证。

原chat薄桥先核同次回执、当前空间/发起者及受支持图，再交RunService创建唯一运行；原inputs中的明确null在标准RunController边界保全。resume绑定同一run、固定版本和Checkpoint，以原reply内容及revision/CAS走既有真实检查点，不由Worker代答。SSE仅投影已经持久化的事件，节点stop不替代Run终态；waiting结束当前流，回复后按中心游标接续。后台读取复用平台TokenService、当前菜单/成员检查与共享线程池，恢复线程请求/认证上下文；有界连接和单次在途读取，关闭只释放传输资源，不偷偷取消运行。当前仅支持顶层五类纯节点和静态QA，模型、其他资源及迭代的原页流在入队前明确503。

15:31:51的[首次全模块检查](证据/本地源码检查/调试聊天接续完整模块首次检查结果.json)实际519项、47个测试类全部通过，0失败/错误/跳过、exit0；旧471项全部本次新执行，新增构建22项与根执行者运行/薄流26项。215份源码/SQL/契约读取窗口变化0。真实H2 Mapper、Spring事务和MockMvc覆盖同键重放/竞争、普通与比较保存隔离、绑定错配/撤权、默认区、版本预算/编译回滚、运行/检查点/游标及原响应；受控Token和调度夹具仍不是实际JWT/Redis或网络连接。预执行自审只修了普通保存会同步Agent inputExample的测试预期，原未知/null、revision和latest断言保持；没有把未执行候选当失败日志。

首次通过后，[独立CallerRuns首反例](证据/本地源码检查/checks/原调试薄流CallerRuns调度线程首反例.json)确认共享池真实CallerRunsPolicy在满载时会让读库/发送任务进入调度线程。局部薄流修复拒绝同一触发线程内联交付，503前不读桥或发送、取消本次轮询并归还名额；共享ThreadPoolConfig未改。[同源私有探针复验](证据/本地源码检查/checks/原调试薄流CallerRuns修后定向复验.json)十一组通过，正常异步夹具改用独立交付线程，没有启动服务。16:03:19的[十一项定向检查](证据/本地源码检查/调试流调度回退修复定向检查结果.json)十通过、一错误：新增测试错误地把Spring CGLIB代理当Mockito mock，业务断言未失败；仅改实际Service委托计数断言后，[单项复验](证据/本地源码检查/调试流代理断言修复定向检查结果.json)16:09:58通过。首源码、报告和反例继续保留。当前[520项身份核对](证据/本地源码检查/调试聊天接续三次检查用例沿用核对.json)为首轮未受影响509项、调度修复十项及代理断言修复一项；没有重跑当前520全套，不能把沿用项称为最新新执行。

调度修复后的实际H2/MockMvc构建、流与回复字节经过[原消费者九项核对](证据/本地源码检查/checks/调试接续调度修复Java实际响应原消费者最终独立核对.json)。原499取消帧经未改解析器及handleMessage的AST/spy进入failed并显示运行已取消；这是实际原分支观察，不是页面验收或新的cancelled UI状态。真实RESUMED且stopped=false只确认回复受理，不冒称运行结束；探针的标准SSE状态/Content-Type上下文另为隔离构造，没有真实HTTP或浏览器。

前端在原服务与既有条件适配中传同次receipt，比较快照不提升草稿写条件；身份/空间、历史卡片及调试所有者切换失效旧上下文。断开只清当前所有者的本地等待/中断/队列显示，不调用save/delete/cancel，不让旧请求清理新调试；历史抽屉关闭保持原只读。首104项、历史选择109项、断开首反例/AST夹具失败与最终七输入冻结分别保全在[前端阶段汇总](证据/本地源码检查/checks/原React调试接续最终阶段证据汇总.json)。[当前定向检查](证据/本地源码检查/原React调试接续断开视图与只读最终定向检查.json)实际110项Node全部通过，七份输入窗口0；两份未变helper的独立strict结果沿用。类型基线3712、当前3698、新增0，整个工程类型仍未通过。[已执行生产构建](证据/本地源码检查/调试接续原React生产构建结果.json)242份输出对应较早flow-chat源码47e649，不包含最新dab0ff的历史选择/本地视图修复；最新源没有重新打包或部署，也没有浏览器验收。

准确输入、三次Java检查、前端各时点与限制唯一汇总在[本地源码检查](证据/本地源码检查/调试聊天接续本地源码检查.json)。后续文档和接口索引刷新与215/七输入技术窗口分开记录，不更新本节历史计数。[原应用身份核对](证据/本地源码检查/checks/原应用身份与全局变量作用域来源只读核对.json)确认原草稿CommonApp与用户发布App是不同链；当前中心没有正式App/chat/旧flow映射，OpenAI/QA兼容空appId不是变量scope。Worker变量预检窄修另在接续，不能写作本节已完成。模块仍default OFF；001至018未执行真实DDL，没有真实数据库/Provider/Redis/服务、页面业务通过、发布或Git写入，完整迁移继续进行。
"""

readme_append = """

原调试构建与本次运行接续现有本地中心实现，更新上文顶层纯图/静态QA的历史未接入范围。普通build复用原保存/编译规则，保存一次后固定本次快照；comparison不改草稿、Agent或当前版本。请求使用X-PaiFlow-Build-Key和真实revision条件，回执绑定十字段固定版本及debug发起者/真实Agent关系，重放不取最新草稿。调试快照与普通业务版本隔离，原空space-id继续默认个人区/null响应。SQL018增加六nullable来源字段及唯一/完整性约束，001至018仍是未执行MySQL草案；只在随机H2夹具加载。

原chat/resume/SSE薄桥复用同一Run/事件/Checkpoint，要求本次回执与当前用户/空间/版本一致；支持门仍仅顶层五纯节点和静态QA。后台复用TokenService与共享线程池，当前权限/成员重查，关闭只释放传输而不取消运行；共享池CallerRunsPolicy可能内联任务的真实反例已修为读/发送前拒绝，共享ThreadPoolConfig未改。模型/资源/迭代调试流、正式App/chat/history/变量scope仍未接通，Worker预检另在接续，不由空appId或每run私有状态代替。

首次全模块519项、47类全部通过，旧471项本次全部新执行。后续调度修复十一项为十通过一代理断言错误，单项修复复验通过；当前520身份为509首轮沿用+十项复验+一项最新复验，没有重跑当前全套。前端当前110项Node通过，类型3712→3698新增0但整工程类型未通过；242份生产输出仅对应较早源码，最新历史选择/断开本地视图修复未重建或部署。实际隔离MVC字节经原消费者九项核对，499仅AST/spy观察，不是页面UAT。准确原文、失败与复验见[唯一实施记录第23节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#23-2026-10-03原调试构建同次运行与问答流接续)及[单一本地检查汇总](../../文档/项目/PaiFlow工作流项目/证据/本地源码检查/调试聊天接续本地源码检查.json)。default OFF；未访问真实业务库/Provider/Redis、启动服务、执行真实DDL、发布或写Git，完整业务验收仍未执行。
"""

changed = []
for path, addition in [(record, record_append), (readme, readme_append)]:
    old_bytes = original[path]
    path.write_bytes(old_bytes + addition.encode("utf-8"))
    actual = path.read_bytes()
    if actual[:len(old_bytes)] != old_bytes:
        raise RuntimeError("旧文档前缀被改写")
    changed.append({"path": path.relative_to(root).as_posix(), "oldBytes": len(old_bytes), "oldSha256": sha(old_bytes), "currentBytes": len(actual), "currentSha256": sha(actual), "oldPrefixPreserved": True})

def anchor(value):
    value = re.sub(r"[`*_]", "", value.strip().lower())
    return re.sub(r"[^\w\- ]", "", value, flags=re.UNICODE).replace(" ", "-")

checked_links, bad = [], []
for path, addition in [(record, record_append), (readme, readme_append)]:
    for target in re.findall(r"\]\(([^)]+)\)", addition):
        target = target.strip().strip("<>")
        if re.match(r"[a-z]+://", target):
            continue
        file_part, _, fragment = target.partition("#")
        resolved = (path.parent / unquote(file_part)).resolve() if file_part else path
        item = {"from": path.relative_to(root).as_posix(), "target": target, "exists": resolved.exists()}
        if fragment and resolved.suffix == ".md" and resolved.exists():
            headings = re.findall(r"^#{1,6}\s+(.+)$", resolved.read_text(encoding="utf-8-sig"), re.MULTILINE)
            item["fragmentFound"] = unquote(fragment) in {anchor(value) for value in headings}
        checked_links.append(item)
        if not item["exists"] or item.get("fragmentFound") is False:
            bad.append(item)

audit_after = audit_path.read_bytes()
report = {
    "checkedAt": datetime.now(timezone.utc).isoformat(),
    "scope": "仅追加唯一记录第23节及backend README；局部文档链接/原文前缀检查，不代替完整文档索引或业务验收",
    "rawPreservation": backup_files,
    "changedDocuments": changed,
    "section23Count": record.read_text(encoding="utf-8-sig").count("## 23."),
    "summaryBeforeSha256": sha(audit_bytes),
    "summaryAfterSha256": sha(audit_after),
    "summaryUnchanged": audit_bytes == audit_after,
    "javaBaseline": "519首次完整执行；当前520=509沿用+10修复复验+1代理断言复验，非520新全跑",
    "links": checked_links,
    "localLinkCount": len(checked_links),
    "badLocalLinks": bad,
    "javaSourceOrTestEdits": 0,
    "sqlOrContractEdits": 0,
    "indexEdits": 0,
    "targetWrites": 0,
    "MavenRuns": 0,
    "businessAcceptance": "未执行",
}
out = checks / "调试接续文档局部更新检查.json"
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
if bad or audit_after != audit_bytes:
    raise RuntimeError("文档检查未通过：" + str(bad))
print(json.dumps({"reportSha256": sha(out.read_bytes()), "documents": changed, "localLinks": len(checked_links), "bad": len(bad), "oldPrefixesPreserved": True, "summaryUnchanged": True}, ensure_ascii=True))
