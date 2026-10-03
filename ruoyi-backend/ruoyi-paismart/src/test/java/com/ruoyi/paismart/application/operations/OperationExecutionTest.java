package com.ruoyi.paismart.application.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paismart.application.documents.*;
import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class OperationExecutionTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private DocumentService docs;
    private SpaceService spaces;
    private UploadService uploads;
    private JobService jobs;
    private MemoryStore store;
    @BeforeEach void setup() {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        new ResourceDatabasePopulator(new ClassPathResource("db/paismart/V001__control_billing_jobs.sql"),new ClassPathResource("db/paismart/V002__documents_spaces.sql"),new ClassPathResource("db/paismart/V003__intelligence.sql"),new ClassPathResource("db/paismart/V004__operations_migration_receipts.sql"),new ClassPathResource("db/paismart/V005__evaluation_datasets.sql")).execute(ds);
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,del_flag CHAR(1) DEFAULT '0')");jdbc.update("INSERT INTO sys_user(user_id) VALUES(2)");
        docs=mock(DocumentService.class);spaces=mock(SpaceService.class);uploads=mock(UploadService.class);jobs=mock(JobService.class);store=new MemoryStore();
        when(spaces.canManageDocument(any(),anyLong(),any(),anyString())).thenReturn(true);
    }
    private OperationExecutionService service(boolean enabled) {return new OperationExecutionService(jdbc,tx,docs,uploads,spaces,store,jobs,enabled);}
    private JobService.Job plan(String kind,Map<String,Object> manifest) {
        String json=Jsons.write(manifest),id=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ps_operation_plan(id,type,state,payload_json,manifest_hash,reason,created_by,approved_by,job_id) VALUES(?,?,'QUEUED',?,?,'测试',2,1,?)",id,kind,json,DocumentService.sha256(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),job);
        return new JobService.Job(job,1,null,"OPERATION_EXECUTE","RUNNING",Map.of("planId",id),Map.of(),"lease",Instant.now().plusSeconds(3600),1,null,1);
    }
    private DocumentService.Document document(String id,String key,Instant deleted,String state) {
        byte[] data="body".getBytes();String sha=DocumentService.sha256(data);
        jdbc.update("INSERT INTO ps_document(id,user_id,visibility,file_name,sha256,object_key,byte_size,mime_type,status,deleted_at) VALUES(?,2,'PRIVATE','notes.txt',?,?,4,'text/plain',?,?)",id,sha,key,state,deleted==null?null:java.sql.Timestamp.from(deleted));
        store.data.put(key,data);
        var d=new DocumentService.Document(id,null,"notes.txt","notes.txt",state,1,1,"PRIVATE",4,null,2,0,2,key,"text/plain",deleted,Instant.now(),null,null);
        when(docs.getForProcessing(id)).thenReturn(d);return d;
    }
    @Test void disabledExecutionNeverReadsOrChangesObjects() {
        var job=plan("cleanup-plans",Map.of("documents",List.of()));
        assertThrows(ServiceException.class,()->service(false).processJob(job));assertEquals(0,store.removals);verifyNoInteractions(docs,uploads);
    }
    @Test void expiredSoftDeleteIsPurgedOnceWhileBillingAndTombstoneStay() throws Exception {
        var d=document("document-id","paismart/documents/document-id/source.txt",Instant.now().minusSeconds(40L*86400),"DELETED");
        jdbc.update("INSERT INTO ps_token_account(user_id,scope,available) VALUES(2,'LLM',123)");
        var job=plan("cleanup-plans",Map.of("documents",List.of(Map.of("id",d.id(),"objectKey",d.objectKey(),"sha256",DocumentService.sha256("body".getBytes()),"byteSize",4))));
        var executor=service(true);assertEquals("SUCCEEDED",executor.processJob(job).get("state"));executor.processJob(job);
        assertEquals(1,store.removals);assertEquals("PURGED",jdbc.queryForObject("SELECT status FROM ps_document WHERE id=?",String.class,d.id()));
        assertEquals(123L,jdbc.queryForObject("SELECT available FROM ps_token_account WHERE user_id=2",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ps_document WHERE deleted_at IS NOT NULL",Integer.class));
    }
    @Test void freshDeleteForeignNamespaceAndSharedReferenceRejectBeforeObjectMutation() {
        var recent=document("fresh","paismart/documents/fresh/source.txt",Instant.now().minusSeconds(86400),"DELETED");
        var job=plan("cleanup-plans",Map.of("documents",List.of(Map.of("id",recent.id(),"objectKey",recent.objectKey(),"sha256",DocumentService.sha256("body".getBytes()),"byteSize",4))));
        assertThrows(ServiceException.class,()->service(true).processJob(job));assertEquals(0,store.removals);
        var outside=document("outside","fashion/documents/source.txt",Instant.now().minusSeconds(40L*86400),"DELETED");
        var other=plan("cleanup-plans",Map.of("documents",List.of(Map.of("id",outside.id(),"objectKey",outside.objectKey(),"sha256",DocumentService.sha256("body".getBytes()),"byteSize",4))));
        assertThrows(ServiceException.class,()->service(true).processJob(other));assertEquals(0,store.removals);
    }
    @Test void objectMigrationChecksCopyAndKeepsSourceForRecovery() throws Exception {
        var d=document("move","paismart/documents/move/source.txt",null,"READY");
        var job=plan("object-migrations",Map.of("documents",List.of(Map.of("id",d.id(),"sourceKey",d.objectKey(),"targetKey","paismart/documents/move/v2/source.txt","sha256",DocumentService.sha256("body".getBytes()),"byteSize",4))));
        service(true).processJob(job);
        assertTrue(store.data.containsKey(d.objectKey()));assertArrayEquals(store.data.get(d.objectKey()),store.data.get("paismart/documents/move/v2/source.txt"));
        assertEquals("paismart/documents/move/v2/source.txt",jdbc.queryForObject("SELECT object_key FROM ps_document WHERE id='move'",String.class));
        assertEquals(0,store.removals);
    }
    @Test void approvalHashAndArbitraryLocalPathAreEnforced() {
        var job=plan("bootstrap-import",Map.of("path","C:\\Users\\admin\\secret.txt","files",List.of()));
        assertThrows(ServiceException.class,()->service(true).processJob(job));verifyNoInteractions(uploads);
        var altered=plan("cleanup-plans",Map.of("documents",List.of()));jdbc.update("UPDATE ps_operation_plan SET manifest_hash=? WHERE job_id=?","0".repeat(64),altered.id());
        assertThrows(ServiceException.class,()->service(true).processJob(altered));assertEquals(0,store.removals);
    }
    @Test void duplicateManifestIsRejectedBeforeFirstObjectEffect() {
        var d=document("duplicate","paismart/documents/duplicate/source.txt",Instant.now().minusSeconds(40L*86400),"DELETED");
        var entry=Map.<String,Object>of("id",d.id(),"objectKey",d.objectKey(),"sha256",DocumentService.sha256("body".getBytes()),"byteSize",4);
        var job=plan("cleanup-plans",Map.of("documents",List.of(entry,entry)));
        assertThrows(ServiceException.class,()->service(true).processJob(job));assertEquals(0,store.removals);
    }
    @Test void cancellationDuringCopyStopsMetadataSwitchAndRetainsSource() {
        var d=document("cancel-copy","paismart/documents/cancel-copy/source.txt",null,"READY");
        var job=plan("object-migrations",Map.of("documents",List.of(Map.of("id",d.id(),"sourceKey",d.objectKey(),"targetKey","paismart/documents/cancel-copy/v2/source.txt","sha256",DocumentService.sha256("body".getBytes()),"byteSize",4))));
        store.afterPut=()->doThrow(new IllegalStateException("任务已取消")).when(jobs).requireLease(job.id(),job.leaseToken());
        assertThrows(IllegalStateException.class,()->service(true).processJob(job));
        assertEquals(d.objectKey(),jdbc.queryForObject("SELECT object_key FROM ps_document WHERE id=?",String.class,d.id()));
        assertTrue(store.data.containsKey(d.objectKey()));assertEquals(0,store.removals);
    }
    @Test void bootstrapUsesVerifiedUploadPipelineAndQueuesRealDocumentProcessing() throws Exception {
        var realSpaces=new SpaceService(jdbc);var member=mock(MembershipService.class);
        var realDocs=new DocumentService(jdbc,realSpaces,member,store,jobs);
        var realUploads=new UploadService(jdbc,realSpaces,member,realDocs,store,jobs,512L*1024*1024);
        store.data.put("paismart/imports/builtin/notes.txt","body".getBytes());
        var entry=Map.<String,Object>of("sourceKey","paismart/imports/builtin/notes.txt","filename","notes.txt","byteSize",4,"sha256",DocumentService.sha256("body".getBytes()),"visibility","PRIVATE");
        var job=plan("bootstrap-import",Map.of("files",List.of(entry)));
        var executor=new OperationExecutionService(jdbc,tx,realDocs,realUploads,realSpaces,store,jobs,true);
        executor.processJob(job);executor.processJob(job);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ps_document WHERE status='QUEUED'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ps_upload WHERE status='MERGED'",Integer.class));
        String id=jdbc.queryForObject("SELECT id FROM ps_document",String.class);
        verify(jobs,times(1)).submit(eq(2L),eq("DOCUMENT_PROCESS"),eq(id),anyString());
        assertTrue(store.data.containsKey("paismart/imports/builtin/notes.txt"));
    }
    private UploadService realUploads() {
        spaces=new SpaceService(jdbc);docs=new DocumentService(jdbc,spaces,mock(MembershipService.class),store,jobs);
        uploads=new UploadService(jdbc,spaces,mock(MembershipService.class),docs,store,jobs,512L*1024*1024);return uploads;
    }
    private UploadService.Upload unfinished(String request) {
        byte[] body="body".getBytes();var owner=new SpaceService.Scope(2,false);
        var u=tx.execute(s->uploads.create(owner,new UploadService.Create("notes.txt",4,DocumentService.sha256(body),65536,null,"PRIVATE",request)));
        tx.executeWithoutResult(s->uploads.chunk(owner,u.uploadId(),0,DocumentService.sha256(body),new ByteArrayInputStream(body)));return u;
    }
    @Test void expiredTemporaryChunksHaveApprovedScopeAndAtomicRetryReceipts() throws Exception {
        realUploads();var u=unfinished("expired");jdbc.update("UPDATE ps_upload SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(10)),u.uploadId());
        var owner=new SpaceService.Scope(2,false);var candidates=uploads.cleanupCandidates(owner);assertEquals(1,candidates.size());
        assertTrue(uploads.cleanupCandidates(new SpaceService.Scope(3,false)).isEmpty());
        var job=plan("cleanup-plans",Map.of("documents",List.of(),"uploads",candidates));var executor=service(true);executor.processJob(job);executor.processJob(job);
        assertEquals(1,store.removals);assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM ps_upload WHERE id=?",String.class,u.uploadId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ps_upload_chunk",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ps_operation_item",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ps_upload",Integer.class));
    }
    @Test void cancellingAfterTemporaryDeleteKeepsDbReceiptAndCannotDeleteNextObject() {
        realUploads();var u=unfinished("cancel-temporary");jdbc.update("UPDATE ps_upload SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(10)),u.uploadId());
        var candidates=uploads.cleanupCandidates(new SpaceService.Scope(2,false));var job=plan("cleanup-plans",Map.of("documents",List.of(),"uploads",candidates));
        store.afterRemove=()->doThrow(new IllegalStateException("lease invalid")).when(jobs).requireLease(job.id(),job.leaseToken());
        assertThrows(IllegalStateException.class,()->service(true).processJob(job));assertEquals(1,store.removals);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ps_upload_chunk",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ps_operation_item",Integer.class));
        assertEquals("UPLOADING",jdbc.queryForObject("SELECT status FROM ps_upload WHERE id=?",String.class,u.uploadId()));
    }
    @Test void legacyFormalObjectCopiesOnlyApprovedSourceIntoExistingMetadataAndRetainsOldSource() throws Exception {
        var d=document("legacy","paismart/documents/legacy/source.txt",null,"QUEUED");store.data.remove(d.objectKey());jdbc.update("UPDATE ps_document SET legacy_md5=? WHERE id=?","a".repeat(32),d.id());
        var executor=service(true);List<String> sourceReads=new ArrayList<>();executor.legacySource(key->{sourceReads.add(key);return new ByteArrayInputStream("body".getBytes());});
        var entry=Map.<String,Object>of("id",d.id(),"legacySourceKey","merged/"+"a".repeat(32),"targetKey",d.objectKey(),"sha256",DocumentService.sha256("body".getBytes()),"byteSize",4);
        var job=plan("object-migrations",Map.of("documents",List.of(entry)));executor.processJob(job);executor.processJob(job);
        assertArrayEquals("body".getBytes(),store.data.get(d.objectKey()));assertEquals(List.of("merged/"+"a".repeat(32)),sourceReads);assertEquals(0,store.removals);
        var bad=new LinkedHashMap<>(entry);bad.put("legacySourceKey","merged/unrelated-file.txt");var another=plan("object-migrations",Map.of("documents",List.of(bad)));
        assertThrows(ServiceException.class,()->executor.processJob(another));assertEquals(1,sourceReads.size());
    }
    @Test void legacyIncompletePieceCanResumeAfterExactSourceCopyButMismatchedHashCannot() throws Exception {
        realUploads();var u=unfinished("legacy-piece");String target="paismart/uploads/"+u.uploadId()+"/0";store.data.remove(target);jdbc.update("UPDATE ps_upload SET legacy_md5=? WHERE id=?","b".repeat(32),u.uploadId());
        var executor=service(true);executor.legacySource(key->new ByteArrayInputStream("body".getBytes()));
        var entry=Map.<String,Object>of("uploadId",u.uploadId(),"chunkNo",0,"legacySourceKey","chunks/"+"b".repeat(32)+"/0","targetKey",target,"sha256",DocumentService.sha256("body".getBytes()),"byteSize",4);
        executor.processJob(plan("object-migrations",Map.of("documents",List.of(entry))));
        assertEquals(List.of(0),uploads.status(new SpaceService.Scope(2,false),u.uploadId()).completedChunks());
        var d=tx.execute(s->uploads.merge(new SpaceService.Scope(2,false),u.uploadId()));assertArrayEquals("body".getBytes(),store.data.get(d.objectKey()));
    }
    @Test void builtinSameLogicalSourceSkipsDuplicatePlansAndChangedContentKeepsOldTombstone() throws Exception {
        realUploads();store.data.put("paismart/imports/builtin/notes.txt","body".getBytes());
        var entry=Map.<String,Object>of("sourceKey","paismart/imports/builtin/notes.txt","filename","notes.txt","byteSize",4,"sha256",DocumentService.sha256("body".getBytes()),"visibility","PRIVATE");
        var executor=service(true);executor.processJob(plan("bootstrap-import",Map.of("files",List.of(entry))));executor.processJob(plan("bootstrap-import",Map.of("files",List.of(entry))));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ps_document",Integer.class));String old=jdbc.queryForObject("SELECT id FROM ps_document",String.class);docs.markProcessingResult(old,1,"READY","body",null,0);
        store.data.put("paismart/imports/builtin/notes.txt","new body".getBytes());var changed=new LinkedHashMap<>(entry);changed.put("byteSize",8);changed.put("sha256",DocumentService.sha256("new body".getBytes()));
        executor.processJob(plan("bootstrap-import",Map.of("files",List.of(changed))));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM ps_document",Integer.class));assertEquals("DELETED",docs.getForProcessing(old).state());
        assertTrue(store.data.containsKey(docs.getForProcessing(old).objectKey()));assertNotEquals(old,jdbc.queryForObject("SELECT document_id FROM ps_bootstrap_document",String.class));
    }
    @Test void builtinMissingIndexEvidenceQueuesRepairRatherThanClaimingAlreadyReady() throws Exception {
        realUploads();store.data.put("paismart/imports/builtin/notes.txt","body".getBytes());var entry=Map.<String,Object>of("sourceKey","paismart/imports/builtin/notes.txt","filename","notes.txt","byteSize",4,"sha256",DocumentService.sha256("body".getBytes()),"visibility","PRIVATE");
        var executor=service(true);executor.processJob(plan("bootstrap-import",Map.of("files",List.of(entry))));String id=jdbc.queryForObject("SELECT id FROM ps_document",String.class);docs.markProcessingResult(id,1,"READY","body",null,0);
        jdbc.update("INSERT INTO ps_document_chunk(id,document_id,content_version,chunk_no,text_content,anchor_text,created_at) VALUES('c',?,1,0,'body','body',CURRENT_TIMESTAMP)",id);
        var search=mock(com.ruoyi.paismart.application.intelligence.SearchService.class);when(search.activeIndex()).thenReturn(Map.of("index_name","paismart-knowledge-test"));when(search.esRequest(eq("POST"),endsWith("/_count"),any(),eq(30))).thenReturn(Map.of("count",0));executor.indexReadiness(search);
        executor.processJob(plan("bootstrap-import",Map.of("files",List.of(entry))));assertEquals("QUEUED",docs.getForProcessing(id).state());assertEquals(2,docs.getForProcessing(id).contentVersion());
        verify(jobs,times(1)).submit(eq(2L),eq("DOCUMENT_PROCESS"),eq(id),anyString());verify(jobs,times(1)).enqueue(eq(2L),eq("DOCUMENT_PROCESS"),anyMap(),anyString());
    }
    private static class MemoryStore implements DocumentObjectStore {
        Map<String,byte[]> data=new HashMap<>();int removals;Runnable afterPut=()->{},afterRemove=()->{};
        public void put(String key,InputStream stream,long size,String mime) {try{data.put(key,stream.readAllBytes());afterPut.run();}catch(IOException ex){throw new UncheckedIOException(ex);}}
        public InputStream open(String key) {return new ByteArrayInputStream(data.get(key));}
        public long size(String key) {return data.get(key).length;}
        public boolean exists(String key) {return data.containsKey(key);}
        public void remove(String key) {removals++;data.remove(key);afterRemove.run();}
    }
}
