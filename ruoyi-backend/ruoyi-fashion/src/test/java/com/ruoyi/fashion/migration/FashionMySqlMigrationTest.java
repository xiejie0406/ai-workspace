package com.ruoyi.fashion.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;

import com.ruoyi.fashion.configuration.persistence.FashionDatabasePreconditions;
import com.ruoyi.fashion.configuration.persistence.FashionFlywayFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Docker 可用时，在隔离 MySQL 8.4 执行真实 RuoYi baseline + Fashion migration。 */
@Testcontainers(disabledWithoutDocker = true)
class FashionMySqlMigrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.0")
            .withDatabaseName("fashion_test")
            .withUsername("fashion_test")
            .withPassword("fashion_test");

    private static DataSource dataSource;
    private static Flyway flyway;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void baselineAndMigrate() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        ResourceDatabasePopulator baseline = new ResourceDatabasePopulator();
        baseline.setSqlScriptEncoding("UTF-8");
        baseline.addScript(new FileSystemResource(workspacePath("ruoyi-backend", "sql", "ry_20260417.sql")));
        baseline.execute(dataSource);

        FashionDatabasePreconditions.Inspection inspection =
                FashionDatabasePreconditions.inspect(dataSource, "fashion_test");
        assertTrue(inspection.businessSchemaEmpty());
        flyway = FashionFlywayFactory.create(dataSource);
        flyway.baseline();
        assertEquals(3, flyway.migrate().migrationsExecuted);
        flyway.validate();
        FashionDatabasePreconditions.verifyCurrentSchema(dataSource, "fashion_test");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsExpectedBusinessTablesAndDedicatedHistory() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                 where table_schema = 'fashion_test' and table_name like 'fq\\_%'
                 order by table_name
                """, String.class);
        assertEquals(19, tables.size());
        assertEquals(FashionDatabasePreconditions.EXPECTED_BUSINESS_TABLES, SetSupport.copyOf(tables));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema = 'fashion_test' and table_name = 'fashion_flyway_schema_history'
                """, Integer.class));
        assertEquals(18, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = 'fashion_test' and table_name like 'fq\\_%'
                   and column_name = 'row_version' and column_default = '1'
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema = 'fashion_test'
                   and table_name in ('fq_import_template','fq_ai_feedback','fq_price_version',
                                      'fq_stock_version','fq_quote_snapshot','fq_audit','fq_outbox')
                """, Integer.class));
    }

    @Test
    void preservesRuoYiDataAndEnforcesDatabaseConstraints() {
        assertEquals("true", jdbc.queryForObject(
                "select config_value from sys_config where config_key='sys.account.captchaEnabled'", String.class));
        jdbc.update("""
                insert into fq_customer
                    (id, code, name, customer_type, salesperson_id, collaborator_ids, status,
                     create_by, create_time, update_by, update_time, row_version)
                values (1, 'C-001', '测试客户', 'wholesale', 1, json_array(), 'active',
                        1, utc_timestamp(3), 1, utc_timestamp(3), 1)
                """);
        assertThrows(Exception.class, () -> jdbc.update("""
                insert into fq_customer
                    (id, code, name, customer_type, salesperson_id, collaborator_ids, status,
                     create_by, create_time, update_by, update_time, row_version)
                values (2, 'C-001', '重复编码', 'wholesale', 1, json_array(), 'active',
                        1, utc_timestamp(3), 1, utc_timestamp(3), 1)
                """));
        assertThrows(Exception.class, () -> jdbc.update("""
                insert into fq_customer
                    (id, code, name, customer_type, salesperson_id, collaborator_ids, status,
                     create_by, create_time, update_by, update_time, row_version)
                values (3, 'C-003', '错误版本', 'wholesale', 1, json_array(), 'active',
                        1, utc_timestamp(3), 1, utc_timestamp(3), 0)
                """));
        assertEquals(1, jdbc.queryForObject("select count(*) from fq_customer", Integer.class));
    }

    @Test
    void repeatedMigrationIsNoOpAndWrongDatabaseFailsClosed() {
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertThrows(IllegalStateException.class,
                () -> FashionDatabasePreconditions.inspect(dataSource, "not_fashion_test"));
    }

    private static Path workspacePath(String... parts) {
        Path current = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && current != null; up++, current = current.getParent()) {
            Path candidate = current;
            for (String part : parts) {
                candidate = candidate.resolve(part);
            }
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("无法定位 workspace 文件 " + String.join("/", parts));
    }

    private static final class SetSupport {
        private SetSupport() {
        }

        static java.util.Set<String> copyOf(List<String> values) {
            return java.util.Set.copyOf(values);
        }
    }
}
