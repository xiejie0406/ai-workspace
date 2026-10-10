"""离线构建并保全本次快照/终端局部修复的新私有候选包；不启动业务通道。"""
import datetime, hashlib, json, os, pathlib, re, shutil, subprocess, time, zipfile

ROOT=pathlib.Path(__file__).resolve().parents[7]
OUT=pathlib.Path(__file__).resolve().parent
ENGINE=ROOT/"ruoyi-backend/ruoyi-paicli"
PRIVATE=pathlib.Path(r"C:\Users\admin\paicli-local-20261003T123101-ed12e6a6cb174b07bfd8db55753e8ac5")
TARGET=ENGINE/"paicli-cli/target/paicli-cli-3.9.2.jar"
OLD=PRIVATE/"acceptance-cli-a4b04ac7d6656772/paicli-cli-3.9.2.jar"
FROZEN=PRIVATE/"acceptance-cli-379a3849fac537e9/paicli-cli-3.9.2.jar"
JAVA=pathlib.Path(r"C:\Users\admin\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot")
MAVEN=pathlib.Path(r"D:\java-i-exe\apache-maven-3.9.15\bin\mvn.cmd")

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def info(path): return {"path":str(path),"sha256":sha(path),"bytes":path.stat().st_size,"mtimeNs":path.stat().st_mtime_ns}
def save(name,value): (OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
def inventory():
    result={}
    for path in ENGINE.rglob("*"):
        if not path.is_file(): continue
        parts=path.relative_to(ENGINE).parts
        if "target" in parts or "_reference" in parts or ".git" in parts: continue
        if path.name=="pom.xml" or path.suffix==".java" or ("src" in parts and "resources" in parts):
            result[str(path.relative_to(ROOT)).replace("\\","/")]=sha(path)
    return result
def entries(path):
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist(); assert len(names)==len(set(names)),"JAR含重复条目"
        return {name:hashlib.sha256(archive.read(name)).hexdigest() for name in names if not name.endswith("/")}

assert (ROOT/"ruoyi-backend/ruoyi-paicli/pom.xml").is_file()
assert PRIVATE.resolve()==PRIVATE and PRIVATE.is_dir(),"私有父目录身份异常"
assert OLD.is_file() and sha(OLD)=="a4b04ac7d66567724092b4af96d38c8e154a5eb4c0127eb912074b4fcd45ad6f"
# 旧资料导出固定379A样本不参与本次构建或覆盖；实际私有旧包按先前目录核对。
if not FROZEN.is_file():
    matches=[p for p in PRIVATE.rglob("paicli-cli-3.9.2.jar") if sha(p)=="379a3849fac537e9d371369b4a8214f1b1c753c682f92332d0bc7fe1f39b0a20"]
    assert len(matches)==1,"固定379A旧包身份不唯一"; FROZEN=matches[0]
assert not (OUT/"构建与分发-事前预期.json").exists(),"已有证据禁止覆盖"
frozen={str(p):info(p) for p in (OLD,FROZEN)}
source=inventory()
command=[str(MAVEN),"-o","-f","ruoyi-backend/ruoyi-paicli/pom.xml","-pl","paicli-cli","-am","-DskipTests=true","package"]
save("构建与分发-事前预期.json",{"性质":"技术构建与私有候选分发，不是业务通过、运行宿主更新或用户接受","时间":datetime.datetime.now().astimezone().isoformat(),"命令":command,"工作目录":str(ROOT),"Java路径":str(JAVA),"预期":"离线构建成功，新旧JAR语义差异只属于本次三生产模块，源/资源/POM不变，379A和A4旧私有包字节/mtime不变；只新增带SHA的私有候选，不启动服务/CLI/模型。","旧私有包":frozen,"输入源资源POM":source})
env=os.environ.copy();env["JAVA_HOME"]=str(JAVA)
started=datetime.datetime.now().astimezone().isoformat();clock=time.monotonic()
process=subprocess.Popen(command,cwd=ROOT,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
stdout,stderr=process.communicate()
outputs={}
for stream,content in (("stdout",stdout),("stderr",stderr)):
    raw=OUT/("构建与分发-"+stream+".bin");raw.write_bytes(content)
    encoding=None
    for candidate in ("utf-8","gb18030"):
        try: text=content.decode(candidate,errors="strict");encoding=candidate;break
        except UnicodeDecodeError: pass
    if encoding:
        log=OUT/("构建与分发-"+stream+".log");log.write_text(text,encoding="utf-8",newline="")
    outputs[stream]={"raw":raw.name,"sha256":sha(raw),"bytes":len(content),"encoding":encoding}
actual={"性质":"技术构建与私有候选分发，不计业务通过","命令":command,"工作目录":str(ROOT),"开始":started,"完成":datetime.datetime.now().astimezone().isoformat(),"进程PID":process.pid,"耗时秒":round(time.monotonic()-clock,3),"退出码":process.returncode,"输出":outputs}
save("构建与分发-实际回执.json",actual)
assert process.returncode==0,"构建失败，原证据保留；未复制新候选"
assert source==inventory(),"构建期间输入源资源POM发生变化"
assert frozen=={str(p):info(p) for p in (OLD,FROZEN)},"旧包发生变化"
before=entries(OLD);after=entries(TARGET)
changed=[name for name in sorted(before.keys()|after.keys()) if before.get(name)!=after.get(name)]
allowed=re.compile(r"^(?:com/paicli/tui/RootPane|com/paicli/tui/pane/CenterPane|com/paicli/snapshot/SideGitManager|com/paicli/snapshot/SnapshotPathSafety|com/paicli/runner/ResourceFingerprint)(?:\$[^/]*)?\.class$")
assert changed and all(allowed.fullmatch(name) for name in changed),"JAR差异超出本次生产文件，未复制候选"
target_sha=sha(TARGET);directory=PRIVATE/("acceptance-cli-"+target_sha[:16]);directory.mkdir(exist_ok=True)
assert directory.resolve()==directory and not directory.is_symlink(),"新候选目录身份异常"
candidate=directory/TARGET.name
if candidate.exists(): assert sha(candidate)==target_sha,"候选路径已有不同内容"
else: shutil.copyfile(TARGET,candidate)
assert sha(candidate)==target_sha and source==inventory() and frozen=={str(p):info(p) for p in (OLD,FROZEN)}
actual.update({"输入文件数":len(source),"输入未变":True,"旧私有包未变":True,"新目标包":info(TARGET),"新私有候选包":info(candidate),"旧ZIP文件条目数":len(before),"新ZIP文件条目数":len(after),"ZIP新增条目":[name for name in changed if name not in before],"ZIP移除条目":[name for name in changed if name not in after],"ZIP变化条目":changed,"语义差异范围核对":True,"运行边界":"没有启动新候选、Runtime、Runner、PTY或真实模型；旧资料175固定379A以及106已执行A4版本证据保持原版本。"})
save("构建与分发-实际回执.json",actual)
print(json.dumps({"退出码":0,"新私有候选包":str(candidate),"SHA256":target_sha,"ZIP变化数":len(changed),"输入文件数":len(source)},ensure_ascii=False))
