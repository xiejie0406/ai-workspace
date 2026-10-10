package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.domain.*;
import com.ruoyi.paiflow.mapper.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原Factory实际分支结果为预期；仅中心来源端口隔离，不声称真实Provider。 */
class BranchDefaultSourceTest {
    static JSONArray cases() throws Exception {try(var in=BranchDefaultSourceTest.class.getResourceAsStream("/fixtures/branch-default-source-golden.json")){return ModelMessageSources.sourceObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("cases");}}
    record Fixture(JSONObject task,JSONObject graph,JSONObject target,JSONArray events,RunRecord run,CompletedOutputSources sources) {}
    Fixture fixture(JSONObject raw){var c=ModelMessageSources.sourceObject(raw.toJSONString(JSONWriter.Feature.WriteMapNullValue));var task=c.getJSONObject("assignment");var graph=task.getJSONObject("dsl");var target=DataWorkerOperationService.find(graph,"node-end::e");var events=c.getJSONArray("actualEvents");
        if(c.getString("name").startsWith("decision-")){c.put("nodeJson",DataWorkerOperationService.find(graph,"decision-making::model"));var f=new CompletedOutputSourcesTest().fixture(c);return new Fixture(f.task(),f.task().getJSONObject("dsl"),f.target(),f.events(),f.run(),f.sources());}
        var run=new RunRecord();run.setId(task.getString("runId"));run.setLeaseId(task.getString("leaseId"));run.setAttempt(task.getIntValue("attempt"));run.setLastEventSeq(events.stream().mapToLong(value->((JSONObject)value).getLongValue("seq")).max().orElse(0));run.setAssignmentJson(task.toJSONString(JSONWriter.Feature.WriteMapNullValue));var checkpoints=mock(CheckpointMapper.class);
        if(c.getString("name").startsWith("qa-")){var created=c.getJSONArray("actualCheckpointCalls").getJSONArray(0).getJSONArray(0);var response=c.getJSONArray("actualConsumeResponses").getJSONObject(0);var cp=new CheckpointRecord();cp.setRunId(run.getId());cp.setLeaseId(run.getLeaseId());cp.setAttempt(run.getAttempt());cp.setNodeId("question-answer::qa");cp.setId(response.getString("checkpointId"));cp.setOrdinal(1);cp.setState(response.getString("state"));cp.setQuestionJson(created.getJSONObject(5).toJSONString(JSONWriter.Feature.WriteMapNullValue));cp.setResumeDataJson(response.getJSONObject("resumeData").toJSONString(JSONWriter.Feature.WriteMapNullValue));when(checkpoints.byPosition(run.getId(),run.getAttempt(),cp.getNodeId(),null,1)).thenReturn(cp);}
        return new Fixture(task,graph,target,events,run,new CompletedOutputSources(null,null,checkpoints));
    }
    static JSONObject output(JSONObject event){return event.getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").getJSONObject("outputs");}
    static JSONObject event(Fixture f,String node){return f.events.stream().map(raw->(JSONObject)raw).filter(e->node.equals(e.get("nodeId"))&&"node.completed".equals(e.get("eventType"))).findFirst().orElseThrow();}
    @TestFactory Stream<DynamicTest> originalBranchesAndMergedDefaults() throws Exception {return cases().stream().map(raw->{var c=(JSONObject)raw;return DynamicTest.dynamicTest(c.getString("name"),()->{var f=fixture(c);assertTrue(ToolSchema.same(output(event(f,"node-end::e")),new JSONObject(f.sources.values(f.run,f.graph,f.target,f.events))));});});}
    @Test void missingControlDoesNotProveSkippedDefault() throws Exception {var f=fixture(cases().getJSONObject(0));f.events.removeIf(raw->"if-else::c".equals(((JSONObject)raw).get("nodeId")));assertEquals(403,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->f.sources.values(f.run,f.graph,f.target,f.events)).getCode());}
    @Test void forgedControlCannotAuthorizeBranch() throws Exception {var f=fixture(cases().getJSONObject(0));output(event(f,"if-else::c")).put("res",false);assertEquals(409,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->f.sources.values(f.run,f.graph,f.target,f.events)).getCode());}
    @Test void activeSourceMissingCannotBecomeDefault() throws Exception {var f=fixture(cases().getJSONObject(0));f.events.removeIf(raw->"text-joiner::yes".equals(((JSONObject)raw).get("nodeId")));assertEquals(403,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->f.sources.values(f.run,f.graph,f.target,f.events)).getCode());}
    @Test void forgedSkippedCompletionCannotReplaceDefault() throws Exception {var f=fixture(cases().getJSONObject(0));var fake=ModelMessageSources.sourceObject(event(f,"text-joiner::yes").toJSONString(JSONWriter.Feature.WriteMapNullValue));fake.put("nodeId","text-joiner::no");fake.getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").put("id","text-joiner::no");fake.put("seq",f.run.getLastEventSeq()+1);f.run.setLastEventSeq(f.run.getLastEventSeq()+1);f.events.add(fake);assertEquals(403,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->f.sources.values(f.run,f.graph,f.target,f.events)).getCode());}
    @Test void skippedConsumerCannotReceiveDispatchPermission() throws Exception {var f=fixture(cases().getJSONObject(0));var skipped=DataWorkerOperationService.find(f.graph,"text-joiner::no");assertEquals(403,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->f.sources.values(f.run,f.graph,skipped,f.events)).getCode());}
    @Test void laterControlCannotProveEarlierCompletion() throws Exception {var f=fixture(cases().getJSONObject(0));event(f,"if-else::c").put("seq",f.run.getLastEventSeq());assertEquals(403,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->f.sources.values(f.run,f.graph,f.target,f.events)).getCode());}
    /** 中心接受事件水位的并行读取反例；不冒充原Factory必然按此顺序调度。 */
    @TestFactory Stream<DynamicTest> unfinishedActiveParallelPredecessorIsRejectedInBothEdgeOrders(){return Stream.of(true,false).map(lateFirst->DynamicTest.dynamicTest("parallel-control-edge-order-"+lateFirst,()->{
        var f=fixture(cases().getJSONObject(0));var original=DataWorkerOperationService.find(f.graph,"if-else::c");var parallel=ModelMessageSources.sourceObject(original.toJSONString(JSONWriter.Feature.WriteMapNullValue));parallel.put("id","if-else::parallel");f.graph.getJSONArray("nodes").add(parallel);
        var edges=f.graph.getJSONArray("edges");edges.add(JSONObject.of("sourceNodeId","node-start::s","targetNodeId",parallel.get("id")));
        var extra=JSONObject.of("sourceNodeId",parallel.get("id"),"targetNodeId","text-joiner::yes","sourceHandle","branch_one_of::yes");if(lateFirst)edges.add(0,extra);else edges.add(extra);edges.add(JSONObject.of("sourceNodeId",parallel.get("id"),"targetNodeId","text-joiner::yes","sourceHandle","branch_one_of::default"));
        var completed=ModelMessageSources.sourceObject(event(f,"if-else::c").toJSONString(JSONWriter.Feature.WriteMapNullValue));completed.put("nodeId",parallel.get("id"));completed.getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").put("id",parallel.get("id"));completed.put("seq",f.run.getLastEventSeq()+1);f.run.setLastEventSeq(f.run.getLastEventSeq()+1);f.events.add(completed);f.run.setAssignmentJson(f.task.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        assertEquals(403,assertThrows(com.ruoyi.common.exception.ServiceException.class,()->f.sources.values(f.run,f.graph,f.target,f.events)).getCode());
    }));}
}
