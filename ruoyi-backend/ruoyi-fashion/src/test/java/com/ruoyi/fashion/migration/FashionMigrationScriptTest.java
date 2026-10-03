package com.ruoyi.fashion.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ruoyi.fashion.configuration.persistence.FashionDatabasePreconditions;
import org.junit.jupiter.api.Test;

class FashionMigrationScriptTest {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?im)^create table (fq_[a-z_]+)\\s*\\(");
    private static final Pattern DESIGN_TABLE = Pattern.compile("^### T\\d+ `(?<table>fq_[a-z_]+)");
    private static final Pattern DESIGN_FIELD = Pattern.compile("^\\| (?<field>[a-z][a-z0-9_]*) \\|");
    private static final Pattern SQL_COLUMN = Pattern.compile("(?m)^    (?<field>[a-z][a-z0-9_]*)\\s+");
    private static final List<String> COMMON_COLUMNS = List.of(
            "id", "create_by", "create_time", "update_by", "update_time", "row_version");

    @Test
    void createsExactlyTheTwentyApprovedBusinessTablesThroughV4() throws IOException {
        String sql = migrationSql();
        Path directory = workspacePath("ruoyi-backend", "ruoyi-fashion", "src", "main", "resources", "db", "fashion-migration");
        try(var paths=Files.list(directory)) {
            for(Path path:paths.filter(p->!p.getFileName().toString().startsWith("V1__")).sorted().toList())
                sql += "\n" + Files.readString(path);
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = CREATE_TABLE.matcher(sql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }

        assertEquals(FashionDatabasePreconditions.EXPECTED_BUSINESS_TABLES, names);
        assertFalse(sql.toLowerCase().contains("drop table"));
        assertFalse(sql.toLowerCase().contains("foreign_key_checks"));
        for (String forbidden : List.of(
                "fq_import_template", "fq_ai_feedback", "fq_price_version", "fq_stock_version",
                "fq_quote_snapshot", "fq_audit", "fq_budget_ledger", "fq_outbox", "fq_callback")) {
            assertFalse(names.contains(forbidden), forbidden);
        }
    }

    @Test
    void databaseDesignColumnsMatchMigrationExactly() throws IOException {
        Map<String, List<String>> design = designColumns();
        String sql = migrationSql();
        Set<String> v1Tables=new LinkedHashSet<>();Matcher names=CREATE_TABLE.matcher(sql);while(names.find())v1Tables.add(names.group(1));
        assertEquals(v1Tables, design.keySet());

        for (Map.Entry<String, List<String>> entry : design.entrySet()) {
            String table = entry.getKey();
            Matcher block = Pattern.compile(
                    "(?is)create table " + Pattern.quote(table) + " \\((.*?)\\) engine=innodb")
                    .matcher(sql);
            assertTrue(block.find(), table);
            List<String> actual = new ArrayList<>();
            Matcher column = SQL_COLUMN.matcher(block.group(1));
            while (column.find()) {
                String candidate = column.group("field");
                if (!Set.of("primary", "unique", "key", "constraint").contains(candidate)) {
                    actual.add(candidate);
                }
            }
            List<String> expected = new ArrayList<>(COMMON_COLUMNS);
            expected.addAll(entry.getValue());
            assertEquals(new LinkedHashSet<>(expected), new LinkedHashSet<>(actual), table);
            assertEquals(expected.size(), actual.size(), table + " duplicate or missing column");
        }
    }

    private static Map<String, List<String>> designColumns() throws IOException {
        List<String> lines = Files.readAllLines(workspacePath(
                "文档", "项目", "智能选品项目", "归档", "设计", "2026-10-01-设计归一前", "架构", "数据库设计.md"));
        Map<String, List<String>> result = new LinkedHashMap<>();
        String current = null;
        boolean collecting = false;
        for (String line : lines) {
            Matcher table = DESIGN_TABLE.matcher(line);
            if (table.find()) {
                current = table.group("table");
                result.put(current, new ArrayList<>());
                collecting = false;
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher field = DESIGN_FIELD.matcher(line);
            if (field.find()) {
                String name = field.group("field");
                if (!"字段".equals(name)) {
                    result.get(current).add(name);
                    collecting = true;
                }
            } else if (collecting && !line.startsWith("| ---")) {
                current = null;
                collecting = false;
            }
        }
        return result;
    }

    private static String migrationSql() throws IOException {
        return Files.readString(workspacePath(
                "ruoyi-backend", "ruoyi-fashion", "src", "main", "resources", "db",
                "fashion-migration", "V1__create_fashion_schema.sql"));
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
}
