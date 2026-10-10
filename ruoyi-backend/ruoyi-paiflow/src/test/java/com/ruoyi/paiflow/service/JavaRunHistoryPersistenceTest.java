package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.*;
import com.ruoyi.paiflow.mapper.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.*;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离H2正式历史/模型Mapper和事务；身份/租约端口隔离，预置流帧，不算原Worker闭环或业务验收。 */
public class JavaRunHistoryPersistenceTest {
    JdbcDataSource db;Connection connection;RunHistoryMapper rows;ModelOperationMapper models;
    RunMapper runs;RunLeaseService leases;ResourceAccessService access;SpaceMapper spaces;
    RunHistoryService service,target;CompletedOutputSources sources;JSONObject original,historyGolden;
    Map<String,RunRecord> records=new HashMap<>();Map<String,List<RunMapper.EventRow>> events=new HashMap<>();
    WorkerIdentityService.Principal worker=new WorkerIdentityService.Principal("java-worker","java");
    WorkerIdentityService.Principal pythonWorker=new WorkerIdentityService.Principal("python-worker","python");
    static final String NODE="spark-llm::2",CHAT="original-history-chat";
    public static Timestamp utcNow(){return Timestamp.from(Instant.now());}
    public static Timestamp utcNow(int precision){return utcNow();}
    private <T> T tx(T value){var p=new ProxyFactory(value);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    static <T> ObjectProvider<T> provider(T value){ObjectProvider<T> p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);return p;}
    static String resource(String file)throws Exception{try(var in=JavaRunHistoryPersistenceTest.class.getResourceAsStream(file)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    @BeforeEach void setup()throws Exception {
        original=JSON.parseObject(resource("/fixtures/java-model/original-java-model-graph-golden.json"));
        historyGolden=JSON.parseObject(resource("/fixtures/java-model/original-history-golden.json"));
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");connection=db.getConnection();
        try(var sql=connection.createStatement()) {
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.JavaRunHistoryPersistenceTest.utcNow'");
            sql.execute("CREATE TABLE paiflow_run(id VARCHAR(36) PRIMARY KEY)");
            for(String name:List.of("013-paiflow-model-operation.sql","021-paiflow-run-history.sql","033-paiflow-java-model-history.sql")) {
                String ddl=Files.readString(Path.of("sql",name)).replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","")
                        .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
                for(String part:ddl.split(";"))if(!part.isBlank())sql.execute(part);
            }
        }
        var config=new Configuration(new Environment("java-history",new SpringManagedTransactionFactory(),db));
        for(String name:List.of("RunHistoryMapper","ModelOperationMapper"))try(var in=getClass().getResourceAsStream("/mapper/paiflow/"+name+".xml")){
            new XMLMapperBuilder(in,config,name,config.getSqlFragments()).parse();
        }
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));rows=session.getMapper(RunHistoryMapper.class);models=session.getMapper(ModelOperationMapper.class);
        runs=mock(RunMapper.class);leases=mock(RunLeaseService.class);access=mock(ResourceAccessService.class);spaces=mock(SpaceMapper.class);
        when(runs.find(anyString())).thenAnswer(a->records.get(a.getArgument(0)));when(runs.lock(anyString())).thenAnswer(a->records.get(a.getArgument(0)));
        when(runs.eventsThrough(anyString(),anyLong(),anyLong(),anyInt())).thenAnswer(a->events.getOrDefault(a.getArgument(0),List.of()));
        when(leases.requireActiveLease(anyString(),any(),anyString(),anyInt())).thenAnswer(a->{
            var run=records.get(a.getArgument(0));if(run==null || !("python".equals(run.getEngineProfile())?pythonWorker:worker).equals(a.getArgument(1)) || !run.getLeaseId().equals(a.getArgument(2))
                    || run.getAttempt()!=((Integer)a.getArgument(3)))throw new ServiceException("isolated lease denied",403);return run;
        });
        when(access.requirePermission(anyString())).thenReturn(42L);when(spaces.lock(anyLong())).thenReturn(new SpaceRecord());
        sources=new CompletedOutputSources(null,models,null);target=new RunHistoryService(rows,runs,leases,access);target.useJavaSources(provider(sources),spaces,models);service=tx(target);
    }
    @AfterEach void close()throws Exception{connection.close();}
    RunRecord run(String user,JSONObject params,String chat,long owner,long space,long workflow)throws Exception {
        var r=new RunRecord();r.setId(UUID.randomUUID().toString());r.setLeaseId(UUID.randomUUID().toString());r.setAttempt(1);r.setState("RUNNING");
        r.setExecutionStartedAt(LocalDateTime.now());r.setSpaceId(space);r.setWorkflowId(workflow);r.setWorkflowVersionId(3L);r.setOwnerUserId(owner);r.setEngineProfile("java");r.setWorkerId(worker.id());r.setLastEventSeq(2L);
        var assignment=ModelOperationJson.copy(original.getJSONObject("assignment"));var node=assignment.getJSONObject("dsl").getJSONArray("nodes").getJSONObject(1);
        if(params!=null)node.getJSONObject("data").put("nodeParam",ModelOperationJson.copy(params));
        node.getJSONObject("data").getJSONObject("nodeParam").put("modelResourceId","model:1:version:2");
        assignment.put("runId",r.getId());assignment.put("leaseId",r.getLeaseId());assignment.put("attempt",1);assignment.put("executionCodec",DslCodec.V2);
        assignment.put("input",JSONObject.of("x",user));assignment.put("dslHash",RunJsonBoundary.hashFull(assignment.getJSONObject("dsl")));r.setAssignmentJson(RunJsonBoundary.canonicalFull(assignment));
        var accepted=ModelOperationJson.copy(original.getJSONArray("actualEvents").getJSONObject(1));accepted.put("runId",r.getId());accepted.put("leaseId",r.getLeaseId());accepted.getJSONObject("payload").getJSONObject("result").put("inputs",JSONObject.of("x",user));
        records.put(r.getId(),r);events.put(r.getId(),List.of(new RunMapper.EventRow(2,RunJsonBoundary.canonicalFull(accepted))));
        try(var sql=connection.prepareStatement("INSERT INTO paiflow_run(id) VALUES(?)")){sql.setString(1,r.getId());sql.executeUpdate();}
        assertEquals(1,rows.bind(r.getId(),space,workflow,owner,"original-model-flow","debug:"+workflow,chat));return r;
    }
    JSONObject params(RunRecord r){return node(r).getJSONObject("data").getJSONObject("nodeParam");}
    JSONObject node(RunRecord r){return ModelMessageSources.sourceObject(r.getAssignmentJson()).getJSONObject("dsl").getJSONArray("nodes").getJSONObject(1);}
    JSONObject params(String system,String user,int rounds){return JSONObject.of("modelId",1,"domain","fixed-model","systemTemplate",system,"template",user,"enableChatHistoryV2",JSONObject.of("isEnabled",true,"rounds",rounds));}
    JSONObject request(RunRecord r){return JSONObject.of("leaseId",r.getLeaseId(),"attempt",1,"nodeId",NODE);}
    JSONObject open(RunRecord r){return service.openJava(r.getId(),worker,request(r));}
    Map<String,Object> inputs(RunRecord r){return Map.of("x",ModelMessageSources.sourceObject(r.getAssignmentJson()).getJSONObject("input").get("x"));}
    JSONObject snapshot(RunRecord r){return service.javaSnapshot(r,node(r),inputs(r));}
    void same(Object expected,Object actual){assertTrue(ToolSchema.same(expected,actual),()->"expected="+JSON.toJSONString(expected)+" actual="+JSON.toJSONString(actual));}
    static Object stable(Object raw){if(raw instanceof Map<?,?> m){var result=new LinkedHashMap<String,Object>();m.forEach((k,v)->{if(!"timestamp".equals(k))result.put((String)k,stable(v));});return result;}if(raw instanceof List<?> l)return l.stream().map(JavaRunHistoryPersistenceTest::stable).toList();return raw;}
    ModelOperationRecord model(RunRecord r,String answer,String thinking,boolean length)throws Exception {
        var gold=JSON.parseArray(resource("/fixtures/java-model-wire/original-sdk.json")).getJSONObject(length?4:0);var raw=gold.getJSONArray("rawFrames");
        boolean reasoningAssigned=false;
        for(Object value:raw){var frame=(JSONObject)value;var choices=frame.getJSONArray("choices");if(choices==null || choices.isEmpty())continue;var delta=choices.getJSONObject(0).getJSONObject("delta");if(delta.get("content") instanceof String s&&!s.isBlank())delta.put("content",answer);if(delta.get("reasoning_content") instanceof String){delta.put("reasoning_content",reasoningAssigned?"":thinking);reasoningAssigned=true;}}
        if(answer.length()>32000){var content=raw.getJSONObject(1);raw.remove(1);int at=1;for(int from=0;from<answer.length();from+=32000){var chunk=ModelOperationJson.copy(content);chunk.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").put("content",answer.substring(from,Math.min(answer.length(),from+32000)));raw.add(at++,chunk);}}
        var row=new ModelOperationRecord();row.setId(UUID.randomUUID().toString());row.setRunId(r.getId());row.setLeaseId(r.getLeaseId());row.setWorkerId(worker.id());row.setAttempt(1);row.setNodeId(NODE);row.setWorkflowVersionId(3L);row.setSpaceId(r.getSpaceId());row.setOwnerUserId(r.getOwnerUserId());
        row.setDslHash(ModelMessageSources.sourceObject(r.getAssignmentJson()).getString("dslHash"));row.setResourceId("model:1:version:2");row.setResourceVersion("2");row.setProvider("openai");row.setRequestKey("fixture");row.setRequestKeyHash(ModelOperationJson.sha("fixture"));row.setRequestHash(ModelOperationJson.sha("fixture"));
        row.setParametersJson(RunJsonBoundary.canonicalFull(JSONObject.of("resourceId",row.getResourceId(),"resourceVersion",row.getResourceVersion(),"provider","openai","modelName","original-model","mode","chat","messages",snapshot(r).get("messages"),"options",JavaModelSemantics.options(node(r)))));
        var candidate=JSONObject.of("runId",r.getId(),"leaseId",r.getLeaseId(),"attempt",1,"ownerUserId",r.getOwnerUserId(),"spaceId",r.getSpaceId(),"workflowVersionId",3L,"dslHash",row.getDslHash(),"nodeId",NODE,"iterationId",null,"resourceId",row.getResourceId(),"resourceVersion",row.getResourceVersion(),"provider","openai","engineProfile","java", "nodeJson",ModelOperationJson.canonicalNode(node(r),ModelOperationJson.REQUEST_BYTES),
                "parametersJson",row.getParametersJson(),"parametersHash",ModelOperationJson.sha(row.getParametersJson()),"inputJson",RunJsonBoundary.canonicalFull(ModelMessageSources.sourceObject(r.getAssignmentJson()).getJSONObject("input")),"verifiedInputsJson",ModelMessageSources.verifiedInputsJson(inputs(r)),"javaHistoryJson",RunJsonBoundary.canonicalFull(snapshot(r)));
        row.setApprovalJson(RunJsonBoundary.canonicalFull(JSONObject.of("candidate",candidate)));row.setApprovalRef("isolated-fixture");row.setApprovalExpiresAt(LocalDateTime.now().plusMinutes(1));row.setDeadline(LocalDateTime.now().plusMinutes(1));
        row.setState(length?"FAILED":"COMPLETED");row.setFailureKind(length?"provider":null);row.setLastSeq((long)raw.size());row.setFinishSeq((long)raw.size()-1);row.setFinishReason(length?"length":"stop");row.setStopConfirmed(1);row.setStopProofId("stream-finished:"+row.getId()+":"+raw.size()+":"+(raw.size()-1));row.setDispatchStarted(1);row.setGrantAcquired(1);row.setRevision(0L);
        row.setUsageJson(RunJsonBoundary.canonicalFull(raw.getJSONObject(raw.size()-1).getJSONObject("usage")));long bytes=0;for(Object value:raw)bytes+=RunJsonBoundary.canonicalFull((JSONObject)value).getBytes(StandardCharsets.UTF_8).length;row.setResponseBytes(bytes);assertEquals(1,models.insert(row));long seq=0;
        for(Object value:raw){String json=RunJsonBoundary.canonicalFull((JSONObject)value);int size=json.getBytes(StandardCharsets.UTF_8).length;assertTrue(size<=ModelOperationJson.FRAME_BYTES,"每帧保留生产字节预算");assertEquals(1,models.insertFrame(row.getId(),++seq,json,ModelOperationJson.sha(json),size));}return row;
    }
    void seed()throws Exception{
        for(Object raw:historyGolden.getJSONArray("seed")){var item=(JSONObject)raw;var messages=item.getJSONArray("userInputs");var r=run("值",params(messages.getJSONObject(0).getString("content"),messages.getJSONObject(1).getString("content"),0),CHAT,42,1,2);open(r);
            var model=model(r,item.getJSONArray("llmResponses").getJSONObject(0).getString("content"),item.getJSONArray("llmThinking").getJSONObject(0).getString("content"),false);service.completeJava(r.getId(),NODE,model.getId());}
    }
    @Test void actualOriginalTwentyOneConfigurationsMatchSelectionAndProviderMessages()throws Exception {
        seed();var matrix=historyGolden.getJSONArray("matrix");assertEquals(21,matrix.size());
        for(Object raw:matrix){var sample=(JSONObject)raw;var r=run("值",sample.getJSONObject("node").getJSONObject("data").getJSONObject("nodeParam"),CHAT,42,1,2);
            var calls=sample.getJSONArray("calls");if(calls.isEmpty()){assertEquals(400,assertThrows(ServiceException.class,()->open(r),sample.getString("name")).getCode());assertNull(rows.javaRound(r.getId(),1,NODE));continue;}
            assertEquals(sample.getLongValue("historyLimit"),JavaModelSemantics.historyLimit(node(r)).longValue(),sample.getString("name"));
            var response=open(r);assertTrue(ToolSchema.same(stable(calls.getJSONObject(0).get("history")),stable(response.get("history"))),sample.getString("name"));same(calls.getJSONObject(0).get("messages"),snapshot(r).get("messages"));
            // 矩阵各项独立读取同一原种子窗口，不让前一配置的测试半轮改变后项前提。
            try(var sql=connection.prepareStatement("DELETE FROM paiflow_java_node_history WHERE run_id=?")){sql.setString(1,r.getId());sql.executeUpdate();}
        }
    }
    @Test void failedHalfRoundAndZeroHistoryCurrentRoundPersistBeforeModelCall()throws Exception {
        var first=run("第一问",params("系统{{x}}","用户{{x}}",0),CHAT,42,1,2);assertTrue(open(first).getJSONArray("history").isEmpty());
        var firstRow=rows.javaRound(first.getId(),1,NODE);assertEquals("OPEN",firstRow.get("state"));var item=JSON.parseObject((String)firstRow.get("itemJson"));assertTrue(item.getJSONArray("llmResponses").isEmpty());assertEquals(2,item.getJSONArray("userInputs").size());
        first.setState("FAILED");var second=run("第二问",params("新系统{{x}}","新用户{{x}}",2),CHAT,42,1,2);var response=open(second);assertEquals(1,response.getJSONArray("history").size());
        same(List.of(JSONObject.of("role","system","content","新系统第二问"),JSONObject.of("role","user","content","用户第一问"),JSONObject.of("role","user","content","新用户第二问")),snapshot(second).get("messages"));
    }
    @Test void replayPreservesSnapshotDespiteLaterCompletionAndEviction()throws Exception {
        seed();var r=run("值",params("当前系统","当前用户",11),CHAT,42,1,2);var before=open(r);assertEquals(10,before.getJSONArray("history").size());
        var later=run("后续",params("系统","用户",0),CHAT,42,1,2);open(later);var op=model(later,"后续回答","思考",false);service.completeJava(later.getId(),NODE,op.getId());
        same(before,open(r));assertEquals(10,rows.javaRecent(1,2,42,"debug:2",CHAT,NODE).size());same(before.getJSONArray("history"),snapshot(r).get("history"));
    }
    @Test void realModelFramesFillAnswerThinkingAndLengthWithoutChangingFailedLedger()throws Exception {
        for(boolean length:List.of(false,true)){var r=run("值",params("系统","问题",0),CHAT,42,1,2);open(r);var op=model(r,"原文本","思考",length);service.completeJava(r.getId(),NODE,op.getId());service.completeJava(r.getId(),NODE,op.getId());
            var stored=rows.javaRound(r.getId(),1,NODE);assertEquals("COMPLETED",stored.get("state"));assertEquals(2,((Number)stored.get("revision")).intValue());var item=JSON.parseObject((String)stored.get("itemJson"));assertEquals("原文本",item.getJSONArray("llmResponses").getJSONObject(0).get("content"));assertEquals("思考",item.getJSONArray("llmThinking").getJSONObject(0).get("content"));assertEquals(length?"FAILED":"COMPLETED",models.find(op.getId()).getState());}
    }
    @Test void whitespaceThinkingIsNotStoredByOriginalNonblankRule()throws Exception {var r=run("值",params("系统","问题",0),CHAT,42,1,2);open(r);var op=model(r,"原文本","   ",false);service.completeJava(r.getId(),NODE,op.getId());assertTrue(JSON.parseObject((String)rows.javaRound(r.getId(),1,NODE).get("itemJson")).getJSONArray("llmThinking").isEmpty());}
    @Test void ownerSpaceWorkflowAndChatWindowsStaySeparate()throws Exception {
        var base=run("值",params("系统","问题",0),CHAT,42,1,2);open(base);
        for(var scope:List.of(List.of(43L,1L,2L),List.of(42L,2L,2L),List.of(42L,1L,3L))){var other=run("值",params("系统","问题",2),CHAT,scope.get(0),scope.get(1),scope.get(2));assertTrue(open(other).getJSONArray("history").isEmpty());}
        assertTrue(open(run("值",params("系统","问题",2),"different",42,1,2)).getJSONArray("history").isEmpty());
    }
    @Test void requestCannotDeclareMessagesScopeOrUnsupportedEngine()throws Exception {var r=run("值",params("系统","问题",2),CHAT,42,1,2);var req=request(r);req.put("answer","伪答案");assertThrows(ServiceException.class,()->service.openJava(r.getId(),worker,req));r.setEngineProfile("python");assertEquals(403,assertThrows(ServiceException.class,()->open(r)).getCode());assertNull(rows.javaRound(r.getId(),1,NODE));}
    @Test void inactiveOrUnbegunLeaseCannotWrite()throws Exception {var r=run("值",params("系统","问题",2),CHAT,42,1,2);r.setState("COMPLETED");assertEquals(410,assertThrows(ServiceException.class,()->open(r)).getCode());r.setState("RUNNING");r.setExecutionStartedAt(null);assertThrows(ServiceException.class,()->open(r));assertNull(rows.javaRound(r.getId(),1,NODE));}
    @Test void missingUpstreamCompletionCannotOpenHalfRound()throws Exception {var r=run("值",params("系统","问题",2),CHAT,42,1,2);events.put(r.getId(),List.of());assertEquals(403,assertThrows(ServiceException.class,()->open(r)).getCode());assertNull(rows.javaRound(r.getId(),1,NODE));}
    @Test void changedInputLeaseAndTamperedSnapshotCannotAuthorize()throws Exception {var r=run("值",params("系统","问题",2),CHAT,42,1,2);open(r);assertEquals(403,assertThrows(ServiceException.class,()->service.javaSnapshot(r,node(r),Map.of("x","伪输入"))).getCode());String lease=r.getLeaseId();r.setLeaseId("new-lease");assertEquals(409,assertThrows(ServiceException.class,()->snapshot(r)).getCode());r.setLeaseId(lease);try(var sql=connection.createStatement()){sql.execute("UPDATE paiflow_java_node_history SET snapshot_json='{}'");}assertEquals(409,assertThrows(ServiceException.class,()->open(r)).getCode());}
    @Test void incompleteOrChangedFramesCannotInventAssistant()throws Exception {var r=run("值",params("系统","问题",0),CHAT,42,1,2);open(r);var op=model(r,"原文本","思考",false);try(var sql=connection.prepareStatement("DELETE FROM paiflow_model_frame WHERE operation_id=? AND seq=2")){sql.setString(1,op.getId());sql.executeUpdate();}assertEquals(403,assertThrows(ServiceException.class,()->service.completeJava(r.getId(),NODE,op.getId())).getCode());assertEquals("OPEN",rows.javaRound(r.getId(),1,NODE).get("state"));}
    @Test void failedOpenInsertRollsBackAndRetryUsesOneNaturalRound()throws Exception {var r=run("值",params("系统","问题",0),CHAT,42,1,2);var failing=spy(rows);doThrow(new IllegalStateException("isolated insert failure")).when(failing).openJava(anyString(),anyString(),anyInt(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());var broken=new RunHistoryService(failing,runs,leases,access);broken.useJavaSources(provider(sources),spaces,models);assertThrows(IllegalStateException.class,()->tx(broken).openJava(r.getId(),worker,request(r)));assertNull(rows.javaRound(r.getId(),1,NODE));same(open(r),open(r));assertEquals(1,rows.javaRecent(1,2,42,"debug:2",CHAT,NODE).size());}
    @Test void wrongResourceWorkerOptionsCandidateOrHistoryCannotFillTrustedRound()throws Exception {
        for(String kind:List.of("resourceVersion","worker","options","candidateEngine","historyRef","noCandidate")) {
            var r=run("值",params("系统","问题",0),CHAT,42,1,2);open(r);var op=model(r,"原文本","思考",false);
            String column,value;
            if(kind.equals("resourceVersion")){column="resource_version";value="999";}
            else if(kind.equals("worker")){column="worker_id";value="other-worker";}
            else if(kind.equals("options")){column="parameters_json";var p=JSON.parseObject(op.getParametersJson());p.getJSONObject("options").put("temperature",99);value=RunJsonBoundary.canonicalFull(p);}
            else{column="approval_json";var a=JSON.parseObject(op.getApprovalJson());if(kind.equals("noCandidate"))a=new JSONObject();else if(kind.equals("candidateEngine"))a.getJSONObject("candidate").put("engineProfile","python");else{var h=JSON.parseObject(a.getJSONObject("candidate").getString("javaHistoryJson"));h.put("historyRef","999");a.getJSONObject("candidate").put("javaHistoryJson",RunJsonBoundary.canonicalFull(h));}value=RunJsonBoundary.canonicalFull(a);}
            try(var sql=connection.prepareStatement("UPDATE paiflow_model_operation SET "+column+"=? WHERE id=?")){sql.setString(1,value);sql.setString(2,op.getId());sql.executeUpdate();}
            assertEquals(403,assertThrows(ServiceException.class,()->service.completeJava(r.getId(),NODE,op.getId()),kind).getCode());var round=rows.javaRound(r.getId(),1,NODE);assertEquals("OPEN",round.get("state"));assertEquals(1,((Number)round.get("revision")).intValue());
        }
    }
    @Test void wholeResponseBudgetRollsBackNewHalfRoundWithoutTruncation()throws Exception {
        var old=run("值",params("系统","问题",0),CHAT,42,1,2);open(old);var op=model(old,"a".repeat(800*1024),"",false);service.completeJava(old.getId(),NODE,op.getId());
        var next=run("值",params("新系统","新问题",1),CHAT,42,1,2);assertEquals(413,assertThrows(ServiceException.class,()->open(next)).getCode());assertNull(rows.javaRound(next.getId(),1,NODE));assertEquals("COMPLETED",rows.javaRound(old.getId(),1,NODE).get("state"));
    }
    @Test void completedRoundSurvivesLaterRunFailure()throws Exception {var r=run("值",params("系统","问题",0),CHAT,42,1,2);open(r);var op=model(r,"已完成答案","思考",false);service.completeJava(r.getId(),NODE,op.getId());r.setState("FAILED");var next=run("值",params("系统","问题",1),CHAT,42,1,2);var previous=open(next).getJSONArray("history").getJSONObject(0);assertEquals("已完成答案",previous.getJSONArray("llmResponses").getJSONObject(0).get("content"));}
    @Test void legacyPythonAppendWithoutModelFactsCannotInventTrustedHistory()throws Exception {
        // 当前Python前提使用原Factory的Start完成帧及034冻结；不能借Java事件形状触发更早的无关拒绝。
        var python=new PythonRunHistoryPersistenceTest();python.setup();try{var r=python.run("问题",CHAT);python.read(r);
            var body=JSONObject.of("leaseId",r.getLeaseId(),"attempt",1,"nodeId",PythonRunHistoryPersistenceTest.NODE,"question",JSONObject.of("role","user","content","ask=问题"),"answer",JSONObject.of("role","assistant","content","回答"),"idempotencyKey","python-old-key");
            assertEquals(403,assertThrows(ServiceException.class,()->python.append(r,body)).getCode());
            same(JSONObject.of("history",new JSONArray(),"historyV2",new JSONArray()),python.f.service.read(r.getId(),python.f.pythonWorker,python.request(r)));assertNull(python.f.rows.javaRound(r.getId(),1,PythonRunHistoryPersistenceTest.NODE));
        }finally{python.close();}
    }
}
