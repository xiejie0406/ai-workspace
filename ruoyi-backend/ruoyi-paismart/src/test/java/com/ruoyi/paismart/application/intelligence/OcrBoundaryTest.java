package com.ruoyi.paismart.application.intelligence;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import java.sql.Connection;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OcrBoundaryTest {
    JdbcTemplate db;OcrService ocr;JobService jobs;JobService.Job job;String token;byte[] image;
    String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @BeforeEach void prepare()throws Exception{var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:ocr_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/V003__intelligence.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/V005__evaluation_datasets.sql"));}db=new JdbcTemplate(source);db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY)");db.update("INSERT INTO sys_user VALUES(1)");jobs=mock(JobService.class);job=new JobService.Job("job",1,"doc","DOCUMENT_PROCESS","RUNNING",Map.of(),Map.of(),"lease",java.time.Instant.now().plusSeconds(180),1,null,1);when(jobs.get("job")).thenReturn(job);var docs=mock(DocumentService.class);var doc=mock(DocumentService.Document.class);when(doc.contentVersion()).thenReturn(1);when(docs.getForProcessing("doc")).thenReturn(doc);var settings=mock(IntelligenceSettings.class);when(settings.enabled("ocr.aliyun-enabled")).thenReturn(true);when(settings.text("ocr.secret-ref","ai.paismart.aliyun.ocr")).thenReturn("ai.paismart.aliyun.ocr");when(settings.modelSecret("ai.paismart.aliyun.ocr","OCR","aliyun")).thenReturn("{\"accessKeyId\":\"test-only\",\"accessKeySecret\":\"test-only\"}");ocr=new OcrService(db,new TransactionTemplate(new DataSourceTransactionManager(source)),jobs,docs,settings);token=UUID.randomUUID().toString()+UUID.randomUUID();var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);image=output.toByteArray();db.update("INSERT INTO ps_ocr_ticket(token_hash,job_id,document_id,content_version,lease_token,max_uses,used_count,expires_at,created_at) VALUES(?,'job','doc',1,'lease',1,1,DATEADD('SECOND',180,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP)",hash(token.getBytes(StandardCharsets.UTF_8)));}
    @Test void completedPageReplayDoesNotCallCloudOrConsumeAnotherPage()throws Exception{String operation="page:job:1:"+hash(image);db.update("INSERT INTO ps_ocr_call(id,user_id,operation_id,job_id,document_id,content_version,image_hash,image_bytes,state,actual_pages,response_json,created_at,updated_at) VALUES('call',1,?,'job','doc',1,?,?,'COMPLETED',1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",operation,hash(image),image.length,Jsons.write(Map.of("results",List.of(Map.of("text","cached evidence")))));assertEquals("cached evidence",Values.map(Values.list(ocr.recognize(token,image).get("results")).get(0)).get("text"));assertEquals(1,db.queryForObject("SELECT used_count FROM ps_ocr_ticket",Integer.class));}
    @Test void uncertainPageNeverRepeatsCloudRequest()throws Exception{db.update("INSERT INTO ps_ocr_call(id,user_id,operation_id,image_hash,image_bytes,state,created_at,updated_at) VALUES('call',1,?,?,?,'UNCERTAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","page:job:1:"+hash(image),hash(image),image.length);assertThrows(IllegalStateException.class,()->ocr.recognize(token,image));assertEquals("UNCERTAIN",db.queryForObject("SELECT state FROM ps_ocr_call",String.class));ocr.reconcile("call",1,"根据供应商页账单核对");assertEquals(1,db.queryForObject("SELECT actual_pages FROM ps_ocr_call",Integer.class));}
    @Test void expiredJobRejectsBeforeAnyCallIsCreated(){doThrow(new IllegalStateException("任务租约失效")).when(jobs).requireLease("job","lease");assertThrows(IllegalStateException.class,()->ocr.recognize(token,image));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_ocr_call",Integer.class));}
    @Test void adapterPreservesNormalizedConfidenceAndReadingOrder(){var left=Map.of("BlockContent","left","BlockConfidence",0.9,"BlockPoints",List.of(Map.of("X",10,"Y",5),Map.of("X",20,"Y",10)));var right=Map.of("BlockContent","right","BlockConfidence",90,"BlockPoints",List.of(Map.of("X",50,"Y",5),Map.of("X",60,"Y",10)));var response=OcrService.adapt(Map.of("SubImages",List.of(Map.of("BlockInfo",Map.of("BlockDetails",List.of(right,left))))));var rows=Values.list(response.get("results"));assertEquals("left",Values.map(rows.get(0)).get("text"));assertEquals(0.9,((Number)Values.map(rows.get(0)).get("confidence")).doubleValue(),0.00001);assertEquals(0.9,((Number)Values.map(rows.get(1)).get("confidence")).doubleValue(),0.00001);}
}
