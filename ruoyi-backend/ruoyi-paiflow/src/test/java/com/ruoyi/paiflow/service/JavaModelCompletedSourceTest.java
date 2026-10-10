package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.*;
import com.ruoyi.paiflow.mapper.ModelOperationMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import static org.junit.jupiter.api.Assertions.*;

/** 原 Worker/SDK 金样与真实 H2 Mapper 回读；不代表真实 Run 准入、Provider 或业务验收。 */
public class JavaModelCompletedSourceTest {
    java.sql.Connection ddl; SqlSessionTemplate session; ModelOperationMapper mapper;
    JSONObject graph,node,target,assignment; JSONArray events; RunRecord run; ModelOperationRecord row;
    public static java.sql.Timestamp utcNow(){return java.sql.Timestamp.from(Instant.now());}
    @BeforeEach void setup() throws Exception {
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");ddl=db.getConnection();
        try(var sql=ddl.createStatement()){
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.JavaModelCompletedSourceTest.utcNow'");
            sql.execute("CREATE TABLE paiflow_run(id VARCHAR(36) PRIMARY KEY)");
            String source=Files.readString(Path.of(System.getProperty("paiflow.moduleSql","sql"),"013-paiflow-model-operation.sql"),StandardCharsets.UTF_8)
                    .replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
            for(String statement:source.split(";"))if(!statement.isBlank())sql.execute(statement);
            sql.execute("INSERT INTO paiflow_run(id) VALUES('java-model-run')");
        }
        var config=new Configuration(new Environment("java-source-isolated",new SpringManagedTransactionFactory(),db));
        try(var xml=getClass().getResourceAsStream("/mapper/paiflow/ModelOperationMapper.xml")){assertNotNull(xml);new XMLMapperBuilder(xml,config,"ModelOperationMapper",config.getSqlFragments()).parse();}
        session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));mapper=session.getMapper(ModelOperationMapper.class);
        var gold=object("original-java-model-graph-golden.json");assignment=gold.getJSONObject("assignment");graph=assignment.getJSONObject("dsl");
        node=graph.getJSONArray("nodes").getJSONObject(1);target=graph.getJSONArray("nodes").getJSONObject(2);events=new JSONArray();
        for(Object raw:gold.getJSONArray("actualEvents")){var event=(JSONObject)raw;if(event.getLongValue("seq")<7)events.add(event);}
        assignment.put("executionCodec",DslCodec.V2);assignment.put("workflowVersionId","1");
        run=new RunRecord();run.setId(assignment.getString("runId"));run.setEngineProfile("java");run.setLeaseId(assignment.getString("leaseId"));run.setAttempt(1);run.setWorkerId("java-source-worker");run.setWorkflowVersionId(1L);run.setSpaceId(2L);run.setOwnerUserId(42L);run.setLastEventSeq(6L);
        syncAssignment();
    }
    @AfterEach void close() throws Exception {if(ddl!=null)ddl.close();}
    static JSONObject object(String name) throws Exception {try(var in=JavaModelCompletedSourceTest.class.getResourceAsStream("/fixtures/java-model/"+name)){assertNotNull(in);return JSON.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
    static JSONArray wires() throws Exception {try(var in=JavaModelCompletedSourceTest.class.getResourceAsStream("/fixtures/java-model/original-java-model-wire-golden.json")){assertNotNull(in);return JSON.parseArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
    static String full(Object value,int budget){String result=RunJsonBoundary.canonicalFull(JSON.parseObject(JSON.toJSONString(value,JSONWriter.Feature.WriteMapNullValue)));assertTrue(result.getBytes(StandardCharsets.UTF_8).length<=budget);return result;}
    void syncAssignment(){run.setAssignmentJson(full(assignment,ModelOperationJson.REQUEST_BYTES));}
    void persist(int variant) throws Exception {
        var raw=wires().getJSONObject(variant).getJSONArray("rawFrames");if(variant==3)node.getJSONObject("data").getJSONObject("nodeParam").put("extraParams",JSONObject.of("n",2));syncAssignment();
        var parameters=JSONObject.of("resourceId","model:1:version:2","resourceVersion","2","provider","openai","modelName","original-model","mode","chat","messages",JavaModelSemantics.messages(node,Map.of("x","值"),new JSONArray()),"options",JavaModelSemantics.options(node));
        String p=ModelOperationJson.canonical(parameters,ModelOperationJson.REQUEST_BYTES);
        var c=JSONObject.of("runId",run.getId(),"leaseId",run.getLeaseId(),"attempt",1,"ownerUserId",42,"spaceId",2,"workflowVersionId",1,"dslHash",assignment.get("dslHash"),"nodeId",node.get("id"),"iterationId",null,"resourceId","model:1:version:2","resourceVersion","2","provider","openai","engineProfile","java","parametersJson",p,"parametersHash",DataRuntimeCatalog.sha(p),"nodeJson",full(node,ModelOperationJson.REQUEST_BYTES),"inputJson",full(assignment.get("input"),ModelOperationJson.REQUEST_BYTES),"verifiedInputsJson",full(Map.of("x","值"),ModelOperationJson.REQUEST_BYTES));
        row=new ModelOperationRecord();row.setId("source-model-op");row.setRunId(run.getId());row.setLeaseId(run.getLeaseId());row.setWorkerId(run.getWorkerId());row.setAttempt(1);row.setNodeId(node.getString("id"));row.setWorkflowVersionId(1L);row.setSpaceId(2L);row.setOwnerUserId(42L);row.setDslHash(assignment.getString("dslHash"));row.setResourceId("model:1:version:2");row.setResourceVersion("2");row.setProvider("openai");row.setRequestKey("fixture-original-operation");row.setRequestKeyHash(DataRuntimeCatalog.sha(row.getRequestKey()));row.setRequestHash(DataRuntimeCatalog.sha(p));row.setParametersJson(p);row.setApprovalJson(full(JSONObject.of("candidate",c),ModelOperationJson.REQUEST_BYTES));row.setApprovalRef("isolated-mapper-fact");row.setApprovalExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(120));row.setDeadline(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(120));row.setDispatchStarted(1);row.setGrantAcquired(1);row.setGrantId("source-grant");row.setGrantTokenHash(DataRuntimeCatalog.sha("fixture-only"));row.setStopConfirmed(1);row.setStopProofId("stream-finished:source-model-op:4:3");row.setLastSeq((long)raw.size());row.setRevision(0L);
        long bytes=0;for(int i=0;i<raw.size();i++){var frame=raw.getJSONObject(i);var facts=ModelProviderWire.facts("openai",frame,"java",parameters.getJSONObject("options"));if(facts.finish()!=null){row.setFinishSeq((long)i+1);row.setFinishReason(facts.finish());}if(facts.usage()!=null)row.setUsageJson(ModelOperationJson.canonical(facts.usage(),ModelOperationJson.FRAME_BYTES));bytes+=ModelOperationJson.canonical(frame,ModelOperationJson.FRAME_BYTES).getBytes(StandardCharsets.UTF_8).length;}
        row.setResponseBytes(bytes);row.setState("stop".equals(row.getFinishReason())?"COMPLETED":"FAILED");row.setFailureKind("stop".equals(row.getFinishReason())?null:"provider");assertEquals(1,mapper.insert(row));
        for(int i=0;i<raw.size();i++){String json=ModelOperationJson.canonical(raw.get(i),ModelOperationJson.FRAME_BYTES);assertEquals(1,mapper.insertFrame(row.getId(),i+1,json,DataRuntimeCatalog.sha(json),json.getBytes(StandardCharsets.UTF_8).length));}
    }
    Map<String,Object> values(){return new CompletedOutputSources(null,mapper,null).values(run,graph,target,events);}
    JSONObject result(){return events.stream().map(v->(JSONObject)v).filter(e->node.get("id").equals(e.get("nodeId"))&&"node.completed".equals(e.get("eventType"))).findFirst().orElseThrow().getJSONObject("payload").getJSONObject("result");}
    void update(String column,String value) throws Exception {try(var sql=ddl.prepareStatement("UPDATE paiflow_model_operation SET "+column+"=? WHERE id=?")){sql.setString(1,value);sql.setString(2,row.getId());assertEquals(1,sql.executeUpdate());}}
    void rejected(){assertThrows(ServiceException.class,this::values);}
    @ParameterizedTest @ValueSource(ints={0,3,4}) void actualJavaGoldWholeFramesReachEndWithoutPythonProjection(int variant) throws Exception {persist(variant);assertEquals(Map.of("answer","原文本"),values());assertEquals(variant==4?"FAILED":"COMPLETED",mapper.find(row.getId()).getState());assertEquals(variant==4?"length":"stop",mapper.find(row.getId()).getFinishReason());}
    @Test void forgedOutputRejected() throws Exception {persist(0);result().getJSONObject("outputs").put("answer","伪造");rejected();}
    @Test void forgedThinkingRejected() throws Exception {persist(0);result().getJSONObject("outputs").put("reason","伪造");rejected();}
    @Test void forgedRawTextRejected() throws Exception {persist(0);result().put("rawOutput","伪造");rejected();}
    @Test void forgedUsageRejected() throws Exception {persist(0);result().getJSONObject("tokenCost").put("total_tokens",8);rejected();}
    @Test void ledgerWorkerRejected() throws Exception {persist(0);update("worker_id","other-worker");rejected();}
    @Test void legacyMissingEngineIsPythonAndRejectedForJava() throws Exception {persist(0);var approval=JSON.parseObject(row.getApprovalJson());approval.getJSONObject("candidate").remove("engineProfile");update("approval_json",full(approval,ModelOperationJson.REQUEST_BYTES));rejected();}
    @Test void candidateInputsRejected() throws Exception {persist(0);var approval=JSON.parseObject(row.getApprovalJson());approval.getJSONObject("candidate").put("verifiedInputsJson","{\"x\":\"伪造\"}");update("approval_json",full(approval,ModelOperationJson.REQUEST_BYTES));rejected();}
    @Test void fullTailFramesRequired() throws Exception {persist(0);try(var sql=ddl.createStatement()){sql.executeUpdate("DELETE FROM paiflow_model_frame WHERE seq=4");}rejected();}
    @Test void changedStoredHashRejected() throws Exception {persist(0);try(var sql=ddl.createStatement()){sql.executeUpdate("UPDATE paiflow_model_frame SET frame_hash='"+"0".repeat(64)+"' WHERE seq=1");}rejected();}
    @Test void finishReasonCannotTurnLengthIntoSuccess() throws Exception {persist(4);update("state","COMPLETED");update("failure_kind",null);rejected();}
    @Test void arbitraryProviderFailureWithStopDoesNotAuthorizeCompletion() throws Exception {persist(0);update("state","FAILED");update("failure_kind","provider");rejected();}
    @Test void terminalUsageLedgerTamperingRejected() throws Exception {persist(0);update("usage_json","{\"prompt_tokens\":4,\"completion_tokens\":3,\"total_tokens\":8}");rejected();}
}

