package com.ruoyi.aippt.migration;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.application.*;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.infrastructure.storage.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
/** 独立 H2 实际事务验证，默认工具从不使用这个数据库或端口。 */
public class AipptImportPersistenceTest  {
    @TempDir Path temp;
    private Connection connection;
    private AipptLegacyImportCli.Plan plan;
    private CountingStorage storage;
    public static Timestamp utc(int precision)  {
        return Timestamp.from(java.time.Instant.now());
    }
    @BeforeEach void setup()throws Exception  {
        connection=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try(Statement ddl=connection.createStatement())  {
            ddl.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.aippt.migration.AipptImportPersistenceTest.utc'");
            ddl.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,dept_id BIGINT,del_flag VARCHAR(1))");
            ddl.execute("INSERT INTO sys_user VALUES(7,3,'0')");
            ddl.execute("CREATE TABLE aippt_dispatch_gate(id INT PRIMARY KEY)");
            ddl.execute("INSERT INTO aippt_dispatch_gate VALUES(1)");
            for(String table:List.of("project","source","source_section","outline","slide","asset","audit","task","proposal","export","snapshot","event","outbox","idempotency"))ddl.execute("CREATE TABLE aippt_"+table+"(id VARCHAR(36) PRIMARY KEY,project_id VARCHAR(36),owner_user_id BIGINT,status VARCHAR(32),revision BIGINT,body VARCHAR(1000000),created_at TIMESTAMP,updated_at TIMESTAMP)");
        }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        Path image=temp.resolve("image.png");
        Files.write(image,bytes.toByteArray());
        String batch=UUID.randomUUID().toString();
        JSONObject project=JSONObject.of("id",UUID.randomUUID().toString(),"owner_id",7,"legacy_owner_id","old-user","import_batch",batch,"title","导入测试","page_count",10,"theme_id","ivory","sources",new JSONArray(),"slides",new JSONArray(),"outline",new JSONObject());
        JSONObject asset=JSONObject.of("asset_id",UUID.randomUUID().toString(),"path",image.toString(),"size",bytes.size(),"mime","image/png","sha256",AssetService.bytesHash(bytes.toByteArray()));
        JSONObject manifest=JSONObject.of("schema_version","aippt-import-1","complete",true,"import_batch",batch,"source_sha256","0".repeat(64),"catalog_version","aippt-1-90c52568ea7d","projects",JSONArray.of(JSONObject.of("project",project,"assets",JSONArray.of(asset),"audit",new JSONObject())));
        plan=AipptLegacyImportCli.prepare(manifest,temp,new CatalogService(new AipptProperties()),JSONObject.of("old-user",7));
        storage=new CountingStorage(new LocalAipptStorage(temp.resolve("storage").toString()));
    }
    @AfterEach void close()throws Exception  {
        connection.close();
    }
    @Test void projectCommitIsAtomicAndReplayDoesNotUploadAgain()throws Exception  {
        apply();
        apply();
        assertEquals(1,count("project"));
        assertEquals(1,count("asset"));
        assertEquals(1,count("audit"));
        assertEquals(1,storage.puts);
        try(Statement query=connection.createStatement();
        ResultSet result=query.executeQuery("SELECT body FROM aippt_project"))  {
            assertTrue(result.next());
            assertEquals(3,JSON.parseObject(result.getString(1)).getLongValue("owner_dept_id"));
        }
        assertTrue(Files.readString(temp.resolve("journal.jsonl")).contains("stage_started"));
        assertTrue(Files.readString(temp.resolve("journal.jsonl")).contains("committed"));
    }
    @Test void missingTargetOwnerStopsBeforeObjectsOrFormalWrites()throws Exception  {
        try(Statement statement=connection.createStatement())  {
            statement.execute("DELETE FROM sys_user");
        }
        assertThrows(AipptException.class,this::apply);
        assertEquals(0,storage.puts);
        assertEquals(0,count("project"));
    }
    @Test void failedRowCommitRollsBackProjectAndOnlyDeletesThisAttemptStage()throws Exception  {
        String conflict=plan.projects().get(0).rows().stream().filter(r->r.table().equals("audit")).findFirst().orElseThrow().body().getString("id");
        try(PreparedStatement insert=connection.prepareStatement("INSERT INTO aippt_audit(id,project_id,revision,body) VALUES(?,'other-project',1,'{}')"))  {
            insert.setString(1,conflict);
            insert.executeUpdate();
        }
        assertThrows(SQLException.class,this::apply);
        assertEquals(0,count("project"));
        assertEquals(0,count("asset"));
        assertEquals(1,count("audit"));
        assertEquals(1,storage.deletes);
    }
    @Test void rollbackRefusesLaterChangesAndPreservesData()throws Exception  {
        apply();
        try(Statement statement=connection.createStatement())  {
            statement.execute("UPDATE aippt_project SET revision=revision+1");
        }
        assertThrows(AipptException.class,()->AipptLegacyImportCli.rollback(connection,plan));
        assertEquals(1,count("project"));
        assertEquals(1,count("asset"));
        assertEquals(0,storage.deletes);
    }
    @Test void exactUntouchedBatchCanWithdrawRowsWhileRetainingPrivateObjects()throws Exception  {
        apply();
        AipptLegacyImportCli.rollback(connection,plan);
        assertEquals(0,count("project"));
        assertEquals(0,count("asset"));
        assertEquals(0,count("audit"));
        assertEquals(0,storage.deletes);
    }
    @Test void sourceObjectChangedAfterValidationCannotCommitMismatchedBytes()throws Exception  {
        Files.writeString(plan.projects().get(0).objects().get(0).source(),"modified");
        assertThrows(AipptException.class,this::apply);
        assertEquals(0,count("project"));
        assertEquals(0,storage.puts);
    }
    private void apply()throws Exception  {
        AipptLegacyImportCli.apply(connection,storage,plan,plan.projects().get(0),temp.resolve("journal.jsonl"));
    }
    private int count(String table)throws SQLException  {
        try(Statement query=connection.createStatement();
        ResultSet result=query.executeQuery("SELECT COUNT(*) FROM aippt_"+table))  {
            result.next();
            return result.getInt(1);
        }
    }
    private static class CountingStorage implements AipptStoragePort  {
        final AipptStoragePort delegate;
        int puts,deletes;
        CountingStorage(AipptStoragePort delegate)  {
            this.delegate=delegate;
        }
        public void put(String key,byte[] bytes,String mime)throws IOException  {
            puts++;
            delegate.put(key,bytes,mime);
        }
        public byte[] get(String key)throws IOException  {
            return delegate.get(key);
        }
        public void delete(String key)throws IOException  {
            deletes++;
            delegate.delete(key);
        }
    }
}
