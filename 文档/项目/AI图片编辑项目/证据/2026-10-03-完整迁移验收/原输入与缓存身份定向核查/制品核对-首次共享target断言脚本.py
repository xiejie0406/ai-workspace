"""只读核对已有构建，冻结新Python包；不编译、测试、导入Runtime或操作服务。"""
from datetime import UTC, datetime
import hashlib
import io
import json
from pathlib import Path
import shutil
import tarfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile


root = Path(__file__).resolve().parents[2]
private = root / ".runtime/aipicture"
baseline_path = private / "remaining-completion-artifacts.json"
output_path = private / "unicode-image-artifacts.json"
package_directory = private / "python-package-unicode-image-20261003"
assert not output_path.exists(), "禁止覆盖已有冻结JSON"
assert not package_directory.exists(), "禁止覆盖已有冻结包目录"
assert private.resolve().is_relative_to(root.resolve())


def file_sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def data_sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


baseline = read(baseline_path)
baseline_sha = file_sha(baseline_path)
pyroot = root / "python/aipicture-runtime"
py_sources = {path.relative_to(pyroot).as_posix(): file_sha(path)
              for path in sorted((pyroot / "aipicture_runtime").rglob("*.py"))}
assert len(py_sources) == 38 and py_sources.keys() == baseline["python"]["source_sha256"].keys()
changed = [name for name, sha in py_sources.items() if baseline["python"]["source_sha256"][name] != sha]
expected_changed = {"aipicture_runtime/artifacts.py", "aipicture_runtime/main.py",
                    "aipicture_runtime/edits/segment.py"}
assert set(changed) == expected_changed, changed

agent_paths = [private / "image-input-rejection-release.json", private / "sam-cache-identity-after.json"]
agent_records = [read(path) for path in agent_paths]
agent_production = {}
for record in agent_records:
    for entry in record.get("sources", record.get("files", [])):
        if entry["path"].startswith("python/aipicture-runtime/aipicture_runtime/"):
            name = entry["path"].removeprefix("python/aipicture-runtime/")
            assert py_sources[name] == entry["sha256"], name
            agent_production[name] = entry["sha256"]
assert agent_production.keys() == expected_changed

build_log = private / "unicode-image-python-build.log"
build_text = build_log.read_text(encoding="utf-8-sig", errors="replace")
wheel = pyroot / "dist/ruoyi_aipicture_runtime-0.1.0-py3-none-any.whl"
sdist = pyroot / "dist/ruoyi_aipicture_runtime-0.1.0.tar.gz"
assert f"Successfully built dist\\{wheel.name}" in build_text
assert f"Successfully built dist\\{sdist.name}" in build_text
wheel_sha, sdist_sha = file_sha(wheel), file_sha(sdist)
with ZipFile(wheel) as archive, tarfile.open(sdist) as tar:
    wheel_names = {name for name in archive.namelist() if name.startswith("aipicture_runtime/") and name.endswith(".py")}
    tar_sources = {member.name.partition("/")[2]: member for member in tar.getmembers()
                   if member.isfile() and member.name.partition("/")[2].startswith("aipicture_runtime/")
                   and member.name.endswith(".py")}
    assert wheel_names == py_sources.keys() == tar_sources.keys()
    for name, sha in py_sources.items():
        assert data_sha(archive.read(name)) == sha, ("wheel", name)
        assert data_sha(tar.extractfile(tar_sources[name]).read()) == sha, ("sdist", name)
    readme_members = [member for member in tar.getmembers() if member.name.partition("/")[2] == "README.md"]
    assert len(readme_members) == 1
    assert data_sha(tar.extractfile(readme_members[0]).read()) == file_sha(pyroot / "README.md")

lock_sha = file_sha(pyroot / "uv.lock")
assert lock_sha == baseline["python"]["uv_lock_sha256"]

module = root / "ruoyi-backend/ruoyi-aipicture"
java_sources = {path.relative_to(module / "src/main/java").as_posix(): file_sha(path)
                for path in sorted((module / "src/main/java/com/ruoyi/aipicture").rglob("*.java"))}
assert java_sources == baseline["java"]["source_sha256"]
classes = {path.relative_to(module / "target/classes").as_posix(): file_sha(path)
           for path in sorted((module / "target/classes/com/ruoyi/aipicture").rglob("*.class"))}
assert classes == baseline["java"]["compiled_classes"]
frozen_java = Path(baseline["java"]["frozen_path"])
assert file_sha(frozen_java) == baseline["java"]["total_sha256"]
assert file_sha(root / "ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar") == baseline["java"]["total_sha256"]
with ZipFile(frozen_java) as jar:
    payload = jar.read("BOOT-INF/lib/ruoyi-aipicture-3.9.2.jar")
    assert data_sha(payload) == baseline["java"]["module_sha256"]
    assert file_sha(module / "target/ruoyi-aipicture-3.9.2.jar") == data_sha(payload)
    with ZipFile(io.BytesIO(payload)) as packed:
        packed_classes = {name: data_sha(packed.read(name)) for name in packed.namelist()
                          if name.startswith("com/ruoyi/aipicture/") and name.endswith(".class")}
        assert packed_classes == classes
        for name, sha in baseline["java"]["contracts"].items():
            assert data_sha(packed.read("contracts/aipicture/v1/" + name)) == sha
            assert file_sha(root / "contracts/aipicture/v1" / name) == sha
            assert file_sha(module / "target/classes/contracts/aipicture/v1" / name) == sha
