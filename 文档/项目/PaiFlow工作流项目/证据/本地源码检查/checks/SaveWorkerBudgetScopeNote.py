"""只保存Run与Model不同预算来源；不运行错误64KiB前提的MVC反例。"""
from datetime import datetime,timezone
import hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def source(path,needle):
    p=ROOT/path;lines=p.read_text(encoding='utf-8-sig').splitlines();matches=[i+1 for i,t in enumerate(lines) if needle in t];assert matches
    return {'path':path,'sha256':sha(p),'needle':needle,'lines':matches}
def main():
    items=[
      source('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/controller/WorkerRunController.java','RunProtocolLimits.EVENT_BYTES'),
      source('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunProtocolLimits.java','public static final int EVENT_BYTES'),
      source('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunProtocolLimits.java','public static final int RESULT_BYTES'),
      source('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/filter/WorkerRequestBoundaryFilter.java','route.endsWith("/result")'),
      source('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunLeaseService.java','RunJsonBoundary.check(result,RunProtocolLimits.RESULT_BYTES)'),
      source('contracts/paiflow/internal.openapi.json','结果正文超过2MiB'),
      source('contracts/paiflow/worker.schema.json','"Result"'),
      source('python/paiflow-runtime/worker/task_runner.py','引擎结果超出预算'),
      source('ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/ModelOperationJson.java','FRAME_BYTES'),
    ]
    before={e['path']:e['sha256'] for e in items}
    controller=ROOT/items[0]['path'];assert sha(controller)=='9691b7a01c1d577c3539fddd334b72758402a7e0952f173e085588938a626955'
    copy=OUT/'Worker结果预算核对当前入口原文/WorkerRunController.java';assert not copy.exists();copy.parent.mkdir(parents=True);shutil.copyfile(controller,copy)
    after={p:sha(ROOT/p) for p in before};assert before==after
    result={'checkedAt':datetime.now(timezone.utc).isoformat(),'scope':'区分原空值修复入口的Run结果/事件预算与Model帧预算；仅读源和已有RunProtocolLimits.class常量','sources':items,'sourceHashesBefore':before,'sourceHashesAfter':after,'sourceWindowChanges':[],'preservedCurrentController':{'path':copy.relative_to(ROOT).as_posix(),'sha256':sha(copy)},'actualFacts':{'runEventBytes':4194304,'runResultBytes':2097152,'modelFrameBytes':65536,'controllerSharedJsonBudget':'EVENT_BYTES=4MiB','resultFilterBudget':'RESULT_BYTES=2MiB','resultServiceBudget':'RESULT_BYTES=2MiB','openApiResult413Description':'结果正文超过2MiB','workerResultOutputOnlyLimit':'TaskRunner output JSON UTF8 >2MiB拒绝；中心Filter/Service限制完整结果正文','javapReadOnlyCompiledConstantObservation':'现RunProtocolLimits.class的EVENT_BYTES4194304、RESULT_BYTES2097152，与source一致；未执行Service'},'correction':'最初把Model帧64KiB误套Run事件；约70KiB合法结果不会因当前Controller4MiB预算首先413，不伪造该反例','decision':'按Root更正仅保存scope注记，预算/路由不改，正式MVC null绑定tests由Root另行实施','retainedNullEvidence':{'path':(OUT/'Worker事件HTTP显式空值修复独立复验.json').relative_to(ROOT).as_posix(),'sha256':sha(OUT/'Worker事件HTTP显式空值修复独立复验.json'),'modified':False},'budgetMvcProbeExecuted':False,'budgetServiceExecuted':False,'sourceModified':False,'targetWritten':False,'maven':False,'network':False,'database':False,'businessVerified':False}
    target=OUT/'Worker结果入口预算来源只读注记.json';assert not target.exists();target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'path':target.relative_to(ROOT).as_posix(),'sha256':sha(target),'sourceWindowChanges':[],'budgetProbeExecuted':False},ensure_ascii=False))
if __name__=='__main__':main()
