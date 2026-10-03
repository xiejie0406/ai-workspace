package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.domain.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AipptOutlineRecoveryTest {
    private AipptPersistenceFixture f;
    @BeforeEach void setup() throws Exception { f=new AipptPersistenceFixture(); }
    private JSONObject generate(String key) {
        return f.service.generateOutline(f.project,f.owner,key,JSONObject.of("input_revision",f.project().getLongValue("input_revision")));
    }
    @Test void regeneratingOutlineKeepsOldPagesBlocksEditsAndExposesFinalFailureBeforeRetry() {
        JSONObject before=f.outline("draft");
        JSONObject accepted=generate("first");
        JSONObject during=f.service.outline(f.project,f.owner);
        assertEquals("generating",during.getString("status"));
        assertEquals(before.getJSONArray("pages"),during.getJSONArray("pages"));
        assertNotNull(during.getString("job_id"));
        assertThrows(AipptException.class,()->f.service.saveOutline(f.project,f.owner,"edit",JSONObject.of("revision",during.getLongValue("revision"),"pages",during.getJSONArray("pages"))));
        assertThrows(AipptException.class,()->f.service.confirmOutline(f.project,f.owner,"confirm",JSONObject.of("revision",during.getLongValue("revision"),"input_revision",1),false));
        JSONObject task=f.db.get("task",during.getString("job_id"));
        f.tx.execute(status->{ task.put("status","failed"); f.db.save("task",task,task.getLongValue("revision")); f.service.failedOutput(task,"算法暂不可用"); return null; });
        JSONObject failed=f.service.outline(f.project,f.owner);
        assertEquals("failed",failed.getString("status"));
        assertEquals("算法暂不可用",failed.getString("error"));
        assertEquals(task.getString("id"),failed.getString("job_id"));
        assertEquals(before.getJSONArray("pages"),failed.getJSONArray("pages"));
        generate("retry");
        JSONObject retry=f.service.outline(f.project,f.owner);
        assertEquals("generating",retry.getString("status"));
        assertNull(retry.get("error"));
        assertNotEquals(task.getString("id"),retry.getString("job_id"));
        assertNotNull(accepted);
    }
    @Test void firstQueuedOutlineExpiresWithoutAnyWorkerAndOnlyItsProjectFails() {
        f.source();
        generate("first");
        JSONObject outline=f.service.outline(f.project,f.owner);
        JSONObject task=f.db.get("task",outline.getString("job_id"));
        JSONObject other=f.service.create(f.owner,1,"other",JSONObject.of("title","其他项目"));
        task.put("deadline",Instant.now().minusSeconds(10).toString());
        f.db.save("task",task,task.getLongValue("revision"));
        assertNull(task.get("lease_until"));
        f.props.getWorker().setEnabled(false); // 中心总deadline独立于Worker领取开关。
        f.leases().recoverExpired();
        JSONObject failed=f.service.outline(f.project,f.owner);
        assertEquals("failed",f.db.get("task",task.getString("id")).getString("status"));
        assertEquals("failed",failed.getString("status"));
        assertTrue(failed.getJSONArray("pages").isEmpty());
        assertEquals("任务超过总时限",failed.getString("error"));
        assertEquals("draft",f.db.get("project",other.getString("id")).getString("status"));
        assertTrue(f.db.children("task",other.getString("id")).isEmpty());
        assertEquals(1,f.db.children("event",f.project).stream().filter(e->"failed".equals(e.getString("type"))).count());
    }
    @Test void successfulCurrentTaskClearsOldErrorAndOldTaskCannotReplaceOutline() {
        f.outline("failed");
        generate("retry");
        JSONObject current=f.service.outline(f.project,f.owner),task=f.db.get("task",current.getString("job_id"));
        f.tx.execute(status->{ f.service.completeOutput(task,JSONObject.of("pages",current.getJSONArray("pages")),new JSONArray()); return null; });
        JSONObject ready=f.service.outline(f.project,f.owner);
        assertEquals("draft",ready.getString("status"));
        assertNull(ready.get("error"));
        task.put("id",java.util.UUID.randomUUID().toString());
        assertThrows(AipptException.class,()->f.tx.execute(status->{f.service.completeOutput(task,JSONObject.of("pages",current.getJSONArray("pages")),new JSONArray());return null;}));
        assertEquals(ready.getJSONArray("pages"),f.service.outline(f.project,f.owner).getJSONArray("pages"));
    }
}
