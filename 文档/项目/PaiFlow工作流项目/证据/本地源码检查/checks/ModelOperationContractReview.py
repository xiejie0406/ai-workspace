"""隔离接收端探针：人工构造中心逐行已确认响应形状，不执行中心Service/DB/SDK。"""
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[6]
sys.path.insert(0, str(root / 'python/paiflow-runtime'))
from worker.model_contract import check_frames
from worker.errors import CenterUnavailable

binding = dict(runId='run-fixture', leaseId='lease-fixture', attempt=1,
               nodeId='spark-llm::fixture', iterationId=None, workflowVersionId='1',
               dslHash='a' * 64, operationId='operation-fixture',
               resourceId='model:1:version:1', resourceVersion='1', provider='openai')
cases = []
for kind in ('transport', 'cancelled', 'lease', 'budget'):
    ack = dict(binding, state='EFFECT_UNKNOWN', operationState='EFFECT_UNKNOWN',
               frames=[], nextCursor=0, done=True, lastSeq=0, finishSeq=None,
               failureKind=kind)
    try:
        check_frames(ack, binding, 0)
        accepted = True
    except CenterUnavailable:
        accepted = False
    cases.append(dict(failureKind=kind, acceptedByCurrentConsumer=accepted))
print(json.dumps(dict(actualClientHelper=True, actualCenterService=False,
                     databaseCalls=0, networkCalls=0, providerCalls=0,
                     cases=cases), ensure_ascii=False, indent=2))
if not cases[0]['acceptedByCurrentConsumer']:
    raise SystemExit('正向transport夹具不符合接收端协议')
raise SystemExit(1 if any(not item['acceptedByCurrentConsumer'] for item in cases[1:]) else 0)
