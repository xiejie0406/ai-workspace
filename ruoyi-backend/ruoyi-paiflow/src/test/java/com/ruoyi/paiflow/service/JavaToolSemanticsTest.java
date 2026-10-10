package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.RunRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 三份原 Java Factory/AiToolsIntegration 离线 Exchange 实际采集，不代表中心已准入。 */
class JavaToolSemanticsTest {
    @Test void originalJacksonLiteralAndRootReferencesKeepNumericTypesAndRenderText() throws Exception {
        try(var in=getClass().getResourceAsStream("/fixtures/java-model/original-literal-golden.json")){
            assertNotNull(in);var samples=JSON.parseArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));assertEquals(14,samples.size());
            for(Object raw:samples){var sample=(JSONObject)raw;var node=ModelMessageSources.sourceObject(sample.getString("nodeJson"));Object literal=node.getJSONObject("data").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getJSONObject("value").get("content");
                var values=JavaToolSemantics.inputs(node,Map.of("node-start::s",JavaToolSemantics.startPool(JSONObject.of("q",literal))));
                assertEquals(sample.get("literalText"),String.valueOf(values.get("literal")),sample.getString("rawLiteral"));assertEquals(sample.get("referenceText"),String.valueOf(values.get("reference")),sample.getString("rawLiteral"));
                var types=sample.getJSONObject("inputTypes").getJSONObject("children");if(types.get("literal") instanceof String type)assertEquals(type,values.get("literal").getClass().getName());
            }
        }
    }
    private static Object fixture(String name) throws IOException {
        try (var input = JavaToolSemanticsTest.class.getResourceAsStream("/paiflow/java-tool/"+name)) {
            assertNotNull(input,"原 Java 金样须随测试资源交付");
            return JSON.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private static JSONObject original() throws IOException { return (JSONObject)fixture("original-java-tool-golden.json"); }
    private static JSONObject references() throws IOException { return (JSONObject)fixture("original-java-tool-output-reference-golden.json"); }
    private static JSONObject execution(JSONObject gold) {
        byte[] bytes = Base64.getDecoder().decode(gold.getJSONObject("assignment").getString("dslCanonicalBase64"));
        return JSON.parseObject(new String(bytes,StandardCharsets.UTF_8));
    }
    private static JSONObject node(JSONObject gold,String id) {
        return execution(gold).getJSONArray("nodes").stream().map(JSONObject.class::cast).filter(n -> id.equals(n.get("id"))).findFirst().orElseThrow();
    }
    private static JSONObject descriptor(JSONObject gold) {
        var assignment = gold.getJSONObject("assignment");
        String ref = node(gold,"plugin::2").getJSONObject("data").getJSONObject("nodeParam").getString("toolResourceId");
        return assignment.getJSONObject("resourceSnapshots").getJSONObject(ref);
    }
    private static Map<String,Map<String,Object>> start(JSONObject gold) {
        return new HashMap<>(Map.of("node-start::1",JavaToolSemantics.startPool(gold.getJSONObject("assignment").getJSONObject("input"))));
    }
    private static JSONObject request(JSONObject gold) {
        if (gold.containsKey("actualOperationRequest")) return gold.getJSONObject("actualOperationRequest");
        return gold.getJSONArray("actualRequests").stream().map(JSONObject.class::cast).filter(r -> r.getString("path").endsWith("/operations")).findFirst().orElseThrow().getJSONObject("body");
    }
    private static JSONObject provider(JSONObject gold) {
        var response = gold.getJSONArray("actualResponses").stream().map(JSONObject.class::cast).map(r -> r.getJSONObject("data"))
                .filter(Objects::nonNull).filter(r -> r.get("legacyResponse") instanceof JSONObject).findFirst().orElseThrow().getJSONObject("legacyResponse");
        return JSON.parseObject(response.getJSONObject("payload").getJSONObject("text").getString("text"));
    }
    private static JSONObject event(JSONObject gold,String id) {
        return gold.getJSONArray("actualEvents").stream().map(JSONObject.class::cast).filter(e -> "node.completed".equals(e.get("eventType")) && id.equals(e.get("nodeId"))).findFirst().orElseThrow();
    }
    private static RunRecord run(JSONObject gold) {
        var a = gold.getJSONObject("assignment");
        var run = new RunRecord();
        run.setId(a.getString("runId")); run.setLeaseId(a.getString("leaseId")); run.setAttempt(a.getInteger("attempt"));
        run.setEngineProfile(a.getString("engineProfile")); run.setRunJsonCodec(DslCodec.V2);
        run.setAssignmentJson(a.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        run.setLastEventSeq(gold.getJSONArray("actualEvents").stream().map(JSONObject.class::cast).mapToLong(e -> e.getLongValue("seq")).max().orElseThrow());
        return run;
    }
    private static void equal(Object expected,Object actual) { assertTrue(ToolSchema.same(expected,actual),"原采集值与 Java 重建不同："+ToolSchema.json(expected)+" / "+ToolSchema.json(actual)); }
    private static JSONObject copy(JSONObject value) { return JSON.parseObject(value.toJSONString(JSONWriter.Feature.WriteMapNullValue)); }

    @Test void fullFactoryRequestMatchesFourOriginalEncodedPositions() throws Exception {
        var gold = original();
        var inputs = JavaToolSemantics.inputs(node(gold,"plugin::2"),start(gold));
        equal(event(gold,"plugin::2").getJSONObject("payload").getJSONObject("result").get("inputs"),inputs);
        var rebuilt = JavaToolSemantics.expected(node(gold,"plugin::2"),inputs,descriptor(gold));
        // 比较完整原 base64，而不只比较解码值，保留原 null 省略/编码/位置。
        equal(request(gold).getJSONObject("parameters").get("legacyPayload"),rebuilt);
        ToolSchema.comparePayload(request(gold).getJSONObject("parameters").getJSONObject("legacyPayload"),rebuilt);
    }

    @Test void directOriginalBfsRootFirstMatchIncludingNull() throws Exception {
        var samples = (JSONArray)fixture("original-java-tool-bfs-golden.json");
        assertEquals(2,samples.size());
        for (Object raw : samples) {
            var gold = (JSONObject)raw;
            var rebuilt = JavaToolSemantics.expected(node(gold,"plugin::2"),gold.getJSONObject("actualInputs"),descriptor(gold));
            equal(request(gold).getJSONObject("parameters").get("legacyPayload"),rebuilt);
            equal(gold.get("actualOutput"),JavaToolSemantics.project(provider(gold)));
        }
        var nullSample = samples.getJSONObject(1);
        var header = request(nullSample).getJSONObject("parameters").getJSONObject("legacyPayload").getJSONObject("payload").getJSONObject("message");
        assertEquals("e30=",header.get("header"),"原非空 header Map 含显式 null，仍发送编码后的 {}，不选深层同名值");
    }

    @Test void originalProviderWholeMapAndIndexedEndReferenceRemainUnfiltered() throws Exception {
        var gold = references(); var outputs = JavaToolSemantics.project(provider(gold));
        equal(event(gold,"plugin::2").getJSONObject("payload").getJSONObject("result").get("outputs"),outputs);
        assertEquals("原字符串",outputs.get("count")); assertEquals("未声明输出",outputs.get("extra"));
        assertTrue(outputs.containsKey("nullable")); assertNull(outputs.get("nullable")); assertFalse(outputs.containsKey("missing"));
        var pool = start(gold); pool.put("plugin::2",JavaToolSemantics.outputPool(outputs));
        var end = JavaToolSemantics.inputs(node(gold,"node-end::3"),pool);
        equal(event(gold,"node-end::3").getJSONObject("payload").getJSONObject("result").get("outputs"),end);
        assertEquals("数组索引实际值",end.get("answer")); assertTrue(end.containsKey("missing")); assertNull(end.get("missing"));
    }

    @Test void originalStartAndPluginAndEndCompletionShapesAreVerified() throws Exception {
        for (var gold : List.of(original(),references())) {
            var pool = start(gold); var run = run(gold);
            var startInputs = JavaToolSemantics.inputs(node(gold,"node-start::1"),pool);
            JavaToolSemantics.verifyEvent(run,node(gold,"node-start::1"),event(gold,"node-start::1"),startInputs,Map.of());
            var inputs = JavaToolSemantics.inputs(node(gold,"plugin::2"),pool);
            var outputs = JavaToolSemantics.project(provider(gold));
            JavaToolSemantics.verifyEvent(run,node(gold,"plugin::2"),event(gold,"plugin::2"),inputs,outputs);
            pool.put("plugin::2",JavaToolSemantics.outputPool(outputs));
            var endInputs = JavaToolSemantics.inputs(node(gold,"node-end::3"),pool);
            JavaToolSemantics.verifyEvent(run,node(gold,"node-end::3"),event(gold,"node-end::3"),endInputs,endInputs);
        }
    }

    @Test void providerBusinessErrorKeysAreKeptAndNotOverwritten() throws Exception {
        var raw = provider(references()); raw.put("errorCode","业务值"); raw.put("errorMessage",null);
        var result = JavaToolSemantics.project(raw);
        equal(raw,result); assertEquals("业务值",result.get("errorCode")); assertTrue(result.containsKey("errorMessage")); assertNull(result.get("errorMessage"));
        raw.put("extra","changed"); assertEquals("未声明输出",result.get("extra"));
    }

    @Test void literalStringsAndFalseAndZeroKeepJavaValuesInsteadOfPythonConversion() throws Exception {
        var gold = original(); var target = copy(node(gold,"plugin::2"));
        var fields = target.getJSONObject("data").getJSONArray("inputs");
        var literal = fields.getJSONObject(0).getJSONObject("schema").getJSONObject("value");
        literal.put("type","literal"); literal.put("content","{'answer': 1}");
        var values = JavaToolSemantics.inputs(target,start(gold));
        assertEquals("{'answer': 1}",values.get("h")); assertEquals(false,values.get("flag")); assertEquals(0,values.get("count"));
    }

    @Test void originalStartDoesNotFillDeclaredLiteralAbsentFromFixedRunInput() throws Exception {
        var gold = original(); var fixed = copy(gold.getJSONObject("assignment").getJSONObject("input"));
        fixed.remove("count");
        var start = node(gold,"node-start::1");
        assertTrue(start.getJSONObject("data").getJSONArray("inputs").stream().map(JSONObject.class::cast).anyMatch(f -> "count".equals(f.get("name"))));
        var resolved = JavaToolSemantics.inputs(start,Map.of(start.getString("id"),JavaToolSemantics.startPool(fixed)));
        assertFalse(resolved.containsKey("count")); equal(JavaToolSemantics.startPool(fixed),resolved);
    }

    @Test void originalNodeIdChoosesExecutorEvenWithWrongOrMissingDisplayMeta() throws Exception {
        var gold = original(); var target = node(gold,"plugin::2");
        var raw = request(gold).getJSONObject("parameters").getJSONObject("legacyPayload");
        target.getJSONObject("data").getJSONObject("nodeMeta").put("nodeType","node-start");
        equal(raw,JavaToolSemantics.expected(target,JavaToolSemantics.inputs(target,start(gold)),descriptor(gold)));
        target.getJSONObject("data").remove("nodeMeta");
        equal(raw,JavaToolSemantics.expected(target,JavaToolSemantics.inputs(target,start(gold)),descriptor(gold)));
        var start = node(gold,"node-start::1"); start.getJSONObject("data").remove("nodeMeta");
        equal(JavaToolSemantics.startPool(gold.getJSONObject("assignment").getJSONObject("input")),JavaToolSemantics.inputs(start,start(gold)));
    }

    @Test void startNullAndAbsentMapNullFollowOriginalVariablePool() throws Exception {
        var raw = new JSONObject(); raw.put("present",null); raw.put("nested",JSONObject.of("present",null,"zero",0));
        var values = JavaToolSemantics.startPool(raw);
        assertTrue(values.containsKey("present")); assertNull(values.get("present"));
        assertFalse(((JSONObject)values.get("nested")).containsKey("present"));
        var outputs = JavaToolSemantics.outputPool(JSONObject.of("present",null,"zero",0));
        assertFalse(outputs.containsKey("present")); assertEquals(0,outputs.get("zero"));
    }

    @Test void missingReferenceFieldIsNullButUnprovenSourceIsRejected() throws Exception {
        var gold = references(); var target = node(gold,"node-end::3");
        assertThrows(ServiceException.class,() -> JavaToolSemantics.inputs(target,start(gold)));
        var pool = start(gold); pool.put("plugin::2",JavaToolSemantics.outputPool(provider(gold)));
        var fields = target.getJSONObject("data").getJSONArray("inputs");
        fields.getJSONObject(0).getJSONObject("schema").getJSONObject("value").getJSONObject("content").put("name","data[999].x");
        assertNull(JavaToolSemantics.inputs(target,pool).get("answer"));
        fields.getJSONObject(0).getJSONObject("schema").getJSONObject("value").getJSONObject("content").put("name","data[-1].x");
        assertThrows(ServiceException.class,() -> JavaToolSemantics.inputs(target,pool));
    }

    @Test void completedScopeMustMatchCurrentRunLeaseAttemptAndWatermark() throws Exception {
        var gold = references(); var node = node(gold,"plugin::2"); var run = run(gold);
        for (var wrong : List.of(JSONObject.of("leaseId","old"),JSONObject.of("runId","other"),JSONObject.of("attempt",2),JSONObject.of("attempt",1.0),JSONObject.of("iterationId","nested"),JSONObject.of("nodeId","other"),JSONObject.of("seq",run.getLastEventSeq()+1))) {
            var event = copy(event(gold,"plugin::2")); event.putAll(wrong);
            assertThrows(ServiceException.class,() -> JavaToolSemantics.eventOutputs(run,node,event));
        }
        run.setEngineProfile("python"); assertThrows(ServiceException.class,() -> JavaToolSemantics.eventOutputs(run,node,event(gold,"plugin::2")));
    }

    @Test void successfulJavaCompletionCannotBeReplacedByLegacyFrameOrFailedResult() throws Exception {
        var gold = references(); var run = run(gold); var node = node(gold,"plugin::2");
        var wrongNode = copy(event(gold,"plugin::2")); wrongNode.getJSONObject("payload").put("nodeId","other");
        assertThrows(ServiceException.class,() -> JavaToolSemantics.eventOutputs(run,node,wrongNode));
        var event = copy(event(gold,"plugin::2")); event.getJSONObject("payload").getJSONObject("result").put("status","FAILED");
        var failed = event; assertThrows(ServiceException.class,() -> JavaToolSemantics.eventOutputs(run,node,failed));
        event = copy(event(gold,"plugin::2")); event.getJSONObject("payload").getJSONObject("result").put("error","provider error");
        var errorEvent = event; assertThrows(ServiceException.class,() -> JavaToolSemantics.eventOutputs(run,node,errorEvent));
    }

    @Test void selfReportedInputOrOutputChangeCannotPassLedgerComparison() throws Exception {
        var gold = references(); var run = run(gold); var node = node(gold,"plugin::2");
        var inputs = JavaToolSemantics.inputs(node,start(gold)); var outputs = provider(gold);
        var changedOutput = copy(event(gold,"plugin::2")); changedOutput.getJSONObject("payload").getJSONObject("result").getJSONObject("outputs").put("count",9);
        assertThrows(ServiceException.class,() -> JavaToolSemantics.verifyEvent(run,node,changedOutput,inputs,outputs));
        var changedInput = copy(event(gold,"plugin::2")); changedInput.getJSONObject("payload").getJSONObject("result").getJSONObject("inputs").put("count",999);
        assertThrows(ServiceException.class,() -> JavaToolSemantics.verifyEvent(run,node,changedInput,inputs,outputs));
    }

    @Test void fixedDescriptorMismatchAndTtsRemainRejected() throws Exception {
        var gold = original(); var target = node(gold,"plugin::2"); var values = JavaToolSemantics.inputs(target,start(gold));
        var wrong = copy(descriptor(gold)); wrong.put("operationId","other");
        assertThrows(ServiceException.class,() -> JavaToolSemantics.expected(target,values,wrong));
        target.getJSONObject("data").getJSONObject("nodeParam").put("pluginId","tool@8b2262bef821000");
        assertThrows(ServiceException.class,() -> JavaToolSemantics.expected(target,values,descriptor(gold)));
        assertThrows(ServiceException.class,() -> JavaToolSemantics.project(List.of("array")));
    }
}

