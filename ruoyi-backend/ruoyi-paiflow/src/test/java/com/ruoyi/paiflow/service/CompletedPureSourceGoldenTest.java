package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.domain.*;
import com.ruoyi.paiflow.mapper.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际原Factory完成帧作为预期；不将隔离检查点/变量fixture称为真实业务。 */
class CompletedPureSourceGoldenTest {
    static JSONArray cases() throws Exception {try(var in=CompletedPureSourceGoldenTest.class.getResourceAsStream("/fixtures/pure-static-source-golden.json")){return JSON.parseObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("cases");}}
    @TestFactory Stream<DynamicTest> actualOriginalPureAndStaticQaProjection() throws Exception {return cases().stream().filter(raw->!((JSONObject)raw).getString("name").startsWith("variable-")).map(raw->{var c=(JSONObject)raw;return DynamicTest.dynamicTest(c.getString("name"),()->{
        var task=c.getJSONObject("assignment");var graph=task.getJSONObject("dsl");var events=c.getJSONArray("actualEvents");
        if(c.getString("name").startsWith("qa-"))verifyQa(c);else for(Object value:events){var event=(JSONObject)value;if(!"node.completed".equals(event.get("eventType"))||event.getString("nodeId").startsWith("node-start::"))continue;var node=DataWorkerOperationService.find(graph,event.getString("nodeId"));var expected=event.getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").getJSONObject("outputs");assertTrue(ToolSchema.same(expected,CompletedPureOutputs.project(node,WorkflowInputSources.values(graph,node,task.getJSONObject("input"),events))));}
    });});}
    private void verifyQa(JSONObject c){var task=ModelMessageSources.sourceObject(c.getJSONObject("assignment").toJSONString(JSONWriter.Feature.WriteMapNullValue));var graph=task.getJSONObject("dsl");var node=DataWorkerOperationService.find(graph,"question-answer::qa");var target=DataWorkerOperationService.find(graph,"node-end::e");var events=c.getJSONArray("actualEvents");var run=new RunRecord();run.setId(task.getString("runId"));run.setLeaseId(task.getString("leaseId"));run.setAttempt(task.getIntValue("attempt"));run.setLastEventSeq(events.stream().mapToLong(value->((JSONObject)value).getLongValue("seq")).max().orElse(0));run.setAssignmentJson(task.toJSONString(JSONWriter.Feature.WriteMapNullValue));var calls=c.getJSONArray("actualCheckpointCalls");var created=calls.getJSONArray(0).getJSONArray(0);var response=c.getJSONArray("actualConsumeResponses").getJSONObject(0);var cp=new CheckpointRecord();cp.setRunId(run.getId());cp.setLeaseId(run.getLeaseId());cp.setAttempt(run.getAttempt());cp.setNodeId(node.getString("id"));cp.setOrdinal(1);cp.setId(response.getString("checkpointId"));cp.setState(response.getString("state"));cp.setQuestionJson(created.getJSONObject(5).toJSONString());cp.setResumeDataJson(response.getJSONObject("resumeData").toJSONString());var mapper=mock(CheckpointMapper.class);when(mapper.byPosition(run.getId(),run.getAttempt(),node.getString("id"),null,1)).thenReturn(cp);var sources=new CompletedOutputSources(null,null,mapper);sources.verify(run,graph,target,events);for(Object value:events){var event=(JSONObject)value;if(node.get("id").equals(event.get("nodeId"))&&"node.completed".equals(event.get("eventType")))event.getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").getJSONObject("outputs").put("content","forged checkpoint reply");}assertEquals(409,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->sources.verify(run,graph,target,events)).getCode());}
    @Test void incompleteRunFactReturnsPolicyError(){var run=new RunRecord();assertEquals(403,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->new CompletedOutputSources(null,null,null).verify(run,new JSONObject(),new JSONObject(),new JSONArray())).getCode());}
}
