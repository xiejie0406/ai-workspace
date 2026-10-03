"""READY之后才读取实际Java投影产物，并复用原页面AST/Node VM。"""
import argparse
from datetime import datetime, timezone
import json
import subprocess
from QaGoldenFramesReview import ROOT, OUT, JAVASCRIPT, sha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ready", action="store_true", help="仅在根代理通知真实Maven产物READY后使用")
    args = parser.parse_args()
    if not args.ready:
        raise SystemExit("待真实Maven产物READY；本脚本不会生成或猜造Java帧")
    artifact = ROOT / "ruoyi-backend/ruoyi-paiflow/target/legacy-workflow-projected-frames.json"
    if not artifact.is_file():
        raise SystemExit("实际Java投影产物不存在，未执行消费者核对")
    consumer = ROOT / "frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts"
    sources = [
        artifact, consumer,
        ROOT / "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowStreamCodec.java",
        ROOT / "ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowStreamCodecTest.java",
        ROOT / "ruoyi-backend/ruoyi-paiflow/target/classes/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowStreamCodec.class",
        ROOT / "contracts/paiflow/fixtures/legacy-workflow-stream.json",
        ROOT / "文档/项目/PaiFlow工作流项目/证据/本地源码检查/静态问答与运行JSON编码冻结输入.json",
    ]
    before = {p.relative_to(ROOT).as_posix(): sha(p) for p in sources}
    source = json.loads(artifact.read_text(encoding="utf-8"))
    frames = source["frames"]
    assert [f["name"] for f in frames] == ["golden-0", "golden-1", "golden-2", "golden-3", "golden-4", "center-COMPLETED", "center-FAILED", "center-CANCELLED"]
    command = ["node", "-e", JAVASCRIPT, str(ROOT / "frontend/paiflow-web/node_modules/typescript"), str(consumer)]
    executed = subprocess.run(command, input=json.dumps(frames), capture_output=True, text=True, encoding="utf-8")
    if executed.returncode:
        raise RuntimeError((executed.returncode, executed.stderr))
    observations = json.loads(executed.stdout)
    assert len(observations) == 8
    assert observations[0]["nodeUpdates"][0]["nodeStatus"] == "ing"
    assert [o["id"] for o in observations[1]["interrupt"]["option"]] == ["A", "B"]
    assert observations[1]["interrupt"]["needReply"] is False
    assert observations[2]["interrupt"]["needReply"] is True
    assert observations[3]["nodeUpdates"][0]["nodeStatus"] == "stop" and observations[3]["flowResult"] is None
    assert observations[3]["nodeUpdates"][0]["responseResult"]["outputs"]["opaqueNull"] is None
    assert observations[4]["nodeUpdates"][0]["responseResult"]["status"] == "failed" and observations[4]["flowResult"] is None
    for index in [5, 6, 7]:
        result = observations[index]["flowResult"]
        assert result["status"] == ("success" if index == 5 else "failed")
        assert result["totalTokens"] == "5" and result["timeCost"] == "0.75"
    assert [frames[i]["frame"]["code"] for i in [5, 6, 7]] == [0, 500, 499]
    assert all(not o["auditCalled"] for o in observations)
    after = {p.relative_to(ROOT).as_posix(): sha(p) for p in sources}
    assert before == after
    copy = OUT / "原问答Java实际投影八帧快照.json"
    copy.write_bytes(artifact.read_bytes())
    assert sha(copy) == before[artifact.relative_to(ROOT).as_posix()]
    result = {
        "schemaVersion": "paiflow.legacy-java-projection-original-consumer-review.v1",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "status": "actual_java_artifact_original_consumer_branches_checked",
        "artifact": artifact.relative_to(ROOT).as_posix(),
        "artifactScope": source.get("scope"),
        "preservedArtifact": copy.relative_to(ROOT).as_posix(),
        "sourceHashesBefore": before, "sourceHashesAfter": after, "sourceWindowChanges": [],
        "framesChecked": 8, "originalConsumerObservations": observations,
        "command": command, "workingDirectory": str(ROOT), "exitCode": executed.returncode,
        "productionSourceModified": False, "mavenExecutedByThisProbe": False,
        "targetWrittenByThisProbe": False, "network": False, "providerCalls": False,
        "factoryCalled": False, "taskRunnerCalled": False, "centerCalls": 0,
        "browserUsed": False, "businessVerified": False,
        "limitations": [
            "Java产物来自真实project方法的隔离Run/Checkpoint fixture，不是正式中心业务记录。",
            "原TS函数用AST提取并在Node VM执行，store/setNode/render为显式spy；未做页面验收。",
            "中心CANCELLED以499在原UI呈失败终态，这是投影行为；不声称原UI已有cancelled终态或Provider已停止。",
            "核对实际产物与当前Java测试输入SHA，不替代完整Maven报告或QA原Factory执行证明。",
        ],
    }
    path = OUT / "原问答Java实际投影原消费者独立核对.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path), "artifactSha256": sha(artifact), "framesChecked": 8, "sourceWindowChanges": []}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
