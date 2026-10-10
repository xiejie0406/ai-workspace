package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.mapper.RunVariableMapper;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 原全局变量操作复用中心租约/历史域；不允许Worker指定Redis键或他人UID。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name={"enabled","runtime-state.enabled"},havingValue="true")
public class RunVariableService {
    private final RunVariableMapper variables;private final RunLeaseService leases;private final RunHistoryService histories;
    private org.springframework.beans.factory.ObjectProvider<CompletedOutputSources> sourceProvider;
    private com.ruoyi.paiflow.mapper.RunMapper sourceRuns;
    @org.springframework.beans.factory.annotation.Autowired
    void useSources(org.springframework.beans.factory.ObjectProvider<CompletedOutputSources> provider,com.ruoyi.paiflow.mapper.RunMapper runs){sourceProvider=provider;sourceRuns=runs;}
    private com.alibaba.fastjson2.JSONArray accepted(com.ruoyi.paiflow.domain.RunRecord run){if(sourceRuns==null)throw new ServiceException("变量输入来源事件未装配",503);var rows=sourceRuns.eventsThrough(run.getId(),0,run.getLastEventSeq(),1001);if(rows.size()>1000)throw new ServiceException("变量来源事件超出预算",503);var events=new com.alibaba.fastjson2.JSONArray();for(var row:rows){var e=ModelMessageSources.sourceObject(row.eventJson());if(row.seq()!=e.getLongValue("seq")||!java.util.Objects.equals(run.getId(),e.get("runId")))throw new ServiceException("变量输入源数据库序号不一致",403);if(java.util.Objects.equals(run.getAttempt(),e.getInteger("attempt")))events.add(e);}return events;}
    private void saveSource(com.ruoyi.paiflow.domain.RunRecord run,JSONObject node,String key,String operation,String scope,JSONObject request,JSONObject response){String raw=RunJsonBoundary.canonicalFull(response);if(variables.recordSnapshot(run.getId(),run.getLeaseId(),run.getAttempt(),node.getString("id"),key,operation,scope,RunJsonBoundary.hashFull(node),RunJsonBoundary.canonicalFull(request),raw,DataRuntimeCatalog.sha(raw))!=1)throw new ServiceException("变量来源回执保存失败",500);}
    private JSONObject sourceResponse(com.ruoyi.paiflow.domain.RunRecord run,JSONObject node,Map<String,Object> source){var context=histories.executionContext(run);if(context==null)throw new ServiceException("变量来源缺少中心会话域",403);String scope=RunJsonBoundary.hashFull(JSONObject.of("space",run.getSpaceId(),"workflow",run.getWorkflowId(),"owner",run.getOwnerUserId(),"app",context.get("appId"),"chat",context.get("chatId")));var request=ModelMessageSources.sourceObject((String)source.get("requestJson"));String key="set".equals(request.get("operation"))?ModelOperationJson.sha(request.getString("idempotencyKey")):DataRuntimeCatalog.sha(request.getString("operation")+":"+request.getOrDefault("variableName",""));if(!java.util.Objects.equals(scope,source.get("scopeKey"))||!java.util.Objects.equals(key,source.get("snapshotKey"))||!java.util.Objects.equals(run.getLeaseId(),request.get("leaseId"))||request.getIntValue("attempt")!=run.getAttempt()||!java.util.Objects.equals(node.get("id"),request.get("nodeId"))||!java.util.Objects.equals(request.get("operation"),source.get("operation")))throw new ServiceException("变量来源原请求范围无效",403);if(!java.util.Objects.equals(run.getId(),source.get("runId"))||!java.util.Objects.equals(run.getLeaseId(),source.get("leaseId"))||((Number)source.get("attempt")).intValue()!=run.getAttempt()||!java.util.Objects.equals(node.get("id"),source.get("nodeId"))||!java.util.Objects.equals(RunJsonBoundary.hashFull(node),source.get("nodeHash"))||!(source.get("responseJson") instanceof String raw)||!java.util.Objects.equals(DataRuntimeCatalog.sha(raw),source.get("responseHash")))throw new ServiceException("变量来源固定范围或回执哈希无效",403);return ModelMessageSources.sourceObject((String)source.get("responseJson"));}
    public JSONObject completed(com.ruoyi.paiflow.domain.RunRecord run,JSONObject node){return completed(run,node,WorkflowInputSources.values(ModelMessageSources.sourceObject(run.getAssignmentJson()).getJSONObject("dsl"),node,ModelMessageSources.sourceObject(run.getAssignmentJson()).getJSONObject("input"),accepted(run)));}
    JSONObject completed(com.ruoyi.paiflow.domain.RunRecord run,JSONObject node,Map<String,Object> local){var rows=variables.snapshots(run.getId(),run.getLeaseId(),run.getAttempt(),node.getString("id"));if(rows.size()>1024)throw new ServiceException("变量来源回执超过预算",503);String method=node.getJSONObject("data").getJSONObject("nodeParam").getString("method");var values=new JSONObject();if("get".equals(method)){var reads=rows.stream().filter(r->"get_all".equals(r.get("operation"))).toList();if(reads.size()!=1)throw new ServiceException("变量来源没有唯一当时读快照",403);values=sourceResponse(run,node,reads.getFirst()).getJSONObject("variables");if(values==null)throw new ServiceException("变量读快照结构无效",403);}else if("set".equals(method)){var seen=new HashSet<String>();for(var row:rows){sourceResponse(run,node,row);if(!"set".equals(row.get("operation")))continue;var body=ModelMessageSources.sourceObject((String)row.get("requestJson"));seen.add(body.getString("variableName"));values.put(body.getString("variableName"),body.get("value"));}for(Object raw:node.getJSONObject("data").getJSONArray("inputs"))if(!seen.contains(((JSONObject)raw).getString("name")))throw new ServiceException("变量set没有完整当时写回执",403);}else throw new ServiceException("变量来源固定方法无效",403);var outputs=new JSONObject();for(Object raw:node.getJSONObject("data").getJSONArray("outputs")){var field=(JSONObject)raw;String name=field.getString("name");outputs.put(name,values.containsKey(name)?values.get(name):local.containsKey(name)?local.get(name):CompletedModelOutputs.defaultValue(field));}return outputs;}

