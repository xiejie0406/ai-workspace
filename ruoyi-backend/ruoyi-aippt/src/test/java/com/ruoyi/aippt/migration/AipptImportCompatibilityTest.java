package com.ruoyi.aippt.migration;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.application.CatalogService;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.*;
import com.ruoyi.aippt.infrastructure.storage.LocalAipptStorage;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class AipptImportCompatibilityTest {
    @TempDir Path temp;
    private final CatalogService catalog=new CatalogService(new AipptProperties());
    public static Timestamp utc(int precision) { return Timestamp.from(Instant.now()); }
    private JSONObject manifest() {
        String batch=UUID.randomUUID().toString();
        JSONObject project=JSONObject.of("id",UUID.randomUUID().toString(),"owner_id",7,"legacy_owner_id","legacy","import_batch",batch,"title","原项目","audience",null,"page_count",40,"theme_id","ivory","sources",new JSONArray(),"slides",new JSONArray(),"outline",new JSONObject());
        return JSONObject.of("schema_version","aippt-import-1","complete",true,"source_sha256","0".repeat(64),"import_batch",batch,"catalog_version",catalog.version(),"projects",JSONArray.of(JSONObject.of("project",project,"assets",new JSONArray(),"audit",new JSONObject())));
    }
    private AipptLegacyImportCli.Plan plan(JSONObject manifest) throws Exception { return AipptLegacyImportCli.prepare(manifest,temp,catalog,JSONObject.of("legacy",7)); }
    private JSONObject project(JSONObject manifest) { return manifest.getJSONArray("projects").getJSONObject(0).getJSONObject("project"); }
    @Test void originalSettingsCredentialsAreRejectedRecursivelyButBodyAndNotesAreUntouched() throws Exception {
        for(String key:List.of("image_api_key","UNSPLASH-ACCESS-KEY","cos_secret_id","Cos_Secret_Key","image_workspace_id","worker_key","custom_api_key")) {
            JSONObject manifest=manifest();
            manifest.getJSONArray("projects").getJSONObject(0).getJSONObject("audit").put("nested",JSONArray.of(JSONObject.of(key,"do-not-import")));
            assertThrows(AipptException.class,()->plan(manifest),key);
        }
        JSONObject manifest=manifest();
        project(manifest).put("notes","正文说明 image_api_key 与 password，不是结构化凭据");
        JSONObject official=plan(manifest).projects().get(0).rows().stream().filter(row->row.table().equals("project")).findFirst().orElseThrow().body();
        assertEquals(project(manifest).get("notes"),official.get("notes"));
    }
    @Test void unfinishedSlideStubsArePreservedButReadyAndNonemptyMissingTreeAreRejected() throws Exception {
        for(String status:List.of("pending","generating","failed")) {
            JSONObject manifest=manifest();
            JSONObject slide=JSONObject.of("id",UUID.randomUUID().toString(),"status",status,"layout_mode","flex","layout_id","bullets","blocks",new JSONArray(),"layout_tree",null,"position",0,"error","原失败原因","job_id","旧任务");
            project(manifest).getJSONArray("slides").add(slide);
            JSONObject official=plan(manifest).projects().get(0).rows().stream().filter(row->row.table().equals("slide")).findFirst().orElseThrow().body();
            assertEquals("generating".equals(status)?"pending":status,official.getString("status"));
            assertEquals("原失败原因",official.getString("error"));
            assertTrue(official.containsKey("layout_tree")); assertNull(official.get("layout_tree"));
            assertFalse(official.containsKey("job_id"));
            slide.put("status","ready");
            assertThrows(AipptException.class,()->plan(manifest));
            slide.put("status",status);slide.put("blocks",JSONArray.of(AipptRules.defaultBlock("text")));
            assertThrows(AipptException.class,()->plan(manifest));
        }
    }
    @Test void failedAndGeneratingOutlineStatesAndAudienceNullReachActualOfficialRows() throws Exception {
        try(Connection connection=DriverManager.getConnection("jdbc:h2:mem:import-compat-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try(Statement ddl=connection.createStatement()) {
                ddl.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.aippt.migration.AipptImportCompatibilityTest.utc'");
                ddl.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,dept_id BIGINT,del_flag VARCHAR(1))");
                ddl.execute("INSERT INTO sys_user VALUES(7,3,'0')");
                ddl.execute("CREATE TABLE aippt_dispatch_gate(id INT PRIMARY KEY)"); ddl.execute("INSERT INTO aippt_dispatch_gate VALUES(1)");
                for(String table:List.of("project","source","source_section","outline","slide","asset","audit"))ddl.execute("CREATE TABLE aippt_"+table+"(id VARCHAR(36) PRIMARY KEY,project_id VARCHAR(36),owner_user_id BIGINT,status VARCHAR(32),revision BIGINT,body VARCHAR(1000000),created_at TIMESTAMP,updated_at TIMESTAMP)");
            }
            for(String status:List.of("failed","generating","draft")) {
                JSONObject manifest=manifest();
                String outlineId=UUID.randomUUID().toString();
                project(manifest).put("outline",JSONObject.of("id",outlineId,"status",status,"pages",new JSONArray(),"error","旧算法中断原因","job_id","旧任务"));
                var plan=plan(manifest);
                AipptLegacyImportCli.apply(connection,new LocalAipptStorage(temp.resolve("objects").toString()),plan,plan.projects().get(0),temp.resolve("stages.jsonl"));
                try(PreparedStatement query=connection.prepareStatement("SELECT body FROM aippt_outline WHERE id=?")) {
                    query.setString(1,outlineId);
                    try(ResultSet row=query.executeQuery()) {
                        assertTrue(row.next());JSONObject official=JSON.parseObject(row.getString(1));
                        assertEquals("failed".equals(status)?"failed":"draft",official.getString("status"));
                        assertEquals("旧算法中断原因",official.getString("error"));
                        assertFalse(official.containsKey("job_id"));
                        if("generating".equals(status))assertEquals(status,official.getString("legacy_status"));
                    }
                }
                try(PreparedStatement query=connection.prepareStatement("SELECT body FROM aippt_project WHERE id=?")) {
                    query.setString(1,project(manifest).getString("id"));
                    try(ResultSet row=query.executeQuery()) { assertTrue(row.next());JSONObject official=JSON.parseObject(row.getString(1));assertTrue(official.containsKey("audience"));assertNull(official.get("audience"));assertEquals(40,official.getIntValue("page_count")); }
                }
            }
        }
    }
    @Test void checkpointDistinguishesAbsentAndNullAndMandatoryNullIsRejected() throws Exception {
        JSONObject manifest=manifest();
        project(manifest).remove("audience");
        String absent=plan(manifest).projects().get(0).checkpoint();
        project(manifest).put("audience",null);
        assertNotEquals(absent,plan(manifest).projects().get(0).checkpoint());
        for(String field:List.of("title","tone","content_density","layout_mode","page_count")) {
            JSONObject invalid=com.ruoyi.aippt.domain.AipptRules.copy(manifest);
            project(invalid).put(field,null);
            assertThrows(AipptException.class,()->plan(invalid),field);
        }
    }
}
