package com.ruoyi.paismart.application.intelligence;

import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际MySQL形状的H2事务与本地对象替身；不启动存储、模型、Kafka或Spring服务。 */
class EvaluationDatasetBoundaryTest {
    static final SpaceService.Scope OWNER=new SpaceService.Scope(2,false),OTHER=new SpaceService.Scope(3,false),ROOT=new SpaceService.Scope(1,true);
    static final String JSONL="{\"id\":\"one\",\"question\":\"问题\",\"referenceAnswer\":\"答案\",\"expectedFileNames\":[\"one.pdf\"]}";
    static final String SAMPLE_HASH="42ce93fd1e8539742cbe53e94ac5478647194aacef223b3ed92066d33c3803fc";
    JdbcTemplate db;GovernanceService governance;DocumentObjectStore objects;IntelligenceSettings settings;ModelGateway models;SearchService search;JobService jobs;
    Map<String,byte[]> memory;
    @BeforeEach void prepare()throws Exception{
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:eval_dataset_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try(Connection c=source.getConnection()){for(String file:List.of("V001__control_billing_jobs.sql","V002__documents_spaces.sql","V003__intelligence.sql","V005__evaluation_datasets.sql"))ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/"+file));}
        db=new JdbcTemplate(source);db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status CHAR(1),del_flag CHAR(1))");db.update("INSERT INTO sys_user VALUES(1,'0','0'),(2,'0','0'),(3,'0','0')");db.update("INSERT INTO ps_member(user_id,status) VALUES(2,'ACTIVE'),(3,'ACTIVE')");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);jobs=new JobService(db,tx);
        search=mock(SearchService.class);models=mock(ModelGateway.class);var providers=mock(ProviderService.class);settings=mock(IntelligenceSettings.class);
        when(settings.text(anyString(),anyString())).thenAnswer(i->i.getArgument(1));when(settings.number(anyString(),anyInt())).thenAnswer(i->i.getArgument(1));when(search.activeIndex()).thenReturn(Map.of("id","frozen-index"));
        var provider=new ProviderService.Provider("provider","LLM","测试","https://provider.invalid/v1","model",0,"ai.paismart.test",true,true,1,Map.of());when(models.active("LLM")).thenReturn(provider);when(providers.snapshot(provider)).thenReturn(Map.of("id","provider","revision",1));
        governance=new GovernanceService(db,tx,jobs,search,mock(DocumentIntelligenceService.class),providers,mock(DocumentService.class),models,settings,mock(IndexCoordinator.class));
        ReflectionTestUtils.setField(governance,"memberships",new MembershipService(db));objects=mock(DocumentObjectStore.class);ReflectionTestUtils.setField(governance,"objects",objects);memory=new ConcurrentHashMap<>();
        doAnswer(i->{memory.put(i.getArgument(0),((InputStream)i.getArgument(1)).readAllBytes());return null;}).when(objects).put(anyString(),any(),anyLong(),anyString());
        when(objects.open(anyString())).thenAnswer(i->{byte[] bytes=memory.get(i.getArgument(0));if(bytes==null)throw new IllegalStateException("对象不存在");return new ByteArrayInputStream(bytes);});doAnswer(i->{memory.remove(i.getArgument(0));return null;}).when(objects).remove(anyString());
    }
    MockMultipartFile file(String content){return new MockMultipartFile("file","samples.jsonl","application/x-ndjson",content.getBytes(StandardCharsets.UTF_8));}
    Map<String,Object> uploaded(){return governance.uploadDataset(OWNER,file(JSONL),"私有样本","upload");}
    String objectKey(String id){return db.queryForObject("SELECT object_key FROM ps_evaluation_dataset WHERE id=?",String.class,id);}
    Map<String,Object> input(String selector){return Map.of("label","固定评测","datasetId",selector,"seed",17,"topK",7);}
    Map<String,Object> config(JobService.Job job){return Jsons.map(db.queryForObject("SELECT config_json FROM ps_evaluation WHERE id=?",String.class,job.payload().get("evaluationId")));}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(SpaceService.Scope actor,int page,int size){return (List<Map<String,Object>>)governance.datasetPage(actor,page,size).get("rows");}

