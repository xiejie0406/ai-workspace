package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.service.RunLeaseService;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实际Jackson3/MVC/Controller的传输形状检查；身份和RunLease明确mock。
 * capture只证明入站JSON保全，不证明Worker授权、租约、持久化或业务通过。
 */
class WorkerRunControllerJsonBindingTest {
    private WorkerIdentityService identities;
    private RunLeaseService runs;
    private MockMvc mvc;
    private final WorkerIdentityService.Principal principal=new WorkerIdentityService.Principal("worker-fixture","python");
    private final String runId="00000000-0000-0000-0000-000000000001";
    private final String base="/internal/paiflow/v1/runs/"+runId;

    @BeforeEach void setup() {
        identities=mock(WorkerIdentityService.class);
        runs=mock(RunLeaseService.class);
        when(identities.authenticate("Bearer fixture")).thenReturn(principal);
        var jackson=new JacksonJsonHttpMessageConverter(JsonMapper.builder().findAndAddModules().build());
        mvc=MockMvcBuilders.standaloneSetup(new WorkerRunController(identities,runs))
            .setMessageConverters(jackson).setControllerAdvice(new WorkerRunExceptionHandler()).build();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String route,String body) {
        return post(base+route).header("Authorization","Bearer fixture").contentType("application/json").content(body);
    }

    private static void assertNullAndScalars(JSONObject object) {
        assertNotNull(object);
        assertTrue(object.containsKey("value"),"显式null不得变成缺失字段");
        assertNull(object.get("value"));
        assertInstanceOf(Number.class,object.get("zero"));
        assertEquals(0,object.getIntValue("zero"));
        assertEquals(Boolean.FALSE,object.get("flag"));
    }

    @Test void eventLegacyFrameKeepsNullableOutputMetadataAndFinishFields() throws Exception {
        when(runs.append(eq(runId),eq(principal),any(JSONObject.class))).thenReturn(JSONObject.of("accepted",true,"seq",1));
        String body="""
            {"schemaVersion":"paiflow.events.v1","runId":"00000000-0000-0000-0000-000000000001",
             "leaseId":"lease-fixture","attempt":1,"seq":1,"eventType":"node.started",
             "timestamp":"2026-10-03T00:00:00Z","nodeId":"text-joiner::fixture",
             "payload":{"metadata":{"nested":{"value":null,"zero":0,"flag":false}},
               "legacyFrame":{"id":"00000000-0000-0000-0000-000000000001","code":0,"message":"",
                 "workflow_step":{"seq":0,"progress":0,"node":{"id":"text-joiner::fixture","finish_reason":null,
                   "outputs":{"value":null,"zero":0,"flag":false,"nested":{"value":null,"zero":0,"flag":false},
                     "items":[null,{"value":null,"zero":0,"flag":false}]}}},
                 "choices":[{"index":0,"delta":{"role":"assistant","content":null},"finish_reason":null}]}}}
            """;
        mvc.perform(request("/events",body)).andExpect(status().isOk());
        var capture=ArgumentCaptor.forClass(JSONObject.class);
        verify(runs).append(eq(runId),eq(principal),capture.capture());
        var payload=capture.getValue().getJSONObject("payload");
        assertNullAndScalars(payload.getJSONObject("metadata").getJSONObject("nested"));
        var frame=payload.getJSONObject("legacyFrame");
        var node=frame.getJSONObject("workflow_step").getJSONObject("node");
        var outputs=node.getJSONObject("outputs");
        assertNullAndScalars(outputs);
        assertNullAndScalars(outputs.getJSONObject("nested"));
        assertEquals(2,outputs.getJSONArray("items").size());
        assertNull(outputs.getJSONArray("items").get(0));
        assertNullAndScalars(outputs.getJSONArray("items").getJSONObject(1));
        assertTrue(node.containsKey("finish_reason"));
        assertNull(node.get("finish_reason"));
        var choice=frame.getJSONArray("choices").getJSONObject(0);
        assertTrue(choice.containsKey("finish_reason"));
        assertNull(choice.get("finish_reason"));
        var delta=choice.getJSONObject("delta");
        assertTrue(delta.containsKey("content"));
        assertNull(delta.get("content"));
    }

    @Test void resultKeepsNestedNullableOutputAndScalars() throws Exception {
        when(runs.result(eq(runId),eq(principal),any(JSONObject.class))).thenReturn(JSONObject.of("accepted",true,"state","COMPLETED"));
        String body="""
            {"leaseId":"lease-fixture","attempt":1,"state":"COMPLETED",
             "output":{"value":null,"zero":0,"flag":false,"nested":{"value":null,"zero":0,"flag":false},
               "items":[null,{"value":null,"zero":0,"flag":false}]}}
            """;
        mvc.perform(request("/result",body)).andExpect(status().isOk());
        var capture=ArgumentCaptor.forClass(JSONObject.class);
        verify(runs).result(eq(runId),eq(principal),capture.capture());
        var output=capture.getValue().getJSONObject("output");
        assertNullAndScalars(output);
        assertNullAndScalars(output.getJSONObject("nested"));
        assertEquals(2,output.getJSONArray("items").size());
        assertNull(output.getJSONArray("items").get(0));
        assertNullAndScalars(output.getJSONArray("items").getJSONObject(1));
    }

    @Test void malformedLeaseAttemptIsActual400BeforeLeaseService() throws Exception {
        for(String attempt:new String[]{"null","\"1\"","false","1.5"}) {
            mvc.perform(request("/lease/verify","{\"leaseId\":\"lease-fixture\",\"attempt\":"+attempt+"}"))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(runs);
    }
}
