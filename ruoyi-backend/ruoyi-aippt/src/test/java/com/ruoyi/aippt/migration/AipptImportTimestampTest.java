package com.ruoyi.aippt.migration;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.application.CatalogService;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.infrastructure.storage.LocalAipptStorage;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 旧日期须到达正式正文及排序列；只使用独立 H2 和临时文件。 */
public class AipptImportTimestampTest {
    @TempDir Path temp;
    final CatalogService catalog=new CatalogService(new AipptProperties());
    public static Timestamp utc(int precision) { return Timestamp.from(Instant.now()); }
    private JSONObject manifest() {
        String batch=UUID.randomUUID().toString();
        JSONObject project=JSONObject.of("id",UUID.randomUUID().toString(),"owner_id",7,"legacy_owner_id","legacy","import_batch",batch,"title","旧日期核对","page_count",5,"theme_id","ivory","sources",new JSONArray(),"slides",new JSONArray(),"outline",new JSONObject(),"created_at","2024-01-02T03:04:05.123456+08:00","updated_at","2024-05-06T07:08:09.987654+08:00");
        project.getJSONArray("sources").add(JSONObject.of("id",UUID.randomUUID().toString(),"kind","text","sections",new JSONArray(),"created_at","2024-02-03T04:05:06.111222Z"));
        project.put("outline",JSONObject.of("id",UUID.randomUUID().toString(),"status","failed","pages",new JSONArray(),"created_at","2024-03-04T05:06:07Z","updated_at","2024-03-05T05:06:07Z"));
        project.getJSONArray("slides").add(JSONObject.of("id",UUID.randomUUID().toString(),"position",1,"status","pending","layout_mode","flex","layout_id","bullets","blocks",new JSONArray(),"layout_tree",null,"created_at","2024-04-05T06:07:08-04:00","updated_at","2024-04-06T06:07:08-04:00"));
        return JSONObject.of("schema_version","aippt-import-1","complete",true,"source_sha256","0".repeat(64),"import_batch",batch,"catalog_version",catalog.version(),"projects",JSONArray.of(JSONObject.of("project",project,"assets",new JSONArray(),"audit",new JSONObject())));
    }
    private AipptLegacyImportCli.Plan prepare(JSONObject manifest) throws Exception { return AipptLegacyImportCli.prepare(manifest,temp,catalog,JSONObject.of("legacy",7)); }
    private JSONObject project(JSONObject manifest) { return manifest.getJSONArray("projects").getJSONObject(0).getJSONObject("project"); }
    private Map<String,JSONObject> originals(JSONObject manifest) {
        JSONObject project=project(manifest);
        return Map.of("project",project,"source",project.getJSONArray("sources").getJSONObject(0),"outline",project.getJSONObject("outline"),"slide",project.getJSONArray("slides").getJSONObject(0));
    }
    @Test void oldBusinessDatesReachOfficialBodyAndSqlColumnsAndReplayKeepsThem() throws Exception {
        JSONObject manifest=manifest(); var plan=prepare(manifest); var expected=originals(manifest);
        try(Connection db=DriverManager.getConnection("jdbc:h2:mem:import-dates-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try(Statement ddl=db.createStatement()) {
                ddl.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.aippt.migration.AipptImportTimestampTest.utc'");
                ddl.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,dept_id BIGINT,del_flag VARCHAR(1))"); ddl.execute("INSERT INTO sys_user VALUES(7,3,'0')");
                ddl.execute("CREATE TABLE aippt_dispatch_gate(id INT PRIMARY KEY)"); ddl.execute("INSERT INTO aippt_dispatch_gate VALUES(1)");
                for(String table:List.of("project","source","source_section","outline","slide","asset","audit"))ddl.execute("CREATE TABLE aippt_"+table+"(id VARCHAR(36) PRIMARY KEY,project_id VARCHAR(36),owner_user_id BIGINT,status VARCHAR(32),revision BIGINT,body VARCHAR(1000000),created_at TIMESTAMP(6),updated_at TIMESTAMP(6))");
            }
            var storage=new LocalAipptStorage(temp.resolve("storage").toString());
            for(int replay=0;replay<2;replay++) {
                var current=replay==0?plan:prepare(manifest);
                AipptLegacyImportCli.apply(db,storage,current,current.projects().get(0),temp.resolve("journal.jsonl"));
                for(var entry:expected.entrySet())try(Statement query=db.createStatement();ResultSet row=query.executeQuery("SELECT body,created_at,updated_at FROM aippt_"+entry.getKey())) {
                    assertTrue(row.next()); JSONObject body=JSON.parseObject(row.getString(1));
                    for(String field:List.of("created_at","updated_at"))if(entry.getValue().containsKey(field)) {
                        String old=entry.getValue().getString(field);
                        assertEquals(old,body.getString(field),entry.getKey()+"."+field);
                        assertEquals(OffsetDateTime.parse(old).toInstant(),row.getTimestamp(field,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant(),entry.getKey()+" SQL "+field);
                    }
                    assertTrue(Instant.parse(body.getString("imported_at")).isAfter(Instant.parse("2026-01-01T00:00:00Z")));
                    assertFalse(row.next());
                }
            }
        }
    }
    @Test void invalidOrAmbiguousPresentDatesAreRejectedWithoutInventingTimezone() throws Exception {
        for(Object invalid:Arrays.asList(null,true,123,"2024-01-02T03:04:05","not-a-date","0001-01-01T00:00:00Z"))for(String table:List.of("project","source","outline","slide")) {
            JSONObject manifest=manifest(); originals(manifest).get(table).put("created_at",invalid);
            assertThrows(AipptException.class,()->prepare(manifest),table+" "+invalid);
        }
    }
    @Test void missingOptionalDatesRemainCompatibleAndNewAuditUsesImportTime() throws Exception {
        JSONObject manifest=manifest();
        for(JSONObject row:originals(manifest).values()) { row.remove("created_at"); row.remove("updated_at"); }
        var plan=prepare(manifest);
        for(var row:plan.projects().get(0).rows()) {
            assertNotNull(row.body().getString("created_at")); assertNotNull(row.body().getString("updated_at")); assertNotNull(row.body().getString("imported_at"));
        }
    }
    @Test void sqlOrderingUsesOldProjectAndSourceDatesRegardlessOfImportOrder() throws Exception {
        JSONObject manifest=manifest(),newer=project(manifest),older=project(manifest());
        older.put("import_batch",manifest.getString("import_batch"));
        older.put("updated_at","2023-01-01T00:00:00Z");
        manifest.getJSONArray("projects").add(JSONObject.of("project",older,"assets",new JSONArray(),"audit",new JSONObject()));
        JSONObject firstSource=newer.getJSONArray("sources").getJSONObject(0),earlySource=JSON.parseObject(firstSource.toJSONString());
        earlySource.put("id",UUID.randomUUID().toString()); earlySource.put("created_at","2022-01-01T00:00:00Z");
        newer.getJSONArray("sources").add(earlySource);
        var plan=prepare(manifest);
        try(Connection db=DriverManager.getConnection("jdbc:h2:mem:import-order-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try(Statement ddl=db.createStatement()) {
                ddl.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.aippt.migration.AipptImportTimestampTest.utc'");
                ddl.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,dept_id BIGINT,del_flag VARCHAR(1))"); ddl.execute("INSERT INTO sys_user VALUES(7,3,'0')");
                ddl.execute("CREATE TABLE aippt_dispatch_gate(id INT PRIMARY KEY)"); ddl.execute("INSERT INTO aippt_dispatch_gate VALUES(1)");
                for(String table:List.of("project","source","source_section","outline","slide","asset","audit"))ddl.execute("CREATE TABLE aippt_"+table+"(id VARCHAR(36) PRIMARY KEY,project_id VARCHAR(36),owner_user_id BIGINT,status VARCHAR(32),revision BIGINT,body VARCHAR(1000000),created_at TIMESTAMP(3),updated_at TIMESTAMP(3))");
            }
            for(var project:plan.projects())AipptLegacyImportCli.apply(db,new LocalAipptStorage(temp.resolve("ordering-storage").toString()),plan,project,temp.resolve("ordering-journal.jsonl"));
            try(Statement query=db.createStatement();ResultSet row=query.executeQuery("SELECT id FROM aippt_project ORDER BY updated_at DESC,id")) {
                assertTrue(row.next()); assertEquals(newer.getString("id"),row.getString(1)); assertTrue(row.next()); assertEquals(older.getString("id"),row.getString(1)); assertFalse(row.next());
            }
            try(PreparedStatement query=db.prepareStatement("SELECT id FROM aippt_source WHERE project_id=? ORDER BY created_at,id")) {
                query.setString(1,newer.getString("id"));
                try(ResultSet row=query.executeQuery()) { assertTrue(row.next()); assertEquals(earlySource.getString("id"),row.getString(1)); assertTrue(row.next()); assertEquals(firstSource.getString("id"),row.getString(1)); assertFalse(row.next()); }
            }
        }
    }
}
