"""仅检查文档/契约/已打包字节，不导入产品代码，不启动服务或执行产品测试。"""
from pathlib import Path
import csv, hashlib, json, re, shutil, subprocess, xml.etree.ElementTree as ET, zipfile

evidence = Path(__file__).resolve().parent
root = evidence.parents[6]
feature = evidence.parent.parent
checks, errors = [], []
def record(name, ok, detail):
    checks.append({"检查": name, "结果": "通过" if ok else "未通过", "说明": detail})
    if not ok: errors.append(name)
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

schema_path = root / "contracts/paicli/terminal-v1.schema.json"
schema = json.loads(schema_path.read_text(encoding="utf-8-sig"))
refs = []
def walk(value):
    if isinstance(value, dict):
        if "$ref" in value: refs.append(value["$ref"])
        for child in value.values(): walk(child)
    elif isinstance(value, list):
        for child in value: walk(child)
walk(schema)
for ref in refs:
    node = schema
    if ref.startswith("#/"):
        for part in ref[2:].split("/"): node = node[part.replace("~1", "/").replace("~0", "~")]
record("终端机器契约", True, f"JSON可解析，{len(refs)}个内部引用可解析；未执行通信")
for module in ("paicli-core", "paicli-runner", "paicli-cli", "paicli-api"):
    ET.parse(root / "ruoyi-backend/ruoyi-paicli" / module / "pom.xml")
record("模块POM", True, "四模块XML可解析；不证明运行环境就绪")

matrix = list(csv.DictReader((evidence / "原终端能力逐项核对.csv").open(encoding="utf-8-sig", newline="")))
record("原终端能力核对矩阵", len(matrix) == 111, f"实际读取{len(matrix)}项；逐项状态仍为静态核对")
missing_links=[]
docs = [feature / "README.md", feature / "实施任务.md", evidence / "独立命令审查.md", evidence / "独立安全与集成复核.md", evidence / "实施与整合记录.md"]
for doc in docs:
    if not doc.exists(): missing_links.append(str(doc)); continue
    for raw in re.findall(r"\]\(([^\n)]+)\)", doc.read_text(encoding="utf-8-sig")):
        target=raw.strip().strip("<>").split("#",1)[0]
        if not target or re.match(r"[a-z]+://",target,re.I): continue
        if not (doc.parent / target).resolve().exists(): missing_links.append(f"{doc.name}: {target}")
record("功能与证据文档相对链接", not missing_links, missing_links or "本功能五份文档的本地目标均存在")

modules=root / "ruoyi-backend/ruoyi-paicli"
selected=[]
names={
 "paicli-core": {"CenterCommandGateway","ImageReferenceParser"},
 "paicli-runner": {"RunnerTerminalHost","PtyProcessManager","RunnerPrivateDirectory","CenterGateway","CenterHttpClient","HttpCenterCommandGateway","RunnerEngine","NativeControlExecutor"},
 "paicli-cli": {"CenterCli","CenterCompletionData","CenterArtifactText","CenterSessionExporter","HostedInputHistory","PaiCliHistory","CenterHarnessControl","LanternaRenderer","LanternaWindow","RootPane","InputBar"},
 "paicli-api": {"PcCommandService","PcTerminalCommandArguments","PcKnowledgeService","PcRunnerService","PcToolPolicy","PcArtifactService","PcDeviceService","PcSchemaGuard","PcTable","PcConfiguration"},
}
for module, stems in names.items():
    for source in (modules / module / "src/main/java").rglob("*.java"):
        if source.stem in stems or source.stem.startswith("PcTerminal"): selected.append(source)
frontend=root / "frontend/admin-web"
selected += [frontend / p for p in ("package.json","package-lock.json","src/types/paicli-terminal.ts","src/api/paicli/terminals.ts","src/views/paicli/useTerminal.ts","src/views/paicli/components/WebTerminalPane.vue","src/views/paicli/components/ImageAttachments.vue","src/views/paicli/workbench/index.vue")]
selected += [schema_path, modules / "paicli-api/src/main/resources/db/paicli-migration/V6__paicli_web_terminal.sql"]
whitespace=subprocess.run(["git","diff","--check","--"]+[str(p.relative_to(root)) for p in selected],cwd=root,capture_output=True,text=True,encoding="utf-8",errors="replace")
record("受影响源码差异格式", whitespace.returncode == 0, whitespace.stdout.strip() or "git diff --check未发现空白错误；未执行Git写入")

candidate=root / ".runtime/paicli/terminal-candidate-20261004"
candidate.mkdir(parents=True,exist_ok=True)
artifacts=[]
class_count=0
for module in names:
    jar=modules / module / "target" / f"{module}-3.9.2.jar"
    if not jar.exists(): record(f"{module}打包",False,"目标JAR不存在");continue
    with zipfile.ZipFile(jar) as archive:
        for source in selected:
            source_root=modules / module / "src/main/java"
            if not source.is_relative_to(source_root):continue
            stem=source.relative_to(source_root).with_suffix("").as_posix()
            classes=[name for name in archive.namelist() if name == stem+".class" or name.startswith(stem+"$") and name.endswith(".class")]
            if not classes: errors.append(f"JAR缺少{stem}")
            for entry in classes:
                compiled=modules / module / "target/classes" / entry
                if not compiled.exists() or archive.read(entry) != compiled.read_bytes(): errors.append(f"JAR字节不同{entry}")
                class_count+=1
        if module == "paicli-cli":
            needed=["com/paicli/cli/Main.class","com/paicli/runner/RunnerTerminalHost.class","com/pty4j/windows/conpty/WinConPtyProcess.class","META-INF/services/com.paicli.runtime.center.CenterCommandGateway"]
            for entry in needed:
                if entry not in archive.namelist(): errors.append("完整CLI缺少"+entry)
            natives=[entry for entry in archive.namelist() if entry.startswith("resources/com/pty4j/native/")]
            record("完整CLI分发入口", bool(natives), f"SPI、宿主、ConPTY类可见；{len(natives)}个原生资源条目，未执行PTY")
    destination=candidate / jar.name
    if destination.exists() and digest(destination)!=digest(jar): raise RuntimeError("候选目录已存在不同制品，拒绝覆盖")
    if not destination.exists(): shutil.copy2(jar,destination)
    artifacts.append({"文件":str(destination.relative_to(root)),"字节":destination.stat().st_size,"SHA256":digest(destination),"用途":"本地候选，未更新运行目标"})
record("打包类与编译类字节", not any("JAR" in error for error in errors), f"实际核对{class_count}个受影响类，包含内部类")
web=root / ".runtime/paicli/terminal-frontend-final-20261004"
for item in [web / "index.html"] + sorted((web / "static").rglob("*")):
    if item.is_file(): artifacts.append({"文件":str(item.relative_to(root)),"字节":item.stat().st_size,"SHA256":digest(item),"用途":"前端独立候选目录，未更新运行目标"})
record("前端候选产物", (web / "index.html").exists(), f"记录{len(artifacts)-4}件前端文件指纹；未打开产品页面")
sources=[{"文件":str(p.relative_to(root)),"SHA256":digest(p)} for p in selected]
report={"性质":"本地静态及制品检查；不是产品测试或业务验收", "检查":checks,"错误":errors,"源码":sources,"制品":artifacts,"运行目标更新":False,"数据库迁移执行":False}
(evidence / "最终静态核对与制品指纹.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps({"检查项":len(checks),"错误":errors,"源码文件":len(sources),"受影响类":class_count,"候选制品":len(artifacts)},ensure_ascii=False))
raise SystemExit(1 if errors else 0)
