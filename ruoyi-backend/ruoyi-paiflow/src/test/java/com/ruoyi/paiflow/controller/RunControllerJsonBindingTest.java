package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.service.RunService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RunControllerJsonBindingTest {
    @Test void actualHttpMapConversionKeepsNestedExplicitNullAndArrayNullForRunHash() throws Exception {
        var service=mock(RunService.class);var mvc=MockMvcBuilders.standaloneSetup(new RunController(service)).build();mvc.perform(post("/paiflow/runs").contentType("application/json").content("{\"workflowVersionId\":1,\"engineProfile\":\"python\",\"input\":{\"top\":null,\"nested\":{\"value\":null},\"list\":[null,{\"nullable\":null}]},\"idempotencyKey\":\"http_binding_key\"}")).andExpect(status().isOk());var input=ArgumentCaptor.forClass(JSONObject.class);verify(service).create(eq(1L),eq("python"),input.capture(),eq("http_binding_key"),eq(900));var actual=input.getValue();assertTrue(actual.containsKey("top"));assertNull(actual.get("top"));assertTrue(actual.getJSONObject("nested").containsKey("value"));assertNull(actual.getJSONArray("list").get(0));assertTrue(actual.getJSONArray("list").getJSONObject(1).containsKey("nullable"));
    }
}
