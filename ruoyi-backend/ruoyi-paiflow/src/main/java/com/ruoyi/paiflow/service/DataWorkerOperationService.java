package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 复用中心Worker身份和租约，数据同步操作在同一中心事务完成并回执。 */
@Service @ConditionalOnProperty(prefix="paiflow",name={"enabled","data.enabled"},havingValue="true")
@Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
public class DataWorkerOperationService {
    private final RunMapper runs;private final SpaceMapper spaces;private final RunLeaseService leases;private final DataRuntimeCatalog catalog;private final DataOperationService operations;private final CompletedOutputSources completedOutputs;
    public DataWorkerOperationService(RunMapper runs,SpaceMapper spaces,RunLeaseService leases,DataRuntimeCatalog catalog,DataOperationService operations,CompletedOutputSources completedOutputs){this.runs=runs;this.spaces=spaces;this.leases=leases;this.catalog=catalog;this.operations=operations;this.completedOutputs=Objects.requireNonNull(completedOutputs);}
    private RunRecord current(String id,WorkerIdentityService.Principal worker,JSONObject body){var before=runs.find(id);if(before==null)throw DataResourceService.fail("运行不存在",404);if(spaces.lock(before.getSpaceId())==null)throw DataResourceService.fail("运行空间不存在",403);var run=leases.requireActiveLease(id,worker,body.getString("leaseId"),RunLeaseService.attempt(body));if(!Objects.equals(run.getSpaceId(),before.getSpaceId())||!"python".equals(run.getEngineProfile())||!"RUNNING".equals(run.getState()))throw DataResourceService.fail("数据操作运行不可继续",410);RunLeaseService.requireExecutionBegun(run);return run;}
    private static JSONObject task(RunRecord run){var value=ModelMessageSources.sourceObject(run.getAssignmentJson());if(value==null||value.getJSONObject("dsl")==null||value.getJSONObject("resourceSnapshots")==null)throw DataResourceService.fail("固定运行事实不完整",409);return value;}
    public JSONObject resource(String id,WorkerIdentityService.Principal worker,String resource,JSONObject body){RunJsonBoundary.fields(body,Set.of("leaseId","attempt"),Set.of());var run=current(id,worker,body);var descriptor=task(run).getJSONObject("resourceSnapshots").getJSONObject(resource);if(descriptor==null)throw DataResourceService.fail("数据资源不属于固定运行",403);var fixed=catalog.resolvePinned(run.getSpaceId(),run.getOwnerUserId(),resource,descriptor);current(id,worker,body);return JSON.parseObject(fixed.descriptor().toJSONString(JSONWriter.Feature.WriteMapNullValue));}
    public JSONObject start(String id,WorkerIdentityService.Principal worker,JSONObject request){
        RunJsonBoundary.fields(request,Set.of("leaseId","attempt","nodeId","operation","parameters","idempotencyKey"),Set.of("iterationId"));RunJsonBoundary.check(request,2*1024*1024);
        var run=current(id,worker,request);var task=task(run);var execution=task.getJSONObject("dsl");String nodeId=DataResourceService.text(request.get("nodeId"),255,"nodeId"),iteration=request.getString("iterationId");PureIterationPolicy.requireEventScope(execution,task.getJSONObject("input"),nodeId,iteration);
        var node=find(execution,nodeId);if(node==null||!"database".equals(com.ruoyi.paiflow.domain.NodeCatalog.typeOf(nodeId)))throw DataResourceService.fail("操作不属于固定数据节点",403);var nodeParam=node.getJSONObject("data").getJSONObject("nodeParam");var params=request.getJSONObject("parameters");if(params==null)throw DataResourceService.fail("数据操作参数缺失",400);
        RunJsonBoundary.fields(params,Set.of("resourceId","resourceVersion","kind","provider","legacyPayload","resourceBindings"),Set.of());String resource=nodeParam.getString("dataResourceId");var descriptor=task.getJSONObject("resourceSnapshots").getJSONObject(resource);
        if(descriptor==null||!Objects.equals(resource,params.get("resourceId"))||!Objects.equals(descriptor.get("version"),params.get("resourceVersion"))||!"database".equals(params.get("kind"))||!"center-data".equals(params.get("provider"))||!(params.get("resourceBindings") instanceof JSONObject bindings)||!bindings.isEmpty())throw DataResourceService.fail("数据操作与固定资源不一致",409);
        var payload=params.getJSONObject("legacyPayload");if(payload==null)throw DataResourceService.fail("数据操作正文缺失",400);validateNode(nodeParam,payload);
        var events=sources(run);var values=completedOutputs.values(run,execution,node,events);
        var expected=DataInputPayload.expected(node,values,DataResourceService.environment(task.get("dataEnvironment")));
        if(!RunJsonBoundary.canonicalFull(expected).equals(RunJsonBoundary.canonicalFull(payload)))throw DataResourceService.fail("数据操作值与固定输入及中心完成事件不一致",409);
        String kind=DataResourceService.text(request.get("operation"),64,"operation"),key=DataResourceService.text(request.get("idempotencyKey"),4096,"idempotencyKey");if(!Set.of("data.query","data.mutate").contains(kind))throw DataResourceService.fail("未知数据操作",400);
        operations.requireCompletionKey(run,node,iteration,key);
        var response=operations.execute(run,resource,descriptor,kind,payload,key);
        operations.recordCompleted(run,node,iteration,resource,descriptor,kind,payload,key,response);current(id,worker,request);
        return JSONObject.of("kind","database","provider","center-data","resourceId",resource,"resourceVersion",descriptor.get("version"),"resourceBindings",new JSONObject(),"state","COMPLETED","failureKind",null,"legacyResponse",response);
    }
    /** 规范化执行图的迭代成员也位于nodes数组；扩展对象不属于执行协议。 */
    static JSONObject find(Object value,String id){
        if(!(value instanceof JSONObject execution) || !(execution.get("nodes") instanceof JSONArray nodes))return null;
        JSONObject found=null;
        for(Object raw:nodes)if(raw instanceof JSONObject node && Objects.equals(id,node.get("id"))){if(found!=null)throw DataResourceService.fail("固定执行图节点ID重复",409);found=node;}
        return found;
    }
    private JSONArray sources(RunRecord run){
        if(run.getLastEventSeq()==null || run.getLastEventSeq()==0)return new JSONArray();
        var rows=runs.eventsThrough(run.getId(),0,run.getLastEventSeq(),1001);if(rows.size()>1000)throw DataResourceService.fail("数据输入来源事件超出当前预算",503);
        var events=new JSONArray();long bytes=0;
        for(var row:rows){bytes+=row.eventJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes>RunProtocolLimits.EVENT_BYTES)throw DataResourceService.fail("数据输入来源事件字节超出预算",413);
            var event=ModelMessageSources.sourceObject(row.eventJson());
            if(Objects.equals(run.getId(),event.get("runId")) && event.getLongValue("seq")==row.seq() && Objects.equals(run.getAttempt(),event.getInteger("attempt")))events.add(event);
        }
        return events;
    }
    /** 模式、逻辑表、投影/排序/条件结构先核对，完整值另由中心重建并对等检查。 */
    static void validateNode(JSONObject node,JSONObject payload){
        int mode=Math.toIntExact(DataResourceService.idOrZero(node.get("mode")));if(DataResourceService.idOrZero(payload.get("mode"))!=mode)throw DataResourceService.fail("Worker不能更改数据节点模式",409);
        if(mode==0){RunJsonBoundary.fields(payload,Set.of("mode","sql","execDev"),Set.of());String actual=DataResourceService.text(payload.get("sql"),65536,"CUSTOM SQL"),template=node.getString("sql");if(template==null)throw DataResourceService.fail("固定SQL缺失",409);if(!DataSqlService.matchesTemplate(template,actual))throw DataResourceService.fail("Worker SQL变量不能改变固定语句结构",409);return;}
        RunJsonBoundary.fields(payload,Set.of("mode","table","values","where","columns","order","limit","returning","execDev"),Set.of());if(!Objects.equals(node.get("tableName"),payload.get("table")))throw DataResourceService.fail("Worker不能更改逻辑表",409);
        var assignments=node.getJSONArray("assignmentList");if(assignments==null)assignments=new JSONArray();var values=payload.getJSONObject("values");if(values==null)throw DataResourceService.fail("数据values须为对象",400);if(mode==2&&!new HashSet<>(assignments.toJavaList(String.class)).equals(values.keySet()))throw DataResourceService.fail("更新列与固定节点不一致",409);if(mode!=1&&mode!=2&&!values.isEmpty())throw DataResourceService.fail("只读或删除不能携带赋值",409);
        var columns=payload.getJSONArray("columns");if(columns==null||!columns.equals(mode==3?assignments:new JSONArray()))throw DataResourceService.fail("查询投影与固定节点不一致",409);var order=node.getJSONArray("orderData");if(order==null)order=new JSONArray();if(!Objects.equals(payload.get("order"),mode==3?order:new JSONArray()))throw DataResourceService.fail("排序与固定节点不一致",409);if(!Objects.equals(payload.get("limit"),mode==3?(node.containsKey("limit")?node.get("limit"):0):null))throw DataResourceService.fail("查询数量与固定节点不一致",409);
        var cases=node.getJSONArray("cases");var first=cases==null||cases.isEmpty()?null:cases.getJSONObject(0);var where=payload.getJSONObject("where");if(where==null)throw DataResourceService.fail("条件结构缺失",400);String logical=first==null?"and":first.getString("logicalOperator");var conditions=first==null?new JSONArray():first.getJSONArray("conditions");var actual=where.getJSONArray("conditions");if(actual==null||conditions==null||actual.size()!=conditions.size()||!Objects.equals(where.get("logicalOperator"),logical))throw DataResourceService.fail("条件组与固定节点不一致",409);for(int i=0;i<conditions.size();i++){var expected=conditions.getJSONObject(i);var condition=actual.getJSONObject(i);if(!Objects.equals(expected.get("fieldName"),condition.get("fieldName"))||!Objects.equals(expected.get("selectCondition"),condition.get("operator")))throw DataResourceService.fail("条件列或操作与固定节点不一致",409);}
    }
}
