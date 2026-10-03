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
import com.ruoyi.paiflow.mapper.RunMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.service.ISysUserService;
import java.io.InputStream;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实Mapper和事务的隔离验证；不代表DDL草案已在MySQL执行。 */
class RunPersistenceContractTest {
    private JdbcDataSource source;private DataSource transactions;private RunMapper mapper;private RunLeaseService service;
    private SpaceMapper spaces;private ISysUserService users;private SysPermissionService permissions;
    private final Instant instant=Instant.parse("2026-10-03T00:00:00Z");
    private final WorkerIdentityService.Principal worker=new WorkerIdentityService.Principal("worker-a","python");
    @BeforeEach void setup() throws Exception {
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        // 测试池默认RR，服务自身必须在等待运行锁之后获取新授权视图。
        transactions=new DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException { var value=super.getConnection();value.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);return value; }
            @Override public Connection getConnection(String username,String password) throws SQLException { var value=super.getConnection(username,password);value.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);return value; }
        };
        try(var connection=source.getConnection();var sql=connection.createStatement()) {
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.PersistenceContractTest.utcNow'");
            sql.execute("CREATE TABLE paiflow_run(id VARCHAR(36) PRIMARY KEY,workflow_id BIGINT,workflow_version_id BIGINT,space_id BIGINT,owner_user_id BIGINT,engine_profile VARCHAR(16),state VARCHAR(24),assignment_json CLOB,model_permit_json CLOB,input_hash VARCHAR(64),idempotency_key VARCHAR(128),worker_id VARCHAR(64),lease_id VARCHAR(36),attempt INT,lease_expires_at TIMESTAMP,execution_started_at TIMESTAMP,deadline TIMESTAMP,last_event_seq BIGINT,revision BIGINT,result_json CLOB,result_hash VARCHAR(64),create_by VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(owner_user_id,idempotency_key))");
            sql.execute("CREATE TABLE paiflow_run_event(run_id VARCHAR(36),seq BIGINT,event_json CLOB,create_time TIMESTAMP,PRIMARY KEY(run_id,seq))");
        }
        var configuration=new Configuration(new Environment("isolated-run-test",new SpringManagedTransactionFactory(),transactions));
        String file="mapper/paiflow/RunMapper.xml";
        try(InputStream input=getClass().getClassLoader().getResourceAsStream(file)) {
            assertNotNull(input);new XMLMapperBuilder(input,configuration,file,configuration.getSqlFragments()).parse();
        }
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(RunMapper.class);
        spaces=mock(SpaceMapper.class);users=mock(ISysUserService.class);permissions=mock(SysPermissionService.class);
        var user=new SysUser();user.setUserId(42L);user.setStatus("0");user.setDelFlag("0");when(users.selectUserById(42L)).thenReturn(user);
        when(permissions.getMenuPermission(user)).thenReturn(Set.of("paiflow:run:create"));var space=new SpaceRecord();space.setDeleted(0);when(spaces.find(10L)).thenReturn(space);
        var member=new SpaceMember();member.setStatus("active");member.setRole(3);when(spaces.member(10L,42L)).thenReturn(member);
        service=proxy(new RunLeaseService(mapper,spaces,users,permissions,Clock.fixed(instant,ZoneOffset.UTC)));
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(transactions),new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();
    }
    private RunRecord queued() {
        var run=new RunRecord();run.setId(UUID.randomUUID().toString());run.setWorkflowId(1L);run.setWorkflowVersionId(2L);run.setSpaceId(10L);run.setOwnerUserId(42L);run.setEngineProfile("python");run.setState("QUEUED");
        run.setAssignmentJson("{\"schemaVersion\":\"paiflow.worker.v1\",\"runId\":\""+run.getId()+"\",\"dslHash\":\""+"a".repeat(64)+"\",\"workflowVersionId\":\"2\",\"dsl\":{\"nodes\":[{\"id\":\"node-start::a\",\"data\":{}}],\"edges\":[]}}");
        run.setInputHash("fixture");run.setIdempotencyKey(UUID.randomUUID().toString());run.setDeadline(LocalDateTime.ofInstant(instant.plusSeconds(600),ZoneOffset.UTC));run.setCreateBy("fixture");
        assertEquals(1,mapper.insert(run));return mapper.find(run.getId());
    }
    private JSONObject event(JSONObject task,long seq) {
        return JSONObject.of("schemaVersion","paiflow.events.v1","runId",task.getString("runId"),"leaseId",task.getString("leaseId"),"attempt",task.getIntValue("attempt"),"seq",seq,
            "nodeId","node-start::a","eventType","node.started","timestamp",instant.toString(),"payload",new JSONObject());
    }
    @Test void claimPersistsOneLeaseAndSecondClaimDoesNotDuplicateIt() {
        var run=queued();var task=service.claim(worker);assertEquals(run.getId(),task.getString("runId"));assertEquals(1,task.getIntValue("attempt"));assertNotNull(task.getString("leaseId"));
        assertNull(service.claim(worker));var saved=mapper.find(run.getId());assertEquals("RUNNING",saved.getState());assertEquals(1L,saved.getRevision());
        var check=service.verify(run.getId(),worker,task.getString("leaseId"),1);assertTrue(check.getBooleanValue("valid"));assertEquals(task,check.getJSONObject("assignment"));
    }
    @Test void defaultPersonalForeignOwnerWithDirtyOwnerMemberCannotBeClaimed() {
        var run=queued();var hidden=new SpaceRecord();hidden.setKind("default_personal");hidden.setOwnerUserId(99L);hidden.setDeleted(0);when(spaces.find(10L)).thenReturn(hidden);
        var dirty=new SpaceMember();dirty.setStatus("active");dirty.setRole(1);when(spaces.member(10L,42L)).thenReturn(dirty);
        assertNull(service.claim(worker));var saved=mapper.find(run.getId());assertEquals("FAILED",saved.getState());assertNull(saved.getLeaseId());assertEquals(0,saved.getAttempt());assertNull(saved.getExecutionStartedAt());
        assertEquals("AUTHORIZATION_REVOKED",JSON.parseObject(saved.getResultJson()).getJSONObject("error").getString("code"));assertEquals(1,mapper.events(run.getId(),0,100).size());
    }
    @Test void defaultPersonalLeaseRequiresOwnerRoleAndNoTeamAfterClaim() {
        var hidden=new SpaceRecord();hidden.setKind("default_personal");hidden.setOwnerUserId(42L);hidden.setDeleted(0);when(spaces.find(10L)).thenReturn(hidden);
        var member=new SpaceMember();member.setStatus("active");member.setRole(1);when(spaces.member(10L,42L)).thenReturn(member);
        var run=queued();var task=service.claim(worker);assertNotNull(task);String lease=task.getString("leaseId");assertTrue(service.verify(run.getId(),worker,lease,1).getBooleanValue("valid"));
        member.setRole(3);assertEquals(403,assertThrows(ServiceException.class,()->service.verify(run.getId(),worker,lease,1)).getCode());assertEquals(403,assertThrows(ServiceException.class,()->service.beginExecution(run.getId(),worker,lease,1)).getCode());
        member.setRole(1);hidden.setTeamId(7L);assertEquals(403,assertThrows(ServiceException.class,()->service.heartbeat(run.getId(),worker,lease,1)).getCode());
        var saved=mapper.find(run.getId());assertEquals("RUNNING",saved.getState());assertEquals(1L,saved.getRevision());assertNull(saved.getExecutionStartedAt());assertEquals(0L,saved.getLastEventSeq());assertNull(saved.getResultJson());
    }
    @Test void eventAndSequenceUpdateRollBackTogetherWhenRunWriteFails() throws Exception {
        var run=queued();var task=service.claim(worker);
        service.beginExecution(run.getId(),worker,task.getString("leaseId"),1);
        try(var connection=source.getConnection();var sql=connection.createStatement()) { sql.execute("ALTER TABLE paiflow_run ADD CONSTRAINT fixture_fail_sequence CHECK(last_event_seq=0)"); }
        assertThrows(RuntimeException.class,()->service.append(run.getId(),worker,event(task,1)));
        assertNull(mapper.event(run.getId(),1));assertEquals(0L,mapper.find(run.getId()).getLastEventSeq());
    }
    @Test void realEventReplayAndTerminalReceiptAreIdempotent() {
        var run=queued();var task=service.claim(worker);var first=event(task,1);
        service.beginExecution(run.getId(),worker,task.getString("leaseId"),1);
        service.append(run.getId(),worker,first);service.append(run.getId(),worker,first);
        var result=JSONObject.of("leaseId",task.getString("leaseId"),"attempt",1,"state","COMPLETED","output",JSONObject.of("answer","fixture算法输出"));
        service.result(run.getId(),worker,result);service.result(run.getId(),worker,result);
        var stored=mapper.find(run.getId());assertEquals("COMPLETED",stored.getState());assertEquals(2L,stored.getLastEventSeq());assertEquals(result,JSON.parseObject(stored.getResultJson()));
        assertEquals(2,mapper.events(run.getId(),0,100).size());assertEquals(1,mapper.events(run.getId(),1,100).size());
    }
    @Test void cancellingQueuedRunPersistsActualTerminalAndCannotBeClaimed() {
        var run=queued();var access=mock(ResourceAccessService.class);var identity=mock(IdentityMappingService.class);
        var user=new SysUser();user.setUserId(42L);when(identity.current()).thenReturn(new LoginUser(42L,8L,user,Set.of()));
        var runs=proxy(new RunService(mock(WorkflowService.class),mapper,access,identity));
        assertEquals("CANCELLED",runs.cancel(run.getId()).state());assertNull(service.claim(worker));
        var event=JSON.parseObject(mapper.event(run.getId(),1));assertEquals(0,event.getIntValue("attempt"));assertEquals("center",event.getString("leaseId"));
        assertEquals("CANCELLED",mapper.find(run.getId()).getState());
    }
    @Test void expiredReadOnlyLeaseIsFailedAtCenterWithoutAutomaticReplay() throws Exception {
        var run=queued();service.claim(worker);
        try(var connection=source.getConnection();var sql=connection.prepareStatement("UPDATE paiflow_run SET lease_expires_at=? WHERE id=?")) {
            sql.setObject(1,LocalDateTime.ofInstant(instant.minusSeconds(1),ZoneOffset.UTC));sql.setString(2,run.getId());sql.executeUpdate();
        }
        assertNull(service.claim(worker));assertEquals("FAILED",mapper.find(run.getId()).getState());
        assertEquals(1,mapper.events(run.getId(),0,100).size());assertEquals(1,mapper.find(run.getId()).getAttempt());
    }
    @Test void expiredWriteCapableLeasePreservesUnknownAndIsNeverRequeued() throws Exception {
        var run=queued();service.claim(worker);var assignment=JSON.parseObject(run.getAssignmentJson());assignment.put("effectPolicy",JSONObject.of("mode","approved_write"));
        try(var connection=source.getConnection();var sql=connection.prepareStatement("UPDATE paiflow_run SET assignment_json=?,lease_expires_at=? WHERE id=?")) {
            sql.setString(1,assignment.toJSONString());sql.setObject(2,LocalDateTime.ofInstant(instant.minusSeconds(1),ZoneOffset.UTC));sql.setString(3,run.getId());sql.executeUpdate();
        }
        assertNull(service.claim(worker));assertEquals("EFFECT_UNKNOWN",mapper.find(run.getId()).getState());assertNull(service.claim(worker));
        assertEquals(1,mapper.events(run.getId(),0,100).size());
    }
    @Test void claimWaitingForRunLockObservesCommittedMembershipRevocation() throws Exception {
        var run=queued();var jdbc=new JdbcTemplate(transactions);
        jdbc.execute("CREATE TABLE fixture_member(space_id BIGINT,user_id BIGINT,role INT,status VARCHAR(16))");
        jdbc.update("INSERT INTO fixture_member VALUES(10,42,3,'active')");
        when(spaces.member(10L,42L)).thenAnswer(invocation->jdbc.queryForObject("SELECT role,status FROM fixture_member WHERE space_id=10 AND user_id=42",(rs,row)-> {
            var value=new SpaceMember();value.setRole(rs.getInt("role"));value.setStatus(rs.getString("status"));return value;
        }));
        var reachedLock=new CountDownLatch(1);var delegated=mock(RunMapper.class,org.mockito.AdditionalAnswers.delegatesTo(mapper));
        doAnswer(invocation->{reachedLock.countDown();return mapper.lock(invocation.getArgument(0));}).when(delegated).lock(anyString());
        var claimant=proxy(new RunLeaseService(delegated,spaces,users,permissions,Clock.fixed(instant,ZoneOffset.UTC)));
        var executor=Executors.newSingleThreadExecutor();
        try(var blocker=source.getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM paiflow_run WHERE id=? FOR UPDATE")) {
                statement.setString(1,run.getId());try(var ignored=statement.executeQuery()) { assertTrue(ignored.next()); }
            }
            var result=executor.submit(()->claimant.claim(worker));
            assertTrue(reachedLock.await(5,TimeUnit.SECONDS),"须实际到达被另一事务占用的运行锁");
            jdbc.update("UPDATE fixture_member SET status='removed' WHERE space_id=10 AND user_id=42");
            blocker.commit();assertNull(result.get(5,TimeUnit.SECONDS));
            assertEquals("FAILED",mapper.find(run.getId()).getState());assertNull(mapper.find(run.getId()).getLeaseId());
            assertEquals("AUTHORIZATION_REVOKED",JSON.parseObject(mapper.find(run.getId()).getResultJson()).getJSONObject("error").getString("code"));
        } finally { executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void centerReconciliationFinishesExpiredRunsWithoutAWorkerClaim() throws Exception {
        var queued=queued();var running=queued();service.claim(worker);
        var jdbc=new JdbcTemplate(transactions);
        // 用数据库已保存的状态区分未派发、已派发只读和已派发写能力，不执行任何外部操作。
        var claimed=mapper.find(queued.getId()).getAttempt()>0?queued:running;
        var waiting=claimed==queued?running:queued;
        jdbc.update("UPDATE paiflow_run SET lease_expires_at=? WHERE id=?",LocalDateTime.ofInstant(instant.minusSeconds(1),ZoneOffset.UTC),claimed.getId());
        jdbc.update("UPDATE paiflow_run SET deadline=? WHERE id=?",LocalDateTime.ofInstant(instant.minusSeconds(1),ZoneOffset.UTC),waiting.getId());
        var write=queued();var snapshot=JSON.parseObject(write.getAssignmentJson());snapshot.put("effectPolicy",JSONObject.of("mode","approved_write"));
        jdbc.update("UPDATE paiflow_run SET engine_profile='java',state='RUNNING',attempt=1,worker_id='java-worker',lease_id='lease-write',assignment_json=?,lease_expires_at=? WHERE id=?",
            snapshot.toJSONString(),LocalDateTime.ofInstant(instant.minusSeconds(1),ZoneOffset.UTC),write.getId());
        assertEquals(3,service.reconcileExpiredRuns());assertEquals(0,service.reconcileExpiredRuns());
        assertEquals("FAILED",mapper.find(claimed.getId()).getState());assertEquals("FAILED",mapper.find(waiting.getId()).getState());
        assertEquals("EFFECT_UNKNOWN",mapper.find(write.getId()).getState());
        var beforeDispatch=JSON.parseObject(mapper.event(waiting.getId(),1));assertEquals(0,beforeDispatch.getIntValue("attempt"));assertEquals("center",beforeDispatch.getString("leaseId"));
        for(var value:new RunRecord[]{claimed,waiting,write})assertEquals(1,mapper.events(value.getId(),0,100).size());
    }
    @Test void executionPermitPersistsAndAnotherServiceInstanceCannotReissueIt() {
        var run=queued();var task=service.claim(worker);String lease=task.getString("leaseId");
        assertNull(mapper.find(run.getId()).getExecutionStartedAt());
        var ack=service.beginExecution(run.getId(),worker,lease,1);
        assertEquals(JSONObject.of("begun",true,"runId",run.getId(),"leaseId",lease,"attempt",1,"workflowVersionId","2","dslHash","a".repeat(64)),ack);
        assertEquals(LocalDateTime.ofInstant(instant,ZoneOffset.UTC),mapper.find(run.getId()).getExecutionStartedAt());
        var restarted=proxy(new RunLeaseService(mapper,spaces,users,permissions,Clock.fixed(instant,ZoneOffset.UTC)));
        assertTrue(restarted.verify(run.getId(),worker,lease,1).getBooleanValue("valid"));
        assertEquals(409,assertThrows(ServiceException.class,()->restarted.beginExecution(run.getId(),worker,lease,1)).getCode());
        restarted.heartbeat(run.getId(),worker,lease,1);
        assertEquals(LocalDateTime.ofInstant(instant,ZoneOffset.UTC),mapper.find(run.getId()).getExecutionStartedAt());
        assertEquals("RUNNING",mapper.find(run.getId()).getState());assertTrue(mapper.events(run.getId(),0,100).isEmpty());
    }
    @Test void twoConcurrentWorkerProcessesReceiveOnlyOneExecutionPermit() throws Exception {
        var run=queued();var task=service.claim(worker);String lease=task.getString("leaseId");var start=new CountDownLatch(1);
        var second=proxy(new RunLeaseService(mapper,spaces,users,permissions,Clock.fixed(instant,ZoneOffset.UTC)));var executor=Executors.newFixedThreadPool(2);
        try {
            var firstFuture=executor.submit(()->{start.await();try{service.beginExecution(run.getId(),worker,lease,1);return 200;}catch(ServiceException failure){return failure.getCode();}});
            var secondFuture=executor.submit(()->{start.await();try{second.beginExecution(run.getId(),worker,lease,1);return 200;}catch(ServiceException failure){return failure.getCode();}});
            start.countDown();assertEquals(Set.of(200,409),Set.of(firstFuture.get(5,TimeUnit.SECONDS),secondFuture.get(5,TimeUnit.SECONDS)));
            assertEquals(2L,mapper.find(run.getId()).getRevision());assertNotNull(mapper.find(run.getId()).getExecutionStartedAt());
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void cancellationExpiryOrRevocationCannotMintAnExecutionPermit() {
        var run=queued();var task=service.claim(worker);String lease=task.getString("leaseId");var jdbc=new JdbcTemplate(transactions);
        jdbc.update("UPDATE paiflow_run SET state='CANCEL_REQUESTED' WHERE id=?",run.getId());
        assertEquals(410,assertThrows(ServiceException.class,()->service.beginExecution(run.getId(),worker,lease,1)).getCode());
        jdbc.update("UPDATE paiflow_run SET state='RUNNING',lease_expires_at=? WHERE id=?",LocalDateTime.ofInstant(instant.minusSeconds(1),ZoneOffset.UTC),run.getId());
        assertEquals(410,assertThrows(ServiceException.class,()->service.beginExecution(run.getId(),worker,lease,1)).getCode());
        jdbc.update("UPDATE paiflow_run SET lease_expires_at=? WHERE id=?",LocalDateTime.ofInstant(instant.plusSeconds(60),ZoneOffset.UTC),run.getId());
        var user=users.selectUserById(42L);when(permissions.getMenuPermission(user)).thenReturn(Set.of());
        assertEquals(403,assertThrows(ServiceException.class,()->service.beginExecution(run.getId(),worker,lease,1)).getCode());
        assertNull(mapper.find(run.getId()).getExecutionStartedAt());assertEquals(1L,mapper.find(run.getId()).getRevision());
    }
    @Test void beginWaitingForRunLockChecksFreshCommittedPermission() throws Exception {
        var run=queued();var task=service.claim(worker);String lease=task.getString("leaseId");var jdbc=new JdbcTemplate(transactions);
        jdbc.execute("CREATE TABLE fixture_run_permission(user_id BIGINT PRIMARY KEY,enabled INT)");jdbc.update("INSERT INTO fixture_run_permission VALUES(42,1)");
        var user=users.selectUserById(42L);when(permissions.getMenuPermission(user)).thenAnswer(invocation->jdbc.queryForObject("SELECT enabled FROM fixture_run_permission WHERE user_id=42",Integer.class)==1?Set.of("paiflow:run:create"):Set.of());
        var reachedLock=new CountDownLatch(1);var delegated=mock(RunMapper.class,org.mockito.AdditionalAnswers.delegatesTo(mapper));
        doAnswer(invocation->{reachedLock.countDown();return mapper.lock(invocation.getArgument(0));}).when(delegated).lock(anyString());
        var blocked=proxy(new RunLeaseService(delegated,spaces,users,permissions,Clock.fixed(instant,ZoneOffset.UTC)));var executor=Executors.newSingleThreadExecutor();
        try(var blocker=source.getConnection()) {
            blocker.setAutoCommit(false);try(var statement=blocker.prepareStatement("SELECT id FROM paiflow_run WHERE id=? FOR UPDATE")) {statement.setString(1,run.getId());try(var row=statement.executeQuery()){assertTrue(row.next());}}
            var future=executor.submit(()->{try{blocked.beginExecution(run.getId(),worker,lease,1);return 200;}catch(ServiceException failure){return failure.getCode();}});
            assertTrue(reachedLock.await(5,TimeUnit.SECONDS));jdbc.update("UPDATE fixture_run_permission SET enabled=0 WHERE user_id=42");blocker.commit();assertEquals(403,future.get(5,TimeUnit.SECONDS));
            assertNull(mapper.find(run.getId()).getExecutionStartedAt());assertEquals("RUNNING",mapper.find(run.getId()).getState());
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void failedPermitPersistenceRollsBackAndDoesNotConsumeTheOnlyPermit() throws Exception {
        var run=queued();var task=service.claim(worker);String lease=task.getString("leaseId");
        try(var connection=source.getConnection();var sql=connection.createStatement()) {
            sql.execute("ALTER TABLE paiflow_run ADD CONSTRAINT fixture_fail_begin CHECK(execution_started_at IS NULL)");
            var failure=assertThrows(RuntimeException.class,()->service.beginExecution(run.getId(),worker,lease,1));
            String errors="";for(Throwable cause=failure;cause!=null;cause=cause.getCause())errors+=cause.getMessage();assertTrue(errors.toLowerCase(java.util.Locale.ROOT).contains("fixture_fail_begin"));
            assertNull(mapper.find(run.getId()).getExecutionStartedAt());assertEquals(1L,mapper.find(run.getId()).getRevision());
            sql.execute("ALTER TABLE paiflow_run DROP CONSTRAINT fixture_fail_begin");
        }
        assertTrue(service.beginExecution(run.getId(),worker,lease,1).getBooleanValue("begun"));
    }
    @Test void nodeEventsAndCompletedResultsCannotPrecedeExecutionPermit() {
        var run=queued();var task=service.claim(worker);
        assertEquals(409,assertThrows(ServiceException.class,()->service.append(run.getId(),worker,event(task,1))).getCode());
        var completed=JSONObject.of("leaseId",task.getString("leaseId"),"attempt",1,"state","COMPLETED");
        assertEquals(409,assertThrows(ServiceException.class,()->service.result(run.getId(),worker,completed)).getCode());
        assertEquals("RUNNING",mapper.find(run.getId()).getState());assertTrue(mapper.events(run.getId(),0,100).isEmpty());
        var rejected=JSONObject.of("leaseId",task.getString("leaseId"),"attempt",1,"state","FAILED","error",JSONObject.of("code","LOCAL_PREFLIGHT_FAILED"));
        assertTrue(service.result(run.getId(),worker,rejected).getBooleanValue("accepted"));assertEquals("FAILED",mapper.find(run.getId()).getState());assertNull(mapper.find(run.getId()).getExecutionStartedAt());
    }
    @Test void savedCancelRequestWinsOverLateCompletionWithoutErasingNodeFacts() {
        var run=queued();var task=service.claim(worker);String lease=task.getString("leaseId");service.beginExecution(run.getId(),worker,lease,1);
        var fact=event(task,1);fact.put("eventType","node.completed");fact.put("payload",JSONObject.of("outputs",JSONObject.of("answer","已实际产生的节点结果")));service.append(run.getId(),worker,fact);
        assertFalse(service.verify(run.getId(),worker,lease,1).getBooleanValue("cancelRequested"));
        // 模拟最终verify之后，用户取消先由中心事务提交；不发真实外部请求。
        new JdbcTemplate(transactions).update("UPDATE paiflow_run SET state='CANCEL_REQUESTED',revision=revision+1 WHERE id=?",run.getId());
        var completed=JSONObject.of("leaseId",lease,"attempt",1,"state","COMPLETED","output",JSONObject.of("answer","late"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.result(run.getId(),worker,completed)).getCode());
        var current=mapper.find(run.getId());assertEquals("CANCEL_REQUESTED",current.getState());assertNull(current.getResultJson());assertEquals(1L,current.getLastEventSeq());
        assertEquals(RunJsonBoundary.canonical(fact),mapper.event(run.getId(),1));assertTrue(service.verify(run.getId(),worker,lease,1).getBooleanValue("cancelRequested"));
        var stopped=JSONObject.of("leaseId",lease,"attempt",1,"state","CANCELLED","error",JSONObject.of("code","RUN_CANCELLED","message","隔离Worker确认原引擎已停止"));
        assertEquals("CANCELLED",service.result(run.getId(),worker,stopped).getString("state"));assertEquals(RunJsonBoundary.canonical(fact),mapper.event(run.getId(),1));assertEquals(2L,mapper.find(run.getId()).getLastEventSeq());
    }
    @Test void completionCommittedBeforeCancellationRemainsActualTerminal() {
        var run=queued();var task=service.claim(worker);String lease=task.getString("leaseId");service.beginExecution(run.getId(),worker,lease,1);
        var completed=JSONObject.of("leaseId",lease,"attempt",1,"state","COMPLETED","output",JSONObject.of("answer","实际完成"));service.result(run.getId(),worker,completed);
        var access=mock(ResourceAccessService.class);var identity=mock(IdentityMappingService.class);var user=new SysUser();user.setUserId(42L);
        when(identity.current()).thenReturn(new LoginUser(42L,8L,user,Set.of()));var commands=proxy(new RunService(mock(WorkflowService.class),mapper,access,identity));
        assertEquals("COMPLETED",commands.cancel(run.getId()).state());assertTrue(service.result(run.getId(),worker,completed).getBooleanValue("accepted"));
        assertEquals(1,mapper.events(run.getId(),0,100).size());assertEquals(completed,JSON.parseObject(mapper.find(run.getId()).getResultJson()));
    }
}
