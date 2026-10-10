package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 固定执行图只承认nodes成员，原业务扩展不被当作节点执行或改写。 */
class DataFixedNodeScopeTest {
    @Test void earlierExtensionWithSameIdCannotReplaceTheActualWorkerNode() {
        var f=new DataWorkerOperationTest.Fixture();var assignment=JSON.parseObject(f.run.getAssignmentJson());
        var nodes=assignment.getJSONObject("dsl").getJSONArray("nodes");var actual=nodes.getJSONObject(0);
        var shadow=JSONObject.of("id",actual.get("id"),"data",JSONObject.of("nodeParam",JSONObject.of("mode",4)));
        nodes.add(0,JSONObject.of("id","text-joiner::earlier","data",JSONObject.of("ext",shadow)));
        f.run.setAssignmentJson(assignment.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        var legacy=JSONObject.of("code",0,"message","success","data",JSONObject.of("exec_success",new JSONArray()));
        when(f.operation.execute(eq(f.run),eq("data:5:revision:2"),eq(f.descriptor),eq("data.query"),any(),eq("key1"))).thenReturn(legacy);
        assertEquals("COMPLETED",f.worker.start(f.run.getId(),f.identity,f.request()).getString("state"));
        verify(f.operation).requireCompletionKey(eq(f.run),eq(actual),isNull(),eq("key1"));
    }
    @Test void compilerDoesNotBindOrRewriteADataLookingBusinessExtension() {
        var hidden=JSONObject.of("id","database::extension","data",JSONObject.of("nodeParam",JSONObject.of("dbId",999L,"mode",3,"tableName","people","uid","保留")));
        var graph=JSONObject.of("nodes",new JSONArray(List.of(JSONObject.of("id","text-joiner::real","data",JSONObject.of("ext",hidden)))),"edges",new JSONArray());
        var original=RunJsonBoundary.canonicalFull(graph);var resources=new JSONObject();
        DataNodeCompiler.compile(graph,resources,id->{fail("业务扩展不得绑定数据库资源");return null;});
        assertTrue(resources.isEmpty());assertEquals(original,RunJsonBoundary.canonicalFull(graph));
        assertTrue(DataNodeCompiler.reconstruct(graph,resources).isEmpty());
    }
    @Test void admissionUsesRealFlatNodesAndIgnoresExtensionObjects() {
        var f=new DataOperationTest.Fixture();
        var read=JSONObject.of("id","database::r","data",JSONObject.of("nodeParam",JSONObject.of("mode",0,"sql","SELECT name FROM people","dataResourceId",DataOperationTest.RESOURCE)));
        read.getJSONObject("data").put("ext",JSONObject.of("id","database::not-a-node"));
        var graph=JSONObject.of("nodes",new JSONArray(List.of(read)),"edges",new JSONArray());
        assertEquals(Set.of("data.query"),f.operation.admit(2,1,JSONObject.of(DataOperationTest.RESOURCE,f.descriptor),graph,2));
        verify(f.catalog,times(1)).resolvePinned(2,1,DataOperationTest.RESOURCE,f.descriptor);verifyNoInteractions(f.rows);
    }
}
