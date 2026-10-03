package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.domain.AipptRules;
import com.ruoyi.aippt.infrastructure.persistence.AipptRepository;
import com.ruoyi.aippt.infrastructure.storage.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AipptAssetCompensationTest {
    @TempDir Path temp;
    private AipptPersistenceFixture f;
    private FailingStorage storage;
    private AssetCompensationJournal journal;
    private AssetService assets;
    @BeforeEach void setup() throws Exception {
        f=new AipptPersistenceFixture();
        f.props.setStoragePath(temp.resolve("objects").toString());
        f.props.setCompensationPath(temp.resolve("journal").toString());
        journal=new AssetCompensationJournal(f.props.getCompensationPath(),null);
        storage=new FailingStorage();
        assets=new AssetService(f.db,f.service,mock(TaskLeaseService.class),f.tx,f.props,storage);
    }
    private MockMultipartFile file() { return new MockMultipartFile("file","材料.txt","text/plain","业务正文".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private String stageId() { return storage.key.substring(storage.key.lastIndexOf('/')+1); }
    private JSONObject outbox(JSONObject entry) {
        JSONObject out=f.service.record(f.project,f.owner,"pending");
        out.putAll(JSONObject.of("id",AipptRules.hash("asset-stage:"+entry.getString("asset_id")).substring(0,36),"type","asset_stage_cleanup","asset_id",entry.getString("asset_id"),"object_key",entry.getString("object_key"),"not_before",Instant.now().toString()));
        f.db.insert("outbox",out);
        return out;
    }
    @Test void putThenDatabaseAndDeleteFailureLeavesDurableRecoverableStage() {
        AipptRepository db=spy(f.db);
        doThrow(new IllegalStateException("isolated insert failure")).when(db).insert(eq("asset"),any());
        assets=new AssetService(db,f.service,mock(TaskLeaseService.class),f.tx,f.props,storage);
        storage.failDelete=true;
        assertThrows(IllegalStateException.class,()->assets.upload(f.project,f.owner,file(),"source"));
        assertEquals("cleanup_pending",journal.get(stageId()).getString("status"));
        assertNull(f.db.get("asset",stageId()));
        assertTrue(storage.objects.containsKey(storage.key));
        assertEquals(1,journal.pending(Instant.now().plusSeconds(1)).size());
        JSONObject out=outbox(journal.get(stageId()));
        f.props.getCleanup().setEnabled(true);
        storage.failDelete=false;
        assets.cleanCompensation(out);
        assertEquals("deleted",journal.get(stageId()).getString("status"));
        assertFalse(storage.objects.containsKey(storage.key));
    }
    @Test void outerTransactionRollbackNeverMarksInsertedAssetCommitted() {
        storage.failDelete=true;
        f.tx.execute(status->{
            JSONObject staged=assets.upload(f.project,f.owner,file(),"source");
            assertNotNull(f.db.get("asset",staged.getString("id")));
            assertEquals("stage_started",journal.get(staged.getString("id")).getString("status"));
            status.setRollbackOnly();
            return null;
        });
        assertNull(f.db.get("asset",stageId()));
        assertEquals("cleanup_pending",journal.get(stageId()).getString("status"));
        assertTrue(storage.objects.containsKey(storage.key));
    }
    @Test void formalAssetIsProtectedEvenWhenCrashLeftAnUnregisteredJournal() {
        JSONObject formal=assets.upload(f.project,f.owner,file(),"source");
        assertEquals("registered",journal.get(formal.getString("id")).getString("status"));
        journal.mark(formal.getString("id"),"stage_started");
        JSONObject out=outbox(journal.get(formal.getString("id")));
        f.props.getCleanup().setEnabled(true);
        assets.cleanCompensation(out);
        assertEquals(0,storage.deletes);
        assertTrue(storage.objects.containsKey(storage.key));
        assertEquals("registered",journal.get(formal.getString("id")).getString("status"));
        assertEquals("ready",f.db.get("asset",formal.getString("id")).getString("status"));
    }
    @Test void disabledCleanupDoesNotConsumePendingStageOrDeleteAnything() {
        storage.failDelete=true;
        f.tx.execute(status->{assets.upload(f.project,f.owner,file(),"source");status.setRollbackOnly();return null;});
        JSONObject entry=journal.get(stageId());
        int before=storage.deletes;
        assets.enqueueCompensations();
        assets.cleanCompensation(entry);
        assertEquals(before,storage.deletes);
        assertTrue(f.db.children("outbox",f.project).isEmpty());
        assertEquals("cleanup_pending",journal.get(stageId()).getString("status"));
    }
    @Test void missingPrivateCompensationPathRejectsBeforeObjectPut() {
        f.props.setStoragePath(""); f.props.setCompensationPath("");
        assertThrows(com.ruoyi.aippt.domain.AipptException.class,()->assets.upload(f.project,f.owner,file(),"source"));
        assertNull(storage.key);
    }
    private static class FailingStorage implements AipptStoragePort {
        final Map<String,byte[]> objects=new HashMap<>();
        String key;
        int deletes;
        boolean failDelete;
        public void put(String key,byte[] data,String mime) { this.key=key;objects.put(key,data); }
        public byte[] get(String key) { return objects.get(key); }
        public void delete(String key) throws IOException { deletes++;if(failDelete)throw new IOException("isolated delete failure");objects.remove(key); }
    }
}
