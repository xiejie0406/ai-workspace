package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 实际Agent/WF/Run Mapper与短事务，复用既有Factory24帧；不重新运行Python/Worker或服务。 */
class LegacyPureIterationDebugPersistenceTest extends WorkflowDebugTestFixture {
    private static final String FIXTURE_SHA="a694b3776c2b41d565b307df2c1d97f6fc12e4d455982ffb7cfa4f21f7b09792";
    private JSONObject fixture,body,built,task;
    private LegacyDebugBridgeService bridge;
    private LegacyDebugBridgeService.Attachment attachment;

    @BeforeEach void setupIterationStream() throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of("../../contracts/paiflow/fixtures/pure-iteration-legacy-stream.json"));
        assertEquals(FIXTURE_SHA,sha(bytes));fixture=JSON.parseObject(new String(bytes,StandardCharsets.UTF_8));
        assertEquals(24,fixture.getJSONArray("originalCallbackFrames").size());
        for(Object raw:fixture.getJSONArray("sources")) {
            var source=(JSONObject)raw;
            assertEquals(source.getString("sha256"),sha(Files.readAllBytes(Path.of("../..").resolve(source.getString("path")))));
        }
        bridge=proxy(new LegacyDebugBridgeService(workflows,defaults,access,runs,runMapper,checkpoints,checkpointMapper));
    }
    private static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private JSONObject sourceFrame(int ordinal){return fixture.getJSONArray("originalCallbackFrames").getJSONObject(ordinal-1);}
    private JSONObject rawForCurrentRun(int ordinal){var raw=copy(sourceFrame(ordinal).getJSONObject("raw"));raw.put("id",attachment.runId());return raw;}
    private JSONObject outerEvent(int ordinal,long seq) {
        var original=sourceFrame(ordinal);var raw=rawForCurrentRun(ordinal);var node=raw.getJSONObject("workflow_step").getJSONObject("node");
        var event=JSONObject.of("schemaVersion","paiflow.events.v1","runId",task.get("runId"),"leaseId",task.get("leaseId"),"attempt",task.get("attempt"),
                "seq",seq,"nodeId",node.get("id"),"eventType","stop".equals(node.get("finish_reason"))?"node.completed":"node.started",
                "timestamp",clock.instant().toString(),"payload",JSONObject.of("legacyFrame",raw));
        if(original.get("iterationId")!=null)event.put("iterationId",original.get("iterationId"));
        return event;
    }
    private void start() {
        // 图与这些24帧取自同一个原claim记录，不替换节点ID、内部边、原帧顺序或原输出。
        var graph=copy(fixture.getJSONObject("execution"));var agent=createAgent(graph);built=ordinary(agent,graph,"iteration_stream_build_key");
        var input=copy(fixture.getJSONObject("input"));input.put("protocolNull",null);input.put("protocolFalse",false);input.put("protocolZero",0);input.put("protocolEmpty","");
        body=JSONObject.of("flow_id",built.get("flowId"),"inputs",input,"chatId","isolated_iteration_chat","regen",false,
                "debugReceipt",receipt(built),"requestKey","iteration_stream_request_key");
        attachment=bridge.chat(body,Long.toString(space),null);task=leases.claim(worker);
        assertNotNull(task);assertEquals(attachment.runId(),task.get("runId"));assertEquals(receipt(built).get("workflowVersionId"),task.get("workflowVersionId"));
        assertEquals(receipt(built).get("dslHash"),task.get("dslHash"));assertEquals("python",task.get("engineProfile"));assertTrue(task.getJSONObject("resourceSnapshots").isEmpty());
        var snapshot=workflows.requireDebugReceipt(receipt(built),space).snapshot();
        assertEquals(snapshot.canonicalDslBase64(),task.get("dslCanonicalBase64"));
        assertEquals(snapshot.dslHash(),HexFormat.of().formatHex(hashCanonical(task)));
        assertEquals(RunJsonBoundary.canonicalFull(snapshot.execution()),RunJsonBoundary.canonicalFull(task.getJSONObject("dsl")));
        assertTrue(leases.beginExecution(attachment.runId(),worker,task.getString("leaseId"),task.getIntValue("attempt")).getBooleanValue("begun"));
    }
    private static byte[] hashCanonical(JSONObject task){try{return MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(task.getString("dslCanonicalBase64")));}catch(java.security.NoSuchAlgorithmException ex){throw new AssertionError(ex);}}
    private void storeAssignment(JSONObject assignment)throws Exception {
        try(var c=db.getConnection();var sql=c.prepareStatement("UPDATE paiflow_run SET assignment_json=? WHERE id=?")) {
            sql.setString(1,assignment.toJSONString(JSONWriter.Feature.WriteMapNullValue));sql.setString(2,attachment.runId());assertEquals(1,sql.executeUpdate());
        }
    }
    private JSONObject cancelBody(){return JSONObject.of("flow_id",body.get("flow_id"),"runId",attachment.runId(),"debugReceipt",body.get("debugReceipt"));}
    private JSONObject resumeBody(){return JSONObject.of("flow_id",body.get("flow_id"),"runId",attachment.runId(),"debugReceipt",body.get("debugReceipt"),
            "eventId","no_checkpoint_in_pure_iteration","eventType","abort","expectedRevision","0","replyKey","iteration_reply_key");}

    @Test void exactOriginalTwentyFourFramesPersistAndProjectButOnlyFormalResultEndsRun() throws Exception {
        start();assertEquals(1,rows("paiflow_agent"));assertEquals(1,rows("paiflow_workflow"));assertEquals(1,rows("paiflow_workflow_version"));assertEquals(1,rows("paiflow_run"));
        var persistedInput=JSON.parseObject(runMapper.find(attachment.runId()).getAssignmentJson()).getJSONObject("input");
        assertTrue(persistedInput.containsKey("protocolNull"));assertNull(persistedInput.get("protocolNull"));assertEquals(false,persistedInput.get("protocolFalse"));assertEquals(0,persistedInput.get("protocolZero"));assertEquals("",persistedInput.get("protocolEmpty"));
        Set<String> actualIds=new HashSet<>();for(Object raw:task.getJSONObject("dsl").getJSONArray("nodes"))actualIds.add(((JSONObject)raw).getString("id"));
        int main=0,inner=0;
        for(int ordinal=1;ordinal<=24;ordinal++) {
            var event=outerEvent(ordinal,ordinal);var original=sourceFrame(ordinal);String node=event.getString("nodeId");assertTrue(actualIds.contains(node));
            if(original.get("iterationId")==null){main++;assertFalse(event.containsKey("iterationId"));}else{inner++;assertEquals(original.get("iterationId"),event.get("iterationId"));}
            if(ordinal==1)event.getJSONObject("payload").put("protocolFalsy",JSONObject.of("nullValue",null,"falseValue",false,"zeroValue",0,"emptyValue",""));
            var ack=leases.append(attachment.runId(),worker,event);assertTrue(ack.getBooleanValue("accepted"));assertEquals(ordinal,ack.getLongValue("seq"));
            var saved=JSON.parseObject(runMapper.event(attachment.runId(),ordinal));
            assertEquals(RunJsonBoundary.canonicalFull(rawForCurrentRun(ordinal)),RunJsonBoundary.canonicalFull(saved.getJSONObject("payload").getJSONObject("legacyFrame")));
            var page=bridge.page(attachment,ordinal-1);assertFalse(page.terminal());assertFalse(page.waiting());assertEquals("RUNNING",page.state());
            assertEquals(ordinal,page.scannedThrough());assertEquals(1,page.frames().size());var frame=page.frames().get(0);
            assertEquals(attachment.runId()+":"+ordinal,frame.id());
            assertEquals(RunJsonBoundary.canonicalFull(rawForCurrentRun(ordinal)),RunJsonBoundary.canonicalFull(frame.data()));
            assertTrue(frame.data().getJSONArray("choices").getJSONObject(0).containsKey("finish_reason"));assertNull(frame.data().getJSONArray("choices").getJSONObject(0).get("finish_reason"));
            assertEquals(0,frame.data().getJSONObject("workflow_step").getIntValue("seq"));
        }
        assertEquals(6,main);assertEquals(18,inner);assertEquals("node-end::e",sourceFrame(6).getJSONObject("raw").getJSONObject("workflow_step").getJSONObject("node").get("id"));
        assertNull(sourceFrame(6).getJSONObject("raw").getJSONObject("workflow_step").getJSONObject("node").get("finish_reason"));
        var falsy=JSON.parseObject(runMapper.event(attachment.runId(),1)).getJSONObject("payload").getJSONObject("protocolFalsy");
        assertTrue(falsy.containsKey("nullValue"));assertNull(falsy.get("nullValue"));assertEquals(false,falsy.get("falseValue"));assertEquals(0,falsy.get("zeroValue"));assertEquals("",falsy.get("emptyValue"));
        var first=bridge.page(attachment,0).frames().get(0).data();assertTrue(first.containsKey("usage"));assertNull(first.get("usage"));
        var firstNode=first.getJSONObject("workflow_step").getJSONObject("node");assertTrue(firstNode.containsKey("ext"));assertNull(firstNode.get("ext"));assertEquals(0.0,firstNode.getDoubleValue("executed_time"));assertEquals("",first.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").get("content"));
        assertEquals(24,rows("paiflow_run_event"));assertEquals("RUNNING",runMapper.find(attachment.runId()).getState());
        leases.result(attachment.runId(),worker,result(task,"COMPLETED",copy(fixture.getJSONObject("expectedOutput"))));
        var end=bridge.page(attachment,24);assertTrue(end.terminal());assertFalse(end.waiting());assertEquals("COMPLETED",end.state());assertEquals(1,end.frames().size());assertEquals(attachment.runId()+":25",end.frames().get(0).id());
        assertEquals("stop",end.frames().get(0).data().getJSONArray("choices").getJSONObject(0).get("finish_reason"));assertEquals(0,end.frames().get(0).data().getIntValue("code"));
        assertEquals(fixture.getJSONObject("expectedOutput").getJSONArray("answer"),JSON.parseObject(runMapper.find(attachment.runId()).getResultJson()).getJSONObject("output").getJSONArray("answer"));
        assertEquals(25,rows("paiflow_run_event"));assertEquals(0,rows("paiflow_checkpoint"));assertEquals(0,rows("paiflow_model_operation"));
    }

    @Test void acceptedOriginalInnerReceiptReplaysWithoutWriteAndChangedReceiptConflicts()throws Exception {
        start();var event=outerEvent(8,1);leases.append(attachment.runId(),worker,event);var before=runMapper.find(attachment.runId());String stored=runMapper.event(attachment.runId(),1);
        assertTrue(leases.append(attachment.runId(),worker,copy(event)).getBooleanValue("accepted"));
        var changed=copy(event);changed.getJSONObject("payload").put("changed",true);assertEquals(409,denied(()->leases.append(attachment.runId(),worker,changed)));
        var after=runMapper.find(attachment.runId());assertEquals(before.getRevision(),after.getRevision());assertEquals(1L,after.getLastEventSeq());assertEquals(stored,runMapper.event(attachment.runId(),1));assertEquals(1,rows("paiflow_run_event"));
        assertEquals(1,bridge.page(attachment,0).frames().size());assertFalse(bridge.page(attachment,0).terminal());
    }

    @Test void exactHistoricalBadScopeReceiptIsNotRewrittenAndItsProjectionFailsClosed()throws Exception {
        start();var historical=outerEvent(1,1);historical.put("iterationId","batch-0");
        assertEquals(400,denied(()->leases.append(attachment.runId(),worker,historical)));assertEquals(0,rows("paiflow_run_event"));
        // 直接H2种子只表示旧已存事实，不能通过新append写入这个非法scope。
        var row=runMapper.find(attachment.runId());String stored=RunJsonCodec.canonical(row,historical);assertEquals(1,runMapper.insertEvent(attachment.runId(),1,stored));row.setLastEventSeq(1L);assertEquals(1,runMapper.update(row));
        var before=runMapper.find(attachment.runId());assertTrue(leases.append(attachment.runId(),worker,copy(historical)).getBooleanValue("accepted"));
        assertEquals(409,denied(()->bridge.page(attachment,0)));var changed=copy(historical);changed.getJSONObject("payload").put("changed",true);assertEquals(409,denied(()->leases.append(attachment.runId(),worker,changed)));
        var next=copy(historical);next.put("seq",2L);assertEquals(400,denied(()->leases.append(attachment.runId(),worker,next)));
        assertEquals(stored,runMapper.event(attachment.runId(),1));assertEquals(before.getRevision(),runMapper.find(attachment.runId()).getRevision());assertEquals(1L,runMapper.find(attachment.runId()).getLastEventSeq());assertEquals(1,rows("paiflow_run_event"));
    }

    @Test void invalidNextSequenceOwnerOrdinalMainTagAndRawBindingsPersistNoEventOrRevision()throws Exception {
        start();var before=runMapper.find(attachment.runId());var invalid=new ArrayList<JSONObject>();
        var missing=outerEvent(8,1);missing.remove("iterationId");invalid.add(missing);
        for(String tag:List.of("iteration::i:3","iteration::i:00","iteration::i:-1","iteration::other:0","iteration::i:0/nested:0")){var value=outerEvent(8,1);value.put("iterationId",tag);invalid.add(value);}
        var main=outerEvent(1,1);main.put("iterationId","iteration::i:0");invalid.add(main);
        var wrongRun=outerEvent(8,1);wrongRun.getJSONObject("payload").getJSONObject("legacyFrame").put("id","other-run");invalid.add(wrongRun);
        var wrongNode=outerEvent(8,1);wrongNode.getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").put("id","iteration-node-start::is");invalid.add(wrongNode);
        for(JSONObject value:invalid){assertEquals(400,denied(()->leases.append(attachment.runId(),worker,value)));assertEquals(0,rows("paiflow_run_event"));assertEquals(before.getRevision(),runMapper.find(attachment.runId()).getRevision());assertEquals(0L,runMapper.find(attachment.runId()).getLastEventSeq());}
        assertTrue(leases.append(attachment.runId(),worker,outerEvent(8,1)).getBooleanValue("accepted"));assertEquals(1,rows("paiflow_run_event"));
    }

    @Test void tamperedFixedAssignmentCannotPageCancelOrResumeBeforeAnyBusinessWrite()throws Exception {
        start();var before=runMapper.find(attachment.runId());var original=JSON.parseObject(before.getAssignmentJson());var variants=new ArrayList<JSONObject>();
        var hash=copy(original);hash.put("dslHash","f".repeat(64));variants.add(hash);
        var canonical=copy(original);canonical.put("dslCanonicalBase64",Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));variants.add(canonical);
        var engine=copy(original);engine.put("engineProfile","java");variants.add(engine);
        var dsl=copy(original);data(dsl.getJSONObject("dsl"),"text-joiner::j").getJSONObject("nodeParam").put("alteredFixedGraph",true);variants.add(dsl);
        var resources=copy(original);resources.put("resourceSnapshots",JSONObject.of("forged",JSONObject.of("kind","model","provider","openai","version","7","modelName","forged","enabled",true)));variants.add(resources);
        var version=copy(original);version.put("workflowVersionId",Long.toString(attachment.workflowVersionId()+1));variants.add(version);
        var missingInput=copy(original);missingInput.remove("input");variants.add(missingInput);
        for(JSONObject variant:variants) {
            storeAssignment(variant);assertEquals(409,denied(()->bridge.page(attachment,0)));
            assertEquals(409,denied(()->bridge.cancel(cancelBody(),Long.toString(space))));assertEquals(409,denied(()->bridge.resume(resumeBody(),Long.toString(space),null)));
            var row=runMapper.find(attachment.runId());assertEquals("RUNNING",row.getState());assertEquals(before.getRevision(),row.getRevision());assertEquals(0L,row.getLastEventSeq());assertEquals(0,rows("paiflow_run_event"));assertEquals(0,rows("paiflow_checkpoint"));
            storeAssignment(original);assertTrue(bridge.page(attachment,0).frames().isEmpty());assertFalse(bridge.page(attachment,0).terminal());
        }
        assertEquals(1,rows("paiflow_run"));assertEquals(1,rows("paiflow_workflow_version"));
    }

    @Test void storedInnerScopeUsesActualFixedInputAndCurrentReadAuthority()throws Exception {
        start();leases.append(attachment.runId(),worker,outerEvent(8,1));var original=JSON.parseObject(runMapper.find(attachment.runId()).getAssignmentJson());
        var empty=copy(original);empty.getJSONObject("input").put("items",new JSONArray());storeAssignment(empty);
        assertEquals(409,denied(()->bridge.page(attachment,0)));var next=outerEvent(8,2);assertEquals(400,denied(()->leases.append(attachment.runId(),worker,next)));assertEquals(1,rows("paiflow_run_event"));
        storeAssignment(original);assertEquals(1,bridge.page(attachment,0).frames().size());revoke("paiflow:run:query");assertEquals(403,denied(()->bridge.page(attachment,0)));assertEquals(1,rows("paiflow_run_event"));assertEquals("RUNNING",runMapper.find(attachment.runId()).getState());
    }
}
