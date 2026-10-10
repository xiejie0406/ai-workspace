package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 独立原 Java SDK/原图捕获作预期，不将纯语义通过计为生产准入或同 Run 联机。 */
class JavaModelSemanticsTest {
    @Test void originalExecutorLiteralAndReferenceMessagesMatchActualNumericGolden() throws Exception {
        var samples=JSON.parseArray(resource("original-literal-golden.json"));assertEquals(14,samples.size());
        for(Object raw:samples){var sample=(JSONObject)raw;var node=ModelMessageSources.sourceObject(sample.getString("nodeJson"));Object content=node.getJSONObject("data").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getJSONObject("value").get("content");
            var values=JavaToolSemantics.inputs(node,Map.of("node-start::s",JavaToolSemantics.startPool(JSONObject.of("q",content))));
            assertEquals(sample.get("rendered"),JavaModelSemantics.messages(node,values,new JSONArray()).getJSONObject(0).get("content"),sample.getString("rawLiteral"));
        }
    }
    @Test void actualOriginalPojoScalarConversionAndJacksonObjectTemplate() throws Exception {
        for (Object raw : JSON.parseArray(resource("original-scalar-golden.json"))) {
            var sample = (JSONObject) raw;
            if ("maxTokens".equals(sample.get("kind"))) same(sample.get("options"), JavaModelSemantics.options(node(JSONObject.of("maxTokens",sample.get("value")))));
            else if ("objectTemplate".equals(sample.get("kind"))) {
                var messages = JavaModelSemantics.messages(sample.getJSONObject("node"),new LinkedHashMap<>(sample.getJSONObject("inputs")),new JSONArray());
                assertEquals(sample.get("rendered"),messages.getJSONObject(0).get("content"));
            }
        }
    }
    @Test void fiveActualOriginalSdkWireAndOutputGoldens() throws Exception {
        var cases = JSON.parseArray(resource("original-java-model-wire-golden.json"));
        assertEquals(5, cases.size());
        for (Object raw : cases) {
            var sample = (JSONObject) raw;
            int variant = sample.getIntValue("variant");
            var params = JSONObject.of("template", "问题", "systemTemplate", "系统");
            params.put("maxTokens", variant == 0 ? null : 32);
            if (variant == 1) params.put("extraParams", JSONObject.of("maxTokens",12,"temperature",0,"topP",0.0,"presencePenalty",0,"frequencyPenalty",0,"n",1));
            if (variant == 2) params.put("extraParams", JSONObject.of("maxTokens","bad","temperature","bad","topP","bad","n","bad"));
            if (variant == 3) params.put("extraParams", JSONObject.of("n",2));
            var node = node(params, "answer", "reason", "unused");
            var original = sample.getJSONObject("actualRequest").getJSONObject("parameters");
            same(original.get("options"), JavaModelSemantics.options(node));
            same(original.get("messages"), JavaModelSemantics.messages(node, new LinkedHashMap<>(), new JSONArray()));
            var projected = JavaModelSemantics.projection(sample.getJSONArray("rawFrames"));
            var output = sample.getJSONObject("actualOutput");
            assertEquals(output.get("content"), projected.content(), "原 variant " + variant);
            assertEquals(output.get("thinkContent"), projected.thinking());
            var usage = output.getJSONObject("usage");
            same(JSONObject.of("prompt_tokens",usage.get("promptTokens"),"completion_tokens",usage.get("completionTokens"),"total_tokens",usage.get("totalTokens")),projected.usage());
            assertEquals(variant == 4 ? "length" : "stop", projected.finishReason());
            same(JSONObject.of("answer",output.get("content"),"reason",output.get("thinkContent")), JavaModelSemantics.outputs(node,sample.getJSONArray("rawFrames")));
        }
    }

