package com.ruoyi.paismart.application.intelligence;

import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.jobs.*;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 中央Job真实状态CAS与Handler回调；H2短事务验证取消、旧租约及保留结果，不运行外部依赖。 */
class EvaluationLifecycleBoundaryTest {
    JdbcTemplate db;JobService jobs;GovernanceService governance;ModelGateway models;SearchService search;ProviderService providers;
    @SuppressWarnings("unchecked") @BeforeEach void prepare()throws Exception{
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:eval_lifecycle_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try(Connection c=source.getConnection()){for(String file:List.of("V001__control_billing_jobs.sql","V002__documents_spaces.sql","V003__intelligence.sql","V005__evaluation_datasets.sql"))ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/"+file));}
        db=new JdbcTemplate(source);db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status CHAR(1),del_flag CHAR(1))");db.update("INSERT INTO sys_user VALUES(2,'0','0'),(3,'0','0')");db.update("INSERT INTO ps_member(user_id,status) VALUES(2,'ACTIVE'),(3,'ACTIVE')");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);jobs=spy(new JobService(db,tx));
        search=mock(SearchService.class);models=mock(ModelGateway.class);providers=mock(ProviderService.class);var settings=mock(IntelligenceSettings.class);
        when(settings.text(anyString(),anyString())).thenAnswer(i->i.getArgument(1));when(settings.number(anyString(),anyInt())).thenAnswer(i->i.getArgument(1));when(search.activeIndex()).thenReturn(Map.of("id","frozen-index"));
        var provider=new ProviderService.Provider("provider","LLM","测试","https://provider.invalid/v1","model",0,"ai.paismart.test",true,true,1,Map.of());when(models.active("LLM")).thenReturn(provider);when(providers.snapshot(provider)).thenReturn(Map.of("id","provider","revision",1));when(providers.fromSnapshot(anyString())).thenReturn(provider);
        governance=new GovernanceService(db,tx,jobs,search,mock(DocumentIntelligenceService.class),providers,mock(DocumentService.class),models,settings,mock(IndexCoordinator.class));ReflectionTestUtils.setField(governance,"memberships",new MembershipService(db));
        ObjectProvider<PaismartJobHandler> handlers=mock(ObjectProvider.class);when(handlers.orderedStream()).thenAnswer(i->Stream.of(governance));jobs.handlers(handlers);
        when(search.searchAt(any(),anyString(),anyInt(),anyString(),anyMap())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"检索不得持业务事务锁");return new SearchService.SearchResult(List.of(),"TEXT_ONLY",List.of(),0);});when(search.expand(any(),anyList())).thenReturn(List.of());
        when(models.text(anyLong(),startsWith("eval-answer:"),anyString(),anyString(),anyInt(),any(),anyInt())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"模型不得持业务事务锁");return "答案";});when(models.text(anyLong(),startsWith("eval-judge:"),anyString(),anyString(),anyInt(),any(),anyInt())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"裁判不得持业务事务锁");return "{\"score\":5,\"correct\":true,\"reason\":\"正确\"}";});
    }
    JobService.Job create(String key,String... caseIds){String jsonl=Arrays.stream(caseIds).map(id->Jsons.write(Map.of("id",id,"question","问题","referenceAnswer","答案"))).collect(java.util.stream.Collectors.joining("\n"));return governance.createEvaluation(2,Map.of("label","冻结评测","inlineJsonl",jsonl,"seed",17),key);}
    String id(JobService.Job job){return job.payload().get("evaluationId").toString();}
    String state(JobService.Job job){return db.queryForObject("SELECT state FROM ps_evaluation WHERE id=?",String.class,id(job));}
    String config(JobService.Job job){return db.queryForObject("SELECT config_json FROM ps_evaluation WHERE id=?",String.class,id(job));}
    String cases(JobService.Job job){return db.queryForObject("SELECT dataset_json FROM ps_evaluation WHERE id=?",String.class,id(job));}
    void cancel(JobService.Job job){jobs.cancel(job.id(),jobs.get(job.id()).rowVersion());}
    void retry(JobService.Job job){jobs.retry(job.id(),jobs.get(job.id()).rowVersion());}
    Map<String,Object> oldResult(){return Map.of("id","first","question","旧问题","answer","历史答案","judgeCorrect",true,"judgeScore",5,"diagnosis","OK","retrievalMode","TEXT_ONLY","needsHumanReview",false);}
    void preserveCase(JobService.Job job){db.update("INSERT INTO ps_evaluation_case(evaluation_id,case_id,result_json) VALUES(?,'first',?)",id(job),Jsons.write(oldResult()));}
    void modelCall(JobService.Job job,String status){String operation="eval-answer:"+id(job)+":first";boolean completed="COMPLETED".equals(status);db.update("INSERT INTO ps_token_reservation(id,user_id,scope,operation_id,mode,reserved_tokens,actual_tokens,state,business_day) VALUES('old-reservation',2,'LLM',?,'BALANCE',11,?,?,CURRENT_DATE)",operation,completed?11L:null,completed?"SETTLED":"RESERVED");db.update("INSERT INTO ps_model_call(id,user_id,operation_id,scope,request_hash,status,reservation_id,response_json,actual_tokens,estimated,created_at,updated_at) VALUES('old-call',2,?,'LLM',?,?,'old-reservation',?,?,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",operation,"0".repeat(64),status,Jsons.write(Map.of("content","历史答案")),completed?11L:null);}

    @Test void centralQueuedAndRunningCancellationImmediatelyChangesOnlyTheLinkedEvaluation(){
        var queued=create("queued","first");String frozen=config(queued),dataset=cases(queued);cancel(queued);assertEquals("CANCELED",jobs.get(queued.id()).state());assertEquals("CANCELED",state(queued));assertEquals("CANCELED",governance.report(id(queued)).get("state"));assertEquals(frozen,config(queued));assertEquals(dataset,cases(queued));
        var running=create("running","second");var lease=jobs.claimById(running.id(),600).orElseThrow();cancel(lease);assertEquals("CANCELED",state(running));assertEquals("CANCELED",jobs.get(lease.id()).state());verify(models,never()).text(anyLong(),anyString(),anyString(),anyString(),anyInt(),any(),anyInt());
    }
    @Test void cancellationDuringTheModelCallCannotPersistItsCaseOrCompleteReport(){
        var created=create("during","first");var lease=jobs.claimById(created.id(),600).orElseThrow();String frozen=config(created);
        when(models.text(anyLong(),startsWith("eval-answer:"),anyString(),anyString(),anyInt(),any(),anyInt())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());cancel(lease);return "晚到的答案";});
        assertThrows(IllegalStateException.class,()->governance.processJob(lease));assertEquals("CANCELED",state(created));assertEquals(frozen,config(created));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_case",Long.class));assertNull(db.queryForObject("SELECT report_json FROM ps_evaluation",String.class));verify(models,never()).text(anyLong(),startsWith("eval-judge:"),anyString(),anyString(),anyInt(),any(),anyInt());
    }
    @Test void retryPreservesFormalCasesCallsAndFrozenInputAndDoesNotRegenerateTheCompletedCase(){
        var created=create("resume","first","second");preserveCase(created);modelCall(created,"COMPLETED");String frozen=config(created),dataset=cases(created),old=Jsons.write(oldResult());var oldLease=jobs.claimById(created.id(),600).orElseThrow();cancel(oldLease);retry(created);assertEquals("QUEUED",jobs.get(created.id()).state());assertEquals("QUEUED",state(created));assertEquals(frozen,config(created));assertEquals(dataset,cases(created));
        var resumed=jobs.claimById(created.id(),600).orElseThrow();assertNotEquals(oldLease.leaseToken(),resumed.leaseToken());var result=governance.processJob(resumed);jobs.complete(resumed.id(),resumed.leaseToken(),result);
        assertEquals("SUCCEEDED",jobs.get(created.id()).state());assertEquals("COMPLETE",state(created));assertEquals(old,db.queryForObject("SELECT result_json FROM ps_evaluation_case WHERE case_id='first'",String.class));assertEquals(2L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_case",Long.class));assertEquals("COMPLETED",db.queryForObject("SELECT status FROM ps_model_call",String.class));assertEquals(11L,db.queryForObject("SELECT actual_tokens FROM ps_model_call",Long.class));verify(models,never()).text(anyLong(),eq("eval-answer:"+id(created)+":first"),anyString(),anyString(),anyInt(),any(),anyInt());verify(models,times(1)).text(anyLong(),eq("eval-answer:"+id(created)+":second"),anyString(),anyString(),anyInt(),any(),anyInt());
    }
    @Test void unresolvedModelCallRejectsRetryAndRollsBackTheCentralQueuedTransition(){
        var created=create("uncertain","first");cancel(created);modelCall(created,"UNCERTAIN");assertThrows(IllegalStateException.class,()->retry(created));assertEquals("CANCELED",jobs.get(created.id()).state());assertEquals("CANCELED",state(created));assertEquals("UNCERTAIN",db.queryForObject("SELECT status FROM ps_model_call",String.class));
        db.update("UPDATE ps_model_call SET status='STARTED'");assertThrows(IllegalStateException.class,()->retry(created));assertEquals("CANCELED",jobs.get(created.id()).state());db.update("UPDATE ps_model_call SET status='COMPLETED',actual_tokens=11");db.update("UPDATE ps_token_reservation SET state='SETTLED',actual_tokens=11");retry(created);assertEquals("QUEUED",state(created));assertEquals("QUEUED",jobs.get(created.id()).state());
    }
    @Test void aDifferentOwnerOrJobCannotExecuteOrResetTheEvaluationAssociation(){
        var first=create("association-one","first");var second=create("association-two","second");var firstLease=jobs.claimById(first.id(),600).orElseThrow();var secondLease=jobs.claimById(second.id(),600).orElseThrow();clearInvocations(models,search,providers);
        var wrongOwner=new JobService.Job(firstLease.id(),3,null,"EVALUATION_RUN","RUNNING",firstLease.payload(),Map.of(),firstLease.leaseToken(),firstLease.leaseUntil(),firstLease.retries(),null,firstLease.rowVersion());assertThrows(IllegalStateException.class,()->governance.processJob(wrongOwner));
        var wrongJob=new JobService.Job(secondLease.id(),2,null,"EVALUATION_RUN","RUNNING",firstLease.payload(),Map.of(),secondLease.leaseToken(),secondLease.leaseUntil(),secondLease.retries(),null,secondLease.rowVersion());assertThrows(IllegalStateException.class,()->governance.processJob(wrongJob));assertEquals("QUEUED",state(first));assertEquals("QUEUED",state(second));verifyNoInteractions(models,search,providers);
        cancel(secondLease);var canceled=jobs.get(second.id());var wrongHook=new JobService.Job(canceled.id(),2,null,"EVALUATION_RUN","CANCELED",firstLease.payload(),Map.of(),null,null,canceled.retries(),null,canceled.rowVersion());governance.onCancelled(wrongHook);assertEquals("QUEUED",state(first));assertEquals("CANCELED",state(second));
    }
    @Test void staleCancelAndRetrySnapshotsCannotOverwriteAResumedRunningEvaluation(){
        var created=create("stale-hooks","first");cancel(created);var canceled=jobs.get(created.id());retry(created);var queued=jobs.get(created.id());var lease=jobs.claimById(created.id(),600).orElseThrow();db.update("UPDATE ps_evaluation SET state='RUNNING' WHERE id=?",id(created));long version=db.queryForObject("SELECT row_version FROM ps_evaluation",Long.class);
        governance.onCancelled(canceled);governance.onRetry(queued);assertEquals("RUNNING",state(created));assertEquals(version,db.queryForObject("SELECT row_version FROM ps_evaluation",Long.class));assertEquals(lease.leaseToken(),jobs.get(created.id()).leaseToken());
    }
    @Test void oldLeaseAfterCancelAndClaimedRetryCannotStartOrWriteTheNewRun(){
        var created=create("old-lease","first");var old=jobs.claimById(created.id(),600).orElseThrow();cancel(old);retry(created);var current=jobs.claimById(created.id(),600).orElseThrow();clearInvocations(models,search,providers);
        assertThrows(IllegalStateException.class,()->governance.processJob(old));assertEquals("QUEUED",state(created));assertEquals(current.leaseToken(),jobs.get(created.id()).leaseToken());assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_case",Long.class));verifyNoInteractions(models,search,providers);
    }
    @Test void cancelAndRetryDuringRetrievalRejectsTheOldWorkerBeforeAnyLateCaseWrite(){
        var created=create("lease-during","first");var old=jobs.claimById(created.id(),600).orElseThrow();var current=new java.util.concurrent.atomic.AtomicReference<JobService.Job>();
        when(search.searchAt(any(),anyString(),anyInt(),anyString(),anyMap())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());cancel(old);retry(created);current.set(jobs.claimById(created.id(),600).orElseThrow());return new SearchService.SearchResult(List.of(),"TEXT_ONLY",List.of(),0);});
        assertThrows(IllegalStateException.class,()->governance.processJob(old));assertEquals("QUEUED",state(created));assertNotEquals(old.leaseToken(),current.get().leaseToken());assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_case",Long.class));assertNull(db.queryForObject("SELECT report_json FROM ps_evaluation",String.class));verify(models,never()).text(anyLong(),anyString(),anyString(),anyString(),anyInt(),any(),anyInt());
    }
    @Test void cancelAfterACompleteReportCommitUsesCurrentStateWithoutClearingTheReportOrCases(){
        var created=create("report-gap","first");var lease=jobs.claimById(created.id(),600).orElseThrow();governance.processJob(lease);String snapshot=db.queryForObject("SELECT report_json FROM ps_evaluation",String.class);assertEquals("RUNNING",jobs.get(created.id()).state());assertEquals("COMPLETE",state(created));
        cancel(lease);assertEquals("CANCELED",state(created));assertEquals(snapshot,db.queryForObject("SELECT report_json FROM ps_evaluation",String.class));var canceledReport=governance.report(id(created));assertEquals("CANCELED",canceledReport.get("state"));assertEquals(1,((List<?>)canceledReport.get("cases")).size());assertEquals(1,((Map<?,?>)canceledReport.get("summary")).get("totalCases"));
        retry(created);assertEquals("QUEUED",governance.report(id(created)).get("state"));clearInvocations(models,search);governance.processJob(jobs.claimById(created.id(),600).orElseThrow());assertEquals("COMPLETE",state(created));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_case",Long.class));verifyNoInteractions(models,search);
    }
    @Test void partialReportAfterCancellationAndRetryRetainsOneFormalCaseOfThreeWithoutInventingMetrics(){
        var created=create("partial-report","first","second","third");preserveCase(created);modelCall(created,"COMPLETED");String frozen=config(created),saved=Jsons.write(oldResult());var lease=jobs.claimById(created.id(),600).orElseThrow();cancel(lease);clearInvocations(models,search,providers);
        for(String expectedState:List.of("CANCELED","QUEUED")){
            if("QUEUED".equals(expectedState))retry(created);var report=governance.report(id(created));assertEquals(expectedState,report.get("state"));assertEquals(Jsons.map(frozen),report.get("configuration"));assertEquals(report.get("configuration"),report.get("config"));assertEquals(5,report.get("topK"));assertEquals("inline",report.get("datasetSource"));
            var actual=Values.list(report.get("cases"));assertEquals(1,actual.size());assertEquals("first",Values.map(actual.get(0)).get("id"));assertEquals("历史答案",Values.map(actual.get(0)).get("answer"));var summary=Values.map(report.get("summary"));assertEquals(3,summary.get("totalCases"));assertEquals(1,summary.get("processedCases"));assertEquals(2,summary.get("pendingCases"));assertEquals(1,summary.get("samples"));assertEquals(1,summary.get("judgedCases"));assertEquals(0,summary.get("labeledCases"));assertEquals(0,summary.get("failedCases"));assertEquals(1d,summary.get("accuracy"));assertEquals(5d,summary.get("meanJudgeScore"));for(String metric:List.of("recallAtK","mrr","ndcg","meanExpectedDocCoverage"))assertNull(summary.get(metric),"没有可计算检索案例时不得伪造零分："+metric);
        }
        assertNull(db.queryForObject("SELECT report_json FROM ps_evaluation",String.class));assertEquals(saved,db.queryForObject("SELECT result_json FROM ps_evaluation_case",String.class));assertEquals("COMPLETED",db.queryForObject("SELECT status FROM ps_model_call",String.class));assertEquals(11L,db.queryForObject("SELECT actual_tokens FROM ps_token_reservation",Long.class));verifyNoInteractions(models,search,providers);
    }
    @Test void completedReportDeliveryReplayDoesNotStartAnotherPaidCaseOrResetItsVersion(){
        var created=create("terminal-replay","first");var lease=jobs.claimById(created.id(),600).orElseThrow();var first=governance.processJob(lease);long version=db.queryForObject("SELECT row_version FROM ps_evaluation",Long.class);String snapshot=db.queryForObject("SELECT report_json FROM ps_evaluation",String.class);clearInvocations(models,search,providers);
        assertEquals(first,governance.processJob(lease));assertEquals(version,db.queryForObject("SELECT row_version FROM ps_evaluation",Long.class));assertEquals(snapshot,db.queryForObject("SELECT report_json FROM ps_evaluation",String.class));verifyNoInteractions(models,search,providers);
    }
    @Test void cancelWaitsForTheShortCaseTransactionAndCannotBeOverwrittenAfterItCommits()throws Exception{
        var created=create("locked-write","first");var lease=jobs.claimById(created.id(),600).orElseThrow();var judged=new AtomicBoolean();var paused=new AtomicBoolean();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(models.text(anyLong(),startsWith("eval-judge:"),anyString(),anyString(),anyInt(),any(),anyInt())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());judged.set(true);return "{\"score\":5,\"correct\":true,\"reason\":\"正确\"}";});
        doAnswer(i->{i.callRealMethod();if(judged.get()&&TransactionSynchronizationManager.isActualTransactionActive()&&paused.compareAndSet(false,true)){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}return null;}).when(jobs).requireLease(anyString(),anyString());
        var pool=Executors.newFixedThreadPool(2);try{var worker=pool.submit(()->governance.processJob(lease));assertTrue(entered.await(5,TimeUnit.SECONDS));var cancellation=pool.submit(()->cancel(lease));Thread.sleep(120);assertFalse(cancellation.isDone(),"取消应等待受租约行锁保护的短写入事务");release.countDown();cancellation.get(5,TimeUnit.SECONDS);try{worker.get(5,TimeUnit.SECONDS);}catch(ExecutionException canceled){assertInstanceOf(IllegalStateException.class,canceled.getCause());}assertEquals("CANCELED",jobs.get(created.id()).state());assertEquals("CANCELED",state(created));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_case",Long.class));assertEquals("CANCELED",governance.report(id(created)).get("state"));}finally{release.countDown();pool.shutdownNow();}
    }
}
