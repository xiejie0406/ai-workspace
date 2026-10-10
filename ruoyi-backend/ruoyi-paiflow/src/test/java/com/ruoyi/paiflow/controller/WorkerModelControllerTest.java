package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.ModelOperationService;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import com.ruoyi.paiflow.service.DataWorkerOperationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 实际MVC/若依信封和服务认证；ModelOperation事务行为在独立真实Mapper夹具核对。 */
class WorkerModelControllerTest {
    private WorkerIdentityService identities;private ModelOperationService models;private MockMvc mvc;
    private final WorkerIdentityService.Principal principal=new WorkerIdentityService.Principal("worker-a","python");
    private final String run="00000000-0000-0000-0000-000000000001",base="/internal/paiflow/v1/runs/"+run;
    @BeforeEach void setup(){identities=mock(WorkerIdentityService.class);models=mock(ModelOperationService.class);when(identities.authenticate("Bearer fixture")).thenReturn(principal);mvc=MockMvcBuilders.standaloneSetup(new WorkerModelController(identities,models)).setControllerAdvice(new WorkerRunExceptionHandler()).build();}
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String route,String json){return post(base+route).header("Authorization","Bearer fixture").contentType("application/json").content(json);}
    @Test void rejectedIdentityCannotReachAnyModelEndpoint() throws Exception {
        when(identities.authenticate("Bearer fixture")).thenThrow(new ServiceException("Worker服务身份无效",401));
        for(String route:new String[]{"/resources/model:1:version:2","/operations","/operations/op/frames","/operations/op/cancel"})mvc.perform(request(route,"{\"apiKey\":\"untrusted\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(models);
    }
    @Test void jacksonMapBindingPreservesExplicitNullAndFloatOptions() throws Exception {
        when(models.start(eq(run),eq(principal),any())).thenReturn(JSONObject.of("operationId","op","state","RUNNING","iterationId",null));
        var result=mvc.perform(request("/operations","{\"leaseId\":\"lease-a\",\"attempt\":1,\"iterationId\":null,\"parameters\":{\"options\":{\"temperature\":1.0}}}")).andExpect(status().isOk()).andReturn();
        var captor=org.mockito.ArgumentCaptor.forClass(JSONObject.class);verify(models).start(eq(run),eq(principal),captor.capture());assertTrue(captor.getValue().containsKey("iterationId"));assertNull(captor.getValue().get("iterationId"));assertEquals("1.0",captor.getValue().getJSONObject("parameters").getJSONObject("options").get("temperature").toString());
        var data=JSON.parseObject(result.getResponse().getContentAsString()).getJSONObject("data");assertTrue(data.containsKey("iterationId"));assertNull(data.get("iterationId"));
    }
    @Test void leaseConflictAndMissingProducerAreActualHttpRejections() throws Exception {
        when(models.start(eq(run),eq(principal),any())).thenThrow(new ServiceException("重复请求不同",409));mvc.perform(request("/operations","{}")).andExpect(status().isConflict());
        when(models.resource(eq(run),eq(principal),eq("model:1:version:2"),any())).thenThrow(new ServiceException("中心适配未装配",503));mvc.perform(request("/resources/model:1:version:2","{}")).andExpect(status().isServiceUnavailable());
    }
    @Test void fourRoutesKeepResourceAndOperationBindingsInsideData() throws Exception {
        when(models.resource(eq(run),eq(principal),eq("model:1:version:2"),any())).thenReturn(JSONObject.of("kind","model","version","2"));
        when(models.frames(eq(run),eq(principal),eq("operation-a"),any())).thenReturn(JSONObject.of("operationId","operation-a","frames",new com.alibaba.fastjson2.JSONArray(),"finishSeq",null,"failureKind",null));
        when(models.cancel(eq(run),eq(principal),eq("operation-a"),any())).thenReturn(JSONObject.of("operationId","operation-a","stopped",false,"proofId",null));
        mvc.perform(request("/resources/model:1:version:2","{\"leaseId\":\"a\",\"attempt\":1}")).andExpect(status().isOk());
        var frames=mvc.perform(request("/operations/operation-a/frames","{\"leaseId\":\"a\",\"attempt\":1,\"afterCursor\":0,\"limit\":64}")).andExpect(status().isOk()).andReturn();
        var data=JSON.parseObject(frames.getResponse().getContentAsString()).getJSONObject("data");assertTrue(data.containsKey("finishSeq"));assertTrue(data.containsKey("failureKind"));assertNull(data.get("finishSeq"));
        var stopped=mvc.perform(request("/operations/operation-a/cancel","{\"leaseId\":\"a\",\"attempt\":1}")).andExpect(status().isOk()).andReturn();assertFalse(JSON.parseObject(stopped.getResponse().getContentAsString()).getJSONObject("data").getBooleanValue("stopped"));
    }
    @Test void privateMaterialInWorkerBodyIsRejectedBeforeOperationService() throws Exception {
        mvc.perform(request("/operations","{\"parameters\":{\"apiKey\":\"untrusted\"}}")).andExpect(status().isBadRequest());verifyNoInteractions(models);
    }
    @Test void dataDisabledRejectsInsteadOfFallingBackToModel() throws Exception {
        mvc.perform(request("/resources/data:1:revision:2","{\"leaseId\":\"a\",\"attempt\":1}")).andExpect(status().isServiceUnavailable());
        mvc.perform(request("/operations","{\"parameters\":{\"kind\":\"database\",\"resourceId\":\"data:1:revision:2\"}}")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(models);
    }
    @Test void dataUsesSameIdentityAndPreservesConsumerCompletedReceipt() throws Exception {
        var data=mock(DataWorkerOperationService.class);
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(data);
        var controller=new WorkerModelController(identities,models);controller.useDataOperations(provider);
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WorkerRunExceptionHandler()).build();
        when(data.resource(eq(run),eq(principal),eq("data:1:revision:2"),any())).thenReturn(JSONObject.of("kind","database","provider","center-data","version","2"));
        when(data.start(eq(run),eq(principal),any())).thenReturn(JSONObject.of("kind","database","state","COMPLETED","failureKind",null,"legacyResponse",JSONObject.of("code",0)));
        mvc.perform(request("/resources/data:1:revision:2","{\"leaseId\":\"a\",\"attempt\":1}")).andExpect(status().isOk());
        var response=mvc.perform(request("/operations","{\"leaseId\":\"a\",\"attempt\":1,\"parameters\":{\"kind\":\"database\",\"resourceId\":\"data:1:revision:2\",\"legacyPayload\":{\"execDev\":1,\"limit\":null}}}")).andExpect(status().isOk()).andReturn();
        var captor=org.mockito.ArgumentCaptor.forClass(JSONObject.class);verify(data).start(eq(run),eq(principal),captor.capture());
        var payload=captor.getValue().getJSONObject("parameters").getJSONObject("legacyPayload");assertEquals(1,payload.getIntValue("execDev"));assertTrue(payload.containsKey("limit"));
        var receipt=JSON.parseObject(response.getResponse().getContentAsString()).getJSONObject("data");assertEquals("COMPLETED",receipt.getString("state"));assertTrue(receipt.containsKey("failureKind"));
        verifyNoInteractions(models);verify(identities,times(2)).authenticate("Bearer fixture");
        when(identities.authenticate("Bearer fixture")).thenThrow(new ServiceException("身份失效",401));
        clearInvocations(data);mvc.perform(request("/resources/data:1:revision:2","{}")).andExpect(status().isUnauthorized());mvc.perform(request("/operations","{\"parameters\":{\"kind\":\"database\"}}")).andExpect(status().isUnauthorized());verifyNoInteractions(data);
    }
}
