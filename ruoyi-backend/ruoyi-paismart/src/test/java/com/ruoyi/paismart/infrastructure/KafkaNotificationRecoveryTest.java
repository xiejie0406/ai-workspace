package com.ruoyi.paismart.infrastructure;
import com.ruoyi.paismart.application.jobs.JobService;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class KafkaNotificationRecoveryTest {
    @Test void malformedNotificationIsDurablyRedactedAndDoesNotBlockNextMessage()throws Exception{
        var db=database();JobService jobs=mock(JobService.class);var runtime=new PaismartKafkaRuntime(db,jobs,List.of(),mock(KafkaConnectionSettings.class),false,"");
        @SuppressWarnings("unchecked") Consumer<String,String> cursor=mock(Consumer.class);
        var poison=new ConsumerRecord<String,String>("paismart.jobs",0,7,"irrelevant","{\"secret\":\"DO_NOT_RETAIN_RAW\"");
        assertTrue(runtime.handleNotification(poison,cursor));assertTrue(runtime.handleNotification(poison,cursor));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_outbox WHERE topic='paismart.jobs.dlq'",Integer.class));
        String payload=db.queryForObject("SELECT payload_json FROM ps_outbox",String.class);assertFalse(payload.contains("DO_NOT_RETAIN_RAW"));assertTrue(payload.contains("payloadHash"));
        String valid=UUID.randomUUID().toString();when(jobs.claimById(valid,900)).thenReturn(Optional.empty());assertTrue(runtime.handleNotification(new ConsumerRecord<>("paismart.jobs",0,8,valid,Jsons.write(Map.of("jobId",valid))),cursor));
        verify(cursor,times(2)).commitSync(Map.of(new TopicPartition("paismart.jobs",0),new OffsetAndMetadata(8)));verify(cursor).commitSync(Map.of(new TopicPartition("paismart.jobs",0),new OffsetAndMetadata(9)));verify(cursor,never()).seek(any(),anyLong());
    }
    @Test void missingDeadLetterDatabaseDoesNotAcknowledgePoison(){var db=mock(JdbcTemplate.class);when(db.update(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("outage"));var runtime=new PaismartKafkaRuntime(db,mock(JobService.class),List.of(),mock(KafkaConnectionSettings.class),false,"");@SuppressWarnings("unchecked") Consumer<String,String> cursor=mock(Consumer.class);assertFalse(runtime.handleNotification(new ConsumerRecord<>("paismart.jobs",0,7,"x","broken"),cursor));verify(cursor).seek(new TopicPartition("paismart.jobs",0),7);verify(cursor,never()).commitSync(anyMap());}
    @Test void manySuccessfulNotificationReplaysDoNotFailBackloggedJob()throws Exception{
        var db=database();String id=UUID.randomUUID().toString(),outbox=UUID.randomUUID().toString();db.update("INSERT INTO ps_job(id,user_id,type,state,payload_json,request_key) VALUES(?,2,'DOCUMENT_PROCESS','QUEUED','{}','fixture')",id);db.update("INSERT INTO ps_outbox(id,job_id,topic,payload_json,state) VALUES(?,?,'paismart.jobs','{}','PENDING')",outbox,id);var runtime=new PaismartKafkaRuntime(db,mock(JobService.class),List.of(),mock(KafkaConnectionSettings.class),false,"");
        for(int i=0;i<20;i++){runtime.markPublished(outbox);db.update("UPDATE ps_outbox SET next_attempt_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),outbox);runtime.recoverNotifications();assertEquals("QUEUED",db.queryForObject("SELECT state FROM ps_job WHERE id=?",String.class,id));assertEquals(0,db.queryForObject("SELECT attempts FROM ps_outbox WHERE id=?",Integer.class,outbox));assertEquals("PENDING",db.queryForObject("SELECT state FROM ps_outbox WHERE id=?",String.class,outbox));}
        db.update("UPDATE ps_outbox SET attempts=12 WHERE id=?",outbox);runtime.recoverNotifications();assertEquals("FAILED",db.queryForObject("SELECT state FROM ps_job WHERE id=?",String.class,id));
    }
    private static JdbcTemplate database()throws Exception{var source=new org.h2.jdbcx.JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");try(var c=source.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("db/paismart/V001__control_billing_jobs.sql"));org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("db/paismart/V005__evaluation_datasets.sql"));}return new JdbcTemplate(source);}
    @Test void claimFailureRewindsAndOnlyItsSuccessfulReplayCommits(){
        JobService jobs=mock(JobService.class);var connection=mock(KafkaConnectionSettings.class);
        var runtime=new PaismartKafkaRuntime(mock(JdbcTemplate.class),jobs,List.of(),connection,false,"");
        @SuppressWarnings("unchecked") Consumer<String,String> cursor=mock(Consumer.class);
        String id=UUID.randomUUID().toString();var message=new ConsumerRecord<String,String>("paismart.jobs",0,7,id,Jsons.write(Map.of("jobId",id)));
        when(jobs.claimById(id,900)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture database outage")).thenReturn(Optional.empty());
        assertFalse(runtime.handleNotification(message,cursor));verify(cursor).seek(new TopicPartition("paismart.jobs",0),7);verify(cursor,never()).commitSync(anyMap());
        assertTrue(runtime.handleNotification(message,cursor));verify(cursor).commitSync(Map.of(new TopicPartition("paismart.jobs",0),new OffsetAndMetadata(8)));
    }
    @Test void successfulOtherPartitionDoesNotCommitFailedPartition(){
        JobService jobs=mock(JobService.class);var runtime=new PaismartKafkaRuntime(mock(JdbcTemplate.class),jobs,List.of(),mock(KafkaConnectionSettings.class),false,"");
        @SuppressWarnings("unchecked") Consumer<String,String> cursor=mock(Consumer.class);String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        when(jobs.claimById(a,900)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("outage"));when(jobs.claimById(b,900)).thenReturn(Optional.empty());
        assertFalse(runtime.handleNotification(new ConsumerRecord<>("paismart.jobs",0,7,a,Jsons.write(Map.of("jobId",a))),cursor));
        assertTrue(runtime.handleNotification(new ConsumerRecord<>("paismart.jobs",1,20,b,Jsons.write(Map.of("jobId",b))),cursor));
        verify(cursor).commitSync(Map.of(new TopicPartition("paismart.jobs",1),new OffsetAndMetadata(21)));verify(cursor,never()).commitSync(Map.of(new TopicPartition("paismart.jobs",0),new OffsetAndMetadata(8)));
    }
}
