package com.ruoyi.paismart.application.intelligence;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.IntelligenceSettings;
import java.sql.Connection;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CitationBoundaryTest {
    @Test void sentenceBeforeOrAfterPeriodHasSameCitation(){var claims=CitationExtractor.extract("大小5MB[1]。磁盘合并。[2]",300);assertEquals(2,claims.size());assertEquals("大小5MB",claims.get(0).claim());assertEquals("磁盘合并。",claims.get(1).claim());assertEquals(List.of(2),claims.get(1).referenceNumbers());}
    @Test void groupedAdjacentAndLegacyFormatsShareClaim(){var claims=CitationExtractor.extract("断点续传[1,2][3]。恢复上传(来源#4: upload.md)(来源#5: other.md)。",300);assertEquals(List.of(1,2,3),claims.get(0).referenceNumbers());assertEquals(List.of(4,5),claims.get(1).referenceNumbers());}
    @Test void secondCitationOnlyAppliesToNewClaim(){var claims=CitationExtractor.extract("分片5MB[1]且落到磁盘[2]。",300);assertEquals("且落到磁盘",claims.get(1).claim());assertEquals(List.of(2),claims.get(1).referenceNumbers());}
    @Test void markdownNoiseAndLongClaimAreBounded(){var claims=CitationExtractor.extract("- **"+"前".repeat(30)+"末尾事实[1]\n2. 下一条[2]",4);assertEquals("末尾事实",claims.get(0).claim());assertEquals("下一条",claims.get(1).claim());}
    @Test void invalidHugeNumbersAndNoCitationDoNotInventClaims(){assertTrue(CitationExtractor.extract("未引用",300).isEmpty());assertEquals(List.of(1),CitationExtractor.extract("事实[99999999999999999999][1]",300).get(0).referenceNumbers());}
    @Test void worstVerdictSticksAndUnjudgedClaimsRemainUnverified(){
        var pairs=List.of(new CitationService.ClaimPair(1,"第一句",1,"证据"),new CitationService.ClaimPair(2,"第二句",1,"证据"),new CitationService.ClaimPair(3,"第三句",2,"证据"));
        var result=CitationService.aggregate(pairs,"前缀 [{\"id\":1,\"supported\":false,\"reason\":\"矛盾\"},{\"id\":2,\"supported\":\"true\"}] 后缀");
        assertEquals("SUSPICIOUS",result.get(1).get("status"));assertTrue(result.get(1).get("reason").toString().contains("第一句"));assertFalse(result.containsKey(2));
        assertTrue(CitationService.aggregate(pairs,"[{\"id\":1,\"supported\":true}]").isEmpty());assertTrue(CitationService.aggregate(pairs,"未给JSON").isEmpty());
    }
    JdbcTemplate db;CitationService service;DocumentService docs;ModelGateway models;IntelligenceSettings settings;
    @BeforeEach void setup() throws Exception{
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:citation_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/V003__intelligence.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/V005__evaluation_datasets.sql"));}
        db=new JdbcTemplate(source);docs=mock(DocumentService.class);models=mock(ModelGateway.class);settings=mock(IntelligenceSettings.class);
        when(settings.enabled("citation-verification-enabled")).thenReturn(true);when(settings.number(anyString(),anyInt())).thenAnswer(i->i.getArgument(1));
        service=new CitationService(db,docs);ReflectionTestUtils.setField(service,"models",models);ReflectionTestUtils.setField(service,"settings",settings);
        var document=mock(DocumentService.Document.class);when(document.id()).thenReturn("doc");when(document.title()).thenReturn("文件");when(document.contentVersion()).thenReturn(1);when(docs.getReadable(any(),eq("doc"))).thenReturn(document);
    }
    Map<String,Object> reference(int number,String id){return new LinkedHashMap<>(Map.of("id",id,"number",number,"documentId","doc","contentVersion",1,"text","小块原文"));}
    @Test void registerPersistsRealMetadataAndRawEvidence(){
        var e=new SearchService.Evidence("chunk","doc",1,2,3,"文件","原文证据","定位","背景说明",0.7,"RRF","相邻真实原文","parent");
        var ref=service.register("message",e,1,"查询");var preview=service.getReference(new SpaceService.Scope(2,false),ref.get("id").toString());
        assertEquals("原文证据",preview.get("text"));assertEquals("查询",preview.get("query"));assertEquals("RRF",preview.get("scoreType"));assertEquals("相邻真实原文",preview.get("parentContext"));assertEquals("背景说明",preview.get("context"));assertNull(preview.get("verified"));
    }
    @Test void batchesValidPairsUsesParentAndKeepsFailureUnverified(){
        var refs=new ArrayList<>(List.of(reference(1,"a"),reference(2,"b")));var provider=mock(ProviderService.Provider.class);
        when(models.text(eq(2L),eq("verify:op"),anyString(),anyString(),eq(800),eq(provider),eq(20))).thenReturn("前缀 [{\"id\":1,\"supported\":true},{\"id\":2,\"supported\":false,\"reason\":\"磁盘\"},{\"id\":3,\"supported\":true}] 后缀");
        var result=service.verifyAnswer(new SpaceService.Scope(2,false),"verify:op","大小5MB[1]。内存合并[2]。断点续传[1][9]。",refs,Map.of(1,"父原文：5MB和断点",2,"磁盘"),provider,()->{});
        assertEquals(2,result.size());assertEquals("SUSPICIOUS",result.get(2).get("status"));
        var payload=org.mockito.ArgumentCaptor.forClass(String.class);verify(models).text(eq(2L),eq("verify:op"),anyString(),payload.capture(),eq(800),eq(provider),eq(20));
        var items=Jsons.list(payload.getValue());assertEquals(3,items.size());assertEquals("父原文：5MB和断点",((Map<?,?>)items.get(0)).get("reference"));
        when(models.text(eq(2L),eq("verify:fail"),anyString(),anyString(),anyInt(),eq(provider),anyInt())).thenThrow(new IllegalStateException("provider unavailable"));
        var unverified=new ArrayList<>(List.of(reference(1,"c")));assertTrue(service.verifyAnswer(new SpaceService.Scope(2,false),"verify:fail","事实[1]",unverified,Map.of(),provider,()->{}).isEmpty());assertFalse(unverified.get(0).containsKey("verification"));
    }
    @Test void maxPairsAndUnknownNumbersAreBounded(){var pairs=service.pairs("甲[1][9]。乙[1][2]。",Map.of(1,"a".repeat(50),2,"b"),1,300,12);assertEquals(1,pairs.size());assertEquals(12,pairs.get(0).reference().length());assertEquals(1,pairs.get(0).referenceNumber());}
    @Test void modelCancellationPropagatesWithoutWritingVerification(){
        var provider=mock(ProviderService.Provider.class);var refs=new ArrayList<>(List.of(reference(1,"stopped")));
        when(models.text(eq(2L),eq("verify:stopped"),anyString(),anyString(),anyInt(),eq(provider),anyInt())).thenThrow(new java.util.concurrent.CancellationException("用户已停止"));
        assertThrows(java.util.concurrent.CancellationException.class,()->service.verifyAnswer(new SpaceService.Scope(2,false),"verify:stopped","已保存事实[1]",refs,Map.of(),provider,()->{}));
        assertFalse(refs.get(0).containsKey("verification"));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_reference WHERE verification_json IS NOT NULL",Long.class));
    }
    @Test void disabledVerificationAndLeaseFailureNeverCallModel(){
        when(settings.enabled("citation-verification-enabled")).thenReturn(false);assertTrue(service.verifyAnswer(new SpaceService.Scope(2,false),"op","事实[1]",List.of(reference(1,"a")),Map.of(),null,()->{}).isEmpty());verifyNoInteractions(models);
        when(settings.enabled("citation-verification-enabled")).thenReturn(true);assertThrows(IllegalStateException.class,()->service.verifyAnswer(new SpaceService.Scope(2,false),"op","事实[1]",List.of(reference(1,"a")),Map.of(),null,()->{throw new IllegalStateException("lost lease");}));verifyNoInteractions(models);
    }
}
