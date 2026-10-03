package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.domain.AipptRules;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 正式页操作后的提纲确认复用真实 MyBatis/H2 持久化，不调用模型或外部服务。 */
class AipptFormalPageConfirmationTest {
    private JSONObject prepared(AipptPersistenceFixture f, int count) {
        JSONObject outline = f.outline("confirmed");
        JSONArray pages = outline.getJSONArray("pages");
        for (int i = 0; i < pages.size(); i++) {
            JSONObject slide = f.slide(AipptRules.blank().getJSONArray("blocks"));
            slide.put("outline_page_id", pages.getJSONObject(i).getString("id"));
            slide.put("position", i + 1);
            f.db.save("slide", slide, slide.getLongValue("revision"));
        }
        while (f.db.children("slide", f.project).size() < count) {
            f.service.pages(f.project, null, "add", f.owner, UUID.randomUUID().toString(),
                JSONObject.of("deck_revision", f.project().getLongValue("deck_revision")));
        }
        while (f.db.children("slide", f.project).size() > count) {
            List<JSONObject> slides = f.db.children("slide", f.project);
            f.service.pages(f.project, slides.get(slides.size() - 1).getString("id"), "remove", f.owner,
                UUID.randomUUID().toString(), JSONObject.of("deck_revision", f.project().getLongValue("deck_revision")));
        }
        outline = f.db.get("outline", outline.getString("id"));
        f.service.unconfirm(f.project, f.owner, "unconfirm",
            JSONObject.of("revision", outline.getLongValue("revision"), "input_revision", f.project().getLongValue("input_revision")));
        return f.db.get("outline", outline.getString("id"));
    }

    private JSONObject confirm(AipptPersistenceFixture f, JSONObject outline) {
        return f.service.confirmOutline(f.project, f.owner, UUID.randomUUID().toString(),
            JSONObject.of("revision", outline.getLongValue("revision"), "input_revision", f.project().getLongValue("input_revision")), false);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 21, 40})
    void manualPageOperationsCanUnconfirmAndConfirmWithoutChangingContent(int count) throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject outline = prepared(f, count);
        JSONArray beforePages = AipptRules.copy(outline).getJSONArray("pages");
        List<JSONObject> beforeSlides = f.db.children("slide", f.project);
        long deckRevision = f.project().getLongValue("deck_revision");
        JSONObject confirmed = confirm(f, outline);
        assertEquals("confirmed", confirmed.getString("status"));
        assertEquals(count, confirmed.getJSONArray("pages").size());
        assertEquals(beforePages, confirmed.getJSONArray("pages"));
        assertEquals(beforeSlides, f.db.children("slide", f.project));
        assertEquals(count, f.project().getIntValue("page_count"));
        assertEquals(deckRevision, f.project().getLongValue("deck_revision"));
        assertEquals("outline_ready", f.project().getString("status"));
    }

    @Test
    void formalConfirmationStillRejectsUnknownLayoutCountStalenessAndOldRevision() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject outline = prepared(f, 5);
        JSONObject invalid = AipptRules.copy(outline);
        invalid.getJSONArray("pages").getJSONObject(0).put("layout_id", "unknown-layout");
        f.db.save("outline", invalid, invalid.getLongValue("revision"));
        assertThrows(AipptException.class, () -> confirm(f, invalid));
        assertEquals("draft", f.db.get("outline", invalid.getString("id")).getString("status"));

        JSONObject restored = f.db.get("outline", invalid.getString("id"));
        restored.put("pages", outline.getJSONArray("pages"));
        f.db.save("outline", restored, restored.getLongValue("revision"));
        JSONObject project = f.project();
        project.put("page_count", 4);
        f.db.save("project", project, project.getLongValue("revision"));
        assertThrows(AipptException.class, () -> confirm(f, restored));
        project = f.project();
        project.put("page_count", 5);
        project.put("title", "已变化的内容输入");
        f.db.save("project", project, project.getLongValue("revision"));
        AipptException stale = assertThrows(AipptException.class, () -> confirm(f, restored));
        assertEquals("OUTLINE_STALE", stale.errorCode());
        assertThrows(AipptException.class, () -> f.service.confirmOutline(f.project, f.owner, "old-revision",
            JSONObject.of("revision", restored.getLongValue("revision") - 1, "input_revision", f.project().getLongValue("input_revision")), false));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 21, 40})
    void savingAnEditedInitialOutlineStillUsesFiveToTwentyLimit(int count) throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject outline = prepared(f, count);
        assertThrows(AipptException.class, () -> f.service.saveOutline(f.project, f.owner, "save-boundary",
            JSONObject.of("revision", outline.getLongValue("revision"), "input_revision", f.project().getLongValue("input_revision"), "pages", outline.getJSONArray("pages"))));
        assertEquals("draft", f.db.get("outline", outline.getString("id")).getString("status"));
    }
}
