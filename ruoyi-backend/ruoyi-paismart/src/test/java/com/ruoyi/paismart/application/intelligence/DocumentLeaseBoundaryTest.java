package com.ruoyi.paismart.application.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** H2和Mock只验证取消/租约边界，不进行模型或ES调用。 */
class DocumentLeaseBoundaryTest {
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private DocumentService docs;
    private DocumentObjectStore objects;
    private IsolatedDocumentParser parser;
    private ModelGateway models;
    private ProviderService providers;
    private SearchService search;
    private IntelligenceSettings settings;
    private JobService jobs;
    private IndexCoordinator coordinator;
    private DocumentIntelligenceService service;
    private final AtomicBoolean canceled=new AtomicBoolean();
    private final JobService.Job job=new JobService.Job("job",2,"document","DOCUMENT_PROCESS","RUNNING",Map.of(),Map.of(),"lease",Instant.now().plusSeconds(600),1,null,1);
    private final DocumentService.Document document=new DocumentService.Document("document",null,"notes.pdf","notes.pdf","QUEUED",1,1,"PRIVATE",4,null,10,0,2,"paismart/documents/document/source.pdf","application/pdf",null,Instant.now(),null,"job");
    private final ProviderService.Provider provider=new ProviderService.Provider("provider","EMBEDDING","sample","https://model.example.invalid","sample-model",2,"ai.paismart.sample.key",true,true,1,Map.of());
    @BeforeEach void setup() throws IOException {
        canceled.set(false);JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        new ResourceDatabasePopulator(new ClassPathResource("db/paismart/V002__documents_spaces.sql"),new ClassPathResource("db/paismart/V003__intelligence.sql"),new ClassPathResource("db/paismart/V005__evaluation_datasets.sql")).execute(source);
        db.update("INSERT INTO ps_document(id,user_id,file_name,sha256,object_key,byte_size,visibility,mime_type) VALUES('document',2,'notes.pdf',?,?,4,'PRIVATE','application/pdf')","0".repeat(64),document.objectKey());
        db.update("INSERT INTO ps_index_revision(id,index_name,provider_snapshot,state,created_at,updated_at) VALUES('revision','paismart-knowledge-test','{}','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        docs=mock(DocumentService.class);objects=mock(DocumentObjectStore.class);parser=mock(IsolatedDocumentParser.class);models=mock(ModelGateway.class);providers=mock(ProviderService.class);search=mock(SearchService.class);settings=mock(IntelligenceSettings.class);jobs=mock(JobService.class);coordinator=mock(IndexCoordinator.class);
        when(docs.getForProcessing("document")).thenReturn(document);when(objects.open(document.objectKey())).thenReturn(new ByteArrayInputStream("body".getBytes()));
        when(docs.isCurrentProcessingJob(job)).thenReturn(true);
        when(coordinator.acquire(anyString())).thenReturn(new IndexCoordinator.Fence("job:lease",1));
        doAnswer(inv->{if(canceled.get())throw new IllegalStateException("任务已取消");return null;}).when(jobs).requireLease(job.id(),job.leaseToken());
        when(settings.number(anyString(),anyInt())).thenAnswer(inv->inv.getArgument(1));
        when(providers.fromSnapshot(anyString())).thenReturn(provider);
        when(providers.snapshot(provider)).thenReturn(Map.of("model","sample-model","dimension",2));
        when(parser.parse(any(),anyString(),eq(job))).thenReturn(List.of(new IsolatedDocumentParser.Page(1,"第一页正文。".repeat(100)),new IsolatedDocumentParser.Page(2,"第二页正文。".repeat(100))));
        when(models.embed(eq(2L),anyString(),anyList(),eq(provider))).thenAnswer(inv->{List<String> inputs=inv.getArgument(2);return new ModelGateway.Embedding(inputs.stream().map(s->List.of(0.1,0.2)).toList(),17);});
        service=new DocumentIntelligenceService(db,tx,docs,objects,parser,models,providers,search,settings,jobs,coordinator);
    }
    @Test void canceledBeforeClaimNeverAcquiresIndexOrReadsDocument() {
        canceled.set(true);assertThrows(IllegalStateException.class,()->service.processJob(job));
        verifyNoInteractions(coordinator,docs,objects,parser,models,search);
    }
    @Test void canceledAfterParserCannotWritePagesChunksOrFailureState() throws IOException {
        when(parser.parse(any(),anyString(),eq(job))).thenAnswer(inv->{canceled.set(true);return List.of(new IsolatedDocumentParser.Page(1,"正文"));});
        assertThrows(IllegalStateException.class,()->service.processJob(job));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_document_chunk",Integer.class));
        verify(docs,never()).markPageCount(anyString(),anyInt(),anyInt());
        verify(docs,never()).markProcessingResult(anyString(),anyInt(),eq("FAILED"),any(),any(),anyLong());
        verifyNoInteractions(models,search);
    }
    @Test void canceledAfterEmbeddingCannotPersistCacheOrPublishReady() {
        when(models.embed(eq(2L),anyString(),anyList(),eq(provider))).thenAnswer(inv->{canceled.set(true);List<String> input=inv.getArgument(2);return new ModelGateway.Embedding(input.stream().map(s->List.of(0.1,0.2)).toList(),17);});
        assertThrows(IllegalStateException.class,()->service.processJob(job));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_document_chunk WHERE vector_json IS NOT NULL",Integer.class));
        verify(search,never()).esRequest(eq("PUT"),anyString(),any());
        verify(docs,never()).markProcessingResult(anyString(),anyInt(),eq("READY"),any(),any(),anyLong());
        verify(docs,never()).markProcessingResult(anyString(),anyInt(),eq("FAILED"),any(),any(),anyLong());
    }
    @Test void pageCountUsesActualPagesAndFullContentSurvivesChunkCheckpoint() throws IOException {
        service.processJob(job);
        verify(docs).markPageCount("document",1,2);
        verify(docs).markProcessingResult(eq("document"),eq(1),eq("PARSING"),contains("第一页正文。"),isNull(),eq(0L));
        verify(docs).markProcessingResult(eq("document"),eq(1),eq("READY"),contains("第二页正文。"),isNull(),eq(17L));
    }
    @Test void cachedEmbeddingUsesCompletedLedgerTokensWithoutCallingModelAgain() {
        String hash=DocumentIntelligenceService.embeddingInputHash("正文",providers.snapshot(provider));
        String operation=DocumentIntelligenceService.embeddingOperation("document",1,"revision",0,List.of(hash));
        db.update("INSERT INTO ps_model_call(id,user_id,operation_id,scope,request_hash,status,reservation_id,actual_tokens,created_at,updated_at) VALUES('call',2,?,'EMBEDDING',?,'COMPLETED','reserve',37,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",operation,"0".repeat(64));
        var chunk=Map.<String,Object>of("id","chunk","text_content","正文","embedding_revision","revision","embedding_input_hash",hash,"vector_json","[0.1,0.2]");
        long tokens=service.indexDocument(document,List.of(chunk),Map.of("id","revision","index_name","paismart-knowledge-test","provider_snapshot","{}"),job);
        assertEquals(37,tokens);verifyNoInteractions(models);
        verify(search).esRequest(eq("PUT"),eq("/paismart-knowledge-test/_doc/chunk"),any());
    }
    private void importedChunk() {
        db.update("INSERT INTO ps_document_chunk(id,document_id,content_version,chunk_no,page_no,text_content,anchor_text,token_count,created_at) VALUES('legacy-chunk','document',1,0,1,'旧引用证据','旧锚点',1,CURRENT_TIMESTAMP)");
    }
    private void persistMetadata() {
        doAnswer(inv->{db.update("UPDATE ps_document SET page_count=? WHERE id=?",new Object[]{inv.getArgument(2),inv.getArgument(0)});return null;}).when(docs).markPageCount(anyString(),anyInt(),anyInt());
        doAnswer(inv->{db.update("UPDATE ps_document SET content_text=COALESCE(?,content_text),status=?,actual_tokens=? WHERE id=?",new Object[]{inv.getArgument(3),inv.getArgument(2),inv.getArgument(5),inv.getArgument(0)});return null;}).when(docs).markProcessingResult(anyString(),anyInt(),anyString(),nullable(String.class),nullable(String.class),anyLong());
    }
    @Test void importedPdfRepairsMissingMetadataWithoutReplacingReferencedChunkIdentity() throws IOException {
        importedChunk();persistMetadata();
        db.update("INSERT INTO ps_reference(id,message_id,chunk_id,document_id,content_version,reference_no,page_no,anchor_text,evidence_text,created_at) VALUES('legacy-reference','message','legacy-chunk','document',1,1,1,'旧锚点','旧引用证据',CURRENT_TIMESTAMP)");
        service.processJob(job);
        assertEquals(2,db.queryForObject("SELECT page_count FROM ps_document WHERE id='document'",Integer.class));
        String content=db.queryForObject("SELECT content_text FROM ps_document WHERE id='document'",String.class);assertTrue(content.contains("第一页正文。"));assertTrue(content.contains("第二页正文。"));assertFalse(content.contains("旧引用证据"));
        assertEquals("legacy-chunk",db.queryForObject("SELECT chunk_id FROM ps_reference WHERE id='legacy-reference'",String.class));
        assertEquals("旧引用证据",db.queryForObject("SELECT text_content FROM ps_document_chunk WHERE id='legacy-chunk'",String.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_document_chunk",Integer.class));verify(parser).parse(any(),eq("application/pdf"),eq(job));
    }
    @Test void completeImportedMetadataNeverParsesOrReadsObjectAgain() throws IOException {
        importedChunk();db.update("UPDATE ps_document SET content_text='已完整迁入正文',page_count=2 WHERE id='document'");
        service.processJob(job);verifyNoInteractions(parser,objects);verify(docs,never()).markPageCount(anyString(),anyInt(),anyInt());
        verify(docs).markProcessingResult(eq("document"),eq(1),eq("READY"),eq("已完整迁入正文"),isNull(),eq(17L));
    }
}
