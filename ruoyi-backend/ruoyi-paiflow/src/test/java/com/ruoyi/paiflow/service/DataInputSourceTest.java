package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.mapper.RunMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原Factory黄金样本与实际Worker入口；中心/行设施为隔离Mock，不代表真实库验收。 */
class DataInputSourceTest {
    private static JSONArray goldens() throws Exception {
        try(var input=DataInputSourceTest.class.getResourceAsStream("/paiflow/pgsql-source-golden.json")) {
            assertNotNull(input);return ModelMessageSources.sourceObject(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases");
        }
    }
    private static JSONObject sample(String name) throws Exception {
        return goldens().stream().map(JSONObject.class::cast).filter(value->name.equals(value.get("name"))).findFirst().orElseThrow();
    }
    private static DataWorkerOperationTest.Fixture bind(JSONObject sample) {
        var f=new DataWorkerOperationTest.Fixture();var assignment=sample.getJSONObject("assignment");
        assignment.put("resourceSnapshots",JSONObject.of("data:7:revision:2",f.descriptor));f.run.setAssignmentJson(assignment.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        var events=sample.getJSONArray("acceptedFrames");var rows=new ArrayList<RunMapper.EventRow>();long last=0;
        for(Object raw:events) {
            var event=(JSONObject)raw;event.put("runId",f.run.getId());event.put("leaseId",f.run.getLeaseId());event.put("attempt",f.run.getAttempt());long seq=event.getLongValue("seq");last=Math.max(last,seq);rows.add(new RunMapper.EventRow(seq,event.toJSONString(JSONWriter.Feature.WriteMapNullValue)));
        }
        f.run.setLastEventSeq(last);when(f.runs.eventsThrough(eq(f.run.getId()),eq(0L),eq(last),eq(1001))).thenReturn(rows);
        when(f.operation.execute(eq(f.run),eq("data:7:revision:2"),any(),anyString(),any(),eq("key1")))
            .thenReturn(JSONObject.of("code",0,"message","success","data",JSONObject.of("exec_success",new JSONArray())));
        return f;
    }
    private static JSONObject request(DataWorkerOperationTest.Fixture f,JSONObject sample) {
        var request=f.request();request.put("nodeId",sample.getJSONObject("nodeJson").get("id"));
        var captured=sample.getJSONObject("actualParameters");
        request.put("parameters",captured==null?JSONObject.of("kind","database","provider","center-data","resourceId","data:7:revision:2","resourceVersion","2","resourceBindings",new JSONObject(),"legacyPayload",sample.getJSONObject("expectedLegacyPayload")):captured);
        request.put("operation",sample.getJSONObject("expectedLegacyPayload").getIntValue("mode")==3 || sample.getString("name").startsWith("custom-")?"data.query":"data.mutate");return request;
    }
    @Test void actualOriginalFactoryInputsAndPayloadsMatchCentreReconstruction() throws Exception {
        var cases=goldens();assertEquals(19,cases.size());
        for(Object raw:cases) {
            var sample=(JSONObject)raw;var assignment=sample.getJSONObject("assignment");
            var expected=DataInputPayload.expected(sample.getJSONObject("dsl"),sample.getJSONObject("nodeJson"),sample.getJSONObject("actualRunInput"),sample.getJSONArray("acceptedFrames"),assignment.getIntValue("dataEnvironment"));
            assertEquals(RunJsonBoundary.canonicalFull(sample.getJSONObject("expectedLegacyPayload")),RunJsonBoundary.canonicalFull(expected),sample.getString("name"));
            // 原str的未转义对象产生无效SQL，来源转换须相同，现有SQL解析仍须拒绝；中文占位的合法SQL正常接入。
            if("custom-python-str-only-word-placeholders".equals(sample.get("name"))) {
                var invalid=bind(sample);var request=request(invalid,sample);assertThrows(ServiceException.class,()->invalid.worker.start(invalid.run.getId(),invalid.identity,request));verifyNoInteractions(invalid.operation);continue;
            }
            var f=bind(sample);assertEquals("COMPLETED",f.worker.start(f.run.getId(),f.identity,request(f,sample)).get("state"),sample.getString("name"));
            verify(f.operation).execute(eq(f.run),eq("data:7:revision:2"),any(),anyString(),argThat(payload->RunJsonBoundary.canonicalFull(payload).equals(RunJsonBoundary.canonicalFull(expected))),eq("key1"));
        }
    }
    @Test void forgedAddUpdateAndCustomValuesNeverReachRowProducer() throws Exception {
        for(String name:List.of("integer-string","update-first-case-assignment-order-like-fallback")) {
            var sample=sample(name);var f=bind(sample);var request=request(f,sample);var payload=request.getJSONObject("parameters").getJSONObject("legacyPayload");
            if(payload.getIntValue("mode")==0)payload.put("sql",payload.getString("sql").replace("True","False"));
            else payload.getJSONObject("values").put(payload.getJSONObject("values").keySet().iterator().next(),"worker-forged-value");
            assertEquals(409,assertThrows(ServiceException.class,()->f.worker.start(f.run.getId(),f.identity,request),name).getCode());verifyNoInteractions(f.operation);
        }
        var f=new DataWorkerOperationTest.Fixture();var task=JSON.parseObject(f.run.getAssignmentJson());
        var data=task.getJSONObject("dsl").getJSONArray("nodes").getJSONObject(0).getJSONObject("data");
        data.getJSONObject("nodeParam").putAll(JSONObject.of("mode",0,"sql","UPDATE people SET name='{{name}}' WHERE id=2"));
        data.put("inputs",new JSONArray(List.of(JSONObject.of("name","name","schema",JSONObject.of("type","string","value",JSONObject.of("type","ref","content",JSONObject.of("nodeId","node-start::s","name","name")))))));
        task.put("input",JSONObject.of("name","fixed-authorized-input"));f.run.setAssignmentJson(task.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        var request=f.request();request.put("operation","data.mutate");request.getJSONObject("parameters").put("legacyPayload",JSONObject.of("mode",0,"sql","UPDATE people SET name='worker-forged-value' WHERE id=2","execDev",2));
        assertEquals(409,assertThrows(ServiceException.class,()->f.worker.start(f.run.getId(),f.identity,request)).getCode());verifyNoInteractions(f.operation);
    }
    @Test void conditionsZeroEnvironmentAndReturnCannotChangeFixedRequest() throws Exception {
        var sample=sample("update-first-case-assignment-order-like-fallback");
        for(String field:List.of("where","execDev","returning")) {
            var isolated=ModelMessageSources.sourceObject(sample.toJSONString(JSONWriter.Feature.WriteMapNullValue));var f=bind(isolated);var request=request(f,isolated);var payload=request.getJSONObject("parameters").getJSONObject("legacyPayload");
            if("where".equals(field))payload.getJSONObject("where").getJSONArray("conditions").getJSONObject(0).put("value",0);
            else payload.put(field,"execDev".equals(field)?1:"last_inserted_row");
            assertThrows(ServiceException.class,()->f.worker.start(f.run.getId(),f.identity,request));verifyNoInteractions(f.operation);
        }
    }
    @Test void missingCompletedWrongAttemptAndUnrelatedUpstreamRejectBeforeProducer() throws Exception {
        var sample=sample("accepted-upstream-text-joiner");
        for(String invalid:List.of("missing","wrong-attempt","unrelated")) {
            var isolated=ModelMessageSources.sourceObject(sample.toJSONString(JSONWriter.Feature.WriteMapNullValue));
            if("wrong-attempt".equals(invalid))isolated.getJSONArray("acceptedFrames").forEach(raw->((JSONObject)raw).put("attempt",2));
            var f=bind(isolated);
            if("missing".equals(invalid) || "wrong-attempt".equals(invalid))when(f.runs.eventsThrough(eq(f.run.getId()),eq(0L),anyLong(),eq(1001)))
                .thenReturn("missing".equals(invalid)?List.of():isolated.getJSONArray("acceptedFrames").stream().map(JSONObject.class::cast).map(event->{event.put("attempt",2);return new RunMapper.EventRow(event.getLongValue("seq"),event.toJSONString());}).toList());
            if("unrelated".equals(invalid)) {var task=JSON.parseObject(f.run.getAssignmentJson());task.getJSONObject("dsl").put("edges",new JSONArray());f.run.setAssignmentJson(task.toJSONString(JSONWriter.Feature.WriteMapNullValue));}
            var request=request(f,isolated);assertThrows(ServiceException.class,()->f.worker.start(f.run.getId(),f.identity,request));verifyNoInteractions(f.operation);
        }
    }
    @Test void literalParserDoesNotExecuteExpressionsOrAcceptUnserializableConstants() {
        for(String input:List.of("__import__('os').system('echo forged')","[x for x in range(3)]","{1, 2}","1+2","01","1__2","1e9999"))assertThrows(ServiceException.class,()->PythonLiteralValue.parse(input),input);
        assertEquals(RunJsonBoundary.canonicalFull(JSONObject.of("a",new JSONArray(List.of(true,3)))),RunJsonBoundary.canonicalFull((JSONObject)PythonLiteralValue.parse("{'a': [True, 3,],}")));
        assertEquals("原\n文",PythonLiteralValue.parse("'原\\n文'"));assertEquals(new JSONArray(List.of(1,2)),JSON.parseArray(JSON.toJSONString(PythonLiteralValue.parse("(1, 2)"))));
    }
    @Test void actualOriginalFactoryLiteralSyntaxMatchesCentreAndWorkerStart() throws Exception {
        try(var source=DataInputSourceTest.class.getResourceAsStream("/paiflow/pgsql-literal-syntax-golden.json")) {
            assertNotNull(source);var cases=ModelMessageSources.sourceObject(new String(source.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases");assertEquals(33,cases.size());
            for(Object raw:cases) {
                var sample=(JSONObject)raw;Object actual=PythonLiteralValue.parse(sample.getString("literal"));
                assertEquals(RunJsonBoundary.canonicalFull(JSONObject.of("value",sample.get("expectedValue"))),RunJsonBoundary.canonicalFull(JSONObject.of("value",actual)),sample.getString("name"));
                var f=bind(sample);var request=request(f,sample);assertEquals("COMPLETED",f.worker.start(f.run.getId(),f.identity,request).get("state"),sample.getString("name"));
            }
        }
    }
    @Test void originalTupleRemainsTupleInCustomSqlAndArrayInJson() throws Exception {
        try(var source=DataInputSourceTest.class.getResourceAsStream("/paiflow/pgsql-custom-tuple-golden.json")) {
            assertNotNull(source);var cases=ModelMessageSources.sourceObject(new String(source.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases");assertEquals(1,cases.size());
            var sample=cases.getJSONObject(0);var assignment=sample.getJSONObject("assignment");
            var actual=DataInputPayload.expected(assignment.getJSONObject("dsl"),sample.getJSONObject("nodeJson"),sample.getJSONObject("actualRunInput"),sample.getJSONArray("acceptedFrames"),2);
            assertEquals(RunJsonBoundary.canonicalFull(sample.getJSONObject("expectedLegacyPayload")),RunJsonBoundary.canonicalFull(actual));
            assertEquals("(1,)",WorkflowInputSources.pythonString(PythonLiteralValue.parse("(1,)"),false));
            assertEquals("{'a': (1, 2)}",WorkflowInputSources.pythonString(PythonLiteralValue.parse("{'a': (1, 2)}"),false));
            var f=bind(sample);assertEquals("COMPLETED",f.worker.start(f.run.getId(),f.identity,request(f,sample)).get("state"));
            String template=sample.getJSONObject("nodeJson").getJSONObject("data").getJSONObject("nodeParam").getString("sql");
            for(String sql:List.of("SELECT name FROM items WHERE count IN (score)","SELECT name FROM items WHERE count IN (1, 2) OR TRUE","SELECT name FROM items WHERE count IN (ABS(1))"))assertFalse(DataSqlService.matchesTemplate(template,sql));
        }
    }
    @Test void allOfficialUnicodeAliasesMatchActualPythonLookup() throws Exception {
        try(var source=DataInputSourceTest.class.getResourceAsStream("/paiflow/unicode-aliases-python-golden.json")) {
            assertNotNull(source);var cases=JSON.parseArray(new String(source.readAllBytes(),StandardCharsets.UTF_8));assertEquals(473,cases.size());
            for(Object raw:cases){var sample=(JSONObject)raw;assertEquals(new String(Character.toChars(sample.getIntValue("codepoint"))),PythonLiteralValue.parse("'\\N{"+sample.getString("alias")+"}'"),sample.getString("alias"));}
        }
    }
    @Test void originalFactoryRejectedLiteralShapesRemainRejected() throws Exception {
        try(var source=DataInputSourceTest.class.getResourceAsStream("/paiflow/pgsql-literal-rejection-golden.json")) {
            assertNotNull(source);var cases=JSON.parseObject(new String(source.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases");assertEquals(9,cases.size());
            for(Object raw:cases){var sample=(JSONObject)raw;assertThrows(ServiceException.class,()->PythonLiteralValue.parse(sample.getString("literal")),sample.getString("name"));}
        }
    }
    @Test void originalOmittedSearchOptionsKeepTheirDefaultsAndNullLimitRejects() throws Exception {
        try(var source=DataInputSourceTest.class.getResourceAsStream("/paiflow/pgsql-node-default-golden.json")) {
            assertNotNull(source);var cases=ModelMessageSources.sourceObject(new String(source.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases");assertEquals(2,cases.size());
            for(Object raw:cases){var sample=(JSONObject)raw;var f=bind(sample);assertEquals("COMPLETED",f.worker.start(f.run.getId(),f.identity,request(f,sample)).get("state"));
                var fixed=JSON.parseObject(f.run.getAssignmentJson());DataWorkerOperationService.find(fixed.getJSONObject("dsl"),sample.getJSONObject("nodeJson").getString("id")).getJSONObject("data").getJSONObject("nodeParam").put("limit",null);f.run.setAssignmentJson(fixed.toJSONString(JSONWriter.Feature.WriteMapNullValue));assertThrows(ServiceException.class,()->f.worker.start(f.run.getId(),f.identity,request(f,sample)));}
        }
    }
}

