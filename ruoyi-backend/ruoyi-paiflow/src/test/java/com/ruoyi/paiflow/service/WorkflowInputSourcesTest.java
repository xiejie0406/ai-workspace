package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 对照真实原Factory/VariablePool金样；输入解析不替代调用端的完成来源校核。 */
class WorkflowInputSourcesTest {
    private JSONObject original() throws Exception {
        try(var stream=getClass().getResourceAsStream("/fixtures/output-pool-default-golden.json")){
            assertNotNull(stream);return ModelMessageSources.sourceObject(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test void missingDeclaredOutputUsesOriginalPoolDefaultAndImplicitErrorValues() throws Exception {
        var gold=original();var assignment=gold.getJSONObject("assignment");var execution=assignment.getJSONObject("dsl");var target=DataWorkerOperationService.find(execution,"node-end::e");
        var actual=WorkflowInputSources.values(execution,target,assignment.getJSONObject("input"),gold.getJSONArray("actualEvents"));
        var expected=gold.getJSONArray("actualResults").getJSONObject(0).getJSONObject("output").getJSONObject("outputs");
        assertEquals(RunJsonBoundary.canonicalFull(expected),RunJsonBoundary.canonicalFull(new JSONObject(actual)));
        assertTrue(actual.containsKey("nil"));assertNull(actual.get("nil"));assertEquals(0,actual.get("count"));assertEquals(0,actual.get("errorCode"));assertEquals("",actual.get("errorMessage"));
    }
    @Test void undeclaredFieldCannotBecomeAReferenceEvenIfAnEventContainsIt() throws Exception {
        var gold=original();var assignment=gold.getJSONObject("assignment");var execution=assignment.getJSONObject("dsl");var target=DataWorkerOperationService.find(execution,"node-end::e");
        target.getJSONObject("data").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getJSONObject("value").getJSONObject("content").put("name","undeclared");
        for(Object raw:gold.getJSONArray("actualEvents")){var event=(JSONObject)raw;if("text-joiner::middle".equals(event.get("nodeId"))&&"node.completed".equals(event.get("eventType")))event.getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").getJSONObject("outputs").put("undeclared","不属于输出池");}
        assertEquals(403,assertThrows(ServiceException.class,()->WorkflowInputSources.values(execution,target,assignment.getJSONObject("input"),gold.getJSONArray("actualEvents"))).getCode());
    }
}
