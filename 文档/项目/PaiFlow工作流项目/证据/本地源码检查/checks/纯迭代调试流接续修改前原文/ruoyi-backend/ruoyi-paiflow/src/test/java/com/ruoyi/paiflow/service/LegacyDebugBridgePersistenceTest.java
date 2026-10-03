package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.adapter.legacy.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真正的构建→固定版本→Run→租约→Checkpoint→正式结束，全部限随机H2。 */
class LegacyDebugBridgePersistenceTest extends WorkflowDebugTestFixture {
    LegacyDebugBridgeService bridge;
    JSONObject body;
    LegacyDebugBridgeService.Attachment attachment;
    @BeforeEach void setupBridge(){bridge=proxy(new LegacyDebugBridgeService(workflows,defaults,access,runs,runMapper,checkpoints,checkpointMapper));}
    JSONObject chatBody(JSONObject built){return JSONObject.of("flow_id",built.get("flowId"),"inputs",JSONObject.of("question","本地实际题面","opaqueNull",null),"chatId","original_chat","regen",false,"debugReceipt",receipt(built),"requestKey","original_run_key");}
    void start(){var graph=canvas();body=chatBody(ordinary(createAgent(graph),graph,"original_build_key"));attachment=bridge.chat(body,Long.toString(space),null);}
    JSONObject claim(){var task=leases.claim(worker);assertEquals(attachment.runId(),task.get("runId"));assertTrue(leases.beginExecution(attachment.runId(),worker,task.getString("leaseId"),task.getIntValue("attempt")).getBooleanValue("begun"));return task;}
    JSONObject publishedCheckpoint(JSONObject task){var cp=checkpoint(task);var row=checkpointMapper.find(cp.getString("checkpointId"));var frame=JSONObject.of("code",0,"message","","id",task.get("runId"),"choices",JSONArray.of(JSONObject.of("delta",new JSONObject(),"finish_reason","interrupt")),"workflow_step",JSONObject.of("node",JSONObject.of("id",row.getNodeId(),"finish_reason","interrupt")),"event_data",JSONObject.of("event_id",row.getId(),"event_type","interrupt","need_reply",row.getNeedReply()==1,"value",JSON.parseObject(row.getQuestionJson())));var event=event(task,JSONObject.of("checkpointId",row.getId(),"revision",row.getRevision(),"legacyFrame",frame));event.put("eventType","checkpoint");event.put("seq",runMapper.find(attachment.runId()).getLastEventSeq()+1);leases.append(attachment.runId(),worker,event);return cp;}
    JSONObject replyBody(JSONObject cp,String type){return JSONObject.of("flow_id",body.get("flow_id"),"runId",attachment.runId(),"debugReceipt",body.get("debugReceipt"),"eventId",cp.get("checkpointId"),"eventType",type,"expectedRevision",cp.get("revision").toString(),"replyKey","original_reply_key");}
    JSONObject stopBody(){return JSONObject.of("flow_id",body.get("flow_id"),"runId",attachment.runId(),"debugReceipt",body.get("debugReceipt"));}
    JSONObject nodeFrame(JSONObject task,String finish){return JSONObject.of("code",0,"message","","sid",task.get("runId"),"id",task.get("runId"),"choices",JSONArray.of(JSONObject.of("delta",JSONObject.of("content","实际回答"),"finish_reason",finish)),"workflow_step",JSONObject.of("node",JSONObject.of("id","question-answer::q","finish_reason","stop","outputs",JSONObject.of("content","实际回答","opaqueNull",null))));}
    @Test void sameChatIntentReturnsExactRunAndChangedInputOrDifferentReceiptCannotReuseIt() throws Exception {
        start();assertEquals(attachment,bridge.chat(copy(body),Long.toString(space),null));assertEquals(1,rows("paiflow_run"));var changed=copy(body);changed.getJSONObject("inputs").put("question","changed");assertEquals(409,denied(()->bridge.chat(changed,Long.toString(space),null)));
        var graph=canvas();var other=chatBody(ordinary(createAgent(graph),graph,"other_build_key"));assertEquals(409,denied(()->bridge.chat(other,Long.toString(space),null)));assertEquals(1,rows("paiflow_run"));
    }
    @Test void invalidCursorRollsBackNewRunInsteadOfLeavingAnUnobservedIntent() throws Exception {
        var graph=canvas();body=chatBody(ordinary(createAgent(graph),graph,"invalid_cursor_build"));assertEquals(400,denied(()->bridge.chat(body,Long.toString(space),"other-run:0")));assertEquals(0,rows("paiflow_run"));assertEquals(0,rows("paiflow_run_event"));
    }
    @Test void receiptFieldsScopeAndCallerAreCheckedBeforeRunInsertion() throws Exception {
        var graph=canvas();body=chatBody(ordinary(createAgent(graph),graph,"receipt_scope_build"));var forged=copy(body);forged.getJSONObject("debugReceipt").put("dslHash","0".repeat(64));assertEquals(409,denied(()->bridge.chat(forged,Long.toString(space),null)));assertEquals(404,denied(()->bridge.chat(body,Long.toString(space+100),null)));loginAs(43);assertTrue(Set.of(403,404).contains(denied(()->bridge.chat(body,Long.toString(space),null))));assertEquals(0,rows("paiflow_run"));
    }
    @Test void comparisonNeedsItsExactBuildUuidAndDoesNotBecomeOrdinaryOrLatest() throws Exception {
        var graph=canvas();var agent=createAgent(graph);var uuid=UUID.randomUUID().toString();var built=workflows.debugBuild(agent.getLongValue("workflowId"),space,0L,buildBody(agent,graph),"comparison",uuid,"comparison_build_key");body=chatBody(built);assertEquals(409,denied(()->bridge.chat(body,Long.toString(space),null)));body.put("version",uuid);body.put("promptDebugger",true);attachment=bridge.chat(body,Long.toString(space),null);assertEquals(receipt(built).getLongValue("workflowVersionId"),attachment.workflowVersionId());assertEquals(0,workflowMapper.find(agent.getLongValue("workflowId")).getRevision());var changed=copy(body);changed.put("version",UUID.randomUUID().toString());assertEquals(409,denied(()->bridge.chat(changed,Long.toString(space),null)));assertEquals(1,rows("paiflow_run"));
    }
    @Test void jsonObjectHttpBindingPreservesMapNestedNullAndRejectsStringOrArrayObjects() throws Exception {
        var graph=canvas();body=chatBody(ordinary(createAgent(graph),graph,"http_map_build_key"));var mvc=MockMvcBuilders.standaloneSetup(new BindingController(bridge,space)).build();mvc.perform(post("/binding").contentType("application/json").content(body.toJSONString(JSONWriter.Feature.WriteMapNullValue))).andExpect(status().isOk());assertEquals(1,rows("paiflow_run"));var run=runMapper.byRequest(42,"original_run_key");var input=JSON.parseObject(run.getAssignmentJson()).getJSONObject("input");assertTrue(input.containsKey("opaqueNull"));assertNull(input.get("opaqueNull"));
        for(Object malformed:List.of("{}",new JSONArray())){var changed=copy(body);changed.put("inputs",malformed);assertEquals(400,denied(()->bridge.chat(changed,Long.toString(space),null)));var invalid=copy(body);invalid.put("debugReceipt",malformed);assertEquals(400,denied(()->bridge.chat(invalid,Long.toString(space),null)));}assertEquals(1,rows("paiflow_run"));
    }
    @RestController static class BindingController {
        final LegacyDebugBridgeService bridge;final long space;
        BindingController(LegacyDebugBridgeService bridge,long space){this.bridge=bridge;this.space=space;}
        @PostMapping("/binding") public LegacyDebugBridgeService.Attachment bind(@RequestBody JSONObject body){assertInstanceOf(Map.class,body.get("inputs"));return bridge.chat(body,Long.toString(space),null);}
    }
    @Test void actualCheckpointEndsOnlyThisReadAndCarriesFixedReplyRevisionAndDatabaseSequence() throws Exception {
        start();var task=claim();var cp=publishedCheckpoint(task);var page=bridge.page(attachment,0);assertTrue(page.waiting());assertFalse(page.terminal());assertEquals("RUNNING",page.state());assertEquals(1,page.frames().size());var frame=page.frames().get(0);assertEquals(attachment.runId()+":"+page.scannedThrough(),frame.id());assertEquals("0",frame.data().getJSONObject("event_data").get("checkpoint_revision"));assertEquals("WAITING",frame.data().getJSONObject("event_data").get("checkpoint_state"));assertEquals(cp.get("checkpointId"),frame.data().getJSONObject("event_data").get("event_id"));assertEquals("RUNNING",runMapper.find(attachment.runId()).getState());
    }
    @Test void nodeStopAndAuxiliaryEventsCannotProduceSuccessfulFlowBeforeActualResult() throws Exception {
        start();var task=claim();var event=event(task,JSONObject.of("legacyFrame",nodeFrame(task,"stop")));leases.append(attachment.runId(),worker,event);var auxiliary=event(task,new JSONObject());auxiliary.put("seq",2L);auxiliary.put("eventType","usage");leases.append(attachment.runId(),worker,auxiliary);var page=bridge.page(attachment,0);assertFalse(page.terminal());assertFalse(page.waiting());assertEquals(2,page.scannedThrough());assertEquals(1,page.frames().size());assertNull(page.frames().get(0).data().getJSONArray("choices").getJSONObject(0).get("finish_reason"));leases.result(attachment.runId(),worker,result(task,"COMPLETED",JSONObject.of("answer","实际回答")));var end=bridge.page(attachment,page.scannedThrough());assertTrue(end.terminal());assertEquals("COMPLETED",end.state());assertEquals(0,end.frames().get(0).data().getIntValue("code"));assertEquals("stop",end.frames().get(0).data().getJSONArray("choices").getJSONObject(0).get("finish_reason"));
    }
    @Test void answerUsesFirstRevisionAndSameReplyKeyReplaysWithoutSecondWriteOrNewRun() throws Exception {
        start();var task=claim();var cp=publishedCheckpoint(task);var reply=replyBody(cp,"resume");reply.put("content","实际回答");var first=bridge.resume(reply,Long.toString(space),attachment.runId()+":0");assertEquals("RESUMED",first.checkpoint().get("state"));assertEquals(attachment.runId(),first.attachment().runId());long revision=checkpointMapper.find(cp.getString("checkpointId")).getRevision();var replay=bridge.resume(copy(reply),Long.toString(space),attachment.runId()+":0");assertEquals(first,replay);assertEquals(revision,checkpointMapper.find(cp.getString("checkpointId")).getRevision());assertEquals(1,rows("paiflow_run"));var changed=copy(reply);changed.put("content","changed");assertEquals(409,denied(()->bridge.resume(changed,Long.toString(space),null)));
    }
    @Test void onlyMissingContentAbortGetsEmptyStringAndReplyAcknowledgementIsNotTerminal() throws Exception {
        start();var task=claim();var cp=publishedCheckpoint(task);var resume=replyBody(cp,"resume");assertEquals(400,denied(()->bridge.resume(resume,Long.toString(space),null)));var aborted=bridge.resume(replyBody(cp,"abort"),Long.toString(space),null);assertEquals("RESUMED",aborted.checkpoint().get("state"));assertEquals(Boolean.FALSE,aborted.checkpoint().get("stopped"));assertEquals("RUNNING",runMapper.find(attachment.runId()).getState());assertFalse(bridge.page(attachment,0).terminal());
    }
    @Test void checkpointCrossRunInvalidCursorAndMissingOrForgedVersionNeverReply() throws Exception {
        start();var task=claim();var cp=publishedCheckpoint(task);var reply=replyBody(cp,"abort");assertEquals(400,denied(()->bridge.resume(reply,Long.toString(space),"different-run:0")));reply.put("expectedRevision","1");assertEquals(409,denied(()->bridge.resume(reply,Long.toString(space),null)));reply.put("expectedRevision","0");reply.put("eventId","other_checkpoint");assertEquals(404,denied(()->bridge.resume(reply,Long.toString(space),null)));assertEquals("WAITING",checkpointMapper.find(cp.getString("checkpointId")).getState());
    }
    @Test void queuedCancelIsPersistedTerminalWhileRunningCancelStaysRequestedUntilWorkerResult() throws Exception {
        start();assertEquals("CANCELLED",bridge.cancel(stopBody(),Long.toString(space)).state());var end=bridge.page(attachment,0);assertTrue(end.terminal());assertEquals(499,end.frames().get(0).data().getIntValue("code"));var graph=canvas();body=chatBody(ordinary(createAgent(graph),graph,"second_cancel_build"));body.put("requestKey","second_cancel_run");attachment=bridge.chat(body,Long.toString(space),null);var task=claim();assertEquals("CANCEL_REQUESTED",bridge.cancel(stopBody(),Long.toString(space)).state());assertFalse(bridge.page(attachment,0).terminal());leases.result(attachment.runId(),worker,result(task,"CANCELLED",null));assertTrue(bridge.page(attachment,0).terminal());assertEquals(2,rows("paiflow_run"));
    }
    @Test void readPermissionOrMembershipRevocationIsRecheckedFromCurrentDatabase() throws Exception {
        start();revoke("paiflow:run:query");assertEquals(403,denied(()->bridge.page(attachment,0)));sql("UPDATE sys_menu SET status='0' WHERE perms='paiflow:run:query'");assertEquals(1,sql("DELETE FROM paiflow_space_member WHERE space_id="+space+" AND user_id=42"));assertTrue(Set.of(403,404).contains(denied(()->bridge.page(attachment,0))));assertEquals("QUEUED",runMapper.find(attachment.runId()).getState());
    }
    @Test void missingEventAndStoredSequenceMismatchFailClosedInsteadOfSkippingOrCompleting() throws Exception {
        start();var task=claim();leases.append(attachment.runId(),worker,event(task,JSONObject.of("legacyFrame",nodeFrame(task,null))));var original=runMapper.event(attachment.runId(),1);var corrupted=JSON.parseObject(original);corrupted.put("seq",2);try(var s=db.getConnection();var p=s.prepareStatement("UPDATE paiflow_run_event SET event_json=? WHERE run_id=? AND seq=1")){p.setString(1,corrupted.toJSONString(JSONWriter.Feature.WriteMapNullValue));p.setString(2,attachment.runId());assertEquals(1,p.executeUpdate());}assertEquals(409,denied(()->bridge.page(attachment,0)));assertEquals(1,sql("DELETE FROM paiflow_run_event WHERE run_id='"+attachment.runId()+"' AND seq=1"));assertEquals(410,denied(()->bridge.page(attachment,0)));
    }
    @Test void reconnectCursorNeverCrossesRunOrActualEventWatermarkAndDoesNotCreateAnotherRun() throws Exception {
        start();var task=claim();publishedCheckpoint(task);var replay=bridge.chat(body,Long.toString(space),attachment.runId()+":1");assertEquals(1,replay.after());assertEquals(attachment.runId(),replay.runId());assertEquals(409,denied(()->bridge.chat(body,Long.toString(space),attachment.runId()+":2")));assertEquals(400,denied(()->bridge.chat(body,Long.toString(space),attachment.runId()+":01")));assertEquals(1,rows("paiflow_run"));
    }
    @Test void expiredLeaseCannotTurnPersistedWaitingStateIntoARecoverableWaitingEnd() throws Exception {
        start();var task=claim();publishedCheckpoint(task);assertEquals(1,sql("UPDATE paiflow_run SET lease_expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id='"+attachment.runId()+"'"));var page=bridge.page(attachment,0);assertFalse(page.waiting());assertFalse(page.terminal());var data=page.frames().get(0).data().getJSONObject("event_data");assertEquals("WAITING",data.get("checkpoint_state"));assertEquals(Boolean.FALSE,data.get("checkpoint_waiting"));assertEquals("RUNNING",page.state());
    }
}
