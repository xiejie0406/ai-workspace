package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.domain.AipptException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 幂等键重放绑定原项目，不能把旧目标回执当下一项目已写入。 */
class AipptCommandTargetTest {
    @Test void projectUpdateReceiptCannotBeReplayedAsAnotherProjectWithIdenticalBody() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject other = f.service.create(f.owner, 1, "second-project", JSONObject.of("title", "项目乙", "page_count", 5));
        JSONObject request = JSONObject.of("input_revision", 1, "title", "用户改名意图");
        JSONObject original = f.service.update(f.project, f.owner, "same-intent", request);
        AipptException failure = assertThrows(AipptException.class,
                () -> f.service.update(other.getString("id"), f.owner, "same-intent", request));
        assertEquals(409, failure.status());
        JSONObject unchanged = f.service.getProject(other.getString("id"), f.owner);
        assertEquals("项目乙", unchanged.getString("title"));
        assertEquals(1, unchanged.getLongValue("input_revision"));
        assertTrue(f.db.children("event", other.getString("id")).isEmpty());
        JSONObject replay = f.service.update(f.project, f.owner, "same-intent", request);
        assertEquals(original.getString("id"), replay.getString("id"));
        assertEquals(original.getString("title"), replay.getString("title"));
        assertEquals(2, replay.getLongValue("input_revision"));
    }

    @Test void sourceAddReceiptCannotReturnAnotherProjectsSourceOrTask() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject other = f.service.create(f.owner, 1, "second-project", JSONObject.of("title", "项目乙", "page_count", 5));
        JSONObject request = JSONObject.of("input_revision", 1, "kind", "text", "text", "同一份材料意图");
        JSONObject source = f.service.addSource(f.project, f.owner, "same-intent", request);
        AipptException failure = assertThrows(AipptException.class,
                () -> f.service.addSource(other.getString("id"), f.owner, "same-intent", request));
        assertEquals(409, failure.status());
        assertTrue(f.db.children("source", other.getString("id")).isEmpty());
        assertTrue(f.db.children("task", other.getString("id")).isEmpty());
        assertTrue(f.db.children("outbox", other.getString("id")).isEmpty());
        JSONObject replay = f.service.addSource(f.project, f.owner, "same-intent", request);
        assertEquals(source.getString("id"), replay.getString("id"));
        assertEquals(source.getString("job_id"), replay.getString("job_id"));
        assertEquals(1, f.db.children("source", f.project).size());
    }
}
