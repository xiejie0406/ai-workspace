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
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;

/** 真实021/034/013 Mapper及事务，原Factory输入事件/原流帧；租约隔离端口，不算真实Worker或Provider联机。 */
class PythonRunHistoryPersistenceTest {
    JavaRunHistoryPersistenceTest f;JSONObject gold;
    ModelOperationService calls,target;ManagedModelInvocationPolicy policy;
    java.util.concurrent.atomic.AtomicReference<PrivateGrant> grant=new java.util.concurrent.atomic.AtomicReference<>();
    static final String NODE="spark-llm::model";
    @BeforeEach void setup()throws Exception {
        f=new JavaRunHistoryPersistenceTest();f.setup();
        gold=JSON.parseObject(JavaRunHistoryPersistenceTest.resource("/fixtures/llm-output-projection-golden.json")).getJSONArray("cases").getJSONObject(0);
        try(var sql=f.connection.createStatement()){
            sql.execute("ALTER TABLE paiflow_run ADD COLUMN assignment_json LONGTEXT");
            sql.execute("CREATE TABLE paiflow_run_event(run_id VARCHAR(36),seq BIGINT,event_json LONGTEXT,PRIMARY KEY(run_id,seq))");
            ddl(sql,"034-paiflow-python-history-snapshot.sql");
        }
        f.sources.useHistory(JavaRunHistoryPersistenceTest.provider(f.service));
        var catalog=mock(ModelRuntimeCatalog.class);var fixed=mock(ModelRuntimeCatalog.FixedModel.class);
        when(catalog.resolvePinned(eq(1L),eq(42L),eq("model:1:version:2"),any())).thenReturn(fixed);
        when(fixed.provider()).thenReturn("xinghuo");when(fixed.domain()).thenReturn("model-fixture");when(fixed.modelVersionId()).thenReturn(2L);when(fixed.capabilities()).thenReturn(Set.of("chat"));
        when(fixed.credentialRef()).thenReturn("isolated-python-history-reference");when(fixed.credentialVersion()).thenReturn("1");when(fixed.privateBaseUrl()).thenReturn("https://no-network.invalid/v1");
        var runtime=mock(ManagedModelRuntime.class);var profile=new ModelRuntimeProperties.Profile();profile.setEnabled(true);profile.setInvocationAllowed(true);profile.setRuntimeProvider("xinghuo");profile.setSourceProvider("xinghuo");profile.setDomain("model-fixture");profile.setCredentialRef("isolated-python-history-reference");profile.setAllowedUserIds(List.of(42L));profile.setAllowedSpaceIds(List.of(1L));profile.setMaxCalls(50);profile.setMaxTokens(10000000);profile.setMaxRequestBytes(ModelOperationJson.REQUEST_BYTES);profile.setTimeoutSeconds(300);when(runtime.profile(fixed)).thenReturn(profile);
        policy=new ManagedModelInvocationPolicy(runtime,catalog,f.models);policy.useHistory(JavaRunHistoryPersistenceTest.provider(f.service),f.runs);
        target=new ModelOperationService(f.models,f.runs,f.spaces,f.leases,catalog);target.useCompletedSources(f.sources);target.useHistory(JavaRunHistoryPersistenceTest.provider(f.service));calls=proxy(target);
        var credentials=new CredentialMaterialPort(){public boolean available(ModelRuntimeCatalog.FixedModel m){return true;}public PrivateMaterial resolve(ModelRuntimeCatalog.FixedModel m){return new PrivateMaterial(m.credentialRef(),m.credentialVersion(),m.privateBaseUrl(),"isolated-no-network-key");}};
        var bridge=new ProducerBridge(){public boolean available(ModelRuntimeCatalog.FixedModel m){return true;}public DispatchAck submit(PrivateGrant g,ProducerCallbacks cb){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());grant.set(g);return new DispatchAck(g.operationId(),g.grantId(),true);}public StopEvidence stop(String op,String g,String reason){return null;}};
        target.usePorts(JavaRunHistoryPersistenceTest.provider(policy),JavaRunHistoryPersistenceTest.provider(credentials),JavaRunHistoryPersistenceTest.provider(bridge),JavaRunHistoryPersistenceTest.provider(calls));
    }
    <T>T proxy(T value){var p=new org.springframework.aop.framework.ProxyFactory(value);p.setProxyTargetClass(true);p.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.db),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    static void ddl(Statement sql,String file)throws Exception {
        String text=Files.readString(Path.of("sql",file)).replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","")
                .replace(" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin","")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        for(String part:text.split(";"))if(!part.isBlank())sql.execute(part);
    }
    @AfterEach void close()throws Exception{f.close();}
    RunRecord run(String value,String chat)throws Exception {
        var r=new RunRecord();r.setId(UUID.randomUUID().toString());r.setLeaseId(UUID.randomUUID().toString());r.setAttempt(1);r.setState("RUNNING");
        r.setExecutionStartedAt(LocalDateTime.now());r.setSpaceId(1L);r.setWorkflowId(2L);r.setWorkflowVersionId(3L);r.setOwnerUserId(42L);r.setEngineProfile("python");r.setWorkerId(f.pythonWorker.id());r.setLastEventSeq(5L);
        var task=ModelOperationJson.copy(gold.getJSONObject("assignment"));task.put("runId",r.getId());task.remove("leaseId");task.remove("attempt");task.remove("lastEventSeq");task.put("workflowVersionId","3");task.put("input",JSONObject.of("question",value));
        var node=DataWorkerOperationService.find(task.getJSONObject("dsl"),NODE);var params=node.getJSONObject("data").getJSONObject("nodeParam");params.put("enableChatHistory",true);params.put("enableChatHistoryV2",JSONObject.of("isEnabled",false,"rounds",1));params.put("modelResourceId","model:1:version:2");
        var descriptor=task.getJSONObject("resourceSnapshots").getJSONObject("model-1");descriptor.put("version","2");descriptor.put("capabilities",new JSONArray(List.of("chat")));task.put("resourceSnapshots",JSONObject.of("model:1:version:2",descriptor));
        task.put("dslHash",RunJsonBoundary.hashFull(task.getJSONObject("dsl")));r.setAssignmentJson(RunJsonBoundary.canonicalFull(task));f.records.put(r.getId(),r);
        try(var sql=f.connection.prepareStatement("INSERT INTO paiflow_run(id,assignment_json) VALUES(?,?)")){sql.setString(1,r.getId());sql.setString(2,r.getAssignmentJson());sql.executeUpdate();}
        var event=ModelOperationJson.copy(gold.getJSONArray("actualEvents").getJSONObject(1));event.put("runId",r.getId());event.put("leaseId",r.getLeaseId());var frame=event.getJSONObject("payload").getJSONObject("legacyFrame");frame.put("id",r.getId());frame.getJSONObject("workflow_step").getJSONObject("node").put("inputs",JSONObject.of("question",value));
        f.events.put(r.getId(),List.of(new RunMapper.EventRow(5,RunJsonBoundary.canonicalFull(event))));
        try(var sql=f.connection.prepareStatement("INSERT INTO paiflow_run_event(run_id,seq,event_json) VALUES(?,?,?)")){sql.setString(1,r.getId());sql.setLong(2,5);sql.setString(3,RunJsonBoundary.canonicalFull(event));sql.executeUpdate();}
        assertEquals(1,f.rows.bind(r.getId(),1,2,42,"original-model-flow","debug:2",chat));r.setDeadline(LocalDateTime.ofInstant(Instant.now().plusSeconds(300),ZoneOffset.UTC));
        r.setModelPermitJson(RunJsonBoundary.canonicalFull(calls.admit(r.getId(),42,1,3,task.getString("dslHash"),task.getJSONObject("input"),task.getJSONObject("resourceSnapshots"),task.getJSONObject("dsl"),r.getDeadline().toInstant(ZoneOffset.UTC),"python")));return r;
    }
    JSONObject node(RunRecord r){return DataWorkerOperationService.find(ModelMessageSources.sourceObject(r.getAssignmentJson()).getJSONObject("dsl"),NODE);}
    void change(RunRecord r,java.util.function.Consumer<JSONObject> mutation)throws Exception {
        var task=ModelMessageSources.sourceObject(r.getAssignmentJson());mutation.accept(DataWorkerOperationService.find(task.getJSONObject("dsl"),NODE));task.put("dslHash",RunJsonBoundary.hashFull(task.getJSONObject("dsl")));r.setAssignmentJson(RunJsonBoundary.canonicalFull(task));
        try(var sql=f.connection.prepareStatement("UPDATE paiflow_run SET assignment_json=? WHERE id=?")){sql.setString(1,r.getAssignmentJson());sql.setString(2,r.getId());sql.executeUpdate();}
        r.setModelPermitJson(RunJsonBoundary.canonicalFull(calls.admit(r.getId(),42,1,3,task.getString("dslHash"),task.getJSONObject("input"),task.getJSONObject("resourceSnapshots"),task.getJSONObject("dsl"),r.getDeadline().toInstant(ZoneOffset.UTC),"python")));
    }
    JSONObject request(RunRecord r){return JSONObject.of("leaseId",r.getLeaseId(),"attempt",1);}
    JSONObject read(RunRecord r){return f.service.readPython(r.getId(),f.pythonWorker,request(r));}
    Map<String,Object> inputs(RunRecord r){return f.sources.values(r,ModelMessageSources.sourceObject(r.getAssignmentJson()).getJSONObject("dsl"),node(r),new JSONArray(f.events.get(r.getId()).stream().map(e->ModelMessageSources.sourceObject(e.eventJson())).toList()));}
    ModelOperationRecord model(RunRecord r,String answer)throws Exception {
        var n=node(r);var values=inputs(r);
        var parameters=ModelOperationJson.copy(gold.getJSONArray("actualOperations").getJSONArray(0).getJSONObject(5));parameters.put("resourceId","model:1:version:2");parameters.put("resourceVersion","2");parameters.put("messages",ModelMessageSources.expected(n,values,new JSONArray()));parameters.put("options",ModelOperationOptions.forNode(n));
        var raw=ModelOperationJson.copy(JSONObject.of("frames",gold.getJSONArray("sourceFrames"))).getJSONArray("frames");
        if(answer!=null){var template=ModelOperationJson.copy(raw.getJSONObject(0));var last=raw.getJSONObject(1);raw.clear();
            for(int offset=0;offset<answer.length();offset+=40000){var chunk=ModelOperationJson.copy(template);chunk.getJSONObject("payload").getJSONObject("choices").getJSONArray("text").getJSONObject(0).put("content",answer.substring(offset,Math.min(offset+40000,answer.length())));if(offset>0)chunk.getJSONObject("payload").getJSONObject("choices").getJSONArray("text").getJSONObject(0).put("reasoning_content","");raw.add(chunk);}
            last.getJSONObject("payload").getJSONObject("choices").getJSONArray("text").getJSONObject(0).put("content","");raw.add(last);}
        var request=request(r);request.put("nodeId",NODE);request.put("iterationId",null);request.put("operation","model.invoke");request.put("idempotencyKey","model_"+r.getId());request.put("parameters",parameters);
        assertEquals("RUNNING",calls.start(r.getId(),f.pythonWorker,request).get("state"));
        for(int i=0;i<raw.size();i++)calls.commitFrame(grant.get(),i+1,raw.getJSONObject(i));calls.finishStream(grant.get(),raw.size(),raw.size(),"stop");
        var row=f.models.find(grant.get().operationId());assertEquals("COMPLETED",row.getState());return row;
    }
    JSONObject appendRequest(RunRecord r){var completed=f.sources.pythonHistory(r,node(r),inputs(r));var body=request(r);body.put("nodeId",NODE);body.put("idempotencyKey","original-append-key");body.put("question",completed.get("question"));body.put("answer",completed.get("answer"));return body;}
    JSONObject append(RunRecord r,JSONObject body){return f.service.append(r.getId(),f.pythonWorker,body);}
    void same(Object a,Object b){assertTrue(ToolSchema.same(a,b),()->JSON.toJSONString(a)+" != "+JSON.toJSONString(b));}
    int code(Runnable call){return assertThrows(ServiceException.class,call::run).getCode();}
    long count(String table)throws Exception{try(var sql=f.connection.createStatement();var result=sql.executeQuery("SELECT COUNT(*) FROM "+table)){result.next();return result.getLong(1);}}

    @Test void originalFramesAppendBeforeCompletedEventAndFrozenReplayPreserveEnvelope()throws Exception {
        var first=run("原始问题","chat");first.setExecutionStartedAt(null);var empty=read(first);assertEquals(Set.of("history","historyV2","historyRef","historyHash","snapshot"),empty.keySet());assertEquals(15,empty.getJSONObject("snapshot").size());assertTrue(empty.getJSONArray("history").isEmpty());first.setExecutionStartedAt(LocalDateTime.now());
        var op=model(first,null);var body=appendRequest(first);same(JSONObject.of("stored",true,"revision",1),append(first,body));same(append(first,body),append(first,body));assertEquals(1,count("paiflow_node_history"));assertEquals(1,count("paiflow_python_history_evidence"));
        // 没有LLM完成事件，也没有End事件；源只要求原模型执行成功，raw answer不拼thinking。
        assertEquals(1,f.events.get(first.getId()).size());assertEquals(gold.getJSONArray("actualNodeCompletedFrames").getJSONObject(0).getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").getJSONObject("outputs").get("first"),body.getJSONObject("answer").get("content"));assertEquals(op.getId(),f.rows.pythonEvidence(first.getId(),1,ModelOperationJson.sha("original-append-key")).get("modelOperationId"));
        var second=run("第二问","chat");var frozen=read(second);assertEquals(1,frozen.getJSONArray("history").size());same(new JSONArray(List.of(body.get("question"),body.get("answer"))),frozen.getJSONArray("history").getJSONObject(0).getJSONArray("chat_history"));same(frozen,read(second));same(empty,read(first));
        var display=f.service.read(second.getId(),f.pythonWorker,request(second));assertEquals(Set.of("history","historyV2"),display.keySet());same(display.get("history"),frozen.get("history"));
    }
    @Test void fakeAnswerOrProofOrModelFramesCannotPersistHistory()throws Exception {
        var r=run("q","chat");read(r);var row=model(r,null);var body=appendRequest(r);body.getJSONObject("answer").put("content","worker-forged");assertEquals(403,code(()->append(r,body)));assertEquals(0,count("paiflow_node_history"));
        var legitimate=appendRequest(r);var candidate=ModelMessageSources.sourceObject(row.getApprovalJson());candidate.getJSONObject("candidate").put("pythonHistoryJson","{}");try(var sql=f.connection.prepareStatement("UPDATE paiflow_model_operation SET approval_json=? WHERE id=?")){sql.setString(1,RunJsonBoundary.canonicalFull(candidate));sql.setString(2,row.getId());sql.executeUpdate();}assertEquals(403,code(()->append(r,legitimate)));assertEquals(0,count("paiflow_python_history_evidence"));
    }
    @Test void originalWindowContainingUnverifiedLegacyRoundIsExplicitlyBlocked()throws Exception {
        var old=run("old","chat");assertEquals(1,f.rows.append(old.getId(),1,NODE,ModelOperationJson.sha("old"),"unverified", "{\"role\":\"user\",\"content\":\"q\"}","{\"role\":\"assistant\",\"content\":\"a\"}"));
        var next=run("next","chat");assertEquals(503,code(()->read(next)));assertNull(f.rows.pythonSnapshot(next.getId(),1));assertEquals(1,f.service.read(next.getId(),f.pythonWorker,request(next)).getJSONArray("history").size());
    }
    @Test void snapshotScopeInputAndHashTamperingCannotBeReplayed()throws Exception {
        var r=run("q","chat");var frozen=read(r);var snapshot=ModelOperationJson.copy(frozen.getJSONObject("snapshot"));snapshot.put("chatId","different");try(var sql=f.connection.prepareStatement("UPDATE paiflow_python_history_snapshot SET snapshot_json=?,snapshot_hash=? WHERE run_id=?")){sql.setString(1,RunJsonBoundary.canonicalFull(snapshot));sql.setString(2,RunJsonBoundary.hashFull(snapshot));sql.setString(3,r.getId());sql.executeUpdate();}assertEquals(409,code(()->read(r)));
    }
    @Test void currentLeaseBegunTopLevelAndEngineAreRechecked()throws Exception {
        var r=run("q","chat");var req=request(r);req.put("leaseId","wrong");assertEquals(403,code(()->f.service.readPython(r.getId(),f.pythonWorker,req)));read(r);model(r,null);var body=appendRequest(r);r.setExecutionStartedAt(null);assertEquals(409,code(()->append(r,body)));r.setExecutionStartedAt(LocalDateTime.now());r.setEngineProfile("java");assertEquals(403,code(()->append(r,body)));assertEquals(0,count("paiflow_node_history"));
    }
    @Test void immutableReplayDoesNotReselectOrReverifyNewRows()throws Exception {
        var r=run("q","chat");var empty=read(r);model(r,null);append(r,appendRequest(r));same(empty,read(r));var next=run("next","chat");var frozen=read(next);
        try(var sql=f.connection.createStatement()){sql.execute("UPDATE paiflow_python_history_evidence SET evidence_hash='broken'");}same(frozen,read(next));var later=run("later","chat");assertEquals(409,code(()->read(later)));
    }
    @Test void evidenceInsertFailureRollsBackQuestionAnswerTogether()throws Exception {
        var r=run("q","chat");read(r);model(r,null);var body=appendRequest(r);try(var sql=f.connection.createStatement()){sql.execute("ALTER TABLE paiflow_python_history_evidence ADD CONSTRAINT reject_fixture CHECK(attempt<0)");}assertThrows(RuntimeException.class,()->append(r,body));assertEquals(0,count("paiflow_node_history"));assertEquals(0,count("paiflow_python_history_evidence"));
    }
    @Test void oldStreamCorruptionIsNotTrustedFromEvidenceOrCurrentMutableRows()throws Exception {
        var r=run("q","chat");read(r);var op=model(r,null);append(r,appendRequest(r));try(var sql=f.connection.prepareStatement("UPDATE paiflow_model_frame SET frame_hash='broken' WHERE operation_id=?")){sql.setString(1,op.getId());sql.executeUpdate();}var next=run("next","chat");assertEquals(403,code(()->read(next)));assertNull(f.rows.pythonSnapshot(next.getId(),1));
    }
    @Test void realSqlByteCostIgnoresSelfReportedStreamSize()throws Exception {
        var r=run("q","chat");read(r);var op=model(r,null);var actual=f.rows.pythonOperationCost(op.getId());assertTrue(((Number)actual.get("bytes")).longValue()>op.getResponseBytes());
        try(var sql=f.connection.prepareStatement("UPDATE paiflow_model_operation SET response_bytes=1 WHERE id=?")){sql.setString(1,op.getId());sql.executeUpdate();}same(actual,f.rows.pythonOperationCost(op.getId()));assertEquals(2,((Number)actual.get("count")).intValue());assertTrue(((Number)f.rows.pythonRunCost(r.getId()).get("bytes")).longValue()>r.getAssignmentJson().getBytes(StandardCharsets.UTF_8).length);
    }
    @Test void exactlyOriginalLatestTenRoundsAreSelectedThenRestoredChronologically()throws Exception {
        var bodies=new ArrayList<JSONObject>();
        for(int i=0;i<11;i++){var r=run("question"+i,"ten-round-chat");read(r);model(r,"answer"+i);var body=appendRequest(r);append(r,body);bodies.add(body);}
        var next=run("next","ten-round-chat");var frozen=read(next);var messages=frozen.getJSONArray("history").getJSONObject(0).getJSONArray("chat_history");assertEquals(20,messages.size());
        for(int i=1;i<11;i++){same(bodies.get(i).get("question"),messages.get((i-1)*2));same(bodies.get(i).get("answer"),messages.get((i-1)*2+1));}
        assertEquals(11,count("paiflow_node_history"));
    }
    @Test void rawFalseAndZeroStringsAppendButDoNotReadOldDisplayIntoProvider()throws Exception {
        for(String flag:List.of("false","0")){var r=run(flag,"raw-flag-"+flag);change(r,n->n.getJSONObject("data").getJSONObject("nodeParam").put("enableChatHistory",flag));var frozen=read(r);assertTrue(frozen.getJSONArray("history").isEmpty());model(r,null);append(r,appendRequest(r));
            var next=run("next","raw-flag-"+flag);change(next,n->n.getJSONObject("data").getJSONObject("nodeParam").put("enableChatHistory",flag));assertTrue(read(next).getJSONArray("history").isEmpty());assertTrue(f.service.pythonSnapshot(next,node(next)).getJSONArray("history").isEmpty());
            var reader=run("enabled","raw-flag-"+flag);assertEquals(1,read(reader).getJSONArray("history").size());}
        assertEquals(2,count("paiflow_node_history"));
    }
    @Test void rawOutputCanAppendBeforeLaterRequiredOutputTypeFailure()throws Exception {
        var r=run("q","required-failure-chat");change(r,n->{n.getJSONObject("data").getJSONObject("nodeParam").put("respFormat",2);n.getJSONObject("data").getJSONArray("outputs").getJSONObject(0).put("schema",JSONObject.of("type","object","properties",JSONObject.of("known",JSONObject.of("type","string"))));});read(r);var op=model(r,"{\"first\":{\"unregistered\":\"value\"}}");var body=appendRequest(r);append(r,body);assertEquals(1,count("paiflow_node_history"));
        var frames=new JSONArray(f.models.frames(op.getId(),0,ModelOperationJson.MAX_FRAMES).stream().map(frame->ModelMessageSources.sourceObject(frame.getFrameJson())).toList());
        assertThrows(ServiceException.class,()->CompletedModelOutputs.requiredTypes(node(r),CompletedModelOutputs.project(node(r),frames)));
    }
    @Test void wholeRepeatedHistoryAckBudgetRollsBackNewSnapshotWithoutTruncation()throws Exception {
        var old=run("old","large-ack");read(old);model(old,"a".repeat(950000));append(old,appendRequest(old));var next=run("next","large-ack");assertEquals(413,code(()->read(next)));assertNull(f.rows.pythonSnapshot(next.getId(),1));assertEquals(1,count("paiflow_node_history"));assertEquals(1,count("paiflow_python_history_evidence"));
    }
    @Test void actualSql128MiBPreflightRejectsOversizedStoredSourceBeforeReadingEvents()throws Exception {
        var old=run("q","large-source");read(old);model(old,null);append(old,appendRequest(old));var next=run("next","large-source");
        // 显式破坏持久assignment字节而不篡改自报response_bytes；证明SQL实际预算先于事件/帧加载，非合法Provider样本。
        try(var sql=f.connection.prepareStatement("UPDATE paiflow_run SET assignment_json=REPEAT('x',134217729) WHERE id=?")){sql.setString(1,old.getId());sql.executeUpdate();}
        assertTrue(((Number)f.rows.pythonRunCost(old.getId()).get("bytes")).longValue()>128L*1024*1024);clearInvocations(f.runs);assertEquals(413,code(()->read(next)));verify(f.runs,never()).eventsThrough(eq(old.getId()),anyLong(),anyLong(),anyInt());assertNull(f.rows.pythonSnapshot(next.getId(),1));
    }
    @Test void actualUpstreamAuxiliaryLedgerCostsUseStringsNotReportedCounters()throws Exception {
        var r=run("q","auxiliary-budget");try(var sql=f.connection.createStatement()){
            sql.execute("CREATE TABLE paiflow_tool_operation(run_id VARCHAR(36),request_json LONGTEXT,binding_json LONGTEXT,response_json LONGTEXT)");
            sql.execute("CREATE TABLE paiflow_data_request(run_id VARCHAR(36),response_json LONGTEXT)");sql.execute("CREATE TABLE paiflow_data_completion(run_id VARCHAR(36),completion_json LONGTEXT)");
            sql.execute("CREATE TABLE paiflow_variable_source(run_id VARCHAR(36),request_json LONGTEXT,response_json LONGTEXT)");
            sql.execute("INSERT INTO paiflow_tool_operation VALUES('"+r.getId()+"','{}','{}','returned')");sql.execute("INSERT INTO paiflow_data_request VALUES('"+r.getId()+"','response')");sql.execute("INSERT INTO paiflow_data_completion VALUES('"+r.getId()+"','completion')");sql.execute("INSERT INTO paiflow_variable_source VALUES('"+r.getId()+"','input','snapshot')");}
        assertEquals(12,((Number)f.rows.pythonToolCost(r.getId()).get("bytes")).intValue());assertEquals(18,((Number)f.rows.pythonDataCost(r.getId()).get("bytes")).intValue());assertEquals(13,((Number)f.rows.pythonVariableCost(r.getId()).get("bytes")).intValue());
    }
    @Test void sameOperationCannotBecomeMultipleHistoryRoundsByChangingNaturalKey()throws Exception {
        var r=run("q","operation-unique");read(r);model(r,null);var body=appendRequest(r);var ack=append(r,body);same(ack,append(r,body));
        var changed=ModelOperationJson.copy(body);changed.put("idempotencyKey","different-history-key");ServiceException rejected=null;
        try{append(r,changed);}catch(ServiceException exception){rejected=exception;}
        assertEquals(1,count("paiflow_node_history"),"同一实际模型操作不得占据多轮历史；真实Mapper原位统计");
        assertEquals(1,count("paiflow_python_history_evidence"));assertNotNull(rejected);assertEquals(409,rejected.getCode());same(ack,append(r,body));
    }
    @Test void sqlOperationUniquenessRollsBackConcurrentNaturalKeys()throws Exception {
        var r=run("q","concurrent-operation");read(r);var model=model(r,null);var body=appendRequest(r);append(r,body);
        var original=f.rows.pythonEvidence(r.getId(),1,ModelOperationJson.sha("original-append-key"));var value=JSON.parseObject((String)original.get("evidenceJson"));
        try(var sql=f.connection.createStatement()){sql.execute("DELETE FROM paiflow_python_history_evidence");sql.execute("DELETE FROM paiflow_node_history");}
        // 有意绕过Service的空间串行锁，单独证明最后数据库约束与问答/证据同事务能抵御两个不同键。
        var barrier=new java.util.concurrent.CyclicBarrier(2);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var futures=new ArrayList<java.util.concurrent.Future<String>>();for(int i=0;i<2;i++){final String key=ModelOperationJson.sha("race-"+i);futures.add(pool.submit(()->{
                var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.db));transaction.setTimeout(15);
                try{return transaction.execute(status->{assertEquals(1,f.rows.append(r.getId(),1,NODE,key,value.getString("requestHash"),RunJsonBoundary.canonicalFull(body.getJSONObject("question")),RunJsonBoundary.canonicalFull(body.getJSONObject("answer"))));
                    try{barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception error){throw new IllegalStateException(error);}
                    assertEquals(1,f.rows.recordPythonEvidence(r.getId(),1,key,model.getId(),(String)original.get("evidenceJson"),(String)original.get("evidenceHash")));return "committed";
                });}catch(org.springframework.dao.DuplicateKeyException conflict){return "unique-conflict";}
            }));}
            var outcomes=new ArrayList<String>();for(var future:futures)outcomes.add(future.get(20,java.util.concurrent.TimeUnit.SECONDS));Collections.sort(outcomes);assertEquals(List.of("committed","unique-conflict"),outcomes);
        }finally{pool.shutdownNow();}
        assertEquals(1,count("paiflow_node_history"));assertEquals(1,count("paiflow_python_history_evidence"));assertNotNull(f.rows.pythonEvidenceByOperation(model.getId()));
    }
    @Test void validHistoryAckDoesNotAuthorizeOversizedCombinedPrivateCandidate()throws Exception {
        var old=run("old","combined-private-budget");read(old);model(old,"a".repeat(850000));append(old,appendRequest(old));
        var next=run("current".repeat(65000),"combined-private-budget");
        RunJsonBoundary.check(ModelMessageSources.sourceObject(next.getAssignmentJson()).getJSONObject("input"),RunProtocolLimits.INPUT_BYTES);
        for(var event:f.events.get(next.getId()))RunJsonBoundary.check(ModelMessageSources.sourceObject(event.eventJson()),RunProtocolLimits.EVENT_BYTES);
        var frozen=read(next);assertTrue(RunJsonBoundary.canonicalFull(frozen).getBytes(StandardCharsets.UTF_8).length<1800*1024);
        assertEquals(850000,frozen.getJSONArray("history").getJSONObject(0).getJSONArray("chat_history").getJSONObject(1).getString("content").length());
        var oldGrant=grant.get().operationId();var error=assertThrows(ServiceException.class,()->model(next,null));assertEquals(413,error.getCode());
        assertTrue(f.models.forRunResource(next.getId(),"model:1:version:2").isEmpty());assertEquals(oldGrant,grant.get().operationId());same(frozen,read(next));
    }
}
