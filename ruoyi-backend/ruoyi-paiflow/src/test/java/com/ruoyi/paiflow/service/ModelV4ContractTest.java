package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原Python Factory→CenterClient实际载荷；隔离消息契约，不能代表生产调用或业务验收。 */
class ModelV4ContractTest {
    @Test void originalUidDefaultsWithoutRewritingHistoricalNodeBytes() throws Exception {
        var node=node(cases().getJSONObject(0));var p=node.getJSONObject("data").getJSONObject("nodeParam");p.remove("uid");String before=ModelOperationJson.canonicalNode(node,ModelOperationJson.REQUEST_BYTES);assertEquals("",ModelNodeSemantics.providerUid(node));assertEquals(before,ModelOperationJson.canonicalNode(node,ModelOperationJson.REQUEST_BYTES));
        p.put("uid","business-user");assertEquals("business-user",ModelNodeSemantics.providerUid(node));for(Object invalid:new Object[]{null,12,true}){p.put("uid",invalid);assertEquals(422,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->ModelNodeSemantics.providerUid(node)).getCode());}
    }
    static JSONArray cases() throws Exception {
        try(var in=ModelV4ContractTest.class.getResourceAsStream("/fixtures/model-v4-actual-consumer.json")) {
            assertNotNull(in);return JSON.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases");
        }
    }
    static JSONObject node(JSONObject item) {
        return item.getJSONObject("assignment").getJSONObject("dsl").getJSONArray("nodes").stream().map(v->(JSONObject)v).filter(ModelNodeSemantics::isModelNode).findFirst().orElseThrow();
    }
    @Test void seventeenOriginalVariantsAndEighteenActualRequestsKeepMessagesOptionsAndFunctions() throws Exception {
        int count=0;
        for(Object raw:cases()) {
            var item=(JSONObject)raw;var node=ModelOperationJson.copy(node(item));ModelNodeSemantics.normalize(node);
            var sources=new JSONArray();var calls=item.getJSONArray("parameters");
            for(int index=0;index<calls.size();index++) {
                var call=calls.getJSONObject(index);var name=item.getString("name")+":"+index;
                if("question-answer".equals(ModelNodeSemantics.type(node))) {
                    var question=item.getJSONArray("checkpoints").getJSONArray(index).getJSONArray(0).getJSONObject(5).getString("content");
                    if(index>0)sources.getJSONObject(index-1).put("priorModelOutput","{\"role\":\"assistant\",\"content\":\"请补充年龄\",\"completed\":{},\"incomplete\":{\"age\":null}}");
                    sources.add(JSONObject.of("checkpointId",call.getString("checkpointId"),"ordinal",index+1,"question",question,"reply",index==0?"还没年龄":"年龄8"));
                }
                assertEquals(ModelOperationJson.canonical(call.get("messages"),ModelOperationJson.REQUEST_BYTES),ModelOperationJson.canonical(ModelMessageSources.expected(node,item.getJSONObject("assignment").getJSONObject("input"),new JSONArray(),sources),ModelOperationJson.REQUEST_BYTES),name);
                assertEquals(ModelOperationJson.canonical(call.get("options"),ModelOperationJson.REQUEST_BYTES),ModelOperationJson.canonical(ModelOperationOptions.forNode(node),ModelOperationJson.REQUEST_BYTES),name);
                ModelOperationOptions.validateRequest(call,node);
                if(call.containsKey("functions"))assertEquals(ModelOperationJson.canonicalFunctions(call.get("functions"),ModelOperationJson.REQUEST_BYTES),ModelOperationJson.canonicalFunctions(ModelMessageSources.functions(node),ModelOperationJson.REQUEST_BYTES),name);
                count++;
            }
        }
        assertEquals(18,count);
    }
    @Test void originalSlotDefaultsAndEmptyQuestionKeepTheirMeaning() {
        var output=ModelMessageSources.slotOutput("{\"incomplete\":{\"age\":null}}");
        assertEquals("",output.getString("content"));assertEquals("assistant",output.getString("role"));assertTrue(output.getJSONObject("completed").isEmpty());assertFalse(output.getJSONObject("incomplete").isEmpty());
        assertEquals("",ModelMessageSources.renderQuestion("{{q}}",java.util.Map.of("q","")));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->ModelMessageSources.slotOutput("{\"content\":null}"));
    }
    @Test void pythonNumericAndNestedControlRepresentationsMatchActualOriginalSource() throws Exception {
        try(var in=getClass().getResourceAsStream("/fixtures/model-scalar-actual-source.json")) {
            var fixture=ModelMessageSources.sourceObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            for(Object raw:fixture.getJSONArray("cases")){var item=(JSONObject)raw;var node=JSONObject.of("id","extractor-parameter::model","data",item.getJSONObject("nodeData"));ModelNodeSemantics.normalize(node);assertEquals(item.getJSONObject("parameters").getJSONArray("messages"),ModelMessageSources.expected(node,item.getJSONObject("input"),new JSONArray(),new JSONArray()),item.getString("name"));}
        }
        assertEquals("1e+23",ModelMessageSources.pythonString(1e23,false));assertEquals("0.0001",ModelMessageSources.pythonString(1e-4,false));assertEquals("1000000000000000.0",ModelMessageSources.pythonString(1e15,false));assertEquals("1e+16",ModelMessageSources.pythonString(1e16,false));
        assertEquals("-0.0",ModelMessageSources.pythonString(ModelMessageSources.sourceObject("{\"q\":-0.0}").get("q"),false));
    }
}
