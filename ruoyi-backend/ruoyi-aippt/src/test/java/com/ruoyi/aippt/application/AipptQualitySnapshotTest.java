package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.aippt.controller.AipptExceptionHandler;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.domain.AipptRules;
import com.ruoyi.aippt.infrastructure.storage.AipptStoragePort;
import com.ruoyi.aippt.infrastructure.storage.LocalAipptStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 MyBatis/H2 快照及中心错误信封；存储仅使用禁止写入的隔离适配器。 */
class AipptQualitySnapshotTest {
    @TempDir Path temp;

    @Test void qualitySnapshotKeepsReferencedHeadingNumbersAndBodyImmutable() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject outline = f.outline("confirmed");
        JSONObject source = f.db.children("source", f.project).get(0);
        JSONObject section = source.getJSONArray("sections").getJSONObject(0);
        section.put("heading", "收入增长18%");
        section.put("text", "经营情况稳健");
        f.db.save("source", source, source.getLongValue("revision"));
        outline.put("input_signature", AipptRules.fingerprint(f.project(), java.util.List.of(source)));
        f.db.save("outline", outline, outline.getLongValue("revision"));

        JSONObject kpi = AipptRules.defaultBlock("kpi");
        kpi.put("value", "18%");
        kpi.put("label", "收入增长");
        JSONObject slide = f.slide(JSONArray.of(kpi));
        slide.put("outline_page_id", outline.getJSONArray("pages").getJSONObject(0).getString("id"));
        f.db.save("slide", slide, slide.getLongValue("revision"));

        JSONObject accepted = f.service.quality(f.project, f.owner);
        JSONObject task = f.db.get("task", accepted.getString("jobId"));
        JSONObject snapshot = task.getJSONObject("payload").getJSONObject("snapshot");
        JSONObject stored = f.db.get("snapshot", snapshot.getString("id"));
        String expected = "收入增长18%\n经营情况稳健";
        assertEquals("quality", task.getString("type"));
        assertFalse(section.getString("text").contains("18"));
        assertEquals(expected, snapshot.getJSONObject("slide_sources").getString(slide.getString("id")));
        assertEquals(expected, stored.getJSONObject("slide_sources").getString(slide.getString("id")));
        assertEquals("content", snapshot.getJSONObject("slide_roles").getString(slide.getString("id")));
        assertEquals("18%", snapshot.getJSONArray("slides").getJSONObject(0).getJSONArray("blocks").getJSONObject(0).getString("value"));
        capture("quality-task.json", task);

        section.put("heading", "后来修改的标题");
        section.put("text", "后来修改的正文");
        f.db.save("source", source, source.getLongValue("revision"));
        JSONObject reread = f.db.get("task", task.getString("id")).getJSONObject("payload").getJSONObject("snapshot");
        assertEquals(expected, reread.getJSONObject("slide_sources").getString(slide.getString("id")));
        assertEquals(expected, f.db.get("snapshot", snapshot.getString("id")).getJSONObject("slide_sources").getString(slide.getString("id")));
    }

    @Test void actualMissingStorageReadProducesTheCenter503Envelope() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.props.setStoragePath(temp.resolve("private-objects").toString());
        JSONObject asset = f.service.record(f.project, f.owner, "ready");
        asset.putAll(JSONObject.of("role", "image", "mime", "image/png", "object_key", "isolated/missing-image", "sha256", "a".repeat(64)));
        f.db.insert("asset", asset);
        AipptStoragePort missing = new AipptStoragePort() {
            @Override public void put(String key, byte[] bytes, String mime) { fail("本检查不能写入对象"); }
            @Override public byte[] get(String key) throws IOException { throw new IOException("isolated missing object"); }
            @Override public void delete(String key) { fail("本检查不能删除对象"); }
        };
        AssetService assets = new AssetService(f.db, f.service, f.leases(), f.tx, f.props, missing);
        AipptException error = assertThrows(AipptException.class, () -> assets.read(asset.getString("id"), f.owner));
        var response = new AipptExceptionHandler().business(error);
        assertEquals(503, response.getStatusCode().value());
        assertEquals("ASSET_MISSING", response.getBody().get("errorCode"));
        assertEquals(true, response.getBody().get("retryable"));
        assertFalse(response.getBody().toString().contains("isolated missing object"));
        JSONObject captured = JSONObject.of("status", response.getStatusCode().value(), "body", response.getBody());
        capture("asset-missing-response.json", captured);
    }

    @Test void actualPrivateFileWithWrongHashProducesTheCorrupt503Envelope() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.props.setStoragePath(temp.resolve("private-objects").toString());
        LocalAipptStorage storage = new LocalAipptStorage(f.props.getStoragePath());
        JSONObject asset = f.service.record(f.project, f.owner, "ready");
        String key = f.project + "/" + asset.getString("id");
        storage.put(key, "isolated bytes with wrong recorded hash".getBytes(StandardCharsets.UTF_8), "image/png");
        asset.putAll(JSONObject.of("role", "image", "mime", "image/png", "object_key", key, "sha256", "a".repeat(64)));
        f.db.insert("asset", asset);
        AssetService assets = new AssetService(f.db, f.service, f.leases(), f.tx, f.props, storage);
        AipptException error = assertThrows(AipptException.class, () -> assets.read(asset.getString("id"), f.owner));
        var response = new AipptExceptionHandler().business(error);
        assertEquals(503, response.getStatusCode().value());
        assertEquals("ASSET_CORRUPT", response.getBody().get("errorCode"));
        assertEquals("ready", f.db.get("asset", asset.getString("id")).getString("status"));
        capture("asset-corrupt-response.json", JSONObject.of("status", response.getStatusCode().value(), "body", response.getBody()));
    }

    private static void capture(String filename, JSONObject actual) throws IOException {
        String directory = System.getProperty("aippt.evidence.dir");
        if (directory == null || directory.isBlank()) return;
        Path target = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(target);
        Files.writeString(target.resolve(filename), actual.toJSONString(JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
    }
}
