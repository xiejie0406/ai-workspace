package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.aippt.controller.WorkerTaskController;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.domain.AipptRules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 H2/MyBatis 质量回执、导出排队及失败租约；不连接 Worker 或对象存储。 */
class AipptExportVerificationFailureTest {
    private record ExportAttempt(AipptPersistenceFixture fixture, JSONObject export, JSONObject lease) {}

    private ExportAttempt exportAttempt() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.outline("confirmed");
        f.slide(JSONArray.of(AipptRules.defaultBlock("text")));
        JSONObject queued = f.service.quality(f.project, f.owner);
        JSONObject qualityLease = claim(f, "quality");
        assertEquals(queued.getString("jobId"), qualityLease.getString("taskId"));
        JSONObject receipt = request(qualityLease);
        receipt.put("output", JSONObject.of("font_manifest_hash", "unavailable", "font_mode", "estimate", "can_export", true, "issues", new JSONArray()));
        assertEquals("succeeded", f.leases().complete(qualityLease.getString("taskId"), receipt).getString("status"));
        assertTrue(f.project().getJSONObject("quality").getBooleanValue("can_export"));
        JSONObject exported = f.service.export(f.project, f.owner, "export", JSONObject.of("deck_revision", f.project().getLongValue("deck_revision"), "theme_revision", f.project().getLongValue("theme_revision")));
        JSONObject lease = claim(f, "export");
        assertEquals(exported.getString("job_id"), lease.getString("taskId"));
        return new ExportAttempt(f, exported, lease);
    }

    private JSONObject claim(AipptPersistenceFixture f, String type) {
        return f.leases().claim(JSONObject.of("workerId", "isolated-verifier", "catalogVersion", f.catalog.version(), "types", JSONArray.of(type)));
    }
    private JSONObject request(JSONObject lease) {
        return JSONObject.of("attempt", lease.get("attempt"), "leaseToken", lease.get("leaseToken"), "fencingToken", lease.get("fencingToken"));
    }
    private JSONObject report() {
        JSONObject issue = JSONObject.of("check", "content_integrity", "slide_index", 1, "shape", null, "message", "第1页原生内容回读不符");
        return JSONObject.of("passed", false, "slide_count", 1, "expected_slide_count", 1, "issues", JSONArray.of(issue), "diagnostics_truncated", false, "total_issues", 1);
    }
    private JSONObject failure(JSONObject lease, JSONObject report) {
        JSONObject request = request(lease);
        request.putAll(JSONObject.of("code", "PPTX_VERIFY_FAILED", "message", "PPTX回读未通过，禁止下载", "retryable", false, "verification_failure", report));
        return request;
    }

    @Test void realFailPersistsPageDiagnosticsInTaskAndExportButNotAuditOrEvents() throws Exception {
        ExportAttempt attempt = exportAttempt();
        AipptPersistenceFixture f = attempt.fixture();
        JSONObject expected = report();
        String input = System.getProperty("aippt.verification.input");
        if (input != null && !input.isBlank()) {
            JSONObject workerBody = JSONObject.parseObject(Files.readString(Path.of(input), StandardCharsets.UTF_8));
            assertEquals("PPTX_VERIFY_FAILED", workerBody.getString("code"));
            assertEquals(Boolean.FALSE, workerBody.get("retryable"));
            expected = workerBody.getJSONObject("verification_failure");
            assertNotNull(expected);
            assertEquals(1, expected.getIntValue("expected_slide_count"));
        }
        JSONObject body = failure(attempt.lease(), expected);
        new WorkerTaskController(f.leases(), null).fail(attempt.lease().getString("taskId"), new HashMap<>(body));
        JSONObject task = f.service.task(attempt.lease().getString("taskId"), f.owner);
        JSONObject exported = f.service.getExport(attempt.export().getString("id"), f.owner);
        assertEquals("failed", task.getString("status"));
        assertEquals(expected, task.getJSONObject("verification_failure"));
        assertEquals("failed", exported.getString("status"));
        assertEquals(expected, exported.getJSONObject("verify"));
        assertFalse(exported.getJSONObject("verify").getBooleanValue("passed"));
        for (String table : List.of("audit", "event")) {
            for (JSONObject row : f.db.children(table, f.project)) {
                assertFalse(row.toJSONString().contains("第1页原生内容回读不符"), table);
                assertFalse(row.toJSONString().contains("verification_failure"), table);
            }
        }
        capture("verification-failure-center.json", JSONObject.of("task", task, "export", exported));
    }

    @Test void wrongTaskAndWrongFailureClassificationCannotAttachVerificationReport() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.service.quality(f.project, f.owner);
        JSONObject qualityLease = claim(f, "quality");
        assertThrows(AipptException.class, () -> f.leases().fail(qualityLease.getString("taskId"), failure(qualityLease, report())));
        assertEquals("running", f.db.get("task", qualityLease.getString("taskId")).getString("status"));
        JSONObject ordinary = request(qualityLease);
        ordinary.putAll(JSONObject.of("message", "普通质量任务失败", "retryable", false));
        assertEquals("failed", f.leases().fail(qualityLease.getString("taskId"), ordinary).getString("status"));
        assertFalse(f.db.get("task", qualityLease.getString("taskId")).containsKey("verification_failure"));

        ExportAttempt attempt = exportAttempt();
        for (Object retryable : new Object[]{true, "false", 0, null}) {
            JSONObject invalid = failure(attempt.lease(), report());
            invalid.put("retryable", retryable);
            assertThrows(AipptException.class, () -> attempt.fixture().leases().fail(attempt.lease().getString("taskId"), invalid));
        }
        JSONObject wrongCode = failure(attempt.lease(), report());
        wrongCode.put("code", "OTHER_FAILURE");
        assertThrows(AipptException.class, () -> attempt.fixture().leases().fail(attempt.lease().getString("taskId"), wrongCode));
        JSONObject nullReport = failure(attempt.lease(), null);
        assertThrows(AipptException.class, () -> attempt.fixture().leases().fail(attempt.lease().getString("taskId"), nullReport));
        assertEquals("running", attempt.fixture().db.get("task", attempt.lease().getString("taskId")).getString("status"));
    }

    @Test void strictReportValidationRollsBackUnknownTypesCountsAndPageLocations() throws Exception {
        ExportAttempt attempt = exportAttempt();
        List<Consumer<JSONObject>> invalid = new ArrayList<>();
        invalid.add(r -> r.put("unknown", null));
        invalid.add(r -> r.remove("diagnostics_truncated"));
        invalid.add(r -> r.put("passed", true));
        invalid.add(r -> r.put("passed", null));
        invalid.add(r -> r.put("issues", null));
        invalid.add(r -> r.put("issues", "invalid"));
        invalid.add(r -> { r.put("issues", new JSONArray()); r.put("total_issues", 0); });
        invalid.add(r -> r.put("issues", new JSONArray()));
        for (Object value : new Object[]{true, "1", 1.0, -1, 1001}) invalid.add(r -> r.put("slide_count", value));
        invalid.add(r -> r.put("expected_slide_count", 2));
        invalid.add(r -> r.put("expected_slide_count", 0));
        invalid.add(r -> r.put("diagnostics_truncated", "false"));
        invalid.add(r -> r.put("total_issues", 0));
        invalid.add(r -> r.put("total_issues", 2));
        invalid.add(r -> r.put("total_issues", 1_000_001));
        invalid.add(r -> r.getJSONArray("issues").getJSONObject(0).put("check", "unknown"));
        invalid.add(r -> r.getJSONArray("issues").getJSONObject(0).put("unknown", null));
        invalid.add(r -> r.getJSONArray("issues").getJSONObject(0).remove("shape"));
        for (Object value : new Object[]{0, 2, true, "1", 1.0}) invalid.add(r -> r.getJSONArray("issues").getJSONObject(0).put("slide_index", value));
        for (Object value : new Object[]{"", "字".repeat(1001), 1}) invalid.add(r -> r.getJSONArray("issues").getJSONObject(0).put("message", value));
        invalid.add(r -> r.getJSONArray("issues").getJSONObject(0).put("shape", "😀".repeat(201)));
        invalid.add(r -> r.getJSONArray("issues").getJSONObject(0).put("shape", 1));
        invalid.add(r -> {
            JSONArray issues = new JSONArray();
            for (int index = 0; index < 2001; index++) issues.add(AipptRules.copy(r.getJSONArray("issues").getJSONObject(0)));
            r.put("issues", issues);
            r.put("total_issues", 2001);
        });
        for (Consumer<JSONObject> mutation : invalid) {
            JSONObject report = report();
            mutation.accept(report);
            assertThrows(AipptException.class, () -> attempt.fixture().leases().fail(attempt.lease().getString("taskId"), failure(attempt.lease(), report)));
            JSONObject task = attempt.fixture().db.get("task", attempt.lease().getString("taskId"));
            assertEquals("running", task.getString("status"));
            assertFalse(task.containsKey("verification_failure"));
            assertEquals("queued", attempt.fixture().db.get("export", attempt.export().getString("id")).getString("status"));
        }
    }

    @Test void boundedTruncatedDiagnosticsCountUnicodeCodePointsAndAllowNullLocation() throws Exception {
        ExportAttempt attempt = exportAttempt();
        JSONObject report = report();
        JSONObject issue = report.getJSONArray("issues").getJSONObject(0);
        issue.put("slide_index", null);
        issue.put("shape", "😀".repeat(200));
        issue.put("message", "😀".repeat(1000));
        JSONArray issues = new JSONArray();
        issues.add(issue);
        for (int index = 1; index < 2000; index++) issues.add(JSONObject.of("check", "open", "slide_index", null, "shape", null, "message", "回读问题"));
        report.put("issues", issues);
        report.put("total_issues", 2001);
        report.put("diagnostics_truncated", true);
        assertEquals("failed", attempt.fixture().leases().fail(attempt.lease().getString("taskId"), failure(attempt.lease(), report)).getString("status"));
        JSONObject saved = attempt.fixture().service.task(attempt.lease().getString("taskId"), attempt.fixture().owner).getJSONObject("verification_failure");
        assertEquals(2000, saved.getJSONArray("issues").size());
        assertEquals(2001, saved.getIntValue("total_issues"));
        assertEquals(1000, saved.getJSONArray("issues").getJSONObject(0).getString("message").codePointCount(0, 2000));
    }

    @Test void oldFencingCannotChangeTaskOrExportWithAValidFailureReport() throws Exception {
        ExportAttempt attempt = exportAttempt();
        JSONObject stale = failure(attempt.lease(), report());
        stale.put("fencingToken", attempt.lease().getLongValue("fencingToken") + 1);
        AipptException error = assertThrows(AipptException.class, () -> attempt.fixture().leases().fail(attempt.lease().getString("taskId"), stale));
        assertEquals("LEASE_LOST", error.errorCode());
        assertEquals("running", attempt.fixture().db.get("task", attempt.lease().getString("taskId")).getString("status"));
        assertFalse(attempt.fixture().db.get("task", attempt.lease().getString("taskId")).containsKey("verification_failure"));
        assertEquals("queued", attempt.fixture().db.get("export", attempt.export().getString("id")).getString("status"));
    }

    private static void capture(String filename, JSONObject actual) throws Exception {
        String directory = System.getProperty("aippt.evidence.dir");
        if (directory == null || directory.isBlank()) return;
        Path target = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(target);
        Files.writeString(target.resolve(filename), actual.toJSONString(JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
    }
}
