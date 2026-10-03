package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.controller.AipptController;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 材料删除的真实服务/HTTP入口回归；仅隔离H2，不读取真实材料或对象。 */
class AipptSourceDeletionEntryTest {
    @Test void deletedSourceSectionsAreUnavailableAtControllerWhileAuditRowIsRetained() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject source = f.source();
        JSONObject section = f.service.record(f.project, f.owner, "ready");
        section.put("source_id", source.getString("id"));
        f.db.insert("source_section", section);
        AipptController controller = new AipptController(f.service, mock(AssetService.class),
                f.catalog, f.db, f.props, mock(AipptHealthService.class));
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(f.owner);
            JSONArray before = (JSONArray) controller.sections(f.project, source.getString("id")).get("data");
            assertEquals(source.getJSONArray("sections"), before);
            controller.removeSource(f.project, source.getString("id"), "remove", Map.of("input_revision", 1));
            assertEquals("deleted", f.db.get("source", source.getString("id")).getString("status"));
            assertTrue(f.service.sources(f.project, f.owner).isEmpty());
            assertTrue(f.db.children("source_section", f.project).isEmpty());
            AipptException failure = assertThrows(AipptException.class,
                    () -> controller.sections(f.project, source.getString("id")));
            assertEquals(404, failure.status());
        }
    }

    @Test void newDeleteCommandForDeletedSourceDoesNotAdvanceInputVersionOrCreateAnotherCommand() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject source = f.source();
        f.service.removeSource(f.project, source.getString("id"), f.owner, "remove",
                JSONObject.of("input_revision", 1));
        long revision = f.project().getLongValue("input_revision");
        int commands = f.db.children("idempotency", f.project).size();
        int events = f.db.children("event", f.project).size();
        AipptException failure = assertThrows(AipptException.class,
                () -> f.service.removeSource(f.project, source.getString("id"), f.owner, "remove-again",
                        JSONObject.of("input_revision", revision)));
        assertEquals(404, failure.status());
        assertEquals(revision, f.project().getLongValue("input_revision"));
        assertEquals(commands, f.db.children("idempotency", f.project).size());
        assertEquals(events, f.db.children("event", f.project).size());
    }

    @Test void identicalDeleteReplayStillReturnsOriginalResultWithoutASecondVersionChange() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject source = f.source();
        JSONObject request = JSONObject.of("input_revision", 1);
        JSONObject original = f.service.removeSource(f.project, source.getString("id"), f.owner, "remove", request);
        JSONObject replay = f.service.removeSource(f.project, source.getString("id"), f.owner, "remove", request);
        assertEquals(original.keySet(), replay.keySet());
        assertEquals(original.getString("id"), replay.getString("id"));
        assertEquals(original.getLongValue("input_revision"), replay.getLongValue("input_revision"));
        assertEquals(2, f.project().getLongValue("input_revision"));
        assertEquals(1, f.db.children("idempotency", f.project).size());
    }

    @Test void deletingPendingSourceCancelsParserAndRejectsItsPreviouslyValidReceipt() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject source = f.service.addSource(f.project, f.owner, "add",
                JSONObject.of("input_revision", 1, "kind", "text", "text", "保留另一材料的正文"));
        JSONObject task = f.db.get("task", source.getString("job_id"));
        task.putAll(JSONObject.of("status", "running", "attempt", 1, "fencing_token", 1,
                "lease_token", "isolated-lease", "lease_until", java.time.Instant.now().plusSeconds(60).toString()));
        f.db.save("task", task, task.getLongValue("revision"));
        f.service.removeSource(f.project, source.getString("id"), f.owner, "remove",
                JSONObject.of("input_revision", 2));
        assertEquals("cancelled", f.db.get("task", task.getString("id")).getString("status"));
        assertTrue(f.db.get("task", task.getString("id")).getBooleanValue("cancel_requested"));
        AipptException failure = assertThrows(AipptException.class,
                () -> f.leases().complete(task.getString("id"), JSONObject.of("attempt", 1,
                        "fencingToken", 1, "leaseToken", "isolated-lease", "output", JSONObject.of("sections", new JSONArray()))));
        assertEquals(409, failure.status());
        assertEquals("deleted", f.db.get("source", source.getString("id")).getString("parse_status"));
    }
}
