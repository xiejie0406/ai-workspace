"""快照恢复指纹的本地离线技术检查；不启动正式中心或调用模型。"""
import datetime, hashlib, json, os, pathlib, shutil, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parents[7]
OUT = pathlib.Path(__file__).resolve().parent
JAVA = pathlib.Path(r"C:\Users\admin\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot")
MAVEN = pathlib.Path(r"D:\java-i-exe\apache-maven-3.9.15\bin\mvn.cmd")
PROD = ROOT / "ruoyi-backend/ruoyi-paicli/paicli-runner/src/main/java/com/paicli/runner/ResourceFingerprint.java"
TESTS = ROOT / "ruoyi-backend/ruoyi-paicli/paicli-runner/src/test/java/com/paicli/runner"

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")

stage = sys.argv[1]
if stage not in ("修复前", "修复后"):
    raise SystemExit("只接受明确的检查阶段")
names = ["SnapshotResourceFingerprintTest"]
if stage == "修复后":
    names += ["NativeSnapshotFlowTest", "NativeSnapshotAdapterTest", "NativeSnapshotProofTest", "RunnerSnapshotFlowTest", "RunnerBoundaryTest"]
prefix = "Runner-"+stage
expected_path = OUT / (prefix+"-事前预期.json")
if expected_path.exists():
    raise SystemExit("该阶段证据已存在；禁止覆盖")
assert (ROOT / "ruoyi-backend/ruoyi-paicli/pom.xml").is_file(), ROOT
tracked = [PROD, TESTS/"SnapshotResourceFingerprintTest.java", TESTS/"NativeSnapshotFlowTest.java"]
core = ROOT/"ruoyi-backend/ruoyi-paicli/paicli-core/src/main/java/com/paicli/snapshot"
tracked += [core/"SideGitManager.java",core/"SnapshotPathSafety.java"]
before = {str(p.relative_to(ROOT)).replace("\\", "/"): sha(p) for p in tracked}
command = [str(MAVEN), "-o", "-f", "ruoyi-backend/ruoyi-paicli/pom.xml", "-pl", "paicli-runner", "-am", "-Dtest="+",".join(names), "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
expected = {"性质":"本地离线技术检查，不计业务或截图通过", "阶段":stage, "命令":command, "工作目录":str(ROOT), "预期":"修复前保留全树指纹范围缺陷；修复后定向用例无失败/错误。软链/POSIX样本只能以真实宿主能力判断，跳过不证明能力通过。", "检查类":names, "输入SHA256":before, "Java路径":str(JAVA), "创建时间":datetime.datetime.now().astimezone().isoformat()}
save(expected_path, expected)
env = os.environ.copy(); env["JAVA_HOME"] = str(JAVA)
started = datetime.datetime.now().astimezone().isoformat(); started_ns = time.time_ns(); clock = time.monotonic()
process = subprocess.Popen(command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
stdout, stderr = process.communicate()
outputs = {}
for stream, content in (("stdout",stdout),("stderr",stderr)):
    raw = OUT/(prefix+"-"+stream+".bin"); raw.write_bytes(content)
    text_path=OUT/(prefix+"-"+stream+".log")
    encoding=None
    for candidate in ("utf-8", "gb18030"):
        try:
            text=content.decode(candidate,errors="strict"); encoding=candidate; break
        except UnicodeDecodeError:
            pass
    if encoding is not None:
        text_path.write_text(text,encoding="utf-8",newline="")
    outputs[stream]={"raw":raw.name,"sha256":sha(raw),"bytes":len(content),"encoding":encoding,"text":text_path.name if encoding else None}
reports=OUT/(prefix+"-Surefire"); reports.mkdir()
copied=[]
source=ROOT/"ruoyi-backend/ruoyi-paicli/paicli-runner/target/surefire-reports"
for name in names:
    for extension in ("xml","txt"):
        path=source/(("TEST-" if extension=="xml" else "")+"com.paicli.runner."+name+"."+extension)
        if path.is_file() and path.stat().st_mtime_ns >= started_ns-2_000_000_000:
            destination=reports/path.name; shutil.copyfile(path,destination)
            copied.append({"file":str(destination.relative_to(OUT)).replace("\\","/"),"sha256":sha(destination)})
actual={"性质":expected["性质"],"阶段":stage,"命令":command,"工作目录":str(ROOT),"进程PID":process.pid,"退出码":process.returncode,"开始":started,"完成":datetime.datetime.now().astimezone().isoformat(),"耗时秒":round(time.monotonic()-clock,3),"输出":outputs,"报告":copied,"输入SHA256":before,"输入未变":{str(p.relative_to(ROOT)).replace("\\","/"):sha(p)==before[str(p.relative_to(ROOT)).replace("\\","/")] for p in tracked},"事前预期SHA256":sha(expected_path)}
save(OUT/(prefix+"-实际回执.json"),actual)
print(json.dumps({"阶段":stage,"退出码":process.returncode,"报告文件数":len(copied),"目录":str(OUT)},ensure_ascii=False))
raise SystemExit(process.returncode)
