package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.mapper.RunMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原Factory分支金样经正式数据入口消费；无未选节点完成帧仍保留合法默认。 */
class DataBranchDefaultTest {
    @Test void dataMergeConsumesOriginalDefaultsAndRejectsChangedOrUnprovenValues() throws Exception {
        var sample=BranchDefaultSourceTest.cases().getJSONObject(0);assertEquals("if-yes-skipped-defaults",sample.getString("name"));
        var f=new DataWorkerOperationTest.Fixture();var task=ModelMessageSources.sourceObject(sample.getJSONObject("assignment").toJSONString(JSONWriter.Feature.WriteMapNullValue));var graph=task.getJSONObject("dsl");
        var originalEvents=sample.getJSONArray("actualEvents");var originalEnd=originalEvents.stream().map(JSONObject.class::cast).filter(event->"node-end::e".equals(event.get("nodeId"))&&"node.completed".equals(event.get("eventType"))).findFirst().orElseThrow();
        var expectedValues=BranchDefaultSourceTest.output(originalEnd);assertEquals("no-default",expectedValues.get("no"));assertEquals(false,expectedValues.get("flag"));
        var target=DataWorkerOperationService.find(graph,"node-end::e");target.put("id","database::1");target.getJSONObject("data").put("nodeParam",JSONObject.of("mode",1,"tableName","people","dataResourceId","data:5:revision:2"));
        for(Object raw:graph.getJSONArray("edges")){var edge=(JSONObject)raw;if("node-end::e".equals(edge.get("targetNodeId")))edge.put("targetNodeId","database::1");}
        var events=new JSONArray();for(Object raw:originalEvents){var event=ModelMessageSources.sourceObject(((JSONObject)raw).toJSONString(JSONWriter.Feature.WriteMapNullValue));if("node-end::e".equals(event.get("nodeId")))continue;event.put("runId",f.run.getId());event.put("leaseId",f.run.getLeaseId());events.add(event);}
        assertTrue(events.stream().noneMatch(raw->"text-joiner::no".equals(((JSONObject)raw).get("nodeId"))));
        f.run.setLastEventSeq(events.stream().mapToLong(raw->((JSONObject)raw).getLongValue("seq")).max().orElse(0));task.put("dataEnvironment",2);task.put("resourceSnapshots",JSONObject.of("data:5:revision:2",f.descriptor));
        task.put("runId",f.run.getId());task.put("leaseId",f.run.getLeaseId());task.put("attempt",f.run.getAttempt());task.put("dslHash",DslCodec.fixedExecutionHash(graph,DslCodec.V4));task.put("dslCanonicalBase64",DslCodec.canonicalDslBase64(graph,DslCodec.V4));f.run.setAssignmentJson(task.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        var rows=new ArrayList<RunMapper.EventRow>();for(Object raw:events){var event=(JSONObject)raw;rows.add(new RunMapper.EventRow(event.getLongValue("seq"),event.toJSONString(JSONWriter.Feature.WriteMapNullValue)));}
        when(f.runs.eventsThrough(f.run.getId(),0,f.run.getLastEventSeq(),1001)).thenReturn(rows);
        var request=f.request();request.put("operation","data.mutate");var payload=DataInputPayload.expected(target,expectedValues,2);request.getJSONObject("parameters").put("legacyPayload",payload);
        var legacy=JSONObject.of("code",0,"message","success","data",JSONObject.of("exec_success",new JSONArray()));when(f.operation.execute(eq(f.run),eq("data:5:revision:2"),eq(f.descriptor),eq("data.mutate"),any(),eq("key1"))).thenReturn(legacy);
        assertEquals("COMPLETED",f.worker.start(f.run.getId(),f.identity,request).get("state"));verify(f.operation).execute(f.run,"data:5:revision:2",f.descriptor,"data.mutate",payload,"key1");
        clearInvocations(f.operation);payload.getJSONObject("values").put("no","错误默认");assertEquals(409,assertThrows(ServiceException.class,()->f.worker.start(f.run.getId(),f.identity,request)).getCode());verifyNoInteractions(f.operation);
        payload.getJSONObject("values").put("no",expectedValues.get("no"));rows.removeIf(row->ModelMessageSources.sourceObject(row.eventJson()).getString("nodeId").equals("if-else::c"));assertEquals(403,assertThrows(ServiceException.class,()->f.worker.start(f.run.getId(),f.identity,request)).getCode());verifyNoInteractions(f.operation);
    }
}
