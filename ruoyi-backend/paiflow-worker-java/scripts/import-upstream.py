from pathlib import Path
import hashlib
import json
import shutil

target = Path(__file__).resolve().parents[1]
workspace = target.parents[1]
source = workspace / "参考项目/PaiFlow-main/core-workflow-java"
roots = ["src/main/java/com/iflytek/astron/workflow/engine", "src/main/java/com/iflytek/astron/workflow/exception", "src/main/java/com/iflytek/astron/workflow/components"]
files = [path for root in roots for path in (source / root).rglob("*") if path.is_file()]
files += [source / "src/main/java/com/iflytek/astron/link/execution/HttpExecutor.java", source / "src/main/java/com/iflytek/astron/workflow/controller/vo/NodeDebugRespVo.java"]
files += list((source / "src/test").rglob("*.java"))
files += [source / "pom.xml"]
manifest = []
for path in files:
    relative = path.relative_to(source)
    destination = target / relative
    if destination.exists():
        raise RuntimeError(f"拒绝覆盖已有源码：{destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, destination)
    manifest.append({"source": path.relative_to(workspace).as_posix(), "target": relative.as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size})
for name in ["LICENSE", "NOTICE"]:
    path = source.parent / name
    if path.exists():
        shutil.copy2(path, target / name)
(target / "upstream-manifest.json").write_text(json.dumps({"source": "参考项目/PaiFlow-main/core-workflow-java", "files": manifest, "excluded": ["WorkflowApplication.java旧启动入口", "workflow/controller旧管理HTTP入口（除引擎所需NodeDebugRespVo）", "workflow/flow全部正式WorkflowMapper及Service/数据库配置", "link/tools全部正式ToolMapper及CRUD/数据库配置", "link/cache正式Redis状态", "link/controller全部正式管理HTTP入口", "src/main/resources旧配置与mapper", "Dockerfile/启动脚本/.env/cache/构建产物"]}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"引入 {len(manifest)} 个源文件")
