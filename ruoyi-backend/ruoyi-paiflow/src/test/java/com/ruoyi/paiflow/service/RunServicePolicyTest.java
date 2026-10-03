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
}
