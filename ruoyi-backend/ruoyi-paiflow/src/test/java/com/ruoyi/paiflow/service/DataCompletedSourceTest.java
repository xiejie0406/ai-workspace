package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.mapper.RunMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际数据入口使用共同来源校验；Mapper/最终数据操作为隔离设施，无真实业务写入。 */
class DataCompletedSourceTest {
    private static class Chain {
        final DataWorkerOperationTest.Fixture f=new DataWorkerOperationTest.Fixture();
        final JSONObject execution,node,event,receipt;
        Chain(String answer) {
            f.run.setLeaseId("lease");f.run.setLastEventSeq(1L);
            var outputs=new JSONArray(List.of(field("answer","string"),field("count","integer"),field("flag","boolean")));
            var source=JSONObject.of("id","plugin::source","data",JSONObject.of("inputs",new JSONArray(),"outputs",outputs,"nodeParam",new JSONObject()));
            var inputs=new JSONArray();
            for(Object value:outputs){var output=(JSONObject)value;inputs.add(JSONObject.of("name",output.get("name"),"schema",JSONObject.of("type",output.getJSONObject("schema").get("type"),"value",JSONObject.of("type","ref","content",JSONObject.of("nodeId","plugin::source","name",output.get("name"))))));}
            node=JSONObject.of("id","database::1","data",JSONObject.of("inputs",inputs,"nodeParam",JSONObject.of("mode",1,"tableName","people","dataResourceId","data:5:revision:2","cases",new JSONArray())));
            execution=JSONObject.of("nodes",new JSONArray(List.of(source,node)),"edges",new JSONArray(List.of(JSONObject.of("sourceNodeId","plugin::source","targetNodeId","database::1"))));
            var task=JSON.parseObject(f.run.getAssignmentJson());task.put("dsl",execution);f.run.setAssignmentJson(task.toJSONString(JSONWriter.Feature.WriteMapNullValue));
            event=JSONObject.of("schemaVersion","paiflow.events.v1","runId",f.run.getId(),"leaseId","lease","attempt",1,"seq",1,"nodeId","plugin::source","eventType","node.completed","payload",JSONObject.of("legacyFrame",JSONObject.of("workflow_step",JSONObject.of("node",JSONObject.of("id","plugin::source","outputs",JSONObject.of("answer",answer,"count",0,"flag",false))))));
            receipt=JSONObject.of("header",JSONObject.of("code",0),"payload",JSONObject.of("text",JSONObject.of("text",JSONObject.of("answer","中心可信输出","count",0,"flag",false).toJSONString())));
            when(f.runs.eventsThrough(f.run.getId(),0,1,1001)).thenReturn(List.of(new RunMapper.EventRow(1,event.toJSONString(JSONWriter.Feature.WriteMapNullValue))));
            when(f.tools.nodeOperations(f.run.getId(),"lease",1,"plugin::source")).thenReturn(List.of(JSONObject.of("state","COMPLETED","responseJson",receipt.toJSONString())));
        }
        private static JSONObject field(String name,String type){return JSONObject.of("name",name,"schema",JSONObject.of("type",type));}
        JSONObject request(){var request=f.request();request.put("operation","data.mutate");request.getJSONObject("parameters").put("legacyPayload",DataInputPayload.expected(execution,node,new JSONObject(),new JSONArray(List.of(event)),2));return request;}
    }
    @Test void acceptedWorkerValueCannotBecomeWriteUnlessItMatchesRealToolReceipt() {
        var chain=new Chain("篡改成任意业务写入值");var request=chain.request();
        // 请求与已接受事件完全一致；仅固定模板比对无法发现这一篡改。
        assertEquals("篡改成任意业务写入值",request.getJSONObject("parameters").getJSONObject("legacyPayload").getJSONObject("values").get("answer"));
        assertEquals(409,assertThrows(ServiceException.class,()->chain.f.worker.start(chain.f.run.getId(),chain.f.identity,request)).getCode());
        verifyNoInteractions(chain.f.operation);
    }
    @Test void confirmedToolValuesPreserveFalseAndZeroAndDispatchDataExactlyOnce() {
        var chain=new Chain("中心可信输出");var request=chain.request();var payload=request.getJSONObject("parameters").getJSONObject("legacyPayload");
        assertEquals(0,payload.getJSONObject("values").get("count"));assertEquals(false,payload.getJSONObject("values").get("flag"));
        when(chain.f.operation.execute(eq(chain.f.run),eq("data:5:revision:2"),eq(chain.f.descriptor),eq("data.mutate"),any(),eq("key1"))).thenReturn(JSONObject.of("code",0,"data",JSONObject.of("last_inserted_row",new JSONArray())));
        assertEquals("COMPLETED",chain.f.worker.start(chain.f.run.getId(),chain.f.identity,request).get("state"));
        verify(chain.f.operation,times(1)).execute(chain.f.run,"data:5:revision:2",chain.f.descriptor,"data.mutate",payload,"key1");
        verify(chain.f.tools).nodeOperations(chain.f.run.getId(),"lease",1,"plugin::source");
    }
    @Test void missingFailedOrAmbiguousToolReceiptsCannotAuthorizeDataWrite() {
        for(String state:List.of("missing","FAILED","ambiguous")){
            var chain=new Chain("中心可信输出");var row=JSONObject.of("state",state,"responseJson",chain.receipt.toJSONString());
            when(chain.f.tools.nodeOperations(chain.f.run.getId(),"lease",1,"plugin::source")).thenReturn("missing".equals(state)?List.of():"ambiguous".equals(state)?List.of(row,row):List.of(row));
            var request=chain.request();assertEquals(403,assertThrows(ServiceException.class,()->chain.f.worker.start(chain.f.run.getId(),chain.f.identity,request),state).getCode());verifyNoInteractions(chain.f.operation);
        }
    }
    @Test void dataWithoutProducerReferencesDoesNotRequireToolOrModelConfiguration() {
        var f=new DataWorkerOperationTest.Fixture();when(f.operation.execute(eq(f.run),anyString(),any(),anyString(),any(),anyString())).thenReturn(JSONObject.of("code",0,"data",new JSONObject()));
        assertEquals("COMPLETED",f.worker.start(f.run.getId(),f.identity,f.request()).get("state"));
        verifyNoInteractions(f.tools,f.models,f.checkpoints);
    }
    @Test void originalTextJoinerKeepsItsLegalChainAndCannotReplaceConfirmedToolValue() {
        for(boolean changed:List.of(false,true)){
            var chain=new Chain("中心可信输出");
            var binding=JSONObject.of("name","answer","schema",JSONObject.of("type","string","value",JSONObject.of("type","ref","content",JSONObject.of("nodeId","plugin::source","name","answer"))));
            var joiner=JSONObject.of("id","text-joiner::middle","data",JSONObject.of("inputs",new JSONArray(List.of(binding)),"outputs",new JSONArray(List.of(Chain.field("joined","string"))),"nodeParam",JSONObject.of("mode",0,"prompt","expected={{answer}}")));
            chain.execution.getJSONArray("nodes").add(1,joiner);
            chain.execution.put("edges",new JSONArray(List.of(JSONObject.of("sourceNodeId","plugin::source","targetNodeId","text-joiner::middle"),JSONObject.of("sourceNodeId","text-joiner::middle","targetNodeId","database::1"),JSONObject.of("sourceNodeId","plugin::source","targetNodeId","database::1"))));
            chain.node.getJSONObject("data").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getJSONObject("value").put("content",JSONObject.of("nodeId","text-joiner::middle","name","joined"));
            String joined=changed?"错误的中间值":"expected=中心可信输出";
            var middle=JSONObject.of("schemaVersion","paiflow.events.v1","runId",chain.f.run.getId(),"leaseId","lease","attempt",1,"seq",2,"nodeId","text-joiner::middle","eventType","node.completed","payload",JSONObject.of("legacyFrame",JSONObject.of("workflow_step",JSONObject.of("node",JSONObject.of("id","text-joiner::middle","outputs",JSONObject.of("joined",joined))))));
            var task=JSON.parseObject(chain.f.run.getAssignmentJson());task.put("dsl",chain.execution);chain.f.run.setAssignmentJson(task.toJSONString(JSONWriter.Feature.WriteMapNullValue));chain.f.run.setLastEventSeq(2L);
            when(chain.f.runs.eventsThrough(chain.f.run.getId(),0,2,1001)).thenReturn(List.of(new RunMapper.EventRow(1,chain.event.toJSONString()),new RunMapper.EventRow(2,middle.toJSONString())));
            var request=chain.f.request();request.put("operation","data.mutate");request.getJSONObject("parameters").put("legacyPayload",DataInputPayload.expected(chain.execution,chain.node,new JSONObject(),new JSONArray(List.of(chain.event,middle)),2));
            if(changed){assertEquals(409,assertThrows(ServiceException.class,()->chain.f.worker.start(chain.f.run.getId(),chain.f.identity,request)).getCode());verifyNoInteractions(chain.f.operation);}
            else{when(chain.f.operation.execute(eq(chain.f.run),anyString(),any(),eq("data.mutate"),any(),eq("key1"))).thenReturn(JSONObject.of("code",0,"data",new JSONObject()));assertEquals("COMPLETED",chain.f.worker.start(chain.f.run.getId(),chain.f.identity,request).get("state"));verify(chain.f.operation).execute(eq(chain.f.run),anyString(),any(),eq("data.mutate"),any(),eq("key1"));}
        }
    }
}