    @Test void actualOriginalStartLlmEndGraphMessagesCompletedOutputRawTextAndUsage() throws Exception {
        var graph = JSON.parseObject(resource("original-java-model-graph-golden.json"));
        var dsl = graph.getJSONObject("assignment").getJSONObject("dsl");
        JSONObject node = dsl.getJSONArray("nodes").getJSONObject(1), completed = null;
        for (Object raw : graph.getJSONArray("actualEvents")) {
            var event = (JSONObject) raw;
            if ("spark-llm::2".equals(event.get("nodeId")) && "node.completed".equals(event.get("eventType"))) completed = event.getJSONObject("payload").getJSONObject("result");
        }
        assertNotNull(completed);
        var inputs = new LinkedHashMap<String,Object>(completed.getJSONObject("inputs"));
        same(graph.getJSONObject("actualRequest").getJSONObject("parameters").get("messages"), JavaModelSemantics.messages(node, inputs, new JSONArray()));
        var raw = JSON.parseArray(resource("original-java-model-wire-golden.json")).getJSONObject(0).getJSONArray("rawFrames");
        var projected = JavaModelSemantics.projection(raw);
        same(completed.get("outputs"), JavaModelSemantics.outputs(node, projected));
        assertEquals(completed.get("rawOutput"), projected.content());
        same(completed.get("tokenCost"), projected.usage());
        assertFalse(JavaModelSemantics.outputs(node, projected).containsKey("unused"));
    }

    @Test void originalLiteralRenderingRetainsMissingNullAndActualMapContainerString() {
        var inputs = new LinkedHashMap<String,Object>();
        inputs.put("nil",null); inputs.put("number",0);inputs.put("boolean",false);inputs.put("literal","$1\\path");
        var ordinary = new LinkedHashMap<String,Object>();ordinary.put("z",1);ordinary.put("a",false);
        inputs.put("map",ordinary);inputs.put("json",new JSONObject(ordinary));inputs.put("list",List.of("中",2));
        String template = "{{missing}}/{{nil}}/{{number}}/{{boolean}}/{{literal}}/{{map}}/{{json}}/{{list}}";
        assertEquals("{{missing}}/{{nil}}/0/false/$1\\path/{z=1, a=false}/" + inputs.get("json") + "/[中, 2]", JavaModelSemantics.render(template,inputs));
        assertNull(JavaModelSemantics.render(null,inputs));
        assertEquals("",JavaModelSemantics.render("",inputs));
        assertThrows(ServiceException.class,()->JavaModelSemantics.messages(node(new JSONObject()),inputs,new JSONArray()));
        same(new JSONArray(List.of(JSONObject.of("role","user","content","42"))),JavaModelSemantics.messages(node(JSONObject.of("template",42,"systemTemplate"," \n")),inputs,null));
    }

    @Test void originalHistorySkipsOldSystemUsesAllOtherUserInputsAndOnlyFirstAssistant() throws Exception {
        var history = new JSONArray(List.of(JSONObject.of("userInputs",new JSONArray(List.of(
                JSONObject.of("role","SYSTEM","content","旧系统"),JSONObject.of("role","USER","content","旧用户"),JSONObject.of("role","ASSISTANT","content","原仍按user"))),
                "llmThinking",new JSONArray(List.of(JSONObject.of("role","THINKING","content","不发历史思考"))),
                "llmResponses",new JSONArray(List.of(JSONObject.of("role","ASSISTANT","content","旧答1"),JSONObject.of("role","ASSISTANT","content","旧答2"))))));
        var node = node(JSONObject.of("template","当前","systemTemplate","新系统"));
        var golden = JSON.parseArray(resource("original-scalar-golden.json"));
        var originalHistory = golden.stream().map(raw -> (JSONObject) raw).filter(sample -> "history".equals(sample.get("kind"))).findFirst().orElseThrow();
        same(originalHistory.get("messages"),JavaModelSemantics.messages(node,new LinkedHashMap<>(),history));
        node.getJSONObject("data").getJSONObject("nodeParam").put("enableChatHistoryV2",JSONObject.of("isEnabled",true,"rounds",2));
        assertEquals(2,JavaModelSemantics.historyLimit(node));
        node.getJSONObject("data").getJSONObject("nodeParam").getJSONObject("enableChatHistoryV2").put("rounds","2");
        assertEquals(0,JavaModelSemantics.historyLimit(node));
    }

