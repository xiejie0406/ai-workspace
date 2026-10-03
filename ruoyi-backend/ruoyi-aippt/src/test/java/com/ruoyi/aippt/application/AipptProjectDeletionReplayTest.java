package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.domain.AipptException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 已批准删除补偿的未知回执恢复；已删项目只允许本人重放原删除命令。 */
class AipptProjectDeletionReplayTest {
    private long cleanupCount(AipptPersistenceFixture fixture) {
        return fixture.db.children("outbox", fixture.project).stream()
                .filter(out -> "asset_cleanup".equals(out.getString("type"))).count();
    }
    @Test void identicalProjectDeletionReplayReturnsSavedResultAndOneCleanupManifest() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject request = JSONObject.of("input_revision", 1);
        JSONObject deleted = f.service.deleteProject(f.project, f.owner, "delete", request);
        JSONObject replay = f.service.deleteProject(f.project, f.owner, "delete", request);
        assertEquals(deleted.keySet(), replay.keySet());
        for (String field : java.util.List.of("id", "status", "cleanup_id")) {
            assertEquals(deleted.getString(field), replay.getString(field));
        }
        assertEquals(1, cleanupCount(f));
        assertEquals(1, f.db.children("idempotency", f.project).size());
        assertEquals("deleted", f.db.get("project", f.project).getString("status"));
    }

    @Test void changedBodyWithSameDeletionKeyRemainsConflictAfterDeletion() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.service.deleteProject(f.project, f.owner, "delete", JSONObject.of("input_revision", 1));
        AipptException failure = assertThrows(AipptException.class,
                () -> f.service.deleteProject(f.project, f.owner, "delete", JSONObject.of("input_revision", 2)));
        assertEquals(409, failure.status());
        assertEquals(1, cleanupCount(f));
    }

    @Test void newKeyCannotDeleteOrReopenAlreadyDeletedProject() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.service.deleteProject(f.project, f.owner, "delete", JSONObject.of("input_revision", 1));
        AipptException failure = assertThrows(AipptException.class,
                () -> f.service.deleteProject(f.project, f.owner, "delete-again", JSONObject.of("input_revision", 1)));
        assertEquals(404, failure.status());
        assertEquals(1, cleanupCount(f));
    }

    @Test void otherOwnerCannotReadDeletionReceiptWithTheSameKey() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject request = JSONObject.of("input_revision", 1);
        f.service.deleteProject(f.project, f.owner, "delete", request);
        AipptException failure = assertThrows(AipptException.class,
                () -> f.service.deleteProject(f.project, f.owner + 1, "delete", request));
        assertEquals(404, failure.status());
    }

    @Test void deletionKeyCannotReturnAnotherProjectsReceiptOrDeleteThatProject() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject request = JSONObject.of("input_revision", 1);
        f.service.deleteProject(f.project, f.owner, "delete", request);
        JSONObject other = f.service.create(f.owner, 1, "create-other", JSONObject.of("title", "另一个项目", "page_count", 5));
        AipptException failure = assertThrows(AipptException.class,
                () -> f.service.deleteProject(other.getString("id"), f.owner, "delete", request));
        assertEquals(409, failure.status());
        assertEquals("draft", f.service.getProject(other.getString("id"), f.owner).getString("status"));
        assertTrue(f.db.children("outbox", other.getString("id")).isEmpty());
    }

    @Test void CachedOtherCommandsStillRejectDeletedProjectBeforeReturningTheirResults() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject update = JSONObject.of("input_revision", 1, "title", "原先合法更新");
        f.service.update(f.project, f.owner, "update", update);
        f.service.deleteProject(f.project, f.owner, "delete", JSONObject.of("input_revision", 2));
        AipptException failure = assertThrows(AipptException.class,
                () -> f.service.update(f.project, f.owner, "update", update));
        assertEquals(404, failure.status());
        assertThrows(AipptException.class, () -> f.service.getProject(f.project, f.owner));
        assertEquals(1, cleanupCount(f));
    }
}
