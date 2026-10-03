"""保存既有实际迭代回调与只读源码核对；不执行Engine/服务或修改生产源码。"""
from datetime import datetime, timezone
import hashlib,json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[6]
OUT=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def anchor(path,needle):
    p=ROOT/path;lines=p.read_text(encoding='utf-8-sig').splitlines()
    matches=[i+1 for i,text in enumerate(lines) if needle in text]
    assert matches,(path,needle)
    return {'path':path,'sha256':sha(p),'needle':needle,'lines':matches}
def main():
    sources=[
      ('frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts','const handleMessage'),
      ('参考项目/PaiFlow-main/console/frontend/src/components/workflow/store/flow-chat-function.ts','const handleMessage'),
      ('frontend/paiflow-web/src/components/workflow/modal/iterative-amplification/index.tsx','flowNodes'),
      ('frontend/paiflow-web/src/components/workflow/nodes/iterator/components/flow-container/index.tsx','parentId'),
      ('python/paiflow-runtime/worker/node_context.py','def iteration_scope'),
      ('python/paiflow-runtime/worker/engine_adapter.py','_worker_iteration_id'),
      ('python/paiflow-runtime/workflow/engine/nodes/iteration/iteration_node.py','enumerate(batch_datas)'),
      ('python/paiflow-runtime/workflow/engine/callbacks/callback_handler.py','def _get_node_progress'),
      ('python/paiflow-runtime/workflow/engine/callbacks/openai_types_sse.py','finish_reason'),
      ('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/PureIterationPolicy.java','record Budget'),
      ('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunLeaseService.java','iterationId'),
      ('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowStreamCodec.java','requireSupportedExecution'),
      ('ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service/RunLeasePolicyTest.java','batch-0'),
      ('contracts/paiflow/events.schema.json','iterationId'),
    ]
    refs=[anchor(p,n) for p,n in sources];before={x['path']:x['sha256'] for x in refs}
    actual=ROOT/'python/paiflow-runtime/verification/pure-iteration-java-assignment-check.json'
    assert sha(actual)=='fa4fd2b1d374d447b11edbd676ff6c02849ff1723781b101dbc6959410bc937b'
    report=json.loads(actual.read_text(encoding='utf-8-sig'));frames=report['originalFactory']['originalCallbackFrames']
    assert len(frames)==24
    probe=OUT/'原纯迭代实际回调原React消费独立核对.json'
    assert sha(probe)=='b48c7e80429c5765157589b3620df54f810d1bd430b3e99e2169491dda89662f'
    check=json.loads(probe.read_text(encoding='utf-8-sig'))
    assert check['actualFrameCount']==24 and not check['sourceWindowChanges']
    facts=[
      {'fact':'原公开frame没有iteration身份；Worker回调创建时的ContextVar被保存为Pydantic私有attr，排队后adapter按nodeId+iterationId划分started/completed。','sources':[4,5,6,8]},
      {'fact':'单层标签为真实containerId+冒号+zero-based index，原nodeId允许::；不能用第一个冒号拆分或重写nodeId。','sources':[4,6]},
      {'fact':'原/current handleMessage只按workflow_step.node.id更新固定节点；每批running重开同节点，完成覆盖debuggerResult。24既有实际raw经两套真实AST均得到三次running/success及最后item=丙；容器输出数组保持乙/甲/丙。','sources':[0,1]},
      {'fact':'原迭代弹窗effect依赖flowNodes/flowEdges并按data.parentId复制到iteratorStore，主画布更新可传播；parentId只是原显示归属，不能授权执行scope。此次VM没有挂载React effect。','sources':[2,3]},
      {'fact':'actual24中所有choice.finish_reason为null，节点完成node.finish_reason为stop；choice.stop仅能是中心terminal。真实内部节点progress为0，来自外层master_chains定位，UI没有用它标识batch。','sources':[0,1,7,8]},
      {'fact':'外层End可在内部完成前先发started，不能把End.started或progress当完成；只有中心固定最后seq+真实terminal/result可发终帧。失败样例不是happy重建帧。','sources':[5,7,11]},
      {'fact':'现Codec whole-assignment白名单不含iteration/inner start/end，且非null iterationId直接503；纯迭代Run准入不等于原debug SSE可用。','sources':[11]},
      {'fact':'PureIterationPolicy同一分析已核Scope.owner、单归属、真实batchSizes与展开/路径预算，但返回Budget不返回owners；不得另靠raw parentId建立第二规则。','sources':[9]},
      {'fact':'append目前核run/lease/attempt、活动/已begin、fixed node和seq/CAS，但iteration只按非空String形状；原schema及旧非迭代fixture明确允许batch-0。新增owner/index准入是可观察协议收紧，必须记录必要证据和旧exact replay处理。','sources':[10,12,13]},
    ]
    recommendations=[
      '复用现Policy唯一analysis导出受控owner+fixed batchSizes；root/container节点不带tag，body节点须tag等于真实owner+":"+无前导零十进制index且0<=index<fixed input batch，未知owner/跨body/嵌套tag均拒绝。',
      '在相同run/lease/attempt/fixed-version/dslHash之后使用同一scope helper；projection必须取正式assignment.input，不用空数组、最新草稿或frame.parentId猜batch。原nodeId保全，SSE cursor仍正式runId:seq，不用raw seq=0。',
      'before-write若收紧，区分新seq准入与已保存exact replay；旧正确canonical replay不修改持久事实，旧坏scope不能通过投影归一。不得为新SSE悄删旧batch-0断言，须明确其兼容边界变化。',
      '原UI仅能保留每批即时状态/最后批结果与容器有序聚合，无法凭现组件保存完整batch trace或真实batch进度；不要伪造新展示或重写React。',
      '仍拒绝嵌套迭代、inner QA/资源/model/global节点，且不能从Frame标签单独判断能力。实际Factory错误、End预started和中心terminal分别保留。',
    ]
    after={p:sha(ROOT/p) for p in before};changed=[p for p in before if before[p]!=after[p]];assert not changed
    output={'schemaVersion':'paiflow.iteration-stream-source-review.v1','checkedAt':datetime.now(timezone.utc).isoformat(),'scope':'原单层纯迭代回调/原React消费与现中心SSE未接通边界的独立只读核对','sources':refs,'sourceHashesBefore':before,'sourceHashesAfter':after,'sourceWindowChanges':changed,'facts':facts,'recommendations':recommendations,'actualExistingSample':{'path':actual.relative_to(ROOT).as_posix(),'sha256':sha(actual),'frames':24,'rerun':False},'actualPrivateAstConsumer':{'path':probe.relative_to(ROOT).as_posix(),'sha256':sha(probe),'originalAndCurrent':True,'reactMounted':False},'limits':['源码推导与既有24帧AST内存执行分开；未重新运行Factory/TaskRunner/Engine。','未执行Maven或服务、真实DB/网络/Provider/浏览器/页面；无业务验收。','未修改Codec/Node/React/store/测试/target/契约/正式设计；本文件只保存技术来源。'],'sourceModified':False,'businessVerified':False}
    target=OUT/'原纯迭代调试SSE来源独立核对.json';assert not target.exists(),'证据已存在，不覆盖'
    target.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'path':target.relative_to(ROOT).as_posix(),'sha256':sha(target),'sources':len(refs),'actualFrames':24,'sourceWindowChanges':changed},ensure_ascii=False))
if __name__=='__main__':main()
