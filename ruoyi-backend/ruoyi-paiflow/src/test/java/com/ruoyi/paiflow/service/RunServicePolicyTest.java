package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.RunMapper;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunServicePolicyTest {
    private WorkflowService workflows;private RunMapper mapper;private RunService service;private JSONObject graph;private ResourceAccessService access;
    @BeforeEach void setup() {
        workflows=mock(WorkflowService.class);mapper=mock(RunMapper.class);access=mock(ResourceAccessService.class);var identity=mock(IdentityMappingService.class);
        when(access.requirePermission("paiflow:run:create")).thenReturn(42L);var user=new SysUser();user.setUserId(42L);user.setUserName("fixture-42");
        when(identity.current()).thenReturn(new LoginUser(42L,8L,user,Set.of()));when(mapper.insert(any())).thenReturn(1);
        service=new RunService(workflows,mapper,access,identity);
        graph=JSON.parseObject("{\"nodes\":[{\"id\":\"node-start::a\",\"data\":{}},{\"id\":\"node-end::b\",\"data\":{}}],\"edges\":[{\"sourceNodeId\":\"node-start::a\",\"targetNodeId\":\"node-end::b\"}]}");
        snapshot(graph);
    }
    private void snapshot(JSONObject value) {
        var normalized=new DslCodec().normalize(value);
        when(workflows.runtimeSnapshot(2)).thenReturn(new WorkflowService.RuntimeSnapshot(1,2,10,43,"fixed-flow","python",normalized.executionHash(),
            DslCodec.canonicalDslBase64(normalized.execution()),normalized.execution(),normalized.original(),new JSONObject(),new JSONObject()));
    }
    @Test void runUsesActualActorAndPinnedVersionWhileInputCannotReplaceDsl() {
        var input=JSONObject.of("dsl",JSONObject.of("malicious","untrusted"));var result=service.create(2,"python",input,"fixture-key-0001",600);
        var captor=org.mockito.ArgumentCaptor.forClass(RunRecord.class);verify(mapper).insert(captor.capture());var run=captor.getValue();
        assertEquals(42L,run.getOwnerUserId());assertEquals(2L,run.getWorkflowVersionId());assertEquals("QUEUED",result.state());
        var task=JSON.parseObject(run.getAssignmentJson());assertEquals(graph,task.getJSONObject("dsl"));assertEquals(input,task.getJSONObject("input"));
        assertEquals("read_only",task.getJSONObject("effectPolicy").getString("mode"));assertTrue(task.getJSONObject("resourceSnapshots").isEmpty());
    }
    @Test void sameIdempotencyKeyAndBodyReturnsOriginalRunAndDifferentBodyIsRejected() {
        var input=JSONObject.of("question","first");service.create(2,"python",input,"fixture-key-0002",600);
        var captor=org.mockito.ArgumentCaptor.forClass(RunRecord.class);verify(mapper).insert(captor.capture());var stored=captor.getValue();
        when(mapper.byRequest(42,"fixture-key-0002")).thenReturn(stored);
        assertEquals(stored.getId(),service.create(2,"python",input,"fixture-key-0002",600).runId());
        assertEquals(409,assertThrows(ServiceException.class,()->service.create(2,"python",JSONObject.of("question","changed"),"fixture-key-0002",600)).getCode());
        verify(mapper,times(1)).insert(any());
    }
    @Test void unresolvedResourceNodeAndVersionEngineMismatchNeverCreateTask() {
        assertEquals(409,assertThrows(ServiceException.class,()->service.create(2,"java",new JSONObject(),"fixture-key-0003",600)).getCode());
        graph.getJSONArray("nodes").add(JSONObject.of("id","plugin::tool","data",new JSONObject()));graph.getJSONArray("edges").clear();
        graph.getJSONArray("edges").add(JSONObject.of("sourceNodeId","node-start::a","targetNodeId","plugin::tool"));
        graph.getJSONArray("edges").add(JSONObject.of("sourceNodeId","plugin::tool","targetNodeId","node-end::b"));snapshot(graph);
        assertEquals(503,assertThrows(ServiceException.class,()->service.create(2,"python",new JSONObject(),"fixture-key-0004",600)).getCode());verify(mapper,never()).insert(any());
    }
    @Test void replaySurvivesDeletedWorkflowButCannotBypassRevokedSpaceAccess() {
        var input=JSONObject.of("question","first");service.create(2,"python",input,"fixture-key-0005",600);
        var captor=org.mockito.ArgumentCaptor.forClass(RunRecord.class);verify(mapper).insert(captor.capture());var stored=captor.getValue();
        when(mapper.byRequest(42,"fixture-key-0005")).thenReturn(stored);
        when(workflows.runtimeSnapshot(2)).thenThrow(new ServiceException("工作流已删除",404));
        assertEquals(stored.getId(),service.create(2,"python",input,"fixture-key-0005",600).runId());
        verify(workflows,times(1)).runtimeSnapshot(2);verify(access).requireSpace(10,"paiflow:run:create",3,false);
        when(access.requireSpace(10,"paiflow:run:create",3,false)).thenThrow(new ServiceException("成员已撤销",404));
        assertEquals(404,assertThrows(ServiceException.class,()->service.create(2,"python",input,"fixture-key-0005",600)).getCode());
        verify(mapper,times(1)).insert(any());
    }
    private DataOperationService dataSnapshot(Set<String> operations) {
        graph.getJSONArray("nodes").add(JSONObject.of("id","database::rows","data",JSONObject.of("nodeParam",JSONObject.of("mode",3,"dataResourceId","data:9:revision:1"))));
        graph.getJSONArray("edges").clear();graph.getJSONArray("edges").add(JSONObject.of("sourceNodeId","node-start::a","targetNodeId","database::rows"));graph.getJSONArray("edges").add(JSONObject.of("sourceNodeId","database::rows","targetNodeId","node-end::b"));
        var normalized=new DslCodec().normalize(graph);var resources=JSONObject.of("data:9:revision:1",JSONObject.of("kind","database","version","1"));
        when(workflows.runtimeSnapshot(2)).thenReturn(new WorkflowService.RuntimeSnapshot(1,2,10,43,"fixed-flow","python",normalized.executionHash(),DslCodec.canonicalDslBase64(normalized.execution()),normalized.execution(),normalized.original(),new JSONObject(),new JSONObject(),resources));
        var data=mock(DataOperationService.class);var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(data);service.useDataOperations(provider);
        when(data.admit(eq(10L),eq(42L),any(),any(),anyInt())).thenReturn(operations);return data;
    }
    @Test void debugDataEnvironmentIsFixedAndCannotBeSelectedByInput() {
        var data=dataSnapshot(Set.of("data.query"));var input=JSONObject.of("dataEnvironment",2,"execDev",2);
        service.createDebug(2,"python",input,"data-debug-key",600);
        var captor=org.mockito.ArgumentCaptor.forClass(RunRecord.class);verify(mapper).insert(captor.capture());var stored=captor.getValue();var task=JSON.parseObject(stored.getAssignmentJson());
        assertEquals(1,task.getIntValue("dataEnvironment"));assertEquals(input,task.getJSONObject("input"));assertEquals("read_only",task.getJSONObject("effectPolicy").getString("mode"));verify(data).admit(eq(10L),eq(42L),any(),any(),eq(1));
        when(mapper.byRequest(42,"data-debug-key")).thenReturn(stored);assertEquals(stored.getId(),service.createDebug(2,"python",input,"data-debug-key",600).runId());
        assertEquals(409,assertThrows(ServiceException.class,()->service.create(2,"python",input,"data-debug-key",600)).getCode());verify(mapper,times(1)).insert(any());
    }
    @Test void formalMutationUsesProductionEnvironmentAndCenterEffectPolicy() {
        var data=dataSnapshot(Set.of("data.mutate"));service.create(2,"python",JSONObject.of("execDev",1),"data-formal-key",600);
        var captor=org.mockito.ArgumentCaptor.forClass(RunRecord.class);verify(mapper).insert(captor.capture());var task=JSON.parseObject(captor.getValue().getAssignmentJson());
        assertEquals(2,task.getIntValue("dataEnvironment"));assertEquals("approved_write",task.getJSONObject("effectPolicy").getString("mode"));assertTrue(task.getJSONObject("effectPolicy").getJSONArray("allowedOperations").contains("data.mutate"));verify(data).admit(eq(10L),eq(42L),any(),any(),eq(2));
    }
    @Test void dataPermissionOrMissingPortRejectsBeforeEnqueue() {
        var data=dataSnapshot(Set.of("data.query"));when(data.admit(eq(10L),eq(42L),any(),any(),anyInt())).thenThrow(new ServiceException("数据权限已撤销",403));
        assertEquals(403,assertThrows(ServiceException.class,()->service.create(2,"python",new JSONObject(),"data-denied-key",600)).getCode());
        service.useDataOperations(null);assertEquals(503,assertThrows(ServiceException.class,()->service.create(2,"python",new JSONObject(),"data-off-key",600)).getCode());verify(mapper,never()).insert(any());
    }
    private ModelOperationService v4Snapshot(boolean question) {
        var data=JSONObject.of("inputs",new com.alibaba.fastjson2.JSONArray(),"outputs",new com.alibaba.fastjson2.JSONArray(),"nodeParam",JSONObject.of("modelResourceId","model:7:version:2","source","xinghuo","domain","generalv3.5"));
        if(question)data.getJSONObject("nodeParam").putAll(JSONObject.of("answerType","direct","directAnswer",JSONObject.of("handleResponse",true,"maxRetryCounts",3)));
        String id=question?"question-answer::q":"decision-making::d";
        for(Object raw:graph.getJSONArray("nodes")){var node=(JSONObject)raw;node.put("data",JSONObject.of("inputs",new com.alibaba.fastjson2.JSONArray(),"outputs",new com.alibaba.fastjson2.JSONArray(),"nodeParam",new JSONObject()));}
        graph.getJSONArray("nodes").add(JSONObject.of("id",id,"data",data));graph.getJSONArray("edges").clear();
        graph.getJSONArray("edges").add(JSONObject.of("sourceNodeId","node-start::a","targetNodeId",id));graph.getJSONArray("edges").add(JSONObject.of("sourceNodeId",id,"targetNodeId","node-end::b"));
        if(!question){graph.getJSONArray("nodes").add(JSONObject.of("id","node-end::second","data",JSONObject.of("inputs",new com.alibaba.fastjson2.JSONArray(),"outputs",new com.alibaba.fastjson2.JSONArray(),"nodeParam",new JSONObject())));graph.getJSONArray("edges").add(JSONObject.of("sourceNodeId",id,"targetNodeId","node-end::second"));}
        var n=new DslCodec().normalize(graph,DslCodec.V4);var resources=JSONObject.of("model:7:version:2",JSONObject.of("kind","model","provider","xinghuo","version","2","modelName","generalv3.5","enabled",true,"capabilities",com.alibaba.fastjson2.JSONArray.of("chat","function_call")));
        when(workflows.runtimeSnapshot(2)).thenReturn(new WorkflowService.RuntimeSnapshot(1,2,10,43,"fixed-flow","python",n.executionHash(),DslCodec.canonicalDslBase64(n.execution(),DslCodec.V4),n.execution(),n.original(),new JSONObject(),new JSONObject(),resources,DslCodec.V4));
        var model=mock(ModelOperationService.class);var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(model);service.useModelOperations(provider);
        when(model.admit(anyString(),eq(42L),eq(10L),eq(2L),anyString(),any(),any(),any(),any(),eq("python"))).thenReturn(JSONObject.of("approvalRef","isolated-grant","expiresAt",java.time.Instant.now().plusSeconds(300).toString()));
        return model;
    }
    @Test void v4DecisionRetainsMultipleEndsAndMarkerCannotBeOverriddenByInput() {
        var model=v4Snapshot(false);var input=JSONObject.of("executionCodec",DslCodec.V3);service.create(2,"python",input,"v4-decision-key",600);
        var captor=org.mockito.ArgumentCaptor.forClass(RunRecord.class);verify(mapper).insert(captor.capture());var task=JSON.parseObject(captor.getValue().getAssignmentJson());
        assertEquals(DslCodec.V4,task.get("executionCodec"));assertEquals(input,task.get("input"));assertEquals(2,task.getJSONObject("dsl").getJSONArray("nodes").stream().filter(raw->((JSONObject)raw).getString("id").startsWith("node-end::")).count());
        assertEquals("approved_write",task.getJSONObject("effectPolicy").get("mode"));verify(model).admit(anyString(),eq(42L),eq(10L),eq(2L),anyString(),eq(input),any(),any(),any(),eq("python"));
    }
    @Test void v4DynamicQuestionRequiresBothCheckpointAndModelPortsBeforeSavingRun() {
        var model=v4Snapshot(true);assertEquals(503,assertThrows(ServiceException.class,()->service.create(2,"python",new JSONObject(),"v4-question-key",600)).getCode());verifyNoInteractions(model);
        var checkpointProvider=mock(org.springframework.beans.factory.ObjectProvider.class);when(checkpointProvider.getIfAvailable()).thenReturn(mock(CheckpointService.class));service.useCheckpoints(checkpointProvider);
        service.useModelOperations(null);assertEquals(503,assertThrows(ServiceException.class,()->service.create(2,"python",new JSONObject(),"v4-question-key",600)).getCode());verify(mapper,never()).insert(any());
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(model);service.useModelOperations(provider);service.create(2,"python",new JSONObject(),"v4-question-key",600);verify(mapper).insert(any());
    }
}
