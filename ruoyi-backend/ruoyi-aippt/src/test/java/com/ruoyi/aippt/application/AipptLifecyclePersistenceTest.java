package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptRules;
import com.ruoyi.aippt.infrastructure.persistence.*;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 原提纲改动与复制流程的服务/MyBatis事务回归；纯内存H2，不替代真实MySQL验收。 */
public class AipptLifecyclePersistenceTest {
    private AipptRepository db;
    private TransactionTemplate tx;
    private AipptService service;
    private CatalogService catalog;
    private String projectId;
    private JSONObject source, outline;
    private final long owner = 42;

    public static Timestamp utc(int precision) { return Timestamp.from(Instant.now()); }

    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:lifecycle-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = ds.getConnection(); var ddl = connection.createStatement()) {
            ddl.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.aippt.application.AipptLifecyclePersistenceTest.utc'");
            ddl.execute("CREATE TABLE aippt_dispatch_gate(id INT PRIMARY KEY)");
            ddl.execute("INSERT INTO aippt_dispatch_gate VALUES(1)");
            for (String table : List.of("project", "source", "source_section", "outline", "slide", "asset", "audit", "task", "proposal", "export", "snapshot", "event", "outbox", "idempotency")) {
                ddl.execute("CREATE TABLE aippt_" + table + "(id VARCHAR(36) PRIMARY KEY,project_id VARCHAR(36),owner_user_id BIGINT,status VARCHAR(32),revision BIGINT,body VARCHAR(1000000),created_at TIMESTAMP,updated_at TIMESTAMP,event_seq BIGINT DEFAULT 0)");
            }
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(AipptMapper.class);
        factory.setConfiguration(configuration);
        db = new AipptRepository(new SqlSessionTemplate(factory.getObject()).getMapper(AipptMapper.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        AipptProperties props = new AipptProperties();
        props.getWorker().setEnabled(true);
        props.getProvider().setEnabled(true); // 只允许写隔离任务行，无实际Worker或Provider。
        catalog = new CatalogService(props);
        catalog.verifyManifest();
        service = new AipptService(db, tx, props, catalog);
        JSONObject project = service.create(owner, 1, "create", JSONObject.of("title", "完整迁移流程", "page_count", 5, "layout_mode", "fixed"));
        projectId = project.getString("id");
        source = service.record(projectId, owner, "ready");
        String ref = UUID.randomUUID().toString();
        source.putAll(JSONObject.of("kind", "text", "parse_status", "ready", "parser_version", "fixture-1", "sections", JSONArray.of(JSONObject.of("id", ref, "ref", ref, "text", "2025年收入增长18%，经营风险需要独立核对", "level", 0, "locator", "第一段"))));
        outline = service.record(projectId, owner, "confirmed");
        JSONArray pages = new JSONArray();
        JSONArray samples = catalog.sample().getJSONArray("slides");
        tx.execute(status -> {
            db.insert("source", source);
            for (int i = 0; i < 5; i++) {
                JSONObject sample = AipptRules.copy(samples.getJSONObject(i));
                JSONObject page = JSONObject.of("id", UUID.randomUUID().toString(), "title", "原页面" + (i + 1), "objective", "核对经营事实" + i, "key_points", JSONArray.of("收入增长18%", "保留原来源"), "source_refs", JSONArray.of(ref), "layout_id", sample.getString("layout_id"), "page_role", i == 0 ? "cover" : "content", "visual", "保留原视觉意图" + i);
                pages.add(page);
                JSONObject slide = service.record(projectId, owner, "ready");
                sample.remove("id");
                sample.remove("revision");
                slide.putAll(sample);
                slide.putAll(JSONObject.of("title", page.getString("title"), "outline_page_id", page.getString("id"), "position", i + 1, "status", "ready", "layout_mode", "fixed"));
                slide.put("layout_tree", null);
                db.insert("slide", slide);
            }
            outline.put("pages", pages);
            outline.put("input_signature", AipptRules.fingerprint(project, List.of(source)));
            db.insert("outline", outline);
            return null;
        });
    }

    private JSONObject project() { return db.get("project", projectId); }
    private JSONArray slides() { return service.deck(projectId, owner).getJSONArray("slides"); }
    private JSONObject generate(String key) {
        return service.generateDeck(projectId, owner, key, JSONObject.of("deck_revision", project().getLongValue("deck_revision")));
    }

    @Test void changedOutlineDeletesStalePagesAlignsOrderAndPreservesUnchangedReadyContent() {
        JSONArray before = slides();
        Map<String, String> hashes = new HashMap<>();
        for (Object raw : before) { JSONObject slide = (JSONObject) raw; hashes.put(slide.getString("outline_page_id"), slide.getJSONArray("blocks").toJSONString()); }
        String removed = before.getJSONObject(1).getString("id");
        tx.execute(status -> {
            JSONObject current = db.lock("outline", outline.getString("id"));
            JSONArray original = current.getJSONArray("pages"), next = new JSONArray();
            next.add(original.getJSONObject(4));
            next.add(original.getJSONObject(0));
            next.add(original.getJSONObject(2));
            next.add(original.getJSONObject(3));
            JSONObject replacement = AipptRules.copy(original.getJSONObject(1));
            replacement.put("id", UUID.randomUUID().toString());
            replacement.put("title", "新增提纲页");
            next.add(replacement);
            next.getJSONObject(0).put("title", "重排后的标题");
            current.put("pages", next);
            db.save("outline", current, current.getLongValue("revision"));
            return null;
        });
        JSONObject result = generate("outline-changed");
        assertEquals(1, result.getIntValue("pending"));
        assertNull(db.get("slide", removed));
        JSONArray after = slides(), pages = db.get("outline", outline.getString("id")).getJSONArray("pages");
        assertEquals(5, after.size());
        assertEquals("重排后的标题", after.getJSONObject(0).getString("title"));
        for (int i = 0; i < after.size(); i++) {
            JSONObject slide = after.getJSONObject(i);
            assertEquals(i + 1, slide.getIntValue("position"));
            assertEquals(pages.getJSONObject(i).getString("id"), slide.getString("outline_page_id"));
            if (i < 4) {
                assertEquals("ready", slide.getString("status"));
                assertEquals(hashes.get(slide.getString("outline_page_id")), slide.getJSONArray("blocks").toJSONString());
            }
        }
    }

    @Test void changedLayoutAndFixedToFlexAreRegeneratedButUnchangedReadyPagesAreResumed() {
        assertEquals(0, generate("resume-unchanged").getIntValue("pending"));
        tx.execute(status -> {
            JSONObject current = db.lock("outline", outline.getString("id"));
            current.getJSONArray("pages").getJSONObject(0).put("layout_id", "bullets");
            db.save("outline", current, current.getLongValue("revision"));
            return null;
        });
        assertEquals(1, generate("layout-changed").getIntValue("pending"));
        JSONObject changed = slides().getJSONObject(0);
        assertEquals("bullets", changed.getString("layout_id"));
        assertTrue(changed.getJSONArray("blocks").isEmpty());
        service.cancel(projectId, owner, "cancel-before-mode-change", JSONObject.of("deck_revision", project().getLongValue("deck_revision")));
        tx.execute(status -> {
            JSONObject current = db.lock("project", projectId);
            current.put("layout_mode", "flex");
            db.save("project", current, current.getLongValue("revision"));
            return null;
        });
        assertEquals(5, generate("mode-changed").getIntValue("pending"));
        for (Object raw : slides()) {
            JSONObject slide = (JSONObject) raw;
            assertEquals("flex", slide.getString("layout_mode"));
            assertTrue(slide.getJSONArray("blocks").isEmpty());
            assertNull(slide.get("layout_tree"));
        }
    }

    @Test void duplicatePreservesEveryOutlineFieldAndContentProtectionWithFreshIds() {
        JSONObject original = slides().getJSONObject(0);
        JSONObject originalPage = db.get("outline", outline.getString("id")).getJSONArray("pages").getJSONObject(0);
        tx.execute(status -> {
            JSONObject row = db.lock("slide", original.getString("id"));
            row.getJSONArray("blocks").getJSONObject(0).put("locked", true);
            row.put("speaker_notes", "原有备注不得遗漏");
            db.save("slide", row, row.getLongValue("revision"));
            return null;
        });
        service.pages(projectId, original.getString("id"), "duplicate", owner, "duplicate", JSONObject.of("deck_revision", project().getLongValue("deck_revision")));
        JSONArray after = slides(), pages = db.get("outline", outline.getString("id")).getJSONArray("pages");
        assertEquals(6, after.size());
        assertEquals(6, project().getIntValue("page_count"));
        JSONObject duplicate = after.getJSONObject(1), duplicatePage = pages.getJSONObject(1);
        assertNotEquals(original.getString("id"), duplicate.getString("id"));
        assertNotEquals(originalPage.getString("id"), duplicatePage.getString("id"));
        assertEquals(duplicate.getString("outline_page_id"), duplicatePage.getString("id"));
        JSONObject expected = AipptRules.copy(originalPage), actual = AipptRules.copy(duplicatePage);
        expected.remove("id"); actual.remove("id");
        assertEquals(expected, actual);
        assertEquals("原有备注不得遗漏", duplicate.getString("speaker_notes"));
        assertTrue(duplicate.getJSONArray("blocks").getJSONObject(0).getBooleanValue("locked"));
        assertNotEquals(original.getJSONArray("blocks").getJSONObject(0).getString("id"), duplicate.getJSONArray("blocks").getJSONObject(0).getString("id"));
        assertEquals(0, generate("resume-after-duplicate").getIntValue("pending"));
    }
}
