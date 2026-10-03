package com.paicli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** SQLite/WAL和JSONL全部由本测试临时目录生成，不接触真实历史或中心数据库。 */
class LegacyBundleExporterTest {
    @TempDir Path temporary;
    private final ObjectMapper json=new ObjectMapper();
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"),ZoneOffset.UTC);
    @Test void originalProjectAndLocalOverrideRulesAreBothPreservedAsUnverifiedRecords()throws Exception {
        Path source=Files.createDirectory(temporary.resolve("project-rules"));write(source,"PAI.md","project original rules\n");write(source,"PAI.local.md","local override rules\n");
        var bundle=json.readTree(LegacyBundleExporter.export(source,temporary.resolve("project-rules.json"),clock).output().toFile());var rules=records(bundle,"POLICY");assertEquals(2,rules.size());
        var local=rules.stream().filter(row->row.path("payload").path("variant").asText().equals("LOCAL_OVERRIDE")).findFirst().orElseThrow();
        assertEquals("PAI.local.md",local.path("payload").path("relativePath").asText());assertEquals("local override rules\n",local.path("payload").path("content").asText());assertEquals("IMPORTED_UNVERIFIED",local.path("payload").path("status").asText());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.resolve("PAI.local.md")))),local.path("payload").path("sourceSha256").asText());assertEquals("project original rules\n",Files.readString(source.resolve("PAI.md")));assertEquals("local override rules\n",Files.readString(source.resolve("PAI.local.md")));
    }
    @Test void ledgerEventsAndOriginalRuntimeTimesArePreservedWithoutBecomingCurrentApprovals() throws Exception {
        Path source=Files.createDirectory(temporary.resolve("complete-ledger"));
        write(source,"history/raw/test.jsonl","{\"schemaVersion\":1,\"sessionId\":\"test\",\"sequence\":1,\"timestamp\":1700000000000,\"event\":\"compaction\",\"mode\":\"react\",\"actor\":\"agent\",\"source\":\"automatic\",\"message\":null,\"metadata\":{\"beforeTokens\":100,\"afterTokens\":20}}\n{\"schemaVersion\":1,\"sessionId\":\"test\",\"sequence\":2,\"timestamp\":1700000000001,\"event\":\"message\",\"mode\":\"team\",\"actor\":\"reviewer\",\"source\":\"actual-provider\",\"message\":{\"role\":\"assistant\",\"content\":\"原文\"},\"metadata\":{\"stepId\":\"step-1\"}}\n");
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+source.resolve("runtime.db"));Statement sql=connection.createStatement()) {
            sql.execute("CREATE TABLE runtime_tasks(id TEXT PRIMARY KEY,status TEXT,prompt TEXT,created_at TEXT,started_at TEXT,finished_at TEXT,duration_ms INTEGER)");
            sql.execute("INSERT INTO runtime_tasks VALUES('1','completed','original task','2026-09-01T00:00:00Z','2026-09-01T00:00:01Z','2026-09-01T00:00:03Z',2000)");
        }
        var exported=LegacyBundleExporter.export(source,temporary.resolve("complete.json"),clock); JsonNode bundle=json.readTree(exported.output().toFile());
        assertEquals("compaction",records(bundle,"LEDGER_EVENT").get(0).path("payload").path("event").asText());
        assertEquals("automatic",records(bundle,"LEDGER_EVENT").get(0).path("payload").path("_ledger").path("source").asText());
        JsonNode ledger=records(bundle,"MESSAGE").get(0).path("payload").path("_ledger");
        assertEquals("reviewer",ledger.path("actor").asText()); assertEquals("team",ledger.path("mode").asText());
        assertEquals("actual-provider",ledger.path("source").asText()); assertEquals("step-1",ledger.path("metadata").path("stepId").asText());
        assertEquals(1700000000001L,ledger.path("timestamp").asLong()); assertFalse(ledger.has("message"));
        var task=records(bundle,"TASK").get(0).path("payload"); assertEquals("2026-09-01T00:00:01Z",task.path("startedAt").asText()); assertEquals(2000,task.path("durationMs").asInt());
    }
    @Test void completeBundleRemovesCredentialsKeepsOwnerAndPendingMemoryWithoutModifyingSource()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("source"));
        write(source,"config.json","""
            {"providers":{"deepseek":{"apiKey":"fixture-long-credential","baseUrl":"https://provider.example/v1","model":"original-id","models":{"known-id":{"contextWindow":64000,"supportsImages":true}}}},"defaultProvider":"deepseek"}
            """);
        write(source,"mcp.json","""
            {"mcpServers":{"fixture":{"transport":"stdio","command":"fixture","env":{"AUTHORIZATION":"Bearer fixture-env-credential"},"headers":{"X-API-Key":"fixture-header-credential"}}}}
            """);
        write(source,"memory/long_term_memory.json","""
            [{"id":"memory-1","content":"用户明确的偏好","timestamp":"2026-09-01T00:00:00Z","metadata":{"scope":"global","verification_pending":"true"}}]
            """);
        write(source,"history/raw/s.jsonl","""
            {"schemaVersion":1,"sessionId":"old-session","sequence":1,"timestamp":1788220800000,"event":"message","mode":"react","message":{"role":"assistant","content":"fixture-long-credential Bearer fixture-env-credential","parts":[{"type":"image","base64Data":"aW1hZ2UtY29udGVudA=="}]}}
            """);
        write(source,"skills/local/SKILL.md","---\nname: local\n---\n正文只是来源资料\n");
        Map<String,String> before=files(source);Path output=temporary.resolve("bundle.json");var summary=LegacyBundleExporter.export(source,output,clock);JsonNode bundle=json.readTree(output.toFile());
        assertEquals("paicli-legacy-v1",bundle.path("schemaVersion").asText());assertEquals(clock.instant().toString(),bundle.path("exportedAt").asText());assertEquals(before,files(source));assertTrue(summary.sourceFingerprint().matches("[a-f0-9]{64}"));
        String content=Files.readString(output);for(String secret:List.of("fixture-long-credential","fixture-env-credential","fixture-header-credential","base64Data","apiKey","AUTHORIZATION"))assertFalse(content.contains(secret),secret);
        Set<String> kinds=new HashSet<>();bundle.path("records").forEach(row->{kinds.add(row.path("kind").asText());assertEquals("local",row.path("legacyOwnerId").asText());assertTrue(row.path("occurredAt").asText().endsWith("Z"));});
        assertTrue(kinds.containsAll(Set.of("PROVIDER","MODEL","MEMORY","SESSION","MESSAGE","MCP","SKILL")));
        JsonNode memory=records(bundle,"MEMORY").get(0);assertEquals("PENDING",memory.path("payload").path("status").asText());assertEquals("PERSONAL",memory.path("payload").path("scope").asText());
        assertFalse(records(bundle,"SKILL").get(0).path("payload").path("enabled").asBoolean());assertEquals(5,bundle.path("sourceFiles").size());
    }
    @Test void committedWalRowsAreExportedWithoutUncommittedRowsOrTaskRecovery()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("wal-source")), database=source.resolve("tasks.db");
        try(Connection writer=DriverManager.getConnection("jdbc:sqlite:"+database);Statement statement=writer.createStatement()){
            statement.execute("PRAGMA journal_mode=WAL");statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("CREATE TABLE runtime_tasks(id TEXT PRIMARY KEY,status TEXT,prompt TEXT,result TEXT,error TEXT,created_at TEXT)");
            statement.execute("CREATE TABLE runtime_threads(id TEXT PRIMARY KEY,created_at TEXT)");statement.execute("CREATE TABLE runtime_events(id INTEGER PRIMARY KEY,thread_id TEXT,type TEXT,data TEXT,created_at TEXT)");
            statement.execute("INSERT INTO runtime_tasks VALUES('running','running','old input',NULL,NULL,'2026-09-01T00:00:00Z')");statement.execute("INSERT INTO runtime_tasks VALUES('queued','enqueued','queued input',NULL,NULL,'2026-09-01T00:00:00Z')");
            statement.execute("INSERT INTO runtime_threads VALUES('thread-old','2026-09-01T00:00:00Z')");statement.execute("INSERT INTO runtime_events VALUES(1,'thread-old','message.delta','{\"content\":\"已提交的WAL结果\"}','2026-09-01T00:00:00Z')");
            writer.setAutoCommit(false);statement.execute("INSERT INTO runtime_tasks VALUES('uncommitted','running','not committed',NULL,NULL,'2026-09-01T00:00:00Z')");
            assertTrue(Files.size(source.resolve("tasks.db-wal"))>0);Path shm=source.resolve("tasks.db-shm");long shmSize=Files.size(shm);var shmModified=Files.getLastModifiedTime(shm);Map<String,String> before=files(source);Path output=temporary.resolve("wal-bundle.json");LegacyBundleExporter.export(source,output,clock);assertEquals(before,files(source));assertEquals(shmSize,Files.size(shm));assertEquals(shmModified,Files.getLastModifiedTime(shm));
            JsonNode bundle=json.readTree(output.toFile());List<JsonNode> tasks=records(bundle,"TASK");assertEquals(2,tasks.size());assertTrue(tasks.stream().anyMatch(row->row.path("payload").path("status").asText().equals("RECONCILE_REQUIRED")));assertTrue(tasks.stream().anyMatch(row->row.path("payload").path("status").asText().equals("PAUSED_IMPORTED")));
            assertTrue(Files.readString(output).contains("已提交的WAL结果"));assertFalse(Files.readString(output).contains("uncommitted"));assertFalse(Files.readString(output).contains("outbox"));
            try(ResultSet row=statement.executeQuery("SELECT status FROM runtime_tasks WHERE id='running'")){assertTrue(row.next());assertEquals("running",row.getString(1));}writer.rollback();
        }
    }
    @Test void actualMessageImagesKeepDecodedContentHashForExplicitCenterAttachmentMapping() throws Exception {
        Path source=Files.createDirectory(temporary.resolve("image-ledger"));
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        String encoded=Base64.getEncoder().encodeToString(bytes.toByteArray());String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        var entry=json.createObjectNode();entry.put("schemaVersion",1);entry.put("sessionId","old");entry.put("sequence",1);entry.put("timestamp",1700000000000L);
        var message=entry.putObject("message");message.put("role","user");message.put("content","原图片说明");
        var image=message.putArray("contentParts").addObject();image.put("type","image_base64");image.put("imageBase64",encoded);image.put("mimeType","image/png");image.put("name","original.png");
        write(source,"history/raw/image.jsonl",entry.toString()+"\n");Path output=temporary.resolve("images.json");Map<String,String> before=files(source);
        LegacyBundleExporter.export(source,output,clock);JsonNode part=records(json.readTree(output.toFile()),"MESSAGE").get(0).path("payload").path("contentParts").get(0);
        assertEquals("image_omitted",part.path("type").asText());assertEquals(sha,part.path("legacyAttachmentSha256").asText());assertEquals(bytes.size(),part.path("size").asInt());assertEquals("original.png",part.path("name").asText());
        assertFalse(Files.readString(output).contains(encoded));assertFalse(part.has("imageBase64"));assertEquals(before,files(source));
        image.put("imageBase64","%%%invalid%%%");write(source,"history/raw/image.jsonl",entry.toString()+"\n");Path invalidOutput=temporary.resolve("invalid-images.json");
        assertThrows(IOException.class,()->LegacyBundleExporter.export(source,invalidOutput,clock));assertFalse(Files.exists(invalidOutput));
    }
    @Test void promptOverridesRequireExplicitScopeAndPreserveRelativePathAndActualSourceHash() throws Exception {
        Path source=Files.createDirectory(temporary.resolve("prompt-source"));String body="项目自己的 planner 正文\n";write(source,"prompts/modes/planner.md",body);
        Map<String,String> before=files(source);Path missing=temporary.resolve("prompt-unspecified.json");
        assertThrows(IOException.class,()->LegacyBundleExporter.export(source,missing,clock));assertFalse(Files.exists(missing));
        Path output=temporary.resolve("prompt-project.json");LegacyBundleExporter.export(source,output,clock,"PROJECT");
        JsonNode prompt=records(json.readTree(output.toFile()),"PROMPT").get(0).path("payload");assertEquals("modes/planner.md",prompt.path("relativePath").asText());assertEquals("PROJECT",prompt.path("scope").asText());assertEquals(body,prompt.path("content").asText());
        assertEquals(before.get(Path.of("prompts","modes","planner.md").toString()),prompt.path("sourceSha256").asText());assertFalse(prompt.path("enabled").asBoolean());assertEquals(before,files(source));
        assertEquals(0,LegacyBundleExporter.run(new String[]{"export-legacy","--source",source.toString(),"--output",temporary.resolve("prompt-personal.json").toString(),"--prompt-scope","PERSONAL"}));
    }
    @Test void identicalSourcesProduceSameFingerprintAcrossDifferentExportTime()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("stable"));write(source,"memory/long_term_memory.json","[]");
        var first=LegacyBundleExporter.export(source,temporary.resolve("one.json"),clock);var second=LegacyBundleExporter.export(source,temporary.resolve("two.json"),Clock.offset(clock,java.time.Duration.ofDays(1)));
        assertEquals(first.sourceFingerprint(),second.sourceFingerprint());assertNotEquals(json.readTree(first.output().toFile()).path("exportedAt"),json.readTree(second.output().toFile()).path("exportedAt"));
    }
    @Test void sqliteCredentialsAreCollectedBeforeRemovingFieldsAndRedactEarlierAndLaterRows()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("sqlite-secrets")),database=source.resolve("runtime.db");
        try(Connection writer=DriverManager.getConnection("jdbc:sqlite:"+database);Statement statement=writer.createStatement()){
            statement.execute("CREATE TABLE runtime_tasks(id TEXT PRIMARY KEY,status TEXT,prompt TEXT,access_key TEXT,result TEXT,created_at TEXT)");
            statement.execute("CREATE TABLE runtime_threads(id TEXT PRIMARY KEY,bridge_token TEXT,created_at TEXT)");
            statement.execute("CREATE TABLE runtime_events(id INTEGER PRIMARY KEY,thread_id TEXT,type TEXT,data TEXT,created_at TEXT)");
            statement.execute("INSERT INTO runtime_tasks VALUES('task','completed','fixture-sql-row-key fixture-sql-event-key fixture-sql-thread-token fixture-json-column-key','fixture-sql-row-key','{\"client_key\":\"fixture-json-column-key\",\"ordinary\":\"kept\"}','2026-09-01T00:00:00Z')");
            statement.execute("INSERT INTO runtime_threads VALUES('thread','fixture-sql-thread-token','2026-09-01T00:00:00Z')");
            statement.execute("INSERT INTO runtime_events VALUES(1,'thread','connection.result','{\"env\":{\"SERVICE_KEY\":\"fixture-sql-event-key\"}}','2026-09-01T00:00:00Z')");
            statement.execute("INSERT INTO runtime_events VALUES(2,'thread','message.delta','{\"content\":\"fixture-sql-row-key fixture-sql-event-key fixture-sql-thread-token\"}','2026-09-01T00:00:00Z')");
        }
        Map<String,String> before=files(source);Path output=temporary.resolve("sqlite-secrets.json");LegacyBundleExporter.export(source,output,clock);
        String bundle=Files.readString(output);for(String secret:List.of("fixture-sql-row-key","fixture-sql-event-key","fixture-sql-thread-token","fixture-json-column-key","SERVICE_KEY","access_key","bridge_token","client_key"))assertFalse(bundle.contains(secret),secret);
        assertTrue(records(json.readTree(bundle),"TASK").get(0).path("payload").path("result").isTextual());assertTrue(bundle.contains("ordinary"));
        assertTrue(bundle.contains("[已移除敏感内容]"));assertEquals(before,files(source));assertTrue(records(json.readTree(bundle),"MESSAGE").isEmpty());
        List<JsonNode> events=records(json.readTree(bundle),"EVENT");assertEquals(2,events.size());
        assertEquals("message.delta",events.get(1).path("payload").path("type").asText());assertEquals(2,events.get(1).path("payload").path("sequence").asInt());
        assertTrue(events.get(1).path("payload").path("data").has("content"));
    }
    @Test void authenticationArgumentFormsAndEncodedUrlCredentialsNeverReachBundle()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("argument-secrets"));
        write(source,"mcp.json","""
            {"mcpServers":{"fixture":{"transport":"stdio","command":"fixture","args":["--access-key=fixture-inline-key","--keep","-auth_key","fixture-separated-key","--client-key fixture-space-key","--public-argument"],"url":"https://endpoint.example/mcp?%2561pi%255Fkey=fixture-url-key&limit=10"}}}
            """);
        write(source,"history/raw/echo.jsonl","""
            {"schemaVersion":1,"sessionId":"old","sequence":1,"timestamp":1788220800000,"message":{"role":"assistant","content":"fixture-inline-key fixture-separated-key fixture-space-key fixture-url-key"}}
            """);
        Path output=temporary.resolve("argument-secrets.json");LegacyBundleExporter.export(source,output,clock);String content=Files.readString(output);
        for(String secret:List.of("fixture-inline-key","fixture-separated-key","fixture-space-key","fixture-url-key","access-key","auth_key","client-key","%2561pi%255Fkey"))assertFalse(content.contains(secret),secret);
        JsonNode config=records(json.readTree(content),"MCP").get(0).path("payload");assertEquals(List.of("--keep","--public-argument"),json.convertValue(config.path("args"),List.class));assertTrue(config.path("url").asText().contains("limit=10"));
    }
    @Test void invalidLedgerSchemaAndBadTimestampsFailWithoutPublishingPartialBundle()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("broken"));write(source,"history/raw/s.jsonl","{\"schemaVersion\":999,\"sessionId\":\"s\",\"timestamp\":1}");Path output=temporary.resolve("broken.json");
        assertThrows(IOException.class,()->LegacyBundleExporter.export(source,output,clock));assertFalse(Files.exists(output));
        write(source,"history/raw/s.jsonl","{\"schemaVersion\":1,\"sessionId\":\"s\",\"timestamp\":\"bad-time\",\"message\":{\"role\":\"user\",\"content\":\"old\"}}");assertThrows(IOException.class,()->LegacyBundleExporter.export(source,output,clock));assertFalse(Files.exists(output));
    }
    @Test void sourceDestinationAndExistingOutputCannotBeOverwritten()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("protect"));write(source,"memory/long_term_memory.json","[]");
        assertThrows(IOException.class,()->LegacyBundleExporter.export(source,source.resolve("bundle.json"),clock));Path existing=temporary.resolve("existing.json");Files.writeString(existing,"user draft");assertThrows(IOException.class,()->LegacyBundleExporter.export(source,existing,clock));assertEquals("user draft",Files.readString(existing));
    }
    @Test void nonSqliteFileIsNotInitializedAndOversizedSourceIsRejected()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("invalid-db"));write(source,"tasks.db","not sqlite");assertThrows(IOException.class,()->LegacyBundleExporter.export(source,temporary.resolve("invalid-db.json"),clock));assertEquals("not sqlite",Files.readString(source.resolve("tasks.db")));
        Files.delete(source.resolve("tasks.db"));Path huge=source.resolve("memory.json");try(RandomAccessFile file=new RandomAccessFile(huge.toFile(),"rw")){file.setLength(50L*1024*1024+1);}assertThrows(IOException.class,()->LegacyBundleExporter.export(source,temporary.resolve("huge.json"),clock));
    }
    @Test void sourceLinksCannotReadOtherDirectories()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("links")), outside=temporary.resolve("private.json");Files.writeString(outside,"outside fixture");
        try{Files.createSymbolicLink(source.resolve("config.json"),outside);}catch(IOException|UnsupportedOperationException|SecurityException unavailable){Assumptions.assumeTrue(false,"当前fixture账号无法创建链接");}
        assertThrows(IOException.class,()->LegacyBundleExporter.export(source,temporary.resolve("linked.json"),clock));assertEquals("outside fixture",Files.readString(outside));
    }
    @Test void exporterRejectsBundleAboveCentersRecordLimitBeforePublishing()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("record-limit"));StringJoiner entries=new StringJoiner(",","[","]");for(int i=0;i<10001;i++)entries.add("{\"id\":\"m-"+i+"\",\"content\":\"fixture\"}");write(source,"memory/long_term_memory.json",entries.toString());Path output=temporary.resolve("record-limit.json");
        IOException rejected=assertThrows(IOException.class,()->LegacyBundleExporter.export(source,output,clock));assertTrue(rejected.getMessage().contains("一万条"));assertFalse(Files.exists(output));
    }
    @Test void commandNeedsNoCenterEnvironmentAndCannotResumeOldTask()throws Exception{
        Path source=Files.createDirectory(temporary.resolve("cli"));write(source,"memory/long_term_memory.json","[]");Path output=temporary.resolve("cli.json");
        assertEquals(0,LegacyBundleExporter.run(new String[]{"export-legacy","--source",source.toString(),"--output",output.toString()}));assertTrue(Files.exists(output));assertEquals(2,LegacyBundleExporter.run(new String[]{"export-legacy","--source",source.toString()}));
        assertEquals("RECONCILE_REQUIRED",LegacyBundleExporter.taskStatus("WAITING_APPROVAL"));assertEquals("RECONCILE_REQUIRED",LegacyBundleExporter.taskStatus("LEASED"));assertEquals("PAUSED_IMPORTED",LegacyBundleExporter.taskStatus("ENQUEUED"));assertEquals("COMPLETED",LegacyBundleExporter.taskStatus("COMPLETED"));
    }
    private void write(Path source,String name,String value)throws IOException{Path file=source.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,value,StandardCharsets.UTF_8);}
    private static List<JsonNode> records(JsonNode bundle,String kind){List<JsonNode> values=new ArrayList<>();bundle.path("records").forEach(row->{if(kind.equals(row.path("kind").asText()))values.add(row);});return values;}
    private static Map<String,String> files(Path source)throws Exception{Map<String,String> result=new TreeMap<>();try(var stream=Files.walk(source)){for(Path file:stream.filter(Files::isRegularFile).filter(file->!file.getFileName().toString().endsWith("-shm")).toList())result.put(source.relativize(file).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));}return result;}
}
