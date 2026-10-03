package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.filter.WorkerRequestBoundaryFilter;
import com.ruoyi.paiflow.service.CheckpointService;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 只核对内部传输/过滤器/异常Advice，不启动HTTP服务或代替真实账号鉴权验收。 */
class WorkerCheckpointControllerTest {
    private static final String BASE="/internal/paiflow/v1/runs/00000000-0000-0000-0000-000000000001/checkpoints";
    @Test void invalidIdentityIsRejectedBeforeMalformedCheckpointJsonBinding() throws Exception {
        var identities=mock(WorkerIdentityService.class);var service=mock(CheckpointService.class);
        when(identities.authenticate("Bearer invalid")).thenThrow(new ServiceException("身份无效",401));
        var mvc=MockMvcBuilders.standaloneSetup(new WorkerCheckpointController(identities,service)).addFilters(new WorkerRequestBoundaryFilter(identities)).setControllerAdvice(new WorkerRunExceptionHandler()).build();
        mvc.perform(post(BASE).header("Authorization","Bearer invalid").contentType(MediaType.APPLICATION_JSON).content("{invalid"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));verifyNoInteractions(service);
    }
    @Test void checkpointLeaseConflictHasActualWorkerHttpStatusAndPlatformEnvelope() throws Exception {
        var identities=mock(WorkerIdentityService.class);var service=mock(CheckpointService.class);var worker=new WorkerIdentityService.Principal("worker-a","python");
        when(identities.authenticate("Bearer valid")).thenReturn(worker);when(service.consume(anyString(),anyString(),eq(worker),any(JSONObject.class))).thenThrow(new ServiceException("检查点版本冲突",409));
        var mvc=MockMvcBuilders.standaloneSetup(new WorkerCheckpointController(identities,service)).addFilters(new WorkerRequestBoundaryFilter(identities)).setControllerAdvice(new WorkerRunExceptionHandler()).build();
        mvc.perform(post(BASE+"/checkpoint-a/consume").header("Authorization","Bearer valid").contentType(MediaType.APPLICATION_JSON).content("{\"leaseId\":\"lease-a\",\"attempt\":1,\"expectedRevision\":1,\"idempotencyKey\":\"consume-fixture-0001\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }
}
