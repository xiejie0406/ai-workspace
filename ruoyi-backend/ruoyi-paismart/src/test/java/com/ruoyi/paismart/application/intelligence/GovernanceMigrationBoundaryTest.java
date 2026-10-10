package com.ruoyi.paismart.application.intelligence;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 固定块数、空探针、首索引与缺水位反例；所有外部请求均为本地替身。 */
class GovernanceMigrationBoundaryTest {
    JdbcTemplate db;TransactionTemplate tx;GovernanceService service;IndexCoordinator coordinator;SearchService search;ProviderService providers;DocumentIntelligenceService indexing;DocumentService docs;ModelGateway models;IntelligenceSettings settings;JobService jobs;SpaceService spaces;
    ProviderService.Provider provider=new ProviderService.Provider("provider","EMBEDDING","名称","https://provider.invalid","model",2,"ai.paismart.test",true,false,1,Map.of());
    @BeforeEach void setup()throws Exception{
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:gov_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try(Connection c=source.getConnection()){for(String file:List.of("V001__control_billing_jobs.sql","V002__documents_spaces.sql","V003__intelligence.sql","V005__evaluation_datasets.sql"))ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/"+file));}
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));search=mock(SearchService.class);providers=mock(ProviderService.class);indexing=mock(DocumentIntelligenceService.class);docs=mock(DocumentService.class);models=mock(ModelGateway.class);settings=mock(IntelligenceSettings.class);jobs=mock(JobService.class);spaces=mock(SpaceService.class);coordinator=new IndexCoordinator(db,tx);
        when(settings.text(anyString(),anyString())).thenAnswer(i->i.getArgument(1));when(settings.number(anyString(),anyInt())).thenAnswer(i->i.getArgument(1));when(providers.fromSnapshot(anyString())).thenReturn(provider);when(providers.get("provider")).thenReturn(provider);when(providers.active("EMBEDDING")).thenReturn(provider);when(providers.snapshot(provider)).thenReturn(Map.of("id","provider","revision",1));when(models.embed(anyLong(),anyString(),anyList(),any())).thenReturn(new ModelGateway.Embedding(List.of(List.of(.1,.2)),2));
        db.update("INSERT INTO ps_provider(id,scope,name,endpoint,model,dimension,secret_ref,enabled,active,config_json,updated_at) VALUES('provider','EMBEDDING','名称','https://provider.invalid','model',2,'ai.paismart.test',TRUE,FALSE,'{}',CURRENT_TIMESTAMP)");
        service=new GovernanceService(db,tx,jobs,search,indexing,providers,docs,models,settings,coordinator);ReflectionTestUtils.setField(service,"spaces",spaces);ReflectionTestUtils.setField(service,"memberships",mock(com.ruoyi.paismart.application.MembershipService.class));
        when(search.esRequest("GET","/_alias/paismart-knowledge",null)).thenAnswer(i->{String name=db.queryForObject("SELECT index_name FROM ps_index_revision ORDER BY created_at DESC LIMIT 1",String.class);return Map.of(name,Map.of("aliases",Map.of("paismart-knowledge",Map.of())));});
        when(search.activeIndex()).thenReturn(Map.of("id","target"));when(search.esRequest(anyString(),endsWith("/_count"),isNull())).thenReturn(Map.of("count",0));
        when(search.esRequest("GET","/paismart-knowledge-target",null)).thenReturn(targetMapping(2,"target"));
    }
    JobService.Job job(String type){Map<String,Object> payload="INDEX_ROLLBACK".equals(type)?Map.of("indexId","target","expectedVersion",db.queryForObject("SELECT row_version FROM ps_index_revision WHERE id='target'",Long.class)):Map.of("indexId","target");return new JobService.Job("job",2,null,type,"RUNNING",payload,Map.of(),"lease",null,0,null,1);}
    void revision(String state,String queries){db.update("INSERT INTO ps_index_revision(id,index_name,provider_snapshot,state,checkpoint_document,processed_count,source_watermark,validation_json,created_at,updated_at) VALUES('target','paismart-knowledge-target','{}',?,'zzzz',1,TIMESTAMP '2099-01-01 00:00:00',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",state,Jsons.write(Map.of("queries",Jsons.list(queries),"state","PENDING")));}
    DocumentService.Document document(String id,String text){
        db.update("INSERT INTO ps_document(id,user_id,visibility,file_name,sha256,object_key,byte_size,mime_type,status,updated_at) VALUES(?,2,'PUBLIC',?,'"+"0".repeat(64)+"','key',1,'text/plain','READY',TIMESTAMP '2000-01-01 00:00:00')",id,id+".txt");
        var doc=mock(DocumentService.Document.class);when(doc.id()).thenReturn(id);when(doc.ownerId()).thenReturn(2L);when(doc.visibility()).thenReturn("PUBLIC");when(doc.state()).thenReturn("READY");when(doc.contentVersion()).thenReturn(1);when(doc.rowVersion()).thenReturn(1L);when(doc.filename()).thenReturn(id+".txt");when(docs.getForProcessing(id)).thenReturn(doc);when(docs.getReadable(any(),eq(id))).thenReturn(doc);chunk(id+"0",id,0,text);return doc;
    }
    void chunk(String id,String doc,int no,String text){db.update("INSERT INTO ps_document_chunk(id,document_id,content_version,chunk_no,text_content,anchor_text,vector_json,embedding_revision,created_at) VALUES(?,?,1,?,?,'anchor','[0.1,0.2]','target',CURRENT_TIMESTAMP)",id,doc,no,text);}
    Map<String,Object> targetMapping(int dimension,String revision){return Map.of("paismart-knowledge-target",Map.of("mappings",Map.of("properties",Map.of("text_content",Map.of("type","text"),"embedding",Map.of("type","dense_vector","dims",dimension)),"_meta",Map.of("project","paismart","providerId","provider","providerRevision",1,"model","model","dimension",dimension,"indexRevision",revision))));}
    void currentRevision(){db.update("INSERT INTO ps_index_revision(id,index_name,provider_snapshot,state,source_watermark,validation_json,created_at,updated_at) VALUES('current','paismart-knowledge-current','{}','ACTIVE',CURRENT_TIMESTAMP,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");when(search.esRequest("GET","/_alias/paismart-knowledge",null)).thenReturn(Map.of("paismart-knowledge-target",Map.of("aliases",Map.of("paismart-knowledge",Map.of()))));}
    void actualGovernanceHandlers(){@SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<com.ruoyi.paismart.application.jobs.PaismartJobHandler> handlers=mock(org.springframework.beans.factory.ObjectProvider.class);when(handlers.orderedStream()).thenAnswer(i->java.util.stream.Stream.of(service));jobs.handlers(handlers);}
    void realIndexJob(String state){actualRequestReceipts();actualGovernanceHandlers();db.update("INSERT INTO ps_job(id,user_id,type,state,payload_json,request_key) VALUES('stale',2,'INDEX_REBUILD',?,'{\"indexId\":\"target\"}','stale-key')",state);}
    @Test void retiredRevisionCannotBeReactivatedByRetryingItsOldFailedRebuild(){
        revision("RETIRED","[]");currentRevision();realIndexJob("FAILED");
        assertThrows(IllegalStateException.class,()->jobs.retry("stale",1));
        assertEquals("FAILED",jobs.get("stale").state());assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));assertEquals("RETIRED",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));verifyNoInteractions(search,models,indexing);
    }
    @Test void retiredRevisionWorkerRejectsBeforeRewritingHistoricalStateOrAlias(){
        revision("RETIRED","[]");currentRevision();
        assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_REBUILD")));
        assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));assertEquals("RETIRED",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));verifyNoInteractions(search,models,indexing);
    }
    @Test void activeRevisionWorkerCannotTurnCurrentVersionBackIntoValidated(){
        revision("ACTIVE","[]");assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_REBUILD")));
        assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));verifyNoInteractions(search,models,indexing);
    }
    @Test void pendingAliasTransitionRequiresRecoveryInsteadOfRebuildingItsTarget(){
        revision("FAILED","[]");currentRevision();realIndexJob("FAILED");db.update("INSERT INTO ps_index_switch(id,from_revision,to_revision,state,created_at,updated_at) VALUES('transition','current','target','RECOVERY_REQUIRED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertThrows(IllegalStateException.class,()->jobs.retry("stale",1));assertEquals("FAILED",jobs.get("stale").state());
        db.update("UPDATE ps_job SET state='QUEUED' WHERE id='stale'");var rebuilding=jobs.claimById("stale",600).orElseThrow();assertThrows(IllegalStateException.class,()->service.processJob(rebuilding));assertEquals("FAILED",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));verifyNoInteractions(search,models,indexing);
        when(search.esRequest("GET","/_aliases",null)).thenReturn(Map.of("paismart-knowledge-target",Map.of("aliases",Map.of("paismart-knowledge",Map.of()))));
        db.update("UPDATE ps_job SET type='INDEX_RECOVER',state='QUEUED' WHERE id='stale'");assertEquals("RECOVERED",service.processJob(jobs.claimById("stale",600).orElseThrow()).get("state"));assertEquals("COMPLETED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));
    }
    @Test void failedRevisionStillAllowsItsOwnRetryWithoutChangingCurrentUntilSwitch(){
        revision("FAILED","[]");currentRevision();realIndexJob("FAILED");jobs.retry("stale",1);
        assertEquals("QUEUED",jobs.get("stale").state());assertEquals("BUILDING",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));
        assertEquals("ACTIVE",service.processJob(jobs.claimById("stale",600).orElseThrow()).get("state"));verifyNoInteractions(models);
    }
    @Test void validatedWithoutSwitchLogCanResumeAfterItsRunningLeaseExpires(){
        revision("VALIDATED","[]");currentRevision();realIndexJob("RUNNING");db.update("UPDATE ps_job SET lease_token='expired',lease_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id='stale'");
        var reclaimed=jobs.claimById("stale",600).orElseThrow();assertNotEquals("expired",reclaimed.leaseToken());assertEquals("ACTIVE",service.processJob(reclaimed).get("state"));assertEquals("COMPLETED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));verifyNoInteractions(models);
    }
    @Test void newContinuationCannotCreateAnotherUnfinishedJobForTheSameRevision(){
        revision("FAILED","[]");realIndexJob("QUEUED");assertThrows(IllegalStateException.class,()->service.rebuild(2,Map.of("targetProvider","provider","fresh",false),"second"));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));assertEquals("FAILED",db.queryForObject("SELECT state FROM ps_index_revision",String.class));
    }
    @Test void zeroProgressFailedContinuationRestoresItsMissingPhysicalIndexWithFrozenMetadata(){
        revision("FAILED","[]");currentRevision();actualRequestReceipts();db.update("UPDATE ps_index_revision SET checkpoint_document=NULL,processed_count=0 WHERE id='target'");when(search.esRequest("GET","/paismart-knowledge-target",null)).thenThrow(new ControlledHttp.HttpStatusFailure(404));
        var queued=service.rebuild(2,Map.of("targetProvider","provider","fresh",false),"restore-empty");assertEquals("ACTIVE",service.processJob(jobs.claimById(queued.id(),600).orElseThrow()).get("state"));
        verify(indexing).createIndex(eq("paismart-knowledge-target"),eq(2),argThat(meta->"target".equals(meta.get("indexRevision"))&&"provider".equals(meta.get("providerId"))));verifyNoInteractions(models);
    }
    @Test void existingPhysicalIndexWithWrongDimensionOrRevisionIsNeverOverwritten(){
        revision("BUILDING","[]");currentRevision();when(search.esRequest("GET","/paismart-knowledge-target",null)).thenReturn(targetMapping(3,"foreign"));
        assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_REBUILD")));assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));verify(search,never()).esRequest(eq("PUT"),anyString(),any());verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());verifyNoInteractions(models,indexing);
    }
    @Test void missingPhysicalIndexWithExistingCheckpointCannotResumeByCreatingAnEmptyProjection(){
        revision("FAILED","[]");currentRevision();when(search.esRequest("GET","/paismart-knowledge-target",null)).thenThrow(new ControlledHttp.HttpStatusFailure(404));
        assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_REBUILD")));verifyNoInteractions(indexing,models);verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));
    }
    Map<String,Object> emptyMetadataMapping(){return Map.of("paismart-knowledge-target",Map.of("mappings",Map.of("properties",Map.of("text_content",Map.of("type","text"),"embedding",Map.of("type","dense_vector","dims",2)),"_meta",Map.of())));}
    @Test void zeroProgressEmptyPhysicalIndexCanFinishItsInterruptedMetadataInitialization(){
        revision("FAILED","[]");currentRevision();db.update("UPDATE ps_index_revision SET checkpoint_document=NULL,processed_count=0 WHERE id='target'");when(search.esRequest("GET","/paismart-knowledge-target",null)).thenReturn(emptyMetadataMapping());
        assertEquals("ACTIVE",service.processJob(job("INDEX_REBUILD")).get("state"));verify(search).esRequest(eq("PUT"),eq("/paismart-knowledge-target/_mapping"),argThat(body->"target".equals(Values.map(Values.map(body).get("_meta")).get("indexRevision"))));verifyNoInteractions(indexing,models);
    }
    @Test void emptyMetadataOnNonemptyPhysicalIndexCannotBeAdoptedOrSwitched(){
        revision("FAILED","[]");currentRevision();db.update("UPDATE ps_index_revision SET checkpoint_document=NULL,processed_count=0 WHERE id='target'");when(search.esRequest("GET","/paismart-knowledge-target",null)).thenReturn(emptyMetadataMapping());when(search.esRequest("GET","/paismart-knowledge-target/_count",null)).thenReturn(Map.of("count",1));
        assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_REBUILD")));verify(search,never()).esRequest(eq("PUT"),anyString(),any());verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());verifyNoInteractions(indexing,models);assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));
    }
    void actualAlias(){var alias=new java.util.concurrent.atomic.AtomicReference<>("paismart-knowledge-current");when(search.esRequest(eq("POST"),eq("/_aliases"),any())).thenAnswer(i->{for(Object raw:Values.list(Values.map(i.getArgument(2)).get("actions"))){var action=Values.map(raw);if(action.containsKey("add"))alias.set(Values.text(Values.map(action.get("add")).get("index")));}return Map.of("acknowledged",true);});when(search.esRequest("GET","/_alias/paismart-knowledge",null)).thenAnswer(i->Map.of(alias.get(),Map.of("aliases",Map.of("paismart-knowledge",Map.of()))));when(search.esRequest("GET","/paismart-knowledge-current",null)).thenReturn(Map.of("paismart-knowledge-current",Values.map(targetMapping(2,"current").get("paismart-knowledge-target"))));}
    Map<String,Object> completeJob(JobService.Job queued){var claimed=jobs.claimById(queued.id(),600).orElseThrow();var result=service.processJob(claimed);jobs.complete(claimed.id(),claimed.leaseToken(),result);return result;}
    @Test void delayedRollbackCannotCrossLaterRetirementVersionAndReverseCurrentAgain(){
        revision("RETIRED","[]");currentRevision();actualRequestReceipts();actualGovernanceHandlers();actualAlias();
        var first=service.rollback(2,Map.of("indexId","target","expectedVersion",1),"first");var delayed=service.rollback(2,Map.of("indexId","target","expectedVersion",1),"delayed");completeJob(first);completeJob(service.rollback(2,Map.of("indexId","current","expectedVersion",2),"reverse"));
        assertEquals(3L,db.queryForObject("SELECT row_version FROM ps_index_revision WHERE id='target'",Long.class));var stale=jobs.claimById(delayed.id(),600).orElseThrow();clearInvocations(search);assertThrows(IllegalStateException.class,()->service.processJob(stale));
        db.update("UPDATE ps_job SET retries=3 WHERE id=?",stale.id());jobs.fail(stale.id(),stale.leaseToken(),"目标版本已变化");assertThrows(IllegalStateException.class,()->jobs.retry(stale.id(),jobs.get(stale.id()).rowVersion()));assertEquals("FAILED",jobs.get(stale.id()).state());
        assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));assertEquals("RETIRED",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));verifyNoInteractions(search,models,indexing);
    }
    @Test void sameRollbackKeyReplaysOriginalReceiptAfterSwitchAndRejectsChangedVersion(){
        revision("RETIRED","[]");currentRevision();actualRequestReceipts();actualAlias();var body=Map.<String,Object>of("indexId","target","expectedVersion",1);var first=service.rollback(2,body,"same");completeJob(first);
        doThrow(new IllegalStateException("凭据已暂停")).when(providers).secret(any());var replay=service.rollback(2,body,"same");assertEquals(first.id(),replay.id());assertEquals("SUCCEEDED",replay.state());assertThrows(IllegalStateException.class,()->service.rollback(2,Map.of("indexId","target","expectedVersion",2),"same"));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));
    }
    @Test void activeTargetAndLegacyUnfrozenRollbackCannotBeProcessedOrRebound(){
        revision("ACTIVE","[]");assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_ROLLBACK")));db.update("UPDATE ps_index_revision SET state='RETIRED'");var legacy=new JobService.Job("legacy",2,null,"INDEX_ROLLBACK","RUNNING",Map.of("indexId","target"),Map.of(),"lease",null,0,null,1);assertThrows(IllegalStateException.class,()->service.processJob(legacy));assertEquals("RETIRED",db.queryForObject("SELECT state FROM ps_index_revision",String.class));verifyNoInteractions(search,models,indexing);
    }
    @Test void failedRollbackCanRetryItsFrozenVersionBeforeAliasSideEffect(){
        revision("RETIRED","[]");currentRevision();actualRequestReceipts();actualGovernanceHandlers();actualAlias();var queued=service.rollback(2,Map.of("indexId","target","expectedVersion",1),"retry-before-alias");var claimed=jobs.claimById(queued.id(),600).orElseThrow();doThrow(new IllegalStateException("校验暂不可用")).doNothing().when(search).requireRawProjection("paismart-knowledge-target");
        assertThrows(IllegalStateException.class,()->service.processJob(claimed));db.update("UPDATE ps_job SET retries=3 WHERE id=?",claimed.id());jobs.fail(claimed.id(),claimed.leaseToken(),"校验暂不可用");assertEquals("FAILED",jobs.get(claimed.id()).state());assertEquals("RETIRED",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));jobs.retry(claimed.id(),jobs.get(claimed.id()).rowVersion());assertEquals("ACTIVE",completeJob(jobs.get(claimed.id())).get("state"));verifyNoInteractions(models);
    }
    @Test void rollbackAliasFailureRequiresRecoveryButCompletedRecoveryRemainsValid(){
        revision("RETIRED","[]");currentRevision();actualRequestReceipts();actualGovernanceHandlers();actualAlias();var queued=service.rollback(2,Map.of("indexId","target","expectedVersion",1),"alias-failure");var claimed=jobs.claimById(queued.id(),600).orElseThrow();when(search.esRequest("GET","/_alias/paismart-knowledge",null)).thenThrow(new IllegalStateException("别名回读暂不可用"));assertThrows(IllegalStateException.class,()->service.processJob(claimed));assertEquals("RECOVERY_REQUIRED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));
        db.update("UPDATE ps_job SET retries=3 WHERE id=?",claimed.id());jobs.fail(claimed.id(),claimed.leaseToken(),"别名待恢复");assertThrows(IllegalStateException.class,()->jobs.retry(claimed.id(),jobs.get(claimed.id()).rowVersion()));assertEquals("FAILED",jobs.get(claimed.id()).state());when(search.esRequest("GET","/_aliases",null)).thenReturn(Map.of("paismart-knowledge-target",Map.of("aliases",Map.of("paismart-knowledge",Map.of()))));completeJob(service.recoverIndex(2,"recover"));assertEquals("COMPLETED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));
    }
    @Test void rollbackMissingPhysicalIndexCannotCreateAReplacementForItsRetiredVersion(){
        revision("RETIRED","[]");currentRevision();db.update("UPDATE ps_index_revision SET checkpoint_document=NULL,processed_count=0 WHERE id='target'");when(search.esRequest("GET","/paismart-knowledge-target",null)).thenThrow(new ControlledHttp.HttpStatusFailure(404));
        assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_ROLLBACK")));assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));assertEquals("RETIRED",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));verifyNoInteractions(indexing,models);verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());
    }
    @Test void rollbackRejectsForeignPhysicalMetadataBeforeCatchupOrModelProbe(){
        revision("RETIRED","[\"问题\"]");currentRevision();when(search.esRequest("GET","/paismart-knowledge-target",null)).thenReturn(targetMapping(2,"other"));
        assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_ROLLBACK")));assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));verifyNoInteractions(indexing,models);verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());
    }
    Map<String,Object> changedPhysicalNumber(String key,Object value,boolean field){
        var index=new LinkedHashMap<>(Values.map(targetMapping(2,"target").get("paismart-knowledge-target")));var mappings=new LinkedHashMap<>(Values.map(index.get("mappings")));
        if(field){var properties=new LinkedHashMap<>(Values.map(mappings.get("properties")));var embedding=new LinkedHashMap<>(Values.map(properties.get("embedding")));embedding.put(key,value);properties.put("embedding",embedding);mappings.put("properties",properties);}
        else{var metadata=new LinkedHashMap<>(Values.map(mappings.get("_meta")));metadata.put(key,value);mappings.put("_meta",metadata);}
        index.put("mappings",mappings);return Map.of("paismart-knowledge-target",index);
    }
    void rejectRollbackMapping(Map<String,Object> mapping){
        when(search.esRequest("GET","/paismart-knowledge-target",null)).thenReturn(mapping);assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_ROLLBACK")));assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='current'",String.class));assertEquals("RETIRED",db.queryForObject("SELECT state FROM ps_index_revision WHERE id='target'",String.class));assertEquals(1L,db.queryForObject("SELECT row_version FROM ps_index_revision WHERE id='target'",Long.class));verifyNoInteractions(indexing,models);verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());
    }
    @Test void fractionalFrozenProviderRevisionCannotBeTruncatedIntoTheRequestedVersion(){revision("RETIRED","[]");currentRevision();rejectRollbackMapping(changedPhysicalNumber("providerRevision",1.5,false));}
    @Test void fractionalFrozenDimensionCannotBeTruncatedIntoTheRequestedDimension(){revision("RETIRED","[]");currentRevision();rejectRollbackMapping(changedPhysicalNumber("dimension",2.5,false));}
    @Test void fractionalVectorFieldDimensionCannotBeTruncatedIntoTheRequestedDimension(){revision("RETIRED","[]");currentRevision();rejectRollbackMapping(changedPhysicalNumber("dims",2.5,true));}
    @Test void nonfiniteAndStringPhysicalNumbersCannotImpersonateFrozenNumbers(){
        revision("RETIRED","[]");currentRevision();for(Object value:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,"2")){rejectRollbackMapping(changedPhysicalNumber("dimension",value,false));rejectRollbackMapping(changedPhysicalNumber("dims",value,true));}rejectRollbackMapping(changedPhysicalNumber("providerRevision","1",false));
    }
    @Test void exactCountMismatchFailsBeforeAliasOrPaidProbe(){
        revision("BUILDING","[\"问题\"]");document("doc","证据");assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_REBUILD")));assertEquals("FAILED",db.queryForObject("SELECT state FROM ps_index_revision",String.class));var report=Jsons.map(db.queryForObject("SELECT validation_json FROM ps_index_revision",String.class));assertEquals(1L,((Number)report.get("expectedChunks")).longValue());assertEquals(0L,((Number)report.get("actualChunks")).longValue());verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());verifyNoInteractions(models);
    }
    @Test void emptyConfiguredKnnProbeFailsAndPersistsActualDiagnostics(){
        revision("BUILDING","[\"问题\"]");document("doc","证据");when(search.esRequest(eq("GET"),endsWith("/_count"),isNull())).thenReturn(Map.of("count",1));when(search.esRequest(eq("POST"),endsWith("/_search"),any())).thenReturn(Map.of("hits",Map.of("hits",List.of())));
        assertThrows(IllegalStateException.class,()->service.processJob(job("INDEX_REBUILD")));assertEquals("FAILED",Jsons.map(db.queryForObject("SELECT validation_json FROM ps_index_revision",String.class)).get("state"));verify(models).embed(eq(2L),startsWith("search:index-validation:target:"),eq(List.of("问题")),eq(provider));verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());
    }
    @Test void realCountAndNonemptyProbePermitSingleDurableSwitch(){
        revision("BUILDING","[\"问题\"]");document("doc","证据");when(search.esRequest(eq("GET"),endsWith("/_count"),isNull())).thenReturn(Map.of("count",1));when(search.esRequest(eq("POST"),endsWith("/_search"),any())).thenReturn(Map.of("hits",Map.of("hits",List.of(Map.of("_id","doc0")))));
        assertEquals("ACTIVE",service.processJob(job("INDEX_REBUILD")).get("state"));assertEquals("COMPLETED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));assertEquals("PASSED",Jsons.map(db.queryForObject("SELECT validation_json FROM ps_index_revision",String.class)).get("state"));verify(search,times(1)).esRequest(eq("POST"),eq("/_aliases"),any());
    }
    @Test void firstAliasSideEffectFailureIsDurableAndRecoverable(){
        revision("PREPARED","[]");AtomicInteger checks=new AtomicInteger();assertThrows(IllegalStateException.class,()->coordinator.activate("target",()->{if(checks.incrementAndGet()==4)throw new IllegalStateException("lost lease");},providers,search));assertEquals("RECOVERY_REQUIRED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));assertEquals("PREPARED",db.queryForObject("SELECT state FROM ps_index_revision",String.class));
        when(search.esRequest("GET","/_aliases",null)).thenReturn(Map.of("paismart-knowledge-target",Map.of("aliases",Map.of("paismart-knowledge",Map.of()))));assertEquals("RECOVERED",service.processJob(job("INDEX_RECOVER")).get("state"));assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision",String.class));assertEquals("COMPLETED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));
    }
    @Test void nullLegacyWatermarkReplaysOldDocumentBeforeRollbackActivation(){
        revision("RETIRED","[]");db.update("UPDATE ps_index_revision SET source_watermark=NULL");var doc=document("old","证据");when(search.esRequest(eq("GET"),endsWith("/_count"),isNull())).thenReturn(Map.of("count",1));service.processJob(job("INDEX_ROLLBACK"));verify(indexing,times(1)).indexDocument(eq(doc),anyList(),anyMap(),any());assertNotNull(db.queryForObject("SELECT source_watermark FROM ps_index_revision",Object.class));assertEquals("ACTIVE",db.queryForObject("SELECT state FROM ps_index_revision",String.class));
    }
    @Test void failedFirstSwitchWithNoActualAliasIsAbortedWithoutInventingActiveIndex(){
        revision("PREPARED","[]");db.update("INSERT INTO ps_index_switch(id,to_revision,state,created_at,updated_at) VALUES('switch','target','PREPARED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");when(search.esRequest("GET","/_aliases",null)).thenReturn(Map.of());
        service.processJob(job("INDEX_RECOVER"));assertEquals("ABORTED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));assertEquals("FAILED",db.queryForObject("SELECT state FROM ps_index_revision",String.class));verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());
    }
    @Test void firstIndexHasMetadataCountWatermarkAndCommonSwitch(){
        var service=new DocumentIntelligenceService(coordinatorDb(),tx,docs,mock(com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore.class),mock(IsolatedDocumentParser.class),models,providers,search,settings,jobs,coordinator);
        var index=service.ensureIndex();assertEquals("ACTIVE",index.get("state"));assertNotNull(index.get("source_watermark"));assertEquals("COMPLETED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));
        var body=org.mockito.ArgumentCaptor.forClass(Object.class);verify(search).esRequest(eq("PUT"),matches("/paismart-knowledge-[a-z0-9-]+"),body.capture());Map<?,?> mappings=(Map<?,?>)((Map<?,?>)body.getValue()).get("mappings");assertEquals(index.get("id"),((Map<?,?>)mappings.get("_meta")).get("indexRevision"));
    }
    JdbcTemplate coordinatorDb(){return db;}
    static final String NEW="订单服务的 HTTP 端口配置为 8080，连接超时时间为 30 秒。",OLD="订单服务的 HTTP 端口配置为 9090，连接超时时间为 30 秒。";
    void conflictCandidates(){document("new",NEW);document("old",OLD);chunk("new1","new",1,NEW);chunk("new2","new",2,NEW);var old=db.queryForMap("SELECT * FROM ps_document_chunk WHERE id='old0'");old.put("cosineSimilarity",.97);when(search.conflictCandidates(any(),eq("new"),anyList())).thenReturn(List.of(old,old));when(settings.number("conflict.max-conflicts-per-file",50)).thenReturn(2);}
    @Test void conflictCapsDeduplicatesAndBatchesLlmWithMissingVerdictRetained(){
        conflictCandidates();when(settings.enabled("conflict-llm-confirmation-enabled")).thenReturn(true);when(models.text(anyLong(),startsWith("conflict:"),anyString(),anyString(),anyInt(),any())).thenReturn("前缀 [{\"id\":1,\"conflict\":false}] 后缀");
        assertEquals(1,service.detectConflicts("new"));verify(search,times(2)).conflictCandidates(any(),eq("new"),anyList());verify(models,times(1)).text(eq(2L),startsWith("conflict:"),anyString(),anyString(),eq(800),any());assertEquals("new1",db.queryForObject("SELECT new_chunk_id FROM ps_document_conflict",String.class));
    }
    @Test void failedConflictConfirmationPreservesRulesAndReadOnlyActorCannotIgnore(){
        conflictCandidates();when(settings.enabled("conflict-llm-confirmation-enabled")).thenReturn(true);when(models.text(anyLong(),anyString(),anyString(),anyString(),anyInt(),any())).thenThrow(new IllegalStateException("unavailable"));assertEquals(2,service.detectConflicts("new"));
        String id=db.queryForList("SELECT id FROM ps_document_conflict ORDER BY new_chunk_id",String.class).get(0);var actor=new SpaceService.Scope(3,false);var body=Map.<String,Object>of("action","IGNORED","note","核对","expectedVersion",1);
        assertThrows(IllegalArgumentException.class,()->service.resolve(actor,id,body));assertEquals("PENDING",db.queryForObject("SELECT status FROM ps_document_conflict WHERE id=?",String.class,id));
        when(spaces.canManageDocument(eq(actor),eq(2L),isNull(),eq("PUBLIC"))).thenReturn(true);assertEquals("RESOLVED",service.resolve(actor,id,body).get("state"));verify(docs,never()).delete(any(),anyString(),anyLong());
    }
    JobService.Job conflictJob(){return new JobService.Job("conflictJob",2,"new","CONFLICT_DETECT","RUNNING",Map.of(),Map.of(),"lease",null,0,null,1);}
    @Test void actualConflictJobAcquiresTheSharedFenceScansAndReleasesIt(){
        conflictCandidates();var result=service.processJob(conflictJob());assertEquals(2,result.get("detected"));assertEquals(2L,db.queryForObject("SELECT COUNT(*) FROM ps_document_conflict",Long.class));assertNull(db.queryForObject("SELECT owner FROM ps_intelligence_lock",String.class));assertEquals(1L,db.queryForObject("SELECT fence_revision FROM ps_intelligence_lock",Long.class));verify(jobs,atLeastOnce()).requireLease("conflictJob","lease");verify(search,times(2)).conflictCandidates(any(),eq("new"),anyList());
    }
    @Test void conflictJobCannotScanDuringAnExistingIndexWriterFence(){
        conflictCandidates();var existing=coordinator.acquire("indexWriter");assertThrows(IllegalStateException.class,()->service.processJob(conflictJob()));assertEquals("indexWriter",db.queryForObject("SELECT owner FROM ps_intelligence_lock",String.class));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_document_conflict",Long.class));verify(search,never()).conflictCandidates(any(),anyString(),anyList());coordinator.release(existing);
    }
    @Test void revokedConflictJobLeasePreventsCandidatesAndAlwaysReleasesItsFence(){
        conflictCandidates();AtomicInteger leases=new AtomicInteger();doAnswer(i->{if(leases.incrementAndGet()>=3)throw new IllegalStateException("lost lease");return null;}).when(jobs).requireLease("conflictJob","lease");assertThrows(IllegalStateException.class,()->service.processJob(conflictJob()));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_document_conflict",Long.class));assertNull(db.queryForObject("SELECT owner FROM ps_intelligence_lock",String.class));verify(search,never()).conflictCandidates(any(),anyString(),anyList());verifyNoInteractions(models);
    }

    @Test void preparedFirstIndexRejectsNetworkFailureWithoutRecreatingOrSwitching(){
        revision("PREPARED","[]");when(search.esRequest("GET","/paismart-knowledge-target",null)).thenThrow(new ControlledHttp.HttpStatusFailure(500));
        var first=new DocumentIntelligenceService(db,tx,docs,mock(com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore.class),mock(IsolatedDocumentParser.class),models,providers,search,settings,jobs,coordinator);
        assertThrows(ControlledHttp.HttpStatusFailure.class,first::ensureIndex);verify(search,never()).esRequest(eq("PUT"),anyString(),any());verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());assertEquals("PREPARED",db.queryForObject("SELECT state FROM ps_index_revision",String.class));
    }
    @Test void evaluationListUsesPersistedConfigAndJobAssociationWithinOwnerScope(){
        db.update("INSERT INTO ps_job(id,user_id,type,state,payload_json,result_json,request_key) VALUES('evaljob',2,'EVALUATION_RUN','RUNNING','{}','{\"checkpoint\":{\"stage\":\"CASE\"}}','key')");
        db.update("INSERT INTO ps_evaluation(id,user_id,label,state,job_id,config_json,dataset_json,created_at,updated_at) VALUES('eval',2,'样例','RUNNING','evaljob','{\"topK\":7}','[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        db.update("INSERT INTO ps_evaluation(id,user_id,label,state,config_json,dataset_json,created_at,updated_at) VALUES('other',3,'他人','RUNNING','{\"topK\":5}','[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var page=service.evaluationPage(new SpaceService.Scope(2,false),null,"RUNNING",1,20);assertEquals(1L,page.get("total"));var row=(Map<?,?>)((List<?>)page.get("rows")).get(0);assertEquals(7,row.get("topK"));assertEquals("evaljob",row.get("jobId"));assertEquals("RUNNING",row.get("jobState"));assertEquals("CASE",((Map<?,?>)((Map<?,?>)row.get("jobResult")).get("checkpoint")).get("stage"));assertFalse(row.containsKey("configJson"));assertEquals(2L,service.evaluationPage(new SpaceService.Scope(1,true),null,"RUNNING",1,20).get("total"));
    }
    @Test void evaluationCreationPersistsOriginalSeparateTimeoutsAndItsRealJobAssociation(){
        when(models.active("LLM")).thenReturn(provider);when(jobs.enqueue(eq(2L),eq("EVALUATION_RUN"),anyMap(),eq("request"))).thenAnswer(i->new JobService.Job("createdJob",2,null,"EVALUATION_RUN","QUEUED",i.getArgument(2),Map.of(),null,null,0,null,1));
        var result=service.createEvaluation(2,Map.of("label","测试","inlineJsonl","{\"question\":\"问题\",\"referenceAnswer\":\"答案\"}"),"request");assertEquals("createdJob",result.id());var row=db.queryForMap("SELECT config_json,job_id FROM ps_evaluation");var snapshot=Jsons.map(row.get("config_json").toString());assertEquals(90,snapshot.get("generationTimeoutSeconds"));assertEquals(60,snapshot.get("judgeTimeoutSeconds"));assertEquals(1500,snapshot.get("generationMaxTokens"));assertEquals(300,snapshot.get("judgeMaxTokens"));assertEquals("createdJob",row.get("job_id"));
    }
    @Test void invalidEvaluationTimeoutOrTokenBudgetCannotCreateAnUnboundedQueuedRun(){
        when(models.active("LLM")).thenReturn(provider);var body=Map.<String,Object>of("label","测试","inlineJsonl","{\"question\":\"问题\",\"referenceAnswer\":\"答案\"}");when(settings.number("eval.judge-timeout-seconds",60)).thenReturn(91);assertThrows(IllegalArgumentException.class,()->service.createEvaluation(2,body,"request"));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation",Long.class));
        when(settings.number("eval.judge-timeout-seconds",60)).thenReturn(60);when(settings.number("eval.generation-max-tokens",1500)).thenReturn(-1);assertThrows(IllegalArgumentException.class,()->service.createEvaluation(2,body,"request"));verify(jobs,never()).enqueue(anyLong(),anyString(),anyMap(),anyString());
    }
    static final String JOIN_UUID="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
    void caseInsensitiveIdentifierColumns(){
        db.execute("ALTER TABLE ps_document ALTER COLUMN id VARCHAR_IGNORECASE(36)");
        db.execute("ALTER TABLE ps_document_chunk ALTER COLUMN document_id VARCHAR_IGNORECASE(36)");
    }
    DocumentIntelligenceService firstIndexService(){return new DocumentIntelligenceService(db,tx,docs,mock(com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore.class),mock(IsolatedDocumentParser.class),models,providers,search,settings,jobs,coordinator);}
    void identifierDocument(String id){
        db.update("INSERT INTO ps_document(id,user_id,visibility,file_name,sha256,object_key,byte_size,mime_type,status) VALUES(?,2,'PUBLIC','标识核对.txt',?,'key',1,'text/plain','READY')",id,"0".repeat(64));
        chunk(UUID.randomUUID().toString(),id,0,"精确原块");
    }
    @Test void firstIndexMustNotTreatForeignIdCaseOrDifferentUuidSuffixAsReadyChunks(){
        caseInsensitiveIdentifierColumns();identifierDocument(JOIN_UUID);db.update("DELETE FROM ps_document_chunk");
        chunk("suffix",JOIN_UUID.substring(0,35)+"2",0,"不同末位的孤立块");
        chunk("case",JOIN_UUID.toUpperCase(Locale.ROOT),1,"大小写不同的孤立块");
        assertEquals("ACTIVE",firstIndexService().ensureIndex().get("state"));
        assertEquals("COMPLETED",db.queryForObject("SELECT state FROM ps_index_switch",String.class));
        verifyNoInteractions(models);
    }
    @Test void firstIndexStillRejectsAnExactReadyChunkBeforeAliasOrPaidEmbedding(){
        caseInsensitiveIdentifierColumns();identifierDocument(JOIN_UUID);
        assertThrows(IllegalStateException.class,()->firstIndexService().ensureIndex());
        assertEquals("PREPARED",db.queryForObject("SELECT state FROM ps_index_revision",String.class));
        assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_index_switch",Long.class));
        verify(search,never()).esRequest(eq("POST"),eq("/_aliases"),any());verifyNoInteractions(models);
    }
    @Test void rebuildValidationCountsOnlyExactReadyCurrentUndeletedDocumentChunks(){
        caseInsensitiveIdentifierColumns();revision("BUILDING","[]");identifierDocument(JOIN_UUID);
        chunk("suffix",JOIN_UUID.substring(0,35)+"2",0,"不同末位");chunk("case",JOIN_UUID.toUpperCase(Locale.ROOT),1,"不同大小写");
        chunk("stale",JOIN_UUID,2,"旧版");db.update("UPDATE ps_document_chunk SET content_version=0 WHERE id='stale'");
        document("deleted","已删除");db.update("UPDATE ps_document SET deleted_at=CURRENT_TIMESTAMP WHERE id='deleted'");
        document("failed","失败");db.update("UPDATE ps_document SET status='FAILED' WHERE id='failed'");
        when(search.esRequest(eq("GET"),endsWith("/_count"),isNull())).thenReturn(Map.of("count",1));
        service.validateIndex(job("INDEX_REBUILD"),"target");
        var report=Jsons.map(db.queryForObject("SELECT validation_json FROM ps_index_revision WHERE id='target'",String.class));
        assertEquals(1L,((Number)report.get("expectedChunks")).longValue());assertEquals("PASSED",report.get("state"));verifyNoInteractions(models);
    }
    void actualRequestReceipts(){db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status CHAR(1),del_flag CHAR(1))");db.update("INSERT INTO sys_user VALUES(2,'0','0'),(3,'0','0')");jobs=new JobService(db,tx);service=new GovernanceService(db,tx,jobs,search,indexing,providers,docs,models,settings,coordinator);ReflectionTestUtils.setField(service,"spaces",spaces);ReflectionTestUtils.setField(service,"memberships",mock(com.ruoyi.paismart.application.MembershipService.class));when(providers.freezeActiveText()).thenReturn(provider);}
    Map<String,Object> evaluationRequest(){return Map.of("label","固定样例","inlineJsonl","{\"question\":\"问题\",\"referenceAnswer\":\"答案\"}","topK",5);}
    @Test void sameEvaluationRequestReplaysReceiptBeforeConfigurationIsRefrozen(){actualRequestReceipts();var body=evaluationRequest();var first=service.createEvaluation(2,body,"same");String frozen=db.queryForObject("SELECT config_json FROM ps_evaluation",String.class);when(search.activeIndex()).thenThrow(new IllegalStateException("配置已关闭"));when(providers.freezeActiveText()).thenThrow(new IllegalStateException("模型已停用"));when(settings.number("eval.judge-timeout-seconds",60)).thenReturn(20);var replay=service.createEvaluation(2,body,"same");assertEquals(first.id(),replay.id());assertEquals(first.payload(),replay.payload());assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation",Long.class));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_outbox",Long.class));assertEquals(frozen,db.queryForObject("SELECT config_json FROM ps_evaluation",String.class));verify(search,times(1)).activeIndex();verify(providers,times(1)).freezeActiveText();}
    @Test void changedEvaluationInputRejectsTheSameKeyAndDifferentOwnerHasOwnReceipt(){actualRequestReceipts();service.createEvaluation(2,evaluationRequest(),"same");var changed=new LinkedHashMap<>(evaluationRequest());changed.put("topK",6);assertThrows(IllegalStateException.class,()->service.createEvaluation(2,changed,"same"));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation",Long.class));service.createEvaluation(3,evaluationRequest(),"same");assertEquals(2L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation",Long.class));assertEquals(2L,db.queryForObject("SELECT COUNT(*) FROM ps_outbox",Long.class));}
    @Test void newIndexIdempotentRetryReturnsOriginalRevisionEvenAfterProviderChanges(){actualRequestReceipts();var body=Map.<String,Object>of("targetProvider","provider","fresh",false);var first=service.rebuild(2,body,"same");String frozen=db.queryForObject("SELECT provider_snapshot FROM ps_index_revision",String.class);when(providers.get("provider")).thenThrow(new IllegalStateException("已删除配置"));var replay=service.rebuild(2,body,"same");assertEquals(first.id(),replay.id());assertEquals(first.payload().get("indexId"),replay.payload().get("indexId"));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_index_revision",Long.class));assertEquals(frozen,db.queryForObject("SELECT provider_snapshot FROM ps_index_revision",String.class));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_outbox",Long.class));assertThrows(IllegalStateException.class,()->service.rebuild(2,Map.of("targetProvider","provider","fresh",true),"same"));}
    @Test void fractionalVersionCannotApproveAConflictAndFractionalTopKCannotQueueEvaluation(){conflictCandidates();service.detectConflicts("new");var id=db.queryForList("SELECT id FROM ps_document_conflict",String.class).get(0);assertThrows(IllegalArgumentException.class,()->service.resolve(new SpaceService.Scope(2,false),id,Map.of("action","IGNORED","note","核对","expectedVersion",1.5)));assertEquals("PENDING",db.queryForObject("SELECT status FROM ps_document_conflict WHERE id=?",String.class,id));var request=new LinkedHashMap<>(evaluationRequest());request.put("topK",5.2);assertThrows(IllegalArgumentException.class,()->service.createEvaluation(2,request,"key"));verify(jobs,never()).enqueue(anyLong(),anyString(),anyMap(),anyString());}
}
