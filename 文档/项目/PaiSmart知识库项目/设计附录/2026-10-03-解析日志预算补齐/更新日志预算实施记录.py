"""将已实际完成的日志资源修复和定向复验事实同步到现有唯一记录。"""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROUND = Path(__file__).resolve().parent
ROOT = ROUND.parents[4]
APPENDIX = ROUND.parent
MASTER = APPENDIX.parent / "完整迁移设计.md"
README = ROOT / "ruoyi-backend/ruoyi-paismart/README.md"
RECORD = APPENDIX / "2026-10-03-真实业务验收准备/验收记录.json"
SOURCE = ROOT / "ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/infrastructure/intelligence/IsolatedDocumentParser.java"
TEST = ROOT / "ruoyi-backend/ruoyi-paismart/src/test/java/com/ruoyi/paismart/infrastructure/intelligence/IsolatedParserLifecycleTest.java"
CLI = APPENDIX / "2026-10-03-解析中断与迁移恢复补齐/实际解析CLI复验/103325-775006/results.json"
NOW = datetime.now().astimezone().isoformat()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def suite(path):
    doc = ET.parse(path).getroot()
    return {"report": str(path.relative_to(ROOT)).replace("\\", "/"), "sha256": sha(path),
            **{key: int(doc.get(key)) for key in ("tests", "failures", "errors", "skipped")}}


