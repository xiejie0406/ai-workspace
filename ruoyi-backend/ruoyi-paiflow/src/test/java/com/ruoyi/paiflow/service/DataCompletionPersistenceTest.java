package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.DataMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 中心真实Mapper/行操作与完成快照共用同一隔离事务，不查询项目MySQL。 */
class DataCompletionPersistenceTest {
    static final String RESOURCE="data:5:revision:2";
    static class Fixture {
        final DataMapper mapper;final RunRecord run=new RunRecord();final JSONObject node,descriptor,payload;
        final DataOperationService service;final DataRuntimeCatalog catalog;final DataResourceService rows;
        Fixture(SqlSession session){this(session.getMapper(DataMapper.class));}
        Fixture(DataMapper actualMapper){
            mapper=actualMapper;var database=mapper.database(5);var table=mapper.table(7);
            descriptor=JSONObject.of("version","2","tables",JSONObject.of("people",JSONObject.of("tableId",7L,"allowedModes",new JSONArray(List.of(0,1,2,3,4)),"execEnvs",new JSONArray(List.of(1,2)),"columns",new JSONArray(List.of("id","name")))));
            node=JSONObject.of("id","database::one","data",JSONObject.of("nodeParam",JSONObject.of("dataResourceId",RESOURCE,"mode",3,"tableName","people")));
            run.setId("33333333-3333-3333-3333-333333333333");run.setLeaseId("44444444-4444-4444-4444-444444444444");run.setAttempt(1);run.setState("RUNNING");run.setSpaceId(2L);run.setOwnerUserId(1L);run.setExecutionStartedAt(LocalDateTime.now());
            run.setAssignmentJson(JSONObject.of("dsl",JSONObject.of("nodes",new JSONArray(List.of(node))),"resourceSnapshots",JSONObject.of(RESOURCE,descriptor),"dataEnvironment",2,"effectPolicy",JSONObject.of("mode","approved_write","allowedOperations",new JSONArray(List.of("data.query","data.mutate")))).toJSONString());
            catalog=mock(DataRuntimeCatalog.class);when(catalog.resolvePinned(2,1,RESOURCE,descriptor)).thenReturn(new DataRuntimeCatalog.FixedData(RESOURCE,descriptor,database,List.of(table)));
            rows=new DataResourceService(mapper,mock(DefaultPersonalSpaceService.class),mock(ResourceAccessService.class));service=new DataOperationService(catalog,rows,mapper);
            payload=JSONObject.of("mode",3,"table","people","execDev",2,"where",JSONObject.of("logicalOperator","and","conditions",new JSONArray()),"values",new JSONObject(),"columns",new JSONArray(List.of("name")),"order",new JSONArray(),"limit",10);
            var row=new HashMap<String,Object>();row.put("table",7L);row.put("env",2);row.put("json","{\"name\":\"完成时的值\"}");mapper.insertRow(row);
        }
        JSONObject complete(String key){return complete(key,"data.query");}
        JSONObject complete(String key,String kind){service.requireCompletionKey(run,node,null,key);var response=service.execute(run,RESOURCE,descriptor,kind,payload,key);service.recordCompleted(run,node,null,RESOURCE,descriptor,kind,payload,key,response);return response;}
    }
    @Test void realOperationSnapshotSurvivesLaterRowChangesAndRetryDoesNotDuplicate() throws Exception {
        try(var session=new DataMapperPersistenceTest().factory().openSession()){
            var f=new Fixture(session);var response=f.complete("once");assertEquals("完成时的值",response.getJSONObject("data").getJSONArray("exec_success").getJSONObject(0).getString("name"));
            long id=((Number)f.mapper.rows(7,2,10).get(0).get("id")).longValue();assertEquals(1,f.mapper.updateRow(7,2,id,"{\"name\":\"后来修改的值\"}"));
            assertEquals(response,f.complete("once"));assertEquals(1,f.mapper.completedSources(f.run.getId(),f.run.getLeaseId(),1,"database::one","").size());
            var saved=f.service.completed(f.run,f.node,null);assertEquals(response,saved.getJSONObject("responseJson"));assertEquals("2",saved.getString("resourceVersion"));assertNull(saved.get("iterationId"));
            saved.getJSONObject("responseJson").getJSONObject("data").put("exec_success",new JSONArray());assertEquals(response,f.service.completed(f.run,f.node,null).getJSONObject("responseJson"));
        }
    }
    @Test void completionReceiptAndActualRowRollbackTogether() throws Exception {
        var factory=new DataMapperPersistenceTest().factory();String run;
        try(var session=factory.openSession()){var f=new Fixture(session);run=f.run.getId();f.complete("rollback");assertNotNull(f.mapper.request(run,1,DataRuntimeCatalog.sha("rollback")));assertEquals(1,f.mapper.countRows(7,2));session.rollback();}
        try(var session=factory.openSession()){var mapper=session.getMapper(DataMapper.class);assertNull(mapper.request(run,1,DataRuntimeCatalog.sha("rollback")));assertNull(mapper.completion(run,1,DataRuntimeCatalog.sha("rollback")));assertEquals(0,mapper.countRows(7,2));}
    }
    @Test void missingFactsCannotBeCreatedAndAnotherKeyCannotReplaceOneNodeCompletion() throws Exception {
        try(var session=new DataMapperPersistenceTest().factory().openSession()){
            var f=new Fixture(session);assertEquals(403,assertThrows(ServiceException.class,()->f.service.completed(f.run,f.node,null)).getCode());
            assertEquals(409,assertThrows(ServiceException.class,()->f.service.recordCompleted(f.run,f.node,null,RESOURCE,f.descriptor,"data.query",f.payload,"missing",JSONObject.of("code",0))).getCode());
            var first=f.complete("one");assertEquals(409,assertThrows(ServiceException.class,()->f.complete("two")).getCode());
            assertEquals(first,f.service.completed(f.run,f.node,null).getJSONObject("responseJson"));
        }
    }
    @Test void sameOperationKeyCannotBecomeAnotherNodeAndSnapshotMustMatchFixedSource() throws Exception {
        try(var session=new DataMapperPersistenceTest().factory().openSession()){
            var f=new Fixture(session);var response=f.complete("same");var other=JSON.parseObject(f.node.toJSONString());other.put("id","database::two");var assignment=JSON.parseObject(f.run.getAssignmentJson());assignment.getJSONObject("dsl").getJSONArray("nodes").add(other);f.run.setAssignmentJson(assignment.toJSONString());
            assertEquals(409,assertThrows(ServiceException.class,()->f.service.recordCompleted(f.run,other,null,RESOURCE,f.descriptor,"data.query",f.payload,"same",response)).getCode());
            f.node.getJSONObject("data").getJSONObject("nodeParam").put("tableName","changed");assertEquals(409,assertThrows(ServiceException.class,()->f.service.completed(f.run,f.node,null)).getCode());
        }
    }
    @Test void historicalReceiptCannotBeReassignedToAnUnprovenNode() throws Exception {
        try(var session=new DataMapperPersistenceTest().factory().openSession()){
            var f=new Fixture(session);f.service.execute(f.run,RESOURCE,f.descriptor,"data.query",f.payload,"legacy");
            assertEquals(409,assertThrows(ServiceException.class,()->f.service.requireCompletionKey(f.run,f.node,null,"legacy")).getCode());
            assertNull(f.mapper.completion(f.run.getId(),1,DataRuntimeCatalog.sha("legacy")));
        }
    }
    @Test void nullableRowProjectionRemainsExplicitNullInBothPersistentReceipts() throws Exception {
        try(var session=new DataMapperPersistenceTest().factory().openSession()){
            var f=new Fixture(session);long id=((Number)f.mapper.rows(7,2,10).get(0).get("id")).longValue();f.mapper.updateRow(7,2,id,"{\"name\":null}");
            var response=f.complete("nullable");var row=response.getJSONObject("data").getJSONArray("exec_success").getJSONObject(0);
            assertTrue(row.containsKey("name"));assertNull(row.get("name"));
            assertEquals(RunJsonBoundary.canonicalFull(response),RunJsonBoundary.canonicalFull(f.service.completed(f.run,f.node,null).getJSONObject("responseJson")));
            assertEquals(response,f.complete("nullable"));
        }
    }
    @Test void oneFixedNodeCannotInsertAgainByChangingItsOperationKey() throws Exception {
        try(var session=new DataMapperPersistenceTest().factory().openSession()){
            var f=new Fixture(session);
            try(var update=session.getConnection().prepareStatement("UPDATE paiflow_data_table SET fields_json=? WHERE id=7")){
                update.setString(1,"[{\"name\":\"id\",\"type\":\"Integer\",\"isSystem\":true},{\"name\":\"uid\",\"type\":\"String\",\"isSystem\":true},{\"name\":\"create_time\",\"type\":\"Time\",\"isSystem\":true},{\"name\":\"name\",\"type\":\"String\",\"isRequired\":true}]");update.executeUpdate();
            }
            f.node.getJSONObject("data").getJSONObject("nodeParam").put("mode",1);var assignment=JSON.parseObject(f.run.getAssignmentJson());assignment.getJSONObject("dsl").getJSONArray("nodes").set(0,f.node);f.run.setAssignmentJson(assignment.toJSONString());
            f.payload.put("mode",1);f.payload.put("values",JSONObject.of("name","只应新增一次"));f.payload.put("columns",new JSONArray());
            var first=f.complete("first-key","data.mutate");assertEquals(2,f.mapper.countRows(7,2));
            assertEquals(409,assertThrows(ServiceException.class,()->f.complete("different-key","data.mutate")).getCode());
            assertEquals(2,f.mapper.countRows(7,2));assertEquals(RunJsonBoundary.canonicalFull(first),RunJsonBoundary.canonicalFull(f.complete("first-key","data.mutate")));assertEquals(2,f.mapper.countRows(7,2));
        }
    }
    @Test void completionSqlAlsoRejectsAnotherKeyForTheSameFixedNode() throws Exception {
        try(var session=new DataMapperPersistenceTest().factory().openSession()){
            var f=new Fixture(session);f.complete("original");var original=f.mapper.completion(f.run.getId(),1,DataRuntimeCatalog.sha("original"));
            var receipt=f.mapper.request(f.run.getId(),1,DataRuntimeCatalog.sha("original"));String another=DataRuntimeCatalog.sha("another");
            f.mapper.record(f.run.getId(),1,another,String.valueOf(receipt.get("requestHash")),String.valueOf(receipt.get("responseJson")));
            var duplicate=new HashMap<String,Object>();duplicate.put("run",f.run.getId());duplicate.put("lease",f.run.getLeaseId());duplicate.put("attempt",1);duplicate.put("node",f.node.getString("id"));duplicate.put("iteration","");duplicate.put("key",another);duplicate.put("json",original.get("completionJson"));duplicate.put("hash",original.get("completionHash"));
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,()->f.mapper.recordCompletion(duplicate));
            assertEquals(1,f.mapper.completedSources(f.run.getId(),f.run.getLeaseId(),1,f.node.getString("id"),"").size());
        }
    }
}