    public RunVariableService(RunVariableMapper variables,RunLeaseService leases,RunHistoryService histories){this.variables=variables;this.leases=leases;this.histories=histories;}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject operate(String id,WorkerIdentityService.Principal worker,JSONObject body) {
        String operation=body==null?null:body.getString("operation");boolean writing="set".equals(operation);
        Set<String> common=Set.of("leaseId","attempt","nodeId","operation");var fields=new HashSet<>(common);
        if(writing)fields.addAll(Set.of("variableName","value","expectedRevision","idempotencyKey"));
        else if("get".equals(operation))fields.add("variableName");
        else if(!"get_all".equals(operation))throw new ServiceException("原变量节点仅支持读/写；未授权清空",403);
        RunJsonBoundary.fields(body,fields,Set.of());RunJsonBoundary.check(body,RunProtocolLimits.RESULT_BYTES);
        var run=leases.requireActiveLease(id,worker,ModelOperationJson.text(body.get("leaseId"),64,"leaseId"),RunLeaseService.attempt(body));
        RunLeaseService.requireExecutionBegun(run);if(!"RUNNING".equals(run.getState()))throw new ServiceException("运行不能继续变量操作",410);
        var context=histories.executionContext(run);if(context==null)throw new ServiceException("中心变量作用域尚未绑定",503);
        String nodeId=ModelOperationJson.text(body.get("nodeId"),255,"nodeId");JSONObject node=null;
        var assignment=JSON.parseObject(run.getAssignmentJson());
        for(Object raw:assignment.getJSONObject("dsl").getJSONArray("nodes"))if(raw instanceof JSONObject n && nodeId.equals(n.get("id")))node=n;
        if(node==null || !nodeId.startsWith("node-variable::"))throw new ServiceException("变量调用不属于固定节点",403);
        var data=node.getJSONObject("data");var parameters=data==null?null:data.getJSONObject("nodeParam");
        if(parameters==null || !Set.of("set","get").contains(parameters.getString("method")))throw new ServiceException("固定变量节点方法无效",409);
        if(writing && !"set".equals(parameters.get("method")))throw new ServiceException("只读变量节点不能写入",403);
        String name=null;if(body.containsKey("variableName")) {
            name=ModelOperationJson.text(body.get("variableName"),128,"variableName");
            var configured=data.getJSONArray(writing?"inputs":"outputs");boolean declared=false;
            if(configured!=null)for(Object item:configured)if(item instanceof JSONObject p && name.equals(p.get("name")))declared=true;
            if(!declared)throw new ServiceException("变量名称不属于固定节点声明",403);
        }
        String scope=RunJsonBoundary.hashFull(JSONObject.of("space",run.getSpaceId(),"workflow",run.getWorkflowId(),"owner",run.getOwnerUserId(),"app",context.get("appId"),"chat",context.get("chatId")));
        if(!writing) {
            String readKey=DataRuntimeCatalog.sha(operation+":"+(name==null?"":name));
            if("get".equals(parameters.get("method"))){var previous=variables.snapshot(id,run.getLeaseId(),run.getAttempt(),nodeId,readKey);if(previous!=null)return sourceResponse(run,node,previous);}
            var state=variables.find(scope);long revision=state==null?0:revision(state.get("revision"));
            var values=state==null?new JSONObject():JSON.parseObject((String)state.get("valuesJson"));
            var result=JSONObject.of("accepted",true,"revision",revision);
            if("get_all".equals(operation))result.put("variables",values);
            else {result.put("found",values.containsKey(name));if(values.containsKey(name))result.put("value",values.get(name));}
            RunJsonBoundary.check(result,RunProtocolLimits.ASSIGNMENT_BYTES);
            if("get".equals(parameters.get("method")))saveSource(run,node,readKey,operation,scope,body,result);
            return result;
        }
        var effect=assignment.getJSONObject("effectPolicy");
        if(effect==null || !"approved_write".equals(effect.get("mode")) || !(effect.get("allowedOperations") instanceof JSONArray allowed) || !allowed.contains("variables.set"))
            throw new ServiceException("固定运行未批准变量写入",403);
        var events=accepted(run);var verifier=sourceProvider==null?null:sourceProvider.getIfAvailable();if(verifier==null)throw new ServiceException("变量写入来源校核未装配",503);var expectedInputs=verifier.values(run,assignment.getJSONObject("dsl"),node,events);if(!expectedInputs.containsKey(name)||!ToolSchema.same(expectedInputs.get(name),body.get("value")))throw new ServiceException("变量写入值偏离固定输入来源",409);
        String key=ModelOperationJson.sha(ModelOperationJson.text(body.get("idempotencyKey"),4096,"idempotencyKey")),hash=RunJsonBoundary.hashFull(body);
        var old=variables.request(id,run.getAttempt(),key);
        if(old!=null) {
            if(!scope.equals(old.get("scopeKey")) || !hash.equals(old.get("requestHash")))throw new ServiceException("变量幂等键已用于不同请求",409);
            var saved=variables.snapshot(id,run.getLeaseId(),run.getAttempt(),nodeId,key);if(saved==null)throw new ServiceException("历史变量写没有当时来源回执",403);return sourceResponse(run,node,saved);
        }
        long expected=revision(body.get("expectedRevision"));variables.initialize(scope);var state=variables.lock(scope);
        if(state==null || revision(state.get("revision"))!=expected)throw new ServiceException("变量版本已变化，请重新核对",409);
        var values=JSON.parseObject((String)state.get("valuesJson"));values.put(name,body.get("value"));
        if(values.size()>512)throw new ServiceException("会话变量超过512项",413);RunJsonBoundary.check(values,RunProtocolLimits.INPUT_BYTES);
        if(expected==Long.MAX_VALUE || variables.update(scope,expected,RunJsonBoundary.canonicalFull(values))!=1)throw new ServiceException("变量版本保存冲突",409);
        if(variables.record(id,run.getAttempt(),key,hash,scope,expected+1)!=1)throw new ServiceException("变量写入回执保存失败",500);
        var response=JSONObject.of("accepted",true,"revision",expected+1);saveSource(run,node,key,operation,scope,body,response);return response;
    }
    private static long revision(Object raw) {
        if(!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) || ((Number)raw).longValue()<0)
            throw new ServiceException("变量版本须为非负整数",400);return ((Number)raw).longValue();
    }
}
