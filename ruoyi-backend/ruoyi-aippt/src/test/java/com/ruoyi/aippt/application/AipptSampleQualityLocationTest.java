package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.aippt.domain.AipptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 H2/MyBatis 样例任务与缓存；报告为明确合成输入，不调用 Provider 或 HTTP。 */
class AipptSampleQualityLocationTest {
    @Test void queuedResponseMapsEveryPrivateUuidToTheSameCatalogSlide() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject response = f.service.sampleIssues(f.owner, "ivory");
        JSONObject task = f.db.get("task", response.getString("jobId"));
        JSONObject project = f.db.get("project", task.getString("project_id"));
        assertMapping(f, response, project);
        assertEquals(f.catalog.version(), project.getString("catalog_version"));
        assertFalse(task.containsKey("sample_slide_ids"));
        assertFalse(task.getJSONObject("payload").containsKey("sample_slide_ids"));
        assertFalse(task.getJSONObject("payload").getJSONObject("snapshot").containsKey("sample_slide_ids"));
        capture("sample-quality-queued.json", response);

        JSONObject other = f.service.sampleIssues(43, "ivory");
        Set<String> ownIds = response.getJSONObject("sample_slide_ids").keySet();
        Set<String> otherIds = other.getJSONObject("sample_slide_ids").keySet();
        assertTrue(java.util.Collections.disjoint(ownIds, otherIds));
        assertEquals(new HashSet<>(response.getJSONObject("sample_slide_ids").values()),
                new HashSet<>(other.getJSONObject("sample_slide_ids").values()));
        assertMapping(f, other, f.db.get("project", f.db.get("task", other.getString("jobId")).getString("project_id")));
    }

    @Test void cachedResponseLocatesIssuesWithoutChangingTheOfficialReport() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject queued = f.service.sampleIssues(f.owner, "ivory");
        JSONObject task = f.db.get("task", queued.getString("jobId"));
        JSONObject project = f.db.get("project", task.getString("project_id"));
        String official = storedSlides(f, project.getString("id")).get(0).getString("id");
        JSONObject issue = JSONObject.of("code", "sample-warning", "severity", "warning", "slide_id", official,
                "slot_id", "title", "message", "合成样例告警");
        JSONObject output = JSONObject.of("issues", JSONArray.of(issue), "can_export", true,
                "fonts_available", false, "font_manifest_hash", "unavailable", "font_mode", "estimate");
        TaskLeaseService leases = f.leases();
        JSONObject lease = leases.claim(JSONObject.of("workerId", "isolated-sample-test", "catalogVersion", f.catalog.version()));
        assertEquals(task.getString("id"), lease.getString("taskId"));
        leases.complete(task.getString("id"), JSONObject.of("attempt", lease.get("attempt"), "leaseToken", lease.get("leaseToken"),
                "fencingToken", lease.get("fencingToken"), "output", output, "assets", new JSONArray()));

        JSONObject cached = f.service.sampleIssues(f.owner, "ivory");
        assertMapping(f, cached, project);
        assertEquals(official, cached.getJSONArray("issues").getJSONObject(0).getString("slide_id"));
        assertEquals("s01", cached.getJSONObject("sample_slide_ids").getString(official));
        assertFalse(f.db.get("project", project.getString("id")).getJSONObject("quality").containsKey("sample_slide_ids"));
        assertFalse(f.db.get("task", task.getString("id")).getJSONObject("output").containsKey("sample_slide_ids"));
        assertFalse(f.service.quality(project.getString("id"), f.owner).containsKey("sample_slide_ids"));
        capture("sample-quality-cached.json", cached);
        cached.getJSONObject("sample_slide_ids").put(official, "wrong-local-value");
        cached.getJSONArray("issues").getJSONObject(0).put("slide_id", "wrong-local-value");
        JSONObject reread = f.service.sampleIssues(f.owner, "ivory");
        assertEquals("s01", reread.getJSONObject("sample_slide_ids").getString(official));
        assertEquals(official, reread.getJSONArray("issues").getJSONObject(0).getString("slide_id"));
        JSONObject sampleExport = f.service.sampleExport(f.owner, "sample-export", JSONObject.of("theme_id", "ivory", "acknowledge_warnings", true));
        assertEquals("queued", sampleExport.getString("status"));
        assertEquals(project.getString("id"), sampleExport.getString("project_id"));
        AipptException normalExport = assertThrows(AipptException.class, () -> f.service.export(project.getString("id"), f.owner,
                "ordinary-export", JSONObject.of("deck_revision", 1, "theme_revision", 1, "acknowledge_warnings", true)));
        assertEquals("SAMPLE_READ_ONLY", normalExport.errorCode());
    }

    @Test void sampleTaskProjectIdCannotBeUsedToEditTheCatalogBackedPrivateCopy() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject response = f.service.sampleIssues(f.owner, "ivory");
        String project = f.db.get("task", response.getString("jobId")).getString("project_id");
        JSONObject original = storedSlides(f, project).get(0);
        JSONObject block = original.getJSONArray("blocks").getJSONObject(0);
        JSONObject command = com.ruoyi.aippt.domain.AipptRules.copy(block);
        command.put("revision", original.getLongValue("revision"));
        command.put("text", "不应覆盖原目录样例");
        AipptException edit = assertThrows(AipptException.class, () -> f.service.edit(project, original.getString("id"), f.owner,
                "sample-edit", "content", block.getString("id"), command));
        assertEquals("SAMPLE_READ_ONLY", edit.errorCode());
        assertEquals(original, f.db.get("slide", original.getString("id")));
        AipptException theme = assertThrows(AipptException.class, () -> f.service.theme(project, f.owner, "sample-theme",
                JSONObject.of("theme_revision", 1, "theme_id", "midnight")));
        assertEquals("SAMPLE_READ_ONLY", theme.errorCode());
        assertEquals("ivory", f.db.get("project", project).getString("theme_id"));
        assertEquals(1, f.db.children("task", project).size());
        assertMapping(f, f.service.sampleIssues(f.owner, "ivory"), f.db.get("project", project));
    }

    @Test void missingOrDifferentCatalogProvenanceIsRejectedWithoutEnqueueing() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject response = f.service.sampleIssues(f.owner, "ivory");
        JSONObject task = f.db.get("task", response.getString("jobId"));
        JSONObject project = f.db.get("project", task.getString("project_id"));
        for (String version : new String[]{null, "another-version"}) {
            if (version == null) project.remove("catalog_version");
            else project.put("catalog_version", version);
            f.db.save("project", project, project.getLongValue("revision"));
            AipptException error = assertThrows(AipptException.class, () -> f.service.sampleIssues(f.owner, "ivory"));
            assertEquals("SAMPLE_CATALOG_MISMATCH", error.errorCode());
            assertEquals(1, f.db.children("task", project.getString("id")).size());
        }
    }

    @Test void cachedQualityCannotAuthorizeAnExportWithUnknownCatalogProvenance() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject queued = f.service.sampleIssues(f.owner, "ivory");
        JSONObject task = f.db.get("task", queued.getString("jobId"));
        TaskLeaseService leases = f.leases();
        JSONObject lease = leases.claim(JSONObject.of("workerId", "isolated-sample-test", "catalogVersion", f.catalog.version()));
        leases.complete(task.getString("id"), JSONObject.of("attempt", lease.get("attempt"), "leaseToken", lease.get("leaseToken"),
                "fencingToken", lease.get("fencingToken"), "output", JSONObject.of("issues", new JSONArray(), "can_export", true,
                        "fonts_available", false, "font_manifest_hash", "unavailable", "font_mode", "estimate"), "assets", new JSONArray()));
        JSONObject project = f.db.get("project", task.getString("project_id"));
        assertTrue(project.getJSONObject("quality").getBooleanValue("can_export"));
        for (String version : new String[]{null, "another-version"}) {
            project.put("catalog_version", version);
            f.db.save("project", project, project.getLongValue("revision"));
            AipptException error = assertThrows(AipptException.class, () -> f.service.sampleExport(f.owner, "unverified-export",
                    JSONObject.of("theme_id", "ivory", "acknowledge_warnings", true)));
            assertEquals("SAMPLE_CATALOG_MISMATCH", error.errorCode());
            assertEquals(1, f.db.children("task", project.getString("id")).size());
            assertTrue(f.db.children("export", project.getString("id")).isEmpty());
        }
        project.put("catalog_version", f.catalog.version());
        f.db.save("project", project, project.getLongValue("revision"));
        JSONObject slide = storedSlides(f, project.getString("id")).get(1);
        slide.put("position", 1);
        f.db.save("slide", slide, slide.getLongValue("revision"));
        assertEquals("SAMPLE_MAPPING_INVALID", assertThrows(AipptException.class, () -> f.service.sampleExport(f.owner,
                "unverified-export", JSONObject.of("theme_id", "ivory"))).errorCode());
        assertTrue(f.db.children("export", project.getString("id")).isEmpty());
    }

    @Test void DuplicateMissingOrFractionalPositionsCannotProduceAGuessedMap() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject response = f.service.sampleIssues(f.owner, "ivory");
        String project = f.db.get("task", response.getString("jobId")).getString("project_id");
        JSONObject slide = storedSlides(f, project).get(1);
        for (Object position : new Object[]{1, null, 2.5}) {
            slide.put("position", position);
            f.db.save("slide", slide, slide.getLongValue("revision"));
            AipptException error = assertThrows(AipptException.class, () -> f.service.sampleIssues(f.owner, "ivory"));
            assertEquals("SAMPLE_MAPPING_INVALID", error.errorCode());
            assertEquals(1, f.db.children("task", project).size());
        }
    }

    private static void assertMapping(AipptPersistenceFixture f, JSONObject response, JSONObject project) {
        assertEquals(f.catalog.version(), response.getString("catalog_version"));
        JSONObject map = response.getJSONObject("sample_slide_ids");
        assertNotNull(map, "样例异步响应必须给出正式 UUID 到原目录页 ID 的明确关系");
        JSONArray catalogSlides = f.catalog.sample().getJSONArray("slides");
        assertEquals(catalogSlides.size(), map.size());
        assertTrue(map.size() >= 1 && map.size() <= 40);
        for (JSONObject slide : storedSlides(f, project.getString("id"))) {
            assertEquals(catalogSlides.getJSONObject(slide.getIntValue("position") - 1).getString("id"), map.getString(slide.getString("id")));
            assertEquals(project.getString("id"), slide.getString("project_id"));
        }
    }

    private static java.util.List<JSONObject> storedSlides(AipptPersistenceFixture f, String project) {
        return f.db.children("slide", project).stream().sorted(java.util.Comparator.comparingInt(s -> s.getIntValue("position"))).toList();
    }

    private static void capture(String name, JSONObject response) throws Exception {
        String directory = System.getProperty("aippt.sample.evidence.dir");
        if (directory == null || directory.isBlank()) return;
        Path target = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(target);
        Files.writeString(target.resolve(name), response.toJSONString(JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
    }
}
