package com.ruoyi.paismart.application.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.documents.UploadService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 本地内存对象配合真实作业行锁：远程复制不持事务，最终指针和收据必须受同一租约保护。 */
class OperationMigrationLeaseBoundaryTest {
    private static final String SOURCE="paismart/documents/move/source.txt",TARGET="paismart/documents/move/v2/source.txt";
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private MemoryStore objects;
    private JobService jobs;
    private OperationExecutionService service;
    private JobService.Job job;
    private Runnable finalGuard=()->{};
    private ExecutorService competitor;
    @BeforeEach void setup() {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        new ResourceDatabasePopulator(new ClassPathResource("db/paismart/V001__control_billing_jobs.sql"),new ClassPathResource("db/paismart/V002__documents_spaces.sql"),new ClassPathResource("db/paismart/V003__intelligence.sql"),new ClassPathResource("db/paismart/V004__operations_migration_receipts.sql"),new ClassPathResource("db/paismart/V005__evaluation_datasets.sql")).execute(ds);
        db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,del_flag CHAR(1),status CHAR(1))");db.update("INSERT INTO sys_user VALUES(2,'0','0')");
        objects=new MemoryStore();objects.data.put(SOURCE,"body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jobs=new JobService(db,tx){@Override public void requireLease(String id,String token){super.requireLease(id,token);if(objects.targetVerified){Runnable once=finalGuard;finalGuard=()->{};once.run();}}};
        SpaceService spaces=mock(SpaceService.class);when(spaces.canManageDocument(any(),anyLong(),any(),anyString())).thenReturn(true);
        var documents=new DocumentService(db,spaces,mock(MembershipService.class),objects,jobs);
        service=new OperationExecutionService(db,tx,documents,mock(UploadService.class),spaces,objects,jobs,true);
        db.update("INSERT INTO ps_document(id,user_id,visibility,file_name,sha256,object_key,byte_size,mime_type,status) VALUES('move',2,'PRIVATE','notes.txt',?,?,4,'text/plain','READY')",DocumentService.sha256(objects.data.get(SOURCE)),SOURCE);
        String planId=UUID.randomUUID().toString();Map<String,Object> manifest=Map.of("documents",List.of(Map.of("id","move","sourceKey",SOURCE,"targetKey",TARGET,"sha256",DocumentService.sha256(objects.data.get(SOURCE)),"byteSize",4)));
        String json=Jsons.write(manifest);
        job=jobs.enqueue(2,"OPERATION_EXECUTE",Map.of("planId",planId),"plan:"+planId);job=jobs.claimById(job.id(),600).orElseThrow();
        db.update("INSERT INTO ps_operation_plan(id,type,state,payload_json,manifest_hash,reason,created_by,approved_by,job_id) VALUES(?,'object-migrations','QUEUED',?,?,'本地边界夹具',2,1,?)",planId,json,DocumentService.sha256(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),job.id());
        competitor=Executors.newSingleThreadExecutor();
    }
    @AfterEach void close() throws InterruptedException {competitor.shutdownNow();assertTrue(competitor.awaitTermination(10,TimeUnit.SECONDS),"边界夹具线程必须退出");}
    private String pointer(){return db.queryForObject("SELECT object_key FROM ps_document WHERE id='move'",String.class);}
    private void executeAllowingLeaseLoss() throws Exception {try{service.processJob(job);}catch(IllegalStateException lost){assertTrue(lost.getMessage().contains("租约"));}}
    private void competeAtFinalGuard(Runnable mutation) throws Exception {
        AtomicBoolean committedAtGuard=new AtomicBoolean();List<Future<?>> pending=new ArrayList<>();
        finalGuard=()->{
            CountDownLatch attempted=new CountDownLatch(1);
            Future<?> future=competitor.submit(()->{attempted.countDown();mutation.run();});pending.add(future);
            try {
                assertTrue(attempted.await(5,TimeUnit.SECONDS));
                try{future.get(300,TimeUnit.MILLISECONDS);committedAtGuard.set(true);}catch(TimeoutException heldByGuard){/* 正式作业行锁阻止竞争写入先提交。 */}
            } catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}catch(ExecutionException ex){throw new AssertionError(ex.getCause());}
        };
        executeAllowingLeaseLoss();
        assertEquals(1,pending.size());pending.get(0).get(10,TimeUnit.SECONDS);
        assertFalse(committedAtGuard.get(),"取消或换手不能在最终租约校验后、文档切换前提交；旧源会在此窗口切换已失效作业的指针");
        assertEquals(TARGET,pointer());assertTrue(objects.data.containsKey(SOURCE));assertEquals(1,objects.puts);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_operation_item WHERE state='SUCCEEDED'",Integer.class));
    }
    @Test void cancellationCannotCommitBetweenFinalLeaseGuardAndPointerSwitch() throws Exception {
        competeAtFinalGuard(()->jobs.cancel(job.id(),jobs.get(job.id()).rowVersion()));
        assertEquals("CANCELED",jobs.get(job.id()).state());
    }
    @Test void leaseHandoffCannotCommitBetweenFinalLeaseGuardAndPointerSwitch() throws Exception {
        competeAtFinalGuard(()->db.update("UPDATE ps_job SET lease_token='replacement',row_version=row_version+1 WHERE id=?",job.id()));
        assertEquals("replacement",jobs.get(job.id()).leaseToken());
    }
    @Test void cancellationBeforeFinalGuardRetainsSourcePointerAndNoSuccessReceipt() {
        objects.afterVerified=()->jobs.cancel(job.id(),jobs.get(job.id()).rowVersion());
        assertThrows(IllegalStateException.class,()->service.processJob(job));assertEquals(SOURCE,pointer());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_operation_item WHERE state='SUCCEEDED'",Integer.class));assertTrue(objects.data.containsKey(TARGET));
    }
    @Test void aLaterDocumentWriteIsPreservedRatherThanOverwrittenByTheCopy() {
        objects.afterVerified=()->db.update("UPDATE ps_document SET file_name='later.txt',row_version=row_version+1 WHERE id='move'");
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->service.processJob(job));assertEquals(SOURCE,pointer());
        assertEquals("later.txt",db.queryForObject("SELECT file_name FROM ps_document WHERE id='move'",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_operation_item WHERE state='SUCCEEDED'",Integer.class));assertTrue(objects.data.containsKey(SOURCE));
    }
    @Test void anAcceptedCopyWithLostResponseIsVerifiedOnRetryWithoutSendingItAgain() throws Exception {
        objects.loseResponse=true;
        assertThrows(IllegalStateException.class,()->service.processJob(job));assertEquals(SOURCE,pointer());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_operation_item",Integer.class),"响应丢失须保留可核对的未决收据");
        assertEquals("UNCERTAIN",db.queryForObject("SELECT state FROM ps_operation_item",String.class));
        jobs.cancel(job.id(),jobs.get(job.id()).rowVersion());jobs.retry(job.id(),jobs.get(job.id()).rowVersion());job=jobs.claimById(job.id(),600).orElseThrow();
        objects.loseResponse=false;service.processJob(job);
        assertEquals(1,objects.puts,"响应丢失后的目标已存在时应读回核验，不重送对象写入");assertEquals(TARGET,pointer());assertTrue(objects.data.containsKey(SOURCE));
        assertEquals("SUCCEEDED",db.queryForObject("SELECT state FROM ps_operation_item",String.class));
    }
    @Test void anExistingWrongTargetIsRejectedBeforeAnyWriteOrPointerSwitch() {
        objects.data.put(TARGET,"evil".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->service.processJob(job));
        assertEquals(0,objects.puts);assertEquals(SOURCE,pointer());assertTrue(objects.data.containsKey(SOURCE));
    }
    private static class MemoryStore implements DocumentObjectStore {
        final Map<String,byte[]> data=new ConcurrentHashMap<>();int puts;boolean targetVerified,loseResponse;Runnable afterVerified=()->{};
        public void put(String key,InputStream body,long size,String mime){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"远程对象写入必须在事务外");try{data.put(key,body.readAllBytes());puts++;}catch(IOException failure){throw new UncheckedIOException(failure);}if(loseResponse)throw new IllegalStateException("模拟对象接受后响应丢失");}
        public InputStream open(String key){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"远程对象摘要核验必须在事务外");byte[] bytes=data.get(key);if(bytes==null)throw new IllegalStateException("缺少对象");return new ByteArrayInputStream(bytes){private boolean reported;@Override public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);if(n==-1&&TARGET.equals(key)&&!reported){reported=true;targetVerified=true;afterVerified.run();}return n;}};}
        public boolean exists(String key){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return data.containsKey(key);}
        public long size(String key){return data.get(key).length;}
        public void remove(String key){fail("搬迁不得删除源对象");}
    }
}
