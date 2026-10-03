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
            new ClassPathResource("db/paismart/V005__evaluation_datasets.sql")
        ).execute(source);
    }
    @Test void completeVersionedSchemaNeedsNoBusinessDataOrWrites() {
        assertEquals(List.of(),readiness.missing());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_member",Integer.class));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM paismart_schema_history",Integer.class));
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
        db.update("INSERT INTO paismart_schema_history(version_no,description) VALUES(6,'Unrelated later version')");
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
}
