package com.ruoyi.paismart.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 仅在临时H2内执行版本脚本，用元数据核对就绪覆盖，不连接真实库。 */
class SchemaReadinessTest {
    private JdbcDataSource source;
    private JdbcTemplate db;
    private SchemaReadiness readiness;
    @BeforeEach void prepare() {
        source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:readiness_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(source);readiness=new SchemaReadiness(db);
        new ResourceDatabasePopulator(
            new ClassPathResource("db/paismart/V001__control_billing_jobs.sql"),
            new ClassPathResource("db/paismart/V002__documents_spaces.sql"),
            new ClassPathResource("db/paismart/V003__intelligence.sql"),
            new ClassPathResource("db/paismart/V004__operations_migration_receipts.sql"),
            new ClassPathResource("db/paismart/V005__evaluation_datasets.sql"),
            new ClassPathResource("db/paismart/V006__message_feedback.sql")
        ).execute(source);
    }
    @Test void completeVersionedSchemaNeedsNoBusinessDataOrWrites() {
        assertEquals(List.of(),readiness.missing());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_member",Integer.class));
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM paismart_schema_history",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_job",Integer.class));
    }
    @Test void essentialOcrModelProcessingAndMigrationColumnsAreRequired() {
        db.execute("ALTER TABLE ps_document DROP COLUMN process_job_id");
        db.execute("ALTER TABLE ps_document DROP COLUMN page_count");
        db.execute("ALTER TABLE ps_model_call DROP COLUMN actual_tokens");
        db.execute("ALTER TABLE ps_ocr_call DROP COLUMN response_json");
        db.execute("ALTER TABLE ps_ocr_ticket DROP COLUMN lease_token");
        db.execute("ALTER TABLE ps_member_space DROP COLUMN manager_allowed");
        db.execute("ALTER TABLE ps_operation_item DROP COLUMN payload_hash");
        assertEquals(Set.of("ps_document.process_job_id","ps_document.page_count",
            "ps_model_call.actual_tokens","ps_ocr_call.response_json","ps_ocr_ticket.lease_token",
            "ps_member_space.manager_allowed","ps_operation_item.payload_hash"),new HashSet<>(readiness.missing()));
    }
    @Test void unrelatedSchemaWithSameTableCannotHideMissingTable() {
        db.execute("DROP TABLE ps_legacy_migration_row");
        db.execute("CREATE SCHEMA other_project");
        db.execute("CREATE TABLE other_project.ps_legacy_migration_row(id INT)");
        assertEquals(List.of("ps_legacy_migration_row"),readiness.missing());
    }
    @Test void completeTablesWithoutHistoryMarkersCannotClaimReady() {
        db.update("DELETE FROM paismart_schema_history WHERE version_no IN (2,4,5)");
        db.update("INSERT INTO paismart_schema_history(version_no,description) VALUES(7,'Unrelated later version')");
        assertEquals(List.of("paismart_schema_history.version:2","paismart_schema_history.version:4","paismart_schema_history.version:5"),readiness.missing());
    }
    @Test void existingFourVersionSchemaRequiresExplicitDatasetUpgrade() {
        db.execute("DROP TABLE ps_evaluation_dataset");
        db.update("DELETE FROM paismart_schema_history WHERE version_no=5");
        assertEquals(List.of("ps_evaluation_dataset","paismart_schema_history.version:5"),readiness.missing());
        new ResourceDatabasePopulator(new ClassPathResource("db/paismart/V005__evaluation_datasets.sql")).execute(source);
        assertEquals(List.of(),readiness.missing());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_evaluation_dataset",Integer.class));
    }
    @Test void privateDatasetOwnershipAndObjectIntegrityColumnsAreRequired() {
        db.execute("DROP INDEX ix_ps_eval_dataset_owner");
        db.execute("ALTER TABLE ps_evaluation_dataset DROP CONSTRAINT uk_ps_eval_dataset_object");
        for(String column:List.of("user_id","object_key","sha256","byte_size","case_count"))
            db.execute("ALTER TABLE ps_evaluation_dataset DROP COLUMN "+column);
        assertEquals(Set.of("ps_evaluation_dataset.user_id","ps_evaluation_dataset.object_key",
            "ps_evaluation_dataset.sha256","ps_evaluation_dataset.byte_size","ps_evaluation_dataset.case_count"),
            new HashSet<>(readiness.missing()));
    }
    @Test void everyDdlColumnIsCoveredIncludingFutureSchemaChanges() throws Exception {
        Map<String,Set<String>> actual=new LinkedHashMap<>();
        try(Connection connection=source.getConnection();var result=connection.getMetaData()
            .getColumns(connection.getCatalog(),connection.getSchema(),"%","%")) {
            while(result.next()) actual.computeIfAbsent(result.getString("TABLE_NAME").toLowerCase(Locale.ROOT),key -> new HashSet<>())
                .add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        Map<String,Set<String>> expected=new LinkedHashMap<>();
        SchemaReadiness.required().forEach((table,fields) -> expected.put(table,new HashSet<>(fields)));
        assertEquals(expected,actual);
    }
    @Test void versionSixAndAllMessageFeedbackFieldsAreRequired(){
        db.execute("DROP INDEX uk_ps_feedback_user_message");for(String column:List.of("message_id","generation_id","row_version","updated_at"))db.execute("ALTER TABLE ps_tool_feedback DROP COLUMN "+column);db.execute("ALTER TABLE ps_chat_message DROP COLUMN generation_id");db.update("DELETE FROM paismart_schema_history WHERE version_no=6");
        assertEquals(Set.of("ps_chat_message.generation_id","ps_tool_feedback.message_id","ps_tool_feedback.generation_id","ps_tool_feedback.row_version","ps_tool_feedback.updated_at","paismart_schema_history.version:6","ps_tool_feedback.unique:user_id,message_id"),new HashSet<>(readiness.missing()));
    }
    @Test void sameIndexNameWithoutUniquenessCannotClaimMessageFeedbackReady(){db.execute("DROP INDEX uk_ps_feedback_user_message");db.execute("CREATE INDEX uk_ps_feedback_user_message ON ps_tool_feedback(user_id,message_id)");assertEquals(List.of("ps_tool_feedback.unique:user_id,message_id"),readiness.missing());}
    @Test void reversedOrWiderUniqueIndexCannotReplaceExactUserMessageConstraint(){
        db.execute("DROP INDEX uk_ps_feedback_user_message");db.execute("CREATE UNIQUE INDEX wrong_order ON ps_tool_feedback(message_id,user_id)");db.execute("CREATE UNIQUE INDEX wrong_width ON ps_tool_feedback(user_id,message_id,generation_id)");assertEquals(List.of("ps_tool_feedback.unique:user_id,message_id"),readiness.missing());
    }
    @Test void singleColumnUniqueAndOtherSchemaIndexCannotHideMissingConstraint(){
        db.execute("DROP INDEX uk_ps_feedback_user_message");db.execute("CREATE UNIQUE INDEX wrong_single ON ps_tool_feedback(message_id)");db.execute("CREATE SCHEMA unrelated_feedback");db.execute("CREATE TABLE unrelated_feedback.ps_tool_feedback(user_id BIGINT,message_id VARCHAR(36),UNIQUE(user_id,message_id))");assertEquals(List.of("ps_tool_feedback.unique:user_id,message_id"),readiness.missing());
    }
    @Test void ConstraintIsCheckedByItsDefinitionInsteadOfJustExpectedName(){db.execute("DROP INDEX uk_ps_feedback_user_message");db.execute("CREATE UNIQUE INDEX actual_feedback_constraint ON ps_tool_feedback(user_id,message_id)");assertEquals(List.of(),readiness.missing());}
    List<String> mysqlConstraintMissing(Integer prefix)throws Exception{
        try(Connection actual=source.getConnection()){
            String catalog=actual.getCatalog();
            var metadata=org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class,org.mockito.AdditionalAnswers.delegatesTo(actual.getMetaData()));org.mockito.Mockito.doReturn("MySQL").when(metadata).getDatabaseProductName();
            var connection=org.mockito.Mockito.mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(actual));org.mockito.Mockito.doReturn(metadata).when(connection).getMetaData();var statement=org.mockito.Mockito.mock(java.sql.PreparedStatement.class);var rows=org.mockito.Mockito.mock(java.sql.ResultSet.class);var position=new java.util.concurrent.atomic.AtomicInteger();
            String sql="SELECT SEQ_IN_INDEX,COLUMN_NAME,NON_UNIQUE,SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX";
            org.mockito.Mockito.doReturn(statement).when(connection).prepareStatement(sql);org.mockito.Mockito.when(statement.executeQuery()).thenReturn(rows);org.mockito.Mockito.when(rows.next()).thenAnswer(invocation->position.incrementAndGet()<=2);org.mockito.Mockito.when(rows.getInt("SEQ_IN_INDEX")).thenAnswer(invocation->position.get());org.mockito.Mockito.when(rows.getString("COLUMN_NAME")).thenAnswer(invocation->position.get()==1?"user_id":"message_id");org.mockito.Mockito.when(rows.getObject("SUB_PART")).thenAnswer(invocation->position.get()==2?prefix:null);
            var wrapped=org.mockito.Mockito.mock(javax.sql.DataSource.class);org.mockito.Mockito.when(wrapped.getConnection()).thenReturn(connection);var result=new SchemaReadiness(new JdbcTemplate(wrapped)).missing();org.mockito.Mockito.verify(statement).setString(1,catalog);return result;
        }
    }
    @Test void mysqlUniquePrefixCannotClaimCompleteMessageIdConstraint()throws Exception{assertEquals(List.of("ps_tool_feedback.unique:user_id,message_id"),mysqlConstraintMissing(6));}
    @Test void mysqlFullColumnUniqueMetadataIsAcceptedWithoutDatabaseWrites()throws Exception{assertEquals(List.of(),mysqlConstraintMissing(null));}
}