assert len(java_sources) == 32 and len(classes) == 43 and len(baseline["java"]["contracts"]) == 5

# 只读既有XML核定计数与不重叠，不运行pytest。
xml_paths = [private / "image-input-rejection-after.xml", private / "sam-cache-identity-after.xml"]
case_groups = []
for path in xml_paths:
    tree = ET.parse(path)
    suites = list(tree.iter("testsuite"))
    assert all(int(suite.get("failures", "0")) == int(suite.get("errors", "0")) == int(suite.get("skipped", "0")) == 0
               for suite in suites)
    names = {(case.get("classname"), case.get("name")) for case in tree.iter("testcase")}
    assert len(names) == sum(int(suite.get("tests", "0")) for suite in suites)
    case_groups.append(names)
assert [len(group) for group in case_groups] == [23, 21]
assert not (case_groups[0] & case_groups[1])

# 所有核对通过后才写新目录和独立JSON；旧包/旧阶段记录一律保持。
package_directory.mkdir(exist_ok=False)
for original in [wheel, sdist]:
    target = package_directory / original.name
    with original.open("rb") as incoming, target.open("xb") as outgoing:
        shutil.copyfileobj(incoming, outgoing)
    assert file_sha(target) == file_sha(original)
assert file_sha(baseline_path) == baseline_sha

java = dict(baseline["java"])
java.update(fields_inherited_from=".runtime/aipicture/remaining-completion-artifacts.json",
            fields_inherited_record_sha256=baseline_sha, current_changed_vs_baseline=[],
            recompiled_during_this_freeze=False, tests_executed_during_this_freeze=0,
            inherited_targeted_tests_note="13为原r11检查，不是本次重测；本次核Java字节/源码/class/契约一致性。",
            readonly_rechecked_counts={"sources": len(java_sources), "classes": len(classes), "contracts": 5})
python = dict(baseline["python"])
python.update(source_sha256=py_sources, changed_sources=changed,
              source_wheel_sdist_match_count=38, wheel_sha256=wheel_sha, sdist_sha256=sdist_sha,
              frozen_directory=str(package_directory), uv_lock_sha256=lock_sha,
              targeted_tests=sum(len(group) for group in case_groups),
              targeted_tests_origin="引用两个代理既有after XML的44互异检查，不是在冻结阶段执行。",
              targeted_test_composition={"image_input_rejection": 23, "sam_cache_identity": 21, "intersection": 0},
              tests_executed_during_this_freeze=0, real_inference_reexecuted_this_turn=False,
              source_delta_baseline=".runtime/aipicture/remaining-completion-artifacts.json",
              agent_source_fingerprints_match=agent_production, sdist_readme_matches=True,
              readme_sha256=file_sha(pyroot / "README.md"), wheel_size_bytes=wheel.stat().st_size,
              sdist_size_bytes=sdist.stat().st_size, parent_build_exit_code=0,
              parent_build_method="uv build --offline，主线程已执行；本脚本只读取成功日志和包。",
              build_log=".runtime/aipicture/unicode-image-python-build.log", build_log_sha256=file_sha(build_log))
record = {"frozen_at": datetime.now(UTC).isoformat(), "java": java, "python": python,
          "baseline_record": ".runtime/aipicture/remaining-completion-artifacts.json",
          "baseline_record_sha256": baseline_sha, "runtime_synchronized": False,
          "provider_calls": 0, "database_writes": 0, "business_acceptance": False,
          "tests_executed_during_freeze": 0, "builds_executed_during_freeze": 0,
          "model_inference_during_freeze": 0, "service_operations_during_freeze": 0,
          "new_python_packages_only": True,
          "referenced_agent_records": [{"path": str(path.relative_to(root)), "sha256": file_sha(path)} for path in agent_paths],
          "referenced_test_xml": [{"path": str(path.relative_to(root)), "sha256": file_sha(path)} for path in xml_paths],
          "note": "仅冻结构建字节并核对，运行同步/实际HTTP与真实模型证据由主线程后续单独记录。"}
with output_path.open("x", encoding="utf-8") as output:
    output.write(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"record": str(output_path), "record_sha256": file_sha(output_path),
                  "python": {key: python[key] for key in ["source_wheel_sdist_match_count", "changed_sources", "wheel_sha256", "sdist_sha256", "frozen_directory", "targeted_tests"]},
                  "java_readonly_rechecked_counts": java["readonly_rechecked_counts"],
                  "runtime_synchronized": False, "tests_executed_during_freeze": 0}, ensure_ascii=False))
