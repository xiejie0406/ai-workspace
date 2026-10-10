package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.config.PaiflowWorkerProperties;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.domain.SpaceMember;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.RunMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.service.ISysUserService;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunLeasePolicyTest {
    private final String id="00000000-0000-0000-0000-000000000001";
    private final WorkerIdentityService.Principal worker=new WorkerIdentityService.Principal("worker-a","python");
    private final Instant instant=Instant.parse("2026-10-03T00:00:00Z");
    private RunMapper mapper;private SpaceMapper spaces;private ISysUserService users;private SysPermissionService permissions;
    private RunLeaseService service;private RunRecord run;
    @BeforeEach void setup() {
        mapper=mock(RunMapper.class);spaces=mock(SpaceMapper.class);users=mock(ISysUserService.class);permissions=mock(SysPermissionService.class);
        service=new RunLeaseService(mapper,spaces,users,permissions,Clock.fixed(instant,ZoneOffset.UTC));
        run=new RunRecord();run.setId(id);run.setSpaceId(10L);run.setOwnerUserId(42L);run.setEngineProfile("python");run.setState("RUNNING");
        run.setWorkerId("worker-a");run.setLeaseId("lease-a");run.setAttempt(1);run.setRevision(0L);run.setLastEventSeq(0L);
        run.setDeadline(LocalDateTime.ofInstant(instant.plusSeconds(600),ZoneOffset.UTC));run.setLeaseExpiresAt(LocalDateTime.ofInstant(instant.plusSeconds(60),ZoneOffset.UTC));
        run.setExecutionStartedAt(LocalDateTime.ofInstant(instant,ZoneOffset.UTC));
        run.setAssignmentJson("{\"dslHash\":\"fixed\",\"workflowVersionId\":\"2\",\"dsl\":{\"nodes\":[{\"id\":\"node-start::a\",\"data\":{}}],\"edges\":[]}}");
        when(mapper.lock(id)).thenReturn(run);when(mapper.update(any())).thenReturn(1);when(mapper.insertEvent(anyString(),anyLong(),anyString())).thenReturn(1);
        var user=new SysUser();user.setUserId(42L);user.setStatus("0");user.setDelFlag("0");when(users.selectUserById(42L)).thenReturn(user);
        when(permissions.getMenuPermission(user)).thenReturn(Set.of("paiflow:run:create"));
        var space=new SpaceRecord();space.setDeleted(0);when(spaces.find(10L)).thenReturn(space);
        var member=new SpaceMember();member.setRole(3);member.setStatus("active");when(spaces.member(10L,42L)).thenReturn(member);
    }
    private JSONObject event(long seq) { return JSON.parseObject("{\"schemaVersion\":\"paiflow.events.v1\",\"runId\":\""+id+"\",\"leaseId\":\"lease-a\",\"attempt\":1,\"seq\":"+seq+",\"nodeId\":\"node-start::a\",\"eventType\":\"node.started\",\"timestamp\":\"2026-10-03T00:00:00Z\",\"payload\":{}}"); }
    @Test void workerIdentityRequiresExactCredentialAndUnexpiredEngineBinding() throws Exception {
        var properties=new PaiflowWorkerProperties();var credential=new PaiflowWorkerProperties.Credential();
        String token="worker-a."+"x".repeat(40);
        credential.setEngineProfile("python");credential.setExpiresAt(Instant.now().plusSeconds(60));
        credential.setTokenSha256(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        properties.getIdentities().put("worker-a",credential);var identities=new WorkerIdentityService(properties);
        assertEquals(worker,identities.authenticate("Bearer "+token));
        assertEquals(401,assertThrows(ServiceException.class,()->identities.authenticate("Bearer platform-user-token")).getCode());
        assertThrows(ServiceException.class,()->identities.authenticate("Bearer "+token.substring(0,token.length()-1)+"y"));
        credential.setExpiresAt(Instant.now().minusSeconds(1));assertThrows(ServiceException.class,()->identities.authenticate("Bearer "+token));
    }
    @Test void wrongWorkerExpiredLeaseAndRevokedMembershipCannotReadAssignment() {
        assertEquals(409,assertThrows(ServiceException.class,()->service.verify(id,new WorkerIdentityService.Principal("worker-b","python"),"lease-a",1)).getCode());
        run.setLeaseExpiresAt(LocalDateTime.ofInstant(instant.minusSeconds(1),ZoneOffset.UTC));
        assertEquals(410,assertThrows(ServiceException.class,()->service.verify(id,worker,"lease-a",1)).getCode());
        spaces.member(10L,42L).setStatus("removed");assertEquals(403,assertThrows(ServiceException.class,()->service.verify(id,worker,"lease-a",1)).getCode());
    }
    @Test void gapOrConflictingDuplicateCannotAdvanceSequence() {
        assertEquals(409,assertThrows(ServiceException.class,()->service.append(id,worker,event(2))).getCode());
        var first=event(1);assertTrue(service.append(id,worker,first).getBooleanValue("accepted"));
        when(mapper.event(id,1)).thenReturn(RunJsonBoundary.canonical(first));assertTrue(service.append(id,worker,first).getBooleanValue("accepted"));
        first.getJSONObject("payload").put("changed",true);assertThrows(ServiceException.class,()->service.append(id,worker,first));
        verify(mapper,times(1)).insertEvent(eq(id),eq(1L),anyString());assertEquals(1L,run.getLastEventSeq());
    }
    @Test void workerCannotCreateTerminalEventsOrInjectCredentialsAndUnknownNodes() {
        var terminal=event(1);terminal.put("eventType","run.completed");assertThrows(ServiceException.class,()->service.append(id,worker,terminal));
        var secret=event(1);secret.getJSONObject("payload").put("nested",JSONObject.of("api_key","private-fixture"));
        assertThrows(ServiceException.class,()->service.append(id,worker,secret));
        secret.getJSONObject("payload").put("nested",JSONObject.of("apiSecret","private-fixture"));
        assertThrows(ServiceException.class,()->service.append(id,worker,secret));
        var bad=event(1);bad.put("nodeId","node-start::other");assertThrows(ServiceException.class,()->service.append(id,worker,bad));
        verify(mapper,never()).insertEvent(anyString(),anyLong(),anyString());
    }
    @Test void terminalResultIsCasPersistedAndDuplicateReceiptDoesNotEmitAgain() {
        var result=JSON.parseObject("{\"leaseId\":\"lease-a\",\"attempt\":1,\"state\":\"COMPLETED\",\"output\":{\"answer\":\"真实算法结果\"}}");
        assertEquals("COMPLETED",service.result(id,worker,result).getString("state"));assertEquals("COMPLETED",run.getState());assertNotNull(run.getResultHash());
        assertTrue(service.result(id,worker,result).getBooleanValue("accepted"));
        result.put("output",JSONObject.of("changed",true));assertThrows(ServiceException.class,()->service.result(id,worker,result));
        verify(mapper,times(1)).insertEvent(eq(id),eq(1L),anyString());verify(mapper,times(1)).update(run);
    }
    @Test void heartbeatPersistsBoundedLeaseAndActualCancellationRequest() {
        run.setState("CANCEL_REQUESTED");var response=service.heartbeat(id,worker,"lease-a",1);
        assertTrue(response.getBooleanValue("valid"));assertTrue(response.getBooleanValue("cancelRequested"));
        assertEquals(instant.plusSeconds(60).toString(),response.getString("leaseExpiresAt"));verify(mapper).update(run);
    }
    @Test void originalLifecycleRetainsAcceptedInputAndOutputWithinSharedBudget() {
        String text="x".repeat(RunProtocolLimits.INPUT_BYTES-64);
        var input=JSONObject.of("question",text);RunJsonBoundary.check(input,RunProtocolLimits.INPUT_BYTES);
        var completed=event(1);completed.put("eventType","node.completed");
        completed.put("payload",JSONObject.of("inputs",input,"outputs",JSONObject.of("answer",text)));
        assertTrue(service.append(id,worker,completed).getBooleanValue("accepted"));
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);verify(mapper).insertEvent(eq(id),eq(1L),captured.capture());
        var persisted=JSON.parseObject(captured.getValue()).getJSONObject("payload");
        assertEquals(text,persisted.getJSONObject("inputs").getString("question"));assertEquals(text,persisted.getJSONObject("outputs").getString("answer"));
        var tooLarge=event(2);tooLarge.put("payload",JSONObject.of("text","x".repeat(RunProtocolLimits.EVENT_BYTES)));
        assertEquals(413,assertThrows(ServiceException.class,()->service.append(id,worker,tooLarge)).getCode());
        assertEquals(1L,run.getLastEventSeq());
    }
    @Test void optionalEventIdsMustFollowPublishedSchemaWithoutCoercion() {
        for(String field:Set.of("nodeId","iterationId")) {
            for(Object invalid:new Object[]{null,123,"",new JSONObject()}) {
                var value=event(1);value.put(field,invalid);
                assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,worker,value)).getCode());
            }
        }
        verify(mapper,never()).insertEvent(anyString(),anyLong(),anyString());
        // 原batch-0只符合非空字符串，不能为非迭代图授予批次；原断言已保全。
        var invalid=event(1);invalid.put("iterationId","batch-0");
        assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,worker,invalid)).getCode());
        assertEquals(0L,run.getLastEventSeq());assertEquals(0L,run.getRevision());
        verify(mapper,never()).insertEvent(anyString(),anyLong(),anyString());verify(mapper,never()).update(any());
        assertTrue(service.append(id,worker,event(1)).getBooleanValue("accepted"));
    }
    @Test void exactHistoricalIterationReceiptStaysIdempotentWithoutRewritingOrGrantingNewScope() {
        var old=event(1);old.put("iterationId","batch-0");run.setLastEventSeq(1L);run.setRevision(7L);
        when(mapper.event(id,1)).thenReturn(RunJsonCodec.canonical(run,old));
        assertTrue(service.append(id,worker,old).getBooleanValue("accepted"));
        assertEquals(1L,run.getLastEventSeq());assertEquals(7L,run.getRevision());
        var different=JSON.parseObject(old.toJSONString());different.getJSONObject("payload").put("changed",true);
        assertEquals(409,assertThrows(ServiceException.class,()->service.append(id,worker,different)).getCode());
        var next=JSON.parseObject(old.toJSONString());next.put("seq",2L);
        assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,worker,next)).getCode());
        verify(mapper,never()).insertEvent(anyString(),anyLong(),anyString());verify(mapper,never()).update(any());
    }
    @Test void historicalExactReplayStillRequiresCurrentWorkerAuthorityAndActiveLease() {
        var old=event(1);old.put("iterationId","batch-0");run.setLastEventSeq(1L);
        when(mapper.event(id,1)).thenReturn(RunJsonCodec.canonical(run,old));
        assertEquals(409,assertThrows(ServiceException.class,()->service.append(id,new WorkerIdentityService.Principal("worker-b","python"),old)).getCode());
        spaces.member(10L,42L).setStatus("removed");
        assertEquals(403,assertThrows(ServiceException.class,()->service.append(id,worker,old)).getCode());
        spaces.member(10L,42L).setStatus("active");run.setLeaseExpiresAt(LocalDateTime.ofInstant(instant.minusSeconds(1),ZoneOffset.UTC));
        assertEquals(410,assertThrows(ServiceException.class,()->service.append(id,worker,old)).getCode());
        verify(mapper,never()).event(anyString(),anyLong());verify(mapper,never()).insertEvent(anyString(),anyLong(),anyString());verify(mapper,never()).update(any());
    }
    @Test void newRawFrameMustMatchRunAndOuterNodeBeforeAnyWrite() {
        Object[] invalidFrames={null,"{}",new com.alibaba.fastjson2.JSONArray(),
            JSONObject.of("id","other-run","workflow_step",JSONObject.of("node",JSONObject.of("id","node-start::a"))),
            JSONObject.of("id",id,"workflow_step",JSONObject.of("node",JSONObject.of("id","node-start::other"))),
            JSONObject.of("id",id,"workflow_step",JSONObject.of("node",JSONObject.of("id",123))),
            JSONObject.of("id",id,"workflow_step",new JSONObject())};
        for(Object frame:invalidFrames) {
            var value=event(1);value.getJSONObject("payload").put("legacyFrame",frame);
            assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,worker,value)).getCode());
            assertEquals(0L,run.getLastEventSeq());assertEquals(0L,run.getRevision());
        }
        verify(mapper,never()).insertEvent(anyString(),anyLong(),anyString());verify(mapper,never()).update(any());
        var valid=event(1);valid.getJSONObject("payload").put("legacyFrame",JSONObject.of("id",id,"workflow_step",JSONObject.of("node",JSONObject.of("id","node-start::a"))));
        assertTrue(service.append(id,worker,valid).getBooleanValue("accepted"));assertEquals(1L,run.getLastEventSeq());
    }
    @Test void javaOriginalSessionFramePersistsWithoutChangingSourceAndStillRequiresBoundLease() {
        run.setEngineProfile("java");var javaWorker=new WorkerIdentityService.Principal("worker-a","java");
        var value=event(1);value.put("eventType","text.delta");
        var frame=JSONObject.of("id","workflow#原流程-1791168653315","workflow_step",JSONObject.of("node",JSONObject.of("id","node-start::a","outputs",JSONObject.of("answer","原结果"))));
        value.getJSONObject("payload").put("legacyFrame",frame);
        assertTrue(service.append(id,javaWorker,value).getBooleanValue("accepted"));
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);verify(mapper).insertEvent(eq(id),eq(1L),captured.capture());
        assertEquals(frame,JSON.parseObject(captured.getValue()).getJSONObject("payload").getJSONObject("legacyFrame"));
        when(mapper.event(id,1)).thenReturn(captured.getValue());assertTrue(service.append(id,javaWorker,value).getBooleanValue("accepted"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.append(id,new WorkerIdentityService.Principal("worker-b","java"),value)).getCode());
        var wrongLease=event(2);wrongLease.put("leaseId","other-lease");wrongLease.getJSONObject("payload").put("legacyFrame",frame);
        assertEquals(409,assertThrows(ServiceException.class,()->service.append(id,javaWorker,wrongLease)).getCode());
        verify(mapper,times(1)).insertEvent(anyString(),anyLong(),anyString());assertEquals(1L,run.getLastEventSeq());
    }
    @Test void javaOriginalSessionCannotAuthorizeInvalidFrameShapeNodeRunOrSequence() {
        run.setEngineProfile("java");var javaWorker=new WorkerIdentityService.Principal("worker-a","java");
        for(Object sid:new Object[]{null,123,""," ","x\n","x".repeat(513)}){
            var value=event(1);value.getJSONObject("payload").put("legacyFrame",JSONObject.of("id",sid,"workflow_step",JSONObject.of("node",JSONObject.of("id","node-start::a"))));
            assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,javaWorker,value)).getCode());
        }
        var frame=JSONObject.of("id","workflow#原流程","workflow_step",JSONObject.of("node",JSONObject.of("id","node-start::other")));
        var wrongNode=event(1);wrongNode.getJSONObject("payload").put("legacyFrame",frame);assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,javaWorker,wrongNode)).getCode());
        var otherRun=event(1);otherRun.put("runId","other-run");assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,javaWorker,otherRun)).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->service.append(id,javaWorker,event(2))).getCode());
        verify(mapper,never()).insertEvent(anyString(),anyLong(),anyString());verify(mapper,never()).update(any());assertEquals(0L,run.getLastEventSeq());
    }
    @Test void runWideAuxiliaryWithoutNodeOrFrameRemainsValidButTagAloneCannotAcquireScope() {
        var value=event(1);value.remove("nodeId");value.put("eventType","usage");
        assertTrue(service.append(id,worker,value).getBooleanValue("accepted"));
        var tagged=event(2);tagged.remove("nodeId");tagged.put("eventType","usage");tagged.put("iterationId","iteration::i:0");
        assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,worker,tagged)).getCode());
        assertEquals(1L,run.getLastEventSeq());assertEquals(1L,run.getRevision());
        verify(mapper,times(1)).insertEvent(eq(id),eq(1L),anyString());verify(mapper,times(1)).update(run);
    }
    @Test void newIterationEventUsesFixedOwnerAndBatchWithoutAdvancingOnInvalidNextSequence() throws Exception {
        var sample=JSON.parseObject(java.nio.file.Files.readString(java.nio.file.Path.of("../../contracts/paiflow/fixtures/pure-iteration.json"))).getJSONObject("baseline");
        var task=JSON.parseObject(run.getAssignmentJson());task.put("dsl",sample.getJSONObject("dsl"));task.put("input",sample.getJSONObject("input"));run.setAssignmentJson(task.toJSONString());
        var valid=event(1);valid.put("nodeId","text-joiner::j");valid.put("iterationId","iteration::i:2");
        assertTrue(service.append(id,worker,valid).getBooleanValue("accepted"));
        for(String tag:new String[]{"iteration::i:3","iteration::other:0","iteration::i:00"}) {
            var invalid=event(2);invalid.put("nodeId","text-joiner::j");invalid.put("iterationId",tag);
            assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,worker,invalid)).getCode());
        }
        var missing=event(2);missing.put("nodeId","iteration-node-end::ie");
        assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,worker,missing)).getCode());
        var outer=event(2);outer.put("nodeId","iteration::i");outer.put("iterationId","iteration::i:0");
        assertEquals(400,assertThrows(ServiceException.class,()->service.append(id,worker,outer)).getCode());
        assertEquals(1L,run.getLastEventSeq());assertEquals(1L,run.getRevision());
        verify(mapper,times(1)).insertEvent(eq(id),eq(1L),anyString());verify(mapper,times(1)).update(run);
    }
}
