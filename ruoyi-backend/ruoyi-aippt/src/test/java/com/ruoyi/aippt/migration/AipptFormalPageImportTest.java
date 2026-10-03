package com.ruoyi.aippt.migration;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.application.CatalogService;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.domain.AipptRules;
import com.ruoyi.aippt.infrastructure.storage.LocalAipptStorage;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AipptFormalPageImportTest {
    @TempDir Path temp;
    private final CatalogService catalog = new CatalogService(new AipptProperties());

    private JSONObject manifest(int count, String status) {
        String batch = UUID.randomUUID().toString();
        String sectionId = UUID.randomUUID().toString();
        JSONArray pages = new JSONArray(), slides = new JSONArray();
        for (int i = 0; i < count; i++) {
            String pageId = UUID.randomUUID().toString();
            pages.add(JSONObject.of("id", pageId, "title", "原正式页面" + i, "objective", "原内容目的", "key_points", JSONArray.of("事实一", "事实二"),
                "source_refs", JSONArray.of(sectionId), "layout_id", "bullets", "page_role", "content", "visual", null));
            JSONObject slide = AipptRules.blank();
            slide.putAll(JSONObject.of("id", UUID.randomUUID().toString(), "outline_page_id", pageId, "position", i + 1, "status", "ready", "speaker_notes", "原备注" + i));
            slides.add(slide);
        }
        JSONObject source = JSONObject.of("id", UUID.randomUUID().toString(), "kind", "text", "parse_status", "ready", "parser_version", "legacy", "char_count", 4,
            "sections", JSONArray.of(JSONObject.of("id", sectionId, "ref", sectionId, "heading", "原材料", "level", 0, "text", "原文保留")));
        JSONObject outline = JSONObject.of("id", UUID.randomUUID().toString(), "status", status, "legacy_status", "confirmed", "pages", pages, "revision", 5);
        JSONObject project = JSONObject.of("id", UUID.randomUUID().toString(), "owner_id", 7, "legacy_owner_id", "old-owner", "import_batch", batch,
            "title", "正式页迁移", "audience", null, "page_count", count, "theme_id", "ivory", "sources", JSONArray.of(source), "slides", slides, "outline", outline);
        return JSONObject.of("schema_version", "aippt-import-1", "complete", true, "source_sha256", "0".repeat(64), "import_batch", batch,
            "catalog_version", catalog.version(), "projects", JSONArray.of(JSONObject.of("project", project, "assets", new JSONArray(), "audit", new JSONObject())));
    }

    private JSONObject project(JSONObject manifest) { return manifest.getJSONArray("projects").getJSONObject(0).getJSONObject("project"); }
    private AipptLegacyImportCli.Plan plan(JSONObject manifest) throws Exception {
        return AipptLegacyImportCli.prepare(manifest, temp, catalog, JSONObject.of("old-owner", 7));
    }

    private Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:formal-import-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.aippt.migration.AipptImportCompatibilityTest.utc'");
            ddl.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,dept_id BIGINT,del_flag VARCHAR(1))");
            ddl.execute("INSERT INTO sys_user VALUES(7,3,'0')");
            ddl.execute("CREATE TABLE aippt_dispatch_gate(id INT PRIMARY KEY)");
            ddl.execute("INSERT INTO aippt_dispatch_gate VALUES(1)");
            for (String table : List.of("project", "source", "source_section", "outline", "slide", "asset", "audit")) {
                ddl.execute("CREATE TABLE aippt_" + table + "(id VARCHAR(36) PRIMARY KEY,project_id VARCHAR(36),owner_user_id BIGINT,status VARCHAR(32),revision BIGINT,body VARCHAR(1000000),created_at TIMESTAMP,updated_at TIMESTAMP)");
            }
        }
        return connection;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 21, 40})
    void formalOutlineAndAllSlidesReachOfficialRowsWithReconfirmationRequired(int count) throws Exception {
        JSONObject manifest = manifest(count, "draft");
        var plan = plan(manifest);
        var expected = project(manifest);
        try (Connection connection = database()) {
            AipptLegacyImportCli.apply(connection, new LocalAipptStorage(temp.resolve("objects").toString()), plan, plan.projects().get(0), temp.resolve("stages.jsonl"));
            try (PreparedStatement query = connection.prepareStatement("SELECT body FROM aippt_outline WHERE id=?")) {
                query.setString(1, expected.getJSONObject("outline").getString("id"));
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    JSONObject official = JSON.parseObject(row.getString(1));
                    assertEquals("draft", official.getString("status"));
                    assertEquals("confirmed", official.getString("legacy_status"));
                    assertTrue(official.getBooleanValue("requires_reconfirmation"));
                    assertEquals(expected.getJSONObject("outline").getJSONArray("pages"), official.getJSONArray("pages"));
                    assertEquals(count, official.getJSONArray("pages").size());
                    assertNotNull(official.getString("input_signature"));
                }
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT body FROM aippt_slide WHERE project_id=?")) {
                query.setString(1, expected.getString("id"));
                try (ResultSet row = query.executeQuery()) {
                    int actual = 0;
                    while (row.next()) {
                        JSONObject official = JSON.parseObject(row.getString(1));
                        JSONObject before = expected.getJSONArray("slides").stream().map(JSONObject.class::cast)
                            .filter(slide -> slide.getString("id").equals(official.getString("id"))).findFirst().orElseThrow();
                        assertEquals(before.getJSONArray("blocks"), official.getJSONArray("blocks"));
                        assertEquals(before.get("speaker_notes"), official.get("speaker_notes"));
                        actual++;
                    }
                    assertEquals(count, actual);
                }
            }
        }
    }

    @Test
    void malformedRefsLayoutsAndConfirmedCountAreRejected() throws Exception {
        for (String failure : List.of("uuid", "duplicate", "key_points", "ref", "layout", "count")) {
            JSONObject manifest = manifest(5, "confirmed");
            JSONObject project = project(manifest);
            JSONArray pages = project.getJSONObject("outline").getJSONArray("pages");
            switch (failure) {
                case "uuid" -> pages.getJSONObject(0).put("id", "not-a-uuid");
                case "duplicate" -> pages.getJSONObject(1).put("id", pages.getJSONObject(0).getString("id"));
                case "key_points" -> pages.getJSONObject(0).put("key_points", JSONArray.of("单条要点"));
                case "ref" -> pages.getJSONObject(0).put("source_refs", JSONArray.of(UUID.randomUUID().toString()));
                case "layout" -> pages.getJSONObject(0).put("layout_id", "unknown-layout");
                case "count" -> project.put("page_count", 6);
            }
            assertThrows(AipptException.class, () -> plan(manifest), failure);
        }
        JSONObject converted = manifest(21, "draft");
        project(converted).put("page_count", 20);
        assertThrows(AipptException.class, () -> plan(converted), "转换后的 legacy_status=confirmed 仍核对页数");
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "failed"})
    void unconfirmedDraftMayRetainOldPagesWhileNewTargetAwaitsRegeneration(String status) throws Exception {
        JSONObject manifest = manifest(21, status);
        JSONObject original = project(manifest);
        original.getJSONObject("outline").remove("legacy_status");
        original.put("page_count", 10);
        var planned = plan(manifest).projects().get(0);
        JSONObject officialOutline = planned.rows().stream().filter(row -> row.table().equals("outline")).findFirst().orElseThrow().body();
        JSONObject officialProject = planned.rows().stream().filter(row -> row.table().equals("project")).findFirst().orElseThrow().body();
        assertEquals(21, officialOutline.getJSONArray("pages").size());
        assertEquals(10, officialProject.getIntValue("page_count"));
        assertEquals(status, officialOutline.getString("status"));
    }
}
