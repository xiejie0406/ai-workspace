package com.ruoyi.fashion.configuration.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
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
        tables.addAll(Set.of("fq_selection_pool", "fq_selection_draft", "fq_proposal_image_task", "fq_proposal_delivery"));
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
            if (historyExists) verifyVersionSchema(connection, expected, tables, currentVersion(connection), false);
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
            if (!tableExists(connection, expected, RUOYI_MARKER_TABLE)
                    || !tableExists(connection, expected, FashionFlywayFactory.HISTORY_TABLE))
                throw new IllegalStateException("Fashion 当前 Schema 缺少平台标志或迁移历史");
            verifyVersionSchema(connection, expected, businessTables(connection, expected), currentVersion(connection), true);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法校验 Fashion 当前 Schema", exception);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        int current = 0;
        boolean seen = false;
        try (PreparedStatement statement = connection.prepareStatement(
                "select version,type,success from " + FashionFlywayFactory.HISTORY_TABLE + " order by installed_rank");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                if (!rows.getBoolean("success")) throw new IllegalStateException("Fashion 存在失败迁移，停止并核查原记录");
                String version = rows.getString("version");
                if (version == null) throw new IllegalStateException("Fashion 历史含未认可的无版本迁移");
                int next;
                try { next = Integer.parseInt(version); }
                catch (NumberFormatException ex) { throw new IllegalStateException("Fashion 历史含未认可的版本"); }
                if (next < 0 || next > 4 || (next == 0 && (seen || !"BASELINE".equals(rows.getString("type"))))
                        || (next > 0 && (!"SQL".equals(rows.getString("type")) || next != current + 1)))
                    throw new IllegalStateException("Fashion 迁移历史不连续或超出支持版本");
                current = next;
                seen = true;
            }
        }
        if (!seen) throw new IllegalStateException("Fashion 已存在的迁移历史为空，停止并核查原记录");
        return current;
    }

    private static void verifyVersionSchema(Connection connection, String database, Set<String> tables,
            int version, boolean requireCurrent) throws SQLException {
        if (requireCurrent && version != Integer.parseInt(FashionFlywayFactory.EXPECTED_VERSION))
            throw new IllegalStateException("Fashion 业务需要 Schema V4，当前为 V" + version);
        Set<String> expected = new LinkedHashSet<>();
        if (version > 0) expected.addAll(LEGACY_BUSINESS_TABLES);
        if (version >= 3) expected.addAll(Set.of("fq_selection_pool", "fq_selection_draft", "fq_proposal_image_task"));
        if (version >= 4) expected.add("fq_proposal_delivery");
        if (!tables.equals(expected)) throw mismatch(tables);
        if (version == 0) return;
        if (version < 2) rejectColumns(connection, database, "fq_product", Set.of("attributes_json"));
        if (version < 3) rejectColumns(connection, database, "fq_product", Set.of("group_price", "vip_price"));
        if (version == 3) rejectColumns(connection, database, "fq_proposal_image_task", Set.of("provider_code", "model", "estimated_cost", "actual_cost", "cost_currency", "billing_status", "submitted_at", "finished_at", "cancel_requested_at", "next_query_at", "retry_count"));
        requireColumns(connection, database, "fq_product", Set.of("id", "source_code", "sku_code", "sale_price",
                "currency", "tax_mode", "price_as_of", "images_json", "main_image_key", "row_version"));
        if (version >= 2) requireColumns(connection, database, "fq_product", Set.of("attributes_json"));
        if (version >= 3) {
            requireColumns(connection, database, "fq_product", Set.of("group_price", "vip_price"));
            requireColumns(connection, database, "fq_selection_pool", Set.of("id", "owner_id", "title", "request_json", "snapshot_json", "create_time"));
            requireColumns(connection, database, "fq_selection_draft", Set.of("pool_id", "owner_id", "row_version", "selection_json", "update_time"));
            requireColumns(connection, database, "fq_proposal_image_task", Set.of("id", "pool_id", "owner_id", "draft_version", "combo_id", "input_hash", "input_json", "request_key", "status", "provider_task_id", "result_json", "review_json", "error_message", "row_version", "create_time", "update_time"));
            requireConstraints(connection, database, Map.of(
                    "fq_product", Set.of("ck_fq_product_group_price", "ck_fq_product_vip_price"),
                    "fq_selection_pool", Set.of("ck_fq_pool_request_json", "ck_fq_pool_snapshot_json"),
                    "fq_selection_draft", Set.of("fk_fq_selection_draft_pool", "ck_fq_selection_draft_json", "ck_fq_selection_draft_version"),
                    "fq_proposal_image_task", Set.of("uk_fq_proposal_image_request", "fk_fq_proposal_image_pool", "ck_fq_proposal_image_input", "ck_fq_proposal_image_result", "ck_fq_proposal_image_review", "ck_fq_proposal_image_version")));
        }
        if (version >= 4) {
            requireColumns(connection, database, "fq_proposal_image_task", Set.of("provider_code", "model", "estimated_cost", "actual_cost", "cost_currency", "billing_status", "submitted_at", "finished_at", "cancel_requested_at", "next_query_at", "retry_count"));
            requireColumns(connection, database, "fq_proposal_delivery", Set.of("id", "owner_id", "pool_id", "draft_version", "request_key", "title", "status", "snapshot_json", "file_key", "file_hash", "file_size", "page_count", "download_count", "create_time", "update_time", "error_message"));
            requireConstraints(connection, database, Map.of(
                    "fq_proposal_image_task", Set.of("ck_fq_proposal_image_cost", "ck_fq_proposal_image_retry"),
                    "fq_proposal_delivery", Set.of("uk_proposal_delivery_request", "fk_fq_proposal_delivery_pool", "ck_fq_proposal_delivery_counts")));
        }
    }

    private static void requireColumns(Connection connection, String database, String table, Set<String> required) throws SQLException {
        Set<String> missing = new LinkedHashSet<>(required);
        try (PreparedStatement statement = connection.prepareStatement("select column_name from information_schema.columns where table_schema=? and table_name=?")) {
            statement.setString(1, database); statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) missing.remove(rows.getString(1)); }
        }
        if (!missing.isEmpty()) throw new IllegalStateException("Fashion Schema 缺少列：" + table + " " + missing);
    }

    private static void rejectColumns(Connection connection, String database, String table, Set<String> future) throws SQLException {
        Set<String> found = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("select column_name from information_schema.columns where table_schema=? and table_name=?")) {
            statement.setString(1, database); statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) { String name=rows.getString(1); if(future.contains(name)) found.add(name); } }
        }
        if (!found.isEmpty()) throw new IllegalStateException("Fashion Schema 与迁移版本不一致，发现未登记的后续列：" + table + " " + found);
    }

    private static void requireConstraints(Connection connection, String database, Map<String, Set<String>> required) throws SQLException {
        for (var entry : required.entrySet()) {
            Set<String> missing = new LinkedHashSet<>(entry.getValue());
            try (PreparedStatement statement = connection.prepareStatement("select constraint_name from information_schema.table_constraints where table_schema=? and table_name=? and enforced='YES'")) {
                statement.setString(1, database); statement.setString(2, entry.getKey());
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) missing.remove(rows.getString(1)); }
            }
            if (!missing.isEmpty()) throw new IllegalStateException("Fashion Schema 缺少生效约束：" + entry.getKey() + " " + missing);
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
