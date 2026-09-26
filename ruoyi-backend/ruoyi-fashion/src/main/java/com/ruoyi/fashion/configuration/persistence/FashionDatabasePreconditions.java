package com.ruoyi.fashion.configuration.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.sql.DataSource;

/** 迁移前允许完整旧基线，迁移后校验当前精确表集合，阻止错库与半套 Schema。 */
public final class FashionDatabasePreconditions {
    public static final String RUOYI_MARKER_TABLE = "sys_menu";
    private static final Set<String> LEGACY_BUSINESS_TABLES = Set.of(
            "fq_product", "fq_stock", "fq_customer", "fq_quote", "fq_quote_combo",
            "fq_quote_detail", "fq_quote_image", "fq_quote_file", "fq_import_batch",
            "fq_import_detail", "fq_ai_agent", "fq_ai_agent_version", "fq_ai_conversation",
            "fq_ai_message", "fq_ai_run", "fq_ai_run_step");
    public static final Set<String> EXPECTED_BUSINESS_TABLES;
    static {
        Set<String> tables = new LinkedHashSet<>(LEGACY_BUSINESS_TABLES);
        tables.addAll(Set.of("fq_selection_pool", "fq_selection_draft", "fq_proposal_image_task"));
        EXPECTED_BUSINESS_TABLES = Set.copyOf(tables);
    }

    private FashionDatabasePreconditions() {
    }

    public static Inspection inspect(DataSource dataSource, String expectedDatabase) {
        try (Connection connection = dataSource.getConnection()) {
            String expected = requireExpectedDatabase(expectedDatabase);
            String actual = scalarString(connection, "select database()");
            if (!expected.equals(actual)) {
                throw new IllegalStateException(
                        "拒绝操作非预期数据库：expected=" + expected + ", actual=" + actual);
            }
            if (!tableExists(connection, expected, RUOYI_MARKER_TABLE)) {
                throw new IllegalStateException("拒绝操作缺少 RuoYi 标志表 sys_menu 的数据库 " + expected);
            }
            Set<String> tables = businessTables(connection, expected);
            boolean historyExists = tableExists(connection, expected, FashionFlywayFactory.HISTORY_TABLE);
            Set<String> unexpected = new LinkedHashSet<>(tables);
            unexpected.removeAll(EXPECTED_BUSINESS_TABLES);
            if (!unexpected.isEmpty()) {
                throw new IllegalStateException("拒绝操作含未知 fq_* 业务表：" + unexpected);
            }
            if (!tables.isEmpty() && !historyExists) {
                throw new IllegalStateException("拒绝操作存在未纳入 Fashion Flyway 历史的 fq_* 表");
            }
            if (historyExists && !tables.isEmpty() && !tables.equals(EXPECTED_BUSINESS_TABLES)
                    && !tables.equals(LEGACY_BUSINESS_TABLES)) {
                throw mismatch(tables);
            }
            return new Inspection(actual, historyExists, tables.isEmpty());
        } catch (SQLException exception) {
            throw new IllegalStateException("无法校验 Fashion 目标数据库", exception);
        }
    }

    public static void verifyCurrentSchema(DataSource dataSource, String expectedDatabase) {
        try (Connection connection = dataSource.getConnection()) {
            String expected = requireExpectedDatabase(expectedDatabase);
            String actual = scalarString(connection, "select database()");
            if (!expected.equals(actual)) {
                throw new IllegalStateException(
                        "拒绝操作非预期数据库：expected=" + expected + ", actual=" + actual);
            }
            Set<String> tables = businessTables(connection, expected);
            if (!tables.equals(EXPECTED_BUSINESS_TABLES)) {
                throw mismatch(tables);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("无法校验 Fashion 当前 Schema", exception);
        }
    }

    private static IllegalStateException mismatch(Set<String> actual) {
        Set<String> missing = new LinkedHashSet<>(EXPECTED_BUSINESS_TABLES);
        missing.removeAll(actual);
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(EXPECTED_BUSINESS_TABLES);
        return new IllegalStateException(
                "Fashion 业务表集合不匹配：missing=" + missing + ", unexpected=" + unexpected);
    }

    private static Set<String> businessTables(Connection connection, String database) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select table_name from information_schema.tables
                 where table_schema = ? and left(table_name, 3) = 'fq_'
                 order by table_name
                """)) {
            statement.setString(1, database);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
        }
        return tables;
    }

    private static boolean tableExists(Connection connection, String database, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.tables
                 where table_schema = ? and table_name = ?
                """)) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : null;
        }
    }

    static String requireExpectedDatabase(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("fashion.migration.expected-database 不能为空");
        }
        return value.trim();
    }

    public record Inspection(String database, boolean historyExists, boolean businessSchemaEmpty) {
    }
}
