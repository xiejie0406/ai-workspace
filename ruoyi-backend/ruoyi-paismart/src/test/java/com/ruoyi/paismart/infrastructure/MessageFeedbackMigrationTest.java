package com.ruoyi.paismart.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 原版本脚本到V006的真实H2迁移；不连接原库、不推断历史关联。 */
class MessageFeedbackMigrationTest {
    private JdbcDataSource source;private JdbcTemplate db;private SchemaReadiness readiness;
    @BeforeEach void prepare(){
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:feedback_migration_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(source);readiness=new SchemaReadiness(db);
        for(String script:List.of("V001__control_billing_jobs.sql","V002__documents_spaces.sql","V003__intelligence.sql","V004__operations_migration_receipts.sql","V005__evaluation_datasets.sql"))new ResourceDatabasePopulator(new ClassPathResource("db/paismart/"+script)).execute(source);
    }
    void migrate(){new ResourceDatabasePopulator(new ClassPathResource("db/paismart/V006__message_feedback.sql")).execute(source);}
    void oldFeedback(String id){db.update("INSERT INTO ps_tool_feedback(id,user_id,conversation_id,rating,note,created_at) VALUES(?,2,'old-conversation','HELPFUL','旧反馈原文',TIMESTAMP '2020-01-02 03:04:05')",id);}
    void feedback(String id,long user,String message){db.update("INSERT INTO ps_tool_feedback(id,user_id,conversation_id,rating,note,created_at,message_id,generation_id) VALUES(?,?,'conversation','HELPFUL','反馈',CURRENT_TIMESTAMP,?,'generation')",id,user,message);}
    @Test void upgradePreservesOldFieldsAndDoesNotGuessMessageOrGenerationAssociations(){
        db.update("INSERT INTO ps_chat_message(id,conversation_id,role,content,references_json,state,created_at) VALUES('old-answer','old-conversation','assistant','旧答案','{\"original\":true}','COMPLETED',TIMESTAMP '2020-01-02 03:04:05')");
        db.update("INSERT INTO ps_chat_generation(id,conversation_id,user_id,assistant_message_id,client_request_id,state,request_json,created_at,updated_at) VALUES('old-generation','old-conversation',2,'old-answer','old-request','COMPLETED','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");oldFeedback("old-one");oldFeedback("old-two");
        var message=db.queryForMap("SELECT * FROM ps_chat_message WHERE id='old-answer'");var old=db.queryForMap("SELECT * FROM ps_tool_feedback WHERE id='old-one'");migrate();
        var upgradedMessage=db.queryForMap("SELECT * FROM ps_chat_message WHERE id='old-answer'");message.forEach((key,value)->assertEquals(value,upgradedMessage.get(key)));assertNull(upgradedMessage.get("GENERATION_ID"));
        var upgraded=db.queryForMap("SELECT * FROM ps_tool_feedback WHERE id='old-one'");old.forEach((key,value)->assertEquals(value,upgraded.get(key)));assertNull(upgraded.get("MESSAGE_ID"));assertNull(upgraded.get("GENERATION_ID"));assertNull(upgraded.get("UPDATED_AT"));assertEquals(1L,((Number)upgraded.get("ROW_VERSION")).longValue());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ps_tool_feedback WHERE message_id IS NULL AND generation_id IS NULL AND updated_at IS NULL",Integer.class));assertEquals(List.of(),readiness.missing());assertEquals(List.of(1,2,3,4,5,6),db.queryForList("SELECT version_no FROM paismart_schema_history ORDER BY version_no",Integer.class));
    }
    @Test void uniqueUserMessageAllowsUnknownHistoryAndDifferentUsersOrMessages(){
        oldFeedback("before-one");oldFeedback("before-two");migrate();feedback("current",2,"message-one");assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->feedback("duplicate",2,"message-one"));feedback("other-user",3,"message-one");feedback("other-message",2,"message-two");feedback("unknown-one",2,null);feedback("unknown-two",2,null);assertEquals(7,db.queryForObject("SELECT COUNT(*) FROM ps_tool_feedback",Integer.class));assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM ps_tool_feedback WHERE message_id IS NULL",Integer.class));
    }
    @Test void oldCodeExplicitColumnInsertsRemainCompatibleAfterAdditiveUpgrade(){
        migrate();oldFeedback("old-code-feedback");db.update("INSERT INTO ps_chat_message(id,conversation_id,role,content,state,created_at) VALUES('old-code-message','conversation','assistant','答案','COMPLETED',CURRENT_TIMESTAMP)");assertEquals(1L,db.queryForObject("SELECT row_version FROM ps_tool_feedback",Long.class));assertNull(db.queryForObject("SELECT generation_id FROM ps_chat_message",String.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_tool_feedback WHERE message_id IS NULL AND generation_id IS NULL AND updated_at IS NULL",Integer.class));
    }
    @Test void replayStopsAtDuplicateDdlWithoutOverwritingHistoryOrBusinessRows(){
        oldFeedback("preserved");migrate();feedback("current",2,"message");var before=db.queryForList("SELECT * FROM ps_tool_feedback ORDER BY id");assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,this::migrate);assertEquals(before,db.queryForList("SELECT * FROM ps_tool_feedback ORDER BY id"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM paismart_schema_history WHERE version_no=6",Integer.class));assertEquals(List.of(),readiness.missing());
    }
    List<String> statements()throws Exception{
        try(var input=new ClassPathResource("db/paismart/V006__message_feedback.sql").getInputStream()){String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);String executable=sql.lines().filter(line->!line.stripLeading().startsWith("--")).reduce("",(first,next)->first+next+"\n");return Arrays.stream(executable.split(";")).map(String::trim).filter(part->!part.isEmpty()).toList();}
    }
    @Test void partialDdlIsNotReadyAndNeedsExplicitVerifiedRemainingStatements()throws Exception{
        oldFeedback("preserved");var statements=statements();for(int i=0;i<2;i++)db.execute(statements.get(i));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM paismart_schema_history WHERE version_no=6",Integer.class));assertTrue(readiness.missing().containsAll(List.of("ps_tool_feedback.generation_id","ps_tool_feedback.row_version","ps_tool_feedback.updated_at","paismart_schema_history.version:6","ps_tool_feedback.unique:user_id,message_id")));
        assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,this::migrate);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_tool_feedback",Integer.class));for(int i=2;i<statements.size();i++)db.execute(statements.get(i));assertEquals(List.of(),readiness.missing());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_tool_feedback WHERE message_id IS NULL AND generation_id IS NULL AND updated_at IS NULL",Integer.class));
    }
    @Test void laterHistoryMarkerCannotSubstituteForMissingVersionSix(){db.update("INSERT INTO paismart_schema_history(version_no,description) VALUES(7,'later')");assertTrue(readiness.missing().contains("paismart_schema_history.version:6"));assertTrue(readiness.missing().contains("ps_chat_message.generation_id"));}
}