    @Test void originalRegisteredSampleHasFiveRealCasesAndNeverImportsItsExpectedDocument(){
        var listed=rows(OWNER,1,20);assertEquals(1,listed.size());var sample=listed.get(0);assertEquals("builtin:paismart-sample",sample.get("id"));assertEquals(5,sample.get("caseCount"));assertEquals(SAMPLE_HASH,sample.get("sha256"));assertTrue(sample.get("label").toString().contains("对应版本 paismart.pdf"));assertNull(sample.get("ownerId"));assertFalse(sample.containsKey("resource"));assertFalse(sample.containsKey("objectKey"));
        var job=governance.createEvaluation(2,input(sample.get("id").toString()),"resource");var cases=Jsons.list(db.queryForObject("SELECT dataset_json FROM ps_evaluation",String.class));assertEquals(5,cases.size());assertEquals("stack-springboot",((Map<?,?>)cases.get(0)).get("id"));assertEquals(List.of("paismart.pdf"),((Map<?,?>)cases.get(0)).get("expectedFileNames"));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_document",Long.class));assertEquals("EVALUATION_RUN",job.type());verifyNoInteractions(objects);
    }
    @Test void inlineUploadAndRegisteredResourceFreezeDistinctSourcesAndTheSameSeedRules(){
        var inline=governance.createEvaluation(2,Map.of("label","内联","inlineJsonl",JSONL,"seed",-17),"inline");var upload=uploaded();var uploadedRun=governance.createEvaluation(2,input(upload.get("id").toString()),"uploaded");var resource=governance.createEvaluation(2,input("builtin:paismart-sample"),"registered");
        assertEquals("inline",config(inline).get("datasetSource"));assertEquals(-17,((Number)config(inline).get("seed")).intValue());assertFalse(config(inline).containsKey("datasetId"));
        var frozen=config(uploadedRun);assertEquals("upload",frozen.get("datasetSource"));assertEquals(upload.get("id"),frozen.get("datasetId"));assertEquals(upload.get("sha256"),frozen.get("datasetHash"));assertEquals(JSONL.getBytes(StandardCharsets.UTF_8).length,frozen.get("datasetByteSize"));assertEquals(1,frozen.get("datasetCaseCount"));assertEquals(17,((Number)frozen.get("seed")).intValue());assertEquals("resource",config(resource).get("datasetSource"));assertEquals(3L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));
    }
    @Test void datasetSelectorIsExclusiveAndDoesNotAcceptClientPathsHostsOrObjectKeys(){
        for(var body:List.of(Map.<String,Object>of("label","缺来源"),Map.<String,Object>of("label","双来源","inlineJsonl",JSONL,"datasetId","builtin:paismart-sample"),Map.<String,Object>of("label","路径","datasetId","../../secret.jsonl"),Map.<String,Object>of("label","主机","datasetId","https://approved.invalid/a.jsonl"),Map.<String,Object>of("label","对象","inlineJsonl",JSONL,"objectKey","paismart/other"),Map.<String,Object>of("label","服务器路径","inlineJsonl",JSONL,"datasetPath","classpath:eval/sample-eval.jsonl")))assertThrows(IllegalArgumentException.class,()->governance.createEvaluation(2,body,"invalid"));
        assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));verifyNoInteractions(objects,models,search);
    }
    @Test void foreignPrivateDatasetIsDeniedBeforeAnyObjectReadOrProviderSnapshot(){
        var id=uploaded().get("id").toString();clearInvocations(objects,models,search);assertThrows(AccessDeniedException.class,()->governance.createEvaluation(3,input(id),"foreign"));verifyNoInteractions(objects,models,search);
        assertEquals(1,rows(OTHER,1,20).size());assertEquals(2,rows(OWNER,1,20).size());assertEquals(2,rows(ROOT,1,20).size());
    }
    @Test void revokedMembershipCannotListUploadOrRestoreAnExistingEvaluationReceipt(){
        var original=governance.createEvaluation(2,Map.of("label","原请求","inlineJsonl",JSONL),"same");db.update("UPDATE ps_member SET status='DISABLED' WHERE user_id=2");clearInvocations(objects,models,search);
        assertThrows(AccessDeniedException.class,()->governance.datasetPage(OWNER,1,20));assertThrows(AccessDeniedException.class,()->uploaded());assertThrows(AccessDeniedException.class,()->governance.createEvaluation(2,Map.of("label","原请求","inlineJsonl",JSONL),"same"));assertEquals("QUEUED",jobs.get(original.id()).state());verifyNoInteractions(objects,models,search);
    }
    @Test void sameUploadKeyReturnsOneObjectAndChangedBytesOrLabelAreRejected(){
        var first=uploaded();var second=uploaded();assertEquals(first.get("id"),second.get("id"));assertEquals(first.get("sha256"),second.get("sha256"));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_dataset",Long.class));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_mutation_request",Long.class));verify(objects,times(1)).put(anyString(),any(),anyLong(),anyString());
        assertThrows(IllegalStateException.class,()->governance.uploadDataset(OWNER,file(JSONL.replace("答案","不同")),"私有样本","upload"));assertThrows(IllegalStateException.class,()->governance.uploadDataset(OWNER,file(JSONL),"换标签","upload"));assertEquals(1,memory.size());
    }
    @Test void concurrentSameUploadRequestLocksPlatformOwnerAndPublishesExactlyOneObject()throws Exception{
        var firstPut=new CountDownLatch(1);var release=new CountDownLatch(1);var putCount=new AtomicInteger();
        doAnswer(i->{if(putCount.incrementAndGet()==1){firstPut.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}memory.put(i.getArgument(0),((InputStream)i.getArgument(1)).readAllBytes());return null;}).when(objects).put(anyString(),any(),anyLong(),anyString());
        var pool=Executors.newFixedThreadPool(2);try{var first=pool.submit(this::uploaded);assertTrue(firstPut.await(5,TimeUnit.SECONDS));var second=pool.submit(this::uploaded);Thread.sleep(120);assertFalse(first.isDone());assertFalse(second.isDone());release.countDown();assertEquals(first.get(5,TimeUnit.SECONDS).get("id"),second.get(5,TimeUnit.SECONDS).get("id"));assertEquals(1,putCount.get());assertEquals(1,memory.size());assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_dataset",Long.class));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_mutation_request",Long.class));}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void databaseReceiptFailureRollsBackMetadataAndReclaimsOnlyItsNewSampleObject(){
        db.execute("ALTER TABLE ps_mutation_request ADD CONSTRAINT reject_dataset_receipt CHECK(kind<>'EVAL_DATASET_UPLOAD')");assertThrows(RuntimeException.class,this::uploaded);assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_dataset",Long.class));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_mutation_request",Long.class));assertTrue(memory.isEmpty());verify(objects).remove(matches("paismart/evaluation-datasets/2/[a-f0-9-]{36}\\.jsonl"));
    }
    @Test void ambiguousObjectWriteCannotProduceAnAvailableDatasetReceipt(){
        doAnswer(i->{memory.put(i.getArgument(0),JSONL.getBytes(StandardCharsets.UTF_8));throw new IllegalStateException("写入回执未知");}).when(objects).put(anyString(),any(),anyLong(),anyString());assertThrows(IllegalStateException.class,this::uploaded);assertTrue(memory.isEmpty());assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_dataset",Long.class));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_mutation_request",Long.class));
    }
    @Test void sampleObjectHashSizeUtf8AndByteBudgetAreVerifiedBeforeAnyModelOrJob(){
        String id=uploaded().get("id").toString(),key=objectKey(id);byte[] original=memory.get(key);clearInvocations(objects,models,search);
        for(byte[] changed:List.of(JSONL.replace("答案","伪造").getBytes(StandardCharsets.UTF_8),(JSONL+"\n").getBytes(StandardCharsets.UTF_8),new byte[]{(byte)0xc3,0x28},new byte[900001])){memory.put(key,changed);assertThrows(RuntimeException.class,()->governance.createEvaluation(2,input(id),"tampered"));}
        assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));verifyNoInteractions(models,search);memory.put(key,original);
    }
    @Test void invalidUtf8AndMoreThanFiveHundredCasesNeverWriteAObject(){
        var invalid=new MockMultipartFile("file","bad.jsonl","application/x-ndjson",new byte[]{(byte)0xc3,0x28});assertTrue(assertThrows(IllegalArgumentException.class,()->governance.uploadDataset(OWNER,invalid,"错误","utf8")).getMessage().contains("UTF-8"));
        StringBuilder many=new StringBuilder();for(int n=0;n<501;n++)many.append(Jsons.write(Map.of("id","case"+n,"question","q","referenceAnswer","a"))).append('\n');assertTrue(assertThrows(IllegalArgumentException.class,()->governance.uploadDataset(OWNER,file(many.toString()),"超样本","cases")).getMessage().contains("500"));verifyNoInteractions(objects);
    }
    @Test void multipartDeclaredSizeCannotHideItsLargerActualStream(){
        var dishonest=new MockMultipartFile("file","sample.jsonl","application/x-ndjson",JSONL.getBytes(StandardCharsets.UTF_8)){@Override public long getSize(){return 1;}};
        assertTrue(assertThrows(IllegalArgumentException.class,()->governance.uploadDataset(OWNER,dishonest,"字节声明","dishonest")).getMessage().contains("实际字节数"));verifyNoInteractions(objects);assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_dataset",Long.class));
    }
    @Test void byteLimitAppliesToMultibyteInlineAndUploadTextInsteadOfJavaCharacterCount(){
        StringBuilder many=new StringBuilder();for(int n=0;n<80;n++)many.append(Jsons.write(Map.of("id","case"+n,"question","q","referenceAnswer","中".repeat(4000)))).append('\n');String jsonl=many.toString();assertTrue(jsonl.length()<900000);assertTrue(jsonl.getBytes(StandardCharsets.UTF_8).length>900000);
        assertThrows(IllegalArgumentException.class,()->governance.createEvaluation(2,Map.of("label","多字节","inlineJsonl",jsonl),"bytes"));assertThrows(IllegalArgumentException.class,()->governance.uploadDataset(OWNER,file(jsonl),"多字节","bytes"));assertThrows(IllegalArgumentException.class,()->governance.createEvaluation(2,Map.of("label","无效unicode","inlineJsonl",JSONL+"\ud800"),"unicode"));verifyNoInteractions(objects,models,search);
    }
    @Test void filenamesAreMetadataAndCannotSmugglePathsOrOtherExtensionsIntoObjectStorage(){
        for(String name:List.of("../sample.jsonl","C:\\sample.jsonl","https://x/sample.jsonl","sample.json","sample\u0001.jsonl"))assertThrows(IllegalArgumentException.class,()->governance.uploadDataset(OWNER,new MockMultipartFile("file",name,"text/plain",JSONL.getBytes(StandardCharsets.UTF_8)),"样本","filename"));verifyNoInteractions(objects);
    }
    @Test void fixedSeedAcceptsBothSafeIntegerExtremesButRejectsFractionalAndOverflowValues(){
        for(long seed:List.of(-9007199254740991L,42L,9007199254740991L)){var job=governance.createEvaluation(2,Map.of("label","seed","inlineJsonl",JSONL,"seed",seed),"seed:"+seed);assertEquals(seed,((Number)config(job).get("seed")).longValue());}
        for(Object seed:List.of(1.5,9007199254740992L,-9007199254740992L,Double.NaN))assertThrows(IllegalArgumentException.class,()->governance.createEvaluation(2,Map.of("label","seed","inlineJsonl",JSONL,"seed",seed),"invalidseed"));assertEquals(3L,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation",Long.class));
    }
    @Test void replayKeepsFrozenCasesAfterObjectAndProviderConfigurationChange(){
        String id=uploaded().get("id").toString();var original=governance.createEvaluation(2,input(id),"same");String frozen=db.queryForObject("SELECT config_json FROM ps_evaluation",String.class),cases=db.queryForObject("SELECT dataset_json FROM ps_evaluation",String.class);memory.put(objectKey(id),"changed".getBytes(StandardCharsets.UTF_8));when(search.activeIndex()).thenThrow(new IllegalStateException("新配置不可用"));when(models.active("LLM")).thenThrow(new IllegalStateException("新模型关闭"));clearInvocations(objects,models,search);
        assertEquals(original.id(),governance.createEvaluation(2,input(id),"same").id());assertEquals(frozen,db.queryForObject("SELECT config_json FROM ps_evaluation",String.class));assertEquals(cases,db.queryForObject("SELECT dataset_json FROM ps_evaluation",String.class));verifyNoInteractions(objects,models,search);
        var changed=new LinkedHashMap<>(input(id));changed.put("seed",18);assertThrows(IllegalStateException.class,()->governance.createEvaluation(2,changed,"same"));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_outbox",Long.class));
    }
    String registration(String id,String resource,String hash,String visibility,Long owner){var entry=new LinkedHashMap<String,Object>();entry.put("id",id);entry.put("label","受控样本");entry.put("resource",resource);entry.put("sha256",hash);entry.put("visibility",visibility);if(owner!=null)entry.put("ownerId",owner);return Jsons.write(List.of(entry));}
    @Test void unregisteredResourcesAndUnsafeCatalogPathsAreRejectedWithoutExternalAccess(){
        assertThrows(IllegalArgumentException.class,()->governance.createEvaluation(2,input("builtin:unknown"),"unknown"));
        for(String resource:List.of("../sample-eval.jsonl","https://evil/a.jsonl","C:/a.jsonl","/eval/sample-eval.jsonl","nested/../../a.jsonl","sample-eval.jsonl%00")){when(settings.text(eq("eval.dataset-resources-json"),anyString())).thenReturn(registration("bad",resource,SAMPLE_HASH,"SHARED",null));assertThrows(IllegalArgumentException.class,()->governance.datasetPage(OWNER,1,20));}
        verifyNoInteractions(objects,models,search);
    }
    @Test void privateCatalogResourceRequiresOwnerAndDeclaredHashBeforeItCanBeFrozen(){
        when(settings.text(eq("eval.dataset-resources-json"),anyString())).thenReturn(registration("private","sample-eval.jsonl",SAMPLE_HASH,"PRIVATE",3L));assertTrue(rows(OWNER,1,20).isEmpty());assertEquals(1,rows(OTHER,1,20).size());assertThrows(AccessDeniedException.class,()->governance.createEvaluation(2,input("builtin:private"),"private"));
        when(settings.text(eq("eval.dataset-resources-json"),anyString())).thenReturn(registration("private","sample-eval.jsonl","0".repeat(64),"PRIVATE",3L));assertThrows(IllegalStateException.class,()->governance.createEvaluation(3,input("builtin:private"),"changedhash"));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));verifyNoInteractions(objects,models,search);
    }
    @Test void resourceReplayUsesItsOriginalRunEvenWhenTheNewRegistrationHashDoesNotMatch(){
        var first=governance.createEvaluation(2,input("builtin:paismart-sample"),"same");String frozen=db.queryForObject("SELECT dataset_json FROM ps_evaluation",String.class);
        when(settings.text(eq("eval.dataset-resources-json"),anyString())).thenReturn(registration("paismart-sample","sample-eval.jsonl","0".repeat(64),"SHARED",null));assertEquals(first.id(),governance.createEvaluation(2,input("builtin:paismart-sample"),"same").id());assertEquals(frozen,db.queryForObject("SELECT dataset_json FROM ps_evaluation",String.class));assertThrows(IllegalStateException.class,()->governance.createEvaluation(2,input("builtin:paismart-sample"),"new"));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));
    }
    @Test void realPaginationCountsOnlyTheAuthorizedSetAndCrossesResourceUploadBoundary(){
        for(int n=0;n<24;n++)governance.uploadDataset(OWNER,file(JSONL),"样本"+n,"upload"+n);governance.uploadDataset(OTHER,file(JSONL),"他人样本","other");
        assertEquals(25L,governance.datasetPage(OWNER,1,20).get("total"));assertEquals(20,rows(OWNER,1,20).size());assertEquals("RESOURCE",rows(OWNER,1,20).get(0).get("source"));assertEquals(5,rows(OWNER,2,20).size());assertEquals(2L,governance.datasetPage(OTHER,1,20).get("total"));assertEquals(26L,governance.datasetPage(ROOT,1,20).get("total"));
        var ids=new HashSet<Object>();for(int page=1;page<=5;page++)for(var row:rows(OWNER,page,5))assertTrue(ids.add(row.get("id")));assertEquals(25,ids.size());assertTrue(rows(OWNER,6,5).isEmpty());assertThrows(IllegalArgumentException.class,()->governance.datasetPage(OWNER,1,101));
    }
}
