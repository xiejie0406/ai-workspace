"""复用首反例同一实际DTO/adapter/原TS提取器；只验证新空值投影，不替代页面。"""
import asyncio
from datetime import datetime, timezone
import json
import subprocess
from QaFramesReadOnlyProbe import (
    ROOT, RUNTIME, OUT, FILES, Context, NODE_REVIEW, sha,
    LLMGenerate, WorkflowStep, NodeInfo, Choice, Delta, _CenterFrames,
)


async def main():
    before = {p.relative_to(ROOT).as_posix(): sha(p) for p in FILES}
    assert before["python/paiflow-runtime/worker/engine_adapter.py"] == "724a6d1f3b3d7b2e22cd3ed6c1ffc75eac01bce6c7cb7c513e88d98222ed61f8"
    start = LLMGenerate.node_start(sid="fixture-run", node_id="question-answer::fixture", alias_name="fixture QA", progress=0.5)
    complete = LLMGenerate(id="fixture-run", workflow_step=WorkflowStep(node=NodeInfo(id="question-answer::fixture", finish_reason="stop", inputs={"query": "fixture"}, outputs={"answer": "fixture", "opaqueNull": None}), progress=0.75), choices=[Choice(delta=Delta())])
    context = Context()
    adapter = _CenterFrames(context)
    await adapter.put(start)
    await adapter.put(complete)
    frames = [
        {"name": "original Pydantic node-start", "frame": start.model_dump(mode="json")},
        {"name": "new actual Worker node.started", "frame": context.events[0]["payload"]["legacyFrame"]},
        {"name": "original Pydantic node-completed", "frame": complete.model_dump(mode="json")},
        {"name": "new actual Worker node.completed", "frame": context.events[1]["payload"]["legacyFrame"]},
    ]
    command = ["node", "-e", NODE_REVIEW, str(ROOT / "frontend/paiflow-web/node_modules/typescript"), str(FILES[2])]
    completed = subprocess.run(command, input=json.dumps(frames), capture_output=True, text=True, encoding="utf-8")
    if completed.returncode:
        raise RuntimeError((completed.returncode, completed.stderr))
    observations = json.loads(completed.stdout)
    assert all(c["choiceFinishKeyPresent"] and c["choiceIsNull"] and c["originalNodeBranch"] for c in observations["cases"])
    assert observations["cases"][1]["nodeFinishKeyPresent"] and observations["cases"][1]["nodeIsNull"]
    assert observations["cases"][1]["originalNodeStatus"] == "ing"
    assert observations["cases"][3]["originalNodeStatus"] == "stop"
    assert "opaqueNull" in observations["cases"][3]["outputs"] and observations["cases"][3]["outputs"]["opaqueNull"] is None
    after = {p.relative_to(ROOT).as_posix(): sha(p) for p in FILES}
    assert before == after
    output = {
        "schemaVersion": "paiflow.worker-null-projection-independent-recheck.v1",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "status": "same_actual_dto_adapter_and_original_consumer_probe_passed",
        "historicalFailureEvidence": "原问答帧空值与Worker投影独立反例.json",
        "historicalFailureSha256": "2ae87244c6096811cceaf15d062b2d19d4394fd7b93c0604e26419f3b0f86ae1",
        "sourceHashesBefore": before,
        "sourceHashesAfter": after,
        "sourceWindowChanges": [],
        "frames": frames,
        "originalConsumerObservations": observations,
        "command": command,
        "workingDirectory": str(ROOT),
        "exitCode": completed.returncode,
        "productionSourceModified": False,
        "network": False,
        "providerCalls": False,
        "factoryCalled": False,
        "taskRunnerCalled": False,
        "centerCalls": 0,
        "browserUsed": False,
        "businessVerified": False,
        "limitations": [
            "惰性DTO帧与Context.emit收集器，没有运行正式QA/检查点/租约。",
            "只执行原TS extractNodeInfo及严格null分支，不挂载组件/store、不验证真实页面。",
            "不是265全量或54定向重跑；不改变中心旧V1历史事件已经省略的null事实。",
            "秘密边界/Provider/实际Factory留在各owner正式测试，本探针没有扩大其结论。",
        ],
    }
    path = OUT / "原问答帧空值与Worker投影独立复验.json"
    path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path), "cases": observations["cases"], "sourceWindowChanges": []}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
