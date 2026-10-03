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
        return JSONObject.of("schemaVersion","paiflow.events.v1","runId",run.getId(),"leaseId",run.getLeaseId(),
            "attempt",run.getAttempt(),"seq",1L,"eventType",type,"timestamp","2023-11-14T22:13:20Z","nodeId",NODE,
            "payload",JSONObject.of("legacyFrame",frame));
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
    @Test void wholeFixedGraphRejectsIterationEvenWhenTopLevelWorkerOmitsIterationId() throws Exception {
        for(String type:new String[]{"iteration","iteration-node-start","iteration-node-end","spark-llm","node-variable"}) {
            var run=run();var assignment=JSON.parseObject(run.getAssignmentJson());
            assignment.getJSONObject("dsl").getJSONArray("nodes").add(JSONObject.of("id",type+"::fixture"));
            run.setAssignmentJson(assignment.toJSONString());
            // 顶层容器不会带 iterationId，不能用 Worker 信封的缺省值判定能力。
            var value=event(run,"node.started",golden(0));
            assertFalse(value.containsKey("iterationId"));
            assertEquals(503,assertThrows(ServiceException.class,()->LegacyWorkflowStreamCodec.project(run,value,null)).getCode(),type);
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
