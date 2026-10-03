package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.RunLeaseService;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 不启动HTTP服务；实际MVC绑定和内部响应，独立验证服务认证与协议。 */
class WorkerRunControllerTest {
    private WorkerIdentityService identities;private RunLeaseService runs;private MockMvc mvc;
    private final String runId="00000000-0000-0000-0000-000000000001";
    private final WorkerIdentityService.Principal principal=new WorkerIdentityService.Principal("worker-a","python");
    @BeforeEach void setup() {
        identities=mock(WorkerIdentityService.class);runs=mock(RunLeaseService.class);
        mvc=MockMvcBuilders.standaloneSetup(new WorkerRunController(identities,runs)).setControllerAdvice(new WorkerRunExceptionHandler()).build();
    }
    @Test void invalidServiceIdentityIsActualHttp401BeforeLeaseOrBodyProcessing() throws Exception {
        when(identities.authenticate("Bearer user-token")).thenThrow(new ServiceException("Worker服务身份无效",401));
        mvc.perform(post("/internal/paiflow/v1/runs/"+runId+"/lease/verify").header("Authorization","Bearer user-token")
            .contentType("application/json").content("{\"leaseId\":\"lease-a\",\"attempt\":1}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(runs);
    }
    @Test void unknownLeaseFieldsAreRejectedAs400AndNeverReachRunService() throws Exception {
        when(identities.authenticate("Bearer worker-token")).thenReturn(principal);
        mvc.perform(post("/internal/paiflow/v1/runs/"+runId+"/heartbeat").header("Authorization","Bearer worker-token")
            .contentType("application/json").content("{\"leaseId\":\"lease-a\",\"attempt\":1,\"dsl\":{}}")).andExpect(status().isBadRequest());
        verifyNoInteractions(runs);
    }
    @Test void verifyJsonPreservesStrictAssignmentInItsOwnField() throws Exception {
        when(identities.authenticate("Bearer worker-token")).thenReturn(principal);
        when(runs.verify(runId,principal,"lease-a",1)).thenReturn(JSONObject.of("valid",true,"assignment",JSONObject.of("schemaVersion","paiflow.worker.v1")));
        var result=mvc.perform(post("/internal/paiflow/v1/runs/"+runId+"/lease/verify").header("Authorization","Bearer worker-token")
            .contentType("application/json").content("{\"leaseId\":\"lease-a\",\"attempt\":1}")).andExpect(status().isOk()).andReturn();
        var envelope=com.alibaba.fastjson2.JSON.parseObject(result.getResponse().getContentAsString());assertEquals(200,envelope.getIntValue("code"));
        assertTrue(envelope.getJSONObject("data").getBooleanValue("valid"));assertEquals("paiflow.worker.v1",envelope.getJSONObject("data").getJSONObject("assignment").getString("schemaVersion"));
    }
    @Test void emptyQueueReturnsExplicitNullDataForStrictClients() throws Exception {
        when(identities.authenticate("Bearer worker-token")).thenReturn(principal);
        var result=mvc.perform(post("/internal/paiflow/v1/runs/claim").header("Authorization","Bearer worker-token")).andExpect(status().isOk()).andReturn();
        var envelope=com.alibaba.fastjson2.JSON.parseObject(result.getResponse().getContentAsString());assertTrue(envelope.containsKey("data"));assertNull(envelope.get("data"));
    }
    @Test void beginExecutionPreservesBoundSnapshotAndDuplicateIsActual409() throws Exception {
        when(identities.authenticate("Bearer worker-token")).thenReturn(principal);
        var permit=JSONObject.of("begun",true,"runId",runId,"leaseId","lease-a","attempt",1,"workflowVersionId","2","dslHash","a".repeat(64));
        when(runs.beginExecution(runId,principal,"lease-a",1)).thenReturn(permit).thenThrow(new ServiceException("运行执行许可已经签发",409));
        var first=mvc.perform(post("/internal/paiflow/v1/runs/"+runId+"/execution/begin").header("Authorization","Bearer worker-token")
            .contentType("application/json").content("{\"leaseId\":\"lease-a\",\"attempt\":1}")).andExpect(status().isOk()).andReturn();
        assertEquals(permit,com.alibaba.fastjson2.JSON.parseObject(first.getResponse().getContentAsString()).getJSONObject("data"));
        mvc.perform(post("/internal/paiflow/v1/runs/"+runId+"/execution/begin").header("Authorization","Bearer worker-token")
            .contentType("application/json").content("{\"leaseId\":\"lease-a\",\"attempt\":1}")).andExpect(status().isConflict());
    }
    @Test void beginCannotAcceptCallerSnapshotOrClaimDataAsPermission() throws Exception {
        when(identities.authenticate("Bearer worker-token")).thenReturn(principal);
        mvc.perform(post("/internal/paiflow/v1/runs/"+runId+"/execution/begin").header("Authorization","Bearer worker-token")
            .contentType("application/json").content("{\"leaseId\":\"lease-a\",\"attempt\":1,\"begun\":true}")).andExpect(status().isBadRequest());
        verifyNoInteractions(runs);
    }
}
