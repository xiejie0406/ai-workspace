package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.CheckpointRecord;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.service.RunJsonBoundary;
import com.ruoyi.paiflow.service.RunJsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyWorkflowStreamCodecTest {
    private JSONObject originalJava() throws Exception {
        try(var source=getClass().getResourceAsStream("/fixtures/legacy-java-original-node-frames.json")) {
            assertNotNull(source);return JSON.parseObject(new String(source.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private RunRecord javaRun(String id) {
        var run=run();run.setId(id);run.setEngineProfile("java");run.setLastEventSeq(12L);
        run.setAssignmentJson(JSONObject.of("dsl",JSONObject.of("nodes",JSONArray.of(JSONObject.of("id","node-start::1"),JSONObject.of("id","node-end::3"))),
            "input",new JSONObject(),"resourceSnapshots",new JSONObject()).toJSONString());return run;
    }
    @Test void actualJavaEmptyStartsAndStopsPreserveOriginalNodeFieldsAndIgnoreMapFacts() throws Exception {
        var run=javaRun("java-run");int projectedCount=0;
        for(Object raw:originalJava().getJSONArray("events")) {
            var event=(JSONObject)raw;if("plugin::2".equals(event.getString("nodeId")))continue;
            event.put("leaseId",run.getLeaseId());
            var frame=event.getJSONObject("payload").getJSONObject("legacyFrame");
            if(frame==null) {assertNull(LegacyWorkflowStreamCodec.project(run,event,null));continue;}
            String before=RunJsonBoundary.canonicalFull(frame);
            var projected=LegacyWorkflowStreamCodec.project(run,event,null);projectedCount++;
            assertEquals(run.getId(),projected.get("id"));assertEquals(frame.get("workflow_step"),projected.get("workflow_step"));
            assertEquals(frame.get("usage"),projected.get("usage"));assertTrue(projected.containsKey("event_data"));
            assertNull(projected.getJSONArray("choices").getJSONObject(0).get("finish_reason"));
            assertEquals(before,RunJsonBoundary.canonicalFull(frame));
        }
        assertEquals(4,projectedCount);
    }
    @Test void actualJavaFailureCodeSurvivesButRawFlowEndCannotReplaceCentreTerminal() throws Exception {
        var run=javaRun("java-run");var frames=originalJava().getJSONArray("failureFrames");
        var failure=frames.getJSONObject(4);failure.getJSONObject("workflow_step").getJSONObject("node").put("id","node-start::1");
        var event=event(run,"text.delta",failure);event.put("nodeId","node-start::1");
        var projected=LegacyWorkflowStreamCodec.project(run,event,null);assertEquals(1001,projected.getIntValue("code"));
        assertEquals(failure.get("message"),projected.get("message"));assertNull(projected.getJSONArray("choices").getJSONObject(0).get("finish_reason"));
        var stopped=frames.getJSONObject(5);event.getJSONObject("payload").put("legacyFrame",stopped);event.put("nodeId","flow_obj");
        assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,event,null));
        failure.getJSONObject("workflow_step").getJSONObject("node").put("finish_reason",null);event.put("nodeId","node-start::1");
        event.getJSONObject("payload").put("legacyFrame",failure);assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,event,null));
    }
    @Test void javaSidCompatibilityDoesNotRelaxOuterScopeNodeOrPythonIdentity() throws Exception {
        var run=javaRun("java-run");var raw=originalJava().getJSONArray("events").getJSONObject(6);
        raw.put("leaseId",run.getLeaseId());
        for(String field:new String[]{"runId","leaseId","attempt","nodeId","seq"}) {
            var changed=JSON.parseObject(RunJsonBoundary.canonicalFull(raw));
            changed.put(field,"attempt".equals(field)?2:"seq".equals(field)?13L:"wrong");
            assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,changed,null),field);
        }
        var frame=raw.getJSONObject("payload").getJSONObject("legacyFrame");
        for(Object sid: new Object[]{null,""," ",42,"bad\nsource","x".repeat(513)}) {
            frame.put("id",sid);assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,raw,null));
        }
        frame.put("id","original-java-sid");run.setEngineProfile("python");
        assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,raw,null));
        run.setEngineProfile("java");frame.getJSONObject("workflow_step").getJSONObject("node").put("id","node-end::3");
        assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,raw,null));
    }
    private static final String NODE="question-answer::fixture";
    private JSONObject golden(int index) throws Exception {
        return JSON.parseObject(Files.readString(Path.of("../../contracts/paiflow/fixtures/legacy-workflow-stream.json")))
            .getJSONArray("frames").getJSONObject(index).getJSONObject("frame");
    }
    private RunRecord run() {
        var value=new RunRecord();value.setId("fixture-run");value.setRunJsonCodec(RunJsonCodec.V2);
        value.setLeaseId("fixture-lease");value.setAttempt(1);value.setLastEventSeq(9L);value.setState("RUNNING");
        value.setAssignmentJson(JSON.toJSONString(JSONObject.of("dsl",JSONObject.of("nodes",JSONArray.of(JSONObject.of("id",NODE))))));
        return value;
    }
    private JSONObject event(RunRecord run,String type,JSONObject frame) {
        var value=JSONObject.of("schemaVersion","paiflow.events.v1","runId",run.getId(),"leaseId",run.getLeaseId(),
            "attempt",run.getAttempt(),"seq",1L,"eventType",type,"timestamp","2023-11-14T22:13:20Z","nodeId",NODE,
            "payload",JSONObject.of("legacyFrame",frame));
        // 中心正式终态不带节点作用域；节点stop仍保留nodeId由原帧投影。
        if(type.startsWith("run."))value.remove("nodeId");return value;
    }
    private CheckpointRecord checkpoint(JSONObject frame) {
        var value=new CheckpointRecord();var data=frame.getJSONObject("event_data");
        value.setId(data.getString("event_id"));value.setRunId("fixture-run");value.setLeaseId("fixture-lease");value.setAttempt(1);
        value.setNodeId(NODE);value.setNeedReply(data.getBooleanValue("need_reply")?1:0);value.setRevision(2L);
        value.setQuestionJson(JSON.toJSONString(data.getJSONObject("value"),JSONWriter.Feature.WriteMapNullValue));return value;
    }
    private JSONObject checkpointEvent(RunRecord run,JSONObject frame) {
        var value=event(run,"checkpoint",frame);value.getJSONObject("payload").put("checkpointId",frame.getJSONObject("event_data").getString("event_id"));
        value.getJSONObject("payload").put("revision",0L);return value;
    }
    @Test void originalStartedCompletedAndFailedFramesPreservePageContractAndOpaqueNull() throws Exception {
        for(int index:new int[]{0,3,4}) {
            var frame=golden(index);String before=JSON.toJSONString(frame,JSONWriter.Feature.WriteMapNullValue);
            var value=event(run(),index==0?"node.started":index==3?"node.completed":"node.failed",frame);
            var projected=LegacyWorkflowStreamCodec.project(run(),value,null);
            assertEquals(frame,projected);
            assertTrue(projected.getJSONArray("choices").getJSONObject(0).containsKey("finish_reason"));
            assertNull(projected.getJSONArray("choices").getJSONObject(0).get("finish_reason"));
            assertEquals(before,JSON.toJSONString(frame,JSONWriter.Feature.WriteMapNullValue));
            if(index==3)assertTrue(projected.getJSONObject("workflow_step").getJSONObject("node").getJSONObject("outputs").containsKey("opaqueNull"));
        }
    }
    @Test void historicalEncodingRestoresOnlyDeclaredNullableStateFields() throws Exception {
        var frame=JSON.parseObject(RunJsonBoundary.canonical(golden(0)));
        var projected=LegacyWorkflowStreamCodec.project(run(),event(run(),"node.started",frame),null);
        assertTrue(projected.getJSONArray("choices").getJSONObject(0).containsKey("finish_reason"));
        assertNull(projected.getJSONObject("workflow_step").getJSONObject("node").get("finish_reason"));
        assertTrue(projected.getJSONObject("workflow_step").getJSONObject("node").containsKey("finish_reason"));
        assertFalse(frame.getJSONArray("choices").getJSONObject(0).containsKey("finish_reason"));
        assertFalse(projected.getJSONObject("workflow_step").getJSONObject("node").containsKey("usage"));
    }
    @Test void workerChoiceStopDoesNotCompleteTheRun() throws Exception {
        var frame=golden(3);frame.getJSONArray("choices").getJSONObject(0).put("finish_reason","stop");
        var projected=LegacyWorkflowStreamCodec.project(run(),event(run(),"node.completed",frame),null);
        assertNull(projected.getJSONArray("choices").getJSONObject(0).get("finish_reason"));
        assertEquals("stop",frame.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
    }
    @Test void optionAndDirectInterruptAreBoundToActualCheckpointAndKeepDefaultOrder() throws Exception {
        for(int index:new int[]{1,2}) {
            var frame=golden(index);var record=checkpoint(frame);var value=checkpointEvent(run(),frame);
            assertEquals(frame,LegacyWorkflowStreamCodec.project(run(),value,record));
            assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),value,null)).getCode());
        }
    }
    @Test void staleCheckpointRevisionReplaysButForgedFutureRevisionAndDifferentQuestionDoNot() throws Exception {
        var frame=golden(1);var record=checkpoint(frame);var value=checkpointEvent(run(),frame);
        assertDoesNotThrow(()->LegacyWorkflowStreamCodec.project(run(),value,record));
        value.getJSONObject("payload").put("revision",3L);
        assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),value,record));
        value.getJSONObject("payload").put("revision",0L);frame.getJSONObject("event_data").getJSONObject("value").put("content","forged");
        assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),value,record));
    }
    @Test void wrongRunLeaseAttemptNodeAndSidCannotProduceOriginalPageFrames() throws Exception {
        for(String field:new String[]{"runId","leaseId","attempt","nodeId"}) {
            var value=event(run(),"node.started",golden(0));value.put(field,"attempt".equals(field)?2:"forged");
            assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),value,null),field);
        }
        var frame=golden(0);frame.put("id","forged");
        assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),event(run(),"node.started",frame),null));
        var wrongNodeFrame=golden(0);wrongNodeFrame.getJSONObject("workflow_step").getJSONObject("node").put("id","node-end::forged");
        var value=event(run(),"node.started",wrongNodeFrame);
        assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),value,null));
    }
    @Test void missingFailureCodeCannotTurnIntoSuccessfulNodeFrame() throws Exception {
        var frame=golden(4);frame.remove("code");
        assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),event(run(),"node.failed",frame),null));
    }
    @Test void nonFailureEventsCannotHijackOriginalAuditBranchWithDifferentStatusCode() throws Exception {
        for(int code:new int[]{21103,22101}) {
            for(String type:new String[]{"node.started","text.delta"}) {
                var frame=golden(0);frame.put("code",code);var value=event(run(),type,frame);
                assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),value,null));
            }
            var frame=golden(1);var record=checkpoint(frame);frame.put("code",code);var value=checkpointEvent(run(),frame);
            assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),value,record));
        }
    }
    @Test void auxiliaryReasoningAndUsageEventsDoNotDuplicateOriginalFrame() {
        assertNull(LegacyWorkflowStreamCodec.project(run(),event(run(),"reasoning.delta",null),null));
        assertNull(LegacyWorkflowStreamCodec.project(run(),event(run(),"usage",null),null));
    }
    @Test void wholeFixedGraphRejectsUnadmittedMixedNodesEvenWhenTopLevelWorkerOmitsIterationId() throws Exception {
        for(String type:new String[]{"iteration","iteration-node-start","iteration-node-end","spark-llm"}) {
            var run=run();var assignment=JSON.parseObject(run.getAssignmentJson());
            assignment.getJSONObject("dsl").getJSONArray("nodes").add(JSONObject.of("id",type+"::fixture"));
            run.setAssignmentJson(assignment.toJSONString());
            // 顶层容器不会带 iterationId，不能用 Worker 信封的缺省值判定能力。
            var value=event(run,"node.started",golden(0));
            assertFalse(value.containsKey("iterationId"));
            // 本夹具含QA，与迭代混合仍未准入；真实纯迭代输入门另有正反检查。
            assertEquals(503,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,value,null)).getCode(),type);
        }
        // 变量已迁入顶层页流能力；此处只核许可后投影，不把合成图当作变量联机证据。
        var variableRun=run();var assignment=JSON.parseObject(variableRun.getAssignmentJson());
        assignment.getJSONObject("dsl").getJSONArray("nodes").add(JSONObject.of("id","node-variable::fixture",
            "data",JSONObject.of("nodeParam",JSONObject.of("method","get"))));
        variableRun.setAssignmentJson(assignment.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        var original=golden(0);String before=original.toJSONString(JSONWriter.Feature.WriteMapNullValue);
        var legal=event(variableRun,"node.started",original);
        assertEquals(before,LegacyWorkflowStreamCodec.project(variableRun,legal,null).toJSONString(JSONWriter.Feature.WriteMapNullValue));
        assertEquals(before,original.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        var wrongScope=event(variableRun,"node.started",golden(0));wrongScope.put("iterationId","iteration::other:0");
        assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(variableRun,wrongScope,null)).getCode());
        var missingNode=event(variableRun,"node.started",golden(0));missingNode.put("nodeId","node-variable::not-in-fixed-graph");
        assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(variableRun,missingNode,null)).getCode());
    }
    private RunRecord iterationRun() throws Exception {
        var baseline=JSON.parseObject(Files.readString(Path.of("../../contracts/paiflow/fixtures/pure-iteration.json"))).getJSONObject("baseline");
        var run=run();run.setAssignmentJson(JSONObject.of("dsl",baseline.getJSONObject("dsl"),"input",baseline.getJSONObject("input"),
            "resourceSnapshots",new JSONObject()).toJSONString(JSONWriter.Feature.WriteMapNullValue));return run;
    }
    @Test void pinnedTopLevelModelFramesPreserveOriginalBodyAndRejectMissingChangedOrUnusedResources() throws Exception {
        for(String type:new String[]{"spark-llm","extractor-parameter","decision-making","question-answer"}) {
            String nodeId=type+"::model";String resourceId="model:7:version:2";
            var params=JSONObject.of("modelResourceId",resourceId,"source","xinghuo","domain","generalv3.5","answerType","direct","directAnswer",JSONObject.of("handleResponse",true));
            var descriptor=JSONObject.of("kind","model","provider","xinghuo","version","2","modelName","generalv3.5","enabled",true,"capabilities",JSONArray.of("chat","function_call"));
            var execution=JSONObject.of("nodes",JSONArray.of(JSONObject.of("id",nodeId,"data",JSONObject.of("nodeParam",params))));
            var resources=JSONObject.of(resourceId,descriptor);var run=run();run.setAssignmentJson(JSONObject.of("dsl",execution,"resourceSnapshots",resources).toJSONString(JSONWriter.Feature.WriteMapNullValue));
            var frame=golden(3);frame.getJSONObject("workflow_step").getJSONObject("node").put("id",nodeId);
            var value=event(run,"node.completed",frame);value.put("nodeId",nodeId);String before=frame.toJSONString(JSONWriter.Feature.WriteMapNullValue);
            assertEquals(before,LegacyWorkflowStreamCodec.project(run,value,null).toJSONString(JSONWriter.Feature.WriteMapNullValue));assertEquals(before,frame.toJSONString(JSONWriter.Feature.WriteMapNullValue));
            resources.clear();assertEquals(503,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(execution,resources)).getCode());
            resources.put(resourceId,descriptor);params.put("domain","forged");assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(execution,resources)).getCode());
            params.put("domain","generalv3.5");resources.put("model:8:version:2",descriptor);assertEquals(503,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(execution,resources)).getCode());
        }
    }
    private JSONObject iterationEvent(RunRecord run,String nodeId,String iterationId) throws Exception {
        var frame=golden(0);frame.getJSONObject("workflow_step").getJSONObject("node").put("id",nodeId);
        var value=event(run,"node.started",frame);value.put("nodeId",nodeId);
        if(iterationId!=null)value.put("iterationId",iterationId);return value;
    }
    @Test void pureIterationProjectionUsesActualOwnerAndFixedInputWithoutChangingRawNodeFields() throws Exception {
        var run=iterationRun();var value=iterationEvent(run,"text-joiner::j","iteration::i:2");
        var raw=value.getJSONObject("payload").getJSONObject("legacyFrame");
        var outputs=JSONObject.of("opaqueNull",null,"zero",0,"disabled",false);
        raw.getJSONObject("workflow_step").getJSONObject("node").put("outputs",outputs);
        String before=raw.toJSONString(JSONWriter.Feature.WriteMapNullValue);
        var projected=LegacyWorkflowStreamCodec.project(run,value,null);
        assertEquals(raw,projected);assertEquals(before,raw.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        var actual=projected.getJSONObject("workflow_step").getJSONObject("node").getJSONObject("outputs");
        assertTrue(actual.containsKey("opaqueNull"));assertNull(actual.get("opaqueNull"));assertEquals(0,actual.get("zero"));assertEquals(false,actual.get("disabled"));
        assertDoesNotThrow(()->LegacyWorkflowStreamCodec.project(run,iterationEvent(run,"iteration::i",null),null));
    }
    @Test void iterationProjectionRejectsUnboundWrongOwnerAndOutOfInputTags() throws Exception {
        var run=iterationRun();
        for(String tag:new String[]{null,"","iteration::i:3","iteration::i:00","iteration::other:0","iteration::i:0/nested:0"}) {
            var value=iterationEvent(run,"text-joiner::j",tag);
            assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,value,null)).getCode());
        }
        assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,iterationEvent(run,"iteration::i","iteration::i:0"),null)).getCode());
        var assignment=JSON.parseObject(run.getAssignmentJson());assignment.getJSONObject("input").put("items",new JSONArray());run.setAssignmentJson(assignment.toJSONString());
        assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,iterationEvent(run,"text-joiner::j","iteration::i:0"),null)).getCode());
    }
    @Test void inputAwareGateReusesPurePolicyWhileOldEntryAndMixedResourceGraphsStayConservative() throws Exception {
        var assignment=JSON.parseObject(iterationRun().getAssignmentJson());var graph=assignment.getJSONObject("dsl");var input=assignment.getJSONObject("input");
        assertDoesNotThrow(()->LegacyWorkflowStreamCodec.requireSupportedExecution(graph,new JSONObject(),input));
        assertEquals(503,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(graph,new JSONObject())).getCode());
        assertEquals(400,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(graph,new JSONObject(),null)).getCode());
        assertEquals(503,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(graph,JSONObject.of("resource",true),input)).getCode());
        for(String type:new String[]{"question-answer","node-variable","spark-llm"}) {
            var mixed=JSON.parseObject(graph.toJSONString().replace("text-joiner::j",type+"::mixed"));
            assertEquals(503,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(mixed,new JSONObject(),input)).getCode());
        }
    }
    @Test void storedStringFrameAndForgedAuxiliaryScopeFailClosedBeforeProjectionOrFiltering() throws Exception {
        var run=iterationRun();var value=iterationEvent(run,"text-joiner::j","iteration::i:0");
        var payload=value.getJSONObject("payload");payload.put("legacyFrame",payload.getJSONObject("legacyFrame").toJSONString(JSONWriter.Feature.WriteMapNullValue));
        assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,value,null)).getCode());
        for(String type:new String[]{"usage","reasoning.delta"}) {
            var auxiliary=iterationEvent(run,"text-joiner::j","iteration::other:0");auxiliary.put("eventType",type);
            assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,auxiliary,null)).getCode());
            auxiliary.put("iterationId","iteration::i:0");assertNull(LegacyWorkflowStreamCodec.project(run,auxiliary,null));
        }
    }
    @Test void centreTerminalCannotCarryForgedNodeOrIterationMetadata() {
        var run=run();run.setState("COMPLETED");run.setResultJson("{\"state\":\"COMPLETED\"}");
        for(String field:new String[]{"nodeId","iterationId"}) {
            var value=event(run,"run.completed",null);value.put("seq",9L);value.put("payload",JSONObject.of("state","COMPLETED"));
            value.put(field,"nodeId".equals(field)?NODE:"wrong-owner:0");
            assertEquals(409,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,value,null)).getCode());
        }
    }
    @Test void onlyPersistedCentreTerminalCanStopPageAndCarriesActualUsageAndElapsedTime() {
        var run=run();run.setState("COMPLETED");run.setExecutionStartedAt(LocalDateTime.of(2023,11,14,22,13,19,250_000_000));
        run.setResultJson("{\"state\":\"COMPLETED\",\"output\":{\"usage\":{\"total_tokens\":5}}}");
        var value=event(run,"run.completed",null);value.put("seq",9L);value.put("payload",JSONObject.of("state","COMPLETED"));
        var frame=LegacyWorkflowStreamCodec.project(run,value,null);
        assertEquals("stop",frame.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
        assertEquals(0,frame.getIntValue("code"));assertEquals(5,frame.getJSONObject("usage").getIntValue("total_tokens"));
        assertEquals(0.75,frame.getDoubleValue("executedTime"));
        value.put("seq",8L);assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,value,null));
        value.put("seq",9L);run.setState("RUNNING");assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,value,null));
    }
    @Test void failedCancelledAndUnknownEffectAreNonSuccessWithoutInventedUsage() {
        for(String state:new String[]{"FAILED","CANCELLED","EFFECT_UNKNOWN"}) {
            var run=run();run.setState(state);run.setResultJson("{\"state\":\""+state+"\",\"error\":{\"code\":\"ENGINE_FAILED\",\"message\":\"fixture safe failure\"}}");
            var value=event(run,"CANCELLED".equals(state)?"run.cancelled":"run.failed",null);value.put("seq",9L);value.put("payload",JSONObject.of("state",state));
            var frame=LegacyWorkflowStreamCodec.project(run,value,null);
            assertNotEquals(0,frame.getIntValue("code"));assertEquals("fixture safe failure",frame.getString("message"));
            assertFalse(frame.containsKey("usage"));assertFalse(frame.containsKey("executedTime"));
        }
    }
    @Test void actualJavaProjectionMatrixIsSavedForTheUnchangedOriginalConsumer() throws Exception {
        JSONArray frames=new JSONArray();
        for(int index=0;index<=4;index++) {
            var original=golden(index);var run=run();
            var record=(index==1 || index==2)?checkpoint(original):null;
            var value=record==null?event(run,index==0?"node.started":index==3?"node.completed":"node.failed",original):checkpointEvent(run,original);
            frames.add(JSONObject.of("name","golden-"+index,"frame",LegacyWorkflowStreamCodec.project(run,value,record)));
        }
        for(String state:new String[]{"COMPLETED","FAILED","CANCELLED"}) {
            var run=run();run.setState(state);run.setExecutionStartedAt(LocalDateTime.of(2023,11,14,22,13,19,250_000_000));
            run.setResultJson(JSON.toJSONString(JSONObject.of("state",state,"output",JSONObject.of("usage",JSONObject.of("total_tokens",5)),
                "error",JSONObject.of("message","fixture safe terminal"))));
            var value=event(run,"COMPLETED".equals(state)?"run.completed":"CANCELLED".equals(state)?"run.cancelled":"run.failed",null);
            value.put("seq",9L);value.put("payload",JSONObject.of("state",state));
            frames.add(JSONObject.of("name","center-"+state,"frame",LegacyWorkflowStreamCodec.project(run,value,null)));
        }
        assertEquals(8,frames.size());
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/legacy-workflow-projected-frames.json"),JSON.toJSONString(JSONObject.of(
            "scope","实际Java纯投影的隔离固定Run/Checkpoint fixture，非正式记录、非页面验收","frames",frames),JSONWriter.Feature.WriteMapNullValue));
        var malformed=golden(7);
        assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run(),event(run(),"node.started",malformed),null));
    }
}