    @Test void nullHistoryLimitRemainsOriginalNullableConfigurationFailure() throws Exception {
        var golden = JSON.parseArray(resource("original-scalar-golden.json"));
        var sample = golden.stream().map(raw -> (JSONObject) raw).filter(item -> "nullHistoryLimit".equals(item.get("kind"))).findFirst().orElseThrow();
        assertTrue(sample.containsKey("limit"));assertNull(sample.get("limit"));
        assertEquals("java.lang.NullPointerException",sample.get("originalException"));
        JSONObject node = sample.getJSONObject("node");
        assertNull(JavaModelSemantics.historyLimit(node),"不在纯函数中把 null 解箱吞成 0");
        var history = node.getJSONObject("data").getJSONObject("nodeParam").getJSONObject("enableChatHistoryV2");
        history.remove("rounds");assertNull(JavaModelSemantics.historyLimit(node));
        history.put("isEnabled",false);assertEquals(0,JavaModelSemantics.historyLimit(node));
        history.put("isEnabled",true);history.put("rounds",0);assertEquals(0,JavaModelSemantics.historyLimit(node));
    }

    @Test void nullableOptionsZeroInvalidOverridesAndNonFiniteRemainOriginalConversionFacts() {
        var params = JSONObject.of("maxTokens",12,"extraParams",JSONObject.of("maxTokens","bad","n",0,"topP",0,"temperature","NaN","frequencyPenalty","Infinity"));
        var options = JavaModelSemantics.options(node(params));
        assertEquals(12,options.get("maxTokens"));assertEquals(0,options.get("n"));assertEquals(0.0,options.get("topP"));
        assertTrue(Double.isNaN((Double) options.get("temperature")));
        assertEquals(Double.POSITIVE_INFINITY,options.get("frequencyPenalty"));
        assertTrue(options.containsKey("presencePenalty"));assertNull(options.get("presencePenalty"));
        assertEquals(Set.of("maxTokens","temperature","topP","presencePenalty","frequencyPenalty","n","streamUsage","enableThinking"), options.keySet());
    }

    @Test void firstReasonDeclarationOverridesFirstTextAndNoDeclarationsUseOutput() throws Exception {
        var raw = JSON.parseArray(resource("original-java-model-wire-golden.json")).getJSONObject(0).getJSONArray("rawFrames");
        same(JSONObject.of("ReAsOn","思考"),JavaModelSemantics.outputs(node(new JSONObject(),"ReAsOn","reason","unused"),raw));
        same(JSONObject.of("output","原文本"),JavaModelSemantics.outputs(node(new JSONObject()),raw));
    }

    @Test void missingFinishAndContentAfterFinishDoNotBecomeVerifiedCompletedOutput() throws Exception {
        var raw = JSON.parseArray(resource("original-java-model-wire-golden.json")).getJSONObject(0).getJSONArray("rawFrames");
        raw.remove(2);
        JSONArray missing = raw;
        assertThrows(ServiceException.class,()->JavaModelSemantics.projection(missing));
        raw = JSON.parseArray(resource("original-java-model-wire-golden.json")).getJSONObject(0).getJSONArray("rawFrames");
        raw.add(raw.getJSONObject(0));
        JSONArray invalid = raw;
        assertThrows(ServiceException.class,()->JavaModelSemantics.projection(invalid));
    }

    @Test void absentUsageHasOriginalZeroTokenCostAndPartialUsageComputesTotal() throws Exception {
        var raw = JSON.parseArray(resource("original-java-model-wire-golden.json")).getJSONObject(0).getJSONArray("rawFrames");
        raw.remove(3);
        same(JSONObject.of("prompt_tokens",0,"completion_tokens",0,"total_tokens",0),JavaModelSemantics.projection(raw).usage());
        raw.getJSONObject(2).put("usage",JSONObject.of("prompt_tokens",4,"completion_tokens",3));
        same(JSONObject.of("prompt_tokens",4,"completion_tokens",3,"total_tokens",7),JavaModelSemantics.projection(raw).usage());
    }

    private static JSONObject node(JSONObject params,String... outputs) {
        var declared = new JSONArray();
        for (String name : outputs) declared.add(JSONObject.of("name",name,"schema",JSONObject.of("type","object")));
        return JSONObject.of("id","spark-llm::2","data",JSONObject.of("nodeParam",params,"inputs",new JSONArray(),"outputs",declared));
    }
    private static String resource(String name) throws Exception {
        try(var in=JavaModelSemanticsTest.class.getResourceAsStream("/fixtures/java-model/"+name)) {assertNotNull(in);return new String(in.readAllBytes(), StandardCharsets.UTF_8);}
    }
    private static void same(Object expected,Object actual) {
        assertEquals(JSON.toJSONString(expected,JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue), JSON.toJSONString(actual,JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue));
    }
}