assert MASTER.read_bytes() == (ROUND / "日志补齐前-完整迁移设计.md").read_bytes(), "控制页后来变化，不能覆盖"
assert RECORD.read_bytes() == (ROUND / "日志补齐前-真实验收记录.json").read_bytes(), "验收源后来变化，不能覆盖"
old_source = (ROUND / "IsolatedDocumentParser-修复前原文.java").read_text(encoding="utf-8-sig")
expected_source = old_source.replace(',log=dir.resolve("process.log")', '').replace('redirectOutput(log.toFile())', 'redirectOutput(ProcessBuilder.Redirect.DISCARD)')
assert SOURCE.read_text(encoding="utf-8-sig") == expected_source
assert sha(SOURCE) == "24244afc4540dfd8f8126211e42b27fdd881086e91e749a459b625500659deae"
assert sha(TEST) == "f0d71e1360858d94115b9e29bd6ea1537bffc71092cc490e1aa6f98dfd1c9464"
backend = load(APPENDIX / "2026-10-03-终版后端测试汇总.json")
assert backend["totals"] == {"tests": 362, "failures": 0, "errors": 0, "skipped": 0}
assert backend["executionComposition"]["retainedFullRunTests"] == 359
assert backend["executionComposition"]["targetedTests"] == 3
delivery = load(APPENDIX / "2026-10-03-终版交付产物核对.json")
cli = load(CLI)
assert cli["passed"] == cli["total"] == 19
assert delivery["ModuleSHA256"] == delivery["WorkerSHA256"] == delivery["NestedModuleSHA256"] == "acb82c0b4299a87846a64d3647470337ce33de93521b56d27af07d1b0a8676f5"
xml_name = "TEST-com.ruoyi.paismart.infrastructure.intelligence.IsolatedParserLifecycleTest.xml"
passed_path = ROUND / "全目录增长修正后生命周期通过报告" / xml_name
passed_xml = ET.parse(passed_path).getroot()
stdout = "\n".join(node.text or "" for node in passed_xml.iter("system-out"))
metrics = re.search(r"before=TemporarySnapshot\[bytes=(\d+).*?after=TemporarySnapshot\[bytes=(\d+).*?growth=(\d+)", stdout, re.S)
assert metrics and tuple(map(int, metrics.groups())) == (65552, 65552, 0)
technical = {
    "time": NOW, "scope": "实际本地Java夹具验证stdout/stderr磁盘增长与解析生命周期；未执行真实业务或OS沙箱验收。",
    "sourceChanges": [{"path": str(path.relative_to(ROOT)).replace("\\", "/"), "sha256": sha(path)} for path in (SOURCE, TEST)],
    "productionChange": "仅移除未消费process.log路径，并改为合并输出后DISCARD；JSON与退出/超时/中断异常不变。",
    "originalFailure": suite(ROUND / "旧源单项失败报告" / xml_name),
    "firstRepairFullRun": {"tests": 362, "failures": 1, "errors": 0, "skipped": 0,
                           "log": "解析日志-修后全模块复验.log", "reports": "首修全模块失败报告"},
    "diagnosticFailure": suite(ROUND / "首修单项文件诊断失败报告" / xml_name),
    "diagnosticCause": "原64KiB固定目录断言包含JVM性能文件65536字节和输入16字节；原失败未删除。",
    "after": suite(passed_path), "stdoutAndStderrBytes": 16 * 1024 * 1024,
    "temporaryDirectoryBeforeBytes": int(metrics[1]), "temporaryDirectoryAfterBytes": int(metrics[2]),
    "temporaryDirectoryGrowthBytes": int(metrics[3]), "maximumGrowthBytes": 4096,
    "measurementScope": "同一已核对测试子进程和任务目录；输出前后全目录统计，不排除JVM或任何指定文件。",
    "currentBackend": {"classes": 36, "tests": 362, "retainedFirstFullRunTests": 359, "targetedTests": 3,
                       "provenance": "当前通过报告/执行来源.json"},
    "newWorkerCLI": {"report": str(CLI.relative_to(ROOT)).replace("\\", "/"), "sha256": sha(CLI), "tests": 19, "passed": 19},
    "businessAcceptanceExecuted": False, "realDatabaseExecuted": False,
}
(ROUND / "解析日志-技术结果.json").write_text(json.dumps(technical, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

backup_readme = ROUND / "日志补齐前-模块运行说明.md"
assert not backup_readme.exists()
backup_readme.write_bytes(README.read_bytes())
readme = README.read_text(encoding="utf-8-sig")
marker = "这只能核对捕获的进程树；"
assert marker in readme
readme = readme.replace(marker, "Worker标准输出和错误输出合并后直接丢弃，不写入无上限诊断日志；独立JSON结果以及非零退出、超时和中断反馈保留。实际夹具在16MiB输出前后统计全部任务临时文件，允许有限增长并验证目录回收、许可复用和退出码。" + marker, 1)
README.write_text(readme, encoding="utf-8")

master = MASTER.read_text(encoding="utf-8-sig")
master = master.replace("当前源码完整复验36类361项后端", "日志预算继续复核前，源码完整复验36类361项后端", 1)
master = master.replace("[当前后端汇总](设计附录/2026-10-03-终版后端测试汇总.json)", "[日志修复前后端汇总](设计附录/2026-10-03-解析日志预算补齐/日志补齐前-后端测试汇总.json)", 1)
master = master.replace("模块、admin内嵌与本批固定Worker主JAR哈希一致", "日志预算继续复核前的模块、admin内嵌与当时固定Worker主JAR哈希一致", 1)
master = master.replace("[当前交付核对](设计附录/2026-10-03-终版交付产物核对.json)", "[日志修复前交付核对](设计附录/2026-10-03-解析日志预算补齐/日志补齐前-交付产物核对.json)", 1)
master = master.replace("最后只读核对发现共享", "日志预算继续复核前的最后只读核对发现共享", 1)
master = master.replace("当前交付核对另记稍后文件的实际摘要", "当时交付核对另记稍后文件的实际摘要", 1)
master = master.replace("见[当前呈现核对](设计附录/2026-10-03-真实业务验收准备/验收记录.json)", "见[日志修复前呈现记录](设计附录/2026-10-03-解析日志预算补齐/日志补齐前-真实验收记录.json)", 1)
addition = """

2026-10-03继续核对解析资源约束，修复未消费的子进程日志无字节上限问题：中心将Worker标准输出与错误输出合并后直接丢弃，独立JSON结果及退出码、超时、中断反馈不变。旧代码受控输出16MiB后临时目录为16,842,768字节，实际单项失败；首修完整362项中仅新夹具失败，诊断确认其固定64KiB断言漏算JVM启动文件。改为输出前后同一目录全量统计，实测65,552→65,552字节、增长0，并通过JSON、非零退出、许可与目录回收检查；所有原失败和夹具版本保留，见[日志资源技术结果](设计附录/2026-10-03-解析日志预算补齐/解析日志-技术结果.json)。

当前后端36类362项通过，由同生产源码、其余源码未变的首修35类359项通过基线与受影响生命周期类3项实际定向复验组成，明确各XML原文来源，不冒称整批重复执行，见[当前后端汇总](设计附录/2026-10-03-终版后端测试汇总.json)和[逐类执行来源](设计附录/2026-10-03-解析日志预算补齐/当前通过报告/执行来源.json)。前端10文件72项、类型与构建、离线转换17项按未变代码沿用原通过结果，本轮新增前端/迁移执行0项。

新Worker及23模块admin装配构建通过，本次admin包固定在本模块target的独立目录，并保存构建前共享包原文；未安装或切换运行通道。普通模块、固定admin内嵌模块与固定Worker主JAR摘要一致，12默认开关关闭，240依赖及资源核对通过，见[当前固定交付核对](设计附录/2026-10-03-终版交付产物核对.json)和[本批依赖](设计附录/2026-10-03-解析运行依赖-parser-worker-5c53211a16b648a3b48f86aff101d92e.json)。本批实际执行原19份输入和正文预期，19项通过，见[本批解析CLI结果](设计附录/2026-10-03-解析中断与迁移恢复补齐/实际解析CLI复验/103325-775006/results.json)。契约及原工程只读核对保留原证据范围。

第5节31项真实业务仍全部未执行，日志资源观察已纳入原第6项，不改变原顺序、业务状态或用户接受。指定运行通道、原MySQL、原数据、低权限运行身份及相应操作授权前，真实数据/菜单、联机、切换恢复、OS隔离和容量验证仍未完成，完整迁移目标保持原范围。
"""
marker = "\n<!-- 内部元数据："
assert master.count(marker) == 1
MASTER.write_text(master.replace(marker, addition + marker, 1), encoding="utf-8")

record = load(RECORD)
facts = {row["name"]: row for row in record["technicalBaseline"]}
facts["后端本地测试"]["fact"] = "当前36类362项通过；35类359项沿用本轮首修通过结果，受影响生命周期类3项实际定向复验，生产和其余源码未变，各XML原文与来源均保留。首修新夹具失败及诊断失败不删除；本地H2/替身不证明真实联机。"
facts["共享装配和固定Worker"]["fact"] = "新Worker及admin构建通过，admin固定副本与模块、固定Worker主JAR摘要一致；240依赖、同版4个BC、V005及资源入包，12默认开关关闭。已保存构建前共享包，不切换运行通道；COS1项仍按未变源码沿用。"
facts["运行与恢复步骤"]["fact"] = "实际开关、离线准备和恢复限制；解析诊断输出直接丢弃，JSON与退出/超时/中断反馈保留。临时文件有限回收；父提前退出的脱离后代仍需OS约束，源码没有实现OS沙箱。"
facts["解析生命周期复验"]["href"] = "../2026-10-03-解析日志预算补齐/解析日志-技术结果.json"
facts["解析生命周期复验"]["fact"] = "3项真实本地Java夹具通过：中断/超时树退出及16MiB两路输出；全目录前后65552字节、增长0，JSON、exit7、许可、目录回收保持。原失败与修正依据保留；不计真实业务或OS沙箱。"
facts["固定Worker实际格式复验"]["href"] = "../2026-10-03-解析中断与迁移恢复补齐/实际解析CLI复验/103325-775006/results.json"
facts["固定Worker实际格式复验"]["fact"] = "本轮在5c固定Worker实际重跑19份原脱敏样例，全部匹配原输入摘要及正文marker；本批19项通过，240依赖固定。不将前批结果冒充本批执行，不代表所有生产格式或语言包验收。"
case = next(row for row in record["businessCases"] if row["number"] == 6)
case["steps"].insert(-1, "使用批准的诊断大量输出样例，记录Worker启动后与输出后的任务临时文件全量基线、增长及最终回收；核对独立JSON结果、非零退出和后续作业可继续。")
case["expected"] += "；诊断输出不持续增加任务临时磁盘占用，合法启动文件计入基线，原处理与失败反馈保持"
assert [row["number"] for row in record["businessCases"]] == list(range(1, 32))
assert all(row["result"] == "未执行" and not row["evidence"] for row in record["businessCases"])
assert all(record[key] is False for key in ("businessAcceptanceExecuted", "userAccepted", "published"))
record["technicalFactsUpdatedAt"] = NOW
record["reportRendering"] = {"status": "待本轮报告呈现检查", "businessEvidence": False,
                             "previousEvidence": "../2026-10-03-解析日志预算补齐/日志补齐前-真实验收记录.json",
                             "note": "技术事实与第6项准备步骤已更新；前版截图按原版本保留，本轮尚未声明重新呈现。"}
RECORD.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("已更新日志技术结果、既有控制页/模块说明/验收源；31项仍未执行，报告视图待生成和呈现。")
