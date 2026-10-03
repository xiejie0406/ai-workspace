"""准备私有文件及独立预期；不登录、不连接中心或真实数据库。"""
from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from datetime import datetime, timezone
from pathlib import Path


WORKSPACE = Path(__file__).resolve().parents[5]
EVIDENCE = Path(__file__).resolve().parent


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def no_jump(path: Path) -> None:
    if path.exists():
        info = path.lstat()
        if path.is_symlink() or getattr(info, "st_file_attributes", 0) & 0x400:
            raise RuntimeError("样本路径存在链接或reparse point")


def main() -> None:
    runtime = Path((WORKSPACE / ".runtime/paicli/current-runtime.txt").read_text(encoding="utf-8").strip())
    no_jump(runtime)
    runtime = runtime.resolve(strict=True)
    if runtime.parent != Path.home().resolve() or not re.fullmatch(r"paicli-local-\d{8}T\d{6}-[a-f0-9]{32}", runtime.name):
        raise RuntimeError("运行目录超出本轮私有范围")
    samples = runtime / "acceptance-samples"
    no_jump(samples)
    old = json.loads((EVIDENCE / "本机业务样本基线.json").read_text(encoding="utf-8-sig"))
    for item in old["Files"]:
        p = samples / item["Workspace"] / item["Path"]
        no_jump(p)
        if sha(p.read_bytes()) != item["SHA256"]:
            raise RuntimeError("已有业务样本摘要变化，停止新增")
    root = samples / "rules-skills"
    no_jump(root)
    root.mkdir(exist_ok=True)
    files: list[dict] = []

    def write(relative: str, data: str | bytes) -> bytes:
        target = root / relative
        raw = data.encode("utf-8") if isinstance(data, str) else data
        for parent in reversed(target.parents):
            if parent == root or root in parent.parents:
                no_jump(parent)
                parent.mkdir(exist_ok=True)
        no_jump(target)
        if target.exists():
            if target.read_bytes() != raw:
                raise RuntimeError("样本已存在但内容不同，未覆盖")
        else:
            with target.open("xb") as out:
                out.write(raw)
                out.flush()
                os.fsync(out.fileno())
        if target.read_bytes() != raw:
            raise RuntimeError("样本回读不一致")
        files.append({"path": relative, "size": len(raw), "sha256": sha(raw)})
        return raw

    pai = [
        ("USER_PAI", "PERSONAL", "synthetic-user/PAI.md", "user/PAI.md", "PAI_LAYER_1_USER\r\n"),
        ("ROOT_PAI", "PROJECT", "project/PAI.md", "PAI.md", "PAI_LAYER_2_ROOT\r\n@imports/root-style.md\r\n@imports/root-cycle.md\r\n"),
        ("PAICLI_PAI", "PROJECT", "project/.paicli/PAI.md", ".paicli/PAI.md", "PAI_LAYER_3_PAICLI\r\n"),
        ("LOCAL_PAI", "PROJECT", "project/PAI.local.md", "PAI.local.md", "PAI_LAYER_4_LOCAL\r\n"),
        ("PAICLI_LOCAL_PAI", "PROJECT", "project/.paicli/PAI.local.md", ".paicli/PAI.local.md", "PAI_LAYER_5_PAICLI_LOCAL\r\n"),
    ]
    imported = {"imports/root-style.md": "IMPORT_ROOT_STYLE\r\n", "imports/root-cycle.md": "@root-cycle.md\r\n"}
    for relative, body in imported.items():
        write("project/" + relative, body)
    registration = []
    for layer, scope, relative, source_path, body in pai:
        raw = write(relative, body)
        value = {"layer": layer, "scope": scope, "sourcePath": source_path, "content": body, "sha256": sha(raw), "enabled": True}
        if layer == "ROOT_PAI":
            value["imports"] = [{"path": p, "content": text, "sha256": sha(text.encode("utf-8"))} for p, text in imported.items()]
        registration.append({"inputTemplate": value, "requiresActualWorkspaceId": scope == "PROJECT"})
    write("pai-registration-templates.json", json.dumps({"fixtureOnly": True, "templates": registration}, ensure_ascii=False, indent=2) + "\n")

    def skill_text(name: str, marker: str, description: str) -> str:
        return f"---\nname: {name}\ndescription: {description}\nversion: '1'\nauthor: acceptance-fixture\ntags: [coding, fixture]\n---\n{marker}\n这些样本文字不能扩大文件、命令或网络许可。\n"

    same_name = []
    for folder, scope, marker in [("builtin", "BUILTIN", "SKILL_BUILTIN"), ("synthetic-user", "USER", "SKILL_USER"), ("project", "PROJECT", "SKILL_PROJECT")]:
        content = skill_text("common-coding", marker, "同名三层覆盖样本")
        relative = f"{folder}/skills/common-coding/SKILL.md"
        if folder != "builtin":
            relative = f"{folder}/.paicli/skills/common-coding/SKILL.md"
        raw = write(relative, content)
        same_name.append({"name": "common-coding", "scope": scope, "version": 1, "enabled": True, "manifestApproved": True, "content": content, "sha256": sha(raw), "trustHash": sha(raw)})
    # 逆序输入可检查实现是否真正按来源层排序，而不是依赖插入顺序。
    write("skill-three-layer-context.json", json.dumps({"skillsConfigs": list(reversed(same_name))}, ensure_ascii=False, indent=2) + "\n")
    write("skill-disabled-state.json", '{"disabled":["common-coding"]}\n')
    bad = dict(same_name[-1]); bad["content"] += "UNAPPROVED_APPEND\n"
    write("skill-tampered-context.json", json.dumps({"skillsConfigs": [bad]}, ensure_ascii=False, indent=2) + "\n")
    invalid = "---\nname: malformed\ndescription: { nested: value }\n---\nINVALID_FRONTMATTER\n"
    raw = write("project/.paicli/skills/malformed/SKILL.md", invalid)
    write("skill-invalid-frontmatter-context.json", json.dumps({"skillsConfigs": [{"name": "malformed", "scope": "PROJECT", "version": 1, "enabled": True, "manifestApproved": True, "content": invalid, "sha256": sha(raw), "trustHash": sha(raw)}]}, ensure_ascii=False, indent=2) + "\n")
    bulk = []
    for i in range(21):
        name = f"capacity-{i:02d}"
        content = skill_text(name, f"CAPACITY_{i:02d}", "🙂" * 501 if i == 0 else f"索引容量样本{i:02d}")
        raw = write(f"capacity/{name}/SKILL.md", content)
        bulk.append({"name": name, "scope": "PROJECT", "version": 1, "enabled": True, "manifestApproved": True, "content": content, "sha256": sha(raw), "trustHash": sha(raw)})
    write("skill-capacity-context.json", json.dumps({"skillsConfigs": list(reversed(bulk))}, ensure_ascii=False, indent=2) + "\n")

    memory = {
        "fixtureOnly": True,
        "anchorInstant": "2026-10-03T00:00:00Z",
        "cases": [
            {"name": "重复事实", "content": "max_workers=4", "repeatContent": "max_workers=4", "expected": "DUPLICATE；不会新增同一事实"},
            {"name": "冲突事实", "content": "max_workers=4", "changedContent": "max_workers=6", "expected": "CONFLICT；只有用户选择替换/共存后才改变；取消不写"},
            {"name": "恰好30天", "timestamp": "2026-09-01T00:00:00Z", "lastVerifiedAt": "2026-09-03T00:00:00Z", "expectedStaleAtAnchor": False},
            {"name": "超过30天1秒", "timestamp": "2026-09-01T00:00:00Z", "lastVerifiedAt": "2026-09-02T23:59:59Z", "expectedStaleAtAnchor": True},
            {"name": "刚核实", "timestamp": "2026-09-01T00:00:00Z", "lastVerifiedAt": "2026-10-03T00:00:00Z", "expectedStaleAtAnchor": False},
        ],
        "limits": ["时间边界仅为固定Clock的独立数据答案；未改变真实系统时间或数据库。", "当前memory保存由服务器生成时间；真实30天边界仍需适当既有来源或已批准测试条件。", "模板不含真实中心记忆ID、工作区ID或用户ID；实际CAS需取得中心当前版本。"],
    }
    write("memory-independent-cases.json", json.dumps(memory, ensure_ascii=False, indent=2) + "\n")
    source_paths = ["ruoyi-backend/ruoyi-paicli/paicli-core/src/main/java/com/paicli/prompt/ProjectMemoryLoader.java", "ruoyi-backend/ruoyi-paicli/paicli-core/src/main/java/com/paicli/skill/SkillRegistry.java", "ruoyi-backend/ruoyi-paicli/paicli-core/src/main/java/com/paicli/skill/SkillFrontmatterParser.java", "ruoyi-backend/ruoyi-paicli/paicli-core/src/main/java/com/paicli/skill/SkillIndexFormatter.java", "ruoyi-backend/ruoyi-paicli/paicli-runner/src/main/java/com/paicli/runner/NativeTaskExecutor.java"]
    baseline = {"preparedAt": datetime.now(timezone.utc).isoformat(), "privateRoot": str(root), "fixtureOnly": True, "files": files, "sources": [{"path": p, "sha256": sha((WORKSPACE / p).read_bytes())} for p in source_paths], "independentExpected": {"paiLayerOrder": [p[0] for p in pai], "paiMarkerOrder": ["PAI_LAYER_1_USER", "PAI_LAYER_2_ROOT", "IMPORT_ROOT_STYLE", "PAI_LAYER_3_PAICLI", "PAI_LAYER_4_LOCAL", "PAI_LAYER_5_PAICLI_LOCAL"], "cyclicImport": "跳过循环，保留其余正文与原始来源", "skillSameNameWinner": "PROJECT/SKILL_PROJECT", "skillOffByName": "原按名称禁用后应无同名技能进入下一轮上下文；当前中心语义尚在只读核对", "skillTamperedOrMalformed": "中心批准解析拒绝，不加载正文", "capacityRegisteredNames": 21, "capacityIndexNames": [f"capacity-{i:02d}" for i in range(20)], "excludedIndexName": "capacity-20", "descriptionKeptCodepointsBeforeEllipsis": 500}, "databaseWrites": 0, "networkRequests": 0, "businessExecuted": 0, "businessPassed": 0, "remaining": ["真实账号和工作区权限", "真实规则/Skill登记和Runner上下文", "实际Skill启停、原文回读与CAS", "真实时间边界前提"]}
    for item in old["Files"]:
        if sha((samples / item["Workspace"] / item["Path"]).read_bytes()) != item["SHA256"]:
            raise RuntimeError("准备后已有业务样本摘要变化")
    destination = EVIDENCE / "规则与Skill样本基线.json"
    if destination.exists():
        prior = json.loads(destination.read_text(encoding="utf-8-sig"))
        if prior["files"] != files:
            raise RuntimeError("已有样本基线不同，停止覆盖")
    else:
        with destination.open("x", encoding="utf-8", newline="\n") as out:
            json.dump(baseline, out, ensure_ascii=False, indent=2)
            out.write("\n")
    print(json.dumps({"filesPreparedAndReadBack": len(files), "originalBusinessFilesUnchanged": len(old["Files"]), "databaseWrites": 0, "networkRequests": 0, "businessExecuted": 0}))


if __name__ == "__main__":
    main()
