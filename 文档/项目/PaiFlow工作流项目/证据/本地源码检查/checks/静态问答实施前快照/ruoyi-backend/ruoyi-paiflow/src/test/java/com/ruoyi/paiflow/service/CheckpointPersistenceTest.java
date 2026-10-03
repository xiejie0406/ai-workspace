package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.domain.SpaceMember;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.CheckpointMapper;
import com.ruoyi.paiflow.mapper.RunMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.service.ISysUserService;
import java.io.InputStream;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实Mapper/行锁/事务；运行与账号是隔离夹具，不代表QA资源图已可从正式中心派发。 */
class CheckpointPersistenceTest {
    private JdbcDataSource source;private RunMapper runs;private CheckpointMapper checkpoints;private RunLeaseService leases;private CheckpointService service;
    private ResourceAccessService access;private IdentityMappingService identities;private RunRecord run;private JSONObject task;
    private SysPermissionService permissions;private SysUser user;
    private Clock clock;
    private final AtomicReference<Instant> time=new AtomicReference<>(Instant.parse("2026-10-03T00:00:00Z"));
    private final WorkerIdentityService.Principal worker=new WorkerIdentityService.Principal("worker-a","python");
    @BeforeEach void setup() throws Exception {
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.PersistenceContractTest.utcNow'");
        jdbc.execute("CREATE TABLE paiflow_run(id VARCHAR(36) PRIMARY KEY,workflow_id BIGINT,workflow_version_id BIGINT,space_id BIGINT,owner_user_id BIGINT,engine_profile VARCHAR(16),state VARCHAR(24),assignment_json CLOB,model_permit_json CLOB,input_hash VARCHAR(64),idempotency_key VARCHAR(128),worker_id VARCHAR(64),lease_id VARCHAR(36),attempt INT,lease_expires_at TIMESTAMP,execution_started_at TIMESTAMP,deadline TIMESTAMP,last_event_seq BIGINT,revision BIGINT,result_json CLOB,result_hash VARCHAR(64),create_by VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(owner_user_id,idempotency_key))");
        jdbc.execute("CREATE TABLE paiflow_run_event(run_id VARCHAR(36),seq BIGINT,event_json CLOB,create_time TIMESTAMP,PRIMARY KEY(run_id,seq))");
        jdbc.execute("CREATE TABLE paiflow_run_checkpoint(id VARCHAR(36) PRIMARY KEY,run_id VARCHAR(36),lease_id VARCHAR(36),attempt INT,node_id VARCHAR(255),iteration_id VARCHAR(255),iteration_key VARCHAR(255) NOT NULL,ordinal INT,need_reply INT,state VARCHAR(16),request_key VARCHAR(128),request_hash VARCHAR(64),question_json CLOB,resume_data_json CLOB,reply_key VARCHAR(128),reply_hash VARCHAR(64),consume_key VARCHAR(128),consume_hash VARCHAR(64),revision BIGINT,deadline TIMESTAMP,create_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(run_id,attempt,request_key),UNIQUE(run_id,attempt,node_id,iteration_key,ordinal))");
        var configuration=new Configuration(new Environment("isolated-checkpoint",new SpringManagedTransactionFactory(),source));
        for(String file:new String[]{"mapper/paiflow/RunMapper.xml","mapper/paiflow/CheckpointMapper.xml"}) {
            try(InputStream input=getClass().getClassLoader().getResourceAsStream(file)){assertNotNull(input);new XMLMapperBuilder(input,configuration,file,configuration.getSqlFragments()).parse();}
        }
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));runs=session.getMapper(RunMapper.class);checkpoints=session.getMapper(CheckpointMapper.class);
        var spaces=mock(SpaceMapper.class);var users=mock(ISysUserService.class);permissions=mock(SysPermissionService.class);
        user=new SysUser();user.setUserId(42L);user.setStatus("0");user.setDelFlag("0");when(users.selectUserById(42L)).thenReturn(user);
        when(permissions.getMenuPermission(user)).thenReturn(Set.of("paiflow:run:create","paiflow:run:query","paiflow:run:resume"));var space=new SpaceRecord();space.setDeleted(0);when(spaces.find(10L)).thenReturn(space);
        var member=new SpaceMember();member.setRole(3);member.setStatus("active");when(spaces.member(10L,42L)).thenReturn(member);
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time.get();}};
        leases=proxy(new RunLeaseService(runs,spaces,users,permissions,clock));access=mock(ResourceAccessService.class);identities=mock(IdentityMappingService.class);
        when(access.requirePermission(anyString())).thenReturn(42L);when(identities.current()).thenReturn(new LoginUser(42L,8L,user,Set.of("paiflow:run:resume")));
        service=proxy(new CheckpointService(checkpoints,runs,leases,access,identities,permissions,clock));run=queued();task=leases.claim(worker);
        leases.beginExecution(run.getId(),worker,task.getString("leaseId"),1);
    }
    @SuppressWarnings("unchecked") private <T>T proxy(T target){var value=new ProxyFactory(target);value.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));return (T)value.getProxy();}
    private RunRecord queued(){
        var value=new RunRecord();value.setId(UUID.randomUUID().toString());value.setWorkflowId(1L);value.setWorkflowVersionId(2L);value.setSpaceId(10L);value.setOwnerUserId(42L);value.setEngineProfile("python");value.setState("QUEUED");
        value.setAssignmentJson("{\"dsl\":{\"nodes\":[{\"id\":\"question-answer::a\",\"data\":{\"nodeParam\":{\"answerType\":\"direct\",\"timeout\":1,\"needReply\":true,\"directAnswer\":{\"handleResponse\":true,\"maxRetryCounts\":2}}}},{\"id\":\"question-answer::b\",\"data\":{\"nodeParam\":{\"answerType\":\"option\",\"timeout\":1,\"needReply\":false,\"optionAnswer\":[{\"id\":\"real-uuid\",\"name\":\"A\",\"type\":2,\"content_type\":\"image\",\"content\":\"{{source.image}}\"}]}}}],\"edges\":[]},\"effectPolicy\":{\"mode\":\"read_only\"}}");
        var assignment=JSON.parseObject(value.getAssignmentJson());assignment.put("workflowVersionId","2");assignment.put("dslHash",RunJsonBoundary.hash(assignment.getJSONObject("dsl")));value.setAssignmentJson(assignment.toJSONString());
        value.setInputHash("fixture");value.setIdempotencyKey(UUID.randomUUID().toString());value.setDeadline(LocalDateTime.ofInstant(time.get().plusSeconds(600),ZoneOffset.UTC));value.setCreateBy("fixture");
        assertEquals(1,runs.insert(value));return runs.find(value.getId());
    }
    private JSONObject createBody(){return JSONObject.of("leaseId",task.getString("leaseId"),"attempt",1,"nodeId","question-answer::a","ordinal",1,"question",JSONObject.of("type","direct","content","请回答"),"needReply",true,"timeoutSeconds",60,"idempotencyKey","checkpoint-create-0001");}
    private JSONObject optionBody(){var body=createBody();body.put("nodeId","question-answer::b");body.put("needReply",false);body.put("question",JSONObject.of("type","option","content","请选择","option",JSON.parseArray("[{\"id\":\"A\",\"text\":\"受控资源\",\"content_type\":\"image\"}]")));return body;}
    private JSONObject pollBody(long revision){return JSONObject.of("leaseId",task.getString("leaseId"),"attempt",1,"afterRevision",revision);}
    private JSONObject consumeBody(long revision,String key){return JSONObject.of("leaseId",task.getString("leaseId"),"attempt",1,"expectedRevision",revision,"idempotencyKey",key);}
    private JSONObject replyBody(String type,String content,String key){return JSONObject.of("expectedRevision",0,"idempotencyKey",key,"event_type",type,"content",content);}
    @Test void createPreservesQuestionAndUsesRequestAndPositionIdempotency(){
        var body=optionBody();var first=service.create(run.getId(),worker,body);assertEquals(first,service.create(run.getId(),worker,body));
        assertEquals(body.getJSONObject("question"),service.detail(run.getId(),first.getString("checkpointId")).getJSONObject("question"));
        body.put("idempotencyKey","checkpoint-different-key");assertEquals(409,assertThrows(ServiceException.class,()->service.create(run.getId(),worker,body)).getCode());
        body.put("ordinal",2);assertEquals(400,assertThrows(ServiceException.class,()->service.create(run.getId(),worker,body)).getCode());
        body.put("timeoutSeconds",61);assertEquals(400,assertThrows(ServiceException.class,()->service.create(run.getId(),worker,body)).getCode());
        assertEquals(1,new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM paiflow_run_checkpoint",Integer.class));
    }
    @Test void claimedButNotBegunWorkerCannotCreateQuestionCheckpoint(){
        var other=queued();var otherTask=leases.claim(worker);var body=createBody();body.put("leaseId",otherTask.getString("leaseId"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.create(other.getId(),worker,body)).getCode());
        assertEquals(0,new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM paiflow_run_checkpoint WHERE run_id=?",Integer.class,other.getId()));
        assertNull(runs.find(other.getId()).getExecutionStartedAt());
    }
    @Test void realReplyIsConsumedOnceWithOriginalQueueEnvelopeAndMetadata(){
        var created=service.create(run.getId(),worker,createBody());String id=created.getString("checkpointId");
        assertEquals(409,assertThrows(ServiceException.class,()->service.consume(run.getId(),id,worker,consumeBody(0,"consume-fixture-0001"))).getCode());
        var reply=replyBody("resume","A","reply-fixture-0001");assertEquals("RESUMED",service.reply(run.getId(),id,reply).getString("state"));
        assertEquals("RESUMED",service.reply(run.getId(),id,reply).getString("state"));assertFalse(service.poll(run.getId(),id,worker,pollBody(0)).containsKey("resumeData"));
        var request=consumeBody(1,"consume-fixture-0001");var consumed=service.consume(run.getId(),id,worker,request);
        assertTrue(consumed.getBooleanValue("consumed"));assertEquals(2,consumed.getIntValue("revision"));assertEquals(consumed,service.consume(run.getId(),id,worker,request));
        var receipt=service.create(run.getId(),worker,createBody());assertEquals("CONSUMED",receipt.getString("state"));assertTrue(receipt.getBooleanValue("stopped"));
        var data=consumed.getJSONObject("resumeData");assertEquals(JSONObject.of("event_type","resume","content","A"),JSON.parseObject(data.getString("message")));
        assertEquals(0,data.getJSONObject("metadata").getIntValue("retries"));assertEquals(time.get().getEpochSecond(),data.getJSONObject("metadata").getLongValue("timestamp"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.consume(run.getId(),id,worker,consumeBody(1,"consume-different-0002"))).getCode());
        var next=createBody();next.put("ordinal",2);next.put("idempotencyKey","checkpoint-create-0002");var second=service.create(run.getId(),worker,next);
        service.reply(run.getId(),second.getString("checkpointId"),replyBody("resume","继续","reply-fixture-0002"));
        assertEquals(1,service.consume(run.getId(),second.getString("checkpointId"),worker,consumeBody(1,"consume-fixture-0002")).getJSONObject("resumeData").getJSONObject("metadata").getIntValue("retries"));
    }
    @Test void leaseAttemptRunScopeAndActualUserCannotBeReplaced(){
        var created=service.create(run.getId(),worker,createBody());String id=created.getString("checkpointId");
        assertEquals(409,assertThrows(ServiceException.class,()->service.poll(run.getId(),id,new WorkerIdentityService.Principal("other-worker","python"),pollBody(0))).getCode());
        var old=pollBody(0);old.put("attempt",2);assertEquals(409,assertThrows(ServiceException.class,()->service.poll(run.getId(),id,worker,old)).getCode());
        var other=queued();var otherTask=leases.claim(worker);var request=JSONObject.of("leaseId",otherTask.getString("leaseId"),"attempt",1,"afterRevision",0);
        assertEquals(404,assertThrows(ServiceException.class,()->service.poll(other.getId(),id,worker,request)).getCode());
        when(access.requirePermission("paiflow:run:resume")).thenReturn(43L);assertEquals(403,assertThrows(ServiceException.class,()->service.reply(run.getId(),id,replyBody("resume","A","reply-fixture-0001"))).getCode());
        assertNull(checkpoints.find(id).getResumeDataJson());
    }
    @Test void requiredQuestionCannotBeIgnoredAndOriginalAbortIsPreserved(){
        var created=service.create(run.getId(),worker,createBody());String id=created.getString("checkpointId");
        assertEquals(400,assertThrows(ServiceException.class,()->service.reply(run.getId(),id,replyBody("ignore","","reply-ignore-0001"))).getCode());
        service.reply(run.getId(),id,replyBody("abort","","reply-abort-0001"));
        assertEquals("abort",JSON.parseObject(service.consume(run.getId(),id,worker,consumeBody(1,"consume-abort-0001")).getJSONObject("resumeData").getString("message")).getString("event_type"));
    }
    @Test void optionalQuestionRetainsIgnoreWithoutInventingContent(){
        var body=optionBody();String id=service.create(run.getId(),worker,body).getString("checkpointId");
        service.reply(run.getId(),id,replyBody("ignore","","reply-ignore-0001"));
        var message=JSON.parseObject(service.consume(run.getId(),id,worker,consumeBody(1,"consume-ignore-0001")).getJSONObject("resumeData").getString("message"));
        assertEquals("ignore",message.getString("event_type"));assertEquals("",message.getString("content"));
    }
    @Test void expiredOrCancelledWaitCannotConsumeReply(){
        var body=createBody();body.put("timeoutSeconds",60);String id=service.create(run.getId(),worker,body).getString("checkpointId");
        time.set(time.get().plusSeconds(61));assertEquals("EXPIRED",service.poll(run.getId(),id,worker,pollBody(0)).getString("state"));
        var stop=service.consume(run.getId(),id,worker,consumeBody(1,"consume-expired-0001"));assertFalse(stop.getBooleanValue("consumed"));assertTrue(stop.getBooleanValue("stopped"));assertFalse(stop.containsKey("resumeData"));
        assertEquals(410,assertThrows(ServiceException.class,()->service.reply(run.getId(),id,replyBody("resume","迟到","reply-expired-0001"))).getCode());
    }
    @Test void cancellingRunSealsResumedQuestionBeforeConsumption(){
        String id=service.create(run.getId(),worker,createBody()).getString("checkpointId");service.reply(run.getId(),id,replyBody("resume","A","reply-fixture-0001"));
        new JdbcTemplate(source).update("UPDATE paiflow_run SET state='CANCEL_REQUESTED' WHERE id=?",run.getId());
        var stopped=service.consume(run.getId(),id,worker,consumeBody(1,"consume-cancelled-0001"));assertEquals("CANCELLED",stopped.getString("state"));assertFalse(stopped.getBooleanValue("consumed"));
        assertTrue(stopped.getBooleanValue("stopped"));assertFalse(stopped.containsKey("resumeData"));
    }
    @Test void replyDataAndCasStateRollBackTogetherOnPersistenceFailure(){
        String id=service.create(run.getId(),worker,createBody()).getString("checkpointId");new JdbcTemplate(source).execute("ALTER TABLE paiflow_run_checkpoint ADD CONSTRAINT fixture_fail_reply CHECK(state='WAITING')");
        assertThrows(RuntimeException.class,()->service.reply(run.getId(),id,replyBody("resume","A","reply-fixture-0001")));
        var saved=checkpoints.find(id);assertEquals("WAITING",saved.getState());assertNull(saved.getResumeDataJson());assertNull(saved.getReplyKey());assertEquals(0L,saved.getRevision());
    }
    @Test void simultaneousDifferentRepliesCannotOverwriteEachOther() throws Exception {
        String id=service.create(run.getId(),worker,createBody()).getString("checkpointId");var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()-> {start.await();try{service.reply(run.getId(),id,replyBody("resume","A","reply-concurrent-0001"));return 200;}catch(ServiceException ex){return ex.getCode();}});
            var second=executor.submit(()-> {start.await();try{service.reply(run.getId(),id,replyBody("resume","B","reply-concurrent-0002"));return 200;}catch(ServiceException ex){return ex.getCode();}});
            start.countDown();assertEquals(Set.of(200,409),Set.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS)));
            var saved=checkpoints.find(id);assertEquals(1L,saved.getRevision());assertEquals("RESUMED",saved.getState());
            var content=JSON.parseObject(JSON.parseObject(saved.getResumeDataJson()).getString("message")).getString("content");assertTrue(Set.of("A","B").contains(content));
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void platformTaskCanSealAbandonedWaitWithoutWorkerPolling(){
        String id=service.create(run.getId(),worker,createBody()).getString("checkpointId");new JdbcTemplate(source).update("UPDATE paiflow_run SET state='CANCEL_REQUESTED' WHERE id=?",run.getId());
        assertEquals(1,service.reconcileEnded());assertEquals(0,service.reconcileEnded());assertEquals("CANCELLED",checkpoints.find(id).getState());
    }
    @Test void cachedLoginPermissionCannotOverrideCurrentResumePermissionRevocation(){
        String id=service.create(run.getId(),worker,createBody()).getString("checkpointId");when(permissions.getMenuPermission(user)).thenReturn(Set.of("paiflow:run:create","paiflow:run:query"));
        assertTrue(identities.current().getPermissions().contains("paiflow:run:resume"));
        assertEquals(403,assertThrows(ServiceException.class,()->service.reply(run.getId(),id,replyBody("resume","回答","reply-revoked-0001"))).getCode());
        assertEquals("WAITING",checkpoints.find(id).getState());assertNull(checkpoints.find(id).getResumeDataJson());
    }
    @Test void oversizedIdentifiersAndChangedVersionPolicyAreRejectedBeforeInsert(){
        var body=createBody();body.put("iterationId","x".repeat(256));assertEquals(400,assertThrows(ServiceException.class,()->service.create(run.getId(),worker,body)).getCode());
        body.remove("iterationId");body.put("needReply",false);assertEquals(400,assertThrows(ServiceException.class,()->service.create(run.getId(),worker,body)).getCode());
        body.put("needReply",true);body.put("timeoutSeconds",300);assertEquals(400,assertThrows(ServiceException.class,()->service.create(run.getId(),worker,body)).getCode());
        assertEquals(0,new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM paiflow_run_checkpoint",Integer.class));
    }
    @Test void optionsCannotBeCoercedFromJsonTextNullOrObject(){
        for(Object invalid:new Object[]{"[{\"id\":\"A\",\"text\":\"受控资源\",\"content_type\":\"image\"}]",null,new JSONObject()}) {
            var body=optionBody();body.getJSONObject("question").put("option",invalid);
            assertEquals(400,assertThrows(ServiceException.class,()->service.create(run.getId(),worker,body)).getCode());
        }
        assertEquals(0,new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM paiflow_run_checkpoint",Integer.class));
    }
    @Test void wrongRunIsRejectedBeforeAcquiringAnotherRunsCheckpointLock(){
        String id=service.create(run.getId(),worker,createBody()).getString("checkpointId");var other=queued();var otherTask=leases.claim(worker);
        var observed=mock(CheckpointMapper.class,org.mockito.AdditionalAnswers.delegatesTo(checkpoints));
        var guarded=proxy(new CheckpointService(observed,runs,leases,access,identities,permissions,clock));
        var request=JSONObject.of("leaseId",otherTask.getString("leaseId"),"attempt",1,"afterRevision",0);
        assertEquals(404,assertThrows(ServiceException.class,()->guarded.poll(other.getId(),id,worker,request)).getCode());
        verify(observed).find(id);verify(observed,never()).lock(id);
    }
    @Test void replyWaitingForRunLockReadsPermissionRevocationAfterTheWait() throws Exception {
        String id=service.create(run.getId(),worker,createBody()).getString("checkpointId");var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE fixture_live_permission(permission VARCHAR(80) PRIMARY KEY)");
        for(String value:Set.of("paiflow:run:create","paiflow:run:query","paiflow:run:resume"))jdbc.update("INSERT INTO fixture_live_permission VALUES(?)",value);
        when(permissions.getMenuPermission(user)).thenAnswer(invocation->Set.copyOf(jdbc.queryForList("SELECT permission FROM fixture_live_permission",String.class)));
        var reachedLock=new CountDownLatch(1);var observed=mock(RunMapper.class,org.mockito.AdditionalAnswers.delegatesTo(runs));
        doAnswer(invocation->{reachedLock.countDown();return runs.lock(invocation.getArgument(0));}).when(observed).lock(anyString());
        var guarded=proxy(new CheckpointService(checkpoints,observed,leases,access,identities,permissions,clock));var executor=Executors.newSingleThreadExecutor();
        try(var blocker=source.getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM paiflow_run WHERE id=? FOR UPDATE")) {
                statement.setString(1,run.getId());try(var row=statement.executeQuery()){assertTrue(row.next());}
            }
            var result=executor.submit(()-> {try{guarded.reply(run.getId(),id,replyBody("resume","不得写入","reply-wait-revoked-0001"));return 200;}catch(ServiceException ex){return ex.getCode();}});
            assertTrue(reachedLock.await(5,TimeUnit.SECONDS));jdbc.update("DELETE FROM fixture_live_permission WHERE permission='paiflow:run:resume'");
            blocker.commit();assertEquals(403,result.get(5,TimeUnit.SECONDS));
            assertEquals("WAITING",checkpoints.find(id).getState());assertNull(checkpoints.find(id).getResumeDataJson());assertEquals(0L,checkpoints.find(id).getRevision());
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
