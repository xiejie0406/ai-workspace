package com.ruoyi.paismart.application.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 原业务语义及收费缓存边界；仅H2/替身，不发送模型或ES请求。 */
class ContextChunkBoundaryTest {
    private JdbcTemplate db;
    private ModelGateway models;
    private ProviderService providers;
    private SearchService search;
    private IntelligenceSettings settings;
    private DocumentIntelligenceService service;
    private final AtomicBoolean canceled=new AtomicBoolean();
    private final Map<String,Object> snapshot=Map.of("id","provider","model","sample-model","dimension",2);
    private final ProviderService.Provider provider=new ProviderService.Provider("provider","EMBEDDING","sample","https://model.example.invalid","sample-model",2,"ai.paismart.sample.key",true,true,1,Map.of());
    private final JobService.Job job=new JobService.Job("job",2,"document","DOCUMENT_PROCESS","RUNNING",Map.of(),Map.of(),"lease",Instant.now().plusSeconds(600),1,null,1);
    private final DocumentService.Document document=new DocumentService.Document("document",null,"年度业务报告","notes.txt","INDEXING",1,1,"PRIVATE",4,null,10,0,2,"paismart/documents/document/source.txt","text/plain",null,Instant.now(),null,"job");
    private final Map<String,Object> index=Map.of("id","revision","index_name","paismart-knowledge-test","provider_snapshot","{}");
    @BeforeEach void setup() {
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:context_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/paismart/V002__documents_spaces.sql"),new ClassPathResource("db/paismart/V003__intelligence.sql"),new ClassPathResource("db/paismart/V005__evaluation_datasets.sql")).execute(source);
        db.update("INSERT INTO ps_document(id,user_id,file_name,sha256,object_key,byte_size,visibility,mime_type) VALUES('document',2,'notes.txt',?,'paismart/documents/document/source.txt',4,'PRIVATE','text/plain')","0".repeat(64));
        DocumentService docs=mock(DocumentService.class);when(docs.getForProcessing("document")).thenReturn(document);
        models=mock(ModelGateway.class);providers=mock(ProviderService.class);search=mock(SearchService.class);settings=mock(IntelligenceSettings.class);
        when(settings.number(anyString(),anyInt())).thenAnswer(inv->inv.getArgument(1));when(providers.fromSnapshot(anyString())).thenReturn(provider);when(providers.snapshot(provider)).thenReturn(snapshot);when(models.active("LLM")).thenReturn(provider);
        when(models.embed(eq(2L),anyString(),anyList(),eq(provider))).thenAnswer(inv->{List<String> input=inv.getArgument(2);return new ModelGateway.Embedding(input.stream().map(text->List.of(.1,.2)).toList(),17);});
        JobService jobs=mock(JobService.class);doAnswer(inv->{if(canceled.get())throw new IllegalStateException("已取消");return null;}).when(jobs).requireLease("job","lease");
        service=new DocumentIntelligenceService(db,new TransactionTemplate(new DataSourceTransactionManager(source)),docs,mock(DocumentObjectStore.class),mock(IsolatedDocumentParser.class),models,providers,search,settings,jobs,mock(IndexCoordinator.class));
    }
    private List<Map<String,Object>> chunks(int count) {
        for(int i=0;i<count;i++)db.update("INSERT INTO ps_document_chunk(id,document_id,content_version,chunk_no,page_no,text_content,anchor_text,token_count,created_at) VALUES(?,'document',1,?,1,?,'anchor',1,CURRENT_TIMESTAMP)","chunk"+i,i,"原文"+i+"。".repeat(100));
        return rows();
    }
    private List<Map<String,Object>> rows(){return db.queryForList("SELECT * FROM ps_document_chunk ORDER BY chunk_no");}
    @Test void semanticOverlapKeepsCompleteSentenceAndWholeLatinWord() {
        assertEquals("Last sentence.",TextChunker.semanticTail("First complete sentence. Last sentence.",18));
        assertEquals("",TextChunker.semanticTail("prefix internationalization",12));
        String text="alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau.";
        var split=TextChunker.split(text,32,0,0);
        assertEquals(text.replaceAll("\\s+",""),String.join("",split).replaceAll("\\s+",""));
        Set<String> words=new HashSet<>(List.of(text.replace(".","").split(" ")));
        for(String part:split)for(String word:part.replace(".","").split(" "))assertTrue(words.contains(word),word);
    }
    @Test void interiorSmallChunkMergesWithEitherNeighbourWithinSizePlusOverlap() {
        var result=TextChunker.mergeSmall(List.of("甲".repeat(33),"乙乙","丙".repeat(33)),32,8,10);
        assertEquals(List.of("甲".repeat(33)+"\n\n乙乙","丙".repeat(33)),result);
        assertEquals(2,TextChunker.mergeSmall(List.of("甲".repeat(39),"乙乙"),32,8,10).size());
        var beginning=TextChunker.mergeSmall(List.of("甲甲","乙".repeat(33)),32,8,10);assertEquals(1,beginning.size());
    }
    @Test void windowIncludesTitleTargetsAndOnlyBoundedImmediateNeighbours() {
        List<Map<String,Object>> chunks=new ArrayList<>();for(int i=0;i<20;i++)chunks.add(Map.of("chunk_no",i,"page_no",i+1,"text_content","唯一邻居"+i+"。".repeat(4000)));
        String window=DocumentIntelligenceService.contextWindow("年度报告".repeat(1000),chunks,5,13,2,6000);
        assertTrue(window.length()<=6000);assertTrue(window.contains("文档标题：年度报告"));
        for(int i=5;i<13;i++)assertTrue(window.contains("【目标 #"+i+"，"));
        assertFalse(window.contains("唯一邻居0"));assertFalse(window.contains("唯一邻居19"));
    }
    @Test void responseIgnoresUnknownIdsNormalizesAndCapsEachDescriptionWithoutBreakingUnicode() {
        String answer=Jsons.write(List.of(Map.of("id",0,"context","  背景\n说明 "+"😀".repeat(300)),Map.of("id",99,"context","越权编号"),Map.of("id",1,"context","   ")));
        var result=DocumentIntelligenceService.parseContexts(answer,Set.of(0L,1L),200);
        assertEquals(Set.of(0L),result.keySet());String context=result.get(0L);assertTrue(context.startsWith("背景 说明 "));assertTrue(context.length()<=200);assertFalse(Character.isHighSurrogate(context.charAt(context.length()-1)));
    }
    @Test void seventeenChunksUseThreeContextRequestsAndPreserveCachedDescriptions() {
        var rows=chunks(17);when(settings.enabled("contextual-enabled")).thenReturn(true);
        when(models.text(eq(2L),anyString(),anyString(),anyString(),eq(1200),eq(provider))).thenAnswer(inv->{String prompt=inv.getArgument(3);Matcher m=Pattern.compile("【目标 #(\\d+)，").matcher(prompt);List<Map<String,Object>> contexts=new ArrayList<>();while(m.find())contexts.add(Map.of("id",Long.parseLong(m.group(1)),"context","背景"));return Jsons.write(contexts);});
        service.indexDocument(document,rows,index,job);
        ArgumentCaptor<String> prompts=ArgumentCaptor.forClass(String.class);verify(models,times(3)).text(eq(2L),anyString(),anyString(),prompts.capture(),eq(1200),eq(provider));
        for(String prompt:prompts.getAllValues()){assertTrue(prompt.length()<=6000);assertTrue(prompt.contains("年度业务报告"));assertTrue(prompt.split("【目标 #",-1).length-1<=8);}
        assertEquals(17,db.queryForObject("SELECT COUNT(*) FROM ps_document_chunk WHERE context_text='背景'",Integer.class));
        ArgumentCaptor<Object> indexed=ArgumentCaptor.forClass(Object.class);verify(search,times(17)).esRequest(eq("PUT"),startsWith("/paismart-knowledge-test/_doc/"),indexed.capture());
        for(int i=0;i<17;i++){Map<?,?> payload=(Map<?,?>)indexed.getAllValues().get(i);String original="原文"+i+"。".repeat(100);assertEquals(original,payload.get("text_content"));assertEquals("背景\n"+original,payload.get("search_text"));}
        reset(models);when(models.embed(eq(2L),anyString(),anyList(),eq(provider))).thenAnswer(inv->{List<String> input=inv.getArgument(2);return new ModelGateway.Embedding(input.stream().map(text->List.of(.1,.2)).toList(),17);});
        // 只重复上下文入口：取消已有缓存的向量，隔离正式用量账本检查。
        db.update("UPDATE ps_document_chunk SET vector_json=NULL");service.indexDocument(document,rows(),index,job);
        verify(models,never()).text(anyLong(),anyString(),anyString(),anyString(),anyInt(),any());
    }
    @Test void disabledContextKeepsDatabaseDescriptionButRebuildsOldContextVectorFromOriginal() {
        chunks(1);db.update("UPDATE ps_document_chunk SET context_text='旧背景',vector_json='[0.8,0.9]',embedding_revision='revision',embedding_input_hash=?",DocumentIntelligenceService.embeddingInputHash("旧背景\n原文0"+"。".repeat(100),snapshot));
        service.indexDocument(document,rows(),index,job);
        ArgumentCaptor<List<String>> inputs=ArgumentCaptor.forClass(List.class);verify(models).embed(eq(2L),anyString(),inputs.capture(),eq(provider));assertEquals("原文0"+"。".repeat(100),inputs.getValue().get(0));
        assertEquals("旧背景",db.queryForObject("SELECT context_text FROM ps_document_chunk",String.class));
        ArgumentCaptor<Object> payload=ArgumentCaptor.forClass(Object.class);verify(search).esRequest(eq("PUT"),eq("/paismart-knowledge-test/_doc/chunk0"),payload.capture());assertEquals(inputs.getValue().get(0),((Map<?,?>)payload.getValue()).get("search_text"));assertEquals(inputs.getValue().get(0),((Map<?,?>)payload.getValue()).get("text_content"));
        verify(models,never()).text(anyLong(),anyString(),anyString(),anyString(),anyInt(),any());
    }
    @Test void malformedContextFallsBackToOriginalAndCanceledContextCannotWriteOrDeleteProjection() {
        var rows=chunks(1);when(settings.enabled("contextual-enabled")).thenReturn(true);when(models.text(eq(2L),anyString(),anyString(),anyString(),eq(1200),eq(provider))).thenReturn("不是JSON");
        service.indexDocument(document,rows,index,job);assertNull(db.queryForObject("SELECT context_text FROM ps_document_chunk",String.class));
        db.update("UPDATE ps_document_chunk SET vector_json=NULL");clearInvocations(search,models);
        when(models.text(eq(2L),anyString(),anyString(),anyString(),eq(1200),eq(provider))).thenAnswer(inv->{canceled.set(true);return "[{\"id\":0,\"context\":\"迟到说明\"}]";});
        assertThrows(IllegalStateException.class,()->service.indexDocument(document,rows(),index,job));assertNull(db.queryForObject("SELECT context_text FROM ps_document_chunk",String.class));verifyNoInteractions(search);
    }
    @Test void cacheWithoutMatchingFormalLedgerRefusesClaimedFreeCompletion() {
        chunks(1);String input="原文0"+"。".repeat(100);db.update("UPDATE ps_document_chunk SET vector_json='[0.1,0.2]',embedding_revision='revision',embedding_input_hash=?",DocumentIntelligenceService.embeddingInputHash(input,snapshot));
        assertThrows(IllegalStateException.class,()->service.indexDocument(document,rows(),index,job));verifyNoInteractions(models);verify(search,never()).esRequest(eq("PUT"),anyString(),any());
    }
}
