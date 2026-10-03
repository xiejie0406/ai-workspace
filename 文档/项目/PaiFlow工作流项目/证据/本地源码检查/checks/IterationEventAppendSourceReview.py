"""本批已冻结append/Policy源码事实，独立有限probe与静态结论分开。"""
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[6]
CHECKS=Path(__file__).resolve().parent
paths=['ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/'+p for p in
    ['PureIterationPolicy.java','RunLeaseService.java','RunJsonCodec.java','RunJsonBoundary.java']]
paths+=['python/paiflow-runtime/worker/task_runner.py','python/paiflow-runtime/verification/pure-iteration-java-assignment-check.json']
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
before={p:sha(ROOT/p) for p in paths}
assert before[paths[0]]=='07df720d4b2b505a4b50b95a44058c5a102353ad9ae216eecba5160f6d1c4684'
assert before[paths[1]]=='6f3a17e1dc56943f5266a1bbc4c34ea5ca8d4e1f2889f99aea9d27c9d4de11b3'
assert before[paths[4]]=='f567da8fc3b06cb6810d4dd42af17338c6bfc2e7bbee9578f56009da1e90e65d'
content={p:(ROOT/p).read_text(encoding='utf-8-sig') for p in paths}
def ref(index,needle):
    text=content[paths[index]]
    assert needle in text,needle
    return {'path':paths[index],'line':text[:text.index(needle)].count('\n')+1}
facts=[
 {'fact':'validate与requireEventScope复用唯一私有Analysis；预算及owner来自固定DSL/Input，未建立缓存或表。',
  'refs':[ref(0,'private record Analysis'),ref(0,'return analyze(execution,input).budget()'),ref(0,'var analysis=analyze(execution,input)')]},
 {'fact':'主scope含iteration容器不能带tag；inner必须完整owner前缀与规范ASCII非负索引，小于固定数组批数。两位限制符合MAX_BATCH64且超大数先被长度拒。',
  'refs':[ref(0,'if(owner.equals("main"))'),ref(0,'String prefix=owner+":"'),ref(0,'index.length()>2'),ref(0,'Integer.parseInt(index)>=batch')]},
 {'fact':'ordinary图仅检查实际节点存在和无tag，不借本次PurePolicy重新授予Agent/model等资源能力。node空/unknown在member门拒。',
  'refs':[ref(0,'if(!member)throw invalid'),ref(0,'if(!iteration)')]},
 {'fact':'optional缺input取原有效required后决定声明default或[]；显式false/0/null不作为数组，显式null不选default。items细类型仍由原Factory裁决，guard接受某些原FactoryFAILED样例不等于执行成功。',
  'refs':[ref(0,'if(input.containsKey(sourceName))actual=input.get(sourceName)'),ref(0,'ModelNodeOptions.bool(output.get("required")'),ref(0,'if(!(actual instanceof JSONArray array))')]},
 {'fact':'append协议/预算初验后锁实际run；sameLease校service身份并调用ownerStillAuthorized，其账号、成员、菜单复查在读取历史seq回执之前；当前租约/deadline/begin也先于回执。',
  'refs':[ref(1,'ownerStillAuthorized(run);'),ref(1,'permissions.getMenuPermission(user)'),ref(1,'var run=lock(id);sameLease(run,worker,event.getString("leaseId"),attempt(event));active(run);requireExecutionBegun(run)')]},
 {'fact':'seq<=lastEventSeq仅比较run不可变编码标记下canonical原文；完全相同回执无insert/update，不重写存量坏tag；不同内容409。新seq严格last+1，不能以旧replay授新scope。',
  'refs':[ref(1,'if(seq<=run.getLastEventSeq())'),ref(1,'if(!encoded.equals(runs.event(id,seq)))'),ref(1,'if(seq!=run.getLastEventSeq()+1)'),ref(2,'public static String canonical(RunRecord run,JSONObject value)')]},
 {'fact':'新seq在insertEvent之前以assignment.dsl/input校owner/batch。实际存在legacyFrame时要求对象、raw.id==实际run、workflow_step.node.id==外层node；辅助事件不强造frame，不从frame推scope。',
  'refs':[ref(1,'PureIterationPolicy.requireEventScope(execution,input,node,iteration)'),ref(1,'if(payload.containsKey("legacyFrame"))'),ref(1,'Objects.equals(run.getId(),frame.get("id"))'),ref(1,'Objects.equals(node,frameNode.get("id"))'),ref(1,'if(runs.insertEvent(id,seq,encoded)!=1)')]},
 {'fact':'已保存24真实回调包含主End提前started，不能附加整图串行生命周期或以单个node.stop代替run终态。此次未重跑Factory或篡改原artifact期限。',
  'refs':[{'path':paths[5],'jsonPointer':'/originalFactory/originalCallbackFrames'}]},
]
after={p:sha(ROOT/p) for p in paths}
proof=CHECKS/'纯迭代事件归属实际策略独立有限检查.json'
assert sha(proof)=='929c19f4af89507ba87fe569ebd5c692b9732a7e5fc7803fa85f9832dbb1a3b5'
data={'checkedAtUtc':datetime.now(timezone.utc).isoformat(),'scope':'现有checks的独立源码审查，不是第二状态或正式事务验收',
    'sourceBefore':before,'sourceAfter':after,'sourceWindowChanges':[p for p in before if before[p]!=after[p]],
    'facts':facts,'actualPrivatePolicyProof':{'path':str(proof),'sha256':sha(proof),'cases':86,'compileExit':0,'runExit':0},
    'confirmedDefects':[],'pending':['未收到本批LegacyWorkflowStreamCodec冻点，尚未实际新投影检查',
        'RunLease仅本次静态源码核对；没有实际Mapper/事务/授权撤销/并发执行probe，须根定向Maven另证'],
    'realDBHTTPProviderEngineServicesMavenCalls':0,'productionTestContractTargetEdits':0}
assert not data['sourceWindowChanges']
out=CHECKS/'纯迭代事件append与重放独立源审查.json'
if out.exists():raise RuntimeError('已有原证据拒覆盖')
out.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'path':str(out),'sha256':sha(out),'facts':len(facts),'sourceChanged':data['sourceWindowChanges']},ensure_ascii=False))
